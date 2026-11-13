package com.fem.linexa.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.ui.theme.haven.HavenColors

/**
 * Per-accent scheme lookup — HAVEN edition.
 *
 * The Haven rebuild collapsed the old hand-tuned per-theme palettes into ONE
 * token builder: [HavenColors.darkPalette] / [HavenColors.lightPalette]
 * parameterised by the accent. The four persisted [ColorTheme] keys are
 * unchanged (`alpine_blue` / `lavender` / `forest` / `sunset`, stored as
 * stable strings — never ordinals), and their accents follow the concept's
 * Settings → Appearance swatch rows.
 *
 * [schemeFor] now derives every M3 scheme from the Haven tokens (see
 * [com.fem.linexa.ui.theme.toMaterialScheme]), so a legacy M3 component and a
 * Haven component on the same screen always agree on color.
 */
object ColorThemes {

    fun schemeFor(theme: ColorTheme, darkTheme: Boolean): ColorScheme {
        val haven = if (darkTheme) {
            HavenColors.darkPalette(HavenColors.accentFor(theme, dark = true))
        } else {
            HavenColors.lightPalette(HavenColors.accentFor(theme, dark = false))
        }
        return haven.toMaterialScheme()
    }

    /** Preview swatch for Settings → Appearance — the theme's dark primary. */
    fun swatch(theme: ColorTheme): Color = HavenColors.accentFor(theme, dark = true)
}
