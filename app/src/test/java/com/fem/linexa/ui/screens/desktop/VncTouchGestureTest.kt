package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 8 (§1.138) — JVM tests for [VncTouchGesture] and the masks in
 * [VncPointer].
 *
 * This is the whole reason the state machine is a stateful-but-Android-free class
 * rather than six fields inside `VncSurfaceView`: touch behaviour is otherwise
 * checkable only by hand, one device at a time, and the two defects Phase 8 fixes
 * (movement pressing the left button; a hold becoming a right click) were both
 * *implemented as intended* — they were design errors that compiled, ran, and
 * quietly made the desktop unusable.
 *
 * The four rules the brief names explicitly are each pinned below: ordinary
 * pointer movement is not mistaken for a long press, there are no accidental
 * right clicks, no accidental left clicks during movement, no accidental drags,
 * and jitter does not reach the wire.
 */
class VncTouchGestureTest {

    private fun gesture() = VncTouchGesture()

    private fun List<VncPointerAction>.pointers(): List<VncPointerAction.Pointer> =
        filterIsInstance<VncPointerAction.Pointer>()

    private fun List<VncPointerAction>.masks(): List<Int> = pointers().map { it.buttonMask }

    // ── button masks (unchanged from §1.125, still asserted) ─────────────

    @Test
    fun `the three primary buttons are distinct single bits`() {
        val masks = listOf(
            VncPointer.BUTTON_MASK_LEFT,
            VncPointer.BUTTON_MASK_MIDDLE,
            VncPointer.BUTTON_MASK_RIGHT
        )
        assertEquals("masks must be distinct", masks.size, masks.toSet().size)
        masks.forEach { assertEquals("each mask is one bit", 1, Integer.bitCount(it)) }
    }

    @Test
    fun `button masks match the RFB spec`() {
        // RFB 3.8 §7.5.5: bit 0 = left, bit 1 = middle, bit 2 = right.
        assertEquals(0x00, VncPointer.BUTTON_MASK_NONE)
        assertEquals(0x01, VncPointer.BUTTON_MASK_LEFT)
        assertEquals(0x02, VncPointer.BUTTON_MASK_MIDDLE)
        assertEquals(0x04, VncPointer.BUTTON_MASK_RIGHT)
    }

    @Test
    fun `no primary button collides with the wheel bits`() {
        // §1.114's bug was button *numbers* 4/5 written into a *mask*. The wheel
        // occupies bits 3 and 4, so a primary button must never set those.
        val wheelBits = 0x08 or 0x10
        listOf(
            VncPointer.BUTTON_MASK_LEFT,
            VncPointer.BUTTON_MASK_MIDDLE,
            VncPointer.BUTTON_MASK_RIGHT
        ).forEach { assertEquals(0, it and wheelBits) }
    }

    @Test
    fun `release is the empty mask`() {
        // A non-zero "release" is how a remote desktop ends up stuck mid-drag.
        assertEquals(0, VncPointer.BUTTON_MASK_NONE)
    }

    // ── touching down commits nothing ────────────────────────────────────

    @Test
    fun `touching down sends no pointer event and only arms the hold`() {
        val g = gesture()
        val actions = g.onTouch(VncTouch.Down(100f, 100f, 0L))
        assertTrue("contact alone must not press a button", actions.pointers().isEmpty())
        assertEquals(
            listOf(VncPointerAction.ScheduleHold(VncPointer.LONG_PRESS_MS)),
            actions
        )
        assertEquals(VncTouchGesture.State.PRESSED, g.state)
        assertTrue(g.pendingHold)
    }

    // ── (A) moving a finger moves the pointer, with NO button ────────────

    @Test
    fun `moving a finger moves the pointer with no button held`() {
        // The headline Phase 8 fix. Pre-§1.138 this pressed the left button at the
        // origin and dragged all the way, so simply moving the pointer selected
        // text and moved windows.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        val actions = g.onTouch(VncTouch.Move(200f, 140f, 30L))
        assertEquals(VncTouchGesture.State.MOVING, g.state)
        assertEquals(listOf(VncPointer.BUTTON_MASK_NONE), actions.masks())
        assertEquals("one message per moved sample", 1, actions.pointers().size)
    }

