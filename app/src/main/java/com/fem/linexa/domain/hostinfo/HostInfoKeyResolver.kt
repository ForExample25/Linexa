package com.fem.linexa.domain.hostinfo

/**
 * The stable, nameable set of host facts a RootFS or UI surface may request
 * INDIVIDUALLY. This is the contract behind §15's selective pass-through: a
 * consumer names keys — never categories, never "everything" — and receives
 * exactly those.
 *
 * Keys are strings in config (`config.yaml linexa.host_info.keys`) by enum
 * name; unknown names are rejected at parse time with the valid list, so
 * typos fail loudly at import instead of silently thinning the payload.
 *
 * Each key belongs to one [HostInfoCategory] (see [category]), which is how
 * resolution maps a request onto minimal collector work. Adding a new key
 * requires: an entry here mapping to its category, a value producer in
 * [HostInfoKeyResolver], and (if guest-visible) a LINEXA_* env name — see
 * docs/HOST_SYSTEM_INFO.md §"adding a key".
 */
enum class HostInfoKey(val category: HostInfoCategory) {
    // CPU — configured / granted / online is the Phase 2 triad. The three
    // counts are DISTINCT FACTS about distinct layers of the stack:
    // CPU_CORES = silicon capacity (`_SC_NPROCESSORS_CONF`),
    // CPU_AFFINITY_COUNT = scheduler grant to this environment (what `nproc`
    // prints; from sched_getaffinity via Cpus_allowed_list),
    // CPU_ONLINE_COUNT = kernel hotplug state (`/sys/devices/system/cpu/online`).
    CPU_CORES(HostInfoCategory.CPU),
    CPU_AFFINITY_COUNT(HostInfoCategory.CPU),
    CPU_AFFINITY_CORES(HostInfoCategory.CPU),
    CPU_ONLINE_COUNT(HostInfoCategory.CPU),
    CPU_ARCHITECTURE(HostInfoCategory.CPU),
    CPU_MODEL(HostInfoCategory.CPU),
    CPU_MAX_FREQ_KHZ(HostInfoCategory.CPU),
    CPU_LOADAVG_1M(HostInfoCategory.CPU),
    CPU_USAGE_PERCENT(HostInfoCategory.CPU),

    // Graphics silicon. GPU_MODEL/GPU_VENDOR are identity-level (stable for
    // the process lifetime, safe in the conservative default set); the
    // telemetry keys are live values the feed exposes on capable kernels.
    GPU_MODEL(HostInfoCategory.GPU),
    GPU_VENDOR(HostInfoCategory.GPU),
    GPU_TEMP_C(HostInfoCategory.GPU),
    GPU_BUSY_PERCENT(HostInfoCategory.GPU),
    GPU_CLOCK_KHZ(HostInfoCategory.GPU),

    // Memory / swap
    MEMORY_TOTAL_KB(HostInfoCategory.MEMORY),
    MEMORY_AVAILABLE_KB(HostInfoCategory.MEMORY),
    MEMORY_USED_KB(HostInfoCategory.MEMORY),
    SWAP_TOTAL_KB(HostInfoCategory.SWAP),
    SWAP_FREE_KB(HostInfoCategory.SWAP),

    // Storage (host device storage — NOT per-workspace usage)
    STORAGE_TOTAL_BYTES(HostInfoCategory.STORAGE),
    STORAGE_FREE_BYTES(HostInfoCategory.STORAGE),

    // Uptime / system identity
    UPTIME_SECONDS(HostInfoCategory.SYSTEM),
    KERNEL_RELEASE_HOST(HostInfoCategory.SYSTEM),
    ANDROID_VERSION(HostInfoCategory.SYSTEM),
    ANDROID_SDK_INT(HostInfoCategory.SYSTEM),
    DEVICE_MODEL(HostInfoCategory.SYSTEM),
    DEVICE_MANUFACTURER(HostInfoCategory.SYSTEM),
    MACHINE_ARCH(HostInfoCategory.SYSTEM),

    // Battery / thermal / network posture
    BATTERY_PERCENT(HostInfoCategory.BATTERY),
    BATTERY_CHARGING(HostInfoCategory.BATTERY),
    THERMAL_STATUS(HostInfoCategory.THERMAL),
    NETWORK_TYPE(HostInfoCategory.NETWORK),
    NETWORK_METERED(HostInfoCategory.NETWORK),
    NETWORK_INTERFACE(HostInfoCategory.NETWORK),
    NETWORK_IPV4(HostInfoCategory.NETWORK),
    SELINUX_ENFORCING(HostInfoCategory.SYSTEM)
}

/**
 * Pure resolver: snapshot → requested values.
 *
 * Resolution rules, all testable without Android:
 * - Only REQUESTED keys appear in the output map (selectivity enforced at
 *   the last mile too — no accidental over-exposure).
 * - A key whose category did not refresh successfully, or whose field is
 *   null/unknown on this snapshot, is OMITTED rather than emitted as empty,
 *   0, or -1. Consumers branch on existence; "present" always means real.
 * - Rendering is deterministic lowercase-canonical ("true"/"false", plain
 *   integers, stable strings) so shells can compare safely.
 */
object HostInfoKeyResolver {

    /**
     * Resolve [keys] against [snapshot]. Returns a LinkedHashMap keyed by
     * canonical uppercase key name (same spelling as the enum constant) so
     * logging and config round-trips read identically.
     */
    fun resolve(snapshot: HostSystemInfoSnapshot, keys: Set<HostInfoKey>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(keys.size)
        for (key in keys) {
            val rendered = render(snapshot, key) ?: continue
            out[key.name] = rendered
        }
        return out
    }

