package com.fem.linexa.service.installer

import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.runtime.WorkspaceFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

/**
 * Phase 9B (OPT-ARCH-002) — the stale-`rootfsPath` startup repair.
 *
 * The workspace ID is the single rootfs identity; the persisted field is
 * diagnostic (no production launch path reads it). This test pins the one
 * thing that keeps a diagnostic field honest: the startup backfill rewrites
 * a row whose stored path disagrees with the path the ID derives to — and
 * leaves every agreeing row, and every non-Ready row, untouched.
 *
 * The repository double is a hand-rolled recording fake (the house pattern —
 * the same choice as `InstallCoordinatorTest`/`InstallRecoveryManagerTest`),
 * NOT a Mockito mock: the interface's default `mutate` reads through
 * [getById] and writes through [update], so the fake's plain row field
 * records the repair without any matcher machinery, and the assertion reads
 * the persisted row — the observable that matters — instead of a call log.
 *
 * The attribute backfill probes run against a REAL empty temp directory so
 * the desktop/distro probes answer deterministically without a device: no
 * package manager → desktop capability `false`; no os-release → no distro
 * id. The pre-existing desktop backfill is exercised end-to-end (missing
 * attr → `false` written) to prove the repair did not disturb it.
 */
class WorkspaceMetadataBackfillManagerTest {

    private val fileManager = mock(WorkspaceFileManager::class.java)
    private val repository = RecordingRepository()
    private val subject = WorkspaceMetadataBackfillManager(fileManager, repository)

    private val tempBase: File = Files.createTempDirectory("backfill-9b").toFile()
    private val derivedRootfs: File = File(tempBase, "w1/rootfs").apply { mkdirs() }

    @Before
    fun setUp() {
        Mockito.`when`(fileManager.listWorkspaceIds()).thenReturn(listOf("w1"))
        Mockito.`when`(fileManager.getRootfsPath("w1")).thenReturn(derivedRootfs)
    }

    /**
     * Records every write the backfill can make, keeping [mutate]'s interface
     * default (read → transform → update) exactly as a production caller
     * observes it. `updates` counts whole-row writes; the rootfs repair is
     * visible as the row's persisted `rootfsPath` after the pass.
     */
    private class RecordingRepository : WorkspaceRepository {
        var row: WorkspaceMetadata? = null
        var updates = 0
        val attributeUpdates = mutableListOf<Pair<String, Map<String, String>>>()

        override fun observeAll(): Flow<List<WorkspaceMetadata>> = flowOf(listOfNotNull(row))
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = flowOf(row)
        override suspend fun getById(id: String): WorkspaceMetadata? = row
        override suspend fun insert(metadata: WorkspaceMetadata) {
            row = metadata
            updates++
        }

        override suspend fun update(metadata: WorkspaceMetadata) {
            row = metadata
            updates++
        }

        override suspend fun deleteById(id: String) {
            row = null
        }

        override suspend fun updateStatus(id: String, status: WorkspaceStatus) {
            row = row?.copy(status = status)
        }

        override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) {
            row = row?.copy(featureToggles = toggles)
        }

        override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
            attributeUpdates += id to attributes
            val current = row ?: return
            row = current.copy(attributes = current.attributes + attributes)
        }
    }

    /** Same row shape the repository tests use (`WorkspaceRepositoryDeletionTest.row`). */
    private fun metadata(
        rootfsPath: String,
        attributes: Map<String, String> = emptyMap(),
        status: WorkspaceStatus = WorkspaceStatus.Ready
    ) = WorkspaceMetadata(
        id = "w1",
        status = status,
        distro = com.fem.linexa.domain.workspace.DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = "w1",
        avatarLetter = "D",
        avatarColorHex = "#112233",
        rootfsPath = rootfsPath,
        createdAt = 0L,
        runtimeConfig = com.fem.linexa.domain.workspace.RuntimeConfig(),
        terminalSettings = com.fem.linexa.domain.workspace.TerminalSettings(),
        featureToggles = emptyMap(),
        attributes = attributes
    )

    @Test
    fun `the stale persisted rootfsPath is repaired to the derived authority`() = runBlocking {
        // Both capability attributes present AND agreeing with the (empty)
        // rootfs, so the only work this pass has is the repair itself: the
        // distro attribute is skipped when present, and the desktop refresh
        // branch finds `false` agrees with a package-manager-less rootfs.
        repository.row = metadata(
            rootfsPath = "/stale/wrong/path",
            attributes = mapOf(
                DownloadService.ATTR_DISTRO_ID to "debian",
                WorkspaceCapabilities.ATTR_DESKTOP to "false"
            )
        )

        subject.backfillReadyWorkspaces()

        assertEquals("exactly one row write: the repair", 1, repository.updates)
        assertEquals(derivedRootfs.absolutePath, repository.row?.rootfsPath)
        assertTrue("the repair must not ride on an attribute write", repository.attributeUpdates.isEmpty())
    }

    @Test
    fun `an agreeing rootfsPath is not rewritten`() = runBlocking {
        repository.row = metadata(
            rootfsPath = derivedRootfs.absolutePath,
            attributes = mapOf(
                DownloadService.ATTR_DISTRO_ID to "debian",
                WorkspaceCapabilities.ATTR_DESKTOP to "false"
            )
        )

        subject.backfillReadyWorkspaces()

        assertEquals("no row write at all", 0, repository.updates)
        assertEquals(derivedRootfs.absolutePath, repository.row?.rootfsPath)
        assertTrue(repository.attributeUpdates.isEmpty())
    }

    @Test
    fun `a workspace that is not Ready is skipped entirely`() = runBlocking {
        repository.row = metadata(rootfsPath = "/stale/wrong/path", status = WorkspaceStatus.Broken("test"))

        subject.backfillReadyWorkspaces()

        assertEquals("no row write", 0, repository.updates)
        assertTrue("no attribute write", repository.attributeUpdates.isEmpty())
    }

    @Test
    fun `the pre-existing attribute backfill still runs beside the repair`() = runBlocking {
        // No attributes at all: the desktop capability probe runs against the
        // real (empty) rootfs directory and answers false — the backfill
        // writes that derivation, exactly as before Phase 9B. (The distro
        // probe finds no os-release in the empty rootfs and writes nothing.)
        repository.row = metadata(rootfsPath = derivedRootfs.absolutePath)

        subject.backfillReadyWorkspaces()

        assertEquals("no rootfs-path rewrite", 0, repository.updates)
        assertEquals(
            listOf("w1" to mapOf(WorkspaceCapabilities.ATTR_DESKTOP to "false")),
            repository.attributeUpdates
        )
        assertTrue("the empty rootfs dir was the probe input", derivedRootfs.isDirectory)
        assertFalse(
            "the fixture must have no package manager for the probe to answer false",
            File(derivedRootfs, "usr/bin/apt").isFile
        )
    }
}
