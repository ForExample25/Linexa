package com.fem.linexa.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.domain.settings.AppLanguage
import com.fem.linexa.domain.settings.ThemeMode
import com.fem.linexa.ui.components.haven.HavenDestination
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HPageHeader
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.components.haven.HBottomNav
import com.fem.linexa.ui.components.haven.havenTabSwipe
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HRic
import com.fem.linexa.ui.components.haven.HRow
import com.fem.linexa.ui.components.haven.HSectionHeader
import com.fem.linexa.ui.components.haven.HSegmented
import com.fem.linexa.ui.components.haven.HToggle
import com.fem.linexa.ui.theme.haven.em
import com.fem.linexa.ui.theme.haven.HavenType as HType

/**
 * The Settings tab — HAVEN (concept section 08, `fr-set`).
 *
 * The real groups in one calm scroll: Appearance (theme segments +
 * accent swatch rows), Diagnostics, Performance, Debug, Access, Help, Community,
 * Application Updates, About — closed by
 * the app icon + version footer ([AppInfoFooter]), the single bottom brand block
 * (a second HFootBrand footer below it rendered the logo twice; removed 2026-08-31).
 * The Terminal group holds only app-scoped presentation preferences —
 * Recent Sessions layout and the keyboard toolbar. Terminal appearance (font
 * size, colours) still belongs to the terminal itself: it is changed at the
 * terminal, not inferred here.
 * Packages deliberately do NOT appear here: in the Haven
 * architecture they are a workspace tool (Workspace → Tools), not a global
 * destination — matching the concept's information architecture.
 */
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenDeviceInfo: () -> Unit,
    onOpenTerminalLayout: () -> Unit,
    onOpenTerminalToolbar: () -> Unit,
    onSelectTab: (HavenDestination) -> Unit,
    viewModel: GlobalSettingsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val colorTheme by viewModel.colorTheme.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val recentSessionsLayout by viewModel.recentSessionsLayout.collectAsState()
    val terminalToolbar by viewModel.terminalToolbar.collectAsState()
    val logSizeBytes by viewModel.logSizeBytes.collectAsState()
    val logFileCount by viewModel.logFileCount.collectAsState()
    val lastCleared by viewModel.lastClearedBytes.collectAsState()

    val content = remember(themeMode, colorTheme, logSizeBytes, logFileCount) {
        GlobalSettingsContent.from(
            themeMode = themeMode,
            colorTheme = colorTheme,
            logSizeBytes = logSizeBytes,
            logFileCount = logFileCount
        )
    }

    LaunchedEffect(lastCleared) {
        if (lastCleared == null) return@LaunchedEffect
        kotlinx.coroutines.delay(CLEAR_CONFIRMATION_MS)
        viewModel.consumeClearResult()
    }

    Scaffold(
        containerColor = h.bg,
        bottomBar = {
            HBottomNav(selected = HavenDestination.SETTINGS, onSelect = onSelectTab)
        }
    ) { padding ->
        HavenScreen(
            statusBarInsets = false,
            modifier = Modifier
                .padding(padding)
                .havenTabSwipe(
                    current = HavenDestination.SETTINGS,
                    onSelectTab = onSelectTab
                )
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    HPageHeader(eyebrow = "LINEXA", title = stringResource(R.string.haven_settings_title))
                }

                settingsSection {
                    // ── Language ──
                    // First, not buried: it is reachable before any workspace
                    // exists, and a user who cannot read the current UI needs
                    // it to be the first thing they see. Each language is
                    // named in its own script in every locale, so the row is
                    // findable even while the rest of the screen is in the
                    // language the user is trying to leave.
                    HSectionHeader(title = stringResource(R.string.global_settings_language_group))
                    HCard(tight = true) {
                        LanguageOptionRow(
                            title = stringResource(R.string.global_settings_language_system),
                            description = stringResource(R.string.global_settings_language_system_desc),
                            selected = appLanguage == AppLanguage.SYSTEM,
                            onClick = { viewModel.setAppLanguage(AppLanguage.SYSTEM) }
                        )
                        LanguageOptionRow(
                            title = stringResource(R.string.global_settings_language_english),
                            selected = appLanguage == AppLanguage.ENGLISH,
                            onClick = { viewModel.setAppLanguage(AppLanguage.ENGLISH) }
                        )
                        LanguageOptionRow(
                            title = stringResource(R.string.global_settings_language_persian),
                            selected = appLanguage == AppLanguage.PERSIAN,
                            onClick = { viewModel.setAppLanguage(AppLanguage.PERSIAN) }
                        )
                    }
                }

                settingsSection {
                    // ── Appearance ──
                    HSectionHeader(title = stringResource(R.string.global_settings_appearance_group))
                    HCard {
                        ThemeSegment(
                            selected = content.themeMode,
                            onSelect = viewModel::setThemeMode
                        )
                        Column {
                            com.fem.linexa.domain.settings.ColorTheme.entries.forEach { theme ->
                                ColorSwatchRow(
                                    label = stringResource(colorThemeLabel(theme)),
                                    swatch = com.fem.linexa.ui.theme.ColorThemes.swatch(theme),
                                    isDefault = theme == com.fem.linexa.domain.settings.ColorTheme.DEFAULT,
                                    selected = content.colorTheme == theme,
                                    onClick = { viewModel.setColorTheme(theme) }
                                )
                            }
                        }
                    }
                }

                settingsSection {
                    // ── Terminal ──
                    // The terminal owns its font (changed at the terminal,
                    // not here) — but these two are genuinely app-scoped UI
                    // preferences: how Recent Sessions is arranged, and which
                    // shortcuts sit above the keyboard. Both apply live to an
                    // already-open terminal without recreating a pty.
                    HSectionHeader(title = stringResource(R.string.global_settings_terminal_group))
                    HCard(tight = true) {
                        HRow(
                            title = stringResource(R.string.terminal_settings_recent_layout_title),
                            description = recentSessionsLayoutSummary(recentSessionsLayout),
                            onClick = onOpenTerminalLayout,
                            leading = { HRic(R.drawable.ic_haven_layout_columns) },
                            trailing = { HChevron() }
                        )
                        HRow(
                            title = stringResource(R.string.terminal_settings_toolbar_title),
                            description = toolbarSummary(terminalToolbar),
                            onClick = onOpenTerminalToolbar,
                            leading = { HRic(R.drawable.ic_haven_keyboard) },
                            trailing = { HChevron() }
                        )
                    }
                }

                settingsSection {
                    // ── Diagnostics ──
                    HSectionHeader(title = stringResource(R.string.global_settings_diagnostics_group))
                    HCard(tight = true) {
                        HRow(
                            title = stringResource(R.string.global_settings_logs_title),
                            description = when (val summary = content.logSummary) {
                                GlobalSettingsContent.LogSummary.Empty ->
                                    stringResource(R.string.global_settings_logs_empty)
                                is GlobalSettingsContent.LogSummary.Present -> stringResource(
                                    R.string.global_settings_logs_present,
                                    summary.sizeLabel,
                                    summary.fileCount
                                )
                            },
                            onClick = null,
                            leading = { HRic(R.drawable.ic_haven_activity) }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = content.logSummary != GlobalSettingsContent.LogSummary.Empty) {
                                    viewModel.clearAppLogs()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .heightIn(min = HavenDim.rowMin),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            HRic(
                                R.drawable.ic_haven_trash,
                                danger = true
                            )
                            Text(
                                text = if (lastCleared != null) {
                                    stringResource(R.string.global_settings_logs_cleared, lastCleared!!)
                                } else {
                                    stringResource(R.string.global_settings_logs_clear)
                                },
                                style = HavenType.rowTitle,
                                color = h.red
                            )
                        }
                    }
                }

                settingsSection {
                    // ── Device ──
                    // UI/UX Phase 3: the standalone Performance Dashboard was
                    // removed — Devices & System Information already carries the
                    // live host metrics (CPU/RAM/storage/battery), so a second
                    // destination for the same data was redundant. The device
                    // info screen and its route stay; the shared performance
                    // logic (WorkspacePerformance*, hostinfo providers) is
                    // untouched for the features that still use it.
                    HSectionHeader(title = stringResource(R.string.settings_group_performance))
                    HCard(tight = true) {
                        HRow(
                            title = stringResource(R.string.device_info_row_title),
                            onClick = onOpenDeviceInfo,
                            leading = { HRic(R.drawable.ic_haven_grid) },
                            trailing = { HChevron() }
                        )
                    }
                }

                settingsSection {
                    // ── Debug ──
                    HSectionHeader(title = stringResource(R.string.workspace_debug_title))
                    HCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.global_settings_debug_title),
                                    style = HavenType.body.copy(fontWeight = FontWeight.Medium),
                                    color = h.text
                                )
                                Text(
                                    text = stringResource(R.string.global_settings_debug_desc),
                                    style = HavenType.desc,
                                    color = h.text2,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            HToggle(checked = debugEnabled, onCheckedChange = viewModel::setDebugEnabled)
                        }
                    }
                }

                settingsSection {
                    // ── Access / Help / Community (existing Haven-colored groups) ──
                    AccessSettingsGroup()
                    HelpSettingsGroups()
                }

                settingsSection {
                    // ── Application Updates (2026-11-16) ──
                    // Before About, after Help/Community: it is an action group
                    // (check → download → install), not an information group.
                    // PERF-008 remainder (2026-11-24 continuation): the group
                    // owns its default `hiltViewModel()`, so creating it here
                    // defers the update coordinator window until this lazy item
                    // is actually scrolled into view.
                    AppUpdateGroup()
                }

                settingsSection {
                    // ── About ──
                    HSectionHeader(title = stringResource(R.string.global_settings_about_group))
                    HCard(tight = true) {
                        HRow(
                            title = stringResource(R.string.global_settings_about_title),
                            description = stringResource(R.string.global_settings_about_desc),
                            onClick = onOpenAbout,
                            leading = { HRic(R.drawable.ic_haven_shield) },
                            trailing = { HChevron() }
                        )
                    }
                }

                settingsSection {
                    AppInfoFooter()
                }
            }
        }
    }
}

