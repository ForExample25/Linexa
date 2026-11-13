package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Touch work (2026-08) — the direct (absolute) touch machine's
 * contract. The relative machine's semantics live in [VncTouchGestureTest];
 * this asserts the differences that justify a second machine, plus the
 * two-finger gestures the two modes deliberately share.
 */
class VncDirectGestureTest {

    private val gesture = VncDirectGesture()

    private fun down(x: Float = 100f, y: Float = 100f, t: Long = 0) =
        gesture.onTouch(VncTouch.Down(x, y, t))

    private fun move(x: Float, y: Float, t: Long = 100) =
        gesture.onTouch(VncTouch.Move(x, y, t))

    private fun up(x: Float = 100f, y: Float = 100f, t: Long = 200) =
        gesture.onTouch(VncTouch.Up(x, y, t))

    // ---- tap: click at the finger's own position, on the lift ----

    @Test
    fun aTapEmitsPressAndReleaseAtTheFingerPositionOnTheLift() {
        val downActions = down()
        // Nothing is sent on contact: the lift decides.
        assertTrue(downActions.filterIsInstance<VncPointerAction.PointerAt>().isEmpty())
        assertTrue(downActions.any { it is VncPointerAction.ScheduleHold })

        val upActions = up(x = 150f, y = 120f)
        val masks = upActions.filterIsInstance<VncPointerAction.PointerAt>().map { it.buttonMask }
        assertEquals(listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_NONE), masks)
        val press = upActions.filterIsInstance<VncPointerAction.PointerAt>().first()
        assertEquals(150f, press.x, 0f)
        assertEquals(120f, press.y, 0f)
    }

    // ---- drag: press-and-move, no hold required ----

    @Test
    fun movementPastSlopPressesTheLeftButtonAndDrags() {
        down()
        val dragStart = move(100f + VncPointer.MOVE_SLOP_PX + 1f, 100f)
        val press = dragStart.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_LEFT, press.buttonMask)
        assertTrue(dragStart.any { it is VncPointerAction.CancelHold })

        val dragMove = move(200f, 140f)
        val held = dragMove.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_LEFT, held.buttonMask)
        assertEquals(200f, held.x, 0f)
        assertEquals(140f, held.y, 0f)

        val lift = up(210f, 145f)
        val release = lift.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_NONE, release.buttonMask)
    }

    @Test
    fun jitterBelowSlopSendsNothingAndKeepsTheHoldArmed() {
        down()
        val jitter = move(100f + VncPointer.MOVE_SLOP_PX / 2f, 100f)
        assertTrue(jitter.isEmpty())
        assertTrue(gesture.pendingHold)
    }

    // ---- long-press: the right button, with haptic confirmation ----

    @Test
    fun aStationaryHoldBecomesARightClick() {
        down()
        val elapsed = gesture.onTouch(VncTouch.HoldElapsed(600))
        val masks = elapsed.filterIsInstance<VncPointerAction.PointerAt>().map { it.buttonMask }
        assertEquals(listOf(VncPointer.BUTTON_MASK_RIGHT), masks)
        assertTrue(elapsed.any { it is VncPointerAction.Haptic })

        val lift = up()
        val release = lift.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_NONE, release.buttonMask)
    }

    @Test
    fun movingAfterARightClickRightDrags() {
        down()
        gesture.onTouch(VncTouch.HoldElapsed(600))
        val drag = move(160f, 130f)
        val held = drag.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_RIGHT, held.buttonMask)
    }

    @Test
    fun aHoldThatStartedAsADragDoesNotRightClick() {
        down()
        move(100f + VncPointer.MOVE_SLOP_PX + 1f, 100f)
        val elapsed = gesture.onTouch(VncTouch.HoldElapsed(600))
        assertTrue(elapsed.isEmpty())
    }

    // ---- two fingers: the shared semantics ----

    @Test
    fun aTwoFingerTapIsARightClickAtTheGestureOrigin() {
        down()
        gesture.onTouch(VncTouch.SecondaryDown(140f, 110f, 50, pointerCount = 2))
        val resolve = gesture.onTouch(VncTouch.SecondaryUp(140f, 110f, 200, pointerCount = 1))
        val masks = resolve.filterIsInstance<VncPointerAction.PointerAt>().map { it.buttonMask }
        assertEquals(listOf(VncPointer.BUTTON_MASK_RIGHT, VncPointer.BUTTON_MASK_NONE), masks)
        val click = resolve.filterIsInstance<VncPointerAction.PointerAt>().first()
        // The click means at the gesture origin (the machine's own rule); the
        // view maps the coordinates through the zoom.
        assertEquals(140f, click.x, 0f)
        assertEquals(110f, click.y, 0f)
        assertTrue(resolve.any { it is VncPointerAction.Haptic })
    }

    @Test
    fun aLongTwoFingerContactIsNotATap() {
        down()
        gesture.onTouch(VncTouch.SecondaryDown(140f, 110f, 0, pointerCount = 2))
        val resolve = gesture.onTouch(VncTouch.SecondaryUp(140f, 110f, VncPointer.MULTI_TAP_MS + 1, pointerCount = 1))
        assertTrue(resolve.isEmpty())
    }

    @Test
    fun twoFingerTravelBeyondScrollSlopBecomesAWheel() {
        down()
        gesture.onTouch(VncTouch.SecondaryDown(140f, 110f, 0, pointerCount = 2))
        val scroll = move(140f, 110f + VncPointer.SCROLL_SLOP_PX + 5f)
        val wheel = scroll.filterIsInstance<VncPointerAction.Wheel>().single()
        assertEquals(VncPointer.SCROLL_SLOP_PX + 5f, wheel.deltaY, 0.01f)
        // And the lift must not click.
        assertTrue(up().isEmpty())
    }

    // ---- teardown ----

    @Test
    fun aHeldButtonIsNeverLeftDownOnCancel() {
        down()
        move(100f + VncPointer.MOVE_SLOP_PX + 1f, 100f)
        val cancel = gesture.onTouch(VncTouch.Cancel)
        val release = cancel.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_NONE, release.buttonMask)
        // After the reset the next gesture starts clean: a tap is a tap.
        val fresh = down()
        assertTrue(fresh.any { it is VncPointerAction.ScheduleHold })
        val freshUp = up()
        val masks = freshUp.filterIsInstance<VncPointerAction.PointerAt>().map { it.buttonMask }
        assertEquals(listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_NONE), masks)
    }

    @Test
    fun aSecondFingerDuringAHeldDragDiscardsTheGestureAndReleases() {
        down()
        move(100f + VncPointer.MOVE_SLOP_PX + 1f, 100f)
        val secondary = gesture.onTouch(VncTouch.SecondaryDown(150f, 120f, 300, pointerCount = 2))
        // The left button is released before the new gesture takes over.
        val release = secondary.filterIsInstance<VncPointerAction.PointerAt>().single()
        assertEquals(VncPointer.BUTTON_MASK_NONE, release.buttonMask)
        // And the lifted fingers cannot resolve to anything.
        gesture.onTouch(VncTouch.SecondaryUp(150f, 120f, 320, pointerCount = 1))
        assertTrue(up().isEmpty())
    }

    // ---- dedupe ----

    @Test
    fun aStationaryFingerDoesNotFloodTheSocket() {
        down()
        move(100f + VncPointer.MOVE_SLOP_PX + 1f, 100f)
        val repeat = move(100.5f + VncPointer.MOVE_SLOP_PX, 100f)
        assertTrue("a sub-pixel move must not resend", repeat.isEmpty())
    }
}
