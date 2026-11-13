package com.fem.linexa.service.workspace

import android.app.Application
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.RuntimeLifecycleManager
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.StartOutcome
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceTerminalManager
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.wakelock.RecordingWakeLock
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.InMemoryWorkspaceRepository
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.buildWorkspaceMetadata
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.117 — [WorkspaceDeleter] tests.
 *
 * The defect these pin: both delete call sites called
 * `WorkspaceRepository.deleteById` directly, which removes the DB row and then
 * unlinks `{workspace}/` recursively. Neither stopped the runtime first, and
 * the Danger Zone one is reachable **only from inside a live workspace**
 * (entering a workspace always starts its session), so the delete raced a
 * running PRoot. The runtime was stopped only later, by
 * `RuntimeViewModel.onCleared`, after the graph had already been popped —
 * anything the guest recreated behind `deleteRecursively`'s top-down walk
 * simply survived, leaking the disk the delete was meant to free.
 *
 * These exercise the real [RuntimeLifecycleManager] and the real
 * [WorkspaceTerminalManager] against the fake-PRoot harness, and the repository
 * fake performs a real `deleteRecursively` so the disk assertions mean
 * something. The ordering is asserted from *inside* the delete callback — the
 * only place that can prove the stop happened first rather than merely
 * eventually.
 */
class WorkspaceDeleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var runtimeTrace: RuntimeTrace
    private lateinit var sessionManager: SessionManager
    private lateinit var healthRegistry: com.fem.linexa.service.runtime.WorkspaceHealthRegistry
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var terminals: WorkspaceTerminalManager
    private lateinit var wakeLock: RecordingWakeLock
    private lateinit var wakeLockManager: WorkspaceWakeLockManager
    private lateinit var lifecycleManager: RuntimeLifecycleManager

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        harness = ProotRuntimeTestHarness(
            prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS),
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.ptyFactory.controllable = true

        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(harness.filesDir)
        runtimeTrace = RuntimeTrace()
        healthRegistry = com.fem.linexa.service.runtime.WorkspaceHealthRegistry()
        sessionManager = SessionManager(runtimeTrace, healthRegistry)
        fileManager = WorkspaceFileManager(app)
        // Keep the real teardown synchronous so a stop that "completes" has
        // genuinely finished by the time the deleter proceeds (the production
        // path hops to Dispatchers.IO inside closeWorkspaceSessions).
        val synchronousTeardownRuntime = object : ContainerRuntime by harness.runtime {
            override suspend fun closeWorkspaceSessions(workspaceId: String) {
                runBlocking { harness.runtime.closeWorkspaceSessions(workspaceId) }
            }
        }
        terminals = WorkspaceTerminalManager(synchronousTeardownRuntime, runtimeTrace)
        wakeLock = RecordingWakeLock()
        wakeLockManager = WorkspaceWakeLockManager(
            wakeLock = wakeLock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        lifecycleManager = RuntimeLifecycleManager(sessionManager, terminals, fileManager, runtimeTrace, healthRegistry, wakeLockManager)
    }

    /** Seeds `{files}/workspaces/{id}/rootfs` with a marker file, and returns the rootfs path. */
    private fun seedRootfs(workspaceId: String): String {
        val rootfs = fileManager.ensureWorkspaceDirs(workspaceId)
        listOf("bin", "etc", "lib", "usr", "var").forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "etc/os-release").writeText("NAME=LinexaTest\n")
        return rootfs.absolutePath
    }

    private fun markerFor(workspaceId: String): File =
        File(fileManager.getRootfsPath(workspaceId), "etc/os-release")

    /**
     * A repository fake that mirrors [com.fem.linexa.data.repository.WorkspaceRepositoryImpl]:
     * `deleteWorkspace` drops the record AND recursively deletes the workspace
     * directory. [onDeleteEntered] runs at the top of that call, which is the
     * only vantage point that can prove the runtime was already down *before*
     * disk was touched.
     */
    private inner class DiskBackedRepository(
        private val onDeleteEntered: () -> Unit = {}
    ) : InMemoryWorkspaceRepository() {
        val calls = mutableListOf<String>()

        override suspend fun deleteWorkspace(id: String): WorkspaceDeletionResult {
            calls += "deleteWorkspace"
            onDeleteEntered()
            val result = super.deleteWorkspace(id)
            if (result is WorkspaceDeletionResult.Deleted) {
                fileManager.getWorkspaceBasePath(id).deleteRecursively()
                fileManager.invalidateRootfsSize(id)
            }
            return result
        }
    }

    @Test
    fun theRuntimeIsAlreadyDownWhenDiskIsTouched() = runBlocking {
        val workspaceId = "ws-order"
        val rootfs = seedRootfs(workspaceId)

        // Observed from inside deleteWorkspace — the moment disk is about to go.
        var gateAtDeleteTime: String? = "not-captured"
        var sessionOverAtDeleteTime = false
        var terminalAliveAtDeleteTime = true

        val repo = DiskBackedRepository(onDeleteEntered = {
            gateAtDeleteTime = lifecycleManager.activeWorkspaceId.value
            sessionOverAtDeleteTime = lifecycleManager.sessionTrulyOver(workspaceId)
            terminalAliveAtDeleteTime =
                harness.runtime.isTerminalAlive(workspaceId, com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID)
        })
        repo.add(buildWorkspaceMetadata(workspaceId))

        // Bring the workspace up for real, the way entering it does.
        val started = lifecycleManager.requestStart(workspaceId, rootfs)
        assertTrue("expected the harness workspace to start, got $started", started is StartOutcome.Started)
        assertEquals(workspaceId, lifecycleManager.activeWorkspaceId.value)
        assertTrue(
            harness.runtime.isTerminalAlive(workspaceId, com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID)
        )

        val result = WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)

        assertEquals(WorkspaceDeleter.Outcome.Deleted, result)
        // The ordering guarantee, observed at the instant of the disk touch.
        assertNull("runtime gate must already be clear when disk is deleted", gateAtDeleteTime)
        assertTrue("session must already be over when disk is deleted", sessionOverAtDeleteTime)
        assertFalse("no terminal may still be alive when disk is deleted", terminalAliveAtDeleteTime)
        // And the delete really happened.
        assertEquals(listOf("deleteWorkspace"), repo.calls)
        assertNull(repo.getById(workspaceId))
        assertFalse("rootfs must be gone", markerFor(workspaceId).exists())
    }

    @Test
    fun deleteGoesThroughTheTypedRepositoryContract() = runBlocking {
        val workspaceId = "ws-status"
        seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        assertEquals(
            WorkspaceDeleter.Outcome.Deleted,
            WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)
        )
        assertEquals(listOf("deleteWorkspace"), repo.calls)
    }

    @Test
    fun deletingANeverStartedWorkspaceSucceeds() = runBlocking {
        // A Broken/never-launched workspace must still be deletable — the stop
        // is a no-op returning true, not a refusal.
        val workspaceId = "ws-never-ran"
        seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        assertEquals(
            WorkspaceDeleter.Outcome.Deleted,
            WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)
        )
        assertNull(repo.getById(workspaceId))
        assertFalse(markerFor(workspaceId).exists())
    }

    /**
     * A lifecycle manager whose session close never returns, with a short
     * teardown bound so the deterministic block path is what gets exercised.
     */
    private fun wedgedManager(): RuntimeLifecycleManager {
        val wedgedRuntime = object : ContainerRuntime by harness.runtime {
            override suspend fun closeWorkspaceSessions(workspaceId: String) {
                delay(Long.MAX_VALUE)
            }
        }
        return RuntimeLifecycleManager(
            sessionManager,
            WorkspaceTerminalManager(wedgedRuntime, runtimeTrace),
            fileManager,
            runtimeTrace,
            healthRegistry,
            wakeLockManager,
            teardownTimeoutMs = 50L
        )
    }

    @Test
    fun aFailedTeardownRefusesTheDeleteAndTouchesNothing() = runBlocking {
        // A teardown that cannot complete within the bound must NOT delete: the
        // guest may still be writing, and deleting under it is the corruption
        // §1.117 exists to prevent. Refusing is recoverable; a half-deleted
        // tree with a live writer is not.
        val workspaceId = "ws-wedged"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        val manager = wedgedManager()
        val started = manager.requestStart(workspaceId, rootfs)
        assertTrue("expected a start, got $started", started is StartOutcome.Started)

        val result = WorkspaceDeleter(manager, repo).delete(workspaceId)

        assertTrue("expected StopFailed, got $result", result is WorkspaceDeleter.Outcome.StopFailed)
        // Nothing was deleted — not the status, not the row, not the disk.
        assertTrue("repository must not have been touched, was: ${repo.calls}", repo.calls.isEmpty())
        assertNotNull(repo.getById(workspaceId))
        assertTrue("rootfs must survive a refused delete", markerFor(workspaceId).exists())
    }

    @Test
    fun refusalReasonIsUserFacing() = runBlocking {
        val workspaceId = "ws-reason"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        val manager = wedgedManager()
        manager.requestStart(workspaceId, rootfs)

        val result = WorkspaceDeleter(manager, repo).delete(workspaceId)
        val reason = (result as WorkspaceDeleter.Outcome.StopFailed).reason

        // The message is rendered verbatim in a dialog, so it must say that
        // nothing was deleted and that retrying is the action.
        assertTrue(reason, reason.contains("nothing was deleted", ignoreCase = true))
        assertTrue(reason, reason.contains("try again", ignoreCase = true))
        // And it must not leak an exception/class name into the UI.
        assertFalse(reason, reason.contains("Exception"))
    }

    @Test
    fun metadataSurvivesARefusedDeleteSoRetryIsPossible() = runBlocking {
        // The refusal path must leave the workspace fully intact — including a
        // status the UI can still render (never a stranded Deleting, which
        // would blank the card while the workspace still exists).
        val workspaceId = "ws-intact"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        val manager = wedgedManager()
        manager.requestStart(workspaceId, rootfs)

        WorkspaceDeleter(manager, repo).delete(workspaceId)

        val metadata: WorkspaceMetadata? = repo.getById(workspaceId)
        assertNotNull(metadata)
        assertFalse(
            "a refused delete must not leave the workspace stranded in Deleting",
            metadata!!.status is WorkspaceStatus.Deleting
        )
    }

    @Test
    fun deleteIsIdempotentForAnAlreadyGoneWorkspace() = runBlocking {
        // The confirm dialog can be double-tapped; a second delete must not
        // throw or report a refusal.
        val workspaceId = "ws-twice"
        seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))
        val deleter = WorkspaceDeleter(lifecycleManager, repo)

        assertEquals(WorkspaceDeleter.Outcome.Deleted, deleter.delete(workspaceId))
        assertEquals(WorkspaceDeleter.Outcome.Deleted, deleter.delete(workspaceId))
        assertNull(repo.getById(workspaceId))
    }

    @Test
    fun deletingOneWorkspaceLeavesAnotherRunning() = runBlocking {
        // The stop must be scoped to the workspace being deleted — Home can
        // delete a card while a different workspace owns the runtime.
        val victim = "ws-victim"
        val bystander = "ws-bystander"
        seedRootfs(victim)
        val bystanderRootfs = seedRootfs(bystander)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(victim))
        repo.add(buildWorkspaceMetadata(bystander))

        lifecycleManager.requestStart(bystander, bystanderRootfs)
        assertEquals(bystander, lifecycleManager.activeWorkspaceId.value)

        val result = WorkspaceDeleter(lifecycleManager, repo).delete(victim)

        assertEquals(WorkspaceDeleter.Outcome.Deleted, result)
        // The bystander still owns the runtime and still has its files.
        assertEquals(bystander, lifecycleManager.activeWorkspaceId.value)
        assertTrue(
            harness.runtime.isTerminalAlive(bystander, com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID)
        )
        assertNotNull(repo.getById(bystander))
        assertTrue(markerFor(bystander).exists())
        assertNull(repo.getById(victim))
        assertFalse(markerFor(victim).exists())
    }

    @Test
    fun deletingTheRunningWorkspaceTearsDownEveryTerminal() = runBlocking {
        // A workspace can have several terminals (§1.97); all of them must be
        // gone before the rootfs they are executing inside is unlinked.
        val workspaceId = "ws-multi"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        assertTrue(lifecycleManager.requestStart(workspaceId, rootfs) is StartOutcome.Started)
        terminals.openTerminal(workspaceId, rootfs, "T1", asRoot = false)
        terminals.openTerminal(workspaceId, rootfs, "T2", asRoot = false)
        assertEquals(3, terminals.terminalStates.value[workspaceId]?.size)

        assertEquals(
            WorkspaceDeleter.Outcome.Deleted,
            WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)
        )

        assertFalse(harness.runtime.isTerminalAlive(workspaceId, "T0"))
        assertFalse(harness.runtime.isTerminalAlive(workspaceId, "T1"))
        assertFalse(harness.runtime.isTerminalAlive(workspaceId, "T2"))
        assertTrue(terminals.terminalStates.value[workspaceId].isNullOrEmpty())
        assertFalse(markerFor(workspaceId).exists())
    }

    @Test
    fun deletingAWorkspaceReleasesItsWakeLock() = runBlocking {
        // §1.142: delete is a stop trigger, and the workspace it held a lock for
        // no longer exists — nothing else could ever release it.
        val workspaceId = "ws-awake"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        assertTrue(lifecycleManager.requestStart(workspaceId, rootfs) is StartOutcome.Started)
        wakeLockManager.toggle(workspaceId)
        assertTrue(wakeLock.isHeld())

        assertEquals(
            WorkspaceDeleter.Outcome.Deleted,
            WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)
        )

        assertFalse("a deleted workspace must not keep the CPU awake", wakeLock.isHeld())
        assertNull(wakeLockManager.hold.value)
    }

    @Test
    fun aDiskFailureKeepsTheRowAndSurfacesTheReason() = runBlocking {
        // The phase-1 contract: if the on-disk tree cannot be removed, the
        // database row must survive so the user can retry — and the deleter
        // must not claim success.
        val workspaceId = "ws-diskfail"
        val repo = object : InMemoryWorkspaceRepository() {
            override suspend fun deleteWorkspace(id: String): WorkspaceDeletionResult =
                WorkspaceDeletionResult.DiskFailure(
                    "Could not remove the workspace files on disk; nothing was deleted."
                )
        }
        repo.add(buildWorkspaceMetadata(workspaceId))

        val result = WorkspaceDeleter(lifecycleManager, repo).delete(workspaceId)

        assertTrue("expected DeleteFailed, got $result", result is WorkspaceDeleter.Outcome.DeleteFailed)
        val reason = (result as WorkspaceDeleter.Outcome.DeleteFailed).reason
        assertTrue(reason, reason.contains("nothing was deleted", ignoreCase = true))
        assertNotNull("the row must survive a disk failure", repo.getById(workspaceId))
    }

    @Test
    fun aRefusedDeleteStillReleasesTheWakeLock() = runBlocking {
        // The refusal keeps the workspace and its files (above), but the hold is
        // not part of that state: the teardown was attempted and the guest is
        // wedged, so continuing to hold the CPU awake buys nothing.
        val workspaceId = "ws-wedged-awake"
        val rootfs = seedRootfs(workspaceId)
        val repo = DiskBackedRepository()
        repo.add(buildWorkspaceMetadata(workspaceId))

        val manager = wedgedManager()
        manager.requestStart(workspaceId, rootfs)
        wakeLockManager.toggle(workspaceId)

        val result = WorkspaceDeleter(manager, repo).delete(workspaceId)

        assertTrue("expected StopFailed, got $result", result is WorkspaceDeleter.Outcome.StopFailed)
        assertFalse(wakeLock.isHeld())
        assertNotNull("the workspace itself must survive the refusal", repo.getById(workspaceId))
    }
}
