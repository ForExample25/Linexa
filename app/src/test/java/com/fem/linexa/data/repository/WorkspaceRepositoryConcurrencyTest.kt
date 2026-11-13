package com.fem.linexa.data.repository

import com.fem.linexa.data.room.WorkspaceDao
import com.fem.linexa.domain.security.SecretAttributeCodec
import com.fem.linexa.domain.security.SecretAttributeCodecTest
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.features.FeatureSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Phase 05 (OPT-DATA-001/002): the row mutations are serialized AND their
 * read-modify-writes are atomic.
 *
 * The interleaving that used to lose updates is reproduced deterministically
 * with a gate in the fake DAO's [WorkspaceDao.getById] — the FIRST read parks
 * mid-critical-section (the row mutex is held), the competing writer runs (and,
 * on the fixed code, blocks on the row mutex), then the gate opens and the
 * parked mutation writes. On the pre-Phase-05 code — a caller-side read +
 * whole-row write with no shared lock — the competing writer's change was
 * silently reverted by the parked write; on the fixed code both survive.
 *
 * `runTest`'s single-thread scheduler makes this deterministic: the competing
 * writer is given a few `yield()`s to reach the mutex before the gate opens.
 */
class WorkspaceRepositoryConcurrencyTest {

    /**
     * Same shape as the secrets test's fake: keeps the RAW STORED rows so
     * assertions can look at the persisted (ciphertext) form.
     *
     * The gate: when [holdUntil] is set, the first [getById] signals
     * [firstReadGate] and parks until it completes — simulating another
     * writer running between one mutation's read and its write.
     */
    private class FakeDao : WorkspaceDao {
        private val json = Json { ignoreUnknownKeys = true }
        val rows = MutableStateFlow<Map<String, WorkspaceMetadata>>(emptyMap())
        var updates = 0

        var holdUntil: CompletableDeferred<Unit>? = null
        var firstReadGate: CompletableDeferred<Unit>? = null
        private var reads = 0

        override fun observeAll(): Flow<List<WorkspaceMetadata>> = rows.map { it.values.toList() }
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = rows.map { it[id] }
        override suspend fun getById(id: String): WorkspaceMetadata? {
            reads++
            if (reads == 1) {
                firstReadGate?.complete(Unit)
                holdUntil?.await()
            }
            return rows.value[id]
        }
        override suspend fun insert(workspace: WorkspaceMetadata) {
            rows.value = rows.value + (workspace.id to workspace)
        }
        override suspend fun update(workspace: WorkspaceMetadata) {
            updates++; rows.value = rows.value + (workspace.id to workspace)
        }
        override suspend fun deleteById(id: String) { rows.value = rows.value - id }
        override suspend fun updateStatus(id: String, status: String) {
            val current = rows.value[id] ?: return
            rows.value = rows.value + (id to current.copy(status = json.decodeFromString<WorkspaceStatus>(status)))
        }
    }

    private val dao = FakeDao()
    private val pwKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD

    private fun repo() = WorkspaceRepositoryImpl(
        dao,
        Mockito.mock(WorkspaceFileManager::class.java),
        WorkspaceSecretAttributes(SecretAttributeCodec(SecretAttributeCodecTest.SoftwareAesGcmCipher()))
    )

    private fun row(id: String, attrs: Map<String, String> = emptyMap()) = WorkspaceMetadata(
        id = id,
        status = WorkspaceStatus.Ready,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = id,
        avatarLetter = "D",
        avatarColorHex = "#112233",
        rootfsPath = "/tmp/$id",
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap(),
        attributes = attrs
    )

    @Test
    fun aConcurrentStatusUpdateAndRenameBothSurvive() = runTest {
        val r = repo()
        r.insert(row("w1"))
        val renameRead = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        dao.firstReadGate = renameRead
        dao.holdUntil = releaseRename

        val rename = launch { r.mutate("w1") { it.copy(displayName = "renamed") } }
        renameRead.await() // the rename has READ the row (mutex held) and parks

        val status = async { r.updateStatus("w1", WorkspaceStatus.Ready) }
        repeat(5) { yield() } // let the status write reach the row mutex
        releaseRename.complete(Unit)
        rename.join()
        status.await()

        val final = dao.rows.value.getValue("w1")
        assertEquals("renamed", final.displayName)
        assertEquals(WorkspaceStatus.Ready, final.status)
        assertEquals("renamed", r.getById("w1")!!.displayName)
    }

