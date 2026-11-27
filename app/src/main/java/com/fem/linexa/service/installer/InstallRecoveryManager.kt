package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.RootfsCheck
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and cleans up installs interrupted by the app/process dying
 * mid-extraction (§1.20's "crash recovery" — see the roadmap for why this
 * is *detection and safe cleanup*, not true byte-level resume: the source
 * is a fully-local `content://` archive already fully readable at call
 * time, not a network download, so there's nothing to resume from a byte
 * offset — the honest recovery contract is "never leave debris, never
 * leave a workspace claiming a status it doesn't have."
 *
 * Signal used, deliberately without a separate state file: a workspace
 * whose staging directory exists but whose persisted status isn't `Ready`
 * can only mean [StagedRootFsInstaller.install] was interrupted before
 * finishing — a completed install always deletes its own staging directory
 * (success) or already discarded it (`failAndCleanUp` on failure/exception).
 * If both signals are ever inconsistent, that itself means something died
 * mid-write; treat it exactly the same as an interrupted install.
 */
@Singleton
class InstallRecoveryManager @Inject constructor(
    private val fileManager: WorkspaceFileManager,
    private val repository: WorkspaceRepository
) {
    /**
     * Call once, early in app startup (see `LinexaApplication.onCreate`).
     * Cheap: directory-existence + one DB row per workspace, never reads
     * rootfs contents.
     *
     * Pre-release audit extension (2026-09-02, finding F4): the staging
     * directory was the only interrupt signal, which leaked two more
     * interrupted-state classes forever:
     *  (a) a row stuck in `Importing` with NO staging — killed during the
     *      download, or between activation and the Ready write. It is now
     *      marked Broken and its partial download cache removed, so the
     *      Retry/Delete UI can act on it;
     *  (b) a workspace directory with NO DB row that carries only scratch
     *      (`.import-cache` / `.download-cache`) — killed mid-scan/mid-
     *      download before the row was inserted. The scratch is now deleted;
     *      a directory with any real content (rootfs/staging) is still left
     *      alone for manual inspection rather than guessed at.
     */
    suspend fun recoverInterruptedInstalls() {
        val workspaceIds = fileManager.listWorkspaceIds()
        for (workspaceId in workspaceIds) {
            // RF-021: if the process died inside activateStagedRootfs's
            // live→`rootfs.previous-*` swap, the workspace can be left with
            // no live rootfs at all. Restore the newest previous tree BEFORE
            // any status decision, so recovery judges the tree the user
            // actually has instead of declaring the workspace empty.
            val restored = fileManager.restorePreviousRootfsIfMissing(workspaceId)
            if (restored != null) {
                Logger.w(
                    "InstallRecovery",
                    "Restored prior rootfs for $workspaceId after an interrupted activation"
                )
            }

            // RF-031: a staged config only becomes `config.yaml` once the
            // staging rename has completed. The config is therefore promoted
            // in the `!stagingExists` branch below; while staging exists,
            // activation did not happen and the staged bytes describe a tree
            // that recovery is about to discard.
            val stagingExists = fileManager.getStagingRootfsPath(workspaceId).exists()
            if (!stagingExists) {
                if (fileManager.promoteStagedConfigIfPresent(workspaceId)) {
                    Logger.w("InstallRecovery", "Promoted staged config.yaml for $workspaceId")
                }
                // F4(a): no staging — but a row that still claims to be
                // mid-install can only mean the process died mid-download or
                // mid-activation. RF-030: distinguish those two cases by
                // validating the live rootfs instead of always blaming the
                // download.
                val metadata = repository.getById(workspaceId)
                if (metadata?.status is WorkspaceStatus.Importing) {
                    recoverInterruptedActivation(workspaceId)
                }
                sweepDebris(workspaceId)
                continue
            }

            if (fileManager.discardStagedConfigIfPresent(workspaceId)) {
                Logger.w(
                    "InstallRecovery",
                    "Discarded staged config.yaml for $workspaceId (activation never completed)"
                )
            }

            val metadata = repository.getById(workspaceId)
            if (metadata == null) {
                // Staging exists but there's no DB record at all for this ID —
                // debris from an install that died before its own DB insert
                // committed. Nothing references it; just remove it.
                Logger.w("InstallRecovery", "Orphaned staging dir with no workspace record: $workspaceId")
                fileManager.deleteStagingIfExists(workspaceId)
                // F4(b): the same killed-before-insert install also leaves its
                // tee'd import cache behind; clear it along with the staging.
                File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache").deleteRecursively()
                File(fileManager.getWorkspaceBasePath(workspaceId), ".download-cache").deleteRecursively()
                sweepDebris(workspaceId)
                continue
            }

            if (metadata.status !is WorkspaceStatus.Ready) {
                Logger.w(
                    "InstallRecovery",
                    "Interrupted install detected for $workspaceId (status was ${metadata.status}); marking Broken"
                )
                fileManager.deleteStagingIfExists(workspaceId)
                // §1.26: the native installer also tees the archive into
                // {workspace}/.import-cache during its scan pass — that is
                // scratch too, and a crash mid-scan leaves a partial copy.
                File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache").deleteRecursively()
                repository.updateStatus(
                    workspaceId,
                    WorkspaceStatus.Broken("Installation was interrupted. Tap Retry to reinstall, or delete this workspace.")
                )
            }
            // If status IS Ready with leftover staging present, that's an
            // installer bug (staging should already be gone on success) —
            // logged, but left alone rather than touching a workspace that
            // claims to be fine; deleting stale staging debris under a Ready
            // workspace is still safe and worth doing.
            else {
                Logger.w("InstallRecovery", "Ready workspace $workspaceId had stale staging debris; removing it")
                fileManager.deleteStagingIfExists(workspaceId)
            }
            sweepDebris(workspaceId)
        }
    }

    /**
     * RF-030: a row still `Importing` with no staging is either a killed
     * download or a killed post-activation install. The old code always chose
     * the download diagnosis — deleting the caches and marking a complete
     * rootfs Broken with the wrong reason. Validate the live tree first:
     *
     *  - `Ready` → the install actually finished; promote the row and keep it.
     *  - `Damaged` → say so, instead of claiming the download was interrupted.
     *  - `Missing` → the original download-interrupted diagnosis applies.
     */
    private suspend fun recoverInterruptedActivation(workspaceId: String) {
        val check = fileManager.checkRootfs(workspaceId)
        val base = fileManager.getWorkspaceBasePath(workspaceId)
        when (check) {
            RootfsCheck.Ready -> {
                Logger.w(
                    "InstallRecovery",
                    "Interrupted status detected for $workspaceId, but its rootfs is valid; promoting to Ready"
                )
                File(base, ".import-cache").deleteRecursively()
                File(base, ".download-cache").deleteRecursively()
                repository.updateStatus(workspaceId, WorkspaceStatus.Ready)
            }
            is RootfsCheck.Damaged -> {
                Logger.w(
                    "InstallRecovery",
                    "Rootfs for $workspaceId is installed but damaged: ${check.reason}"
                )
                File(base, ".import-cache").deleteRecursively()
                File(base, ".download-cache").deleteRecursively()
                repository.updateStatus(
                    workspaceId,
                    WorkspaceStatus.Broken(
                        "The rootfs was installed but is damaged: ${check.reason} " +
                            "Reinstall or repair this workspace."
                    )
                )
            }
            is RootfsCheck.Missing -> {
                Logger.w(
                    "InstallRecovery",
                    "Interrupted DOWNLOAD detected for $workspaceId (status Importing, no staging, no rootfs); marking Broken"
                )
                File(base, ".import-cache").deleteRecursively()
                File(base, ".download-cache").deleteRecursively()
                repository.updateStatus(
                    workspaceId,
                    WorkspaceStatus.Broken("Download was interrupted. Tap Retry to download again, or delete this workspace.")
                )
            }
        }
    }

    /**
     * RF-021: startup is the one point where no install can be writing in
     * this process, so crash-swap trees (`rootfs.previous-*`) and extraction
     * quarantines (`.staging-rootfs.quarantine-*`) are safe to reclaim.
     * Failures are logged, never fatal.
     */
    private fun sweepDebris(workspaceId: String) {
        val sweep = fileManager.sweepInstallDebris(workspaceId)
        if (sweep.restoredPrevious != null || sweep.deletedCount > 0) {
            Logger.i(
                "InstallRecovery",
                "Workspace $workspaceId debris sweep: restoredPrevious=${sweep.restoredPrevious != null} " +
                    "deletedPrevious=${sweep.deletedPrevious.size} " +
                    "deletedQuarantine=${sweep.deletedStagingQuarantine.size}"
            )
        }
    }
}
