package com.fem.linexa.service.runtime

import android.app.Application
import android.os.StatFs
import com.fem.linexa.core.filesystem.deleteRecursivelyAllowingReadOnly
import com.fem.linexa.data.repository.WorkspaceDiskStore
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.domain.workspace.RootfsInspector
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the directory structure for Workspaces.
 * Each workspace lives at: {app-files}/workspaces/{id}/
 * Rootfs at: {app-files}/workspaces/{id}/rootfs/
 * Staging area (§1.20 staged installer): {app-files}/workspaces/{id}/.staging-rootfs/
 *
 * §9A (Phase 9): also implements the domain port [RootfsInspector] — the two
 * disk facts `WorkspaceLaunchValidator` needs (rootfs present + non-empty).
 * Interface refinement only: the same methods serve the service callers (by
 * concrete type) and the domain validator (through the port), so the
 * `domain → service` import is gone without touching a call site. Paths
 * never cross the port: both methods take the workspace ID (Phase 9B keeps
 * this class the single path authority).
 *
 * §9C (Phase 9, OPT-ARCH-003): also implements the repository's disk store
 * [WorkspaceDiskStore] — the layout/deletion/size-cache primitives
 * `WorkspaceRepositoryImpl` consumes. Same refinement pattern: existing
 * members serve, plus one new member ([deleteWorkspaceTree]) that wraps the
 * read-only-tolerant deletion so the repository no longer imports the
 * concrete class at all.
 */
