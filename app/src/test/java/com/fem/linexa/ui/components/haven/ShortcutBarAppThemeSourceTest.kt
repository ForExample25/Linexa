package com.fem.linexa.ui.components.haven

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 (Issues 2+3) — the terminal **shortcut bar is application UI**.
 *
 * Why a source test: the requirement is about which THEME owns every color on
 * the bar and about the *absence* of a selected-key background — properties a
 * JVM test can only pin structurally (this repo has no Robolectric and no
 * Compose UI harness, §1.124(a)).
 *
 * The contract pinned here:
 *
 *  - the bar's surface is the Haven theme `surface` token (dark theme → dark
 *    surface, light theme → light surface) — never a terminal color;
 *  - the key foregrounds are the Haven `keyFg` token, and ONLY the label
 *    color changes when a modifier is armed — to the theme accent `primary`;
 *  - an armed Ctrl/Alt/Shift/Fn gets **no** filled/colored background of any
 *    kind (no pill, no tint, no terminal palette entry behind the key);
 *  - nothing in the Haven kit reads terminal emulator color state
 *    (`mCurrentColors` / `TerminalColors` / `com.termux.*`).
 */
class ShortcutBarAppThemeSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/components/haven/HavenTerminal.kt")
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

    private val havenTerminal: String
        get() = source("com/fem/linexa/ui/components/haven/HavenTerminal.kt")

    /** Just the [HavenExtraKeys] composable body — the whole shortcut bar. */
    private val shortcutBar: String
        get() = havenTerminal.substringAfter("fun HavenExtraKeys(")
            .substringBefore("\n/**")

    private val havenColors: String
        get() = source("com/fem/linexa/ui/theme/haven/HavenColors.kt")

    @Test
    fun theBarSurfaceIsTheHavenThemeSurfaceToken() {
        assertTrue(
            "the shortcut bar background must be the Haven `surface` token " +
                "(dark theme → dark surface, light theme → light surface)",
            shortcutBar.contains(".background(h.surface)")
        )
        assertFalse(
            "the bar must never paint a terminal-owned color",
            shortcutBar.contains("h.term") || shortcutBar.contains("termBar")
        )
    }

    @Test
    fun anArmedModifierGetsNoColoredBackground() {
        // Exactly ONE .background( may exist in the whole bar: the Row's
        // theme-surface paint. A second occurrence would be a per-key fill —
        // the colored selected-key background this contract forbids.
        assertEquals(
            "the only background in the shortcut bar is the Row's theme surface; " +
                "selected keys must not gain a filled background",
            1,
            Regex("\\.background\\(").findAll(shortcutBar).count()
        )
        assertFalse(
            "the unused `keyBg` token must not come back as a key fill",
            shortcutBar.contains("keyBg")
        )
    }

    @Test
    fun selectionChangesOnlyTheLabelColorToTheThemeAccent() {
        assertTrue(
            "unselected keys use the theme's shortcut foreground token",
            shortcutBar.contains("else h.keyFg")
        )
        assertTrue(
            "an armed modifier's label becomes the app accent color (h.primary), " +
                "with the key background untouched",
            shortcutBar.contains("if (active) h.primary else h.keyFg")
        )
        assertTrue(
            "the armed state must stay visually obvious without a background " +
                "(weight change is allowed)",
            shortcutBar.contains("if (active) FontWeight.Bold")
        )
    }

    @Test
    fun theShortcutTokensExistInBothHavenPalettes() {
        listOf("darkPalette", "lightPalette").forEach { palette ->
            val body = havenColors.substringAfter("fun $palette(")
                .substringBefore("\n        fun ")
            assertTrue(
                "$palette must define keyFg (the normal shortcut label color)",
                body.contains("keyFg =")
            )
        }
        assertTrue(
            "dark theme keyFg must be a readable light color",
            havenColors.contains("keyFg = Color(0xFFD6DAE0)")
        )
        assertTrue(
            "light theme keyFg must be a readable dark color",
            havenColors.contains("keyFg = Color(0xFF2B3036)")
        )
    }

    @Test
    fun theShortcutBarTakesNoColorInputFromItsCallers() {
        // The bar is app UI that reads HavenTheme itself — its API has no
        // color parameter for a caller (panel or screen) to inject terminal
        // or ad-hoc colors through.
        val signature = havenTerminal.substringAfter("fun HavenExtraKeys(")
            .substringBefore(")")
        assertFalse(
            "HavenExtraKeys must accept no color parameter — the bar's " +
                "surface and label colors are Haven-theme-owned",
            signature.contains("Color") || signature.contains("color")
        )
    }

    @Test
    fun theHavenKitNeverReadsTerminalColorState() {
        assertFalse(
            "Haven terminal chrome must not read the emulator's runtime palette",
            havenTerminal.contains("mCurrentColors") ||
                havenTerminal.contains("TerminalColors") ||
                havenTerminal.contains("com.termux")
        )
    }
}
