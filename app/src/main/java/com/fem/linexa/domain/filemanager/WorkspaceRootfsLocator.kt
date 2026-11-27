package com.fem.linexa.domain.filemanager

import java.io.File

/**
 * F-003 — the host-side facts the file manager needs to answer a read-only
 * operation without launching PRoot.
 *
 * @property rootfs the workspace's live rootfs directory (the host side of
 *   the guest's `/`).
 * @property excludedGuestPrefixes guest paths whose host-side contents are
 *   NOT the same as the guest's, because PRoot bind-mounts something over
 *   them (`/dev`, `/proc`, `/sys`, `/run`, `/mnt/shared`, and any
 *   `launch.bindMounts` entry). Operations under these prefixes must keep
 *   using the guest.
 */
data class RootfsView(
    val rootfs: File,
    val excludedGuestPrefixes: List<String> = emptyList()
)

/**
 * F-003 — resolves a workspace id to the host directory that backs its guest
 * root filesystem.
 *
 * This is a domain port so [WorkspaceFileManagerImpl] can stay in the domain
 * package: the implementation lives in the service layer (which owns the
 * workspace layout and the config parser) and is bound in DI. Tests can
 * supply a temp directory without an Application.
 */
interface WorkspaceRootfsLocator {

    /** The host view of [workspaceId]'s rootfs, or null when none is installed. */
    fun viewFor(workspaceId: String): RootfsView?

    /** Default used by JVM tests and callers that have no workspace layer. */
    object None : WorkspaceRootfsLocator {
        override fun viewFor(workspaceId: String): RootfsView? = null
    }
}
