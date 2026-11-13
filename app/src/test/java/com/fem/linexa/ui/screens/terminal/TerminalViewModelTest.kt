package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.pty.PtyEvent
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * §1.59/§1.97-W3: JVM tests for `TerminalViewModel`'s per-terminal
 * pty-resolution logic. The ViewModel operates on `(workspaceId, terminalId)`
 * and resolves exactly the requested terminal:
 *  - per-terminal resolution (T0 vs T1),
 *  - a missing terminal resolves nothing (no fallback to T0),
 *  - attach-before-running vs attach-after-running convergence,
 *  - bounded retry while the terminal is open but the pty lookup is null,
 *  - state-driven clearing (Stopped / Error),
 *  - a replaced binder (service reconnect) re-observing,
 *  - detach clears only the UI reference.
 *
 * Runs with a fake [PtySessionSource] + fake [PtySession] on a shared test
 * dispatcher (virtual time), no Android runtime required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun resolvesExactTerminalT0() = runTest(dispatcher) {
        val source = FakeSource()
        val pty = FakePtySession(WS)
        source.registerTerminal("T0", TerminalState.Running, pty)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T0", source)

        assertSame(pty, vm.ptySession.value)
        assertEquals(TerminalState.Running, vm.terminalState.value)
    }

    @Test
    fun resolvesExactTerminalT1() = runTest(dispatcher) {
        val source = FakeSource()
        val ptyT1 = FakePtySession(WS)
        source.registerTerminal("T0", TerminalState.Running, FakePtySession(WS))
        source.registerTerminal("T1", TerminalState.Running, ptyT1)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)

        assertSame(ptyT1, vm.ptySession.value)
    }

    @Test
    fun independentTerminalsResolveSeparatePtys() = runTest(dispatcher) {
        val source = FakeSource()
        val ptyT0 = FakePtySession(WS)
        val ptyT1 = FakePtySession(WS)
        source.registerTerminal("T0", TerminalState.Running, ptyT0)
        source.registerTerminal("T1", TerminalState.Running, ptyT1)

        val vm0 = TerminalViewModel(RuntimeTrace())
        val vm1 = TerminalViewModel(RuntimeTrace())
        vm0.attachBinder(WS, "T0", source)
        vm1.attachBinder(WS, "T1", source)

        assertSame(ptyT0, vm0.ptySession.value)
        assertSame(ptyT1, vm1.ptySession.value)
        assertTrue(vm0.ptySession.value!!.id != vm1.ptySession.value!!.id)
    }

    @Test
    fun switchingToAStartingTerminalDoesNotRenderThePreviousSessionsPty() = runTest(dispatcher) {
        val source = FakeSource()
        val ptyT0 = FakePtySession(WS)
        source.registerTerminal("T0", TerminalState.Running, ptyT0)
        source.registerTerminal("T1", TerminalState.Starting, null)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T0", source)
        assertSame(ptyT0, vm.ptySession.value)

        vm.attachBinder(WS, "T1", source)

        assertNull(
            "a new window must never inherit the previously focused window's pty",
            vm.ptySession.value
        )
    }

    @Test
    fun missingTerminalResolvesNothingWithoutFallback() = runTest(dispatcher) {
        val source = FakeSource()
        // Only T0 exists; the screen asks for T999.
        val ptyT0 = FakePtySession(WS)
        source.registerTerminal("T0", TerminalState.Running, ptyT0)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T999", source)

        assertNull(vm.terminalState.value)
        assertNull("must NOT fall back to T0", vm.ptySession.value)
    }

    @Test
    fun ptyConvergesWhenRunningEmittedAfterAttach() = runTest(dispatcher) {
        val source = FakeSource()
        source.registerTerminal("T1", TerminalState.Starting, null)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertNull(vm.ptySession.value)

        val pty = FakePtySession(WS)
        source.registerTerminal("T1", TerminalState.Running, pty)
        advanceUntilIdle()

        assertSame(pty, vm.ptySession.value)
    }

    @Test
    fun retriesLookupWhileTerminalOpenUntilSessionAppears() = runTest(dispatcher) {
        val source = FakeSource()
        source.registerTerminal("T1", TerminalState.Running, null)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertNull(vm.ptySession.value)

        val pty = FakePtySession(WS)
        source.registerPty("T1", pty)

        // One retry tick (250 ms) must resolve it.
        advanceTimeBy(250L)
        advanceUntilIdle()

        assertSame(pty, vm.ptySession.value)
    }

    @Test
    fun ptyClearedWhenTerminalStops() = runTest(dispatcher) {
        val source = FakeSource()
        val pty = FakePtySession(WS)
        source.registerTerminal("T1", TerminalState.Running, pty)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertSame(pty, vm.ptySession.value)

        source.registerTerminal("T1", TerminalState.Stopped, null)
        advanceUntilIdle()

        assertNull(vm.ptySession.value)
        assertEquals(TerminalState.Stopped, vm.terminalState.value)
    }

    @Test
    fun terminalErrorSurfacesReasonAndClearsPty() = runTest(dispatcher) {
        val source = FakeSource()
        val pty = FakePtySession(WS)
        source.registerTerminal("T1", TerminalState.Running, pty)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertSame(pty, vm.ptySession.value)

        source.registerTerminal("T1", TerminalState.Error("shell failed"), null)
        advanceUntilIdle()

        assertNull(vm.ptySession.value)
        assertEquals(TerminalState.Error("shell failed"), vm.terminalState.value)
    }

    @Test
    fun replacedBinderReObservesAndOldSourceStopsBeingTrusted() = runTest(dispatcher) {
        val sourceA = FakeSource()
        val ptyA = FakePtySession(WS)
        sourceA.registerTerminal("T1", TerminalState.Running, ptyA)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", sourceA)
        assertSame(ptyA, vm.ptySession.value)

        // Service reconnected → new binder; its terminal is still Starting.
        val sourceB = FakeSource()
        sourceB.registerTerminal("T1", TerminalState.Starting, null)
        vm.attachBinder(WS, "T1", sourceB)
        advanceUntilIdle()
        assertNull(vm.ptySession.value)

        // Old binder now claims Running — must be ignored.
        sourceA.registerTerminal("T1", TerminalState.Running, FakePtySession(WS))
        advanceUntilIdle()
        assertNull(vm.ptySession.value)

        // New binder reaching Running resolves through the new source only.
        val ptyB = FakePtySession(WS)
        sourceB.registerTerminal("T1", TerminalState.Running, ptyB)
        advanceUntilIdle()

        assertSame(ptyB, vm.ptySession.value)
        assertTrue(sourceB.lookupCount.get() > 0)
    }

    @Test
    fun detachClearsUiReferenceOnly() = runTest(dispatcher) {
        val source = FakeSource()
        val pty = FakePtySession(WS)
        source.registerTerminal("T1", TerminalState.Running, pty)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertSame(pty, vm.ptySession.value)

        vm.detachBinder()

        assertNull(vm.ptySession.value)
        assertNull(vm.terminalState.value)
        // The source still holds the (live) terminal — detach never closes it.
        assertSame(pty, source.terminalPty("T1"))
    }

    @Test
    fun reattachResolvesSameSession() = runTest(dispatcher) {
        val source = FakeSource()
        val pty = FakePtySession(WS)
        source.registerTerminal("T1", TerminalState.Running, pty)

        val vm = TerminalViewModel(RuntimeTrace())
        vm.attachBinder(WS, "T1", source)
        assertSame(pty, vm.ptySession.value)

        vm.detachBinder()
        assertNull(vm.ptySession.value)

        vm.attachBinder(WS, "T1", source)
        assertSame(pty, vm.ptySession.value)
    }

    private companion object {
        const val WS = "workspace-1"
    }

    private class FakeSource : PtySessionSource {
        val states = MutableStateFlow<Map<String, RuntimeState>>(emptyMap())
        private val _terminalStates = MutableStateFlow<Map<String, Map<String, TerminalState>>>(emptyMap())
        override val terminalStates: StateFlow<Map<String, Map<String, TerminalState>>> get() = _terminalStates

        private val ptys = mutableMapOf<String, PtySession>()
        val lookupCount = AtomicInteger(0)

        override val sessionStates: StateFlow<Map<String, RuntimeState>> get() = states

        override fun getPtySession(workspaceId: String): PtySession? = ptys["T0"]

        override fun getPtySession(workspaceId: String, terminalId: String): PtySession? {
            lookupCount.incrementAndGet()
            return ptys[terminalId]
        }

        fun registerTerminal(terminalId: String, state: TerminalState, pty: PtySession?) {
            _terminalStates.value = _terminalStates.value + (
                WS to (_terminalStates.value[WS].orEmpty() + (terminalId to state))
            )
            if (pty != null) ptys[terminalId] = pty
        }

        fun registerPty(terminalId: String, pty: PtySession) {
            ptys[terminalId] = pty
        }

        fun terminalPty(terminalId: String): PtySession? = ptys[terminalId]
    }

    private class FakePtySession(override val workspaceId: String) : PtySession {
        override val id: String = "pty-${UUID.randomUUID()}"
        override val pid: Int = 4242
        override val isAlive: Boolean get() = true
        override val exitCode: Int? get() = null

        private val _events = MutableSharedFlow<PtyEvent>(extraBufferCapacity = 8)
        override val events: SharedFlow<PtyEvent> get() = _events

        override fun write(data: ByteArray) = Unit
        override fun write(text: String) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun currentTranscript(): String = ""
        override fun recentOutput(maxChars: Int): String = ""
        override suspend fun awaitExit(): Int = 0
        override fun destroy() = Unit
    }
}
