package com.fem.linexa.ui.screens.desktop

/**
 * Pointer-button masks and gesture thresholds for the embedded VNC client.
 *
 * §1.125 introduced this file to name the RFB button masks (§1.114 had already
 * shipped a bug where wheel button *numbers* were written into a bit *mask*, and
 * the same mistake was one edit away for the primary buttons because nothing
 * named them). Those masks are unchanged.
 *
 * **UI/UX Phase 8 (§1.138)** replaced the classification that used to live here.
 * The old model was a single pure `classify(elapsedMs, travelPx)` returning
 * `PENDING` / `DRAG` / `LONG_PRESS`, which encoded two decisions that turned out
 * to be wrong for a touchscreen VNC client:
 *
 *  * **There was no way to move the pointer without pressing a button.** Any
 *    travel beyond the slop was `DRAG`, i.e. left button *down* for the whole
 *    movement. So the most common interaction on a remote desktop — look at
 *    something, put the pointer on it — dragged across the desktop instead,
 *    selecting text and moving windows on the way. Nothing failed and nothing
 *    logged; the desktop simply behaved as though the mouse button were stuck.
 *
 *  * **Long press competed with dragging.** Press-and-hold-then-move is the
 *    natural touch expression of "press the button, then drag", and it is also
 *    exactly how a hesitant drag starts. Mapping it to a right click meant a slow
 *    drag popped a context menu instead — which is the "interprets touch as
 *    long-press too aggressively" symptom. Raising the timeout could not fix it,
 *    because the two gestures are the *same* input; only giving them different
 *    inputs can.
 *
 * The state machine that replaces it is [VncTouchGesture]. This object keeps the
 * masks (still named, still asserted against the wheel bits) and the thresholds
 * that machine reads, because thresholds are the part a future edit is most
 * likely to change without understanding the interaction they protect.
 */
internal object VncPointer {

    /** RFB 3.8 §7.5.5 button mask bits for the three primary buttons. */
    const val BUTTON_MASK_NONE = 0x00
    const val BUTTON_MASK_LEFT = 0x01
    const val BUTTON_MASK_MIDDLE = 0x02
    const val BUTTON_MASK_RIGHT = 0x04

    /**
     * How long a **stationary** finger must stay down before the left button is
     * pressed and the gesture becomes a drag (§1.138).
     *
     * This is no longer the right-click delay (that gesture is a two-finger tap
     * now), so it is free to be short: it is the delay before a deliberate
     * press-and-hold starts dragging, and the user is given haptic confirmation
     * the instant it does. Android's own long-press default (~500 ms) is the right
     * value for that, because it is the interval users already associate with
     * "press and hold to grab something".
     *
     * A drag can only start from a finger that has **not** moved since it went
     * down ([VncTouchGesture]), so this timer never competes with pointer
     * movement.
     */
    const val LONG_PRESS_MS = 500L

    /**
     * How far the finger may travel before the gesture is committed to *moving
     * the pointer* rather than being a tap or a hold, in view pixels.
     *
     * Doubles as the jitter floor: below this, samples are dropped entirely
     * rather than forwarded, so a resting finger neither nudges the remote
     * pointer nor cancels the hold that is about to become a drag.
     */
    const val MOVE_SLOP_PX = 12f

    /**
     * How long two fingers may be down and still count as a two-finger **tap**
     * (right click). A longer contact is a scroll gesture the user has not moved
     * yet, or a pinch — neither of which may resolve to a click.
     */
    const val MULTI_TAP_MS = 400L

    /**
     * How far the primary finger must travel with two fingers down before the
     * gesture becomes a scroll. Below it, nothing is sent — otherwise the jitter
     * of placing two fingers would scroll the remote desktop, and the two-finger
     * tap could never be still enough to register.
     */
    const val SCROLL_SLOP_PX = 16f

    /** Euclidean distance, so every slop is a radius rather than a bounding box. */
    fun travel(fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
        val dx = toX - fromX
        val dy = toY - fromY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
