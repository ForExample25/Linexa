package com.fem.linexa.domain.hostinfo

/**
 * The single source of truth for Android host system information.
 *
 * Everything that wants host facts — the Terminal Workspace UI, diagnostics,
 * the RootFS compatibility layer — asks THIS interface and receives typed,
 * immutable data. No consumer parses Linux command output or Android files
 * directly; no consumer depends on the concrete implementation.
 *
 * Contract, in testable terms:
 *
 * - `snapshot()` is the workhorse: returns the freshest consistent set,
 *   refreshing only categories whose TTL expired (pull-based; NO background
 *   polling exists in the subsystem — work happens when someone asks).
 * - `refreshSnapshot()` forces every category to re-measure now, in place
 *   replacing caches only with successful collections (a failed category
 *   keeps its previous values; its capability flag tells why).
 * - `snapshotOf(categories)` is the SELECTIVE path: non-requested categories
 *   resolve from cache (or empty) WITHOUT being collected, which is exactly
 *   what the RootFS bridge uses so a guest asking for CPU_CORES + UPTIME
 *   never triggers a storage walk as a side effect.
 * - Methods suspend but never throw: collection errors become capability
 *   flags ([HostInfoAvailability.ERROR]) on a complete snapshot. A host-info
 *   failure must degrade a UI panel, never crash a session launch.
 *
 * Convenience accessors (`getCpuInfo()`…) exist because §13's API sketch
 * names them; each returns ONLY that category's model after refreshing just
 * that category when stale.
 */
interface HostSystemInfoProvider {

    /** Cached-then-refreshed full snapshot, TTL-driven per category. */
    suspend fun snapshot(): HostSystemInfoSnapshot

    /** Force re-collection of every category right now. */
    suspend fun refreshSnapshot(): HostSystemInfoSnapshot

    /**
     * Selective snapshot: refresh ONLY [categories]; others resolve from the
     * last cache without I/O. Returns a complete-shaped snapshot either way.
     */
    suspend fun snapshotOf(categories: Set<HostInfoCategory>): HostSystemInfoSnapshot

    /** Cache-only view for cheap UI reads; null before the first collection. */
    fun lastSnapshot(): HostSystemInfoSnapshot?

    /** Invalidate every cached value (next read re-collects). */
    fun invalidateAll()

    // ── Category convenience accessors (refresh only their own category) ──

    suspend fun getCpuInfo(): HostCpuInfo =
        snapshotOf(setOf(HostInfoCategory.CPU)).cpu

    suspend fun getMemoryInfo(): HostMemoryInfo =
        snapshotOf(setOf(HostInfoCategory.MEMORY)).memory

    suspend fun getStorageInfo(): HostStorageInfo =
        snapshotOf(setOf(HostInfoCategory.STORAGE)).storage

    suspend fun getDeviceInfo(): HostDeviceInfo =
        snapshotOf(setOf(HostInfoCategory.SYSTEM)).device

    suspend fun getBatteryInfo(): HostBatteryInfo =
        snapshotOf(setOf(HostInfoCategory.BATTERY)).battery

    suspend fun getThermalInfo(): HostThermalInfo =
        snapshotOf(setOf(HostInfoCategory.THERMAL)).thermal

    suspend fun getNetworkInfo(): HostNetworkInfo =
        snapshotOf(setOf(HostInfoCategory.NETWORK)).network

    suspend fun getProcessInfo(): HostProcessInfo =
        snapshotOf(setOf(HostInfoCategory.PROCESSES)).processes
}
