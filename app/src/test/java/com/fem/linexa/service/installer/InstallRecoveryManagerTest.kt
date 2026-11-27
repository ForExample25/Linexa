package com.fem.linexa.service.installer

import android.app.Application
import android.content.ContentResolver
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.96 unit tests for [InstallRecoveryManager] — the startup safety net that
 * cleans up installs interrupted by process death (P0-4 gap from the
 * 2026-08-08 audit: zero coverage for this class, which runs on every app
 * start via `LinexaApplication.recoverInterruptedInstalls()`).
 *
 * Uses a real [WorkspaceFileManager] (Mockito `Application` + TemporaryFolder,
 * exactly like InstallCoordinatorTest.setUpCoordinator) so the staging /
 * `.import-cache` directory logic is exercised against real files on disk —
 * assertions are on real filesystem state, not mock-verify. The repository is
 * faked (in-memory, with updateStatus/getById call tracking) per the existing
 * FakeWorkspaceRepository pattern.
 */
class InstallRecoveryManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var repository: TrackingFakeWorkspaceRepository
    private lateinit var recovery: InstallRecoveryManager

    private fun setUp() {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.contentResolver).thenReturn(Mockito.mock(ContentResolver::class.java))

        fileManager = WorkspaceFileManager(app)
        repository = TrackingFakeWorkspaceRepository()
        recovery = InstallRecoveryManager(fileManager, repository)
    }

    /** Workspace dir on disk: {files}/workspaces/{id}. */
    private fun workspaceDir(id: String): File = fileManager.getWorkspaceBasePath(id)

    /** Create a real staging dir on disk, with an optional `.import-cache` sibling. */
    private fun createStaging(id: String, withImportCache: Boolean = false): File {
        val staging = fileManager.getStagingRootfsPath(id)
        assertTrue("staging dir must be created for test", staging.mkdirs())
        File(staging, "payload").writeText("partial")
        if (withImportCache) {
            val cache = File(workspaceDir(id), ".import-cache")
            assertTrue("import-cache dir must be created for test", cache.mkdirs())
            File(cache, "partial.tar").writeText("partial")
        }
        return staging
    }

    private fun metadata(id: String, status: WorkspaceStatus): WorkspaceMetadata =
        WorkspaceMetadata(
            id = id,
            status = status,
            distro = DistroInfo(name = "Debian", version = "12", arch = "arm64-v8a"),
            displayName = id,
            avatarLetter = "D",
            avatarColorHex = "#000000",
            rootfsPath = fileManager.getRootfsPath(id).absolutePath,
            createdAt = 1_700_000_000_000L,
            runtimeConfig = RuntimeConfig(),
            terminalSettings = TerminalSettings(),
            featureToggles = emptyMap()
        )

    private fun runRecovery() = runBlocking { recovery.recoverInterruptedInstalls() }

    // 1. No workspaces at all -> no-op.
    @Test
    fun noWorkspacesIsANoOp() {
        setUp()
        runRecovery()
        assertEquals("no repository calls at all", emptyList<String>(), repository.getByIdCalls)
        assertEquals(
            "no repository updates at all",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls
        )
    }

    // 2. Staging exists but no DB record (orphan) -> staging removed, nothing updated.
    @Test
    fun orphanStagingWithoutDbRecordIsRemoved() {
        setUp()
        val staging = createStaging("orphan-ws")

        runRecovery()

        assertFalse("orphan staging dir must be physically removed", staging.exists())
        assertEquals(
            "orphan must never be updated",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls
        )
        assertEquals("orphan getById must be observed", listOf("orphan-ws"), repository.getByIdCalls)
    }

    // 3. Staging + DB record with status != Ready -> staging + .import-cache removed, marked Broken exactly once.
    @Test
    fun interruptedInstallIsMarkedBrokenAndCleaned() {
        setUp()
        val staging = createStaging("interrupted-ws", withImportCache = true)
        repository.seed(metadata("interrupted-ws", WorkspaceStatus.Importing(0.5f)))

        runRecovery()

        assertFalse("staging dir must be removed", staging.exists())
        assertFalse(
            "sibling .import-cache must be removed",
            File(workspaceDir("interrupted-ws"), ".import-cache").exists()
        )
        val updates = repository.updateStatusCalls.filter { it.first == "interrupted-ws" }
        assertEquals("updateStatus must be called exactly once", 1, updates.size)
        val broken = updates.single().second
        assertTrue("workspace must be marked Broken", broken is WorkspaceStatus.Broken)
        assertTrue(
            "Broken reason must be non-empty",
            (broken as WorkspaceStatus.Broken).reason.isNotBlank()
        )
    }

    // 4. Staging + DB record with status == Ready (stale debris) -> staging removed, Ready never touched.
    @Test
    fun readyWorkspaceStaleStagingIsCleanedWithoutTouchingStatus() {
        setUp()
        val staging = createStaging("ready-ws")
        repository.seed(metadata("ready-ws", WorkspaceStatus.Ready))

        runRecovery()

        assertFalse("stale staging under a Ready workspace must be removed", staging.exists())
        assertEquals(
            "Ready workspace must never be updateStatus'd",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls
        )
    }

    // 5. No staging dir:
    //    - an Importing row (killed mid-download / mid-activation) is marked
    //      Broken and its download/import scratch is removed — the F4 fix
    //      (2026-09-02): the old "no staging → untouched" behavior left the
    //      row stuck Importing forever;
    //    - a Ready row without staging is still untouched entirely.
    @Test
    fun importingWorkspaceWithoutStagingIsRecoveredAsInterruptedDownload() {
        setUp()
        // workspace dir exists on disk (so listWorkspaceIds sees it) but has no staging
        val dir = workspaceDir("no-staging-ws")
        assertTrue(dir.mkdirs())
        repository.seed(metadata("no-staging-ws", WorkspaceStatus.Importing(0.25f)))
        File(dir, ".download-cache").mkdirs()

        runRecovery()

        val updates = repository.updateStatusCalls.filter { it.first == "no-staging-ws" }
        assertEquals("Importing row without staging must be marked Broken", 1, updates.size)
        val broken = updates.single().second
        assertTrue(broken is WorkspaceStatus.Broken)
        assertTrue(
            "reason must describe the interrupted download",
            (broken as WorkspaceStatus.Broken).reason.contains("interrupted", ignoreCase = true)
        )
        assertFalse(".download-cache scratch must be removed", File(dir, ".download-cache").exists())
        assertTrue("workspace dir itself must remain", dir.exists())
    }

    @Test
    fun readyWorkspaceWithoutStagingIsNotTouched() {
        setUp()
        val dir = workspaceDir("ready-no-staging-ws")
        assertTrue(dir.mkdirs())
        repository.seed(metadata("ready-no-staging-ws", WorkspaceStatus.Ready))

        runRecovery()

        assertEquals(
            "a Ready workspace without staging must never be updateStatus'd",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls.filter { it.first == "ready-no-staging-ws" }
        )
        assertTrue("workspace dir itself must remain", dir.exists())
    }

    // 6. Multiple workspaces mixing all cases -> each handled independently.
    @Test
    fun multipleWorkspacesAreHandledIndependently() {
        setUp()
        // orphan: staging only, no DB record
        createStaging("orphan")
        // interrupted: staging + .import-cache, DB status Importing
        createStaging("interrupted", withImportCache = true)
        repository.seed(metadata("interrupted", WorkspaceStatus.Importing(0.5f)))
        // ready-debris: staging, DB status Ready
        createStaging("ready")
        repository.seed(metadata("ready", WorkspaceStatus.Ready))
        // broken-record: staging + DB already Broken
        createStaging("broken")
        repository.seed(metadata("broken", WorkspaceStatus.Broken("old failure")))
        // untouched: dir on disk, no staging, DB status Ready
        assertTrue(workspaceDir("untouched").mkdirs())
        repository.seed(metadata("untouched", WorkspaceStatus.Ready))

        runRecovery()

        // Orphan: staging gone, never updated.
        assertFalse(fileManager.getStagingRootfsPath("orphan").exists())
        assertEquals(
            "orphan must not be updated",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls.filter { it.first == "orphan" }
        )
        // Interrupted: staging + cache gone, exactly one Broken update.
        assertFalse(fileManager.getStagingRootfsPath("interrupted").exists())
        assertFalse(File(workspaceDir("interrupted"), ".import-cache").exists())
        val interruptedUpdates = repository.updateStatusCalls.filter { it.first == "interrupted" }
        assertEquals("interrupted must be updated exactly once", 1, interruptedUpdates.size)
        assertTrue(interruptedUpdates.single().second is WorkspaceStatus.Broken)
        // Ready-debris: staging gone, status untouched.
        assertFalse(fileManager.getStagingRootfsPath("ready").exists())
        assertEquals(
            "Ready must not be updated",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls.filter { it.first == "ready" }
        )
        // Already-Broken with staging: staging gone, re-marked Broken once.
        assertFalse(fileManager.getStagingRootfsPath("broken").exists())
        assertEquals("already-Broken must be re-marked exactly once", 1, repository.updateStatusCalls.filter { it.first == "broken" }.size)
        // Untouched (Ready, no staging): never status-mutated. It IS read
        // (the F4 fix (2026-09-02) classifies no-staging workspaces by their
        // status before deciding), but a Ready row without staging is left
        // exactly as it was.
        assertEquals(
            "untouched must not be updated",
            emptyList<Pair<String, WorkspaceStatus>>(),
            repository.updateStatusCalls.filter { it.first == "untouched" }
        )
        // The repository still holds the seeded records (recovery never deletes rows).
        assertNotNull(repository.getByIdSync("ready"))
        assertNotNull(repository.getByIdSync("interrupted"))
    }

    // ── RF-030: activation finished, status write did not ─────────────────

    @Test
    fun importingWorkspaceWithAValidLiveRootfsIsPromotedToReady() {
        setUp()
        val id = "activated-ws"
        assertTrue(workspaceDir(id).mkdirs())
        createValidRootfs(id)
        repository.seed(metadata(id, WorkspaceStatus.Importing(0.9f)))
        File(workspaceDir(id), ".import-cache").mkdirs()
        File(workspaceDir(id), ".download-cache").mkdirs()

        runRecovery()

        val updates = repository.updateStatusCalls.filter { it.first == id }
        assertEquals("exactly one status decision", 1, updates.size)
        assertEquals(
            "a complete rootfs must not be declared a broken download",
            WorkspaceStatus.Ready,
            updates.single().second
        )
        assertFalse("import scratch must be reclaimed", File(workspaceDir(id), ".import-cache").exists())
        assertFalse("download scratch must be reclaimed", File(workspaceDir(id), ".download-cache").exists())
        assertTrue("the rootfs itself must be left in place", fileManager.getRootfsPath(id).isDirectory)
    }

    @Test
    fun importingWorkspaceWithADamagedRootfsReportsDamageNotADownload() {
        setUp()
        val id = "damaged-ws"
        assertTrue(workspaceDir(id).mkdirs())
        createDamagedRootfs(id)
        repository.seed(metadata(id, WorkspaceStatus.Importing(0.9f)))

        runRecovery()

        val broken = repository.updateStatusCalls.single { it.first == id }.second
        assertTrue("must be Broken", broken is WorkspaceStatus.Broken)
        val reason = (broken as WorkspaceStatus.Broken).reason
        assertTrue("the reason must name the damage, got: $reason", reason.contains("damaged", ignoreCase = true))
        assertFalse("it must not blame the download, got: $reason", reason.contains("Download", ignoreCase = true))
        assertFalse("it must not claim the install was interrupted, got: $reason", reason.contains("interrupted", ignoreCase = true))
    }

    // ── RF-021: activation swap recovery and debris sweep ─────────────────

    @Test
    fun anInterruptedActivationRestoresThePreviousRootfsInsteadOfLeavingTheWorkspaceEmpty() {
        setUp()
        val id = "swap-ws"
        val dir = workspaceDir(id)
        assertTrue(dir.mkdirs())
        // Crash between `live → rootfs.previous-*` and `staging → live`:
        // no live rootfs, no staging, one previous tree.
        val previous = File(dir, "rootfs.previous-1000")
        createValidRootfsAt(previous)
        repository.seed(metadata(id, WorkspaceStatus.Importing(0.5f)))

        runRecovery()

        val live = fileManager.getRootfsPath(id)
        assertTrue("the previous tree must be restored as the live rootfs", live.isDirectory)
        assertTrue("the shell inside it must survive the rename", File(live, "bin/sh").canExecute())
        assertFalse("the previous name must be consumed", previous.exists())
        assertEquals(
            "a restored, valid rootfs must be promoted rather than marked Broken",
            WorkspaceStatus.Ready,
            repository.updateStatusCalls.single { it.first == id }.second
        )
    }

    @Test
    fun staleActivationAndQuarantineDebrisIsSweptWhileUnrelatedEntriesStay() {
        setUp()
        val id = "debris-ws"
        val dir = workspaceDir(id)
        assertTrue(dir.mkdirs())
        createValidRootfs(id)
        repository.seed(metadata(id, WorkspaceStatus.Ready))

        val previous = File(dir, "rootfs.previous-1000").apply {
            assertTrue(mkdirs())
            File(this, "payload").writeText("old rootfs")
        }
        val quarantine = File(dir, ".staging-rootfs.quarantine-1000").apply {
            assertTrue(mkdirs())
            File(this, "payload").writeText("contained tree")
        }
        val notDebris = File(dir, "rootfs.previous-not-a-timestamp").apply {
            assertTrue(mkdirs())
        }

        runRecovery()

        assertFalse("stale rootfs.previous-* must be reclaimed", previous.exists())
        assertFalse("a stale containment quarantine must be reclaimed", quarantine.exists())
        assertTrue("only the exact numeric debris names may be touched", notDebris.exists())
    }

    // ── RF-031: config staged until activation ────────────────────────────

    @Test
    fun aStagedConfigIsPromotedOnlyWhenTheLiveRootfsExists() {
        setUp()
        val id = "config-ws"
        assertTrue(workspaceDir(id).mkdirs())
        createValidRootfs(id)
        repository.seed(metadata(id, WorkspaceStatus.Ready))
        val staged = fileManager.getStagedConfigPath(id)
        staged.writeText("workspace: ConfigWs\n")

        runRecovery()

        assertEquals(
            "the staged config must become the real config.yaml",
            "workspace: ConfigWs\n",
            fileManager.getWorkspaceConfigPath(id).readText()
        )
        assertFalse("the staged name must not survive", staged.exists())
    }

    @Test
    fun aStagedConfigWithoutARootfsIsDiscardedAsOrphaned() {
        setUp()
        val id = "config-orphan-ws"
        assertTrue(workspaceDir(id).mkdirs())
        repository.seed(metadata(id, WorkspaceStatus.Broken("never activated")))
        val staged = fileManager.getStagedConfigPath(id)
        staged.writeText("workspace: NeverActivated\n")

        runRecovery()

        assertFalse("a config for a rootfs that never activated must not survive", staged.exists())
        assertFalse(
            "and it must not be promoted to config.yaml",
            fileManager.getWorkspaceConfigPath(id).exists()
        )
    }

    @Test
    fun aStagedConfigIsDiscardedWhileStagingStillExistsEvenWithALiveRootfs() {
        setUp()
        val id = "config-mid-swap-ws"
        assertTrue(workspaceDir(id).mkdirs())
        // The old tree is live (or restored from `rootfs.previous-*`), but the
        // staging rename never completed. The staged config describes the
        // staging tree, not the live one, so it must not replace the old file.
        createValidRootfs(id)
        fileManager.getWorkspaceConfigPath(id).writeText("workspace: OldTree\n")
        createStaging(id)
        repository.seed(metadata(id, WorkspaceStatus.Importing(0.9f)))
        val staged = fileManager.getStagedConfigPath(id)
        staged.writeText("workspace: NewTree\n")

        runRecovery()

        assertEquals(
            "the old rootfs keeps its own config while activation never completed",
            "workspace: OldTree\n",
            fileManager.getWorkspaceConfigPath(id).readText()
        )
        assertFalse("the unactivated staged config must be discarded", staged.exists())
        assertFalse("the staging tree is still cleaned up", fileManager.getStagingRootfsPath(id).exists())
    }

    private fun createValidRootfs(id: String) =
        createValidRootfsAt(fileManager.getRootfsPath(id))

    /** Minimal tree that satisfies [com.fem.linexa.service.runtime.WorkspaceFileManager.checkRootfs]. */
    private fun createValidRootfsAt(rootfs: File) {
        for (dir in listOf("etc", "usr", "usr/bin", "usr/lib", "bin")) {
            assertTrue("must create $dir", File(rootfs, dir).mkdirs())
        }
        val shell = File(rootfs, "bin/sh")
        shell.writeText("#!/bin/sh\nexit 0\n")
        assertTrue("the launch shell must be executable", shell.setExecutable(true))
    }

    /** Layout present, launch shell gone — the RF-003 `Damaged` shape. */
    private fun createDamagedRootfs(id: String) {
        val rootfs = fileManager.getRootfsPath(id)
        for (dir in listOf("etc", "usr", "usr/bin", "usr/lib")) {
            assertTrue("must create $dir", File(rootfs, dir).mkdirs())
        }
    }
}

