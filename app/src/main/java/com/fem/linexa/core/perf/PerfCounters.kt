package com.fem.linexa.core.perf

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase M0-A — the fixed, bounded counter set.
 *
 * There is deliberately no map keyed by an arbitrary name: every counter is a
 * field below, so the memory cost is constant for the life of the process
 * (three longs per terminal session are separate — see `TerminalSession`).
 *
 * All increments are gated on [DebugCapture], matching the rest of the
 * instrumentation: with the user's debug switch off, a counter update is one
 * volatile read and a return. Nothing is formatted or allocated until
 * [logSummary] is called from E7 (and only while capture is on).
 *
 * These are diagnostic totals, not accounting: they may be read while a
 * counter is being incremented and are allowed to race by a single operation.
 */
object PerfCounters {

    private const val TAG = "PerfCounters"

    private val repairPipelineInvocations = AtomicLong()
    private val repairOperations = AtomicLong()
    private val repairSkips = AtomicLong()
    private val dpkgRecoveryInvocations = AtomicLong()
    private val dpkgRecoveryDeferred = AtomicLong()
    private val dpkgRecoveryNotNeeded = AtomicLong()
    private val firstBootInvocations = AtomicLong()
    private val firstBootCommands = AtomicLong()
    private val firstBootSkips = AtomicLong()
    private val overlayTicks = AtomicLong()
    private val overlayTickNanos = AtomicLong()
    private val overlayFilesWritten = AtomicLong()
    private val bridgeScans = AtomicLong()
    private val bridgeScanNanos = AtomicLong()
    private val screenRevisions = AtomicLong()
    private val screenUpdates = AtomicLong()
    private val ptyEventsDropped = AtomicLong()
    private val lifecycleMutexWaits = AtomicLong()
    private val lifecycleMutexWaitNanos = AtomicLong()
    private val lifecycleMutexHoldNanos = AtomicLong()

    fun repairPipelineInvoked() = add(repairPipelineInvocations, 1L)
    fun repairOperation() = add(repairOperations, 1L)
    fun repairSkipped() = add(repairSkips, 1L)

    fun dpkgRecoveryInvoked() = add(dpkgRecoveryInvocations, 1L)
    fun dpkgRecoveryDeferred() = add(dpkgRecoveryDeferred, 1L)
    fun dpkgRecoveryNotNeeded() = add(dpkgRecoveryNotNeeded, 1L)

    fun firstBootInvoked() = add(firstBootInvocations, 1L)
    fun firstBootCommand() = add(firstBootCommands, 1L)
    fun firstBootSkipped() = add(firstBootSkips, 1L)

    fun overlayTick(durationNanos: Long, filesWritten: Int) {
        if (!DebugCapture.isEnabled()) return
        overlayTicks.incrementAndGet()
        overlayTickNanos.addAndGet(durationNanos.coerceAtLeast(0L))
        overlayFilesWritten.addAndGet(filesWritten.coerceAtLeast(0).toLong())
    }

    fun overlayFilesWritten(count: Int) = add(overlayFilesWritten, count.coerceAtLeast(0).toLong())

    fun bridgeScan(durationNanos: Long) {
        if (!DebugCapture.isEnabled()) return
        bridgeScans.incrementAndGet()
        bridgeScanNanos.addAndGet(durationNanos.coerceAtLeast(0L))
    }

    /** One emulator screen revision (output or runtime colors). */
    fun screenRevision() = add(screenRevisions, 1L)

    /** One screen-update notification emitted by a terminal session. */
    fun screenUpdate() = add(screenUpdates, 1L)

    /** One bounded-channel PtyEvent dropped by a terminal session (diagnostic). */
    fun ptyEventDropped() = add(ptyEventsDropped, 1L)

    /** Returns -1 when capture is off; pass the result to [mutexWaitEnd]. */
    fun mutexWaitBegin(): Long =
        if (DebugCapture.isEnabled()) PerfTrace.nowNanos() else -1L

    fun mutexWaitEnd(startNanos: Long) {
        if (startNanos < 0L || !DebugCapture.isEnabled()) return
        lifecycleMutexWaits.incrementAndGet()
        lifecycleMutexWaitNanos.addAndGet((PerfTrace.nowNanos() - startNanos).coerceAtLeast(0L))
    }

    /**
     * A transition that was cancelled while waiting never held the lock; its
     * wait is still real and is recorded under the same totals.
     */
    fun mutexWaitAbandoned(startNanos: Long) = mutexWaitEnd(startNanos)

    /** Returns -1 when capture is off; pass the result to [mutexHoldEnd]. */
    fun mutexHoldBegin(): Long =
        if (DebugCapture.isEnabled()) PerfTrace.nowNanos() else -1L

    fun mutexHoldEnd(startNanos: Long) {
        if (startNanos < 0L || !DebugCapture.isEnabled()) return
        lifecycleMutexHoldNanos.addAndGet((PerfTrace.nowNanos() - startNanos).coerceAtLeast(0L))
    }

