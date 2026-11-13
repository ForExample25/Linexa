package com.fem.linexa.data.repository

import com.fem.linexa.data.room.WorkspaceDao
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DB-facing `WorkspaceRepository` implementation — the **DB store** of
 * the Phase 9C (OPT-ARCH-003) separation:
 *
 *  - **DB + concurrency (HERE):** all row persistence through [workspaceDao],
 *    and [rowMutationMutex] — the one lock every row mutation funnels
 *    through. The locking order is: this is the ONLY lock; a `mutate`
 *    transform must never call back into the repository (one mutex, no
 *    nesting) and must not perform file/network I/O (precompute outside).
 *  - **Disk (`WorkspaceDiskStore`):** layout, deletion and size-cache
 *    invalidation are delegated. The deletion ORDER itself (stop the
 *    runtime → install guard → disk → row) is owned by
 *    `service.workspace.WorkspaceDeleter` (§1.117); this class keeps
 *    `deleteWorkspace`'s disk-first/row-second contract on top of the store.
 *  - **Secrets (`WorkspaceSecretStore`):** reveal on every read, seal on
 *    every write, legacy migration in [migrateLegacySecrets].
 *
 * `WorkspaceRepository` remains the consumer-facing contract — no caller
 * above this package sees any of the three stores.
 */
