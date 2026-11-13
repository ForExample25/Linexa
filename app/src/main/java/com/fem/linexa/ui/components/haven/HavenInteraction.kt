package com.fem.linexa.ui.components.haven

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * HAVEN press feedback — a subtle scale-down while the pointer is held.
 *
 * This is deliberately a **modifier**, not a wrapper: it derives `pressed`
 * from the exact [MutableInteractionSource] the caller already hands to
 * `clickable` / `combinedClickable`. It never installs a second pointer-input
 * detector, never intercepts the click, and cannot create a second ripple.
 *
 * Scale is applied in `graphicsLayer` (draw phase), so press feedback never
 * invalidates layout or text measurement — the row, card or grid cell keeps
 * its exact bounds while it animates.
 *
 * Rules:
 *  - pass `enabled = false` wherever the underlying clickable is disabled;
 *  - do **not** nest two `havenPressScale` modifiers on the same hit target
 *    (0.97 × 0.97 is visible as a deeper dip);
 *  - the duration comes from [LocalHavenMotions]' `press` spec, so
 *    "remove animations" collapses it to `tween(0)` with no separate path.
 *
 * The default 0.97 is the top of the design reference's 0.96–0.97 press
 * range: enough to acknowledge touch on a large card, quiet enough not to
 * wobble the surrounding grid.
 */
@Composable
fun Modifier.havenPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f
): Modifier {
    val motions = LocalHavenMotions.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = motions.press,
        label = "haven-press-scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
