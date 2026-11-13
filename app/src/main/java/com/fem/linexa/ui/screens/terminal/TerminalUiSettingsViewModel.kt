package com.fem.linexa.ui.screens.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Read-only view of the app-scoped terminal UI settings for the live terminal
 * panels.
 *
 * This is deliberately separate from `GlobalSettingsViewModel`: the terminal
 * needs the current values and must react live when Settings changes them, but
 * it does not own the log store or any settings-editing intent. Both collectors
 * observe the same singleton [SettingsRepository] flows, so a tap in Settings
 * reaches an already-composed terminal without a restart, a navigation, or a
 * pty recreation.
 */
@HiltViewModel
class TerminalUiSettingsViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {

    val recentSessionsLayout: StateFlow<RecentSessionsLayout> =
        settingsRepository.observeRecentSessionsLayout().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RecentSessionsLayout.DEFAULT
        )

    val toolbarSettings: StateFlow<TerminalToolbarSettings> =
        settingsRepository.observeTerminalToolbar().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TerminalToolbarSettings.DEFAULT
        )
}
