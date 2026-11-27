package com.fem.linexa.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 011 — the pure payloads behind **Copy error** and **Diagnostics**.
 *
 * The panel is source-guarded elsewhere; this file pins the evidence itself.
 * The audit asks for "code, reason, and last output", so every test here
 * asserts those survive in the copied text, and that the report never invents
 * a status when the observer had none (finding 027).
 */
class TerminalErrorReportTest {

    private val error127 = TerminalState.Error(
        reason = "Terminal T1 exited: command or shell not found (code 127)",
        exitCode = 127,
        hint = "Check launch.shell and environment.shell, and that the command exists in this image."
    )

    @Test
    fun copyTextCarriesCodeReasonHintAndLastOutput() {
        val text = TerminalErrorReport.copyText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = error127,
            lastOutput = "linexa@guest:~$ not-a-command\r\nnot-a-command: not found\r\n"
        )

        assertTrue("workspace id must be present: $text", text.contains("Workspace: ws-7"))
        assertTrue("terminal id must be present: $text", text.contains("Terminal: T1"))
        assertTrue("raw status must be present: $text", text.contains("Exit status: 127"))
        assertTrue("reason must be verbatim: $text", text.contains(error127.reason))
        assertTrue("hint must travel with the reason: $text", text.contains(error127.hint!!))
        assertTrue("last output must be present: $text", text.contains("not-a-command: not found"))
    }

    @Test
    fun copyTextStatesAnUnavailableStatusInsteadOfInventingOne() {
        val text = TerminalErrorReport.copyText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = TerminalState.Error(
                reason = "Terminal T1 exited unexpectedly (exit status unavailable)",
                exitCode = null
            ),
            lastOutput = ""
        )

        assertTrue("the absence must be explicit: $text", text.contains("Exit status: unavailable"))
        assertFalse("must not fabricate -1/SIGHUP: $text", text.contains("Exit status: -1"))
        assertTrue(
            "a blank screen gets an honest placeholder: $text",
            text.contains("(no terminal output was captured)")
        )
    }

    @Test
    fun anOptionalHintDoesNotLeaveAnEmptyLineOrClaimACause() {
        val text = TerminalErrorReport.copyText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = TerminalState.Error(reason = "Terminal T1 was killed by signal 2", exitCode = -2),
            lastOutput = "partial"
        )

        assertFalse("no hint means no Hint line: $text", text.contains("Hint:"))
        assertTrue("the status is still evidence: $text", text.contains("Exit status: -2"))
    }

    @Test
    fun longOutputKeepsTheTailAndSaysHowMuchWasOmitted() {
        val prefix = "old-line-".repeat(2_000)
        val tail = "THE-FAILURE-LINE"
        val text = TerminalErrorReport.copyText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = error127,
            lastOutput = prefix + tail
        )

        assertTrue("the tail must survive: $text", text.contains(tail))
        assertTrue("the omission must be stated: $text", text.contains("earlier characters omitted"))
        assertTrue(
            "the omitted length must be truthful: $text",
            text.contains("${prefix.length + tail.length - TerminalErrorReport.DEFAULT_OUTPUT_CHARS} earlier characters omitted")
        )
    }

    @Test
    fun diagnosticsAddsSectionHeadersAndTheWorkspaceTrace() {
        val trace = "[2026-11-15 10:00:00.000] WorkspaceTerminalManager.openTerminal ENTER terminalId=T1"
        val text = TerminalErrorReport.diagnosticsText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = error127,
            lastOutput = "guest output",
            runtimeTrace = trace
        )

        assertTrue("section header must be parseable: $text", text.contains("=== Terminal T1 ==="))
        assertTrue("last output section: $text", text.contains("=== Last output ==="))
        assertTrue("runtime trace section: $text", text.contains("=== Runtime trace ==="))
        assertTrue("the trace must be included: $text", text.contains(trace))
        assertTrue("the failure facts are still there: $text", text.contains("Exit status: 127"))
    }

    @Test
    fun diagnosticsNamesAMissingTraceInsteadOfShowingAnEmptySection() {
        val text = TerminalErrorReport.diagnosticsText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = error127,
            lastOutput = "guest output",
            runtimeTrace = "   \n"
        )

        assertTrue(
            "an empty trace must be stated, not implied: $text",
            text.contains("(no runtime trace captured)")
        )
    }

    @Test
    fun trimmingDoesNotEatTheTrailingFailureEvidence() {
        // The tail is the evidence; trimEnd() removes only the pty's trailing
        // newlines, never the last visible character.
        val text = TerminalErrorReport.copyText(
            workspaceId = "ws-7",
            terminalId = "T1",
            error = error127,
            lastOutput = "prompt$ crash\n\n\n"
        )

        assertTrue("the last visible line must survive: $text", text.contains("prompt$ crash"))
        assertFalse("no dangling blank line is needed", text.endsWith("\n"))
    }

    @Test
    fun noOutputDiagnosticsDescribesALiveSessionWithoutInventingAnExitStatus() {
        val text = TerminalErrorReport.noOutputDiagnostics(
            workspaceId = "ws-7",
            terminalId = "T1",
            pid = 4242,
            silentMs = 8_000L,
            runtimeTrace = "trace line"
        )

        assertTrue(text.contains("=== Terminal T1 ==="))
        assertTrue(text.contains("State: live, no output"))
        assertTrue(text.contains("PID: 4242"))
        assertTrue(text.contains("Silent for: 8000ms"))
        assertTrue(text.contains("=== Runtime trace ==="))
        assertTrue(text.contains("trace line"))
        assertFalse("a live session has no exit status to report", text.contains("Exit status"))
    }

    @Test
    fun theReportIsDeterministic() {
        val first = TerminalErrorReport.diagnosticsText("ws-7", "T1", error127, "out", "trace")
        val second = TerminalErrorReport.diagnosticsText("ws-7", "T1", error127, "out", "trace")

        assertEquals("same evidence must render identically", first, second)
    }
}
