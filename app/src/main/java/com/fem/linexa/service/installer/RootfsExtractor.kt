package com.fem.linexa.service.installer

import android.app.Application
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.fem.linexa.core.filesystem.ProcessIdentity
import com.fem.linexa.core.filesystem.ProcessSignal
import com.fem.linexa.core.filesystem.ProcessSignalSender
import com.fem.linexa.core.filesystem.ProcessTreeTerminator
import com.fem.linexa.core.filesystem.captureProcessIdentity
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Pass 2 of the staged installer (§1.26 native engine): the native
 * `proot --link2symlink <bin>/tar -xf <temp> -C <staging>
 * --delay-directory-restore --preserve-permissions` extraction that
 * tiny_container runs on real devices.
 *
 * Extracted verbatim from `StagedRootFsInstaller.runNativeExtraction`
 * (Roadmap Item 2 — pure responsibility extraction, no behavior change):
 * every forensic log line, the §1.52 argv/env dump format, the §1.48
 * per-invocation PROOT_TMP_DIR scoping, the §1.65 read-from-staging
 * extraction whitelist semantics, and the pass/fail surface are identical
 * to the monolith. The log TAG is kept as "StagedRootFsInstaller" so every
 * existing logcat line and filter keeps its identity.
 *
 * Roadmap Item 3 added the §1.50 seccomp/kernel-incompatibility fallback
 * on top of the verbatim core: the run is factored into single-attempt
 * [runOneExtractionAttempt] and [extract] retries once with
 * `PROOT_NO_SECCOMP=1` when the detector matches — mirroring
 * `PRootContainerRuntime.exec()`, sharing the memoized
 * [BootstrapManager.needsNoSeccomp] flag so the first detection from either
 * call site makes every later launch pre-apply the fallback.
 */
