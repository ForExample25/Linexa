package com.fem.linexa.service.runtime

import android.app.Application
import com.fem.linexa.domain.filemanager.RootfsView
import com.fem.linexa.domain.filemanager.WorkspaceRootfsLocator
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-003 — the production [WorkspaceRootfsLocator].
 *
 * It lives in the service layer because the workspace layout and the config
 * parser are service concerns; the file manager only sees the domain port.
 *
 * The exclusion list is the difference between "the rootfs directory on the
 * host" and "what the guest actually sees". PRoot overlays these guest paths
 * with host-backed binds, so their host directories inside the rootfs are
 * not authoritative:
 *
 * - `/dev`, `/proc`, `/sys` — host kernel interfaces;
 * - `/run` — rootfs-owned bind (kept excluded anyway: cheap to be exact);
 * - `/mnt/shared` — the app's external shared folder;
 * - every `linexa.launch.bindMounts` guest destination from `config.yaml`.
 */
@Singleton
class WorkspaceRootfsViewLocator @Inject constructor(
    private val application: Application,
    private val configParser: WorkspaceConfigParser
) : WorkspaceRootfsLocator {

    override fun viewFor(workspaceId: String): RootfsView? {
        val base = File(File(application.filesDir, "workspaces"), workspaceId)
        val rootfs = File(base, "rootfs")
        if (!rootfs.isDirectory) return null
        val configFile = File(base, "config.yaml")
        val stamp = listOf(rootfs.lastModified(), configFile.lastModified())
        cache[workspaceId]?.let { cached ->
            if (cached.stamp == stamp && cached.view.rootfs.isDirectory) return cached.view
        }
        val extraBinds = runCatching {
            configParser.load(configFile)
                ?.launch
                ?.bindMounts
                .orEmpty()
                .map { it.guest }
        }.getOrDefault(emptyList())
        val view = RootfsView(
            rootfs = rootfs,
            excludedGuestPrefixes = BUILTIN_EXCLUDES + extraBinds
        )
        cache[workspaceId] = Cached(stamp, view)
        return view
    }

    private data class Cached(val stamp: List<Long>, val view: RootfsView)

    private val cache = ConcurrentHashMap<String, Cached>()

    private companion object {
        val BUILTIN_EXCLUDES = listOf("/dev", "/proc", "/sys", "/run", "/mnt/shared")
    }
}
