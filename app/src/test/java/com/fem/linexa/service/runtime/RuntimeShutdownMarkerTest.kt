package com.fem.linexa.service.runtime

import android.app.Application
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.wakelock.RecordingWakeLock
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Phase 07 (OPT-REL-005): the shutdown-marker lifecycle that makes the
 * off-Main service teardown crash-safe.
 *
 * `RuntimeService.onDestroy` writes one marker per still-active workspace
 * SYNCHRONOUSLY before it returns (Android may kill the process the moment it
 * does), then runs teardown off Main. The marker is a RESERVATION: a clean
 * teardown clears it ([RuntimeLifecycleManager.stopWorkspaceLocked]'s success
 * path); only a death or teardown failure between write and clear leaves it
 * behind — and the next process's startup sweep must then run the pending
 * teardown and consume it.
 *
 * Same harness as [RuntimeLifecycleManagerTest]: the fake-PRoot runtime, a
 * tmp-dir-backed [WorkspaceFileManager] (harness.create() stubs `filesDir`),
 * and the real [WorkspaceTerminalManager] so the sweep's `closeWorkspace`
 * has a real terminal to close.
 */
class RuntimeShutdownMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var runtimeTrace: RuntimeTrace
    private lateinit var healthRegistry: WorkspaceHealthRegistry
    private lateinit var sessionManager: SessionManager
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var terminals: WorkspaceTerminalManager
    private lateinit var wakeLock: RecordingWakeLock
    private lateinit var manager: RuntimeLifecycleManager

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
        harness.ptyFactory.controllable = true
        runtimeTrace = RuntimeTrace()
        healthRegistry = WorkspaceHealthRegistry()
        sessionManager = SessionManager(runtimeTrace, healthRegistry)
        fileManager = WorkspaceFileManager(Mockito.mock(Application::class.java))
        terminals = WorkspaceTerminalManager(harness.runtime, runtimeTrace)
        wakeLock = RecordingWakeLock()
        manager = RuntimeLifecycleManager(
            sessionManager,
            terminals,
            fileManager,
            runtimeTrace,
            healthRegistry,
            newWakeLockManager()
        )
    }

    /** Mirrors production: the wake-lock expiry timer outlives any single caller. */
    private fun newWakeLockManager(): WorkspaceWakeLockManager =
        WorkspaceWakeLockManager(
            wakeLock = wakeLock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

    private fun markerFor(workspaceId: String): File =
        File(fileManager.getWorkspaceBasePath(workspaceId), RuntimeLifecycleManager.SHUTDOWN_MARKER)

    private suspend fun start(workspaceId: String): StartOutcome =
        manager.requestStart(workspaceId, harness.fakeRootfs().absolutePath)

    @Test
    fun anActiveWorkspaceGetsAMarkerWhenShutdownIsPending() = runBlocking {
        assertTrue("workspace must start", start("ws-a") is StartOutcome.Started)

        manager.writeShutdownMarkers()

        assertTrue("the pending teardown must be recorded on disk", markerFor("ws-a").exists())
        assertTrue(
            "the marker names the workspace it reserves",
            markerFor("ws-a").readText().startsWith("ws-a ")
        )
    }

    @Test
    fun aWorkspaceWithoutAnActiveSessionGetsNoMarker() = runBlocking {
        manager.writeShutdownMarkers()
        assertFalse("no active session — nothing to reserve", markerFor("ws-a").exists())
    }

    @Test
    fun aCleanStopClearsTheMarkerTheTeardownReserved() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        manager.writeShutdownMarkers()
        assertTrue(markerFor("ws-a").exists())

        assertTrue(manager.requestStop("ws-a"))

        assertFalse("the completed teardown must release the reservation", markerFor("ws-a").exists())
    }

    @Test
    fun aStoppedWorkspaceGetsNoNewMarker() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        assertTrue(manager.requestStop("ws-a"))

        manager.writeShutdownMarkers()

        assertFalse(markerFor("ws-a").exists())
    }

    @Test
    fun aCrashedShutdownsMarkerIsConsumedByTheNextStartupSweep() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        manager.writeShutdownMarkers()
        assertTrue(markerFor("ws-a").exists())

        // The process dies right after onDestroy returned: the session records
        // (in-memory) are gone, the marker survived, and the terminal the dead
        // process left behind is still tracked by the terminal manager. The
        // next process reconstructs the manager over the same on-disk state —
        // a FRESH SessionManager over the SAME file/terminal managers.
        val nextProcessManager = RuntimeLifecycleManager(
            SessionManager(RuntimeTrace(), WorkspaceHealthRegistry()),
            terminals,
            fileManager,
            RuntimeTrace(),
            WorkspaceHealthRegistry(),
            newWakeLockManager()
        )

        val report = nextProcessManager.startupSweep()

        assertFalse(
            "consumption must clear the marker: $report",
            markerFor("ws-a").exists()
        )
        assertFalse(
            "the sweep must run the pending teardown — the terminal the dead process left must be closed",
            harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID)
        )
    }
}
