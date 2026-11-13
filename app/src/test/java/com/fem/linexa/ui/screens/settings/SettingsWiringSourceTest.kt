package com.fem.linexa.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 4 structural guard: the two settings this phase exposes must actually
 * be *applied*, not merely persisted.
 *
 * This exists because of the §1.19 audit's finding, which is the highest-frequency
 * defect class in this repo: a ViewModel/repository correctly built and a screen right
 * next to it still reading the old source. `assembleDebug` cannot catch it. Both Phase
 * 4 settings are exactly that shape — the theme is only real if `LinexaApp` passes a
 * resolved `darkTheme` into `LinexaTheme` (whose parameter has a default that silently
 * keeps the old behaviour), and the font size is only shared if `RealTerminalView`
 * stopped reading SharedPreferences itself.
 *
 * The font half changed owner in the 2026-08-29 terminal migration: the terminal now
 * owns its appearance outright (terminal-local `TerminalFontPreference` store, applied
 * by the adapter; no repository flow, no appearance ViewModel, no Settings row). The
 * pins below were rewritten for that contract — they now hold the *new* shape: exactly
 * one writer, no appearance layer, and no font logic in the `update` block.
 *
 * Same technique and rationale as `HomeTerminalOverlaySourceTest` (§1.128) and
 * `DistroAvatarPreviewSourceTest`: there is no Compose UI harness in this repo
 * (§1.124(a)), and the alternative is no coverage at all of the one thing the phase
 * exists to guarantee.
 */
class SettingsWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String = File(moduleRoot, "src/main/java/$path").readText()

    private val mainActivity: String get() = source("com/fem/linexa/MainActivity.kt")
    private val realTerminalView: String
        get() = source("com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")
    private val globalSettingsScreen: String
        get() = source("com/fem/linexa/ui/screens/settings/SettingsScreen.kt")

    // ── The theme setting is applied ───────────────────────────────────────

    @Test
    fun theRootComposablePassesAResolvedDarkThemeIntoTheTheme() {
        // LinexaTheme's `darkTheme` parameter DEFAULTS to isSystemInDarkTheme(), so
        // calling `LinexaTheme { }` compiles, runs, and silently ignores the setting.
        // That is precisely the §1.19 bug class.
        assertTrue(
            "LinexaApp must pass darkTheme explicitly, or the persisted mode is ignored",
            mainActivity.contains("LinexaTheme(darkTheme = darkTheme)")
        )
        assertFalse(
            "a bare LinexaTheme { } call would drop the setting",
            Regex("LinexaTheme\\s*\\{").containsMatchIn(mainActivity)
        )
    }

    @Test
    fun theModeComesFromTheRepositoryAndNotFromALocalDefault() {
        assertTrue(
            "the mode must come from the settings-backed ViewModel",
            mainActivity.contains("themeViewModel.themeMode")
        )
        assertTrue(
            "and must be observed, so changing it in Settings repaints the app",
            mainActivity.contains("collectAsState()")
        )
    }

    @Test
    fun systemModeIsResolvedInsideCompositionSoItStaysLive() {
        // SYSTEM is a live subscription to the device setting: resolving it in the
        // ViewModel would freeze whatever the system reported at construction, so a
        // device flipping to dark at sunset would not flip the app.
        assertTrue(
            "the system value must be read in composition",
            mainActivity.contains("isSystemInDarkTheme()")
        )
        assertTrue(
            "and combined with the persisted mode through ThemeMode's own rule",
            mainActivity.contains("resolveDarkTheme(isSystemInDarkTheme())")
        )
    }

    // ── The terminal owns its font (2026-08-29 terminal migration) ─────────
    //
    // The Phase-4 arrangement — a repository flow + appearance ViewModel + a
    // Global Settings stepper feeding the view through the `update` block — is
    // gone. The font now has exactly one owner (the terminal), one store
    // (TerminalFontPreference, terminal-local), and two writers (the pinch
    // gesture and the Haven ⋯ sheet), both through the adapter. These pins keep
    // it that way.

    @Test
    fun theTerminalIsTheOnlyFontWriterAndPersistsThroughTheTerminalStore() {
        // One owner means one store: the adapter persists through
        // TerminalFontPreference (terminal-local `linexa_terminal` prefs), and
        // no appearance ViewModel or repository sits in the path.
        assertTrue(
            "the pinch handler must persist through the terminal-local store",
            realTerminalView.contains("TerminalFontPreference.save(context, nextDp)")
        )
        assertFalse(
            "the appearance layer must not return: no appearance ViewModel may be referenced",
            realTerminalView.contains("TerminalAppearanceViewModel") ||
                realTerminalView.contains("appearanceViewModel")
        )
        assertFalse(
            "and the settings repository must not be a font source either",
            realTerminalView.contains("settingsRepository")
        )
    }

    @Test
    fun theTerminalTakesItsSizeFromTheSharedPreferenceRules() {
        assertTrue(
            "the size must be clamped by the one shared rule",
            realTerminalView.contains("TerminalFontPreference.sanitize(")
        )
        assertTrue(
            "and stepped by it too, so a pinch and the sheet slider move by the same amount",
            realTerminalView.contains("TerminalFontPreference.increase(") &&
                realTerminalView.contains("TerminalFontPreference.decrease(")
        )
    }

    @Test
    fun aPinchReportsThroughTheAdapterCallback() {
        assertTrue(
            "the gesture must report the new size so the sheet shows the current value",
            realTerminalView.contains("onFontSizeChanged(next)")
        )
    }

    @Test
    fun noExternalWriterCanResizeTheTerminalAnymore() {
        // The terminal owns its size, so the AndroidView `update` block must NOT
        // carry any font logic — only the session re-attach (the §1.145 rule the
        // repaint-bridge test pins). A size change applied from the update block
        // would mean a second writer reappeared.
        val update = realTerminalView.substringAfter("update = {").substringBefore("onRelease")
        assertFalse(
            "the update block must not apply font sizes — the terminal is the only writer",
            update.contains("setTextSize")
        )
        assertTrue(
            "and it must still hold the re-attach logic",
            update.contains("attachSession")
        )
    }

    @Test
    fun theGestureCallbackReadsTheTrackedSizeNotAStaleSnapshot() {
        // The TerminalViewClient is created once in the factory, so reading the
        // renderer's size there would freeze the first composition's value (and
        // the renderer's field is package-private anyway). The client reads the
        // tracked dp state at callback time through a lambda.
        assertTrue(
            "the callback must read the tracked state, not a captured value",
            realTerminalView.contains("currentFontDp = { fontSizeDpState.value }")
        )
    }

    // ── The screen renders through the pure content type ───────────────────

    @Test
    fun theSettingsRowsReadTheirTextFromThePureContentType() {
        assertTrue(
            "row text must come from GlobalSettingsContent so the data-dependent cases are asserted",
            globalSettingsScreen.contains("GlobalSettingsContent.from(")
        )
    }

    @Test
    fun clearingLogsRunsThroughTheStoreAndReportsWhatWasReclaimed() {
        val viewModel = source("com/fem/linexa/ui/screens/settings/GlobalSettingsViewModel.kt")
        assertTrue(
            "clearing must go through AppLogStore, which only ever deletes files it owns",
            viewModel.contains("appLogStore.clear()")
        )
        assertTrue(
            "file deletion must not run on the main thread",
            viewModel.contains("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "the stats must be re-read afterwards rather than assumed to be zero",
            viewModel.contains("refreshLogStats()")
        )
    }

    // ── Phase 4's three remaining sections are actually composed ───────────
    //
    // Each of these is a composable that compiles, tests green and renders nothing
    // whatsoever if the one call site is missing — the §1.19 defect class again, and for
    // the permissions section it is the worst variant: the rows were REMOVED from the
    // Workspace tab in the same change, so a missing call here does not degrade the
    // feature, it deletes it.

    @Test
    fun theAppGlobalPermissionsAreComposedInGlobalSettings() {
        assertTrue(
            "AccessSettingsGroup must be composed — the rows were removed from the Workspace Settings tab in the same change, so without this call the app has no permission surface at all",
            globalSettingsScreen.contains("AccessSettingsGroup()")
        )
    }

    @Test
    fun theHelpAndCommunityLinksAreComposedInGlobalSettings() {
        assertTrue(
            "HelpSettingsGroups must be composed",
            globalSettingsScreen.contains("HelpSettingsGroups()")
        )
    }

    @Test
    fun theAppIconAndVersionFooterIsComposedLast() {
        assertTrue(
            "AppInfoFooter must be composed",
            globalSettingsScreen.contains("AppInfoFooter()")
        )
        // "At the bottom" is the requirement, and it is meaningful: the footer identifies
        // the app rather than offering a setting, so a row below it would read as part of
        // the identification.
        val footerIndex = globalSettingsScreen.indexOf("AppInfoFooter()")
        val lastGroup = globalSettingsScreen.lastIndexOf("HSectionHeader(title =")
        assertTrue(
            "the footer must come after every settings group",
            footerIndex > lastGroup
        )
    }

    @Test
    fun theVersionComesFromTheBuildAndNotFromALiteral() {
        val footer = source("com/fem/linexa/ui/screens/settings/HelpSettingsSections.kt")
        assertTrue(
            "the version must be read from BuildConfig, so a version bump cannot leave a stale string behind",
            footer.contains("BuildConfig.VERSION_NAME") &&
                footer.contains("BuildConfig.VERSION_CODE")
        )
        assertTrue(
            "and formatted through the tested pure helper rather than concatenated inline",
            footer.contains("AppVersionLabel.format(")
        )
    }

    @Test
    fun buildConfigGenerationIsEnabledOrTheVersionCannotBeRead() {
        // AGP 8's default for this flag is false. Without it BuildConfig is not generated
        // at all, so the footer above would be an unresolved reference — which does fail
        // the build, but only in CI, and the failure names a symbol rather than the flag.
        val buildFile = File(moduleRoot, "build.gradle.kts").readText()
        assertTrue(
            "android.buildFeatures.buildConfig must be enabled (AGP 8 defaults it to false)",
            Regex("buildConfig\\s*=\\s*true").containsMatchIn(buildFile)
        )
    }

    @Test
    fun theHelpRowsShowNoRawUrlAndOpenTheCatalogTarget() {
        // Issue #11 (2026-09-03): the requirement REVERSED this pin — the
        // settings screen shows clean Help/Community rows, and the raw URL
        // must NOT be permanently printed underneath. The row subtitle is a
        // human phrase (subtitleRes); the destination is revealed on tap.
        val footer = source("com/fem/linexa/ui/screens/settings/HelpSettingsSections.kt")
        assertFalse(
            "the raw URL must not be rendered under a Help/Community row",
            footer.contains("HelpLink.displayUrl(link.url)")
        )
        assertTrue(
            "each row must carry its human subtitle",
            footer.contains("description = stringResource(link.subtitleRes())")
        )
        assertTrue(
            "and the URL opened must still be the catalog's, not a re-built string",
            footer.contains("openLink(context, link.url)")
        )
    }

    @Test
    fun theLinkIntentsCannotCrashOnADeviceWithNoHandler() {
        val footer = source("com/fem/linexa/ui/screens/settings/HelpSettingsSections.kt")
        assertTrue(
            "startActivity throws when nothing handles https/t.me, which is unusual but real",
            footer.contains("catch (_: ActivityNotFoundException)")
        )
    }
}
