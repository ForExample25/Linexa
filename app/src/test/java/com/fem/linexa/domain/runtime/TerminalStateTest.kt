package com.fem.linexa.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10D — the behavioural half of §1.191's SIGKILL classification, moved here
 * from TerminalKilledNoticeSourceTest, where the derivation's shape
 * (`exitCode == SIGKILL_EXIT_STATUS`) and the `-9` literal were pinned as
 * source text. [TerminalState.Error] is a pure data class, so the whole
 * contract is directly observable on the JVM.
 *
 * [com.fem.linexa.service.runtime.WorkspaceTerminalManagerTest] already pins
 * the classification through the real terminal pipeline; this pins the domain
 * type itself, including the no-exit-status default a failure-to-open relies
 * on.
 */
class TerminalStateTest {

    @Test
    fun sigkillIsClassifiedOnlyFromTheNegatedSignalStatus() {
        assertTrue(
            "the status terminal-lib reports for a SIGKILLed child must classify as killedBySigkill",
            TerminalState.Error(reason = "killed", exitCode = TerminalState.SIGKILL_EXIT_STATUS)
                .killedBySigkill
        )
        // A -9 cannot prove its own cause — which is why the notice copy hedges
        // — but no other status may borrow the notice: a normal exit, another
        // signal, and a failure with no child must all classify as "not SIGKILL".
        assertFalse(TerminalState.Error(reason = "exited", exitCode = 0).killedBySigkill)
        assertFalse(TerminalState.Error(reason = "sigterm", exitCode = -15).killedBySigkill)
        assertFalse(TerminalState.Error(reason = "spawn failed").killedBySigkill)
    }

    @Test
    fun theSigkillStatusIsTheNegatedSignalNumber() {
        // Negated-signal convention: a child killed by signal N reports -N
        // through waitpid, so SIGKILL (9) is -9.
        assertEquals(-9, TerminalState.SIGKILL_EXIT_STATUS)
    }

    @Test
    fun errorDefaultsToNoExitStatus() {
        // Defaulted, because a failure-to-open has no child and therefore no
        // exit status to report. Dropping the default would force every Error
        // construction site to invent one.
        assertNull(TerminalState.Error(reason = "spawn failed").exitCode)
    }

    @Test
    fun hintIsAdditiveAndDefaultsToNone() {
        // Finding 027: the actionable half of the exit diagnosis rides beside
        // the reason. It is optional so a failure-to-open (no child, no status)
        // and diagnostic-only construction sites stay valid unchanged.
        val withHint = TerminalState.Error(reason = "exited", exitCode = 127, hint = "Check the shell.")
        assertEquals("Check the shell.", withHint.hint)
        assertNull(TerminalState.Error(reason = "exited", exitCode = 127).hint)
        assertNull(TerminalState.Error(reason = "spawn failed").hint)
    }
}