    /** One bounded snapshot (allocated only when capture is on). */
    fun snapshot(): Snapshot = Snapshot(
        repairPipelineInvocations = repairPipelineInvocations.get(),
        repairOperations = repairOperations.get(),
        repairSkips = repairSkips.get(),
        dpkgRecoveryInvocations = dpkgRecoveryInvocations.get(),
        dpkgRecoveryDeferred = dpkgRecoveryDeferred.get(),
        dpkgRecoveryNotNeeded = dpkgRecoveryNotNeeded.get(),
        firstBootInvocations = firstBootInvocations.get(),
        firstBootCommands = firstBootCommands.get(),
        firstBootSkips = firstBootSkips.get(),
        overlayTicks = overlayTicks.get(),
        overlayTickNanos = overlayTickNanos.get(),
        overlayFilesWritten = overlayFilesWritten.get(),
        bridgeScans = bridgeScans.get(),
        bridgeScanNanos = bridgeScanNanos.get(),
        screenRevisions = screenRevisions.get(),
        screenUpdates = screenUpdates.get(),
        ptyEventsDropped = ptyEventsDropped.get(),
        lifecycleMutexWaits = lifecycleMutexWaits.get(),
        lifecycleMutexWaitNanos = lifecycleMutexWaitNanos.get(),
        lifecycleMutexHoldNanos = lifecycleMutexHoldNanos.get()
    )

    /** Logs one compact line; no-op unless capture is on. */
    fun logSummary() {
        if (!DebugCapture.isEnabled()) return
        Logger.i(TAG, "M0A counters " + snapshot().summary())
    }

    fun resetForTesting() {
        repairPipelineInvocations.set(0L)
        repairOperations.set(0L)
        repairSkips.set(0L)
        dpkgRecoveryInvocations.set(0L)
        dpkgRecoveryDeferred.set(0L)
        dpkgRecoveryNotNeeded.set(0L)
        firstBootInvocations.set(0L)
        firstBootCommands.set(0L)
        firstBootSkips.set(0L)
        overlayTicks.set(0L)
        overlayTickNanos.set(0L)
        overlayFilesWritten.set(0L)
        bridgeScans.set(0L)
        bridgeScanNanos.set(0L)
        screenRevisions.set(0L)
        screenUpdates.set(0L)
        ptyEventsDropped.set(0L)
        lifecycleMutexWaits.set(0L)
        lifecycleMutexWaitNanos.set(0L)
        lifecycleMutexHoldNanos.set(0L)
    }

    private fun add(counter: AtomicLong, delta: Long) {
        if (!DebugCapture.isEnabled()) return
        counter.addAndGet(delta)
    }

    data class Snapshot(
        val repairPipelineInvocations: Long,
        val repairOperations: Long,
        val repairSkips: Long,
        val dpkgRecoveryInvocations: Long,
        val dpkgRecoveryDeferred: Long,
        val dpkgRecoveryNotNeeded: Long,
        val firstBootInvocations: Long,
        val firstBootCommands: Long,
        val firstBootSkips: Long,
        val overlayTicks: Long,
        val overlayTickNanos: Long,
        val overlayFilesWritten: Long,
        val bridgeScans: Long,
        val bridgeScanNanos: Long,
        val screenRevisions: Long,
        val screenUpdates: Long,
        val ptyEventsDropped: Long,
        val lifecycleMutexWaits: Long,
        val lifecycleMutexWaitNanos: Long,
        val lifecycleMutexHoldNanos: Long
    ) {
        fun summary(): String = buildString(256) {
            append("repairPipeline=").append(repairPipelineInvocations)
            append(" repairOps=").append(repairOperations)
            append(" repairSkips=").append(repairSkips)
            append(" dpkgRecovery=").append(dpkgRecoveryInvocations)
            append(" dpkgDeferred=").append(dpkgRecoveryDeferred)
            append(" dpkgNotNeeded=").append(dpkgRecoveryNotNeeded)
            append(" firstBoot=").append(firstBootInvocations)
            append(" firstBootCommands=").append(firstBootCommands)
            append(" firstBootSkips=").append(firstBootSkips)
            append(" overlayTicks=").append(overlayTicks)
            append(" overlayTickUs=").append(overlayTickNanos / 1_000L)
            append(" overlayFiles=").append(overlayFilesWritten)
            append(" bridgeScans=").append(bridgeScans)
            append(" bridgeScanUs=").append(bridgeScanNanos / 1_000L)
            append(" screenRevisions=").append(screenRevisions)
            append(" screenUpdates=").append(screenUpdates)
            append(" ptyEventsDropped=").append(ptyEventsDropped)
            append(" mutexWaits=").append(lifecycleMutexWaits)
            append(" mutexWaitUs=").append(lifecycleMutexWaitNanos / 1_000L)
            append(" mutexHoldUs=").append(lifecycleMutexHoldNanos / 1_000L)
        }
    }
}
