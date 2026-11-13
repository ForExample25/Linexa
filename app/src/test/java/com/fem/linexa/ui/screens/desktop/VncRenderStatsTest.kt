package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.core.logging.DebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.153 — JVM tests for [VncRenderStats].
 *
 * Instrumentation is worth testing for one specific reason: a counter that is
 * silently wrong is worse than no counter, because the audit's §16 recommendations
 * are meant to be judged by these numbers. A mean that divides by the wrong count,
 * or a fraction that saturates at 1.0 for the wrong reason, would make a
 * pessimisation look like an improvement — and unlike a rendering defect there is
 * nothing on screen to contradict it.
 *
 * The gate is asserted too. Capture is off in a fresh process (Phase 7's fail-closed
 * default), and "the counters run anyway" is exactly the condition Phase 7 exists to
 * forbid — silent background work with the user's switch off.
 */
class VncRenderStatsTest {

    /** A hand-driven clock: intervals are the thing being measured. */
    private var now = 0L
    private fun stats() = VncRenderStats { now }

    @Before
    fun enableCapture() {
        DebugCapture.setEnabled(true)
    }

    @After
    fun restoreCapture() {
        // The flag is process-wide, so leaking it would silently start capture in
        // every test that runs after this class in the same JVM.
        DebugCapture.resetForTesting()
    }

    // ---- the gate ----

    @Test
    fun nothingIsRecordedWhileCaptureIsOff() {
        DebugCapture.setEnabled(false)
        val stats = stats()
        stats.recordServerUpdate(4)
        now += 16_000_000L
        stats.recordServerUpdate(4)
        stats.recordUpload(100L, 1000L)
        stats.recordAcknowledge(applied = false)

        val s = stats.snapshot()
        assertEquals(0L, s.updates)
        assertEquals(0L, s.uploads)
        assertEquals(0L, s.acknowledgements)
    }

    @Test
    fun captureIsOffByDefaultInAFreshProcess() {
        // Phase 7's fail-closed default, restated here because this class is on the
        // frame path: if it ever ran unconditionally it would be a new instance of the
        // cost the §1.153 audit was investigating.
        assertEquals(false, DebugCapture.DEFAULT_ENABLED)
    }

    // ---- inter-update interval ----

    @Test
    fun theFirstUpdateContributesNoInterval() {
        // There is nothing to measure from. Counting it as a zero interval would
        // halve the reported mean of a two-update session.
        val stats = stats()
        stats.recordServerUpdate(1)
        val s = stats.snapshot()
        assertEquals(1L, s.updates)
        assertEquals(0.0, s.meanIntervalMillis, 0.0)
    }

    @Test
    fun theMeanIntervalDividesByTheGapsAndNotTheUpdates() {
        // Three updates are two gaps. Dividing by three would under-report the
        // interval by a third — in the direction that makes a change look good.
        val stats = stats()
        stats.recordServerUpdate(1)
        now += 10_000_000L // 10 ms
        stats.recordServerUpdate(1)
        now += 30_000_000L // 30 ms
        stats.recordServerUpdate(1)

        val s = stats.snapshot()
        assertEquals(3L, s.updates)
        assertEquals(20.0, s.meanIntervalMillis, 0.0001)
        assertEquals(30.0, s.maxIntervalMillis, 0.0001)
    }

    @Test
    fun theWorstIntervalIsKeptSeparatelyFromTheMean() {
        // A single long stall is the symptom a user reports as "it froze", and a mean
        // over a busy session hides it completely.
        val stats = stats()
        stats.recordServerUpdate(1)
        repeat(9) {
            now += 1_000_000L
            stats.recordServerUpdate(1)
        }
        now += 500_000_000L
        stats.recordServerUpdate(1)

        val s = stats.snapshot()
        assertEquals(500.0, s.maxIntervalMillis, 0.0001)
        assertTrue("the mean must not be dominated by the stall", s.meanIntervalMillis < 60.0)
    }

    @Test
    fun rectanglesPerUpdateIsAveragedOverUpdates() {
        val stats = stats()
        stats.recordServerUpdate(1)
        now += 1_000_000L
        stats.recordServerUpdate(9)
        assertEquals(5.0, stats.snapshot().meanRectanglesPerUpdate, 0.0001)
    }

    // ---- uploaded area ----

