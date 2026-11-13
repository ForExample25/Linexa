package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Zoom (2026-08) — the pure zoom/pan contract of [VncZoom].
 *
 * Every claim the class KDoc makes about the coordinate system is asserted
 * here as a property, because the draw transform and the input transform must
 * agree by construction or taps land where the desktop is not drawn.
 */
class VncZoomTest {

    // ---- the fit view (userZoom = 1) is exactly the historical contain-scale ----

    @Test
    fun atFitTheOffsetIsTheHistoricalCentring() {
        // 1920x1080 framebuffer in a 2280x1080 view (20:9 phone, landscape):
        // width binds, so the content fills 1920 and centres horizontally
        // ((2280-1920)/2 = 180) while the height matches exactly (offset 0).
        val zoom = VncZoom(viewWidth = 2280, viewHeight = 1080, frameWidth = 1920, frameHeight = 1080)
        assertEquals(1f, zoom.userZoom, 0f)
        assertEquals(1f, zoom.baseScale, 0f)
        assertEquals(180f, zoom.offsetX, 0.01f)
        assertEquals(0f, zoom.offsetY, 0.01f)
        assertFalse(zoom.pannable)
    }

    @Test
    fun aWiderFramebufferThanTheViewLetterboxesExactlyLikeTheContainScale() {
        // Same numbers as the old scale()/offsetX()/offsetY() helpers:
        // baseScale = min(0.5, 0.625) = 0.5 → content 1000x400 in a 1000x500
        // view: no horizontal letterbox, (500-400)/2 = 50 vertical.
        val zoom = VncZoom(viewWidth = 1000, viewHeight = 500, frameWidth = 2000, frameHeight = 800)
        val baseScale = minOf(1000f / 2000f, 500f / 800f)
        assertEquals(baseScale, zoom.baseScale, 0f)
        assertEquals((1000 - 2000 * baseScale) / 2f, zoom.offsetX, 0.01f)
        assertEquals((500 - 800 * baseScale) / 2f, zoom.offsetY, 0.01f)
    }

    // ---- zoom clamping ----

    @Test
    fun zoomIsClampedToTheDocumentedRange() {
        val zoom = VncZoom(1000, 500, 2000, 800)
        assertEquals(VncZoom.MIN_ZOOM, zoom.zoomed(0.1f, 500f, 250f).userZoom, 0f)
        assertEquals(VncZoom.MAX_ZOOM, zoom.zoomed(50f, 500f, 250f).userZoom, 0f)
        // A no-op zoom (already at the clamp) returns the same state.
        assertEquals(zoom.userZoom, zoom.zoomed(zoom.userZoom, 0f, 0f).userZoom, 0f)
    }

    @Test
    fun zoomNeverShrinksBelowTheFitView() {
        val zoom = VncZoom(1000, 500, 2000, 800)
        val min = zoom.zoomed(0.5f, 500f, 250f)
        assertEquals(VncZoom.MIN_ZOOM, min.userZoom, 0f)
        assertFalse(min.pannable)
    }

    // ---- focal-point zoom keeps the content under the focus stationary ----

    @Test
    fun theFramebufferPointUnderTheFocusStaysUnderIt() {
        val viewW = 1000
        val viewH = 500
        val zoom = VncZoom(viewW, viewH, 2000, 800)
        val focusX = 400f
        val focusY = 200f
        val before = zoom.viewToFrame(focusX, focusY)
        val after = zoom.zoomed(2f, focusX, focusY).viewToFrame(focusX, focusY)
        assertEquals("frame X under the focus is invariant", before.first, after.first, 0.5f)
        assertEquals("frame Y under the focus is invariant", before.second, after.second, 0.5f)
    }

    // ---- pan clamping keeps the content covering the view ----

    @Test
    fun panningIsRejectedAtFit() {
        val zoom = VncZoom(1000, 500, 2000, 800)
        val panned = zoom.panned(100f, 100f)
        assertEquals(zoom, panned)
    }

    @Test
    fun panningWhileZoomedNeverExposesAGutter() {
        val zoom = VncZoom(1000, 500, 2000, 800).zoomed(2f, 500f, 250f)
        assertTrue(zoom.pannable)
        // Drag the content far past the right/bottom edges.
        val panned = zoom.panned(10_000f, 10_000f)
        // The content's right/bottom edge must reach the view's right/bottom edge.
        assertTrue(panned.offsetX + panned.contentWidth >= 1000f - 0.01f)
        assertTrue(panned.offsetY + panned.contentHeight >= 500f - 0.01f)
        // And never overshoot into a gutter on the near side either.
        assertTrue(panned.offsetX <= 0f)
        assertTrue(panned.offsetY <= 0f)
        // ...unless the content is smaller than the view, where it centres.
    }

