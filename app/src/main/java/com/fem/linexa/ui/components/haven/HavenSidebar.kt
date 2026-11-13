package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.data.Workspace
import com.fem.linexa.ui.components.DistroIdentity
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * The tablet two-pane sidebar (concept section 11): 238dp surface column with
 * the three top-level destinations and the workspace list beneath. Selecting
 * a workspace opens its detail in the pane BESIDE this list — the same
 * navigation model as the phone's bottom bar, just persistently visible.
 */
@Composable
fun HavenSidebar(
    selected: HavenDestination?,
    onSelect: (HavenDestination) -> Unit,
    workspaces: List<Workspace>,
    onOpenWorkspace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .width(HavenDim.sidebarW)
            .background(h.surface)
            .statusBarsPadding()
            .padding(top = 20.dp, start = 14.dp, end = 14.dp, bottom = 14.dp)
    ) {
        Text(
            text = "LINEXA",
            style = HavenType.brand,
            color = h.muted,
            modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)
        )

        sidebarRow(
            iconRes = com.fem.linexa.R.drawable.ic_haven_grid,
            label = "Workspaces",
            selected = selected == HavenDestination.WORKSPACES,
            trailingCount = workspaces.size.takeIf { it > 0 }?.toString(),
            onClick = { onSelect(HavenDestination.WORKSPACES) }
        )
        sidebarRow(
            iconRes = com.fem.linexa.R.drawable.ic_haven_activity,
            label = "Activity",
            selected = selected == HavenDestination.ACTIVITY,
            onClick = { onSelect(HavenDestination.ACTIVITY) }
        )
        sidebarRow(
            iconRes = com.fem.linexa.R.drawable.ic_custom_settings,
            label = "Settings",
            selected = selected == HavenDestination.SETTINGS,
            onClick = { onSelect(HavenDestination.SETTINGS) }
        )

        Text(
            text = "WORKSPACES",
            style = HavenType.eyebrow.copy(letterSpacing = com.fem.linexa.ui.theme.haven.em(0.16f)),
            color = h.muted,
            modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            workspaces.forEach { workspace ->
                val isRunning = workspace.isRunning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(HavenDim.rSidebarRow))
                        .clickable { onOpenWorkspace(workspace.id) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DistroIdentity(
                        distroId = workspace.distroId,
                        letter = workspace.avatarLetter,
                        color = workspace.avatarColor,
                        size = HavenDim.glyphSm
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = workspace.name,
                            style = HavenType.body.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                            ),
                            color = h.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (workspace.storageUsedLabel != null) {
                            Text(
                                text = workspace.storageUsedLabel,
                                style = HavenType.descMono,
                                color = h.muted,
                                maxLines = 1
                            )
                        }
                    }
                    HDot(
                        color = when {
                            isRunning -> h.primary
                            workspace.status is com.fem.linexa.domain.workspace.WorkspaceStatus.Broken -> h.red
                            else -> h.blue
                        },
                        size = 6.5f
                    )
                }
            }
        }
    }
}

@Composable
private fun sidebarRow(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingCount: String? = null
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(HavenDim.rSidebarRow))
            .background(if (selected) h.surface2 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) h.text else h.text2,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = HavenType.body.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            color = if (selected) h.text else h.text2,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (trailingCount != null) {
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(h.surface2)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = trailingCount,
                    style = HavenType.small.copy(fontFamily = HavenType.mono, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = h.text2
                )
            }
        }
    }
}
