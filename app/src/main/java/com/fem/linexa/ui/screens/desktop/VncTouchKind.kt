package com.fem.linexa.ui.screens.desktop

import android.view.MotionEvent

/**
 * 10D (Phase 10) — the touch-relevant kind of a [MotionEvent], extracted from
 * [VncSurfaceView.onTouchEvent]'s dispatch so the action→kind mapping is
 * asserted behaviourally in [VncTouchKindTest] instead of being pinned as
 * source text in DesktopInputWiringSourceTest.
 *
 * The mapping must stay total over every action a device can deliver: a kind
 * that silently fell into a wrong branch would drop the behaviour its branch
 * carries — the §1.138 defect class, where a missing ACTION_POINTER_UP
 * silently removed the right click and a missing ACTION_CANCEL left the
 * remote button held after the window took the gesture away. An action that is
 * not a touch at all (hover, scroll, stylus buttons) maps to
 * [VncTouchKind.Other], which the view refuses rather than mistranslates.
 */
enum class VncTouchKind {
    /** Primary finger down — starts a gesture. */
    Down,

    Move,

    /** A secondary finger lands. */
    SecondaryDown,

    /** A secondary finger lifts. */
    SecondaryUp,

    /** Primary finger up — ends the gesture. */
    Up,

    Cancel,

    /** Not a touch: hover, scroll, stylus buttons, … — the view must refuse it. */
    Other
}

/** Maps a [MotionEvent] `actionMasked` to its [VncTouchKind]; total by contract. */
fun vncTouchKindOf(actionMasked: Int): VncTouchKind = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> VncTouchKind.Down
    MotionEvent.ACTION_MOVE -> VncTouchKind.Move
    MotionEvent.ACTION_POINTER_DOWN -> VncTouchKind.SecondaryDown
    MotionEvent.ACTION_POINTER_UP -> VncTouchKind.SecondaryUp
    MotionEvent.ACTION_UP -> VncTouchKind.Up
    MotionEvent.ACTION_CANCEL -> VncTouchKind.Cancel
    else -> VncTouchKind.Other
}
