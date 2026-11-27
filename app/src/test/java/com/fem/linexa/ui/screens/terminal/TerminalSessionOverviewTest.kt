package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.features.RecordingGuestFeatureHost
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.test.support.ControllableStubPtySession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * §1.98 — behavior contract for the terminal **session overview** (the
 * Termux / browser-style tab switcher that replaced the Commit-2 chip bar).
 *
 * The overview UI itself is Compose; what can be reliably verified on the JVM
 * is the *behavior the overview drives*, which lives entirely in
 * [TerminalsViewModel] over the real [WorkspaceTerminalManager] + [RuntimeBinder]
 * and a recording [ContainerRuntime]. These tests pin the §1.98 requirements:
 *
 *  1. the overview's data model (the `items` stream it renders) contains every
 *     open session, in order, and tracks removal;
 *  2. the trailing "new session" action allocates the NEXT id and preserves T0;
 *  3. switching T0 → T1 → T0 (what tapping a card does) never recreates a PTY
 *     and never closes the terminal left behind;
 *  4. a card's X calls only `closeTerminal` for that id — never the workspace;
 *  5. an Error terminal is represented in the model.
 *
 * There is no Compose UI test infrastructure in this project (no
 * `androidx.compose.ui.test` / Robolectric), so the pixel-level card layout is
 * verified on-device; the session/lifecycle semantics are verified here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionOverviewTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var fakeRuntime: RecordingContainerRuntime
    private lateinit var manager: WorkspaceTerminalManager
    private lateinit var binder: RuntimeBinder

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        fakeRuntime = RecordingContainerRuntime()
        manager = WorkspaceTerminalManager(fakeRuntime, RuntimeTrace())
        val fileManager = Mockito.mock(WorkspaceFileManager::class.java)
        Mockito.`when`(fileManager.getRootfsPath(anyString())).thenReturn(File("/tmp/rootfs"))
        binder = RuntimeBinder(
            sessionManager = Mockito.mock(SessionManager::class.java),
            containerRuntime = fakeRuntime,
            fileManager = fileManager,
            workspaceTerminalManager = manager,
            // §1.110: feature methods are delegated; unused by this test.
            guestFeatureHost = RecordingGuestFeatureHost(),
            events = MutableSharedFlow(),
            healthRegistry = WorkspaceHealthRegistry()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun overviewModelContainsAllActiveSessionsInOrder() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")
        openDirect("ws-1", "T2")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        // Every open session is present, ordered T0 < T1 < T2 (§9.1 order).
        assertEquals(listOf("T0", "T1", "T2"), vm.items.value.map { it.terminalId })
        assertEquals(
            listOf(TerminalState.Running, TerminalState.Running, TerminalState.Running),
            vm.items.value.map { it.state }
        )
    }

    @Test
    fun newSessionAllocatesNextIdAndPreservesT0() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        assertEquals(1, fakeRuntime.openTerminalCalls)

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        // The trailing "+" card → createTerminal() allocates the next id.
        vm.createTerminal()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T1", vm.createdTerminal.value)
        assertEquals(2, fakeRuntime.openTerminalCalls)
        // T0's session is untouched by the new-terminal allocation.
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T0"))
    }

    @Test
    fun switchingTerminalsDoesNotRecreateOrCloseEitherSession() = runBlocking {
        // What tapping a card does: re-attach the SAME TerminalViewModel to a
        // different existing id. No openTerminal call, no close.
        val t0 = openDirect("ws-1", "T0")
        val t1 = openDirect("ws-1", "T1")
        val opensBefore = fakeRuntime.openTerminalCalls

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T0", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(t0, vm.ptySession.value)

        // Switch T0 → T1.
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(t1, vm.ptySession.value)

        // Switch T1 → T0.
        vm.attachBinder("ws-1", "T0", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(t0, vm.ptySession.value)

        // Switching created nothing new and closed nothing.
        assertEquals(opensBefore, fakeRuntime.openTerminalCalls)
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertSame(t1, manager.getPtySession("ws-1", "T1"))
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T0"))
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun closingSessionCardCallsOnlyCloseTerminalNotWorkspace() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0", "T1"), vm.items.value.map { it.terminalId })

        // The X on T1's card → closeTerminal("T1") only.
        vm.closeTerminal("T1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRuntime.closeTerminalCalls)
        assertEquals(0, fakeRuntime.closeWorkspaceCalls)
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })
        assertNull(manager.getPtySession("ws-1", "T1"))
        assertNotNull(manager.getPtySession("ws-1", "T0"))
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T0"))
    }

    @Test
    fun closingT0LeavesWorkspaceAndSiblingsAlive() = runBlocking {
        // P0 contract: closing even the anchor terminal T0 closes only T0.
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        vm.closeTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fakeRuntime.closeWorkspaceCalls)
        assertEquals(listOf("T1"), vm.items.value.map { it.terminalId })
        assertNull(manager.getPtySession("ws-1", "T0"))
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun closingTheLastSessionCardReplacesItInsteadOfEmptyingTheOverview() = runBlocking {
        // §1.106: the X on the ONLY card must not leave a Running workspace with
        // zero sessions. The runtime opens a replacement and the ViewModel
        // announces it so the terminal screen switches to it.
        openDirect("ws-1", "T0")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })

        vm.closeTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        // Exactly one session again — never zero, never two.
        assertEquals(listOf("T1"), vm.items.value.map { it.terminalId })
        assertEquals("T1", vm.createdTerminal.value)
        assertEquals(0, fakeRuntime.closeWorkspaceCalls)
        assertTrue(fakeRuntime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun removingSessionUpdatesOverviewModel() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")
        openDirect("ws-1", "T2")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0", "T1", "T2"), vm.items.value.map { it.terminalId })

        vm.closeTerminal("T1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("T0", "T2"), vm.items.value.map { it.terminalId })
    }

    @Test
    fun errorTerminalIsRepresentedInOverviewModel() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        // T0 dies unexpectedly → the overview model carries its Error state.
        t0.completeExit(1)
        awaitManagerState("ws-1", "T0") { it is TerminalState.Error }
        dispatcher.scheduler.advanceUntilIdle()

        val t0Item = vm.items.value.first { it.terminalId == "T0" }
        assertTrue("expected Error, got ${t0Item.state}", t0Item.state is TerminalState.Error)
        // The sibling is unaffected.
        assertEquals(TerminalState.Running, vm.items.value.first { it.terminalId == "T1" }.state)
    }

    private fun openDirect(workspaceId: String, terminalId: String): ControllableStubPtySession = runBlocking {
        manager.openTerminal(workspaceId, "/tmp/rootfs", terminalId, asRoot = false) as ControllableStubPtySession
    }

    private suspend fun awaitManagerState(
        workspaceId: String,
        terminalId: String,
        predicate: (TerminalState?) -> Boolean
    ) {
        withTimeout(2_000) {
            manager.terminalStates.first { states -> predicate(states[workspaceId]?.get(terminalId)) }
        }
    }

    /**
     * A [ContainerRuntime] that records open/close calls and hands out
     * controllable stub ptys — no real proot involved. Tracks closeTerminal vs
     * closeWorkspaceSessions separately so a test can prove a card's X never
     * escalates to a workspace teardown.
     */
    private class RecordingContainerRuntime : ContainerRuntime {
        var openTerminalCalls = 0
        var closeTerminalCalls = 0
        var closeWorkspaceCalls = 0
        private val sessions = mutableMapOf<Pair<String, String>, PtySession>()

        override suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        asRoot: Boolean
    ): PtySession {
            openTerminalCalls++
            val pty = ControllableStubPtySession(
                id = "pty-$openTerminalCalls",
                workspaceId = workspaceId,
                pid = 1000 + openTerminalCalls
            )
            sessions[workspaceId to terminalId] = pty
            return pty
        }

        override fun getTerminal(workspaceId: String, terminalId: String): PtySession? =
            sessions[workspaceId to terminalId]

        override fun isTerminalAlive(workspaceId: String, terminalId: String): Boolean =
            sessions.containsKey(workspaceId to terminalId)

        override suspend fun closeTerminal(workspaceId: String, terminalId: String) {
            closeTerminalCalls++
            sessions.remove(workspaceId to terminalId)
        }

        override suspend fun closeWorkspaceSessions(workspaceId: String) {
            closeWorkspaceCalls++
            sessions.keys.removeAll { it.first == workspaceId }
        }

        override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult =
            CommandResult(exitCode = 0, output = "")

        override suspend fun execWithInput(
            workspaceId: String,
            rootfsPath: String,
            command: String,
            stdin: String
        ): CommandResult = exec(workspaceId, rootfsPath, command)

        override suspend fun execStreaming(
            workspaceId: String,
            rootfsPath: String,
            command: String,
            timeoutSeconds: Long,
            onLine: (String) -> Unit
        ): CommandResult = CommandResult(exitCode = 0, output = "")

        override suspend fun startDaemon(
            workspaceId: String,
            rootfsPath: String,
            name: String,
            command: String
        ): Boolean = false

        override suspend fun stopDaemon(workspaceId: String, name: String) = Unit

        override fun isDaemonAlive(workspaceId: String, name: String): Boolean = false

        override suspend fun stopAllDaemons(workspaceId: String) = Unit

        override suspend fun startupRecoverySweep(): RecoverySweepReport = RecoverySweepReport(0, 0, 0)
    }
}
