package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.core.logging.DebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.165 — JVM tests for the diagnostics surface: [VncRenderStatsRegistry] and
 * [VncRenderStatsReport].
 *
 * These are worth testing for the same reason [VncRenderStatsTest] is: the whole
 * point of the surface is that a human reads a number off it and draws a conclusion
 * about §16.1/§16.2, and a readout that mislabels a counter, reports a rate over the
 * wrong window, or shows a stale session's numbers as live would produce a *confident
 * wrong* conclusion with nothing on screen to contradict it. The composable itself is
 * untestable here (no Compose harness, §1.124(a)); everything it displays is in these
 * two pure types precisely so that it can be.
 */
class VncRenderStatsRegistryTest {

    private var now = 0L
    private fun stats() = VncRenderStats { now }

    @Before
    fun enableCapture() {
        DebugCapture.setEnabled(true)
        VncRenderStatsRegistry.resetForTesting()
    }

    @After
    fun restore() {
        // Both are process-wide, so leaking either would contaminate every test that
        // runs after this class in the same JVM.
        DebugCapture.resetForTesting()
        VncRenderStatsRegistry.resetForTesting()
    }

    // ── the registry's lifecycle ──────────────────────────────────────────

    @Test
    fun thereIsNothingToReadBeforeAnySessionHasRun() {
        // Null, not a zeroed snapshot: "no desktop has run" and "a desktop ran and
        // recorded nothing" are different facts and only the second is a finding.
        assertNull(VncRenderStatsRegistry.read())
    }

    @Test
    fun anAttachedSessionReadsLive() {
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(2)

        val reading = VncRenderStatsRegistry.read()
        assertNotNull(reading)
        assertTrue("an attached session must report as live", reading!!.live)
        assertEquals(1L, reading.snapshot.updates)
    }

    @Test
    fun aLiveReadingTracksTheCountersItWasAttachedTo() {
        // The registry holds a reference, not a copy — a reader pressing Refresh during
        // a session must see the session's progress, which is the entire reason the
        // panel has a Refresh button.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)
        assertEquals(1L, VncRenderStatsRegistry.read()!!.snapshot.updates)

        now += 10_000_000L
        stats.recordServerUpdate(1)
        assertEquals(2L, VncRenderStatsRegistry.read()!!.snapshot.updates)
    }

    @Test
    fun retiringFreezesTheFinalNumbersAndStopsReportingLive() {
        // Leaving the desktop screen disposes the client (§1.140), which is exactly
        // when the user goes to read the result. Without retention there would be
        // nothing there.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(3)
        now += 20_000_000L
        stats.recordServerUpdate(3)

        VncRenderStatsRegistry.retire()

        val reading = VncRenderStatsRegistry.read()
        assertNotNull("the last session's numbers must survive the disconnect", reading)
        assertFalse("a retired session must not claim to be live", reading!!.live)
        assertEquals(2L, reading.snapshot.updates)
    }

    @Test
    fun aRetiredWindowDoesNotKeepGrowingAfterTheSessionEnded() {
        // The reason retire() snapshots instead of storing the stats object: elapsed
        // time is computed when the snapshot is taken, so a live read minutes later
        // would report a minutes-long window over a seconds-long session and every
        // derived rate would be wrong in the flattering direction.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)
        now += 1_000_000_000L // 1 s of session
        stats.recordServerUpdate(1)

        VncRenderStatsRegistry.retire()
        val atRetire = VncRenderStatsRegistry.read()!!.snapshot.elapsedMillis

        now += 300_000_000_000L // five minutes pass with the panel closed
        val laterRead = VncRenderStatsRegistry.read()!!.snapshot.elapsedMillis

        assertEquals(atRetire, laterRead, 0.0001)
        assertEquals(1000.0, laterRead, 1.0)
    }

    @Test
    fun retiringWithoutASessionLeavesTheExistingReadingAlone() {
        // `disconnect` runs on paths where the connection never succeeded; that must
        // not overwrite a real reading with an empty one.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(5)
        VncRenderStatsRegistry.retire()

        VncRenderStatsRegistry.retire() // second call, nothing attached

        val reading = VncRenderStatsRegistry.read()
        assertNotNull(reading)
        assertEquals(1L, reading!!.snapshot.updates)
    }

