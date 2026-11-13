package com.fem.linexa.service.runtime.features

/**
 * Desktop Touch work (2026-08) — how finger touches map onto the remote
 * desktop's pointer, for the VNC run mode's embedded viewer.
 *
 * The two modes answer "where does this click mean?" differently, and both are
 * legitimate pointer models that mature mobile VNC clients ship side by side
 * (the Tiny Container frontend's AVNC-based viewer exposes the same pair):
 *
 * - [RELATIVE] — touchpad-style. The finger's *movement* moves the remote
 *   cursor; its position is ignored. Precision-oriented: the finger never
 *   covers the thing being clicked, and small targets are reachable by moving
 *   the cursor in small strokes. This is Linexa's historical behaviour
 *   (§1.138/§1.139) and stays the default, so the upgrade cannot change how
 *   anyone's desktop feels.
 *
 * - [DIRECT] — absolute. The finger's *position* is where the click means
 *   (mapped view→frame through the current zoom by the viewer): tap = click
 *   where you tapped, drag = press-and-move, long-press = right button,
 *   two-finger gestures identical to [RELATIVE]. The model most users reach
 *   for first on a phone.
 *
 * Consumer: [com.fem.linexa.ui.screens.desktop.VncSurfaceView] selects the
 * gesture machine ([com.fem.linexa.ui.screens.desktop.VncTouchGesture] vs
 * [com.fem.linexa.ui.screens.desktop.VncDirectGesture]) from this value. It is
 * read live per touch event, so switching takes effect immediately — the only
 * desktop setting that does not wait for the next start, because it changes
 * nothing on the server side.
 */
enum class DesktopTouchMode(
    /** Stable persisted id — see [FeatureSettings.KEY_DESKTOP_TOUCH_MODE]. */
    val id: String
) {
    RELATIVE("relative"),
    DIRECT("direct");

    companion object {
        /** The default, resolved fail-open exactly like the other desktop ids. */
        val DEFAULT = RELATIVE

        /** Fail-open parse: an unknown or absent id resolves to [DEFAULT]. */
        fun fromId(raw: String?): DesktopTouchMode =
            entries.firstOrNull { it.id == raw?.trim() } ?: DEFAULT
    }
}
