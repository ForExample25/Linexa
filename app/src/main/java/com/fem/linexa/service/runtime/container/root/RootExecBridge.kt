package com.fem.linexa.service.runtime.container.root

import com.fem.linexa.domain.runtime.CommandResult
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app-side half of the guest `sudo` bridge.
 *
 * A normal-user Linexa session cannot elevate itself with a real setuid
 * binary: PRoot emulates ids, it does not grant kernel privileges. What the
 * app *can* do is launch a separate `-0` PRoot process — the same mechanism
 * behind Root Shell and the package manager. This bridge exposes exactly that
 * capability to one non-interactive guest command at a time through the
 * mailbox defined by [RootExecProtocol].
 *
 * The class is deliberately Android-free (plain `java.io`/`java.nio` plus
 * coroutines) so its claim/stream/status state machine can be exercised by a
 * JVM test with a fake [execute] lambda. The PRoot launch itself is supplied
 * by `PRootContainerRuntime.execInternal`, which already owns package
 * serialization, dpkg health checks, the non-interactive env and timeouts.
 *
 * ## Lifecycle
 *
 * [start] is idempotent and writes the liveness marker only after the control
 * directory exists. [stop] removes the marker and stops polling, but does NOT
 * cancel an in-flight command: a detached terminal closing mid-`apt install`
 * must not leave the guest waiting on a `status` file that can never appear.
 * The in-flight job writes its status and exits on its own bound
 * (`OPERATION_TIMEOUT_SECONDS` in production).
 *
 * ## Replay safety
 *
 * A request is claimed with an atomic `createNewFile()` BEFORE the marker is
 * read or the command is executed. A bridge restart therefore never replays a
 * request that may already have mutated the guest, and concurrent bridges
 * (two terminals opening at once) cannot execute the same request twice.
 */
