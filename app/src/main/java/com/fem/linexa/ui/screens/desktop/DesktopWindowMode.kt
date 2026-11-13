package com.fem.linexa.ui.screens.desktop

/**
 * UI/UX Phase 8 (§1.138) — what the *window* must look like for a given desktop
 * state, as a value rather than as branches inside a `DisposableEffect`.
 *
 * ## What was wrong
 *
 * §1.125 fixed the leak of hidden system bars (the effect became a
 * `DisposableEffect` that shows them again `onDispose`), but the desktop was
 * never made **landscape**. The X framebuffer has been landscape since §1.125
 * ([DesktopGeometry] takes the long edge as the width), and the viewer
 * contain-scales, so on a phone held in portrait the desktop was drawn into a
 * portrait window as a letterboxed strip across the middle: roughly 20% of the
 * screen used, and the rest black. Nothing failed — it simply looked like a
 * broken thumbnail of a desktop.
 *
 * ## The rules
 *
 * **(a) Orientation is requested for the Desktop *surface*, never for the app.**
 * The activity is shared by every screen (one `ComponentActivity`, one nav
 * graph), so an orientation set on entry has to be *restored* on exit rather than
 * cleared — [restoreOrientation] carries the value the activity had before, which
 * is why this is a pair of decisions and not a constant. Setting
 * `SCREEN_ORIENTATION_UNSPECIFIED` on the way out would have looked identical in
 * every manual test and quietly overridden a per-activity orientation set
 * anywhere else in the app.
 *
 * **(b) Landscape is `USER_LANDSCAPE`, not `LANDSCAPE`.** The plain constant pins
 * one specific rotation, so a user holding the phone the other way gets an
 * upside-down desktop and cannot turn it round. `USER_LANDSCAPE` allows both
 * landscape rotations and honours the user's own rotation preference within them.
 *
 * **(c) The window follows the *session*, not the screen.** Only
 * [DesktopUiState.Running] is fullscreen-landscape: the placeholder states are
 * ordinary Compose UI with an app bar, a status chip and buttons, and forcing
 * *those* into landscape would rotate the phone to show a "Stopped" message. This
 * is also what makes leaving mid-session safe — the mode is derived, so any state
 * that is not `Running` restores the window even before the screen is disposed.
 *
 * **(d) Transient bars, not sticky-hidden ones.** A fullscreen surface with the
 * bars *gone* has no way back on a gesture-navigation device; with
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` a swipe brings them back over the
 * desktop without ending the session.
 *
 * Keeping this as a value means the two states are asserted in a JVM test. The
 * pre-Phase-8 version was an `if (isRunning) hide() else show()` inside the
 * effect, which is exactly the shape that let the missing-restore bug live
 * through §1.114 — the code read as though it handled both directions.
 */
internal data class DesktopWindowMode(
    /** Whether the system status/navigation bars are hidden. */
    val hideSystemBars: Boolean,
    /** Whether the desktop surface requests a landscape orientation. */
    val landscape: Boolean
) {
    companion object {

        /** The mode for [state]; only a live session takes over the window. */
        fun forState(state: DesktopUiState): DesktopWindowMode =
            if (state is DesktopUiState.Running) {
                DesktopWindowMode(hideSystemBars = true, landscape = true)
            } else {
                DesktopWindowMode(hideSystemBars = false, landscape = false)
            }

        /**
         * `ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE` — both landscape
         * rotations, honouring the user's rotation preference. Named here rather
         * than referenced in the screen so the choice is testable without an
         * Android framework class on the JVM classpath.
         */
        const val ORIENTATION_USER_LANDSCAPE = 11

        /**
         * `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`, the value an activity
         * that has never requested an orientation reports. Only ever *restored*,
         * never assumed — see rule (a).
         */
        const val ORIENTATION_UNSPECIFIED = -1
    }
}
