package com.fem.linexa.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.domain.settings.TerminalShortcut
import com.fem.linexa.domain.settings.TerminalToolbarConfig
import com.fem.linexa.domain.settings.TerminalToolbarPreset
import com.fem.linexa.domain.settings.TerminalToolbarRowMode
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HHairline
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HSectionHeader
import com.fem.linexa.ui.components.haven.HSegmented
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HavenExtraKeys
import com.fem.linexa.ui.components.haven.HavenTerminalModifiers
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * `Settings → Terminal → Keyboard toolbar`.
 *
 * Every visual here is the REAL [HavenExtraKeys] bar, driven by the same
 * [TerminalToolbarConfig] the terminal paints. A preset is therefore a live
 * preview, not a picture: if the bar grows a key, the Settings card grows it
 * too, and a preview can never promise a shortcut dispatch does not support.
 *
 * The editing model is deliberately one-way: presets are canonical, and the
 * first edit (add / remove / move / assign) seeds the saved custom rows from
 * the preset being edited and switches the preset to Custom. Selecting a
 * built-in preset again is always lossless and instant.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalToolbarSettingsScreen(
    onBack: () -> Unit,
    viewModel: GlobalSettingsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val settings by viewModel.terminalToolbar.collectAsState()
    val config = settings.config
    val previewModifiers = remember { HavenTerminalModifiers() }
    var addTargetRow by rememberSaveable { mutableIntStateOf(0) }

    TerminalSettingsPage(
        title = stringResource(R.string.terminal_toolbar_screen_title),
        subtitle = stringResource(R.string.terminal_toolbar_screen_subtitle),
        onBack = onBack
    ) {
        // ── Live preview ────────────────────────────────────────────────
        HSectionHeader(title = stringResource(R.string.terminal_toolbar_preview_label))
        HCard(tight = true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = h.border,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    HavenExtraKeys(
                        modifiers = previewModifiers,
                        config = config,
                        interactive = false,
                        applyNavigationBarsPadding = false
                    )
                }
            }
        }

        // ── Presets ─────────────────────────────────────────────────────
        HSectionHeader(title = stringResource(R.string.terminal_toolbar_presets_label))
        HCard(tight = true) {
            val presets = TerminalToolbarPreset.entries
            presets.forEachIndexed { index, preset ->
                PresetRow(
                    preset = preset,
                    selected = settings.preset == preset,
                    preview = settings.configFor(preset).copy(
                        // One row keeps five presets readable at a glance; the
                        // live preview above shows the active row mode.
                        rowMode = TerminalToolbarRowMode.SINGLE,
                        row2 = emptyList()
                    ),
                    onSelect = { viewModel.selectToolbarPreset(preset) }
                )
                if (index != presets.lastIndex) {
                    HHairline(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        Text(
            text = stringResource(R.string.terminal_toolbar_preset_hint),
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 10.dp)
        )

        // ── Row mode ────────────────────────────────────────────────────
        HSectionHeader(title = stringResource(R.string.terminal_toolbar_rows_label))
        HCard {
            HSegmented(
                options = TerminalToolbarRowMode.entries.map { toolbarRowModeLabel(it) },
                selected = TerminalToolbarRowMode.entries.indexOf(settings.rowMode),
                onSelect = { index ->
                    viewModel.setToolbarRowMode(TerminalToolbarRowMode.entries[index])
                }
            )
            if (settings.rowMode == TerminalToolbarRowMode.SINGLE) {
                Text(
                    text = stringResource(R.string.terminal_toolbar_single_row_note),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        // ── Custom rows ─────────────────────────────────────────────────
        HSectionHeader(title = stringResource(R.string.terminal_toolbar_custom_label))
        ShortcutRowEditor(
            rowIndex = 0,
            row = config.row1,
            otherRowSize = config.row2.size,
            onMove = { index, delta -> viewModel.moveToolbarShortcut(0, index, delta) },
            onMoveToOtherRow = { shortcut ->
                viewModel.moveToolbarShortcutToOtherRow(0, shortcut)
            },
            onRemove = { shortcut -> viewModel.removeToolbarShortcut(0, shortcut) }
        )
        Spacer(modifier = Modifier.height(HavenDim.cardGap))
        ShortcutRowEditor(
            rowIndex = 1,
            row = config.row2,
            otherRowSize = config.row1.size,
            onMove = { index, delta -> viewModel.moveToolbarShortcut(1, index, delta) },
            onMoveToOtherRow = { shortcut ->
                viewModel.moveToolbarShortcutToOtherRow(1, shortcut)
            },
            onRemove = { shortcut -> viewModel.removeToolbarShortcut(1, shortcut) }
        )
        Text(
            text = stringResource(R.string.terminal_toolbar_custom_hint),
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 10.dp)
        )

        // ── Add ─────────────────────────────────────────────────────────
        HSectionHeader(title = stringResource(R.string.terminal_toolbar_add_label))
        HCard {
            HSegmented(
                options = listOf(
                    stringResource(R.string.terminal_toolbar_row_1),
                    stringResource(R.string.terminal_toolbar_row_2)
                ),
                selected = addTargetRow,
                onSelect = { addTargetRow = it },
                mini = true
            )
            val present = (config.row1 + config.row2).toSet()
            val available = TerminalShortcut.entries.filterNot { it in present }
            val targetSize = if (addTargetRow == 0) config.row1.size else config.row2.size
            val targetFull = targetSize >= TerminalToolbarSettings.MAX_ROW_SIZE
            when {
                available.isEmpty() -> Text(
                    text = stringResource(R.string.terminal_toolbar_add_empty),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 12.dp)
                )
                else -> {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        available.forEach { shortcut ->
                            HChip(
                                label = shortcut.label,
                                selected = false,
                                enabled = !targetFull,
                                onClick = {
                                    viewModel.addToolbarShortcut(addTargetRow, shortcut)
                                }
                            )
                        }
                    }
                    if (targetFull) {
                        Text(
                            text = stringResource(R.string.terminal_toolbar_row_full),
                            style = HavenType.desc,
                            color = h.amber,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }

        // ── Restore ─────────────────────────────────────────────────────
        HButton(
            label = stringResource(R.string.terminal_toolbar_restore),
            onClick = viewModel::restoreToolbarDefaults,
            variant = HavenButtonVariant.SECONDARY,
            block = true,
            modifier = Modifier.padding(top = 22.dp),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_restore),
                    contentDescription = null,
                    tint = h.text2,
                    modifier = Modifier.size(17.dp)
                )
            }
        )
    }
}

/** One selectable preset with its real, non-interactive bar preview. */
@Composable
private fun PresetRow(
    preset: TerminalToolbarPreset,
    selected: Boolean,
    preview: TerminalToolbarConfig,
    onSelect: () -> Unit
) {
    val h = HavenTheme.colors
    val modifiers = remember { HavenTerminalModifiers() }
    val label = toolbarPresetLabel(preset)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onSelect)
            .semantics { this.selected = selected }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = HavenType.rowTitle,
                color = if (selected) h.primary else h.text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_check),
                    contentDescription = null,
                    tint = h.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    color = if (selected) h.primary.copy(alpha = 0.45f) else h.border,
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            HavenExtraKeys(
                modifiers = modifiers,
                config = preview,
                interactive = false,
                applyNavigationBarsPadding = false
            )
        }
    }
}

