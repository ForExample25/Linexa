package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 8 (§1.138) — JVM tests for [VncComposingText].
 *
 * The defect being pinned here is invisible to a test of `keysymsFor` (which was
 * correct): an IME re-sends the **entire** composing region on every change, so
 * typing `hello` on any predictive/gesture/CJK keyboard sent
 * `h he hel hell hello` — 15 characters for 5 keystrokes. An IME cannot be driven
 * from a JVM test, so the translation is where correctness has to be established.
 */
class VncComposingTextTest {

    private fun codePointsOf(text: String) = VncTextInput.codePoints(text)

    private fun typed(batch: VncKeyBatch): String {
        val sb = StringBuilder()
        batch.keysyms.forEach { keysym ->
            // The keysyms produced here are Latin-1 or the 0x01000000 Unicode range.
            val codePoint = if (keysym >= 0x01000000) keysym - 0x01000000 else keysym
            sb.appendCodePoint(codePoint)
        }
        return sb.toString()
    }

    /** Applies a batch to a model of the remote application's buffer. */
    private fun StringBuilder.apply(batch: VncKeyBatch): StringBuilder {
        repeat(batch.backspaces) {
            if (isNotEmpty()) {
                val last = codePointBefore(length)
                setLength(length - Character.charCount(last))
            }
        }
        append(typed(batch))
        return this
    }

    // ── the headline defect ──────────────────────────────────────────────

    @Test
    fun composingOneCharacterAtATimeTypesOnlyTheNewCharacter()
    {
        val tracker = VncComposingText()
        assertEquals("h", typed(tracker.compose("h")))
        assertEquals("e", typed(tracker.compose("he")))
        assertEquals("l", typed(tracker.compose("hel")))
        assertEquals("l", typed(tracker.compose("hell")))
        assertEquals("o", typed(tracker.compose("hello")))
    }

    @Test
    fun growingARegionNeverBackspaces() {
        val tracker = VncComposingText()
        listOf("h", "he", "hel", "hell", "hello").forEach {
            assertEquals(0, tracker.compose(it).backspaces)
        }
    }

    @Test
    fun theRemoteBufferEndsUpHoldingExactlyTheTypedWord() {
        // The property that matters, stated against a model of the remote buffer
        // rather than against the keysym list.
        val tracker = VncComposingText()
        val remote = StringBuilder()
        listOf("h", "he", "hel", "hell", "hello").forEach {
            remote.apply(tracker.compose(it))
        }
        assertEquals("hello", remote.toString())
    }

    // ── autocorrect / revision ───────────────────────────────────────────

    @Test
    fun aRevisedRegionRetractsOnlyTheDivergingTail() {
        // `helli` → `hello`: one BackSpace and one `o`, not five and five.
        val tracker = VncComposingText()
        tracker.compose("helli")
        val batch = tracker.compose("hello")
        assertEquals(1, batch.backspaces)
        assertEquals("o", typed(batch))
    }

    @Test
    fun anAutocorrectToADifferentWordLeavesOnlyTheCorrection() {
        val tracker = VncComposingText()
        val remote = StringBuilder()
        remote.apply(tracker.compose("teh"))
        remote.apply(tracker.compose("the"))
        assertEquals("the", remote.toString())
    }

    @Test
    fun aWholesaleReplacementRetractsTheWholeRegion() {
        val tracker = VncComposingText()
        tracker.compose("abc")
        val batch = tracker.compose("xyz")
        assertEquals(3, batch.backspaces)
        assertEquals("xyz", typed(batch))
    }

    @Test
    fun shrinkingARegionOnlyBackspaces() {
        // The IME's own backspace inside a composing region.
        val tracker = VncComposingText()
        tracker.compose("hello")
        val batch = tracker.compose("hell")
        assertEquals(1, batch.backspaces)
        assertTrue(batch.keysyms.isEmpty())
    }

