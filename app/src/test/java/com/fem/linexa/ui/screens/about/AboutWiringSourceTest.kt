package com.fem.linexa.ui.screens.about

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.130 structural guard: the About screen must be *reachable*, and it must render
 * through the pure content type.
 *
 * This is the §1.19 defect class, which is the highest-frequency one in this repo: a
 * screen, a ViewModel and a catalog all correctly written, and nothing that opens them.
 * `assembleDebug` cannot catch it — an unreferenced composable compiles perfectly. For an
 * attribution surface the failure is worse than usual, because the *point* of the feature
 * is that a user can see it; an About screen with no entry point satisfies no obligation
 * while looking, in the source tree, exactly like one that does.
 *
 * Same technique and rationale as `SettingsWiringSourceTest` (§1.129) and
 * `HomeTerminalOverlaySourceTest` (§1.128): there is no Compose UI harness in this repo
 * (§1.124(a)), so the alternative is no coverage at all of the one thing the phase exists
 * to guarantee.
 */
class AboutWiringSourceTest {

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

    private val navGraph: String get() = source("com/fem/linexa/navigation/NavGraph.kt")
    // HAVEN rebuild: Settings is now the SettingsScreen tab; HomeScreen is gone.
    private val settingsScreen: String
        get() = source("com/fem/linexa/ui/screens/settings/SettingsScreen.kt")
    private val aboutScreen: String get() = source("com/fem/linexa/ui/screens/about/AboutScreen.kt")
    private val appModule: String get() = source("com/fem/linexa/di/AppModule.kt")

    // ── The screen is reachable ────────────────────────────────────────────

    @Test
    fun theRouteIsRegisteredInTheNavGraph() {
        assertTrue(
            "a route constant with no composable() is not a destination",
            navGraph.contains("composable(Routes.ABOUT)")
        )
        assertTrue(
            "and the destination must actually be the About screen",
            navGraph.contains("AboutScreen(")
        )
    }

    @Test
    fun theRouteIsNavigatedToFromHome() {
        assertTrue(
            "nothing navigates to the About route",
            navGraph.contains("navigate(Routes.ABOUT)")
        )
        assertTrue(
            "the callback must be handed to HomeScreen, which owns the Settings tab",
            navGraph.contains("onOpenAbout =")
        )
    }

    @Test
    fun theSettingsTabAcceptsTheCallback() {
        // The Settings tab is a top-level destination; the About row must both
        // accept and actually invoke the callback (the §1.19 bug shape).
        assertTrue(
            "SettingsScreen must accept the callback",
            settingsScreen.contains("onOpenAbout: () -> Unit")
        )
        assertTrue(
            "and its About row must invoke it",
            settingsScreen.contains("onClick = onOpenAbout")
        )
        // UI/UX Phase 3: the Performance Dashboard row was removed; the Device
        // & System Information row must still be threaded.
        assertTrue(
            "the Device & System Information row must be threaded too",
            settingsScreen.contains("onClick = onOpenDeviceInfo")
        )
        assertFalse(
            "the removed Performance Dashboard callback must not reappear",
            settingsScreen.contains("onOpenPerformance")
        )
    }

    @Test
    fun theSettingsRowInvokesTheCallbackRatherThanDoingNothing() {
        assertTrue(
            "the About row must exist in Settings",
            settingsScreen.contains("R.string.global_settings_about_title")
        )
        assertTrue(
            "and its tap must open the destination",
            settingsScreen.contains("onClick = onOpenAbout")
        )
    }

    // ── The loader is provided, so the ViewModel can be constructed ─────────

    @Test
    fun theLicenseTextLoaderIsBoundToTheRealAssetManager() {
        // AboutViewModel is @HiltViewModel and injects LicenseTextLoader; without a
        // provider the screen crashes on open, which no compile step catches.
        assertTrue(
            "LicenseTextLoader must be provided",
            appModule.contains("fun provideLicenseTextLoader(")
        )
        assertTrue(
            "and backed by the app's real assets, not a stub",
            appModule.contains("application.assets.open(path)")
        )
    }

    // ── The screen renders through the pure content type ───────────────────

    @Test
    fun theListReadsItsTextFromThePureContentType() {
        assertTrue(
            "row text must come from AboutContent so the data-dependent cases are asserted",
            aboutScreen.contains("AboutContent.from()")
        )
        assertFalse(
            "the screen must not reach into the catalog and format text itself",
            aboutScreen.contains("AttributionCatalog.")
        )
    }

    @Test
    fun theContentIsBuiltOncePerCompositionScope() {
        // Derived from a compile-time constant; rebuilding it on every recomposition of a
        // scrolling list is pure waste.
        assertTrue(
            "AboutContent must be remembered",
            aboutScreen.contains("remember { AboutContent.from() }")
        )
    }

    @Test
    fun theLicenseTextIsReadOffTheMainThread() {
        val viewModel = source("com/fem/linexa/ui/screens/about/AboutViewModel.kt")
        assertTrue(
            "a ~41 KB asset read must not run on the main thread",
            viewModel.contains("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "a result arriving after the user moved on must not clobber the new state",
            viewModel.contains("current.ref != ref")
        )
    }

    @Test
    fun backClosesAnOpenDocumentBeforeLeavingTheScreen() {
        // One destination, two views (§1.126's rationale): the document is a drill-down
        // with a single exit, so back must unwind it rather than leaving About entirely.
        assertTrue(
            "system back must close the document first",
            aboutScreen.contains("BackHandler(enabled = showingLicense)")
        )
        assertTrue(
            "and the app bar's up action must behave the same way",
            aboutScreen.contains("if (showingLicense) viewModel.closeLicense() else onBack()")
        )
    }

    @Test
    fun aLicenseWithNoDocumentAndAnUnreadableOneAreReportedDifferently() {
        // Collapsing them would report a packaging fault as a normal state.
        assertTrue(
            aboutScreen.contains("R.string.about_license_no_document")
        )
        assertTrue(
            aboutScreen.contains("R.string.about_license_unavailable")
        )
    }

    @Test
    fun theDocumentIsSelectableAndMonospaced() {
        // Verbatim legal text: the user must be able to copy the terms exactly, and the
        // paragraphs are single unwrapped lines that a proportional font reflows badly.
        assertTrue(aboutScreen.contains("SelectionContainer"))
        assertTrue(aboutScreen.contains("FontFamily.Monospace"))
    }
}
