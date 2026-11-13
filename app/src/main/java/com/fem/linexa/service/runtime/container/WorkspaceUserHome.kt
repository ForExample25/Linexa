package com.fem.linexa.service.runtime.container

import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.WorkspaceFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single application-side authority for "where is the Workspace user's
 * home?".
 *
 * It deliberately does NOT use the Android process environment, `System.getenv`,
 * or the uid the root-exec bridge happens to run under. A root exec is an
 * implementation detail for privileged operations; its `uid 0` says nothing
 * about which Workspace identity the user is browsing. The selected identity is
 * the account declared by the installed image, resolved against the guest's own
 * `/etc/passwd` (see [WorkspaceHomeLayout.resolveHomes]).
 *
 * Consumers:
 *  - the File Manager overview (Home and Root Home places);
 *  - desktop provisioning (the VNC keyfile lives under the real home);
 *  - any future surface that needs identity-aware navigation.
 *
 * Resolution reads at most `/etc/passwd` and the cached RootFS descriptor, so
 * it is cheap; callers on the main thread should use [resolve], which hops to
 * the IO dispatcher.
 */
@Singleton
class WorkspaceUserHome @Inject constructor(
    private val fileManager: WorkspaceFileManager,
    private val workspaceRootFsMetadata: WorkspaceRootFsMetadata
) {

    /** Identity-aware homes for [workspaceId], off the main thread. */
    suspend fun resolve(workspaceId: String): WorkspaceHomeLayout.WorkspaceHomes =
        withContext(Dispatchers.IO) { resolveBlocking(workspaceId) }

    /**
     * Identity-aware homes for [workspaceId].
     *
     * Fail-open: a missing workspace/rootfs/passwd yields the declared (or
     * default) normal-user home — never `/root`, because that would repeat the
     * exact conflation this class exists to remove.
     */
    fun resolveBlocking(workspaceId: String): WorkspaceHomeLayout.WorkspaceHomes {
        val declared = runCatching { workspaceRootFsMetadata.of(workspaceId) }.getOrNull()
        val environment = GuestEnvironmentResolver.resolve(declared)
        val rootfs = runCatching { fileManager.getRootfsPath(workspaceId) }.getOrNull()
        return WorkspaceHomeLayout.resolveHomes(
            rootfs = rootfs,
            declaredUser = environment.user,
            declaredHome = environment.home
        )
    }
}