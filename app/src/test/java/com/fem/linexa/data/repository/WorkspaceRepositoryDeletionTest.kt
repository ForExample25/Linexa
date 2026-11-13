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
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * OPT-REL-001 / OPT-REL-006 — the deletion contract at the repository seam.
 *
 * These use a real [WorkspaceFileManager] over a temporary `filesDir`, so the
 * disk assertions are about real filesystem behaviour (including POSIX modes),
 * not a fake that "deletes" by flipping a map.
 */
class WorkspaceRepositoryDeletionTest {

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
        repo = WorkspaceRepositoryImpl(
            dao,
            fileManager,
            WorkspaceSecretAttributes(SecretAttributeCodec(SecretAttributeCodecTest.SoftwareAesGcmCipher()))
        )
    }

    @After
    fun restorePermissions() {
        // A deliberately read-only parent must never make the temp-folder rule
        // fail to clean up after the test.
        File(filesDir, "workspaces").setWritable(true, true)
        File(filesDir, "workspaces").setExecutable(true, true)
        File(filesDir, "workspaces").setReadable(true, true)
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
    fun readOnlyDirectoriesDoNotStopDeletion() = runBlocking {
        val id = "ws-readonly"
        val rootfs = seedWorkspace(id)
        repo.insert(row(id))

        // The exact shape an Arch/Fedora rootfs leaves behind: a 0555 directory
        // with a file inside. `File.deleteRecursively()` fails here.
        val readOnly = File(rootfs, "usr/lib/readonly")
        assertTrue(readOnly.mkdirs())
        val nested = File(readOnly, "payload.txt").apply { writeText("keep me") }
        assumePosix()
        Files.setPosixFilePermissions(
            readOnly.toPath(),
            PosixFilePermissions.fromString("r-xr-xr-x")
        )
        assertFalse("fixture must really be read-only", readOnly.canWrite())

        val result = repo.deleteWorkspace(id)

        assertEquals(WorkspaceDeletionResult.Deleted, result)
        assertFalse("the whole workspace tree must be gone", fileManager.getWorkspaceBasePath(id).exists())
        assertFalse(nested.exists())
        assertNull(repo.getById(id))
        assertEquals(listOf(id), dao.deleted)
    }

    @Test
    fun aDiskFailureKeepsTheRowSoTheUserCanRetry() = runBlocking {
        val id = "ws-disk-failure"
        seedWorkspace(id)
        repo.insert(row(id))

        // Unlinking the workspace base itself needs write permission on its
        // parent. The helper widens directories *inside* the tree, never the
        // parent, so this is a faithful simulation of a deletion that cannot
        // complete.
        val parent = File(filesDir, "workspaces")
        assumePosix()
        Files.setPosixFilePermissions(
            parent.toPath(),
            PosixFilePermissions.fromString("r-xr-xr-x")
        )
        assertFalse("fixture parent must really be read-only", parent.canWrite())

        val result = repo.deleteWorkspace(id)

        assertTrue("expected DiskFailure, got $result", result is WorkspaceDeletionResult.DiskFailure)
        assertTrue((result as WorkspaceDeletionResult.DiskFailure).reason.isNotBlank())
        assertTrue("the workspace tree must survive", fileManager.getWorkspaceBasePath(id).exists())
        assertNotNull("the row must be kept for a retry", repo.getById(id))
        assertTrue(dao.deleted.isEmpty())
    }

    @Test
    fun unknownWorkspaceIsReportedAsNotFound() = runBlocking {
        assertEquals(WorkspaceDeletionResult.NotFound, repo.deleteWorkspace("never-existed"))
        assertTrue(dao.deleted.isEmpty())
    }

    @Test
    fun anOrphanDirectoryFromAnOlderVersionIsReclaimed() = runBlocking {
        // The old row-first implementation could leave a directory behind with
        // no row. The cleanup path must still remove it.
        val id = "ws-orphan"
        seedWorkspace(id)

        val result = repo.deleteWorkspace(id)

        assertEquals(WorkspaceDeletionResult.Deleted, result)
        assertFalse(fileManager.getWorkspaceBasePath(id).exists())
    }

    @Test
    fun theLegacyDeleteByIdAlsoUsesTheSafePath() = runBlocking {
        val id = "ws-legacy"
        val rootfs = seedWorkspace(id)
        repo.insert(row(id))
        val readOnly = File(rootfs, "usr/readonly")
        assertTrue(readOnly.mkdirs())
        File(readOnly, "file").writeText("x")
        assumePosix()
        Files.setPosixFilePermissions(
            readOnly.toPath(),
            PosixFilePermissions.fromString("r-xr-xr-x")
        )

        @Suppress("DEPRECATION")
        repo.deleteById(id)

        assertFalse(fileManager.getWorkspaceBasePath(id).exists())
        assertNull(repo.getById(id))
    }

    private fun assumePosix() {
        assumeTrue(
            "POSIX permissions are required for this test",
            File(filesDir, "workspaces").toPath().fileSystem.supportedFileAttributeViews().contains("posix")
        )
    }
}
