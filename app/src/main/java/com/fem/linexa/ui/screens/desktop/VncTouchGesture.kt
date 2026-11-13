package com.fem.linexa.ui.screens.desktop

/**
 * UI/UX Phase 8 (§1.138) — the deterministic touch → RFB pointer state machine
 * for the embedded VNC client.
 *
 * ## Why this replaces `VncPointer.classify`
 *
 * The §1.125 model was a pure function of *(elapsed, travel)* returning
 * `PENDING` / `DRAG` / `LONG_PRESS`. It produced two behaviours that make a
 * remote desktop unusable, neither of which can surface as an error:
 *
 *  * **Moving a finger held the left button down.** Any travel past the slop was
 *    classified `DRAG`, so the single most common thing a user does — move the
 *    pointer onto a target — pressed the button at the origin and dragged all the
 *    way there, selecting text, moving windows and rubber-banding the desktop.
 *    There was no gesture at all for "just move the pointer".
 *  * **A slow drag became a right click.** Press-and-hold-then-move *is* how a
 *    touchscreen expresses "press the button and drag", and it is also how a
 *    careful drag begins. With hold → right click, a hesitant drag popped a
 *    context menu. §1.125 raised the timeout to 600 ms to compensate, which is
 *    the fix the phase brief explicitly rejects ("do not simply increase the
 *    timeout without analyzing the gesture conflict") — and correctly, because
 *    the two gestures were the *same input*, so no threshold separates them.
 *
 * ## The model
 *
 * | Gesture                              | Result                                  |
 * |--------------------------------------|-----------------------------------------|
 * | Move one finger                      | Cursor moves by that much, **no button** (hover) |
 * | Short tap                            | Left click where the cursor already is  |
 * | Hold ~[VncPointer.LONG_PRESS_MS], then move | Left button down → move → up on release |
 * | Two-finger tap                       | Right click where the cursor already is |
 * | Two-finger drag                      | Wheel scroll                            |
 *
 * §1.139: every result above happens at the **cursor's** position, never the
 * finger's. Pointing is relative (touchpad-style), because RFB has no relative
 * pointer message and forwarding the touch position teleported the remote cursor
 * under the fingertip covering the target. This machine therefore emits no
 * coordinates at all — it decides *what* to send, and `VncSurfaceView` owns where
 * the cursor is ([VncRelativeCursor]).
 *
 * Three deliberate choices, each of which is the reason a defect above cannot
 * come back:
 *
 * **(1) Right click is a two-finger tap, not a long press.** The brief allows
 * "a deliberate long press or another appropriate gesture", and long press is
 * the wrong half of that: it is the same input as the start of a drag, so
 * whichever meaning it is given, the other becomes unreachable. Dragging is the
 * more frequent and less replaceable of the two (there is no other way to move a
 * window, drag a scrollbar or select text), so it keeps the hold. A two-finger
 * tap is unambiguous — the finger *count* is the discriminator, not a duration —
 * and it costs nothing, because two-finger *drag* is already scroll, so the
 * second finger is already in the vocabulary.
 *
 * **(2) A drag may only start from a finger that has not moved.** The hold timer
 * is cancelled by the first movement past [VncPointer.MOVE_SLOP_PX]. That is what
 * makes "move the pointer" and "start a drag" disjoint rather than racing: once
 * the gesture is moving it stays a hover until the finger lifts, and once it is
 * dragging it stays a drag. No sample can be classified two ways.
 *
 * **(3) Nothing is emitted on touch *down*.** The button is committed on the hold
 * timer (drag) or on the lift (tap), never on contact, so a gesture that turns
 * out to be something else has not already pressed a button that cannot be
 * un-pressed. Sub-slop samples are dropped entirely, so a resting finger neither
 * nudges the remote pointer nor cancels the hold it is about to complete.
 *
 * ## Shape
 *
 * The machine is stateful but has **no Android types**: it consumes [VncTouch]
 * events (view coordinates, event timestamps) and returns the ordered
 * [VncPointerAction]s to perform, including when to arm and cancel the hold timer.
 * That is what lets every rule above — including the timing ones — be asserted
 * from a JVM test, which is the only way touch behaviour gets checked at all
 * here: this repo has no instrumentation harness, and a gesture bug is otherwise
 * discoverable only by hand, one device at a time.
 */
internal class VncTouchGesture : VncGestureMachine {

    /** What the machine is currently doing. Exposed so tests can pin transitions. */
    enum class State {
        /** No finger down. */
        IDLE,

