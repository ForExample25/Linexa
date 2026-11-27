package com.fem.linexa.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Repository for app-level user settings (persisted via SharedPreferences).
 *
 * Everything here is **app-scoped**, not workspace-scoped: a value belongs in this
 * interface only if it would be wrong for two workspaces to disagree about it.
 * Per-workspace state lives in the Room workspace row instead (`featureToggles`,
 * the distro fields, status), and per-workspace *guest* configuration lives in
 * that workspace's `config.yaml` (§1.26). Values are kept small and typed; add new
 * keys here rather than reading SharedPreferences directly in composables.
 *
 * The **terminal font size is deliberately absent** (2026-08-29 terminal
 * migration): the terminal owns its appearance — size lives terminal-locally in
 * [TerminalFontPreference] (`linexa_terminal`/`font_size`) and is applied by the
 * terminal adapter itself, the way a real terminal emulator owns its own font.
 * A terminal rendering knob is not an app-scoped setting, and an appearance
 * layer in front of the terminal was the wrong owner.
 *
 * Note the two shapes of accessor, which are not redundant:
 *  * `observeX(): Flow<T>` — for anything rendered. Every consumer stays live, so
 *    changing a setting in one place updates every screen showing it.
 *  * `currentX(): T` — **non-suspend**, for the callers that must read a value
 *    at a point where there is no coroutine and no recomposition to wait for:
 *    `LinexaTheme` (before the first frame — a suspend read would flash the wrong
 *    scheme). Both are backed by an in-memory value that is seeded from prefs at
 *    construction, so neither does disk I/O on the main thread.
 */
interface SettingsRepository {

    // ── Diagnostics ────────────────────────────────────────────────────────

    fun observeDebugEnabled(): Flow<Boolean>
    suspend fun isDebugEnabled(): Boolean
    suspend fun setDebugEnabled(enabled: Boolean)

    // ── Appearance ─────────────────────────────────────────────────────────

    /** The chosen light/dark policy. See [ThemeMode] — the default is `SYSTEM`. */
    fun observeThemeMode(): Flow<ThemeMode>

    /**
     * The chosen policy, read synchronously.
     *
     * Exists for `LinexaTheme`, which must decide which `ColorScheme` to use
     * before composing the first frame. Reading this asynchronously would render
     * one frame in the wrong scheme and then correct it — a visible flash on every
     * cold start, for a setting whose entire purpose is which colors appear.
     */
    fun currentThemeMode(): ThemeMode

    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * The chosen color-theme dimension. See [ColorTheme] — the default is
     * `ALPINE_BLUE`, and this is independent of [observeThemeMode] (the light/dark
     * axis). Changing one must not reset the other.
     */
    fun observeColorTheme(): Flow<ColorTheme>

    /**
     * The chosen color theme, read synchronously.
     *
     * Same rationale as [currentThemeMode]: `LinexaTheme` must resolve a
     * `ColorScheme` from `(colorTheme, darkTheme)` before the first frame — a
     * suspend read would flash the wrong palette on every cold start.
     */
    fun currentColorTheme(): ColorTheme

    suspend fun setColorTheme(theme: ColorTheme)

    // ── Language ───────────────────────────────────────────────────────────

    /**
     * The app-language preference: system-following by default, or an explicit
     * English/Persian override.
     *
     * Observed so the root composable can re-render the whole UI — strings,
     * layout direction and the language selector itself — the moment the user
     * picks a language, without restarting the activity or the Linux runtime.
     */
    fun observeAppLanguage(): Flow<AppLanguage>

    /**
     * The preference, read synchronously.
     *
     * Same reason as [currentThemeMode]: the root composition and any
     * non-composition string lookup must know the language before the first
     * frame, and a suspend read would flash the wrong language.
     */
    fun currentAppLanguage(): AppLanguage

    suspend fun setAppLanguage(language: AppLanguage)

    // ── Terminal UI ────────────────────────────────────────────────────────

    /**
     * The Recent Sessions arrangement: a two-column vertical grid or one large
     * horizontal card per page. Observed so switching the layout from Settings
     * updates an already-open overview immediately, without recreating any
     * terminal session.
     */
    fun observeRecentSessionsLayout(): Flow<RecentSessionsLayout>

    suspend fun setRecentSessionsLayout(layout: RecentSessionsLayout)

    /**
     * The complete keyboard-shortcut-toolbar state (preset, row mode, saved
     * custom rows). The active rows are derived from the preset, so built-in
     * presets stay canonical and an edit never silently rewrites one.
     */
    fun observeTerminalToolbar(): Flow<TerminalToolbarSettings>

    suspend fun setTerminalToolbar(settings: TerminalToolbarSettings)

    // ── What's New ─────────────────────────────────────────────────────────

    /**
     * The `versionCode` whose changelog the user has already seen, or
     * [com.fem.linexa.domain.whatsnew.WhatsNewDecision.NOTHING_ACKNOWLEDGED] when
     * nothing has been recorded yet.
     *
     * The *entire* persisted state of the What's New feature — one Int. There is
     * deliberately no "has dismissed" boolean and no per-version set: a single
     * high-water mark answers every question the feature asks ("is the running build
     * newer than what they have seen?"), and it cannot drift out of sync with itself
     * the way a flag plus a version could. It also means dismissing one version can
     * never disable the feature, because the next release's code is simply higher.
     *
     * Observed rather than read once, so acknowledging the card removes it from Home
     * immediately through the same flow the rest of this interface uses.
     */
    fun observeLastAcknowledgedWhatsNewVersion(): Flow<Int>

    /**
     * Record [versionCode] as seen.
     *
     * Called both when the user dismisses the card and, silently, to establish the
     * baseline on a fresh install (see `WhatsNewDecision.Outcome.EstablishBaseline`).
     * One writer for both, because "the newest version the user need not be told
     * about" is one fact regardless of how it came to be true.
     */
    suspend fun setLastAcknowledgedWhatsNewVersion(versionCode: Int)
}
