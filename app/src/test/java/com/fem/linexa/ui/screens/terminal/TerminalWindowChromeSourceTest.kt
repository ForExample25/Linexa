package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural pins for the window-chrome pass: desktop control order, the
 * circular minimized-only dock, and the Recent Terminals motion/actions.
 * Compose has no UI-test harness in this repo, so (like the sibling
 * source tests) these assert against the source.
 */
class TerminalWindowChromeSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")
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

    private fun source(name: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/terminal/$name").readText()

    private val panel get() = source("WorkspaceTerminalPanel.kt")
    private val overview get() = source("TerminalSessionOverview.kt")

    @Test
    fun mergedBarOrdersControlsMinimizeRecentsThenClose() {
        val bar = panel.substringAfter("private fun TerminalWindowTopBar(")
            .substringBefore("private fun TerminalWindowSurface(")
        val minimize = bar.indexOf("terminal_window_minimize")
        val recents = bar.indexOf("terminals_recent_title")
        val close = bar.indexOf("terminal_window_close")
        assertTrue("all three controls present", minimize >= 0 && recents >= 0 && close >= 0)
        assertTrue("minimize < recents < close", minimize < recents && recents < close)
        assertTrue("close is the danger-styled control", bar.contains("danger = true"))
        assertTrue("the square shows the window-count badge", bar.contains("badge = windowCount"))
    }

    @Test
    fun minimizeGoesHomeWithoutTouchingTheSession() {
        assertTrue(panel.contains("onMinimize = onGoHome"))
        assertTrue(
            "the workspace wires go-home to closing both overview and terminal",
            File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
                .readText()
                .contains("onGoHome = {")
        )
    }

    @Test
    fun closingTheLastWindowUsesThePolicy() {
        assertTrue(panel.contains("TerminalChromePolicy.returnsHomeAfterClose("))
    }

    @Test
    fun controlsHaveHoverPressedAndTransitionStates() {
        assertTrue(panel.contains("collectIsHoveredAsState()"))
        assertTrue(panel.contains("collectIsPressedAsState()"))
        assertTrue(panel.contains("animateColorAsState("))
    }

    @Test
    fun noMinimizedDockOrWorkspaceBarRemains() {
        assertFalse(panel.contains("MinimizedDock("))
        assertFalse(panel.contains("TerminalDesktopTopBar("))
    }

    @Test
    fun overviewIsAPortraitGridWithOneCardDesignAndAnimatedSelection() {
        assertTrue("the overview is a fixed-column grid", overview.contains("LazyVerticalGrid("))
        assertTrue("cards derive height from the shared ratio", overview.contains("TerminalOverviewMetrics.CARD_ASPECT_RATIO"))
        assertTrue("one card design", overview.contains("private fun TerminalWindowCard("))
        assertFalse("the snapping carousel is gone", overview.contains("rememberSnapFlingBehavior("))
        assertFalse("the layout crossfade is gone", overview.contains("Crossfade("))
        assertFalse("the layout toggle is gone", overview.contains("TerminalOverviewLayout"))
        assertFalse("cards no longer carry min/max buttons", overview.contains("onToggleMaximize"))
        assertTrue("selection is an animated chrome change", overview.contains("animateColorAsState("))
    }
}
