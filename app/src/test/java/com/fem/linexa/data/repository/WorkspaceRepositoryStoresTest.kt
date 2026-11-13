package com.fem.linexa.data.repository

import com.fem.linexa.data.room.WorkspaceDao
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 9C (OPT-ARCH-003) — the repository against FAKES for both extracted
 * stores (the brief's acceptance: "Repository tests pass with fakes for the
 * disk/secret stores").
 *
 * The other repository tests (Deletion/Concurrency/Secrets) exercise the
 * REAL `WorkspaceFileManager` + `WorkspaceSecretAttributes` on temp
 * filesystems; this one pins the seams themselves:
 *
 *  - insert: the disk store's `ensureWorkspaceDirs` runs BEFORE the row is
 *    persisted (the layout must exist before anything can reference it);
 *  - deleteWorkspace: disk-first/row-second through the store, with the
 *    size-cache invalidation only on success;
 *  - DiskFailure keeps the row (Retry possible) and never invalidates;
 *  - reads are revealed and writes are sealed through the secret store —
 *    `mutate`'s transform sees plaintext, the persisted row is sealed, and
 *    the row the caller gets back is revealed again.
 *
 * The shared `ops` log is the whole assertion mechanism for ordering: the
 * fakes append their call names and the tests assert the sequence.
 */
class WorkspaceRepositoryStoresTest {

    private val ops = mutableListOf<String>()
    private val tempBase: File = Files.createTempDirectory("stores-9c").toFile()
    private val basePath: File = File(tempBase, "w1")

    private val dao = FakeDao(ops)
    private val disk = FakeDiskStore(ops, basePath)
    private val secrets = FakeSecretStore(ops)
    private val repo = WorkspaceRepositoryImpl(dao, disk, secrets)

    @After
    fun cleanUp() {
        tempBase.deleteRecursively()
    }

    private fun row(id: String = "w1") = WorkspaceMetadata(
        id = id,
        status = WorkspaceStatus.Ready,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = id,
        avatarLetter = "D",
        avatarColorHex = "#112233",
        rootfsPath = "diagnostic-only",
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap(),
        attributes = emptyMap()
    )

    @Test
    fun `insert creates the workspace dirs through the disk store before the row write`() = runBlocking {
        repo.insert(row())

        assertTrue(
            "the layout must exist before the row that references it is persisted",
            ops.indexOf("disk.ensureDirs") < ops.indexOf("dao.insert")
        )
        assertTrue("the row must be sealed on the way in", ops.contains("secrets.protect"))
        assertTrue(
            "the persisted row is the sealed form",
            dao.rows["w1"]!!.attributes.containsKey("sealed")
        )
    }

    @Test
    fun `deleteWorkspace is disk-first row-second and invalidates the size cache on success`() = runBlocking {
        basePath.mkdirs()
        dao.rows["w1"] = row()
        disk.treeDeleted = true

        val result = repo.deleteWorkspace("w1")

        assertEquals(WorkspaceDeletionResult.Deleted, result)
        assertTrue(
            "the tree must be removed before the row is deleted",
            ops.indexOf("disk.deleteTree") < ops.indexOf("dao.delete")
        )
        assertTrue("the size cache must be dropped after a successful delete", ops.contains("disk.invalidateSize"))
        assertNull(dao.rows["w1"])
    }

    @Test
    fun `deleteWorkspace keeps the row when the tree survives (DiskFailure)`() = runBlocking {
        basePath.mkdirs()
        dao.rows["w1"] = row()
        disk.treeDeleted = false

        val result = repo.deleteWorkspace("w1")

        assertTrue("a surviving tree is a DiskFailure", result is WorkspaceDeletionResult.DiskFailure)
        assertNotNull("the row must be kept so the user can retry", dao.rows["w1"])
        assertFalse("no size invalidation on failure", ops.contains("disk.invalidateSize"))
        assertFalse("no row deletion on failure", ops.contains("dao.delete"))
    }

    @Test
    fun `mutate reads revealed and writes sealed through the secret store`() = runBlocking {
        dao.rows["w1"] = row().copy(attributes = mapOf("sealed" to "true"))

        val result = repo.mutate("w1") { current ->
            assertFalse(
                "the transform must never see the sealed form",
                current.attributes.containsKey("sealed")
            )
            current.copy(attributes = current.attributes + ("plain" to "value"))
        }

        assertTrue("the read went through the secret store", ops.contains("secrets.reveal"))
        assertTrue("the write went through the secret store", ops.contains("secrets.protect"))
        // The row the caller gets back is the revealed form again.
        assertFalse(result!!.attributes.containsKey("sealed"))
        // The persisted row is the sealed form, still carrying the transform's data.
        assertTrue(dao.rows["w1"]!!.attributes.containsKey("sealed"))
        assertEquals("value", dao.rows["w1"]!!.attributes["plain"])
    }

    private class FakeDao(private val ops: MutableList<String>) : WorkspaceDao {
        val rows = LinkedHashMap<String, WorkspaceMetadata>()

        override fun observeAll(): Flow<List<WorkspaceMetadata>> = flowOf(rows.values.toList())
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = flowOf(rows[id])
        override suspend fun getById(id: String): WorkspaceMetadata? = rows[id]

        override suspend fun insert(workspace: WorkspaceMetadata) {
            ops += "dao.insert"
            rows[workspace.id] = workspace
        }

        override suspend fun update(workspace: WorkspaceMetadata) {
            ops += "dao.update"
            rows[workspace.id] = workspace
        }

        override suspend fun deleteById(id: String) {
            ops += "dao.delete"
            rows.remove(id)
        }

        override suspend fun updateStatus(id: String, status: String) {
            ops += "dao.updateStatus"
        }
    }

    private class FakeDiskStore(
        private val ops: MutableList<String>,
        private val base: File
    ) : WorkspaceDiskStore {
        /** What [deleteWorkspaceTree] reports — `false` simulates a surviving tree. */
        var treeDeleted = true

        override fun ensureWorkspaceDirs(workspaceId: String): File {
            ops += "disk.ensureDirs"
            return File(base, "rootfs")
        }

        override fun getWorkspaceBasePath(workspaceId: String): File = base

        override fun deleteWorkspaceTree(workspaceId: String): Boolean {
            ops += "disk.deleteTree"
            return treeDeleted
        }

        override fun invalidateRootfsSize(workspaceId: String) {
            ops += "disk.invalidateSize"
        }
    }

    private class FakeSecretStore(private val ops: MutableList<String>) : WorkspaceSecretStore {
        /**
         * The fake's marker: reveal strips `sealed`, protect adds it. Needs the
         * stored row whenever the incoming map carries data the transform added,
         * mirroring the real contract's "has a secret to reconcile" shape.
         */
        override fun needsStoredRow(attributes: Map<String, String>): Boolean =
            attributes.containsKey("plain") || attributes.containsKey("sealed")

        override fun protect(
            workspaceId: String,
            incoming: Map<String, String>,
            existingRaw: Map<String, String>?
        ): Map<String, String> {
            ops += "secrets.protect"
            return incoming + ("sealed" to "true")
        }

        override fun reveal(workspaceId: String, stored: Map<String, String>): Map<String, String> {
            ops += "secrets.reveal"
            return stored - "sealed"
        }

        override fun migrate(workspaceId: String, stored: Map<String, String>): Map<String, String>? = null
    }
}
