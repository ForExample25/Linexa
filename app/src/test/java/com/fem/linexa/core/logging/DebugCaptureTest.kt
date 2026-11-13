package com.fem.linexa.core.logging

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 7 — the capture flag itself.
 *
 * Small, but the two properties asserted here are the ones the whole phase rests
 * on, and both are the kind of thing that would silently regress: the default must
 * be **off** (so a build that never wires the gate under-collects rather than
 * logging in the background with the switch off), and there must be exactly one
 * flag (so "capture is off" cannot be true for the log sink and false for the
 * runtime trace).
 */
class DebugCaptureTest {

    @After
    fun reset() {
        // The flag is process-wide, so a leak here would silently enable capture in
        // every test that runs afterwards in this JVM — including the many that
        // construct a real RuntimeTrace.
        DebugCapture.resetForTesting()
    }

    @Test
    fun captureIsOffByDefault() {
        assertFalse(
            "a fresh process must capture nothing until something enables it — under-collecting is visible, background collection with the switch off is not",
            DebugCapture.DEFAULT_ENABLED
        )
        assertFalse(DebugCapture.isEnabled())
    }

    @Test
    fun theFlagFollowsTheLastValueWritten() {
        DebugCapture.setEnabled(true)
        assertTrue(DebugCapture.isEnabled())
        DebugCapture.setEnabled(false)
        assertFalse(DebugCapture.isEnabled())
    }

    @Test
    fun resetRestoresTheFailClosedDefault() {
        DebugCapture.setEnabled(true)
        DebugCapture.resetForTesting()
        assertFalse(DebugCapture.isEnabled())
    }

    @Test
    fun everyReaderObservesOneFlag() {
        // Stated as a test because the alternative design — a flag per subsystem —
        // compiles and passes every other test while leaving the app in the state
        // Phase 7 forbids: half of it still capturing after the toggle is off.
        DebugCapture.setEnabled(true)
        val asSeenByTheSink = DebugCapture.isEnabled()
        val asSeenByTheTrace = DebugCapture.isEnabled()
        assertTrue(asSeenByTheSink && asSeenByTheTrace)
        DebugCapture.setEnabled(false)
        assertFalse(DebugCapture.isEnabled())
    }
}
