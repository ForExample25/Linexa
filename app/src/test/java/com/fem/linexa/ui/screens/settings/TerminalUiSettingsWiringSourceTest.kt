package com.fem.linexa.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wiring pins for the 2026-11 terminal settings pass.
 *
 * The behaviour (model edits, persistence, layout metrics) is covered by JVM
 * tests; these assertions cover the Compose/navigation seams that only exist in
 * source: a Settings row must actually navigate, both terminal panels must read
 * the SAME live toolbar state, and changing either preference must never sit in
 * a composition key that recreates the pty attachment.
 */
class TerminalUiSettingsWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/settings/SettingsScreen.kt")
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

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val settings: String
        get() = source("com/fem/linexa/ui/screens/settings/SettingsScreen.kt")

    private val layoutScreen: String
        get() = source("com/fem/linexa/ui/screens/settings/TerminalLayoutSettingsScreen.kt")

    private val toolbarScreen: String
        get() = source("com/fem/linexa/ui/screens/settings/TerminalToolbarSettingsScreen.kt")

    private val navGraph: String
        get() = source("com/fem/linexa/navigation/NavGraph.kt")

    private val panel: String
        get() = source("com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")

    private val managementPanel: String
        get() = source("com/fem/linexa/ui/screens/terminal/ManagementTerminalPanel.kt")

    private val workspaceScreen: String
        get() = source("com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")

    @Test
    fun settingsExposesBothTerminalPreferences() {
        assertTrue(
            "Settings renders the Terminal group",
            settings.contains("R.string.global_settings_terminal_group")
        )
        assertTrue(
            "Recent Sessions opens its layout screen",
            settings.contains("R.string.terminal_settings_recent_layout_title") &&
                settings.contains("onClick = onOpenTerminalLayout")
        )
        assertTrue(
            "the toolbar row opens its editor",
            settings.contains("R.string.terminal_settings_toolbar_title") &&
                settings.contains("onClick = onOpenTerminalToolbar")
        )
        assertTrue(
            "the row summaries are live state, not fixed copy",
            settings.contains("recentSessionsLayoutSummary(recentSessionsLayout)") &&
                settings.contains("toolbarSummary(terminalToolbar)")
        )
    }

    @Test
    fun bothSettingsDestinationsAreRealBackStackEntries() {
        assertTrue(
            "routes are declared",
            navGraph.contains("TERMINAL_LAYOUT = \"settings/terminal/recent-sessions\"") &&
                navGraph.contains("TERMINAL_TOOLBAR = \"settings/terminal/toolbar\"")
        )
        assertTrue(
            "Settings navigates to each",
            navGraph.contains("navigate(Routes.TERMINAL_LAYOUT)") &&
                navGraph.contains("navigate(Routes.TERMINAL_TOOLBAR)")
        )
        assertTrue(
            "both composables pop back to Settings",
            navGraph.contains("composable(Routes.TERMINAL_LAYOUT)") &&
                navGraph.contains("composable(Routes.TERMINAL_TOOLBAR)") &&
                navGraph.contains("TerminalLayoutSettingsScreen(onBack = { navController.popBackStack() })") &&
                navGraph.contains("TerminalToolbarSettingsScreen(onBack = { navController.popBackStack() })")
        )
    }

    @Test
    fun layoutScreenOffersTwoVisualChoicesThatApplyImmediately() {
        assertTrue(layoutScreen.contains("RecentSessionsLayout.VERTICAL"))
        assertTrue(layoutScreen.contains("RecentSessionsLayout.HORIZONTAL"))
        assertTrue(
            "a tap writes the app-scoped setting",
            layoutScreen.contains("viewModel.setRecentSessionsLayout(RecentSessionsLayout.VERTICAL)") &&
                layoutScreen.contains("viewModel.setRecentSessionsLayout(RecentSessionsLayout.HORIZONTAL)")
        )
        assertTrue(
            "previews use the real overview geometry",
            layoutScreen.contains("TerminalOverviewMetrics.CARD_ASPECT_RATIO")
        )
        assertTrue(
            "the active choice is announced to accessibility",
            layoutScreen.contains("this.selected = isSelected") &&
                layoutScreen.contains("Role.RadioButton")
        )
    }

    @Test
    fun theToolbarEditorRendersTheRealBarAndEverySupportedPreset() {
        assertTrue(
            "presets come from the closed domain enum",
            toolbarScreen.contains("TerminalToolbarPreset.entries")
        )
        assertTrue(
            "row modes come from the domain enum",
            toolbarScreen.contains("TerminalToolbarRowMode.entries")
        )
        assertTrue(
            "the live preview is the real terminal bar",
            toolbarScreen.contains("HavenExtraKeys(") &&
                toolbarScreen.contains("config = config")
        )
        assertTrue(
            "preview bars do not add the navigation-bar inset",
            toolbarScreen.contains("applyNavigationBarsPadding = false")
        )
        assertTrue(
            "each preset row renders a real toolbar component",
            toolbarScreen.substringAfter("private fun PresetRow(").contains("HavenExtraKeys(")
        )
        assertTrue(
            "the editor exposes add / remove / reorder / assign / restore",
            toolbarScreen.contains("viewModel.addToolbarShortcut(") &&
                toolbarScreen.contains("viewModel.removeToolbarShortcut(") &&
                toolbarScreen.contains("viewModel.moveToolbarShortcut(") &&
                toolbarScreen.contains("viewModel.moveToolbarShortcutToOtherRow(") &&
                toolbarScreen.contains("viewModel::restoreToolbarDefaults")
        )
    }

    @Test
    fun bothTerminalPanelsReadTheLiveToolbar() {
        assertTrue(
            "the workspace panel observes the shared settings",
            panel.contains("uiSettingsViewModel.toolbarSettings.collectAsState()")
        )
        assertTrue(
            "the workspace panel paints the active configuration",
            panel.contains("config = toolbarSettings.config")
        )
        assertTrue(
            "the management shell paints the same active configuration",
            managementPanel.contains("settingsViewModel.toolbarSettings.collectAsState()") &&
                managementPanel.contains("config = toolbar.config")
        )
        assertTrue(
            "modifier keys arm sticky state by domain identity",
            panel.contains("key.modifier?.let { terminalModifiers.toggle(it) }")
        )
        assertTrue(
            "control-letter shortcuts dispatch real meta state",
            panel.contains("key.dispatchMetaState()")
        )
    }

    @Test
    fun theLayoutIsObservedLiveButNeverRecreatesThePtyAttachment() {
        assertTrue(
            "the panel observes the selected layout",
            panel.contains("uiSettingsViewModel.recentSessionsLayout.collectAsState()")
        )
        assertTrue(
            "the overview receives it directly",
            panel.contains("layout = recentSessionsLayout")
        )
        val attach = panel.substringAfter("// Attach the one rendered TerminalView to exactly the active window.")
            .substringBefore("// Preview polling")
        assertTrue(
            "the pty attachment is keyed on workspace/window/binder only",
            attach.contains("LaunchedEffect(workspaceId, activeWindow?.terminalId, binder)")
        )
        assertFalse(
            "a layout preference must never sit in the attach key",
            attach.contains("recentSessionsLayout") || attach.contains("toolbarSettings")
        )
    }

    @Test
    fun imePaddingIsConfinedToTheTerminalColumn() {
        assertTrue(
            "the panel owns its terminal surface",
            panel.contains(".background(h.term)")
        )
        assertTrue(
            "the terminal column applies the navigation-bar ∪ IME inset once",
            panel.contains(".windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))")
        )
        assertFalse(
            "WorkspaceScreen no longer wraps the panel in an inset-padded surface",
            workspaceScreen.contains("HavenTerminalSurface(")
        )
        val overviewCall = panel.substringAfter("TerminalOverviewLayer(")
            .substringBefore("val pendingClose")
        assertFalse(
            "the overview layer has no IME padding",
            overviewCall.contains("imePadding")
        )
    }

    @Test
    fun workspaceNameAndTechnicalLabelsAreNotRepeatedInTheWindowBar() {
        val topBar = panel.substringAfter("private fun TerminalWindowTopBar(")
            .substringBefore("private fun TerminalWindowSurface(")
        assertFalse(
            "the window bar no longer prints the workspace name beside the window title",
            topBar.contains("text = workspaceName")
        )
        assertTrue(
            "the workspace name survives only as the no-window fallback",
            topBar.contains("window?.title ?: workspaceName")
        )
        assertFalse(
            "the bar does not print technical display labels",
            topBar.contains("window.displayLabel")
        )
    }

    @Test
    fun settingsCardsDoNotDoublePadTheirRows() {
        val swatch = settings.substringAfter("private fun ColorSwatchRow(")
        assertFalse(
            "ColorSwatchRow lives inside a padded HCard and must not add a second horizontal inset",
            swatch.contains(".padding(horizontal = 20.dp")
        )
    }
}
