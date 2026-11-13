package com.fem.linexa.domain.resource

/**
 * Device CPU usage derived from per-core cpuidle counters.
 *
 * This is the non-root fallback for devices where `/proc/stat` is
 * SELinux-denied to ordinary apps (the modern Android default). Every online
 * core publishes one cumulative microsecond counter per idle state under
 * `/sys/devices/system/cpu/cpuN/cpuidle/stateM/time`; summing the state
 * counters gives that core's cumulative idle time. A delta of two readings,
 * subtracted from the monotonic wall interval, is the core's busy time.
 *
 * ## Why this is honest
 *
 * - `idle` here is real kernel idle residency, not a guess or a random walk.
 * - A core only contributes when it was readable in *both* samples. A
 *   hotplugged/offlined core therefore cannot be counted as idle or busy.
 * - A counter that moves backwards or by more than the elapsed interval is a
 *   reset/suspend artefact; that core is reported as unknown rather than
 *   clamped into a plausible percentage.
 * - The aggregate denominator is the number of cores actually measured in
 *   both samples. This is the observable online capacity for the interval,
 *   which is the same convention `/proc/stat` uses (busy ticks over total
 *   ticks across online cores).
 *
 * The values are percentages of total device capacity: one core saturated
 * reads 1/cores × 100, all cores saturated reads 100.
 */
object CpuidleCpuCalculator {

    /**
     * Overall and per-core busy percentages between two cumulative idle
     * readings. [previous] is null for the first sample, which yields
     * [MetricAvailability.PENDING] rather than a fabricated zero.
     */
    fun deviceDelta(
        previous: Map<Int, Long>?,
        current: Map<Int, Long>,
        elapsedMicros: Long
    ): DeviceCpuDelta {
        val currentCores = current.keys.sorted()
        if (previous == null || elapsedMicros <= 0L || current.isEmpty()) {
            return DeviceCpuDelta(
                overallPercent = null,
                perCorePercent = currentCores.associateWith { null },
                availability = MetricAvailability.PENDING
            )
        }

        val perCore = LinkedHashMap<Int, Float?>(currentCores.size)
        var busyMicros = 0.0
        var measuredCores = 0
        var everyMeasuredCoreValid = true

        for (core in currentCores) {
            val now = current.getValue(core)
            val then = previous[core]
            if (then == null) {
                // Core was not measured in the previous sample (hotplugged in
                // or newly readable). It has no interval of its own yet.
                perCore[core] = null
                continue
            }
            val idleDelta = now - then
            if (idleDelta < 0L || idleDelta > elapsedMicros) {
                // Counter reset, wrap, or a clock/idle mismatch. Do not clamp:
                // the core's value for this interval is genuinely unknown.
                perCore[core] = null
                everyMeasuredCoreValid = false
                continue
            }
            measuredCores++
            val busy = (elapsedMicros - idleDelta).coerceAtLeast(0L)
            busyMicros += busy.toDouble()
            perCore[core] = ((busy.toDouble() / elapsedMicros) * 100.0)
                .coerceIn(0.0, 100.0)
                .toFloat()
        }

        val overall = if (measuredCores > 0 && everyMeasuredCoreValid) {
            ((busyMicros / (elapsedMicros.toDouble() * measuredCores)) * 100.0)
                .coerceIn(0.0, 100.0)
                .toFloat()
        } else {
            null
        }

        return DeviceCpuDelta(
            overallPercent = overall,
            perCorePercent = perCore,
            availability = if (overall != null) {
                MetricAvailability.AVAILABLE
            } else {
                MetricAvailability.PENDING
            }
        )
    }
}
