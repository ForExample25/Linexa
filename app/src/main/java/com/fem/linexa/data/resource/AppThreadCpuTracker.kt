package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.MetricAvailability
import com.fem.linexa.domain.resource.ProcMetricParsers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One sampled thread of the Linexa app process. */
data class AppThreadCpuSample(
    val tid: Int,
    /** Kernel `comm`; Android truncates this to 15 characters. */
    val name: String,
    /** Share of total device capacity, 0..100, normalised by online cores. */
    val cpuPercentOfDevice: Float?,
    val cpuAvailability: MetricAvailability
)

/**
 * Thread-level view of the app process.
 *
 * @property readable false when `/proc/<pid>/task` could not be listed or none
 *   of its stat rows parsed. Thread monitoring is an OS-permission surface on
 *   Android: same-UID task stats are normally readable, but a hardened kernel
 *   may deny them. The caller must show that limitation rather than an empty
 *   "0%" list.
 */
data class AppThreadCpuSnapshot(
    val threads: List<AppThreadCpuSample>,
    val readable: Boolean
) {
    val threadCount: Int get() = threads.size

    val busiest: AppThreadCpuSample?
        get() = threads.maxByOrNull { it.cpuPercentOfDevice ?: -1f }
}

/** `/proc/<pid>/task` seam, so thread parsing and deltas stay JVM-testable. */
interface ThreadStatReader {
    fun listThreadIds(pid: Int): List<Int>?
    fun readThreadStat(pid: Int, tid: Int): String?
}

/** Production thread reader. Same-UID `/proc/<pid>/task` is the only scope read. */
@Singleton
class AndroidThreadStatReader @Inject constructor() : ThreadStatReader {
    override fun listThreadIds(pid: Int): List<Int>? =
        runCatching {
            File("/proc/$pid/task").list()?.mapNotNull { it.toIntOrNull() }
        }.getOrNull()

    override fun readThreadStat(pid: Int, tid: Int): String? =
        runCatching { File("/proc/$pid/task/$tid/stat").readText() }.getOrNull()
}

/**
 * Samples the app's own threads with the same conventions as process rows.
 *
 * The tracker is a singleton so the Activity tab and Task Manager cannot each
 * own a different baseline for the same thread. It intentionally reads only
 * `/proc/<own pid>/task`: Android exposes nothing else to an ordinary app, and
 * reading other processes' threads is not needed for a Linexa self-scope
 * figure.
 */
@Singleton
class AppThreadCpuTracker @Inject constructor(
    private val reader: ThreadStatReader,
    private val topologyProvider: CpuTopologyProvider
) {

    private val tracker = ProcessCpuTracker()

    /** Test seam; production uses CLOCK_MONOTONIC like every other CPU delta. */
    internal var clockMs: () -> Long = { System.nanoTime() / 1_000_000L }

    fun snapshot(pid: Int): AppThreadCpuSnapshot {
        if (pid <= 0) return AppThreadCpuSnapshot(emptyList(), readable = false)
        val tids = reader.listThreadIds(pid)
            ?: return AppThreadCpuSnapshot(emptyList(), readable = false)
        if (tids.isEmpty()) return AppThreadCpuSnapshot(emptyList(), readable = false)

        val topology = topologyProvider.topology()
        val clockTicks = topologyProvider.clockTicksPerSecond
        val nowMs = clockMs()
        val samples = ArrayList<AppThreadCpuSample>(tids.size)
        for (tid in tids.sorted()) {
            val stat = reader.readThreadStat(pid, tid)
                ?.let(ProcMetricParsers::pidStatSample)
                ?: continue
            val reading = tracker.observe(
                pid = tid,
                startTimeTicks = stat.startTimeTicks,
                ownTicks = stat.ownTicks,
                atMs = nowMs,
                clockTicksPerSecond = clockTicks,
                capacityCores = topology.capacityCores
            )
            samples += AppThreadCpuSample(
                tid = tid,
                name = stat.name.ifBlank { "tid $tid" },
                cpuPercentOfDevice = reading.percent,
                cpuAvailability = reading.availability
            )
        }
        return AppThreadCpuSnapshot(
            threads = samples,
            readable = samples.isNotEmpty()
        )
    }
}
