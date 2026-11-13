package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.DebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 7 — the runtime trace's capture gate, and the buffer behaviour the
 * gate depends on.
 *
 * There was no `RuntimeTraceTest` before this phase, which is why the class could
 * quietly become the app's largest unconditional allocator: ~50 call sites append
 * to it, nothing asserted when it appends, and nothing asserted that it ever stops.
 *
 * The asymmetry between the write side and the read side is the point and is
 * asserted in both directions: [RuntimeTrace.record] and [RuntimeTrace.newRun] are
 * gated, while [RuntimeTrace.render] is not — a user who enables debug, reproduces
 * a failure and opens the panel must see what was captured, so a gated read would
 * return "" for data that exists.
 */
class RuntimeTraceTest {

    private val trace = RuntimeTrace()

    @After
    fun reset() {
        DebugCapture.resetForTesting()
    }

    // ── The gate ──────────────────────────────────────────────────────────

    @Test
    fun recordsNothingWhileCaptureIsOff() {
        DebugCapture.setEnabled(false)
        trace.newRun("ws", "startup")
        repeat(5) { trace.record("ws", "hop $it") }
        assertEquals("", trace.render("ws"))
    }

    @Test
    fun doesNotBumpTheRevisionWhileCaptureIsOff() {
        DebugCapture.setEnabled(false)
        val before = trace.revision.value
        repeat(10) { trace.record("ws", "hop $it") }
        assertEquals(
            "a bump with capture off would re-render a hidden panel once per traced hop — the defect Phase 7 removes",
            before,
            trace.revision.value
        )
    }

    @Test
    fun recordsOnceCaptureIsEnabled() {
        DebugCapture.setEnabled(true)
        trace.record("ws", "hello")
        val rendered = trace.render("ws")
        assertTrue(rendered.contains("hello"))
        assertNotEquals(0L, trace.revision.value)
    }

    @Test
    fun enablingCaptureMidSessionStartsRecordingFromThatPoint() {
        // The realistic sequence: a user hits a problem, turns debug on, retries.
        // Nothing before the flip is recoverable — which is honest, and is why the
        // panel must not imply it has a full history.
        DebugCapture.setEnabled(false)
        trace.record("ws", "before")
        DebugCapture.setEnabled(true)
        trace.record("ws", "after")
        val rendered = trace.render("ws")
        assertFalseContains(rendered, "before")
        assertTrue(rendered.contains("after"))
    }

    @Test
    fun disablingCaptureStopsFurtherAppends() {
        DebugCapture.setEnabled(true)
        trace.record("ws", "first")
        DebugCapture.setEnabled(false)
        trace.record("ws", "second")
        val rendered = trace.render("ws")
        assertTrue(rendered.contains("first"))
        assertFalseContains(rendered, "second")
    }

    // ── clear() ───────────────────────────────────────────────────────────

    @Test
    fun clearDropsEveryWorkspacesBuffer() {
        DebugCapture.setEnabled(true)
        trace.record("a", "one")
        trace.record("b", "two")
        trace.clear()
        assertEquals("", trace.render("a"))
        assertEquals("", trace.render("b"))
    }

    @Test
    fun clearBumpsTheRevisionSoAnOpenPanelConvergesOnEmpty() {
        DebugCapture.setEnabled(true)
        trace.record("ws", "one")
        val before = trace.revision.value
        trace.clear()
        assertNotEquals(before, trace.revision.value)
    }

    @Test
    fun clearIsANoOpWhenNothingWasBuffered() {
        // Guards a pointless recomposition on every toggle-off for the common case
        // of a user who never had capture on.
        DebugCapture.setEnabled(false)
        val before = trace.revision.value
        trace.clear()
        assertEquals(before, trace.revision.value)
    }

    @Test
    fun clearIsNotGatedOnTheFlag() {
        // It runs *after* DebugCapture.setEnabled(false) (see DebugCaptureGate), so
        // a gated clear would never execute and the buffers would be retained for
        // the life of the process — the exact leak this phase fixes.
        DebugCapture.setEnabled(true)
        trace.record("ws", "one")
        DebugCapture.setEnabled(false)
        trace.clear()
        assertEquals("", trace.render("ws"))
    }

    // ── Pre-existing behaviour, previously unasserted ──────────────────────

    @Test
    fun theBufferIsBoundedAndDropsTheOldestLine() {
        DebugCapture.setEnabled(true)
        repeat(450) { trace.record("ws", "line$it") }
        val rendered = trace.render("ws")
        assertFalseContains(rendered, "line0\n")
        assertTrue("the newest line must survive", rendered.contains("line449"))
        // 400 lines + a blank line + the footer.
        assertEquals(402, rendered.lines().size)
    }

    @Test
    fun renderIsEmptyForAnUnknownWorkspace() {
        DebugCapture.setEnabled(true)
        trace.record("ws", "one")
        assertEquals("", trace.render("other"))
    }

    @Test
    fun renderAppendsTheFooterOnlyWhenThereIsContent() {
        DebugCapture.setEnabled(true)
        assertEquals("", trace.render("ws"))
        trace.record("ws", "one")
        assertTrue(trace.render("ws").trimEnd().endsWith("—"))
    }

    @Test
    fun newRunAppendsAHeaderRatherThanResettingTheBuffer() {
        // Documents existing behaviour deliberately: `newRun` reads like a reset and
        // is not one, so a future change that relies on it clearing would be wrong.
        // Dropping the previous run is `clear()`'s job.
        DebugCapture.setEnabled(true)
        trace.record("ws", "from the first run")
        trace.newRun("ws", "second run")
        val rendered = trace.render("ws")
        assertTrue(rendered.contains("from the first run"))
        assertTrue(rendered.contains("=== second run ==="))
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue("expected not to contain \"$needle\"", !haystack.contains(needle))
    }
}
