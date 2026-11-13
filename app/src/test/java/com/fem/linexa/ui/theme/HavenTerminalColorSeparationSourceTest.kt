package com.fem.linexa.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 (Issue 3) — the strict Haven ⇄ terminal color boundary.
 *
 * Why a source test: the boundary is an ownership rule ("who may name whose
 * colors"), which a JVM-only harness (§1.124(a)) can pin exactly by scanning
 * who references whose vocabulary. [com.fem.linexa.architecture.ImportBoundaryTest]
 * Rule 4 already confines `com.termux.*` to the sanctioned terminal adapters;
 * this test pins the two directions the architecture brief adds:
 *
 *  1. **Haven never reads terminal color state** — no file under
 *     `ui/` (the entire Haven layer) may reference the emulator's runtime
 *     palette (`mCurrentColors`, `TerminalColors`, `COLOR_INDEX_*`) or the
 *     vendored modules. The application cannot overwrite terminal colors when
 *     the theme changes if it literally has no path to them.
 *  2. **The terminal never reads Haven tokens** — the pty/session layer and
 *     the terminal view host may not import `ui.theme`/`HavenColors`/
 *     `HavenTheme`. The terminal's scheme comes from `colors.properties`
 *     (TermuxPtySession) and the OSC layer only — never from the app theme.
 *
 * The terminal-owned rendering contract itself (the background the TerminalView
 * paints comes from the emulator's own palette, which is what makes
 * colors.properties / OSC 11 / OSC 111 work) is pinned positively on the
 * vendored view's patched `onDraw`.
 */
class HavenTerminalColorSeparationSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/theme/haven/HavenColors.kt")
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

    private fun uiSources(): Sequence<Pair<String, String>> =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(moduleRoot).path to it.readText() }

    @Test
    fun theUiLayerNeverReadsTerminalColorState() {
        val offenders = uiSources().filter { (_, text) ->
            text.contains("mCurrentColors") ||
                text.contains("TerminalColors") ||
                text.contains("TerminalColorScheme") ||
                Regex("COLOR_INDEX_[A-Z_]+").containsMatchIn(text)
        }.map { (path, _) -> path }.toList()
        assertTrue(
            "no ui-layer file may reference terminal emulator color state " +
                "(offenders: $offenders) — Haven owns app surfaces only; the " +
                "terminal's palette is unreachable from the theme layer",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePtyLayerNeverReadsHavenTokens() {
        for (file in listOf(
            "com/fem/linexa/service/runtime/pty/TermuxPtySession.kt",
            "com/fem/linexa/service/runtime/pty/TermuxTerminalSessionProvider.kt",
            "com/fem/linexa/service/runtime/pty/PtySession.kt",
            "com/fem/linexa/ui/screens/terminal/RealTerminalView.kt"
        )) {
            val text = source(file)
            assertTrue(
                "$file must not reference the Haven theme layer — the terminal's " +
                    "colors come from colors.properties and the OSC layer, never " +
                    "from the app theme",
                !text.contains("ui.theme.haven") &&
                    !text.contains("HavenColors") &&
                    !text.contains("HavenTheme") &&
                    !text.contains("MaterialTheme")
            )
        }
    }

    @Test
    fun theTerminalSchemeIsConfiguredOnlyFromTerminalConfiguration() {
        val pty = source("com/fem/linexa/service/runtime/pty/TermuxPtySession.kt")
        assertTrue(
            "colors.properties must be applied to the terminal color scheme",
            pty.contains(".termux/colors.properties") &&
                pty.contains("TerminalColorScheme") &&
                pty.contains("TerminalColors.COLOR_SCHEME.mDefaultColors")
        )
        assertTrue(
            "the scheme setup must state the Haven boundary in its own contract note",
            pty.contains("Haven UI tokens never touch this path")
        )
    }

    @Test
    fun theTerminalViewPaintsItsBackgroundFromTheEmulatorPalette() {
        // The vendored module is a SIBLING of the app module (unit tests run
        // with user.dir = app/), so walk up from moduleRoot to the repo root
        // that contains terminal-view/.
        var dir: File? = moduleRoot
        while (dir != null && !File(dir, "terminal-view/src/main/java/com/termux/view/TerminalView.java").exists()) {
            dir = dir.parentFile
        }
        assertTrue("the terminal-view module must be reachable from the test", dir != null)
        val view = File(
            dir!!,
            "terminal-view/src/main/java/com/termux/view/TerminalView.java"
        ).readText()
        assertTrue(
            "TerminalView.onDraw must fill the view's own bounds from the emulator's " +
                "own current background color — that is what makes colors.properties, " +
                "OSC 11 and OSC 111 own the visible background (and what keeps " +
                "Haven from ever painting it)",
            view.contains(
                "drawBackgroundWithinBounds(\n" +
                    "                canvas, mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);"
            ) &&
                // 2026-08-31 (fullscreen black overlay fix): the fill must be
                // bounded to the view's dimensions — never an unbounded
                // canvas.drawColor, which escaped the view's bounds under
                // Compose interop and overpainted the app chrome.
                view.contains("canvas.drawRect(0, 0, getWidth(), getHeight(), mBackgroundFillPaint)") &&
                !view.contains("canvas.drawColor(mEmulator")
        )
    }
}