class RootExecBridge(
    private val workspaceId: String,
    val rootfsPath: String,
    /**
     * Runs [command] in the workspace's privileged, root-emulated context,
     * invoking [onLine] for every output line. Production passes
     * `execInternal(..., onLine, OPERATION_TIMEOUT_SECONDS)`.
     */
    private val execute: suspend (command: String, onLine: (String) -> Unit) -> CommandResult,
    /** Diagnostic sink; production wires the app logger and the runtime trace. */
    private val log: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = 100L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()
    private var pollJob: Job? = null
    private var scope: CoroutineScope? = null
    private var lastHeartbeatAt = 0L

    private val tmpDir: File get() = File(rootfsPath, "tmp")
    private val controlDir: File get() = File(rootfsPath, RootExecProtocol.CONTROL_DIR)
    private val markerFile: File get() = File(controlDir, RootExecProtocol.MARKER_FILE)
    private val heartbeatFile: File get() = File(controlDir, RootExecProtocol.HEARTBEAT_FILE)

    /** True while the poller is live. Exposed for tests and diagnostics. */
    val isRunning: Boolean
        get() = synchronized(lock) { pollJob?.isActive == true }

    /**
     * Creates the marker, sweeps abandoned request directories and starts the
     * poller. Failure is logged and leaves the bridge stopped: the guest then
     * gets the helper's actionable "root bridge is not running" message
     * instead of a hang.
     */
    fun start() {
        synchronized(lock) {
            if (pollJob?.isActive == true) return
            try {
                val control = controlDir
                if (!control.isDirectory && !control.mkdirs() && !control.isDirectory) {
                    log("root-exec bridge start failed: cannot create ${control.absolutePath}")
                    return
                }
                atomicWrite(
                    markerFile,
                    RootExecProtocol.markerContents(workspaceId, UUID.randomUUID().toString())
                )
                lastHeartbeatAt = 0L
                writeHeartbeat(force = true)
                sweepStaleRequests()
                val bridgeScope = CoroutineScope(SupervisorJob() + dispatcher)
                scope = bridgeScope
                pollJob = bridgeScope.launch { pollLoop() }
                log("root-exec bridge started for $workspaceId")
            } catch (e: Exception) {
                log("root-exec bridge start failed for $workspaceId: ${e.message}")
            }
        }
    }

    /** Stops accepting new requests and removes the liveness marker. */
    fun stop() {
        val job: Job?
        synchronized(lock) {
            job = pollJob
            pollJob = null
            scope = null
            // Delete under the same lock start() uses: a terminal racing open
            // cannot have its fresh marker removed by this stop.
            runCatching { Files.deleteIfExists(markerFile.toPath()) }
            runCatching { Files.deleteIfExists(heartbeatFile.toPath()) }
        }
        job?.cancel()
        log("root-exec bridge stopped for $workspaceId")
    }

    private suspend fun pollLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    writeHeartbeat()
                    scanOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("root-exec bridge scan failed for $workspaceId: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        } catch (_: CancellationException) {
            // Normal stop.
        }
    }

    /**
     * Refreshes the heartbeat counter at most every
     * [RootExecProtocol.HEARTBEAT_INTERVAL_MS]. A guest waiting on a claimed
     * request uses it to distinguish "still running" from "the app process
     * died and no status will ever appear".
     */
    private fun writeHeartbeat(force: Boolean = false) {
        synchronized(lock) {
            if (!force && pollJob?.isActive != true) return
            val now = nowMs()
            if (!force && now - lastHeartbeatAt < RootExecProtocol.HEARTBEAT_INTERVAL_MS) return
            lastHeartbeatAt = now
            try {
                if (controlDir.isDirectory) {
                    atomicWrite(heartbeatFile, "$now\n")
                }
            } catch (e: Exception) {
                log("root-exec bridge could not refresh heartbeat for $workspaceId: ${e.message}")
            }
        }
    }

    private fun scanOnce() {
        val bridgeScope = synchronized(lock) { scope } ?: return
        val tmp = runCatching { tmpDir.canonicalFile }.getOrNull() ?: return
        if (!tmp.isDirectory) return
        tmp.listFiles().orEmpty().forEach { candidate ->
            if (!RootExecProtocol.isRequestName(candidate.name)) return@forEach
            val dir = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            // A symlinked request directory resolves outside /tmp; reject it.
            if (dir.parentFile != tmp || !dir.isDirectory) return@forEach
            if (File(dir, RootExecProtocol.CLAIMED_FILE).exists()) return@forEach
            if (File(dir, RootExecProtocol.STATUS_FILE).exists()) return@forEach

            val commandFile = File(dir, RootExecProtocol.COMMAND_FILE)
            if (!commandFile.isFile || Files.isSymbolicLink(commandFile.toPath())) return@forEach
            val size = commandFile.length()
            if (size > RootExecProtocol.MAX_COMMAND_BYTES) {
                // Claim so the guest gets an immediate, explicit failure
                // instead of the 15-second accept timeout.
                if (File(dir, RootExecProtocol.CLAIMED_FILE).createNewFile()) {
                    log("root-exec bridge rejected ${dir.name}: command size $size bytes")
                    writeFailure(
                        dir,
                        "linexa: the root request is too large (${size} bytes; limit ${RootExecProtocol.MAX_COMMAND_BYTES})"
                    )
                }
                return@forEach
            }
            if (size <= 0L) return@forEach
            // Claim BEFORE execution; this is the replay/concurrency guard.
            if (!File(dir, RootExecProtocol.CLAIMED_FILE).createNewFile()) return@forEach
            // stop() may have cancelled the poller between listing and the
            // claim. Never leave a claimed request waiting on a status that
            // this bridge will not write. The check and launch share the
            // lock stop() takes, so a handler cannot be born cancelled.
            val launched = synchronized(lock) {
                if (pollJob?.isActive != true) {
                    false
                } else {
                    bridgeScope.launch { handle(dir) }
                    true
                }
            }
            if (!launched) {
                writeFailure(dir, "linexa: the root bridge stopped before it could run the command")
                return@forEach
            }
            log("root-exec bridge accepted ${dir.name} for $workspaceId")
        }
    }

    private suspend fun handle(dir: File) {
        val command = try {
            readCommand(File(dir, RootExecProtocol.COMMAND_FILE))
        } catch (e: Exception) {
            log("root-exec bridge could not read ${dir.name}: ${e.message}")
            writeFailure(dir, "linexa: the root bridge could not read the request command")
            return
        }
        if (command.isBlank()) {
            writeFailure(dir, "linexa: the root bridge received an empty command")
            return
        }

        val outputFile = File(dir, RootExecProtocol.OUTPUT_FILE)
        try {
            // Replace any pre-existing entry (including a symlink) with a fresh
            // regular file, then keep the stream open. Writes go to this inode,
            // so a later rename/symlink race cannot redirect them.
            Files.deleteIfExists(outputFile.toPath())
            Files.newOutputStream(
                outputFile.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { stream ->
                val onLine: (String) -> Unit = { line ->
                    try {
                        stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                        stream.flush()
                    } catch (_: IOException) {
                        // The terminal/guest went away; the command still owns
                        // its own lifetime and the status file records the result.
                    }
                }
                val result = try {
                    execute(command, onLine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLine("linexa: root bridge execution failed: ${e.message}")
                    CommandResult(
                        exitCode = RootExecProtocol.EXIT_BRIDGE_ERROR,
                        output = "",
                        error = e.message.orEmpty()
                    )
                }
                if (result.exitCode != 0 && result.output.isBlank() && result.error.isNotBlank()) {
                    onLine("linexa: ${result.error.trim()}")
                }
                stream.flush()
                writeStatus(dir, RootExecProtocol.normalizeExitCode(result.exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("root-exec bridge failed to run ${dir.name}: ${e.message}")
            writeFailure(dir, "linexa: the root bridge could not run the command: ${e.message}")
        }
    }

    private fun readCommand(file: File): String {
        val bytes = Files.readAllBytes(file.toPath())
        if (bytes.size > RootExecProtocol.MAX_COMMAND_BYTES) {
            throw IOException("command exceeds ${RootExecProtocol.MAX_COMMAND_BYTES} bytes")
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.indexOf('\u0000') >= 0) throw IOException("command contains NUL")
        return text.removeSuffix("\r").removeSuffix("\n")
    }

    private fun writeFailure(dir: File, message: String) {
        try {
            val outputFile = File(dir, RootExecProtocol.OUTPUT_FILE)
            if (!outputFile.exists()) {
                Files.write(
                    outputFile.toPath(),
                    "$message\n".toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
            }
        } catch (_: Exception) {
            // Best effort: the status below is the authoritative failure signal.
        }
        writeStatus(dir, RootExecProtocol.EXIT_BRIDGE_ERROR)
    }

    private fun writeStatus(dir: File, rawExitCode: Int) {
        val code = RootExecProtocol.normalizeExitCode(rawExitCode)
        val tmp = File(dir, ".status-${UUID.randomUUID()}")
        val status = File(dir, RootExecProtocol.STATUS_FILE)
        try {
            Files.write(
                tmp.toPath(),
                "$code\n".toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(
                    tmp.toPath(),
                    status.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), status.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log("root-exec bridge could not write status for ${dir.name}: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    /**
     * Start-up hygiene for requests abandoned by a killed app/guest. Only
     * directories that are already finished, or that were never claimed and
     * are at least [STALE_REQUEST_MS] old, are removed; a claimed-but-unfinished
     * request is left alone so a slow package operation is never deleted
     * under a still-live bridge.
     */
    private fun sweepStaleRequests() {
        val tmp = runCatching { tmpDir.canonicalFile }.getOrNull() ?: return
        if (!tmp.isDirectory) return
        val cutoff = nowMs() - STALE_REQUEST_MS
        tmp.listFiles().orEmpty().forEach { candidate ->
            if (!RootExecProtocol.isRequestName(candidate.name)) return@forEach
            val dir = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (dir.parentFile != tmp || !dir.isDirectory) return@forEach
            val status = File(dir, RootExecProtocol.STATUS_FILE).exists()
            val claimed = File(dir, RootExecProtocol.CLAIMED_FILE).exists()
            val old = dir.lastModified() in 1..cutoff
            // A claimed request whose bridge/app process died never receives a
            // status and is never torn down by its guest (that shell died with
            // it). It can never be legitimately running this long: production
            // execs are bounded by OPERATION_TIMEOUT_SECONDS.
            val abandoned = dir.lastModified() in 1..(nowMs() - ABANDONED_REQUEST_MS)
            if (abandoned || (old && (status || !claimed))) {
                runCatching { dir.deleteRecursively() }
                    .onSuccess { log("root-exec bridge swept stale request ${dir.name}") }
            }
        }
    }

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(
                tmp.toPath(),
                contents.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    companion object {
        /** Abandoned, never-claimed requests are swept after one hour. */
        private const val STALE_REQUEST_MS = 60L * 60L * 1000L

        /** Any request directory this old is unreachable and swept (24 h). */
        private const val ABANDONED_REQUEST_MS = 24L * 60L * 60L * 1000L
    }
}
