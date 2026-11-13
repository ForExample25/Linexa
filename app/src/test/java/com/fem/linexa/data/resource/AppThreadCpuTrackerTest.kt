package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.CpuUsageCalculator
import com.fem.linexa.domain.resource.MetricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread-level monitoring is scoped to the app's own `/proc/<pid>/task`. The
 * same delta rules as process rows apply, and an unreadable task directory must
 * be reported as a limitation instead of an empty zero list.
 */
class AppThreadCpuTrackerTest {

    private class FakeThreadReader(
        var tids: List<Int>?,
        val stats: MutableMap<Int, String>
    ) : ThreadStatReader {
        override fun listThreadIds(pid: Int): List<Int>? = tids

        override fun readThreadStat(pid: Int, tid: Int): String? = stats[tid]
    }

    /** A syntactically valid `/proc/<pid>/stat` line with controllable own ticks. */
    private fun threadStat(
        tid: Int,
        name: String,
        ownTicks: Long,
        startTimeTicks: Long = 1L
    ): String {
        val fields = MutableList(37) { "0" }
        fields[0] = "S"
        fields[1] = "1"
        fields[11] = (ownTicks / 2).toString()
        fields[12] = (ownTicks - ownTicks / 2).toString()
        fields[19] = startTimeTicks.toString()
        fields[36] = "0"
        return "$tid ($name) " + fields.joinToString(" ")
    }

    private fun newTracker(reader: FakeThreadReader): AppThreadCpuTracker =
        AppThreadCpuTracker(reader, CpuTopologyProvider()).also { it.clockMs = { clock } }

    private var clock = 0L

    @Test
    fun `threads are listed by name and the first sample is pending`() {
        clock = 0L
        val reader = FakeThreadReader(
            tids = listOf(101, 102),
            stats = mutableMapOf(
                101 to threadStat(101, "RenderThread", ownTicks = 1_000),
                102 to threadStat(102, "pool-2-thread", ownTicks = 500)
            )
        )
        val tracker = newTracker(reader)

        val first = tracker.snapshot(pid = 4000)
        assertTrue(first.readable)
        assertEquals(2, first.threadCount)
        assertEquals(listOf("RenderThread", "pool-2-thread"), first.threads.map { it.name })
        first.threads.forEach {
            assertNull(it.cpuPercentOfDevice)
            assertEquals(MetricAvailability.PENDING, it.cpuAvailability)
        }
    }

    @Test
    fun `the second sample applies the shared process formula per thread`() {
        clock = 0L
        val reader = FakeThreadReader(
            tids = listOf(101, 102),
            stats = mutableMapOf(
                101 to threadStat(101, "RenderThread", ownTicks = 1_000),
                102 to threadStat(102, "pool-2-thread", ownTicks = 500)
            )
        )
        val tracker = newTracker(reader)
        tracker.snapshot(pid = 4000)

        clock = 1_000L
        reader.stats[101] = threadStat(101, "RenderThread", ownTicks = 1_100)
        reader.stats[102] = threadStat(102, "pool-2-thread", ownTicks = 550)
        val second = tracker.snapshot(pid = 4000)

        val topology = CpuTopologyProvider().topology()
        val expectedBusy = CpuUsageCalculator.processPercent(
            previousTicks = 1_000,
            currentTicks = 1_100,
            elapsedMs = 1_000,
            clockTicksPerSecond = CpuTopologyProvider().clockTicksPerSecond,
            capacityCores = topology.capacityCores
        )
        assertEquals(expectedBusy!!, second.threads.first { it.tid == 101 }.cpuPercentOfDevice!!, 0.0001f)
        assertTrue(second.threads.first { it.tid == 101 }.cpuPercentOfDevice!! > 0f)
        assertEquals(MetricAvailability.AVAILABLE, second.threads.first { it.tid == 101 }.cpuAvailability)
    }

    @Test
    fun `an unreadable task directory is an explicit limitation`() {
        clock = 0L
        val unreadable = AppThreadCpuTracker(
            FakeThreadReader(tids = null, stats = mutableMapOf()),
            CpuTopologyProvider()
        )
        val snapshot = unreadable.snapshot(pid = 4000)
        assertFalse(snapshot.readable)
        assertEquals(0, snapshot.threadCount)
        assertNull(snapshot.busiest)
    }

    @Test
    fun `a pid whose task rows all fail to parse is also unreadable`() {
        val reader = FakeThreadReader(
            tids = listOf(101),
            stats = mutableMapOf(101 to "not a stat line")
        )
        val tracker = AppThreadCpuTracker(reader, CpuTopologyProvider())
        val snapshot = tracker.snapshot(pid = 4000)
        assertFalse(snapshot.readable)
        assertEquals(0, snapshot.threadCount)
    }

    @Test
    fun `zero pid short circuits without touching proc`() {
        val reader = FakeThreadReader(tids = listOf(1), stats = mutableMapOf())
        val snapshot = AppThreadCpuTracker(reader, CpuTopologyProvider()).snapshot(pid = 0)
        assertFalse(snapshot.readable)
        assertEquals(0, snapshot.threadCount)
    }
}
