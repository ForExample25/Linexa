package com.fem.linexa.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Source pins for the November 2026 defect pass.
 *
 * Compose UI tests cannot run in this project's terminal/CI harness, so the
 * layout-topology and wiring halves of these fixes are pinned on the sources,
 * the same way the other `*SourceTest` classes do. The behavioural halves live
 * in [com.fem.linexa.ui.screens.workspace.LauncherGridModelTest],
 * `SettingsLinksTest` and the existing PackageManager ViewModel tests.
 */
class LinexaRegressionFixesSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        error("module root not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$relative").readText()

    // ── Package Manager: sort resets the Installed list only on a change ──

    @Test
    fun packageManagerResetsInstalledScrollOnlyWhenTheSortChanges() {
        val screen = source("ui/screens/packagemanager/PackageManagerScreen.kt")
        val screenScope = screen.substringBefore("private fun InstalledTab(")
        assertTrue(
            "the Installed scroll reset must be screen-level, not tab-level",
            screenScope.contains("snapshotFlow { installedSort }")
        )
        assertTrue(
            "the initial snapshot emission must be skipped so tab returns keep their position",
            screenScope.contains(".drop(1)")
        )
        assertTrue(
            "a real sort change scrolls the Installed list to its first row",
            screenScope.contains("installedListState.scrollToItem(0)")
        )
        assertFalse(
            "the tab itself must not reset the list on every composition",
            screen.substringAfter("private fun InstalledTab(").contains("scrollToItem(0)")
        )
    }

    // ── Apps: category selection is idempotent; All is null ────────────────

    @Test
    fun appsCategorySelectionIsIdempotentAndAllIsNull() {
        val launcher = source("ui/screens/workspace/AppLauncherSegment.kt")
        assertTrue(
            "the chip callback must be able to express All as null",
            launcher.contains("onCategorySelect: (String?) -> Unit")
        )
        assertTrue(
            "the tapped category becomes the filter directly (no toggle)",
            launcher.contains("categoryFilter = it")
        )
        assertFalse(
            "the old toggle is what made repeated All taps blank the grid",
            launcher.contains("categoryFilter = if (categoryFilter == it)")
        )
        val allChip = launcher.substringAfter("label = stringResource(R.string.grid_chip_all)")
            .substringBefore("categories.forEach")
        assertTrue(
            "the All chip passes null, not an empty string",
            allChip.contains("onCategorySelect(null)")
        )
    }

    // ── Workspace Activity: no duplicated device/app resource strip ────────

    @Test
    fun workspaceActivityNoLongerRepeatsTheDeviceAndAppResourceStrip() {
        val sessions = source("ui/screens/workspace/SessionsSegment.kt")
        assertFalse(
            "the device/app context strip was removed",
            sessions.contains("SystemContextRow")
        )
        assertFalse(
            "its labels must not survive as dead references",
            sessions.contains("sessions_context_device_")
        )
        assertTrue(
            "the workspace's own resource card stays",
            sessions.contains("WorkspaceResourceCard(")
        )
    }

    // ── File Manager: both storage sections are always visible ─────────────

    @Test
    fun fileManagerShowsBothStorageSectionsWithoutCollapsibleHeaders() {
        val fileManager = source("ui/screens/filemanager/FileManagerScreen.kt")
        assertFalse(
            "the collapsible header composable is gone",
            fileManager.contains("CollapsibleSectionHeader")
        )
        assertFalse(
            "no expansion state may gate the storage sections",
            fileManager.contains("androidExpanded") ||
                fileManager.contains("linuxExpanded") ||
                fileManager.contains("placesExpanded")
        )
        assertTrue(
            "the Android storage section is still rendered",
            fileManager.contains("R.string.fm_android_title")
        )
        assertTrue(
            "the Linux workspace section is still rendered",
            fileManager.contains("R.string.fm_storage_title") &&
                fileManager.contains("R.string.fm_place_root")
        )
        assertTrue(
            "every Android folder action is preserved",
            fileManager.contains("onOpenAndroid(it.path)")
        )
        assertTrue(
            "every Linux location action is preserved",
            fileManager.contains("onOpenLinux(it.path)")
        )
    }

    // ── Bottom navigation: restrained selection polish ─────────────────────

    @Test
    fun bottomNavigationAddsSelectionPolishWithoutChangingTabSemantics() {
        val chrome = source("ui/components/haven/HavenChrome.kt")
        val nav = chrome.substringAfter("fun HBottomNav(")
            .substringBefore("private fun Modifier.navSelectionPill")
        assertTrue("the items keep tab semantics", nav.contains("Role.Tab"))
        assertTrue("the selected state is still reported", nav.contains("selected = isOn"))
        assertTrue("the sliding indicator is preserved", nav.contains("nav-indicator"))
        assertTrue(
            "the selected icon gets a bounded scale",
            nav.contains("label = \"nav-icon-scale\"") &&
                nav.contains("targetValue = if (isOn) 1.06f else 1f")
        )
        assertTrue(
            "taps get the shared press feedback",
            nav.contains("havenPressScale(interactionSource, pressedScale = 0.96f)")
        )
        assertTrue(
            "the bar keeps its hairline separation",
            nav.contains("drawLine(")
        )
        val pill = chrome.substringAfter("private fun Modifier.navSelectionPill")
            .substringBefore("private data class NavItem")
        assertTrue(
            "the selection capsule is inset instead of filling the cell",
            pill.contains("val insetX") && pill.contains("val insetY")
        )
    }

    // ── Root Terminal: sibling overlay over stable chrome ──────────────────

    @Test
    fun workspaceTerminalIsASiblingOverlayOverStableChrome() {
        val screen = source("ui/screens/workspace/WorkspaceScreen.kt")
        assertFalse(
            "the app bar must never be removed while the terminal is open",
            screen.contains("if (!terminalOpen)")
        )
        assertTrue(
            "the bar fades instead of leaving composition",
            screen.contains("alpha = appBarAlpha")
        )
        assertTrue(
            "the terminal is the shared overlay sibling",
            screen.contains("TerminalOverlay(") && screen.contains("visible = terminalOpen")
        )
        val beforeOverlay = screen.substringBefore("TerminalOverlay(")
        assertTrue(
            "the terminal desktop is attached before the overlay composes",
            beforeOverlay.contains("terminalsViewModel.attachBinder(workspace.id, binder)")
        )
        assertTrue(
            "the requested window is focused through the ViewModel's single authority " +
                "before the first overlay frame",
            beforeOverlay.contains("terminalsViewModel.requestActiveTerminal(")
        )
        assertTrue(
            "the workspace content stays composed under the overlay",
            screen.contains("WorkspaceIdentityHeader(") &&
                screen.contains("AppLauncherSegment(")
        )
    }
}
