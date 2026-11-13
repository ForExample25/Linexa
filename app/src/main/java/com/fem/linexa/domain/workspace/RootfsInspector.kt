package com.fem.linexa.domain.workspace

/**
 * Phase 9A (OPT-ARCH-001) — the domain's vocabulary for "what is actually on
 * disk for this workspace".
 *
 * `WorkspaceLaunchValidator` (the single source of truth for "is this
 * workspace actually launchable?") needs exactly two facts about the rootfs:
 * does it exist with its essential directories, and is it non-empty. Before
 * this port it got them by importing `service.runtime.WorkspaceFileManager`
 * — the second half of the `domain → service` cycle (the first half is the
 * command-execution port, `domain.runtime.CommandExecutor`).
 *
 * The port is deliberately NARROW (the two methods the validator calls,
 * nothing more): no path derivation, no staging paths, no deletion, no size
 * invalidation — those are service-level file-management concerns. Paths
 * themselves are never passed across this port: both methods take the
 * workspace ID, so the service layer stays the single authority for where a
 * rootfs lives (Phase 9B keeps that true).
 *
 * Implemented by `service.runtime.WorkspaceFileManager` (its existing
 * `hasValidRootfs`/`getRootfsSize` bodies already match; a `@Binds` in
 * `AppModule` supplies the singleton, so the validator's Hilt factory
 * references a domain type, not the service class).
 */
interface RootfsInspector {

    /**
     * True when the workspace's rootfs directory exists and contains the
     * essential guest directories (`bin`, `etc`, `lib`, `usr`, `var`) —
     * the "extraction completed and was not wiped" check.
     */
    fun hasValidRootfs(workspaceId: String): Boolean

    /**
     * Cached byte size of the rootfs (a full recursive walk, memoized per
     * workspace until the rootfs is promoted or deleted); `0` when the
     * rootfs does not exist.
     */
    fun getRootfsSize(workspaceId: String): Long
}
