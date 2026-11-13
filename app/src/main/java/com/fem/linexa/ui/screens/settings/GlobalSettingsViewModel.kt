package com.fem.linexa.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.AppLogStore
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.TerminalShortcut
import com.fem.linexa.domain.settings.TerminalToolbarEditing
import com.fem.linexa.domain.settings.TerminalToolbarPreset
import com.fem.linexa.domain.settings.TerminalToolbarRowMode
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State for the app-level Global Settings destination (§ Home redesign — the
 * second bottom-nav tab).
 *
 * UI/UX Phase 4 grew this from a single Debug switch into the things that were
 * *already implemented but unreachable*: the light/dark choice (both schemes
 * existed; nothing selected between them) and the app log (written since
 * Phase 0, read by nothing, up to ~2 MB the user could neither see nor reclaim).
 *
 * The terminal font-size row that Phase 4 also added is deliberately gone
 * (2026-08-29 terminal migration): the terminal owns its appearance. The size
 * lives terminal-locally in `TerminalFontPreference` and is changed at the
 * terminal — pinch, or the Haven terminal ⋯ sheet — exactly where a real
 * terminal emulator keeps it. Anything app-scoped belongs here; per-workspace
 * state does not (see [SettingsRepository]'s doc).
 */
@HiltViewModel
class GlobalSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appLogStore: AppLogStore
) : ViewModel() {

    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(settingsRepository.currentThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * Phase 7 — the color-theme dimension. Same pattern as [themeMode]: seeded
     * synchronously so the row renders the persisted value on the first frame,
     * observed so a change made elsewhere (e.g. a future reset-to-default flow)
     * repaints the picker.
     */
    private val _colorTheme = MutableStateFlow(settingsRepository.currentColorTheme())
    val colorTheme: StateFlow<ColorTheme> = _colorTheme.asStateFlow()

    // ── Terminal UI ────────────────────────────────────────────────────────

    /** Which Recent Sessions arrangement is active. */
    private val _recentSessionsLayout = MutableStateFlow(RecentSessionsLayout.DEFAULT)
    val recentSessionsLayout: StateFlow<RecentSessionsLayout> = _recentSessionsLayout.asStateFlow()

    /** Preset + row mode + the saved custom rows; the active config is derived. */
    private val _terminalToolbar = MutableStateFlow(TerminalToolbarSettings.DEFAULT)
    val terminalToolbar: StateFlow<TerminalToolbarSettings> = _terminalToolbar.asStateFlow()

    private val _logSizeBytes = MutableStateFlow(0L)
    val logSizeBytes: StateFlow<Long> = _logSizeBytes.asStateFlow()

    private val _logFileCount = MutableStateFlow(0)
    val logFileCount: StateFlow<Int> = _logFileCount.asStateFlow()

    /**
     * One-shot confirmation of the last clear, in bytes; null when there is nothing
     * to report.
     *
     * Reported rather than inferred from the size dropping to zero, because the
     * clear is best-effort per file ([AppLogStore.clear]) — a partial reclaim is a
     * real outcome and the number the user is told must be the number actually
     * removed. Consumed via [consumeClearResult] so it survives a recomposition but
     * not a second visit to the screen.
     */
    private val _lastClearedBytes = MutableStateFlow<Long?>(null)
    val lastClearedBytes: StateFlow<Long?> = _lastClearedBytes.asStateFlow()

    /** The directory shown under the Diagnostics row, so the files are findable. */
    val logPath: String = appLogStore.displayPath()

    init {
        viewModelScope.launch {
            settingsRepository.observeDebugEnabled().collect { enabled ->
                _debugEnabled.value = enabled
            }
        }
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
        viewModelScope.launch {
            settingsRepository.observeRecentSessionsLayout().collect { layout ->
                _recentSessionsLayout.value = layout
            }
        }
        viewModelScope.launch {
            settingsRepository.observeTerminalToolbar().collect { toolbar ->
                _terminalToolbar.value = toolbar
            }
        }
        refreshLogStats()
    }

    fun setDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDebugEnabled(enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    /**
     * Phase 7 — pick a color theme. Independent of the Light/Dark axis: choosing
     * one does not reset the other (the repository keeps the two keys in
     * separate SharedPreferences entries, with no cross-write).
     */
    fun setColorTheme(theme: ColorTheme) {
        viewModelScope.launch {
            settingsRepository.setColorTheme(theme)
        }
    }

    // ── Terminal UI ────────────────────────────────────────────────────────

    /**
     * Applies a Recent Sessions layout immediately.
     *
     * The local flow is updated before the persist coroutine runs so the
     * settings preview acknowledges the tap in the same frame; the repository
     * write then reaches every live terminal panel through the shared flow.
     */
    fun setRecentSessionsLayout(layout: RecentSessionsLayout) {
        _recentSessionsLayout.value = layout
        viewModelScope.launch { settingsRepository.setRecentSessionsLayout(layout) }
    }

    fun selectToolbarPreset(preset: TerminalToolbarPreset) {
        updateToolbar { TerminalToolbarEditing.selectPreset(it, preset) }
    }

    fun setToolbarRowMode(mode: TerminalToolbarRowMode) {
        updateToolbar { TerminalToolbarEditing.setRowMode(it, mode) }
    }

    fun addToolbarShortcut(rowIndex: Int, shortcut: TerminalShortcut) {
        updateToolbar { TerminalToolbarEditing.add(it, rowIndex, shortcut) }
    }

    fun removeToolbarShortcut(rowIndex: Int, shortcut: TerminalShortcut) {
        updateToolbar { TerminalToolbarEditing.remove(it, rowIndex, shortcut) }
    }

    fun moveToolbarShortcut(rowIndex: Int, index: Int, delta: Int) {
        updateToolbar { TerminalToolbarEditing.move(it, rowIndex, index, delta) }
    }

    fun moveToolbarShortcutToOtherRow(rowIndex: Int, shortcut: TerminalShortcut) {
        updateToolbar { TerminalToolbarEditing.assignToOtherRow(it, rowIndex, shortcut) }
    }

    fun restoreToolbarDefaults() {
        updateToolbar { TerminalToolbarEditing.restoreDefaults() }
    }

    private fun updateToolbar(transform: (TerminalToolbarSettings) -> TerminalToolbarSettings) {
        val next = transform(_terminalToolbar.value).sanitized()
        _terminalToolbar.value = next
        viewModelScope.launch { settingsRepository.setTerminalToolbar(next) }
    }

    /**
     * Delete the app log and report what was reclaimed.
     *
     * Both the clear and the re-measure run on [Dispatchers.IO] — this is file
     * deletion behind a settings tap, and the stats are read back afterwards rather
     * than assumed to be zero, because [AppLogStore.clear] is best-effort per file
     * and the sink may legitimately have written a new line in the meantime.
     */
    fun clearAppLogs() {
        viewModelScope.launch {
            val reclaimed = withContext(Dispatchers.IO) { appLogStore.clear() }
            _lastClearedBytes.value = reclaimed
            refreshLogStats()
        }
    }

    /** Acknowledge the clear confirmation so it is shown once. */
    fun consumeClearResult() {
        _lastClearedBytes.value = null
    }

    /**
     * Re-measure the log on disk.
     *
     * Public so the screen can refresh on resume: the log grows while the user is
     * elsewhere in the app, and a stale size next to a Clear button would understate
     * what clearing does.
     */
    fun refreshLogStats() {
        viewModelScope.launch {
            val (size, count) = withContext(Dispatchers.IO) {
                appLogStore.totalSizeBytes() to appLogStore.fileCount()
            }
            _logSizeBytes.value = size
            _logFileCount.value = count
        }
    }
}
