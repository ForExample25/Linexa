package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Source pins for the UI/UX refinement pass (Apps interaction states, File
 * Manager navigation). These guard wiring that a compile cannot: which surface
 * a not-installed app opens, that the management menu is installed-only, and
 * that the Filesystem place is a real navigation.
 */
class UiRefinementSourcePinsTest {

    private lateinit var root: File

    @Before
    fun locate() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) { root = dir; return }
            dir = dir.parentFile
        }
        root = File(".")
    }

    private fun src(p: String) = File(root, "src/main/java/com/fem/linexa/$p").readText()

    private val launcher get() = src("ui/screens/workspace/AppLauncherSegment.kt")
    private val workspace get() = src("ui/screens/workspace/WorkspaceScreen.kt")
    private val fmScreen get() = src("ui/screens/filemanager/FileManagerScreen.kt")
    private val fmVm get() = src("ui/screens/filemanager/FileManagerViewModel.kt")

    @Test
    fun notInstalledAppsOpenTheInstallSheetNeverTheManagementMenu() {
        assertTrue(launcher.contains("AppInstallSheet("))
        // Long-press on a non-installed app must route to the sheet.
        assertTrue(launcher.contains("menuTargetId = null\n                        installTarget = appId"))
        // The menu's items are empty unless the MEASURED state is INSTALLED,
        // and never while a package operation is running.
        assertTrue(launcher.contains("expanded = menuOpen && isInstalled && !jobRunning"))
        assertTrue(launcher.contains("items = if (!isInstalled || jobRunning) emptyList()"))
    }

    @Test
    fun uninstallStillRequiresConfirmation() {
        assertTrue(launcher.contains("AppConfirmDialog("))
        assertTrue(launcher.contains("onUninstall = { uninstallTarget = it.id }"))
    }

    @Test
    fun appsTabOwnsTheContentAreaWithoutNestingAScrollable() {
        assertTrue(launcher.contains("LazyVerticalGrid("))
        // 2026-11 motion pass: the four segments live inside an AnimatedContent
        // (directional quarter-slide + fade) whose container OWNS the content
        // region below the fixed header + selector. The old conditional
        // `if (selectedSegment == 0) Modifier … else verticalScroll(...)` share
        // is gone: the Apps branch renders the launcher's grid directly, and
        // the other three wrap themselves in SegmentScrollPage (a plain
        // vertical-scroll page) — so the lazy grid is never nested inside a
        // vertically scrollable Column.
        assertTrue(workspace.contains("targetState = selectedSegment"))
        assertFalse(
            "the old conditional scroll modifier must not come back",
            workspace.contains("verticalScroll(contentScrollState")
        )
        val segmentBody = workspace.substringAfter(") { segment ->")
            .substringBefore("/**\n * The three non-launcher segments")
        assertTrue(workspace.contains("0 -> AppLauncherSegment("))
        // The launcher branch itself must stay scroller-free.
        val appBranch = segmentBody.substringAfter("0 -> AppLauncherSegment(")
            .substringBefore("1 -> SegmentScrollPage(")
        assertFalse(
            "the Apps branch must render the grid directly, not wrapped in a vertical scroll",
            appBranch.contains("verticalScroll")
        )
    }

    @Test
    fun theToolPairSitsAboveTheSearchFieldAndPushesExistingDestinations() {
        // Priority-1 spec: Packages / Files come from the workspace's Apps
        // area, side by side, ABOVE the launcher search field; they push the
        // SAME WorkspaceRoutes destinations the old Settings-tab Tools rows
        // navigated to (no screen/ViewModel/implementation duplicates).
        val buttons = launcher.indexOf("LauncherToolButtons(")
        val search = launcher.indexOf("LauncherSearchAndChips(")
        assertTrue("the tool pair must exist in the launcher", buttons >= 0)
        assertTrue(
            "the Packages/Files buttons must be composed above the search field",
            buttons < search
        )
        // The destinations are the existing routes — pushed by the button
        // callbacks that WorkspaceScreen forwards to its graph.
        assertTrue(workspace.contains("onOpenPackageManager = onOpenPackageManager"))
        assertTrue(workspace.contains("onOpenFileManager = onOpenFileManager"))
        assertFalse(
            "the launcher must not duplicate actual package/file management — it only opens the existing screens",
            launcher.contains("PackageManagerViewModel") || launcher.contains("FileManagerViewModel")
        )
    }

    @Test
    fun theActivitySegmentDropsTheProcessListUiAndKeepsTheEngine() {
        // The in-card expandable process list (Show/Hide Processes, kill
        // dialogs, subprocess rows) is gone from session cards; the engine —
        // kinds, metrics, counts — stays, and Task Manager still renders the
        // full process list via its own screen.
        val sessions = src("ui/screens/workspace/SessionsSegment.kt")
        assertFalse(
            "the expandable process-list UI must stay removed",
            sessions.contains("tm_processes_expanded") ||
                sessions.contains("tm_processes_collapsed") ||
                sessions.contains("processesExpanded")
        )
        assertTrue(
            "the session engine stays (per-session process counts are card metadata)",
            sessions.contains("processCount")
        )
        // The processes live in Task Manager's screen, not the session cards.
        val tm = src("ui/screens/taskmanager/TaskManagerScreen.kt")
        assertTrue(
            "Task Manager keeps rendering the real process list",
            tm.contains("private fun ProcessCard(") && tm.contains("WorkspaceProcessSample")
        )
    }

    @Test
    fun filesystemPlaceIsARealNavigationNotAnOverviewRefresh() {
        assertTrue(fmVm.contains("val showOverview"))
        assertTrue(fmVm.contains("fun openLinuxPlace(path: String)"))
        assertTrue(fmVm.contains("navigateTo(path, force = true)"))
        assertTrue(fmScreen.contains("onOpenLinux = { viewModel.openLinuxPlace(it) }"))
        // The overview is gated by explicit state, not by `currentPath == "/"`.
        assertFalse(fmScreen.contains("val atRoot = currentPath == \"/\""))
    }
}
