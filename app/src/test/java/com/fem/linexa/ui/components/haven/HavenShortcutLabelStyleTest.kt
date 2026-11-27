package com.fem.linexa.ui.components.haven

import androidx.compose.ui.text.font.FontWeight
import com.fem.linexa.ui.theme.haven.HavenColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 10D — the shortcut-bar key label's armed style, extracted from
 * [HavenExtraKeys]'s inline `if (active)` pair into [HavenShortcutLabel] so the
 * armed→accent mapping is asserted behaviourally against both Haven palettes
 * instead of being pinned as source text in ShortcutBarAppThemeSourceTest.
 *
 * Contract (2026-08-30, Issues 2+3): an ARMED modifier's label takes the theme
 * accent `primary` and goes Bold; an unarmed key takes the shortcut foreground
 * `keyFg` at SemiBold. Only the LABEL changes — the no-key-background rule
 * stays pinned structurally in ShortcutBarAppThemeSourceTest.
 */
class HavenShortcutLabelStyleTest {

    @Test
    fun anArmedKeyTakesTheThemeAccentAndBold() {
        listOf(
            "dark" to HavenColors.darkPalette(),
            "light" to HavenColors.lightPalette()
        ).forEach { (theme, palette) ->
            assertEquals(
                "armed label color ($theme palette) must be the theme accent",
                palette.primary,
                HavenShortcutLabel.color(palette, active = true)
            )
            assertEquals(
                "armed label weight ($theme palette) must be Bold",
                FontWeight.Bold,
                HavenShortcutLabel.weight(active = true)
            )
        }
    }

    @Test
    fun anUnarmedKeyTakesTheShortcutForegroundAndSemiBold() {
        listOf(
            "dark" to HavenColors.darkPalette(),
            "light" to HavenColors.lightPalette()
        ).forEach { (theme, palette) ->
            assertEquals(
                "unarmed label color ($theme palette) must be the shortcut foreground",
                palette.keyFg,
                HavenShortcutLabel.color(palette, active = false)
            )
            assertEquals(
                "unarmed label weight ($theme palette) must be SemiBold",
                FontWeight.SemiBold,
                HavenShortcutLabel.weight(active = false)
            )
        }
    }
}
