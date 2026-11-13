package com.fem.linexa.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPreviewTest {

    @Test
    fun stripsAnsiAndOscSequencesFromRealTerminalOutput() {
        val raw = "\u001B]0;user@linexa: ~\u0007\u001B[32muser@linexa\u001B[0m:\u001B[34m~\u001B[0m$ uname -a\r\nLinux linexa 6.6.0"

        val preview = TerminalPreview.sanitize(raw)

        assertEquals("user@linexa:~$ uname -a\nLinux linexa 6.6.0", preview)
        assertFalse(preview.contains('\u001B'))
    }

    @Test
    fun normalizesCarriageReturnsAndTrimsTrailingWhitespace() {
        val preview = TerminalPreview.sanitize("first   \r\nsecond\t\rthird   ")

        assertEquals("first\nsecond\nthird", preview)
    }

    @Test
    fun keepsOnlyTheMostRecentLines() {
        val raw = (1..20).joinToString("\n") { "line $it" }

        val preview = TerminalPreview.sanitize(raw, maxLines = 3)

        assertEquals("line 18\nline 19\nline 20", preview)
    }

    @Test
    fun respectsTheCharacterBudgetFromTheTail() {
        val raw = "a".repeat(100) + "KEEP"

        val preview = TerminalPreview.sanitize(raw, maxChars = 4)

        assertEquals("KEEP", preview)
    }

    @Test
    fun emptyOrBlankOutputProducesNoFakePlaceholder() {
        assertEquals("", TerminalPreview.sanitize(""))
        assertEquals("", TerminalPreview.sanitize("   \n\t\n"))
        assertEquals("", TerminalPreview.sanitize("anything", maxLines = 0))
        assertEquals("", TerminalPreview.sanitize("anything", maxChars = 0))
    }

    @Test
    fun previewIsPerSessionContentNotSynthesized() {
        val alpha = TerminalPreview.sanitize("alpha-output\n$ ")
        val beta = TerminalPreview.sanitize("beta-output\n$ ")

        assertTrue(alpha.contains("alpha-output"))
        assertFalse(alpha.contains("beta-output"))
        assertTrue(beta.contains("beta-output"))
        assertFalse(beta.contains("alpha-output"))
    }
}