    @Test
    fun `starting to move cancels the hold timer`() {
        // What makes "move the pointer" and "start a drag" disjoint rather than
        // racing: without this, a moving finger would still be pressed 500 ms in.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        val actions = g.onTouch(VncTouch.Move(200f, 100f, 30L))
        assertTrue(actions.contains(VncPointerAction.CancelHold))
        assertFalse(g.pendingHold)
    }

    @Test
    fun `a moving finger never presses a button, however long it moves for`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        g.onTouch(VncTouch.Move(200f, 100f, 30L))
        val emitted = mutableListOf<Int>()
        var t = 60L
        repeat(40) {
            emitted += g.onTouch(VncTouch.Move(200f + it * 5f, 100f, t)).masks()
            t += 50L
        }
        assertTrue("2 seconds of movement must stay buttonless", emitted.all { it == 0 })
    }

    @Test
    fun `a hold that fires after the gesture started moving does nothing`() {
        // Defensive against the timer racing the first move sample: the machine is
        // no longer PRESSED, so the elapsed hold must be ignored rather than
        // pressing the button under a finger that is already moving.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        g.onTouch(VncTouch.Move(200f, 100f, 30L))
        val actions = g.onTouch(VncTouch.HoldElapsed(500L))
        assertTrue(actions.isEmpty())
        assertEquals(VncTouchGesture.State.MOVING, g.state)
    }

    @Test
    fun `lifting after a move does not click`() {
        // "No accidental left-clicks during movement": the pointer was parked on a
        // target, and parking it must not activate it.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        g.onTouch(VncTouch.Move(300f, 300f, 40L))
        val actions = g.onTouch(VncTouch.Up(300f, 300f, 80L))
        assertTrue("no button may be pressed on lift", actions.masks().none { it != 0 })
        assertEquals(VncTouchGesture.State.IDLE, g.state)
    }

    // ── (B) a tap is a left click ────────────────────────────────────────

    @Test
    fun `a short tap is a left click`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        val actions = g.onTouch(VncTouch.Up(101f, 100f, 60L))
        assertEquals(
            listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_NONE),
            actions.masks()
        )
    }

    @Test
    fun `a pointer action carries no coordinates`() {
        // §1.139: pointing is relative, so the position on the wire is the
        // cursor's — synthesised by the view from finger deltas. A coordinate here
        // would be a second, wrong answer to "where does this click land?", which
        // is exactly the teleport §1.139 removes.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        val press = g.onTouch(VncTouch.Up(101f, 100f, 60L)).pointers().first()
        assertEquals(VncPointerAction.Pointer(VncPointer.BUTTON_MASK_LEFT), press)
    }

    @Test
    fun `a tap releases the button it pressed`() {
        // Press with no release is a stuck mouse button on the remote desktop.
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        val masks = g.onTouch(VncTouch.Up(50f, 50f, 40L)).masks()
        assertEquals(VncPointer.BUTTON_MASK_NONE, masks.last())
    }

    @Test
    fun `a tap cancels its own hold timer`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        val actions = g.onTouch(VncTouch.Up(50f, 50f, 40L))
        assertTrue(actions.contains(VncPointerAction.CancelHold))
        assertFalse(g.pendingHold)
    }

    // ── (C) hold-then-move is a drag ─────────────────────────────────────

    @Test
    fun `holding still presses the left button`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(120f, 220f, 0L))
        val actions = g.onTouch(VncTouch.HoldElapsed(VncPointer.LONG_PRESS_MS))
        assertEquals(VncTouchGesture.State.DRAGGING, g.state)
        assertEquals(listOf(VncPointer.BUTTON_MASK_LEFT), actions.masks())
        // §1.139: the grab happens wherever the *cursor* is, which is where the user
        // aimed it — not under the finger, which is covering that spot.
        assertEquals(
            VncPointerAction.Pointer(VncPointer.BUTTON_MASK_LEFT),
            actions.pointers().single()
        )
    }

    @Test
    fun `the drag start is confirmed haptically`() {
        // The button went down with no local visual feedback; the remote server
        // draws the consequence a frame later.
        val g = gesture()
        g.onTouch(VncTouch.Down(10f, 10f, 0L))
        val actions = g.onTouch(VncTouch.HoldElapsed(VncPointer.LONG_PRESS_MS))
        assertTrue(actions.contains(VncPointerAction.Haptic))
    }

    @Test
    fun `moving after the hold keeps the left button down`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(10f, 10f, 0L))
        g.onTouch(VncTouch.HoldElapsed(VncPointer.LONG_PRESS_MS))
        val masks = g.onTouch(VncTouch.Move(60f, 10f, 600L)).masks() +
            g.onTouch(VncTouch.Move(110f, 10f, 640L)).masks()
        assertEquals(
            listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_LEFT),
            masks
        )
    }

    @Test
    fun `releasing a drag releases the button`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(10f, 10f, 0L))
        g.onTouch(VncTouch.HoldElapsed(VncPointer.LONG_PRESS_MS))
        g.onTouch(VncTouch.Move(200f, 90f, 600L))
        val actions = g.onTouch(VncTouch.Up(240f, 90f, 700L))
        assertEquals(listOf(VncPointer.BUTTON_MASK_NONE), actions.masks())
    }

    @Test
    fun `a drag never produces a second click on release`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(10f, 10f, 0L))
        g.onTouch(VncTouch.HoldElapsed(VncPointer.LONG_PRESS_MS))
        g.onTouch(VncTouch.Move(80f, 10f, 600L))
        val masks = g.onTouch(VncTouch.Up(80f, 10f, 700L)).masks()
        assertFalse(
            "a drag must not click on the way out",
            masks.contains(VncPointer.BUTTON_MASK_LEFT)
        )
    }

    @Test
    fun `a full drag sequence is press, moves, release in that order`() {
        val g = gesture()
        val all = mutableListOf<VncPointerAction>()
        all += g.onTouch(VncTouch.Down(30f, 30f, 0L))
        all += g.onTouch(VncTouch.HoldElapsed(500L))
        all += g.onTouch(VncTouch.Move(100f, 30f, 560L))
        all += g.onTouch(VncTouch.Move(170f, 30f, 620L))
        all += g.onTouch(VncTouch.Up(170f, 30f, 700L))
        assertEquals(
            listOf(
                VncPointer.BUTTON_MASK_LEFT,
                VncPointer.BUTTON_MASK_LEFT,
                VncPointer.BUTTON_MASK_LEFT,
                VncPointer.BUTTON_MASK_NONE
            ),
            all.masks()
        )
    }

    // ── (D) right click is a two-finger tap ──────────────────────────────

    @Test
    fun `a two-finger tap is a right click`() {
        // Phase 8's gesture-model change: long press is the same input as the start
        // of a drag, so the finger *count* discriminates instead of a duration.
        val g = gesture()
        g.onTouch(VncTouch.Down(80f, 90f, 0L))
        g.onTouch(VncTouch.SecondaryDown(80f, 90f, 30L, pointerCount = 2))
        val actions = g.onTouch(VncTouch.SecondaryUp(80f, 90f, 150L, pointerCount = 1))
        assertEquals(
            listOf(VncPointer.BUTTON_MASK_RIGHT, VncPointer.BUTTON_MASK_NONE),
            actions.masks()
        )
        assertTrue(actions.contains(VncPointerAction.Haptic))
    }

    @Test
    fun `a right click does not move the cursor first`() {
        // §1.139: the two fingers land wherever they land; the click must happen at
        // the cursor, so the machine emits no position and the view sends the
        // cursor's. The wobble between the two fingers' samples must not leak out as
        // movement either.
        val g = gesture()
        g.onTouch(VncTouch.Down(400f, 300f, 0L))
        g.onTouch(VncTouch.SecondaryDown(400f, 300f, 20L, pointerCount = 2))
        val actions = g.onTouch(VncTouch.SecondaryUp(402f, 301f, 120L, pointerCount = 1))
        assertEquals(
            listOf(
                VncPointerAction.Pointer(VncPointer.BUTTON_MASK_RIGHT),
                VncPointerAction.Pointer(VncPointer.BUTTON_MASK_NONE)
            ),
            actions.pointers()
        )
    }

    @Test
    fun `the second finger cancels the hold so a right click cannot also drag`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(80f, 90f, 0L))
        val actions = g.onTouch(VncTouch.SecondaryDown(80f, 90f, 30L, pointerCount = 2))
        assertTrue(actions.contains(VncPointerAction.CancelHold))
        assertFalse(g.pendingHold)
    }

    @Test
    fun `a long press is NOT a right click`() {
        // The gesture conflict Phase 8 resolves. Holding must produce a left-button
        // drag; a right click must be unreachable from one finger.
        val g = gesture()
        g.onTouch(VncTouch.Down(80f, 90f, 0L))
        val masks = g.onTouch(VncTouch.HoldElapsed(5_000L)).masks()
        assertFalse(masks.contains(VncPointer.BUTTON_MASK_RIGHT))
        assertEquals(listOf(VncPointer.BUTTON_MASK_LEFT), masks)
    }

    @Test
    fun `two fingers resting too long are not a tap`() {
        // An unstarted scroll or a pinch. Clicking here would be a right click the
        // user never asked for.
        val g = gesture()
        g.onTouch(VncTouch.Down(80f, 90f, 0L))
        g.onTouch(VncTouch.SecondaryDown(80f, 90f, 0L, pointerCount = 2))
        val actions = g.onTouch(
            VncTouch.SecondaryUp(80f, 90f, VncPointer.MULTI_TAP_MS + 1, pointerCount = 1)
        )
        assertTrue(actions.pointers().isEmpty())
    }

    @Test
    fun `three fingers produce nothing`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(80f, 90f, 0L))
        g.onTouch(VncTouch.SecondaryDown(80f, 90f, 20L, pointerCount = 2))
        g.onTouch(VncTouch.SecondaryDown(80f, 90f, 40L, pointerCount = 3))
        val actions = g.onTouch(VncTouch.SecondaryUp(80f, 90f, 100L, pointerCount = 2)) +
            g.onTouch(VncTouch.Up(80f, 90f, 140L))
        assertTrue("an unknown gesture must not degrade into a known one", actions.isEmpty())
    }

    @Test
    fun `a second finger during a drag ends the drag and does not right-click`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        g.onTouch(VncTouch.HoldElapsed(500L))
        g.onTouch(VncTouch.Move(120f, 50f, 560L))
        val down = g.onTouch(VncTouch.SecondaryDown(120f, 50f, 600L, pointerCount = 2))
        assertEquals(
            "the held button must be released cleanly",
            listOf(VncPointer.BUTTON_MASK_NONE),
            down.masks()
        )
        val rest = g.onTouch(VncTouch.SecondaryUp(120f, 50f, 660L, pointerCount = 1)) +
            g.onTouch(VncTouch.Up(120f, 50f, 700L))
        assertTrue("a drag must not be able to become a right click", rest.isEmpty())
    }

    // ── two-finger drag is scroll ────────────────────────────────────────

    @Test
    fun `two fingers moving scroll instead of clicking`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(200f, 400f, 0L))
        g.onTouch(VncTouch.SecondaryDown(200f, 400f, 20L, pointerCount = 2))
        val actions = g.onTouch(VncTouch.Move(200f, 500f, 80L))
        val wheel = actions.filterIsInstance<VncPointerAction.Wheel>().single()
        assertEquals(100f, wheel.deltaY, 0.001f)
        assertTrue(actions.pointers().isEmpty())
        assertEquals(VncTouchGesture.State.SCROLLING, g.state)
    }

    @Test
    fun `scroll direction follows the finger`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(200f, 400f, 0L))
        g.onTouch(VncTouch.SecondaryDown(200f, 400f, 20L, pointerCount = 2))
        val down = g.onTouch(VncTouch.Move(200f, 500f, 80L))
            .filterIsInstance<VncPointerAction.Wheel>().single()
        val up = g.onTouch(VncTouch.Move(200f, 300f, 140L))
            .filterIsInstance<VncPointerAction.Wheel>().single()
        assertTrue(down.deltaY > 0f)
        assertTrue(up.deltaY < 0f)
    }

    @Test
    fun `lifting after a scroll does not click`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(200f, 400f, 0L))
        g.onTouch(VncTouch.SecondaryDown(200f, 400f, 20L, pointerCount = 2))
        g.onTouch(VncTouch.Move(200f, 520f, 80L))
        val actions = g.onTouch(VncTouch.SecondaryUp(200f, 520f, 140L, pointerCount = 1)) +
            g.onTouch(VncTouch.Up(200f, 520f, 180L))
        assertTrue(actions.pointers().isEmpty())
    }

    @Test
    fun `a scroll that has begun cannot resolve to a right click`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(200f, 400f, 0L))
        g.onTouch(VncTouch.SecondaryDown(200f, 400f, 20L, pointerCount = 2))
        g.onTouch(VncTouch.Move(200f, 500f, 60L))
        val actions = g.onTouch(VncTouch.SecondaryUp(200f, 500f, 100L, pointerCount = 1))
        assertTrue(actions.masks().none { it == VncPointer.BUTTON_MASK_RIGHT })
    }

    // ── jitter ───────────────────────────────────────────────────────────

    @Test
    fun `sub-slop jitter sends nothing at all`() {
        // "Excessive pointer jitter": a resting finger must neither nudge the remote
        // pointer nor cancel the hold it is about to complete.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        val actions = g.onTouch(VncTouch.Move(103f, 102f, 20L)) +
            g.onTouch(VncTouch.Move(99f, 101f, 40L))
        assertTrue(actions.isEmpty())
        assertEquals(VncTouchGesture.State.PRESSED, g.state)
        assertTrue("the hold must survive jitter", g.pendingHold)
    }

    @Test
    fun `jitter still leaves a tap available`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        g.onTouch(VncTouch.Move(104f, 100f, 20L))
        val masks = g.onTouch(VncTouch.Up(104f, 100f, 60L)).masks()
        assertEquals(
            listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_NONE),
            masks
        )
    }

    @Test
    fun `a repeated position is not re-sent`() {
        // One RFB pointer message per *change*: a stationary stream of MOVE samples
        // is common and would otherwise flood the socket.
        val g = gesture()
        g.onTouch(VncTouch.Down(100f, 100f, 0L))
        g.onTouch(VncTouch.Move(300f, 300f, 30L))
        val repeated = g.onTouch(VncTouch.Move(300f, 300f, 60L))
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun `the move slop is small enough to feel immediate and non-zero`() {
        assertTrue(VncPointer.MOVE_SLOP_PX > 0f)
        assertTrue(VncPointer.MOVE_SLOP_PX <= 24f)
    }

    @Test
    fun `travel is euclidean so every slop is a radius`() {
        // A bounding-box test would allow ~17 px of diagonal travel inside a 12 px
        // box, i.e. visible movement classified as stationary.
        assertEquals(5f, VncPointer.travel(0f, 0f, 3f, 4f), 0.001f)
        assertEquals(0f, VncPointer.travel(9f, 9f, 9f, 9f), 0.001f)
    }

    @Test
    fun `travel is direction agnostic`() {
        assertEquals(
            VncPointer.travel(0f, 0f, 10f, 0f),
            VncPointer.travel(10f, 0f, 0f, 0f),
            0.001f
        )
        assertEquals(
            VncPointer.travel(0f, 0f, 0f, -10f),
            VncPointer.travel(0f, 0f, 0f, 10f),
            0.001f
        )
    }

    @Test
    fun `a diagonal move inside the bounding box still moves the pointer`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(0f, 0f, 0L))
        val travel = VncPointer.travel(0f, 0f, 10f, 10f) // ~14.1 px
        assertTrue(travel > VncPointer.MOVE_SLOP_PX)
        assertEquals(VncTouchGesture.State.MOVING, run {
            g.onTouch(VncTouch.Move(10f, 10f, 30L))
            g.state
        })
    }

    // ── cancellation and teardown ────────────────────────────────────────

    @Test
    fun `cancel releases a held button`() {
        // Never leave a button held: the remote desktop would behave as if the user
        // were still dragging, forever.
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        g.onTouch(VncTouch.HoldElapsed(500L))
        g.onTouch(VncTouch.Move(150f, 50f, 560L))
        val actions = g.onTouch(VncTouch.Cancel)
        assertEquals(listOf(VncPointer.BUTTON_MASK_NONE), actions.masks())
        assertEquals(VncTouchGesture.State.IDLE, g.state)
    }

    @Test
    fun `cancel disarms a pending hold`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        val actions = g.onTouch(VncTouch.Cancel)
        assertTrue(actions.contains(VncPointerAction.CancelHold))
        assertFalse(g.pendingHold)
    }

    @Test
    fun `cancel with nothing held emits nothing`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        g.onTouch(VncTouch.Move(200f, 50f, 40L))
        val actions = g.onTouch(VncTouch.Cancel)
        assertTrue(actions.pointers().isEmpty())
    }

    @Test
    fun `reset releases a drag so a torn-down view cannot leak a held button`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        g.onTouch(VncTouch.HoldElapsed(500L))
        assertEquals(listOf(VncPointer.BUTTON_MASK_NONE), g.reset().masks())
    }

    @Test
    fun `a gesture after a cancel starts clean`() {
        val g = gesture()
        g.onTouch(VncTouch.Down(50f, 50f, 0L))
        g.onTouch(VncTouch.HoldElapsed(500L))
        g.onTouch(VncTouch.Cancel)
        g.onTouch(VncTouch.Down(400f, 400f, 1_000L))
        val masks = g.onTouch(VncTouch.Up(400f, 400f, 1_050L)).masks()
        assertEquals(
            listOf(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_NONE),
            masks
        )
    }

    @Test
    fun `events arriving with no finger down are ignored`() {
        val g = gesture()
        assertTrue(g.onTouch(VncTouch.Move(10f, 10f, 0L)).isEmpty())
        assertTrue(g.onTouch(VncTouch.Up(10f, 10f, 10L)).pointers().isEmpty())
        assertTrue(g.onTouch(VncTouch.HoldElapsed(20L)).isEmpty())
    }

    // ── thresholds ───────────────────────────────────────────────────────

    @Test
    fun `the hold delay is the platform long-press interval`() {
        // Free to be short now that it is the *drag* delay rather than the
        // right-click delay: §1.125 had to inflate it to 600 ms precisely because
        // the two gestures were competing.
        assertEquals(500L, VncPointer.LONG_PRESS_MS)
    }

    @Test
    fun `the two-finger tap window is shorter than the hold`() {
        // Otherwise a two-finger gesture could be both a tap and a settled scroll.
        assertTrue(VncPointer.MULTI_TAP_MS < VncPointer.LONG_PRESS_MS)
    }

    @Test
    fun `the scroll slop exceeds the move slop`() {
        // Placing a second finger wobbles more than moving one; a lower slop would
        // make the two-finger tap impossible to hold still enough to register.
        assertTrue(VncPointer.SCROLL_SLOP_PX > VncPointer.MOVE_SLOP_PX)
    }

    @Test
    fun `left and right are different buttons`() {
        assertNotEquals(VncPointer.BUTTON_MASK_LEFT, VncPointer.BUTTON_MASK_RIGHT)
    }
}