    @Test
    fun zoomedOutContentCentresInsteadOfPanning() {
        // At fit on an axis whose content is narrower than the view, the offset
        // is the centring — a pan request cannot un-centre it. baseScale here is
        // 1.25, so the 1000-wide framebuffer draws 1250 wide in a 2000 view:
        // centred at (2000-1250)/2 = 375.
        val zoom = VncZoom(2000, 500, 1000, 400).zoomed(1f, 0f, 0f)
        val panned = zoom.panned(500f, 0f)
        assertEquals((2000 - 1000 * zoom.effectiveScale) / 2f, panned.offsetX, 0.01f)
        assertEquals(375f, panned.offsetX, 0.01f)
    }

    // ---- coordinate transforms round-trip ----

    @Test
    fun viewToFrameFollowsTheDrawPlacement() {
        val zoom = VncZoom(1000, 500, 2000, 800)
        val (fx, fy) = zoom.viewToFrame(0f, 0f)
        // The view's top-left is the framebuffer point at the drawn origin.
        assertEquals(-zoom.offsetX / zoom.effectiveScale, fx, 0.01f)
        assertEquals(-zoom.offsetY / zoom.effectiveScale, fy, 0.01f)
    }

    @Test
    fun frameDeltaScalesWithTheEffectiveScaleNotTheRawMultiplier() {
        // In a 1000x500 view a 2000x800 framebuffer is contained at 0.5, so a
        // user zoom of 2 lands the EFFECTIVE scale back at 1: a 100 px finger
        // move still moves the cursor 100 frame px. The contract is division
        // by the effective scale — the number the draw actually uses — not by
        // the user multiplier alone.
        val zoom = VncZoom(1000, 500, 2000, 800)
        assertEquals(100f / zoom.effectiveScale, zoom.frameDelta(100f), 0.01f)
        val zoomed = zoom.zoomed(2f, 0f, 0f)
        assertEquals(1f, zoomed.effectiveScale, 0.01f)
        assertEquals(100f, zoomed.frameDelta(100f), 0.01f)
        // Zoom to 4: effective scale 2, deltas halve.
        val zoomedMore = zoomed.zoomed(4f, 0f, 0f)
        assertEquals(2f, zoomedMore.effectiveScale, 0.01f)
        assertEquals(50f, zoomedMore.frameDelta(100f), 0.01f)
    }

    // ---- resize keeps the multiplier, resets the pan ----

    @Test
    fun aGeometryChangeKeepsTheZoomMultiplierAndCentresThePan() {
        val zoom = VncZoom(1000, 500, 2000, 800).zoomed(3f, 500f, 250f).panned(-300f, -100f)
        val resized = zoom.resized(1200, 600, 2400, 900)
        assertEquals(3f, resized.userZoom, 0f)
        assertEquals(1200, resized.viewWidth)
        assertEquals(2400, resized.frameWidth)
        // Pan re-derived: at the new fit with zoom 3 the content overruns, so
        // the clamp recentres it (raw pan 0 → clamped ≤ 0, content covers view).
        assertTrue(resized.offsetX + resized.contentWidth >= 1200f - 0.01f)
    }

    // ---- degenerate geometry ----

    @Test
    fun zeroSizedGeometryIsTheIdentity() {
        val zoom = VncZoom(0, 0, 0, 0)
        assertEquals(1f, zoom.baseScale, 0f)
        assertEquals(1f, zoom.effectiveScale, 0f)
        val (fx, fy) = zoom.viewToFrame(100f, 100f)
        assertEquals(100f, fx, 0.01f)
        assertEquals(100f, fy, 0.01f)
    }

    @Test
    fun resetReturnsToTheFitView() {
        val zoom = VncZoom(1000, 500, 2000, 800).zoomed(2.5f, 500f, 250f).panned(-200f, -50f)
        val reset = zoom.reset()
        assertEquals(VncZoom.MIN_ZOOM, reset.userZoom, 0f)
        assertFalse(reset.pannable)
        // And it equals the freshly-constructed fit state.
        assertEquals(VncZoom(1000, 500, 2000, 800), reset)
    }
}