    @Test
    fun anUnchangedRegionEmitsNothing() {
        // IMEs re-send an identical region routinely (cursor moves, style changes);
        // each one would otherwise be a duplicated word.
        val tracker = VncComposingText()
        tracker.compose("hello")
        assertTrue(tracker.compose("hello").isEmpty)
    }

    @Test
    fun clearingTheRegionRetractsAllOfIt() {
        val tracker = VncComposingText()
        tracker.compose("hello")
        val batch = tracker.compose("")
        assertEquals(5, batch.backspaces)
        assertTrue(batch.keysyms.isEmpty())
        assertFalse(tracker.isComposing)
    }

    // ── commit ──────────────────────────────────────────────────────────

    @Test
    fun committingTheRegionThatWasAlreadyComposedTypesNothing() {
        // The same defect one callback along: an IME commits the region it has been
        // showing, so a blind commit types the whole word a second time.
        val tracker = VncComposingText()
        tracker.compose("hello")
        assertTrue(tracker.commit("hello").isEmpty)
    }

    @Test
    fun committingClosesTheRegion() {
        val tracker = VncComposingText()
        tracker.compose("hello")
        tracker.commit("hello")
        assertFalse(tracker.isComposing)
        assertTrue(tracker.composed.isEmpty())
    }

    @Test
    fun committingWithAnAppendedSpaceTypesOnlyTheSpace() {
        // The common Latin path: the IME commits "hello " having composed "hello".
        val tracker = VncComposingText()
        tracker.compose("hello")
        val batch = tracker.commit("hello ")
        assertEquals(0, batch.backspaces)
        assertEquals(" ", typed(batch))
    }

    @Test
    fun committingACorrectionRetractsTheComposedTail() {
        val tracker = VncComposingText()
        tracker.compose("wrold")
        val remote = StringBuilder().apply(VncKeyBatch(0, VncTextInput.keysymsFor("wrold")))
        remote.apply(tracker.commit("world"))
        assertEquals("world", remote.toString())
    }

    @Test
    fun aDirectCommitWithNoRegionTypesTheWholeString() {
        // A plain keyboard (or a paste) never composes; nothing may be retracted.
        val tracker = VncComposingText()
        val batch = tracker.commit("sudo apt update")
        assertEquals(0, batch.backspaces)
        assertEquals("sudo apt update", typed(batch))
    }

    @Test
    fun consecutiveDirectCommitsDoNotDiffAgainstEachOther() {
        // Each commit closes the region, so the second must not see the first as a
        // prefix to retract.
        val tracker = VncComposingText()
        tracker.commit("ls")
        val batch = tracker.commit("ls")
        assertEquals(0, batch.backspaces)
        assertEquals("ls", typed(batch))
    }

    // ── finish ──────────────────────────────────────────────────────────

    @Test
    fun finishingRetractsNothing() {
        // The characters are already in a remote application that has no notion of a
        // provisional region; deleting them would eat text the user watched appear.
        val tracker = VncComposingText()
        tracker.compose("hello")
        tracker.finish()
        assertFalse(tracker.isComposing)
    }

    @Test
    fun aRegionAfterFinishingDiffsFromEmpty() {
        val tracker = VncComposingText()
        tracker.compose("hello")
        tracker.finish()
        val batch = tracker.compose("h")
        assertEquals(0, batch.backspaces)
        assertEquals("h", typed(batch))
    }

    // ── code points, not chars ──────────────────────────────────────────

    @Test
    fun aSurrogatePairIsOneCharacter() {
        // One BackSpace does not delete half an emoji on any X application, so the
        // prefix has to be compared by code point.
        val tracker = VncComposingText()
        tracker.compose("\uD83D\uDE00") // 😀
        assertEquals(1, tracker.composed.size)
    }

    @Test
    fun replacingAnEmojiRetractsOneCharacterNotTwo() {
        val tracker = VncComposingText()
        tracker.compose("\uD83D\uDE00")
        val batch = tracker.compose("\uD83D\uDE01")
        assertEquals(1, batch.backspaces)
    }

