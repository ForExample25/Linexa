package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role

/**
 * HAVEN chrome — page headers, screen scaffold, bottom navigation.
 *
 * The concept has no Material app bar: headers are content. Two shapes:
 *
 *  - [HPageHeader] — eyebrow + h1 (+ optional count) + trailing hbtn actions
 *    (Workspaces / Activity / Settings);
 *  - [HTopBar] — back circle + centered title block + trailing actions
 *    (pushed screens: Files, Packages, viewer, About…).
 *
 * [HavenScreen] paints the bg + status-bar inset + optional toast host.
 * [HBottomNav] is the 3-destination bar: a hairline-topped `bg` strip with a
 * sliding tint capsule behind the selected destination, 22dp icon + 10sp
 * label, and primary/muted selection tints.
 */

/** The Workspaces/Activity/Settings header. */
@Composable
fun HPageHeader(
    eyebrow: String?,
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    subtitle: String? = null
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f, fill = true)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = HavenType.eyebrow,
                    color = h.muted,
                    modifier = Modifier.padding(bottom = 9.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Page titles carry the screen's identity: let a longer Persian
                // translation wrap and grow the header instead of cutting it
                // off with an ellipsis. The weight keeps a trailing count
                // visible even when the title spans the available width.
                Text(
                    text = title,
                    style = HavenType.h1,
                    color = h.text,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (count != null) {
                    Text(
                        text = count,
                        style = HavenType.body.copy(
                            fontFamily = HavenType.mono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = h.muted
                    )
                }
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
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HavenDim.headerActionGap)
            ) {
                trailing()
            }
        }
    }
}

/** Pushed-screen top bar: back circle + title + subtitle + actions. */
@Composable
fun HTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    onBack: (() -> Unit)? = null,
    backContentDescription: String? = null,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            HIconCircle(
                iconRes = com.fem.linexa.R.drawable.ic_custom_arrow_back,
                contentDescription = backContentDescription,
                onClick = onBack
            )
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = HavenType.h3.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = h.text
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) HavenType.descMono else HavenType.small.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                    ),
                    color = if (subtitleMono) h.muted else h.text2,
                    modifier = Modifier.padding(top = 2.dp),
                    // Mono subtitles are paths/IDs: one line with an ellipsis is
                    // correct for technical content. Prose wraps in full.
                    maxLines = if (subtitleMono) 1 else Int.MAX_VALUE,
                    overflow = if (subtitleMono) TextOverflow.Ellipsis else TextOverflow.Clip
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                trailing()
            }
        }
    }
}

/**
 * Pushed sub-screen header: back circle, weighted title block, flex-zero
 * trailing actions. No divider — Haven separates by tone, not rules.
 */
@Composable
fun HSubHeader(
    title: String,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    onBack: () -> Unit,
    backContentDescription: String?,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HIconCircle(
            iconRes = com.fem.linexa.R.drawable.ic_haven_arrow_back,
            contentDescription = backContentDescription,
            onClick = onBack
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = HavenType.h3,
                color = h.text
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) HavenType.descMono else HavenType.desc,
                    color = if (subtitleMono) h.muted else h.text2,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = if (subtitleMono) 1 else Int.MAX_VALUE,
                    overflow = if (subtitleMono) TextOverflow.Ellipsis else TextOverflow.Clip
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HavenDim.headerActionGap),
                content = trailing
            )
        }
    }
}

/** Base Haven screen: bg fill + status bar inset. */
@Composable
fun HavenScreen(
    modifier: Modifier = Modifier,
    statusBarInsets: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HavenTheme.colors.bg)
            .then(if (statusBarInsets) Modifier.statusBarsPadding() else Modifier)
    ) {
        content()
    }
}

/** Bottom nav destinations. */
enum class HavenDestination(val route: String) {
    WORKSPACES("workspaces"),
    ACTIVITY("activity"),
    SETTINGS("settings")
}

