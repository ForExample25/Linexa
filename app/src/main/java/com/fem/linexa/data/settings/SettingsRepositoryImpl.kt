package com.fem.linexa.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.TerminalFontPreference
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.domain.settings.ThemeMode
import com.fem.linexa.domain.whatsnew.WhatsNewDecision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed [SettingsRepository].
 *
 * One prefs file, `linexa_settings`, for settings introduced through this
 * repository (Debug, theme mode, the acknowledged What's New version). The
 * terminal font size is deliberately NOT here (2026-08-29 terminal migration):
 * the terminal owns its appearance, persisted terminal-locally in
 * [TerminalFontPreference.PREFS_FILE] by the terminal adapter itself — a
 * terminal rendering knob is not an app-scoped setting.
 *
 * Every value is mirrored into a `MutableStateFlow` seeded from prefs at
 * construction. That is what lets the interface offer non-suspend `currentX()`
 * readers for the callers that have no coroutine to wait in (`LinexaTheme`
 * before the first frame) without touching the disk on the main thread. Because
 * the class is `@Singleton`, every consumer observes the same flows — so
 * changing a setting in one place updates every screen showing it.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val debugEnabledState = MutableStateFlow(
        prefs.getBoolean(KEY_DEBUG_ENABLED, DEBUG_DEFAULT)
    )

    private val themeModeState = MutableStateFlow(
        // Read by key, never by ordinal — see ThemeMode. An absent or unrecognized
        // value resolves to SYSTEM rather than throwing: this wraps the whole app's
        // color scheme, so it must not be able to fail startup.
        ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
    )

    private val colorThemeState = MutableStateFlow(
        // Same rationale as themeMode: parsed by stable key, falls back to the
        // default on null/unrecognized — the on-disk format is decoupled from
        // both enum ordinals and Kotlin identifiers, and an unreadable value can
        // never crash the app's startup.
        ColorTheme.fromKey(prefs.getString(KEY_COLOR_THEME, null))
    )

    private val recentSessionsLayoutState = MutableStateFlow(
        // Parsed by stable key, never ordinal: a renamed enum or a value from a
        // newer build must degrade to the default, not break the overview.
        RecentSessionsLayout.fromKey(prefs.getString(KEY_RECENT_SESSIONS_LAYOUT, null))
    )

    private val terminalToolbarState = MutableStateFlow(
        // Four small values: preset key, row-mode key and the two saved custom
        // rows. Built-in preset content is derived in the domain type, so there
        // is only one place where "Compact" is defined.
        TerminalToolbarSettings.decode(
            presetKey = prefs.getString(KEY_TOOLBAR_PRESET, null),
            rowModeKey = prefs.getString(KEY_TOOLBAR_ROW_MODE, null),
            customRow1 = prefs.getString(KEY_TOOLBAR_CUSTOM_ROW_1, null),
            customRow2 = prefs.getString(KEY_TOOLBAR_CUSTOM_ROW_2, null)
        )
    )

    /**
     * The newest `versionCode` whose changelog the user has seen.
     *
     * Not sanitized or clamped: any Int that ended up here was
     * written by some build of this app as its own `versionCode`, and the only thing
     * ever done with the value is an ordering comparison against the running build's.
     * The absent case is the one that carries meaning, and
     * [WhatsNewDecision.NOTHING_ACKNOWLEDGED] is the default precisely so it sorts
     * below every real version code.
     */
    private val lastAcknowledgedWhatsNewState = MutableStateFlow(
        prefs.getInt(KEY_WHATS_NEW_ACKNOWLEDGED, WhatsNewDecision.NOTHING_ACKNOWLEDGED)
    )

    // ── Diagnostics ────────────────────────────────────────────────────────

    // No `distinctUntilChanged` on any of the three observers: a StateFlow already
    // conflates equal consecutive values, so the operator is a no-op there (and
    // deprecated for exactly that reason). It matters that this is stated, because a
    // repeated identical emission is what would make a terminal re-measure its
    // character grid or the whole app recompose its color scheme for nothing.
    override fun observeDebugEnabled(): Flow<Boolean> = debugEnabledState.asStateFlow()

    override suspend fun isDebugEnabled(): Boolean = debugEnabledState.value

    override suspend fun setDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_ENABLED, enabled).apply()
        debugEnabledState.value = enabled
    }

    // ── Appearance ─────────────────────────────────────────────────────────

    override fun observeThemeMode(): Flow<ThemeMode> = themeModeState.asStateFlow()

    override fun currentThemeMode(): ThemeMode = themeModeState.value

    override suspend fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
        themeModeState.value = mode
    }

    override fun observeColorTheme(): Flow<ColorTheme> = colorThemeState.asStateFlow()

    override fun currentColorTheme(): ColorTheme = colorThemeState.value

    override suspend fun setColorTheme(theme: ColorTheme) {
        prefs.edit().putString(KEY_COLOR_THEME, theme.key).apply()
        colorThemeState.value = theme
    }

    // ── Terminal UI ────────────────────────────────────────────────────────

    override fun observeRecentSessionsLayout(): Flow<RecentSessionsLayout> =
        recentSessionsLayoutState.asStateFlow()

    override suspend fun setRecentSessionsLayout(layout: RecentSessionsLayout) {
        prefs.edit().putString(KEY_RECENT_SESSIONS_LAYOUT, layout.key).apply()
        recentSessionsLayoutState.value = layout
    }

    override fun observeTerminalToolbar(): Flow<TerminalToolbarSettings> =
        terminalToolbarState.asStateFlow()

    override suspend fun setTerminalToolbar(settings: TerminalToolbarSettings) {
        val repaired = settings.sanitized()
        prefs.edit()
            .putString(KEY_TOOLBAR_PRESET, repaired.preset.key)
            .putString(KEY_TOOLBAR_ROW_MODE, repaired.rowMode.key)
            .putString(KEY_TOOLBAR_CUSTOM_ROW_1, TerminalToolbarSettings.encodeRow(repaired.customRow1))
            .putString(KEY_TOOLBAR_CUSTOM_ROW_2, TerminalToolbarSettings.encodeRow(repaired.customRow2))
            .apply()
        terminalToolbarState.value = repaired
    }

    // ── What's New ─────────────────────────────────────────────────────────

    override fun observeLastAcknowledgedWhatsNewVersion(): Flow<Int> =
        lastAcknowledgedWhatsNewState.asStateFlow()

    override suspend fun setLastAcknowledgedWhatsNewVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_WHATS_NEW_ACKNOWLEDGED, versionCode).apply()
        lastAcknowledgedWhatsNewState.value = versionCode
    }

    private companion object {
        const val PREFS_FILE = "linexa_settings"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_COLOR_THEME = "color_theme"
        const val KEY_RECENT_SESSIONS_LAYOUT = "recent_sessions_layout"
        const val KEY_TOOLBAR_PRESET = "terminal_toolbar_preset"
        const val KEY_TOOLBAR_ROW_MODE = "terminal_toolbar_row_mode"
        const val KEY_TOOLBAR_CUSTOM_ROW_1 = "terminal_toolbar_custom_row_1"
        const val KEY_TOOLBAR_CUSTOM_ROW_2 = "terminal_toolbar_custom_row_2"
        const val KEY_WHATS_NEW_ACKNOWLEDGED = "whats_new_acknowledged_version"
        const val DEBUG_DEFAULT = false
    }
}
