package com.fem.linexa.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fem.linexa.R
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.TerminalToolbarPreset
import com.fem.linexa.domain.settings.TerminalToolbarRowMode
import com.fem.linexa.domain.settings.TerminalToolbarSettings

/**
 * Localized labels for the terminal preferences.
 *
 * The domain enums carry English display names as a safe fallback for logs and
 * tests, but every user-visible label is resolved here so the Settings rows and
 * the two drill-down screens can never disagree about what a preset is called.
 */

@Composable
internal fun recentSessionsLayoutTitle(layout: RecentSessionsLayout): String =
    stringResource(
        when (layout) {
            RecentSessionsLayout.VERTICAL -> R.string.terminal_layout_vertical_title
            RecentSessionsLayout.HORIZONTAL -> R.string.terminal_layout_horizontal_title
        }
    )

@Composable
internal fun recentSessionsLayoutDescription(layout: RecentSessionsLayout): String =
    stringResource(
        when (layout) {
            RecentSessionsLayout.VERTICAL -> R.string.terminal_layout_vertical_desc
            RecentSessionsLayout.HORIZONTAL -> R.string.terminal_layout_horizontal_desc
        }
    )

/** One-line summary used as the Settings row description. */
@Composable
internal fun recentSessionsLayoutSummary(layout: RecentSessionsLayout): String =
    stringResource(
        when (layout) {
            RecentSessionsLayout.VERTICAL -> R.string.terminal_settings_recent_layout_vertical
            RecentSessionsLayout.HORIZONTAL -> R.string.terminal_settings_recent_layout_horizontal
        }
    )

@Composable
internal fun toolbarPresetLabel(preset: TerminalToolbarPreset): String =
    stringResource(
        when (preset) {
            TerminalToolbarPreset.COMPACT -> R.string.terminal_toolbar_preset_compact
            TerminalToolbarPreset.NAVIGATION -> R.string.terminal_toolbar_preset_navigation
            TerminalToolbarPreset.DEVELOPER -> R.string.terminal_toolbar_preset_developer
            TerminalToolbarPreset.SHELL -> R.string.terminal_toolbar_preset_shell
            TerminalToolbarPreset.CUSTOM -> R.string.terminal_toolbar_preset_custom
        }
    )

@Composable
internal fun toolbarRowModeLabel(mode: TerminalToolbarRowMode): String =
    stringResource(
        when (mode) {
            TerminalToolbarRowMode.SINGLE -> R.string.terminal_toolbar_row_single
            TerminalToolbarRowMode.DOUBLE -> R.string.terminal_toolbar_row_double
        }
    )

/** `Preset · Row mode`, the Settings row summary. */
@Composable
internal fun toolbarSummary(settings: TerminalToolbarSettings): String = stringResource(
    R.string.terminal_settings_toolbar_desc,
    toolbarPresetLabel(settings.preset),
    toolbarRowModeLabel(settings.rowMode)
)
