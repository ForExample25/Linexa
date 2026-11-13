package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * HAVEN surfaces — cards, list rows, expanders, sheets, dialogs, menus,
 * breadcrumbs, glyphs (concept `.card/.row/.ric/.panel/.sheet/.dlg/.menu/
 * .crumbs/.glyph/.banner`).
 *
 * Every surface reads [HavenTheme.colors] and [HavenDim]: tone-stepped
 * backgrounds, 20/14/24/999 radii, hairline separators — no borders, no
 * shadows.
 */

/** `.card` — the 20px-radius surface card. `.tight` variant has no padding. */
@Composable
fun HCard(
    modifier: Modifier = Modifier,
    tight: Boolean = false,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(color ?: h.surface)
            .then(if (tight) Modifier else Modifier.padding(HavenDim.cardPad)),
        content = content
    )
}

/** Icon container of a row (`.ric`) — 38px rounded-12 tile. */
@Composable
fun HRic(
    iconRes: Int,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    danger: Boolean = false,
    size: Dp = HavenDim.ric,
    iconSize: Dp = HavenDim.ricIcon
) {
    val h = HavenTheme.colors
    val (bg, fg) = when {
        danger -> h.redTint to h.red
        tinted -> h.tint to h.primary
        else -> h.surface2 to h.text2
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(HavenDim.rRic))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** `.row` — the 62px list row with hairline handling provided by [HCard] callers.
 *
 *  Trailing-anchor contract (UI/UX alignment fix): the title/description
 *  column fills ALL width left over between the leading content and the
 *  trailing control, so a chevron, three-dot menu or any end-of-row action is
 *  anchored to the row's end INDEPENDENT of the text length. The earlier
 *  `weight(1f, fill = false)` let the column hug its text, which parked the
 *  trailing control right after the last glyph (visibly misaligned across
 *  rows whose text lengths differ). Titles/descriptions ellipsize, so a long
 *  line can never push the trailing control out of the row. */
@Composable
fun HRow(
    title: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionMono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minRow: Dp = HavenDim.rowMin,
    titleColor: Color? = null,
    /**
     * 2026-09-01: visual + interaction gate for LOCKED rows (e.g. the distro
     * catalog's not-yet-installable entries). False dims the whole row and
     * makes the click a no-op while keeping the row's layout identical, so
     * locked entries stay visible in place instead of disappearing.
     */
    enabled: Boolean = true
) {
    val h = HavenTheme.colors
    val effectiveOnClick = onClick?.takeIf { enabled }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minRow)
            .then(
                if (effectiveOnClick != null) {
                    Modifier
                        .havenPressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClick = effectiveOnClick
                        )
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = HavenType.rowTitle,
                color = titleColor ?: h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    text = description,
                    style = if (descriptionMono) HavenType.descMono else HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) trailing()
    }
}

// description top margin is applied inline via Modifier.padding(top = 3.dp)

/** `.chev` — the muted chevron used as a row affordance. */
@Composable
fun HChevron(modifier: Modifier = Modifier, expanded: Boolean = false) {
    Icon(
        painter = painterResource(com.fem.linexa.R.drawable.ic_haven_chevron),
        contentDescription = null,
        tint = HavenTheme.colors.muted,
        modifier = modifier
            .size(16.dp)
            .then(if (expanded) Modifier.rotate(90f) else Modifier)
    )
}

/** `.panel` — animated expandable panel. */
@Composable
fun HExpandablePanel(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val motions = LocalHavenMotions.current
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = motions.enterSpec()) +
            fadeIn(animationSpec = motions.enter),
        exit = shrinkVertically(animationSpec = motions.exitSpec()) +
            fadeOut(animationSpec = motions.exit),
        modifier = modifier
    ) {
        Column(content = content)
    }
}

/** Haven modal sheet — 24px top radius, grip, optional header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    /**
     * `false` gives the sheet a compact first state the user can drag up to
     * full height (and back down / away); the body then scrolls so tall
     * content stays reachable. Default `true` keeps every existing caller's
     * single-height behavior unchanged.
     */
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = h.surface,
        contentColor = h.text,
        shape = RoundedCornerShape(topStart = HavenDim.rSheet, topEnd = HavenDim.rSheet),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(h.text2.copy(alpha = 0.28f))
            )
        }
    ) {
        Column(
            modifier = modifier
                .then(
                    if (skipPartiallyExpanded) Modifier
                    else Modifier.verticalScroll(rememberScrollState())
                )
                .padding(bottom = 30.dp)
        ) {
            if (title != null || subtitle != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = HavenType.titleLg.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                color = h.text
                            )
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = HavenType.desc,
                                color = h.text2,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    HIconGhost(
                        iconRes = com.fem.linexa.R.drawable.ic_haven_close,
                        contentDescription = null,
                        onClick = onDismiss
                    )
                }
            }
            content()
        }
    }
}

/** `.dlg` — the centered 24px-radius dialog.
 *
 *  Call-site contract: `content` (dialog body) and `actions` (bottom-right
 *  button row) are the last two parameters, so a trailing lambda binds to
 *  `actions`, NOT `content`. Button-only dialogs may use the trailing
 *  lambda; any call site with real body content MUST pass `content = { … }`
 *  and `actions = { … }` by name (the file manager's dialogs are the
 *  reference) — a body passed positionally renders inside the actions Row
 *  and collapses its children to zero width. */
