package com.fem.linexa.domain.hostinfo

/**
 * The independent categories of Android host information the provider tracks.
 *
 * Categories exist so caching, refresh and (most importantly) RootFS
 * pass-through can be expressed at the right granularity: a guest that needs
 * only `CPU_CORES` and `UPTIME` must not trigger a storage walk or a network
 * probe as a side effect. Every key in [HostInfoKey] belongs to exactly one
 * category; a selective request resolves to the set of categories it touches,
 * and only those are refreshed.
 */
enum class HostInfoCategory {
    /** Static-ish device + Android + kernel identity. Cheap; cached longest. */
    SYSTEM,

    /** Cores, ABI, frequencies, online state, usage/load where measurable. */
    CPU,

    /**
     * Graphics silicon: renderer identity, GPU zone temperature, vendor
     * clocks/busy where the kernel serves them. Separate from [THERMAL] so
     * a guest asking for the GPU model never triggers a thermal sweep, and
     * separate from [CPU] because GPU capability is a distinct decision
     * input (desktop/GPU-feature gating reads this category alone).
     */
    GPU,

    /** Physical RAM, Android available memory, cached/buffers, memory classes. */
    MEMORY,

    /** Swap/zRAM — separate from [MEMORY] so guests can request swap alone. */
    SWAP,

    /** Device volumes and capacity. Distinguished from per-workspace usage. */
    STORAGE,

    /** Battery level/state/health/power-save. */
    BATTERY,

    /** Thermal status/severity/headroom. */
    THERMAL,

    /** Active network type, metered state, interfaces, DNS. */
    NETWORK,

    /**
     * The Linexa app's own process identity and footprint. Enumerating OTHER
     * apps' processes is [HostInfoAvailability.RESTRICTED] by platform
     * design (hidepid) — this category is the documented extension point for
     * that future capability.
     */
    PROCESSES
}
