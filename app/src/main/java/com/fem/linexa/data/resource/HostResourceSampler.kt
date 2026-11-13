package com.fem.linexa.data.resource

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.os.Process
import com.fem.linexa.domain.resource.MetricAvailability
import com.fem.linexa.domain.resource.ProcMetricParsers
import java.io.File
import javax.inject.Inject

/**
 * Measures the two host scopes the Sessions overview must keep separate from
 * the Linux workspace: the **device** (total/available RAM, overall and
 * per-core CPU) and the **Linexa app process** (PSS, CPU share, threads).
 *
 * The parsers and delta arithmetic live in
 * [com.fem.linexa.domain.resource.CpuUsageCalculator] and
 * [com.fem.linexa.domain.resource.CpuidleCpuCalculator], not here, so no two
 * screens can drift into different definitions of "device CPU".
 *
 * ## Shared state
 *
 * Device CPU comes from the singleton [DeviceCpuMonitor], app CPU from the
 * singleton [ProcessCpuTracker], and thread CPU from [AppThreadCpuTracker].
 * This class itself is unscoped (each ViewModel injects its own instance), but
 * no instance owns a CPU baseline any more. Two screens polling at once see
 * the same snapshot instead of overwriting each other's previous ticks.
 *
 * Every reading is nullable: an unavailable value is reported as unknown,
 * never coerced to zero. CPU is a delta, so the first call has no answer by
 * definition.
 */
class HostResourceSampler @Inject constructor(
    private val application: Application,
    private val topologyProvider: CpuTopologyProvider,
    private val deviceCpuMonitor: DeviceCpuMonitor =
        DeviceCpuMonitor(topologyProvider, AndroidDeviceCpuCounterReader()),
    private val processCpuTracker: ProcessCpuTracker = ProcessCpuTracker(),
    private val appThreadCpuTracker: AppThreadCpuTracker =
        AppThreadCpuTracker(AndroidThreadStatReader(), topologyProvider)
) {

    data class DeviceMemory(val totalBytes: Long?, val availableBytes: Long?) {
        /**
         * RAM in use = total − available (`MemAvailable` semantics: reclaimable
         * cache counts as available). Null when either side is unknown. One
         * definition for every screen, so Activity and Sessions can never show
         * two different "used" figures for the same device.
         */
        val usedBytes: Long?
            get() {
                val total = totalBytes ?: return null
                val avail = availableBytes ?: return null
                return (total - avail).coerceIn(0L, total)
            }

        /** 0..1 share of RAM in use, or null when unknown. */
        val usedFraction: Float?
            get() {
                val total = totalBytes?.takeIf { it > 0 } ?: return null
                val used = usedBytes ?: return null
                return (used.toFloat() / total).coerceIn(0f, 1f)
            }
    }

    data class AppMemory(val pssBytes: Long?, val rssBytes: Long?, val isProportional: Boolean)

    /**
     * @property perCorePercent Keyed by kernel core number, so an offline core
     *   cannot shift another core's value into its slot.
     * @property readable false when neither `/proc/stat` nor cpuidle could be
     *   read. On modern Android `/proc/stat` is SELinux-denied to ordinary
     *   apps; cpuidle is the non-root fallback. `readable = true` with a null
     *   value means a baseline is still pending, which is different from
     *   genuinely unavailable.
     * @property availability explicit reason behind a null [overallPercent].
     * @property source which counter family produced this reading.
     */
    data class DeviceCpu(
        val overallPercent: Float?,
        val perCorePercent: Map<Int, Float?>,
        val readable: Boolean = true,
        val availability: MetricAvailability = if (overallPercent != null) {
            MetricAvailability.AVAILABLE
        } else {
            MetricAvailability.PENDING
        },
        val source: DeviceCpuMonitor.Source = DeviceCpuMonitor.Source.NONE
    )

    val topology
        get() = topologyProvider.topology()

    val cores: Int
        get() = topology.capacityCores

    fun deviceMemory(): DeviceMemory? = runCatching {
        val info = ActivityManager.MemoryInfo()
        val am = application.getSystemService(ActivityManager::class.java)
            ?: return@runCatching null
        am.getMemoryInfo(info)
        DeviceMemory(
            totalBytes = info.totalMem.takeIf { it > 0 },
            availableBytes = info.availMem.takeIf { it >= 0 }
        )
    }.getOrNull()

    fun appMemory(): AppMemory? = runCatching {
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val pss = mem.totalPss * 1024L
        val rss = if (mem.totalPss > 0) null else mem.getTotalPrivateDirty() * 1024L
        AppMemory(
            pssBytes = pss.takeIf { it > 0 },
            rssBytes = rss?.takeIf { it > 0 },
            isProportional = mem.totalPss > 0
        )
    }.getOrNull()

    /**
     * Overall + per-core CPU from the authoritative device monitor.
     *
     * The monitor tries `/proc/stat` first and falls back to cpuidle; see
     * [DeviceCpuMonitor]. A first sample is [MetricAvailability.PENDING]; only
     * a device where neither source exists reports `readable = false`.
     */
    fun deviceCpu(): DeviceCpu {
        val sample = deviceCpuMonitor.snapshot()
        return DeviceCpu(
            overallPercent = sample.overallPercent,
            perCorePercent = sample.perCorePercent,
            readable = sample.source != DeviceCpuMonitor.Source.NONE,
            availability = sample.availability,
            source = sample.source
        )
    }

    /**
     * This app process's CPU share of device capacity.
     *
     * Own `utime + stime` ticks only; no reaped-child columns. The baseline is
     * shared through [ProcessCpuTracker], so Activity, Sessions and Task
     * Manager cannot shorten each other's delta window.
     */
    fun appCpuPercent(): Float? = runCatching {
        val pid = Process.myPid()
        val stat = File("/proc/$pid/stat").readText()
        val sample = ProcMetricParsers.pidStatSample(stat) ?: return@runCatching null
        processCpuTracker.observe(
            pid = pid,
            startTimeTicks = sample.startTimeTicks,
            ownTicks = sample.ownTicks,
            atMs = nowMs(),
            clockTicksPerSecond = topologyProvider.clockTicksPerSecond,
            capacityCores = cores
        ).percent
    }.getOrNull()

    /**
     * Per-thread CPU of this app process from `/proc/<pid>/task`.
     *
     * Android normally exposes same-UID task stats; when a hardened kernel
     * denies them the returned snapshot has `readable = false` and consumers
     * must say so. First samples are pending, not zero.
     */
    fun appThreads(): AppThreadCpuSnapshot =
        appThreadCpuTracker.snapshot(Process.myPid())

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
