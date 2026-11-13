package com.fem.linexa.architecture

import androidx.compose.ui.graphics.Color
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.ui.theme.haven.HavenColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase 1 — WCAG 2.1 contrast verification for the existing Haven palette.
 *
 * This test does **not** change the palette. It measures the shipped token
 * pairs and splits them into two groups:
 *
 *  1. pairs that must meet WCAG AA (4.5:1 normal text, 3:1 for large/non-text
 *     UI). These are green today and stay guarded.
 *  2. measured gaps that already exist and are frozen with their exact ratio.
 *     Fixing one is a palette change and must update this table deliberately;
 *     regressing one fails immediately.
 *
 * The test is pure JVM: it reads [Color]'s float channels and never calls
 * `toArgb()` / `android.graphics`. Translucent tokens (`tint`, `redTint`, …)
 * are composited over the surface they actually sit on before measuring.
 *
 * ## Measured gaps (2026-11, palette unchanged)
 *
 * Light theme:
 *  - `muted` (#9AA0A6) is 2.30–2.64:1 — below even the 3:1 non-text floor.
 *  - `primary` (#0E9F5D) on `surface` / `onPrimary` white on primary is
 *    3.42:1 — below AA for normal text; usable as a large/non-text accent.
 *  - accent-on-tint pairs (primary/red/amber/blue) are 3.04–4.35:1; these are
 *    used for tinted chips/banners, not body copy. Compose's `Color.copy`
 *    packs sRGB channels to 8 bits, so `copy(alpha = 0.10f)` is really
 *    26/255 = 0.10196 — the frozen values below are measured with that
 *    quantization, not with the nominal alpha.
 *  - LAVENDER and SUNSET accents are 3.55/3.73:1 on white.
 *
 * Dark theme:
 *  - `muted` (#6C7178) is 3.32–3.84:1 — below AA normal text, above 3:1.
 *
 * Terminal (always dark):
 *  - `termDim` (#697077) on `term` (#0A0E0F) is 3.87:1; `termText` and
 *    `termPrompt` are 12+:1.
 */
class HavenContrastTest {

    private val light = HavenColors.lightPalette()
    private val dark = HavenColors.darkPalette()

    @Test
    fun bodyAndSecondaryTextMeetAaOnEverySurfaceStep() {
        listOf("light" to light, "dark" to dark).forEach { (name, palette) ->
            surfaceSteps(palette).forEach { (surfaceName, background) ->
                assertAtLeast(
                    "$name text on $surfaceName",
                    4.5,
                    contrast(palette.text, background)
                )
                assertAtLeast(
                    "$name text2 on $surfaceName",
                    4.5,
                    contrast(palette.text2, background)
                )
            }
        }
    }

    @Test
    fun semanticColorsMeetTheNonTextMinimum() {
        listOf("light" to light, "dark" to dark).forEach { (name, palette) ->
            assertAtLeast("$name red on surface", 4.5, contrast(palette.red, palette.surface))
            assertAtLeast("$name amber on surface", 4.5, contrast(palette.amber, palette.surface))
            assertAtLeast("$name blue on surface", 4.5, contrast(palette.blue, palette.surface))

            assertAtLeast(
                "$name primary on surface (large/non-text accent)",
                3.0,
                contrast(palette.primary, palette.surface)
            )
            assertAtLeast(
                "$name onPrimary on primary (large/non-text label)",
                3.0,
                contrast(palette.onPrimary, palette.primary)
            )
            assertAtLeast(
                "$name primary on tint",
                3.0,
                contrast(palette.primary, over(palette.tint, palette.surface))
            )
            assertAtLeast(
                "$name red on redTint",
                3.0,
                contrast(palette.red, over(palette.redTint, palette.surface))
            )
            assertAtLeast(
                "$name amber on amberTint",
                3.0,
                contrast(palette.amber, over(palette.amberTint, palette.surface))
            )
            assertAtLeast(
                "$name blue on blueTint",
                3.0,
                contrast(palette.blue, over(palette.blueTint, palette.surface))
            )
        }
    }

    @Test
    fun everyAccentOptionMeetsTheNonTextMinimum() {
        ColorTheme.entries.forEach { theme ->
            val lightAccent = HavenColors.lightPalette(HavenColors.accentFor(theme, dark = false))
            val darkAccent = HavenColors.darkPalette(HavenColors.accentFor(theme, dark = true))

            assertAtLeast(
                "light ${theme.key} primary on surface",
                3.0,
                contrast(lightAccent.primary, lightAccent.surface)
            )
            assertAtLeast(
                "light ${theme.key} onPrimary on primary",
                3.0,
                contrast(lightAccent.onPrimary, lightAccent.primary)
            )
            assertAtLeast(
                "dark ${theme.key} primary on surface",
                3.0,
                contrast(darkAccent.primary, darkAccent.surface)
            )
            assertAtLeast(
                "dark ${theme.key} onPrimary on primary",
                3.0,
                contrast(darkAccent.onPrimary, darkAccent.primary)
            )
        }
    }

    @Test
    fun terminalTextMeetsAaAndTheDimGapStaysFrozen() {
        assertAtLeast("termText on term", 4.5, contrast(light.termText, light.term))
        assertAtLeast("termPrompt on term", 4.5, contrast(light.termPrompt, light.term))
        assertFrozen("termDim on term", 3.87, contrast(light.termDim, light.term))
    }

    @Test
    fun documentedContrastGapsRemainFrozen() {
        // Light theme.
        assertFrozen("light muted on bg", 2.46, contrast(light.muted, light.bg))
        assertFrozen("light muted on surface", 2.64, contrast(light.muted, light.surface))
        assertFrozen("light muted on surface2", 2.30, contrast(light.muted, light.surface2))
        assertFrozen("light primary on surface", 3.42, contrast(light.primary, light.surface))
        assertFrozen("light onPrimary on primary", 3.42, contrast(light.onPrimary, light.primary))
        assertFrozen("light primary on tint", 3.04, contrast(light.primary, over(light.tint, light.surface)))
        assertFrozen("light red on redTint", 4.17, contrast(light.red, over(light.redTint, light.surface)))
        assertFrozen("light amber on amberTint", 4.35, contrast(light.amber, over(light.amberTint, light.surface)))
        assertFrozen("light blue on blueTint", 4.32, contrast(light.blue, over(light.blueTint, light.surface)))

        // Accent variants that also sit below AA on white.
        val lavender = HavenColors.lightPalette(HavenColors.accentFor(ColorTheme.LAVENDER, dark = false))
        val sunset = HavenColors.lightPalette(HavenColors.accentFor(ColorTheme.SUNSET, dark = false))
        assertFrozen("light lavender primary on surface", 3.55, contrast(lavender.primary, lavender.surface))
        assertFrozen("light sunset primary on surface", 3.73, contrast(sunset.primary, sunset.surface))

        // Dark theme: muted is below AA but above the 3:1 non-text floor.
        assertFrozen("dark muted on bg", 3.84, contrast(dark.muted, dark.bg))
        assertFrozen("dark muted on surface", 3.61, contrast(dark.muted, dark.surface))
        assertFrozen("dark muted on surface2", 3.32, contrast(dark.muted, dark.surface2))
    }

    // ── Measurement helpers ────────────────────────────────────────────────

    private fun surfaceSteps(palette: HavenColors) = listOf(
        "bg" to palette.bg,
        "surface" to palette.surface,
        "surface2" to palette.surface2
    )

    private fun assertAtLeast(label: String, minimum: Double, actual: Double) {
        assertTrue(
            "$label is ${"%.2f".format(actual)}:1, below the required " +
                "${"%.1f".format(minimum)}:1. If this is an intentional palette change, update " +
                "HavenContrastTest's documented expectations.",
            actual >= minimum
        )
    }

    private fun assertFrozen(label: String, expected: Double, actual: Double) {
        assertTrue(
            "$label is now ${"%.2f".format(actual)}:1. This pair is a documented Phase 1 gap; " +
                "if it was fixed or worsened intentionally, update the frozen table (and the " +
                "report) rather than silently changing the palette.",
            actual < 4.5
        )
        assertEquals(
            "$label drifted from the frozen measurement.",
            expected,
            actual,
            0.02
        )
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val fg = if (foreground.alpha < 1f) over(foreground, background) else foreground
        val lighter = max(relativeLuminance(fg), relativeLuminance(background))
        val darker = min(relativeLuminance(fg), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** sRGB-alpha composite of a translucent token over an opaque surface. */
    private fun over(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha
        return Color(
            red = foreground.red * alpha + background.red * (1f - alpha),
            green = foreground.green * alpha + background.green * (1f - alpha),
            blue = foreground.blue * alpha + background.blue * (1f - alpha),
            alpha = 1f
        )
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