/**
 * One editable shortcut row. Reordering is explicit (arrow controls), so a
 * drag can never fight the page's vertical scroll, and every action carries a
 * label naming the shortcut it affects.
 */
@Composable
private fun ShortcutRowEditor(
    rowIndex: Int,
    row: List<TerminalShortcut>,
    otherRowSize: Int,
    onMove: (index: Int, delta: Int) -> Unit,
    onMoveToOtherRow: (TerminalShortcut) -> Unit,
    onRemove: (TerminalShortcut) -> Unit
) {
    val h = HavenTheme.colors
    HCard(tight = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (rowIndex == 0) {
                    stringResource(R.string.terminal_toolbar_row_1)
                } else {
                    stringResource(R.string.terminal_toolbar_row_2)
                },
                style = HavenType.small.copy(fontWeight = FontWeight.SemiBold),
                color = h.text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = row.size.toString(),
                style = HavenType.descMono,
                color = h.muted
            )
        }
        if (row.isEmpty()) {
            Text(
                text = stringResource(R.string.terminal_toolbar_empty_row),
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            return@HCard
        }
        row.forEachIndexed { index, shortcut ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // The label is the keycap the terminal shows, so the editor and
                // the live bar always name a shortcut the same way.
                Text(
                    text = shortcut.label,
                    style = HavenType.body.copy(
                        fontFamily = HavenType.mono,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = h.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                )
                HIconGhost(
                    iconRes = R.drawable.ic_haven_chevron_up,
                    contentDescription = stringResource(
                        R.string.terminal_toolbar_move_earlier,
                        shortcut.label
                    ),
                    onClick = { onMove(index, -1) },
                    small = true,
                    enabled = index > 0
                )
                HIconGhost(
                    iconRes = R.drawable.ic_haven_chevron_down,
                    contentDescription = stringResource(
                        R.string.terminal_toolbar_move_later,
                        shortcut.label
                    ),
                    onClick = { onMove(index, 1) },
                    small = true,
                    enabled = index < row.lastIndex
                )
                HIconGhost(
                    iconRes = if (rowIndex == 0) {
                        R.drawable.ic_haven_chevron_right
                    } else {
                        R.drawable.ic_haven_chevron_left
                    },
                    contentDescription = stringResource(
                        R.string.terminal_toolbar_move_other_row,
                        shortcut.label
                    ),
                    onClick = { onMoveToOtherRow(shortcut) },
                    small = true,
                    enabled = otherRowSize < TerminalToolbarSettings.MAX_ROW_SIZE
                )
                HIconGhost(
                    iconRes = R.drawable.ic_haven_close,
                    contentDescription = stringResource(
                        R.string.terminal_toolbar_remove,
                        shortcut.label
                    ),
                    onClick = { onRemove(shortcut) },
                    tint = h.red,
                    small = true
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
