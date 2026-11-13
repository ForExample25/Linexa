package com.fem.linexa.ui.components.haven

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.haven.HavenColors
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * HAVEN data & status primitives — dots, statuses, pills, badges, chips,
 * bars, spinners and sparklines (concept sections `.dot/.st/.pill/.badge/
 * .chip/.sbar/.prog/.spin/.spark/.corebar/.tile`).
 *
 * Everything here reads [HavenTheme.colors]; nothing hard-codes a color.
 */

/** The 7px state dot (`.dot`). */
@Composable
fun HDot(color: Color, modifier: Modifier = Modifier, size: Float = 7f, pulsing: Boolean = false) {
    val pulse = if (pulsing) rememberPulse() else 1f
    Canvas(modifier = modifier.size(size.dp)) {
        drawCircle(
            color = color.copy(alpha = 0.45f + 0.55f * pulse),
            radius = this.size.minDimension / 2f * (0.8f + 0.2f * pulse)
        )
    }
}

/** `.st` — a status read as dot + one quiet word. */
@Composable
fun HStatus(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    coloredText: Boolean = false,
    pulsing: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HDot(color = color, size = 6.5f, pulsing = pulsing)
        Text(
            text = text,
            style = HavenType.desc.copy(fontWeight = FontWeight.Medium),
            color = if (coloredText) color else HavenTheme.colors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** `.pill` — the 26px rounded status chip. */
@Composable
fun HPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HavenTheme.colors.muted,
    container: Color = HavenTheme.colors.surface2,
    withDot: Boolean = true
) {
    Row(
        modifier = modifier
            .height(HavenDim.pillH)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (withDot) HDot(color = color, size = 6f)
        Text(
            text = text,
            style = HavenType.small.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** `.chip` — 32px selection chip; `.on` renders in primary tint. */
@Composable
fun HChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val h = HavenTheme.colors
    // 2026-11 motion pass: the chip's on/off colors cross-fade on the shared
    // selection token (Motion.kt) instead of snapping; reduced motion
    // collapses the tween to an instant switch.
    val motions = LocalHavenMotions.current
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) h.tint else h.surface2,
        animationSpec = motions.selection,
        label = "chip-bg"
    )
    val fg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) h.primary else h.text2,
        animationSpec = motions.selection,
        label = "chip-fg"
    )
    // Press feedback on the shared token (same modifier tiles/cards use).
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(HavenDim.chipH)
            .havenPressScale(interaction, enabled = enabled)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = HavenType.desc.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}

/** Category chip — the NEW/IMPROVED/FIXED/CHANGED tags of What's New. */
enum class HavenCatKind { NEW, IMPROVED, FIXED, CHANGED }

/** Maps a domain [com.fem.linexa.domain.whatsnew.ChangeCategory] onto the chip kinds. */
fun HCatKindOf(category: com.fem.linexa.domain.whatsnew.ChangeCategory): HavenCatKind = when (category) {
    com.fem.linexa.domain.whatsnew.ChangeCategory.NEW -> HavenCatKind.NEW
    com.fem.linexa.domain.whatsnew.ChangeCategory.IMPROVED -> HavenCatKind.IMPROVED
    com.fem.linexa.domain.whatsnew.ChangeCategory.FIXED -> HavenCatKind.FIXED
    com.fem.linexa.domain.whatsnew.ChangeCategory.CHANGED -> HavenCatKind.CHANGED
}

@Composable
fun HCatChip(kind: HavenCatKind, modifier: Modifier = Modifier) {
    val h = HavenTheme.colors
    val (bg, fg, label) = when (kind) {
        HavenCatKind.NEW -> Triple(h.tint, h.primary, "NEW")
        HavenCatKind.IMPROVED -> Triple(h.blueTint, h.blue, "IMPROVED")
        HavenCatKind.FIXED -> Triple(h.amberTint, h.amber, "FIXED")
        HavenCatKind.CHANGED -> Triple(if (h.isDark) Color.White.copy(alpha = 0.07f) else Color(0xFFECEAE5), h.text2, "CHANGED")
    }
    Box(
        modifier = modifier
            .height(HavenDim.chipCatH)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = HavenType.small.copy(fontWeight = FontWeight.Bold, letterSpacing = com.fem.linexa.ui.theme.haven.em(0.08f)),
            color = fg,
            fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
    }
}

