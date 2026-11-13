package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.service.runtime.features.RecordingGuestFeatureHost
import com.fem.linexa.test.support.ControllableStubPtySession
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.97/W3 — integration tests for the terminal-screen lifecycle invariant:
 * **a terminal SCREEN does not own the terminal PROCESS.** Runs the REAL
 * [WorkspaceTerminalManager] over the fake-PRoot harness (controllable stub
 * ptys) and a real [RuntimeBinder], and drives [TerminalViewModel] against
 * it on a shared test dispatcher.
 *
 * Covers (§11 matrix + audit §18 W3 tests):
 *  - detach clears only the UI reference; the pty/session stays alive;
 *  - re-attach resolves the same live session (Terminal → Desktop → Terminal);
 *  - T0 and T1 never share a pty;
 *  - terminal exit while attached transitions the UI state deterministically;
 *  - terminal exit while detached is observed on a later re-attach;
 *  - an unknown terminal id is a deterministic unavailable state (no crash,
 *    no fallback to T0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelW3Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dispatcher: TestDispatcher
    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var manager: WorkspaceTerminalManager
    private lateinit var binder: RuntimeBinder

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        val prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.ptyFactory.controllable = true
        manager = WorkspaceTerminalManager(harness.runtime, RuntimeTrace())
        binder = RuntimeBinder(
            sessionManager = Mockito.mock(SessionManager::class.java),
            containerRuntime = harness.runtime,
            fileManager = Mockito.mock(WorkspaceFileManager::class.java),
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
    fun detachDoesNotDestroyPty() = runBlocking {
        val pty = open("ws-1", "T1")
        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(pty, vm.ptySession.value)

        vm.detachBinder()
        assertNull(vm.ptySession.value)

        // The session + pty must still be alive in the manager (§11 matrix:
        // screen pop = detach UI only, never destroy).
        assertEquals(TerminalState.Running, manager.terminalStates.value["ws-1"]?.get("T1"))
        assertSame(pty, manager.getPtySession("ws-1", "T1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun reattachResolvesSameLiveSession() = runBlocking {
        val pty = open("ws-1", "T1")
        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(pty, vm.ptySession.value)

        // Terminal → Desktop (detach), then Terminal → re-attach.
        vm.detachBinder()
        assertNull(vm.ptySession.value)

        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(pty, vm.ptySession.value)
    }

    @Test
    fun independentTerminalsNeverSharePty() = runBlocking {
        val t0 = open("ws-1", "T0")
        val t1 = open("ws-1", "T1")

        val vm0 = TerminalViewModel(RuntimeTrace())
        val vm1 = TerminalViewModel(RuntimeTrace())
        vm0.attachBinder("ws-1", "T0", binder)
        vm1.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertSame(t0, vm0.ptySession.value)
        assertSame(t1, vm1.ptySession.value)
        assertNotEquals(t0.id, t1.id)
    }

    @Test
    fun switchingViaReattachKeepsPreviousTerminalAlive() = runBlocking {
        // Bottom-bar switch T0 → T1 on the SAME screen/ViewModel: re-attach
        // only (new terminal id, same binder) — the previous pty is never
        // closed (§11 matrix), and nothing new is opened for the target id.
        val t0 = open("ws-1", "T0")
        val t1 = open("ws-1", "T1")
        val vm = TerminalViewModel(RuntimeTrace())

        vm.attachBinder("ws-1", "T0", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(t0, vm.ptySession.value)

        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(t1, vm.ptySession.value)

        // Switching must not close or replace the terminal we left.
        assertSame(t0, manager.getPtySession("ws-1", "T0"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T0"))
        assertSame(t1, manager.getPtySession("ws-1", "T1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun exitWhileAttachedTransitionsUiToError() = runBlocking {
        val pty = open("ws-1", "T1")
        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertSame(pty, vm.ptySession.value)

        pty.completeExit(1)
        awaitManagerState("ws-1", "T1") { it is TerminalState.Error }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("expected Error, got ${vm.terminalState.value}", vm.terminalState.value is TerminalState.Error)
        assertNull(vm.ptySession.value)
    }

    @Test
    fun exitWhileDetachedObservedOnReattach() = runBlocking {
        val pty = open("ws-1", "T1")
        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        vm.detachBinder()

        pty.completeExit(1)
        awaitManagerState("ws-1", "T1") { it is TerminalState.Error }

        // A later attachment must observe the terminal's final state.
        vm.attachBinder("ws-1", "T1", binder)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("expected Error, got ${vm.terminalState.value}", vm.terminalState.value is TerminalState.Error)
        assertNull(vm.ptySession.value)
    }

    @Test
    fun missingTerminalIsDeterministicUnavailable() = runBlocking {
        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder("ws-1", "T999", binder)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.terminalState.value)
        assertNull(vm.ptySession.value)
    }

    private fun open(workspaceId: String, terminalId: String): ControllableStubPtySession = runBlocking {
        manager.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, terminalId, asRoot = false) as ControllableStubPtySession
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
}
