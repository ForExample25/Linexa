package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the **workspace** terminal desktop.
 *
 * Why a source test: the contract is about layout topology and about chrome
 * that must no longer exist, and verifying either behaviourally needs Compose
 * UI testing or Robolectric, neither of which covers these surfaces in this
 * repo. The assertions are deliberately split between *negative* (the old
 * single-session chrome and the terminal route are gone) and *positive* (the
 * desktop window manager, per-window title bars, the real pty view and the
 * Recent Terminals overview are actually composed), so this cannot pass
 * because the terminal was simply deleted.
 *
 * The behavioural half lives in `TerminalWindowManagerTest`,
 * `TerminalsViewModelTest` and `TerminalSessionOverviewTest`.
 */
class WorkspaceTerminalOverlaySourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
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

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val screen: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")

    private val graph: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")

    private val panel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")

    private val havenTerminal: String
        get() = source("src/main/java/com/fem/linexa/ui/components/haven/HavenTerminal.kt")

    private fun codeOnly(text: String): String =
        text.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    // ── Negative: the terminal is no longer a destination ────────────────

    @Test
    fun thereIsNoTerminalRoute() {
        assertFalse(
            "the workspace terminal must not be a nav destination",
            graph.contains("const val TERMINAL =") || graph.contains("fun terminal(terminalId")
        )
        assertFalse(
            "and nothing may navigate to one",
            graph.contains("WorkspaceRoutes.terminal(")
        )
    }

    @Test
    fun theOldTerminalScreenIsGone() {
        assertFalse(
            "TerminalScreen.kt must be deleted",
            File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/terminal/TerminalScreen.kt").exists()
        )
        assertFalse(
            "the bottom shortcut bar it hosted must be deleted with it",
            File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/terminal/TerminalShortcutBar.kt").exists()
        )
    }

    @Test
    fun theDeepLinkNoLongerNeedsATerminalRoute() {
        assertTrue(
            "the requested terminal must open the surface directly",
            graph.contains("terminalOpen = true")
        )
        assertFalse(
            "it must not navigate to a terminal route",
            graph.contains("navigate(WorkspaceRoutes.terminal")
        )
    }

    // ── Negative: the old single-session chrome is gone ──────────────────

    @Test
    fun theOldSessionBarOverflowAndKeyboardIconAreGone() {
        assertFalse(
            "the old 46dp session bar must not be composed by the workspace terminal",
            panel.contains("HavenTerminalBar(")
        )
        assertFalse(
            "the old square session-switching icon/dots are gone",
            panel.contains("HavenSessionDot(")
        )
        assertFalse(
            "the three-dot sheet is gone",
            panel.contains("HavenTerminalSheet(") || panel.contains("ic_haven_more_vert")
        )
        assertFalse(
            "the keyboard toolbar icon is gone; the extra-keys bar is host-only",
            panel.contains("onKeyboard = {") || panel.contains("ic_haven_keyboard")
        )
    }

    // ── Positive: the Haven terminal surface ────────────────────────────

    @Test
    fun theWorkspaceBodyRendersTheHavenTerminalSurfaceWhenOpen() {
        assertTrue(
            "the terminal branch must render through the workspace terminal panel",
            codeOnly(screen).contains("WorkspaceTerminalPanel(")
        )
        assertTrue(
            "and the panel must paint the surface opaque, keyboard-aware, always dark",
            panel.contains(".background(h.term)") &&
                panel.contains(".imePadding()")
        )
        assertTrue(
            "the shared Haven surface keeps its own opaque/IME contract for the " +
                "other terminal host",
            havenTerminal.contains(".background(h.term)") &&
                havenTerminal.contains(".imePadding()")
        )
    }

    @Test
    fun oneMergedTopBarCarriesTheWindowControls() {
        assertTrue(
            "the desktop must compose ONE merged top bar",
            panel.contains("TerminalWindowTopBar(")
        )
        assertFalse(
            "the separate workspace bar and per-window title bar are gone",
            panel.contains("TerminalDesktopTopBar(") || panel.contains("TerminalWindowTitleBar(")
        )
        assertTrue(
            "the window title comes from the window's resolved identity",
            panel.contains("text = window?.title ?: workspaceName")
        )
        for (action in listOf(
            "terminal_window_close",
            "terminal_window_minimize",
            "terminals_recent_title"
        )) {
            assertTrue("missing window control: $action", panel.contains(action))
        }
        assertTrue(
            "minimize only leaves the screen (go home); it never touches the pty",
            panel.contains("onMinimize = onGoHome")
        )
        assertFalse(
            "the square is Recents now; maximize/restore UI is gone",
            panel.contains("toggleMaximizeTerminal") ||
                panel.contains("R.string.terminal_window_maximize)")
        )
    }

    @Test
    fun closingARunningWindowIsConfirmedAndGoesThroughTheViewModel() {
        assertTrue(
            "the destructive close must be gated on live process state",
            panel.contains("terminalsViewModel.requiresCloseConfirmation(")
        )
        assertTrue(
            "the confirmation dialog exists",
            panel.contains("terminal_window_close_title")
        )
        val occurrences = Regex("closeTerminal").findAll(panel).count()
        assertTrue(
            "every close must go through the terminals ViewModel (found $occurrences)",
            occurrences >= 1
        )
        assertFalse(
            "the panel must never destroy a pty directly",
            codeOnly(panel).contains("ptySession.destroy()")
        )
    }

    @Test
    fun theRecentTerminalsOverviewIsAGridWithHoistedState() {
        assertTrue(
            "the overview must be composed as a peer layer",
            panel.contains("TerminalSessionOverview(")
        )
        assertTrue(
            "the overview receives the live window list",
            panel.contains("windows = windows")
        )
        assertTrue(
            "grid scroll is hoisted above the overview layer",
            panel.contains("val overviewGridState = rememberLazyGridState()") &&
                panel.contains("gridState = overviewGridState")
        )
        assertFalse(
            "the persisted carousel/grid layout preference is gone",
            panel.contains("TerminalDesktopPreference") ||
                panel.contains("TerminalOverviewLayout") ||
                panel.contains("overviewLayout")
        )
    }

    @Test
    fun systemBackDismissesOneLayerAndOnlyWhileOpen() {
        assertTrue(
            "back must be intercepted only while the surface is open, or the workspace-exit " +
                "confirmation never gets the press",
            screen.contains("BackHandler(enabled = terminalOpen)")
        )
        assertTrue(
            "the arrow and system back must dismiss the same single layer",
            screen.contains(
                "if (showOverview) onShowOverviewChange(false) else onTerminalOpenChange(false)"
            )
        )
    }

    @Test
    fun theSessionIdentityIsHoistedOutOfTheSurface() {
        assertTrue(
            "the graph must own which terminal is active, and survive process death",
            graph.contains("var activeTerminalId by rememberSaveable")
        )
        assertTrue(
            "and whether the surface is open",
            graph.contains("var terminalOpen by rememberSaveable")
        )
        assertFalse(
            "the panel must not own the active terminal id",
            panel.contains("var activeTerminalId")
        )
    }

    // ── Positive: the terminal mechanism itself is untouched ─────────────

    @Test
    fun theExistingTerminalMechanismIsUnchanged() {
        assertTrue("same pty view", panel.contains("RealTerminalView("))
        assertTrue(
            "same per-terminal ViewModel",
            panel.contains("viewModel.attachBinder(workspaceId, terminalId, binder)")
        )
        assertTrue(
            "same session list",
            panel.contains("terminalsViewModel.attachBinder(workspaceId, binder)")
        )
        assertTrue(
            "the last-active record must still be made",
            panel.contains("binder.markTerminalActive(workspaceId, terminalId)")
        )
        assertTrue(
            "the chain logging must survive",
            panel.contains("TerminalChain")
        )
    }

    @Test
    fun switchingAndMinimizingNeverRecreateOrCloseAPty() {
        assertTrue(
            "switching windows must detach the old view, never open a new session",
            panel.contains("viewModel.detachBinder()")
        )
        assertFalse(
            "the desktop must not open a pty when a window is focused",
            codeOnly(panel).contains("openTerminal(")
        )
        assertTrue(
            "the overview-only preview polling must be tied to overview visibility",
            panel.contains("terminalsViewModel.setOverviewVisible(")
        )
    }

    @Test
    fun theNeverZeroTerminalsInvariantStillLands() {
        assertTrue(
            panel.contains("terminalsViewModel.consumeCreatedTerminal()")
        )
        assertTrue(
            "a closed-but-with-siblings terminal must fall back to a live one",
            panel.contains("Rendered terminal")
        )
    }
}
