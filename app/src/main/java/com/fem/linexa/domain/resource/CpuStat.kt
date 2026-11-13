package com.fem.linexa.domain.resource

/**
 * One cumulative `/proc/stat` row, reduced to the two numbers a delta needs.
 *
 * `total` is the sum of the kernel's first eight columns
 * (`user nice system idle iowait irq softirq steal`). `guest`/`guest_nice`
 * are excluded on purpose: they double-count time already present in
 * `user`/`nice`, and the kernel's own denominators exclude them.
 */
data class CpuTickSample(
    val idleTicks: Long,
    val totalTicks: Long
) {
    val busyTicks: Long
        get() = (totalTicks - idleTicks).coerceAtLeast(0L)
}

/** Aggregate and per-core cumulative ticks from one `/proc/stat` read. */
data class ProcStatSnapshot(
    val aggregate: CpuTickSample,
    val perCore: Map<Int, CpuTickSample>
)

/** A device CPU delta, with every value nullable for the same reason as elsewhere. */
data class DeviceCpuDelta(
    val overallPercent: Float?,
    val perCorePercent: Map<Int, Float?>,
    val availability: MetricAvailability
)

/**
 * Pure `/proc/stat` and CPU-delta arithmetic.
 *
 * Kept out of the Android sampler so the failure cases are assertable on the
 * JVM: a first sample, a counter reset, an offline core, a core that changed
 * index set, and a zero-length interval.
 */
object CpuUsageCalculator {

    private val WHITESPACE = Regex("\\s+")
    private const val REQUIRED_COLUMNS = 8
    private const val IDLE_INDEX = 3
    private const val IOWAIT_INDEX = 4

    /**
     * Parse the aggregate `cpu` line and every `cpuN` line.
     *
     * A malformed per-core row voids the per-core grid rather than admitting a
     * partial one; the aggregate line can still be used. If the aggregate line
     * is missing, a complete per-core grid is summed as a last resort.
     */
    fun parseProcStat(body: String?): ProcStatSnapshot? {
        if (body == null) return null
        var aggregate: CpuTickSample? = null
        val perCore = LinkedHashMap<Int, CpuTickSample>()
        var perCoreMalformed = false

        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("cpu")) continue
            if (line.length >= 4 && line[3].isDigit()) {
                val digits = line.drop(3).takeWhile { it.isDigit() }
                val core = digits.toIntOrNull()
                if (core == null) {
                    perCoreMalformed = true
                    continue
                }
                val sample = parseColumns(line.drop(3 + digits.length))
                if (sample == null) {
                    perCoreMalformed = true
                } else {
                    perCore[core] = sample
                }
            } else if (line.startsWith("cpu ")) {
                aggregate = parseColumns(line.removePrefix("cpu"))
            }
        }

        val cores = if (perCoreMalformed) emptyMap() else perCore
        val aggregateOrSum = aggregate ?: cores.values
            .takeIf { it.isNotEmpty() }
            ?.let { samples ->
                CpuTickSample(
                    idleTicks = samples.sumOf { it.idleTicks },
                    totalTicks = samples.sumOf { it.totalTicks }
                )
            }
        return aggregateOrSum?.let { ProcStatSnapshot(it, cores) }
    }

    private fun parseColumns(raw: String): CpuTickSample? {
        val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < REQUIRED_COLUMNS) return null
        val columns = ArrayList<Long>(REQUIRED_COLUMNS)
        for (i in 0 until REQUIRED_COLUMNS) {
            val value = tokens[i].toLongOrNull() ?: return null
            if (value < 0) return null
            columns += value
        }
        return CpuTickSample(
            idleTicks = columns[IDLE_INDEX] + columns[IOWAIT_INDEX],
            totalTicks = columns.sum()
        )
    }

    /**
     * Overall and per-core percentages between two readings.
     *
     * A first reading or a non-positive counter delta yields [MetricAvailability.PENDING]
     * with null values; unreadable `/proc/stat` is the caller's
     * [MetricAvailability.UNAVAILABLE] case. Per-core values are keyed by the
     * kernel core number, so an offline/online core can never relabel another.
     */
    fun deviceDelta(previous: ProcStatSnapshot?, current: ProcStatSnapshot): DeviceCpuDelta {
        val currentCores = current.perCore.keys.sorted()
        if (previous == null) {
            return DeviceCpuDelta(
                overallPercent = null,
                perCorePercent = currentCores.associateWith { null },
                availability = MetricAvailability.PENDING
            )
        }

        val totalDelta = current.aggregate.totalTicks - previous.aggregate.totalTicks
        val idleDelta = current.aggregate.idleTicks - previous.aggregate.idleTicks
        val overall = if (
            totalDelta > 0L &&
            idleDelta >= 0L &&
            idleDelta <= totalDelta
        ) {
            (((totalDelta - idleDelta).toDouble() / totalDelta) * 100.0)
                .coerceIn(0.0, 100.0)
                .toFloat()
        } else {
            null
        }

        val perCorePercent = currentCores.associateWith { core ->
            val now = current.perCore[core] ?: return@associateWith null
            val then = previous.perCore[core] ?: return@associateWith null
            val total = now.totalTicks - then.totalTicks
            val idle = now.idleTicks - then.idleTicks
            if (total <= 0L || idle < 0L || idle > total) {
                null
            } else {
                (((total - idle).toDouble() / total) * 100.0)
                    .coerceIn(0.0, 100.0)
                    .toFloat()
            }
        }

        return DeviceCpuDelta(
            overallPercent = overall,
            perCorePercent = perCorePercent,
            availability = if (overall != null) {
                MetricAvailability.AVAILABLE
            } else {
                MetricAvailability.PENDING
            }
        )
    }

    /**
     * One process's CPU share of total device capacity.
     *
     * [previousTicks] and [currentTicks] are **own** `utime + stime` ticks.
     * Reaped-child columns are deliberately not included here: summing rows
     * that each include their children would double-count live children, and
     * a parent's reaped-child history is not part of its current row.
     *
     * A backwards counter or non-positive interval is null, never a spike or
     * an invented zero. The caller resets the baseline when this happens.
     */
    fun processPercent(
        previousTicks: Long?,
        currentTicks: Long,
        elapsedMs: Long,
        clockTicksPerSecond: Long,
        capacityCores: Int
    ): Float? {
        if (previousTicks == null) return null
        if (currentTicks < previousTicks) return null
        if (elapsedMs <= 0L) return null
        if (clockTicksPerSecond <= 0L) return null
        if (capacityCores <= 0) return null
        val busySeconds = (currentTicks - previousTicks).toDouble() / clockTicksPerSecond
        val capacitySeconds = (elapsedMs / 1000.0) * capacityCores
        if (capacitySeconds <= 0.0) return null
        return ((busySeconds / capacitySeconds) * 100.0)
            .coerceIn(0.0, 100.0)
            .toFloat()
    }
}
