package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.MetricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared process-CPU table exists because independent per-screen baselines
 * used to shorten each other's delta windows until values quantised to zero.
 * These tests pin the sampling floor, identity check and reset policy.
 */
class ProcessCpuTrackerTest {

    private val tracker = ProcessCpuTracker()

    private fun observe(
        ticks: Long,
        atMs: Long,
        pid: Int = 4242,
        startTimeTicks: Long = 100L,
        cores: Int = 8
    ): ProcessCpuTracker.Reading = tracker.observe(
        pid = pid,
        startTimeTicks = startTimeTicks,
        ownTicks = ticks,
        atMs = atMs,
        clockTicksPerSecond = 100L,
        capacityCores = cores
    )

    @Test
    fun `first observation is pending and the second uses the real interval`() {
        val first = observe(ticks = 1_000, atMs = 0)
        assertNull(first.percent)
        assertEquals(MetricAvailability.PENDING, first.availability)

        // 100 ticks at 100 Hz = 1 CPU-second over 1 s on 8 cores.
        val second = observe(ticks = 1_100, atMs = 1_000)
        assertEquals(12.5f, second.percent!!, 0.0001f)
        assertEquals(MetricAvailability.AVAILABLE, second.availability)
    }

    @Test
    fun `a second consumer inside the floor gets the same answer without stealing the baseline`() {
        observe(ticks = 1_000, atMs = 0)
        val firstConsumer = observe(ticks = 1_100, atMs = 1_000)
        assertEquals(12.5f, firstConsumer.percent!!, 0.0001f)

        // 250 ms later another screen asks: elapsed is below the 1 s floor, so
        // this must return the previous reading and must NOT move the baseline.
        val secondConsumer = observe(ticks = 1_125, atMs = 1_250)
        assertEquals(firstConsumer, secondConsumer)

        // The next real interval is measured from the 1 000 ms baseline.
        val next = observe(ticks = 1_200, atMs = 2_000)
        assertEquals(12.5f, next.percent!!, 0.0001f)
    }

    @Test
    fun `a consumer that arrives first after the floor defines the shared window`() {
        observe(ticks = 1_000, atMs = 0)
        // A slow screen samples at 1.2 s ...
        val slow = observe(ticks = 1_120, atMs = 1_200)
        assertEquals(12.5f, slow.percent!!, 0.0001f)
        // ... and a fast one at 1.4 s must see the same value, not a 200 ms slice.
        val fast = observe(ticks = 1_140, atMs = 1_400)
        assertEquals(slow, fast)
    }

    @Test
    fun `a recycled pid starts pending even when the tick counter looks continuous`() {
        observe(ticks = 1_000, atMs = 0, pid = 77, startTimeTicks = 1L)
        val recycled = observe(ticks = 1_100, atMs = 1_000, pid = 77, startTimeTicks = 2L)
        assertNull(recycled.percent)
        assertEquals(MetricAvailability.PENDING, recycled.availability)
    }

    @Test
    fun `a backwards counter is a reset and the next interval is measured from the new baseline`() {
        observe(ticks = 1_000, atMs = 0)
        val reset = observe(ticks = 900, atMs = 1_000)
        assertNull(reset.percent)
        assertEquals(MetricAvailability.PENDING, reset.availability)

        val recovered = observe(ticks = 1_000, atMs = 2_000)
        assertEquals(12.5f, recovered.percent!!, 0.0001f)
    }

    @Test
    fun `forget drops the baseline so a reused pid cannot inherit it`() {
        observe(ticks = 1_000, atMs = 0)
        tracker.forget(4242)
        assertEquals(0, tracker.trackedCount())

        val afterForget = observe(ticks = 1_100, atMs = 1_000)
        assertNull(afterForget.percent)
        assertEquals(MetricAvailability.PENDING, afterForget.availability)
    }

    @Test
    fun `a genuinely idle process reports zero rather than pending`() {
        observe(ticks = 1_000, atMs = 0)
        val idle = observe(ticks = 1_000, atMs = 1_000)
        assertEquals(0f, idle.percent!!, 0.0001f)
        assertEquals(MetricAvailability.AVAILABLE, idle.availability)
    }

    @Test
    fun `a saturated single core stays a share of total device capacity`() {
        observe(ticks = 0, atMs = 0, cores = 8)
        val oneCore = observe(ticks = 100, atMs = 1_000, cores = 8)
        assertEquals(12.5f, oneCore.percent!!, 0.0001f)

        val fresh = ProcessCpuTracker()
        fresh.observe(1, 1, 0, 0, 100, 1)
        val singleCoreDevice = fresh.observe(1, 1, 100, 1_000, 100, 1)
        assertEquals(100f, singleCoreDevice.percent!!, 0.0001f)
    }
}
