package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.97/W1 — sessions are keyed by [TerminalKey] (workspaceId, terminalId),
 * not by workspace alone. These tests exercise the new keying shape against
 * the REAL [PRootContainerRuntime] through the fake-PRoot harness:
 *
 *  - opening a terminal with the SAME id twice replaces the previous one
 *    (the old pre-W1 replace-on-open behavior, scoped to a terminal id);
 *  - two DISTINCT terminal ids in the SAME workspace coexist as independent
 *    proot sessions with independent PROOT_TMP_DIR scopes;
 *  - [closeTerminal] tears down exactly one terminal, leaving the others
 *    alive;
 *  - [closeWorkspaceSessions] tears down every terminal of one workspace and
 *    nothing in another.
 *
 * W1 keeps the single-terminal call path: everything below also holds when
 * only the default terminal [DEFAULT_TERMINAL_ID] ("T0") is opened, which is
 * how the UI drives it today.
 */
class PRootTerminalKeyingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    private fun openTerminal(workspaceId: String, terminalId: String): PtySession = runBlocking {
        harness.runtime.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, terminalId, asRoot = false)
    }

    private fun openDefaultTerminal(workspaceId: String = "ws-1"): PtySession =
        openTerminal(workspaceId, DEFAULT_TERMINAL_ID)

    @Test
    fun sameTerminalIdReplacesPriorSession() {
        val first = openDefaultTerminal()
        val second = openDefaultTerminal()

        assertNotEquals("replacement must be a fresh pty session", first.pid, second.pid)
        // The map holds exactly the latest session for (ws-1, T0).
        val current = harness.runtime.getTerminal("ws-1", DEFAULT_TERMINAL_ID)
        assertEquals(second.pid, current?.pid)
        assertEquals("two opens → two create calls (the second replaced the first)", 2, harness.createCalls.size)
    }

    @Test
    fun distinctTerminalIdsCoexistInSameWorkspace() {
        val t0 = openTerminal("ws-1", DEFAULT_TERMINAL_ID)
        val t1 = openTerminal("ws-1", "T1")

        assertTrue(harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(t0.pid, harness.runtime.getTerminal("ws-1", DEFAULT_TERMINAL_ID)?.pid)
        assertEquals(t1.pid, harness.runtime.getTerminal("ws-1", "T1")?.pid)
        assertNotEquals("distinct terminals must be distinct sessions", t0.pid, t1.pid)
    }

    @Test
    fun distinctTerminalIdsGetIndependentProotTmpScopes() {
        openTerminal("ws-1", DEFAULT_TERMINAL_ID)
        openTerminal("ws-1", "T1")

        val scopes = harness.createCalls.map { it.env["PROOT_TMP_DIR"] }.distinct()
        assertEquals("each terminal must own its own PROOT_TMP_DIR scope", 2, scopes.size)
        assertTrue(
            "T0 scope must be keyed by (workspaceId, terminalId)",
            scopes.all { it?.endsWith("ws-1/T0") == true || it?.endsWith("ws-1/T1") == true }
        )
    }

    @Test
    fun closeTerminalTearsDownOnlyItsOwnSession() {
        openTerminal("ws-1", DEFAULT_TERMINAL_ID)
        val t1 = openTerminal("ws-1", "T1")

        runBlocking { harness.runtime.closeTerminal("ws-1", DEFAULT_TERMINAL_ID) }

        assertFalse("closed terminal must be gone", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertNull("closed terminal must not resolve", harness.runtime.getTerminal("ws-1", DEFAULT_TERMINAL_ID))
        assertTrue("sibling terminal must survive a terminal-scoped close", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(t1.pid, harness.runtime.getTerminal("ws-1", "T1")?.pid)
    }

    @Test
    fun closeTerminalIsIdempotent() {
        openDefaultTerminal()
        runBlocking { harness.runtime.closeTerminal("ws-1", DEFAULT_TERMINAL_ID) }
        // Closing an already-closed terminal must not throw.
        runBlocking { harness.runtime.closeTerminal("ws-1", DEFAULT_TERMINAL_ID) }
        assertFalse(harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun closeWorkspaceSessionsTearsDownAllTerminalsOfThatWorkspaceOnly() {
        openTerminal("ws-1", DEFAULT_TERMINAL_ID)
        openTerminal("ws-1", "T1")
        val other = openDefaultTerminal("ws-2")

        runBlocking { harness.runtime.closeWorkspaceSessions("ws-1") }

        assertFalse("T0 of ws-1 must be torn down", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertFalse("T1 of ws-1 must be torn down", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertNull(harness.runtime.getTerminal("ws-1", DEFAULT_TERMINAL_ID))
        assertNull(harness.runtime.getTerminal("ws-1", "T1"))
        assertTrue(
            "an unrelated workspace's terminal must survive",
            harness.runtime.isTerminalAlive("ws-2", DEFAULT_TERMINAL_ID)
        )
        assertEquals(other.pid, harness.runtime.getTerminal("ws-2", DEFAULT_TERMINAL_ID)?.pid)
    }

    @Test
    fun closeWorkspaceSessionsIsIdempotentAndSafeWhenEmpty() {
        runBlocking { harness.runtime.closeWorkspaceSessions("never-opened") }
        assertFalse(harness.runtime.isTerminalAlive("never-opened", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun defaultTerminalIsConstantT0() {
        assertEquals("W1 default terminal id", "T0", DEFAULT_TERMINAL_ID)
    }
}
