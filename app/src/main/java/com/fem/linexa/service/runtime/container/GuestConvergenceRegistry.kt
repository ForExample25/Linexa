package com.fem.linexa.service.runtime.container

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P0-1 (2026-11-23 performance remediation): the ONE process-scoped record of
 * "the idempotent guest repair battery has already succeeded for this
 * workspace's current rootfs."
 *
 * The interactive session path has always had an equivalent skip
 * (`PRootContainerRuntime.repairConverged`, RN-003), but every app-driven
 * one-shot `exec` re-ran the full battery — five static `/proc` snapshots
 * immediately overwritten by five host overlays, an `id -G` spawn, dpkg/apt
 * scans and permission windows. `exec` is the File Manager, Package Manager,
 * feature-provisioner and installer-verification path, so that battery was
 * the dominant per-operation overhead (PERF-005 / IND-01).
 *
 * ## What the stamp means
 *
 * A stamp is written only after **every** step of a repair pass succeeded.
 * A fail-open step that failed leaves no stamp, so the next operation retries
 * instead of silently treating a half-initialized rootfs as converged.
 *
 * ## Invalidation
 *
 * - **Workspace stop / delete:** [PRootContainerRuntime.closeWorkspaceSessions]
 *   calls [invalidate].
 * - **Rootfs replacement:** the stamp carries the rootfs path *and* the
 *   filesystem identity (`BasicFileAttributes.fileKey`, i.e. inode/device
 *   where the platform exposes it). A promoted/recreated rootfs at the same
 *   path has a different key and cannot match. A stamp written when the
 *   filesystem exposed no key is path-validated only; the explicit
 *   invalidation paths above cover that fallback.
 * - **Explicit guest mutation:** `execInternal` invalidates before a package
 *   operation, so the next command re-runs the battery and repairs any
 *   partial dpkg state.
 * - **App upgrade / process restart:** the registry is in-memory, so a new
 *   process starts with no stamps.
 * - **Session boundary:** a successful session repair marks the stamp; a
 *   closed session invalidates it. A live interactive session can therefore
 *   not leave a stale stamp behind after it ends.
 *
 * ## What it must never do
 *
 * Suppress initialization that is actually required. Callers must still run
 * their cheap, lstat-gated liveness checks (`/tmp`, `/dev/shm`, `/run`) on a
 * cache hit, and must treat a missing/mismatched stamp as "run the battery".
 */
@Singleton
class GuestConvergenceRegistry @Inject constructor() {

    private data class Stamp(
        val rootfsPath: String,
        val fileKey: String?
    )

    private val stamps = ConcurrentHashMap<String, Stamp>()

    /**
     * Test seam: overrides the filesystem-identity read. Production never
     * assigns it. Keeping it a property (not a constructor parameter) keeps
     * Hilt's `@Inject constructor()` injection unchanged.
     */
    @Volatile
    internal var fileKeyProvider: (File) -> String? = ::readFileKey

    /** True when [rootfs] is the same tree for which the battery last succeeded. */
    fun isConverged(workspaceId: String, rootfs: File): Boolean {
        val stamp = stamps[workspaceId] ?: return false
        if (stamp.rootfsPath != rootfs.absolutePath) return false
        if (!isRealDirectory(rootfs)) return false
        val currentKey = fileKeyProvider(rootfs)
        // If either side could not read an identity, path validation is the
        // strongest check available. Both-present keys must agree exactly:
        // a rename of a new tree over the old path changes the key.
        return stamp.fileKey == null || currentKey == null || stamp.fileKey == currentKey
    }

    /** Records a fully successful repair pass for [rootfs]. */
    fun markConverged(workspaceId: String, rootfs: File) {
        if (!isRealDirectory(rootfs)) return
        stamps[workspaceId] = Stamp(
            rootfsPath = rootfs.absolutePath,
            fileKey = fileKeyProvider(rootfs)
        )
    }

    /** Drops the stamp for one workspace (stop, delete, package mutation). */
    fun invalidate(workspaceId: String) {
        stamps.remove(workspaceId)
    }

    /** Drops every stamp (tests / process-wide reset). */
    fun invalidateAll() {
        stamps.clear()
    }

    /** Test/diagnostic visibility; not a production decision input. */
    internal fun stampedWorkspaceIds(): Set<String> = stamps.keys.toSet()

    private fun isRealDirectory(rootfs: File): Boolean = try {
        Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    private companion object {
        fun readFileKey(rootfs: File): String? = try {
            Files.readAttributes(
                rootfs.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            ).fileKey()?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
