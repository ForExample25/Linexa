package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.commands.QuickCommandsCatalogLoader
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.features.RecordingGuestFeatureHost
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.test.support.ControllableStubPtySession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * App Launcher → terminal desktop integration.
 *
 * Every launch must allocate through the real runtime allocator, open its OWN
 * window, record the app's catalog identity, and write that app's launch
 * command into that window's pty — never into a sibling. Missing launch
 * commands fall back to a plain shell; a startup failure is reported instead of
 * leaving a half-open window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLauncherViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var fakeRuntime: RecordingContainerRuntime
    private lateinit var manager: WorkspaceTerminalManager
    private lateinit var binder: RuntimeBinder
    private lateinit var identities: TerminalWindowIdentityRegistry
    private lateinit var coordinator: AppInstallCoordinator

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
            guestFeatureHost = RecordingGuestFeatureHost(),
            events = MutableSharedFlow(),
            healthRegistry = WorkspaceHealthRegistry()
        )
        identities = TerminalWindowIdentityRegistry()
        coordinator = Mockito.mock(AppInstallCoordinator::class.java)
        Mockito.`when`(coordinator.jobs)
            .thenReturn(MutableStateFlow<Map<String, AppInstallCoordinator.JobState>>(emptyMap()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): AppLauncherViewModel = AppLauncherViewModel(
        quickCommandsResolver = QuickCommandsResolver(QuickCommandsCatalogLoader { null }),
        coordinator = coordinator,
        windowIdentities = identities
    )

    private fun app(
        id: String = "builtin:python",
        name: String = "Python 3",
        launchCommand: String? = "python3",
        iconKey: String? = "terminal",
        launchAsRoot: Boolean = false
    ) = LauncherApp(
        id = id,
        name = name,
        description = null,
        iconKey = iconKey,
        category = null,
        launchCommand = launchCommand,
        installCommand = "apt-get install -y python3",
        removeCommand = null,
        verificationPackages = emptyList(),
        verificationExecutables = emptyList(),
        launchAsRoot = launchAsRoot
    )

    @Test
    fun anAppLaunchOpensItsOwnWindowAndWritesItsOwnCommand() = runBlocking {
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app())
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.launchStates.value.getValue("builtin:python")
        assertTrue("expected Launched, got $state", state is AppLaunchState.Launched)
        assertEquals("T0", (state as AppLaunchState.Launched).terminalId)
        assertEquals("T0", vm.launchedTerminal.value)

        val pty = manager.getPtySession("ws-1", "T0") as ControllableStubPtySession
        assertEquals(listOf("python3\n"), pty.writes)

        val identity = identities.get("ws-1", "T0")!!
        assertEquals("Python 3", identity.title)
        assertEquals("builtin:python", identity.appId)
        assertEquals("python3", identity.command)
        assertEquals(1, fakeRuntime.openTerminalCalls)
    }

    @Test
    fun aRapidDoubleTapOnTheSameAppOpensOnlyOneWindow() = runBlocking {
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app())
        vm.launch(app())
        vm.launch(app())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRuntime.openTerminalCalls)
        assertEquals(1, manager.terminalSessions.value["ws-1"]?.size)
        val pty = manager.getPtySession("ws-1", "T0") as ControllableStubPtySession
        assertEquals("exactly one command write", listOf("python3\n"), pty.writes)
    }

    @Test
    fun differentAppsOpenIndependentWindowsWithIsolatedCommands() = runBlocking {
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app(id = "builtin:python", name = "Python", launchCommand = "python3"))
        vm.launch(app(id = "builtin:node", name = "Node.js", launchCommand = "node"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, fakeRuntime.openTerminalCalls)
        assertEquals(listOf("T0", "T1"), manager.terminalSessions.value["ws-1"]?.keys?.sorted())

        val python = manager.getPtySession("ws-1", "T0") as ControllableStubPtySession
        val node = manager.getPtySession("ws-1", "T1") as ControllableStubPtySession
        assertEquals(listOf("python3\n"), python.writes)
        assertEquals(listOf("node\n"), node.writes)
        assertEquals("Python", identities.get("ws-1", "T0")?.title)
        assertEquals("Node.js", identities.get("ws-1", "T1")?.title)
    }

    @Test
    fun anAppWithoutALaunchCommandGetsAPlainShellWindow() = runBlocking {
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app(launchCommand = null))
        dispatcher.scheduler.advanceUntilIdle()

        val pty = manager.getPtySession("ws-1", "T0") as ControllableStubPtySession
        assertTrue("nothing may be invented for a commandless app", pty.writes.isEmpty())
        assertTrue(vm.launchStates.value.getValue("builtin:python") is AppLaunchState.Launched)
        assertEquals("Python 3", identities.get("ws-1", "T0")?.title)
    }

    @Test
    fun launchAsRootOpensARootTerminal() = runBlocking {
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app(launchAsRoot = true))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(true), fakeRuntime.asRootOpens)
        assertTrue(vm.launchStates.value.getValue("builtin:python") is AppLaunchState.Launched)
    }

    @Test
    fun aFailedStartupIsReportedAndLeavesNoWindowIdentity() = runBlocking {
        fakeRuntime.failNextOpen = true
        val vm = viewModel()
        vm.attach("ws-1", binder)

        vm.launch(app())
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.launchStates.value.getValue("builtin:python")
        assertTrue("expected Failed, got $state", state is AppLaunchState.Failed)
        assertNull("nothing to focus when no session opened", vm.launchedTerminal.value)
        assertTrue(
            "no app identity may outlive a failed open",
            identities.snapshot("ws-1").isEmpty()
        )
        assertNull(manager.getPtySession("ws-1", "T0"))
    }

    @Test
    fun launchingWithoutABinderIsIgnored() = runBlocking {
        val vm = viewModel()

        vm.launch(app())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fakeRuntime.openTerminalCalls)
        assertTrue(vm.launchStates.value.isEmpty())
    }

    /**
     * Records every open and hands out controllable ptys; can fail the next
     * open or fail every open to simulate a workspace that cannot start a pty.
     */
    private class RecordingContainerRuntime : ContainerRuntime {
        var openTerminalCalls = 0
        var failNextOpen = false
        var asRootOpens = mutableListOf<Boolean>()
        private val sessions = mutableMapOf<Pair<String, String>, PtySession>()

        override suspend fun openTerminal(
            workspaceId: String,
            rootfsPath: String,
            terminalId: String,
            asRoot: Boolean
        ): PtySession {
            if (failNextOpen) {
                failNextOpen = false
                throw IllegalStateException("pty start failed")
            }
            openTerminalCalls++
            asRootOpens += asRoot
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
