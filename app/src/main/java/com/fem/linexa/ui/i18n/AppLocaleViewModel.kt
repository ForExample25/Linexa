package com.fem.linexa.ui.i18n

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.settings.AppLanguage
import com.fem.linexa.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Supplies the persisted app-language preference to the root composition.
 *
 * The initial value is read synchronously for the same reason as
 * `AppThemeViewModel`: the first frame must already be in the selected language,
 * otherwise a Persian user sees one frame of English before the flow catches up.
 * Layout direction is not part of this state: the application chrome is always
 * LTR (see `MainActivity`), so a language switch only changes resources and
 * text content. The flow subscription then makes a change from Settings reach
 * the whole tree immediately.
 */
@HiltViewModel
class AppLocaleViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _appLanguage = MutableStateFlow(settingsRepository.currentAppLanguage())
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeAppLanguage().collect { language ->
                _appLanguage.value = language
            }
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setAppLanguage(language) }
    }
}
