package com.fem.linexa.ui.components.haven

import com.fem.linexa.domain.settings.TerminalShortcutModifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10D gap-fill — the sticky-modifier arming contract of
 * [HavenTerminalModifiers], which had no behavioural coverage at all: only its
 * wiring was pinned in the shortcut-bar source tests, while the rules
 * themselves (mutual exclusion, independence, one-shot consumption) were
 * documented in the KDoc and enforced by nothing.
 *
 * The contract (HavenTerminal.kt): CTRL and ALT are mutually exclusive —
 * arming one disarms the other — while SHIFT and FN are independent toggles so
 * Ctrl+Shift-style arming works; `toggle`/`isArmed` address modifiers by
 * domain identity; every `consume…` is a one-shot read; `clear` resets all
 * four flags.
 */
class HavenTerminalModifiersTest {

    @Test
    fun ctrlAndAltAreMutuallyExclusive() {
        val m = HavenTerminalModifiers()
        m.toggleControl()
        assertTrue(m.isArmed(TerminalShortcutModifier.CONTROL))
        assertFalse(m.isArmed(TerminalShortcutModifier.ALT))
        // Arming Alt disarms Ctrl — the pre-Compose contract, unchanged.
        m.toggleAlt()
        assertFalse("arming Alt must disarm Ctrl", m.isArmed(TerminalShortcutModifier.CONTROL))
        assertTrue(m.isArmed(TerminalShortcutModifier.ALT))
        // And the reverse direction.
        m.toggleControl()
        assertFalse("arming Ctrl must disarm Alt", m.isArmed(TerminalShortcutModifier.ALT))
        assertTrue(m.isArmed(TerminalShortcutModifier.CONTROL))
    }

    @Test
    fun shiftIsAnIndependentToggle() {
        val m = HavenTerminalModifiers()
        m.toggleControl()
        m.toggleShift()
        assertTrue(m.isArmed(TerminalShortcutModifier.CONTROL))
        assertTrue(m.isArmed(TerminalShortcutModifier.SHIFT))
        // Ctrl+Shift-style arming survives toggling Shift off and on.
        m.toggleShift()
        assertFalse(m.isArmed(TerminalShortcutModifier.SHIFT))
        assertTrue(m.isArmed(TerminalShortcutModifier.CONTROL))
        m.toggleShift()
        assertTrue(m.isArmed(TerminalShortcutModifier.SHIFT))
        assertTrue(m.isArmed(TerminalShortcutModifier.CONTROL))
        // Re-arming the Ctrl/Alt pair must not touch Shift either.
        m.toggleAlt()
        assertTrue(m.isArmed(TerminalShortcutModifier.SHIFT))
    }

    @Test
    fun toggleAndIsArmedAddressTheDomainIdentity() {
        for (modifier in TerminalShortcutModifier.entries) {
            val m = HavenTerminalModifiers()
            m.toggle(modifier)
            assertTrue("$modifier must arm via toggle()", m.isArmed(modifier))
            TerminalShortcutModifier.entries
                .filter { it != modifier }
                .forEach { other ->
                    assertFalse("toggling $modifier must not arm $other", m.isArmed(other))
                }
        }
    }

    @Test
    fun consumeIsOneShot() {
        val m = HavenTerminalModifiers()
        m.toggleControl()
        assertTrue(m.consumeControl())
        assertFalse("the read is one-shot", m.consumeControl())

        m.toggleAlt()
        assertTrue(m.consumeAlt())
        assertFalse(m.consumeAlt())

        m.toggleShift()
        assertTrue(m.consumeShift())
        assertFalse(m.consumeShift())

        m.toggleFn()
        assertTrue(m.consumeFn())
        assertFalse(m.consumeFn())
    }

    @Test
    fun clearResetsEveryFlag() {
        val m = HavenTerminalModifiers()
        m.toggleShift()
        m.toggleFn()
        m.toggleControl()
        m.clear()
        TerminalShortcutModifier.entries.forEach { modifier ->
            assertFalse("clear() must disarm $modifier", m.isArmed(modifier))
        }
        assertFalse("clear() must disarm Fn", m.fn)
    }
}