/** The 3-destination bottom navigation. */
@Composable
fun HBottomNav(
    selected: HavenDestination,
    onSelect: (HavenDestination) -> Unit,
    modifier: Modifier = Modifier,
    workspaceCount: Int? = null
) {
    val h = HavenTheme.colors
    // 2026-11 motion pass: the selected item's tint fades between states on
    // the shared selection token (Motion.kt) instead of snapping; reduced
    // motion collapses the tween to an instant switch.
    val motions = LocalHavenMotions.current
    val items = navItems(workspaceCount)
    // One selection pill that SLIDES between destinations (reference video:
    // the indicator travels; it never disappears and reappears). The index is
    // animated as a float on the shared selection token, so rapid taps simply
    // retarget the running animation and reduced motion snaps it.
    val selectedIndex = items.indexOfFirst { it.destination == selected }.coerceAtLeast(0)
    val indicatorIndex by androidx.compose.animation.core.animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = motions.segmentSpec(),
        label = "nav-indicator"
    )
    val pillColor = h.tint
    val pillRadius = HavenDim.rNav
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(h.bg)
            // Haven has no elevation; a single hairline is the only divider
            // between the bar and the content that scrolls under it.
            .drawBehind {
                val stroke = HavenDim.hairline.toPx()
                drawLine(
                    color = h.border,
                    start = androidx.compose.ui.geometry.Offset(0f, stroke / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
            .navSelectionPill(items.size, indicatorIndex, pillColor, pillRadius)
    ) {
        items.forEach { item ->
            val isOn = item.destination == selected
            // Resolved per item in composition: a language switch re-renders the
            // whole bar immediately because the root provides the localized
            // context (and the same source drives the tablet sidebar labels).
            val label = stringResource(item.labelRes)
            val tint by androidx.compose.animation.animateColorAsState(
                targetValue = if (isOn) h.primary else h.muted,
                animationSpec = motions.selection,
                label = "nav-tint"
            )
            // The selected icon lifts a touch (6%) while the tint settles;
            // reduced motion collapses this to an instant switch. The scale is
            // a draw-phase transform, so the label never reflows.
            val iconScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isOn) 1.06f else 1f,
                animationSpec = motions.selectionSpec(),
                label = "nav-icon-scale"
            )
            val interactionSource = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(HavenDim.rNav))
                    .havenPressScale(interactionSource, pressedScale = 0.96f)
                    // selectable (not a bare clickable): TalkBack announces each
                    // destination as a tab and reports which one is selected.
                    .selectable(
                        selected = isOn,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(item.destination) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Decorative: the label below names the tab, so the icon must not
                // be announced a second time.
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
                Text(
                    text = label,
                    style = HavenType.navLabel,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Draws the sliding selection pill behind the nav items. Draw-phase only: the
 * animated index is read inside [drawBehind], so the slide never re-composes
 * or re-measures the bar.
 */
private fun Modifier.navSelectionPill(
    count: Int,
    index: Float,
    color: androidx.compose.ui.graphics.Color,
    radius: androidx.compose.ui.unit.Dp
): Modifier = this.drawBehind {
    if (count <= 0) return@drawBehind
    val cell = size.width / count
    val r = radius.toPx()
    // Inset the capsule inside its cell (and vertically) so the selection
    // reads as a floating pill with breathing room instead of a full-height
    // column fill. The horizontal inset is capped for very narrow bars.
    val insetX = 6.dp.toPx().coerceAtMost(cell / 4f)
    val insetY = 4.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(index * cell + insetX, insetY),
        size = androidx.compose.ui.geometry.Size(
            cell - insetX * 2f,
            size.height - insetY * 2f
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
    )
}

private data class NavItem(
    val destination: HavenDestination,
    val iconRes: Int,
    @androidx.annotation.StringRes val labelRes: Int
)

private fun navItems(workspaceCount: Int?): List<NavItem> = listOf(
    NavItem(HavenDestination.WORKSPACES, R.drawable.ic_haven_grid, R.string.haven_nav_workspaces),
    NavItem(HavenDestination.ACTIVITY, R.drawable.ic_haven_activity, R.string.haven_nav_activity),
    NavItem(HavenDestination.SETTINGS, R.drawable.ic_haven_settings, R.string.haven_nav_settings)
)

/** `.footbrand` — the quiet version footer. */
@Composable
fun HFootBrand(versionLine: String, modifier: Modifier = Modifier) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colorResource(com.fem.linexa.R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(com.fem.linexa.R.drawable.ic_linexa_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = versionLine,
            style = HavenType.small,
            color = h.muted,
            maxLines = 1
        )
    }
}