@Composable
fun HDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    dismissLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val h = HavenTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HavenDim.rDialog))
                .background(h.surface)
                .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 20.dp)
        ) {
            Text(
                text = title,
                style = HavenType.titleLg.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), letterSpacing = com.fem.linexa.ui.theme.haven.em(-0.01f)),
                color = h.text
            )
            if (body != null) {
                Text(
                    text = body,
                    style = HavenType.desc.copy(lineHeight = HavenType.desc.lineHeight * 1.18f),
                    color = h.text2,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            content()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (body != null) 18.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/** `.menu` — the anchored dropdown menu. */
@Composable
fun HMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    items: List<HMenuItem>,
    alignEnd: Boolean = true
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    // The popup stays composed until its EXIT has finished (the old early
    // return removed the window on the first frame, so menus could never
    // animate out). currentState flips to false only after the exit tween.
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState) return
    val density = LocalDensity.current
    val positionProvider = remember(density, alignEnd) {
        HavenMenuPositionProvider(
            gapPx = with(density) { 6.dp.roundToPx() },
            marginPx = with(density) { 8.dp.roundToPx() },
            alignEnd = alignEnd
        )
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            // One coherent transform: the menu fades while scaling out of the
            // anchor corner. The old version stacked opacity + scale + a
            // vertical slide, three competing signals for one small surface.
            enter = fadeIn(motions.enter) +
                scaleIn(
                    animationSpec = motions.enterSpec(),
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
                ),
            exit = fadeOut(motions.exit) +
                scaleOut(
                    animationSpec = motions.exitSpec(),
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
                )
        ) {
            Column(
                modifier = modifier
                    .width(172.dp)
                    .clip(RoundedCornerShape(HavenDim.rMenu))
                    .background(h.surface2)
                    .padding(6.dp)
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable {
                                onDismiss()
                                item.onClick()
                            }
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = null,
                            tint = if (item.danger) h.red else h.text2,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = item.label,
                            style = HavenType.body.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)
                            ),
                            color = if (item.danger) h.red else h.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Places a menu next to its anchor and keeps it fully inside the window.
 *
 * Preference: directly below the anchor, aligned to its end (or start) edge;
 * if that would run past the bottom edge it flips above the anchor; the
 * horizontal position is then clamped to the window with [marginPx] breathing
 * room. Pure arithmetic — no allocation beyond the returned [IntOffset].
 */
internal class HavenMenuPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
    private val alignEnd: Boolean
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val desiredX = if (alignEnd) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = desiredX.coerceIn(marginPx, maxX)
        val below = anchorBounds.bottom + gapPx
        val fitsBelow = below + popupContentSize.height <= windowSize.height - marginPx
        val y = if (fitsBelow) {
            below
        } else {
            (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        }
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

data class HMenuItem(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val danger: Boolean = false
)

/** `.crumbs` — breadcrumb chips. */
@Composable
fun HCrumbs(
    crumbs: List<String>,
    current: Int,
    modifier: Modifier = Modifier,
    onCrumbClick: (Int) -> Unit = {}
) {
    val h = HavenTheme.colors
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(crumbs.size) { index ->
            val isOn = index == current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isOn) h.tint else h.surface2)
                        .clickable(enabled = !isOn) { onCrumbClick(index) }
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = crumbs[index],
                        style = HavenType.small.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isOn) h.primary else h.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index < crumbs.lastIndex) {
                    Text(
                        text = "›",
                        style = HavenType.small,
                        color = h.muted,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/** `.glyph` — the distro identity tile. */
@Composable
fun HGlyph(
    letter: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = HavenDim.glyph,
    darkText: Boolean = false
) {
    // Concept `.glyph` type scale (sp, NOT dp-derived): g52→20sp, g44→17sp,
    // g36→14sp, g28→12sp; two-letter marks (e.g. "AR") scale down one step.
    val baseSp = when {
        size >= HavenDim.glyphLg -> 20f
        size >= HavenDim.glyph -> 17f
        size >= HavenDim.glyphSm -> 14f
        else -> 12f
    }
    val fontSize = if (letter.length > 1) baseSp * 0.72f else baseSp
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size >= HavenDim.glyph) HavenDim.rGlyph else if (size >= HavenDim.glyphSm) HavenDim.rGlyphSm else HavenDim.rGlyphXs))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            style = HavenType.title.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(fontSize, androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = FontWeight.Bold,
                letterSpacing = com.fem.linexa.ui.theme.haven.em(-0.02f)
            ),
            color = if (darkText) Color(0xFF1A1C1E) else Color.White,
            maxLines = 1
        )
    }
}

/** `.banner` — the tinted What's New banner. */
@Composable
fun HBanner(
    modifier: Modifier = Modifier,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(color ?: h.tint)
            .padding(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 14.dp),
        content = content
    )
}

/** `.mono-block` — the mono log/trace block.
 *
 *  `maxLines`/`overflow` are optional refinements for confined surfaces
 *  (dialogs) whose height must not breathe as the traced text changes; the
 *  defaults leave every existing call site pixel-identical (install flow's
 *  full-width cards keep their natural wrapping). */
@Composable
fun HMonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val h = HavenTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rMonoBlock))
            .background(color ?: h.surface2)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            style = HavenType.monoBlock,
            color = h.text2,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
