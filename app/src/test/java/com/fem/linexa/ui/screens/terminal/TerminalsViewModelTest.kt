package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.terminal.TerminalWindowIdentity
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.domain.terminal.TerminalWindowUiState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * §1.97/W3 — [TerminalsViewModel] (the workspace terminal selector) tests,
 * against a real [RuntimeBinder] + [WorkspaceTerminalManager] over a
 * recording [ContainerRuntime] fake (controllable stub ptys). The terminal
 * selector's surface is the per-terminal state the manager already exposes,
 * so these tests focus on the ViewModel's own behavior:
 *  - one logical "New Terminal" action never opens more than one terminal,
 *  - the created terminal id is surfaced once and consumed,
 *  - items follow the manager's per-terminal states (ordering + Error),
 *  - closing one terminal leaves the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalsViewModelTest {

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
    fun duplicateCreateOpensOnlyOneTerminal() = runBlocking {
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)

        vm.createTerminal()
        vm.createTerminal()
        vm.createTerminal()
        dispatcher.scheduler.advanceUntilIdle()

        // One logical action → exactly one openTerminal call, one terminal.
        assertEquals(1, fakeRuntime.openTerminalCalls)
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })
        assertEquals(TerminalState.Running, vm.items.value.single().state)
        // The created id is surfaced once.
        assertEquals("T0", vm.createdTerminal.value)

        vm.consumeCreatedTerminal()
        assertNull(vm.createdTerminal.value)
    }

    @Test
    fun newTerminalAllocatesNextIdPreservingT0() = runBlocking {
        // Real flow: RuntimeService opens T0 first; a "New terminal" from the
        // bottom bar must allocate the next id (T1) and leave T0 untouched.
        val t0 = openDirect("ws-1", "T0")
        assertEquals(1, fakeRuntime.openTerminalCalls)

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })

        vm.createTerminal()
        dispatcher.scheduler.advanceUntilIdle()

        // The created id is the next allocator id, never a re-open of the
        // default T0 (§9.1 allocator — T0 is the live launch session).
        assertEquals("T1", vm.createdTerminal.value)
        assertEquals(listOf("T0", "T1"), vm.items.value.map { it.terminalId })
        assertEquals(2, fakeRuntime.openTerminalCalls)
        // T0's session is untouched by the new-terminal allocation.
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
    }

    @Test
    fun itemsFollowManagerPerTerminalStates() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val t1 = openDirect("ws-1", "T1")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("T0", "T1"), vm.items.value.map { it.terminalId })
        assertEquals(listOf(TerminalState.Running, TerminalState.Running), vm.items.value.map { it.state })

        // A terminal that exits unexpectedly shows its Error state in the list.
        t0.completeExit(1)
        awaitManagerState("ws-1", "T0") { it is TerminalState.Error }
        dispatcher.scheduler.advanceUntilIdle()

        val t0State = vm.items.value.first { it.terminalId == "T0" }.state
        assertTrue("expected Error, got $t0State", t0State is TerminalState.Error)
        assertEquals(listOf("T0", "T1"), vm.items.value.map { it.terminalId })
    }

    @Test
    fun closeTerminalRemovesOnlyThatTerminal() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0", "T1"), vm.items.value.map { it.terminalId })

        vm.closeTerminal("T1")
        dispatcher.scheduler.advanceUntilIdle()

        // T1 is gone from the list; T0 (the sibling) stays.
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })
        assertNull(manager.getPtySession("ws-1", "T1"))
        assertNotNull(manager.getPtySession("ws-1", "T0"))
        // §1.106: a sibling remained, so no replacement is announced.
        assertNull(vm.createdTerminal.value)
    }

    @Test
    fun closingLastTerminalAnnouncesTheReplacementToSwitchTo() = runBlocking {
        // §1.106: a Running workspace never has zero terminal sessions. Closing
        // the last one opens a replacement, and the ViewModel publishes its id
        // through the same one-shot channel a user-created terminal uses so the
        // screen switches to a live session instead of the closed id.
        openDirect("ws-1", "T0")

        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })

        vm.closeTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T1", vm.createdTerminal.value)
        assertEquals(listOf("T1"), vm.items.value.map { it.terminalId })
        assertEquals(TerminalState.Running, vm.items.value.single().state)
        assertNotNull("the replacement must be a live session", manager.getPtySession("ws-1", "T1"))
        assertNull("the closed terminal must stay closed", manager.getPtySession("ws-1", "T0"))

        vm.consumeCreatedTerminal()
        assertNull(vm.createdTerminal.value)
    }

    @Test
    fun minimizingAWindowKeepsItsPtyAliveAndLeavesTheDesktopIdle() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)
        assertEquals(TerminalWindowUiState.ACTIVE, vm.desktop.value.window("T0")?.uiState)

        vm.minimizeTerminal("T0")

        // Presentation hid the window; nothing about the session changed.
        assertNull("a minimized desktop has no focused window", vm.desktop.value.activeWindow)
        assertEquals(TerminalWindowUiState.MINIMIZED, vm.desktop.value.window("T0")?.uiState)
        assertEquals(listOf("T0"), vm.desktop.value.minimizedWindows.map { it.terminalId })
        assertSame("the same pty must still be the session", t0, manager.getPtySession("ws-1", "T0"))
        assertTrue("the process must still be alive", t0.isAlive)
        assertEquals(listOf("T0"), vm.items.value.map { it.terminalId })
    }

    @Test
    fun restoringAWindowReusesTheSamePtyInsteadOfRecreatingIt() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val opensBefore = fakeRuntime.openTerminalCalls
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        vm.minimizeTerminal("T0")
        vm.restoreTerminal("T0")

        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)
        assertEquals(TerminalWindowUiState.RESTORED, vm.desktop.value.window("T0")?.uiState)
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertEquals("restoring must not allocate a new terminal", opensBefore, fakeRuntime.openTerminalCalls)
        assertEquals("T0", vm.items.value.single().terminalId)
    }

    @Test
    fun maximizeAndRestoreOnlyChangePresentation() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleMaximizeTerminal("T0")
        assertEquals(TerminalWindowUiState.MAXIMIZED, vm.desktop.value.window("T0")?.uiState)
        assertEquals("T0", vm.desktop.value.maximizedTerminalId)
        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)

        vm.toggleMaximizeTerminal("T0")
        assertEquals(TerminalWindowUiState.ACTIVE, vm.desktop.value.window("T0")?.uiState)
        assertNull(vm.desktop.value.maximizedTerminalId)

        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertTrue(t0.isAlive)
    }

    @Test
    fun closingAWindowNeedsConfirmationOnlyWhileTheProcessIsLive() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.requiresCloseConfirmation("T0"))

        t0.completeExit(0)
        awaitManagerState("ws-1", "T0") { it == null || it is TerminalState.Stopped }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "a session that already exited is not a running process",
            vm.requiresCloseConfirmation("T0")
        )
    }

    @Test
    fun appIdentityTitlesAWindowAndIsPrunedWithTheSession() = runBlocking {
        val identities = TerminalWindowIdentityRegistry()
        identities.record(
            "ws-1",
            "T0",
            TerminalWindowIdentity(
                title = "Python 3 + venv",
                appId = "builtin:python",
                iconKey = "terminal",
                iconRes = 123,
                command = "python3"
            )
        )
        openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(identities)
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        val window = vm.desktop.value.window("T0")!!
        assertEquals("Python 3 + venv", window.title)
        assertEquals("builtin:python", window.appId)
        assertEquals("python3", window.launchCommand)
        assertEquals(123, window.iconRes)

        // A runtime OSC title never overrides the app identity.
        vm.restoreTerminal("T0")
        vm.closeTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull("a closed id must not keep app metadata", identities.get("ws-1", "T0"))
    }

    @Test
    fun anIdentityRecordedAfterTheWindowAppearsUpdatesItsTitleImmediately() = runBlocking {
        val identities = TerminalWindowIdentityRegistry()
        openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(identities)
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Terminal T1", vm.desktop.value.window("T0")?.title)

        // This is the real launch ordering: the runtime allocates/reports the
        // terminal first, then the launcher records the app identity.
        identities.record(
            "ws-1",
            "T0",
            TerminalWindowIdentity(title = "Node.js", appId = "builtin:node", command = "node")
        )
        dispatcher.scheduler.advanceUntilIdle()

        val window = vm.desktop.value.window("T0")!!
        assertEquals("Node.js", window.title)
        assertEquals("builtin:node", window.appId)
        assertEquals("node", window.launchCommand)
    }

    @Test
    fun closingTheFocusedWindowFocusesALiveSibling() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.restoreTerminal("T1")
        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)

        vm.closeTerminal("T1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)
        assertEquals(listOf("T0"), vm.desktop.value.windows.map { it.terminalId })
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
     * A [ContainerRuntime] that records every openTerminal call and hands out
     * controllable stub ptys — no real proot involved.
     */
    private class RecordingContainerRuntime : ContainerRuntime {
        var openTerminalCalls = 0
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
            sessions.remove(workspaceId to terminalId)
        }

        override suspend fun closeWorkspaceSessions(workspaceId: String) {
            sessions.keys.removeAll { it.first == workspaceId }
        }

        override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult =
            CommandResult(exitCode = 0, output = "")

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
