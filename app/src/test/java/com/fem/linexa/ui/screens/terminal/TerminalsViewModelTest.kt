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
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotEquals
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
        // The window list is synced by a separate Main-dispatcher collector.
        // Under a loaded full-suite run its resumption can land after the
        // manager's state has already changed, so one advanceUntilIdle() is
        // not a reliable barrier; advance until the ViewModel has observed it.
        withTimeout(2_000) {
            while (vm.desktop.value.window("T0")?.hasLiveProcess == true) {
                dispatcher.scheduler.advanceUntilIdle()
                delay(5)
            }
        }
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

    @Test
    fun restartingADeadTerminalKeepsTheSameWindowAndOpensOneFreshPty() = runBlocking {
        val dead = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        val windowBefore = vm.desktop.value.window("T0")!!
        val indexBefore = vm.desktop.value.windows.indexOfFirst { it.terminalId == "T0" }

        dead.completeExit(127)
        awaitManagerState("ws-1", "T0") { it is TerminalState.Error }
        dispatcher.scheduler.advanceUntilIdle()

        vm.restartTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        val live = manager.getPtySession("ws-1", "T0")
        assertNotNull("restart must produce a live pty", live)
        assertTrue("the replacement must be a fresh pty", live !== dead)
        assertEquals("restart must not allocate a different terminal id", listOf("T0"), vm.items.value.map { it.terminalId })
        assertEquals(TerminalState.Running, vm.items.value.single().state)

        val windowAfter = vm.desktop.value.window("T0")!!
        assertEquals("the tab position must be preserved", indexBefore, vm.desktop.value.windows.indexOfFirst { it.terminalId == "T0" })
        assertEquals("the title must be preserved", windowBefore.title, windowAfter.title)
        assertEquals("the terminal id must be preserved", windowBefore.terminalId, windowAfter.terminalId)
    }

    @Test
    fun aDoubleRestartTapOpensOnlyOneReplacement() = runBlocking {
        val dead = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        dead.completeExit(1)
        awaitManagerState("ws-1", "T0") { it is TerminalState.Error }
        dispatcher.scheduler.advanceUntilIdle()
        val opensBefore = fakeRuntime.openTerminalCalls

        vm.restartTerminal("T0")
        vm.restartTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("one logical restart → one pty", opensBefore + 1, fakeRuntime.openTerminalCalls)
        assertNotNull(manager.getPtySession("ws-1", "T0"))
    }

    @Test
    fun restartingALiveTerminalIsRefusedAndLeavesItRunning() = runBlocking {
        val live = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        val opensBefore = fakeRuntime.openTerminalCalls

        vm.restartTerminal("T0")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("a live shell must not be restarted", opensBefore, fakeRuntime.openTerminalCalls)
        assertSame("the live pty must be untouched", live, manager.getPtySession("ws-1", "T0"))
        assertTrue(live.isAlive)
        assertNotNull(
            "UX-002: a refused restart must surface its reason instead of only logging it",
            vm.restartFailure.value
        )
        vm.clearRestartFailure()
        assertNull("dismissing the failure clears the state", vm.restartFailure.value)
    }

    /**
     * Finding 019: the no-output banner's Restart is an explicit confirmation,
     * so the ViewModel forwards the opt-in and replaces the silent live pty.
     */
    @Test
    fun restartingALiveTerminalWithTheBannerOptInOpensOneReplacement() = runBlocking {
        val live = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        val opensBefore = fakeRuntime.openTerminalCalls

        vm.restartTerminal("T0", allowRunning = true)
        dispatcher.scheduler.advanceUntilIdle()

        val replacement = manager.getPtySession("ws-1", "T0")
        assertNotNull("the banner restart must produce a live pty", replacement)
        assertNotEquals("the silent pty must be replaced", live.id, replacement!!.id)
        // The old pty's death is a container-runtime fact pinned by
        // WorkspaceTerminalManagerTest against the real runtime; this suite's
        // recording fake overwrites its map without destroying the old stub.
        assertEquals("one explicit restart → one pty", opensBefore + 1, fakeRuntime.openTerminalCalls)
        assertEquals(TerminalState.Running, vm.items.value.single().state)
    }

    /**
     * P0 regression (first Root Shell launch): the runtime allocates T1 and
     * records its identity before the selector's own state emission is
     * processed. The focus request must stay pending across that gap, survive a
     * stale process-death restore of T0, and then land exactly once.
     */
    @Test
    fun aRootSelectionRequestedBeforeTheSelectorSyncsIsProtectedAndThenApplied() = runBlocking {
        val t0 = openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)

        // Runtime opened T1 (its state is already the authoritative source),
        // but the selector coroutine has not processed that emission yet.
        openDirect("ws-1", "T1")
        assertTrue(vm.requestActiveTerminal("T1", TerminalSelectionSource.RootShellLaunch))
        assertEquals("T1", vm.pendingActiveTerminal.value)
        // The desktop may still show the last consistent window for this frame.
        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)

        // A stale restore of the old hoisted id must not erase the pending root.
        assertFalse(
            vm.requestActiveTerminal(
                "T0",
                TerminalSelectionSource.WindowRestore,
                canReplacePending = false
            )
        )
        assertEquals("T1", vm.pendingActiveTerminal.value)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)
        assertNull(vm.pendingActiveTerminal.value)
        // Normal terminal isolation: focusing T1 never touched T0's pty.
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertTrue(t0.isAlive)
    }

    /**
     * The same pending rule when the request arrives before the runtime's very
     * first terminal emission (for example the selector attaches during Root
     * Shell startup and the StateFlow has not produced its first value).
     */
    @Test
    fun aSelectionMadeBeforeAnyTerminalListExistsIsAppliedWhenTheFirstWindowAppears() = runBlocking {
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.desktop.value.windows.isEmpty())

        assertTrue(vm.requestActiveTerminal("T1", TerminalSelectionSource.RootShellLaunch))
        assertEquals("T1", vm.pendingActiveTerminal.value)

        openDirect("ws-1", "T1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)
        assertNull(vm.pendingActiveTerminal.value)
    }

    /**
     * The old defect was a two-effect feedback loop: every sync mirrored one
     * id and restored the other. Once the Root window is selected, later
     * emissions and new siblings must never steal focus back.
     */
    @Test
    fun laterEmissionsAndNewSiblingsDoNotStealFocusFromTheSelectedWindow() = runBlocking {
        openDirect("ws-1", "T0")
        openDirect("ws-1", "T1")
        val identities = TerminalWindowIdentityRegistry()
        val vm = TerminalsViewModel(identities)
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.requestActiveTerminal("T1", TerminalSelectionSource.RootShellLaunch))
        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)

        // Out-of-order-looking updates: identities and later siblings arrive
        // while T1 stays the selected, rendered window.
        identities.record("ws-1", "T1", TerminalWindowIdentity(title = "Root shell"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)

        openDirect("ws-1", "T2")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)

        openDirect("ws-1", "T3")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("T1", vm.desktop.value.activeWindow?.terminalId)
        assertEquals(listOf("T0", "T1", "T2", "T3"), vm.desktop.value.windows.map { it.terminalId })
    }

    /** A request the runtime already disproved must not leave the desktop stuck. */
    @Test
    fun aStaleSelectionFallsBackToTheLiveWindowImmediately() = runBlocking {
        openDirect("ws-1", "T0")
        val vm = TerminalsViewModel(TerminalWindowIdentityRegistry())
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.requestActiveTerminal("T9", TerminalSelectionSource.RootShellLaunch))
        assertNull(vm.pendingActiveTerminal.value)
        assertEquals("T0", vm.desktop.value.activeWindow?.terminalId)
    }

    /**
     * Identity-before-window ordering: a launcher records "Root shell" right
     * after allocation, while the selector's list may still be one emission
     * behind. That identity must survive until the window syncs, or the root
     * window loses its title.
     */
    @Test
    fun anIdentityRecordedBeforeItsWindowSyncsSurvivesAndTitlesTheLateWindow() = runBlocking {
        openDirect("ws-1", "T0")
        val identities = TerminalWindowIdentityRegistry()
        val vm = TerminalsViewModel(identities)
        vm.attachBinder("ws-1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        identities.record(
            "ws-1",
            "T1",
            TerminalWindowIdentity(
                title = "Root shell",
                appId = "linexa:root-shell",
                iconKey = "terminal"
            )
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "the pre-sync identity must not be pruned",
            "Root shell",
            identities.get("ws-1", "T1")?.title
        )

        openDirect("ws-1", "T1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Root shell", vm.desktop.value.window("T1")?.title)

        vm.closeTerminal("T1")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(
            "a terminal that really disappears must still drop its identity",
            identities.get("ws-1", "T1")
        )
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
