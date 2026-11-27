package com.fem.linexa.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal audit finding 027 — the pure exit-code table.
 *
 * The audit asks for one table shared by the manager and the UI, with the raw
 * code kept visible and no cause claimed that the status cannot prove. That
 * makes this a pure-function contract: every row is asserted directly here,
 * and [com.fem.linexa.service.runtime.WorkspaceTerminalManagerTest] pins that
 * the manager actually carries the result onto `TerminalState.Error`.
 */
class TerminalExitDescriptionTest {

    private data class Case(
        val code: Int,
        val summary: String,
        val hint: String?
    )

    /**
     * One row per audit entry, plus the signal-death rows that the same
     * `awaitExit()` surface can produce (waitpid reports those negated, so
     * `-9` — never `137` — is the OS kill).
     */
    private val cases = listOf(
        Case(0, "closed", null),
        Case(
            126,
            "exited: command not executable",
            "Check that the file is executable and that its interpreter (shebang) exists."
        ),
        Case(
            127,
            "exited: command or shell not found",
            "Check launch.shell and environment.shell, and that the command exists in this image."
        ),
        Case(
            137,
            "exited as if killed by SIGKILL",
            "A guest process may have been killed; Android may have reclaimed memory."
        ),
        Case(
            139,
            "exited as if killed by SIGSEGV",
            "A guest binary or its interpreter crashed. Check the image and the command."
        ),
        Case(
            1,
            "exited unexpectedly",
            "Open diagnostics for the session output."
        ),
        Case(
            255,
            "exited unexpectedly",
            "Open diagnostics for the session output."
        ),
        Case(
            -9,
            "was killed by SIGKILL",
            "Android may have reclaimed the terminal to free memory."
        ),
        Case(
            -11,
            "was killed by SIGSEGV",
            "A guest binary crashed with a segmentation fault."
        ),
        Case(
            -15,
            "was killed by SIGTERM",
            "The guest process was asked to terminate."
        ),
        Case(-2, "was killed by signal 2", null)
    )

    @Test
    fun everyRowMatchesTheAuditTable() {
        cases.forEach { case ->
            val description = TerminalExitDescription.of(case.code)

            assertEquals("summary for ${case.code}", case.summary, description.summary)
            assertEquals("hint for ${case.code}", case.hint, description.hint)
            assertEquals("the raw status must be preserved for ${case.code}", case.code, description.exitCode)
        }
    }

    @Test
    fun reasonKeepsTheRawCodeVisibleInEveryRow() {
        // The audit's one hard rule: never replace the number with prose. The
        // manager renders this string, so the terminal id and the raw status
        // must both survive.
        cases.forEach { case ->
            val reason = TerminalExitDescription.of(case.code).reason("T1")

            assertTrue(
                "reason for ${case.code} must name the terminal: $reason",
                reason.startsWith("Terminal T1 ")
            )
            assertTrue(
                "reason for ${case.code} must keep the raw status visible: $reason",
                reason.endsWith("(code ${case.code})")
            )
        }
    }

    @Test
    fun unknownPositiveCodesStayGenericInsteadOfInventingAMeaning() {
        // 2..125, 128..136 and 140..255 have no table entry; the audit's
        // "other" row is deliberately vague, and the hint points at the log.
        listOf(2, 42, 125, 128, 130, 136, 140, 200, 255).forEach { code ->
            val description = TerminalExitDescription.of(code)

            assertEquals("code $code must not acquire a made-up meaning", "exited unexpectedly", description.summary)
            assertEquals("Open diagnostics for the session output.", description.hint)
        }
    }

    @Test
    fun signalDeathsNameOnlyTheSignalsAUserCanActOn() {
        // -9 / -11 / -15 get names; every other signal keeps its number rather
        // than borrowing one of those explanations.
        assertEquals("was killed by SIGKILL", TerminalExitDescription.of(-9).summary)
        assertEquals("was killed by SIGSEGV", TerminalExitDescription.of(-11).summary)
        assertEquals("was killed by SIGTERM", TerminalExitDescription.of(-15).summary)
        listOf(-1, -2, -6, -13, -19, -31).forEach { code ->
            val description = TerminalExitDescription.of(code)

            assertEquals("summary for signal ${-code}", "was killed by signal ${-code}", description.summary)
            assertNull("no honest next step for signal ${-code}", description.hint)
        }
    }

    @Test
    fun conventionalStatusesAreHedgedBecauseTheyCannotProveACause() {
        // 137 and 139 are normal exit statuses: the guest may simply have
        // called exit(137). Only the negated signal rows are kernel evidence,
        // so the 128+N rows say "as if" rather than asserting the signal.
        assertTrue(
            "137 must stay hedged",
            TerminalExitDescription.of(137).summary.contains("as if")
        )
        assertTrue(
            "139 must stay hedged",
            TerminalExitDescription.of(139).summary.contains("as if")
        )
        assertFalse(
            "-9 is the only status that may claim a signal death as kernel evidence",
            TerminalExitDescription.of(-9).summary.contains("as if")
        )
    }
}
