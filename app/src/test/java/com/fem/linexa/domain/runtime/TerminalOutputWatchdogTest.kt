package com.fem.linexa.domain.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 019 — the pure first-output decision.
 *
 * The important property is that this never becomes a kill decision: it only
 * answers whether the UI should explain a still-blank live terminal. The
 * ViewModel tests cover the timer and pty wiring; these cover the boundary.
 */
class TerminalOutputWatchdogTest {

    @Test
    fun warnsOnlyAtOrAfterTheThreshold() {
        val watchdog = TerminalOutputWatchdog(thresholdMs = 8_000L)

        assertFalse(watchdog.shouldWarn(0L))
        assertFalse(watchdog.shouldWarn(7_999L))
        assertTrue(watchdog.shouldWarn(8_000L))
        assertTrue(watchdog.shouldWarn(9_000L))
    }

    @Test
    fun anyOutputPermanentlySuppressesTheWarning() {
        val watchdog = TerminalOutputWatchdog(thresholdMs = 8_000L)

        watchdog.onOutput()

        assertTrue(watchdog.hasOutput)
        assertFalse(watchdog.shouldWarn(0L))
        assertFalse(watchdog.shouldWarn(60_000L))
    }

    @Test
    fun oneOutputIsEnoughForTheSession() {
        val watchdog = TerminalOutputWatchdog()

        watchdog.onOutput()
        watchdog.onOutput()

        assertTrue(watchdog.hasOutput)
        assertFalse(watchdog.shouldWarn(TerminalOutputWatchdog.DEFAULT_THRESHOLD_MS))
    }
}
