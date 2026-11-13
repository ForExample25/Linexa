package com.fem.linexa.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 4 — the theme preference's persistence and resolution rules.
 *
 * Two properties carry real risk and both are asserted here rather than trusted:
 * that the stored representation is the [ThemeMode.key] (an ordinal would silently
 * repoint every user's stored preference if the entries were ever reordered), and
 * that parsing is **total** — this value wraps the whole app's color scheme, so an
 * unreadable one must degrade to "follow the device", never throw during startup.
 */
class ThemeModeTest {

    @Test
    fun systemFollowsTheDeviceInBothDirections() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(systemDark = false))
    }

    @Test
    fun explicitModesIgnoreTheDeviceEntirely() {
        // The whole point of choosing Light or Dark: the device's own setting must
        // stop mattering. A mode that still tracked the system would look like the
        // setting was not applied.
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemDark = true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemDark = false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemDark = false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemDark = true))
    }

    @Test
    fun everyModeRoundTripsThroughItsStoredKey() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(
                "the persisted key must parse back to the same mode",
                mode,
                ThemeMode.fromKey(mode.key)
            )
        }
    }

    @Test
    fun keysAreStableStringsNotOrdinalsOrEnumNames() {
        // Stated explicitly: if someone "simplifies" this to `name`/`ordinal`, the
        // on-disk format changes and existing preferences are reinterpreted.
        assertEquals("system", ThemeMode.SYSTEM.key)
        assertEquals("light", ThemeMode.LIGHT.key)
        assertEquals("dark", ThemeMode.DARK.key)
        assertEquals(
            "keys must be unique or two modes would collide on disk",
            ThemeMode.entries.size,
            ThemeMode.entries.map { it.key }.toSet().size
        )
    }

    @Test
    fun anUnreadableStoredValueFallsBackToSystem() {
        // Absent (nothing chosen yet), blank, an enum *name* written by a build that
        // used `name`, and a value from a hypothetical future mode.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("amoled"))
    }

    @Test
    fun theDefaultIsToFollowTheDevice() {
        // Which is exactly what the app did before this setting existed, so a user
        // who never opens Settings sees no change in behaviour.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
    }
}
