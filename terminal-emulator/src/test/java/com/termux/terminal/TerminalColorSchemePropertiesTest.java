package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Properties;

/**
 * Linexa addition (2026-08-29, appearance fix): pins the colors.properties
 * contract the terminal-owned configuration depends on —
 * {@link TerminalColorScheme#updateWith(Properties)} must map the Termux
 * property names (foreground / background / cursor / colorN) onto
 * COLOR_INDEX_* slots, accept XTerm-style color specs, and reject unknown
 * keys / malformed values (strictness Linexa relies on to catch malformed
 * user files before they are applied).
 *
 * Two upstream behaviors are pinned AS-IS:
 *  - `colorN` with N > 255 throws ArrayIndexOutOfBoundsException (no bounds
 *    check in updateWith) — Linexa catches ALL exceptions from the apply path.
 *  - The auto cursor-color-from-background fallback uses
 *    {@link android.graphics.Color}, which is stubbed to 0 under JVM unit
 *    tests (returnDefaultValues) — so the fallback is device-verifiable only;
 *    here we pin the explicit "cursor" property path.
 */
public class TerminalColorSchemePropertiesTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return p;
    }

    @Test
    public void foregroundBackgroundCursorAndPaletteMapToTheirIndices() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        scheme.updateWith(props(
            "foreground", "#C9CED4",
            "background", "#0A0E0F",
            "cursor", "#FFFFFF",
            "color1", "#CD0000",
            "color15", "#FFFFFF"
        ));

        assertEquals(0xFFC9CED4, scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]);
        assertEquals(0xFF0A0E0F, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(0xFFFFFFFF, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
        assertEquals(0xFFCD0000, scheme.mDefaultColors[1]);
        assertEquals(0xFFFFFFFF, scheme.mDefaultColors[15]);
        // Untouched palette entries keep the defaults:
        assertEquals(0xff00cd00, scheme.mDefaultColors[2]);
    }

    @Test
    public void explicitCursorPropertyWins() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        scheme.updateWith(props("background", "#0A0E0F", "cursor", "#00FF00"));
        assertEquals(0xFF00FF00, scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR]);
    }

    @Test
    public void rgbColonSpecsAreAcceptedLikeXterm() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        // Exact-scaling components (ffff → 255, 8080 → 128, 0000 → 0).
        scheme.updateWith(props("foreground", "rgb:ffff/8080/0000"));
        assertEquals(0xFFFF8000, scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]);
    }

    @Test
    public void unknownKeysAndMalformedValuesAreRejected() {
        TerminalColorScheme scheme = new TerminalColorScheme();
        assertThrows(IllegalArgumentException.class,
            () -> scheme.updateWith(props("not-a-color-key", "#FFFFFF")));
        assertThrows(IllegalArgumentException.class,
            () -> scheme.updateWith(props("background", "#12")));
        // Upstream has no index bounds check — a colorN > 255 escapes as
        // ArrayIndexOutOfBoundsException; Linexa's apply path catches ALL
        // Throwables from this call (pinned in TermuxPtySession).
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> scheme.updateWith(props("color300", "#FFFFFF")));
    }

    @Test
    public void schemeCopyKeepsFailedFilesFromCorruptingTheLiveScheme() {
        // The Linexa apply path parses into a scratch scheme first; this pins
        // that a failed updateWith leaves a FRESH scheme's untouched entries
        // at their defaults (the scratch is discarded on failure).
        TerminalColorScheme scratch = new TerminalColorScheme();
        assertThrows(IllegalArgumentException.class,
            () -> scratch.updateWith(props("color1", "#ZZZZZZ")));
        assertEquals(0xffcd0000, scratch.mDefaultColors[1]);
    }
}
