package com.fem.linexa.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.LinexaColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared progress primitives (§ UI-consistency pass). One visual language for
 * the whole app: indeterminate work is a pulsing blob ([LinexaBlobProgress] —
 * the canonical indeterminate design, extracted verbatim from the Root
 * Filesystem Analysis card), determinate work is a round-capped rail
 * ([LinexaLinearProgress]). Everything draws the same primary accent and
 * track so no screen hands-rolls a differently-styled indicator.
 */

/**
 * The canonical indeterminate indicator — HAVEN edition (concept `.spin`):
 * a ring with a `surface2` track and a primary 100° arc rotating linearly
 * every 800 ms, 3px round-capped stroke, 34dp default. It does not encode
 * progress; it signals "working".
 *
 * The pre-Haven 6-lobe blob was replaced by the ring everywhere (the concept
 * has no blob); the historical name is kept so the 17 call sites and the tests
 * that reference the shared primitive stay untouched.
 */
@Composable
fun LinexaBlobProgress(
    modifier: Modifier = Modifier,
    color: Color = LinexaColors.Primary
) {
    com.fem.linexa.ui.components.haven.HSpinner(
        modifier = modifier,
        color = color
    )
}

/**
 * Determinate when [progress] is non-null, indeterminate otherwise — the
 * caller decides by whether a concrete value exists, not via a boolean.
 * The app-wide determinate visual: a round-capped primary rail over a muted
 * track, matching the Blob's accent rather than a default Material tint.
 */
@Composable
fun LinexaLinearProgress(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    strokeHeight: Dp = 4.dp,
    color: Color = LinexaColors.Primary,
    trackColor: Color = LinexaColors.Surface3,
    strokeCap: StrokeCap = StrokeCap.Round
) {
    val sized = modifier.height(strokeHeight)
    if (progress == null) {
        LinearProgressIndicator(
            modifier = sized,
            color = color,
            trackColor = trackColor,
            strokeCap = strokeCap
        )
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = sized,
            color = color,
            trackColor = trackColor,
            strokeCap = strokeCap
        )
    }
}

/**
 * Centered full-area loading block with an optional label beneath the
 * indicator — the standard "waiting for a screen to be ready" state used
 * across home/workspace/package-manager screens. Renders the canonical
 * [LinexaBlobProgress], not a per-screen indicator.
 */
@Composable
fun LinexaLoadingState(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    label: String? = null,
    labelColor: Color = LinexaColors.TextSecondary
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LinexaBlobProgress(modifier = Modifier.size(size))
        if (label != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}
