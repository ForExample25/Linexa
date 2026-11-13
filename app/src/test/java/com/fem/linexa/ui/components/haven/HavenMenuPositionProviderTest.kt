package com.fem.linexa.ui.components.haven

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-arithmetic contract of the context-menu placement: below the anchor
 * when it fits, flipped above when it does not, always inside the window.
 */
class HavenMenuPositionProviderTest {

    private val window = IntSize(1000, 2000)
    private val menu = IntSize(400, 500)
    private val gap = 10
    private val margin = 20

    private fun place(anchor: IntRect, alignEnd: Boolean = true): IntOffset =
        HavenMenuPositionProvider(gap, margin, alignEnd)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, menu)

    @Test
    fun opensBelowTheAnchorWhenItFits() {
        val p = place(IntRect(500, 300, 800, 500))
        assertEquals(510, p.y)
        assertEquals(800 - 400, p.x) // end-aligned to the anchor
    }

    @Test
    fun flipsAboveWhenBelowWouldOverflow() {
        val p = place(IntRect(500, 1700, 800, 1900))
        assertEquals(1700 - gap - menu.height, p.y)
    }

    @Test
    fun clampsHorizontallyInsideTheWindow() {
        val left = place(IntRect(0, 300, 100, 500), alignEnd = true)
        assertEquals(margin, left.x)
        val right = place(IntRect(900, 300, 1000, 500), alignEnd = false)
        assertEquals(window.width - menu.width - margin, right.x)
    }

    @Test
    fun neverLeavesTheWindowVertically() {
        val p = place(IntRect(0, 50, 100, 150).copy(top = 0, bottom = 1990))
        assertTrue(p.y >= margin)
        assertTrue(p.y + menu.height <= window.height)
    }
}