        /** One finger down, still within the move slop: tap, or a drag about to start. */
        PRESSED,

        /** One finger down, past the slop: the pointer is following it, no button. */
        MOVING,

        /** The hold completed: the left button is down and following the finger. */
        DRAGGING,

        /** Two fingers down, still still: a right-click tap, or a scroll about to start. */
        MULTI,

        /** Two fingers down and moving: scrolling. */
        SCROLLING,

        /**
         * The gesture is over but fingers are still on the glass. Every further
         * sample is ignored, so lifting after a scroll or a two-finger tap cannot
         * emit a stray click.
         */
        SPENT
    }

    var state: State = State.IDLE
        private set

    // Finger positions, in view pixels. §1.139: these are the machine's own
    // bookkeeping — how far the finger has travelled from contact, and whether it
    // has moved since the last message — and are never put on the wire. The
    // pointer's position is the cursor's, which the view owns.

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Where the finger was when the last pointer message was sent, so redundant moves are dropped. */
    private var sentX = Float.NaN
    private var sentY = Float.NaN

    private var heldMask = VncPointer.BUTTON_MASK_NONE

    private var multiDownTimeMs = 0L
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var scrollAnchorY = 0f

    /** True once a hold has been armed and not yet cancelled — for [pendingHold]. */
    private var holdArmed = false

    /** Whether a hold timer is currently expected to fire. Tests read this. */
    val pendingHold: Boolean get() = holdArmed

    override fun onTouch(event: VncTouch): List<VncPointerAction> = when (event) {
        is VncTouch.Down -> onDown(event)
        is VncTouch.Move -> onMove(event)
        is VncTouch.Up -> onUp(event)
        is VncTouch.SecondaryDown -> onSecondaryDown(event)
        is VncTouch.SecondaryUp -> onSecondaryUp(event)
        is VncTouch.HoldElapsed -> onHoldElapsed()
        is VncTouch.Cancel -> onCancel()
    }

    /** Forgets everything, releasing a held button if one is down. */
    override fun reset(): List<VncPointerAction> = onCancel()

    // ── single finger ────────────────────────────────────────────────────

    private fun onDown(event: VncTouch.Down): List<VncPointerAction> {
        downX = event.x
        downY = event.y
        lastX = event.x
        lastY = event.y
        heldMask = VncPointer.BUTTON_MASK_NONE
        state = State.PRESSED
        holdArmed = true
        // Deliberately no pointer message: contact alone commits nothing.
        return listOf(VncPointerAction.ScheduleHold(VncPointer.LONG_PRESS_MS))
    }

    private fun onMove(event: VncTouch.Move): List<VncPointerAction> {
        lastX = event.x
        lastY = event.y
        return when (state) {
            State.PRESSED -> {
                val travel = VncPointer.travel(downX, downY, event.x, event.y)
                if (travel <= VncPointer.MOVE_SLOP_PX) {
                    // Jitter from a resting finger: dropped, so it neither moves the
                    // remote pointer nor cancels the hold that is about to fire.
                    emptyList()
                } else {
                    state = State.MOVING
                    buildList {
                        add(VncPointerAction.CancelHold)
                        holdArmed = false
                        addAll(hover(event.x, event.y))
                    }
                }
            }

            State.MOVING -> hover(event.x, event.y)

            State.DRAGGING -> send(event.x, event.y, VncPointer.BUTTON_MASK_LEFT)

            State.MULTI -> {
                val travel = VncPointer.travel(multiStartX, multiStartY, event.x, event.y)
                if (travel <= VncPointer.SCROLL_SLOP_PX) {
                    // Still a candidate two-finger tap. Placing two fingers always
                    // wobbles a little; scrolling on that wobble would make the tap
                    // impossible to perform.
                    emptyList()
                } else {
                    state = State.SCROLLING
                    wheel(event.x, event.y)
                }
            }

            State.SCROLLING -> wheel(event.x, event.y)

            State.IDLE, State.SPENT -> emptyList()
        }
    }

    private fun onUp(event: VncTouch.Up): List<VncPointerAction> {
        lastX = event.x
        lastY = event.y
        val actions = when (state) {
            State.PRESSED -> buildList {
                add(VncPointerAction.CancelHold)
                holdArmed = false
                // A tap is emitted on the lift, not on contact, so a gesture that
                // turned out to be a drag or a scroll never clicked first.
                add(pointer(event.x, event.y, VncPointer.BUTTON_MASK_LEFT))
                add(pointer(event.x, event.y, VncPointer.BUTTON_MASK_NONE))
            }

            State.MOVING -> hover(event.x, event.y)

            State.DRAGGING -> listOf(pointer(event.x, event.y, VncPointer.BUTTON_MASK_NONE))

            // A gesture that already resolved (scroll, two-finger tap) or never
            // started must not click on the way out.
            State.MULTI, State.SCROLLING, State.SPENT, State.IDLE -> emptyList()
        }
        heldMask = VncPointer.BUTTON_MASK_NONE
        state = State.IDLE
        holdArmed = false
        return actions
    }