@Singleton
class RootfsExtractor @Inject constructor(
    private val application: Application,
    private val bootstrapManager: BootstrapManager,
    private val diagnosticLogManager: DiagnosticLogManager
) {

    /**
     * Phase 04 / RootFS R1 containment. Lazy so plain-JVM tests that never
     * time out do not touch `android.os.Process`; production resolves the app
     * UID/PID once.
     *
     * This is a **tree** terminator, not the older single-PID/group helper:
     * the extractor does not ship `setsid`, so the PRoot host is not a group
     * leader. Snapshotting the host's descendants before signalling is the
     * only way to reach tar/zstd workers that would otherwise survive a
     * SIGKILLed host.
     */
    private val productionTerminator: ProcessTreeTerminator by lazy {
        ProcessTreeTerminator(
            expectedUid = runCatching { Process.myUid() }.getOrNull(),
            sender = ProcessSignalSender { target, signal ->
                try {
                    Os.kill(
                        target,
                        if (signal == ProcessSignal.TERM) OsConstants.SIGTERM
                        else OsConstants.SIGKILL
                    )
                    true
                } catch (e: Exception) {
                    // ESRCH is normal after a race; log and let the poll decide.
                    Logger.w(TAG, "containment signal $signal to $target failed: ${e.message}")
                    false
                }
            },
            selfPid = runCatching { Process.myPid() }.getOrNull()
        )
    }

    /**
     * TEST-ONLY seam: JVM tests cannot call `android.system.Os.kill`, so they
     * inject a terminator whose sender shells out to the host `kill`.
     * Production never assigns it.
     */
    internal var processTerminatorForTest: ProcessTreeTerminator? = null

    private fun terminator(): ProcessTreeTerminator =
        processTerminatorForTest ?: productionTerminator

    /** TEST-ONLY: shortens containment windows for fail-closed tests. */
    internal var containmentGraceOverrideMs: Long? = null

    /** TEST-ONLY: shortens the post-SIGKILL verification window. */
    internal var containmentHardLimitOverrideMs: Long? = null

    /**
     * Phase 04: TEST-ONLY absolute-timeout override. Production uses
     * [EXTRACTION_ABSOLUTE_TIMEOUT_MINUTES]; a JVM test sets this to make
     * [FakeProot] scenarios (`SLOW_EXIT`) hit the timeout branch in
     * milliseconds instead of hours. `internal` keeps it inside the module,
     * so no external caller can shorten the real timeout; nothing in
     * production code ever assigns it.
     */
    internal var extractionTimeoutOverrideMs: Long? = null

    /**
     * RF-012: TEST-ONLY stall-timeout override. Production uses
     * [EXTRACTION_STALL_TIMEOUT_MINUTES]; tests use this to prove that a
     * silent child is treated as stalled long before the absolute bound.
     */
    internal var extractionStallTimeoutOverrideMs: Long? = null

    private fun effectiveTimeoutMs(): Long =
        extractionTimeoutOverrideMs ?: EXTRACTION_ABSOLUTE_TIMEOUT_MINUTES * 60_000L

    /**
     * RF-012: how long extraction may go without ANY progress signal
     * (a new tar checkpoint or a new member name) before it is considered
     * stalled. When the explicit absolute override is in use, stall
     * detection is disabled so the pre-existing timeout tests keep their
     * exact semantics.
     */
    private fun effectiveStallTimeoutMs(): Long = extractionStallTimeoutOverrideMs
        ?: if (extractionTimeoutOverrideMs != null) Long.MAX_VALUE
        else EXTRACTION_STALL_TIMEOUT_MINUTES * 60_000L

    /**
     * Runs the native extraction of [tempArchive] into [staging] with the
     * bundled GNU tar under PRoot. Returns null on success, or a
     * user-meaningful failure message. Same contract as the original
     * `runNativeExtraction`.
     *
     * §1.88: real progress — the tar run now carries `--checkpoint=100`
     * (GNU tar's default checkpoint action echoes `Read checkpoint N` to
     * stderr every 100 records of ~10240 bytes), and while the caller
     * thread waits it turns the latest checkpoint record count into a
     * [0,1] extraction fraction against [expectedExtractedBytes] (the
     * uncompressed size known from the analysis pass). [onProgress] is
     * invoked from the CALLER coroutine thread, never from the output
     * drain thread. [expectedExtractedBytes] <= 0 yields indeterminate
     * progress (no callback emissions).
     *
     * 2026-09-02 ("Now processing" fix): the tar run also carries `-v`, so
     * GNU tar lists every member name it extracts on its **stdout** — while
     * proot's `-v 2` translation trace and tar's diagnostics stay on
     * **stderr**. stdout and stderr are drained separately (never merged),
     * which is what makes per-entry reporting deterministic: a complete
     * stdout line IS the member name, so [parseTarVerboseEntry] can never
     * mistake a proot diagnostic or a tar warning for an entry. The latest
     * name is surfaced through [onEntry] — again from the CALLER coroutine
     * thread, throttled to change events — and flows into
     * `InstallProgress.currentEntryName`; the extraction keeps its genuine
     * checkpoint fraction regardless of whether any name has arrived.
     */
    suspend fun extract(
        tempArchive: File,
        staging: File,
        workspaceId: String,
        expectedExtractedBytes: Long = 0L,
        /**
         * UX-003: non-directory entry count from the analysis pass. The tar
         * stream carries a 512-byte header and padding per member, so the
         * file-size sum alone makes the checkpoint fraction reach 1.0 before
         * the process ends. Adding a per-entry overhead estimate keeps the
         * running fraction honest; `0` keeps the old denominator.
         */
        expectedEntryCount: Long = 0L,
        /**
         * §1.147: leading path components to drop while extracting. Non-zero
         * only when the archive wraps its rootfs in a single top-level
         * directory (`rootfs/etc/...`) — resolved by the analyzer from the
         * whole archive's entry shape, never guessed here.
         */
        stripComponents: Int = 0,
        onProgress: suspend (Float) -> Unit = {},
        onEntry: suspend (String) -> Unit = {}
    ): String? {
        currentCoroutineContext().ensureActive()
        // RF-013: the full argv and the complete process environment are
        // diagnostic detail, not operational telemetry. They used to be
        // written to logcat (and the workspace's diagnostic files) on every
        // install, including in release builds. Gate every full dump on the
        // user's "Enable debug info" switch, exactly like the runtime's
        // launch forensics; keep only path/shape facts at INFO.
        val verbose = DebugCapture.isEnabled()

        if (verbose) {
            // ── §1.40/§1.43/§1.46 FORENSIC LOGGING ────────────────────
            Logger.i(TAG, "=== FORENSIC: bootstrap state ===")
            Logger.i(TAG, bootstrapManager.diagnoseAll())

            // §1.46: Test execve for proot (static) and tar (dynamic) to
            // pinpoint whether the failure is at the kernel execve level,
            // the interpreter level, or the library resolution level.
            Logger.i(TAG, "=== FORENSIC: execve tests (§1.46) ===")
            Logger.i(TAG, "  testExecve(proot): ${bootstrapManager.testExecve(
                File(application.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
            )}")
            Logger.i(TAG, "  testExecve(tar):   ${bootstrapManager.testExecve(
                bootstrapManager.resolveBinary("tar").absolutePath
            )}")
            Logger.i(TAG, "  testExecve(busybox): ${bootstrapManager.testExecve(
                bootstrapManager.resolveBinary("busybox").absolutePath
            )}")
        }

        // §1.43: PRoot itself executes from nativeLibraryDir (apk_data_file
        // SELinux label). But the command it executes (tar) must resolve
        // through bootstrapManager.resolveBinary() — the real copied file
        // in filesDir/bootstrap/bin/, NOT a nativeLibraryDir path. Passing
        // a nativeLibraryDir path directly as proot's command argument
        // causes execve Permission denied (proot's syscall interception
        // alters the execution context). Tiny Container resolves tar via
        // PATH → bootstrap/bin/tar → nativeLibraryDir/lib__bin__tar__.so.
        val nativeLibDir = File(application.applicationInfo.nativeLibraryDir)
        val proot = File(nativeLibDir, "libproot.so")
        val tar = bootstrapManager.resolveBinary("tar")

        // Validate before execution
        val prootIssue = bootstrapManager.validateBinary("proot")
        if (prootIssue != null) {
            Logger.e(TAG, "proot validation failed: $prootIssue")
            return "bootstrap proot invalid: $prootIssue"
        }
        val tarIssue = bootstrapManager.validateBinary("tar")
        if (tarIssue != null) {
            Logger.e(TAG, "tar validation failed: $tarIssue")
            return "bootstrap tar invalid: $tarIssue"
        }

        // §1.48: per-invocation scoped PROOT_TMP_DIR for this extraction
        // (UUID — extraction is a one-shot, so no workspace scope exists yet).
        // Released in the finally below once proot has fully exited.
        val tmpScope = "extract-${java.util.UUID.randomUUID()}"
        val tmpDir = bootstrapManager.scopedProotTmpDir(tmpScope)

        // §1.44/§1.46: Log exact command and environment before execution.
        // §1.52: with debug info enabled the command carries `-v 2`, so proot
        // prints every syscall path translation ("translate(\"/\" + \"...\")")
        // to its stderr, merged into our capture via redirectErrorStream.
        // When proot aborts on its internal assert (compare_paths2:547, §1.52
        // root cause: an empty readlinkat/readlink pathname,
        // proot-me/proot#182), the LAST translate line before the abort names
        // the exact empty path — proot writes these via unbuffered stderr, so
        // they survive the abort. With debug info off (the default) proot
        // runs at `-v 0`: fatal errors still reach stderr, but the per-syscall
        // stream is not generated (F-004 / R-03).
        // RootFS R1: the argv carries `--kill-on-exit` (the runtime already
        // launches this way), and a `setsid` prefix is still applied when a
        // future bootstrap ships that applet. The shipped busybox does NOT,
        // so the host is normally not a group leader and the timeout path
        // contains it by snapshotting its PPID descendants before signalling
        // (see ProcessTreeTerminator). The argv is built by one testable
        // function; the former inline block is it.
        val setsid = bootstrapManager.resolveBinary("setsid").takeIf {
            bootstrapManager.validateBinary("setsid") == null
        }
        Logger.i(TAG, "extraction containment: setsid=${setsid?.absolutePath ?: "<unavailable>"} --kill-on-exit=on")
        val fullArgv = buildExtractionArgv(
            proot = proot,
            tar = tar,
            setsid = setsid,
            tempArchive = tempArchive,
            staging = staging,
            stripComponents = stripComponents,
            // F-004 / R-03: proot's syscall trace is diagnostic detail. It used
            // to be requested unconditionally, so every install paid for a
            // stream the code only ever keeps a bounded tail of. The verbose
            // level is now tied to the same user-visible "Enable debug info"
            // switch that already gates the forensic dumps above.
            verbose = verbose
        )
        // The exact env that will be passed to ProcessBuilder — captured once
        // so every §1.52 block below reports the same map that runs.
        // §1.50 (Roadmap Item 3): the shared memo — if the runtime or a
        // previous extraction already proved this kernel seccomp-incompatible,
        // pre-apply the fallback to the very first attempt, never silently.
        var prootRunEnv = bootstrapManager.prootEnv(tmpDir.absolutePath)
        if (BootstrapManager.needsNoSeccomp) {
            Logger.w(
                TAG,
                "Seccomp/kernel-incompatibility already known for this kernel — pre-applying PROOT_NO_SECCOMP=1 (§1.50)"
            )
            prootRunEnv = prootRunEnv + BootstrapManager.NO_SECCOMP_ENV
        }

        // RF-013: operational facts that are useful for any failure, without
        // the full argv/env. The verbosity level, `stripComponents` and the
        // two paths are not secret and are needed to reproduce a failure.
        Logger.i(
            TAG,
            "PRoot extraction: proot exists=${proot.exists()} canExec=${proot.canExecute()}, " +
                "tar exists=${tar.exists()} canExec=${tar.canExecute()}, strip=$stripComponents, " +
                "verbose=$verbose, " +
                "archive=${tempArchive.name} (${tempArchive.length()} bytes), staging=${staging.name}"
        )
        Logger.i(TAG, "  PROOT_TMP_DIR = ${tmpDir.absolutePath} (scoped, §1.48)")

        if (verbose) {
            // §1.46: proot must be a static ELF (it runs before the linker
            // exists in the guest). This probe is the second proot launch the
            // FakeProot invocation-count contracts in the tests expect.
            Logger.i(TAG, "  proot is static ELF: ${bootstrapManager.testExecve(proot.absolutePath).contains("execve_OK") || proot.absolutePath.let { p ->
                try { java.io.RandomAccessFile(p, "r").use { raf -> raf.readByte().toInt() == 0x7f && raf.readByte().toInt() == 'E'.code && raf.readByte().toInt() == 'L'.code && raf.readByte().toInt() == 'F'.code && raf.readByte().toInt() == 2 } } catch (_: Exception) { false }
            }}")

            // ── §1.52 FORENSIC: full argv (argv[0]..argv[n]) + shell-copyable ──
            Logger.i(TAG, "PRoot extraction command: ${fullArgv.joinToString(" ")}")
            Logger.i(TAG, "=== FORENSIC: full argv (§1.52) ===")
            fullArgv.forEachIndexed { i, arg ->
                val truncated = if (arg.length > 192) " (${arg.length} chars, truncated)" else ""
                Logger.i(TAG, "  argv[$i] = ${arg.take(192)}$truncated")
            }
            val shellCopyable = (prootRunEnv.toSortedMap().map { (k, v) -> "$k=${shellQuote(v)}" } +
                fullArgv.map { shellQuote(it) }).joinToString(" ")
            Logger.i(TAG, "  shell-copyable: $shellCopyable")

            // ── §1.52 FORENSIC: complete host env passed to proot (sorted) ────
            Logger.i(TAG, "=== FORENSIC: complete host env passed to proot (§1.52, sorted) ===")
            val highlightedKeys = setOf(
                "PATH", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR",
                "TMPDIR", "HOME", "PWD", "LANG", "TERM", "USER"
            )
            prootRunEnv.toSortedMap().forEach { (key, value) ->
                val mark = if (key in highlightedKeys) "   <--" else ""
                Logger.i(TAG, "  $key = ${value.orEmpty()}$mark")
            }
            Logger.i(TAG, "  keys=${prootRunEnv.size}; unset in env: TMPDIR=${prootRunEnv["TMPDIR"] ?: "<unset>"}, HOME=${prootRunEnv["HOME"] ?: "<unset>"}, PWD=${prootRunEnv["PWD"] ?: "<unset>"}, LANG=${prootRunEnv["LANG"] ?: "<unset>"}, TERM=${prootRunEnv["TERM"] ?: "<unset>"}, USER=${prootRunEnv["USER"] ?: "<unset>"}")

            // ── §1.65 TEMP DIAG (remove once the EPERM root cause is found) ─────
            // The exact invocation, as proot will run it, plus the archive's
            // on-disk state at launch time. Catches a file deleted/renamed
            // between Pass 1 and Pass 2, and any path-shape mismatch (e.g.
            // /data/user/0 vs /data/data) between Java and proot/tar.
            Logger.i(TAG, "=== §1.65 TEMP DIAG: final PRoot + tar invocation ===")
            val stripArg = if (stripComponents > 0) " --strip-components=$stripComponents" else ""
            val verbosity = if (verbose) "2" else "0"
            Logger.i(TAG, "  proot argv: ${proot.absolutePath} -v $verbosity --link2symlink ${tar.absolutePath}$stripArg -xf ${tempArchive.absolutePath} -C ${staging.absolutePath} --delay-directory-restore --preserve-permissions")
            Logger.i(TAG, "  tar argv:   tar$stripArg -xf ${tempArchive.absolutePath} -C ${staging.absolutePath} --delay-directory-restore --preserve-permissions")
            Logger.i(TAG, "  bind mounts: <none — extraction proot carries no -b args; runtime /dev /proc /sys binds are session-only (§1.20)>")
            Logger.i(TAG, "  source-archive: abs=${tempArchive.absolutePath} canonical=${try { tempArchive.canonicalPath } catch (_: Exception) { "<error>" }} exists=${tempArchive.exists()} isFile=${tempArchive.isFile} canRead=${tempArchive.canRead()} length=${tempArchive.length()} bytes")

            // ── §1.52 FORENSIC: pipeline path verification (file metadata) ─────
            Logger.i(TAG, "=== FORENSIC: pipeline path verification (§1.52) ===")
            listOf(
                "proot" to proot,
                "tar" to tar,
                "loader" to File(bootstrapManager.resolveBinary("loader").absolutePath),
                "archive(temp)" to tempArchive,
                "staging" to staging,
                "binDir" to bootstrapManager.binDir,
                "libDir" to bootstrapManager.libDir,
                "prootTmpDir" to tmpDir
            ).forEach { (label, file) ->
                Logger.i(TAG, fingerprintPath(label, file))
            }

            // §1.53: persist the full command and environment to the
            // workspace's diagnostic files only when debug capture is on.
            try {
                val shellCopyable = (prootRunEnv.toSortedMap().map { (k, v) -> "$k=${shellQuote(v)}" } +
                    fullArgv.map { shellQuote(it) }).joinToString(" ")
                diagnosticLogManager.logExtractionCommand(
                    workspaceId = workspaceId,
                    fullArgv = fullArgv,
                    prootRunEnv = prootRunEnv,
                    shellCopyable = shellCopyable,
                    archivePath = tempArchive.absolutePath,
                    stagingPath = staging.absolutePath,
                    elapsedMs = 0L // Will be updated with actual elapsed time
                )
                diagnosticLogManager.logEnvironment(workspaceId = workspaceId, label = "extraction", env = prootRunEnv)
            } catch (_: Exception) {
                // Best effort — don't fail extraction for logging
            }
        } else {
            // Even with capture off, a support report still needs to know
            // WHICH variables were set — without their values.
            Logger.i(TAG, "  env keys=${prootRunEnv.keys.sorted()}")
        }

        // ── Run, with the §1.50 seccomp/kernel-incompatibility fallback ─────
        var attempt = runOneExtractionAttempt(
            fullArgv, prootRunEnv, workspaceId, tmpScope, proot, tar, staging,
            expectedExtractedBytes, expectedEntryCount, onProgress, onEntry
        )
        val exit = attempt.exitCode
        if (exit != null && exit != 0 && bootstrapManager.isSeccompKernelBugFailure(attempt.output) && !BootstrapManager.needsNoSeccomp) {
            // §1.50 (Roadmap Item 3): detect the known seccomp/kernel-
            // incompatibility signature and retry ONCE with PROOT_NO_SECCOMP=1,
            // mirroring PRootContainerRuntime.exec(). Only fires on a genuine
            // signature (verified against the shipped binary), never on a
            // generic non-zero exit. Logs loudly so the device-specific quirk
            // is visible in diagnostics, not silently masked.
            Logger.w(
                TAG,
                "Seccomp/kernel-incompatibility detected in $workspaceId extraction — retrying once with PROOT_NO_SECCOMP=1 (§1.50)"
            )
            BootstrapManager.needsNoSeccomp = true
            // §1.48: the retry MUST use a fresh scoped PROOT_TMP_DIR — the
            // first attempt's scope was released once its process exited.
            val retryScope = "extract-${java.util.UUID.randomUUID()}"
            val retryTmpDir = bootstrapManager.scopedProotTmpDir(retryScope)
            attempt = runOneExtractionAttempt(
                fullArgv,
                bootstrapManager.prootEnv(retryTmpDir.absolutePath) + BootstrapManager.NO_SECCOMP_ENV,
                workspaceId,
                retryScope,
                proot,
                tar,
                staging,
                expectedExtractedBytes,
                expectedEntryCount,
                onProgress,
                onEntry
            )
        }
        return attempt.failure
    }

    /**
     * Runs one native extraction attempt of [fullArgv] with [prootRunEnv]
     * (a §1.48-scoped `PROOT_TMP_DIR` included), releasing [tmpScope] once
     * the process has fully exited. Returns the [ExtractionAttempt] outcome;
     * [ExtractionAttempt.output] is the captured merged-output tail — the
     * §1.50 detector input. Every forensic log line and §1.53
     * [diagnosticLogManager] call fires per attempt, so a retry is recorded
     * as its own truthful result.
     *
     * §1.88: while [process.waitFor] polls, the output drain thread parses
     * tar's `Read checkpoint N` lines into [checkpoint]; this caller thread
     * turns the latest counter into [onProgress] fractions — see [extract].
     *
     * 2026-09-02 ("Now processing" fix): the extraction runs with
     * `redirectErrorStream(false)` so tar's `-v` member names (stdout) and
     * the proot `-v 2` trace + tar diagnostics (stderr) arrive on separate
     * pipes. The diagnostics drain keeps the exact §1.88 checkpoint parsing
     * and the bounded §1.52 tail; a second drain consumes stdout, parses
     * each complete line with [parseTarVerboseEntry], and records the latest
     * member name in [entryName] — which the caller-thread poll loop forwards
     * through [onEntry] whenever the name changes. onProgress/onEntry run on
     * THIS (caller) coroutine thread, never from the drain threads. If the
     * platform merged the pipes anyway (never under ProcessBuilder with
     * redirectErrorStream unset, but defensively), stdout simply stays empty
     * and entry reporting degrades to indeterminate — checkpoint progress
     * and every failure path are untouched.
     */
    private suspend fun runOneExtractionAttempt(
        fullArgv: List<String>,
        prootRunEnv: Map<String, String>,
        workspaceId: String,
        tmpScope: String,
        proot: File,
        tar: File,
        staging: File,
        expectedExtractedBytes: Long,
        expectedEntryCount: Long,
        onProgress: suspend (Float) -> Unit,
        onEntry: suspend (String) -> Unit
    ): ExtractionAttempt {
        currentCoroutineContext().ensureActive()
        return try {
            val process = ProcessBuilder(fullArgv)
                // 2026-09-02: NO redirectErrorStream — stdout (tar -v member
                // names) and stderr (proot trace, tar diagnostics) must be
                // separate for per-entry reporting to be parseable. The
                // diagnostics drain below re-joins stderr into the same
                // bounded tail the merged capture used to fill, so failure
                // reporting is byte-compatible with the previous behavior.
                .apply {
                    // §1.49 (Issue 4 fix): full replacement env from the
                    // single source — the same prootEnv() the runtime uses for
                    // openTerminal/exec, including the scoped PROOT_TMP_DIR and
                    // the §1.47 PROOT_LOADER.
                    environment().clear()
                    environment().putAll(prootRunEnv)
                }
                .start()
            // §1.52: the merged output (proot -v 2 trace + tar messages) is
            // drained on a background thread into a bounded tail buffer so
            // proot's verbose translation log can never OOM the app on huge
            // archives, and so the tail survives a proot abort. This keeps
            // the old readBytes() semantics (full-stream capture) in result
            // and error reporting while bounding memory.
            val outputTail = BoundedOutputTail()
            // RootFS R1: capture the child's process identity immediately.
            // `/proc/<pid>/stat` field 22 is the only safe handle for the
            // timeout path; a bare PID can be recycled. The tree terminator
            // needs this identity to snapshot descendants while the host is
            // still alive.
            val childIdentity: ProcessIdentity? = captureProcessIdentity(process)
            if (childIdentity == null) {
                Logger.w(TAG, "containment: could not capture /proc identity — timeout will quarantine staging")
            } else {
                Logger.i(
                    TAG,
                    "containment: child pid=${childIdentity.pid} pgid=${childIdentity.processGroupId} " +
                        "start=${childIdentity.startTimeTicks}"
                )
            }
            val checkpoint = java.util.concurrent.atomic.AtomicLong(0)
            val entryName = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val drain = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                // stderr drain — the §1.88 checkpoint parser plus the §1.52
                // bounded tail, byte-compatible with the previous merged drain.
                val drainedErr = drain.submit {
                    process.errorStream.use { input ->
                        val buffer = ByteArray(8192)
                        val pending = StringBuilder()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            outputTail.feed(buffer, n)
                            // §1.88: split the chunk on newlines and look for
                            // tar's "Read checkpoint N" progress markers. The
                            // pending buffer handles markers split across reads.
                            pending.append(String(buffer, 0, n, Charsets.UTF_8))
                            while (true) {
                                val newline = pending.indexOf("\n")
                                if (newline < 0) break
                                val line = pending.substring(0, newline)
                                pending.delete(0, newline + 1)
                                parseCheckpointRecords(line)?.let { checkpoint.set(it) }
                            }
                        }
                        if (pending.isNotEmpty()) {
                            parseCheckpointRecords(pending.toString())?.let { checkpoint.set(it) }
                        }
                    }
                }
                // stdout drain — with `-v` this carries tar's extracted
                // member names line by line. Each complete line is parsed for
                // the entry name ([parseTarVerboseEntry] ignores artifacts) —
                // that is what makes "Now processing" deterministic. The bytes
                // are ALSO fed into the bounded tail so the §1.50 seccomp
                // detector sees both streams exactly as the previous merged
                // capture did (proot prints its kernel-bug marker on stdout,
                // so a stdout-only entry drain would blind the retry).
                val drainedOut = drain.submit {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        val pending = StringBuilder()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            outputTail.feed(buffer, n)
                            pending.append(String(buffer, 0, n, Charsets.UTF_8))
                            while (true) {
                                val newline = pending.indexOf("\n")
                                if (newline < 0) break
                                val line = pending.substring(0, newline)
                                pending.delete(0, newline + 1)
                                parseTarVerboseEntry(line)?.let { entryName.set(it) }
                            }
                        }
                        if (pending.isNotEmpty()) {
                            parseTarVerboseEntry(pending.toString())?.let { entryName.set(it) }
                        }
                    }
                }
                // §1.88: poll instead of a single long waitFor — every second
                // the loop turns the latest checkpoint record count into a
                // real extraction fraction (bytes read / known uncompressed
                // size), so the UI never freezes at a fake phase value.
                // onProgress/onEntry run on THIS (caller) coroutine thread.
                //
                // RF-012: the old single 30-minute wall clock failed a large
                // rootfs on slow storage even while tar was making progress,
                // and let a genuinely wedged child burn the full limit. The
                // limit is now "no progress for [EXTRACTION_STALL_TIMEOUT_MINUTES]"
                // plus a much larger absolute ceiling that only bounds a
                // runaway process. A tar checkpoint or a new member name is
                // progress; either one resets the stall clock.
                val absoluteTimeoutMs = effectiveTimeoutMs()
                val stallTimeoutMs = effectiveStallTimeoutMs()
                val absoluteLabel = if (extractionTimeoutOverrideMs != null) {
                    "${absoluteTimeoutMs}ms"
                } else {
                    "$EXTRACTION_ABSOLUTE_TIMEOUT_MINUTES minutes"
                }
                val stallLabel = if (extractionStallTimeoutOverrideMs != null) {
                    "${stallTimeoutMs}ms"
                } else {
                    "$EXTRACTION_STALL_TIMEOUT_MINUTES minutes"
                }
                val startedAt = System.currentTimeMillis()
                var lastProgressAt = startedAt
                var lastCheckpoint = 0L
                var completed = false
                var timedOutLabel: String? = null
                var lastReportedEntry: String? = null
                var lastFraction = 0f
                while (true) {
                    // RF-010: cancellation must reach the child promptly. The
                    // poll interval is one second, so a user-visible cancel
                    // stops the extraction within that window instead of
                    // waiting for the process to finish on its own.
                    currentCoroutineContext().ensureActive()
                    if (process.waitFor(1, TimeUnit.SECONDS)) {
                        completed = true
                        break
                    }
                    val now = System.currentTimeMillis()
                    val records = checkpoint.get()
                    if (records != lastCheckpoint) {
                        lastCheckpoint = records
                        lastProgressAt = now
                    }
                    if (records > 0 && expectedExtractedBytes > 0) {
                        // UX-003: the denominator includes tar's per-member
                        // overhead, and a running extraction never reports
                        // 100% — the checkpoint stream can reach the file-size
                        // sum before tar has finished the tail. Completion is
                        // signalled explicitly below, after the child exits.
                        val fraction = extractionProgressFraction(
                            records = records,
                            expectedExtractedBytes = expectedExtractedBytes,
                            expectedEntryCount = expectedEntryCount,
                            previous = lastFraction
                        )
                        if (fraction > lastFraction) {
                            lastFraction = fraction
                            onProgress(fraction)
                        }
                    }
                    val entry = entryName.get()
                    if (entry != null && entry != lastReportedEntry) {
                        lastReportedEntry = entry
                        lastProgressAt = now
                        onEntry(entry)
                    }
                    timedOutLabel = extractionTimeoutReason(
                        nowMs = now,
                        startedAtMs = startedAt,
                        lastProgressAtMs = lastProgressAt,
                        absoluteTimeoutMs = absoluteTimeoutMs,
                        stallTimeoutMs = stallTimeoutMs,
                        absoluteLabel = absoluteLabel,
                        stallLabel = stallLabel
                    )
                    if (timedOutLabel != null) break
                }
                if (completed && expectedExtractedBytes > 0) {
                    // The process has actually exited; only now is 100% true.
                    onProgress(1f)
                }
                if (!completed) {
                    val timeoutLabel = timedOutLabel ?: absoluteLabel
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    Logger.e(TAG, "Native extraction timed out after $timeoutLabel")
                    // RootFS R1: containment BEFORE any cleanup. The extractor
                    // ships no `setsid`, so this host is not a group leader;
                    // the tree terminator snapshots the host's descendants
                    // (tar/zstd workers) while it is still alive, escalates
                    // TERM → KILL, and verifies every target by PID + start
                    // time. A bare destroyForcibly only reached the PRoot
                    // host, and `--kill-on-exit` is an exit handler that a
                    // SIGKILLed host never runs — the snapshot is the primary
                    // mechanism. A missing identity stays fail-closed.
                    val outcome = containExtraction(process, childIdentity, staging)
                    if (!outcome.confirmed) {
                        // Last resort against the host itself; this cannot
                        // prove the tracees are gone, so staging must NOT be
                        // deleted underneath anything still extracting.
                        val note = outcome.note
                            ?: "staging quarantine FAILED — staging left in place"
                        Logger.e(TAG, "extraction containment NOT confirmed; $note")
                        try { drainedErr.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                        try { drainedOut.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                        val failureTxt = "Native extraction timed out after $timeoutLabel; " +
                            "process containment could not be confirmed; $note"
                        try {
                            diagnosticLogManager.logExtractionResult(
                                workspaceId = workspaceId,
                                success = false,
                                exitCode = null,
                                outputTail = "Timed out after $timeoutLabel; $note",
                                elapsedMs = elapsedMs
                            )
                        } catch (_: Exception) {}
                        ExtractionAttempt(
                            failure = failureTxt,
                            exitCode = null,
                            output = outputTail.tailText()
                        )
                    } else {
                        try { drainedErr.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                        try { drainedOut.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                        try {
                            diagnosticLogManager.logExtractionResult(
                                workspaceId = workspaceId,
                                success = false,
                                exitCode = null,
                                outputTail = "Timed out after $timeoutLabel",
                                elapsedMs = elapsedMs
                            )
                        } catch (_: Exception) {}
                        ExtractionAttempt(
                            failure = "Native extraction timed out after $timeoutLabel",
                            exitCode = null,
                            output = outputTail.tailText()
                        )
                    }
                } else {
                    val exitCode = process.exitValue()
                    // §1.50/§1.52: the output tail is drained on a background
                    // thread; wait for it to finish consuming the pipe BEFORE
                    // reading the tail, or `tailText()` can come back empty
                    // right after the process exits (the §1.50 detector then
                    // misses a real seccomp marker on a busy/fast runner and
                    // the retry never fires). The timeout branch above already
                    // joins it; the completion branches must too. The stdout
                    // drain joins alongside it so the last entry name lands
                    // even on a fast run.
                    try { drainedErr.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                    try { drainedOut.get(5, TimeUnit.SECONDS) } catch (_: Exception) {}
                    if (exitCode != 0) {
                        // §1.67: exitValue() is PROOT's exit code, not tar's —
                        // proot exits with the LAST tracee's status
                        // (proot-5.3.0 src/tracee/event.c:424). The 2026-08-05
                        // report was mislabeled "tar exit 70": tar itself exited
                        // 2 ("Cannot open: Operation not permitted" on the source
                        // archive) and 70 was zstd's broken-pipe exit after tar
                        // died. Surface the parsed per-tracee exits instead.
                        val exits = parseProotTraceeExits(outputTail.tailText())
                        val traceeInfo = if (exits.isNotEmpty()) "; ${prootExitSummary(exits)}" else ""
                        Logger.e(TAG, "Native extraction failed (proot exit $exitCode$traceeInfo): ${outputTail.tailText(2000)}")
                        try {
                            diagnosticLogManager.logExtractionResult(
                                workspaceId = workspaceId,
                                success = false,
                                exitCode = exitCode,
                                outputTail = outputTail.tailText(4000),
                                elapsedMs = 0L
                            )
                        } catch (_: Exception) {}
                        // F7 fix (2026-09-02): bound the user-facing message — the raw tail can carry
                        // up to 256 KB of proot trace into Room/UI, where the log line above truncates to
                        // 2000 chars but this string never did.
                        val failureTxt = "Native extraction failed (proot exit $exitCode$traceeInfo): ${outputTail.tailText(2000)}"
                        ExtractionAttempt(
                            failure = failureTxt,
                            exitCode = exitCode,
                            output = outputTail.tailText()
                        )
                    } else {
                        Logger.i(TAG, "Native tar extraction ok (${outputTail.total} bytes output)")
                        try {
                            diagnosticLogManager.logExtractionResult(
                                workspaceId = workspaceId,
                                success = true,
                                exitCode = 0,
                                outputTail = outputTail.tailText(2000),
                                elapsedMs = 0L
                            )
                        } catch (_: Exception) {}
                        ExtractionAttempt(failure = null, exitCode = 0, output = outputTail.tailText())
                    }
                }
            } catch (e: CancellationException) {
                // RF-010: the coroutine was cancelled (the user pressed
                // Cancel, or the coordinator is shutting the flow down).
                // Stopping the coroutine alone would leave `proot tar` writing
                // into staging until it finished or the timeout fired, so the
                // child gets the same containment treatment as a timeout —
                // inside NonCancellable so a second cancellation cannot skip
                // the cleanup half-way through.
                withContext(NonCancellable) {
                    val outcome = containExtraction(process, childIdentity, staging)
                    Logger.i(
                        TAG,
                        "extraction cancelled; containment=" +
                            if (outcome.confirmed) "confirmed"
                            else "NOT confirmed (${outcome.note ?: "unknown"})"
                    )
                }
                throw e
            } finally {
                drain.shutdownNow()
            }
        } catch (e: CancellationException) {
            // Cancellation is not an extraction failure: never convert it into
            // an ExtractionAttempt the caller might retry or report as Broken.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Native extraction threw", e)
            try {
                diagnosticLogManager.logExtractionResult(
                    workspaceId = workspaceId,
                    success = false,
                    exitCode = null,
                    outputTail = "Exception: ${e.message}",
                    elapsedMs = 0L
                )
            } catch (_: Exception) {}
            val failureTxt = "Native extraction failed: ${e.message} | proot=${proot.absolutePath} exists=${proot.exists()} canExec=${proot.canExecute()} | tar=${tar.absolutePath} exists=${tar.exists()} canExec=${tar.canExecute()}"
            ExtractionAttempt(
                failure = failureTxt,
                exitCode = null,
                output = ""
            )
        } finally {
            // §1.48: proot has fully exited by now — safe to reclaim scratch.
            bootstrapManager.releaseProotTmpDir(tmpScope)
        }
    }

    /** Outcome of a single native extraction attempt (§1.50 retry predicate). */
    private class ExtractionAttempt(
        /** User-meaningful failure message, or null on success. */
        val failure: String?,
        /** proot's exit code; null on timeout or exception — never retried on. */
        val exitCode: Int?,
        /** Captured merged-output tail — the §1.50 detector input. */
        val output: String
    )

    /**
     * RF-010/RF-012: contains the extraction process tree and, when
     * containment cannot be proven, quarantines staging so no later cleanup
     * can delete a tree that may still be written to. Shared by the timeout
     * and cancellation paths so both apply the same fail-closed rule.
     */
    private fun containExtraction(
        process: java.lang.Process,
        childIdentity: ProcessIdentity?,
        staging: File
    ): ContainmentOutcome {
        val report = childIdentity?.let { identity ->
            terminator().terminate(
                roots = listOf(identity),
                graceMs = containmentGraceOverrideMs
                    ?: ProcessTreeTerminator.DEFAULT_GRACE_MS,
                hardLimitMs = containmentHardLimitOverrideMs
                    ?: ProcessTreeTerminator.DEFAULT_HARD_LIMIT_MS
            )
        }
        val survivorsLabel = report?.survivors
            ?.joinToString(prefix = "[", postfix = "]")
            ?: "<identity-unavailable>"
        Logger.i(
            TAG,
            "containment report: targets=${report?.targets ?: 0} " +
                "signalled=${report?.signalled ?: 0} " +
                "escalated=${report?.escalated ?: 0} survivors=$survivorsLabel"
        )
        if (report?.confirmed == true) return ContainmentOutcome(confirmed = true, note = null)

        // Last resort against the host itself; this cannot prove the tracees
        // are gone, so staging must NOT be deleted underneath anything still
        // extracting. Wait (bounded) for the host to actually exit: a bare
        // destroyForcibly() returns before the kernel has torn the process
        // down, and the caller may otherwise delete the tree while it is
        // still being written.
        process.destroyForcibly()
        try {
            if (!process.waitFor(HOST_KILL_WAIT_SECONDS, TimeUnit.SECONDS)) {
                Logger.w(
                    TAG,
                    "containment: host still alive ${HOST_KILL_WAIT_SECONDS}s after destroyForcibly"
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val quarantined = quarantineStaging(staging)
        val note = if (quarantined != null) {
            "staging quarantined at ${quarantined.absolutePath}"
        } else {
            "staging quarantine FAILED — staging left in place"
        }
        return ContainmentOutcome(confirmed = false, note = note)
    }

    private class ContainmentOutcome(
        val confirmed: Boolean,
        val note: String?
    )

    /**
     * Phase 04: move [staging] out of the installer's path so the caller's
     * normal cleanup (`failAndCleanUp` deletes staging) cannot delete a tree
     * that something still has open. Returns the quarantine path, or null when
     * the rename failed (the caller then deletes as before — logged as a
     * containment failure, never a success).
     */
    private fun quarantineStaging(staging: File): File? {
        val parent = staging.parentFile ?: return null
        val quarantine = File(parent, "${staging.name}.quarantine-${System.currentTimeMillis()}")
        return if (staging.renameTo(quarantine)) quarantine else null
    }

    companion object {
        /**
         * Deliberately kept as "StagedRootFsInstaller": this class is the
         * verbatim extraction of the monolith's Pass 2, and every existing
         * forensic log line (plus any operator logcat filter) keys on this
         * tag. Changing it would silently re-label diagnostics.
         */
        private const val TAG = "StagedRootFsInstaller"

        /** Maximum wall-clock time in minutes; only bounds a runaway process (RF-012). */
        private const val EXTRACTION_ABSOLUTE_TIMEOUT_MINUTES = 360L

        /** Bounded wait for the host to die after the last-resort SIGKILL. */
        private const val HOST_KILL_WAIT_SECONDS = 5L

        /**
         * RF-012: extraction is considered stalled after this many minutes
         * without a new tar checkpoint or member name. Slow storage that is
         * still making progress therefore never trips it.
         */
        private const val EXTRACTION_STALL_TIMEOUT_MINUTES = 15L

        /**
         * §1.88: GNU tar's default blocking factor is 20 records of 512 bytes
         * each, so one "Read checkpoint N" marker corresponds to
         * 20 × 512 = 10240 bytes of uncompressed tar data read. Used to turn
         * the checkpoint counter into a real extraction fraction.
         */
        private const val CHECKPOINT_RECORD_BYTES = 20L * 512L

        /**
         * UX-003: average tar member overhead in the uncompressed stream —
         * a 512-byte header plus padding of the member data to a 512-byte
         * boundary. Padding is at most 511 bytes; half that is used as an
         * estimate, which is enough to stop the checkpoint fraction from
         * hitting 1.0 long before `tar -x` finishes a many-small-files rootfs.
         */
        private const val TAR_MEMBER_OVERHEAD_BYTES = 512L + 256L

        /**
         * UX-003: monotonic, honest extraction fraction. The checkpoint
         * estimate is scaled against data bytes plus a per-member overhead
         * estimate, and is capped just below 1.0 while tar is still running:
         * completion is reported by the caller only after the process exits.
         */
        internal fun extractionProgressFraction(
            records: Long,
            expectedExtractedBytes: Long,
            expectedEntryCount: Long,
            previous: Float
        ): Float {
            if (records <= 0 || expectedExtractedBytes <= 0) return previous
            val overhead = expectedEntryCount.coerceAtLeast(0L) * TAR_MEMBER_OVERHEAD_BYTES
            val expectedStreamBytes = expectedExtractedBytes + overhead
            if (expectedStreamBytes <= 0) return previous
            val raw = (records * CHECKPOINT_RECORD_BYTES.toDouble() / expectedStreamBytes).toFloat()
            return maxOf(previous, raw.coerceIn(0f, RUNNING_PROGRESS_CEILING))
        }

        /** UX-003: never report 100% from an estimate while the child runs. */
        private const val RUNNING_PROGRESS_CEILING = 0.99f

        /**
         * RF-012: the timeout decision, extracted so "a slow but progressing
         * extraction is not killed" is unit-testable without a device.
         *
         * Returns the label to report, or null while the extraction may
         * continue. Stall wins over the absolute ceiling when both are due,
         * because "no progress for N minutes" is the actionable diagnosis.
         */
        internal fun extractionTimeoutReason(
            nowMs: Long,
            startedAtMs: Long,
            lastProgressAtMs: Long,
            absoluteTimeoutMs: Long,
            stallTimeoutMs: Long,
            absoluteLabel: String,
            stallLabel: String
        ): String? {
            if (nowMs - lastProgressAtMs >= stallTimeoutMs) return "$stallLabel without progress"
            if (nowMs - startedAtMs >= absoluteTimeoutMs) return absoluteLabel
            return null
        }
    }
}

/**
 * The extraction argv, built once so the containment contract is testable.
 *
 * - A `setsid` binary (when the bootstrap ships one) is the FIRST argv so the
 *   child becomes a session/process-group leader: `pgid == pid`, which
 *   [ProcessTreeTerminator] treats as a hardening layer. The shipped busybox
 *   has no `setsid` applet, so the timeout path relies on the PPID tree it
 *   snapshots before signalling.
 * - `--kill-on-exit` is a proot option and must precede the traced `tar`
 *   command. On a graceful proot exit it reaps the tracee tree; it does NOT
 *   run after a SIGKILLed host, which is why tree containment is the primary
 *   mechanism, not this flag.
 */
internal fun buildExtractionArgv(
    proot: File,
    tar: File,
    setsid: File?,
    tempArchive: File,
    staging: File,
    stripComponents: Int,
    /**
     * F-004 / R-03: request proot's syscall translation trace (`-v 2`) only
     * when the user enabled debug info. At the default (`-v 0`) proot still
     * reports fatal errors on stderr — including the assert/trace line a
     * failed extraction needs — but does not emit the per-syscall stream that
     * made a full rootfs install write hundreds of MB that were then drained
     * and discarded.
     */
    verbose: Boolean = false
): List<String> = buildList {
    setsid?.let { add(it.absolutePath) }
    add(proot.absolutePath)
    add("-v"); add(if (verbose) "2" else "0")
    add("--link2symlink")
    add("--kill-on-exit")
    add(tar.absolutePath)
    // 2026-09-02 ("Now processing" fix): `-v` makes GNU tar echo each
    // extracted member's name to stdout. stdout is drained separately
    // from stderr (see runOneExtractionAttempt), so these names are
    // the ONLY content on that stream and parse deterministically —
    // unlike the merged capture, where proot's `-v 2` trace shares
    // the stream. Diagnostics (§1.46 forensics, §1.50 detector) are
    // unaffected: they all live on stderr, which keeps its tail.
    add("-v")
    // §1.147: the analyzer resolved that every entry shares one
    // wrapper directory, so the rootfs lives one level down. GNU tar
    // applies strip-components to every member (including symlink
    // targets' names, which is why the analyzer only strips a shape it
    // proved uniform), and drops members with fewer components.
    if (stripComponents > 0) {
        add("--strip-components=$stripComponents")
    }
    add("-xf"); add(tempArchive.absolutePath)
    add("-C"); add(staging.absolutePath)
    add("--delay-directory-restore")
    add("--preserve-permissions")
    // §1.88: real extraction progress — GNU tar's default checkpoint
    // action echoes "Read checkpoint N" to stderr every 100 records
    // (~1 MB at the default 20×512 byte blocking factor); the caller
    // parses those into a real fraction (see [extract]).
    add("--checkpoint=100")
    // distro archives ship mtimes in the future; this is harmless and
    // would otherwise spam the merged capture with "time stamp ... is
    // N s in the future" lines between checkpoints.
    add("--warning=no-timestamp")
}

/**
 * §1.52: Bounded capture of a process's merged output. Keeps at most
 * [maxBytes] of the most recent output plus a running [total] byte
 * count, so proot's `-v 2` translation trace (potentially hundreds of
 * MB on a full rootfs extraction) can never OOM the app process. On
 * failure the tail is exactly what matters — proot's own assert
 * message and the last `translate(...)` line land there.
 *
 * Shared (package-internal) between [RootfsExtractor] (merged proot
 * output) and [RootfsFixupService] (separate stdout/stderr tails).
 *
 * 2026-09-02: since the extraction drains stdout and stderr on TWO
 * threads into one tail, [feed] and [tailText] are now synchronized —
 * the interleaving order of the two streams is unspecified but every
 * byte fed is retained and the tail is never torn.
 */
internal class BoundedOutputTail(private val maxBytes: Int = 256 * 1024) {
    private val chunks = ArrayDeque<ByteArray>()
    private var buffered = 0
    var total: Long = 0
        private set

    fun feed(data: ByteArray, length: Int) {
        synchronized(this) {
            total += length
            if (length <= 0) return
            val chunk = if (length == data.size) data else data.copyOf(length)
            chunks.addLast(chunk)
            buffered += length
            while (buffered > maxBytes && chunks.size > 1) {
                buffered -= chunks.removeFirst().size
            }
        }
    }

    /** Last up to [maxChars] characters of the captured output. */
    fun tailText(maxChars: Int = Int.MAX_VALUE): String = synchronized(this) {
        val text = buildString(buffered) { chunks.forEach { append(it.toString(Charsets.UTF_8)) } }
        if (text.length > maxChars) "…${text.takeLast(maxChars)}" else text
    }
}

/**
 * §1.67: parses proot's per-tracee exit lines out of a `-v 2` capture.
 * proot prints `vpid N: exited with status M` for every tracee exiting
 * normally (proot-5.3.0 src/tracee/event.c:424) and exits with the LAST
 * tracee's status — so `Process.exitValue()` must never be labeled "tar
 * exit" (the 2026-08-05 report: tar exited 2 on EPERM-open of the source
 * archive; zstd then died on a broken pipe, exit 70, which became
 * proot's exit code). Returns (vpid, status) pairs in output order.
 *
 * Roadmap Item 4: strictly defensive — the parser must NEVER throw on
 * unexpected trace formats (the item's one flagged risk: a diagnostics
 * enhancement becoming a new crash source). A line whose captured numbers
 * are not valid `Int`s (e.g. a corrupted or overflowing digit run) is
 * skipped rather than aborting the parse, so the failure label always
 * carries the real proot exit code; a parse that yields nothing simply
 * falls back to the plain `(proot exit $exitCode)` label with no tracee
 * breakdown. The pass/fail decision never consults this parser.
 */
internal fun parseProotTraceeExits(raw: String): List<Pair<Int, Int>> =
    runCatching {
        VPID_EXIT_REGEX.findAll(raw)
            .mapNotNull { match ->
                val vpid = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val status = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                vpid to status
            }
            .toList()
    }.getOrDefault(emptyList())

/** §1.67: compact "vpid 1=2, vpid 2=70, …" summary for the failure label. */
internal fun prootExitSummary(exits: List<Pair<Int, Int>>): String =
    exits.sortedBy { it.first }.joinToString(", ") { (vpid, status) -> "vpid $vpid=$status" }

private val VPID_EXIT_REGEX = Regex("""vpid (\d+): exited with status (\d+)""")

/**
 * §1.88: parses GNU tar's `--checkpoint` progress markers out of a merged
 * output line. The default checkpoint action echoes
 * `Read checkpoint N` / `Write checkpoint N` (some versions prefix `tar: `)
 * where N is the cumulative 10240-byte record count. Returns null for any
 * other line — strictly defensive, never throws, mirrors the §1.67 parser
 * rules. A marker that fails to parse simply leaves progress indeterminate.
 */
internal fun parseCheckpointRecords(line: String): Long? = runCatching {
    CHECKPOINT_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
}.getOrNull()

private val CHECKPOINT_REGEX = Regex("""(?i)(?:tar:\s*)?(?:read|write)\s+checkpoint\s+(\d+)""")

/**
 * 2026-09-02 ("Now processing" fix): parses GNU tar's `-v` output into an
 * extracted member name. With `-v` (and no other list-formatting flag) GNU
 * tar writes each member's name — exactly as stored in the archive, with
 * leading `./` or `/` retained per the archive's packing — followed by a
 * newline, to **stdout**; every diagnostic ("tar: …", checkpoint markers,
 * "Removing leading /") goes to **stderr**. Because the extraction drains
 * the two pipes separately (see [RootfsExtractor.runOneExtractionAttempt]),
 * a complete stdout line IS the member name.
 *
 * A path is accepted only when it is non-blank, contains no newline, and is
 * not a merged-stream artifact: the separator proot/tar interjection would
 * need (a line starting "tar: ") is rejected, checkpoint lines are rejected
 * even if a stream merge ever put them here, and control characters are
 * rejected so a binary misparse can never surface as a UI name. Trailing
 * carriage returns (CRLF archives) are stripped. Anything else returns null
 * — strictly defensive like the §1.67/§1.88 parsers, never throws, and a
 * rejected line simply leaves the previously reported name in place.
 */
internal fun parseTarVerboseEntry(line: String): String? = runCatching {
    val candidate = line.trimEnd('\r').trimEnd()
    when {
        candidate.isEmpty() -> null
        candidate.contains('\n') -> null
        // tar diagnostics never carry a member name on their own line; if a
        // merged stream ever fed one here, refuse it rather than mislabel.
        candidate.startsWith("tar:") -> null
        candidate.contains(IGNORED_LINE_REGEX) -> null
        candidate.any { it.isISOControl() } -> null
        else -> candidate
    }
}.getOrNull()

/** Lines that look like progress/summary artifacts rather than member names. */
private val IGNORED_LINE_REGEX = Regex(
    """(?i)(?:read|write)\s+checkpoint\s+\d+|^translate\(.*|^vpid \d+:.*|^\d+/\d+ files|^total size|^block\s+\d+"""
)

/**
 * §1.52: shellQuote for the diagnostics "shell-copyable" echo. The
 * real runner passes [ProcessBuilder] argv directly (no shell); this
 * only helps a human paste the exact same invocation into a shell.
 */
private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * §1.52: verbose file-metadata fingerprint for one pipeline path.
 * Reports every view the audit needs (original/absolute/canonical/
 * normalized, existence, kind, permission bits, and the symlink target)
 * without ever throwing — a metadata failure becomes a field, not a crash.
 */
private fun fingerprintPath(label: String, file: File): String {
    val type = when {
        file.isDirectory -> "dir"
        file.isFile -> "file"
        else -> "-"
    }
    val symlinkTarget = try {
        java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
    } catch (_: Exception) {
        ""
    }
    val canonical = try { file.canonicalPath } catch (_: Exception) { "<error>" }
    val normalized = try { file.normalize().path } catch (_: Exception) { "<error>" }
    val sym = if (symlinkTarget.isEmpty()) "<not-symlink>" else symlinkTarget
    return "  $label: path=${file.path} abs=${file.absolutePath} canonical=$canonical normalized=$normalized exists=${file.exists()} type=$type readable=${file.canRead()} writable=${file.canWrite()} executable=${file.canExecute()} symlinkTarget=$sym"
}
