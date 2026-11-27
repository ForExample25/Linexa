package com.fem.linexa.service.installer

import android.system.Os
import android.system.OsConstants
import com.fem.linexa.core.filesystem.RootfsWriteLock
import com.fem.linexa.core.filesystem.RootfsWriteWindow
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.GuestIdentity
import com.fem.linexa.service.runtime.container.PRootLauncher
import com.fem.linexa.service.runtime.container.WorkspaceHomeLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.80: repairs guest-rootfs entries that distro tarballs omit, so a
 * workspace is usable before bash is ever launched.
 *
 * Root cause of the "apt update → Unable to mkstemp /tmp/apt.sig (2: No
 * such file or directory)" runtime bug: the Debian/Ubuntu rootfs archives
 * ship NO `/tmp` at all (`/tmp` is a tmpfs mount that systemd creates at
 * boot, so a docker-style tarball never carries it — verified against the
 * bundled trixie archive, which ships only `/var/tmp`). PRoot does not
 * bind `/tmp` (the Android host has no `/tmp` either), so the guest's
 * `/tmp` must originate from the rootfs — and nothing created it. apt and
 * any tool that mkstemp's into `/tmp` then failed.
 *
 * [ensureGuestTmp] guarantees the exact `drwxrwxrwt` (1777) state on
 * `{rootfs}/tmp` before a session or one-shot command starts:
 *   - creates `/tmp` when missing,
 *   - replaces a `/tmp` that exists but is not a directory,
 *   - re-applies `chmod 1777` on every call via the bundled busybox
 *     `chmod` applet — Java NIO can only express the 9 rwx bits, never the
 *     sticky bit (01000), so the applet is the only way to guarantee 1777,
 *   - best-effort verifies the resulting mode through `unix:mode` (exact
 *     on the JVM; skipped on devices where the attribute is unavailable —
 *     there `chmod`'s own exit 0 is the contract).
 *
 * Callers: the installer runs it once during workspace initialization
 * (Pass 3.5, before the rootfs is activated) so fresh workspaces start
 * clean; the runtime runs it before every `openTerminal` and every one-shot
 * `exec` so existing/imported workspaces self-heal at launch. Never a
 * shell workaround — every argv is typed and nothing is concatenated.
 *
 * **UI/UX Phase 7:** because the runtime calls this on every terminal open and
 * every one-shot `exec`, its routine "guest /tmp ready" line was the app's most
 * frequent log write — appended to an uncapped per-workspace file whether or not
 * the user had debug info on. That single line is now gated on [DebugCapture];
 * the failure and repair lines are not. See [logDebugEvent].
 *
 * **Phase 2E-2 (steady-state gate):** `/tmp`, `/dev/shm` and `/run` are
 * lstat-verified (no-follow: REAL directory, full mode 01777) at the top of
 * every ensure call. When the target is already in that exact state the whole
 * repair body — exists/symlink gates, mkdirs and the ~100 ms busybox `chmod`
 * spawn — is skipped and the call returns immediately. Anything else (missing
 * path, symlink, regular file, any deviation in the 12 mode bits `chmod` sets,
 * or an lstat failure) runs the full repair body unchanged.
 *
 * **Phase 3 (symlinked `/tmp`):** a symlinked `/tmp` is NOT skipped (lstat
 * sees `S_IFLNK`), but it is now REPLACED with a real directory instead of
 * being chmod'ed through. The link itself is deleted with
 * `Files.deleteIfExists` — never `deleteRecursively`, which can follow the
 * link — and the no-follow directory assertion runs before and after `chmod`,
 * so a symlink planted inside an archive can no longer redirect the host-side
 * mode change onto its target. A repair keeps its existing logs, so on device
 * the ABSENCE of `guest /tmp chmod ok` lines during steady-state launches is
 * the observable signal that the gate fired.
 */
@Singleton
class GuestRootfsInitializer @Inject constructor(
    private val bootstrapManager: BootstrapManager,
    private val diagnosticLogManager: DiagnosticLogManager
) {

    /**
     * P0-1 (2026-11-23): the host's supplementary group set, read once per
     * process.
     *
     * Android fixes a process's kernel supplementary groups when the process
     * starts; a storage/permission grant that changes them also restarts the
     * app (the zygote must re-fork with the new credential set). Within one
     * process the `id -G` answer is therefore constant, and spawning the
     * bundled `id` applet before every one-shot exec was pure overhead
     * (PERF-005 / IND-01). The guest `/etc/group` reconciliation still runs
     * on every battery pass; only the host-side read is memoized.
     *
     * A *failed* read is never cached: the next pass retries. An empty
     * successful read is cached too — "this process has no supplementary
     * groups" is just as constant as a non-empty set.
     */
    @Volatile
    private var hostSupplementaryGidCache: List<String>? = null
    private val hostSupplementaryGidLock = Any()

    /**
     * Ensures the app (the host owner of every rootfs entry) may create
     * entries directly under the guest's `/`.
     *
     * Arch and Fedora container archives restore `/` as `0555`. A real root
     * user can still write there because the kernel grants CAP_DAC_OVERRIDE;
     * a PRoot guest cannot, because its `-0` identity is emulated while the
     * kernel still checks the app's real uid. Without this repair every
     * top-level provisioning write — the `.linexa` migration marker, future
     * runtime state, a package that creates `/opt` — fails with EACCES on an
     * otherwise healthy image.
     *
     * Only the owner bits are added (the archive's `0555` becomes the normal
     * Linux `/` mode `0755`); group/other permissions are untouched. This is
     * idempotent and runs before the `/tmp` fast path so an imported image
     * is repaired on its first launch/exec, not only when `/tmp` is missing.
     *
     * Returns null on success, or a user-meaningful failure message.
     */
    fun ensureGuestRootfsWritable(rootfs: File, workspaceId: String): String? {
        val path = rootfs.toPath()
        val isLink = try {
            Files.isSymbolicLink(path)
        } catch (_: Exception) {
            false
        }
        if (isLink || !rootfs.isDirectory) {
            val msg = "guest rootfs root is not a real directory at ${rootfs.absolutePath}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, msg)
            return msg
        }

        return try {
            val current = Files.getPosixFilePermissions(path)
            if (PosixFilePermission.OWNER_WRITE in current) return null

            val widened = current + setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
            Files.setPosixFilePermissions(path, widened)
            val notice = "guest rootfs root was read-only (${modeString(current)}) — owner-write added for PRoot parity"
            Logger.i(TAG, notice)
            logEvent(workspaceId, notice)
            null
        } catch (e: Exception) {
            val msg = "could not make guest rootfs root writable at ${rootfs.absolutePath}: ${e.message}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, msg)
            msg
        }
    }

    /** Renders a POSIX set as `rwx` groups, for the permission-repair log. */
    private fun modeString(permissions: Set<PosixFilePermission>): String {
        fun bit(permission: PosixFilePermission): Char = if (permission in permissions) {
            when (permission) {
                PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ -> 'r'
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_WRITE -> 'w'
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE -> 'x'
            }
        } else {
            '-'
        }
        return buildString {
            append(bit(PosixFilePermission.OWNER_READ))
            append(bit(PosixFilePermission.OWNER_WRITE))
            append(bit(PosixFilePermission.OWNER_EXECUTE))
            append(bit(PosixFilePermission.GROUP_READ))
            append(bit(PosixFilePermission.GROUP_WRITE))
            append(bit(PosixFilePermission.GROUP_EXECUTE))
            append(bit(PosixFilePermission.OTHERS_READ))
            append(bit(PosixFilePermission.OTHERS_WRITE))
            append(bit(PosixFilePermission.OTHERS_EXECUTE))
        }
    }

    /**
     * Ensure `{rootfs}/tmp` exists with exactly 1777 (`drwxrwxrwt`).
     * Returns null on success, or a user-meaningful failure message.
     */
    fun ensureGuestTmp(rootfs: File, workspaceId: String): String? {
        // Must run before the `/tmp` fast path: a rootfs whose `/tmp` is
        // already healthy still needs its read-only `/` repaired for the
        // root-emulated package manager and the runtime's own markers.
        ensureGuestRootfsWritable(rootfs, workspaceId)?.let { return it }

        val tmp = File(rootfs, "tmp")

        // Phase 2E-2 steady-state gate: when /tmp is lstat-verified (no-follow)
        // as a real directory already carrying the exact 1777 mode, the whole
        // repair body below — the non-directory replacement, mkdirs and the
        // ~100 ms busybox chmod spawn — is skipped.
        // Pre-release audit fix (2026-09-02, finding F17): a SYMLINKED /tmp is
        // now replaced like any other non-directory. The old path preserved
        // the chmod-through-the-link behaviour, so a symlink planted inside
        // the archive (the same class the /dev gate refuses) would have its
        // TARGET chmod'ed to 1777 on the host view. Bounded to app-writable
        // paths, but the /dev/shm gate already sets the correct precedent.
        if (isAlreadyPrepared(tmp)) return null

        val tmpPath = tmp.toPath()

        // Phase 3: lstat BEFORE any `isDirectory` test. `File.isDirectory`
        // follows links, so the old `exists() && !isDirectory` condition let a
        // symlink-to-directory through, and `chmod 1777` then changed the
        // TARGET's mode through the link. A symlink is now replaced like any
        // other non-directory; the link itself is unlinked, never its target.
        val tmpIsLink = try {
            Files.isSymbolicLink(tmpPath)
        } catch (_: Exception) {
            false
        }
        if (tmpIsLink || (tmp.exists() && !tmp.isDirectory)) {
            val kind = if (tmpIsLink) "a symlink" else "not a directory"
            val notice = "guest /tmp is $kind (${tmp.absolutePath}) — replacing with a real directory"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
            try {
                if (tmpIsLink) {
                    // deleteIfExists unlinks the symlink itself. Never
                    // deleteRecursively here: it can follow the link on some
                    // JVMs and delete the target's contents.
                    Files.deleteIfExists(tmpPath)
                } else if (!tmp.deleteRecursively()) {
                    val msg = "Could not remove non-directory guest /tmp at ${tmp.absolutePath}"
                    Logger.e(TAG, msg)
                    return msg
                }
            } catch (e: Exception) {
                val msg = "Could not remove $kind guest /tmp at ${tmp.absolutePath}: ${e.message}"
                Logger.e(TAG, msg)
                return msg
            }
        }

        if (!tmp.exists()) {
            if (!tmp.mkdirs()) {
                val msg = "Could not create guest /tmp at ${tmp.absolutePath}"
                Logger.e(TAG, msg)
                return msg
            }
            Logger.i(TAG, "created guest /tmp at ${tmp.absolutePath}")
            logEvent(workspaceId, "created guest /tmp at ${tmp.absolutePath}")
        }

        // Phase 3: chmod must only ever run against a REAL directory. If a
        // symlink was raced into place after the removal above, fail instead
        // of following it.
        if (!isRealDirectory(tmpPath)) {
            val msg = "guest /tmp at ${tmp.absolutePath} is not a real directory — refusing to chmod"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "chmod 1777 REFUSED: $msg")
            return msg
        }

        // chmod 1777 through the bundled busybox chmod applet (§1.57
        // guarantees the applet symlink exists). Java NIO cannot set the
        // sticky bit, so this is the mode-setting mechanism on device.
        val chmod = bootstrapManager.resolveBinary("chmod")
        val result = runChmod(chmod, tmp, workspaceId)
        if (!result.success) {
            val msg = "guest /tmp chmod 1777 failed for ${tmp.absolutePath}: ${result.describe(chmod)}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "chmod 1777 FAILED: $msg")
            return msg
        }

        // Phase 3: assert the type again AFTER the spawn — the mode change
        // must have landed on the directory verified above, not on a target
        // reached through a link swapped in mid-call.
        if (!isRealDirectory(tmpPath)) {
            val msg = "guest /tmp at ${tmp.absolutePath} stopped being a real directory after chmod"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "mode verification FAILED: $msg")
            return msg
        }

        // Best-effort verification. Exact on the JVM; on devices where
        // `unix:mode` is unavailable (Android's minimal java.nio.file),
        // chmod's exit 0 above is the contract.
        val modeError = verifyMode(tmp)
        if (modeError != null) {
            val msg = "guest /tmp at ${tmp.absolutePath} $modeError"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "mode verification FAILED: $msg")
            return msg
        }

        Logger.i(TAG, "guest /tmp ready: ${tmp.absolutePath} (mode verified, chmod=$chmod)")
        // UI/UX Phase 7: the success line is the ONE event here that is not a
        // state change, and it is by far the most frequent write in the app —
        // ensureGuestTmp runs before every terminal open AND every one-shot
        // `exec`, so an `apt` session or a File Manager walk appended a line per
        // operation to a per-workspace log that has no rotation and no size cap
        // (§1.18/§1.53 deliberately exclude workspace logs from the app log's
        // generation scheme). Nothing reads it back except a human diagnosing a
        // failure, and a failure writes its own line unconditionally below/above.
        // So this one is gated on the debug toggle while the four failure/repair
        // lines are not: an unexplained broken workspace must still leave
        // evidence, but "it worked again, for the four-hundredth time" must not
        // grow a file the user cannot see.
        logDebugEvent(workspaceId, "guest /tmp ready: ${tmp.absolutePath}")
        return null
    }

    /**
     * F-5 (2026-08-25 audit): ensures `{rootfs}/dev/shm` exists as a real
     * directory with exactly 1777 (`drwxrwxrwt`), the guest-side POSIX
     * shared-memory surface.
     *
     * ## Why this directory is Linexa's to provide
     *
     * The guest's `/dev` is the Android HOST's `/dev` (PRoot `-b /dev`),
     * and Android hosts have no `/dev/shm` — so without this repair the
     * guest simply has no POSIX shm, and `shm_open`-dependent software
     * (Chromium/Electron, Qt WebEngine, some postgres/chrome-headless
     * workloads) degrades or fails. PRoot cannot mount tmpfs (that needs
     * CAP_SYS_ADMIN the app never holds), but glibc's `shm_open` only needs
     * a WRITABLE DIRECTORY at `/dev/shm` — it creates a regular file there
     * and mmaps it. A plain 1777 directory in app-private storage therefore
     * restores cross-process POSIX shm, file-backed (F2FS) instead of
     * tmpfs-backed.
     *
     * The runtime makes this directory guest-visible by binding it over the
     * guest `/dev/shm` path (`PRootLauncher` adds
     * `-b {rootfs}/dev/shm:/dev/shm` AFTER `-b /dev` — the more specific
     * binding wins), which is why the directory must live INSIDE the rootfs
     * and must be a REAL directory, never a symlink.
     *
     * ## Security posture (no unsafe symlink traversal)
     *
     * Both levels are checked WITHOUT following links:
     *  - `{rootfs}/dev` must be a real directory (or absent). If it is a
     *    symlink, this refuses to create anything — `mkdirs()` would
     *    happily write THROUGH the link, outside the rootfs. Pass 1's
     *    [com.fem.linexa.core.security.ArchiveValidator] already rejects
     *    escaping symlinks in archives; this is the defense-in-depth gate.
     *  - `{rootfs}/dev/shm`, when it is a symlink or a non-directory, is
     *    replaced: the LINK (or file) itself is deleted — never anything it
     *    points at — and a real directory is created in its place. A symlink
     *    here could otherwise redirect the runtime bind (and every shm
     *    consumer's writes) to an arbitrary path.
     *
     * Like [ensureGuestTmp]: idempotent (chmod 1777 re-applied through the
     * bundled busybox applet — Java NIO cannot set the sticky bit), verified
     * through `unix:mode` where available, best-effort logged. Returns null
     * on success or a user-meaningful failure message.
     */
    fun ensureGuestDevShm(rootfs: File, workspaceId: String): String? {
        val devPath = File(rootfs, "dev").toPath()
        val shm = File(rootfs, "dev/shm")

        // Phase 2E-2 steady-state gate, same contract as ensureGuestTmp: an
        // lstat-verified real 1777 directory skips the entire body (including
        // the /dev symlink gate — a real 1777 directory at /dev/shm implies
        // /dev exists as a real directory). A symlinked /dev/shm is NOT
        // skipped (lstat type S_IFLNK) and is still replaced below.
        if (isAlreadyPrepared(shm)) return null

        // Security gate 1: /dev must never be a symlink — creating dev/shm
        // through it would write outside the rootfs.
        val devIsLink = try {
            java.nio.file.Files.isSymbolicLink(devPath)
        } catch (_: Exception) {
            false
        }
        if (devIsLink) {
            val msg = "guest /dev is a symlink (${devPath.toFile().absolutePath}) — " +
                "refusing to create /dev/shm through it"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "dev/shm REFUSED: $msg")
            return msg
        }

        // Security gate 2: whatever sits at dev/shm, only accept a REAL
        // directory. A symlink (wherever it points) or a regular file is
        // replaced in place — deleting the entry itself, never its target.
        val shmIsLink = try {
            java.nio.file.Files.isSymbolicLink(shm.toPath())
        } catch (_: Exception) {
            false
        }
        if (shmIsLink || (shm.exists() && !shm.isDirectory)) {
            val kind = if (shmIsLink) "a symlink" else "not a directory"
            val notice = "guest /dev/shm was $kind — replacing with a real directory (F-5)"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
            try {
                java.nio.file.Files.deleteIfExists(shm.toPath())
            } catch (e: Exception) {
                val msg = "Could not remove $kind at ${shm.absolutePath}: ${e.message}"
                Logger.e(TAG, msg)
                return msg
            }
        }

        if (!shm.isDirectory) {
            try {
                // Real directories only: /dev was proven non-symlink above, so
                // createDirectories cannot traverse a link at this level.
                java.nio.file.Files.createDirectories(shm.toPath())
            } catch (e: Exception) {
                val msg = "Could not create guest /dev/shm at ${shm.absolutePath}: ${e.message}"
                Logger.e(TAG, msg)
                return msg
            }
            Logger.i(TAG, "created guest /dev/shm at ${shm.absolutePath}")
            logEvent(workspaceId, "created guest /dev/shm at ${shm.absolutePath}")
        }

        // Phase 3: the same no-follow gate as /tmp — chmod must land on a real
        // directory, never through a link raced in after the checks above.
        if (!isRealDirectory(shm.toPath())) {
            val msg = "guest /dev/shm at ${shm.absolutePath} is not a real directory — refusing to chmod"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "dev/shm chmod 1777 REFUSED: $msg")
            return msg
        }

        // chmod 1777 through the bundled busybox chmod applet — same
        // mechanism as /tmp (§1.57 guarantees the applet symlink exists).
        val chmod = bootstrapManager.resolveBinary("chmod")
        val result = runChmod(chmod, shm, workspaceId)
        if (!result.success) {
            val msg = "guest /dev/shm chmod 1777 failed for ${shm.absolutePath}: ${result.describe(chmod)}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "dev/shm chmod 1777 FAILED: $msg")
            return msg
        }

        val modeError = verifyMode(shm)
        if (modeError != null) {
            val msg = "guest /dev/shm at ${shm.absolutePath} $modeError"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "dev/shm mode verification FAILED: $msg")
            return msg
        }

        Logger.i(TAG, "guest /dev/shm ready: ${shm.absolutePath} (mode verified, chmod=$chmod)")
        logDebugEvent(workspaceId, "guest /dev/shm ready: ${shm.absolutePath}")
        return null
    }

    /**
     * R-5 (2026-08-26 runtime parity audit): ensures `{rootfs}/run` exists as
     * a real directory with exactly 1777 (`drwxrwxrwt`), the guest-side
     * per-session runtime-state surface.
     *
     * ## Why this directory is Linexa's to provide
     *
     * Debian/Ubuntu/Fedora userspace writes per-session runtime state to
     * `/run` (pid files, socket dirs, XDG_RUNTIME_DIR, utmp). Distro
     * rootfs archives ship `/run` as a real directory (sometimes as a
     * symlink to `/var/run` — Debian did this historically; `/var/run`
     * itself is then a symlink back to `/run` in modern Debian). The runtime
     * binds this directory over the guest `/run` path so anything an
     * installed package writes to `/run/foo` lands inside the rootfs
     * (persistent across the session), and anything that reads from
     * `/run/foo` finds what the previous session wrote.
     *
     * Same shape as [ensureGuestDevShm]: a real directory owned by the
     * rootfs, chmod 1777 via the bundled busybox applet, never a symlink.
     *
     * ## Security posture (no unsafe symlink traversal)
     *
     * A symlinked `/run` is REPLACED with a real directory — never written
     * through. The check mirrors [ensureGuestDevShm]'s gate 2 exactly.
     */
    fun ensureGuestRun(rootfs: File, workspaceId: String): String? {
        val run = File(rootfs, "run")

        // Phase 2E-2 steady-state gate, same contract as ensureGuestTmp: an
        // lstat-verified real 1777 directory skips the entire body. A
        // symlinked /run is NOT skipped (lstat type S_IFLNK) and is still
        // replaced with a real directory below.
        if (isAlreadyPrepared(run)) return null

        // Security gate: whatever sits at /run, only accept a REAL directory.
        // A symlink (wherever it points) or a regular file is replaced in
        // place — deleting the entry itself, never its target.
        val runIsLink = try {
            java.nio.file.Files.isSymbolicLink(run.toPath())
        } catch (_: Exception) {
            false
        }
        if (runIsLink || (run.exists() && !run.isDirectory)) {
            val kind = if (runIsLink) "a symlink" else "not a directory"
            val notice = "guest /run was $kind — replacing with a real directory (R-5)"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
            try {
                java.nio.file.Files.deleteIfExists(run.toPath())
            } catch (e: Exception) {
                val msg = "Could not remove $kind at ${run.absolutePath}: ${e.message}"
                Logger.e(TAG, msg)
                return msg
            }
        }

        if (!run.isDirectory) {
            try {
                java.nio.file.Files.createDirectories(run.toPath())
            } catch (e: Exception) {
                val msg = "Could not create guest /run at ${run.absolutePath}: ${e.message}"
                Logger.e(TAG, msg)
                return msg
            }
            Logger.i(TAG, "created guest /run at ${run.absolutePath}")
            logEvent(workspaceId, "created guest /run at ${run.absolutePath}")
        }

        // Phase 3: the same no-follow gate as /tmp and /dev/shm.
        if (!isRealDirectory(run.toPath())) {
            val msg = "guest /run at ${run.absolutePath} is not a real directory — refusing to chmod"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "run chmod 1777 REFUSED: $msg")
            return msg
        }

        // chmod 1777 through the bundled busybox chmod applet — same
        // mechanism as /tmp and /dev/shm.
        val chmod = bootstrapManager.resolveBinary("chmod")
        val result = runChmod(chmod, run, workspaceId)
        if (!result.success) {
            val msg = "guest /run chmod 1777 failed for ${run.absolutePath}: ${result.describe(chmod)}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "run chmod 1777 FAILED: $msg")
            return msg
        }

        val modeError = verifyMode(run)
        if (modeError != null) {
            val msg = "guest /run at ${run.absolutePath} $modeError"
            Logger.e(TAG, msg)
            logEvent(workspaceId, "run mode verification FAILED: $msg")
            return msg
        }

        Logger.i(TAG, "guest /run ready: ${run.absolutePath} (mode verified, chmod=$chmod)")
        logDebugEvent(workspaceId, "guest /run ready: ${run.absolutePath}")
        return null
    }

    /**
     * R-6 (2026-08-26 runtime parity audit): writes static snapshot files
     * for the `/proc` entries the Android host refuses to serve to app-uid
     * processes. See [PRootLauncher.procBindOvers] and
     * [PRootLauncher.PROC_BIND_OVER_NAMES] for the bind-over mechanism; this
     * function owns the rootfs-side artifact creation.
     *
     * ## What this writes
     *
     * One regular file per name in [PRootLauncher.PROC_BIND_OVER_NAMES],
     * placed at `{rootfs}/proc/.<name>` (the dot prefix keeps them out of
     * normal `ls /proc` views — same convention Tiny Container's audit
     * confirmed via `ls /proc/self/root/proc/`).
     *
     * ## Why the values are static snapshots, not live
     *
     * Android's `untrusted_app` SELinux domain + `hidepid=invisible` on
     * `/proc` means the host cannot read the real values at the moment of
     * snapshot creation either. So the snapshot contents are reasonable
     * SYNTHETIC values, NOT a copy of host `/proc`:
     *
     *  - `stat` — minimal `cpu` line + 8 zeroed per-core lines. The CPU
     *    count of 8 matches the typical cpuinfo output (audit:
     *    `nproc=3, cpuinfo=8` — `nproc` reads `/proc/stat` indirectly via
     *    `getrlimit`, but `cpuinfo` is what `nproc` actually parses for
     *    `_SC_NPROCESSORS_ONLN`). With `stat` now bind-overred to a real
     *    file, `nproc` and `btop` no longer abort on `EACCES`.
     *  - `uptime` — `0.0 0.0`. The real host uptime is unknowable from the
     *    app-uid context, so we ship a stable zero rather than a misleading
     *    non-zero placeholder. `uptime` and `btop` will show "0 minutes"
     *    — strictly better than the audit's `Permission denied`.
     *  - `loadavg` — `0.00 0.00 0.00 1/1 1`. Same reasoning as `uptime`.
     *  - `version` — `Linux localhost 6.1.0-linexa (proot@linexa) ...`,
     *    matching [PRootLauncher.SPOOFED_KERNEL_RELEASE].
     *  - `vmstat` — minimal `nr_running 1` / `nr_idle 0` line. `vmstat`
     *    will read a small set of fields and display them; the full
     *    kernel-internal set is not synthesizable.
     *
     * Like [ensureGuestDevShm], this is idempotent and runs before every
     * `openTerminal` and every one-shot `exec` — the bind-over reads the
     * file at *launch* time, not at *first read* time, so a freshly
     * regenerated snapshot takes effect on the next session.
     *
     * Returns null on success or a user-meaningful failure message.
     */
    fun ensureProcSnapshots(rootfs: File, workspaceId: String): String? =
        // The runtime bridge writes its own overlay files into the same
        // read-only `/proc` directory. Both sides widen, write and restore;
        // one shared monitor keeps a restore from landing between a
        // concurrent writer's two files.
        synchronized(RootfsWriteLock.monitor) {
            ensureProcSnapshotsLocked(rootfs, workspaceId)
        }

    private fun ensureProcSnapshotsLocked(rootfs: File, workspaceId: String): String? {
        val procDir = File(rootfs, "proc")

        // The rootfs's /proc must exist as a REAL directory. Distro tarballs
        // ship /proc as an empty directory (systemd mounts proc over it at
        // boot). A symlink is refused outright: widening or writing through
        // it could touch a directory outside this rootfs. The PRoot
        // -b /proc bind overwrites the guest view regardless, so skipping the
        // snapshots for a non-directory loses nothing.
        val procIsSymlink = try {
            Files.isSymbolicLink(procDir.toPath())
        } catch (_: Exception) {
            false
        }
        if (procIsSymlink || !procDir.isDirectory) {
            // Not an error — this rootfs just gets no bind-over snapshots.
            logDebugEvent(workspaceId, "no real /proc directory in rootfs — skipping /proc bind-over snapshots")
            return null
        }

        // Arch ships /proc as mode 0555; Fedora does the same for /usr/bin.
        // A POSIX directory without owner-write refuses new entries, so the
        // snapshot writes would fail with EACCES on an otherwise healthy
        // rootfs. [RootfsWriteWindow] widens exactly this directory and
        // restores the archive's mode afterwards; its close() failure is a
        // hard failure, never a silently more-writable /proc.
        var failure: String? = null
        try {
            RootfsWriteWindow.open(procDir).use {
                for ((name, content) in PROC_SNAPSHOT_CONTENTS) {
                    val snap = File(procDir, ".$name")
                    try {
                        // P0-1: the static content is constant for the life of
                        // the process. A byte-equal file (and a non-symlink
                        // target) needs no write at all — this is what keeps a
                        // converged workspace's snapshot pass write-free.
                        if (isSnapshotCurrent(snap, content)) continue
                        // Atomic-ish: write to a temp file in the same directory,
                        // then rename. Avoids a half-written file being read by
                        // PRoot mid-launch.
                        val tmpFile = File(procDir, ".$name.tmp")
                        tmpFile.writeText(content)
                        if (!tmpFile.renameTo(snap)) {
                            // Fallback: delete target and rename again.
                            snap.delete()
                            if (!tmpFile.renameTo(snap)) {
                                val msg = "Could not install /proc bind-over snapshot for $name"
                                Logger.e(TAG, msg)
                                logEvent(workspaceId, "proc snapshot install FAILED for $name")
                                failure = msg
                                break
                            }
                        }
                    } catch (e: Exception) {
                        val msg = "Could not write /proc bind-over snapshot for $name at ${snap.absolutePath}: ${e.message}"
                        Logger.e(TAG, msg)
                        logEvent(workspaceId, "proc snapshot write FAILED for $name: ${e.message}")
                        failure = msg
                        break
                    }
                }
            }
        } catch (e: Exception) {
            val msg = "Could not write guest /proc bind-over snapshots at ${procDir.absolutePath}: ${e.message}"
            Logger.e(TAG, msg, e)
            logEvent(workspaceId, msg)
            failure = failure?.let { "$it; $msg" } ?: msg
        }

        if (failure == null) {
            logDebugEvent(workspaceId, "guest /proc bind-over snapshots ready (${PROC_SNAPSHOT_CONTENTS.size} files)")
        }
        return failure
    }

    /**
     * P0-1: true when [snap] already carries exactly [content] and is a real
     * file (never a symlink). Reading a few small snapshots is far cheaper
     * than the write+rename pair and its permission-window bookkeeping, and
     * it keeps an already-converged pass write-free.
     */
    private fun isSnapshotCurrent(snap: File, content: String): Boolean = try {
        if (!snap.isFile || Files.isSymbolicLink(snap.toPath())) {
            false
        } else {
            val expected = content.toByteArray(Charsets.UTF_8)
            snap.length() == expected.size.toLong() && snap.readBytes().contentEquals(expected)
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 2026-08-27 expansion (Phase 15): installs the guest-side
     * `/usr/local/bin/linexa-hostinfo` helper — a POSIX-sh, read-only viewer
     * over `/run/linexa/hostinfo.conf` (the structured feed the runtime
     * bridge regenerates every overlay cycle).
     *
     * ## Why an executable script, and why THE PERMISSIONS ARE THE FIX
     *
     * On a previously observed build the same conceptual artifact existed
     * at `/usr/local/bin/linexa-hostinfo` with `-rw------- root root` —
     * present but unexecutable. Whatever writer produced THAT file relied
     * on default permissions; this implementation writes the bytes and then
     * sets the posix permission set EXPLICITLY (rwxr-xr-x via
     * [Files.setPosixFilePermissions] — expressible without the sticky-bit
     * limitation that forces the busybox applet for directories), and a
     * test pins the writer. A data-file-only architecture would equally
     * serve Linexa's needs; we ship BOTH halves of the pair in their
     * correct roles instead: executable helper here, authoritative DATA in
     * the bridge-owned conf — no blind chmods anywhere in the chain.
     *
     * Idempotent: byte-equal installs are skipped untouched (mtime churn
     * would otherwise rewrite on every ENSURE pass).
     *
     * Returns null on success or a user-meaningful failure message.
     */
    fun ensureGuestHostInfo(rootfs: File, workspaceId: String): String? {
        val binDir = File(File(rootfs, "usr"), "local/bin")
        val script = File(binDir, HOSTINFO_HELPER_NAME)

        try {
            if (!binDir.isDirectory) {
                java.nio.file.Files.createDirectories(binDir.toPath())
            }
            val desired = HOSTINFO_HELPER_SCRIPT.toByteArray(Charsets.UTF_8)
            if (!script.isFile || !script.readBytes().contentEquals(desired)) {
                val tmp = File(binDir, "$HOSTINFO_HELPER_NAME.tmp")
                tmp.writeBytes(desired)
                if (!tmp.renameTo(script)) {
                    script.delete()
                    if (!tmp.renameTo(script)) {
                        tmp.delete()
                        return "Could not install guest helper $HOSTINFO_HELPER_NAME"
                    }
                }
            }
            // THE contract: rwxr-xr-x. Failure to set is a hard failure —
            // shipping an unreadable/unexecutable helper repeats exactly
            // the historical bug class this function exists to fix.
            java.nio.file.Files.setPosixFilePermissions(
                script.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
                )
            )
        } catch (e: Exception) {
            val msg = "guest hostinfo helper install failed at ${script.absolutePath}: ${e.message}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, msg)
            return msg
        }

        logDebugEvent(workspaceId, "guest hostinfo helper ready (${script.absolutePath}, 0755)")
        return null
    }

    /**
     * LX-1 (2026-08-29): keeps the guest's group database in sync with the
     * supplementary groups the Android host ACTUALLY propagates into every
     * guest process right now.
     *
     * ## Root cause this repairs
     *
     * PRoot does not (and cannot) filter the tracee's kernel supplementary
     * groups: every guest process — interactive terminal, one-shot exec,
     * guest daemon — inherits the app process's LIVE group set, unchanged.
     * The installer's account fixup ([RootfsFixupService.fixUpAccounts])
     * mirrored that set into `/etc/group` ONCE, at install time, by running
     * the bundled busybox `id` in the then-current app process. But an
     * Android app's supplementary groups are NOT stable across the app's
     * lifetime: storage-grant groups (e.g. GID 1077 `ext_data_rw`,
     * 1078 `ext_obb_rw`), per-user and OEM-specific groups are granted and
     * revoked by the platform as permissions change, and every process
     * re-fork after such a change carries a different set. A workspace
     * installed before one of those changes ends up with a guest
     * `/etc/group` that names the OLD set only — the first `groups`/`id`
     * invocation in every new session then prints
     * `cannot find name for group ID 1077` for each drifted GID, forever.
     *
     * ## Policy (deterministic, no host-specific hardcoding)
     *
     * The guest identity intentionally mirrors the host identity — that is
     * the installer fixup's documented contract (§1.64: group membership
     * inside the guest matches the PRoot-emulated environment) — so the
     * correct fix is to keep the mirror CONVERGED, not to filter groups
     * (PRoot offers no group-filtering seam) and not to hardcode any
     * particular GID. This function enforces the invariant:
     *
     *   every GID the host propagates into the guest resolves to a name in
     *   the guest's `/etc/group`.
     *
     * The live set is read from the SAME source the installer fixup used —
     * the bundled busybox `id -G` run as a host process (which inherits
     * exactly the group set the next PRoot child will inherit). Every GID
     * missing from the guest database gets an `aid_<gid>` entry with the
     * same shape the fixup writes (`aid_<gid>:x:<gid>:root,aid_<user>`,
     * plus the matching `/etc/gshadow` line), where `<user>` is derived the
     * same way the fixup derives it (`id -un` whitelisted by
     * [isSafeAccountNameToken], falling back to the numeric uid). Existing
     * entries — including `root` and all previously written `aid_*` lines —
     * are never touched or duplicated.
     *
     * ## Idempotence and write discipline
     *
     * Called before every `openTerminal`, one-shot `exec` and daemon start,
     * so the common path (already converged) is NO write and — after the
     * first successful host read of the process — no `id -G` spawn either
     * (P0-1: the host group set is process-constant). When entries are
     * missing, both files are rewritten
     * atomically (temp file in the same directory + rename), never appended
     * in place, so a guest process reading the database concurrently always
     * sees one complete, well-formed file. Callers also serialize this
     * function per workspace (PRootContainerRuntime's per-workspace init
     * mutex), so concurrent sessions cannot race the read-modify-write.
     *
     * Fail-open by design: a missing `/etc/group`, a missing `id` applet or
     * an `id` failure degrades to the pre-LX-1 behavior (possible
     * unresolved-GID warning in the guest) with a loud log — a cosmetic
     * group-name gap must never block a session.
     *
     * Returns null on success (or on nothing-to-do), or a user-meaningful
     * failure message.
     */
    fun ensureGuestGroups(rootfs: File, workspaceId: String): String? {
        val groupFile = File(File(rootfs, "etc"), GROUP_FILE_NAME)
        if (!groupFile.isFile) {
            logDebugEvent(workspaceId, "no /etc/group in rootfs — supplementary group reconciliation skipped")
            return null
        }
        val idBin = bootstrapManager.resolveBinary(GROUP_ID_APPLET)
        if (!idBin.isFile) {
            val msg = "guest group reconciliation skipped: ${idBin.absolutePath} is unavailable"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            return null
        }

        // Live supplementary group set — the same host-process `id -G` the
        // installer fixup used, so the mirror source never changes meaning.
        // P0-1: read once per process (see [hostSupplementaryGidCache]);
        // callers still reconcile this workspace's /etc/group on every pass.
        val liveGids = liveHostSupplementaryGids(idBin, workspaceId)
        if (liveGids == null) {
            val msg = "guest group reconciliation skipped: 'id -G' failed — guest group db left untouched"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            return null
        }
        // §1.65 whitelist reuse: digits-only tokens only reach /etc/group.
        if (liveGids.isEmpty()) {
            logDebugEvent(workspaceId, "guest group reconciliation: host reported no supplementary groups")
            return null
        }

        val existing = groupFile.readText()
        val knownGids = existing.lines().mapNotNull { line ->
            val gid = line.split(':').getOrNull(2)?.trim()
            gid?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        }.toSet()
        val missing = liveGids
            .filter { it !in knownGids }
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        if (missing.isEmpty()) {
            logDebugEvent(workspaceId, "guest /etc/group already covers all host supplementary groups (${liveGids.size} gid(s))")
            return null
        }

        // Mirrored account name for the membership field, derived exactly the
        // way the installer fixup derives it (§1.64/§1.65).
        val membership = deriveMembershipSuffix(idBin, workspaceId)

        val additions = missing.joinToString(separator = "") { gid ->
            "aid_$gid:x:$gid:$membership\n"
        }
        val newGroupContent = existing.ensureTrailingNewline() + additions
        val etcDir = groupFile.parentFile
        try {
            val tmp = File(etcDir, ".$GROUP_FILE_NAME.linexa-tmp")
            tmp.writeText(newGroupContent)
            if (!tmp.renameTo(groupFile)) {
                tmp.delete()
                val msg = "guest /etc/group reconciliation failed: could not promote the rewritten file at ${groupFile.absolutePath}"
                Logger.e(TAG, msg)
                logEvent(workspaceId, msg)
                return msg
            }
        } catch (e: Exception) {
            val msg = "guest /etc/group reconciliation failed for ${groupFile.absolutePath}: ${e.message}"
            Logger.e(TAG, msg)
            logEvent(workspaceId, msg)
            return msg
        }

        // Mirror into gshadow the same way the fixup does — only when the
        // rootfs ships one (the group half above is the load-bearing one).
        val gshadow = File(etcDir, GSHADOW_FILE_NAME)
        if (gshadow.isFile) {
            try {
                val gshadowAdditions = missing.joinToString(separator = "") { gid ->
                    "aid_$gid:*::$membership\n"
                }
                val tmp = File(etcDir, ".$GSHADOW_FILE_NAME.linexa-tmp")
                tmp.writeText(gshadow.readText().ensureTrailingNewline() + gshadowAdditions)
                if (!tmp.renameTo(gshadow)) {
                    tmp.delete()
                    Logger.w(TAG, "guest /etc/gshadow reconciliation failed at ${gshadow.absolutePath} — group half already applied")
                    logEvent(workspaceId, "gshadow rename FAILED: ${gshadow.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "guest /etc/gshadow reconciliation failed: ${e.message} — group half already applied")
                logEvent(workspaceId, "gshadow write FAILED: ${e.message}")
            }
        }

        val notice = "guest /etc/group reconciled: appended ${missing.size} missing supplementary group " +
            "entr(y/ies) (${missing.joinToString(" ") { "aid_$it" }}) — host group set drifted since install (LX-1)"
        Logger.i(TAG, notice)
        logEvent(workspaceId, notice)
        return null
    }

    /**
     * 2026-08-31 (terminal appearance): provisions the guest terminal's
     * DEFAULT color scheme — `{rootfs}/root/.termux/colors.properties` — when
     * the workspace ships none.
     *
     * ## Root cause this repairs (proven on device, 2026-08-31)
     *
     * The vendored terminal stack renders the emulator's OWN palette:
     * `TerminalView.onDraw` fills the canvas with
     * `mCurrentColors[COLOR_INDEX_BACKGROUND]`, and the emulator copies the
     * static `TerminalColors.COLOR_SCHEME` at construction.
     * `TermuxPtySession` applies `<rootfs><HOME>/.termux/colors.properties`
     * into that scheme before constructing the session — but NOTHING ever
     * created that file, so every session started from the vendored
     * upstream default scheme: background `#000000`, foreground white. A
     * fresh shell prints only its prompt, the rest of the buffer is
     * default-background cells the renderer skips, and the visible result
     * is the "terminal area is almost completely black / dead" state
     * reported on device (pixel-verified: the area is exactly #000000 with
     * a white prompt + cursor drawn by the TerminalView itself — NOT a
     * Compose overlay, NOT a z-order or layout failure).
     *
     * ## Ownership boundary (deliberate)
     *
     * The colors stay TERMINAL-owned per the Haven ⇄ terminal separation
     * contract (HavenTerminalColorSeparationSourceTest): the default is
     * delivered as a Termux-format `colors.properties` file inside the
     * guest — the exact configuration surface `TermuxPtySession` and OSC
     * 4/10/11/12 already honor — never as a Haven theme token, never as a
     * hardcoded color in the view. The values match the pinned fixture in
     * `TerminalColorSchemePropertiesTest` (the concept's always-dark
     * terminal palette: `--term` background, terminal text, white cursor).
     *
     * ## Policy
     *
     *  - Write-once, never overwrite: an existing file (user-authored or
     *    user-edited) is left byte-for-byte alone — this step only fills
     *    the "workspace provides no terminal scheme" gap.
     *  - Fail-open: a failed write must never block a session — the guest
     *    just keeps the pre-fix vendored default (black), which is the
     *    exact pre-fix behavior, not a new failure mode.
     *  - Standard home only: every workspace terminal launches with
     *    `HOME=/root` (PRootLauncher.DEFAULT_GUEST_HOME; the launch spec
     *    has no working-directory override), so the file is provisioned at
     *    `{rootfs}/root/.termux/colors.properties` — the exact path
     *    `TermuxPtySession` reads. An image that deliberately overrides
     *    HOME via config.yaml owns its terminal configuration surface.
     *
     * Returns null on success (or nothing-to-do), or a failure message.
     */
    fun ensureGuestTerminalColors(
        rootfs: File,
        workspaceId: String,
        guestHomeDir: String = GUEST_TERMINAL_HOME_DIR
    ): String? {
        // Issue #1 (2026-09-03): the scheme lives in the SESSION's home —
        // the launch spec's HOME (now the linexa account's /home/linexa),
        // which is exactly the path TermuxPtySession reads. The historical
        // /root location keeps working as the default for root-launched
        // diagnostics; an existing file at either location is never
        // clobbered (terminal-owned file, write-once).
        val homeRelative = guestHomeDir.trim('/').ifEmpty { GUEST_TERMINAL_HOME_DIR }
        val colorsFile = File(rootfs, "$homeRelative/.termux/$TERMINAL_COLORS_FILE_NAME")
        val legacyFile = File(rootfs, TERMINAL_COLORS_RELATIVE_PATH)
        if (colorsFile.isFile) {
            logDebugEvent(workspaceId, "terminal colors.properties already present — leaving terminal-owned config untouched")
            return null
        }
        // Migration: a workspace provisioned before the linexa account
        // (scheme at /root/.termux) keeps its scheme — the file moves with
        // the session home, honoring write-once (a copy, never an overwrite
        // of anything that exists).
        if (homeRelative != GUEST_TERMINAL_HOME_DIR && legacyFile.isFile) {
            try {
                colorsFile.parentFile?.let { parent ->
                    if (!parent.isDirectory) java.nio.file.Files.createDirectories(parent.toPath())
                }
                legacyFile.copyTo(colorsFile, overwrite = false)
                logDebugEvent(workspaceId, "terminal colors.properties migrated to the session home")
                return null
            } catch (e: Exception) {
                Logger.w(TAG, "terminal colors migration failed: ${e.message} — provisioning the default instead")
            }
        }
        try {
            colorsFile.parentFile?.let { parent ->
                if (!parent.isDirectory) java.nio.file.Files.createDirectories(parent.toPath())
            }
            val tmp = File(colorsFile.parentFile, ".$TERMINAL_COLORS_FILE_NAME.linexa-tmp")
            tmp.writeText(DEFAULT_TERMINAL_COLORS)
            if (!tmp.renameTo(colorsFile)) {
                tmp.delete()
                val msg = "terminal colors provisioning failed: could not promote ${colorsFile.absolutePath}"
                Logger.w(TAG, msg)
                logEvent(workspaceId, msg)
                return msg
            }
        } catch (e: Exception) {
            val msg = "terminal colors provisioning failed for ${colorsFile.absolutePath}: ${e.message}"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            return msg
        }
        Logger.i(TAG, "guest terminal color scheme provisioned (${colorsFile.absolutePath})")
        logEvent(workspaceId, "guest terminal colors.properties provisioned at ${colorsFile.absolutePath}")
        return null
    }

    /**
     * Issue #4 (2026-09-03) — creates (and seeds) the workspace's dedicated
     * guest workspace directory, the directory interactive terminals open in.
     *
     * ## Why a managed directory at all
     *
     * The terminal used to open in `/root` — a system directory where the
     * user's project files, scripts and Linexa-managed content mixed with
     * root's own dotfiles. The declared layout (see GuestEnvironmentResolver)
     * puts that content in a dedicated workspace (e.g. `/home/linexa`), and
     * THIS function is what makes the declaration real on disk: `proot -w`
     * into a directory that does not exist fails the whole session with a
     * cryptic shell-init error, so the directory must exist BEFORE launch.
     *
     * ## Policy
     *
     *  - Idempotent: an existing directory is left untouched (only the
     *    Linexa README below is byte-compared like every other managed
     *    write, so a user-edited README survives... by being rewritten only
     *    when it still matches a previous managed version — see the marker).
     *  - Fail-soft: returns a failure MESSAGE instead of throwing; the
     *    caller decides — the session path treats a failed workspace as
     *    fatal only when even the home fallback cannot be created, and falls
     *    back to the home directory otherwise (a launch must never fail over
     *    a mkdir when a usable directory exists).
     *  - 0755 + a short README.md naming the directory as Linexa-managed, so
     *    a user exploring `/home` understands what they are looking at.
     *
     * @param guestDirectory absolute guest path of the workspace
     *   (e.g. `/home/linexa`). Invalid paths return a failure message — the
     *   resolver never produces those, but a runtime-provided value could.
     */
    fun ensureGuestWorkspace(rootfs: File, workspaceId: String, guestDirectory: String): String? {
        // Structural sanity (absolute, no traversal). The shape rule lives in
        // the metadata validator; this is the last-line defense before the
        // path reaches java.io.File(rootfs, ...).
        if (!guestDirectory.startsWith("/") || guestDirectory.split('/').any { it == ".." }) {
            val msg = "invalid guest workspace directory '$guestDirectory' for $workspaceId"
            Logger.w(TAG, msg)
            return msg
        }
        val dir = File(rootfs, guestDirectory.removePrefix("/"))
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                val msg = "could not create guest workspace directory ${dir.absolutePath}"
                Logger.w(TAG, msg)
                logEvent(workspaceId, msg)
                return msg
            }
            val readme = File(dir, WORKSPACE_README_NAME)
            // Write-once (the same policy as the terminal colors file): the
            // README is documentation FOR the user, and a user who replaced
            // it owns that file from then on — the ensure pass must never
            // clobber their edit back to the managed text.
            if (!readme.isFile) {
                val tmp = File(dir, ".$WORKSPACE_README_NAME.linexa-tmp")
                tmp.writeBytes(WORKSPACE_README_BYTES)
                if (!tmp.renameTo(readme)) {
                    readme.delete()
                    if (!tmp.renameTo(readme)) tmp.delete()
                    // Non-fatal: the README is documentation, not structure.
                    Logger.w(TAG, "workspace README write failed at ${readme.absolutePath} — continuing")
                }
            }
            logDebugEvent(workspaceId, "guest workspace ready at $guestDirectory")
            null
        } catch (e: Exception) {
            val msg = "guest workspace preparation failed for $workspaceId: ${e.message}"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            msg
        }
    }

    /**
     * Issue #7 (2026-09-03) — provisions the Termux-like shell convenience
     * set (aliases) into the guest at `/etc/profile.d/00-linexa-shell.sh`.
     *
     * ## Why this exists
     *
     * `la` worked on Ubuntu but not Debian: Ubuntu's stock `/root/.bashrc`
     * happens to define `la`/`ll`, Debian's minimal rootfs does not. The
     * aliases are shell configuration, not executables — "just ship a binary"
     * would be faking it. The honest fix is a real alias file, sourced by
     * every LOGIN shell (terminals launch `bash --login`, so /etc/profile →
     * profile.d applies), never by scripts (non-interactive shells do not
     * source it, so nothing behavioral changes for automation).
     *
     * ## Policy
     *
     *  - Managed-file discipline: written only when absent or when the
     *    current content still matches a PREVIOUS Linexa-managed version is
     *    not tracked byte-history — instead the file carries a Linexa marker
     *    header; a file WITHOUT the marker is not ours and is left alone
     *    (never clobber a distro/user file planted at the same path).
     *  - Declared aliases ([aliases]) are merged OVER the built-in defaults,
     *    so an image can add to or refine the set without re-declaring it.
     *  - Alias expansions are single-quoted with `'\''` escaping — the same
     *    discipline as the diagnostics shellQuote — so a declared expansion
     *    can never break out of its quoting.
     *  - Fail-open: a failed write logs and returns a message; the shell
     *    simply keeps the distribution's own config (the pre-fix behavior).
     */
    fun ensureGuestShellConfig(rootfs: File, workspaceId: String, aliases: Map<String, String>): String? {
        val profileDir = File(rootfs, "etc/profile.d")
        if (!profileDir.isDirectory && !profileDir.mkdirs()) {
            // Not a POSIX-layout rootfs (no /etc) — nothing to configure.
            logDebugEvent(workspaceId, "shell config skipped: no /etc/profile.d in rootfs")
            return null
        }
        return try {
            val target = File(profileDir, SHELL_CONFIG_FILE_NAME)
            val content = buildShellConfigScript(aliases)
            if (!target.isFile) {
                writeFileAtomically(target, content, workspaceId)
            } else {
                val current = target.readText()
                when {
                    current == content -> Unit // byte-equal: nothing to do
                    current.startsWith(SHELL_CONFIG_MARKER) -> writeFileAtomically(target, content, workspaceId)
                    else -> logDebugEvent(
                        workspaceId,
                        "shell config: ${target.absolutePath} exists but is not Linexa-managed — leaving it alone"
                    )
                }
            }
            null
        } catch (e: Exception) {
            val msg = "guest shell config provisioning failed for $workspaceId: ${e.message}"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            msg
        }
    }

    /** The /etc/profile.d script body for the given alias map. */
    private fun buildShellConfigScript(aliases: Map<String, String>): String = buildString {
        append(SHELL_CONFIG_MARKER)
        append("# Sourced by login shells only (terminals launch '<shell> --login').\n")
        append("# Aliases never affect scripts; the user's own ~/.bashrc is sourced\n")
        append("# AFTER /etc/profile, so anything defined there wins.\n")
        for ((name, expansion) in aliases) {
            append("alias ${name}='${expansion.replace("'", "'\\''")}'\n")
        }
    }

    private fun writeFileAtomically(target: File, content: String, workspaceId: String) {
        val tmp = File(target.parentFile, ".${target.name}.linexa-tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed for ${target.absolutePath}")
            }
        }
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                target.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ
                )
            )
        }
        logDebugEvent(workspaceId, "shell config provisioned at ${target.absolutePath}")
    }

    // ── Issue #1 (2026-09-03): the real `linexa` account ─────────────────

    /**
     * Guarantees the workspace's normal-user account exists and returns the
     * [GuestIdentity] interactive sessions launch with.
     *
     * ## Why this exists
     *
     * The requirement is `linexa@localhost:~$` as the REAL terminal
     * behavior: `id -u` answering 1000, `whoami` answering `linexa`, `~`
     * resolving to `/home/linexa`, and the bash prompt's `\u` resolving
     * through `/etc/passwd`. PRoot can fake the identity
     * (`--change-id=1000:1000`) but cannot invent the passwd entry —
     * without `linexa:x:1000:1000:...:/home/linexa:/bin/bash` in
     * `/etc/passwd`, `whoami` would print "I have no name!" and the
     * prompt would degrade. THIS function is what makes the account real,
     * host-side, with the same file discipline as every other repair
     * (atomic tmp+rename, no guest exec needed, idempotent).
     *
     * ## Resolution order
     *
     *  1. `root` requested → the root identity (`-0`), unchanged behavior.
     *  2. No `/etc/passwd` → not a recognizable Debian-family layout →
     *     root identity (fail-open; a launch must never fail here).
     *  3. [preferredUser] present in passwd → its OWN record wins
     *     (uid/gid/home/shell) — metadata picks the name, the account
     *     database owns the details. Home-directory/`~`-project ensures
     *     still run so the home is usable.
     *  4. Otherwise the account is CREATED: first free uid/gid ≥ 1000
     *     (imported Ubuntu images ship a stock `ubuntu` user at 1000 —
     *     Linexa never collides, it takes the next pair), passwd/group/
     *     shadow entries, home + `/etc/skel` seeds, `sudo` group
     *     membership when that group exists, and the privilege-bridge
     *     provisioning from [ensureGuestPrivilegeBridge].
     *
     * Failure policy: throws nothing — any account-file failure logs and
     * degrades to the root identity (a root shell beats no shell), except
     * where noted.
     */
    fun ensureGuestUser(
        rootfs: File,
        workspaceId: String,
        preferredUser: String,
        preferredHome: String?
    ): GuestIdentity {
        if (preferredUser == "root") return GuestIdentity.root()

        // Arch/Fedora archives restore `/` as 0555. Repair it here as well as
        // in ensureGuestTmp so the install-time account fixup and the legacy
        // home migration can create `.linexa` directly under the rootfs.
        ensureGuestRootfsWritable(rootfs, workspaceId)?.let { message ->
            Logger.w(TAG, "guest rootfs write repair failed for $workspaceId — continuing: $message")
        }

        val passwd = File(File(rootfs, "etc"), PASSWD_FILE_NAME)
        if (!passwd.isFile) {
            logDebugEvent(workspaceId, "no /etc/passwd in rootfs — guest user provisioning skipped, launching as root")
            return GuestIdentity.root()
        }

        // 2026-09-05: complete the /home/tiny → /home/linexa migration ON the
        // workspace's rootfs (both the passwd record and the on-disk data)
        // BEFORE the account is resolved or created, so every later step —
        // the identity, HOME, the desktop session, the VNC keyfile — sees the
        // canonical home. See [migrateLegacyTinyHome] for the policy.
        migrateLegacyTinyHome(rootfs, workspaceId, preferredUser, preferredHome)

        // 2026-11-13 unified home architecture: inspect the account before
        // assuming anything. A normal account whose home is `/root` (a broken
        // import or an old hand-edited image) is the one case where the passwd
        // record is repaired to the normal-user home. Custom homes such as
        // `/srv/home` are respected; root's own record is never touched.
        repairNormalUserHome(rootfs, workspaceId, preferredUser, preferredHome)

        GuestIdentity.resolveNamedUser(rootfs, preferredUser)?.let { existing ->
            ensureGuestHome(rootfs, workspaceId, existing)
            ensureGuestUserHome(rootfs, workspaceId, existing)
            ensureGuestPrivilegeBridge(rootfs, workspaceId, existing)
            reportAmbiguousRootHomeData(rootfs, workspaceId, existing)
            logDebugEvent(
                workspaceId,
                "guest user '${existing.name}' resolved from passwd (uid=${existing.uid} gid=${existing.gid} home=${existing.home})"
            )
            return existing
        }

        // Create the account. Name rules mirror the validator's identifier
        // rule; anything outside it stays a root launch rather than a
        // corrupted account file.
        if (!preferredUser.matches(ACCOUNT_NAME_REGEX) || preferredUser.length > MAX_ACCOUNT_NAME_CHARS) {
            Logger.w(TAG, "guest user '$preferredUser' is not a safe account name — launching as root")
            logEvent(workspaceId, "unsafe guest user name '$preferredUser' — user account skipped")
            return GuestIdentity.root()
        }

        return try {
            val uidGid = GuestIdentity.firstFreeUidGid(rootfs, DEFAULT_FIRST_UID)
            val home = (preferredHome?.takeIf { it.startsWith("/") && !it.endsWith("/") && it != "/" }
                ?: "${GuestIdentity.DEFAULT_USER_HOME.removeSuffix("/${GuestIdentity.DEFAULT_USER_NAME}")}/$preferredUser")
            val shell = resolveGuestShellPath(rootfs)

            appendAccountLine(passwd, workspaceId) { existing ->
                existing.ensureTrailingNewline() +
                    "$preferredUser:x:$uidGid:$uidGid:$ACCOUNT_GECOS:$home:$shell\n"
            }
            val shadow = File(File(rootfs, "etc"), SHADOW_FILE_NAME)
            if (shadow.isFile) {
                appendAccountLine(shadow, workspaceId) { existing ->
                    existing.ensureTrailingNewline() +
                        "$preferredUser:*:19700:0:99999:7:::\n"
                }
            }
            appendAccountLine(File(File(rootfs, "etc"), GROUP_FILE_NAME), workspaceId) { existing ->
                buildString {
                    // Join the distro's own sudo group when it exists (both
                    // Debian and Ubuntu ship one) so sudo tooling that checks
                    // group membership sees the user as an administrator — the
                    // sudo line is REPLACED in place, never duplicated.
                    val sudoLine = existing.lines().firstOrNull { it.startsWith("sudo:") }
                    if (sudoLine == null) {
                        append(existing.ensureTrailingNewline())
                    } else {
                        val members = sudoLine.split(':').getOrNull(3)
                            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        // A group line is name:password:gid:members — append to
                        // the MEMBERS field, whether or not it already has any.
                        val updated = if (preferredUser in members) sudoLine
                        else if (sudoLine.endsWith(":")) "$sudoLine$preferredUser"
                        else "$sudoLine,$preferredUser"
                        val replaced = existing.lines()
                            .joinToString("\n") { if (it == sudoLine) updated else it }
                        append(replaced.ensureTrailingNewline())
                    }
                    append("$preferredUser:x:$uidGid:\n")
                }
            }

            val identity = GuestIdentity(
                name = preferredUser,
                uid = uidGid,
                gid = uidGid,
                home = home,
                shell = shell,
                isRoot = false
            )
            ensureGuestHome(rootfs, workspaceId, identity)
            ensureGuestUserHome(rootfs, workspaceId, identity)
            ensureGuestPrivilegeBridge(rootfs, workspaceId, identity)
            reportAmbiguousRootHomeData(rootfs, workspaceId, identity)
            val notice = "guest user '$preferredUser' created (uid=$uidGid gid=$uidGid home=$home shell=$shell)"
            Logger.i(TAG, notice)
            logEvent(workspaceId, notice)
            identity
        } catch (e: Exception) {
            Logger.e(TAG, "guest user creation failed for $workspaceId — launching as root", e)
            logEvent(workspaceId, "guest user creation failed: ${e.message} — launching as root")
            GuestIdentity.root()
        }
    }

    /**
     * 2026-11-13 — unified Workspace home architecture.
     *
     * Creates the nine standard directories (`Desktop`, `Documents`,
     * `Downloads`, `Music`, `Pictures`, `Photos`, `Public`, `Templates`,
     * `Videos`) and the eight-entry XDG `user-dirs.dirs` under the account's
     * ACTUAL home. The account record is the source of truth for the path and
     * the uid/gid — never a hardcoded `1000`, because imported Ubuntu images
     * may already own uid 1000 and get linexa at 1001 (verified on device).
     *
     * Idempotent and non-destructive: [WorkspaceHomeLayout.provision] leaves
     * existing directories, files and symlinks alone, preserves user data and
     * only repairs the eight managed XDG keys. A second call on a healthy home
     * performs no writes.
     *
     * Host-side ownership is the app uid, which is the only owner a non-root
     * Android process can create; PRoot's fake-id layer maps it to
     * [GuestIdentity.uid]/[GuestIdentity.gid] inside the session. Created
     * directories are 0755 and `user-dirs.dirs` is 0644, so the mapped identity
     * can traverse and update them. The log line records the real uid/gid used.
     *
     * Fail-open: returns null even when individual entries are blocked (a file
     * where a directory should be, a symlink, a read-only `.config`); the
     * blocked names are logged and left untouched so a launch never fails over
     * a cosmetic home issue.
     */
    fun ensureGuestUserHome(
        rootfs: File,
        workspaceId: String,
        identity: GuestIdentity
    ): String? {
        if (identity.isRoot) return null
        val home = WorkspaceHomeLayout.usableNormalHome(identity.home)
            ?: return "guest home '${identity.home}' is not a usable normal-user home"
        val homeDir = File(rootfs, home.removePrefix("/"))
        if (!homeDir.startsWith(rootfs) || !homeDir.isDirectory || isSymlink(homeDir)) {
            return "guest home '$home' is not a real directory inside the rootfs"
        }

        return try {
            val report = WorkspaceHomeLayout.provision(homeDir)
            if (report.changed) {
                val notice = buildString {
                    append("guest home provisioned: ").append(home)
                    append(" uid=").append(identity.uid).append(" gid=").append(identity.gid)
                    if (report.createdDirectories.isNotEmpty()) {
                        append(" created=").append(report.createdDirectories.joinToString(","))
                    }
                    if (report.configDirectoryCreated) append(" .config=created")
                    if (report.userDirsCreated) append(" user-dirs.dirs=created")
                    if (report.userDirsUpdated) append(" user-dirs.dirs=updated")
                }
                Logger.i(TAG, notice)
                logEvent(workspaceId, notice)
            } else {
                logDebugEvent(
                    workspaceId,
                    "guest home already complete: $home (uid=${identity.uid} gid=${identity.gid})"
                )
            }
            if (report.blockedEntries.isNotEmpty()) {
                val warning = "guest home entries left untouched in $home: ${report.blockedEntries.joinToString(",")}"
                Logger.w(TAG, warning)
                logEvent(workspaceId, warning)
            }
            null
        } catch (e: Exception) {
            val message = "guest home provisioning failed for $home: ${e.message}"
            Logger.w(TAG, message)
            logEvent(workspaceId, message)
            message
        }
    }

    /**
     * Rewrites a NORMAL account's home only when the existing passwd value is
     * unusable: `/root` (the root account's home), `/`, a relative path, or a
     * traversal path. A legitimate custom home (`/srv/home`, `/home/tiny` after
     * migration) is left exactly as the account database declares it.
     *
     * Root's own record is never read for mutation here.
     */
    private fun repairNormalUserHome(
        rootfs: File,
        workspaceId: String,
        preferredUser: String,
        preferredHome: String?
    ) {
        val passwd = File(File(rootfs, "etc"), PASSWD_FILE_NAME)
        if (!passwd.isFile) return
        val record = GuestIdentity.readPasswd(rootfs).firstOrNull { it.name == preferredUser } ?: return
        if (record.uid == 0) return
        if (WorkspaceHomeLayout.usableNormalHome(record.home) != null) return

        val canonical = WorkspaceHomeLayout.usableNormalHome(preferredHome)
            ?: "${GuestIdentity.DEFAULT_USER_HOME.removeSuffix("/${GuestIdentity.DEFAULT_USER_NAME}")}/$preferredUser"
        try {
            appendAccountLine(passwd, workspaceId) { existing ->
                existing.lines().joinToString("\n") { line ->
                    val fields = line.split(":")
                    if (fields.size >= 7 && fields[0] == preferredUser && fields[2].toIntOrNull() != 0) {
                        (fields.slice(0..4) + canonical + fields.slice(6 until fields.size))
                            .joinToString(":")
                    } else {
                        line
                    }
                }.let { if (it.endsWith("\n")) it else "$it\n" }
            }
            val notice = "guest home repaired: '${record.home}' → $canonical " +
                "(uid=${record.uid} gid=${record.gid}); /root data left in place"
            Logger.i(TAG, notice)
            logEvent(workspaceId, notice)
        } catch (e: Exception) {
            Logger.w(TAG, "guest home repair failed for '$preferredUser' — leaving passwd as found: ${e.message}")
            logEvent(workspaceId, "guest home repair failed: ${e.message}")
        }
    }

    /**
     * Reports possible user data under `/root` for a normal-user workspace.
     *
     * Per the migration policy this function NEVER moves or deletes anything:
     * ownership and intent cannot be established safely from the host, so the
     * files stay in place and the finding is logged for the user. Stock root
     * dotfiles are not reported (see [WorkspaceHomeLayout.unexpectedRootHomeEntries]).
     *
     * Returns the unexpected entry names (also useful to diagnostics/tests).
     */
    fun reportAmbiguousRootHomeData(
        rootfs: File,
        workspaceId: String,
        identity: GuestIdentity
    ): List<String> {
        if (identity.isRoot) return emptyList()
        val unexpected = WorkspaceHomeLayout.unexpectedRootHomeEntries(rootfs)
        if (unexpected.isNotEmpty()) {
            val notice = "possible user data under /root left in place — not migrated automatically: " +
                unexpected.joinToString(",")
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
        }
        return unexpected
    }

    /**
     * 2026-09-05 — completes the project's /home/tiny → /home/linexa rename on the workspace's rootfs (the legacy home was /home/tiny). The source-side rename left two kinds
     * of drift on EXISTING workspaces, and both are the "desktop resets /
     * files went missing after the update" defect:
     *
     *  1. an `/etc/passwd` record whose home field still says `/home/tiny`
     *     — the launcher's `HOME` follows the account record, so the runtime
     *     silently kept using the legacy path (the exact "silent fallback to
     *     /home/tiny" the migration forbids);
     *  2. the user's data — xfconf/xfce4 and GTK settings, terminal configs,
     *     dotfiles — physically under `/home/tiny`, unreachable from a
     *     session whose HOME is now `/home/linexa`.
     *
     * ## Policy (conservative, one-shot, never destructive)
     *
     *  - **Marker-gated, once per rootfs:** `.linexa/home-migration-tiny.done`
     *    records completion; afterwards this function is a file-exists check.
     *  - **Passwd first:** the preferred account's record, when its home field
     *    points at the legacy path, is rewritten to the canonical home
     *    ([preferredHome], defaulting to `GuestIdentity.DEFAULT_USER_HOME`) —
     *    atomically, through [appendAccountLine]. No OTHER record is touched:
     *    another user whose home happens to be `/home/tiny` (a legacy `tiny`
     *    account kept for reference) is not renamed by proxy.
     *  - **Data second:** when the legacy directory exists (a real directory
     *    inside the rootfs — a symlink is refused, same discipline as
     *    [ensureGuestTmp]):
     *      - canonical home ABSENT → the legacy home is RENAMED into place.
     *        One move, every file follows, nothing is copied or dropped.
     *      - canonical home PRESENT → top-level entries are moved in ONLY
     *        where the target has no counterpart (the canonical home wins
     *        every collision — it is the home the sessions have been writing
     *        to). Entries that cannot move stay in place; a fully emptied
     *        legacy directory is removed, otherwise it is left as found.
     *  - **No silent fallback:** nothing in the runtime ever reads /home/tiny
     *    again — the migration MOVES data to the canonical home rather than
     *    teaching consumers about the legacy path.
     *  - **Fail-open:** every failure logs and leaves the rootfs as found; a
     *    workspace whose migration cannot run still launches (with the same
     *    home the previous build used), and the marker stays unwritten so the
     *    next launch retries.
     */
    fun migrateLegacyTinyHome(
        rootfs: File,
        workspaceId: String,
        preferredUser: String,
        preferredHome: String?
    ) {
        val canonicalHome = preferredHome?.takeIf { it.startsWith("/") && it != "/" }?.trimEnd('/')
            ?: GuestIdentity.DEFAULT_USER_HOME
        if (canonicalHome == LEGACY_TINY_HOME) return

        val markerDir = File(rootfs, LEGACY_MIGRATION_MARKER_DIR)
        val marker = File(markerDir, LEGACY_MIGRATION_MARKER_FILE)
        if (marker.isFile) return

        try {
            val passwd = File(File(rootfs, "etc"), PASSWD_FILE_NAME)
            if (passwd.isFile) {
                val record = GuestIdentity.readPasswd(rootfs).firstOrNull { it.name == preferredUser }
                if (record != null && record.home.trimEnd('/') == LEGACY_TINY_HOME) {
                    appendAccountLine(passwd, workspaceId) { existing ->
                        existing.lines().joinToString("\n") { line ->
                            val fields = line.split(":")
                            if (fields.size >= 7 && fields[0] == preferredUser) {
                                (fields.slice(0..4) + canonicalHome + fields.slice(6 until fields.size))
                                    .joinToString(":")
                            } else line
                        }.let { if (it.endsWith("\n")) it else "$it\n" }
                    }
                    Logger.i(
                        TAG,
                        "home migration: passwd record for '$preferredUser' moved $LEGACY_TINY_HOME → $canonicalHome"
                    )
                    logEvent(workspaceId, "guest home migrated: $LEGACY_TINY_HOME → $canonicalHome (passwd)")
                }
            }

            val legacyDir = File(rootfs, LEGACY_TINY_HOME.removePrefix("/"))
            val canonicalDir = File(rootfs, canonicalHome.removePrefix("/"))
            if (legacyDir.isDirectory && !isSymlink(legacyDir) &&
                canonicalDir.startsWith(rootfs) && legacyDir.startsWith(rootfs)
            ) {
                if (!canonicalDir.exists()) {
                    markerDir.mkdirs()
                    if (legacyDir.renameTo(canonicalDir)) {
                        marker.writeText("moved $LEGACY_TINY_HOME → $canonicalHome\n")
                        Logger.i(TAG, "home migration: $LEGACY_TINY_HOME renamed to ${canonicalDir.absolutePath}")
                        logEvent(workspaceId, "guest home data migrated: $LEGACY_TINY_HOME → $canonicalHome")
                        return
                    }
                    Logger.w(TAG, "home migration: rename of $LEGACY_TINY_HOME failed — attempting entry-wise move")
                }
                // Canonical home already exists (or the rename failed): merge
                // non-conflicting entries; the canonical home wins collisions.
                markerDir.mkdirs()
                var moved = 0
                var kept = 0
                legacyDir.listFiles()?.forEach { entry ->
                    val target = File(canonicalDir, entry.name)
                    if (target.exists()) {
                        kept++
                        return@forEach
                    }
                    if (entry.renameTo(target)) moved++ else kept++
                }
                if (legacyDir.list()?.isEmpty() == true) legacyDir.delete()
                marker.writeText("merged $LEGACY_TINY_HOME → $canonicalHome (moved=$moved kept=$kept)\n")
                Logger.i(TAG, "home migration: merged $LEGACY_TINY_HOME into ${canonicalDir.absolutePath} (moved=$moved kept=$kept)")
                logEvent(workspaceId, "guest home data merged: $LEGACY_TINY_HOME → $canonicalHome (moved=$moved kept=$kept)")
            } else {
                // Nothing to migrate on disk — record the pass so the check is
                // once-per-rootfs, not once-per-launch.
                markerDir.mkdirs()
                marker.writeText("no $LEGACY_TINY_HOME data present\n")
            }
        } catch (e: Exception) {
            // Fail-open: the workspace launches with the home it has; the
            // marker is left unwritten so the next launch retries.
            Logger.w(TAG, "home migration failed for $workspaceId — leaving rootfs as found: ${e.message}")
            logEvent(workspaceId, "guest home migration failed: ${e.message}")
        }
    }

    /**
     * NIO-free symlink check (Android's java.nio lacks full PosixFileAttributes).
     * Used by [migrateLegacyTinyHome] to refuse migrating through a symlink.
     */
    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Creates the account home (idempotent) and seeds it from `/etc/skel` on
     * first creation. Issue #2 (2026-09-04): the `~/linexa` project
     * directory is NO LONGER created — the terminal and the desktop open in
     * the home itself (`linexa@localhost:~$` → `/home/linexa`), and a
     * "linexa" folder inside the linexa home was exactly what the requirement
     * forbade. A leftover folder from a workspace provisioned by an older
     * build is cleaned up by [removeStaleProjectDirectory]. Kernel
     * ownership is the app uid (it owns every rootfs file); the guest's
     * faked stat maps the app uid to the session identity, so the
     * directory shows as user-owned inside the workspace.
     */
    private fun ensureGuestHome(rootfs: File, workspaceId: String, identity: GuestIdentity) {
        if (identity.isRoot) return
        val homeDir = File(rootfs, identity.home.removePrefix("/"))
        if (!homeDir.startsWith(rootfs)) return
        try {
            val created = if (!homeDir.isDirectory) homeDir.mkdirs() else false
            if (created) copySkel(rootfs, homeDir, workspaceId)
            // Issue #2: no more `~/linexa` — and sweep away the stale one an
            // older build may have left behind (safe, content-checked).
            removeStaleProjectDirectory(homeDir, workspaceId)
        } catch (e: Exception) {
            Logger.w(TAG, "guest home preparation failed for ${identity.home}: ${e.message}")
            logEvent(workspaceId, "guest home preparation failed: ${e.message}")
        }
    }

    /**
     * Issue #2 (2026-09-04) — removes a leftover `~/linexa` directory that
     * [ensureGuestHome] of OLDER builds created as the "project directory"
     * (and that, as a declared `linexa.workspace.directory`, was the
     * terminal's startup dir — the exact `/home/linexa/linexa` the user
     * reported).
     *
     * The removal is deliberately conservative: the folder is deleted only
     * when EVERY entry inside is app- or session-owned — the managed README
     * this app writes ([WORKSPACE_README_NAME]) or shell history bookkeeping
     * a login shell in that cwd would have produced. Any other content means
     * the user stored real files there and the folder is left untouched (a
     * migration must never destroy user data; the terminal simply no longer
     * OPENS there).
     */
    private fun removeStaleProjectDirectory(homeDir: File, workspaceId: String) {
        val project = File(homeDir, PRootLauncher.DEFAULT_WORKSPACE_DIR_NAME)
        if (!project.isDirectory) return
        // Do not guess from a failed listing: if the entries cannot be read,
        // the folder stays.
        val entries = project.listFiles() ?: return
        val appOwned = setOf(
            WORKSPACE_README_NAME,
            ".bash_history",
            ".lesshst",
            ".python_history",
            ".sqlite_history",
            ".viminfo"
        )
        if (!entries.all { it.name in appOwned }) {
            logDebugEvent(workspaceId, "stale ~/${PRootLauncher.DEFAULT_WORKSPACE_DIR_NAME} kept: it holds non-managed content")
            return
        }
        val removed = project.deleteRecursively()
        if (removed) {
            Logger.i(TAG, "removed stale ~/${PRootLauncher.DEFAULT_WORKSPACE_DIR_NAME} under ${homeDir.absolutePath}")
            logDebugEvent(workspaceId, "stale ~/${PRootLauncher.DEFAULT_WORKSPACE_DIR_NAME} removed")
        } else {
            Logger.w(TAG, "could not remove stale ~/${PRootLauncher.DEFAULT_WORKSPACE_DIR_NAME} under ${homeDir.absolutePath}")
        }
    }

    /** Copies `/etc/skel` dotfiles into a fresh home (best effort, one level deep + subdirs). */
    private fun copySkel(rootfs: File, homeDir: File, workspaceId: String) {
        val skel = File(rootfs, "etc/skel")
        if (!skel.isDirectory) return
        try {
            skel.listFiles()?.forEach { entry ->
                val target = File(homeDir, entry.name)
                if (entry.isDirectory) {
                    entry.copyRecursively(target, overwrite = false)
                } else if (entry.isFile && !target.exists()) {
                    entry.copyTo(target, overwrite = false)
                }
            }
            logDebugEvent(workspaceId, "guest home seeded from /etc/skel (${homeDir.name})")
        } catch (e: Exception) {
            Logger.w(TAG, "skel copy failed: ${e.message}")
        }
    }

    /** `/bin/bash` → `/usr/bin/bash` → `/bin/sh`, first that exists in the rootfs. */
    private fun resolveGuestShellPath(rootfs: File): String =
        listOf("/bin/bash", "/usr/bin/bash", "/bin/sh").firstOrNull { File(rootfs, it.removePrefix("/")).isFile }
            ?: GuestIdentity.DEFAULT_USER_SHELL

    /**
     * Appends to an account database atomically: [edit] produces the full
     * new content from the old, written tmp-first then renamed — an
     * interrupted write can never leave a truncated passwd behind.
     */
    private fun appendAccountLine(
        file: File,
        workspaceId: String,
        edit: (String) -> String
    ) {
        val tmp = File(file.parentFile, ".${file.name}.linexa-tmp")
        tmp.writeText(edit(file.readText()))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("rename failed for ${file.absolutePath}")
        }
        logDebugEvent(workspaceId, "account db updated: ${file.name}")
    }

    /**
     * Provisions the guest's privilege bridge — the `sudo` users expect —
     * plus the account's sudoers entry.
     *
     * ## Honest architecture note (do not "fix" this into a lie)
     *
     * A proot guest has NO real privilege transition: the kernel runs
     * every process under the app's own uid, and proot's fake-id emulation
     * answers `setuid(0)` from an emulated non-root identity with EPERM
     * (fake_id0.c SETXID — upstream behavior, verified in this tree's
     * BUILD-NOTES). A REAL /usr/bin/sudo therefore refuses to start in a
     * user session ("effective uid is not 0"), no matter how sudoers is
     * written.
     *
     * The bridge is the platform's genuine elevation mechanism: in this
     * runtime, system access is a property of the emulated identity the
     * LAUNCHER selects, and Linexa's own privileged operations (package
     * queue, init, repair) are root-emulated execs. The installed pair is:
     *
     *   - `/usr/local/bin/sudo` — a thin wrapper (shadows PATH's
     *     `/usr/bin/sudo`) that execs the helper;
     *   - `/usr/local/bin/linexa-root-exec` — the mailbox client that asks
     *     the app's runtime bridge to run one non-interactive command in a
     *     separate `-0` PRoot process and streams its output back.
     *
     * The command really runs as the emulated root identity; the caller's
     * identity is never faked. Deleting both files restores the distro's
     * real sudo (which then reports its own honest EPERM-based refusal).
     */
    private fun ensureGuestPrivilegeBridge(rootfs: File, workspaceId: String, identity: GuestIdentity) {
        try {
            val localBin = File(rootfs, "usr/local/bin")
            if (!localBin.isDirectory && !localBin.mkdirs()) {
                logDebugEvent(workspaceId, "privilege bridge skipped: no /usr/local/bin")
                return
            }
            installGuestScript(localBin, SUDO_BRIDGE_NAME, SUDO_BRIDGE_SCRIPT, workspaceId)
            installGuestScript(localBin, ROOT_EXEC_HELPER_NAME, ROOT_EXEC_HELPER_SCRIPT, workspaceId)
            // sudoers entry for the account — no-ops with the bridge but
            // keeps real /usr/bin/sudo (distro-shipped) correctly configured
            // for any environment where a privileged transition is possible.
            val sudoersDir = File(rootfs, "etc/sudoers.d")
            if (sudoersDir.isDirectory || File(rootfs, "etc/sudoers").isFile) {
                if (!sudoersDir.isDirectory) sudoersDir.mkdirs()
                val sudoers = File(sudoersDir, identity.name)
                if (!sudoers.isFile) {
                    val tmp = File(sudoersDir, ".${identity.name}.linexa-tmp")
                    tmp.writeText("${identity.name} ALL=(ALL) NOPASSWD:ALL\n")
                    if (tmp.renameTo(sudoers)) {
                        runCatching {
                            Files.setPosixFilePermissions(
                                sudoers.toPath(),
                                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ)
                            )
                        }
                    } else {
                        tmp.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "privilege bridge provisioning failed: ${e.message}")
            logEvent(workspaceId, "privilege bridge provisioning failed: ${e.message}")
        }
    }

    /**
     * Writes one managed guest script idempotently: content compare first,
     * temp-file + rename so a crash never leaves a half script, then an
     * explicit 0755. A symlink at the target is replaced, never written
     * through.
     */
    private fun installGuestScript(
        dir: File,
        name: String,
        contents: String,
        workspaceId: String
    ) {
        val target = File(dir, name)
        val perms = setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
        )
        val alreadyInstalled = target.isFile &&
            !Files.isSymbolicLink(target.toPath()) &&
            runCatching { target.readText() == contents }.getOrDefault(false)
        if (alreadyInstalled) {
            runCatching { Files.setPosixFilePermissions(target.toPath(), perms) }
            return
        }

        val tmp = File(dir, ".$name.linexa-tmp")
        Files.deleteIfExists(tmp.toPath())
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed for ${target.absolutePath}")
        }
        runCatching { Files.setPosixFilePermissions(target.toPath(), perms) }
        logDebugEvent(workspaceId, "privilege bridge provisioned at ${target.absolutePath}")
    }

    /**
     * LX-1: the `root,aid_<user>` membership suffix for a reconciled group
     * entry, mirroring [RootfsFixupService.fixUpAccounts]'s derivation:
     * `id -un` when it yields a whitelisted account-name token, else the
     * numeric uid, else just `root` (the entry stays well-formed and the
     * kernel group set — which is what actually grants access — is
     * unaffected by this cosmetic field either way).
     */
    private fun deriveMembershipSuffix(idBin: File, workspaceId: String): String {
        val rawName = runIdApplet(idBin, listOf("-un"), workspaceId)?.trim().orEmpty()
        val name = rawName.takeIf { isSafeAccountNameToken(it) }
        if (name != null) return "root,aid_$name"
        val rawUid = runIdApplet(idBin, listOf("-u"), workspaceId)?.trim().orEmpty()
        val uid = rawUid.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        return if (uid != null) "root,aid_$uid" else "root"
    }

    /**
     * P0-1: returns the memoized host supplementary GID set, spawning the
     * bundled `id -G` applet only on the first successful read of this
     * process. A failed read returns null and is NOT cached, preserving the
     * fail-open retry contract.
     */
    private fun liveHostSupplementaryGids(idBin: File, workspaceId: String): List<String>? {
        hostSupplementaryGidCache?.let { return it }
        synchronized(hostSupplementaryGidLock) {
            hostSupplementaryGidCache?.let { return it }
            val liveRaw = runIdApplet(idBin, listOf("-G"), workspaceId) ?: return null
            val gids = parseIdOutput(liveRaw, numericOnly = true).first.distinct()
            hostSupplementaryGidCache = gids
            return gids
        }
    }

    /** Test seam: forgets the memoized host GID set (production never calls it). */
    internal fun clearHostSupplementaryGroupCacheForTesting() {
        synchronized(hostSupplementaryGidLock) { hostSupplementaryGidCache = null }
    }

    /**
     * LX-1: run one host-side bundled-busybox applet (closed env, bounded
     * timeout, output drained off-thread — same shape as [runChmod]) and
     * return its trimmed stdout on exit 0, or null on any failure.
     */
    private fun runIdApplet(idBin: File, appletArgs: List<String>, workspaceId: String): String? {
        return try {
            val pb = ProcessBuilder(listOf(idBin.absolutePath) + appletArgs)
                .redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(bootstrapManager.processEnv())
            val process = pb.start()
            val reader = Executors.newSingleThreadExecutor()
            try {
                val outputFuture = reader.submit<String> {
                    try {
                        process.inputStream.bufferedReader().use { it.readText().trim() }
                    } catch (_: Exception) {
                        ""
                    }
                }
                if (!process.waitFor(GROUP_ID_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    Logger.w(TAG, "guest group reconciliation: 'id ${appletArgs.joinToString(" ")}' timed out")
                    logEvent(workspaceId, "id ${appletArgs.joinToString(" ")} TIMED OUT")
                    return null
                }
                val exitCode = process.exitValue()
                val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                if (exitCode != 0) {
                    Logger.w(TAG, "guest group reconciliation: 'id ${appletArgs.joinToString(" ")}' exit=$exitCode output='${output.take(128)}'")
                    logEvent(workspaceId, "id ${appletArgs.joinToString(" ")} FAILED exit=$exitCode")
                    return null
                }
                output
            } finally {
                reader.shutdownNow()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "guest group reconciliation: 'id' start failed: ${e.message}")
            logEvent(workspaceId, "id START FAILED: ${e.message}")
            null
        }
    }

    private fun String.ensureTrailingNewline(): String =
        if (endsWith("\n") || isEmpty()) this else "$this\n"

    /**
     * Phase 6b bug-fix — repairs guest-rootfs dpkg state that distro tarballs
     * don't ship with but that gets corrupted over time.
     *
     * **Root cause of the "apt install unzip → Sub-process /usr/bin/dpkg
     * returned an error code (1)" runtime bug:** when a previous `apt`/`dpkg`
     * invocation is killed mid-flight — the user closes the terminal while an
     * install is running, the app is force-killed by the OS, PRoot crashes —
     * dpkg leaves behind two kinds of garbage:
     *
     *   1. **Stale lock files** at `/var/lib/dpkg/lock` and
     *      `/var/lib/dpkg/lock-frontend`. The next apt invocation sees these
     *      as "another dpkg is running" and either refuses to start (the
     *      common Debian UX) or, in some rootfs variants, blocks on them
     *      forever. F-6 (2026-08-25 audit): deletion is now gated by the
     *      caller's [allowLockCleanup] — the old "safe at fresh-session
     *      start since no PRoot is running" assumption was false for
     *      multi-terminal workspaces and Package-Manager-UI execs running
     *      concurrently with a live terminal. See the F-6 section below.
     *   2. **Partial state in `/var/lib/dpkg/updates/`** (interrupted dpkg
     *      metadata write) and/or in `/var/cache/apt/archives/partial/`
     *      (interrupted apt download). dpkg refuses to proceed without
     *      `dpkg --configure -a` being run first; apt does not run that for
     *      the user — it surfaces as the exact error the user reported.
     *
     * ## F-6: when lock files may be deleted
     *
     * The lock-file deletion is gated by [allowLockCleanup]. The caller
     * ([PRootContainerRuntime]) passes true only when its per-workspace
     * activity registry proves no live guest process (interactive terminal
     * or one-shot exec) could be running dpkg/apt for this workspace right
     * now. When false the lock files are preserved and a clear line is
     * logged: a preserved stale lock file is harmless (dpkg's advisory fcntl
     * lock lives on the open file descriptor, which a dead dpkg no longer
     * holds), while a wrongly deleted lock can let two dpkgs corrupt the
     * database.
     *
     * Recovery survives the gated case: the partial-state detection
     * ([needsConfigure]) below is never gated, and `dpkg --configure -a`
     * takes dpkg's own fcntl lock — it recovers an interrupted transaction
     * AND safely refuses while a live dpkg still holds the lock.
     *
     * This function does NOT run `dpkg --configure -a` itself (it would need
     * a PRoot invocation, which is the caller's job, not the host-side
     * `GuestRootfsInitializer`'s). It only repairs the host-visible garbage
     * and reports whether the caller needs to run `dpkg --configure -a`
     * inside the just-launched PRoot session before the user's command can
     * safely run. The caller ([PRootContainerRuntime.openTerminal] /
     * [PRootContainerRuntime.execInternal]) decides whether and how to act
     * on [DpkgHealthReport.needsConfigure].
     *
     * Also surfaces a low-free-space warning when the data partition the
     * rootfs lives on drops below [LOW_FREE_SPACE_BYTES]: dpkg's writes to
     * `/var/lib/dpkg/` or `/var/cache/apt/archives/partial/` fail with
     * ENOSPC and surface as the exact same "Sub-process /usr/bin/dpkg
     * returned an error code (1)" — the user cannot tell the difference
     * from the apt output, but the diagnostic log can.
     */
    fun ensureDpkgHealthy(
        rootfs: File,
        workspaceId: String,
        allowLockCleanup: Boolean = true
    ): DpkgHealthReport {
        var freedLocks = 0
        var needsConfigure = false
        var lowFreeSpace = false

        // ── 1. Stale dpkg lock files ────────────────────────────────────────
        // F-6 (2026-08-25 audit): deletion is caller-gated. When another live
        // guest process for this workspace could legitimately be mid-dpkg (an
        // alive interactive terminal, or an in-flight one-shot exec — the
        // caller's activity registry decides), the lock files are left
        // untouched: deleting the file a live dpkg holds fcntl-locked lets a
        // second dpkg lock a fresh inode and run concurrently against the
        // same database — the exact class of corruption this function exists
        // to repair.
        if (allowLockCleanup) {
            for (lockPath in DPKG_LOCK_FILES) {
                val lock = File(rootfs, lockPath)
                if (lock.exists()) {
                    if (lock.delete()) {
                        freedLocks++
                        val notice = "removed stale dpkg lock at ${lock.absolutePath}"
                        Logger.i(TAG, notice)
                        logEvent(workspaceId, notice)
                    } else {
                        val msg = "Could not remove stale dpkg lock at ${lock.absolutePath}"
                        Logger.w(TAG, msg)
                        logEvent(workspaceId, "lock removal FAILED: $msg")
                        // Non-fatal: continue. The dpkg --configure -a step below
                        // will re-emit the same complaint if the lock is real.
                    }
                }
            }
        } else {
            val present = DPKG_LOCK_FILES.count { File(rootfs, it).exists() }
            if (present > 0) {
                val notice = "preserving $present dpkg lock file(s): another live guest process for " +
                    "$workspaceId may be using dpkg (F-6) — stale-lock recovery deferred"
                Logger.i(TAG, notice)
                logEvent(workspaceId, notice)
            }
        }

        // ── 2. Partial state in /var/lib/dpkg/updates/ ─────────────────────
        // dpkg writes atomic updates here during a transaction; a non-empty
        // directory after a process death means a transaction was interrupted
        // mid-write, and dpkg refuses to proceed without `dpkg --configure -a`.
        val updatesDir = File(rootfs, DPKG_UPDATES_DIR)
        if (updatesDir.isDirectory && updatesDir.list()?.isNotEmpty() == true) {
            needsConfigure = true
            val notice = "partial dpkg state detected at ${updatesDir.absolutePath} — caller should run dpkg --configure -a"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
        }

        // ── 3. Partial state in /var/cache/apt/archives/partial/ ───────────
        // apt downloads .deb files into this directory atomically (tmp name →
        // rename to .deb on success). Any leftover file means a download was
        // interrupted; apt would re-download anyway, but the partial state
        // also indicates apt was killed mid-transaction, which usually means
        // dpkg was also in a partial state.
        val partialDir = File(rootfs, APT_PARTIAL_DIR)
        if (partialDir.isDirectory && partialDir.list()?.isNotEmpty() == true) {
            needsConfigure = true
            val notice = "partial apt download state at ${partialDir.absolutePath} — caller should run dpkg --configure -a"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
        }

        // ── 4. Free-space check ────────────────────────────────────────────
        // ENOSPC during dpkg's writes to /var/lib/dpkg/ surfaces as the same
        // "Sub-process /usr/bin/dpkg returned an error code (1)" as a
        // corrupted dpkg database — the user cannot tell the difference from
        // the apt output, but the diagnostic log can. Warn loudly when free
        // space drops below the threshold so a human diagnosing the next
        // failure can see this was the cause.
        val usable = try { rootfs.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
        if (usable < LOW_FREE_SPACE_BYTES) {
            lowFreeSpace = true
            val mb = usable / (1024 * 1024)
            val notice = "LOW FREE SPACE on rootfs partition: ${mb}MB usable — dpkg writes may fail with ENOSPC"
            Logger.w(TAG, notice)
            logEvent(workspaceId, notice)
        }

        return DpkgHealthReport(
            needsConfigure = needsConfigure,
            freedLocks = freedLocks,
            lowFreeSpace = lowFreeSpace
        )
    }

    /**
     * 2026-09-01 (LX-4): makes apt in the guest WAIT for an already-running
     * apt/dpkg transaction instead of erroring with
     * "could not get lock /var/lib/{dpkg,apt/lists}/lock…".
     *
     * ## The reported bug
     *
     * `apt update` in one terminal session, then `apt update` again in a
     * second terminal (or from the Package Manager UI) failed with the raw
     * lock error. That is stock Debian behavior — apt's advisory locks are
     * the corruption guard — but the failure surface in a multi-session
     * phone environment is constant enough to read as a bug, and the
     * Package Manager's one-shot execs have no TTY to retry in.
     *
     * ## The fix (native, not a wrapper)
     *
     * apt ≥ 1.9.11 ships exactly this knob: `APT::Lock::Timeout` (lists/
     * archive locks, i.e. what `apt update` takes) and `DPkg::Lock::Timeout`
     * (dpkg's own lock, i.e. what installs take). While a lock is held, apt
     * prints "Waiting for cache lock …" and retries; on expiry it fails as
     * before. Dropping a config file into `/etc/apt/apt.conf.d/` queues
     * EVERY apt invocation in the guest — user-typed terminal commands,
     * Package Manager operations, and provisioner installs alike — without
     * shadowing any binary or changing any command line. Non-apt rootfs
     * images (no `/etc/apt/`) are skipped untouched.
     *
     * Idempotent, host-side file ops only; runs beside [ensureDpkgHealthy]
     * on every session/exec launch. The file is plain 0644 — it carries no
     * secret and guests may read it.
     */
    fun ensureAptLockWait(rootfs: File, workspaceId: String): String? {
        val aptConfDir = File(rootfs, APT_CONF_DIR)
        if (!aptConfDir.isDirectory) return null // not an apt-based image — nothing to do
        return try {
            val target = File(aptConfDir, APT_LOCK_WAIT_CONF)
            if (!target.isFile || !target.readBytes().contentEquals(APT_LOCK_WAIT_CONF_BYTES)) {
                val tmp = File(aptConfDir, "$APT_LOCK_WAIT_CONF.tmp")
                tmp.writeBytes(APT_LOCK_WAIT_CONF_BYTES)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return "Could not install apt lock-wait config at ${target.absolutePath}"
                    }
                }
                java.nio.file.Files.setPosixFilePermissions(
                    target.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
                )
                logDebugEvent(workspaceId, "apt lock-wait config installed (${target.absolutePath})")
            }
            null
        } catch (e: Exception) {
            val msg = "apt lock-wait config install failed for $workspaceId: ${e.message}"
            Logger.w(TAG, msg)
            logEvent(workspaceId, msg)
            msg
        }
    }

    /**
     * Outcome of [ensureDpkgHealthy]. All four fields are independent: a
     * healthy workspace has all four at their defaults; an unhealthy one
     * surfaces exactly the failures its caller needs to act on.
     */
    data class DpkgHealthReport(
        /**
         * True when `dpkg --configure -a` must be run inside the just-launched
         * PRoot session before the user's command can safely run. False does
         * NOT mean the dpkg database is pristine — only that no interrupted
         * transaction was detected.
         */
        val needsConfigure: Boolean,
        /**
         * How many stale lock files ([DPKG_LOCK_FILES]) were removed. Logged
         * so a future diagnostic can see whether this workspace had a habit of
         * getting its dpkg killed mid-flight.
         */
        val freedLocks: Int,
        /**
         * True when the rootfs partition's usable space has dropped below
         * [LOW_FREE_SPACE_BYTES]. The caller cannot fix this from inside the
         * guest — it can only surface the warning to the user.
         */
        val lowFreeSpace: Boolean
    )

    /**
     * Phase 2E-2 steady-state gate. True only when [target] is, WITHOUT
     * following symlinks, a real directory whose full mode is exactly 01777
     * (`S_IFDIR` type bits + 07777 permission/sticky bits) — the exact state
     * the ensure bodies below produce. Everything else (missing path, symlink,
     * regular file, any deviation in the 12 bits `chmod` sets, lstat failure)
     * returns false so the existing repair body runs unchanged.
     *
     * No cache, no memory: a pure per-launch check-then-skip. Silent by
     * design — the skip must cost nothing and log nothing; the on-device
     * verification signal is the ABSENCE of `guest /tmp chmod ok` lines.
     */
    private fun isAlreadyPrepared(target: File): Boolean {
        val st = lstatModeType(target.absolutePath) ?: return false
        return (st.second and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
            (st.first and 0xFFF) == TMP_MODE_BITS.toInt()
    }

    /**
     * Phase 3 no-follow type gate: true only when [path] is a REAL directory,
     * never a symlink to one. Used immediately before (and, for `/tmp`, after)
     * the host-side `chmod` spawn so the mode change cannot be redirected
     * through a link.
     */
    private fun isRealDirectory(path: java.nio.file.Path): Boolean =
        try {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }

    /**
     * Minimal test seam (same pattern as BootstrapManager.TerminfoTestSeam):
     * returns the no-follow stat for [path] as `(st_mode_and_07777, type_bits)`
     * — `(st.st_mode and 0xFFF, st.st_mode and OsConstants.S_IFMT)` — or null
     * when the path does not exist or the stat cannot be performed.
     *
     * Production uses `android.system.Os.lstat` (libcore allocation, JVM tests
     * cannot execute it, Robolectric fails UnsatisfiedLinkError on this
     * device); the JVM test suite installs [LstatTestSeam.override] as a
     * java.nio NOFOLLOW `unix:mode` reader, which is equivalent for this
     * gate's inputs (verified: dir → 41777, symlink → 120777, a symlink's
     * NOFOLLOW read never touches its target). Production never writes the
     * override — one branch, one nullable reference read in the steady state.
     */
    private fun lstatModeType(path: String): Pair<Int, Int>? {
        val seam = LstatTestSeam.override
        if (seam != null) {
            return try {
                seam(path)
            } catch (_: Exception) {
                null
            }
        }
        return try {
            val st = Os.lstat(path)
            Pair(st.st_mode and 0xFFF, st.st_mode and OsConstants.S_IFMT)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Test-only override hook for [lstatModeType] (see its KDoc). Null in
     * production — the steady-state path is `Os.lstat` with zero reflection.
     */
    internal object LstatTestSeam {
        @Volatile
        var override: ((String) -> Pair<Int, Int>?)? = null
    }

    /**
     * Runs the busybox `chmod 1777 <target>` applet as a plain host process
     * (the same closed-env shape [RootfsFixupService] uses for its fixup
     * commands — outside proot, on host absolute paths). Bounded timeout;
     * output drained on a background thread so a wedged applet can never
     * block the caller on the pipe.
     */
    private fun runChmod(chmod: File, target: File, workspaceId: String): ChmodResult {
        val startedAt = System.currentTimeMillis()
        val env = bootstrapManager.processEnv()
        Logger.i(TAG, "guest /tmp chmod: exe=${chmod.absolutePath} exists=${chmod.exists()} argv=1777 ${target.absolutePath}")
        return try {
            val pb = ProcessBuilder(chmod.absolutePath, "1777", target.absolutePath)
                .redirectErrorStream(true)
            // §1.49 shape: full-replacement env from the single source.
            pb.environment().clear()
            pb.environment().putAll(env)
            val process = pb.start()
            val reader = Executors.newSingleThreadExecutor()
            try {
                val outputFuture = reader.submit<String> {
                    try {
                        process.inputStream.bufferedReader().use { it.readText().trim() }
                    } catch (_: Exception) {
                        ""
                    }
                }
                val completed = process.waitFor(CHMOD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                    Logger.w(TAG, "guest /tmp chmod timed out after $CHMOD_TIMEOUT_SECONDS s (workspace $workspaceId)")
                    ChmodResult(success = false, elapsedMs = System.currentTimeMillis() - startedAt, output = output, timedOut = true)
                } else {
                    val output = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                    val exitCode = process.exitValue()
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (exitCode != 0) {
                        Logger.w(TAG, "guest /tmp chmod failed exit=$exitCode elapsed=${elapsed}ms output='${output.take(256)}'")
                        ChmodResult(success = false, exitCode = exitCode, elapsedMs = elapsed, output = output)
                    } else {
                        Logger.i(TAG, "guest /tmp chmod ok exit=0 elapsed=${elapsed}ms")
                        ChmodResult(success = true, exitCode = 0, elapsedMs = elapsed, output = output)
                    }
                }
            } finally {
                reader.shutdownNow()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "guest /tmp chmod START FAILED (${chmod.absolutePath}): ${e.message}", e)
            ChmodResult(success = false, elapsedMs = System.currentTimeMillis() - startedAt, startError = e.toString())
        }
    }

    /**
     * Reads `unix:mode` and checks the permission portion is exactly
     * 1777 (`0x3FF` = 0777 perms + 01000 sticky). Returns an error string,
     * or null when the mode is correct or unreadable (attribute
     * unavailable on the platform — chmod's exit 0 stands).
     */
    private fun verifyMode(target: File): String? {
        return try {
            val mode = java.nio.file.Files.getAttribute(target.toPath(), "unix:mode") as Number
            val permissionBits = mode.toLong() and 0x3FF
            if (permissionBits != TMP_MODE_BITS) {
                "has wrong permissions (0${permissionBits.toString(8)} != 01777) after chmod"
            } else {
                null
            }
        } catch (_: Exception) {
            // Platform without unix:mode (e.g. Android) — verification unavailable;
            // chmod's exit 0 already established the mode.
            null
        }
    }

    /** Best-effort append to the workspace's persistent `rootfs-init.log`. */
    private fun logEvent(workspaceId: String, content: String) {
        try {
            diagnosticLogManager.appendLog(workspaceId, LOG_FILE, content)
        } catch (_: Exception) {
            // best effort — never fail the install/launch for logging
        }
    }

    /**
     * UI/UX Phase 7: the same append, but only while debug capture is on.
     *
     * Reserved for the routine-success line. Every *failure* and every *repair*
     * keeps using [logEvent] unconditionally, because those are the entries that
     * explain a broken workspace after the fact and a user cannot be expected to
     * have enabled a debug setting before their install went wrong.
     */
    private fun logDebugEvent(workspaceId: String, content: String) {
        if (!DebugCapture.isEnabled()) return
        logEvent(workspaceId, content)
    }

    /** Outcome of one `chmod 1777` run — what failed and why. */
    private data class ChmodResult(
        val success: Boolean,
        val exitCode: Int? = null,
        val elapsedMs: Long,
        val output: String = "",
        val timedOut: Boolean = false,
        val startError: String? = null
    ) {
        fun describe(chmod: File): String = buildString {
            append("exit=${exitCode?.toString() ?: if (timedOut) "timeout" else "start-failed"}")
            if (startError != null) append(" startError='$startError'")
            append(" chmod=$chmod")
            if (output.isNotBlank()) append(" output='${output.take(256)}'")
            append(" elapsed=${elapsedMs}ms")
        }
    }

    companion object {
        private const val TAG = "GuestRootfsInitializer"

        /** `.linexa` marker directory/file inside the rootfs (sibling of first-boot's marker). */
        private const val LEGACY_MIGRATION_MARKER_DIR = ".linexa"
        private const val LEGACY_MIGRATION_MARKER_FILE = "home-migration-tiny.done"

        /**
         * The legacy guest home the project used before /home/linexa became
         * the canonical path. Referenced ONLY by [migrateLegacyTinyHome], to
         * migrate away from it — no runtime consumer may fall back to it.
         */
        const val LEGACY_TINY_HOME = "/home/tiny"

        /** Name of the guest-side host-information viewer (see [ensureGuestHostInfo]). */
        const val HOSTINFO_HELPER_NAME = "linexa-hostinfo"

        /**
         * POSIX-sh, dependency-free viewer over `/run/linexa/hostinfo.conf`.
         *
         * Design constraints, all pinned by tests:
         * - read-only: every code path only ever READS the feed — there is
         *   no verb in this script that writes anywhere;
         * - key-safe: keys come from the Linexa-side enum, values are
         *   resolver-canonical (no whitespace/newlines); sed extraction is
         *   anchored to `^KEY=` and takes the first hit only;
         * - honest absences: an unknown KEY exits 2, a missing/conf feed is
         *   reported verbatim instead of pretending unavailable differently;
         * - usage error exits 64 (EX_USAGE), feed trouble exits 3.
         *
         * NOTE ON ENCODING: the script body uses « (U+00AB) in place of the
         * shell dollar sign so that KOTLIN does not try to interpolate shell
         * «{…»/«var» constructs during compilation; the value below ends
         * with the runtime [.replace] mapping « → $. Nothing else rewrites
         * the bytes, and « cannot legitimately appear in the shell text.
         */
        /** Raw body; see [HOSTINFO_HELPER_SCRIPT] for the « encoding note.
         * Declared BEFORE [HOSTINFO_HELPER_SCRIPT]: companion initializers
         * run top-down and the replace must observe an initialized body.
         */
        private val HOSTINFO_HELPER_BODY: String = """#!/bin/sh
# linexa-hostinfo — authoritative Linexa host snapshot viewer (read-only).
# Data source: /run/linexa/hostinfo.conf, generated by the same provider that
# feeds the Linexa UI. Output sections mirror docs/HOST_SYSTEM_INFO.md.
#
# Interpreter note (2026-09-01 second-pass review): the shebang MUST be the
# guest-native /bin/sh. PRoot resolves a script's interpreter INSIDE the
# rootfs; /system is deliberately not bound into guests (see
# PRootLauncher §1.190), so the earlier /system/bin/sh shebang pointed at a
# path that exists only on the Android host — every guest exec of this
# helper failed with "No such file or directory" before a single line ran.
CONF=/run/linexa/hostinfo.conf

get() {
    [ -r "«CONF" ] || return 3
    sed -n "s/^«1=//p" "«CONF" | head -n 1
}

section() {
    printf '\n%s\n' "«1"
}

row() {
    label="«1"; shift
    value="«*"
    if [ -z "«value" ]; then value='-'; fi
    printf '  %-22s %s\n' "«label" "«value"
}

usage() {
    printf '%s\n' "usage: linexa-hostinfo            show the full host report"
    printf '%s\n' "       linexa-hostinfo get KEY    print one value (exit 2 = unknown)"
    printf '%s\n' "       linexa-hostinfo --json     machine-readable dump"
}

json_escape() {
    printf '%s' "«1" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g'
}

case "«1" in
    get)
        [ -n "«2" ] || { usage >&2; exit 64; }
        if ! value=$(get "«2"); then
            printf '%s\n' "linexa-hostinfo: cannot read «CONF" >&2
            exit 3
        fi
        if [ -z "«value" ]; then exit 2; fi
        printf '%s\n' "«value"
        ;;
    --json)
        [ -r "«CONF" ] || { printf '{"error":"no feed"}'; exit 3; }
        printf '{'
        sep=''
        while IFS= read -r line; do
            case "«line" in ''|'#'*) continue;; esac
            key=«{line%%=*}
            rest=«{line#*=}
            printf '%s"%s":"%s"' "«sep" "$(json_escape "«key")" "$(json_escape "«rest")"
            sep=', '
        done < "«CONF"
        printf '}\n'
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        if [ ! -r "«CONF" ]; then
            printf '%s\n' "linexa-hostinfo: no readable snapshot at «CONF" >&2
            printf '%s\n' "(the feed regenerates while a session runs; retry inside your Linux environment)" >&2
            exit 3
        fi
        section 'Device'
        row model "$(get DEVICE_MODEL)"
        row manufacturer "$(get DEVICE_MANUFACTURER)"
        row android "$(get ANDROID_VERSION) (API $(get ANDROID_SDK_INT))"
        section 'CPU'
        row architecture "$(get MACHINE_ARCH)"
        row cores.host "$(get CPU_CORES)"
        row cores.granted "$(get CPU_AFFINITY_COUNT)"
        row cores.granted.list "$(get CPU_AFFINITY_CORES)"
        row cores.online "$(get CPU_ONLINE_COUNT)"
        row cpu.model "$(get CPU_MODEL)"
        row cpu.max_freq_khz "$(get CPU_MAX_FREQ_KHZ)"
        row cpu.usage.percent "$(get CPU_USAGE_PERCENT)"
        row loadavg.1m "$(get CPU_LOADAVG_1M)"
        section 'GPU'
        printf '  (native sysfs: /sys and /proc are the Android host'"'"'s real trees —\n'
        printf '   lscpu reads /proc/cpuinfo, gpu nodes live under /sys as on the host)\n'
        row gpu.model "$(get GPU_MODEL)"
        row gpu.vendor "$(get GPU_VENDOR)"
        row gpu.temp_c "$(get GPU_TEMP_C)"
        row gpu.busy.percent "$(get GPU_BUSY_PERCENT)"
        row gpu.clock_khz "$(get GPU_CLOCK_KHZ)"
        section 'Memory / swap'
        row ram.total.kb "$(get MEMORY_TOTAL_KB)"
        row ram.available.kb "$(get MEMORY_AVAILABLE_KB)"
        row ram.used.kb "$(get MEMORY_USED_KB)"
        row swap.total.kb "$(get SWAP_TOTAL_KB)"
        row swap.free.kb "$(get SWAP_FREE_KB)"
        section 'Storage'
        row storage.free.bytes "$(get STORAGE_FREE_BYTES)"
        section 'Uptime / kernel'
        row uptime.seconds "$(get UPTIME_SECONDS)"
        # Issue #9: the WORKSPACE's own uptime (this environment's session),
        # written by the bridge only while a session is running. Distinct
        # from the host uptime above — a workspace restart resets it.
        row uptime.workspace.seconds "$(get uptime.workspace.seconds)"
        row kernel.release "$(get KERNEL_RELEASE_HOST)"
        row selinux.enforcing "$(get SELINUX_ENFORCING)"
        section 'Battery / thermal'
        row battery.percent "$(get BATTERY_PERCENT)"
        row battery.charging "$(get BATTERY_CHARGING)"
        row thermal.status "$(get THERMAL_STATUS)"
        section 'Network'
        row type "$(get NETWORK_TYPE)"
        row interface "$(get NETWORK_INTERFACE)"
        row ipv4.primary "$(get NETWORK_IPV4)"
        row metered "$(get NETWORK_METERED)"
        ;;
esac
exit 0
"""

        /** Finished script bytes; the « sentinel maps to the shell dollar. */
        val HOSTINFO_HELPER_SCRIPT: String = HOSTINFO_HELPER_BODY.replace("\u00ab", "$")


        /** The guest `/tmp` mode this class guarantees: `drwxrwxrwt` = 01777. */
        const val TMP_MODE_BITS: Long = 0x3FF

        // ── Guest terminal color-scheme provisioning (2026-08-31) ──────────

        /** The guest terminal HOME — `PRootLauncher.DEFAULT_GUEST_HOME` without
         *  the leading slash; every guest terminal's `HOME=/root`, which is the
         *  directory `TermuxPtySession` reads `.termux/colors.properties` from. */
        const val GUEST_TERMINAL_HOME_DIR = "root"

        /** Termux-format color scheme file name, relative to the guest home. */
        const val TERMINAL_COLORS_FILE_NAME = "colors.properties"

        /** Provisioned scheme file: `{rootfs}/{GUEST_TERMINAL_HOME_DIR}/.termux/colors.properties`. */
        const val TERMINAL_COLORS_RELATIVE_PATH = "$GUEST_TERMINAL_HOME_DIR/.termux/$TERMINAL_COLORS_FILE_NAME"

        /**
         * The default terminal-owned color scheme written when the workspace
         * provides none — Termux `colors.properties` format, values matching
         * the pinned fixture in `TerminalColorSchemePropertiesTest` (the
         * concept's always-dark terminal palette: `--term` background
         * #0A0E0F, terminal text #C9CED4, white cursor). Deliberately a
         * terminal-configuration FILE, not a theme token or a hardcoded view
         * color: the guest's own copy stays the single owner of the visible
         * scheme (user-editable, OSC-overridable), and `TermuxPtySession`
         * applies it before every emulator construction.
         */
        const val DEFAULT_TERMINAL_COLORS: String = """# Linexa default terminal scheme (terminal-owned; edit freely —
# this file is never overwritten once it exists).
foreground = #C9CED4
background = #0A0E0F
cursor = #FFFFFF
"""

        // ── Guest workspace + shell config (issue #4 / #7, 2026-09-03) ─────

        /** The managed workspace README file name. */
        const val WORKSPACE_README_NAME = "README.md"

        // ── Issue #1 (2026-09-03): the real linexa account ─────────────────

        /** `/etc/passwd` file name (relative to `etc/`). */
        const val PASSWD_FILE_NAME = "passwd"

        /** `/etc/shadow` file name (relative to `etc/`). */
        const val SHADOW_FILE_NAME = "shadow"

        /** First uid/gid tried for a created guest account (standard-user range). */
        const val DEFAULT_FIRST_UID = 1000

        /** GECOS for a created guest account. */
        const val ACCOUNT_GECOS = "Linexa User"

        /** Safe account-name rule (matches the metadata validator's identifier rule). */
        private val ACCOUNT_NAME_REGEX = Regex("^[a-z_][a-z0-9_-]*$")

        /** Upper bound on a guest account name. */
        const val MAX_ACCOUNT_NAME_CHARS = 32

        /** File name of the guest privilege bridge (shadows /usr/bin/sudo in PATH). */
        const val SUDO_BRIDGE_NAME = "sudo"

        /** The protocol helper the sudo wrapper delegates to. */
        const val ROOT_EXEC_HELPER_NAME = "linexa-root-exec"

        /**
         * The `sudo` front end with a `«` sentinel for the shell dollar (same
         * convention as HOSTINFO_HELPER_BODY — a Kotlin raw string would
         * otherwise interpolate).
         *
         * This is deliberately THIN. It only locates
         * [ROOT_EXEC_HELPER_NAME] and execs it; option parsing, the root
         * bridge protocol and the honest error messages live in the helper,
         * where they can be tested independently and replaced without
         * touching the file that shadows `/usr/bin/sudo`.
         *
         * Architecture: a proot guest has no kernel privilege transition.
         * The helper asks the Linexa app to run the command in a SEPARATE
         * `-0` proot process (the same mechanism as Root Shell and the
         * package manager). It never pretends the caller's uid changed;
         * `sudo id -u` prints 0 because that command really did run in the
         * root-emulated context.
         */
        const val SUDO_BRIDGE_BODY: String = """#!/bin/sh
# ─────────────────────────────────────────────────────────────────────
# Linexa sudo — installed by Linexa. Delete this file to expose the
# distribution's own /usr/bin/sudo again.
#
# Thin front end. The real work is done by
# /usr/local/bin/linexa-root-exec, which asks the Linexa app to run the
# command in a separate root-emulated proot process. A proot guest has
# no kernel privilege transition; this bridge does not fake one.
# ─────────────────────────────────────────────────────────────────────
helper=/usr/local/bin/linexa-root-exec
if [ ! -x "«helper" ]; then
  echo "linexa: sudo helper «helper is missing or not executable" >&2
  echo "linexa: restart the workspace so Linexa can repair the privilege bridge" >&2
  exit 1
fi
exec "«helper" "«@"
"""

        /** Finished sudo script; the « sentinel maps to the shell dollar. */
        val SUDO_BRIDGE_SCRIPT: String = SUDO_BRIDGE_BODY.replace("«", "$")

        /**
         * `linexa-root-exec` — the non-interactive root-bridge client.
         *
         * ## What it does
         *
         * Parses the `sudo` option surface users actually type, quotes the
         * remaining argv into one POSIX shell command, writes it into the
         * workspace mailbox and streams the root-emulated process's output
         * back. `-i`/`-s` are refused from a user session (the mailbox has no
         * stdin forwarding; Root Shell is the interactive surface), and
         * `-u`/`-g` accept only root because proot cannot change identity.
         *
         * ## Why it is separate from [SUDO_BRIDGE_BODY]
         *
         * `sudo` is a name the distribution already owns and may replace; the
         * wrapper must stay tiny and version-stable. The protocol logic lives
         * here so it can be unit-tested, and so a future transport change
         * does not require editing the file that shadows `/usr/bin/sudo`.
         *
         * ## Error policy
         *
         * Every unavailable/unsupported case prints an actionable line to
         * stderr and exits non-zero. It never silently falls back to running
         * the command as the caller: that is the exact behaviour this bridge
         * exists to replace.
         */
        const val ROOT_EXEC_HELPER_BODY: String = """#!/bin/sh
# ─────────────────────────────────────────────────────────────────────
# Linexa root-exec protocol helper — installed by Linexa.
#
# `sudo` in a Linexa workspace is this bridge, not the distribution's
# /usr/bin/sudo. A proot guest has no kernel privilege transition: the
# only real elevation is a separate root-emulated proot process owned by
# the Linexa app. This script hands one non-interactive command to that
# process through the workspace mailbox and streams its output back. It
# never fakes a uid: `sudo id -u` prints 0 because the command really ran
# in the root-emulated context, not because this script lied.
#
# Interactive shells are deliberately refused from a user session: the
# mailbox has no stdin forwarding. Use Apps → Root shell for that.
# ─────────────────────────────────────────────────────────────────────
set -u

MARKER_DIR=/run/linexa-root
MARKER=«MARKER_DIR/bridge
HEARTBEAT=«MARKER_DIR/heartbeat
HEARTBEAT_TIMEOUT=30
# The mailbox location is part of the app protocol, not of the user's
# environment: the app-side bridge watches <rootfs>/tmp exclusively.
TMPROOT=/tmp
BRIDGE_ERROR=125
PROTOCOL_WAIT_TICKS=150

say_err() { printf 'linexa: %s\n' "«*" >&2; }

usage() {
  cat >&2 <<'USAGE'
usage: sudo [options] command [args...]

Linexa's sudo runs a non-interactive command in the workspace's
root-emulated execution context. Options that ask for an interactive
session (-i, -s) use Apps → Root shell instead. Switching to a
non-root user (-u, -g) is not supported by proot.
USAGE
}

# Quote one argv element exactly as the app-side protocol does. Safe
# shell words stay bare so the runtime's package-command classifier still
# sees `apt` (not `'apt'`); everything else uses the single-quote form,
# which survives spaces, newlines, globs and embedded quotes.
is_safe_word() {
  case "«1" in
    ''|*[!A-Za-z0-9_./:=+@%,^-]*) return 1 ;;
  esac
  return 0
}

quote_arg() {
  if is_safe_word "«1"; then
    printf '%s' "«1"
    return 0
  fi
  _rest=«1
  _out=
  while :; do
    case "«_rest" in
      *\'*)
        _out=«_out«{_rest%%\'*}"'\''"
        _rest=«{_rest#*\'}
        ;;
      *) break ;;
    esac
  done
  printf "'%s'" "«_out«_rest"
}

want_shell=0
no_command_ok=0
chdir=
user=
group=

while [ "«#" -gt 0 ]; do
  case "«1" in
    --) shift; break ;;
    -i|--login) want_shell=1; shift ;;
    -s|--shell) want_shell=1; shift ;;
    -u|--user)
      if [ "«#" -lt 2 ]; then say_err "option '--user' requires an argument"; exit 1; fi
      user=«2; shift 2 ;;
    --user=*) user=«{1#--user=}; shift ;;
    -u?*) user=«{1#-u}; shift ;;
    -g|--group)
      if [ "«#" -lt 2 ]; then say_err "option '--group' requires an argument"; exit 1; fi
      group=«2; shift 2 ;;
    --group=*) group=«{1#--group=}; shift ;;
    -g?*) group=«{1#-g}; shift ;;
    -D|--chdir)
      if [ "«#" -lt 2 ]; then say_err "option '--chdir' requires an argument"; exit 1; fi
      chdir=«2; shift 2 ;;
    --chdir=*) chdir=«{1#--chdir=}; shift ;;
    -D?*) chdir=«{1#-D}; shift ;;
    -p|--prompt|-C|--close-from|-T|--command-timeout|-r|--role|-t|--type)
      if [ "«#" -lt 2 ]; then say_err "option «1 requires an argument"; exit 1; fi
      shift 2 ;;
    --prompt=*|--close-from=*|--command-timeout=*|--role=*|--type=*|--preserve-env=*)
      shift ;;
    -p?*|-C?*|-T?*|-r?*|-t?*) shift ;;
    -E|--preserve-env|-H|--set-home|-n|--non-interactive|-S|--stdin|-b|--background|-P|--preserve-groups|-A|--askpass)
      shift ;;
    -v|--validate|-k|--reset-timestamp|-K|--remove-timestamp)
      no_command_ok=1; shift ;;
    -l|--list)
      printf '%s\n' 'linexa: sudo is the workspace root bridge; there is no sudoers policy to list.'
      printf '%s\n' 'linexa: use Apps → Root shell for an interactive uid-0 session.'
      exit 0 ;;
    -h|--help) usage; exit 0 ;;
    -V|--version)
      printf '%s\n' 'Linexa sudo (proot root bridge) 1.0'
      exit 0 ;;
    -*) say_err "unsupported option '«1'"; say_err "run this command from Apps → Root shell if it needs interactive sudo semantics"; exit 1 ;;
    *) break ;;
  esac
done

is_root_identity() {
  case "«1" in
    root|0|'#0') return 0 ;;
    *) return 1 ;;
  esac
}

if [ -n "«user" ] && ! is_root_identity "«user"; then
  say_err "cannot switch to user '«user': a proot workspace only offers the emulated root context"
  say_err "use Apps → Root shell if you need an interactive root session"
  exit 1
fi
if [ -n "«group" ] && ! is_root_identity "«group"; then
  say_err "cannot switch to group '«group': a proot workspace only offers the emulated root context"
  say_err "use Apps → Root shell if you need an interactive root session"
  exit 1
fi

if [ "«#" -eq 0 ] && [ "«no_command_ok" = 1 ]; then
  exit 0
fi

uid=«(id -u 2>/dev/null || echo unknown)

# Already the emulated root account (Root Shell): execute the command
# directly. Option parsing still validated -u/-g above, so no unsupported
# identity switch is silently ignored.
if [ "«uid" = 0 ]; then
  if [ "«want_shell" = 1 ] || [ "«#" -eq 0 ]; then
    if [ -n "«chdir" ]; then cd -- "«chdir" || exit 1; fi
    exec "«{SHELL:-/bin/sh}" -l
  fi
  if [ -n "«chdir" ]; then cd -- "«chdir" || exit 1; fi
  exec "«@"
fi

if [ "«#" -eq 0 ]; then
  usage
  exit 1
fi
if [ "«want_shell" = 1 ]; then
  say_err "an interactive root shell cannot run through the non-interactive root bridge"
  say_err "open Apps → Root shell for an interactive uid-0 session, or run 'sudo <command>'"
  exit 1
fi

cmd=
for arg in "«@"; do
  quoted=«(quote_arg "«arg") || { say_err "could not quote the command"; exit 1; }
  if [ -z "«cmd" ]; then
    cmd=«quoted
  else
    cmd="«cmd «quoted"
  fi
done
# Real sudo keeps the caller's working directory; the root-emulated process
# starts in /home/linexa. Preserve it unless -D asked for another directory.
if [ -z "«chdir" ]; then
  caller_dir=«(pwd 2>/dev/null || echo '')
  if [ -n "«caller_dir" ]; then chdir=«caller_dir; fi
fi
if [ -n "«chdir" ]; then
  quoted_dir=«(quote_arg "«chdir") || { say_err "could not quote the directory"; exit 1; }
  cmd="cd -- «quoted_dir && «cmd"
fi

if [ ! -r "«MARKER" ]; then
  say_err "the root bridge is not running for this workspace"
  say_err "open Apps → Root shell to run privileged commands, or restart the workspace and retry"
  exit 1
fi
if ! head -n 1 "«MARKER" 2>/dev/null | grep -q '^linexa-root-bridge v1«'; then
  say_err "the root bridge marker is not a recognized protocol version"
  say_err "update Linexa, restart the workspace and retry"
  exit 1
fi
# F-035: the bridge regenerates this token on every start. Echoing it in the
# request rejects directories left over from an earlier session (or written
# without reading the marker) instead of executing them.
bridge_token=«(sed -n 's/^token=//p' "«MARKER" 2>/dev/null | head -n 1)
if [ -z "«bridge_token" ]; then
  say_err "the root bridge marker has no session token"
  say_err "restart the workspace and retry"
  exit 1
fi

umask 077
reqdir=«(mktemp -d "«TMPROOT/.linexa-root-req.XXXXXX" 2>/dev/null) || {
  say_err "could not create a request directory under «TMPROOT"
  exit 1
}
cleanup() { rm -rf "«reqdir"; }
trap cleanup 0 1 2 15

printf '%s\n' "«bridge_token" > "«reqdir/token" || {
  say_err "could not write the root request token under «TMPROOT"
  exit 1
}
printf '%s\n' "«cmd" > "«reqdir/cmd" || {
  say_err "could not write the root request under «TMPROOT"
  exit 1
}

# Wait for the app to claim the request. This is the only bounded wait:
# once claimed, a package operation may legitimately run for many minutes.
ticks=0
while [ ! -f "«reqdir/claimed" ]; do
  ticks=«((ticks + 1))
  if [ "«ticks" -ge "«PROTOCOL_WAIT_TICKS" ]; then
    say_err "the root bridge did not accept the request within 15 seconds"
    say_err "open Apps → Root shell to run privileged commands, or restart the workspace and retry"
    exit "«BRIDGE_ERROR"
  fi
  sleep 0.1 2>/dev/null || sleep 1
done

# Stream the append-only output file. tail -c +N is POSIX/GNU/busybox and
# avoids a FIFO open that could block forever when the bridge disappears.
# The heartbeat distinguishes a long package operation from a dead app
# process: the bridge rewrites it from its poll loop while it is alive.
offset=0
last_beat=«(cat "«HEARTBEAT" 2>/dev/null || echo '')
beat_since=«(date +%s 2>/dev/null || echo '')
while :; do
  if [ -f "«reqdir/out" ]; then
    size=«(wc -c < "«reqdir/out" 2>/dev/null | tr -d '[:space:]')
    case "«size" in ''|*[!0-9]*) size=0 ;; esac
    if [ "«size" -gt "«offset" ]; then
      tail -c +«((offset + 1)) "«reqdir/out"
      offset=«size
    fi
  fi
  if [ -f "«reqdir/status" ]; then
    break
  fi
  if [ ! -f "«MARKER" ]; then
    say_err "the root bridge stopped before the command finished (the workspace was closed)"
    say_err "open Apps → Root shell to run privileged commands, or restart the workspace and retry"
    exit "«BRIDGE_ERROR"
  fi
  beat=«(cat "«HEARTBEAT" 2>/dev/null || echo '')
  now=«(date +%s 2>/dev/null || echo '')
  if [ -n "«now" ]; then
    if [ "«beat" != "«last_beat" ]; then
      last_beat=«beat
      beat_since=«now
    elif [ -n "«beat_since" ] && [ «((now - beat_since)) -ge "«HEARTBEAT_TIMEOUT" ]; then
      say_err "the root bridge stopped responding (the Linexa app may have been restarted)"
      say_err "open Apps → Root shell to run privileged commands, or restart the workspace and retry"
      exit "«BRIDGE_ERROR"
    fi
  fi
  sleep 0.1 2>/dev/null || sleep 1
done

# Final drain: the bridge writes status after flushing every output line,
# but a concurrent reader can still observe the status between two polls.
if [ -f "«reqdir/out" ]; then
  size=«(wc -c < "«reqdir/out" 2>/dev/null | tr -d '[:space:]')
  case "«size" in ''|*[!0-9]*) size=0 ;; esac
  if [ "«size" -gt "«offset" ]; then
    tail -c +«((offset + 1)) "«reqdir/out"
  fi
fi

code=«(tr -d '[:space:]' < "«reqdir/status" 2>/dev/null)
case "«code" in
  ''|*[!0-9]*) code=«BRIDGE_ERROR ;;
esac
if [ "«code" -gt 255 ]; then code=«BRIDGE_ERROR; fi
exit "«code"
"""

        /** Finished helper script; the « sentinel maps to the shell dollar. */
        val ROOT_EXEC_HELPER_SCRIPT: String = ROOT_EXEC_HELPER_BODY.replace("«", "$")


        /** First line marking the /etc/profile.d script as Linexa-managed. */
        const val SHELL_CONFIG_MARKER = "# managed by Linexa — shell conveniences (la, ll, l)\n"

        /** The /etc/profile.d script file name (sorts first, ours stays early). */
        const val SHELL_CONFIG_FILE_NAME = "00-linexa-shell.sh"

        /**
         * The Termux-like alias baseline EVERY workspace gets, regardless of
         * what its rootfs ships in its own dotfiles. Declared
         * `linexa.shell_config.aliases` merge over this (declared wins).
         */
        val DEFAULT_SHELL_ALIASES: Map<String, String> = linkedMapOf(
            "la" to "ls -A",
            "ll" to "ls -alF",
            "l" to "ls -CF",
            "ls" to "ls --color=auto",
            "grep" to "grep --color=auto"
        )

        /** The workspace README body (seeded once, then left to the user). */
        val WORKSPACE_README_BYTES: ByteArray = """
# Linexa workspace

This directory is the dedicated workspace for this Linux environment —
the directory your terminal opens in. It is yours: project files,
scripts, notes, downloads and anything you want to keep all belong here.

Layout seeded by Linexa:
- `projects/` — your code and repositories
- `scripts/`  — your helper scripts
- `bin/`      — your private executables

Nothing here is deleted or overwritten by Linexa (except this README
until you edit it). Your HOME (`~`) still points at the account home.
""".trim().toByteArray() + byteArrayOf('\n'.code.toByte())

        /** Maximum time in seconds to wait for the `chmod 1777` applet run. */
        private const val CHMOD_TIMEOUT_SECONDS = 30L

        /** Per-workspace persistent log for rootfs-init events (§1.80). */
        private const val LOG_FILE = "rootfs-init.log"

        // ── LX-1 guest supplementary-group reconciliation constants ────────

        /** The bundled busybox applet that reports the live group set. */
        private const val GROUP_ID_APPLET = "id"

        /** Guest account database paths (relative to the rootfs). */
        private const val GROUP_FILE_NAME = "group"
        private const val GSHADOW_FILE_NAME = "gshadow"

        /** Maximum time in seconds to wait for one `id` applet run. */
        private const val GROUP_ID_TIMEOUT_SECONDS = 30L

        // ── dpkg-health constants (Phase 6b bug-fix) ───────────────────────

        /**
         * Stale lock files [ensureDpkgHealthy] deletes at fresh-session start.
         *
         * Paths relative to the rootfs. Both are safe to delete when no
         * PRoot is running — they are recreated by dpkg/apt on the next
         * legitimate transaction.
         */
        private val DPKG_LOCK_FILES = listOf(
            "var/lib/dpkg/lock",
            "var/lib/dpkg/lock-frontend"
        )

        /** dpkg's per-transaction update directory; non-empty after a kill. */
        private const val DPKG_UPDATES_DIR = "var/lib/dpkg/updates"

        /** apt's partial-download directory; non-empty after a kill mid-fetch. */
        private const val APT_PARTIAL_DIR = "var/cache/apt/archives/partial"

        /** LX-4: where apt's drop-in configuration lives inside the guest. */
        private const val APT_CONF_DIR = "etc/apt/apt.conf.d"

        /** LX-4: drop-in file name — sorts last, `99-` prefix is apt convention. */
        private const val APT_LOCK_WAIT_CONF = "99linexa-lock-wait"

        /**
         * LX-4: the drop-in body. Both timeouts exist since apt 1.9.11
         * (Debian 11+/Ubuntu 20.04+ — older apt ignores the keys harmlessly
         * and keeps the pre-existing fail-fast behavior). 300 s covers a
         * large `apt upgrade` finishing without an unbounded hang.
         */
        private val APT_LOCK_WAIT_CONF_BYTES: ByteArray = (
            "// Installed by Linexa: queue apt/dpkg behind an already-running " +
                "transaction instead of failing with 'could not get lock'.\n" +
                "APT::Lock::Timeout \"300\";\n" +
                "DPkg::Lock::Timeout \"300\";\n"
            ).toByteArray(Charsets.UTF_8)

        /**
         * Below this many bytes of free space on the rootfs partition,
         * [ensureDpkgHealthy] flags `lowFreeSpace = true` so a human
         * diagnosing the next "dpkg returned error code 1" can see ENOSPC
         * was the cause. 100 MB is roughly twice the size of a typical
         * `apt upgrade` of a mid-sized Debian install — enough headroom
         * that the warning fires *before* dpkg starts failing, not after.
         */
        private const val LOW_FREE_SPACE_BYTES = 100L * 1024 * 1024

        // ── R-6 (2026-08-26 runtime parity audit) ─────────────────────────
        //
        // Snapshot contents for the /proc bind-over files. See
        // [ensureProcSnapshots] for the rationale. Each value is the
        // COMPLETE file content (PRoot bind-over serves it verbatim), so
        // trailing newlines are significant — every line must end with \n
        // or the consumer parser may misbehave.

        /**
         * `/proc/stat` snapshot. Minimal valid shape:
         *   - one aggregate `cpu` line with 10 fields (user, nice, system,
         *     idle, iowait, irq, softirq, steal, guest, guest_nice),
         *   - eight per-core `cpu0`..`cpu7` lines (matches the typical
         *     `cpuinfo` 8-core output; audit: `nproc=3, cpuinfo=8`),
         *   - required trailing summary lines `intr`, `ctxt`, `btime`,
         *     `processes`, `procs_running`, `procs_blocked`, `softirq`.
         *
         * All-zero counters are valid (a freshly-booted real Linux kernel
         * has near-zero counters too); the only load-bearing field is
         * `btime` (boot time, seconds since epoch) which `ps` reads to
         * compute process ages — set to a stable recent value.
         */
        private val PROC_STAT_SNAPSHOT: String = buildString {
            append("cpu  0 0 0 0 0 0 0 0 0 0\n")
            for (i in 0 until 8) append("cpu$i 0 0 0 0 0 0 0 0 0 0\n")
            append("intr 0\n")
            append("ctxt 0\n")
            // 2026-01-01 = 1767225600 — a stable recent-ish epoch.
            append("btime 1767225600\n")
            append("processes 1\n")
            append("procs_running 1\n")
            append("procs_blocked 0\n")
            append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
        }

        /** `/proc/uptime` snapshot: `<uptime> <idle>` seconds. */
        private const val PROC_UPTIME_SNAPSHOT: String = "0.00 0.00\n"

        /**
         * `/proc/loadavg` snapshot: 1/5/15-min load, running/total tasks,
         * last PID.
         */
        private const val PROC_LOADAVG_SNAPSHOT: String = "0.00 0.00 0.00 1/1 1\n"

        /**
         * `/proc/version` snapshot. Matches [PRootLauncher.SPOOFED_KERNEL_RELEASE]
         * so `uname -a` and `cat /proc/version` agree.
         */
        private const val PROC_VERSION_SNAPSHOT: String =
            "Linux version 6.1.0-linexa (proot@linexa) (gcc 13.3.0, GNU ld 2.42) " +
                "#1 SMP PREEMPT_DYNAMIC Fri, 26 Aug 2026 00:00:00 +0000 aarch64 GNU/Linux\n"

        /**
         * `/proc/vmstat` snapshot. `vmstat` (the tool) reads a small set
         * of fields; the most important ones for a non-crashing startup
         * are `nr_running`, `nr_idle`, and the cumulative event counters.
         * The minimal content here lets `vmstat 1 1` produce one line of
         * output without an `EACCES` abort.
         */
        private val PROC_VMSTAT_SNAPSHOT: String = buildString {
            append("nr_running 1\n")
            append("nr_idle 0\n")
            append("nr_iowait 0\n")
            append("nr_anon_pages 0\n")
            append("nr_file_pages 0\n")
            append("pgpgin 0\n")
            append("pgpgout 0\n")
            append("pswpin 0\n")
            append("pswpout 0\n")
            append("pgfault 0\n")
            append("pgmajfault 0\n")
        }

        /**
         * The full snapshot map. Keys MUST be a subset of
         * [PRootLauncher.PROC_BIND_OVER_NAMES] — anything else would be
         * written but never bound. The launcher is the source of truth for
         * the bind set; this map is the source of truth for the content.
         */
        internal val PROC_SNAPSHOT_CONTENTS: List<Pair<String, String>> = listOf(
            "stat" to PROC_STAT_SNAPSHOT,
            "uptime" to PROC_UPTIME_SNAPSHOT,
            "loadavg" to PROC_LOADAVG_SNAPSHOT,
            "version" to PROC_VERSION_SNAPSHOT,
            "vmstat" to PROC_VMSTAT_SNAPSHOT
        )
    }
}