@Singleton
class WorkspaceFileManager @Inject constructor(
    private val application: Application
) : RootfsInspector, WorkspaceDiskStore {
    private val workspacesBase: File
        get() = File(application.filesDir, "workspaces")

    override fun getWorkspaceBasePath(workspaceId: String): File {
        return File(workspacesBase, workspaceId)
    }

    fun getRootfsPath(workspaceId: String): File {
        return File(getWorkspaceBasePath(workspaceId), "rootfs")
    }

    /**
     * Staging directory an install extracts into (§1.20). Never the live
     * rootfs a running Workspace uses — [activateStagedRootfs] is the only
     * thing that promotes it, and only after full extraction succeeds.
     */
    fun getStagingRootfsPath(workspaceId: String): File {
        return File(getWorkspaceBasePath(workspaceId), ".staging-rootfs")
    }

    fun deleteStagingIfExists(workspaceId: String) {
        getStagingRootfsPath(workspaceId).deleteRecursivelyAllowingReadOnly()
    }

    /**
     * Where a distro download's bytes land while in flight — a sibling of
     * the staging-rootfs directory, same reasoning: never overlap with
     * anything InstallRecoveryManager or the live rootfs looks at. Kept
     * across process death on purpose (unlike staging-rootfs, which
     * StagedRootFsInstaller always clears at the start of a fresh
     * `install()`) — this is exactly what makes a resumed download
     * possible after the app or the whole device restarts mid-transfer:
     * [com.fem.linexa.service.distro.HttpDistroDownloader]
     * checks this file's existing length and asks the server for a Range
     * starting there instead of redownloading from zero.
     */
    fun getDownloadCachePath(workspaceId: String, fileName: String): File {
        val dir = File(getWorkspaceBasePath(workspaceId), ".download-cache")
        dir.mkdirs()
        return File(dir, fileName)
    }

    fun deleteDownloadCache(workspaceId: String) {
        File(getWorkspaceBasePath(workspaceId), ".download-cache").deleteRecursively()
    }

    /**
     * Atomically (from an observer's point of view — see body) promotes a
     * fully-extracted staging directory to be the live rootfs. Uses a
     * rename, not a copy: staging and live are siblings on the same
     * filesystem (`{app-files}/workspaces/{id}/`), so `File.renameTo` is a
     * single filesystem-level rename, not a byte-by-byte copy.
     *
     * If a live rootfs already exists (reinstall), it's moved aside first
     * and only deleted after the new one is confirmed in place — so a
     * failure partway through never leaves the workspace with *no* rootfs
     * at all.
     */
    fun activateStagedRootfs(workspaceId: String): Boolean {
        val staging = getStagingRootfsPath(workspaceId)
        if (!staging.exists()) return false
        val live = getRootfsPath(workspaceId)

        val previousLive = File(live.parentFile, "rootfs.previous-${System.currentTimeMillis()}")
        val hadPreviousLive = live.exists()
        if (hadPreviousLive && !live.renameTo(previousLive)) {
            return false // couldn't make room; staging is untouched, caller can retry
        }

        val activated = staging.renameTo(live)
        if (!activated) {
            if (hadPreviousLive) previousLive.renameTo(live) // best-effort restore
            return false
        }

        if (hadPreviousLive) previousLive.deleteRecursivelyAllowingReadOnly() // best-effort; not load-bearing
        // The live rootfs just changed under us — any cached size for this
        // workspace is now stale and must be recomputed on next read.
        invalidateRootfsSize(workspaceId)
        return true
    }

    /** Workspace IDs with a directory on disk — used by [com.fem.linexa.service.installer.InstallRecoveryManager] at startup. */
    fun listWorkspaceIds(): List<String> {
        return workspacesBase.listFiles { file -> file.isDirectory }?.map { it.name } ?: emptyList()
    }

    /**
     * Ensure the workspace directory structure exists.
     * Creates rootfs directory if it doesn't exist.
     */
    override fun ensureWorkspaceDirs(workspaceId: String): File {
        val rootfs = getRootfsPath(workspaceId)
        rootfs.mkdirs()
        return rootfs
    }

    /**
     * §9C: the [WorkspaceDiskStore] deletion primitive — removes the
     * workspace's base tree, tolerating read-only directories (Phase 01's
     * "disk first, row second" contract). `false` means something survived;
     * the repository re-checks existence to distinguish "never existed"
     * from "could not remove".
     */
    override fun deleteWorkspaceTree(workspaceId: String): Boolean =
        getWorkspaceBasePath(workspaceId).deleteRecursivelyAllowingReadOnly()

    /**
     * Check if a workspace has a valid rootfs (contains essential Linux dirs).
     */
    override fun hasValidRootfs(workspaceId: String): Boolean {
        val rootfs = getRootfsPath(workspaceId)
        if (!rootfs.exists()) return false
        val essentialDirs = listOf("bin", "etc", "lib", "usr", "var")
        return essentialDirs.all { File(rootfs, it).exists() }
    }

    /**
     * Per-workspace in-memory cache of rootfs sizes. Disk usage is expensive
     * to compute (a full recursive walk of a multi-GB rootfs), and callers
     * (Home screen, Workspace detail, the launch validator) ask for it
     * frequently. Sizes only change when the live rootfs is promoted
     * ([activateStagedRootfs]) or the workspace is deleted
     * ([invalidateRootfsSize] from the repository), so a simple
     * invalidate-on-mutation cache is both safe and correct.
     */
    private val rootfsSizeCache = ConcurrentHashMap<String, Long>()

    /**
     * Same cache, for the *whole* workspace directory ([getWorkspaceSize]).
     * Separate map rather than a second entry in the one above so the two
     * measurements can never be confused for one another — they answer
     * different questions and differ by however much the logs and the
     * persisted `config.yaml` weigh.
     */
    private val workspaceSizeCache = ConcurrentHashMap<String, Long>()

    /**
     * Drop the cached sizes for [workspaceId] (e.g. after delete).
     *
     * Invalidates both the rootfs-only and the whole-workspace measurement:
     * every mutation that changes one changes the other, and a caller that
     * remembered only one of two invalidation calls would leave a stale number
     * on screen — the exact class of bug the single entry point avoids.
     */
    override fun invalidateRootfsSize(workspaceId: String) {
        rootfsSizeCache.remove(workspaceId)
        workspaceSizeCache.remove(workspaceId)
    }

    /**
     * Get the total size of a workspace's rootfs in bytes.
     *
     * Cached: a full walk of a Debian/Ubuntu rootfs is thousands of files;
     * the Home screen and the launch validator both query this per-workspace
     * on every state emission. The cache is invalidated on every mutation
     * path (rootfs promotion + workspace delete), so a cached value is never
     * stale from the app's own writes.
     */
    override fun getRootfsSize(workspaceId: String): Long {
        val rootfs = getRootfsPath(workspaceId)
        if (!rootfs.exists()) return 0L
        // computeIfAbsent keeps the walk single-flight per workspace even when
        // Home and the detail screen (or validator) query simultaneously.
        return rootfsSizeCache.computeIfAbsent(workspaceId) { calculateDirSize(rootfs) }
    }

    /**
     * Total bytes this workspace occupies on disk — the whole
     * `{app-files}/workspaces/{id}/` directory, not just its rootfs.
     *
     * UI Phase 2: the Home card advertises "total installed Workspace size",
     * and the rootfs alone is not that. The workspace directory also holds the
     * persisted `config.yaml` (§1.26), the per-workspace `logs/` that every
     * install writes (§1.53 — a failed install's `fixup.log` can be
     * substantial), and any `.download-cache` a resumable download left behind
     * (§1.20: kept across process death **on purpose**, so it is real occupied
     * space the user cannot see anywhere else). Reporting only the rootfs
     * understates what deleting the workspace would actually reclaim, which is
     * the number a size on a card is used to decide.
     *
     * Transient install state is included rather than filtered for exactly that
     * reason: `.staging-rootfs` bytes are on the disk while an install runs, and
     * a card that hid them would show a workspace growing to its final size only
     * after the promotion rename. Cached and invalidated on the same mutation
     * paths as [getRootfsSize].
     */
    fun getWorkspaceSize(workspaceId: String): Long {
        val base = getWorkspaceBasePath(workspaceId)
        if (!base.exists()) return 0L
        return workspaceSizeCache.computeIfAbsent(workspaceId) { calculateDirSize(base) }
    }

    /**
     * This workspace's storage reading: what it occupies, and what is actually
     * left on the volume it lives on (UI/UX Phase 5B).
     *
     * The free-space half is deliberately measured rather than assumed. Before
     * Phase 5B the only storage figure in the UI was
     * `Workspace.storageUsedFraction`, whose own KDoc calls it "purely
     * cosmetic" — it divides the workspace size by a hardcoded 10 GB. Inside a
     * panel captioned "Storage" that is a fabricated metric: the same 3 GB
     * workspace shows an identical bar on a full 32 GB phone and on an empty
     * 512 GB one. [android.os.StatFs] on the workspace directory gives the real
     * remaining space for the app's own volume.
     *
     * `availableBytes`, not `freeBytes`: the former already excludes the reserve
     * this uid may not touch, so it is the space this workspace could actually
     * grow into. Free space is **not** cached — it changes for reasons outside
     * this app entirely (other apps, photos, the OS), so a cached value would be
     * stale in a way no invalidation hook here could ever detect. Only the size
     * walk is cached, by [getWorkspaceSize].
     */
    fun getWorkspaceStorage(workspaceId: String): WorkspaceStorage {
        val base = getWorkspaceBasePath(workspaceId)
        val used = getWorkspaceSize(workspaceId)
        // StatFs needs an existing path: a workspace directory that has not been
        // created yet still has meaningful free space, so fall back to filesDir
        // (the same volume) rather than reporting the whole metric unknown.
        val statTarget = if (base.exists()) base else application.filesDir
        val free = runCatching { StatFs(statTarget.absolutePath).availableBytes }
            .getOrNull()
            ?.takeIf { it >= 0L }
        return WorkspaceStorage(usedBytes = used, deviceFreeBytes = free)
    }

    /**
     * Sum of regular files under [root], **without following symlinks**.
     *
     * The old implementation used `File.isDirectory`/`File.length()`, both of
     * which follow symlinks on Android. A Debian merged-usr rootfs ships
     * `/bin -> usr/bin`, `/lib -> usr/lib`, `/sbin -> usr/sbin`,
     * `/lib64 -> usr/lib64`, and §1.26's `proot --link2symlink` extraction
     * turns hardlinks into more symlinks — so the naive walk recursed into
     * the same shared subtree over and over (a real 2.6 GB rootfs displayed
     * as ~30 GB) and, given a symlink cycle, could recurse without bound.
     *
     * `Files.walkFileTree` never follows symbolic links by default, so it
     * (a) never descends into symlinked directories, (b) is cycle-safe by
     * construction, and (c) counts only real regular files via the
     * `BasicFileAttributes` size, so each inode contributes exactly once.
     * Unreadable subtrees are skipped, never fatal.
     *
     * Used for both the rootfs-only and the whole-workspace measurement: the
     * symlink rules that make a rootfs walk correct apply verbatim to its
     * parent directory, which contains it.
     */
    private fun calculateDirSize(root: File): Long {
        var size = 0L
        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // walkFileTree doesn't follow links, so a symlink
                        // reaches us as the link itself (attrs.isRegularFile
                        // == false) and is skipped here — no double counting,
                        // no cycles.
                        if (attrs.isRegularFile) {
                            size += attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // Skip unreadable entries (permissions, broken links)
                        // instead of aborting the whole walk.
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        } catch (_: Exception) {
            // Best-effort: return whatever was accumulated so far.
        }
        return size
    }
}
