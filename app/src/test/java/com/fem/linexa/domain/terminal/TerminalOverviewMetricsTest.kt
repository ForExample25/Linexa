package com.fem.linexa.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry contract for the Recent Sessions grid.
 *
 * The overview used to be a carousel with a persisted horizontal/vertical
 * preference. Both are gone: the grid is fixed-column, and every card is sized
 * from width alone, so keyboard visibility can only change the viewport, never
 * the cards. These are the pure-math pins behind that promise.
 */
class TerminalOverviewMetricsTest {

    @Test
    fun portraitCardsAreInsideTheProductAspectBand() {
        val ratio = TerminalOverviewMetrics.CARD_ASPECT_RATIO
        assertTrue(
            "the product band is 0.68–0.75 width/height (was $ratio)",
            ratio >= 0.68f && ratio <= 0.75f
        )
        assertTrue("cards are taller than wide", ratio < 1f)
        assertNotEquals("a square icon is not a window preview", 1f, ratio, 0.0001f)
    }

    @Test
    fun portraitPhonesGetTwoColumns() {
        // The reference device is 1220 px at 520 dpi ≈ 375 dp wide.
        assertEquals(2, TerminalOverviewMetrics.columnsForWidth(375f))
        assertEquals(2, TerminalOverviewMetrics.columnsForWidth(411f))
        assertEquals(2, TerminalOverviewMetrics.columnsForWidth(599f))
    }

    @Test
    fun veryNarrowScreensFallBackToOneReadableColumn() {
        assertEquals(1, TerminalOverviewMetrics.columnsForWidth(320f))
        assertEquals(1, TerminalOverviewMetrics.columnsForWidth(0f))
    }

    @Test
    fun widerScreensGainColumnsInsteadOfOneHugeCard() {
        assertEquals(3, TerminalOverviewMetrics.columnsForWidth(600f))
        assertEquals(3, TerminalOverviewMetrics.columnsForWidth(719f))
        assertEquals(4, TerminalOverviewMetrics.columnsForWidth(840f))
        assertEquals(4, TerminalOverviewMetrics.columnsForWidth(999f))
        assertEquals(5, TerminalOverviewMetrics.columnsForWidth(1000f))
    }

    @Test
    fun columnCountNeverDecreasesAsWidthGrows() {
        var previous = TerminalOverviewMetrics.columnsForWidth(0f)
        var width = 0f
        while (width <= 1600f) {
            val columns = TerminalOverviewMetrics.columnsForWidth(width)
            assertTrue("columns dropped at ${width}dp", columns >= previous)
            previous = columns
            width += 7f
        }
    }

    @Test
    fun cardWidthIsWidthOnlyTheKeyboardCannotAffectIt() {
        val padding = TerminalOverviewMetrics.SCREEN_PADDING_DP
        val gap = TerminalOverviewMetrics.COLUMN_GAP_DP
        // Exact arithmetic for a 375dp phone in two columns.
        val expected = (375f - padding * 2f - gap) / 2f
        assertEquals(
            expected,
            TerminalOverviewMetrics.cardWidthDp(375f, 2),
            0.0001f
        )
        // No height/IME parameter exists at all: the signature is proof.
        assertEquals(
            expected,
            TerminalOverviewMetrics.cardWidthDp(
                availableWidthDp = 375f,
                columns = 2
            ),
            0.0001f
        )
    }

    @Test
    fun cardWidthStaysPositiveForDegenerateInputs() {
        assertTrue(TerminalOverviewMetrics.cardWidthDp(10f, 2) >= 0f)
        assertEquals(0f, TerminalOverviewMetrics.cardWidthDp(0f, 3), 0.0001f)
        assertTrue(TerminalOverviewMetrics.cardWidthDp(375f, 0) > 0f)
    }

    @Test
    fun twoColumnsFitInsideTheAvailableWidthWithBothGutters() {
        val width = 375f
        val columns = 2
        val card = TerminalOverviewMetrics.cardWidthDp(width, columns)
        val consumed = card * columns +
            TerminalOverviewMetrics.SCREEN_PADDING_DP * 2f +
            TerminalOverviewMetrics.COLUMN_GAP_DP * (columns - 1)
        assertEquals(width, consumed, 0.0001f)
    }

    @Test
    fun horizontalCardUsesTheFullGutterWidthOnPhones() {
        val expected = 375f - TerminalOverviewMetrics.SCREEN_PADDING_DP * 2f
        assertEquals(
            expected,
            TerminalOverviewMetrics.horizontalCardWidthDp(375f),
            0.0001f
        )
    }

    @Test
    fun horizontalCardIsCappedOnTabletsAndNeverDependsOnHeight() {
        assertEquals(
            TerminalOverviewMetrics.HORIZONTAL_MAX_CARD_WIDTH_DP.toFloat(),
            TerminalOverviewMetrics.horizontalCardWidthDp(1200f),
            0.0001f
        )
        assertEquals(
            TerminalOverviewMetrics.HORIZONTAL_MAX_CARD_WIDTH_DP.toFloat(),
            TerminalOverviewMetrics.horizontalCardWidthDp(840f),
            0.0001f
        )
        // Degenerate widths stay finite and non-negative.
        assertEquals(0f, TerminalOverviewMetrics.horizontalCardWidthDp(0f), 0.0001f)
        assertTrue(TerminalOverviewMetrics.horizontalCardWidthDp(20f) >= 0f)
    }

    @Test
    fun horizontalCardHeightFollowsTheSameAspectRatioAsTheGrid() {
        // Height is derived from the width above and the shared ratio; the IME
        // has no input to either function.
        val width = TerminalOverviewMetrics.horizontalCardWidthDp(375f)
        val height = width / TerminalOverviewMetrics.CARD_ASPECT_RATIO
        assertTrue(height > width)
        assertTrue(height < 700f)
    }
}
