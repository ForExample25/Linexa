package com.fem.linexa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.fem.linexa.ui.theme.Tints

/**
 * A minimal, self-contained sparkline: a single smooth Bezier path with no
 * axes, grid, dots, borders, labels, legend, or fill.
 *
 * [values] are the bounded rolling history (newest last); the path is
 * normalized to the draw area and smoothed with midpoint quadratics.
 *
 * Linexa UI/UX 2026 redesign: the sparkline now supports an optional area
 * fill — a vertical gradient that fades from the line color (top, alpha =
 * Tints.solid) down to transparent (bottom). This is the "card hero"
 * variant used by the Performance dashboard panels. The fill is cheap (one
 * path + one gradient brush per frame), so it stays on by default for the
 * dashboard. The previous behavior (stroke-only, no fill) is still
 * available via [fill] = false — used by the inline resource tiles on
 * Home where the sparkline is tiny.
 *
 * Performance: the Canvas redraws only when [values] changes. The
 * smoothing pass is O(n) on the value count, and the value count is
 * capped by the rolling-history window the ViewModel maintains (typically
 * 30 samples), so the redraw cost is fixed per frame.
 */
@Composable
fun MiniSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 2.5f,
    fill: Boolean = true
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val maxV = values.maxOrNull() ?: 0f
        val minV = values.minOrNull() ?: 0f
        val flat = maxV <= minV
        // Guard against a flat (all-equal) series so we don't divide by zero.
        val span = if (flat) 1f else maxV - minV

        val points = values.mapIndexed { i, v ->
            val x = if (values.size == 1) w / 2f else w * i / (values.size - 1).toFloat()
            // Invert: larger value = higher on screen. A flat (all-equal)
            // series is drawn mid-height so a constant metric (e.g. storage)
            // reads as a level line rather than an edge-aligned "zero".
            val normalized = if (flat) 0.5f else ((v - minV) / span).coerceIn(0f, 1f)
            val y = h - normalized * h
            Offset(x, y)
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val midX = (prev.x + curr.x) / 2f
                // Midpoint-quadratic smoothing keeps the curve through the
                // data points while removing the polyline kinks.
                quadraticBezierTo(prev.x, prev.y, midX, (prev.y + curr.y) / 2f)
            }
            lineTo(points.last().x, points.last().y)
        }

        if (fill) {
            // Fill area: extend the line path down to the bottom edge and
            // close it back to the start, then fill with a vertical gradient
            // that fades from the line color to transparent.
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, h)
                lineTo(points.first().x, h)
                close()
            }
            val brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = Tints.solid * 0.45f),
                    color.copy(alpha = 0f)
                )
            )
            drawPath(path = areaPath, brush = brush)
        }

        drawPath(
            path = linePath,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * MetricSparkline — a sparkline sized for a metric tile (the small resource
 * tiles on Home + the per-metric sparklines on the Performance dashboard).
 * Sets sensible defaults so call sites don't repeat the same dp/px numbers.
 */
@Composable
fun MetricSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = true
) {
    MiniSparkline(
        values = values,
        color = color,
        modifier = modifier,
        strokeWidth = 2f,
        fill = fill
    )
}
