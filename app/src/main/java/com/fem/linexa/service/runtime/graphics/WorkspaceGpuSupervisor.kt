package com.fem.linexa.service.runtime.graphics

import android.system.Os
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifecycle owner for the workspace **GPU** feature: one
 * `virgl_test_server_android` host process per workspace with the toggle
 * enabled, supervised with Linexa's standard determinism guarantees.
 *
 * ## Why a supervisor, and why the launch path
 *
 * The GPU bridge is a *launch-path* feature like Shared Storage (§1.82), not a
 * post-launch reconciler step: the guest env (`GALLIUM_DRIVER=virpipe`,
 * `VTEST_SOCKET_NAME`) must be part of the PRoot argv/env, and the server must
 * be confirmed running BEFORE the guest execs anything that could try to use
 * it. The toggle is therefore read on the launch path
 * (`PRootContainerRuntime.isGpuEnabled`) and this supervisor is consulted by
 * the three launch sites (terminal session, daemon, one-shot exec).
 *
 * ## Determinism contract (mirrors §1.83's daemon supervision)
 *
 * - **No auto-restart.** A server that exits unexpectedly is reported Degraded
 *   once and stays down until the next workspace start. An unbounded restart
 *   loop against a wedged GPU driver is the failure mode this refuses.
 * - **Fail-open, file-bridge style.** Every failure path returns an EMPTY env
 *   map: the workspace launches without GPU env, the guest keeps llvmpipe, the
 *   desktop (Xvnc → VNC, CPU-side by design) is unaffected.
 * - **Socket-verified activation.** `GALLIUM_DRIVER=virpipe` is only handed to
 *   a guest after the server's socket file exists host-side (bounded wait), so
 *   a guest never sees a virpipe config pointing at a dead socket from a
 *   failed bring-up.
 * - **One owner per workspace.** A per-workspace mutex serializes provision
 *   calls, so two terminals opening concurrently cannot start two servers
 *   racing for one socket path.
 * - **Full teardown.** [stopFor] destroys the process (term→kill), cancels its
 *   watcher, removes the socket file and forgets the runtime; it is called
 *   from `closeWorkspaceSessions` alongside `stopAllDaemons`.
 * - **Stale-process recovery.** The server is a plain child process; if the
 *   app dies, Android may orphan it. `startupRecoverySweep` calls
 *   [killStaleServers] on every fresh process start, which scans the caller's
 *   own-uid `/proc` for the binary's argv and SIGKILLs matches.
 *
 * ## Binary absence is a Degraded state, not a crash
 *
 * This build does NOT bundle the GPU binaries (see [VirglHostServer]'s KDoc
 * for the ANGLE-path finding). With the toggle on and the triplet absent, the
 * supervisor reports a precise Degraded health entry once per start and
 * returns an empty env — the user sees exactly why, and nothing else changes.
 */
