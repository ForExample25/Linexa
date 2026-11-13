package com.fem.linexa.data.hostinfo

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostGpuInfo
import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.domain.hostinfo.HostInfoRefreshPolicy
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostSystemInfoProvider
import com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot
import com.fem.linexa.domain.hostinfo.HostThermalInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The production [HostSystemInfoProvider]: composes the platform collectors
 * ([HostInfoSources]) behind a per-category TTL cache.
 *
 * ## Caching model (§13 of the host-info requirements)
 *
 * Each category owns one [Entry] `{model, status, collectedAt}`. `snapshot()`
 * checks every category against its [HostInfoRefreshPolicy] TTL and
 * re-collects only expired ones, so concurrent burst consumers — a workspace
 * launch probing cores while a dashboard opens — share one collection.
 * `snapshotOf(categories)` makes the OTHERS strictly ride cache without a
 * single I/O touch: that is the RootFS bridge's selective path, guaranteeing
 * that asking for `CPU_CORES` never pays for a storage walk.
 *
 * A FAILED collection never overwrites a category's last-known-good values;
 * its `collectedAtPerCategory` stamp simply stops moving so staleness is
 * visible, and a never-succeeded category reports [HostInfoAvailability.ERROR]
 * rather than inventing numbers. RESTRICTED/UNAVAILABLE situations arrive INSIDE
 * successful collections as null fields (the collector ran and found the OS
 * withholding), which is why there is no synthetic status mapping here.
 *
 * ## Concurrency & threading
 *
 * One process-wide mutex serializes refresh cycles — deliberate, not lazy:
 * every collection costs at most tens of milliseconds, everything runs on the
 * IO dispatcher, serializing gives the CPU delta-state correctness for free,
 * and dedupes bursts (the reason this subsystem is pull-based, not polling-
 * based). Nothing here schedules background work; construction does zero I/O,
 * which keeps the class constructible in JVM test harnesses.
 */
