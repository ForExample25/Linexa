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
 * The port is deliberately NARROW (the facts the validator calls, nothing
 * more): no path derivation, no staging paths, no deletion, no size
 * invalidation — those are service-level file-management concerns. Paths
 * themselves are never passed across this port: every method takes the
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
     * Detailed launch-readiness check. [RootfsCheck.Missing] means the rootfs
     * directory or its essential layout is absent; [RootfsCheck.Damaged] means
     * the layout exists but the launch shell can no longer run (deleted,
     * non-executable, or an unresolvable interpreter).
     */
    fun checkRootfs(workspaceId: String): RootfsCheck

    /**
     * True when the workspace's rootfs directory exists and contains a
     * launchable guest shell, i.e. [checkRootfs] is [RootfsCheck.Ready] —
     * the "extraction completed and was not damaged afterwards" check.
     */
    fun hasValidRootfs(workspaceId: String): Boolean

    /**
     * Cached byte size of the rootfs (a full recursive walk, memoized per
     * workspace until the rootfs is promoted or deleted); `0` when the
     * rootfs does not exist.
     *
     * Still the right call for DISPLAY (Home card, detail screen). Readiness
     * decisions must use [hasAnyRegularFile], not `getRootfsSize() <= 0` —
     * see P0-3.
     */
    fun getRootfsSize(workspaceId: String): Long

    /**
     * P0-3 (2026-11-23): true when the rootfs contains at least one regular
     * file. Short-circuits at the first file and never follows symlinks, so a
     * readiness/install gate no longer pays for a full size walk. A rootfs
     * with only directories or only symlinks returns false, matching the size
     * walk's accounting.
     */
    fun hasAnyRegularFile(workspaceId: String): Boolean
}

/** Result of [RootfsInspector.checkRootfs]. */
sealed interface RootfsCheck {
    data object Ready : RootfsCheck

    /** The rootfs directory or one of its essential directories is absent. */
    data class Missing(val reason: String) : RootfsCheck

    /** The layout exists, but the launch shell cannot be executed. */
    data class Damaged(val reason: String) : RootfsCheck
}
