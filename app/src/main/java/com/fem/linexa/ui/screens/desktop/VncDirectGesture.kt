package com.fem.linexa.ui.screens.desktop

/**
 * Desktop Zoom / Touch work (2026-08) — the **direct** (absolute) touch mode's
 * gesture machine, the sibling of the touchpad-style [VncTouchGesture].
 *
 * ## Why a second machine
 *
 * Touch input used to be touchpad-relative only: the finger's *movement* was
 * integrated into a synthetic cursor ([VncRelativeCursor]) and its position was
 * never used. That is the precision-optimised model — but it is not the model
 * most mobile users reach for first, and every mature mobile VNC client offers
 * a direct mode where the finger's *position* is where the click means. The two
 * models disagree about what a drag is (relative: press-and-hold-then-move,
 * because movement alone must not click; direct: press-and-move, because the
 * finger *is* the pointer), and the §1.138 history shows that overloading one
 * state machine with two meanings for the same input is how drag-vs-long-press
 * bugs happen. Two machines, one contract.
 *
 * ## The direct contract
 *
 * - **Tap = click where you tapped.** Emitted on the *lift* (same rule as the
 *   relative machine: "a tap is emitted on the lift, so a gesture that turned
 *   out to be a drag never clicked first") at the finger's own position.
 * - **Drag = press-and-move.** Travel beyond [VncPointer.MOVE_SLOP_PX] presses
 *   the left button at that spot and holds it for the rest of the gesture; the
 *   lift releases. No hold timer competes with dragging, because dragging no
 *   longer needs one.
 * - **Long-press = right button.** Direct mode freed the hold from drag duty,
 *   so it becomes the right-click gesture (with haptic confirmation, same as
 *   the relative machine's drag confirmation). Moving while held right-drags.
 * - **Two-finger gestures are shared with the relative mode on purpose:**
 *   two-finger tap = right click, two-finger drag = scroll, same thresholds
 *   ([VncPointer.MULTI_TAP_MS], [VncPointer.SCROLL_SLOP_PX]), so switching
 *   modes never relearns the second finger.
 *
 * ## Coordinates
 *
 * Like [VncTouchGesture], this machine decides *what* to send and the view owns
 * *where the cursor is* — but the direct mode's answer to "where" is different,
 * so it emits [VncPointerAction.PointerAt]: "place the cursor at the *finger's*
 * position (mapped view→frame by the view, through the current zoom) and send
 * this mask". The machine never converts coordinates itself: it does not know
 * the zoom, the contain-scale, or the framebuffer size, and that ignorance is
 * what keeps it pure and JVM-testable.
 */
internal class VncDirectGesture : VncGestureMachine {

    private enum class State {
        /** First finger down, nothing committed yet. */
        PRESSED,

        /** Left button held; the finger is dragging. */
        DRAGGING,

        /** Right button held (a completed long-press). */
        RIGHT_HELD,

        /** Two fingers down, undecided between tap and scroll. */
        MULTI,

        /** Two-finger scroll in progress. */
        SCROLLING,

        /** The gesture resolved without a click; the lift must not click. */
        SPENT,

        IDLE
    }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** The button currently held, so Cancel can always release exactly what is down. */
    private var heldMask = VncPointer.BUTTON_MASK_NONE

    private var multiDownTimeMs = 0L
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var scrollAnchorY = 0f