    @Test
    fun twoRapidFeatureTogglesBothPersist() = runTest {
        val r = repo()
        r.insert(row("w1"))
        val firstRead = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        dao.firstReadGate = firstRead
        dao.holdUntil = releaseFirst

        // Toggle A parks mid-critical-section (after its read, before its
        // write) so toggle B must contend for the mutex instead of racing.
        val toggleA = launch {
            r.setFeatureToggle("w1", "feat.a", true)
        }
        firstRead.await()

        val toggleB = async { r.setFeatureToggle("w1", "feat.b", true) }
        repeat(5) { yield() } // let B reach the row mutex (it must WAIT)
        releaseFirst.complete(Unit)
        toggleA.join()
        toggleB.await()

        assertEquals(
            mapOf("feat.a" to true, "feat.b" to true),
            r.getById("w1")!!.featureToggles
        )
    }

    @Test
    fun aSequentialSetFeatureToggleReplacesNothing() = runTest {
        val r = repo()
        r.insert(row("w1"))
        r.setFeatureToggle("w1", "feat.a", true)
        r.setFeatureToggle("w1", "feat.b", false)
        assertEquals(
            mapOf("feat.a" to true, "feat.b" to false),
            r.getById("w1")!!.featureToggles
        )
    }

    @Test
    fun anAttributeMergeAndAWholeRowMutationBothSurvive() = runTest {
        val r = repo()
        r.insert(row("w1"))
        val renameRead = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        dao.firstReadGate = renameRead
        dao.holdUntil = releaseRename

        val rename = launch { r.mutate("w1") { it.copy(displayName = "renamed") } }
        renameRead.await()

        val merge = async { r.updateAttributes("w1", mapOf("feature.ssh.port" to "2222")) }
        repeat(5) { yield() }
        releaseRename.complete(Unit)
        rename.join()
        merge.await()

        val final = r.getById("w1")!!
        assertEquals("renamed", final.displayName)
        assertEquals("2222", final.attributes["feature.ssh.port"])
    }

    @Test
    fun mutateSeesDecryptedAttributesAndStoresCiphertext() = runTest {
        val r = repo()
        r.insert(row("w1", mapOf(pwKey to "pw12345678")))

        var seenByTransform: Map<String, String>? = null
        r.mutate("w1") { current ->
            seenByTransform = current.attributes
            current.copy(displayName = "renamed")
        }

        assertEquals("pw12345678", seenByTransform?.get(pwKey))
        // The STORED row is ciphertext and the stored value was kept as-is
        // (unchanged secrets are never re-encrypted).
        val stored = dao.rows.value.getValue("w1")
        assertTrue(stored.attributes[pwKey].orEmpty().startsWith("enc:v1:"))
        assertEquals("pw12345678", r.getById("w1")!!.attributes[pwKey])
        assertEquals("renamed", stored.displayName)
    }

    @Test
    fun aThrowingTransformLeavesTheRowUntouched() = runTest {
        val r = repo()
        r.insert(row("w1"))
        val before = dao.rows.value.getValue("w1")
        val updatesBefore = dao.updates

        val outcome = runCatching {
            r.mutate("w1") { error("transform blew up") }
        }

        assertTrue(outcome.isFailure)
        assertEquals(before, dao.rows.value.getValue("w1"))
        assertEquals("no write happened after the failed transform", updatesBefore, dao.updates)
    }

    @Test
    fun mutateOnAMissingWorkspaceReturnsNullWithoutWriting() = runTest {
        val r = repo()
        val updatesBefore = dao.updates
        assertNull(r.mutate("missing") { it.copy(displayName = "x") })
        assertEquals(updatesBefore, dao.updates)
        assertNull(r.setFeatureToggle("missing", "feat.a", true))
        assertEquals(updatesBefore, dao.updates)
    }

    @Test
    fun aParkedMutateWriteCannotRevertALaterStatusWrite() = runTest {
        val r = repo()
        r.insert(row("w1"))
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.firstReadGate = read
        dao.holdUntil = release

        val parked = launch { r.mutate("w1") { it.copy(displayName = "renamed") } }
        read.await()

        // The parked mutate's whole-row write must not interleave with a
        // targeted status write; when the gate opens the status write lands
        // AFTER the row write and both effects are visible.
        val status = async { r.updateStatus("w1", WorkspaceStatus.Broken("why")) }
        repeat(5) { yield() }
        release.complete(Unit)
        parked.join()
        status.await()

        val final = dao.rows.value.getValue("w1")
        assertEquals(WorkspaceStatus.Broken("why"), final.status)
        assertEquals("renamed", final.displayName)
    }
}
