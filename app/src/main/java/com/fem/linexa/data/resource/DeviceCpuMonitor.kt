package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.CpuidleCpuCalculator
import com.fem.linexa.domain.resource.CpuUsageCalculator
import com.fem.linexa.domain.resource.DeviceCpuDelta
import com.fem.linexa.domain.resource.MetricAvailability
import com.fem.linexa.domain.resource.ProcMetricParsers
import com.fem.linexa.domain.resource.ProcStatSnapshot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level CPU counter source. Separated from [DeviceCpuMonitor] so the
 * fallback/coalescing rules are JVM-testable without Android sysfs.
 */
interface DeviceCpuCounterReader {
    /** Raw `/proc/stat` body, or null when SELinux/kernel policy denies it. */
    fun readProcStat(): String?

    /** Online core numbers from `/sys/devices/system/cpu/online`. */
    fun readOnlineCores(): List<Int>?

    /**
     * Cumulative idle microseconds per kernel core, summed over every readable
     * cpuidle state. Null when the kernel has no usable cpuidle counters.
     */
    fun readCpuidleTimesMicros(): Map<Int, Long>?

    /** CLOCK_MONOTONIC in microseconds; excludes device suspend, like cpuidle. */
    fun nowMonotonicMicros(): Long
}

/** Production reader over `/proc/stat` and sysfs cpuidle counters. */
@Singleton
class AndroidDeviceCpuCounterReader @Inject constructor() : DeviceCpuCounterReader {

    override fun readProcStat(): String? =
        runCatching { File("/proc/stat").readText() }.getOrNull()

    override fun readOnlineCores(): List<Int>? =
        runCatching { File("/sys/devices/system/cpu/online").readText() }
            .getOrNull()
            ?.let(ProcMetricParsers::cpuRangeList)

    /**
     * Sum all cpuidle state counters for each core.
     *
     * A core is included only when every `stateN/time` file was numeric. A
     * partial sum would silently overstate busy time, so an unreadable state
     * disqualifies that core for this snapshot; other cores still contribute.
     */
    override fun readCpuidleTimesMicros(): Map<Int, Long>? {
        val base = File("/sys/devices/system/cpu")
        val coreDirs = runCatching {
            base.list { _, name -> CPU_DIR.matches(name) }?.toList()
        }.getOrNull() ?: return null
        if (coreDirs.isEmpty()) return null

        val out = LinkedHashMap<Int, Long>(coreDirs.size)
        for (dir in coreDirs.sortedBy { it.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }) {
            val core = dir.removePrefix("cpu").toIntOrNull() ?: continue
            if (core < 0) continue
            val cpuidle = File(base, "$dir/cpuidle")
            val stateDirs = runCatching {
                cpuidle.list { _, name -> STATE_DIR.matches(name) }?.toList()
            }.getOrNull() ?: continue
            if (stateDirs.isEmpty()) continue

            var total = 0L
            var readable = true
            for (state in stateDirs) {
                val value = runCatching {
                    File(cpuidle, "$state/time").readText().trim().toLongOrNull()
                }.getOrNull()
                if (value == null || value < 0L) {
                    readable = false
                    break
                }
                total += value
            }
            if (readable) out[core] = total
        }
        return out.takeIf { it.isNotEmpty() }
    }

    override fun nowMonotonicMicros(): Long = System.nanoTime() / 1_000L

    private companion object {
        val CPU_DIR = Regex("^cpu\\d+$")
        val STATE_DIR = Regex("^state\\d+$")
    }
}

/**
 * The authoritative device-wide CPU reading for every Linexa screen.
 *
 * ## Source ladder
 *
 * 1. `/proc/stat` deltas when the kernel allows the read. This is the same
 *    parser (`CpuUsageCalculator`) the app already tests, including the
 *    `idle + iowait` idle convention and first-eight-column denominator.
 * 2. Per-core cpuidle counters when `/proc/stat` is denied. The delta
 *    arithmetic lives in [CpuidleCpuCalculator] so resets, hotplug and
 *    zero-length intervals are asserted on the JVM.
 * 3. Explicit [MetricAvailability.UNAVAILABLE] when neither source exists.
 *
 * ## One snapshot, many screens
 *
 * The monitor is a singleton. It owns the previous counters, so Activity,
 * Sessions, Task Manager, Workspace Performance and Device Info cannot each
 * advance a different baseline. Calls inside [MIN_SAMPLE_INTERVAL_US] return
 * the exact same snapshot object instead of re-reading counters; this is what
 * keeps the CPU figure consistent across screens and makes multiple pollers
 * cheap.
 *
 * A first sample is [MetricAvailability.PENDING] with null values, never 0.
 * Switching source or losing one resets the relevant baseline so a delta can
 * never span two different clocks.
 */