    /** Where the finger was when the last pointer message was sent, so redundant moves are dropped. */
    private var sentX = Float.NaN
    private var sentY = Float.NaN

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
        // Deliberately no pointer message on contact: the click lands on the
        // lift, so a gesture that turns out to be a drag, a scroll or a
        // right-click hold never clicks first (the same rule the relative
        // machine states for its own tap).
        return listOf(VncPointerAction.ScheduleHold(VncPointer.LONG_PRESS_MS))
    }

    private fun onMove(event: VncTouch.Move): List<VncPointerAction> {
        lastX = event.x
        lastY = event.y
        return when (state) {
            State.PRESSED -> {
                val travel = VncPointer.travel(downX, downY, event.x, event.y)
                if (travel <= VncPointer.MOVE_SLOP_PX) {
                    // Jitter from a resting finger: dropped, so it neither moves
                    // the remote pointer nor cancels the pending right-click hold.
                    emptyList()
                } else {
                    // Press-and-move IS the drag in direct mode — no hold needed.
                    state = State.DRAGGING
                    heldMask = VncPointer.BUTTON_MASK_LEFT
                    buildList<VncPointerAction> {
                        add(VncPointerAction.CancelHold)
                        holdArmed = false
                        addAll(send(event.x, event.y, VncPointer.BUTTON_MASK_LEFT))
                    }
                }
            }

            State.DRAGGING -> send(event.x, event.y, VncPointer.BUTTON_MASK_LEFT)

            State.RIGHT_HELD -> send(event.x, event.y, VncPointer.BUTTON_MASK_RIGHT)

            State.MULTI -> {
                val travel = VncPointer.travel(multiStartX, multiStartY, event.x, event.y)
                if (travel <= VncPointer.SCROLL_SLOP_PX) {
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
            // The tap: click where the finger is, on the lift.
            State.PRESSED -> buildList<VncPointerAction> {
                add(VncPointerAction.CancelHold)
                holdArmed = false
                addAll(send(event.x, event.y, VncPointer.BUTTON_MASK_LEFT))
                add(pointer(event.x, event.y, VncPointer.BUTTON_MASK_NONE))
            }

            State.DRAGGING -> listOf(pointer(event.x, event.y, VncPointer.BUTTON_MASK_NONE))

            State.RIGHT_HELD -> listOf(pointer(event.x, event.y, VncPointer.BUTTON_MASK_NONE))

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
        state = State.RIGHT_HELD
        heldMask = VncPointer.BUTTON_MASK_RIGHT
        return listOf(
            pointer(downX, downY, VncPointer.BUTTON_MASK_RIGHT),
            // The server draws the right button's consequence a frame later; the
            // device is the only immediate confirmation (same rule as the
            // relative machine's drag-start haptic).
            VncPointerAction.Haptic
        )
    }

    // ── second finger ────────────────────────────────────────────────────

    private fun onSecondaryDown(event: VncTouch.SecondaryDown): List<VncPointerAction> {
        val wasHeld = heldMask != VncPointer.BUTTON_MASK_NONE
        val actions = buildList<VncPointerAction> {
            if (wasHeld) {
                // End the drag/right-hold cleanly rather than leaving the button
                // down while a different gesture takes over.
                add(pointer(lastX, lastY, VncPointer.BUTTON_MASK_NONE))
                heldMask = VncPointer.BUTTON_MASK_NONE
            }
            if (holdArmed) {
                add(VncPointerAction.CancelHold)
                holdArmed = false
            }
        }
        // Three fingers is not a gesture this client has, and a held-button
        // gesture must not be able to *become* a right click — in both cases the
        // rest of the gesture is discarded rather than reinterpreted.
        if (event.pointerCount > 2 || wasHeld || state == State.SPENT) {
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
            // Two fingers rested too long to be a tap. It was an unstarted scroll
            // or a pinch; clicking here would be a right click nobody asked for.
            return emptyList()
        }
        return listOf(
            pointer(multiStartX, multiStartY, VncPointer.BUTTON_MASK_RIGHT),
            pointer(multiStartX, multiStartY, VncPointer.BUTTON_MASK_NONE),
            VncPointerAction.Haptic
        )
    }

    private fun onCancel(): List<VncPointerAction> {
        val actions = buildList<VncPointerAction> {
            if (heldMask != VncPointer.BUTTON_MASK_NONE) {
                // Never leave a button held: the remote desktop would behave as
                // if the user were still dragging, forever.
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

    /**
     * One pointer message at the *finger's* position, dropped when the finger is
     * where the last message was sent from. The dedupe compares finger
     * positions, exactly like the relative machine — one RFB message per change.
     */
    private fun send(x: Float, y: Float, mask: Int): List<VncPointerAction> {
        if (!sentX.isNaN() && VncPointer.travel(sentX, sentY, x, y) < 1f) return emptyList()
        return listOf(pointer(x, y, mask))
    }

    private fun pointer(x: Float, y: Float, mask: Int): VncPointerAction.PointerAt {
        sentX = x
        sentY = y
        return VncPointerAction.PointerAt(x, y, mask)
    }

    private fun wheel(x: Float, y: Float): List<VncPointerAction> {
        val deltaY = y - scrollAnchorY
        if (kotlin.math.abs(deltaY) < 1f) return emptyList()
        scrollAnchorY = y
        return listOf(VncPointerAction.Wheel(deltaY))
    }
}
