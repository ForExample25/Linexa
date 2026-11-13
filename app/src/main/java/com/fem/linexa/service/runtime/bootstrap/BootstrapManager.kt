package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import android.system.Os
import com.fem.linexa.core.logging.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-device "bootstrap" layout for the bundled native binaries
 * (§1.26, revised §1.39, hardened §1.40, root-caused §1.42, symlink-architected §1.45,
 * loader-provisioned §1.47, manifest-validated §1.71).
 *
 * Binaries are packaged as `lib*.so` entries in `jniLibs/{abi}/` so AGP
 * extracts them into `nativeLibraryDir` at install time. On Android 10+
 * with targetSdk 29+, SELinux W^X enforcement blocks `execve()` on files
 * with the `app_data_file` label (filesDir), but allows it for files with
 * the `apk_data_file` label (nativeLibraryDir). This class therefore:
 *
 * 1. **Symlinks** every executable binary from `{filesDir}/bootstrap/bin/`
 *    through an `applib` symlink → `nativeLibraryDir`. The kernel follows
 *    the symlink chain and checks the target's SELinux label (`apk_data_file`),
 *    which allows `execve()`.
 * 2. **Repairs** any broken state on every `setup()` that has work to do:
 *    removes old real copies (pre-§1.45 layouts) and re-creates symlinks.
 *    §16.3.2 memoises the pass once it reports a fully clean layout — see
 *    [BootstrapManager.setup] and [BootstrapManager.forceSetup].
 * 3. **Validates** every binary is accessible (symlink resolving to a
 *    regular file, or a real file) before declaring ready.
 *
 * This matches the architecture of tiny_container (Cateners/tiny_container),
 * which proves this approach works with targetSdk 37.
 *
 * Layout:
 *   {filesDir}/applib → nativeLibraryDir (symlink, the core indirection)
 *   {filesDir}/bootstrap/bin/{busybox,tar,...} → applib/lib__bin__*.so (symlinks)
 *   {filesDir}/bootstrap/bin/{sed,sh,...} → busybox (argv[0] applet links —
 *     §1.57 explicit BusyboxAppletLayer, plus the legacy busybox --install)
 *   {filesDir}/bootstrap/lib/{soname} → applib/lib__lib__*.so (symlinks, linker-only)
 */