    /** The [HostInfoCategory] set touched by [keys], for selective refreshes. */
    fun categoriesFor(keys: Set<HostInfoKey>): Set<HostInfoCategory> =
        keys.mapTo(mutableSetOf()) { it.category }

    /** null = omit the key from output entirely (unknown/withheld this time). */
    private fun render(s: HostSystemInfoSnapshot, key: HostInfoKey): String? = when (key) {
        HostInfoKey.CPU_CORES -> s.cpu.logicalCores.toString()
        // Affinity is the nproc-semantics count. Unknown (unparsable status)
        // omits rather than defaulting to logicalCores — an assumed grant
        // would be a fabricated one.
        HostInfoKey.CPU_AFFINITY_COUNT -> s.cpu.affinityCoreIndices?.size?.toString()
        HostInfoKey.CPU_AFFINITY_CORES ->
            s.cpu.affinityCoreIndices
                ?.takeIf { it.isNotEmpty() }
                ?.let { indices -> compactRanges(indices).joinToString(",") }
        HostInfoKey.CPU_ONLINE_COUNT -> s.cpu.onlineCores?.toString()
        HostInfoKey.CPU_ARCHITECTURE -> s.cpu.architecture ?: s.device.machineArch
        HostInfoKey.CPU_MODEL -> s.cpu.cpuModelName
        HostInfoKey.CPU_MAX_FREQ_KHZ -> s.cpu.maxFrequencyKhz?.toString()
        HostInfoKey.CPU_LOADAVG_1M -> s.cpu.loadAvg1m?.let { String.format(java.util.Locale.US, "%.2f", it) }
        HostInfoKey.CPU_USAGE_PERCENT -> s.cpu.overallUsagePercent?.let {
            String.format(java.util.Locale.US, "%.1f", it)
        }

        HostInfoKey.GPU_MODEL -> s.gpu.model
        HostInfoKey.GPU_VENDOR -> s.gpu.vendorFamily
        HostInfoKey.GPU_TEMP_C -> s.gpu.temperatureC?.let { String.format(java.util.Locale.US, "%.1f", it) }
        HostInfoKey.GPU_BUSY_PERCENT -> s.gpu.busyPercent?.toString()
        HostInfoKey.GPU_CLOCK_KHZ -> s.gpu.clockKhz?.toString()

        HostInfoKey.MEMORY_TOTAL_KB -> s.memory.memTotalKb?.toString()
        // Both fields are already kB in the model contract (the collector
        // converts AM bytes → kB), so no unit mixing happens here.
        HostInfoKey.MEMORY_AVAILABLE_KB ->
            (s.memory.memAvailableKb ?: s.memory.androidAvailKb)?.toString()
        HostInfoKey.MEMORY_USED_KB -> s.memory.usedLinuxKb?.toString()

        HostInfoKey.SWAP_TOTAL_KB -> s.swap.totalKb?.toString()
        HostInfoKey.SWAP_FREE_KB -> s.swap.freeKb?.toString()

        HostInfoKey.STORAGE_TOTAL_BYTES -> s.storage.primaryVolume?.totalBytes?.toString()
        HostInfoKey.STORAGE_FREE_BYTES -> s.storage.primaryVolume?.freeBytes?.toString()

        HostInfoKey.UPTIME_SECONDS -> s.uptimeSeconds?.toString()
        HostInfoKey.KERNEL_RELEASE_HOST -> s.device.kernelRelease
        HostInfoKey.ANDROID_VERSION -> s.device.androidRelease
        HostInfoKey.ANDROID_SDK_INT -> if (s.device.sdkInt > 0) s.device.sdkInt.toString() else null
        HostInfoKey.DEVICE_MODEL -> s.device.model
        HostInfoKey.DEVICE_MANUFACTURER -> s.device.manufacturer
        HostInfoKey.MACHINE_ARCH -> s.device.machineArch

        HostInfoKey.BATTERY_PERCENT -> s.battery.percent?.toString()
        HostInfoKey.BATTERY_CHARGING -> when (s.battery.charging) {
            HostBatteryInfo.ChargingState.CHARGING, HostBatteryInfo.ChargingState.FULL -> "true"
            HostBatteryInfo.ChargingState.DISCHARGING -> "false"
            HostBatteryInfo.ChargingState.UNKNOWN -> null
        }
        HostInfoKey.THERMAL_STATUS -> s.thermal.severityLabel
        HostInfoKey.NETWORK_TYPE -> s.network.primaryTransport
        HostInfoKey.NETWORK_METERED -> s.network.isMetered?.let { it.toString().lowercase() }
        // Sanitized per policy: interface NAME + primary v4 only — kernel-
        // grain enough for diagnostics, still nothing that identifies the
        // user's position (no SSID ever, no full address inventory here).
        HostInfoKey.NETWORK_INTERFACE -> s.network.activeInterface()?.name ?: s.network.interfaceName
        HostInfoKey.NETWORK_IPV4 -> s.network.ipv4Addresses.firstOrNull()
        HostInfoKey.SELINUX_ENFORCING -> s.device.selinuxEnforcing?.let {
            it.toString().lowercase(java.util.Locale.US)
        }
    }

    /** "0-3,6"-style rendering of a sorted distinct index set (taskset form). */
    internal fun compactRanges(indices: List<Int>): List<String> {
        if (indices.isEmpty()) return emptyList()
        val sortedDistinct = indices.toSortedSet().toList()
        val out = mutableListOf<String>()
        var runStart = sortedDistinct.first()
        var previous = runStart
        for (index in sortedDistinct.drop(1)) {
            if (index == previous + 1) {
                previous = index
                continue
            }
            out += if (previous == runStart) "$runStart" else "$runStart-$previous"
            runStart = index
            previous = index
        }
        out += if (previous == runStart) "$runStart" else "$runStart-$previous"
        return out
    }
}