    @Test
    fun aSharedSurrogateLeadIsNotTreatedAsACommonPrefix() {
        // Both start with 0xD83D. A char-wise prefix would keep half a character and
        // then type the trailing surrogate on its own.
        val tracker = VncComposingText()
        tracker.compose("a\uD83D\uDE00")
        val batch = tracker.compose("a\uD83D\uDE01")
        assertEquals(1, batch.backspaces)
        assertEquals(1, batch.keysyms.size)
    }

    @Test
    fun cjkCompositionTypesOnlyTheFinalCharacters() {
        // A Pinyin IME composes latin letters and then replaces them with hanzi.
        val tracker = VncComposingText()
        val remote = StringBuilder()
        remote.apply(tracker.compose("ni"))
        remote.apply(tracker.compose("nih"))
        remote.apply(tracker.compose("niha"))
        remote.apply(tracker.compose("nihao"))
        remote.apply(tracker.commit("\u4F60\u597D"))
        assertEquals("\u4F60\u597D", remote.toString())
    }

    @Test
    fun anUnmappableCodePointIsSkippedWithoutLosingTheRest() {
        // A lone unmapped code point must not discard the surrounding text, and it
        // must not shift the retraction count either.
        val tracker = VncComposingText()
        val batch = tracker.commit("ok")
        assertEquals("ok", typed(batch))
    }

    // ── retract (deleteSurroundingText) ─────────────────────────────────

    @Test
    fun deletingOneCharWithNoRegionIsOneBackspace() {
        assertEquals(1, VncComposingText().retract(1))
    }

    @Test
    fun deletingAnEmojiInsideTheRegionIsOneBackspace() {
        // The IME asks for beforeLength = 2 (UTF-16 chars); two BackSpaces would
        // delete the emoji *and* the character before it.
        val tracker = VncComposingText()
        tracker.compose("a\uD83D\uDE00")
        assertEquals(1, tracker.retract(2))
    }

    @Test
    fun retractingShortensTheTrackedRegion() {
        val tracker = VncComposingText()
        tracker.compose("abc")
        tracker.retract(1)
        assertEquals(codePointsOf("ab"), tracker.composed)
    }

    @Test
    fun retractingBeyondTheRegionStillDeletesEverythingAsked() {
        // Never fewer characters than the IME requested: the excess is outside the
        // region, where the length is already in the right unit.
        val tracker = VncComposingText()
        tracker.compose("ab")
        assertEquals(4, tracker.retract(4))
        assertTrue(tracker.composed.isEmpty())
    }

    @Test
    fun retractingZeroIsANoOp() {
        // IMEs send this routinely; it must not produce a keystroke.
        val tracker = VncComposingText()
        tracker.compose("abc")
        assertEquals(0, tracker.retract(0))
        assertEquals(3, tracker.composed.size)
    }

    @Test
    fun retractingANegativeLengthIsANoOp() {
        assertEquals(0, VncComposingText().retract(-3))
    }

    @Test
    fun aRegionSurvivesAPartialRetraction() {
        val tracker = VncComposingText()
        tracker.compose("hello")
        tracker.retract(2)
        val batch = tracker.compose("help")
        assertEquals(0, batch.backspaces)
        assertEquals("p", typed(batch))
    }

    // ── batch shape ─────────────────────────────────────────────────────

    @Test
    fun anEmptyBatchIsRecognisable() {
        assertTrue(VncKeyBatch(0, emptyList()).isEmpty)
        assertFalse(VncKeyBatch(1, emptyList()).isEmpty)
        assertFalse(VncKeyBatch(0, listOf(0x61)).isEmpty)
    }

    @Test
    fun aFreshTrackerIsNotComposing() {
        val tracker = VncComposingText()
        assertFalse(tracker.isComposing)
        assertTrue(tracker.composed.isEmpty())
    }

    @Test
    fun composingMakesTheTrackerComposing() {
        val tracker = VncComposingText()
        tracker.compose("x")
        assertTrue(tracker.isComposing)
    }
}