    private fun onHoldElapsed(): List<VncPointerAction> {
        holdArmed = false
        if (state != State.PRESSED) return emptyList()
        state = State.DRAGGING
        heldMask = VncPointer.BUTTON_MASK_LEFT
        return listOf(
            pointer(downX, downY, VncPointer.BUTTON_MASK_LEFT),
            // The button went down with no visual feedback of its own — the remote
            // server draws the consequence a frame later, so the confirmation that
            // the drag has begun has to come from the device.
            VncPointerAction.Haptic
        )
    }

    // ── second finger ────────────────────────────────────────────────────

    private fun onSecondaryDown(event: VncTouch.SecondaryDown): List<VncPointerAction> {
        val wasDragging = state == State.DRAGGING
        val actions = buildList {
            if (heldMask != VncPointer.BUTTON_MASK_NONE) {
                // End the drag cleanly rather than leaving the button down while a
                // different gesture takes over.
                add(pointer(lastX, lastY, VncPointer.BUTTON_MASK_NONE))
                heldMask = VncPointer.BUTTON_MASK_NONE
            }
            if (holdArmed) {
                add(VncPointerAction.CancelHold)
                holdArmed = false
            }
        }
        // Three fingers is not a gesture this client has, and a drag already in
        // progress must not be able to *become* a right click — in both cases the
        // rest of the gesture is discarded rather than reinterpreted.
        if (event.pointerCount > 2 || wasDragging || state == State.SPENT) {
            state = State.SPENT
            return actions
        }
        multiDownTimeMs = event.timeMs
        multiStartX = event.x
        multiStartY = event.y
        scrollAnchorY = event.y
        state = State.MULTI
        return actions
    }

    private fun onSecondaryUp(event: VncTouch.SecondaryUp): List<VncPointerAction> {
        if (state != State.MULTI) {
            // Scrolled, or already spent: nothing to resolve.
            state = State.SPENT
            return emptyList()
        }
        state = State.SPENT
        val heldMs = event.timeMs - multiDownTimeMs
        if (heldMs > VncPointer.MULTI_TAP_MS) {
            // Two fingers rested too long to be a tap. It was an unstarted scroll or
            // a pinch; clicking here would be a right click the user did not ask for.
            return emptyList()
        }
        return listOf(
            pointer(multiStartX, multiStartY, VncPointer.BUTTON_MASK_RIGHT),
            pointer(multiStartX, multiStartY, VncPointer.BUTTON_MASK_NONE),
            VncPointerAction.Haptic
        )
    }

    private fun onCancel(): List<VncPointerAction> {
        val actions = buildList {
            if (heldMask != VncPointer.BUTTON_MASK_NONE) {
                // Never leave a button held: the remote desktop would behave as if
                // the user were still dragging, forever.
                add(pointer(lastX, lastY, VncPointer.BUTTON_MASK_NONE))
            }
            if (holdArmed) add(VncPointerAction.CancelHold)
        }
        heldMask = VncPointer.BUTTON_MASK_NONE
        holdArmed = false
        state = State.IDLE
        return actions
    }

    // ── emit helpers ─────────────────────────────────────────────────────

    /** A buttonless pointer message, dropped when the finger has not moved. */
    private fun hover(x: Float, y: Float): List<VncPointerAction> =
        send(x, y, VncPointer.BUTTON_MASK_NONE)

    /**
     * Emits one pointer message unless the finger is where it was when the last
     * one was sent.
     *
     * The dedupe compares **finger** positions, which the machine tracks purely to
     * answer "has anything changed?" — one RFB message per change, since a
     * stationary stream of MOVE samples is common and would otherwise flood the
     * socket. The message itself carries no position (§1.139): the cursor's
     * position is the view's, because touch input is relative.
     */
    private fun send(x: Float, y: Float, mask: Int): List<VncPointerAction> {
        if (!sentX.isNaN() && VncPointer.travel(sentX, sentY, x, y) < 1f) return emptyList()
        return listOf(pointer(x, y, mask))
    }

