package com.fem.linexa.ui.screens.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HAVEN structural guard for the Workspaces tab's management terminal.
 *
 * Why a source test: the requirement is about layout topology and animation,
 * and verifying it behaviourally needs Compose UI testing or Robolectric, neither
 * of which exists in this repo (§1.124(a)). The alternative is no coverage at all
 * of the one thing this surface exists to guarantee: the terminal is a
 * full-bleed Haven layer over the tab (its own 46dp bar + floating extra keys),
 * the bottom nav stays composed and is hidden by opacity, and system back
 * dismisses the layer before leaving the tab.
 *
 * HAVEN rebuild note: the extra-keys row is now the Compose floating pill
 * ([com.fem.linexa.ui.components.haven.HavenExtraKeys]) — the library's
 * ExtraKeysView was the "old shortcut toolbar" the concept removes. These pins
 * follow the new implementation.
 */
class HomeTerminalOverlaySourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/home/WorkspacesScreen.kt")
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

    private val home: String
        get() = source("com/fem/linexa/ui/screens/home/WorkspacesScreen.kt")

    private val overlay: String
        get() = source("com/fem/linexa/ui/components/TerminalOverlay.kt")

    private val mgmtPanel: String
        get() = source("com/fem/linexa/ui/screens/terminal/ManagementTerminalPanel.kt")

    private val havenSurface: String
        get() = source("com/fem/linexa/ui/components/haven/HavenTerminal.kt")

    /** Just the [HavenTerminalSurface] function body — full-bleed pins. */
    private val surfaceFn: String
        get() = havenSurface.substringAfter("fun HavenTerminalSurface(")
            .substringBefore("\n@Composable")

    /** The workspace terminal panel source (pill inset pins). */
    private val workspacePanelPill: String
        get() = source("com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")

    /** The management terminal panel source (pill inset pins). */
    private val mgmtPanelPill: String
        get() = source("com/fem/linexa/ui/screens/terminal/ManagementTerminalPanel.kt")

    @Test
    fun theTerminalIsDrawnAfterTheWorkspacesContentSoItOverlaysIt() {
        val contentLayer = home.indexOf("HavenScreen(statusBarInsets = false)")
        val overlayLayer = home.indexOf("TerminalOverlay(")
        assertTrue("Workspaces content layer must exist", contentLayer >= 0)
        assertTrue(
            "the terminal must be declared after the Workspaces content so it paints on top",
            overlayLayer > contentLayer
        )
    }

    @Test
    fun theOverlaySlidesInAndOutOfTheSamePlace() {
        // HAVEN: the terminal is a fullscreen takeover — it slides UP from the
        // bottom edge and returns the same way (a takeover, not a dropdown).
        assertTrue("must slide in", overlay.contains("slideInVertically("))
        assertTrue("must slide out", overlay.contains("slideOutVertically("))
        assertTrue("must enter from below", overlay.contains("initialOffsetY = { it }"))
        assertTrue("must exit downwards", overlay.contains("targetOffsetY = { it }"))
    }

    @Test
    fun bothDirectionsShareOneFastDuration() {
        // 2026-11 motion pass: the overlay now binds the shared Haven push
        // spec instead of a local tween literal, so the open and close
        // directions cannot drift apart. The legacy constant stays as the
        // named alias other surfaces read.
        assertEquals(
            "both directions must use the shared Haven push spec, so close mirrors open",
            2,
            Regex("""animationSpec = motions\.push""").findAll(overlay).count()
        )
        assertTrue(
            "the overlay constant must stay bound to the Haven push token",
            overlay.contains("TERMINAL_OVERLAY_ANIMATION_MS = HavenMotion.NAV_PUSH_MS")
        )
        val motion = source("com/fem/linexa/ui/components/haven/Motion.kt")
        val duration = Regex("NAV_PUSH_MS = (\\d+)").find(motion)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("NAV_PUSH_MS must be declared", duration != null)
        assertTrue(
            "the overlay must arrive fast — a slow transition reads as a navigation (was $duration ms)",
            duration!! <= 250
        )
    }

    @Test
    fun theHavenTerminalSurfaceIsOpaqueKeyboardAwareAndAlwaysDark() {
        assertTrue(
            "the Haven surface must paint the always-dark terminal background",
            havenSurface.contains(".background(h.term)")
        )
        assertTrue(
            "the navigation-bar ∪ IME union keeps the extra-keys pill above the keyboard",
            havenSurface.contains(".windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))")
        )
        // 2026-08-28 full-bleed rebuild (concept fr-term): the SURFACE body paints
        // edge-to-edge — no inset padding may shrink the painted term background.
        // 2026-08-29 UI optimization (Issue A): the extra keys are NO LONGER a
        // floating pill — they are the LAST layout child of each terminal panel's
        // column (below the terminal surface, above the soft keyboard), so the
        // inset handling moved INTO the HavenExtraKeys bar itself
        // (navigationBarsPadding for the keyboard-closed state; the surface's
        // nav ∪ IME union keeps the whole column above the keyboard when it is
        // open and is consumed once, so the child row's nav padding is zero).
        assertFalse(
            "the term background must reach edge-to-edge (no status-bar padding on the surface body)",
            surfaceFn.contains(".statusBarsPadding()")
        )
        assertFalse(
            "the term background must reach edge-to-edge (no navigation padding on the surface body)",
            surfaceFn.contains(".navigationBarsPadding()")
        )
        assertTrue(
            "the shortcut bar must clear the system navigation bar itself (keyboard-closed state)",
            havenSurface.substringAfter("fun HavenExtraKeys(").contains(".navigationBarsPadding()")
        )
        for ((name, panel) in listOf("workspace" to workspacePanelPill, "management" to mgmtPanelPill)) {
            // The bar must be a layout child INSIDE the panel's Column, not an
            // overlay sibling: no BottomCenter alignment / bottom offset may remain.
            assertFalse(
                "the $name panel must not float the shortcut bar over terminal content",
                panel.contains("HavenExtraKeys(") &&
                    Regex("HavenExtraKeys\\s*\\([^)]*\\.align\\(").containsMatchIn(panel)
            )
            assertFalse(
                "the $name panel must not offset the shortcut bar with a floating bottom padding",
                Regex("HavenExtraKeys\\s*\\([^)]*padding\\(bottom").containsMatchIn(panel)
            )
            assertTrue(
                "the $name panel must host the floating extra-keys bar",
                panel.contains("HavenExtraKeys(")
            )
        }
        // The workspace terminal's keyboard is the system IME; only the
        // management terminal needs the explicit show/hide toggle.
        assertTrue(
            "the management panel must expose the explicit keyboard toggle on the shortcut bar",
            mgmtPanelPill.contains("onKeyboard = {")
        )
        assertTrue(
            // HAVEN rebuild update (pre-existing stale pin, fixed 2026-08 during
            // the desktop work): the bar's background moved from the translucent
            // `termBar` to opaque `surface2` — against the terminal's own default
            // black the translucent termBar vanished (see HavenTerminal.kt's own
            // comment at the site). The edge-to-edge + statusBarsPadding
            // contract below is unchanged.
            "the terminal bar extends its background behind the status bar",
            havenSurface.contains(".background(h.surface2)\n            .statusBarsPadding()")
        )
    }

    @Test
    fun theOverlayIsAFullscreenTakeover() {
        // HAVEN: the management terminal covers the tab's own header too — the
        // terminal's own session bar is the only chrome (concept fr-term).
        assertTrue(
            "the overlay must fill the whole screen (no top-inset reservation)",
            home.contains("Modifier.fillMaxSize()\n            ) {\n                HavenManagementTerminalPanel(") ||
                home.contains("TerminalOverlay(\n                visible = terminalOpen,\n                modifier = Modifier.fillMaxSize()")
        )
    }

    @Test
    fun theFloatingExtraKeysAreTheHavenPillNotTheLegacyRow() {
        assertFalse(
            "the legacy ExtraKeysView row is removed from RealTerminalView (Haven rebuild)",
            source("com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")
                .contains("attachTerminalView(")
        )
        assertTrue(
            "the Haven extra keys pill must exist",
            havenSurface.contains("fun HavenExtraKeys(")
        )
        assertTrue(
            "and the management terminal must host it",
            mgmtPanel.contains("HavenExtraKeys(")
        )
    }

    @Test
    fun theExistingTerminalMechanismIsUnchanged() {
        // The Haven rebuild is a presentation change only: the same ViewModel
        // session and the same pty-backed TerminalView as before (§1.20 — no
        // line-buffered fallback may be introduced for a terminal).
        assertTrue("same pty view", mgmtPanel.contains("RealTerminalView("))
        assertTrue("same session source", home.contains("terminalViewModel.session"))
        assertTrue(
            "the session must still be started lazily on first open",
            home.contains("if (terminalOpen) terminalViewModel.start()")
        )
        assertTrue(
            "the connecting placeholder must remain for the pre-session window",
            mgmtPanel.contains("management_terminal_connecting")
        )
    }

    @Test
    fun systemBackClosesTheOverlayAndOnlyThen() {
        assertTrue(
            "back must be intercepted only while the overlay is open, so Home's own back is untouched",
            home.contains("BackHandler(enabled = terminalOpen)")
        )
    }

    @Test
    fun theBottomNavIsStillComposedWhileTheOverlayIsOpen() {
        // A conditional bottom bar would re-measure the content the overlay is
        // supposed to cover without disturbing; Haven keeps it composed and
        // hides it by opacity + disabled state.
        assertTrue(
            "the bottom nav must be hidden by opacity while the overlay is open",
            home.contains("alpha = if (terminalOpen) 0f else 1f")
        )
    }
}
