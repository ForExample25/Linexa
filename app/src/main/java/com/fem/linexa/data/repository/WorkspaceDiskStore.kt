package com.fem.linexa.data.repository

import java.io.File

/**
 * Phase 9C (OPT-ARCH-003) — the workspace repository's disk vocabulary.
 *
 * `WorkspaceRepositoryImpl` used to mix four responsibilities in one class:
 * DB row persistence, filesystem layout, secret (de)cryption, and the row
 * mutation mutex. This interface carves out the **disk** responsibility so
 * the repository reads it as a collaborator, not as code it owns:
 *
 *  - [ensureWorkspaceDirs] / [getWorkspaceBasePath] — the layout, derived
 *    from the workspace ID ([com.fem.linexa.service.runtime.WorkspaceFileManager]
 *    stays the single path authority — Phase 9B);
 *  - [deleteWorkspaceTree] — the deletion primitive, tolerating read-only
 *    directories (Phase 01: "disk first, row second");
 *  - [invalidateRootfsSize] — the post-deletion cache drop, so a re-install
 *    re-measures instead of resurrecting a stale size.
 *
 * The DELETION ORDER itself (stop the runtime → install guard → disk → row)
 * is not a repository concern and is not here: `service.workspace.WorkspaceDeleter`
 * (§1.117) is the one path every user-facing deletion funnels through, and
 * `WorkspaceRepository.deleteWorkspace` keeps its disk-first/row-second
 * contract on top of this store.
 *
 * Implemented by [com.fem.linexa.service.runtime.WorkspaceFileManager] as an
 * interface refinement (the same zero-churn pattern as `RootfsInspector`):
 * existing members match, callers keep passing the concrete singleton, and
 * repository tests can drive the repository with a fake store. The
 * interface lives next to its consumer (`data.repository`) because these
 * are repository-internal primitives, not domain vocabulary.
 */
interface WorkspaceDiskStore {

    /** Create `{files}/workspaces/{id}/rootfs` (and parents); returns the rootfs dir. */
    fun ensureWorkspaceDirs(workspaceId: String): File

    /** The workspace's base directory — `{files}/workspaces/{id}/` — created or not. */
    fun getWorkspaceBasePath(workspaceId: String): File

    /**
     * Remove the workspace's base tree, tolerating read-only directories.
     * `false` means something survived; the caller re-checks existence to
     * distinguish "never existed" from "could not remove".
     */
    fun deleteWorkspaceTree(workspaceId: String): Boolean

    /** Drop any cached rootfs size for [workspaceId] (post-delete, pre-reinstall). */
    fun invalidateRootfsSize(workspaceId: String)
}
