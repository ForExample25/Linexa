package com.fem.linexa.ui.screens.desktop

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 10D — the action→kind mapping is behaviourally total: every touch action the
 * platform can deliver maps to the kind whose branch implements it in
 * [VncSurfaceView], and every non-touch action lands in [VncTouchKind.Other] —
 * which the view refuses via `super.onTouchEvent(event)` — rather than being
 * mistranslated into a touch.
 *
 * The defect class this guards (§1.138): a missing ACTION_POINTER_UP silently
 * removes the right click; a missing ACTION_CANCEL leaves the remote button
 * held after the window takes the gesture away. The mapping used to be pinned
 * as source text in DesktopInputWiringSourceTest; it is now one named function
 * a test can drive. MotionEvent's action constants are compile-time values, so
 * they are safe to reference on the JVM (§1.124(a)).
 */
class VncTouchKindTest {

    @Test
    fun everyTouchActionMapsToItsOwnKind() {
        assertEquals(VncTouchKind.Down, vncTouchKindOf(MotionEvent.ACTION_DOWN))
        assertEquals(VncTouchKind.Move, vncTouchKindOf(MotionEvent.ACTION_MOVE))
        assertEquals(VncTouchKind.SecondaryDown, vncTouchKindOf(MotionEvent.ACTION_POINTER_DOWN))
        assertEquals(VncTouchKind.SecondaryUp, vncTouchKindOf(MotionEvent.ACTION_POINTER_UP))
        assertEquals(VncTouchKind.Up, vncTouchKindOf(MotionEvent.ACTION_UP))
        assertEquals(VncTouchKind.Cancel, vncTouchKindOf(MotionEvent.ACTION_CANCEL))
    }

    @Test
    fun nonTouchActionsAreRefusedNotMistranslated() {
        // Hover, scroll and stylus-button actions belong to no touch branch;
        // landing in `Other` is the only behaviour the view accepts for them.
        listOf(
            MotionEvent.ACTION_OUTSIDE,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_SCROLL,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE
        ).forEach { action ->
            assertEquals(
                "MotionEvent action $action must not be translated as a touch",
                VncTouchKind.Other,
                vncTouchKindOf(action)
            )
        }
    }
}