    private fun pointer(x: Float, y: Float, mask: Int): VncPointerAction.Pointer {
        sentX = x
        sentY = y
        return VncPointerAction.Pointer(mask)
    }

    private fun wheel(x: Float, y: Float): List<VncPointerAction> {
        val deltaY = y - scrollAnchorY
        if (kotlin.math.abs(deltaY) < 1f) return emptyList()
        scrollAnchorY = y
        return listOf(VncPointerAction.Wheel(deltaY))
    }
}

/**
 * A touch sample, in **view** coordinates. Deliberately not `MotionEvent`: the
 * machine has to be drivable from a JVM test, and the view is the only place that
 * knows how to read a `MotionEvent`.
 */
internal sealed interface VncTouch {
    /** The first finger touches down. */
    data class Down(val x: Float, val y: Float, val timeMs: Long) : VncTouch

    /** The primary finger moved. */
    data class Move(val x: Float, val y: Float, val timeMs: Long) : VncTouch

    /** The last finger lifted. */
    data class Up(val x: Float, val y: Float, val timeMs: Long) : VncTouch

    /**
     * An additional finger touched down. [x]/[y] are the **primary** finger's
     * position (the one the gestures are measured against), [pointerCount] the
     * total now down.
     */
    data class SecondaryDown(
        val x: Float,
        val y: Float,
        val timeMs: Long,
        val pointerCount: Int
    ) : VncTouch

    /** One of several fingers lifted, leaving [pointerCount] down. */
    data class SecondaryUp(
        val x: Float,
        val y: Float,
        val timeMs: Long,
        val pointerCount: Int
    ) : VncTouch

    /** The armed hold timer fired. */
    data class HoldElapsed(val timeMs: Long) : VncTouch

    /** The window took the gesture away (scroll container, phone call, …). */
    data object Cancel : VncTouch
}

/** What the view must do, in order, for one [VncTouch]. */
internal sealed interface VncPointerAction {
    /**
     * Send an RFB pointer event with this button mask, at wherever the cursor
     * currently is. Movement, press and release are all this one message on the
     * wire (RFB 3.8 §7.5.5), so they are one action here rather than three that
     * could disagree.
     *
     * §1.139: deliberately carries **no coordinates**. Touch input is relative, so
     * the position on the wire is the cursor's own — synthesised by the view from
     * accumulated finger deltas ([VncRelativeCursor]) — and a finger position
     * here would be a second, wrong answer to "where does this click land?" that
     * the next edit could pick up. The machine decides *what* to send; the view
     * owns *where* the cursor is.
     */
    data class Pointer(val buttonMask: Int) : VncPointerAction

    /**
     * Desktop Zoom / Touch work (2026-08): send an RFB pointer event with this
     * button mask, after first placing the cursor at the **finger's** position
     * ([x]/[y], in view coordinates — the view maps them through the current
     * zoom/pan to framebuffer coordinates).
     *
     * Emitted only by the direct-touch machine ([VncDirectGesture]), whose
     * answer to "where does this click mean?" is the finger's own position
     * rather than the integrated cursor's. The relative machine deliberately
     * never emits this (§1.139 above): its cursor position is the view's, and a
     * machine-supplied position there would be the teleport defect all over
     * again.
     */
    data class PointerAt(val x: Float, val y: Float, val buttonMask: Int) : VncPointerAction

    /** Scroll by [deltaY] **view** pixels, at the cursor's position. */
    data class Wheel(val deltaY: Float) : VncPointerAction

    /** Confirm a button-down the user cannot yet see (drag start, right click). */
    data object Haptic : VncPointerAction

    /** Arm the hold timer; it must deliver [VncTouch.HoldElapsed] after [delayMs]. */
    data class ScheduleHold(val delayMs: Long) : VncPointerAction

    /** Disarm the hold timer. */
    data object CancelHold : VncPointerAction
}

/**
 * Desktop Touch work (2026-08) — the contract both gesture machines share, so
 * the view can select the touch mode's machine per gesture without knowing
 * which one it is driving.
 *
 * The signature is exactly [VncTouchGesture]'s historical public surface: one
 * pure function over touch samples returning the actions to perform, plus
 * reset-on-teardown. Anything the view needs beyond this (hold-timer state for
 * tests) stays visible on the concrete classes.
 */
internal interface VncGestureMachine {
    fun onTouch(event: VncTouch): List<VncPointerAction>

    /** Forgets everything, releasing a held button if one is down. */
    fun reset(): List<VncPointerAction>
}
