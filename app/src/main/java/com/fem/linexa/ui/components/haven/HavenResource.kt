package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * Shared building blocks for resource readouts — used by the global Activity tab
 * and the workspace Sessions tab so both draw a metric the same way.
 *
 * Design contract (same as the rest of Haven): tone-stepped surfaces, no borders,
 * no shadows. A metric whose value is unknown is drawn as an *empty* rail with a
 * dash — never as an indeterminate sweep (that reads as "loading") and never as
 * a zero-length fill (that reads as "0%").
 */

/**
 * A titled card for one measurement scope (Device / Linexa app / Workspace).
 *
 * @param status short state word shown top-end ("Running", "Measuring…"); null hides it.
 * @param caption one quiet line under the title naming exactly what is measured,
 *   so the scope is never ambiguous.
 */
@Composable
fun HResourceCard(
    title: String,
    iconRes: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
    status: String? = null,
    statusColor: Color = HavenTheme.colors.muted,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(HavenDim.cardPad)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leading != null) {
                leading()
            } else {
                Box(
                    modifier = Modifier
                        .size(HavenDim.ricSm)
                        .clip(RoundedCornerShape(HavenDim.rRicSm))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HavenType.rowTitle,
                    color = h.text
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = HavenType.small,
                        color = h.muted,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            if (status != null) {
                HStatus(text = status, color = statusColor)
            }
        }
        Column(modifier = Modifier.padding(top = 16.dp), content = content)
    }
}

/**
 * One metric: label, value, and a usage rail.
 *
 * @param fraction 0..1 fill, or null when the metric is unknown (empty rail).
 * @param detail optional line under the rail ("of 11.0 GB", "approximate").
 * @param prominent larger value type — used for the workspace card, which is the
 *   primary readout on the Sessions tab.
 */
@Composable
fun HResourceMetric(
    label: String,
    value: String,
    fraction: Float?,
    accent: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    prominent: Boolean = false
) {
    val h = HavenTheme.colors
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = HavenType.small,
            color = h.muted,
            maxLines = 2
        )
        Text(
            text = value,
            style = if (prominent) HavenType.titleLg else HavenType.title,
            color = h.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Box(modifier = Modifier.padding(top = 8.dp)) {
            if (fraction != null) {
                HProgress(fraction = fraction, color = accent)
            } else {
                HEmptyRail()
            }
        }
        if (detail != null) {
            Text(
                text = detail,
                style = HavenType.small,
                color = h.muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Two metrics side by side with equal weight — the CPU | RAM pair of every scope card. */
@Composable
fun HMetricPair(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.weight(1f)) { start() }
        Box(modifier = Modifier.weight(1f)) { end() }
    }
}

/** The "unknown" rail: same geometry as [HProgress], nothing filled. */
@Composable
fun HEmptyRail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HavenDim.progressH)
            .clip(RoundedCornerShape(50))
            .background(HavenTheme.colors.surface2)
    )
}

/** Small CPU/RAM readout used inside list rows: tiny label above a mono value. */
@Composable
fun HMiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = HavenType.section,
            color = h.muted,
            maxLines = 2
        )
        Text(
            text = value,
            style = HavenType.mono12.copy(fontWeight = FontWeight.SemiBold),
            color = h.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Value formatting shared by every resource readout. `—` is the only "unknown" glyph. */
object ResourceText {
    const val UNKNOWN = "—"

    fun percent(value: Float?): String =
        com.fem.linexa.core.format.CpuPercentFormatter.format(value)

    fun percentPrecise(value: Float?): String = percent(value)

    fun bytes(value: Long?, approximate: Boolean = false): String {
        if (value == null) return UNKNOWN
        val text = com.fem.linexa.core.format.FileSizeFormatter.format(value)
        return if (approximate) "~$text" else text
    }

    fun kb(value: Long?, approximate: Boolean = false): String =
        if (value == null) UNKNOWN else bytes(value * 1024L, approximate)
}