@Singleton
class BootstrapManager @Inject constructor(
    private val application: Application
) {
    private val nativeLibraryDir: String
        get() = application.applicationInfo.nativeLibraryDir

    /** `{filesDir}/bootstrap/bin` — symlinks to nativeLibraryDir via applib (§1.45). */
    val binDir: File get() = File(application.filesDir, "bootstrap/bin")

    /** `{filesDir}/bootstrap/lib` — soname symlinks through applib for LD_LIBRARY_PATH. */
    val libDir: File get() = File(application.filesDir, "bootstrap/lib")

    /** Scratch dir for PRoot's socket/tmp needs — per-invocation scope now (§1.48). */
    val prootTmpDir: File get() = File(application.cacheDir, "proot_tmp")

    /**
     * §1.75: `{filesDir}/terminfo` — the HOST-side ncurses database for the
     * bundled tmux binary. tmux runs on the Android host (host-side static
     * binary wrapping libproot.so, §1.20) where NO terminfo exists anywhere:
     * §1.68 forensics fingerprint `/usr/share/terminfo`, `/etc/terminfo` and
     * `/lib/terminfo` and all three are missing on a stock Android host —
     * and the guest rootfs's own terminfo is invisible to it, so an
     * unmapped TERM ("xterm-256color" from the launch env) made tmux die
     * with `can't find terminfo database`. Entries from `assets/terminfo/`
     * are extracted here by [installHostTerminfo] during [setup].
     *
     * Guest behavior is untouched: inside PRoot this path becomes a
     * dangling HOST path, so in-guest ncurses falls through (per
     * terminfo(5) search order: TERMINFO → TERMINFO_DIRS → defaults) to
     * the rootfs's own database exactly as before this change.
     */
    val terminfoDir: File get() = File(application.filesDir, TERMINFO_DIR_NAME)

    /**
     * §1.48: Create a fresh, per-invocation `PROOT_TMP_DIR` under the
     * shared parent [prootTmpDir]. The parent is *never* wiped here —
     * Linexa supports concurrent per-workspace sessions plus a one-shot
     * `exec()` path (Issue 2 of the 2026-08 engineering audit): a single
     * shared, unconditionally-wiped directory is unsafe because two live
     * PRoot invocations could delete each other's scratch state mid-run.
     * Each caller gets its own subdirectory keyed by [scope] (workspace id
     * for `openTerminal` (workspace id + terminal id), UUID for `exec`/extraction) and must call
     * [releaseProotTmpDir] once the owning session/process has exited.
     */
    fun scopedProotTmpDir(scope: String): File {
        val parent = prootTmpDir
        parent.mkdirs()
        val dir = File(parent, scope)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /**
     * §1.48: Delete a scoped `PROOT_TMP_DIR` created by [scopedProotTmpDir].
     * Only call once the owning PtySession has been destroyed or the
     * ProcessBuilder-backed process has exited — never while it may still
     * be running. Idempotent; a missing dir is a no-op.
     */
    fun releaseProotTmpDir(scope: String) {
        val dir = File(prootTmpDir, scope)
        if (dir.exists()) dir.deleteRecursively()
    }

    /**
     * §16.3.2 — whether the full setup has run to completion **and reported
     * zero issues** on this instance, in this process.
     *
     * "Converged" is deliberately stricter than [isReady]. [isReady] is the
     * launch gate and covers binary *access* only; a run that healed every
     * symlink but could not write a terminfo entry, or whose manifest hash did
     * not match, leaves `isReady == true` and a non-null [lastError]. Those are
     * exactly the states whose repair work must keep running, so the memo is
     * published only when `lastError == null` — i.e. when validation, the
     * manifest check and the terminfo install all came back clean and there is
     * provably nothing left for another pass to fix.
     *
     * Written only under [setupLock], read without it on the fast path: the
     * volatile write happens after every filesystem mutation in [runSetup], so
     * a thread that reads `true` also sees the completed layout.
     */
    @Volatile
    private var setupConverged: Boolean = false

    /**
     * §16.3.2 — serializes [runSetup].
     *
     * Concurrent entry is not hypothetical. `PRootContainerRuntime` reaches
     * `setup()` from three places that hold *different* locks or none at all —
     * `openTerminal` (under its `sessionsLock`), `startDaemon` (under
     * `daemonsLock`) and `execInternal` (unsynchronized) — all on
     * `Dispatchers.IO`. Starting the desktop while a package-manager `exec` runs
     * therefore already put two threads through the symlink cleanup, the
     * `busybox --install` subprocess and the terminfo writes at the same time.
     * Serializing here removes that race as a side effect of the memo.
     *
     * A plain monitor rather than a coroutine `Mutex` because [setup] is a
     * blocking function called from non-coroutine code too
     * (`ManagementTerminalViewModel.start`); making it `suspend` would change
     * every caller's signature for no gain, and the callers that are coroutines
     * already wrap it in `withContext(Dispatchers.IO)`.
     */
    private val setupLock = Any()

    /**
     * §16.3.2 — read-only view of the memo, for [diagnoseAll] and tests.
     * Never a substitute for [isReady], which stays a live filesystem query.
     */
    internal val isSetupMemoized: Boolean get() = setupConverged

    /**
     * §16.3.2 — how many times the full pass has actually executed in this
     * process, for [diagnoseAll].
     *
     * The number the memo exists to hold down, and the one thing a
     * stale-bootstrap report cannot reconstruct from the layout itself: "1" says
     * the memo is doing its job, a count tracking the number of launches says it
     * is being retracted every time and something outside this class keeps
     * breaking the layout. Incremented under [setupLock], so no atomic is
     * needed.
     */
    @Volatile
    internal var setupRunCount: Int = 0
        private set

    /**
     * (Re)create the bootstrap layout, unless a previous call in this process
     * already converged (§16.3.2).
     *
     * The work itself is unchanged — see [runSetup] for the steps. What changed
     * is how often it runs: every guest launch used to repeat the whole pass,
     * because `PRootContainerRuntime.ensureBootstrap` is called by
     * `openTerminal`, `execInternal` *and* `startDaemon`. A cold Desktop start
     * alone reaches it several times before Xvnc is asked to start (audit
     * §10.1/§10.2), and each pass re-scans two directories, re-stats every
     * managed binary, re-hashes every bundled artifact against the manifest,
     * re-writes four terminfo entries and spawns `busybox --install`.
     *
     * The first pass that reports **no issues at all** publishes
     * [setupConverged] and later calls return after a 6-`lstat` integrity guard
     * ([canSkipSetup]). Anything less than a clean pass — the two hard early
     * returns, a validation error, a manifest mismatch, a terminfo gap — leaves
     * the memo unset, so the next call runs the full self-heal exactly as
     * before. Failure is never cached as success.
     *
     * A caller that has *itself* detected a broken layout should use
     * [forceSetup]. The guard here only covers [BIN_ALIASES]; a caller that
     * found something wrong beyond those six binaries — or that wants the
     * manifest re-hashed and the terminfo rewritten — needs the unconditional
     * pass, and the three `!isReady` sites use it.
     */
    fun setup() {
        if (canSkipSetup()) return
        synchronized(setupLock) {
            // Re-check under the lock: a racing caller may have converged while
            // this thread waited, in which case its layout is already published
            // and re-verified by the same guard.
            if (canSkipSetup()) return
            runSetup()
        }
    }

    /**
     * §16.3.2 — the fast-path decision: memoised **and** the managed binaries
     * still validate.
     *
     * The second half is a deliberate, bounded exception to "do not re-check the
     * filesystem on the fast path", and it is the reason this memo is safe at
     * all. `setup()` is not an initializer — it is the self-heal, reached from
     * every launch precisely so that a layout broken *after* the first launch
     * gets repaired. A memo with no integrity guard would mean a symlink lost to
     * an app update, a cleaner, or a partially-failed write is never rebuilt for
     * the life of the process, and the failure mode is `execve` returning ENOENT
     * inside PRoot — the exact class of bug §1.45/§1.57 exist to prevent.
     *
     * What makes it acceptable is the asymmetry of cost. The guard is
     * [BIN_ALIASES]`.size` = 6 `lstat`/`readlink` pairs, no subprocess, no reads,
     * no hashing, no writes. The pass it replaces scans two directories, stats 25
     * applet links and 14 library links, spawns `busybox --install` and waits on
     * it, sha256-hashes every bundled artifact against the manifest, and rewrites
     * four terminfo files. It is also not new work in kind: it is exactly the
     * predicate [isReady] computes, which the install path already evaluates
     * twice per import.
     *
     * The guard covers the binaries whose absence actually breaks a launch. It
     * does *not* re-verify applet links, library symlinks, manifest hashes or
     * terminfo — recovering those cheaply is what [forceSetup] and the
     * `!isReady` call sites are for, and paying for them here would rebuild most
     * of the cost the memo removes.
     */
    private fun canSkipSetup(): Boolean {
        if (!setupConverged) return false
        if (binariesValidate()) {
            Logger.d(TAG, "=== SETUP: skipped (§16.3.2 — converged and binaries still validate) ===")
            return true
        }
        // Retract rather than skip: something outside this class changed the
        // layout after a clean pass, so the full self-heal must run again.
        Logger.w(
            TAG,
            "§16.3.2 memo retracted — a managed binary no longer validates; re-running the full setup"
        )
        setupConverged = false
        return false
    }

    /**
     * §16.3.2 — run the full setup unconditionally, dropping any memo.
     *
     * For callers that have already established the layout is not usable, which
     * in this codebase means the three sites that stat the binaries themselves
     * and find `isReady == false`: `ManagementTerminalViewModel.start` and
     * `StagedRootFsInstaller.analyze`/`install`. Their `if (!isReady)` guard is
     * a corruption *detection*, so answering them from the memo would turn the
     * repair they are asking for into a no-op — the one way memoisation could
     * have broken self-heal.
     *
     * The memo is cleared *before* the run, not just overwritten after it, so a
     * concurrent fast-path reader cannot be told the layout is good while it is
     * being rebuilt.
     */
    fun forceSetup() {
        synchronized(setupLock) {
            if (setupConverged) {
                Logger.i(TAG, "=== SETUP: forced — dropping the §16.3.2 memo and re-running ===")
            }
            setupConverged = false
            runSetup()
        }
    }

    /**
     * The bootstrap layout pass itself. Idempotent. Steps:
     *
     * 1. Create `applib` symlink → nativeLibraryDir.
     * 2. Create binDir symlinks → applib/lib__bin__*.so (removes old real copies).
     * 3. Create argv[0] applet symlinks via busybox --install (non-fatal).
     * 3b. Explicit applet compatibility layer (§1.57) — guarantees sed/
     *     grep/awk/sh/cut/rm/mkdir/cp/mv/chmod/chown/uname/env/printf/echo/
     *     cat/head/tail/tr/id applet symlinks exist regardless of step 3.
     * 4. Create libDir symlinks → applib/lib__lib__*.so.
     * 5. Validate every managed binary is accessible.
     * 6. Validate the bundled bootstrap manifest (§1.71, loud but non-gating).
     * 7. Install the host-side terminfo DB for tmux (§1.75, loud but non-gating).
     *
     * §16.3.2: always called under [setupLock], and publishes [setupConverged]
     * as its last act — from one place, after every mutation, so no early
     * return can reach it.
     */
    private fun runSetup() {
        setupRunCount++
        Logger.i(TAG, "=== SETUP: starting (§1.45 symlink architecture) ===")
        Logger.i(TAG, "  nativeLibraryDir = $nativeLibraryDir")
        Logger.i(TAG, "  binDir = ${binDir.absolutePath}")
        Logger.i(TAG, "  libDir = ${libDir.absolutePath}")

        val nativeLibDirFile = File(nativeLibraryDir)
        if (!nativeLibDirFile.exists() || (nativeLibDirFile.listFiles()?.size ?: 0) == 0) {
            Logger.e(TAG, "SETUP: nativeLibraryDir empty or missing, aborting")
            lastError = "nativeLibraryDir empty or missing"
            return
        }

        binDir.mkdirs()
        libDir.mkdirs()
        prootTmpDir.mkdirs()

        // Step 1: applib symlink → nativeLibraryDir (tiny_container architecture).
        // The kernel follows symlinks for execve and checks the TARGET's SELinux
        // label (apk_data_file) which allows execve on Android 10+.
        Logger.i(TAG, "=== SETUP: applib symlink ===")
        val applibSymlink = File(application.filesDir, "applib")
        // Pre-release audit fix (2026-09-02, finding RT-10a): the old code
        // replaced an existing symlink but SKIPPED creation when a regular
        // file (or dangling link with exists()==true semantics) occupied the
        // name — such a leftover was unrepairable for the life of the install
        // ("applib already exists") and every launch failed at execve.
        // Anything that is not the correct symlink is now replaced.
        val expectedApplibTarget = Paths.get(nativeLibraryDir)
        val applibIsCorrect = runCatching {
            Files.isSymbolicLink(applibSymlink.toPath()) &&
                Files.readSymbolicLink(applibSymlink.toPath()) == expectedApplibTarget
        }.getOrDefault(false)
        if (!applibIsCorrect) {
            if (applibSymlink.exists() || Files.isSymbolicLink(applibSymlink.toPath())) {
                applibSymlink.delete()
            }
            try {
                Files.createSymbolicLink(applibSymlink.toPath(), expectedApplibTarget)
                Logger.i(TAG, "SETUP: created applib → $nativeLibraryDir")
            } catch (e: Exception) {
                Logger.e(TAG, "SETUP: failed to create applib symlink", e)
                lastError = "failed to create applib symlink: ${e.message}"
                return
            }
        } else {
            Logger.i(TAG, "SETUP: applib already exists (target verified)")
        }

        // Step 2: bootstrap/bin/ symlinks → applib/lib__bin__*.so.
        // Removes any old real copies from pre-§1.45 layouts.
        Logger.i(TAG, "=== SETUP: binDir symlinks ===")
        for (child in binDir.listFiles() ?: emptyArray<File>()) {
            if (child.isFile && !Files.isSymbolicLink(child.toPath())) {
                child.delete()
                Logger.i(TAG, "SETUP: removed old real copy ${child.name}")
            }
        }
        BIN_ALIASES.forEach { (alias, jniName) ->
            val symlink = File(binDir, alias)
            // Pre-release audit fix (2026-09-02, finding RT-10b): repair by
            // TARGET — the old skip-if-exists left a dangling or
            // wrong-target link in place forever (every launch then logged a
            // validation error and failed at execve). The BusyboxAppletLayer
            // already used this replace-unless-correct semantics; the managed
            // aliases now match it. The expected target string is exactly the
            // one the original creation used, so links written by earlier
            // builds still compare equal and are left untouched.
            val expectedTarget = Paths.get(applibSymlink.absolutePath, jniName)
            val isCorrect = runCatching {
                Files.isSymbolicLink(symlink.toPath()) &&
                    Files.readSymbolicLink(symlink.toPath()) == expectedTarget
            }.getOrDefault(false)
            if (isCorrect) return@forEach
            if (existsNoFollow(symlink)) {
                symlink.delete()
                Logger.i(TAG, "SETUP: replaced stale/wrong-target $alias link")
            }
            try {
                Files.createSymbolicLink(symlink.toPath(), expectedTarget)
                Logger.i(TAG, "SETUP: symlink $alias → applib/$jniName")
            } catch (e: Exception) {
                Logger.e(TAG, "SETUP: failed to create symlink for $alias", e)
            }
        }

        // Step 3: busybox applet symlinks (must run AFTER busybox symlink exists).
        Logger.i(TAG, "=== SETUP: installBusyboxApplets ===")
        installBusyboxApplets()

        // Step 3b (§1.57): explicit BusyBox applet compatibility layer.
        // `busybox --install -s` above is non-fatal by design and can
        // silently produce no applets (observed on-device: binDir/sed
        // missing → "Cannot run program .../bootstrap/bin/sed: error=2 No
        // such file or directory" in the fixup stage). The layer creates
        // the REQUIRED applets directly and repairs stale entries, so the
        // fixup stage never depends on the --install run succeeding.
        Logger.i(TAG, "=== SETUP: BusyboxAppletLayer (§1.57) ===")
        val appletResult = BusyboxAppletLayer.installAppletLinks(binDir)
        Logger.i(TAG, "applet layer ${appletResult.summary}")
        appletResult.failed.forEach { (name, e) -> Logger.e(TAG, "applet link FAILED for $name", e) }

        // Step 4: bootstrap/lib/ symlinks → applib/lib__lib__*.so.
        Logger.i(TAG, "=== SETUP: libDir symlinks ===")
        // Pre-release audit fix (2026-09-02, finding RT-11): the old cleanup
        // deleted only children where isFile()==true — which RESOLVING
        // symlinks satisfy (they re-create fine) but DANGLING links do not.
        // A stale dangling soname link survived cleanup AND the skip-if-
        // exists check, permanently poisoning LD_LIBRARY_PATH resolution
        // after a jniLibs set change. Everything that is not a correct
        // symlink is now replaced below (same repair-by-target semantics as
        // the bin aliases).
        LIB_SONAMES.forEach { soname ->
            val symlink = File(libDir, soname)
            val expectedTarget = Paths.get(applibSymlink.absolutePath, "lib__lib__${soname}__.so")
            val isCorrect = runCatching {
                Files.isSymbolicLink(symlink.toPath()) &&
                    Files.readSymbolicLink(symlink.toPath()) == expectedTarget
            }.getOrDefault(false)
            if (isCorrect) return@forEach
            if (existsNoFollow(symlink)) {
                symlink.delete()
            }
            try {
                Files.createSymbolicLink(symlink.toPath(), expectedTarget)
                Logger.i(TAG, "SETUP: symlink lib/$soname → applib/lib__lib__${soname}__.so")
            } catch (e: Exception) {
                Logger.e(TAG, "SETUP: failed to create lib symlink for $soname", e)
            }
        }

        // Step 5: validate all managed binaries are accessible.
        Logger.i(TAG, "=== SETUP: validate ===")
        val validationErrors = mutableListOf<String>()
        BIN_ALIASES.forEach { (alias, _) ->
            val issue = validateBinary(alias)
            if (issue != null) {
                validationErrors.add(issue)
                Logger.e(TAG, "SETUP: validation FAILED for $alias: $issue")
            }
        }

        // Step 6 (§1.71, Roadmap Item 5): validate the live jniLibs layout
        // against the bundled versioned manifest. This runs AFTER the symlink
        // self-heal (steps 1-4) and is an ADDITIONAL layered check, not a
        // replacement for it — the self-heal behavior is untouched. A manifest
        // mismatch is loud (logcat + lastError, surfaced through the existing
        // diagnoseAll() diagnostics path) but does NOT flip isReady: binary
        // ACCESS validation (steps 1-5) remains the launch/import gate.
        Logger.i(TAG, "=== SETUP: bootstrap manifest validation (§1.71) ===")
        val manifestIssues = validateBootstrapManifest()
        if (manifestIssues.isNotEmpty()) {
            Logger.e(TAG, "SETUP: bootstrap manifest validation FAILED (${manifestIssues.size} issue(s))")
            manifestIssues.forEach { Logger.e(TAG, "  manifest: $it") }
        }

        // Step 7 (§1.75): host terminfo for the bundled tmux. The app's tmux
        // runs on the Android HOST where no terminfo exists (no
        // /usr/share/terminfo, /etc/terminfo or /lib/terminfo — fingerprint
        // verified in §1.68 forensics) and the guest rootfs's terminfo is
        // invisible to it, so TERM=xterm-256color made tmux exit with
        // "can't find terminfo database" before ever spawn proot. Vendored
        // entries (assets/terminfo) are extracted to filesDir/terminfo and
        // exposed via TERMINFO in processEnv()/prootEnv(). This is a real
        // HOST fix — the guest's -w /root argv, HOME and its own terminfo
        // are byte-untouched. Also loud-but-non-gating, like §1.71.
        Logger.i(TAG, "=== SETUP: host terminfo install (§1.75) ===")
        val terminfoIssues = installHostTerminfo()
        if (terminfoIssues.isNotEmpty()) {
            Logger.e(TAG, "SETUP: terminfo install FAILED (${terminfoIssues.size} issue(s))")
            terminfoIssues.forEach { Logger.e(TAG, "  terminfo: $it") }
        }

        lastError = when {
            validationErrors.isNotEmpty() || manifestIssues.isNotEmpty() || terminfoIssues.isNotEmpty() ->
                (validationErrors + manifestIssues + terminfoIssues).joinToString("\n")
            else -> null
        }
        // §16.3.2: publish the memo only for a pass that found nothing to
        // report. `lastError == null` is the strongest statement this function
        // makes about its own outcome — it means step 5's access validation,
        // step 6's manifest hashes and step 7's terminfo install all came back
        // clean, so a further pass has provably nothing to repair. Any issue at
        // all leaves the memo unset and the next caller redoes the full
        // self-heal, which is the pre-§16.3.2 behaviour.
        //
        // This is the single publish point, placed after every filesystem
        // mutation above, so neither early return nor an exception can reach it.
        setupConverged = lastError == null
        Logger.i(
            TAG,
            "=== SETUP: complete === isReady=$isReady errors=${validationErrors.size} manifestIssues=${manifestIssues.size} terminfoIssues=${terminfoIssues.size} memoized=$setupConverged"
        )
    }



    /**
     * §1.40/§1.42: Full diagnostic string for a bootstrap binary. Shows the
     * source (nativeLibraryDir), destination (binDir), real file type via
     * `Files.isSymbolicLink`/`Files.isRegularFile` (NOT the invalid
     * `canonicalPath != absolutePath` heuristic — §1.42), stat() mode,
     * canExecute()/canRead(), and ELF header.
     */
    fun diagnoseBinary(name: String): String {
        val dest = File(binDir, name)
        val jniName = BIN_ALIASES.find { it.first == name }?.second
        val sourceJni = File(nativeLibraryDir, jniName ?: "$name.so")

        val fileType = when {
            isSymlink(dest) -> "SYMLINK → ${readSymlink(dest)}"
            isRealRegularFile(dest) -> "REGULAR FILE"
            dest.exists() -> "OTHER (exists, not a real regular file)"
            else -> "MISSING"
        }

        val statInfo = try {
            val stat = Os.stat(dest.absolutePath)
            "mode=0${Integer.toOctalString(stat.st_mode)} uid=${stat.st_uid} gid=${stat.st_gid} dev=${stat.st_dev} ino=${stat.st_ino}"
        } catch (e: Exception) {
            "stat() failed: ${e.message}"
        }

        val size = dest.length()
        val executable = try { dest.canExecute() } catch (_: Exception) { false }

        val elfInfo = if (isRealRegularFile(dest) && size >= 20) {
            try { elfClass(dest) } catch (e: Exception) { "ELF read error: ${e.message}" }
        } else if (size < 20) {
            "too small for ELF header ($size bytes)"
        } else {
            "cannot read (not a regular file)"
        }

        return buildString {
            append("$name: src=${sourceJni.absolutePath} exists=${sourceJni.exists()}")
            append(" → dest=${dest.absolutePath}")
            append(" type=$fileType size=$size canonical=${try { dest.canonicalPath } catch (_: Exception) { "?" }}")
            append(" $statInfo")
            append(" canExec=${try { dest.canExecute() } catch (_: Exception) { false }} canRead=${try { dest.canRead() } catch (_: Exception) { false }}")
            append(" $elfInfo")
        }
    }

    /**
     * Run `busybox --install -s <bindir>` to auto-create argv[0] symlinks
     * for every compiled applet (ls, cp, mv, grep, ps, sed, chmod, id, ...).
     * Applet symlinks within binDir are fine — the kernel follows to the
     * real busybox copy. Runs after [setup]'s managed copies so existing
     * real files (busybox, tar, proot, zstd, tmux) are never overwritten.
     */
    private fun installBusyboxApplets() {
        val busyboxBin = File(binDir, "busybox")
        // Follow symlink chain to check real executability — canExecute()
        // may return false for symlinks on some Android versions.
        val isExecutable = try {
            busyboxBin.canExecute() || busyboxBin.toPath().toRealPath().toFile().canExecute()
        } catch (_: Exception) { false }
        if (!isExecutable) {
            Logger.w(TAG, "busybox binary missing or not executable, skipping applet install")
            return
        }
        try {
            val pb = ProcessBuilder(busyboxBin.absolutePath, "--install", "-s", binDir.absolutePath)
            val env = processEnv()
            pb.environment()["PATH"] = env["PATH"]
            pb.environment()["LD_LIBRARY_PATH"] = env["LD_LIBRARY_PATH"]
            // §1.48: PROOT_TMP_DIR is deliberately NOT set here — busybox
            // doesn't need it, and processEnv() no longer carries it (it's
            // per-invocation scoped; see prootEnv()).
            pb.redirectErrorStream(true)
            val proc = pb.start()
            // §1.68: drain stdout on a background thread while waitFor
            // enforces the timeout (the same bounded pattern
            // PRootContainerRuntime.runProcess uses). The previous code
            // called readText() BEFORE waitFor, so a wedged busybox that
            // never closed its stdout blocked the caller forever — the 30s
            // timeout was dead code, and because setup() runs at the top of
            // openTerminal (on the RuntimeService Main dispatcher), that
            // hang pinned the workspace state at Starting and left the
            // Terminal stuck on the Starting placeholder indefinitely.
            val reader = Executors.newSingleThreadExecutor()
            try {
                val outputFuture = reader.submit<String> {
                    try {
                        proc.inputStream.bufferedReader().use { it.readText().trim() }
                    } catch (_: Exception) {
                        ""
                    }
                }
                val completed = proc.waitFor(30, TimeUnit.SECONDS)
                if (!completed) {
                    proc.destroyForcibly()
                    val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                    Logger.w(TAG, "busybox --install timed out: $output")
                } else {
                    val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                    val exitCode = proc.exitValue()
                    if (exitCode != 0) {
                        Logger.w(TAG, "busybox --install exited $exitCode: $output")
                    } else {
                        val count = binDir.listFiles()?.size ?: 0
                        Logger.i(TAG, "busybox --install -s done: $count entries in bin/")
                    }
                }
            } finally {
                reader.shutdownNow()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "busybox --install failed (non-fatal, managed copies still in place)", e)
        }
    }

    /**
     * §1.42/§1.45: The executable path for a bootstrap binary. Returns the
     * entry in binDir — typically a symlink to nativeLibraryDir via applib.
     * The kernel follows the symlink chain for execve, checking the target's
     * SELinux label (apk_data_file) which allows execution on Android 10+.
     */
    fun resolveBinary(name: String): File = File(binDir, name)

    /**
     * §1.42/§1.45: Validate that a managed binary in binDir is accessible.
     * Symlinks are valid — the target must resolve to a regular file.
     * Real files must be executable. Returns null if valid, or a descriptive
     * error string.
     */
    fun validateBinary(name: String): String? {
        val file = File(binDir, name)
        if (!existsNoFollow(file)) return "$name: does not exist at ${file.absolutePath}"

        if (isSymlink(file)) {
            // §1.45: Symlinks are valid — validate the target exists and is a regular file.
            return try {
                val target = file.toPath().toRealPath()
                if (!Files.isRegularFile(target)) "$name: symlink target is not a regular file: $target"
                else null
            } catch (e: Exception) {
                "$name: symlink resolution failed: ${e.message}"
            }
        }

        if (!isRealRegularFile(file)) return "$name: is not a real regular file at ${file.absolutePath}"
        if (!file.canExecute()) return "$name: is not executable at ${file.absolutePath}"
        return null
    }

    /**
     * True when all managed binaries are validated real, executable files.
     *
     * Always a **live** filesystem query — §16.3.2's memo deliberately does not
     * short-circuit it, and this getter deliberately has no side effects: it is
     * the launch/import gate, and the one thing every caller uses to ask about
     * the layout as it is *now*.
     */
    val isReady: Boolean
        get() = binariesValidate()

    /**
     * §16.3.2 — the memo's integrity precondition: the same binary-access
     * predicate [isReady] exposes, factored out so [setup]'s fast path can
     * consult it without depending on a public getter.
     */
    private fun binariesValidate(): Boolean =
        BIN_ALIASES.all { (alias, _) -> validateBinary(alias) == null }

    /**
     * §1.75: JVM-test seam — supplies vendored terminfo entry bytes instead
     * of reading the bundled assets, because `Application.getAssets()` is
     * final and cannot be stubbed with plain Mockito (same rationale as
     * [BootstrapManifestTestSeam]). Test-only; production never writes it.
     */
    internal object TerminfoTestSeam {
        var overrideBytes: Map<String, ByteArray>? = null
    }

    /**
     * §1.75: extract the vendored host terminfo entries from
     * `assets/terminfo/` into [terminfoDir], validating each entry's ncurses
     * magic before trusting it. Runs on every [setup] (idempotent, cheap —
     * a few KB), so a stale/corrupt installed entry self-heals the same way
     * the binary symlink layer does. Returns the list of issues (empty =
     * clean); failures are logged and merged into `lastError` but never flip
     * [isReady] — binary ACCESS validation remains the launch gate, and the
     * launch path simply omits `TERMINFO` (ncurses falls back) rather than
     * carrying a broken value.
     */
    internal fun installHostTerminfo(): List<String> {
        val issues = mutableListOf<String>()
        terminfoDir.mkdirs()
        TERMINFO_ENTRIES.forEach { entry ->
            val target = File(terminfoDir, entry)
            try {
                val bytes = TerminfoTestSeam.overrideBytes?.get(entry)
                    ?: application.assets.open("$TERMINFO_ASSET_DIR/$entry").use { it.readBytes() }
                if (bytes.isEmpty()) {
                    issues += "$entry: empty terminfo entry"
                    return@forEach
                }
                if (!isTerminfoMagic(bytes)) {
                    issues += "$entry: not a valid ncurses entry (bad magic, ${bytes.size} bytes)"
                    return@forEach
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            } catch (e: Exception) {
                issues += "$entry: ${e::class.simpleName}: ${e.message}"
            }
        }
        return issues
    }

    /**
     * §1.75: whether the host terminfo install produced a usable database.
     * The launch path sets `TERMINFO` (via [processEnv]) only when the
     * entry the launch env actually uses — `xterm-256color`, the TERM set
     * by [com.fem.linexa.service.runtime.container.PRootLauncher.buildLaunchSpec]
     * — is installed; otherwise it fails open (no TERMINFO, ncurses default
     * lookup, forensics shows the gap).
     */
    fun terminfoKeyEntryUsable(): Boolean =
        File(terminfoDir, TERMINFO_KEY_ENTRY).isFile

    /**
     * §1.71: Load the bundled versioned bootstrap manifest as text. Prefers
     * the bundled asset; the JVM test seam ([BootstrapManifestTestSeam])
     * overrides it in unit tests (Application.getAssets() is final and cannot
     * be stubbed with plain Mockito). Null when unavailable — manifest
     * validation is then skipped (layered check, not a hard requirement).
     */
    internal fun loadBootstrapManifestJson(): String? {
        BootstrapManifestTestSeam.overrideJson?.let { json ->
            Logger.d(TAG, "bootstrap manifest: using test override (${json.length} bytes)")
            return json
        }
        return try {
            application.assets.open(BootstrapManifestSupport.ASSET_PATH)
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Logger.d(TAG, "bootstrap manifest: asset missing/unreadable — validation skipped")
            null
        }
    }

    /**
     * §1.71: Compare the live `nativeLibraryDir` layout against the bundled
     * manifest. Returns a list of issues (empty = clean). Rules:
     *
     * - Every managed file present on disk must match the recorded sha256 of
     *   SOME manifest entry for that file name. The installed ABI is the one
     *   whose entry matches the on-disk hash, so the other ABI's entry is
     *   never a false positive (a correct install always matches its own ABI
     *   entry). A hash matching no entry = corrupted file or stale manifest.
     * - A managed file absent from disk entirely is a loud issue.
     * - Paired artifacts (e.g. loader must come from the same build as proot)
     *   must resolve to entries with the same `buildId`.
     *
     * This never throws: a broken manifest becomes a loud validation failure,
     * not a crash. Not a replacement for the symlink self-heal — it runs after
     * it and only reports.
     */
    private fun validateBootstrapManifest(): List<String> {
        val json = loadBootstrapManifestJson() ?: return emptyList()
        val manifest = BootstrapManifestSupport.parse(json)
        if (manifest == null) {
            return listOf(
                "bootstrap-manifest.json is present but unparseable — bundled binaries cannot be verified"
            )
        }
        if (manifest.schemaVersion != BootstrapManifestSupport.SCHEMA_VERSION) {
            return listOf(
                "bootstrap-manifest.json schemaVersion=${manifest.schemaVersion} unsupported " +
                    "(expected ${BootstrapManifestSupport.SCHEMA_VERSION})"
            )
        }

        val issues = mutableListOf<String>()
        // file -> manifest entry whose recorded hash matched the on-disk file
        // (captures the installed ABI + its buildId for the pair check).
        val matchedByFile = mutableMapOf<String, BootstrapManifest.Artifact>()

        for ((file, variants) in manifest.artifacts.groupBy { it.file }) {
            val onDisk = File(nativeLibraryDir, file)
            if (!isRealRegularFile(onDisk)) {
                issues.add("$file: missing from nativeLibraryDir — no installed copy of this bundled binary")
                continue
            }
            val actual = BootstrapManifestSupport.sha256Hex(onDisk)
            val match = variants.firstOrNull { it.sha256 == actual }
            if (match == null) {
                val expected = variants.joinToString("; ") { "${it.abi}=${it.sha256}" }
                issues.add(
                    "$file: sha256 mismatch — on-disk ${actual ?: "<hash failed>"} matches no manifest entry " +
                        "(expected $expected; recorded versions: ${variants.joinToString(", ") { it.version }})"
                )
            } else {
                matchedByFile[file] = match
            }
        }

        for (pair in manifest.pairs) {
            val resolved = pair.members.mapNotNull { matchedByFile[it] }
            if (resolved.size != pair.members.size) continue // missing/mismatched member already reported
            val builds = resolved.map { it.buildId }.distinct()
            if (builds.size > 1) {
                issues.add(
                    "paired artifacts ${pair.members.joinToString(", ")} must come from the same build — " +
                        resolved.joinToString("; ") { "${it.file}=${it.buildId} (${it.abi})" } + " (${pair.label})"
                )
            }
        }
        return issues
    }

    /** Set when the last [setup] failed to converge on real copies; null when healthy. */
    var lastError: String? = null
        private set

    /**
     * §1.40/§1.42: Run full diagnostics for all managed binaries and dump
     * the complete bootstrap state. Used by the Copy Error UI report.
     */
    fun diagnoseAll(): String {
        return buildString {
            appendLine("=== Bootstrap Diagnostics ===")
            appendLine("nativeLibraryDir: $nativeLibraryDir")
            appendLine("binDir: ${binDir.absolutePath} exists=${binDir.exists()}")
            appendLine("libDir: ${libDir.absolutePath} exists=${libDir.exists()}")
            appendLine("isReady: $isReady")
            // §16.3.2: whether setup() is being answered from the memo. Without
            // this line a diagnostics dump cannot distinguish "the layout is
            // good" from "the layout is good and we stopped re-checking it",
            // which is exactly the question a stale-bootstrap report raises.
            appendLine("setupMemoized (§16.3.2): $setupConverged")
            appendLine("setupRuns (§16.3.2): $setupRunCount")
            if (lastError != null) appendLine("lastError: $lastError")
            appendLine("binDir files:")
            binDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                appendLine("  ${f.name}: ${when {
                    isSymlink(f) -> "SYMLINK → ${readSymlink(f)}"
                    isRealRegularFile(f) -> "REGULAR FILE (${f.length()}B) exec=${f.canExecute()}"
                    else -> "other"
                }}")
            }
            appendLine("libDir files:")
            libDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                appendLine("  ${f.name}: ${if (isSymlink(f)) "SYMLINK → ${readSymlink(f)}" else if (f.isFile) "file" else "other"}")
            }
            appendLine()
            BIN_ALIASES.forEach { (alias, _) ->
                appendLine(diagnoseBinary(alias))
            }
            appendLine()
            appendLine("Validations:")
            BIN_ALIASES.forEach { (alias, _) ->
                val issue = validateBinary(alias)
                appendLine("  $alias: ${issue ?: "OK"}")
            }
            appendLine()
            appendLine(diagnoseInterpreter())
            appendLine(diagnoseLibResolution())
            appendLine(diagnoseProcessEnv())
            appendLine(diagnoseLoader())
            appendLine(diagnoseApplets())
            appendLine(diagnoseManifest())
        }
    }

    /**
     * §1.71: Diagnostics for the bundled versioned manifest — availability,
     * parseability, per-artifact hash status against the live layout, and the
     * paired-artifact (buildId) rules. Surfaced through the EXISTING
     * [diagnoseAll] diagnostics path (Item 5 explicitly did not add a new one).
     */
    fun diagnoseManifest(): String {
        val json = loadBootstrapManifestJson()
        if (json == null) {
            return "=== Bootstrap manifest (§1.71) ===\n  <unavailable (asset missing) — validation skipped>"
        }
        return buildString {
            appendLine("=== Bootstrap manifest (§1.71) ===")
            val manifest = BootstrapManifestSupport.parse(json)
            if (manifest == null) {
                appendLine("  <present but unparseable>")
                return@buildString
            }
            appendLine("  schemaVersion: ${manifest.schemaVersion}")
            appendLine("  artifacts: ${manifest.artifacts.size}, pairs: ${manifest.pairs.size}")
            for ((file, variants) in manifest.artifacts.groupBy { it.file }) {
                val onDisk = File(nativeLibraryDir, file)
                val state = when {
                    !isRealRegularFile(onDisk) -> "MISSING"
                    else -> {
                        val actual = BootstrapManifestSupport.sha256Hex(onDisk)
                        val match = variants.firstOrNull { it.sha256 == actual }
                        if (match == null) "MISMATCH (on-disk ${actual ?: "<hash failed>"})"
                        else "OK ${match.abi} buildId=${match.buildId}"
                    }
                }
                appendLine("  $file: ${variants.joinToString(" | ") { "${it.abi} ${it.version}" }} -> $state")
            }
            manifest.pairs.forEach { pair ->
                appendLine("  pair: ${pair.label} [${pair.members.joinToString(", ")}]")
            }
        }
    }

    /**
     * §1.57: Diagnostics for the BusyBox applet compatibility layer —
     * per-required-applet symlink state ([BusyboxAppletLayer.appletDiagnostics])
     * plus a best-effort membership check against the applets actually
     * compiled into the busybox build (`busybox --list`).
     */
    fun diagnoseApplets(): String {
        return buildString {
            appendLine("=== Busybox Applet Layer (§1.57) ===")
            appendLine(BusyboxAppletLayer.appletDiagnostics(binDir))
            val compiled = compiledAppletList()
            if (compiled != null) {
                val missing = BusyboxAppletLayer.REQUIRED_APPLETS.filter { it !in compiled }
                appendLine("  busybox --list: ${compiled.size} applets compiled in")
                appendLine("  required applets missing from busybox build: ${if (missing.isEmpty()) "<none>" else missing.joinToString(", ")}")
            } else {
                appendLine("  busybox --list: <unavailable>")
            }
        }
    }

    /** §1.57: best-effort `busybox --list` membership set; null when it cannot run. */
    private fun compiledAppletList(): Set<String>? {
        val busybox = File(binDir, "busybox")
        if (!existsNoFollow(busybox)) return null
        return try {
            val process = ProcessBuilder(busybox.absolutePath, "--list")
                .apply { environment().putAll(processEnv()) }
                .start()
            // §1.50 bounded-drain pattern: output is read on a background
            // thread against a bounded waitFor, so a hung busybox can never
            // hang the Copy-Error diagnostics path.
            val drain = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val outputFuture = drain.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
                val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return null
                }
                if (process.exitValue() != 0) return null
                val output = try { outputFuture.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { "" }
                output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } finally {
                drain.shutdownNow()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * §1.47: Diagnose the vendored loader artifact — the tracee-side loader
     * stub PRoot needs to run any guest binary. Checks existence, symlink
     * chain resolution, ELF class (must be a static ELF *executable* of the
     * same bitness as the app's ABI), and whether `PROOT_LOADER` will be set.
     */
    fun diagnoseLoader(): String {
        return buildString {
            appendLine("=== Loader (§1.47) ===")
            val issue = validateBinary("loader")
            val bin = File(binDir, "loader")
            appendLine("  binDir/loader: exists=${existsNoFollow(bin)}")
            if (existsNoFollow(bin)) {
                appendLine("    type=${if (isSymlink(bin)) "SYMLINK → ${readSymlink(bin)}" else if (isRealRegularFile(bin)) "REGULAR FILE" else "OTHER"}")
                appendLine("    size=${bin.length()} canExec=${try { bin.canExecute() } catch (_: Exception) { false }}")
                val real = try { bin.toPath().toRealPath().toFile() } catch (_: Exception) { null }
                if (real != null) {
                    appendLine("    resolves-to=${real.absolutePath}")
                    appendLine("    ELF=${try { elfClass(real) } catch (e: Exception) { "read error: ${e.message}" }}")
                }
            }
            appendLine("  validateBinary: ${issue ?: "OK"}")
            appendLine("  loaderEnv(): ${loaderEnv()}")
            appendLine("  testExecve(loader): ${if (existsNoFollow(bin)) testExecve(bin.absolutePath) else "NOT_FOUND"}")
        }
    }

    /**
     * §1.46: Check dynamic linker accessibility. On Android 10+ with
     * targetSdk 29+, the dynamic linker is at /system/bin/linker64
     * (64-bit) or /system/bin/linker (32-bit), typically symlinks to
     * the APEX linker at /apex/com.android.runtime/lib64/linker64.
     * If the linker can't be opened by the kernel, every dynamic ELF
     * binary's execve() returns EACCES even with mode 0755.
     */
    fun diagnoseInterpreter(): String {
        return buildString {
            appendLine("=== Dynamic Linker (§1.46) ===")
            for (linker in LINKER_PATHS) {
                val f = File(linker)
                val exists = f.exists()
                val isFile = try { f.isFile } catch (_: Exception) { false }
                val canExec = try { f.canExecute() } catch (_: Exception) { false }
                val size = if (exists) f.length() else 0L
                val canonical = if (exists) try { f.canonicalPath } catch (_: Exception) { "?" } else "N/A"
                val isSymlink = isSymlink(f)
                appendLine("  $linker: exists=$exists isFile=$isFile isSymlink=$isSymlink canExec=$canExec size=$size canonical=$canonical")
                if (isSymlink && canonical != linker) {
                    val target = File(canonical)
                    appendLine("    → symlink target: exists=${target.exists()} isFile=${try{target.isFile} catch(_:Exception){false}} canExec=${try{target.canExecute()} catch(_:Exception){false}}")
                }
            }
            // Check if any dynamic binary can actually be execve'd
            appendLine("  testExecve(/system/bin/linker64): ${testExecve("/system/bin/linker64")}")
        }
    }

    /**
     * §1.46: Verify that every NEEDED library in bootstrap/lib can be
     * resolved through the symlink chain to a real file in nativeLibraryDir.
     * Also checks if LD_LIBRARY_PATH at runtime would resolve correctly.
     */
    fun diagnoseLibResolution(): String {
        return buildString {
            appendLine("=== Library Resolution (§1.46) ===")
            appendLine("  LD_LIBRARY_PATH would be: ${libDir.absolutePath}")
            appendLine("  libDir exists: ${libDir.exists()}")
            appendLine("  libDir listFiles: ${libDir.listFiles()?.size ?: 0} entries")
            LIB_SONAMES.forEach { soname ->
                val symlink = File(libDir, soname)
                val exists = existsNoFollow(symlink)
                val isSym = isSymlink(symlink)
                val resolved = if (exists) try { symlink.toPath().toRealPath().toString() } catch (_: Exception) { "?" } else "N/A"
                val targetExists = if (resolved != "N/A") File(resolved).exists() else false
                appendLine("  $soname: exists=$exists isSymlink=$isSym → $resolved targetExists=$targetExists")
            }
            // Check applib symlink
            val applib = File(application.filesDir, "applib")
            val applibTarget = if (isSymlink(applib)) try { readSymlink(applib) } catch (_: Exception) { "?" } else "NOT_SYMLINK"
            val applibValid = try { applib.toPath().toRealPath().toFile().exists() } catch (_: Exception) { false }
            appendLine("  applib symlink: target=$applibTarget valid=$applibValid")
        }
    }

    /**
     * §1.46/§1.49: Log the exact processEnv() that will be passed to every
     * ProcessBuilder and PTY session. This is critical because the
     * LD_LIBRARY_PATH must point to host-side paths that resolve through
     * the symlink chain to nativeLibraryDir.
     */
    fun diagnoseProcessEnv(): String {
        val env = processEnv()
        return buildString {
            appendLine("=== processEnv() (§1.46) ===")
            env.forEach { (key, value) ->
                val resolved = when (key) {
                    "LD_LIBRARY_PATH" -> {
                        val f = File(value)
                        "exists=${f.exists()} isDir=${f.isDirectory} entries=${f.listFiles()?.size ?: 0}"
                    }
                    "PATH" -> {
                        val f = File(value)
                        "exists=${f.exists()} isDir=${f.isDirectory} entries=${f.listFiles()?.size ?: 0}"
                    }
                    // §1.47: PROOT_LOADER resolves through the same
                    // binDir symlink chain — check it's a real, executable
                    // file at launch time so a missing loader is
                    // obvious in device logs before PRoot even runs.
                    "PROOT_LOADER" -> {
                        val f = File(value)
                        "exists=${f.exists()} isSymlink=${java.nio.file.Files.isSymbolicLink(f.toPath())} canExec=${f.canExecute()}"
                    }
                    else -> ""
                }
                appendLine("  $key=$value $resolved")
            }
            // §1.48: PROOT_TMP_DIR is deliberately NOT in processEnv() —
            // it's per-invocation scoped (prootEnv). Show the parent state so
            // a missing scratch root is visible in the same dump.
            appendLine("  (PROOT_TMP_DIR per-invocation (§1.48) parent=${prootTmpDir.absolutePath} exists=${prootTmpDir.exists()})")
        }
    }

    /**
     * §1.50: Detect the known seccomp/ptrace kernel-incompatibility failure
     * signature in a libproot.so launch's output. The exact marker was
     * verified against the shipped binary via `strings` (2026-08-03):
     *
     * ```
     * It seems your kernel contains this bug: https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161
     * To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.
     * ```
     *
     * On devices with hardened/OEM-patched kernels this is the one failure
     * class where an otherwise-correct bootstrap chain fails with a known,
     * documented workaround. Callers must retry once with
     * `PROOT_NO_SECCOMP=1` in the environment when this returns true, and
     * log loudly that the fallback was used (never silently).
     */
    fun isSeccompKernelBugFailure(output: String): Boolean =
        output.contains(SECCOMP_BUG_MARKER)

    /**
     * §1.46: Test whether a binary can be execve'd from this process.
     * Returns a diagnostic string with the result. Uses a 5-second
     * timeout to avoid hanging.
     */
    fun testExecve(binaryPath: String): String {
        val f = File(binaryPath)
        if (!f.exists()) return "NOT_FOUND"
        if (!f.canExecute()) return "NOT_EXECUTABLE"
        return try {
            // For the linker itself, just try to load it — it will fail
            // with a usage message, which means execve succeeded.
            val pb = ProcessBuilder(binaryPath)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            val proc = pb.start()
            // Pre-release audit fix (2026-09-02, finding RT-12): the old
            // readText() BEFORE waitFor blocked until EOF — a probed binary
            // that never closed stdout hung this probe forever and the 5 s
            // timeout below was dead code (the same §1.68 bug the applet
            // installer already fixed, missed here). Bounded drain + bounded
            // wait, matching the applet installer's pattern.
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val outputFuture = executor.submit<String> {
                    try {
                        proc.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Exception) {
                        ""
                    }
                }
                val completed = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    proc.destroyForcibly()
                    "TIMEOUT_5S (binary started but didn't exit — likely working)"
                } else {
                    val exit = proc.exitValue()
                    val output = try {
                        outputFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        ""
                    }
                    "execve_OK exit=$exit output=${output.take(200).replace("\n", "\\n")}"
                }
            } finally {
                executor.shutdownNow()
            }
        } catch (e: java.io.IOException) {
            // This is where Permission denied surfaces
            "EXECVE_FAILED: ${e.javaClass.simpleName}: ${e.message}"
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * §1.190: The ONE environment computation. Both public env outputs
     * ([processEnv] for host helper processes, [guestLaunchEnv] for the
     * PRoot-traced child) are projections of this single map — never
     * independently rebuilt. Add a key here and both outputs see it;
     * differentiate an output by *selecting* below, not by forking this.
     *
     * §1.47: includes `PROOT_LOADER` (when the loader artifact is valid) so
     * libproot.so never falls back to its opaque internal `extract_loader()`
     * self-extraction (Issue 1 of the 2026-08 engineering audit). The
     * loader is executed through the same binDir → applib → nativeLibraryDir
     * symlink chain as every other bootstrap binary, so it inherits the
     * apk_data_file SELinux label that permits execve on Android 10+.
     *
     * §1.49 (Issue 4 fix): the single source of truth for the *host-side*
     * environment of a libproot.so (or tmux-wrapping-proot) invocation. It
     * deliberately omits `PROOT_TMP_DIR` — that value is per-invocation
     * scoped (§1.48) and supplied by callers via [prootEnv].
     *
     * §1.75: `TERMINFO` (the host-side ncurses DB for the bundled tmux) is
     * added only when the install produced a usable entry — fail-open
     * otherwise (see [terminfoKeyEntryUsable]).
     */
    private fun bootstrapEnv(): Map<String, String> = buildMap {
        put("PATH", binDir.absolutePath)
        put("LD_LIBRARY_PATH", libDir.absolutePath)
        putAll(loaderEnv())
        if (terminfoKeyEntryUsable()) put("TERMINFO", terminfoDir.absolutePath)
    }

    /**
     * §1.190 output 1 of 2 — the HOST-process env.
     *
     * For every host-side `ProcessBuilder` that runs a bootstrap binary
     * *outside* PRoot: the installer's `tar`/`zstd`/`chmod`/`sed` applets
     * ([com.fem.linexa.service.installer.RootfsExtractor],
     * [com.fem.linexa.service.installer.RootfsFixupService],
     * [com.fem.linexa.service.installer.GuestRootfsInitializer]), the
     * management terminal, and `tmux kill-server`.
     *
     * `LD_LIBRARY_PATH` = [libDir] is load-bearing here and must NOT be
     * removed. Verified on-device 2026-08-23: jniLibs ships PRoot's
     * dependencies only under mangled `lib__lib__libtalloc.so.2__.so`
     * names, so nativeLibraryDir cannot satisfy the `libtalloc.so.2`
     * SONAME the linker actually requests — [libDir]'s soname symlink farm
     * is the only resolver. With this key removed every bootstrap binary
     * dies at `CANNOT LINK EXECUTABLE ... library "libtalloc.so.2" not
     * found`, before `main`.
     *
     * Unchanged from the pre-§1.190 `processEnv()` — same keys, same values.
     */
    fun processEnv(): Map<String, String> = bootstrapEnv()

    /**
     * §1.190 output 2 of 2 — the GUEST-LAUNCH env.
     *
     * The env handed to [com.fem.linexa.service.runtime.container.PRootLauncher]'s
     * traced child. Same computation as [processEnv], but named separately
     * because its consumer is categorically different: PRoot passes this
     * environment through to the guest process *verbatim* (verified
     * on-device 2026-08-23 by dumping `env` inside a scratch rootfs), so
     * every key here is simultaneously host infrastructure AND part of the
     * guest's environment.
     *
     * `LD_LIBRARY_PATH` is deliberately still present, and that is a
     * *constraint*, not an oversight. PRoot itself is the process being
     * exec'd, so it needs [libDir] to resolve its own `libtalloc.so.2` /
     * `libandroid-shmem.so` before it can trace anything — dropping the key
     * breaks every launch (verified: `env -u LD_LIBRARY_PATH proot …` →
     * `CANNOT LINK EXECUTABLE`).
     *
     * F-8 (2026-08-25 audit): the host path no longer lands in the GUEST,
     * though. The vendored PRoot now carries the guest-env-filter patch
     * (`jniLibs/proot-build/0004-guest-env-filter.patch`): PRootLauncher sets
     * `PROOT_GUEST_ENV_FILTER` naming the host-owned keys, and PRoot scrubs
     * each of them from the tracee's environment at execve time — value-
     * matched, so a guest that exports its OWN `LD_LIBRARY_PATH` keeps it.
     * The scrub happens in PRoot AFTER its own linkage is complete, which is
     * why the key can stay here AND be absent from the guest. The ROOTFS
     * EXTRACTOR deliberately skips the filter: its tracee is the bundled
     * bionic `tar`, which needs this exact key to link.
     *
     * Why a leftover value would have been acceptable for every
     * currently-supported guest (§0 — Debian/Ubuntu/Arch/Fedora/Alpine):
     * the value is a host path that does not exist inside the rootfs, so
     * the guest's own dynamic linker finds nothing there and falls through
     * to `/lib`, `/usr/lib` and the binary's RPATH/RUNPATH. Verified
     * on-device against a scratch rootfs whose binaries resolve via their
     * own RUNPATH: the guest launches and behaves identically with the key
     * present.
     *
     * Where it is NOT acceptable: a *bionic*-linked guest binary, which
     * would consult this path and fail to link. Such guests are already
     * unsupported for an unrelated and more fundamental reason — see the
     * bind-mount note in `PRootLauncher.sharedStorageBindMount`'s KDoc.
     *
     * No guest-side override exists to honour: `linexa.launch`
     * ([com.fem.linexa.service.workspaceconfig.LaunchConfig]) exposes only
     * `shell`/`shellArgs`/`bindMounts`, and `LD_LIBRARY_PATH` is in
     * [HOST_ENV_KEYS] — a `config.yaml` `linexa.env` entry for it is
     * dropped by `PRootContainerRuntime.applyConfigEnv`. So this key has
     * exactly one authority: this function.
     */
    fun guestLaunchEnv(): Map<String, String> = bootstrapEnv()

    /**
     * §1.48/§1.49: The complete guest-launch environment for a single PRoot
     * invocation, with a caller-scoped [prootTmpDir] filled in. This is the
     * function every libproot.so launch path (openTerminal PTY, one-shot
     * exec, native rootfs extraction) must use — the single source of
     * truth, with PROOT_TMP_DIR as the only per-invocation variable.
     *
     * §1.190: layered on [guestLaunchEnv] rather than [processEnv] — every
     * caller of this function is launching a traced child, so the
     * guest-facing output is the correct base.
     */
    fun prootEnv(prootTmpDir: String): Map<String, String> = guestLaunchEnv() +
        ("PROOT_TMP_DIR" to prootTmpDir)

    /**
     * §1.47: `PROOT_LOADER` environment entry pointing at the vendored,
     * validated loader artifact. Empty when the loader failed validation
     * (falls back to libproot.so's self-extraction, logged loudly).
     */
    fun loaderEnv(): Map<String, String> {
        val issue = validateBinary("loader")
        if (issue != null) {
            Logger.w(TAG, "loader artifact invalid ($issue) — libproot.so will fall back to internal self-extraction")
            return emptyMap()
        }
        return mapOf("PROOT_LOADER" to resolveBinary("loader").absolutePath)
    }

    // ---- Path classification helpers (the §1.42 fix) ----

    /** The only reliable symlink test — do NOT use canonicalPath string compares. */
    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        false
    }

    private fun existsNoFollow(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    /** True iff the path is a *real regular file* — not a symlink, not a dir. */
    private fun isRealRegularFile(file: File): Boolean = try {
        !Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath())
    } catch (_: Exception) {
        false
    }

    private fun readSymlink(file: File): String = try {
        Files.readSymbolicLink(file.toPath()).toString()
    } catch (_: Exception) {
        "?"
    }


    private fun elfClass(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
                magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
            ) {
                return "NOT ELF (magic: ${magic.joinToString("") { "%02x".format(it) }})"
            }
            val elfClass = raf.readByte().toInt() and 0xFF
            val endian = raf.readByte().toInt() and 0xFF
            raf.seek(18)
            val eMachine = raf.readShort().toInt() and 0xFFFF
            val arch = when (eMachine) {
                0x03 -> "x86"; 0x3E -> "x86_64"; 0x28 -> "ARM32"
                0xB7 -> "AArch64"; 0xF3 -> "RISC-V"; 0x08 -> "MIPS"
                else -> "unknown(0x${eMachine.toString(16)})"
            }
            return "ELF ${if (elfClass == 2) "64-bit" else "32-bit"} ${if (endian == 1) "LE" else "BE"} $arch"
        }
    }

    private fun linkIfMissing(link: File, target: File) {
        if (existsNoFollow(link)) return
        try {
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            Logger.e(TAG, "Could not create symlink ${link.name} -> ${target.name}", e)
        }
    }

    companion object {
        private const val TAG = "BootstrapManager"

        /** §1.46: Known dynamic linker paths on Android. */
        private val LINKER_PATHS = listOf(
            "/system/bin/linker64",
            "/system/bin/linker",
            "/apex/com.android.runtime/lib64/linker64",
            "/apex/com.android.runtime/lib/linker"
        )

        /**
         * §1.50: Exact substring PRoot prints when its seccomp filter can't
         * be installed on this kernel (verified in libproot.so via strings,
         * 2026-08-03). Used by [isSeccompKernelBugFailure].
         */
        private const val SECCOMP_BUG_MARKER = "It seems your kernel contains this bug"

        /**
         * §1.50: The documented workaround env entry (verified against the
         * shipped binary, 2026-08-03). Shared by the runtime
         * ([com.fem.linexa.service.runtime.container.PRootContainerRuntime])
         * and the installer ([com.fem.linexa.service.installer.RootfsExtractor])
         * so both call sites pre-apply/retry with the same value.
         */
        internal val NO_SECCOMP_ENV: Map<String, String> = mapOf("PROOT_NO_SECCOMP" to "1")

        /** §1.50: Whether the runtime's one-time seccomp probe has run this process. */
        @Volatile internal var seccompProbeDone = false

        /**
         * §1.50: Whether this kernel is known to be seccomp-incompatible.
         * Written by the runtime's probe/exec retry AND by the installer's
         * extraction retry; read by both call sites so the first detection —
         * from either path — makes every later launch pre-apply the fallback
         * at zero probe cost. Hosted here (not per-class) so the runtime and
         * installer never diverge on their own flags.
         */
        @Volatile internal var needsNoSeccomp = false

        /**
         * §1.49 (Issue 3 fix): the closed set of host-infrastructure
         * environment keys that a workspace's `config.yaml` (`linexa.env`)
         * can NEVER override. `linexa.env` is guest-authored content that can
         * arrive from a user-imported rootfs archive; these keys are owned by
         * the launch path itself:
         *
         * - `PATH`, `LD_LIBRARY_PATH` — host-side dynamic-linker resolution
         *   for the vendored static binaries (executed through the
         *   binDir/applib symlink chain).
         * - `PROOT_TMP_DIR` — per-invocation scoped by the runtime (§1.48).
         * - `PROOT_LOADER` — resolved from the vendored loader artifact (§1.47).
         * - `HOME` — the guest user home computed by PRootLauncher.
         * - `PROOT_NO_SECCOMP` — the §1.50 fallback, owned by BootstrapManager
         *   (shared runtime + installer state, never user-overridable).
         * - `TERMINFO` — the §1.75 host-side ncurses DB path for the bundled
         *   tmux; a HOST path, never guest-authored (a guest rootfs's own
         *   terminfo is untouched — the value becomes a dangling path inside
         *   PRoot and in-guest ncurses falls through to the rootfs DB).
         * - `PROOT_GUEST_ENV_FILTER` — F-8 (2026-08-25 audit): the scrub list
         *   the vendored PRoot applies to the tracee environment; owned by
         *   PRootLauncher so a workspace config can never widen the guest's
         *   exposure back to host paths.
         *
         * Enforced in code by PRootContainerRuntime.applyConfigEnv() for
         * every call site — a boundary enforced by data, not by a comment.
         *
         * Pre-release audit fix (2026-09-02): `LD_PRELOAD`/`LD_AUDIT` were
         * missing, so guest-authored config.yaml `environment:` entries could
         * inject them into the HOST-side proot process environment (the env
         * is a full replacement assembled from spec.env; the dynamic loader
         * honors both for every library the proot host maps). They are host
         * -owned now: guest YAML cannot set them.
         */
        val HOST_ENV_KEYS: Set<String> = setOf(
            "PATH",
            "LD_LIBRARY_PATH",
            "LD_PRELOAD",
            "LD_AUDIT",
            "PROOT_TMP_DIR",
            "PROOT_LOADER",
            "HOME",
            "PROOT_NO_SECCOMP",
            "TERMINFO",
            "PROOT_GUEST_ENV_FILTER"
        )

        /**
         * (bootstrap name, jniLibs file name) for executables we expose.
         *
         * §1.47: `loader` is PRoot's tracee-side loader stub, extracted
         * verbatim from this libproot.so build's own embedded blob
         * (`_binary_loader_elf_start/_end`, verified byte-identical) and
         * vendored as a first-class artifact exactly like proot/tar/tmux.
         * This build of libproot.so reads only `PROOT_LOADER` — verified
         * via `strings`: `PROOT_LOADER_32` appears nowhere in the binary —
         * so no 32-bit compat loader (`loader32`) is provisioned. The
         * vendor decision (Issue 1 of the 2026-08 engineering audit):
         * provision the loader explicitly at setup time instead of relying
         * on libproot.so's silent `extract_loader()` self-extraction, which
         * happens inside the exec'd process with no Kotlin-observable
         * diagnostics if it fails.
         */
        private val BIN_ALIASES: List<Pair<String, String>> = listOf(
            "busybox" to "lib__bin__busybox__.so",
            "tar" to "lib__bin__tar__.so",
            "zstd" to "lib__bin__zstd__.so",
            "proot" to "libproot.so",
            "loader" to "lib__bin__loader__.so",
            "tmux" to "lib__bin__tmux__.so"
        )

        /** Soname of every vendored dependency lib. */
        private val LIB_SONAMES: List<String> = listOf(
            "libbusybox.so.1.38.0",
            "libacl.so",
            "libandroid-glob.so",
            "libandroid-selinux.so",
            "libiconv.so",
            "libpcre2-8.so",
            "libzstd.so.1",
            "libz.so.1",
            "liblzma.so.5",
            "libandroid-support.so",
            "libncursesw.so.6",
            "libevent_core-2.1.so",
            // §1.77: proot 5.1.107.89 (termux lineage) dynamic dependencies.
            "libtalloc.so.2",
            "libandroid-shmem.so"
        )

        /**
         * §1.75: Name of the directory under `filesDir` that holds the
         * host-side ncurses database for the bundled tmux binary (see
         * [BootstrapManager.terminfoDir]).
         */
        const val TERMINFO_DIR_NAME = "terminfo"

        /** §1.75: asset directory under `app/src/main/assets/` holding the entries. */
        private const val TERMINFO_ASSET_DIR = "terminfo"

        /** §1.75: the entry the launch env's TERM (`xterm-256color`) actually needs. */
        private const val TERMINFO_KEY_ENTRY = "x/xterm-256color"

        /**
         * §1.75: ncurses entries that must be vendored as host terminfo.
         * Keys are the ncurses on-disk path: `{first-letter}/{terminal}`.
         * The outer TERM is `xterm-256color` (PRootLauncher buildLaunchSpec),
         * so the tmux client needs that entry; `xterm`/`screen`/
         * `screen-256color` cover the fallback outer terminals and tmux's
         * classic `default-terminal` values. Inside the guest, each of these
         * still resolves from the rootfs's own terminfo untouched (see
         * [terminfoDir]).
         */
        val TERMINFO_ENTRIES: List<String> = listOf(
            "x/xterm",
            "x/xterm-256color",
            "s/screen",
            "s/screen-256color"
        )

        /**
         * §1.75: ncurses binary terminfo magic — first byte 0x1A/0x1E, second
         * byte 0x01/0x02 (standard vs extended section). Used by
         * [BootstrapManager.installHostTerminfo] to refuse garbage as a
         * vendored entry.
         */
        internal fun isTerminfoMagic(bytes: ByteArray): Boolean {
            if (bytes.size < 2) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val firstOk = b0 == 0x1A || b0 == 0x1E
            val secondOk = b1 == 0x01 || b1 == 0x02
            return firstOk && secondOk && (b0 == 0x1A) == (b1 == 0x01)
        }
    }
}