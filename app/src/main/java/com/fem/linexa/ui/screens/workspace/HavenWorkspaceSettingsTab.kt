package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HRic
import com.fem.linexa.ui.components.haven.HRow
import com.fem.linexa.ui.components.haven.HSectionHeader
import com.fem.linexa.ui.components.haven.HToggle
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The workspace's own Settings segment — HAVEN (concept frame `fr-wsset`).
 *
 * Structure: Debug first but demoted (its trace reads as a mono block with
 * Copy/Save), then Network (the existing [NetworkSettingsPanel],
 * theme-colored), and the Danger Zone closes the segment with one quiet red
 * row.
 *
 * 2026-11 pass: the old Tools card (Files + Packages rows) moved to the Apps
 * segment — the two destinations the rows opened are pushed by the launcher's
 * Packages/Files buttons now, so this segment carries only genuinely
 * per-workspace settings and no duplicate entry points.
 */
@Composable
fun HavenWorkspaceSettingsTab(
    workspace: Workspace,
    runtimeTraceText: String,
    debugEnabled: Boolean,
    onSetDebugEnabled: (Boolean) -> Unit,
    featureSettings: FeatureSettings,
    networkSettings: NetworkSettings,
    onNetworkSettingsChange: (NetworkSettings) -> Unit,
    onSetTerminalPersistence: (Boolean) -> Unit,
    onDeleteWorkspace: () -> Unit
) {
    val h = HavenTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Debug ──
        HSectionHeader(title = stringResource(R.string.haven_ws_debug))
        HCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.global_settings_debug_title),
                        style = HavenType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = h.text
                    )
                    Text(
                        text = stringResource(R.string.haven_ws_debug_desc),
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                HToggle(checked = debugEnabled, onCheckedChange = onSetDebugEnabled)
            }
            if (debugEnabled) {
                // The trace as a mono block with Copy/Save — the concept's
                // `.mono-block` + two ghost buttons.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .height(160.dp)
                        .background(h.surface2, androidx.compose.foundation.shape.RoundedCornerShape(HavenDim.rMonoBlock))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = runtimeTraceText.ifBlank { stringResource(R.string.haven_ws_trace_empty) },
                        style = HavenType.monoBlock,
                        color = h.text2
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    HButton(
                        label = stringResource(R.string.common_copy),
                        onClick = { clipboard.setText(AnnotatedString(runtimeTraceText)) },
                        variant = HavenButtonVariant.SECONDARY,
                        size = HavenButtonSize.SMALL
                    )
                }
            }
        }

        // ── Network ──
        HSectionHeader(title = stringResource(R.string.haven_ws_network))
        HCard {
            NetworkSettingsPanel(
                settings = networkSettings,
                onSettingsChange = onNetworkSettingsChange
            )
            // Terminal session persistence — the opt-in tmux backend. Same
            // resolved FeatureSettings the runtime reads; row kept from the
            // pre-Haven Settings tab so the feature survives the redesign.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_persistence_label),
                        style = HavenType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = h.text
                    )
                    Text(
                        text = stringResource(R.string.settings_persistence_help),
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                HToggle(
                    checked = featureSettings.terminalPersistence,
                    onCheckedChange = onSetTerminalPersistence
                )
            }
        }

        // ── Danger Zone ──
        HSectionHeader(title = stringResource(R.string.haven_ws_danger))
        HCard(tight = true) {
            HRow(
                title = stringResource(R.string.haven_ws_delete),
                description = stringResource(R.string.haven_ws_delete_desc),
                titleColor = h.red,
                onClick = onDeleteWorkspace,
                leading = { HRic(R.drawable.ic_haven_trash, danger = true) },
                trailing = { HChevron() }
            )
        }
    }
}
