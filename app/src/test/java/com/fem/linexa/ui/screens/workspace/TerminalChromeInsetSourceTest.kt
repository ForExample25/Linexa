package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 (Issue 1) — the workspace terminal lives INSIDE the app chrome.
 *
 * Why a source test: the requirement is about layout topology, window-inset
 * ownership and the *absence* of system-bar manipulation — structural
 * properties this repo's JVM-only harness (§1.124(a): no Robolectric, no
 * Compose UI test) can only pin on the source.
 *
 * The contract pinned here (the exact defects this closes):
 *
 *  - the Haven screen owns the status-bar strip (theme background behind it,
 *    content inset below it) — the terminal may never paint behind the status
 *    bar again;
 *  - the workspace app bar (the header row with back / Terminal / Desktop) is
 *    composed ONCE, outside the terminal body swap — it stays visible while
 *    the terminal overlay is open;
 *  - the terminal surface is rendered inside the content region between the
 *    app bar and the shortcut bar, still opaque, keyboard-aware, always dark
 *    and terminal-color-owned. Since the 2026-11 Settings pass the workspace
 *    panel owns that surface, so the Recent Sessions overview can stay outside
 *    its IME-padded terminal column;
 *  - no per-screen status-bar appearance patching and no immersive /
 *    fullscreen system-bar manipulation exists anywhere on the terminal path.
 */
class TerminalChromeInsetSourceTest {

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

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val screen: String
        get() = source("com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")

    private val havenTerminal: String
        get() = source("com/fem/linexa/ui/components/haven/HavenTerminal.kt")

    /** Just the [HavenTerminalSurface] function body. */
    private val surfaceFn: String
        get() = havenTerminal.substringAfter("fun HavenTerminalSurface(")
            .substringBefore("\n/**")

    @Test
    fun theHavenScreenOwnsTheStatusBarStrip() {
        assertFalse(
            "the workspace screen must not opt out of the status-bar inset — " +
                "the app (theme background) owns the status strip, the terminal " +
                "must never paint behind it again",
            screen.contains("HavenScreen(statusBarInsets = false)")
        )
        assertTrue(
            "the screen uses HavenScreen's default status-bar ownership",
            Regex("HavenScreen\\s*\\{").containsMatchIn(screen)
        )
    }

    @Test
    fun theWorkspaceAppBarIsComposedOutsideTheTerminalSwap() {
        val appBar = screen.indexOf("ic_haven_arrow_back")
        val bodySwap = screen.indexOf("AnimatedContent(")
        assertTrue("the app bar must exist", appBar >= 0)
        assertTrue("the terminal body swap must exist", bodySwap >= 0)
        assertTrue(
            "the app bar must be composed BEFORE (outside) the terminal body " +
                "swap so it stays visible while the terminal overlay is open",
            appBar < bodySwap
        )
        assertEquals(
            "the app bar must be composed exactly once — not once per branch",
            1,
            Regex("ic_haven_arrow_back").findAll(screen).count()
        )
    }

    @Test
    fun theTerminalSurfaceIsConfinedToTheContentRegion() {
        val appBar = screen.indexOf("ic_haven_arrow_back")
        val surface = screen.indexOf("WorkspaceTerminalPanel(")
        assertTrue("the terminal panel must be rendered", surface >= 0)
        assertTrue(
            "the terminal panel must render below (after) the app bar in the " +
                "layout — the content region between app bar and shortcut bar",
            surface > appBar
        )
        // 2026-11 Settings pass: the panel owns the surface so the Recent
        // Sessions overview can stay outside the IME-padded column. Its
        // contract is the same: opaque, always dark, keyboard-aware.
        val panel = source("com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")
        assertTrue(
            "the panel paints the terminal surface",
            panel.contains(".background(h.term)")
        )
        assertTrue(
            "the panel confines IME padding to the terminal column",
            panel.contains(".imePadding()")
        )
    }

    @Test
    fun theTerminalSurfaceContractIsUnchanged() {
        assertTrue(
            "the terminal region stays opaque, always dark, terminal-color-owned",
            surfaceFn.contains(".background(h.term)")
        )
        assertTrue(
            "the terminal region stays keyboard-aware (imePadding)",
            surfaceFn.contains(".imePadding()")
        )
        assertFalse(
            "the terminal region itself must not consume the status-bar inset " +
                "(that is the Haven screen's job now)",
            surfaceFn.contains(".statusBarsPadding()")
        )
    }

    @Test
    fun noPerScreenStatusBarAppearancePatchingOnTheTerminalPath() {
        assertFalse(
            "the per-screen isAppearanceLightStatusBars patch is gone — the " +
                "terminal no longer paints behind the status bar, so the global " +
                "theme value is always correct (the old patch also had a " +
                "stale-closure restore bug)",
            screen.contains("isAppearanceLightStatusBars")
        )
    }

    @Test
    fun noImmersiveOrFullscreenSystemBarManipulationForTheTerminal() {
        for ((name, src) in listOf("WorkspaceScreen" to screen, "HavenTerminal" to havenTerminal)) {
            assertFalse(
                "$name must not hide system bars",
                src.contains("controller.hide(") || src.contains("hide(WindowInsetsCompat")
            )
            assertFalse(
                "$name must not set a system-bars behavior (immersive/sticky)",
                src.contains("systemBarsBehavior")
            )
            assertFalse(
                "$name must not use legacy immersive flags",
                src.contains("IMMERSIVE") || src.contains("SYSTEM_UI_FLAG")
            )
            assertFalse(
                "$name must not touch WindowInsetsController at all",
                src.contains("WindowInsetsController")
            )
        }
    }

    @Test
    fun theTerminalSessionBarRemainsAppChromeStyled() {
        assertTrue(
            "the terminal's own session bar keeps its theme-surface background " +
                "(app chrome, visible over any terminal background)",
            havenTerminal.contains(".background(h.surface2)")
        )
    }
}
