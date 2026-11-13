package com.fem.linexa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * One app bar for the whole app — HAVEN chrome.
 *
 * The Haven rebuild keeps the [GlobalAppBarState] API (all pushed screens
 * construct it) but the rendering is now the concept's header: a 40dp circular
 * back button, a weight-bounded title block (20sp semibold + optional quiet
 * subtitle — long workspace names ellipsize without breaking the layout), and
 * trailing actions drawn as quiet 40dp circles (selected actions take the
 * primary tint). No Material `TopAppBar`, no elevation — the header sits on
 * the page background.
 */
data class TopBarAction(
    val iconRes: Int,
    val contentDescription: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val tint: Color? = null,
    // A selected action is drawn with the primary accent so the current
    // screen's own action (Terminal on the Terminal screen, Desktop on the
    // Desktop screen) reads as active within the shared bar.
    val selected: Boolean = false
)

@Composable
fun LinexaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIconRes: Int = R.drawable.ic_custom_arrow_back,
    navigationContentDescription: String? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContent: (@Composable () -> Unit)? = null,
    actions: List<TopBarAction> = emptyList(),
    containerColor: Color = HavenTheme.colors.bg,
    titleColor: Color = HavenTheme.colors.text,
    titleStyle: TextStyle? = null
) {
    val h = HavenTheme.colors
    // Edge-to-edge fix: the M3 Scaffold does NOT inset its topBar slot (the
    // old Material TopAppBar added its own status-bar padding), so the Haven
    // header must pad itself AND paint its container behind the status bar —
    // otherwise the back circle/title draw under the system display and the
    // status-bar band shows a different color than the bar.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        if (onNavigationClick != null && navigationContent == null && navigationIconRes != 0) {
            HIconCircle(
                iconRes = navigationIconRes,
                contentDescription = navigationContentDescription,
                onClick = onNavigationClick,
                enabled = true
            )
        } else if (navigationContent != null) {
            navigationContent()
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = titleStyle ?: HavenType.h3.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HavenType.small.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                    ),
                    color = h.text2,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions.forEach { action ->
            HIconCircle(
                iconRes = action.iconRes,
                contentDescription = action.contentDescription,
                onClick = action.onClick,
                enabled = action.enabled,
                accent = action.selected
            )
        }
        }
    }
}
