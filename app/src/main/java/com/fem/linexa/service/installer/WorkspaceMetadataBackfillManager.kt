package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.runtime.WorkspaceFileManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup metadata backfill: repairs workspaces that predate attribute
 * persistence by probing their **live rootfs on disk** and recording the
 * attributes the UI resolves from persisted metadata.
 *
 * Two independent fixes, one pass:
 *  - `distroId` (audit item 4): workspaces created before/without
 *    [DownloadService.ATTR_DISTRO_ID] (e.g. file-imports, or rows written
 *    before the attribute existed) show the generic letter avatar forever.
 *    [RootFsIdentityReader] re-resolves the real distro from `etc/os-release`
 *    and the legacy marker files, so existing Debian workspaces get their
 *    branded icon without a reinstall.
 *  - `desktop` capability (audit item 2): nothing ever wrote the desktop
 *    capability flag, so [WorkspaceCapabilities.desktopEnabled] resolved
 *    false for every workspace and the (fully functional) Desktop button
 *    never appeared. [RootFsCapabilityProbe] checks the rootfs for a
 *    supported package manager — the exact dependency Xvnc auto-install
 *    needs — and records it deterministically. 2026-09-02 (desktop
 *    prerequisites): the flag now also requires an actual desktop session
 *    launcher ([RootFsCapabilityProbe.hasDesktopCapability]), and the
 *    backfill REFRESHES an existing flag when it disagrees with the live
 *    rootfs — a stale `true` from the package-manager-only era (or a
 *    desktop the user removed) must not keep a fake-capable button lit.
 *    Refresh is still cheap (a handful of stats) and still fails open.
 *
 * Deliberately conservative:
 *  - Only touches workspaces whose persisted status is `Ready`.
 *  - Only writes an attribute that is currently absent — never overwrites a
 *    value the install path or the user already set.
 *  - Best-effort and fail-open: an unreadable rootfs leaves the workspace
 *    untouched (it keeps its letter avatar / no desktop button), matching
 *    the existing "never fake a button" rule.
 */
@Singleton
class WorkspaceMetadataBackfillManager @Inject constructor(
    private val fileManager: WorkspaceFileManager,
    private val repository: WorkspaceRepository
) {
    /**
     * Call once, early in app startup (see `LinexaApplication.onCreate`,
     * alongside [InstallRecoveryManager.recoverInterruptedInstalls]). Cheap:
     * a few small file reads per workspace, only for Ready workspaces missing
     * either attribute — plus, since Phase 9B, one string comparison per
     * Ready workspace that repairs a persisted `rootfsPath` which drifted
     * from the path the workspace ID derives to (the field is diagnostic;
     * this keeps it honest for exports/debug captures).
     */
    suspend fun backfillReadyWorkspaces() {
        val workspaceIds = fileManager.listWorkspaceIds()
        for (workspaceId in workspaceIds) {
            val metadata = repository.getById(workspaceId) ?: continue
            if (metadata.status !is WorkspaceStatus.Ready) continue

            val rootfs = fileManager.getRootfsPath(workspaceId)
            val updatedAttributes = mutableMapOf<String, String>()

            // Phase 9B (OPT-ARCH-002): the ID is the single rootfs identity;
            // the persisted field is diagnostic and must never disagree with
            // the derived path. A drifted row (legacy writer, manual edit, a
            // future layout change) is repaired here — the launch path would
            // ignore the stored value either way (the service derives its
            // own), so this is bookkeeping, not launch behavior. `mutate`
            // reads-transforms-writes under the row mutex (Phase 05).
            val derivedRootfsPath = rootfs.absolutePath
            if (metadata.rootfsPath != derivedRootfsPath) {
                Logger.w(
                    "MetadataBackfill",
                    "Repairing stale rootfsPath for $workspaceId: ${metadata.rootfsPath} -> $derivedRootfsPath"
                )
                repository.mutate(workspaceId) { it.copy(rootfsPath = derivedRootfsPath) }
            }

            if (!metadata.attributes.containsKey(DownloadService.ATTR_DISTRO_ID)) {
                RootFsIdentityReader.catalogDistroId(rootfs)?.let { catalogId ->
                    updatedAttributes[DownloadService.ATTR_DISTRO_ID] = catalogId
                }
            }
            if (!metadata.attributes.containsKey(WorkspaceCapabilities.ATTR_DESKTOP)) {
                val desktop = RootFsCapabilityProbe.hasDesktopCapability(rootfs)
                updatedAttributes[WorkspaceCapabilities.ATTR_DESKTOP] = desktop.toString()
            } else {
                // 2026-09-02: refresh a persisted flag that disagrees with
                // the live rootfs. Caches are never the sole source of truth
                // (desktop prerequisites, Phase 2 §4.6): a manual install or
                // removal between app runs changes the filesystem, and only
                // a re-derivation keeps the coarse flag honest. A matching
                // value costs one probe and one string comparison.
                val desktop = RootFsCapabilityProbe.hasDesktopCapability(rootfs)
                if (metadata.attributes[WorkspaceCapabilities.ATTR_DESKTOP] != desktop.toString()) {
                    updatedAttributes[WorkspaceCapabilities.ATTR_DESKTOP] = desktop.toString()
                }
            }

            if (updatedAttributes.isNotEmpty()) {
                Logger.i(
                    "MetadataBackfill",
                    "Backfilled $workspaceId: $updatedAttributes"
                )
                // Phase 05: targeted merge under the row mutex. The old full-row
                // `update` re-persisted the snapshot read before the probes ran,
                // reverting any concurrent writer's changes made in between.
                repository.updateAttributes(workspaceId, updatedAttributes)
            }
        }
    }
}
