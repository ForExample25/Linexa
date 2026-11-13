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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

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
 * [HBottomNav] is the 3-destination bar from the concept: 22dp icon + 10sp
 * label, selected in primary, on `bg` — no indicator, no elevation.
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
                Text(
                    text = title,
                    style = HavenType.h1,
                    color = h.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                .weight(1f, fill = false)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = HavenType.h3.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) HavenType.descMono else HavenType.small.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                    ),
                    color = if (subtitleMono) h.muted else h.text2,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                color = h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) HavenType.descMono else HavenType.desc,
                    color = if (subtitleMono) h.muted else h.text2,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
            .navSelectionPill(items.size, indicatorIndex, pillColor, pillRadius)
    ) {
        items.forEach { item ->
            val isOn = item.destination == selected
            val tint by androidx.compose.animation.animateColorAsState(
                targetValue = if (isOn) h.primary else h.muted,
                animationSpec = motions.selection,
                label = "nav-tint"
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(HavenDim.rNav))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(item.destination) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = item.label,
                    style = HavenType.navLabel,
                    color = tint,
                    maxLines = 1
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
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(index * cell, 0f),
        size = androidx.compose.ui.geometry.Size(cell, size.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
    )
}

private data class NavItem(val destination: HavenDestination, val iconRes: Int, val label: String)

private fun navItems(workspaceCount: Int?): List<NavItem> = listOf(
    NavItem(HavenDestination.WORKSPACES, com.fem.linexa.R.drawable.ic_haven_grid, "Workspaces"),
    NavItem(HavenDestination.ACTIVITY, com.fem.linexa.R.drawable.ic_haven_activity, "Activity"),
    NavItem(HavenDestination.SETTINGS, com.fem.linexa.R.drawable.ic_haven_settings, "Settings")
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
