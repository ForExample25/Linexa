package com.fem.linexa.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal UI preference model: two layouts, a closed shortcut set, and a
 * toolbar that stays editable without ever rewriting a built-in preset.
 *
 * These are pure JVM tests — the settings screens and the terminal bar both
 * consume exactly these values, so behaviour proven here holds for both.
 */
class TerminalUiPreferencesTest {

    // ── Layouts ────────────────────────────────────────────────────────────

    @Test
    fun recentSessionsHasExactlyTwoRealLayouts() {
        assertEquals(2, RecentSessionsLayout.entries.size)
        assertEquals(
            listOf("vertical", "horizontal"),
            RecentSessionsLayout.entries.map { it.key }
        )
        assertEquals(RecentSessionsLayout.VERTICAL, RecentSessionsLayout.DEFAULT)
    }

    @Test
    fun layoutKeysRoundTripAndUnknownValuesFallBack() {
        RecentSessionsLayout.entries.forEach { layout ->
            assertEquals(layout, RecentSessionsLayout.fromKey(layout.key))
        }
        assertEquals(RecentSessionsLayout.DEFAULT, RecentSessionsLayout.fromKey(null))
        assertEquals(RecentSessionsLayout.DEFAULT, RecentSessionsLayout.fromKey("diagonal"))
    }

    // ── Subtoolbar model ───────────────────────────────────────────────────

    @Test
    fun everySupportedShortcutCanActuallyDispatch() {
        val expected = setOf(
            "escape", "tab", "ctrl", "alt", "shift", "enter",
            "arrow_up", "arrow_down", "arrow_left", "arrow_right",
            "home", "end", "page_up", "page_down",
            "ctrl_c", "ctrl_d", "ctrl_z"
        )
        assertEquals(expected, TerminalShortcut.entries.map { it.key }.toSet())
        TerminalShortcut.entries.forEach { shortcut ->
            if (shortcut.isModifier) {
                assertNull("${shortcut.key} is a modifier, not a keycode", shortcut.keyCode)
                assertNotEquals(null, shortcut.modifier)
            } else {
                assertNotEquals("${shortcut.key} needs a keycode", null, shortcut.keyCode)
            }
            assertTrue("${shortcut.key} needs a visible label", shortcut.label.isNotBlank())
        }
    }

    @Test
    fun onlyControlLetterShortcutsCarryMetaCtrl() {
        val withMeta = TerminalShortcut.entries.filter { it.metaCtrl }
        assertEquals(
            listOf(
                TerminalShortcut.CTRL_C,
                TerminalShortcut.CTRL_D,
                TerminalShortcut.CTRL_Z
            ),
            withMeta
        )
    }

    @Test
    fun shortcutKeysRoundTripAndUnknownKeysAreDropped() {
        assertEquals(TerminalShortcut.ESCAPE, TerminalShortcut.fromKey("escape"))
        assertNull(TerminalShortcut.fromKey("f13"))
        assertNull(TerminalShortcut.fromKey(null))
    }

    // ── Presets ────────────────────────────────────────────────────────────

    @Test
    fun everyPresetIsCanonicalAndNonEmpty() {
        assertEquals(5, TerminalToolbarPreset.entries.size)
        assertEquals(
            listOf("compact", "navigation", "developer", "shell", "custom"),
            TerminalToolbarPreset.entries.map { it.key }
        )
        TerminalToolbarPreset.entries.forEach { preset ->
            val (row1, row2) = TerminalToolbarSettings.presetRows(preset)
            assertTrue("$preset row 1 is empty", row1.isNotEmpty())
            assertTrue("$preset row 2 is empty", row2.isNotEmpty())
            assertEquals("$preset row 1 has duplicates", row1.size, row1.distinct().size)
            assertEquals("$preset row 2 has duplicates", row2.size, row2.distinct().size)
            assertTrue(row1.size <= TerminalToolbarSettings.MAX_ROW_SIZE)
            assertTrue(row2.size <= TerminalToolbarSettings.MAX_ROW_SIZE)
        }
    }

    @Test
    fun namesAndKeysRoundTrip() {
        TerminalToolbarPreset.entries.forEach {
            assertEquals(it, TerminalToolbarPreset.fromKey(it.key))
        }
        TerminalToolbarRowMode.entries.forEach {
            assertEquals(it, TerminalToolbarRowMode.fromKey(it.key))
        }
        assertEquals(TerminalToolbarPreset.DEFAULT, TerminalToolbarPreset.fromKey("garbage"))
        assertEquals(TerminalToolbarRowMode.DEFAULT, TerminalToolbarRowMode.fromKey("garbage"))
    }

    // ── Active configuration ──────────────────────────────────────────────

    @Test
    fun singleRowHidesRowTwoAndDoubleRowShowsBoth() {
        val settings = TerminalToolbarSettings(preset = TerminalToolbarPreset.COMPACT)
        assertEquals(1, settings.config.rows.size)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_1, settings.config.row1)

