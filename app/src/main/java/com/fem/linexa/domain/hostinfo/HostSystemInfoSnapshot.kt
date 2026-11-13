package com.fem.linexa.domain.hostinfo

/**
 * One immutable point-in-time reading of EVERYTHING the Android host can
 * honestly report about itself.
 *
 * A snapshot is a value object: nothing in it mutates after construction,
 * and every consumer gets either the whole consistent set or nothing.
 * Refresh produces a NEW instance; holding an old one stays valid forever
 * and merely ages.
 *
 * Two truths encoded here govern every consumer:
 *
 * 1. **Fields are nullable; categories never are.** A missing value is
 *    `null` INSIDE its category model (unknown), while the category itself
 *    always has a [capabilities] entry stating WHY at this refresh — so
 *    "battery temperature unknown because hardware withheld it" and
 *    "network unknown because collection errored" render differently even
 *    though both lack a number.
 *
 * 2. **HOST is authoritative; ROOTFS/local stays separate.** Everything in
 *    this snapshot describes the Android device. Workspace-scoped facts
 *    (per-workspace CPU/memory/storage) live in
 *    [com.fem.linexa.domain.resource] and are NOT merged here — see docs/
 *    HOST_SYSTEM_INFO.md §"host-authoritative vs rootfs-local".
 *
 * @property collectedAtEpochMs wall-clock time the refresh cycle started.
 * @property cpu / gpu / memory / swap / storage / device / battery / thermal /
 *   network / processes — one model per [HostInfoCategory]; each model's own
 *   KDoc defines which fields exist on which devices.
 * @property capabilities category → availability for THIS refresh. Never
 *   empty: a fully failed collection still records ERROR per category.
 * @property collectedAtPerCategory epoch ms per category's own most recent
 *   successful collection (sub-category staleness report); entries may be
 *   older than [collectedAtEpochMs] when a category kept valid cache through
 *   a failed refresh.
 */
data class HostSystemInfoSnapshot(
    val collectedAtEpochMs: Long,
    val cpu: HostCpuInfo,

    /**
     * Defaults to the all-unknown model so pre-GPU constructors (tests,
     * future callers) stay valid: an absent argument means "not sampled",
     * which is exactly what [HostGpuInfo.unknown] encodes.
     */
    val gpu: HostGpuInfo = HostGpuInfo.unknown(),
    val memory: HostMemoryInfo,
    val swap: HostSwapInfo,
    val storage: HostStorageInfo,
    val device: HostDeviceInfo,
    val battery: HostBatteryInfo,
    val thermal: HostThermalInfo,
    val network: HostNetworkInfo,
    val processes: HostProcessInfo,
    val capabilities: Map<HostInfoCategory, HostInfoAvailability>,
    val collectedAtPerCategory: Map<HostInfoCategory, Long>
) {
    /** Availability of one category, defaulting to UNAVAILABLE if unrecorded. */
    fun availabilityOf(category: HostInfoCategory): HostInfoAvailability =
        capabilities[category] ?: HostInfoAvailability.UNAVAILABLE

    /**
     * Uptime in seconds when knowable, truncated like `/proc/uptime` reports
     * (fractional seconds there; here integer seconds by contract between
     * host and guest overlays — sub-second precision adds noise, not truth).
     */
    val uptimeSeconds: Long?
        get() = device.uptimeMillis?.let { it / 1000L }

    companion object {
        /**
         * A strictly-empty snapshot for JVM tests and degenerate startups:
         * every category present, every capability UNKNOWN-classed, zero
         * fabricated numbers anywhere. Not what production returns on
         * failure — collectors retry first — but always constructible.
         */
        fun empty(atEpochMs: Long): HostSystemInfoSnapshot {
            val memory = HostMemoryInfo(
                memTotalKb = null, memFreeKb = null, memAvailableKb = null,
                buffersKb = null, cachedKb = null, sReclaimableKb = null, usedLinuxKb = null,
                androidTotalKb = null, androidAvailKb = null, androidThresholdKb = null,
                isLowMemory = null, swap = null, zram = null,
                memoryClassMb = null, largeMemoryClassMb = null
            )
            return HostSystemInfoSnapshot(
                collectedAtEpochMs = atEpochMs,
                cpu = HostCpuInfo(
                    logicalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    onlineCores = null, architecture = null, supportedAbis = emptyList(),
                    cpuModelName = null, cpuImplementation = null, coresPerCoreInfo = emptyList(),
                    minFrequencyKhz = null, maxFrequencyKhz = null, currentFrequencyKhz = null,
                    overallUsagePercent = null, perCoreUsagePercent = emptyList(),
                    loadAvg1m = null, loadAvg5m = null, loadAvg15m = null
                ),
                gpu = HostGpuInfo.unknown(),
                memory = memory,
                swap = HostSwapInfo(totalKb = null, freeKb = null, usedKb = null),
                storage = HostStorageInfo(
                    primaryVolume = null, externalAppVolume = null,
                    sharedStorageVolume = null, allVolumes = emptyList()
                ),
                device = HostDeviceInfo(
                    androidRelease = null, sdkInt = 0, codename = null, incremental = null,
                    securityPatch = null, manufacturer = null, brand = null, model = null,
                    product = null, device = null, board = null, hardware = null,
                    bootloader = null, fingerprint = null,
                    kernelRelease = null, kernelVersion = null, machineArch = null,
                    selinuxEnforcing = null, uptimeMillis = null, deepSleepMillis = null,
                    bootTimeEpochMillis = null
                ),
                battery = HostBatteryInfo(
                    present = null, percent = null,
                    charging = HostBatteryInfo.ChargingState.UNKNOWN,
                    powerSource = HostBatteryInfo.PowerSource.UNKNOWN,
                    health = HostBatteryInfo.Health.UNKNOWN,
                    temperatureC = null, voltageMillivolts = null, currentMicroamps = null,
                    chargeCounterMicroAh = null, designCapacityMilliampHours = null,
                    isPowerSaveMode = null
                ),
                thermal = HostThermalInfo(
                    status = null, severityLabel = null, headroom = null,
                    cpuTemperatureC = null, gpuTemperatureC = null, skinTemperatureC = null,
                    batteryTemperatureC = null
                ),
                network = HostNetworkInfo.offline(),
                processes = HostProcessInfo.unknown(),
                capabilities = HostInfoCategory.entries.associateWith { HostInfoAvailability.UNAVAILABLE },
                collectedAtPerCategory = emptyMap()
            )
        }
    }
}
