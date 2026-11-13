package com.fem.linexa.domain.packagemanager

import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.features.RuntimeGuestFeatureHost
import com.fem.linexa.service.runtime.pty.PtySession
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * §1.93 — Regression tests that a provider's operation stream always emits a
 * terminal [OperationEvent] (Success/Failure) and CLOSES once the underlying
 * process exits — a progress stream must never remain "Running" forever.
 *
 * Uses a real [RuntimeBinder] wired to a [FakeContainerRuntime], so the actual
 * `callbackFlow { send(Progress); execCommandStreaming(...); send(terminal); close() }`
 * lifecycle is exercised end-to-end without any Android/proot involvement.
 * `toList()` returning is itself the proof that each stream closed.
 */
class PackageProviderStreamTest {

    private val fileManager = Mockito.mock(WorkspaceFileManager::class.java)

    @Before
    fun setUp() {
        Mockito.`when`(fileManager.getRootfsPath(anyString())).thenReturn(File("/tmp/rootfs"))
    }

    private fun binder(runtime: FakeContainerRuntime): RuntimeBinder {
        val sessionManager = Mockito.mock(SessionManager::class.java)
        return RuntimeBinder(
            sessionManager = sessionManager,
            containerRuntime = runtime,
            fileManager = fileManager,
            workspaceTerminalManager = WorkspaceTerminalManager(
                containerRuntime = runtime,
                runtimeTrace = RuntimeTrace()
            ),
            // §1.110: the binder delegates its GuestFeatureHost methods; this
            // test doesn't exercise them, so the real (thin) host over the same
            // collaborators is the least surprising value.
            guestFeatureHost = RuntimeGuestFeatureHost(sessionManager, runtime, fileManager),
            events = MutableSharedFlow(),
            healthRegistry = WorkspaceHealthRegistry()
        )
    }

    @Test
    fun aptInstallStreamEmitsSuccessAndClosesWhenProcessExitsZero() = runBlocking {
        val output = "Selecting previously unselected package curl.\nSetting up curl (8.5.0-2)..."
        val runtime = FakeContainerRuntime { command, onLine ->
            // LX-4: every mutating apt op carries the explicit lock-wait pair.
            assertEquals(
                "apt-get install -y curl -o APT::Lock::Timeout=300 -o DPkg::Lock::Timeout=300",
                command
            )
            onLine("Selecting previously unselected package curl.")
            onLine("Setting up curl (8.5.0-2)...")
            CommandResult(exitCode = 0, output = output)
        }

        val events = AptProvider().install(binder(runtime), "ws1", "curl").toList()

        assertTrue("expected at least one event, got ${events.size}", events.isNotEmpty())
        assertTrue(
            "expected live progress lines, got $events",
            events.filterIsInstance<OperationEvent.Progress>().isNotEmpty()
        )
        val terminal = events.last()
        assertTrue("expected Success as the terminal event, got $terminal", terminal is OperationEvent.Success)
        assertEquals(output, (terminal as OperationEvent.Success).output)
        assertEquals(1, runtime.execStreamingCalls)
    }

    @Test
    fun aptInstallStreamEmitsFailureAndClosesWhenProcessExitsNonZero() = runBlocking {
        val runtime = FakeContainerRuntime { _, _ ->
            CommandResult(exitCode = 100, output = "E: dpkg was interrupted, you must manually run 'dpkg --configure -a'")
        }

        val events = AptProvider().install(binder(runtime), "ws1", "curl").toList()

        assertTrue("expected at least one event, got ${events.size}", events.isNotEmpty())
        val terminal = events.last()
        assertTrue("expected Failure as the terminal event, got $terminal", terminal is OperationEvent.Failure)
        assertTrue(
            "expected install failure message, got $terminal",
            (terminal as OperationEvent.Failure).message.startsWith("Install failed")
        )
        assertTrue(terminal.output.contains("dpkg was interrupted"))
    }

    @Test
    fun aptInstallInvalidPackageNameFailsClosedWithoutExecuting() = runBlocking {
        val runtime = FakeContainerRuntime { _, _ -> CommandResult(exitCode = 0, output = "SHOULD NOT RUN") }

        val events = AptProvider().install(binder(runtime), "ws1", "curl; rm -rf /").toList()

        val terminal = events.last()
        assertTrue("expected Failure for an unsafe name, got $terminal", terminal is OperationEvent.Failure)
        // The shell-safety guard must have rejected the command before any exec.
        assertEquals(0, runtime.execStreamingCalls)
    }

    @Test
    fun aptRefreshStreamClosesAfterExitZero() = runBlocking {
        val runtime = FakeContainerRuntime { _, _ ->
            CommandResult(exitCode = 0, output = "Hit:1 http://deb.debian.org/debian bookworm InRelease\nReading package lists... Done")
        }

        val events = AptProvider().refreshSources(binder(runtime), "ws1").toList()

        assertTrue(events.isNotEmpty())
        val terminal = events.last()
        assertTrue("expected Success as the terminal event, got $terminal", terminal is OperationEvent.Success)
    }

    /** Minimal [ContainerRuntime] that records execStreaming calls and answers via [behavior]. */
    private class FakeContainerRuntime(
        private val behavior: (command: String, onLine: (String) -> Unit) -> CommandResult
    ) : ContainerRuntime {
        var execStreamingCalls = 0

        override suspend fun execStreaming(
            workspaceId: String,
            rootfsPath: String,
            command: String,
            timeoutSeconds: Long,
            onLine: (String) -> Unit
        ): CommandResult {
            execStreamingCalls++
            return behavior(command, onLine)
        }

        override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult =
            behavior(command) { }

        override suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        asRoot: Boolean
    ): PtySession =
            error("openTerminal not used in this test")

        override fun getTerminal(workspaceId: String, terminalId: String): PtySession? = null

        override fun isTerminalAlive(workspaceId: String, terminalId: String): Boolean = false

        override suspend fun closeTerminal(workspaceId: String, terminalId: String) = Unit

        override suspend fun closeWorkspaceSessions(workspaceId: String) = Unit

        override suspend fun startDaemon(workspaceId: String, rootfsPath: String, name: String, command: String): Boolean = false

        override suspend fun stopDaemon(workspaceId: String, name: String) = Unit

        override fun isDaemonAlive(workspaceId: String, name: String): Boolean = false

        override suspend fun stopAllDaemons(workspaceId: String) = Unit

        override suspend fun startupRecoverySweep(): RecoverySweepReport = RecoverySweepReport(0, 0, 0)
    }
}