class AndroidHostSystemInfoProvider(
    private val sources: HostInfoSources,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMs: () -> Long = System::currentTimeMillis
) : HostSystemInfoProvider {

    /**
     * Per-category cache cell. Deliberately NON-generic: values are stored as
     * [Any] and re-typed with safe casts during assembly (with all-unknown
     * fallback models), which collapses star-projection bookkeeping while the
     * cast targets are the very types assemble() guarantees.
     */
    private inner class Entry(
        @Volatile var value: Any?,
        val policy: HostInfoRefreshPolicy,
        @Volatile var collector: () -> Any?,
        @Volatile var status: HostInfoAvailability = HostInfoAvailability.UNAVAILABLE,
        @Volatile var collectedAtMs: Long = 0L,
        @Volatile var everSucceeded: Boolean = false
    ) {
        fun isExpired(nowMs: Long): Boolean =
            nowMs - collectedAtMs >= policy.ttlMillis || collectedAtMs == 0L

        /** Force refresh spacing guard against rate-limited platform surfaces. */
        fun eligibleForForce(nowMs: Long): Boolean =
            nowMs - collectedAtMs >= HostInfoRefreshPolicy.MIN_FORCED_REFRESH_MS

        fun invalidate() {
            collectedAtMs = 0L
            // Values intentionally kept: next read re-collects; meanwhile an
            // old but honest model beats a blank UI flash.
        }
    }

    private val refreshMutex = Mutex()
    private val entries = mutableMapOf<HostInfoCategory, Entry>()
    private val entryLock = Any()

    @Volatile
    private var lastAssembled: HostSystemInfoSnapshot? = null

    init {
        synchronized(entryLock) {
            fun register(
                category: HostInfoCategory,
                unknownValue: Any?,
                collector: () -> Any?
            ) {
                entries[category] = Entry(
                    value = unknownValue,
                    policy = HostInfoRefreshPolicy.forCategory(category),
                    collector = collector
                )
            }

            register(HostInfoCategory.SYSTEM, unknownDevice()) { sources.collectSystem() }
            register(HostInfoCategory.CPU, unknownCpu()) { sources.collectCpu() }
            register(HostInfoCategory.GPU, HostGpuInfo.unknown()) { sources.collectGpu() }
            register(HostInfoCategory.MEMORY, unknownMemory()) { sources.collectMemory() }
            register(HostInfoCategory.STORAGE, unknownStorage()) { sources.collectStorage() }
            register(HostInfoCategory.BATTERY, unknownBattery()) { sources.collectBattery() }
            // Thermal mirrors the SAME-cycle battery temperature; collect()
            // rebinds this collector right before refreshing thermal.
            register(HostInfoCategory.THERMAL, unknownThermal()) {
                sources.collectThermal(null)
            }
            register(HostInfoCategory.NETWORK, HostNetworkInfo.offline()) { sources.collectNetwork() }
            register(HostInfoCategory.PROCESSES, HostProcessInfo.unknown()) { sources.collectProcesses() }
            // Swap rides the JUST-refreshed memory slice so one meminfo read
            // serves both categories per refresh cycle.
            register(HostInfoCategory.SWAP, unknownSwap()) {
                (entries[HostInfoCategory.MEMORY]?.value as? HostMemoryInfo)?.swap
                    ?: sources.collectMemory().swap
                    ?: unknownSwap()
            }
        }
    }

    override suspend fun snapshot(): HostSystemInfoSnapshot =
        collect(required = emptySet(), force = false)

    override suspend fun refreshSnapshot(): HostSystemInfoSnapshot =
        collect(required = emptySet(), force = true)

    override suspend fun snapshotOf(categories: Set<HostInfoCategory>): HostSystemInfoSnapshot =
        collect(required = categories, force = false)

    override fun lastSnapshot(): HostSystemInfoSnapshot? = lastAssembled

    override fun invalidateAll() {
        synchronized(entryLock) {
            entries.values.forEach { it.invalidate() }
        }
        lastAssembled = null
    }

    // ── Refresh cycle ───────────────────────────────────────────────────────

    /**
     * One serialized assembly pass. Order constraints that matter:
     * BATTERY before THERMAL (same-cycle temperature mirror),
     * MEMORY before SWAP (single meminfo read), everything else independent.
     */
    private suspend fun collect(
        required: Set<HostInfoCategory>,
        force: Boolean
    ): HostSystemInfoSnapshot = withContext(ioDispatcher) {
        refreshMutex.withLock {
            refreshOne(HostInfoCategory.BATTERY, required, force)
            refreshOne(HostInfoCategory.MEMORY, required, force)
            refreshOne(HostInfoCategory.SWAP, required, force)
            refreshOne(HostInfoCategory.CPU, required, force)
            refreshOne(HostInfoCategory.GPU, required, force)
            refreshOne(HostInfoCategory.STORAGE, required, force)
            refreshOne(HostInfoCategory.SYSTEM, required, force)

            synchronized(entryLock) {
                entries[HostInfoCategory.THERMAL]?.collector = {
                    sources.collectThermal(batteryTemperatureNow())
                }
            }
            refreshOne(HostInfoCategory.THERMAL, required, force)
            refreshOne(HostInfoCategory.NETWORK, required, force)
            refreshOne(HostInfoCategory.PROCESSES, required, force)

            assemble(wallClockMs())
        }
    }

    private fun batteryTemperatureNow(): Float? =
        synchronized(entryLock) {
            (entries[HostInfoCategory.BATTERY]?.value as? HostBatteryInfo)?.temperatureC
        }

    private fun refreshOne(category: HostInfoCategory, required: Set<HostInfoCategory>, force: Boolean) {
        synchronized(entryLock) {
            val entry = entries[category] ?: return
            val nowMs = wallClockMs()
            // Three orthogonal reasons to pay for a collection:
            //  - force: caller demanded fresh data (rate-guarded);
            //  - selective: the caller NAMED this category — always honored;
            //  - standard pass: nothing named, refill whatever expired.
            val shouldCollect = when {
                force -> entry.eligibleForForce(nowMs)
                required.isEmpty() -> entry.isExpired(nowMs)
                else -> category in required
            }
            if (!shouldCollect) return
            try {
                entry.value = entry.collector.invoke()
                entry.status = HostInfoAvailability.AVAILABLE
                entry.everSucceeded = true
                entry.collectedAtMs = wallClockMs()
            } catch (t: Throwable) {
                Logger.e(TAG, "host-info category $category failed to collect: ${t.javaClass.simpleName}: ${t.message}")
                // Last-known-good stays; stamp untouched → visible staleness.
                if (!entry.everSucceeded) entry.status = HostInfoAvailability.ERROR
            }
        }
    }

    private fun assemble(nowMs: Long): HostSystemInfoSnapshot {
        synchronized(entryLock) {
            val caps = HashMap<HostInfoCategory, HostInfoAvailability>(entries.size)
            val stamps = HashMap<HostInfoCategory, Long>()
            for ((category, entry) in entries) {
                caps[category] = entry.status
                if (entry.everSucceeded && entry.collectedAtMs > 0L) stamps[category] = entry.collectedAtMs
            }
            return HostSystemInfoSnapshot(
                collectedAtEpochMs = nowMs,
                cpu = typed(entries[HostInfoCategory.CPU]) { unknownCpu() },
                gpu = typed(entries[HostInfoCategory.GPU]) { HostGpuInfo.unknown() },
                memory = typed(entries[HostInfoCategory.MEMORY]) { unknownMemory() },
                swap = typed(entries[HostInfoCategory.SWAP]) { unknownSwap() },
                storage = typed(entries[HostInfoCategory.STORAGE]) { unknownStorage() },
                device = typed(entries[HostInfoCategory.SYSTEM]) { unknownDevice() },
                battery = typed(entries[HostInfoCategory.BATTERY]) { unknownBattery() },
                thermal = typed(entries[HostInfoCategory.THERMAL]) { unknownThermal() },
                network = typed(entries[HostInfoCategory.NETWORK]) { HostNetworkInfo.offline() },
                processes = typed(entries[HostInfoCategory.PROCESSES]) { HostProcessInfo.unknown() },
                capabilities = caps,
                collectedAtPerCategory = stamps
            )
        }
    }

    /** Safe-cast accessor with an explicit never-fabricate fallback model. */
    private inline fun <reified T : Any> typed(entry: Entry?, fallbackValue: () -> T): T =
        (entry?.value as? T) ?: fallbackValue()

    // Initial-value factories for never-yet-collected state (explicit
    // unknowns identical to Snapshot.empty(); NOT fabricated facts).

    private fun unknownSwap(): HostSwapInfo = HostSwapInfo(totalKb = null, freeKb = null, usedKb = null)

    private fun unknownThermal(): HostThermalInfo = HostThermalInfo(
        status = null, severityLabel = null, headroom = null,
        cpuTemperatureC = null, gpuTemperatureC = null, skinTemperatureC = null,
        batteryTemperatureC = null
    )

    private fun unknownCpu(): HostCpuInfo = HostCpuInfo(
        logicalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(HostCpuInfo.FALLBACK_CORES),
        onlineCores = null, architecture = null, supportedAbis = emptyList(),
        cpuModelName = null, cpuImplementation = null, coresPerCoreInfo = emptyList(),
        minFrequencyKhz = null, maxFrequencyKhz = null, currentFrequencyKhz = null,
        overallUsagePercent = null, perCoreUsagePercent = emptyList(),
        loadAvg1m = null, loadAvg5m = null, loadAvg15m = null
    )

    private fun unknownMemory(): HostMemoryInfo = HostMemoryInfo(
        memTotalKb = null, memFreeKb = null, memAvailableKb = null,
        buffersKb = null, cachedKb = null, sReclaimableKb = null, usedLinuxKb = null,
        androidTotalKb = null, androidAvailKb = null, androidThresholdKb = null,
        isLowMemory = null, swap = null, zram = null, memoryClassMb = null, largeMemoryClassMb = null
    )

    private fun unknownStorage(): HostStorageInfo = HostStorageInfo(
        primaryVolume = null, externalAppVolume = null, sharedStorageVolume = null,
        allVolumes = emptyList()
    )

    private fun unknownDevice(): HostDeviceInfo = HostDeviceInfo(
        androidRelease = null, sdkInt = 0, codename = null, incremental = null,
        securityPatch = null, manufacturer = null, brand = null, model = null,
        product = null, device = null, board = null, hardware = null, bootloader = null,
        fingerprint = null, kernelRelease = null, kernelVersion = null, machineArch = null,
        selinuxEnforcing = null, uptimeMillis = null, deepSleepMillis = null, bootTimeEpochMillis = null
    )

    private fun unknownBattery(): HostBatteryInfo = HostBatteryInfo(
        present = null, percent = null,
        charging = HostBatteryInfo.ChargingState.UNKNOWN,
        powerSource = HostBatteryInfo.PowerSource.UNKNOWN,
        health = HostBatteryInfo.Health.UNKNOWN,
        temperatureC = null, voltageMillivolts = null, currentMicroamps = null,
        chargeCounterMicroAh = null, designCapacityMilliampHours = null,
        isPowerSaveMode = null
    )

    companion object {
        private const val TAG = "HostSystemInfoProvider"
    }
}
