package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.CpuUsageCalculator
import com.fem.linexa.domain.resource.MetricAvailability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide CPU-delta bookkeeping shared by every `/proc` consumer.
 *
 * ## The defect this replaces
 *
 * Before this class each consumer kept its own previous-tick table:
 * [ProcResourceMonitor] (used by Workspace Performance, Sessions and both task
 * managers) had one map, [HostProcessScanner] had another, and every
 * [HostResourceSampler] instance had a third. When two screens polled the same
 * pid, the second reader moved the baseline milliseconds after the first.
 * The next delta window was therefore tiny, and the percentage either
 * quantised to 0 or was rejected as a non-positive interval. The screen whose
 * baseline had just been stolen showed a dash; the other showed a number based
 * on an arbitrary slice of time.
 *
 * One singleton table plus the [MIN_SAMPLE_INTERVAL_MS] floor makes every
 * caller observe the *same* answer for the same process during a sampling
 * window. A caller arriving inside the floor receives the previous result
 * instead of advancing the baseline.
 *
 * ## Identity and resets
 *
 * The key is the pid, but the entry carries the kernel start time
 * (`/proc/<pid>/stat` field 22). A recycled pid is a different identity and
 * starts [MetricAvailability.PENDING]. A backwards tick counter is treated as
 * a reset: the baseline advances to the new reading but the interval returns
 * unknown rather than a negative or clamped value.
 *
 * Limits are deliberate: process rows use own `utime + stime` ticks only.
 * Reaped-child columns are excluded so summing rows cannot double-count live
 * children. Percentages are shares of total device capacity, normalised by the
 * online-core count supplied by the caller.
 */
@Singleton
class ProcessCpuTracker @Inject constructor() {

    /**
     * @property percent null when this process has no usable interval yet.
     * @property availability distinguishes "waiting for the second sample"
     *   from a real zero (which is [MetricAvailability.AVAILABLE]).
     */
    data class Reading(
        val percent: Float?,
        val availability: MetricAvailability
    ) {
        companion object {
            val PENDING = Reading(null, MetricAvailability.PENDING)
        }
    }

    private val lock = Any()
    private val entries = HashMap<Int, Entry>()

    private var lastPruneAtMs = 0L

    /**
     * Record one observation and return the CPU value the process has for the
     * interval since the baseline.
     *
     * Callers must pass the identity (`pid`, [startTimeTicks]) and the current
     * own ticks from the *same* stat read. The first observation of an
     * identity, or any observation before [MIN_SAMPLE_INTERVAL_MS] has passed,
     * returns the last result without moving the baseline.
     */
    fun observe(
        pid: Int,
        startTimeTicks: Long,
        ownTicks: Long,
        atMs: Long,
        clockTicksPerSecond: Long,
        capacityCores: Int
    ): Reading {
        if (pid <= 0) return Reading.PENDING
        synchronized(lock) {
            pruneIfDue(atMs)
            val identity = Identity(pid, startTimeTicks)
            val last = entries[pid]
            if (last == null || last.identity != identity) {
                entries[pid] = Entry(identity, ownTicks, atMs, Reading.PENDING)
                return Reading.PENDING
            }

            val elapsedMs = atMs - last.atMs
            if (elapsedMs < MIN_SAMPLE_INTERVAL_MS) {
                // A second consumer asking inside the same window must not
                // steal the baseline. Return exactly what the first consumer
                // saw; if that was still pending, it remains pending.
                return last.reading
            }

            val percent = CpuUsageCalculator.processPercent(
                previousTicks = last.ticks,
                currentTicks = ownTicks,
                elapsedMs = elapsedMs,
                clockTicksPerSecond = clockTicksPerSecond,
                capacityCores = capacityCores
            )
            val reading = if (percent == null) {
                Reading.PENDING
            } else {
                Reading(percent, MetricAvailability.AVAILABLE)
            }
            entries[pid] = Entry(identity, ownTicks, atMs, reading)
            return reading
        }
    }

    /**
     * Drop the baseline for [pid].
     *
     * Callers use this when a workspace/session ends. Identity checking already
     * makes a recycled pid safe; this only bounds the table earlier than the
     * stale-age prune. Screens that share a live process should not call this
     * while another consumer is still sampling it.
     */
    fun forget(pid: Int) {
        synchronized(lock) { entries.remove(pid) }
    }

    internal fun trackedCount(): Int = synchronized(lock) { entries.size }

    private fun pruneIfDue(nowMs: Long) {
        if (lastPruneAtMs != 0L && nowMs - lastPruneAtMs < PRUNE_INTERVAL_MS) return
        lastPruneAtMs = nowMs
        val cutoff = nowMs - STALE_BASELINE_MS
        entries.entries.removeAll { (_, entry) -> entry.atMs < cutoff }
    }

    private data class Identity(val pid: Int, val startTimeTicks: Long)

    private data class Entry(
        val identity: Identity,
        val ticks: Long,
        val atMs: Long,
        val reading: Reading
    )

    companion object {
        /**
         * 1 s: longer than every UI polling loop's inter-arrival jitter and
         * short enough that a workspace does not lag visibly. This is the
         * minimum delta window, not the UI refresh cadence.
         */
        const val MIN_SAMPLE_INTERVAL_MS = 1_000L

        private const val STALE_BASELINE_MS = 5 * 60 * 1000L
        private const val PRUNE_INTERVAL_MS = 60_000L
    }
}