@Singleton
class DeviceCpuMonitor @Inject constructor(
    private val topologyProvider: CpuTopologyProvider,
    private val reader: DeviceCpuCounterReader
) {

    enum class Source {
        PROC_STAT,
        CPUIDLE,
        NONE
    }

    /**
     * @property procStat parsed `/proc/stat` snapshot for the same read that
     *   produced [overallPercent]. Null when the fallback is in use. Device Info
     *   reuses it for the guest stat overlay's raw tick totals.
     * @property rawProcStat the exact body, kept only so the overlay can sum
     *   all ten kernel columns without a second read.
     */
    data class Snapshot(
        val overallPercent: Float?,
        val perCorePercent: Map<Int, Float?>,
        val availability: MetricAvailability,
        val source: Source,
        val capturedAtMs: Long,
        val procStat: ProcStatSnapshot? = null,
        val rawProcStat: String? = null
    )

    private val lock = Any()

    private var lastAttemptAtUs = 0L
    private var lastSnapshot: Snapshot? = null

    private var lastProcStat: ProcStatSnapshot? = null
    private var lastProcStatAtUs = 0L

    private var lastIdleMicros: Map<Int, Long>? = null
    private var lastIdleAtUs = 0L

    /**
     * Return the newest authoritative device CPU snapshot, reading counters
     * only when [MIN_SAMPLE_INTERVAL_US] has elapsed since the previous read.
     */
    fun snapshot(): Snapshot {
        synchronized(lock) {
            val nowUs = reader.nowMonotonicMicros()
            val cached = lastSnapshot
            if (cached != null && nowUs - lastAttemptAtUs < MIN_SAMPLE_INTERVAL_US) {
                return cached
            }
            lastAttemptAtUs = nowUs

            val body = reader.readProcStat()
            val proc = CpuUsageCalculator.parseProcStat(body)
            if (proc != null) {
                lastIdleMicros = null
                lastIdleAtUs = 0L
                return procSnapshot(proc, body, nowUs)
            }

            // /proc/stat denied: never diff idle counters against a proc
            // baseline that came from a different clock.
            lastProcStat = null
            lastProcStatAtUs = 0L
            return idleSnapshot(nowUs)
        }
    }

    private fun procSnapshot(
        current: ProcStatSnapshot,
        raw: String?,
        nowUs: Long
    ): Snapshot {
        val previous = lastProcStat
        val delta: DeviceCpuDelta = CpuUsageCalculator.deviceDelta(previous, current)
        val capturedAtMs = nowUs / 1_000L
        lastProcStat = current
        lastProcStatAtUs = nowUs
        return Snapshot(
            overallPercent = delta.overallPercent,
            perCorePercent = delta.perCorePercent,
            availability = delta.availability,
            source = Source.PROC_STAT,
            capturedAtMs = capturedAtMs,
            procStat = current,
            rawProcStat = raw
        ).also { lastSnapshot = it }
    }

    private fun idleSnapshot(nowUs: Long): Snapshot {
        val online = reader.readOnlineCores()
            ?.takeIf { it.isNotEmpty() }
            ?: topologyProvider.topology().onlineCores
        val readableIdle = reader.readCpuidleTimesMicros()
        val current = readableIdle?.filterKeys { it in online }?.takeIf { it.isNotEmpty() }
        if (current == null) {
            lastIdleMicros = null
            lastIdleAtUs = 0L
            return Snapshot(
                overallPercent = null,
                perCorePercent = emptyMap(),
                availability = MetricAvailability.UNAVAILABLE,
                source = Source.NONE,
                capturedAtMs = nowUs / 1_000L
            ).also { lastSnapshot = it }
        }

        val previous = lastIdleMicros
        val elapsedUs = if (previous == null) 0L else nowUs - lastIdleAtUs
        val delta = CpuidleCpuCalculator.deviceDelta(previous, current, elapsedUs)
        lastIdleMicros = current
        lastIdleAtUs = nowUs
        return Snapshot(
            overallPercent = delta.overallPercent,
            perCorePercent = delta.perCorePercent,
            availability = delta.availability,
            source = Source.CPUIDLE,
            capturedAtMs = nowUs / 1_000L
        ).also { lastSnapshot = it }
    }

    internal fun lastAttemptForTest(): Long = synchronized(lock) { lastAttemptAtUs }

    internal fun procStatAgeForTest(): Long = synchronized(lock) { lastProcStatAtUs }

    companion object {
        /**
         * 600 ms: the Device Info provider's established CPU-delta floor. It
         * is shorter than every UI poll interval, so normal callers still get
         * a fresh reading, while simultaneous consumers cannot re-read and
         * shrink each other's windows.
         */
        const val MIN_SAMPLE_INTERVAL_US = 600_000L
    }
}
