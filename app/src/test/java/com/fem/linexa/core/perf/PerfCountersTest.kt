package com.fem.linexa.core.perf

import com.fem.linexa.core.logging.DebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase M0-A — the fixed counter set.
 *
 * The contract under test is deliberately small: every counter moves only when
 * capture is enabled, the set is fixed (no caller-supplied key can grow it),
 * timing totals never go negative, and `resetForTesting` really zeroes it.
 */
class PerfCountersTest {

    private var clockNanos = 5_000_000_000L

    @Before
    fun setUp() {
        PerfCounters.resetForTesting()
        DebugCapture.resetForTesting()
        PerfTrace.installClockForTesting { clockNanos }
    }

    @After
    fun tearDown() {
        PerfCounters.resetForTesting()
        DebugCapture.resetForTesting()
        PerfTrace.resetClockForTesting()
    }

    @Test
    fun disabledCountersNeverMove() {
        // DebugCapture defaults to false; be explicit so test order cannot
        // leave a previous test's flag on.
        DebugCapture.setEnabled(false)

        PerfCounters.repairPipelineInvoked()
        PerfCounters.repairOperation()
        PerfCounters.repairSkipped()
        PerfCounters.dpkgRecoveryInvoked()
        PerfCounters.dpkgRecoveryDeferred()
        PerfCounters.dpkgRecoveryNotNeeded()
        PerfCounters.firstBootInvoked()
        PerfCounters.firstBootCommand()
        PerfCounters.firstBootSkipped()
        PerfCounters.overlayTick(1_000L, 3)
        PerfCounters.bridgeScan(2_000L)
        PerfCounters.overlayFilesWritten(5)
        assertEquals(-1L, PerfCounters.mutexWaitBegin())
        assertEquals(-1L, PerfCounters.mutexHoldBegin())
        PerfCounters.mutexWaitEnd(-1L)
        PerfCounters.mutexHoldEnd(-1L)

        val snapshot = PerfCounters.snapshot()
        assertEquals(0L, snapshot.repairPipelineInvocations)
        assertEquals(0L, snapshot.repairOperations)
        assertEquals(0L, snapshot.repairSkips)
        assertEquals(0L, snapshot.dpkgRecoveryInvocations)
        assertEquals(0L, snapshot.dpkgRecoveryDeferred)
        assertEquals(0L, snapshot.dpkgRecoveryNotNeeded)
        assertEquals(0L, snapshot.firstBootInvocations)
        assertEquals(0L, snapshot.firstBootCommands)
        assertEquals(0L, snapshot.firstBootSkips)
        assertEquals(0L, snapshot.overlayTicks)
        assertEquals(0L, snapshot.overlayTickNanos)
        assertEquals(0L, snapshot.overlayFilesWritten)
        assertEquals(0L, snapshot.bridgeScans)
        assertEquals(0L, snapshot.bridgeScanNanos)
        assertEquals(0L, snapshot.screenRevisions)
        assertEquals(0L, snapshot.screenUpdates)
        assertEquals(0L, snapshot.lifecycleMutexWaits)
        assertEquals(0L, snapshot.lifecycleMutexWaitNanos)
        assertEquals(0L, snapshot.lifecycleMutexHoldNanos)
    }

    @Test
    fun enabledCountersAccumulate() {
        DebugCapture.setEnabled(true)

        PerfCounters.repairPipelineInvoked()
        PerfCounters.repairOperation()
        PerfCounters.repairOperation()
        PerfCounters.repairSkipped()
        PerfCounters.dpkgRecoveryInvoked()
        PerfCounters.dpkgRecoveryDeferred()
        PerfCounters.dpkgRecoveryNotNeeded()
        PerfCounters.firstBootInvoked()
        PerfCounters.firstBootCommand()
        PerfCounters.firstBootCommand()
        PerfCounters.firstBootSkipped()
        PerfCounters.overlayTick(1_000L, 3)
        PerfCounters.overlayTick(2_000L, 4)
        PerfCounters.bridgeScan(500L)
        PerfCounters.overlayFilesWritten(2)
        PerfCounters.screenRevision()
        PerfCounters.screenRevision()
        PerfCounters.screenUpdate()

        val snapshot = PerfCounters.snapshot()
        assertEquals(1L, snapshot.repairPipelineInvocations)
        assertEquals(2L, snapshot.repairOperations)
        assertEquals(1L, snapshot.repairSkips)
        assertEquals(1L, snapshot.dpkgRecoveryInvocations)
        assertEquals(1L, snapshot.dpkgRecoveryDeferred)
        assertEquals(1L, snapshot.dpkgRecoveryNotNeeded)
        assertEquals(1L, snapshot.firstBootInvocations)
        assertEquals(2L, snapshot.firstBootCommands)
        assertEquals(1L, snapshot.firstBootSkips)
        assertEquals(2L, snapshot.overlayTicks)
        assertEquals(3_000L, snapshot.overlayTickNanos)
        assertEquals(9L, snapshot.overlayFilesWritten)
        assertEquals(1L, snapshot.bridgeScans)
        assertEquals(500L, snapshot.bridgeScanNanos)
        assertEquals(2L, snapshot.screenRevisions)
        assertEquals(1L, snapshot.screenUpdates)
    }

    @Test
    fun mutexTimingUsesTheInstalledMonotonicClock() {
        DebugCapture.setEnabled(true)

        val waitStart = PerfCounters.mutexWaitBegin()
        clockNanos += 2_500L
        PerfCounters.mutexWaitEnd(waitStart)

        val holdStart = PerfCounters.mutexHoldBegin()
        clockNanos += 1_200L
        PerfCounters.mutexHoldEnd(holdStart)

        val snapshot = PerfCounters.snapshot()
        assertEquals(1L, snapshot.lifecycleMutexWaits)
        assertEquals(2_500L, snapshot.lifecycleMutexWaitNanos)
        assertEquals(1_200L, snapshot.lifecycleMutexHoldNanos)
    }

    @Test
    fun mutexTimingNeverRecordsNegativeDurations() {
        DebugCapture.setEnabled(true)

        val start = PerfCounters.mutexWaitBegin()
        clockNanos -= 10_000L
        PerfCounters.mutexWaitEnd(start)

        assertEquals(0L, PerfCounters.snapshot().lifecycleMutexWaitNanos)
    }

    @Test
    fun summaryNamesEveryCounter() {
        DebugCapture.setEnabled(true)
        val summary = PerfCounters.snapshot().summary()

        listOf(
            "repairPipeline=",
            "repairOps=",
            "repairSkips=",
            "dpkgRecovery=",
            "dpkgDeferred=",
            "dpkgNotNeeded=",
            "firstBoot=",
            "firstBootCommands=",
            "firstBootSkips=",
            "overlayTicks=",
            "overlayTickUs=",
            "overlayFiles=",
            "bridgeScans=",
            "bridgeScanUs=",
            "screenRevisions=",
            "screenUpdates=",
            "mutexWaits=",
            "mutexWaitUs=",
            "mutexHoldUs="
        ).forEach { key -> assertTrue("summary missing $key: $summary", summary.contains(key)) }
    }

    @Test
    fun resetForTestingZeroesEverything() {
        DebugCapture.setEnabled(true)
        PerfCounters.repairOperation()
        PerfCounters.overlayTick(10L, 1)
        PerfCounters.bridgeScan(10L)

        PerfCounters.resetForTesting()

        val snapshot = PerfCounters.snapshot()
        assertEquals(0L, snapshot.repairOperations)
        assertEquals(0L, snapshot.overlayTicks)
        assertEquals(0L, snapshot.bridgeScans)
    }
}
