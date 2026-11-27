package com.fem.linexa.data.repository

import android.app.Application
import com.fem.linexa.data.room.WorkspaceDao
import com.fem.linexa.domain.security.SecretAttributeCodec
import com.fem.linexa.domain.security.SecretAttributeCodecTest
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.test.support.FaultInjector
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * 10E (OPT-TEST-005) — the fault-injected deletion contract: a walk that
 * fails PARTWAY through (some entries already gone) must behave exactly like
 * the intact-failure case `WorkspaceRepositoryDeletionTest` covers — the row
 * survives for a retry, and the row-delete never fires.
 *
 * The injection is [FaultInjector.PartialDiskStore]: a real deletion of a
 * prefix of the tree, then failure. A same-UID JVM cannot produce the POSIX
 * `EACCES` a device produces mid-walk (the deletion helper widens every
 * directory inside the tree), so the injectable seam is the disk store the
 * repository already consumes — the FAILURE SHAPE, not the errno, is what
 * the contract pins. (FaultInjector.kt records why.)
 */
class WorkspaceRepositoryFaultInjectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var filesDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var dao: FakeDao
    private lateinit var repo: WorkspaceRepositoryImpl

    private class FakeDao : WorkspaceDao {
        val rows = MutableStateFlow<Map<String, WorkspaceMetadata>>(emptyMap())
        val deleted = mutableListOf<String>()
        override fun observeAll(): Flow<List<WorkspaceMetadata>> = rows.map { it.values.toList() }
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = rows.map { it[id] }
        override suspend fun getById(id: String): WorkspaceMetadata? = rows.value[id]
        override suspend fun insert(workspace: WorkspaceMetadata) {
            rows.value = rows.value + (workspace.id to workspace)
        }
        override suspend fun update(workspace: WorkspaceMetadata) {
            rows.value = rows.value + (workspace.id to workspace)
        }
        override suspend fun deleteById(id: String) {
            deleted += id
            rows.value = rows.value - id
        }
        override suspend fun updateStatus(id: String, status: String) = Unit
    }

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        fileManager = WorkspaceFileManager(app)
        dao = FakeDao()
        // The injection: every rootfs child is deleted EXCEPT `etc` — the
        // partial state a mid-walk failure leaves behind.
        repo = WorkspaceRepositoryImpl(
            dao,
            FaultInjector.PartialDiskStore(fileManager, keep = setOf("etc")),
            WorkspaceSecretAttributes(SecretAttributeCodec(SecretAttributeCodecTest.SoftwareAesGcmCipher()))
        )
    }

    private fun row(id: String) = WorkspaceMetadata(
        id = id,
        status = WorkspaceStatus.Ready,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = id,
        avatarLetter = "D",
        avatarColorHex = "#112233",
        rootfsPath = fileManager.getRootfsPath(id).absolutePath,
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap(),
        attributes = emptyMap()
    )

    private fun seedWorkspace(id: String): File {
        val rootfs = fileManager.ensureWorkspaceDirs(id)
        listOf("bin", "etc", "lib", "usr", "var").forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "etc/os-release").writeText("NAME=LinexaTest\n")
        return rootfs
    }

    @Test
    fun aDeletionThatFailsPartwayKeepsTheRowForRetry() = runBlocking {
        val id = "ws-partial"
        val rootfs = seedWorkspace(id)
        repo.insert(row(id))
        val keptRelease = File(rootfs, "etc/os-release")

        val result = repo.deleteWorkspace(id)

        assertTrue("expected DiskFailure, got $result", result is WorkspaceDeletionResult.DiskFailure)
        assertTrue((result as WorkspaceDeletionResult.DiskFailure).reason.isNotBlank())
        // The injected mid-walk shape: part of the tree is genuinely gone…
        assertFalse("bin must be deleted by the injected prefix", File(rootfs, "bin").exists())
        assertFalse("usr must be deleted by the injected prefix", File(rootfs, "usr").exists())
        // …and the surviving part is really still there.
        assertTrue("the kept subtree must survive the injected failure", keptRelease.exists())
        assertTrue(
            "the workspace tree must survive a partial disk failure",
            fileManager.getWorkspaceBasePath(id).exists()
        )
        assertNotNull("the row must be kept for a retry", repo.getById(id))
        assertTrue("the row must not be deleted on a partial disk failure", dao.deleted.isEmpty())
    }
}