/** `.badge` — the mono UP / DEL / INSTALL / RUN verbs. */
enum class HavenBadgeKind { PRIMARY, DANGER }

@Composable
fun HBadge(
    label: String,
    kind: HavenBadgeKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val h = HavenTheme.colors
    val (bg, fg) = when {
        checked -> h.tint to h.primary
        kind == HavenBadgeKind.PRIMARY -> h.tint to h.primary
        else -> h.redTint to h.red
    }
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(HavenDim.rBadge))
            .background(if (enabled) bg else bg.copy(alpha = 0.32f))
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = HavenType.small.copy(
                fontFamily = HavenType.mono,
                fontWeight = FontWeight.Bold,
                letterSpacing = com.fem.linexa.ui.theme.haven.em(0.04f)
            ),
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}

/** `.sect` — section header: 12.5px semibold uppercase, optional trailing. */
@Composable
fun HSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) 0.dp else HavenDim.sectionTop, bottom = if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = HavenType.section,
            color = HavenTheme.colors.muted
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** `.sbar` — the 3px storage bar. */
@Composable
fun HStorageBar(fraction: Float, modifier: Modifier = Modifier, color: Color = HavenTheme.colors.primary) {
    val h = HavenTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HavenDim.storageBarH)
            .clip(RoundedCornerShape(50))
            .background(h.surface2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(HavenDim.storageBarH)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** `.prog` — the 6px progress rail (`.big` = 8px), animated like the
 *  concept (`transition: width .3s`); `fraction == null` renders the
 *  indeterminate sweep (`.ind`) instead of an empty track. */
@Composable
fun HProgress(
    fraction: Float?,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    color: Color = HavenTheme.colors.primary
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val height = if (big) HavenDim.progressBigH else HavenDim.progressH
    val animated = animateFloatAsState(
        targetValue = fraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = motions.progress,
        label = "haven-prog"
    ).value
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(h.surface2)
    ) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(height)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        } else {
            // `.ind` — a ~38% segment sweeping the rail end to end. Reduced
            // motion renders the same segment at the rail's start, where its
            // meaning ("work in progress") survives without the sweep.
            val sweep by if (motions.animationsEnabled) {
                val infinite = rememberInfiniteTransition(label = "haven-prog-ind")
                infinite.animateFloat(
                    initialValue = -0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(HavenMotion.PULSE_MS, easing = LinearEasing)
                    ),
                    label = "haven-prog-ind-x"
                )
            } else {
                remember { androidx.compose.runtime.mutableStateOf(0f) }
            }
            Box(
                modifier = Modifier
                    .offset(x = androidx.compose.ui.unit.Dp(sweep * 240f))
                    .fillMaxWidth(0.38f)
                    .height(height)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

/**
 * `.spin` — the indeterminate ring spinner, rebuilt to the concept's exact
 * geometry: a full `var(--surface2)` ring with the top-border quarter (~90°)
 * in `var(--primary)`, the whole ring rotating at 0.8s linear (`@keyframes
 * rot`). `.spin.sm` is the 18px/2px variant the concept uses inline
 * ("Starting desktop…"). The one indeterminate spinner in the design system —
 * Material's `CircularProgressIndicator` (fading variable arc) is a different
 * design and does not appear on Haven surfaces.
 */
@Composable
fun HSpinner(
    modifier: Modifier = Modifier,
    size: Int = 34,
    color: Color = HavenTheme.colors.primary,
    small: Boolean = false
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val resolvedSize = if (small) 18 else size
    val rotation by if (motions.animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "haven-spin")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = HavenMotion.SPIN_MS, easing = LinearEasing)
            ),
            label = "haven-spin-angle"
        )
    } else {
        remember { androidx.compose.runtime.mutableStateOf(0f) }
    }
    Canvas(modifier = modifier.size(resolvedSize.dp)) {
        val stroke = Stroke(width = (if (resolvedSize > 24) 3f else 2f).dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(
            color = h.surface2,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
            topLeft = Offset(inset, inset),
            size = arcSize
        )
        // The top-border quarter — CSS `border-top-color` spans 90° — carried
        // around by the rotation. Start at -90f so the arc leads from the top.
        drawArc(
            color = color,
            startAngle = -90f + rotation,
            sweepAngle = 90f,
            useCenter = false,
            style = stroke,
            topLeft = Offset(inset, inset),
            size = arcSize
        )
    }
}

