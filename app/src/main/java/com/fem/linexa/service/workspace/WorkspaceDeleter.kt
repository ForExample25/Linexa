package com.fem.linexa.service.workspace

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.RuntimeLifecycleManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.117 — the ONE path every workspace deletion funnels through:
 * **stop the runtime first, then delete the disk.**
 *
 * ## Why this exists
 *
 * Both delete call sites used to call [WorkspaceRepository.deleteById]
 * directly, which does `workspaceDao.deleteById(id)` followed by
 * `getWorkspaceBasePath(id).deleteRecursively()`. Neither stopped the runtime,
 * and the Danger Zone one is reachable *only from inside a live workspace* —
 * entering a workspace always starts its session
 * (`WorkspaceNavGraph`'s entry `LaunchedEffect` → `startWorkspace`). So
 * Settings → Danger Zone → Delete recursively unlinked `{workspace}/` while
 * PRoot and its child shells were still executing inside
 * `{workspace}/rootfs`.
 *
 * The only stop was the *later* `RuntimeViewModel.onCleared` →
 * `stopWorkspace`, which fires after `onDeleted()` has already popped the
 * graph to Home. That ordering has two real consequences:
 *
 * 1. **Orphaned directories.** A live guest process writing during or after
 *    `deleteRecursively()` recreates paths under a tree that has already been
 *    walked. `deleteRecursively` is a top-down walk with no retry, so anything
 *    recreated behind it simply survives — leaking exactly the disk the
 *    delete was supposed to free (the leak `WorkspaceRepositoryImpl.deleteById`
 *    was written to fix in the first place).
 * 2. **A torn-down runtime pointed at nothing.** The session keeps running for
 *    the moments between the DB row vanishing and `onCleared` arriving, with
 *    its rootfs disappearing underneath it.
 *
 * Contrast the *leave* path, which already gets this right: the exit
 * confirmation calls `stopWorkspace` before `onBack()`.
 *
 * ## Ordering contract
 *
 * 1. [RuntimeLifecycleManager.requestStop] — the same single teardown path
 *    ACTION_STOP, the FGS notification's Stop action, and workspace-leave all
 *    use. It is serialized on the manager's transition mutex, idempotent, and
 *    already returns `true` for an untouched/never-started workspace, so this
 *    is safe for a Broken workspace that was never running.
 * 2. Only if that teardown **verifiably completed** do we touch disk.
 * 3. [WorkspaceRepository.deleteWorkspace], which removes the on-disk tree
 *    (tolerating read-only directories) and deletes the row only after the
 *    disk result is known. A disk failure keeps the row so Retry is possible.
 *
 * **A failed teardown aborts the delete.** `requestStop` returns false when
 * teardown failed or hit its bound (`DEFAULT_TEARDOWN_TIMEOUT_MS`), which
 * means guest processes may still be live; deleting the rootfs out from under
 * them is precisely the corruption this class exists to prevent. Refusing is
 * recoverable — the workspace is still there and the user can retry — whereas
 * a half-deleted tree with a live writer is not. The failure reason is
 * returned so the UI can surface it instead of silently doing nothing.
 */
@Singleton
class WorkspaceDeleter @Inject constructor(
    private val lifecycleManager: RuntimeLifecycleManager,
    private val repository: WorkspaceRepository,
) {

    /**
     * Stops [workspaceId]'s runtime and then deletes it (DB row + disk).
     *
     * Returns [Outcome.Deleted] only when the workspace is genuinely gone,
     * [Outcome.StopFailed] when teardown could not be confirmed — in which
     * case **nothing was deleted** — or [Outcome.DeleteFailed] when the
     * teardown succeeded but the on-disk tree could not be removed (the row
     * is kept so the user can retry).
     */
    suspend fun delete(workspaceId: String): Outcome {
        Logger.i(TAG, "delete($workspaceId): stopping runtime before touching disk")

        val stopped = try {
            lifecycleManager.requestStop(workspaceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "delete($workspaceId): teardown threw: ${e.message}")
            false
        }

        if (!stopped) {
            val reason = "Could not stop the workspace before deleting it; " +
                "nothing was deleted. Try again."
            Logger.w(TAG, "delete($workspaceId) ABORTED: $reason")
            return Outcome.StopFailed(reason)
        }

        // Pre-release audit fix (2026-09-02, finding U2/H-2): refuse to delete
        // a workspace whose install/download pipeline is still running. The
        // old flow deleted the row + directory while ImportService /
        // DownloadService / InstallCoordinator kept writing — the pipeline
        // re-created `.download-cache` (permanently orphaned: the startup
        // recovery sweep never removed it), burned bandwidth for the rest of
        // the download, and reported progress into a void (the status writes
        // no-op on a deleted row). The user sees the existing status card and
        // can delete once the install finishes or fails.
        val status = repository.getById(workspaceId)?.status
        if (status is WorkspaceStatus.Importing) {
            val reason = "This workspace is being installed right now. " +
                "Wait for the install to finish (or fail), then delete it."
            Logger.w(TAG, "delete($workspaceId) ABORTED: install in progress")
            return Outcome.StopFailed(reason)
        }

        when (val result = repository.deleteWorkspace(workspaceId)) {
            WorkspaceDeletionResult.Deleted -> {
                Logger.i(TAG, "delete($workspaceId): DB row + disk removed")
                return Outcome.Deleted
            }
            WorkspaceDeletionResult.NotFound -> {
                // The confirm dialog can be double-tapped; the second call must
                // behave like the first from the user's point of view.
                Logger.i(TAG, "delete($workspaceId): already gone")
                return Outcome.Deleted
            }
            is WorkspaceDeletionResult.DiskFailure -> {
                Logger.w(TAG, "delete($workspaceId) FAILED: ${result.reason}")
                return Outcome.DeleteFailed(result.reason)
            }
        }
    }

    /** Result of a [delete]. */
    sealed interface Outcome {
        /** The workspace's DB row and on-disk contents are gone. */
        data object Deleted : Outcome

        /**
         * Teardown could not be confirmed, so the delete was refused.
         * Nothing was removed — neither the DB row nor any file.
         */
        data class StopFailed(val reason: String) : Outcome

        /**
         * Teardown succeeded but the on-disk tree could not be removed.
         * The database row was kept so the workspace stays visible and the
         * user can retry once the storage problem is fixed.
         */
        data class DeleteFailed(val reason: String) : Outcome
    }

    private companion object {
        const val TAG = "WorkspaceDeleter"
    }
}
