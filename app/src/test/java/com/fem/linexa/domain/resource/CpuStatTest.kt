package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuStatTest {

    private fun coreLine(core: Int?, user: Long, nice: Long = 0, system: Long = 0, idle: Long, iowait: Long = 0,
                         irq: Long = 0, softirq: Long = 0, steal: Long = 0, guest: Long = 0, guestNice: Long = 0): String {
        val prefix = if (core == null) "cpu " else "cpu$core "
        return prefix + listOf(user, nice, system, idle, iowait, irq, softirq, steal, guest, guestNice)
            .joinToString(" ")
    }

    @Test
    fun `aggregate and per-core totals use the first eight columns`() {
        val body = listOf(
            coreLine(null, user = 100, nice = 10, system = 20, idle = 300, iowait = 40, irq = 5, softirq = 6, steal = 7, guest = 999, guestNice = 999),
            coreLine(0, user = 60, system = 10, idle = 100, iowait = 10, irq = 2, softirq = 3, steal = 1),
            coreLine(1, user = 40, idle = 200, iowait = 30, irq = 3, softirq = 3, steal = 6)
        ).joinToString("\n")

        val parsed = requireNotNull(CpuUsageCalculator.parseProcStat(body))
        assertEquals(340L, parsed.aggregate.idleTicks)
        assertEquals(488L, parsed.aggregate.totalTicks) // excludes 999 + 999 guest time
        assertEquals(2, parsed.perCore.size)
        assertEquals(186L, parsed.perCore.getValue(0).totalTicks)
        assertEquals(110L, parsed.perCore.getValue(0).idleTicks)
        assertEquals(282L, parsed.perCore.getValue(1).totalTicks)
        assertEquals(230L, parsed.perCore.getValue(1).idleTicks)
    }

    @Test
    fun `a malformed per-core row voids the grid but not the aggregate`() {
        val body = """
            cpu  10 0 10 80 0 0 0 0 0 0
            cpu0 10 0 10 80 0 0 0 0 0 0
            cpu1 10 0 nope 80 0 0 0 0 0 0
        """.trimIndent()
        val parsed = requireNotNull(CpuUsageCalculator.parseProcStat(body))
        assertEquals(100L, parsed.aggregate.totalTicks)
        assertTrue(parsed.perCore.isEmpty())
    }

    @Test
    fun `missing aggregate is reconstructed from a complete core grid`() {
        val body = """
            cpu0 10 0 10 80 0 0 0 0 0 0
            cpu1 20 0 20 60 0 0 0 0 0 0
        """.trimIndent()
        val parsed = requireNotNull(CpuUsageCalculator.parseProcStat(body))
        assertEquals(200L, parsed.aggregate.totalTicks)
        assertEquals(140L, parsed.aggregate.idleTicks)
    }

    @Test
    fun `unreadable stat is null`() {
        assertNull(CpuUsageCalculator.parseProcStat(null))
        assertNull(CpuUsageCalculator.parseProcStat("nonsense"))
    }

    @Test
    fun `first device sample is pending with null per-core values`() {
        val current = requireNotNull(CpuUsageCalculator.parseProcStat("cpu 10 0 10 80 0 0 0 0\ncpu0 10 0 10 80 0 0 0 0"))
        val delta = CpuUsageCalculator.deviceDelta(null, current)
        assertNull(delta.overallPercent)
        assertEquals(MetricAvailability.PENDING, delta.availability)
        assertEquals(setOf(0), delta.perCorePercent.keys)
        assertNull(delta.perCorePercent.getValue(0))
    }

    @Test
    fun `device delta is busy over total and preserves core identity`() {
        val previous = requireNotNull(
            CpuUsageCalculator.parseProcStat(
                "cpu 10 0 10 80 0 0 0 0\ncpu0 10 0 10 80 0 0 0 0\ncpu1 0 0 0 0 0 0 0 0"
            )
        )
        val current = requireNotNull(
            CpuUsageCalculator.parseProcStat(
                "cpu 30 0 30 140 0 0 0 0\ncpu0 30 0 30 140 0 0 0 0\ncpu1 0 0 0 0 0 0 0 0"
            )
        )
        val delta = CpuUsageCalculator.deviceDelta(previous, current)
        assertEquals(40f, delta.overallPercent!!, 0.001f)
        assertEquals(40f, delta.perCorePercent.getValue(0)!!, 0.001f)
        assertNull(delta.perCorePercent.getValue(1))
        assertEquals(MetricAvailability.AVAILABLE, delta.availability)
    }

    @Test
    fun `a backwards or zero counter delta is pending rather than a fake value`() {
        val current = requireNotNull(CpuUsageCalculator.parseProcStat("cpu 10 0 10 80 0 0 0 0\ncpu0 10 0 10 80 0 0 0 0"))
        val older = requireNotNull(CpuUsageCalculator.parseProcStat("cpu 20 0 20 160 0 0 0 0\ncpu0 20 0 20 160 0 0 0 0"))
        val delta = CpuUsageCalculator.deviceDelta(older, current)
        assertNull(delta.overallPercent)
        assertEquals(MetricAvailability.PENDING, delta.availability)
        assertNull(delta.perCorePercent.getValue(0))
    }

    @Test
    fun `process percent is normalised by online device capacity`() {
        // 100 own ticks at 100 Hz = 1 CPU-second over 2 s on an 8-core device.
        assertEquals(
            6.25f,
            CpuUsageCalculator.processPercent(
                previousTicks = 1_000,
                currentTicks = 1_100,
                elapsedMs = 2_000,
                clockTicksPerSecond = 100,
                capacityCores = 8
            )!!,
            0.0001f
        )
    }

    @Test
    fun `process percent rejects backwards counters and bad intervals`() {
        assertNull(CpuUsageCalculator.processPercent(100, 90, 2_000, 100, 8))
        assertNull(CpuUsageCalculator.processPercent(100, 110, 0, 100, 8))
        assertNull(CpuUsageCalculator.processPercent(100, 110, 2_000, 0, 8))
        assertNull(CpuUsageCalculator.processPercent(100, 110, 2_000, 100, 0))
        assertNull(CpuUsageCalculator.processPercent(null, 110, 2_000, 100, 8))
    }
}
