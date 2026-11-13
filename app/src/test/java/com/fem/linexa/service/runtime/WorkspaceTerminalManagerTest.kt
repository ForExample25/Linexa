package com.fem.linexa.service.runtime

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.test.support.ControllableStubPtySession
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.97/W2 — [WorkspaceTerminalManager] unit tests, exercised against the REAL
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] through the
 * fake-PRoot harness. The harness factory is flipped to controllable stubs so
 * each test drives a terminal's exit explicitly (unexpected death → Error,
 * clean exit → Stopped, intentional close → gone).
 *
 * Covered:
 *  - create (Starting → Running) + state flow observability;
 *  - multiple terminals coexist per workspace;
 *  - terminal Error is isolated (siblings + workspace unaffected);
 *  - explicit close tears down exactly one terminal;
 *  - workspace close cascades to all terminals of that workspace only;
 *  - reopening an id replaces the previous session (no stale observer effect);
 *  - default terminal stays "T0" with a workspace-scoped PROOT_TMP_DIR;
 *  - close-race hygiene (close during start never leaks a terminal);
 *  - id allocator: monotonic, per-workspace, never reused after close;
 *  - invalid ids rejected; PtySessionSource seam (getPtySession);
 *  - §1.97/PT0 workspace-end signal: closing T0 does NOT end the workspace
 *    session (the P0 SIGKILL "-9" regression), a natural T0 exit does NOT end
 *    it either (terminal lifecycle ≠ workspace lifecycle — a terminal exits,
 *    the workspace and its siblings stay alive), closeWorkspace ends it with
 *    null, per-session fresh signal, per-workspace isolation, safe to await
 *    before anything opens.
 */
class WorkspaceTerminalManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var manager: WorkspaceTerminalManager

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
        // W2: manager tests drive pty exits deterministically.
        harness.ptyFactory.controllable = true
        manager = WorkspaceTerminalManager(harness.runtime, RuntimeTrace())
    }

    private fun open(
        workspaceId: String = "ws-1",
        terminalId: String = "T0"
    ): ControllableStubPtySession = runBlocking {
        manager.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, terminalId, asRoot = false) as ControllableStubPtySession
    }

    private suspend fun awaitTerminalState(
        workspaceId: String,
        terminalId: String,
        predicate: (TerminalState?) -> Boolean
    ) {
        withTimeout(2_000) {
            manager.terminalStates.first { states -> predicate(states[workspaceId]?.get(terminalId)) }
        }
    }

    @Test
    fun openTerminalRegistersRunningSession() = runBlocking {
        val pty = open("ws-1", "T1")

        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertEquals(pty.pid, harness.runtime.getTerminal("ws-1", "T1")?.pid)
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals("manager must hand back the same pty it registered", pty, manager.getPtySession("ws-1", "T1"))
    }

    @Test
    fun openTerminalEmitsStartingThenRunning() = runBlocking {
        // Collect before opening so the transient Starting emission is observed
        // while the container launch is in flight (the open suspends on IO).
        // Inlined (not the open() helper) so the collector shares this event
        // loop; yield() lets it subscribe before the open emits Starting.
        val seenStarting = async {
            manager.terminalStates.first { states -> states["ws-1"]?.get("T1") == TerminalState.Starting }
        }
        yield()
        manager.openTerminal("ws-1", harness.fakeRootfs().absolutePath, "T1", asRoot = false)
        seenStarting.await()
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
    }

    @Test
    fun distinctTerminalsCoexistInSameWorkspace() = runBlocking {
        val t0 = open("ws-1", "T0")
        val t1 = open("ws-1", "T1")

        assertNotEquals("distinct terminals must be distinct sessions", t0.pid, t1.pid)
        assertEquals(2, manager.terminalStates.value["ws-1"]?.size)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T0"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T0"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun terminalErrorIsIsolatedFromSiblingsAndWorkspace() = runBlocking {
        val t0 = open("ws-1", "T0")
        val t1 = open("ws-1", "T1")

        t0.completeExit(1)
        awaitTerminalState("ws-1", "T0") { it is TerminalState.Error }

        // The crashed terminal surfaces Error; the sibling is untouched.
        assertTrue(manager.terminalStates.value["ws-1"]?.get("T0") is TerminalState.Error)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertTrue("sibling must stay alive after another terminal crashed", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertFalse("crashed terminal's pty must read dead", harness.runtime.isTerminalAlive("ws-1", "T0"))
        // The workspace was NOT torn down by a terminal Error.
        assertEquals(2, manager.terminalStates.value["ws-1"]?.size)
    }

    @Test
    fun closeTerminalTearsDownOnlyThatTerminal() = runBlocking {
        val t0 = open("ws-1", "T0")
        val t1 = open("ws-1", "T1")

        assertNull("a close with a sibling left needs no replacement", manager.closeTerminal("ws-1", "T1"))

        assertFalse(harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertNull(harness.runtime.getTerminal("ws-1", "T1"))
        assertNull(manager.getPtySession("ws-1", "T1"))
        assertNull("closed terminal must vanish from the state flow", manager.terminalStates.value["ws-1"]?.get("T1"))
        // Sibling survives.
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T0"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T0"))
        assertEquals(t0.pid, manager.getPtySession("ws-1", "T0")?.pid)
        // Idempotent.
        assertNull(manager.closeTerminal("ws-1", "T1"))
    }

    @Test
    fun closeWorkspaceCascadesToAllTerminalsOfThatWorkspaceOnly() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")
        val other = open("ws-2", "T0")

        manager.closeWorkspace("ws-1")

        assertFalse(harness.runtime.isTerminalAlive("ws-1", "T0"))
        assertFalse(harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertNull(manager.getPtySession("ws-1", "T0"))
        assertNull("closed workspace must be forgotten entirely", manager.terminalStates.value["ws-1"])
        // Unrelated workspace untouched.
        assertTrue(harness.runtime.isTerminalAlive("ws-2", "T0"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-2"]?.get("T0"))
        assertEquals(other.pid, manager.getPtySession("ws-2", "T0")?.pid)
        // Idempotent + safe on never-opened workspaces.
        manager.closeWorkspace("ws-1")
        manager.closeWorkspace("never-opened")
    }

    @Test
    fun reopeningSameTerminalIdReplacesPreviousSession() = runBlocking {
        val first = open("ws-1", "T1")
        val second = open("ws-1", "T1")

        assertNotEquals("replacement must be a fresh pty", first.pid, second.pid)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertEquals(second.pid, manager.getPtySession("ws-1", "T1")?.pid)
        assertEquals(second.pid, harness.runtime.getTerminal("ws-1", "T1")?.pid)

        // The replaced session's exit must not disturb the replacement (its
        // observer was cancelled on reopen).
        first.completeExit(1)
        awaitTerminalState("ws-1", "T1") { it == TerminalState.Running }

        // The replacement's own exit does transition the terminal.
        second.completeExit(2)
        awaitTerminalState("ws-1", "T1") { it is TerminalState.Error }
    }

    @Test
    fun defaultTerminalIsT0WithWorkspaceScopedTmpDir() = runBlocking {
        val pty = open("ws-1", "T0")

        assertEquals("W1 default terminal id", "T0", DEFAULT_TERMINAL_ID)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID))
        assertEquals(pty.pid, manager.getPtySession("ws-1", DEFAULT_TERMINAL_ID)?.pid)
        val scope = harness.createCalls.last().env["PROOT_TMP_DIR"]
        assertTrue("launch scope must be keyed ws-1/T0, was $scope", scope?.endsWith("ws-1/T0") == true)
    }

    @Test
    fun closingWhileStartingNeverRegistersALeakedTerminal() = runBlocking {
        val rootfs = harness.fakeRootfs().absolutePath
        val openJob = launch {
            runCatching { manager.openTerminal("ws-1", rootfs, "T1", asRoot = false) }
        }
        // Run the open up to its first suspension (the container launch hops to
        // IO), then close while it is still Starting.
        yield()
        manager.closeTerminal("ws-1", "T1")
        openJob.join()

        assertNull("terminal closed during start must not be registered", manager.getPtySession("ws-1", "T1"))
        assertNull(manager.terminalStates.value["ws-1"]?.get("T1"))
        assertFalse("the late-arriving pty must have been destroyed", harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun nextTerminalIdSkipsLiveLaunchTerminalAndDoesNotReplaceIt() = runBlocking {
        // §W6 bug regression: a terminal opened WITHOUT the allocator (the
        // pre-fix launch path opened "T0" directly via openTerminal's default
        // id; explicit-id opens do the same) leaves the counter untouched
        // while "T0" is live. Allocating "T0" again used to make the next
        // openTerminal REPLACE the running launch terminal (destroy → SIGKILL
        // -9 → Error → whole workspace torn down). The allocator must skip
        // the in-use terminal and hand out the next free id instead.
        val launch = open("ws-1", DEFAULT_TERMINAL_ID)

        val next = manager.nextTerminalId("ws-1")
        assertEquals("first UI-created terminal after launch must be T1 (T0 is live)", "T1", next)
        assertEquals("T2", manager.nextTerminalId("ws-1"))

        // Opening the allocated id must NOT touch the live launch terminal.
        val second = manager.openTerminal("ws-1", harness.fakeRootfs().absolutePath, next, asRoot = false)
        assertNotEquals("new terminal must be a distinct session", launch.pid, second.pid)
        assertTrue("launch terminal T0 must still be alive", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertEquals("T0 must still resolve its original pty", launch.pid, manager.getPtySession("ws-1", DEFAULT_TERMINAL_ID)?.pid)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID))
        assertEquals(2, manager.terminalStates.value["ws-1"]?.size)
    }

    @Test
    fun nextTerminalIdIsMonotonicPerWorkspaceAndNeverReusedAfterClose() = runBlocking {
        assertEquals("T0", manager.nextTerminalId("ws-1"))
        assertEquals("T1", manager.nextTerminalId("ws-1"))
        // Closing a terminal does not free its id for reuse (§9.1).
        manager.closeTerminal("ws-1", "T1")
        assertEquals("T2", manager.nextTerminalId("ws-1"))
        // A different workspace has its own independent counter.
        assertEquals("T0", manager.nextTerminalId("ws-2"))
        // Workspace close resets the counter (fresh session starts at T0).
        manager.closeWorkspace("ws-1")
        assertEquals("T0", manager.nextTerminalId("ws-1"))
    }

    @Test
    fun openTerminalRejectsIdsOutsideAllowedCharset() {
        val error = runCatching {
            runBlocking { manager.openTerminal("ws-1", harness.fakeRootfs().absolutePath, "bad/../id", asRoot = false) }
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException for an unsafe id, got $error", error is IllegalArgumentException)
    }

    @Test
    fun getPtySessionIsNullForClosedAndErroredTerminals() = runBlocking {
        val t0 = open("ws-1", "T0")

        assertEquals("running terminal resolves its pty", t0, manager.getPtySession("ws-1", "T0"))
        assertNull("unknown terminal must not resolve", manager.getPtySession("ws-1", "T9"))

        t0.completeExit(1)
        awaitTerminalState("ws-1", "T0") { it is TerminalState.Error }
        assertNull("errored terminal must not resolve a pty", manager.getPtySession("ws-1", "T0"))
    }

    // ── §1.103: last-active terminal (notification Open restore target) ──

    @Test
    fun openTerminalMarksNewTerminalLastActive() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")

        // A freshly opened terminal is the active one — the notification
        // should restore it.
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
    }

    @Test
    fun markTerminalActiveTracksTheMostRecentlyViewedTerminal() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")

        // User switches back to T0 → T0 becomes the last-active session.
        manager.markTerminalActive("ws-1", "T0")
        assertEquals("T0", manager.lastActiveTerminalId("ws-1"))

        manager.markTerminalActive("ws-1", "T1")
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
    }

    @Test
    fun markTerminalActiveIgnoresUnknownOrClosedTerminal() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")

        manager.markTerminalActive("ws-1", "T9")
        // The recorded last-active must not be poisoned by an id that isn't open.
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))

        manager.markTerminalActive("ws-1", "T0")
        manager.closeTerminal("ws-1", "T0")
        manager.markTerminalActive("ws-1", "T0")
        // Closed terminal can't be the restore target — the remaining sibling is.
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
    }

    @Test
    fun closingLastActiveFallsBackToMostRecentlyOpenedRemainingTerminal() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")
        manager.markTerminalActive("ws-1", "T0")

        manager.closeTerminal("ws-1", "T0")

        // T0 was last-active but closed → restore falls back to the remaining
        // open terminal (most recently opened = T1).
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun lastActiveFallsBackToDefaultWhenNothingIsOpen() = runBlocking {
        // No terminals open (e.g. workspace still Starting): T0 is the target.
        assertEquals(DEFAULT_TERMINAL_ID, manager.lastActiveTerminalId("ws-1"))

        open("ws-1", "T1")
        manager.closeWorkspace("ws-1")
        // Workspace close forgets the whole workspace → T0 fallback again.
        assertEquals(DEFAULT_TERMINAL_ID, manager.lastActiveTerminalId("ws-1"))
    }

    @Test
    fun lastActiveIsIsolatedPerWorkspace() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")
        open("ws-2", "T0")

        manager.markTerminalActive("ws-1", "T0")
        manager.markTerminalActive("ws-2", "T0")
        assertEquals("T0", manager.lastActiveTerminalId("ws-1"))
        assertEquals("T0", manager.lastActiveTerminalId("ws-2"))

        manager.closeTerminal("ws-1", "T0")
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
        assertEquals("ws-2's last-active must be unaffected", "T0", manager.lastActiveTerminalId("ws-2"))
    }

    // ── §1.97/PT0: workspace-end signal (P0 SIGKILL "-9" regression) ──

    @Test
    fun closingT0DoesNotEndWorkspaceSessionAndSiblingsSurvive() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")

        val ended = async { manager.awaitSessionEnd("ws-1") }
        manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID)
        yield()
        yield()

        // The P0 regression: closing the T0 tab destroyed the pty RuntimeService
        // awaited, ending the whole workspace in SIGKILL -9. The workspace-end
        // signal must NOT fire on a T0 close — the workspace stays Running.
        assertFalse("closing T0 must not end the workspace session", ended.isCompleted)
        assertTrue("sibling T1 must survive the T0 close", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertFalse("T0's pty is gone", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))

        // A genuine teardown still ends it — with null (intentional), never a crash.
        manager.closeWorkspace("ws-1")
        assertNull(withTimeout(2_000) { ended.await() })
    }

    @Test
    fun closingOnlyTerminalT0KeepsWorkspaceRunningUntilTeardown() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)

        val ended = async { manager.awaitSessionEnd("ws-1") }
        // §1.106: closing the ONLY terminal opens a replacement rather than
        // leaving the workspace with zero terminals.
        val replacement = manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID)
        yield()
        yield()
        assertFalse("closing the only terminal must not end the workspace", ended.isCompleted)
        assertEquals("T1", replacement)

        // The workspace session survives and the replacement is live.
        assertTrue("the replacement terminal must be alive", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertFalse("opening the replacement must not end the session either", ended.isCompleted)

        manager.closeWorkspace("ws-1")
        assertNull(withTimeout(2_000) { ended.await() })
    }

    // ── §1.106: never-zero-terminals (Termux-like semantics) ──

    @Test
    fun closingLastTerminalOpensReplacementAndReportsItsId() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)

        val replacement = manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID)

        // A Running workspace never has zero terminals: the close reports the
        // replacement so the UI can switch to a live session.
        assertEquals("T1", replacement)
        assertEquals(1, manager.terminalStates.value["ws-1"]?.size)
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertNotNull("the replacement must resolve a live pty", manager.getPtySession("ws-1", "T1"))
        assertNull("the closed terminal must stay gone", manager.getPtySession("ws-1", DEFAULT_TERMINAL_ID))
        // §9.1 still holds: the replacement is a NEW id, never the closed one.
        assertNull(manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID))
        // The replacement is the notification's restore target.
        assertEquals("T1", manager.lastActiveTerminalId("ws-1"))
    }

    @Test
    fun closingLastTerminalRepeatedlyKeepsExactlyOneTerminal() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)

        // Closing the sole terminal over and over must never reach zero and
        // never accumulate terminals — each close leaves exactly one.
        var current = DEFAULT_TERMINAL_ID
        repeat(3) {
            val next = manager.closeTerminal("ws-1", current)
            assertNotNull("every close of the last terminal must yield a replacement", next)
            current = next!!
            assertEquals(1, manager.terminalStates.value["ws-1"]?.size)
            assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get(current))
        }
        assertEquals("T3", current)
    }

    @Test
    fun closingTerminalWithSiblingsOpensNoReplacement() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")

        assertNull(manager.closeTerminal("ws-1", "T1"))
        assertNull("no replacement must be opened while a sibling remains", manager.closeTerminal("ws-1", "T1"))
        assertEquals(listOf(DEFAULT_TERMINAL_ID), manager.terminalStates.value["ws-1"]?.keys?.toList())
    }

    @Test
    fun closingTerminalOfNonLiveWorkspaceOpensNoReplacement() = runBlocking {
        // Never opened: a close must not conjure a terminal into existence.
        assertNull(manager.closeTerminal("never-opened", DEFAULT_TERMINAL_ID))
        assertNull(manager.terminalStates.value["never-opened"])

        // Already torn down: the workspace session is over, so the
        // never-zero-terminals rule must not resurrect a terminal in it.
        open("ws-1", DEFAULT_TERMINAL_ID)
        manager.closeWorkspace("ws-1")
        assertNull(manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID))
        assertNull(manager.terminalStates.value["ws-1"])
    }

    @Test
    fun replacementTerminalUsesTheWorkspaceLaunchRootfs() = runBlocking {
        val rootfs = harness.fakeRootfs().absolutePath
        manager.openTerminal("ws-1", rootfs, DEFAULT_TERMINAL_ID, asRoot = false)

        val replacement = manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID)

        assertEquals("T1", replacement)
        // The replacement went through the real container launch path with the
        // same rootfs and its own (workspace, terminal)-scoped scratch dir.
        val scope = harness.createCalls.last().env["PROOT_TMP_DIR"]
        assertTrue("replacement scope must be keyed ws-1/T1, was $scope", scope?.endsWith("ws-1/T1") == true)
    }

    @Test
    fun closingSiblingTerminalDoesNotEndWorkspaceSession() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")

        val ended = async { manager.awaitSessionEnd("ws-1") }
        manager.closeTerminal("ws-1", "T1")
        yield()
        yield()

        assertFalse("closing a sibling must not end the workspace", ended.isCompleted)
        assertTrue("T0 stays alive when only T1 closes", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID))

        // Tear down so the awaitSessionEnd waiter completes: runBlocking waits
        // for ALL its children before returning, so an uncompleted waiter would
        // hang the JVM test worker forever (a genuine workspace end must release
        // it — teardown with null, never a crash code).
        manager.closeWorkspace("ws-1")
        assertNull(withTimeout(2_000) { ended.await() })
    }

    @Test
    fun siblingNaturalExitKeepsWorkspaceAliveAndT0Survives() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        val t1 = open("ws-1", "T1")

        val ended = async { manager.awaitSessionEnd("ws-1") }
        t1.completeExit(3)
        awaitTerminalState("ws-1", "T1") { it is TerminalState.Error }

        // §P0: a sibling (T1) natural exit is a terminal event only.
        assertFalse("T1 natural exit must not end the workspace session", ended.isCompleted)
        assertTrue("T0 must survive T1's natural exit", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID))
        assertFalse("T1's pty is gone after its natural exit", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertTrue(
            "T1's failure must remain observable as TerminalState.Error",
            manager.terminalStates.value["ws-1"]?.get("T1") is TerminalState.Error
        )

        manager.closeWorkspace("ws-1")
        assertNull("explicit close ends the session with null", withTimeout(2_000) { ended.await() })
    }

    @Test
    fun t0CleanNaturalExitKeepsWorkspaceAliveAndSiblingsSurvive() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")

        val ended = async { manager.awaitSessionEnd("ws-1") }
        t0.completeExit(0)
        // A clean exit surfaces as Stopped and is then PRUNED from the state map
        // (Phase 6b bookkeeping) — a conflated StateFlow may never show the
        // transient Stopped to a late subscriber, so synchronize on the durable
        // outcome instead: T0 is no longer Running (Stopped or already removed).
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it != TerminalState.Running }

        // §P0: a natural T0 exit is a TERMINAL event, not a workspace event.
        // The workspace session and its siblings must survive it.
        assertFalse("T0 clean exit must not end the workspace session", ended.isCompleted)
        assertTrue("sibling T1 must survive T0's natural exit", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertFalse("T0's pty is gone after its natural exit", harness.runtime.isTerminalAlive("ws-1", DEFAULT_TERMINAL_ID))

        // Only an explicit workspace close ends the session — with null.
        manager.closeWorkspace("ws-1")
        assertNull("explicit close ends the session with null", withTimeout(2_000) { ended.await() })
    }

    @Test
    fun t0NonZeroNaturalExitKeepsWorkspaceAliveButSurfacesTerminalError() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")

        val ended = async { manager.awaitSessionEnd("ws-1") }
        (manager.getPtySession("ws-1", DEFAULT_TERMINAL_ID) as ControllableStubPtySession).completeExit(7)
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it is TerminalState.Error }

        // §P0: even a non-zero T0 exit is a TERMINAL failure — it surfaces on
        // the terminal (observable), but must NOT end the workspace or touch
        // siblings. Genuine failures are visible, not destructive.
        assertFalse("T0 crash must not end the workspace session", ended.isCompleted)
        assertTrue("sibling T1 must survive T0's crash", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertTrue(
            "T0's failure must remain observable as TerminalState.Error",
            manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID) is TerminalState.Error
        )

        manager.closeWorkspace("ws-1")
        assertNull(withTimeout(2_000) { ended.await() })
    }

    @Test
    fun closeWorkspaceCompletesWorkspaceEndWithNull() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)

        val ended = async { manager.awaitSessionEnd("ws-1") }
        manager.closeWorkspace("ws-1")

        assertNull("intentional teardown must read as null (not a crash code)", withTimeout(2_000) { ended.await() })
    }

    @Test
    fun workspaceEndSignalIsFreshPerSession() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        manager.closeWorkspace("ws-1")

        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)
        val ended = async { manager.awaitSessionEnd("ws-1") }
        t0.completeExit(0)
        // Same pruning-conflation caveat as the T0-sibling test: wait for the
        // durable "no longer Running" outcome, not the transient Stopped value.
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it != TerminalState.Running }

        // A fresh session must NOT inherit session 1's completed signal: a
        // stale completed (null) signal from closeWorkspace would make this
        // already-completed. It must be uncompleted — and a T0 exit must not
        // complete it either (terminal lifecycle ≠ workspace lifecycle).
        assertFalse(
            "fresh session must get a fresh (uncompleted) end signal; a stale completed signal from session 1 would make this already-completed",
            ended.isCompleted
        )

        manager.closeWorkspace("ws-1")
        assertNull("the fresh session ends with null on explicit close", withTimeout(2_000) { ended.await() })
    }

    @Test
    fun workspaceSessionsAreIsolatedPerWorkspace() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")
        open("ws-2", DEFAULT_TERMINAL_ID)

        val ws1Ended = async { manager.awaitSessionEnd("ws-1") }
        val ws2Ended = async { manager.awaitSessionEnd("ws-2") }

        // A natural ws-1 T0 exit must not end EITHER session (terminal event).
        (manager.getPtySession("ws-1", DEFAULT_TERMINAL_ID) as ControllableStubPtySession).completeExit(1)
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it is TerminalState.Error }
        assertFalse("ws-1's session must survive its own T0 exit", ws1Ended.isCompleted)
        assertTrue("ws-1's sibling T1 survives the T0 exit", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertFalse("ws-2's session must be unaffected by ws-1's T0 exit", ws2Ended.isCompleted)
        assertTrue("ws-2's T0 stays alive", harness.runtime.isTerminalAlive("ws-2", DEFAULT_TERMINAL_ID))

        // Closing ws-1 completes ONLY ws-1's signal; ws-2 stays live.
        manager.closeWorkspace("ws-1")
        assertNull("ws-1 ends with null on explicit close", withTimeout(2_000) { ws1Ended.await() })
        assertFalse("closing ws-1 must not complete ws-2's signal", ws2Ended.isCompleted)
        assertTrue("ws-2 still alive after ws-1 closed", harness.runtime.isTerminalAlive("ws-2", DEFAULT_TERMINAL_ID))

        // Tear ws-2 down so the runBlocking scope's ws2Ended waiter completes
        // (runBlocking waits for ALL children; an uncompleted waiter would hang
        // the JVM test worker forever).
        manager.closeWorkspace("ws-2")
        assertNull(withTimeout(2_000) { ws2Ended.await() })
    }

    @Test
    fun awaitSessionEndBeforeAnythingOpenCompletesOnTeardown() = runBlocking {
        val ended = async { manager.awaitSessionEnd("never-opened") }
        manager.closeWorkspace("never-opened")

        assertNull("awaiting before anything opens must still complete on teardown", withTimeout(2_000) { ended.await() })
    }

    // ── §1.191: SIGKILL diagnosis carried on the Error state ────────────────

    /**
     * A -9 exit must be *identifiable* as a SIGKILL, so the UI can offer the
     * OOM/background-restriction guidance instead of a bare "exited
     * unexpectedly". Pins the exit status onto the state, not just into the log.
     */
    @Test
    fun sigkillExitIsIdentifiableOnTheTerminalErrorState() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)

        t0.completeExit(TerminalState.SIGKILL_EXIT_STATUS)
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it is TerminalState.Error }

        val error = manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID) as TerminalState.Error
        assertEquals("the raw exit status must reach the UI", -9, error.exitCode)
        assertTrue("a -9 exit must be identifiable as a SIGKILL", error.killedBySigkill)
    }

    /**
     * The distinction the feature rests on: an ordinary non-zero crash must NOT
     * claim to be an OS kill. Without this, the guidance would be shown for
     * every terminal failure and stop meaning anything.
     */
    @Test
    fun ordinaryNonZeroExitIsNotReportedAsSigkill() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)

        t0.completeExit(1)
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it is TerminalState.Error }

        val error = manager.terminalStates.value["ws-1"]?.get(DEFAULT_TERMINAL_ID) as TerminalState.Error
        assertEquals(1, error.exitCode)
        assertFalse("an ordinary crash must not be attributed to Android's memory manager", error.killedBySigkill)
    }

    /**
     * §P0 guard, restated for §1.191: adding SIGKILL *diagnosis* must not turn a
     * killed terminal into a workspace-level event. The workspace session, its
     * sibling terminals, and the sibling's own state all stay untouched — this
     * is why the notice can be inline per-terminal rather than a takeover.
     */
    @Test
    fun sigkillStaysAPerTerminalEventAndNeverEndsTheWorkspace() = runBlocking {
        val t0 = open("ws-1", DEFAULT_TERMINAL_ID)
        open("ws-1", "T1")
        val ended = async { manager.awaitSessionEnd("ws-1") }

        t0.completeExit(TerminalState.SIGKILL_EXIT_STATUS)
        awaitTerminalState("ws-1", DEFAULT_TERMINAL_ID) { it is TerminalState.Error }

        assertFalse("a SIGKILLed terminal must not end the workspace session", ended.isCompleted)
        assertTrue("the sibling terminal must stay alive", harness.runtime.isTerminalAlive("ws-1", "T1"))
        assertEquals(
            "the sibling's state must be untouched by its neighbour's death",
            TerminalState.Running,
            manager.terminalStates.value["ws-1"]?.get("T1")
        )

        manager.closeWorkspace("ws-1")
        assertNull(withTimeout(2_000) { ended.await() })
    }

    /**
     * An intentional close SIGKILLs the child too (terminal-lib's
     * `finishIfRunning` sends SIGKILL), so the observer-cancel-before-destroy
     * ordering in `closeTerminal` is what keeps a user close from being
     * misreported as an OS kill. §1.191 depends on that ordering, so it is
     * pinned here: after closing the only terminal, §1.106 opens a replacement
     * and no Error state exists anywhere in the workspace.
     */
    @Test
    fun intentionalCloseIsNeverMisreportedAsAnOsKill() = runBlocking {
        open("ws-1", DEFAULT_TERMINAL_ID)

        val replacement = manager.closeTerminal("ws-1", DEFAULT_TERMINAL_ID)

        assertNotNull("§1.106: closing the last terminal opens a replacement", replacement)
        yield()
        val states = manager.terminalStates.value["ws-1"].orEmpty()
        assertTrue(
            "an intentional close must not surface as an Error (its observer is cancelled first), " +
                "otherwise every close would show the SIGKILL notice — states=$states",
            states.values.none { it is TerminalState.Error }
        )
    }
}