        val doubled = TerminalToolbarEditing.setRowMode(
            settings,
            TerminalToolbarRowMode.DOUBLE
        )
        assertEquals(2, doubled.config.rows.size)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_2, doubled.config.row2)
    }

    @Test
    fun selectingABuiltInPresetNeverMutatesTheSavedCustomRows() {
        val saved = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = listOf(TerminalShortcut.ESCAPE, TerminalShortcut.CTRL_C),
            customRow2 = listOf(TerminalShortcut.ENTER)
        )
        val compact = TerminalToolbarEditing.selectPreset(saved, TerminalToolbarPreset.COMPACT)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_1, compact.config.row1)
        assertEquals(saved.customRow1, compact.customRow1)
        assertEquals(saved.customRow2, compact.customRow2)

        // ... and returning to Custom restores the edit exactly.
        val custom = TerminalToolbarEditing.selectPreset(compact, TerminalToolbarPreset.CUSTOM)
        assertEquals(saved.customRow1, custom.config.row1)
        assertEquals(saved.customRow2, custom.config.row2)
    }

    // ── Editing ───────────────────────────────────────────────────────────

    @Test
    fun addingToABuiltInSeedsCustomFromThatPresetThenAdds() {
        val compact = TerminalToolbarSettings(preset = TerminalToolbarPreset.COMPACT)
        val edited = TerminalToolbarEditing.add(compact, 0, TerminalShortcut.CTRL_Z)

        assertEquals(TerminalToolbarPreset.CUSTOM, edited.preset)
        // The seed is the preset the user was looking at, so the bar does not
        // jump to some other key set on the first edit.
        assertEquals(
            TerminalToolbarSettings.COMPACT_ROW_1 + TerminalShortcut.CTRL_Z,
            edited.customRow1
        )
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_2, edited.customRow2)
        assertTrue(TerminalShortcut.CTRL_Z in edited.config.row1)
    }

    @Test
    fun addRejectsDuplicatesAcrossRowsAndRespectsTheCap() {
        val base = TerminalToolbarSettings(preset = TerminalToolbarPreset.CUSTOM)
        val duplicate = TerminalToolbarEditing.add(
            base,
            1,
            base.customRow1.first()
        )
        assertEquals(base, duplicate)

        val full = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = TerminalShortcut.entries.take(TerminalToolbarSettings.MAX_ROW_SIZE),
            customRow2 = emptyList()
        )
        val rejected = TerminalToolbarEditing.add(full, 0, TerminalShortcut.PAGE_UP)
        assertEquals(full.customRow1, rejected.customRow1)
        assertFalse(TerminalShortcut.PAGE_UP in rejected.config.row1)
    }

    @Test
    fun removeOnlyTouchesTheRequestedRow() {
        val base = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = listOf(TerminalShortcut.ESCAPE, TerminalShortcut.TAB),
            customRow2 = listOf(TerminalShortcut.ENTER)
        )
        val result = TerminalToolbarEditing.remove(base, 0, TerminalShortcut.ESCAPE)
        assertEquals(listOf(TerminalShortcut.TAB), result.customRow1)
        assertEquals(base.customRow2, result.customRow2)
    }

    @Test
    fun reorderMovesWithinARowAndStopsAtTheEdges() {
        val base = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = listOf(
                TerminalShortcut.ESCAPE,
                TerminalShortcut.TAB,
                TerminalShortcut.ENTER
            ),
            customRow2 = listOf(TerminalShortcut.HOME)
        )
        val moved = TerminalToolbarEditing.move(base, 0, 0, 1)
        assertEquals(
            listOf(TerminalShortcut.TAB, TerminalShortcut.ESCAPE, TerminalShortcut.ENTER),
            moved.customRow1
        )
        assertEquals(base, TerminalToolbarEditing.move(base, 0, 0, -1))
        assertEquals(base, TerminalToolbarEditing.move(base, 0, 2, 1))
        assertEquals(base, TerminalToolbarEditing.move(base, 1, 0, 1))
    }

    @Test
    fun assignMovesTheShortcutToTheOtherRow() {
        val base = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = listOf(TerminalShortcut.ESCAPE, TerminalShortcut.TAB),
            customRow2 = listOf(TerminalShortcut.ENTER)
        )
        val toSecond = TerminalToolbarEditing.assignToOtherRow(
            base,
            0,
            TerminalShortcut.TAB
        )
        assertEquals(listOf(TerminalShortcut.ESCAPE), toSecond.customRow1)
        assertEquals(
            listOf(TerminalShortcut.ENTER, TerminalShortcut.TAB),
            toSecond.customRow2
        )

        val back = TerminalToolbarEditing.assignToOtherRow(
            toSecond,
            1,
            TerminalShortcut.TAB
        )
        assertEquals(listOf(TerminalShortcut.ESCAPE, TerminalShortcut.TAB), back.customRow1)
        assertEquals(listOf(TerminalShortcut.ENTER), back.customRow2)
    }

    @Test
    fun restoreDefaultsIsTheCanonicalCompactToolbar() {
        val custom = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            rowMode = TerminalToolbarRowMode.DOUBLE,
            customRow1 = listOf(TerminalShortcut.CTRL_C),
            customRow2 = listOf(TerminalShortcut.PAGE_DOWN)
        )
        val restored = TerminalToolbarEditing.restoreDefaults()
        assertEquals(TerminalToolbarSettings.DEFAULT, restored)
        assertNotEquals(custom, restored)
        assertEquals(TerminalToolbarPreset.COMPACT, restored.preset)
        assertEquals(TerminalToolbarRowMode.SINGLE, restored.rowMode)
    }

    @Test
    fun anEntirelyEmptyCustomBarFallsBackToCompact() {
        val empty = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = emptyList(),
            customRow2 = emptyList()
        ).sanitized()
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_1, empty.customRow1)
        assertEquals(TerminalToolbarSettings.COMPACT_ROW_2, empty.customRow2)
    }

    @Test
    fun switchingToAnEmptySideOfACustomBarKeepsTheOtherRow() {
        val oneSided = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = listOf(TerminalShortcut.ESCAPE),
            customRow2 = emptyList()
        )
        assertTrue(oneSided.config.row1.isNotEmpty())
        assertEquals(emptyList<TerminalShortcut>(), oneSided.config.row2)
    }

    // ── Persistence codec ─────────────────────────────────────────────────

    @Test
    fun rowsEncodeAndDecodeLosslessly() {
        val row = listOf(
            TerminalShortcut.ESCAPE,
            TerminalShortcut.CTRL,
            TerminalShortcut.ARROW_UP,
            TerminalShortcut.CTRL_C
        )
        val encoded = TerminalToolbarSettings.encodeRow(row)
        assertEquals(row, TerminalToolbarSettings.decodeRow(encoded))
        assertEquals("escape,ctrl,arrow_up,ctrl_c", encoded)
    }

    @Test
    fun decodeDropsUnknownAndDuplicateEntries() {
        assertEquals(
            listOf(TerminalShortcut.ESCAPE, TerminalShortcut.TAB),
            TerminalToolbarSettings.decodeRow("escape,f13,tab,escape")
        )
        assertEquals(emptyList<TerminalShortcut>(), TerminalToolbarSettings.decodeRow(null))
        assertEquals(emptyList<TerminalShortcut>(), TerminalToolbarSettings.decodeRow("  "))
    }

    @Test
    fun theFourPersistedValuesRebuildTheWholeState() {
        val saved = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            rowMode = TerminalToolbarRowMode.DOUBLE,
            customRow1 = listOf(TerminalShortcut.ESCAPE, TerminalShortcut.CTRL_C),
            customRow2 = listOf(TerminalShortcut.ENTER, TerminalShortcut.PAGE_DOWN)
        )
        val decoded = TerminalToolbarSettings.decode(
            presetKey = saved.preset.key,
            rowModeKey = saved.rowMode.key,
            customRow1 = TerminalToolbarSettings.encodeRow(saved.customRow1),
            customRow2 = TerminalToolbarSettings.encodeRow(saved.customRow2)
        )
        assertEquals(saved, decoded)
    }

    @Test
    fun missingOrCorruptPreferencesDegradeToTheDefaultToolbar() {
        val decoded = TerminalToolbarSettings.decode(
            presetKey = "not-a-preset",
            rowModeKey = "not-a-mode",
            customRow1 = "f13,nope",
            customRow2 = null
        )
        assertEquals(TerminalToolbarPreset.DEFAULT, decoded.preset)
        assertEquals(TerminalToolbarRowMode.DEFAULT, decoded.rowMode)
        assertEquals(TerminalToolbarSettings.DEFAULT, decoded)
    }

    @Test
    fun sanitizeCapsAndDeduplicatesBothRows() {
        val tooLong = (TerminalShortcut.entries + TerminalShortcut.entries)
            .take(TerminalToolbarSettings.MAX_ROW_SIZE + 4)
        val sanitized = TerminalToolbarSettings(
            preset = TerminalToolbarPreset.CUSTOM,
            customRow1 = tooLong,
            customRow2 = listOf(TerminalShortcut.ENTER, TerminalShortcut.ENTER)
        ).sanitized()
        assertTrue(sanitized.customRow1.size <= TerminalToolbarSettings.MAX_ROW_SIZE)
        assertEquals(sanitized.customRow1.distinct(), sanitized.customRow1)
        assertEquals(listOf(TerminalShortcut.ENTER), sanitized.customRow2)
    }

    @Test
    fun modifiersAreNotLiteralText() {
        // A modifier must never be dispatchable as a key; the bar arms it.
        listOf(
            TerminalShortcut.CTRL,
            TerminalShortcut.ALT,
            TerminalShortcut.SHIFT
        ).forEach {
            assertTrue(it.isModifier)
            assertNull(it.keyCode)
            assertFalse(it.metaCtrl)
        }
    }
}
