package com.fem.linexa.service.runtime

import android.app.Application
import android.os.StatFs
import com.fem.linexa.core.filesystem.deleteRecursivelyAllowingReadOnly
import com.fem.linexa.data.repository.WorkspaceDiskStore
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.domain.workspace.RootfsCheck
import com.fem.linexa.domain.workspace.RootfsInspector
import com.fem.linexa.service.runtime.container.GuestShellValidator
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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

    /** The launcher-owned config at the workspace root (`config.yaml`). */
    fun getWorkspaceConfigPath(workspaceId: String): File =
        File(getWorkspaceBasePath(workspaceId), "config.yaml")

    /**
     * RF-031: config is written here before activation and promoted only
     * after the live rootfs exists, so a failed activation never leaves a
     * `config.yaml` for a rootfs that was never installed. Dot-prefixed so
     * no config reader can mistake it for the real file.
     */
    fun getStagedConfigPath(workspaceId: String): File =
        File(getWorkspaceBasePath(workspaceId), ".config.yaml.staged")

    /**
     * RF-031: finish the post-activation config step. If the workspace has no
     * live rootfs, the staged file is orphaned debris (activation never
     * happened) and is removed. Symlinked or non-regular staged files are
     * ignored — this path is only ever written by the installer.
     *
     * @return true when a staged config became `config.yaml`.
     */
    fun promoteStagedConfigIfPresent(workspaceId: String): Boolean {
        val stagedPath = getStagedConfigPath(workspaceId).toPath()
        if (!Files.isRegularFile(stagedPath, LinkOption.NOFOLLOW_LINKS)) return false
        val staged = stagedPath.toFile()
        val liveExists = Files.exists(
            getRootfsPath(workspaceId).toPath(),
            LinkOption.NOFOLLOW_LINKS
        )
        if (!liveExists) {
            staged.delete()
            return false
        }
        val target = getWorkspaceConfigPath(workspaceId)
        if (staged.renameTo(target)) return true
        // Rename within the same directory should not fail; copy is the
        // conservative fallback so a valid rootfs is not left without config.
        return runCatching {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }.isSuccess
    }

    /**
     * RF-031: drop a staged config whose activation never happened.
     *
     * Startup recovery uses this when a staging tree is still present: the
     * staging rename is the activation, so the live rootfs (if any) is the
     * PREVIOUS tree and the staged config describes the tree about to be
     * discarded. Promoting it there would leave `config.yaml` describing a
     * rootfs that is not installed.
     *
     * @return true when a staged file existed and was removed.
     */
    fun discardStagedConfigIfPresent(workspaceId: String): Boolean =
        runCatching {
            Files.deleteIfExists(getStagedConfigPath(workspaceId).toPath())
        }.getOrDefault(false)

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

    // ── RF-021: crash-swap and containment debris ─────────────────────────

    /**
     * The old live rootfs trees left by [activateStagedRootfs]'s
     * live→`rootfs.previous-<ms>` swap. Normally deleted immediately after a
     * successful activation; a process death in the swap window leaves one
     * behind, and a failed post-swap delete leaves a multi-gigabyte tree.
     */
    fun getPreviousRootfsPaths(workspaceId: String): List<File> =
        debrisChildren(workspaceId, PREVIOUS_ROOTFS_PREFIX)

    /**
     * Trees the extractor renamed aside when it could not prove a timed-out
     * or cancelled process tree was gone (`.staging-rootfs.quarantine-<ms>`).
     * They are deliberately outside the installer's path; nothing will ever
     * reuse or delete them on its own.
     */
    fun getQuarantinedStagingPaths(workspaceId: String): List<File> =
        debrisChildren(workspaceId, STAGING_QUARANTINE_PREFIX)

    /**
     * RF-021: recover the activation swap window. If the live rootfs is gone
     * but an older tree was already renamed to `rootfs.previous-*`, restore
     * the newest one instead of leaving the workspace with nothing runnable.
     *
     * NOFOLLOW is deliberate: a dangling link still occupies the live name,
     * and renaming over it would silently replace user-visible state.
     *
     * @return the restored live rootfs path, or null when nothing was done.
     */
    fun restorePreviousRootfsIfMissing(workspaceId: String): File? {
        val live = getRootfsPath(workspaceId)
        if (Files.exists(live.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val previous = getPreviousRootfsPaths(workspaceId)
            .lastOrNull { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            ?: return null
        if (!previous.renameTo(live)) return null
        invalidateRootfsSize(workspaceId)
        return live
    }

    /**
     * RF-021: remove crash debris in a workspace. Intended for startup
     * recovery, when no install can be running in this process; [minAgeMs]
     * additionally protects fresh entries for callers that sweep while work
     * may still be in flight.
     *
     * Guards, in order:
     *  - only direct children whose name is the exact prefix plus a numeric
     *    timestamp (no traversal, no user-chosen names);
     *  - symlinks are never followed, and are only unlinked (RF-020's
     *    deletion primitive);
     *  - entries newer than [minAgeMs] are skipped;
     *  - quarantines are skipped while a staging tree exists, because that
     *    means a newer install is using the same workspace.
     */
    fun sweepInstallDebris(
        workspaceId: String,
        minAgeMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): InstallDebrisSweep {
        val restored = restorePreviousRootfsIfMissing(workspaceId)
        val cutoff = nowMs - minAgeMs

        val deletedPrevious = getPreviousRootfsPaths(workspaceId)
            .filter { it.timestampSuffix(PREVIOUS_ROOTFS_PREFIX) <= cutoff }
            .filter { it.deleteRecursivelyAllowingReadOnly() }

        val stagingActive = Files.exists(
            getStagingRootfsPath(workspaceId).toPath(),
            LinkOption.NOFOLLOW_LINKS
        )
        val deletedQuarantine =
            if (stagingActive) {
                emptyList()
            } else {
                getQuarantinedStagingPaths(workspaceId)
                    .filter { it.timestampSuffix(STAGING_QUARANTINE_PREFIX) <= cutoff }
                    .filter { it.deleteRecursivelyAllowingReadOnly() }
            }

        return InstallDebrisSweep(
            restoredPrevious = restored,
            deletedPrevious = deletedPrevious,
            deletedStagingQuarantine = deletedQuarantine
        )
    }

    private fun debrisChildren(workspaceId: String, prefix: String): List<File> {
        val base = getWorkspaceBasePath(workspaceId)
        return base.listFiles().orEmpty()
            .filter { child ->
                child.parentFile == base &&
                    child.name.startsWith(prefix) &&
                    child.name.length > prefix.length &&
                    child.name.substring(prefix.length).all(Char::isDigit)
            }
            .sortedBy { it.timestampSuffix(prefix) }
    }

    private fun File.timestampSuffix(prefix: String): Long =
        name.removePrefix(prefix).toLongOrNull() ?: Long.MAX_VALUE

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
    override fun deleteWorkspaceTree(workspaceId: String): Boolean {
        val deleted = getWorkspaceBasePath(workspaceId).deleteRecursivelyAllowingReadOnly()
        if (deleted) invalidateRootfsSize(workspaceId)
        return deleted
    }

    /**
     * Check if a workspace has a valid rootfs (contains essential Linux dirs).
     */
    override fun checkRootfs(workspaceId: String): RootfsCheck {
        val rootfs = getRootfsPath(workspaceId)
        if (!rootfs.isDirectory) {
            return RootfsCheck.Missing("The rootfs directory is missing.")
        }
        // Layout requirements shared by all shipped profiles: Alpine omits
        // `var`, and Arch/Fedora are usr-merged, so `bin`/`lib` are accepted
        // either at the root or under `usr`.
        val required = listOf("etc", "usr")
        val missing = required.filter { !File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            return RootfsCheck.Missing(
                "The rootfs is missing required directories: ${missing.joinToString(", ")}."
            )
        }
        if (!File(rootfs, "bin").exists() && !File(rootfs, "usr/bin").exists()) {
            return RootfsCheck.Missing("The rootfs has no /bin or /usr/bin directory.")
        }
        if (!File(rootfs, "lib").exists() && !File(rootfs, "usr/lib").exists()) {
            return RootfsCheck.Missing("The rootfs has no /lib or /usr/lib directory.")
        }

        // RF-003: presence of the directories only proves extraction reached
        // its end. The installer also required a shell; package operations or
        // storage faults can remove it afterwards, and the old check let such
        // a rootfs reach PRoot and fail with a bare exit code. GuestShellValidator
        // resolves absolute guest links against the rootfs (never the host)
        // and checks the executable bit and interpreter.
        val rejections = LAUNCH_SHELL_CANDIDATES.map { candidate ->
            candidate to GuestShellValidator.validate(rootfs, "/$candidate")
        }
        if (rejections.any { it.second == null }) return RootfsCheck.Ready

        // "does not exist inside the rootfs" is the validator's reason for a
        // candidate that is absent. Every candidate being absent is the
        // generic "no launch shell" case below. A candidate that *does* exist
        // but names a missing ELF/shebang interpreter also contains "does not
        // exist"; that is a specific failure and must keep its detail.
        val specific = rejections.firstOrNull { (_, reason) ->
            reason != null && reason != "does not exist inside the rootfs"
        }
        return if (specific == null) {
            RootfsCheck.Damaged(
                "The rootfs has no launch shell (looked for " +
                    LAUNCH_SHELL_CANDIDATES.joinToString(", ") { "/$it" } + ")."
            )
        } else {
            RootfsCheck.Damaged(
                "The launch shell /${specific.first} cannot run: ${specific.second}."
            )
        }
    }

    override fun hasValidRootfs(workspaceId: String): Boolean =
        checkRootfs(workspaceId) is RootfsCheck.Ready

    /**
     * P0-3 (2026-11-23): short-circuiting "is there at least one real file?"
     * probe for launch readiness and post-install verification.
     *
     * The old gate was `getRootfsSize() <= 0`, which forced a full recursive
     * walk of the whole rootfs (and, after P0-2, a whole-workspace walk) just
     * to learn that the first file exists. This stops at the first regular
     * file. Symlinks are never followed and never count, matching the size
     * walk's accounting; a rootfs containing only directories or only
     * symlinks is still rejected.
     */
    override fun hasAnyRegularFile(workspaceId: String): Boolean {
        val rootfs = getRootfsPath(workspaceId)
        if (!Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        var found = false
        try {
            Files.walkFileTree(
                rootfs.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) {
                            found = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                }
            )
        } catch (_: Exception) {
            // Fail closed: a walk that could not start/continue proves nothing.
            return false
        }
        return found
    }

    /**
     * P0-2 (2026-11-23): per-workspace disk measurement. One traversal of the
     * workspace base answers BOTH the rootfs and the whole-workspace totals;
     * the cache is cancellable, single-flight and never stores a partial or
     * failed walk (see [SizeMeasurementCache]).
     */
    /**
     * F-010/F-037: the last measured size survives process death, so the
     * first Home paint after a cold start can show a number immediately and
     * revalidate in the background instead of walking every workspace first.
     */
    private val sizeStore = SizeMeasurementStore(
        File(application.filesDir, "workspace-sizes.properties")
    )
    private val sizeMeasurements = SizeMeasurementCache(
        onMeasured = sizeStore::write,
        warmDelayMs = WARM_DELAY_MS
    )

    /**
     * DRA-005 closure: workspaces whose stale-while-revalidate walk has already
     * been started in this process. A stop/re-entry cycle must not launch the
     * 2–7 s whole-tree walk again; the first read of a process still refreshes
     * the persisted value, and [MIN_REVALIDATE_INTERVAL_MS] is the TTL after
     * which a later read refreshes it again.
     */
    private val revalidatedThisProcess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Drop the cached sizes for [workspaceId] (e.g. after delete).
     *
     * Invalidates both the rootfs-only and the whole-workspace measurement:
     * every mutation that changes one changes the other, and a caller that
     * remembered only one of two invalidation calls would leave a stale number
     * on screen — the exact class of bug the single entry point avoids.
     * An in-flight walk is cancelled cooperatively and can never store its
     * (possibly partial) result after this call.
     */
    override fun invalidateRootfsSize(workspaceId: String) {
        sizeMeasurements.invalidate(workspaceId)
        sizeStore.invalidate(workspaceId)
    }

    /**
     * DEEP-AUDIT (2026-11-26): mark the cached size stale without making the
     * last known value unreadable.
     *
     * A workspace runtime stop is NOT a size mutation and, crucially, it is not
     * a request to read the size. The old stop path called
     * [invalidateRootfsSize], which erased the persisted value — so the next
     * entry into that workspace had no cached number and performed a blocking
     * full-tree walk inside the state build that gates the first Success
     * frame. Measured on the audited XFCE workspace: 74 377 files / 2.2 GB,
     * **3 927 ms**.
     *
     * This marks only the in-memory generation stale. The persisted number is
     * returned by the next reader, which then starts a background
     * revalidation ([SizeMeasurementCache.warm]). Delete/install paths still
     * call [invalidateRootfsSize], where the old number is genuinely
     * meaningless.
     */
    fun markRootfsSizeStale(workspaceId: String) {
        sizeMeasurements.markStale(workspaceId)
    }

    /**
     * Get the total size of a workspace's rootfs in bytes.
     *
     * Measured by the shared workspace-base traversal in [SizeMeasurementCache]
     * — the rootfs is a child of that base, so its bytes are attributed during
     * the same walk the workspace total needs. Zero means "unknown/missing"
     * exactly as before (missing rootfs, failed walk, cancelled walk).
     */
    override fun getRootfsSize(workspaceId: String): Long {
        val rootfs = getRootfsPath(workspaceId)
        if (!rootfs.exists()) return 0L
        return measurementsFor(workspaceId)?.rootfsBytes ?: 0L
    }

    /**
     * F-010/F-037: in-memory result → persisted result (while a background
     * revalidation runs) → blocking walk. The last step only happens on a
     * genuinely cold, never-measured workspace.
     */
    private fun measurementsFor(workspaceId: String): SizeMeasurementCache.Measurements? {
        sizeMeasurements.cached(workspaceId)?.let { return it }
        val base = getWorkspaceBasePath(workspaceId)
        val rootfs = getRootfsPath(workspaceId)
        val persisted = sizeStore.read(workspaceId)
        if (persisted != null) {
            // DRA-005 closure: serve the persisted answer and only start a
            // background walk if this process has not refreshed this workspace
            // yet, or the TTL expired. This is what keeps a stop/re-entry cycle
            // from paying the measured 7.1 s CPU walk again.
            val firstThisProcess = revalidatedThisProcess.add(workspaceId)
            val age = sizeStore.ageMillis(workspaceId)
            if (firstThisProcess || age == null || age >= MIN_REVALIDATE_INTERVAL_MS) {
                sizeMeasurements.warm(workspaceId, base, rootfs)
            }
            return persisted
        }
        return sizeMeasurements.get(workspaceId, base, rootfs)
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
        return measurementsFor(workspaceId)?.workspaceBytes ?: 0L
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

    private companion object {
        /**
         * DRA-005 closure: how long a stale size is left to the persisted
         * answer before the background revalidation walk starts. The measured
         * walk on the XFCE workspace is 2–4 s; starting it 2 s after the first
         * read keeps it out of the workspace entry + PRoot start window while
         * still refreshing promptly.
         */
        const val WARM_DELAY_MS = 2_000L

        /**
         * DRA-005 closure: how old a persisted size may be before the same
         * process walks it again (the first read of a process always
         * refreshes). The size is a cosmetic card figure; the walk is
         * multi-second, so a 10-minute TTL is a deliberate freshness/CPU
         * trade-off, not a correctness gate.
         */
        const val MIN_REVALIDATE_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * Union of the shipped profiles' shell candidates, ordered so a
         * POSIX shell wins before bash/busybox where both exist. Kept in the
         * same guest-path form as the profile YAML.
         */
        val LAUNCH_SHELL_CANDIDATES = listOf(
            "bin/sh",
            "usr/bin/sh",
            "bin/ash",
            "usr/bin/ash",
            "bin/bash",
            "usr/bin/bash",
            "bin/busybox"
        )

        /** RF-021: `rootfs.previous-<epoch-ms>` created by [activateStagedRootfs]. */
        const val PREVIOUS_ROOTFS_PREFIX = "rootfs.previous-"

        /** RF-021: `<staging>.quarantine-<epoch-ms>` created by the extractor. */
        const val STAGING_QUARANTINE_PREFIX = ".staging-rootfs.quarantine-"
    }
}

/** What [WorkspaceFileManager.sweepInstallDebris] actually removed (RF-021). */
data class InstallDebrisSweep(
    val restoredPrevious: File?,
    val deletedPrevious: List<File>,
    val deletedStagingQuarantine: List<File>
) {
    val deletedCount: Int get() = deletedPrevious.size + deletedStagingQuarantine.size
}
