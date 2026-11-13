package com.fem.linexa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fem.linexa.ui.components.haven.HavenMotion
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.theme.TerminalBackground

/**
 * The ONE terminal motion spec — shared by every screen that opens a terminal
 * (UI/UX unification: Home/Activity management terminal AND the Workspace
 * terminal must look and behave exactly the same, and that starts with the
 * identical open/close transition).
 *
 *  - **Easing** — the Haven sheet curve `cubic-bezier(.32,.72,.28,1)`, the one
 *    every Haven overlay uses (documented on the Workspace rebuild). The
 *    previous split — this overlay on `FastOutSlowInEasing` while the
 *    Workspace ran the Haven curve — made the two terminals read differently.
 *  - **Duration** — one value for open AND close (close mirrors open), kept
 *    ≤250ms so the overlay still reads as a toggle the user may hit repeatedly
 *    rather than a navigation. 240ms is the midpoint of the two values the
 *    screens had drifted to (180 / 300).
 */
const val TERMINAL_OVERLAY_ANIMATION_MS = HavenMotion.NAV_PUSH_MS

/** The Haven sheet easing shared by every terminal overlay transition. */
val TerminalOverlayEasing = CubicBezierEasing(0.32f, 0.72f, 0.28f, 1f)

/**
 * The one terminal-overlay mechanism (§1.128 for Home, §1.133 for the
 * Workspace).
 *
 * This is the *layering and motion* half only, and it is deliberately generic in
 * its content: Home shows a host-side management shell, the Workspace shows one
 * of its own sessions plus a session overview, and those two have nothing else
 * in common. What they must share is the behaviour that is easy to get subtly
 * different — the overlay slides out from **under the app bar** (it starts one
 * full height above its resting place, i.e. hidden behind the bar) and leaves the
 * same way, in the same time.
 *
 * ## How to use it correctly
 *
 * The caller owns the layering, because only the caller knows its own content:
 *
 *  * Declare the screen's content **first** and this overlay **second**, as two
 *    children of one `Box`. Paint order is declaration order, so second = on top.
 *  * Never wrap the two in a `Column`. That is not an overlay: it gives the
 *    terminal a bounded height, pushes the content down by it, and re-measures
 *    the content on every open and close. That was Home's original defect
 *    (§1.128); the Workspace's was different in shape but the same in effect —
 *    the terminal was a whole *destination*, so opening it replaced the
 *    workspace content rather than covering it.
 *  * Give this overlay the **top** content inset only (see [TerminalSurface]).
 *
 * @param visible whether the overlay is open. Animated on change.
 */
@Composable
fun TerminalOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motions = LocalHavenMotions.current
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = motions.push,
            initialOffsetY = { it }
        ),
        exit = slideOutVertically(
            animationSpec = motions.push,
            targetOffsetY = { it }
        ),
        modifier = modifier,
        content = { content() }
    )
}

/**
 * The terminal overlay's *surface*: an opaque, inset-aware body for whatever
 * terminal the caller puts in it.
 *
 * Everything here is load-bearing rather than cosmetic, which is exactly why it
 * lives in one place instead of being re-stated by each overlay:
 *
 *  * **Opaque.** The screen's content is still composed and still drawn directly
 *    underneath — that is what keeps it from re-laying out — so a translucent
 *    terminal would show the workspace list through the shell output.
 *  * **[imePadding].** The soft keyboard is raised by the terminal's own input
 *    connection, so this is the surface that must shrink to stay above it (the
 *    activity is already `adjustResize`). It also keeps the extra-keys row
 *    directly above the keyboard, where a modifier row is useful.
 *  * **[navigationBarsPadding].** The overlay deliberately extends *past* the
 *    host screen's bottom-bar band — that bar stays composed so the content
 *    underneath does not move, and covering its band is what stops the
 *    extra-keys row from sitting above an empty ~80dp strip. Extending that far
 *    means this surface has to keep the bottom row of keys clear of the system
 *    navigation bar itself.
 *
 * The body is a slot rather than a `PtySession` parameter: a terminal's
 * placeholder is specific to what it is waiting for ("connecting" for a shell
 * that is still starting, versus a workspace terminal's real per-session
 * lifecycle state), and flattening those into one string would lose information
 * the Workspace deliberately surfaces.
 */
@Composable
fun TerminalSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBackground)
            .navigationBarsPadding()
            .imePadding(),
        content = content
    )
}
