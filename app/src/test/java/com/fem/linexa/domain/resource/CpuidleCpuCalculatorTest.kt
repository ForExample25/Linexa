package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The non-root cpuidle fallback is the device CPU source on Android builds
 * that deny `/proc/stat` to apps. These tests pin its two honesty rules:
 * an unavailable interval is unknown (never zero), and a hotplugged/reset
 * counter cannot be mistaken for idle or busy capacity.
 */
class CpuidleCpuCalculatorTest {

    @Test
    fun `first sample is pending with every current core represented`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = null,
            current = mapOf(0 to 1_000L, 1 to 2_000L),
            elapsedMicros = 1_000_000L
        )

        assertNull(delta.overallPercent)
        assertEquals(MetricAvailability.PENDING, delta.availability)
        assertEquals(setOf(0, 1), delta.perCorePercent.keys)
        assertNull(delta.perCorePercent.getValue(0))
        assertNull(delta.perCorePercent.getValue(1))
    }

    @Test
    fun `busy is elapsed minus idle and aggregates over measured capacity`() {
        // Core 0 was idle the whole second; core 1 was idle exactly half.
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 1_000_000L, 1 to 1_000_000L),
            current = mapOf(0 to 2_000_000L, 1 to 1_500_000L),
            elapsedMicros = 1_000_000L
        )

        assertEquals(0f, delta.perCorePercent.getValue(0)!!, 0.001f)
        assertEquals(50f, delta.perCorePercent.getValue(1)!!, 0.001f)
        assertEquals(25f, delta.overallPercent!!, 0.001f)
        assertEquals(MetricAvailability.AVAILABLE, delta.availability)
    }

    @Test
    fun `sub second intervals keep their real percentage`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 0L),
            current = mapOf(0 to 100_000L),
            elapsedMicros = 200_000L
        )

        assertEquals(50f, delta.overallPercent!!, 0.001f)
    }

    @Test
    fun `a core that just came online has no interval and does not join the denominator`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 0L),
            current = mapOf(0 to 500_000L, 1 to 900_000L),
            elapsedMicros = 1_000_000L
        )

        assertNull(delta.perCorePercent.getValue(1))
        assertEquals(50f, delta.perCorePercent.getValue(0)!!, 0.001f)
        // Only core 0 was measured in both samples.
        assertEquals(50f, delta.overallPercent!!, 0.001f)
    }

    @Test
    fun `an offline core simply leaves the current map without relabeling another core`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 0L, 1 to 0L),
            current = mapOf(0 to 250_000L),
            elapsedMicros = 1_000_000L
        )

        assertEquals(setOf(0), delta.perCorePercent.keys)
        // Core 0 accumulated 250 ms of idle in a 1 s interval, so it was busy
        // for 750 ms. With one measured core that is 75% of device capacity.
        assertEquals(75f, delta.perCorePercent.getValue(0)!!, 0.001f)
        assertEquals(75f, delta.overallPercent!!, 0.001f)
    }

    @Test
    fun `a backwards counter is unknown rather than clamped`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 900_000L),
            current = mapOf(0 to 100_000L),
            elapsedMicros = 1_000_000L
        )

        assertNull(delta.perCorePercent.getValue(0))
        assertNull(delta.overallPercent)
        assertEquals(MetricAvailability.PENDING, delta.availability)
    }

    @Test
    fun `idle time larger than the elapsed interval is unknown`() {
        val delta = CpuidleCpuCalculator.deviceDelta(
            previous = mapOf(0 to 0L),
            current = mapOf(0 to 2_000_000L),
            elapsedMicros = 1_000_000L
        )

        assertNull(delta.overallPercent)
        assertEquals(MetricAvailability.PENDING, delta.availability)
    }

    @Test
    fun `zero or negative elapsed time cannot fabricate a reading`() {
        for (elapsed in listOf(0L, -1L)) {
            val delta = CpuidleCpuCalculator.deviceDelta(
                previous = mapOf(0 to 0L),
                current = mapOf(0 to 500_000L),
                elapsedMicros = elapsed
            )
            assertNull(delta.overallPercent)
            assertEquals(MetricAvailability.PENDING, delta.availability)
        }
    }
}
