package com.fem.linexa.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-08-29 (appearance/gesture fix) — source pins for the three fixes that
 * have no plain-JVM-observable behavior:
 *
 *  1. **Pinch accumulation** — the client must accumulate raw per-event scale
 *     ratios itself (the view's factor is reset by our own `return 1f`), with
 *     thresholds that raw ratios can actually cross.
 *  2. **Terminal-owned appearance plumbing** — the container layer resolves
 *     the guest HOME to a HOST path and passes it through the factory; the
 *     session applies `TerminalColorScheme.updateWith` BEFORE the emulator is
 *     constructed; the view loads `.termux/font.ttf`. None of this may route
 *     through Haven tokens.
 *  3. **Theme-aware shortcut bar** — `keyBg`/`keyFg` must be theme-
 *     differentiated (they were identical in both palettes).
 */
class TerminalAppearanceWiringSourceTest {

    private val moduleRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "src/main/java/com/fem/linexa/MainActivity.kt").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("module root not found")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/$path").readText()

    private val realTerminalView: String
        get() = source("com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")

    private val termuxPtySession: String
        get() = source("com/fem/linexa/service/runtime/pty/TermuxPtySession.kt")

    private val containerRuntime: String
        get() = source("com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt")

    private val havenColors: String
        get() = source("com/fem/linexa/ui/theme/haven/HavenColors.kt")

    // ── pinch ──────────────────────────────────────────────────────────────

    @Test
    fun pinchAccumulatesClientSideInsteadOfThresholdingRawRatios() {
        // The view resets its factor to whatever the client returns (1f), so
        // the only state that can accumulate is the client's own.
        assertTrue(
            "the client must keep its own scale accumulator across events",
            realTerminalView.contains("scaleAccum *= scale")
        )
        assertTrue(
            "accumulation thresholds must be crossable by real pinch ratios (±13-15%), " +
                "not per-event ratios (~±5%)",
            realTerminalView.contains("scaleAccum >= 1.15f") &&
                realTerminalView.contains("scaleAccum <= 0.87f")
        )
        assertTrue(
            "the accumulator must reset after each applied step",
            realTerminalView.contains("scaleAccum = 1f")
        )
    }

    @Test
    fun pinchStillUsesTheTerminalOwnedFontStore() {
        // No reintroduction of the app-settings font path.
        assertTrue(
            "font steps must go through the terminal-local preference store",
            realTerminalView.contains("TerminalFontPreference.increase(") &&
                realTerminalView.contains("TerminalFontPreference.decrease(")
        )
    }

    // ── terminal-owned appearance ─────────────────────────────────────────

    @Test
    fun containerResolvesGuestHomeToAHostConfigPath() {
        assertTrue(
            "the container layer must derive the config home from the guest HOME env " +
                "(never hard-code /root)",
            containerRuntime.contains("spec.env[\"HOME\"]?.let { home ->")
        )
        assertTrue(
            "only a path string may cross the container→factory seam",
            containerRuntime.contains("terminalConfigHome = spec.env[\"HOME\"]")
        )
    }

    @Test
    fun sessionAppliesColorsPropertiesBeforeTheEmulatorIsConstructed() {
        assertTrue(
            "the session must parse the Termux-format properties into a scratch scheme " +
                "(updateWith throws mid-mutation on bad files)",
            termuxPtySession.contains("scratch.updateWith(props)")
        )
        assertTrue(
            "a valid scheme is copied into the static theme BEFORE TerminalSession construction",
            Regex("TerminalColors\\.COLOR_SCHEME\\.mDefaultColors").containsMatchIn(termuxPtySession)
        )
        assertTrue(
            "malformed user files must never block a terminal",
            termuxPtySession.contains("Ignoring invalid .termux/colors.properties")
        )
    }

    @Test
    fun viewLoadsTheTerminalOwnedFont() {
        assertTrue(
            "the terminal view must load .termux/font.ttf from the terminal config home",
            realTerminalView.contains(".termux/font.ttf")
        )
        assertTrue(
            "font load failures must fall back to the default monospace",
            realTerminalView.contains("Ignoring invalid .termux/font.ttf")
        )
    }

    @Test
    fun havenTokensAreNotTheTerminalPalette() {
        // The shortcut bar is the ONLY Haven consumer of keyBg/keyFg; the
        // terminal color path reads TerminalColors.COLOR_SCHEME exclusively.
        val pty = source("com/fem/linexa/service/runtime/pty/TermuxPtySession.kt")
        assertTrue(
            "the terminal color path must go through the vendored scheme",
            pty.contains("TerminalColorScheme()")
        )
        assertTrue(
            "Haven tokens must not appear in the terminal color path",
            !pty.contains("keyBg") && !pty.contains("termText")
        )
    }

    // ── theme-aware shortcut bar ──────────────────────────────────────────

    @Test
    fun shortcutSurfaceIsThemeDifferentiated() {
        // Both palettes previously shipped the SAME dark keyBg. The light
        // palette must now carry a light surface.
        val darkBlock = havenColors.substringAfter("fun darkPalette(")
            .substringBefore("fun lightPalette(")
        val lightBlock = havenColors.substringAfter("fun lightPalette(")
        assertTrue(
            "dark shortcut surface must be terminal-dark",
            Regex("keyBg = Color\\(0xFF0A0E0F\\)").containsMatchIn(darkBlock)
        )
        assertTrue(
            "light shortcut surface must be light (was dark in both themes)",
            Regex("keyBg = Color\\(0xFFE7E9EC\\)").containsMatchIn(lightBlock)
        )
    }

    // ── terminal-owned background (the OSC 11 / colors.properties fix) ────

    @Test
    fun terminalViewFillsTheCanvasWithTheCurrentSchemeBackground() {
        val view = File(moduleRoot, "../terminal-view/src/main/java/com/termux/view/TerminalView.java")
            .canonicalFile
        assertTrue("vendored TerminalView.java not found at ${view.path}", view.isFile)
        val text = view.readText()
        val onDraw = text.substringAfter("protected void onDraw")
        // The default-background cells are transparent by design
        // ("only draw non-default background"), so the canvas base MUST be
        // the current palette background — otherwise the visible background
        // is whatever is behind the view (the Haven surface) and neither
        // colors.properties nor OSC 11/104;11 can change it.
        assertTrue(
            "onDraw must fill with the CURRENT scheme background (mCurrentColors)",
            onDraw.contains(
                "drawBackgroundWithinBounds(\n" +
                    "                canvas, mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);"
            )
        )
        // 2026-08-31 (fullscreen black overlay fix): the fill must be BOUNDED
        // to the view's own dimensions. canvas.drawColor painted the ENTIRE
        // canvas clip — the whole window under Compose AndroidView interop —
        // and overpainted the app chrome drawn earlier in the same frame.
        // The bounded rect lives in drawBackgroundWithinBounds (defined before
        // onDraw), so this half reads the whole file.
        assertTrue(
            "the background fill must be bounded to the view's own dimensions " +
                "(drawRect 0,0,w,h — never an unbounded canvas.drawColor)",
            text.contains("canvas.drawRect(0, 0, getWidth(), getHeight(), mBackgroundFillPaint)") &&
                !text.contains("canvas.drawColor(mEmulator")
        )
    }
}
