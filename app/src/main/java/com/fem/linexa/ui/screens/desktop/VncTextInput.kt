package com.fem.linexa.ui.screens.desktop

/**
 * §1.125 — Pure (JVM-testable) soft-keyboard text handling for the VNC client.
 *
 * Why this is needed at all: [VncViewer] was `focusable()` and had an
 * `onKeyEvent` handler, and neither could ever produce typed text.
 *
 * - `FocusRequester` was created and attached but **never requested**, so the Box
 *   held focus only if something else happened to give it focus. Nothing did.
 * - Even with focus, a Compose `Box` has no `InputConnection`, so the Android IME
 *   has nowhere to send text. `onKeyEvent` sees hardware key events only. On a
 *   phone with no physical keyboard — the entire target platform — the desktop was
 *   therefore **input-only via touch**: no way to type a single character, and no
 *   way to even open the keyboard.
 *
 * The fix routes soft-keyboard input as *text* rather than as key codes (see
 * [XKeysyms.unicodeKeysym] for why those are different things), which means the
 * conversion from an IME's committed string to a sequence of RFB key events is a
 * pure function — and therefore the part worth testing, since an IME cannot be
 * driven from a JVM test.
 */
internal object VncTextInput {

    /**
     * Splits [text] into Unicode **code points**, not chars.
     *
     * An IME commits a `CharSequence` in UTF-16. Emoji and many CJK extension
     * characters are surrogate pairs, so iterating `Char`s would send two lone
     * surrogates — each of which [XKeysyms.unicodeKeysym] correctly refuses,
     * meaning the character would silently vanish instead of being typed.
     */
    fun codePoints(text: CharSequence): List<Int> {
        val result = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            result.add(codePoint)
            index += Character.charCount(codePoint)
        }
        return result
    }

    /**
     * The keysyms to send for a committed IME string, in order, skipping anything
     * with no keysym.
     *
     * Skipping rather than aborting: a paste or an autocorrect commit is one call
     * containing many characters, and one unmappable code point in the middle must
     * not discard the rest of the user's text.
     */
    fun keysymsFor(text: CharSequence): List<Int> =
        codePoints(text).mapNotNull { XKeysyms.unicodeKeysym(it) }

    /**
     * The keysym for one code point, or null if it has none.
     *
     * Named here rather than calling [XKeysyms.unicodeKeysym] directly from
     * [VncComposingText] so both IME paths convert through the same function —
     * §1.138's diffing path types the *tail* of a composing region, and a second
     * conversion rule would be a second place for the shifted-character mistake
     * (§1.125) to come back.
     */
    fun keysymFor(codePoint: Int): Int? = XKeysyms.unicodeKeysym(codePoint)

    /**
     * Whether an IME `deleteSurroundingText`/backspace should be sent as a
     * BackSpace key event.
     *
     * The IME asks to delete [beforeLength] chars before the cursor and
     * [afterLength] after it. There is no cursor here — the remote X application
     * owns the text — so the only honest translation is "press BackSpace
     * [beforeLength] times, and Delete [afterLength] times". A request for zero of
     * both is a no-op the IME sends routinely and must not produce a keystroke.
     */
    fun backspaceCount(beforeLength: Int): Int = beforeLength.coerceAtLeast(0)

    /** Companion of [backspaceCount] for the forward direction. */
    fun deleteCount(afterLength: Int): Int = afterLength.coerceAtLeast(0)
}
