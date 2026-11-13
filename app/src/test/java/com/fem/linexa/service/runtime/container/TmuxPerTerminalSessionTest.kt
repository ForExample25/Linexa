package com.fem.linexa.service.runtime.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transformation spec §9/F — per-terminal tmux persistence: every terminal
 * of a workspace owns its OWN tmux session on the workspace socket, so the
 * second terminal no longer reattaches to the first one's session, and a
 * re-opened terminal id reattaches to its own session.
 */
class TmuxPerTerminalSessionTest {

    @Test
    fun `each terminal id gets a distinct session name`() {
        val t0 = TmuxPersist.sessionNameFor("T0")
        val t1 = TmuxPersist.sessionNameFor("T1")
        assertEquals("linexa-T0", t0)
        assertEquals("linexa-T1", t1)
        assertNotEquals(t0, t1)
    }

    @Test
    fun `session name is a legal tmux name (no dot or colon)`() {
        val name = TmuxPersist.sessionNameFor("T12")
        assertTrue("tmux names may not contain '.'", !name.contains('.'))
        assertTrue("tmux names may not contain ':'", !name.contains(':'))
    }

    @Test
    fun `unsanitary ids are sanitized deterministically`() {
        assertEquals("linexa-T_1", TmuxPersist.sessionNameFor("T.1"))
        assertEquals(TmuxPersist.sessionNameFor("T:1"), TmuxPersist.sessionNameFor("T:1"))
    }

    @Test
    fun `blank id degrades to a stable default`() {
        assertEquals("linexa-default", TmuxPersist.sessionNameFor(""))
        assertEquals("linexa-default", TmuxPersist.sessionNameFor("   "))
    }

    @Test
    fun `base session name constant is unchanged for diagnostics compat`() {
        assertEquals("linexa", TmuxPersist.DEFAULT_SESSION_NAME)
    }
}