    @Test
    fun attachingANewSessionDiscardsThePreviousOnesFrozenNumbers() {
        // Showing the last session's totals while a new one is recording would be the
        // worst possible failure of this panel: plausible numbers, wrong session.
        val first = stats()
        VncRenderStatsRegistry.attach(first)
        repeat(4) {
            now += 1_000_000L
            first.recordServerUpdate(1)
        }
        VncRenderStatsRegistry.retire()
        assertEquals(4L, VncRenderStatsRegistry.read()!!.snapshot.updates)

        val second = stats()
        VncRenderStatsRegistry.attach(second)

        val reading = VncRenderStatsRegistry.read()!!
        assertTrue(reading.live)
        assertEquals(0L, reading.snapshot.updates)
    }

    // ── reset ─────────────────────────────────────────────────────────────

    @Test
    fun resetClearsTheLiveCountersAndReportsThatItDidSomething() {
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(2)
        stats.recordUpload(50L, 100L)
        stats.recordAcknowledge(applied = true)

        assertTrue(VncRenderStatsRegistry.reset())

        val s = VncRenderStatsRegistry.read()!!.snapshot
        assertEquals(0L, s.updates)
        assertEquals(0L, s.uploads)
        assertEquals(0L, s.acknowledgements)
    }

    @Test
    fun resetRestartsTheMeasurementWindow() {
        // This is what makes per-scenario measurement possible at all: the counters are
        // cumulative, so the window must be able to start at the scenario.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        now += 60_000_000_000L // a minute of unrelated session

        VncRenderStatsRegistry.reset()
        now += 2_000_000_000L // a two-second scenario

        assertEquals(2000.0, VncRenderStatsRegistry.read()!!.snapshot.elapsedMillis, 1.0)
    }

    @Test
    fun resetWithoutALiveSessionSaysSoAndKeepsTheRetainedNumbers() {
        // The button must not appear to have worked when there was nothing to reset,
        // and it must not throw away the only numbers the user has.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(7)
        VncRenderStatsRegistry.retire()

        assertFalse("no live session — reset must report that", VncRenderStatsRegistry.reset())
        assertEquals(
            "the retired reading is the user's data; a reset must not discard it",
            1L,
            VncRenderStatsRegistry.read()!!.snapshot.updates
        )
    }

    @Test
    fun resetIsPurelyDiagnosticByConstruction() {
        // Stated as a test because the requirement is explicit: reset must not
        // reconnect, clear the framebuffer, or touch dirty regions. VncRenderStats
        // holds no reference to any of those, so the strongest available JVM assertion
        // is that resetting is observable *only* in the counters — which is what the
        // rest of this class checks — plus this: the registry's reset path calls
        // nothing but VncRenderStats.reset.
        val source = moduleSource("VncRenderStatsRegistry.kt")
        val resetBody = source.substringAfter("fun reset(): Boolean").substringBefore("fun resetForTesting")
        assertTrue("reset must delegate to the counters: $resetBody", resetBody.contains("current.reset()"))
        for (forbidden in listOf("connect", "disconnect", "dirty", "publish", "pixels", "acknowledge")) {
            assertFalse(
                "reset must not touch $forbidden: $resetBody",
                resetBody.contains(forbidden)
            )
        }
    }

    // ── the report ────────────────────────────────────────────────────────

