package com.fem.linexa.domain.hostinfo

/**
 * Per-category cache time-to-live for [HostSystemInfoProvider.snapshot].
 *
 * The subsystem is pull-based — nothing here implies background polling.
 * The TTL answers a different question: when SEVERAL consumers ask within a
 * burst (a terminal opening while the dashboard opens, a probe script catting
 * five files), how long may they share one collection instead of paying for
 * five?
 *
 * Two tiers, chosen by how fast reality moves vs how expensive truth is:
 *
 * - **Identity tier** (`SYSTEM`) — device/kernel strings cannot change
 *   during a process lifetime except security patch application (which
 *   requires reboot anyway). Cached for the process lifetime effectively;
 *   still force-refreshable through `refreshSnapshot()`.
 * - **Slow tier** — minutes-scale drift, moderate collection cost (Storage
 *   StatFs probes are syscall-cheap but change as other apps write; uptime
 *   ticks constantly BUT reading SystemClock.elapsedRealtime is free —
 *   SYSTEM's low cost lets guests get fresh-enough uptime overlays while its
 *   identity fields ride the same long cache).
 * - **Fast tier** — seconds-scale signals users actively watch (memory,
 *   battery, thermal, network posture). Collected on demand but bounded so
 *   rapid successive requests share a reading rather than hammering sticky
 *   broadcasts / binder calls.
 * - CPU sits between: usage MUST be delta-based over ≥ ~500 ms to mean
 *   anything (a shorter window reads mostly zero); swap is meminfo-coupled
 *   with memory.
 */
enum class HostInfoRefreshPolicy(val ttlMillis: Long) {
    SYSTEM(30 * 60_000L),      // 30 min: identity-level strings
    STORAGE(10_000L),          // 10 s: capacity answers must survive "can I apt install this?"
    NETWORK(5_000L),
    THERMAL(2_000L),
    BATTERY(2_000L),
    MEMORY(1_000L),
    SWAP(1_000L),              // meminfo-coupled with memory by nature
    CPU(600L),                 // delta window floor; slightly longer keeps consecutive panels consistent
    GPU(2_000L);               // vendor busy/clock nodes tick fast; renderer identity is process-cached inside the collector

    /** Minimum forced-refresh spacing guard for rate-limited platform APIs. */
    companion object {
        const val MIN_FORCED_REFRESH_MS = 100L

        /** Policy lookup guaranteed total (never null) for any category. */
        fun forCategory(category: HostInfoCategory): HostInfoRefreshPolicy = when (category) {
            HostInfoCategory.SYSTEM -> SYSTEM
            HostInfoCategory.CPU -> CPU
            HostInfoCategory.GPU -> GPU
            HostInfoCategory.MEMORY -> MEMORY
            HostInfoCategory.SWAP -> SWAP
            HostInfoCategory.STORAGE -> STORAGE
            HostInfoCategory.BATTERY -> BATTERY
            HostInfoCategory.THERMAL -> THERMAL
            HostInfoCategory.NETWORK -> NETWORK
            HostInfoCategory.PROCESSES -> MEMORY // self-process footprint moves like RAM
        }
    }
}
