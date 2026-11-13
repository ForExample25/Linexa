package com.fem.linexa.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 4 — the one set of font-size rules the pinch gesture and the Global
 * Settings stepper both obey.
 *
 * The point of these tests is the *shared* clamp: before Phase 4 the bounds were
 * `private const`s inside `RealTerminalView`, so a second control surface would have
 * had to duplicate them. A duplicated clamp drifts, and the symptom is a terminal
 * that a stepper can push somewhere a pinch cannot (or vice versa).
 */
class TerminalFontPreferenceTest {

    @Test
    fun theDefaultsMatchWhatWasAlreadyPersisted() {
        // Deliberately a lift, not a redesign — an existing install's stored size
        // keeps working and pinch behaviour is unchanged. Changing any of these is a
        // behaviour change for every current user, so they are pinned.
        assertEquals(18, TerminalFontPreference.DEFAULT_DP)
        assertEquals(8, TerminalFontPreference.MIN_DP)
        assertEquals(96, TerminalFontPreference.MAX_DP)
        assertEquals(2, TerminalFontPreference.STEP_DP)
        assertEquals("linexa_terminal", TerminalFontPreference.PREFS_FILE)
        assertEquals("font_size", TerminalFontPreference.PREFS_KEY)
    }

    @Test
    fun theDefaultIsInsideItsOwnBounds() {
        assertEquals(
            TerminalFontPreference.DEFAULT_DP,
            TerminalFontPreference.sanitize(TerminalFontPreference.DEFAULT_DP)
        )
    }

    @Test
    fun sanitizeIsTotal() {
        // Accepts anything: a hand-edited prefs value, a value written by a build
        // with different bounds, or a garbage int. The failure mode of a bad font
        // size is a terminal that cannot be read, so nothing here may throw.
        assertEquals(TerminalFontPreference.MIN_DP, TerminalFontPreference.sanitize(0))
        assertEquals(TerminalFontPreference.MIN_DP, TerminalFontPreference.sanitize(-40))
        assertEquals(TerminalFontPreference.MIN_DP, TerminalFontPreference.sanitize(Int.MIN_VALUE))
        assertEquals(TerminalFontPreference.MAX_DP, TerminalFontPreference.sanitize(1_000))
        assertEquals(TerminalFontPreference.MAX_DP, TerminalFontPreference.sanitize(Int.MAX_VALUE))
        assertEquals(20, TerminalFontPreference.sanitize(20))
    }

    @Test
    fun steppingMovesByExactlyOneStep() {
        assertEquals(20, TerminalFontPreference.increase(18))
        assertEquals(16, TerminalFontPreference.decrease(18))
    }

    @Test
    fun steppingClampsAtBothEndsInsteadOfOvershooting() {
        assertEquals(
            TerminalFontPreference.MAX_DP,
            TerminalFontPreference.increase(TerminalFontPreference.MAX_DP)
        )
        assertEquals(
            TerminalFontPreference.MIN_DP,
            TerminalFontPreference.decrease(TerminalFontPreference.MIN_DP)
        )
        // One step short of a bound must land exactly on it, not past it.
        assertEquals(
            TerminalFontPreference.MAX_DP,
            TerminalFontPreference.increase(TerminalFontPreference.MAX_DP - 1)
        )
        assertEquals(
            TerminalFontPreference.MIN_DP,
            TerminalFontPreference.decrease(TerminalFontPreference.MIN_DP + 1)
        )
    }

    @Test
    fun steppingSanitizesItsInputFirst() {
        // A stored out-of-range value must be pulled into range by a step rather
        // than stepping relative to the bad value.
        assertEquals(TerminalFontPreference.MIN_DP + 2, TerminalFontPreference.increase(-5))
        assertEquals(TerminalFontPreference.MAX_DP - 2, TerminalFontPreference.decrease(500))
    }

    @Test
    fun theStepperButtonsDisableExactlyWhenAStepWouldDoNothing() {
        // This is the property the UI depends on: `canIncrease` must agree with
        // `increase` returning the same value, or the stepper offers a button that
        // does nothing (or disables one that would have worked).
        val samples = listOf(
            Int.MIN_VALUE, -1, 0,
            TerminalFontPreference.MIN_DP - 1,
            TerminalFontPreference.MIN_DP,
            TerminalFontPreference.MIN_DP + 1,
            18,
            TerminalFontPreference.MAX_DP - 1,
            TerminalFontPreference.MAX_DP,
            TerminalFontPreference.MAX_DP + 1,
            Int.MAX_VALUE
        )
        samples.forEach { size ->
            val sanitized = TerminalFontPreference.sanitize(size)
            assertEquals(
                "canIncrease must agree with increase() for $size",
                TerminalFontPreference.increase(size) != sanitized,
                TerminalFontPreference.canIncrease(size)
            )
            assertEquals(
                "canDecrease must agree with decrease() for $size",
                TerminalFontPreference.decrease(size) != sanitized,
                TerminalFontPreference.canDecrease(size)
            )
        }
    }

    @Test
    fun bothDirectionsAreAvailableAtTheDefault() {
        assertTrue(TerminalFontPreference.canIncrease(TerminalFontPreference.DEFAULT_DP))
        assertTrue(TerminalFontPreference.canDecrease(TerminalFontPreference.DEFAULT_DP))
        assertFalse(TerminalFontPreference.canIncrease(TerminalFontPreference.MAX_DP))
        assertFalse(TerminalFontPreference.canDecrease(TerminalFontPreference.MIN_DP))
    }
}