    @Test
    fun theReportLeadsWithTheWindowTheNumbersCover() {
        // Every counter below it is a total over this window. The previous
        // verification report's "cumulative counters make scenario analysis difficult"
        // is exactly the absence of this row.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        now += 5_000_000_000L
        stats.recordServerUpdate(1)

        val rows = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!)
        assertEquals("Window", rows.first().label)
        assertTrue("the window must be in seconds: ${rows.first().value}", rows.first().value.contains("5.0"))
    }

    @Test
    fun theReportDistinguishesALiveSessionFromAFinishedOne() {
        // "uploadedFraction 0.98" invites opposite conclusions depending on this, so
        // the reading must never be shown without it.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)

        val live = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!).first().hint
        assertTrue("a live session must say so: $live", live!!.contains("live"))

        VncRenderStatsRegistry.retire()
        val ended = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!).first().hint
        assertTrue("a finished session must say so: $ended", ended!!.contains("ended"))
    }

    @Test
    fun theReportCoversEveryCounterTheVerificationPassAsksFor() {
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)
        val labels = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!).map { it.label }

        // The metric list from the device-verification request, in report order.
        for (required in listOf(
            "Window",
            "Server updates",
            "Rectangles / update",
            "Interval mean / max",
            "Uploads",
            "Uploaded fraction",
            "Full-frame uploads",
            "Acknowledgements",
            "Released / no-op"
        )) {
            assertTrue("the panel must show '$required', got $labels", labels.contains(required))
        }
    }

    @Test
    fun theIntervalRowStatesHowManySamplesItAveraged() {
        // A single update renders as 0.0/0.0 ms, which is an absence of data and looks
        // like an extremely fast session.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)

        val row = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!)
            .first { it.label == "Interval mean / max" }
        assertTrue("expected a sample count, got ${row.hint}", row.hint!!.contains("0 interval"))
    }

    @Test
    fun theAcknowledgementRowStatesTheCurrentSemanticsOfANoOp() {
        // §1.154 changed what a no-op means. Read under §1.147's semantics the same
        // number looks like a fault, which is the misreading the verification brief
        // explicitly warned against.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordAcknowledge(applied = true)
        stats.recordAcknowledge(applied = false)

        val row = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!)
            .first { it.label == "Released / no-op" }
        assertEquals("1 / 1", row.value)
        assertTrue("the hint must state §1.154's meaning: ${row.hint}", row.hint!!.contains("already retired"))
    }

    @Test
    fun theUploadedFractionRowExplainsWhichDirectionIsBad() {
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordUpload(pixels = 100L, total = 1000L)

        val row = VncRenderStatsReport.rows(VncRenderStatsRegistry.read()!!)
            .first { it.label == "Uploaded fraction" }
        assertEquals("0.10", row.value)
        assertTrue("${row.hint}", row.hint!!.contains("full-frame"))
    }

    @Test
    fun theCopyTextCarriesEveryRow() {
        // The realistic exit route for these numbers is the clipboard; a block that
        // dropped a row would silently lose a metric from a report.
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(2)
        val reading = VncRenderStatsRegistry.read()!!

        val text = VncRenderStatsReport.asText(reading)
        for (row in VncRenderStatsReport.rows(reading)) {
            assertTrue("copied text must contain '${row.label}': $text", text.contains(row.label))
        }
        assertTrue("and must state the session state: $text", text.contains("state: live"))
    }

    // ── the gate ──────────────────────────────────────────────────────────

    @Test
    fun withCaptureOffThePanelReadsZeroesRatherThanStaleNumbers() {
        // The counters are gated, so with debug off nothing is recorded. The registry
        // is deliberately *not* gated — enabling debug mid-session must start producing
        // numbers rather than leaving the panel permanently empty — so the observable
        // result is a live reading whose counters are all zero.
        DebugCapture.setEnabled(false)
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(4)
        stats.recordUpload(10L, 100L)
        stats.recordAcknowledge(applied = true)

        val reading = VncRenderStatsRegistry.read()!!
        assertTrue(reading.live)
        assertEquals(0L, reading.snapshot.updates)
        assertEquals(0L, reading.snapshot.uploads)
        assertEquals(0L, reading.snapshot.acknowledgements)
    }

    @Test
    fun enablingCaptureMidSessionStartsRecordingIntoTheAttachedCounters() {
        // The reason attach/retire are not gated. A user turns debug on *because* the
        // desktop is misbehaving, which means the session already exists.
        DebugCapture.setEnabled(false)
        val stats = stats()
        VncRenderStatsRegistry.attach(stats)
        stats.recordServerUpdate(1)
        assertEquals(0L, VncRenderStatsRegistry.read()!!.snapshot.updates)

        DebugCapture.setEnabled(true)
        now += 10_000_000L
        stats.recordServerUpdate(1)
        assertEquals(1L, VncRenderStatsRegistry.read()!!.snapshot.updates)
    }

    private fun moduleSource(fileName: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        val marker = "src/main/java/com/fem/linexa/MainActivity.kt"
        while (dir != null) {
            if (java.io.File(dir, marker).exists()) {
                return java.io.File(dir, "src/main/java/com/fem/linexa/ui/screens/desktop/$fileName").readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("module root not found")
    }
}
