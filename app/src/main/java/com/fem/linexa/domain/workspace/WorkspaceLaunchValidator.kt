package com.fem.linexa.domain.workspace

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "is this Workspace actually launchable?"
 *
 * Before this existed, [WorkspaceStatus] was a well-modeled sealed type
 * (Created/Importing/Installed/Ready/Broken/Repairing/Deleting) that was
 * persisted correctly by ImportService/InstallRecoveryManager but never
 * *read* anywhere else — not by the Home screen, not by WorkspaceScreen, not
 * by RuntimeService. A workspace with a 0-byte rootfs, a failed install, or
 * no rootfs directory at all could still be opened and "launched" exactly
 * like a healthy one. This class exists so every launch path (UI and
 * service-level) asks the same question the same way, instead of each
 * caller re-deriving (or forgetting to derive) its own notion of "ready."
 *
 * Deliberately does not trust the persisted [WorkspaceStatus] by itself even
 * when it says [WorkspaceStatus.Ready] — it re-checks against
 * [RootfsInspector] (the domain port `WorkspaceFileManager` implements, §9A)
 * every time, because the DB row and the actual
 * on-disk rootfs can drift apart (storage cleared externally, a previous
 * process death mid-write that [com.fem.linexa.service.installer.InstallRecoveryManager]
 * didn't happen to catch, etc.). "The DB says Ready" is necessary but not
 * sufficient; this is the place that also checks sufficiency.
 */
@Singleton
class WorkspaceLaunchValidator @Inject constructor(
    private val rootfsInspector: RootfsInspector
) {

    fun evaluate(metadata: WorkspaceMetadata): WorkspaceReadiness {
        return when (val status = metadata.status) {
            is WorkspaceStatus.Ready -> checkDisk(metadata.id)
            is WorkspaceStatus.Installed -> checkDisk(metadata.id)
            is WorkspaceStatus.Importing -> WorkspaceReadiness.Installing(status.progress)
            is WorkspaceStatus.Created -> WorkspaceReadiness.Blocked(
                reason = "This workspace was created but installation never started.",
                canRetry = true
            )
            is WorkspaceStatus.Broken -> WorkspaceReadiness.Blocked(
                reason = status.reason,
                canRetry = true
            )
            is WorkspaceStatus.Repairing -> WorkspaceReadiness.Busy("Repairing")
            is WorkspaceStatus.Deleting -> WorkspaceReadiness.Busy("Deleting")
        }
    }

    /**
     * The actual "RootFS exists / extraction completed / shell is launchable"
     * checks the audit asked for. Runs regardless of what [WorkspaceStatus]
     * claims.
     */
    private fun checkDisk(workspaceId: String): WorkspaceReadiness {
        when (val check = rootfsInspector.checkRootfs(workspaceId)) {
            RootfsCheck.Ready -> Unit
            is RootfsCheck.Missing -> return WorkspaceReadiness.Blocked(
                reason = "${check.reason} This can happen if storage was cleared, or if the " +
                    "install was interrupted without being caught at startup.",
                canRetry = true
            )
            is RootfsCheck.Damaged -> return WorkspaceReadiness.Blocked(
                reason = "${check.reason} Reinstall or repair the workspace to restore it.",
                canRetry = true
            )
        }
        if (!rootfsInspector.hasAnyRegularFile(workspaceId)) {
            return WorkspaceReadiness.Blocked(
                reason = "The rootfs directory exists but contains no files.",
                canRetry = true
            )
        }
        return WorkspaceReadiness.Ready
    }
}

/** Result of [WorkspaceLaunchValidator.evaluate]. */
sealed interface WorkspaceReadiness {
    /** RootFS present, non-empty, required directories exist — safe to start a runtime session. */
    data object Ready : WorkspaceReadiness

    /** Extraction is actively in progress; not a failure, just not done yet. */
    data class Installing(val progress: Float) : WorkspaceReadiness

    /** Not launchable right now for a reason the user can act on (Retry / Delete). */
    data class Blocked(val reason: String, val canRetry: Boolean) : WorkspaceReadiness

    /** A background operation (repair/delete) owns this workspace right now; no user action applies. */
    data class Busy(val operation: String) : WorkspaceReadiness
}
