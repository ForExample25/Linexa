package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.139 — JVM tests for [VncRelativeCursor], the synthesised absolute coordinate
 * that makes touch pointing **relative**.
 *
 * RFB's `PointerEvent` carries an absolute framebuffer position (RFB 3.8 §7.5.5),
 * so §1.138's client translated the finger's position and sent that — which
 * teleported the remote cursor under the fingertip on the first sample of every
 * gesture. The cursor left wherever the user was working, and landed under the
 * finger that was covering the target. This class is the fix: it holds the
 * cursor's own position and integrates finger *movement* into it.
 *
 * It is a separate Android-free class for the same reason as [VncTouchGesture]
 * (§1.138): pointer feel is otherwise checkable only by hand, one device at a
 * time.
 */
class VncRelativeCursorTest {

    private fun cursor(width: Int = 1920, height: Int = 1080) =
        VncRelativeCursor().apply { resize(width, height) }

    // ── adopting a geometry ──────────────────────────────────────────────

    @Test
    fun `a new cursor starts centred`() {
        // Centre rather than 0,0: it is where an X server puts the pointer at
        // startup, so the client's idea of the cursor starts out agreeing with the
        // server's, and it is reachable from anywhere with one swipe.
        val c = cursor(1920, 1080)
        assertEquals(960, c.frameX)
        assertEquals(540, c.frameY)
    }

    @Test
    fun `resizing to the same geometry does not move the cursor`() {
        // submitFrame calls resize on every frame; re-centring there would drag the
        // cursor back to the middle ~30 times a second.
        val c = cursor(1920, 1080)
        c.moveBy(200f, -100f)
        val x = c.frameX
        val y = c.frameY
        c.resize(1920, 1080)
        assertEquals(x, c.frameX)
        assertEquals(y, c.frameY)
    }

    @Test
    fun `a real geometry change re-centres`() {
        // The framebuffer the old position referred to no longer exists, and a
        // coordinate outside the new one would be rejected or clamped by the server.
        val c = cursor(1920, 1080)
        c.moveBy(800f, 400f)
        c.resize(1280, 720)
        assertEquals(640, c.frameX)
        assertEquals(360, c.frameY)
    }

    @Test
    fun `movement before any geometry is known is ignored`() {
        // A touch can arrive before the first frame; there is no coordinate space to
        // move in yet, and 0,0 is a real position that would click the corner.
        val c = VncRelativeCursor()
        c.moveBy(50f, 50f)
        assertEquals(0, c.frameX)
        assertEquals(0, c.frameY)
    }

    // ── relative movement ────────────────────────────────────────────────

    @Test
    fun `movement is relative to where the cursor already is`() {
        val c = cursor(1920, 1080)
        c.moveBy(100f, 60f)
        assertEquals(1060, c.frameX)
        assertEquals(600, c.frameY)
    }

    @Test
    fun `movement accumulates across calls`() {
        // Each touch sample is a small delta; the cursor is their sum, not the last
        // one.
        val c = cursor(1920, 1080)
        repeat(10) { c.moveBy(10f, 5f) }
        assertEquals(1060, c.frameX)
        assertEquals(590, c.frameY)
    }

    @Test
    fun `negative deltas move the cursor back`() {
        val c = cursor(1920, 1080)
        c.moveBy(-200f, -100f)
        assertEquals(760, c.frameX)
        assertEquals(440, c.frameY)
    }

    @Test
    fun `a zero delta changes nothing`() {
        val c = cursor(1920, 1080)
        c.moveBy(0f, 0f)
        assertEquals(960, c.frameX)
        assertEquals(540, c.frameY)
    }

    @Test
    fun `the position is never the finger's position`() {
        // The property that distinguishes §1.139 from §1.138: a gesture starting at
        // the top-left corner does not put the cursor there.
        val c = cursor(1920, 1080)
        c.moveBy(4f, 4f) // as if the finger were at 4,4 and had barely moved
        assertNotEquals(4, c.frameX)
        assertNotEquals(4, c.frameY)
    }

    // ── sub-pixel accumulation ───────────────────────────────────────────

    @Test
    fun `sub-pixel movement accumulates instead of being lost`() {
        // Scaled deltas are fractional on any device whose display is smaller than
        // the framebuffer. Truncating each one would make slow movement stall
        // completely — the pointer would refuse to creep.
        val c = cursor(1920, 1080)
        repeat(10) { c.moveBy(0.4f, 0f) }
        assertEquals(964, c.frameX)
    }

    @Test
    fun `the wire coordinate is a truncated integer`() {
        val c = cursor(1920, 1080)
        c.moveBy(0.9f, 0.9f)
        assertEquals(960, c.frameX)
        assertEquals(540, c.frameY)
        assertEquals(960.9f, c.x, 0.001f)
        assertEquals(540.9f, c.y, 0.001f)
    }

    // ── clamping ─────────────────────────────────────────────────────────

    @Test
    fun `the cursor cannot be pushed past the right or bottom edge`() {
        val c = cursor(1920, 1080)
        c.moveBy(5000f, 5000f)
        assertEquals(1919, c.frameX)
        assertEquals(1079, c.frameY)
    }

    @Test
    fun `the cursor cannot be pushed past the left or top edge`() {
        val c = cursor(1920, 1080)
        c.moveBy(-5000f, -5000f)
        assertEquals(0, c.frameX)
        assertEquals(0, c.frameY)
    }

    @Test
    fun `overshoot is not banked so the cursor comes straight back`() {
        // If the excess were kept, a user who swiped hard into an edge would have to
        // swipe the same distance back before the cursor moved at all — which reads
        // as the pointer being stuck.
        val c = cursor(1920, 1080)
        c.moveBy(5000f, 0f)
        c.moveBy(-100f, 0f)
        assertEquals(1819, c.frameX)
    }

    @Test
    fun `clamping is per axis so an edge does not freeze the other direction`() {
        val c = cursor(1920, 1080)
        c.moveBy(5000f, 100f)
        assertEquals(1919, c.frameX)
        assertEquals(640, c.frameY)
    }

    @Test
    fun `every reachable coordinate is inside the framebuffer`() {
        // frameWidth is exclusive: sending width itself is out of bounds.
        val c = cursor(800, 600)
        listOf(
            5000f to 5000f,
            -5000f to 5000f,
            5000f to -5000f,
            -5000f to -5000f
        ).forEach { (dx, dy) ->
            c.moveBy(dx, dy)
            assertTrue("x=${c.frameX}", c.frameX in 0..799)
            assertTrue("y=${c.frameY}", c.frameY in 0..599)
        }
    }

    // ── degenerate geometry ──────────────────────────────────────────────

    @Test
    fun `a zero-sized framebuffer accepts no movement`() {
        val c = VncRelativeCursor()
        c.resize(0, 0)
        c.moveBy(100f, 100f)
        assertEquals(0, c.frameX)
        assertEquals(0, c.frameY)
    }

    @Test
    fun `a one-pixel framebuffer stays at its only coordinate`() {
        val c = cursor(1, 1)
        c.moveBy(50f, 50f)
        assertEquals(0, c.frameX)
        assertEquals(0, c.frameY)
    }
}