    @Test
    fun theUploadedFractionIsWeightedByFramebufferSize() {
        // Averaging per-upload ratios would weight a tiny frame the same as a full
        // one. The quantity of interest is "what share of the pixels that could have
        // been copied were copied", so both halves accumulate.
        val stats = stats()
        stats.recordUpload(pixels = 100L, total = 1000L)
        stats.recordUpload(pixels = 900L, total = 1000L)
        assertEquals(0.5, stats.snapshot().meanUploadedFraction, 0.0001)
    }

    @Test
    fun aFullFrameUploadIsCountedSeparately() {
        // The mean can sit at a comfortable 0.3 while every third upload is a full
        // framebuffer copy, which is the §7.2 degradation the audit predicts.
        val stats = stats()
        stats.recordUpload(pixels = 1000L, total = 1000L)
        stats.recordUpload(pixels = 10L, total = 1000L)
        val s = stats.snapshot()
        assertEquals(2L, s.uploads)
        assertEquals(1L, s.fullFrameUploads)
    }

    @Test
    fun anUploadLargerThanTheFrameStillCountsAsFullAndNotAsMoreThanOne() {
        // The copied region is the published one union'd with the back buffer's debt
        // (§1.150), so it is bounded by the framebuffer — but a fraction above 1.0
        // would be nonsense to read, and clamping is cheaper than arguing about it.
        val stats = stats()
        stats.recordUpload(pixels = 2000L, total = 1000L)
        assertEquals(1L, stats.snapshot().fullFrameUploads)
    }

    @Test
    fun anUploadAgainstAnUnknownFramebufferIsIgnored() {
        // A zero total would divide by zero and poison every later mean.
        val stats = stats()
        stats.recordUpload(pixels = 10L, total = 0L)
        val s = stats.snapshot()
        assertEquals(0L, s.uploads)
        assertEquals(0.0, s.meanUploadedFraction, 0.0)
    }

    // ---- stale acknowledgements ----

    @Test
    fun theStaleRateIsAFractionOfAllAcknowledgements() {
        val stats = stats()
        stats.recordAcknowledge(applied = true)
        stats.recordAcknowledge(applied = false)
        stats.recordAcknowledge(applied = false)
        stats.recordAcknowledge(applied = true)

        val s = stats.snapshot()
        assertEquals(4L, s.acknowledgements)
        assertEquals(2L, s.staleAcknowledgements)
        assertEquals(0.5, s.staleAcknowledgeRate, 0.0001)
    }

    @Test
    fun theStaleRateIsZeroBeforeAnyAcknowledgement() {
        assertEquals(0.0, stats().snapshot().staleAcknowledgeRate, 0.0)
    }

    // ---- the pair the audit's mechanism needs ----

    @Test
    fun aStaleRateAndAFullFrameRateAreReadableTogether() {
        // The §7.2 mechanism is not "acks are stale" and not "uploads are full" — it
        // is both at once, and either alone is benign. A snapshot that could not show
        // them consistently would leave the mechanism unfalsifiable, which is the
        // state §1.153 exists to end.
        val stats = stats()
        repeat(10) {
            stats.recordAcknowledge(applied = false)
            stats.recordUpload(pixels = 1000L, total = 1000L)
        }
        val s = stats.snapshot()
        assertEquals(1.0, s.staleAcknowledgeRate, 0.0001)
        assertEquals(1.0, s.meanUploadedFraction, 0.0001)
        assertEquals(s.uploads, s.fullFrameUploads)
    }

    // ---- reset ----

    @Test
    fun resetClearsEveryCounterIncludingTheIntervalAnchor() {
        // A retained anchor would report the whole gap between two sessions as the
        // first interval of the second one.
        val stats = stats()
        stats.recordServerUpdate(3)
        now += 10_000_000L
        stats.recordServerUpdate(3)
        stats.recordUpload(10L, 100L)
        stats.recordAcknowledge(applied = false)

        stats.reset()
        now += 5_000_000_000L
        stats.recordServerUpdate(1)

        val s = stats.snapshot()
        assertEquals(1L, s.updates)
        assertEquals(0.0, s.meanIntervalMillis, 0.0)
        assertEquals(0.0, s.maxIntervalMillis, 0.0)
        assertEquals(0L, s.uploads)
        assertEquals(0L, s.acknowledgements)
    }
}
