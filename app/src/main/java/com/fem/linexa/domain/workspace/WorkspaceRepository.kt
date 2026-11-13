package com.fem.linexa.domain.workspace

import kotlinx.coroutines.flow.Flow

/**
 * Result of [WorkspaceRepository.deleteWorkspace].
 *
 * Deletion is a two-part operation (on-disk tree + database row). The result
 * makes the difference between "gone", "never existed" and "the disk could not
 * be freed" explicit, so callers never report a delete that left a multi-GB
 * rootfs behind.
 */
sealed interface WorkspaceDeletionResult {
    /** The disk tree and the database row are both gone. */
    data object Deleted : WorkspaceDeletionResult

    /** Neither the row nor the workspace directory existed. */
    data object NotFound : WorkspaceDeletionResult

    /**
     * The on-disk tree could not be removed. The database row was deliberately
     * kept so the workspace stays visible and the user can retry; [reason] is
     * user-facing.
     */
    data class DiskFailure(val reason: String) : WorkspaceDeletionResult
}

/**
 * Repository for workspace persistence. Implementation backed by Room (§1.9).
 * UI screens observe this; ViewModels hold no process handles (§1.4).
 */
interface WorkspaceRepository {
    fun observeAll(): Flow<List<WorkspaceMetadata>>
    fun observeById(id: String): Flow<WorkspaceMetadata?>
    suspend fun getById(id: String): WorkspaceMetadata?
    suspend fun insert(metadata: WorkspaceMetadata)

    /**
     * Whole-row overwrite of the caller's snapshot.
     *
     * Phase 05: this write is serialized against the other row mutations, but
     * **serialization cannot fix a stale snapshot** — the caller read the row
     * some time ago, and anything another writer changed since is silently
     * reverted here. Every read-modify-write must go through [mutate] (or a
     * targeted method such as [updateAttributes]); [update] remains for
     * callers that hold a row they genuinely own (fresh inserts, test fakes).
     */
    @Deprecated("Prefer mutate for read-modify-write; update overwrites the whole row")
    suspend fun update(metadata: WorkspaceMetadata)

    /**
     * Deletes the workspace's on-disk tree first and its database row only
     * after the disk result is known. This ordering is deliberate: a crash
     * between the two steps leaves a row pointing at a missing rootfs, which
     * startup recovery already reports as `Broken` and the user can delete
     * again. The old row-first order left an invisible, unreclaimable tree
     * behind on every failure.
     *
     * Callers must use this method rather than [deleteById].
     */
    suspend fun deleteWorkspace(id: String): WorkspaceDeletionResult {
        @Suppress("DEPRECATION")
        deleteById(id)
        return WorkspaceDeletionResult.Deleted
    }

    /**
     * Legacy row-only deletion. Kept only so pre-Phase-1 test fakes compile.
     * Production callers must use [deleteWorkspace].
     */
    @Deprecated("Use deleteWorkspace; this order can leak the on-disk tree")
    suspend fun deleteById(id: String)

    suspend fun updateStatus(id: String, status: WorkspaceStatus)

    /**
     * Atomic read-modify-write (Phase 05 / OPT-DATA-001/002): the row is read,
     * transformed and written back inside ONE critical section, so two
     * concurrent mutations can never lose each other — the classic failure of
     * a caller-side `getById` + `update` pair, where both read the same base
     * row and the last writer silently dropped the first writer's change.
     *
     * The [transform] receives the **decrypted** row and must be pure and
     * fast: no network or file I/O, and no nested repository calls (that
     * would deadlock on the same mutex). Compute anything expensive or
     * I/O-bound BEFORE calling [mutate] and apply only its result inside the
     * transform. If the transform throws, the row is left untouched (the
     * write happens after it returns).
     *
     * Returns the newly persisted row (decrypted), or null when the workspace
     * no longer exists.
     *
     * The default body exists only so test fakes keep compiling; it is the
     * non-atomic form and production overrides it with the locked one.
     */
    suspend fun mutate(
        id: String,
        transform: (WorkspaceMetadata) -> WorkspaceMetadata
    ): WorkspaceMetadata? {
        val current = getById(id) ?: return null
        val next = transform(current)
        @Suppress("DEPRECATION")
        update(next)
        return next
    }

    /**
     * Atomic single-feature toggle write (Phase 05): the read-modify-write on
     * [WorkspaceMetadata.featureToggles] happens under the same critical
     * section as every other row mutation, so two rapid toggles both persist
     * instead of the second one overwriting the first's key.
     *
     * Same contract as [mutate]: returns the new row, or null when the
     * workspace no longer exists.
     */
    suspend fun setFeatureToggle(
        id: String,
        featureId: String,
        enabled: Boolean
    ): WorkspaceMetadata? =
        mutate(id) { current ->
            current.copy(featureToggles = current.featureToggles + (featureId to enabled))
        }

    /**
     * Persists a workspace's feature-toggle map (§1.81). Reads the current
     * record and writes back with [WorkspaceMetadata.featureToggles] replaced
     * by [toggles]. No-op (without failing) when the workspace no longer
     * exists — the flow observer in the UI converges on the new value.
     */
    suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>)

    /**
     * Persists a workspace's per-feature *configuration* (UI/UX Phase 6) by
     * merging [attributes] into [WorkspaceMetadata.attributes]. No-op (without
     * failing) when the workspace no longer exists, matching
     * [updateFeatureToggles].
     *
     * A **merge**, not a replace, and that is the whole reason this is its own
     * method rather than a caller doing `update(copy(attributes = …))`:
     * `attributes` is a shared map that also holds the install source, the
     * catalog distro id and §1.117's desktop-capability flag, so a writer that
     * only knows about feature settings must not be able to drop the others.
     */
    suspend fun updateAttributes(id: String, attributes: Map<String, String>)
}
