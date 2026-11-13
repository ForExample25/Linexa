package com.fem.linexa.domain.workspace

import kotlinx.serialization.Serializable

/**
 * Persisted status of a Workspace — answers "does this Workspace exist in a
 * usable state on disk?" Survives process death and app restarts (§1.16).
 *
 * Stored as a Room column on [WorkspaceMetadata].
 */
@Serializable
sealed interface WorkspaceStatus {
    /** DB record exists, rootfs not materialized yet. */
    @Serializable
    data object Created : WorkspaceStatus

    /** Rootfs is being extracted. [progress] is 0f..1f. */
    @Serializable
    data class Importing(val progress: Float) : WorkspaceStatus

    /** Rootfs present, not yet validated/launched. */
    @Serializable
    data object Installed : WorkspaceStatus

    /** Validated, launchable. RuntimeService.start() requires this. */
    @Serializable
    data object Ready : WorkspaceStatus

    /** Corrupted rootfs, interrupted import, failed restore. */
    @Serializable
    data class Broken(val reason: String) : WorkspaceStatus

    /** Currently running a repair operation. */
    @Serializable
    data object Repairing : WorkspaceStatus

    /** Currently being deleted. */
    @Serializable
    data object Deleting : WorkspaceStatus
}
