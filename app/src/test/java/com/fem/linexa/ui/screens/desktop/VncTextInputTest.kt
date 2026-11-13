package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.125 — JVM tests for [VncTextInput] and [XKeysyms.unicodeKeysym].
 *
 * An IME cannot be driven from a JVM test, which is precisely why the conversion
 * from a committed `CharSequence` to a list of RFB keysyms was extracted as a pure
 * function. What is pinned here is the part that would otherwise only fail on a
 * device, silently: a surrogate pair split into two lone surrogates types nothing
 * at all, and a shifted character sent down the hardware path types the wrong
 * letter.
 */
class VncTextInputTest {

    // ---- code point splitting ----

    @Test
    fun plainAsciiSplitsPerCharacter() {
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), VncTextInput.codePoints("abc"))
    }

    @Test
    fun anEmptyCommitProducesNothing() {
        assertTrue(VncTextInput.codePoints("").isEmpty())
        assertTrue(VncTextInput.keysymsFor("").isEmpty())
    }

    @Test
    fun aSurrogatePairIsOneCodePointNotTwoChars() {
        // "😀" is U+1F600, two UTF-16 chars. Iterating Chars would send two lone
        // surrogates, each of which unicodeKeysym correctly refuses — so the
        // character would vanish rather than being typed.
        val emoji = "\uD83D\uDE00"
        assertEquals(2, emoji.length)
        assertEquals(listOf(0x1F600), VncTextInput.codePoints(emoji))
    }

    @Test
    fun mixedTextKeepsOrderAcrossASurrogatePair() {
        assertEquals(
            listOf('a'.code, 0x1F600, 'b'.code),
            VncTextInput.codePoints("a\uD83D\uDE00b")
        )
    }

    @Test
    fun cjkTextSplitsPerCharacter() {
        assertEquals(listOf(0x4F60, 0x597D), VncTextInput.codePoints("\u4F60\u597D"))
    }

    // ---- keysym conversion ----

    @Test
    fun asciiMapsToItsOwnCodePoint() {
        // Latin-1 IS the X11 keysym range by definition.
        assertEquals(listOf('h'.code, 'i'.code), VncTextInput.keysymsFor("hi"))
    }

    @Test
    fun aShiftedCharacterKeepsItsOwnKeysym() {
        // The core reason soft input does not use androidKeysym: the IME already
        // applied Shift and sends 'A' / '!' as finished characters. Mapping to the
        // base key here would type 'a' and '1'.
        assertEquals(listOf('A'.code), VncTextInput.keysymsFor("A"))
        assertEquals(listOf('!'.code), VncTextInput.keysymsFor("!"))
        assertEquals(listOf('$'.code), VncTextInput.keysymsFor("$"))
    }

    @Test
    fun latin1AccentsMapDirectly() {
        assertEquals(listOf(0xE9), VncTextInput.keysymsFor("\u00E9")) // é
    }

    @Test
    fun nonLatin1UsesTheX11UnicodeKeysymForm() {
        assertEquals(
            listOf(XKeysyms.UNICODE_KEYSYM_BASE + 0x4F60),
            VncTextInput.keysymsFor("\u4F60")
        )
    }

    @Test
    fun emojiUsesTheX11UnicodeKeysymForm() {
        assertEquals(
            listOf(XKeysyms.UNICODE_KEYSYM_BASE + 0x1F600),
            VncTextInput.keysymsFor("\uD83D\uDE00")
        )
    }

    @Test
    fun anUnmappableCharacterIsSkippedNotFatal() {
        // A paste or autocorrect commit is ONE call containing many characters; one
        // bad code point in the middle must not discard the rest of the user's text.
        // \u0001 is a control character with no keysym.
        assertEquals(listOf('a'.code, 'b'.code), VncTextInput.keysymsFor("a\u0001b"))
    }

    @Test
    fun aLoneSurrogateYieldsNoKeysym() {
        assertNull(XKeysyms.unicodeKeysym(0xD83D))
        assertTrue(VncTextInput.keysymsFor("\uD83D").isEmpty())
    }

    @Test
    fun controlCharactersHaveNoKeysym() {
        // Not typed text: an IME sends newline as KEYCODE_ENTER and Ctrl-chords via
        // the modifier path, both of which androidKeysym handles.
        assertNull(XKeysyms.unicodeKeysym(0x00))
        assertNull(XKeysyms.unicodeKeysym('\n'.code))
        assertNull(XKeysyms.unicodeKeysym('\t'.code))
        assertNull(XKeysyms.unicodeKeysym(0x1F))
    }

    @Test
    fun spaceIsTypeableText() {
        // The boundary of the control-character rule: 0x20 must pass.
        assertEquals(XKeysyms.SPACE, XKeysyms.unicodeKeysym(0x20))
        assertEquals(listOf(XKeysyms.SPACE), VncTextInput.keysymsFor(" "))
    }

    @Test
    fun del7fMapsToTheDeleteKeysym() {
        // 0x7F is not a printable Latin-1 character; X11 gives it XK_Delete.
        assertEquals(XKeysyms.DELETE, XKeysyms.unicodeKeysym(0x7F))
    }

    @Test
    fun theUnicodeKeysymBaseIsTheX11Constant() {
        assertEquals(0x01000000, XKeysyms.UNICODE_KEYSYM_BASE)
    }

    @Test
    fun theLatin1BoundaryIsDirectNotPrefixed() {
        assertEquals(0xFF, XKeysyms.unicodeKeysym(0xFF))
        assertEquals(XKeysyms.UNICODE_KEYSYM_BASE + 0x100, XKeysyms.unicodeKeysym(0x100))
    }

    // ---- deletion ----

    @Test
    fun aZeroLengthDeletionProducesNoKeystroke()
    {
        // The IME sends this routinely; a keystroke here would delete text the user
        // never asked to delete.
        assertEquals(0, VncTextInput.backspaceCount(0))
        assertEquals(0, VncTextInput.deleteCount(0))
    }

    @Test
    fun deletionCountsPassThrough() {
        assertEquals(3, VncTextInput.backspaceCount(3))
        assertEquals(2, VncTextInput.deleteCount(2))
    }

    @Test
    fun aNegativeDeletionLengthIsClampedNotSentBackwards() {
        assertEquals(0, VncTextInput.backspaceCount(-5))
        assertEquals(0, VncTextInput.deleteCount(-5))
    }
}