@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    private val workspaceDao: WorkspaceDao,
    private val diskStore: WorkspaceDiskStore,
    private val secrets: WorkspaceSecretStore
) : WorkspaceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pre-release audit fix (2026-09-02, finding U9/M-6): every read-modify
     * -write row mutation (updateFeatureToggles / updateAttributes / the
     * ViewModel-side getById+copy+update merges) ran unserialized — two
     * concurrent writers each read the same base row and the last write won,
     * silently dropping the other writer's keys/toggles. All row-level
     * mutations are funnelled through this mutex; the merge logic reads its
     * base row INSIDE the critical section.
     *
     * Phase 05: `update` and `updateStatus` joined the funnel too, and the
     * new [mutate] is the atomic primitive the remaining ViewModel/service
     * call sites use. Locking order: this is the ONLY lock — a `mutate`
     * transform must never call back into the repository (one mutex, no
     * nesting), and must not perform file/network I/O (precompute outside).
     */
    private val rowMutationMutex = Mutex()

    // P11: secret attributes (the desktop VNC password) are encrypted at rest. Every
    // read below hands callers the decrypted form and every write persists the
    // encrypted form, so nothing above this class changes. See WorkspaceSecretAttributes.
    private fun WorkspaceMetadata.revealed(): WorkspaceMetadata {
        val plain = secrets.reveal(id, attributes)
        return if (plain == attributes) this else copy(attributes = plain)
    }

    private suspend fun WorkspaceMetadata.sealedForStorage(): WorkspaceMetadata {
        // The stored row is only read when there is a secret (or an unreadable-secret
        // placeholder) to reconcile against it.
        val existing = if (secrets.needsStoredRow(attributes)) workspaceDao.getById(id)?.attributes else null
        val raw = secrets.protect(id, attributes, existing)
        return if (raw == attributes) this else copy(attributes = raw)
    }

    override fun observeAll(): Flow<List<WorkspaceMetadata>> {
        return workspaceDao.observeAll().map { rows -> rows.map { it.revealed() } }
    }

    override fun observeById(id: String): Flow<WorkspaceMetadata?> {
        return workspaceDao.observeById(id).map { it?.revealed() }
    }

    override suspend fun getById(id: String): WorkspaceMetadata? {
        return workspaceDao.getById(id)?.revealed()
    }

    override suspend fun insert(metadata: WorkspaceMetadata) {
        diskStore.ensureWorkspaceDirs(metadata.id)
        workspaceDao.insert(metadata.sealedForStorage())
    }

    @Deprecated("Prefer mutate for read-modify-write; update overwrites the whole row")
    override suspend fun update(metadata: WorkspaceMetadata) {
        // Phase 05: under the row mutex like every other mutation. This still
        // overwrites the whole row with the caller's snapshot — serialization
        // bounds WHEN it lands, it cannot fix a stale snapshot (see mutate).
        rowMutationMutex.withLock {
            workspaceDao.update(metadata.sealedForStorage())
        }
    }

    override suspend fun deleteWorkspace(id: String): WorkspaceDeletionResult {
        // Disk first, row second. See the interface KDoc: a crash
        // between the two leaves a visible `Broken` row, whereas row-first
        // leaves an invisible multi-GB tree that nothing can ever reclaim.
        val row = workspaceDao.getById(id)
        val basePath = diskStore.getWorkspaceBasePath(id)
        val existedOnDisk = basePath.exists()
        val removed = diskStore.deleteWorkspaceTree(id)
        if (!removed && basePath.exists()) {
            val reason = "Could not remove the workspace files on disk; " +
                "nothing was deleted. Free storage or retry."
            return WorkspaceDeletionResult.DiskFailure(reason)
        }

        if (row != null) {
            workspaceDao.deleteById(id)
        }
        // Rootfs is gone — drop any cached size so a re-install re-measures.
        diskStore.invalidateRootfsSize(id)

        return if (row == null && !existedOnDisk) {
            WorkspaceDeletionResult.NotFound
        } else {
            WorkspaceDeletionResult.Deleted
        }
    }

    @Deprecated("Use deleteWorkspace; this order can leak the on-disk tree")
    override suspend fun deleteById(id: String) {
        // Retained for pre-Phase-1 callers/fakes only. Production uses
        // deleteWorkspace, which is read-only-safe and reports failure.
        deleteWorkspace(id)
    }

    override suspend fun updateStatus(id: String, status: WorkspaceStatus) {
        // Phase 05: under the row mutex — a targeted write can still land in
        // the middle of a caller's read-modify-write and be reverted by the
        // caller's whole-row write; serializing the two makes the interleaving
        // impossible (the caller's mutate reads the new status and carries it
        // through its own transform).
        rowMutationMutex.withLock {
            workspaceDao.updateStatus(id, json.encodeToString(status))
        }
    }

    /**
     * Phase 05: the atomic read-modify-write primitive. Read, transform and
     * write all happen under [rowMutationMutex]; the transform sees the
     * decrypted row and the persisted form is re-sealed, exactly like every
     * other entry point. See [WorkspaceRepository.mutate] for the contract.
     */
    override suspend fun mutate(
        id: String,
        transform: (WorkspaceMetadata) -> WorkspaceMetadata
    ): WorkspaceMetadata? = rowMutationMutex.withLock {
        val current = workspaceDao.getById(id) ?: return@withLock null
        val next = transform(current.revealed()).sealedForStorage()
        workspaceDao.update(next)
        next.revealed()
    }

    /**
     * Phase 05: atomic single-toggle write — the read-modify-write that used
     * to live in [com.fem.linexa.ui.screens.workspace.WorkspaceDetailViewModel]
     * (getById → copy(featureToggles + …) → updateFeatureToggles) now happens
     * under the row mutex, so two rapid toggles both persist.
     */
    override suspend fun setFeatureToggle(
        id: String,
        featureId: String,
        enabled: Boolean
    ): WorkspaceMetadata? = mutate(id) { current ->
        current.copy(featureToggles = current.featureToggles + (featureId to enabled))
    }

    override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) {
        rowMutationMutex.withLock {
            val current = workspaceDao.getById(id) ?: return@withLock
            workspaceDao.update(current.copy(featureToggles = toggles))
        }
    }

    override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
        rowMutationMutex.withLock {
            val current = workspaceDao.getById(id) ?: return@withLock
            // Merge — see the interface KDoc: this map also carries the install
            // source, the catalog distro id and the desktop-capability flag.
            // `current` is the raw stored row (ciphertext); only the incoming keys are
            // protected, so existing encrypted values pass through untouched.
            val toStore = secrets.protect(id, attributes, current.attributes)
            workspaceDao.update(current.copy(attributes = current.attributes + toStore))
        }
    }

    /**
     * P11: encrypts any legacy plaintext secret still on disk. Idempotent and safe to
     * run at every start: rows with nothing to migrate are not written, and a row whose
     * encryption fails is left exactly as it was so the next start retries.
     * Runs under [rowMutationMutex], so it cannot interleave with an attribute merge.
     *
     * Deliberately on the concrete class, not on [WorkspaceRepository]: it is startup
     * plumbing, and adding it to the interface would force every test fake to grow it.
     *
     * @return how many rows were rewritten
     */
    suspend fun migrateLegacySecrets(): Int = rowMutationMutex.withLock {
        var rewritten = 0
        for (row in workspaceDao.observeAll().first()) {
            val migrated = secrets.migrate(row.id, row.attributes) ?: continue
            workspaceDao.update(row.copy(attributes = migrated))
            rewritten++
        }
        rewritten
    }
}