/**
 * In-memory [WorkspaceRepository] that additionally records every getById and
 * updateStatus call so tests can assert exactly what recovery touched.
 */
private class TrackingFakeWorkspaceRepository : WorkspaceRepository {
    private val store = mutableMapOf<String, WorkspaceMetadata>()
    private val all = MutableStateFlow<List<WorkspaceMetadata>>(emptyList())

    val getByIdCalls = mutableListOf<String>()
    val updateStatusCalls = mutableListOf<Pair<String, WorkspaceStatus>>()

    fun seed(metadata: WorkspaceMetadata) {
        store[metadata.id] = metadata
        all.value = store.values.toList()
    }

    fun getByIdSync(id: String): WorkspaceMetadata? = store[id]

    override fun observeAll(): Flow<List<WorkspaceMetadata>> = all

    override fun observeById(id: String): Flow<WorkspaceMetadata?> =
        MutableStateFlow(store[id])

    override suspend fun getById(id: String): WorkspaceMetadata? {
        getByIdCalls += id
        return store[id]
    }

    override suspend fun insert(metadata: WorkspaceMetadata) {
        store[metadata.id] = metadata
        all.value = store.values.toList()
    }

    override suspend fun update(metadata: WorkspaceMetadata) {
        store[metadata.id] = metadata
        all.value = store.values.toList()
    }

    override suspend fun deleteById(id: String) {
        store.remove(id)
        all.value = store.values.toList()
    }

    override suspend fun updateStatus(id: String, status: WorkspaceStatus) {
        updateStatusCalls += id to status
        val current = store[id] ?: return
        store[id] = current.copy(status = status)
        all.value = store.values.toList()
    }

    override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) {
        val current = store[id] ?: return
        store[id] = current.copy(featureToggles = toggles)
        all.value = store.values.toList()
    }

    override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
        val current = store[id] ?: return
        store[id] = current.copy(attributes = current.attributes + attributes)
        all.value = store.values.toList()
    }
}