@Singleton
class WorkspaceGpuSupervisor(
    private val runtimeTrace: RuntimeTrace,
    private val healthRegistry: WorkspaceHealthRegistry,
    private val nativeLibraryDir: String,
    private val processFactory: GpuProcessFactory,
    private val logDirFor: (String) -> File,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** Health-registry component key for this feature (§6 component vocabulary). */
    val COMPONENT: String = "gpu"

    private data class GpuRuntime(
        val process: GpuProcess,
        val socketFile: File,
        val socketDir: File,
        val watcher: Job?,
        val stopping: AtomicBoolean
    )

    private val runnings = ConcurrentHashMap<String, GpuRuntime>()
    private val startLocks = ConcurrentHashMap<String, Mutex>()

    /** Whether [workspaceId] currently has a live host server. */
    fun isRunning(workspaceId: String): Boolean {
        val runtime = runnings[workspaceId] ?: return false
        return runtime.process.isAlive
    }

    /**
     * Launch-path provisioning. Returns the guest env to merge into the PRoot
     * launch — EMPTY whenever GPU must not be used (toggle off, binaries
     * absent, or server bring-up failed), so callers can merge unconditionally.
     *
     * Never throws: the launch path treats GPU as additive, and a thrown error
     * here would cost the user their terminal for a graphics preference.
     */
    suspend fun provisionForLaunch(
        workspaceId: String,
        rootfsPath: String,
        enabled: Boolean
    ): Map<String, String> = try {
        provisionInternal(workspaceId, rootfsPath, enabled)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$workspaceId: GPU provision threw — launching without GPU: ${e.message}")
        healthRegistry.reportDegraded(workspaceId, COMPONENT, "GPU provision failed: ${e.message}")
        emptyMap()
    }

    private suspend fun provisionInternal(
        workspaceId: String,
        rootfsPath: String,
        enabled: Boolean
    ): Map<String, String> {
        if (!enabled) {
            // Toggle off: no health entry (a disabled feature is not degraded),
            // and any server from a previous run of THIS workspace stays — the
            // stop path owns teardown, and an un-asked-for kill could race a
            // concurrent launch that still expects it.
            return emptyMap()
        }
        val availability = VirglHostServer.probe(nativeLibraryDir)
        if (!availability.isAvailable) {
            val detail = "GPU binaries are not bundled in this build (missing: " +
                availability.missing.joinToString(", ") + ") — guest keeps software rendering"
            Logger.w(TAG, "$workspaceId: $detail")
            healthRegistry.reportDegraded(workspaceId, COMPONENT, detail)
            runtimeTrace.record(workspaceId, "GPU: binaries absent — software rendering")
            return emptyMap()
        }
        val lock = startLocks.computeIfAbsent(workspaceId) { Mutex() }
        lock.withLock {
            runnings[workspaceId]?.let { existing ->
                if (existing.process.isAlive) return VirglHostServer.guestLaunchEnv()
                // Dead runtime still registered (crash already reported by the
                // watcher): drop it and fall through to a fresh attempt — the
                // user explicitly (re)started the workspace.
                runnings.remove(workspaceId, existing)
            }
            return startServer(workspaceId, availability.binaryPath!!, rootfsPath)
        }
    }

    private suspend fun startServer(
        workspaceId: String,
        binaryPath: String,
        rootfsPath: String
    ): Map<String, String> {
        val socketDir = VirglHostServer.hostSocketDir(rootfsPath)
        val socketFile = File(socketDir, VirglHostServer.SOCKET_FILE_NAME)
        val logDir = logDirFor(workspaceId)
        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, LOG_FILE_NAME)
        try {
            // A stale socket from a crashed predecessor makes bind() fail;
            // the server does not unlink foreign sockets itself.
            socketDir.mkdirs()
            socketFile.delete()
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: could not prepare GPU socket dir: ${e.message}")
        }

        val process = try {
            processFactory.start(
                command = VirglHostServer.buildCommand(binaryPath, socketDir),
                env = VirglHostServer.buildHostEnv(nativeLibraryDir),
                logFile = logFile
            )
        } catch (e: Exception) {
            val detail = "GPU host server could not start: ${e.message}"
            Logger.w(TAG, "$workspaceId: $detail")
            healthRegistry.reportDegraded(workspaceId, COMPONENT, detail)
            runtimeTrace.record(workspaceId, "GPU: server exec failed")
            return emptyMap()
        }

        // Bounded wait for the server's socket. The server binds quickly
        // (EGL init is the slow part and happens per client, not at startup),
        // but a device where it wedges must not hold the launch path forever.
        val deadline = clockMs() + VirglHostServer.SOCKET_WAIT_MS
        var bound = false
        while (clockMs() < deadline) {
            if (!process.isAlive) break
            if (socketFile.exists()) { bound = true; break }
            delay(SOCKET_POLL_MS)
        }
        if (!bound) {
            val detail = if (process.isAlive) {
                "GPU host server did not bind its socket within ${VirglHostServer.SOCKET_WAIT_MS}ms"
            } else {
                "GPU host server exited before binding its socket (see ${logFile.path})"
            }
            Logger.w(TAG, "$workspaceId: $detail")
            runCatching { process.destroy() }
            healthRegistry.reportDegraded(workspaceId, COMPONENT, detail)
            runtimeTrace.record(workspaceId, "GPU: server socket wait failed")
            return emptyMap()
        }

        val stopping = AtomicBoolean(false)
        val watcher = scope.launch {
            // Exit watcher: crash detection WITHOUT a restart loop. An
            // unexpected death is reported once; recovery is the next explicit
            // workspace start, exactly like §1.83's daemon watcher semantics.
            while (process.isAlive) delay(WATCHER_POLL_MS)
            if (!stopping.get()) {
                val detail = "GPU host server exited unexpectedly (guest GL falls back to software)"
                Logger.w(TAG, "$workspaceId: $detail")
                healthRegistry.reportDegraded(workspaceId, COMPONENT, detail)
                runtimeTrace.record(workspaceId, "GPU: server exited unexpectedly")
            }
        }
        val runtime = GpuRuntime(process, socketFile, socketDir, watcher, stopping)
        runnings[workspaceId] = runtime
        healthRegistry.reportHealthy(workspaceId, COMPONENT)
        runtimeTrace.record(workspaceId, "GPU: host server running (pid ${process.pid()})")
        Logger.i(TAG, "$workspaceId: GPU host server started (pid ${process.pid()})")
        return VirglHostServer.guestLaunchEnv()
    }

    /**
     * Env for one-shot execs: merge-only-if-running. A `glxinfo` typed into a
     * running workspace should see the GPU env, but a package-manager exec
     * must never silently spin up a GPU server — sessions and daemons own
     * that decision.
     */
    fun envIfRunning(workspaceId: String): Map<String, String> {
        if (!isRunning(workspaceId)) return emptyMap()
        return VirglHostServer.guestLaunchEnv()
    }

    /**
     * Full teardown for [workspaceId]: destroy the process, cancel the
     * watcher, remove the socket file, forget the runtime. Idempotent; the
     * log file is kept for diagnostics.
     */
    suspend fun stopFor(workspaceId: String) {
        val runtime = runnings.remove(workspaceId) ?: return
        runtime.stopping.set(true)
        runtime.watcher?.cancel()
        runtime.process.destroy()
        try {
            runtime.socketFile.delete()
            if (runtime.socketDir.isDirectory && runtime.socketDir.list().isEmpty()) {
                runtime.socketDir.delete()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: GPU socket cleanup failed: ${e.message}")
        }
        runtimeTrace.record(workspaceId, "GPU: host server stopped")
        Logger.i(TAG, "$workspaceId: GPU host server stopped")
    }

    /**
     * Recovery sweep: SIGKILL any of this app's own orphaned server processes
     * left by a previous process death (children are not auto-killed by
     * Android). Scans own-uid `/proc` entries only — hidepid guarantees no
     * foreign process is even visible. Returns the number of kills.
     */
    fun killStaleServers(): Int {
        val dir = File("/proc")
        var killed = 0
        dir.listFiles { f -> f.name.toIntOrNull() != null }?.forEach { entry ->
            try {
                val cmdline = File(entry, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                if (cmdline.contains(VirglHostServer.BINARY_FILE)) {
                    val pid = entry.name.toInt()
                    Os.kill(pid, android.system.OsConstants.SIGKILL)
                    killed++
                    Logger.w(TAG, "recovery sweep: killed stale GPU server pid=$pid")
                }
            } catch (_: Exception) {
                // A vanished pid, a permission error, or a zombie — all normal
                // for a /proc scan; the sweep is best-effort by contract.
            }
        }
        return killed
    }

    companion object {
        private const val TAG = "GpuSupervisor"
        private const val LOG_FILE_NAME = "gpu-virgl-server.log"
        private const val SOCKET_POLL_MS = 100L
        private const val WATCHER_POLL_MS = 2_000L
    }
}

/**
 * The narrow seam the supervisor uses to spawn and observe the host server —
 * the GPU counterpart of what [com.fem.linexa.service.runtime.pty.PtySessionFactory]
 * does for the PTY, so JVM tests run the real supervisor logic against fakes.
 */
interface GpuProcessFactory {
    fun start(command: List<String>, env: Map<String, String>, logFile: File): GpuProcess
}

/** A live-or-not handle over the host server process (test-seam friendly). */
interface GpuProcess {
    val isAlive: Boolean

    /** The host pid, for diagnostics and traces. */
    fun pid(): Int

    /** Best-effort stop; the supervisor never relies on this succeeding. */
    fun destroy()
}

/** Production [GpuProcessFactory] over `ProcessBuilder`. */
class DefaultGpuProcessFactory : GpuProcessFactory {
    override fun start(command: List<String>, env: Map<String, String>, logFile: File): GpuProcess {
        if (!logFile.exists()) logFile.createNewFile()
        val pb = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        // Full-replacement env, same discipline as every other launch path.
        pb.environment().clear()
        pb.environment().putAll(env)
        val process = pb.start()
        return object : GpuProcess {
            override val isAlive: Boolean get() = process.isAlive
            override fun pid(): Int = runCatching {
                process.javaClass.getMethod("pid").invoke(process) as Int
            }.getOrDefault(-1)
            override fun destroy() {
                process.destroy()
            }
        }
    }
}
