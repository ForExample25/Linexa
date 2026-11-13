package com.fem.linexa.service.runtime.pty

import com.fem.linexa.test.support.ControllableStubPtySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The preview seam every backend shares: a preview is always THIS session's
 * output, bounded and sanitized, and never a shared or synthetic buffer.
 */
class PtySessionPreviewTest {

    @Test
    fun defaultPreviewReadsOnlyTheOwningSessionsOutput() {
        val alpha = ControllableStubPtySession("pty-a", "ws-1", 100)
        val beta = ControllableStubPtySession("pty-b", "ws-1", 101)
        alpha.transcriptOutput = "\u001B[32malpha$ \u001B[0m\n"
        beta.transcriptOutput = "beta$ \n"

        assertEquals("alpha$", alpha.previewText())
        assertEquals("beta$", beta.previewText())
    }

    @Test
    fun previewFollowsTheLineAndCharacterBudgets() {
        val pty = ControllableStubPtySession("pty-a", "ws-1", 100)
        pty.transcriptOutput = (1..30).joinToString("\n") { "line $it" }

        assertEquals("line 29\nline 30", pty.previewText(maxLines = 2))
        assertEquals("line 30", pty.previewText(maxLines = 1, maxChars = 7))
    }

    @Test
    fun previewIsEmptyUntilTheSessionWritesSomething() {
        val pty = ControllableStubPtySession("pty-a", "ws-1", 100)

        assertEquals("", pty.previewText())
        assertNull(pty.title)
        assertNull(pty.foregroundProcessName())
    }
}
