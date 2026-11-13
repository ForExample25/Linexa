package com.fem.linexa.ui.screens.desktop

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent.keyCode]s to X11 keysyms for the RFB/VNC client
 * (§1.79, Phase 4 Path B).
 *
 * Previous behavior passed the Android keycode straight through as the RFB
 * keysym field, which only coincidentally matched the X keysym for A–Z
 * (both 0x41..) and was wrong for nearly every other key (Esc, Return,
 * arrows, modifier keys). The mappings below follow the standard X11
 * keysym encoding used by VNC clients (X11 keysyms are 32-bit; latin-1
 * characters map to their code point, function/editing/modifier keys live
 * in the 0xFF00+ range).
 *
 * Design choice: keep this a pure function of the physical [keyCode]. The
 * client deliberately sends the *base* (unshifted) keysym plus real
 * modifier-key keysyms (Shift/Control/Alt); the X server applies the
 * modifier itself (Shift+"a" → "A", Shift+"1" → "!"), which is how X11
 * keyboards behave. Mapping the shifted character here instead would double
 * the modifier and produce wrong output.
 */
object XKeysyms {

    // XK_* constants (X11 keysym encoding)
    const val SPACE = 0x0020
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val INSERT = 0xFF63
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val SHIFT_L = 0xFFE1
    const val SHIFT_R = 0xFFE2
    const val CONTROL_L = 0xFFE3
    const val CONTROL_R = 0xFFE4
    const val META_L = 0xFFE7
    const val META_R = 0xFFE8
    const val ALT_L = 0xFFE9
    const val ALT_R = 0xFFEA
    const val CAPS_LOCK = 0xFFE5
    const val NUM_LOCK = 0xFF7F
    const val KP_DECIMAL = 0xFFAE
    const val KP_0 = 0xFFB0 // KeyPad digits, contiguous
    const val F1 = 0xFFBE // Function keys, contiguous

    /**
     * Base keysym for a physical Android key. Returns null for keys that
     * should be dropped (volume, power, media, etc. — not X11 concepts) or
     * keys without a meaningful keysym.
     */
    fun androidKeysym(keyCode: Int): Int? {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            // 'a'..'z' (base, unshifted) — contiguous in the X latin-1 range
            return 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return '0'.code + (keyCode - KeyEvent.KEYCODE_0)
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> SPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
            KeyEvent.KEYCODE_TAB -> TAB
            KeyEvent.KEYCODE_DEL -> BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
            KeyEvent.KEYCODE_ESCAPE -> ESCAPE
            KeyEvent.KEYCODE_HOME -> HOME
            KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> INSERT
            KeyEvent.KEYCODE_MOVE_HOME -> HOME
            KeyEvent.KEYCODE_MOVE_END -> END

            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT

            KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
            KeyEvent.KEYCODE_CTRL_LEFT -> CONTROL_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_R
            KeyEvent.KEYCODE_ALT_LEFT -> ALT_L
            KeyEvent.KEYCODE_ALT_RIGHT -> ALT_R
            KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK

            // Pre-release audit fix (2026-09-02, finding D2): Meta keys and
            // the numpad operators were silently dropped (`else -> null`)
            // although the META_L/META_R keysym constants already existed.
            KeyEvent.KEYCODE_META_LEFT -> META_L
            KeyEvent.KEYCODE_META_RIGHT -> META_R

            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> '-'.code
            KeyEvent.KEYCODE_NUMPAD_ADD -> '+'.code
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> '/'.code
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'.code
            KeyEvent.KEYCODE_NUMPAD_DOT -> KP_DECIMAL
            KeyEvent.KEYCODE_NUM -> NUM_LOCK

            KeyEvent.KEYCODE_MINUS -> '-'.code
            KeyEvent.KEYCODE_EQUALS -> '='.code
            KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
            KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
            KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
            KeyEvent.KEYCODE_SEMICOLON -> ';'.code
            KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
            KeyEvent.KEYCODE_COMMA -> ','.code
            KeyEvent.KEYCODE_PERIOD -> '.'.code
            KeyEvent.KEYCODE_SLASH -> '/'.code
            KeyEvent.KEYCODE_GRAVE -> '`'.code

            KeyEvent.KEYCODE_NUMPAD_0 -> KP_0 + 0
            KeyEvent.KEYCODE_NUMPAD_1 -> KP_0 + 1
            KeyEvent.KEYCODE_NUMPAD_2 -> KP_0 + 2
            KeyEvent.KEYCODE_NUMPAD_3 -> KP_0 + 3
            KeyEvent.KEYCODE_NUMPAD_4 -> KP_0 + 4
            KeyEvent.KEYCODE_NUMPAD_5 -> KP_0 + 5
            KeyEvent.KEYCODE_NUMPAD_6 -> KP_0 + 6
            KeyEvent.KEYCODE_NUMPAD_7 -> KP_0 + 7
            KeyEvent.KEYCODE_NUMPAD_8 -> KP_0 + 8
            KeyEvent.KEYCODE_NUMPAD_9 -> KP_0 + 9

            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (keyCode - KeyEvent.KEYCODE_F1)

            else -> null
        }
    }

    /**
     * §1.125 — the keysym for a **typed character**, as opposed to a physical key.
     *
     * [androidKeysym] deliberately maps the *unshifted base* key and lets the X
     * server apply modifiers, which is right for a hardware keyboard: the device
     * reports `KEYCODE_A` plus a real Shift key, and X composes them. It is wrong
     * for a **soft keyboard**, which reports no keycode at all for most input —
     * an IME delivers finished text through `commitText`, so tapping the on-screen
     * `A` yields the character `'A'` and no `KEYCODE_SHIFT_LEFT` anywhere. Feeding
     * that through [androidKeysym] is not even possible (there is no keycode), and
     * feeding the character's *base* key would type `a` for `A` and `1` for `!`.
     *
     * So soft-keyboard input is sent as the keysym of the character itself:
     *
     * - Latin-1 (`U+0020`..`U+00FF`) maps to its own code point, which is the
     *   X11 keysym by definition — this covers the entire ASCII keyboard,
     *   including the shifted punctuation an IME sends as one character.
     * - Everything else uses the X11 Unicode keysym form `0x01000000 + codePoint`
     *   (`XK_Unicode`), which is how every modern X client sends non-Latin-1 text
     *   and is what lets a non-English IME work at all.
     * - Control characters below `U+0020` return null. They are not typed text; a
     *   real IME sends `\n` as a `KEYCODE_ENTER` key event and Ctrl-chords through
     *   the modifier path, both of which [androidKeysym] already handles.
     *
     * Surrogate pairs are the caller's problem to combine into a code point
     * *before* calling this (see [VncTextInput.codePoints]) — a lone surrogate is
     * not a character and is rejected as such.
     */
    fun unicodeKeysym(codePoint: Int): Int? = when {
        codePoint < 0x20 -> null
        codePoint == 0x7F -> DELETE
        codePoint <= 0xFF -> codePoint
        Character.isSupplementaryCodePoint(codePoint) ||
            !Character.isSurrogate(codePoint.toChar()) -> UNICODE_KEYSYM_BASE + codePoint
        // A lone surrogate is half of a character and has no keysym.
        else -> null
    }

    /**
     * X11's "any Unicode code point" keysym range (`XK_Unicode`): keysym
     * `0x01000000 | codePoint` for anything outside the legacy keysym tables.
     */
    const val UNICODE_KEYSYM_BASE = 0x01000000
}