/** `.blob` — the pulsing 10px activity dot. */
@Composable
fun HBlob(modifier: Modifier = Modifier, color: Color = HavenTheme.colors.primary) {
    val pulse = rememberPulse()
    Canvas(modifier = modifier.size(10.dp)) {
        drawCircle(color = color.copy(alpha = 0.4f + 0.6f * pulse))
    }
}

/** `.spark` — the polyline sparkline. */
@Composable
fun HSpark(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = HavenTheme.colors.primary,
    height: Int = 34
) {
    if (points.size < 2) {
        Spacer(modifier = modifier.fillMaxWidth().height(height.dp))
        return
    }
    Canvas(modifier = modifier.fillMaxWidth().height(height.dp)) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (points.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, p ->
            val x = i * step
            val y = size.height - 2f - ((p - min) / range) * (size.height - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        )
    }
}

/** `.tile` — the 20px metric tile (label / big mono value / sub / spark). */
@Composable
fun HTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    history: List<Float> = emptyList(),
    accent: Color? = null
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(HavenDim.rTile))
            .background(h.surface)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = HavenType.tileLabel,
            color = h.muted,
            maxLines = 1
        )
        Text(
            text = value,
            style = HavenType.num22,
            color = h.text,
            modifier = Modifier.padding(top = 9.dp, bottom = 2.dp),
            maxLines = 1
        )
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                style = HavenType.small,
                color = h.muted,
                maxLines = 1
            )
        }
        if (history.size >= 2) {
            HSpark(
                points = history,
                color = accent ?: h.primary,
                height = 26,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** `.corebar` — one per-core utilization line. */
@Composable
fun HCoreBar(label: String, fraction: Float, modifier: Modifier = Modifier) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = HavenType.mono12,
            color = h.muted,
            modifier = Modifier.width(22.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(HavenDim.coreBarH)
                .clip(RoundedCornerShape(50))
                .background(h.surface2)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(HavenDim.coreBarH)
                    .clip(RoundedCornerShape(50))
                    .background(h.primary.copy(alpha = 0.85f))
            )
        }
        Text(
            text = "${(fraction * 100).toInt()}%",
            style = HavenType.mono12,
            color = h.text2,
            modifier = Modifier.width(30.dp),
            textAlign = TextAlign.End
        )
    }
}

/** `.fact` — one icon-labeled fact row (Install flow, dialogs). */
@Composable
fun HFactRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = HavenTheme.colors.text
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        icon()
        Text(
            text = label,
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = HavenType.num14,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** `.vrow` — a label:value hairline row (Performance, Device). */
@Composable
fun HVRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = HavenTheme.colors.text,
    divider: Boolean = true,
    labelContent: (@Composable () -> Unit)? = null
) {
    val h = HavenTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (labelContent != null) labelContent() else {
                Text(
                    text = label,
                    style = HavenType.desc.copy(fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = h.text2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = value,
                    style = HavenType.num14,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HavenDim.hairline)
                    .background(h.border)
            )
        }
    }
}

/** Hairline separator. */
@Composable
fun HHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(HavenDim.hairline)
            .background(HavenTheme.colors.border)
    )
}

/** Pulsing 0..1 wave shared by HDot/HBlob. */
@Composable
fun rememberPulse(periodMs: Int = HavenMotion.PULSE_MS): Float {
    val motions = LocalHavenMotions.current
    val raw by if (motions.animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "haven-pulse")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs),
                repeatMode = RepeatMode.Reverse
            ),
            label = "haven-pulse-value"
        )
    } else {
        remember { androidx.compose.runtime.mutableStateOf(1f) }
    }
    return raw
}
