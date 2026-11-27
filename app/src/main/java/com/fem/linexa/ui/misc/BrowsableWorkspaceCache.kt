package com.fem.linexa.ui.misc

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceReadiness
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * P0-4 (2026-11-23 performance remediation): the SAF provider's workspace
 * index.
 *
 * Before this, every `queryChildDocuments` of the Containers root executed
 * `runBlocking { workspaceRepository.observeAll().first() }` on the Binder
 * thread and then ran [WorkspaceLaunchValidator.evaluate] for every
 * workspace — two disk checks each — even though the Files app polls the
 * same listing repeatedly. `queryDocument` for ONE workspace rebuilt that
 * entire list just to look up one display name.
 *
 * This cache:
 *  - owns one [WorkspaceRepository.observeAll] collector, so Room emissions
 *    update the index in the background instead of charging every query;
 *  - serves a snapshot from memory (no Binder suspension once warm), with a
 *    bounded TTL as a safety net if an emission is ever missed;
 *  - caches readiness per workspace for a short TTL, and clears that cache
 *    on every row emission, so a status/disk change is re-examined when the
 *    underlying rows change;
 *  - resolves one workspace ID without rebuilding the list for the others.
 *
 * Fail-open is preserved deliberately (issue #8): a cold read or collector
 * failure yields an empty-but-valid listing, never a broken Files entry.
 * ContentProvider queries can arrive before Hilt is ready, so the caller
 * constructs this lazily on first use.
 */
internal class BrowsableWorkspaceCache(
    private val repository: WorkspaceRepository,
    private val validator: WorkspaceLaunchValidator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readinessTtlMs: Long = READINESS_TTL_MS,
    private val snapshotTtlMs: Long = SNAPSHOT_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * LINEXA-PERF-008: invoked (outside [lock]) the first time a row snapshot
     * is published. The provider uses it to notify its Containers URI, so a
     * client that queried during the initial window refreshes itself instead
     * of being left with the empty-but-valid listing it got.
     */
    private val onFirstRowsPublished: (() -> Unit)? = null
) {

    /** One row exactly as the Containers root renders it (assigned name). */
    data class BrowsableWorkspace(
        val id: String,
        val displayName: String
    )

    private data class CachedReadiness(val ready: Boolean, val at: Long)

    private val tag = "BrowsableWorkspaceCache"
    private val lock = Any()

    /** Latest raw rows from Room; never filtered under the lock. */
    private var rows: List<WorkspaceMetadata> = emptyList()

    /** Explorer-visible rows derived from [rows] + readiness. */
    private var browsable: List<BrowsableWorkspace>? = null
    private var browsableAt = 0L

    /** Bumped on every row emission; guards a stale concurrent rebuild. */
    private var rowGeneration = 0L

    /** Set once a cold load or a collector emission has produced [rows]. */
    private var loaded = false

    private val readiness = HashMap<String, CachedReadiness>()
    private var collector: Job? = null

    /**
     * Workspace rows for the Containers root. Never blocks: a warm snapshot is
     * served from memory, and a cold one starts the background collector and
     * returns the current (initially empty) view. The provider warms this in
     * `onCreate` and is notified when the first snapshot lands
     * ([onFirstRowsPublished]).
     */
    fun browsableWorkspaces(): List<BrowsableWorkspace> {
        ensureLoaded()
        expireSnapshotIfStale()
        synchronized(lock) {
            browsable?.let { return it }
        }
        return rebuildBrowsable()
    }

    /**
     * Resolves one workspace for `queryDocument`. Returns null when the
     * workspace is not currently browsable, letting the caller keep the
     * existing ID-as-display-name fallback for stale/deleted IDs.
     *
     * A warm snapshot answers without touching disk. A cold/stale snapshot
     * evaluates ONLY this workspace instead of rebuilding the whole list.
     */
    fun find(workspaceId: String): BrowsableWorkspace? {
        ensureLoaded()
        expireSnapshotIfStale()
        synchronized(lock) {
            browsable?.let { rows ->
                return rows.firstOrNull { it.id == workspaceId }
            }
        }
        val meta = synchronized(lock) { rows.firstOrNull { it.id == workspaceId } } ?: return null
        if (!isBrowsable(meta)) return null
        // No assigned-name table without the full listing; the single-row
        // sanitizer still preserves the ID fallback contract.
        val name = LinexaDocumentsMapping.assignDisplayNames(
            listOf(meta.id to meta.displayName)
        )[meta.id] ?: meta.id
        return BrowsableWorkspace(meta.id, name)
    }

    /** Drops every derived entry (test seam / explicit invalidation). */
    internal fun invalidate() {
        synchronized(lock) {
            browsable = null
            browsableAt = 0L
            readiness.clear()
            rowGeneration++
        }
    }

    /** Stops the collector; the provider normally lives for the process. */
    internal fun close() {
        collector?.cancel()
        collector = null
    }

    /**
     * Starts the warm-up without waiting for it. The provider calls this in
     * `onCreate`, so by the time the first `queryChildDocuments` arrives the
     * snapshot is normally already in memory. Never blocks the caller.
     */
    internal fun warm() {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (synchronized(lock) { loaded }) {
            startCollector()
            return
        }
        // LINEXA-PERF-008: this used to `runBlocking { observeAll().first() }`
        // on whatever thread asked — in production, the DocumentsProvider
        // Binder thread — and it held [lock] for the whole query. The first
        // load now happens on [scope]; a query that lands before it finishes
        // serves the empty-but-valid listing and is refreshed by
        // [onFirstRowsPublished]. No Binder thread ever waits on Room.
        startCollector()
    }

    private fun startCollector() {
        if (collector?.isActive == true) return
        synchronized(lock) {
            if (collector?.isActive == true) return
            collector = scope.launch {
                repository.observeAll()
                    .catch { e ->
                        Logger.w(tag, "workspace observation stopped — keeping the last snapshot: ${e.message}")
                    }
                    .collect { observed ->
                        val firstPublish = synchronized(lock) {
                            val wasLoaded = loaded
                            publishLocked(observed)
                            !wasLoaded
                        }
                        if (firstPublish) {
                            runCatching { onFirstRowsPublished?.invoke() }
                                .onFailure { Logger.w(tag, "first-publish notification failed: ${it.message}") }
                        }
                    }
            }
        }
    }

    /** Caller holds [lock]. */
    private fun publishLocked(observed: List<WorkspaceMetadata>) {
        rows = observed
        loaded = true
        rowGeneration++
        // Any row change can change readiness (install finished, rootfs
        // deleted, status flipped), so the readiness verdicts are dropped.
        readiness.clear()
        browsable = null
        browsableAt = 0L
    }

    /**
     * A snapshot older than [snapshotTtlMs] is rebuilt from the last observed
     * rows. If the collector is not running, one asynchronous read is
     * requested as the safety net — never a blocking Binder read.
     */
    private fun expireSnapshotIfStale() {
        val stale = synchronized(lock) {
            if (!loaded || browsable == null) return
            clock() - browsableAt > snapshotTtlMs
        }
        if (!stale) return
        synchronized(lock) { browsable = null }
        if (collector?.isActive != true) {
            scope.launch {
                runCatching { repository.observeAll().first() }
                    .onSuccess { observed ->
                        synchronized(lock) { publishLocked(observed) }
                    }
                    .onFailure { e ->
                        Logger.w(tag, "snapshot refresh failed — rebuilding from the last rows: ${e.message}")
                    }
            }
        }
    }

    private fun rebuildBrowsable(): List<BrowsableWorkspace> {
        val (snapshot, generation) = synchronized(lock) { rows to rowGeneration }
        val readyRows = snapshot.filter { isBrowsable(it) }
        val names = LinexaDocumentsMapping.assignDisplayNames(readyRows.map { it.id to it.displayName })
        val derived = readyRows.map { meta ->
            BrowsableWorkspace(meta.id, names[meta.id] ?: meta.id)
        }
        synchronized(lock) {
            // A collector emission during the walk invalidates this rebuild;
            // the next query re-derives from the newer rows. Never overwrite
            // a fresher generation with an older derivation.
            if (rowGeneration == generation) {
                browsable = derived
                browsableAt = clock()
            }
        }
        return derived
    }

    private fun isBrowsable(meta: WorkspaceMetadata): Boolean {
        // Same gate as LinexaDocumentsMapping.isBrowsable: only Ready rows are
        // ever listed. Installed is intentionally NOT browsable here (the SAF
        // contract predates this cache and must not change), and checking
        // first avoids a pointless disk probe for it.
        if (meta.status !is WorkspaceStatus.Ready) {
            return false
        }
        val now = clock()
        val cached = synchronized(lock) { readiness[meta.id] }
        if (cached != null && now - cached.at <= readinessTtlMs) {
            return LinexaDocumentsMapping.isBrowsable(meta.status, cached.ready)
        }
        val ready = try {
            validator.evaluate(meta) is WorkspaceReadiness.Ready
        } catch (e: Exception) {
            Logger.w(tag, "readiness check failed for ${meta.id} — treating as not browsable: ${e.message}")
            false
        }
        synchronized(lock) { readiness[meta.id] = CachedReadiness(ready, now) }
        return LinexaDocumentsMapping.isBrowsable(meta.status, ready)
    }

    private companion object {
        /** Disk readiness (two filesystem checks) is re-run at most this often. */
        const val READINESS_TTL_MS = 30_000L

        /** Safety net for a missed Room emission; the collector normally wins. */
        const val SNAPSHOT_TTL_MS = 30_000L
    }
}
