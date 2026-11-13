package com.fem.linexa.ui.screens.desktop

/**
 * UI/UX Phase 8 (§1.138) — the composing-region tracker for the VNC client's IME
 * path.
 *
 * ## The defect this exists for
 *
 * §1.125 handled `setComposingText` by typing the whole string it received:
 *
 * ```
 * override fun setComposingText(text: CharSequence?, …) {
 *     VncTextInput.keysymsFor(text).forEach(onKeysym)
 * }
 * ```
 *
 * An IME does not send *new* characters through that callback — it sends the
 * **entire current composing region** every time it changes. So typing `hello` on
 * a keyboard that composes (gesture typing, most predictive keyboards, and every
 * CJK/Indic IME) delivered `h`, `he`, `hel`, `hell`, `hello`, and the remote
 * desktop received **15 characters** for 5 keystrokes: `hhehelhellhello`. Nothing
 * failed, nothing logged, and it is invisible in a JVM test of `keysymsFor`,
 * which was correct — the bug was in *how often* it was called with *what*.
 *
 * Autocorrect made it worse: an IME revising `helli` to `hello` sends the whole
 * corrected region again, so the remote application saw both spellings appended.
 *
 * ## The rule
 *
 * The remote X application is the text buffer, and the only editing operation
 * this client has towards it is "BackSpace". So a composing update is expressed
 * as the difference between what was already typed and what the IME now wants:
 * keep the common **code-point** prefix, backspace away the rest, type the
 * remainder. That is the minimal sequence which leaves the remote buffer equal to
 * the new composing text, and it makes the common case (one character appended)
 * exactly one keysym.
 *
 * The prefix is compared by code point, not by `Char`: a shared surrogate pair
 * would otherwise be "half equal", and one BackSpace does not delete half of an
 * emoji on any X application.
 *
 * ## Why it is a class rather than a pure function
 *
 * It has to remember what it last typed, and that state belongs next to the rule
 * rather than in the `View` — the pre-Phase-8 `VncSurfaceView` held all input
 * state itself, which is why none of it was ever asserted. Everything here is
 * JVM-drivable: an IME cannot be driven from a test, so the translation is where
 * the correctness has to be established.
 */
internal class VncComposingText {

    /** Code points already typed into the remote application for this region. */
    private var typed: List<Int> = emptyList()

    /** Whether a composing region is currently open. */
    val isComposing: Boolean get() = typed.isNotEmpty()

    /** The code points currently believed to be in the remote buffer's tail. */
    val composed: List<Int> get() = typed

    /**
     * The keys to send for a `setComposingText` update to [text].
     *
     * Returns the BackSpaces that retract the diverging tail followed by the
     * keysyms for the new tail — never the whole region again.
     */
    fun compose(text: CharSequence): VncKeyBatch {
        val next = VncTextInput.codePoints(text)
        val shared = sharedPrefixLength(typed, next)
        val backspaces = typed.size - shared
        val tail = next.drop(shared)
        typed = next
        return VncKeyBatch(
            backspaces = backspaces,
            keysyms = tail.mapNotNull { VncTextInput.keysymFor(it) }
        )
    }

    /**
     * The keys to send for a `commitText` of [text], which finishes the region.
     *
     * The committed text usually *equals* what composing already typed (the IME
     * commits the region it has been showing), so this must diff too — committing
     * blind would type the whole word a second time, which is the same defect one
     * callback along.
     */
    fun commit(text: CharSequence): VncKeyBatch {
        val batch = compose(text)
        typed = emptyList()
        return batch
    }

    /**
     * The region was abandoned (`finishComposingText`) without being committed.
     *
     * Nothing is retracted: the characters are already in the remote application,
     * which has no notion of a provisional region — deleting them here would
     * silently eat text the user watched appear. The tracker only forgets, so the
     * next region diffs from empty.
     */
    fun finish() {
        typed = emptyList()
    }

    /**
     * The BackSpaces for a `deleteSurroundingText(beforeLength, …)`, whose length
     * is in **UTF-16 chars** while the remote application deletes **characters**.
     *
     * An IME deleting one emoji asks for `beforeLength = 2`, which as two
     * BackSpaces would delete the emoji *and* whatever preceded it. When a
     * composing region is open its code points are known, so the request is
     * converted exactly by walking the tail. With no region open there is nothing
     * to walk — the remote buffer is not readable — so the length is used as given,
     * which is the pre-Phase-8 behaviour and correct for the BMP text that reaches
     * this path in practice.
     */
    fun retract(beforeLengthUtf16: Int): Int {
        if (beforeLengthUtf16 <= 0) return 0
        if (typed.isEmpty()) return beforeLengthUtf16
        var remainingUtf16 = beforeLengthUtf16
        var count = 0
        var index = typed.lastIndex
        while (index >= 0 && remainingUtf16 > 0) {
            remainingUtf16 -= Character.charCount(typed[index])
            count++
            index--
        }
        typed = typed.take(index + 1)
        // A request longer than the known region still deletes the rest of it plus
        // whatever the IME says is beyond — never fewer characters than asked for.
        return count + maxOf(0, remainingUtf16)
    }

    private fun sharedPrefixLength(a: List<Int>, b: List<Int>): Int {
        var i = 0
        val limit = minOf(a.size, b.size)
        while (i < limit && a[i] == b[i]) i++
        return i
    }
}

/**
 * One IME edit expressed in the only two operations the client has: retract
 * [backspaces] characters, then type [keysyms].
 *
 * Ordered deliberately — a caller that typed first would leave the retraction
 * deleting the text it just sent.
 */
internal data class VncKeyBatch(
    val backspaces: Int,
    val keysyms: List<Int>
) {
    val isEmpty: Boolean get() = backspaces == 0 && keysyms.isEmpty()
}
