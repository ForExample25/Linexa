package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.MetricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device CPU must have one authority, one formula and one snapshot. These tests
 * exercise the `/proc/stat` primary path, the cpuidle fallback used on Android
 * builds that deny procfs, the 600 ms coalescing floor, source switching and
 * hotplug/offline cores — all without reading the real device.
 */
class DeviceCpuMonitorTest {

    private class FakeReader(
        var procStat: String?,
        var cpuidle: Map<Int, Long>?,
        var online: List<Int>? = listOf(0, 1),
        var nowUs: Long = 0L
    ) : DeviceCpuCounterReader {
        var procReads = 0
        var idleReads = 0

        override fun readProcStat(): String? {
            procReads++
            return procStat
        }

        override fun readOnlineCores(): List<Int>? = online

        override fun readCpuidleTimesMicros(): Map<Int, Long>? {
            idleReads++
            return cpuidle
        }

        override fun nowMonotonicMicros(): Long = nowUs
    }

    private fun procStat(user: Long, system: Long, idle: Long): String = listOf(
        "cpu $user 0 $system $idle 0 0 0 0 0 0",
        "cpu0 ${user / 2} 0 ${system / 2} ${idle / 2} 0 0 0 0 0 0",
        "cpu1 ${user / 2} 0 ${system / 2} ${idle / 2} 0 0 0 0 0 0"
    ).joinToString("\n")

    @Test
    fun `proc stat is primary and first sample is pending`() {
        val reader = FakeReader(procStat(100, 100, 800), cpuidle = mapOf(0 to 0L, 1 to 0L))
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)

        val first = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.PROC_STAT, first.source)
        assertNull(first.overallPercent)
        assertEquals(MetricAvailability.PENDING, first.availability)
        assertEquals(setOf(0, 1), first.perCorePercent.keys)
        assertNull(first.perCorePercent.getValue(0))

        reader.nowUs = 1_000_000L
        reader.procStat = procStat(200, 200, 1_600)
        val second = monitor.snapshot()
        assertEquals(20f, second.overallPercent!!, 0.001f)
        assertEquals(20f, second.perCorePercent.getValue(0)!!, 0.001f)
        assertEquals(20f, second.perCorePercent.getValue(1)!!, 0.001f)
        assertEquals(MetricAvailability.AVAILABLE, second.availability)
        assertTrue(second.procStat != null)
    }

    @Test
    fun `calls inside the floor share the exact same snapshot object`() {
        val reader = FakeReader(procStat(100, 100, 800), cpuidle = null)
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)

        monitor.snapshot()
        assertEquals(1, reader.procReads)

        reader.nowUs = 300_000L
        val cached = monitor.snapshot()
        assertEquals(1, reader.procReads)

        reader.nowUs = 600_000L
        val refreshed = monitor.snapshot()
        assertEquals(2, reader.procReads)
        assertSame(refreshed, monitor.snapshot())
        assertTrue(cached !== refreshed)
    }

    @Test
    fun `proc stat denial falls back to cpuidle with the same capacity convention`() {
        val reader = FakeReader(
            procStat = null,
            cpuidle = mapOf(0 to 1_000_000L, 1 to 1_000_000L)
        )
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)

        val first = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.CPUIDLE, first.source)
        assertNull(first.overallPercent)
        assertEquals(MetricAvailability.PENDING, first.availability)

        reader.nowUs = 1_000_000L
        reader.cpuidle = mapOf(0 to 1_500_000L, 1 to 1_000_000L)
        val second = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.CPUIDLE, second.source)
        assertEquals(50f, second.perCorePercent.getValue(0)!!, 0.001f)
        assertEquals(100f, second.perCorePercent.getValue(1)!!, 0.001f)
        assertEquals(75f, second.overallPercent!!, 0.001f)
        assertNull(second.procStat)
    }

    @Test
    fun `an offline core cannot shift another cores cpuidle percentage`() {
        val reader = FakeReader(
            procStat = null,
            cpuidle = mapOf(0 to 1_000_000L, 1 to 1_000_000L)
        )
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)
        monitor.snapshot()

        reader.nowUs = 1_000_000L
        // cpu1 is offline in this sample: it is filtered out before the delta.
        reader.cpuidle = mapOf(0 to 1_250_000L)
        val second = monitor.snapshot()
        assertEquals(setOf(0), second.perCorePercent.keys)
        // cpu0 accumulated 250 ms idle out of a 1 s interval: 75% busy.
        assertEquals(75f, second.perCorePercent.getValue(0)!!, 0.001f)
        assertEquals(75f, second.overallPercent!!, 0.001f)
    }

    @Test
    fun `switching sources resets the baseline instead of diffing two clocks`() {
        val reader = FakeReader(procStat(100, 100, 800), cpuidle = mapOf(0 to 0L, 1 to 0L))
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)
        monitor.snapshot()

        reader.nowUs = 1_000_000L
        reader.procStat = procStat(200, 200, 1_600)
        assertEquals(20f, monitor.snapshot().overallPercent!!, 0.001f)

        // /proc/stat becomes denied; cpuidle has no baseline from this clock.
        reader.nowUs = 2_000_000L
        reader.procStat = null
        reader.cpuidle = mapOf(0 to 100_000L, 1 to 100_000L)
        val idleFirst = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.CPUIDLE, idleFirst.source)
        assertNull(idleFirst.overallPercent)

        reader.nowUs = 3_000_000L
        reader.cpuidle = mapOf(0 to 600_000L, 1 to 600_000L)
        assertEquals(50f, monitor.snapshot().overallPercent!!, 0.001f)

        // /proc/stat returns; again, no proc baseline may be inherited.
        reader.nowUs = 4_000_000L
        reader.procStat = procStat(300, 300, 2_400)
        val procFirst = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.PROC_STAT, procFirst.source)
        assertNull(procFirst.overallPercent)
    }

    @Test
    fun `a cpuidle counter reset is unknown not a clamped zero`() {
        val reader = FakeReader(procStat = null, cpuidle = mapOf(0 to 1_000_000L))
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)
        monitor.snapshot()

        reader.nowUs = 1_000_000L
        reader.cpuidle = mapOf(0 to 100_000L)
        val reset = monitor.snapshot()
        assertNull(reset.overallPercent)
        assertNull(reset.perCorePercent.getValue(0))
        assertEquals(MetricAvailability.PENDING, reset.availability)
    }

    @Test
    fun `neither source is explicitly unavailable`() {
        val reader = FakeReader(procStat = null, cpuidle = null)
        val monitor = DeviceCpuMonitor(CpuTopologyProvider(), reader)

        val snapshot = monitor.snapshot()
        assertEquals(DeviceCpuMonitor.Source.NONE, snapshot.source)
        assertEquals(MetricAvailability.UNAVAILABLE, snapshot.availability)
        assertNull(snapshot.overallPercent)
        assertTrue(snapshot.perCorePercent.isEmpty())
    }
}