private const val CLEAR_CONFIRMATION_MS = 4_000L

/**
 * One Settings section as a single `LazyColumn` item.
 *
 * PERF-008: the body used to be a plain `Column` + `verticalScroll`, which
 * composes every section — including `AppUpdateGroup` and its view model —
 * on the first visit even though the lower half is below the fold. Lazy items
 * compose when scrolled into view. The wrapper keeps the old horizontal inset
 * (`HavenDim.screenH`) and, because sections stay whole, their internal
 * spacing and card grouping are unchanged.
 */
private fun LazyListScope.settingsSection(content: @Composable () -> Unit) {
    item {
        Column(modifier = Modifier.padding(horizontal = HavenDim.screenH)) {
            content()
        }
    }
}

/**
 * One language row. The check is a real trailing state (not a color-only
 * selection) and carries a translated content description, so the current
 * choice is visible and announced before the row is tapped.
 */
@Composable
private fun LanguageOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    val h = HavenTheme.colors
    HRow(
        title = title,
        description = description,
        onClick = onClick,
        trailing = if (selected) {
            {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_haven_check),
                    contentDescription = stringResource(R.string.global_settings_language_selected),
                    tint = h.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            null
        }
    )
}

/** Theme mode — the concept's mini segmented pill inside the Appearance card. */
@Composable
private fun ThemeSegment(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.global_settings_theme_title),
            style = HavenType.body.copy(fontWeight = FontWeight.Medium),
            color = h.text,
            modifier = Modifier.weight(1f)
        )
        Box(modifier = Modifier.width230()) {
            HSegmented(
                options = ThemeMode.entries.map { stringResource(themeModeLabel(it)) },
                selected = ThemeMode.entries.indexOf(selected),
                onSelect = { index -> onSelect(ThemeMode.entries[index]) },
                mini = true
            )
        }
    }
}

