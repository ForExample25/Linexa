package com.fem.linexa.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI/UX Phase 4 — supplies the persisted [ThemeMode] to the app's root composable.
 *
 * Exists because [LinexaTheme] must stay presentation-only (it is used by previews
 * and takes a plain `Boolean`), while the *choice* has to come from
 * [SettingsRepository]. This is the seam, and it is scoped to the activity so the
 * whole nav graph is wrapped by one theme rather than each screen re-resolving it.
 *
 * The initial value is read **synchronously** via
 * [SettingsRepository.currentThemeMode]. That is the entire reason the repository
 * exposes a non-suspend reader: collecting a flow here would compose the first frame
 * with the default mode and correct it on the next one, i.e. a visible flash of the
 * wrong color scheme on every cold start — for a setting whose only purpose is which
 * colors appear.
 *
 * It deliberately does **not** resolve to a `Boolean`. `SYSTEM` is a live
 * subscription to the device setting, so the light/dark decision needs
 * `isSystemInDarkTheme()`, which must be read inside composition; resolving here
 * would freeze whatever the system reported when this ViewModel was constructed.
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _themeMode = MutableStateFlow(settingsRepository.currentThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * Phase 7 — the color-theme dimension. Same shape as [themeMode]: seeded
     * synchronously so the first frame is already correct, and observed live so a
     * change made in Settings repaints the whole app on the next composition.
     */
    private val _colorTheme = MutableStateFlow(settingsRepository.currentColorTheme())
    val colorTheme: StateFlow<ColorTheme> = _colorTheme.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeThemeMode().collect { mode ->
                _themeMode.value = mode
            }
        }
        viewModelScope.launch {
            settingsRepository.observeColorTheme().collect { theme ->
                _colorTheme.value = theme
            }
        }
    }
}
