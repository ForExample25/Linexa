package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.installer.DesktopDetectionCache
import com.fem.linexa.service.installer.DesktopEnvironmentProbe
import com.fem.linexa.ui.screens.desktop.XvncProvisioner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/**
 * The ONE desktop-installation engine.
 *
 * Every in-app path that installs a desktop environment goes through
 * [install]: the Features guided installer and the implicit install that
 * "Start Desktop" performs on a rootfs without a session. Both therefore share
 * the same package definitions ([DesktopPreset.installCommand]), the same
 * package-manager resolution ([XvncProvisioner]), the same target (the
 * workspace's own rootfs — there is no second prefix), the same per-workspace
 * exclusion, the same verification and the same result type.
 *
 * Design rules:
 * - **Idempotent.** A preset whose session launcher is already on disk is
 *   reported [Result.AlreadyInstalled]; nothing is run.
 * - **One install per workspace at a time.** A second concurrent request gets
 *   [Result.Busy] immediately instead of racing the first one on the package
 *   manager's own lock.
 * - **Never "installed" because a command exited 0.** Success requires the
 *   session launcher to exist in the rootfs afterwards ([Result.VerifyFailed]
 *   otherwise).
 * - **Cancellation-safe.** The lock is released in `finally`, and
 *   [CancellationException] is rethrown untouched.
 * - **Testable.** Guest execution is injected as functions, so the whole
 *   decision flow runs in a plain JVM test.
 *
 * Not covered (and documented as such in the engineering report): a
 * guest-terminal command that reaches this service — the guest shell has no IPC
 * channel back into the app today.
 */
@Singleton
class DesktopInstallationService @Inject constructor() {

    sealed interface Result {
        /** Already present; nothing was run. */
        data class AlreadyInstalled(val sessionPath: String) : Result

        /** Installed and verified on disk. */
        data class Installed(val sessionPath: String) : Result

        /** Another install for this workspace is running. */
        data object Busy : Result

        /** No preset, no supported package manager, or no install command for this distro. */
        data class Unsupported(val reason: String) : Result

        /** The package manager failed; [reason] is the real output tail. */
        data class Failed(val reason: String, val exitCode: Int) : Result

        /** The command exited 0 but no session launcher appeared. */
        data object VerifyFailed : Result
    }

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** True while an install for [workspaceId] holds the lock. */
    fun isInstalling(workspaceId: String): Boolean = locks[workspaceId]?.isLocked == true

    /**
     * @param exec one-shot guest command (package-manager probe).
     * @param execStreaming streaming guest command; each output line goes to its callback.
     * @param onLine receives every output line of the install, for progress UIs.
     */
    suspend fun install(
        workspaceId: String,
        preset: DesktopPreset,
        rootfs: File,
        exec: suspend (String) -> CommandResult,
        execStreaming: suspend (String, (String) -> Unit) -> CommandResult,
        onLine: (String) -> Unit = {}
    ): Result {
        if (preset == DesktopPreset.NONE) return Result.Unsupported("No desktop environment selected")

        sessionOnDisk(rootfs, preset)?.let { return Result.AlreadyInstalled(it) }

        val lock = locks.getOrPut(workspaceId) { Mutex() }
        if (!lock.tryLock()) return Result.Busy
        try {
            val detection = exec(XvncProvisioner.DETECT_PACKAGE_MANAGER_COMMAND)
            val pmKey = XvncProvisioner.packageManagerKey(detection.output)
                ?: return Result.Unsupported("No supported package manager in this rootfs")
            val command = DesktopPreset.installCommand(preset, pmKey)
                ?: return Result.Unsupported("${preset.displayName} cannot be installed with $pmKey")

            Logger.i(TAG, "Installing ${preset.id} for $workspaceId via $pmKey")
            val tail = ArrayDeque<String>()
            val result = execStreaming(command) { line ->
                if (line.isNotBlank()) {
                    tail.addLast(line.trim())
                    while (tail.size > TAIL_LINES) tail.removeFirst()
                }
                onLine(line)
            }
            // A changed rootfs invalidates the cached capability, success or not
            // (a half-finished install still changed what is on disk).
            DesktopDetectionCache.invalidate(rootfs)
            if (result.exitCode != 0) {
                val reason = result.error.ifBlank { result.output }.ifBlank { tail.joinToString("\n") }
                    .lines().filter { it.isNotBlank() }.takeLast(TAIL_LINES).joinToString("\n").take(MAX_REASON_CHARS)
                return Result.Failed(reason.ifBlank { "exit code ${result.exitCode}" }, result.exitCode)
            }
            val session = sessionOnDisk(rootfs, preset) ?: return Result.VerifyFailed
            return Result.Installed(session)
        } catch (e: CancellationException) {
            DesktopDetectionCache.invalidate(rootfs)
            throw e
        } finally {
            lock.unlock()
        }
    }

    /** The launcher check is file I/O, so it never runs on the caller's (possibly Main) dispatcher. */
    private suspend fun sessionOnDisk(rootfs: File, preset: DesktopPreset): String? =
        withContext(Dispatchers.IO) { DesktopEnvironmentProbe.anySessionPresent(rootfs, preset) }

    private companion object {
        const val TAG = "DesktopInstallationService"
        const val TAIL_LINES = 8
        const val MAX_REASON_CHARS = 600
    }
}