private fun Modifier.width230(): Modifier = this.then(
    Modifier.fillMaxWidth(0.62f)
)

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.global_settings_theme_system
    ThemeMode.LIGHT -> R.string.global_settings_theme_light
    ThemeMode.DARK -> R.string.global_settings_theme_dark
}

private fun colorThemeLabel(theme: com.fem.linexa.domain.settings.ColorTheme): Int = when (theme) {
    com.fem.linexa.domain.settings.ColorTheme.ALPINE_BLUE -> R.string.global_settings_color_theme_alpine_blue
    com.fem.linexa.domain.settings.ColorTheme.LAVENDER -> R.string.global_settings_color_theme_lavender
    com.fem.linexa.domain.settings.ColorTheme.FOREST -> R.string.global_settings_color_theme_forest
    com.fem.linexa.domain.settings.ColorTheme.SUNSET -> R.string.global_settings_color_theme_sunset
}

/** `.swrow` — accent swatch rows with the default marker and a check. */
@Composable
private fun ColorSwatchRow(
    label: String,
    swatch: androidx.compose.ui.graphics.Color,
    isDefault: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The parent HCard already applies HavenDim.cardPad. Adding a
            // second horizontal inset here made the swatch rows sit 40dp in
            // while the Theme row above sat at 20dp.
            .padding(vertical = 10.dp)
            .heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isDefault) {
                stringResource(R.string.global_settings_color_theme_default_suffix, label)
            } else {
                label
            },
            style = HavenType.body.copy(fontWeight = FontWeight.Medium),
            color = h.text,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            maxLines = 2
        )
        // Swatch dots: accent on surface on text — the concept's swdot row.
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(swatch)
            )
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(h.surface2)
            )
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(h.text)
            )
        }
        if (selected) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_haven_check),
                contentDescription = null,
                tint = h.primary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(18.dp)
            )
        }
    }
}
