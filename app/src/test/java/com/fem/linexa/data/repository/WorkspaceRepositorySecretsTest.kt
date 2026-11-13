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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * P11 at the seam that matters: what [WorkspaceRepositoryImpl] persists versus what it
 * hands back. The DAO fake keeps the *raw stored* rows, so every assertion about the
 * persisted form looks at what would be on disk.
 */
class WorkspaceRepositorySecretsTest {

    private val pwKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD
    private val markerKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD_UNREADABLE

    private class FakeDao : WorkspaceDao {
        val rows = MutableStateFlow<Map<String, WorkspaceMetadata>>(emptyMap())
        var updates = 0
        override fun observeAll(): Flow<List<WorkspaceMetadata>> = rows.map { it.values.toList() }
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = rows.map { it[id] }
        override suspend fun getById(id: String): WorkspaceMetadata? = rows.value[id]
        override suspend fun insert(workspace: WorkspaceMetadata) { rows.value = rows.value + (workspace.id to workspace) }
        override suspend fun update(workspace: WorkspaceMetadata) {
            updates++; rows.value = rows.value + (workspace.id to workspace)
        }
        override suspend fun deleteById(id: String) { rows.value = rows.value - id }
        override suspend fun updateStatus(id: String, status: String) = Unit
    }

    private val dao = FakeDao()
    private val cipher = SecretAttributeCodecTest.SoftwareAesGcmCipher()
    private fun repo(c: com.fem.linexa.domain.security.SecretCipher = cipher) = WorkspaceRepositoryImpl(
        dao,
        Mockito.mock(WorkspaceFileManager::class.java),
        WorkspaceSecretAttributes(SecretAttributeCodec(c))
    )

    private fun row(id: String, attrs: Map<String, String>) = WorkspaceMetadata(
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

    private fun storedPw(id: String) = dao.rows.value.getValue(id).attributes[pwKey].orEmpty()

    @Test
    fun insertPersistsCiphertextAndReadsReturnPlaintext() = runBlocking {
        val r = repo()
        r.insert(row("w1", mapOf(pwKey to "pw12345678", "other" to "x")))
        assertTrue(storedPw("w1").startsWith("enc:v1:"))
        assertFalse(storedPw("w1").contains("pw12345678"))
        assertEquals("pw12345678", r.getById("w1")!!.attributes[pwKey])
        assertEquals("pw12345678", r.observeById("w1").first()!!.attributes[pwKey])
        assertEquals("pw12345678", r.observeAll().first().single().attributes[pwKey])
        assertEquals("x", dao.rows.value.getValue("w1").attributes["other"])
    }

    @Test
    fun updateAttributesEncryptsTheIncomingSecretAndKeepsOtherKeys() = runBlocking {
        val r = repo()
        r.insert(row("w1", mapOf("source" to "catalog")))
        r.updateAttributes("w1", mapOf(pwKey to "pw12345678", markerKey to "false"))
        assertTrue(storedPw("w1").startsWith("enc:v1:"))
        assertEquals("catalog", dao.rows.value.getValue("w1").attributes["source"])
        assertEquals("pw12345678", r.getById("w1")!!.attributes[pwKey])
    }

    @Test
    fun aLaterUnrelatedMergeDoesNotDoubleEncryptOrAlterTheStoredSecret() = runBlocking {
        val r = repo()
        r.insert(row("w1", emptyMap()))
        r.updateAttributes("w1", mapOf(pwKey to "pw12345678"))
        val before = storedPw("w1")
        r.updateAttributes("w1", mapOf("feature.ssh.port" to "2222"))
        assertEquals(before, storedPw("w1"))
        assertEquals("pw12345678", r.getById("w1")!!.attributes[pwKey])
    }

    @Test
    fun aReadModifyWriteThroughUpdateKeepsTheSecretIntact() = runBlocking {
        val r = repo()
        r.insert(row("w1", mapOf(pwKey to "pw12345678")))
        val read = r.getById("w1")!!
        r.update(read.copy(displayName = "renamed"))
        assertEquals("renamed", dao.rows.value.getValue("w1").displayName)
        assertTrue(storedPw("w1").startsWith("enc:v1:"))
        assertEquals("pw12345678", r.getById("w1")!!.attributes[pwKey])
    }

    @Test
    fun anUnreadableStoredSecretSurfacesAsEmptyPlusMarkerNotAsGarbage() = runBlocking {
        repo().insert(row("w1", mapOf(pwKey to "pw12345678")))
        val otherKey = repo(SecretAttributeCodecTest.SoftwareAesGcmCipher())
        val read = otherKey.getById("w1")!!
        assertEquals("", read.attributes[pwKey])
        assertEquals("true", read.attributes[markerKey])
        val settings = FeatureSettings.from(read.attributes)
        assertEquals("", settings.desktopVncPassword)
        assertTrue(settings.desktopVncPasswordUnreadable)
    }

    @Test
    fun writingBackAnUnreadableRowDoesNotDestroyTheStoredCiphertext() = runBlocking {
        repo().insert(row("w1", mapOf(pwKey to "pw12345678")))
        val before = storedPw("w1")
        val broken = repo(SecretAttributeCodecTest.SoftwareAesGcmCipher())
        val read = broken.getById("w1")!!
        broken.update(read.copy(displayName = "renamed"))
        broken.updateAttributes("w1", FeatureSettings.from(read.attributes).toAttributes())
        assertEquals(before, storedPw("w1"))
        // ...and the original key still opens it.
        assertEquals("pw12345678", repo().getById("w1")!!.attributes[pwKey])
    }

    @Test
    fun settingANewPasswordAfterUnreadableReplacesItAndClearsTheMarker() = runBlocking {
        repo().insert(row("w1", mapOf(pwKey to "pw12345678")))
        val broken = repo(SecretAttributeCodecTest.SoftwareAesGcmCipher())
        val fresh = FeatureSettings.from(broken.getById("w1")!!.attributes)
            .copy(desktopVncPassword = "newPassw0rd", desktopVncPasswordUnreadable = false)
        broken.updateAttributes("w1", fresh.toAttributes())
        val read = broken.getById("w1")!!
        assertEquals("newPassw0rd", read.attributes[pwKey])
        assertEquals("false", read.attributes[markerKey])
    }

    @Test
    fun clearingThePasswordOnPurposeRemovesAuthentication() = runBlocking {
        val r = repo()
        r.insert(row("w1", mapOf(pwKey to "pw12345678")))
        val cleared = FeatureSettings.from(r.getById("w1")!!.attributes).copy(desktopVncPassword = "")
        r.updateAttributes("w1", cleared.toAttributes())
        assertEquals("", storedPw("w1"))
        assertEquals("", r.getById("w1")!!.attributes[pwKey])
    }

    @Test
    fun migrationEncryptsLegacyRowsOnceAndLeavesOthersUntouched() = runBlocking {
        dao.insert(row("legacy", mapOf(pwKey to "legacyPw1", "other" to "x")))
        dao.insert(row("nopw", mapOf("other" to "y")))
        dao.insert(row("empty", mapOf(pwKey to "")))
        val r = repo()
        assertEquals(1, r.migrateLegacySecrets())
        assertTrue(storedPw("legacy").startsWith("enc:v1:"))
        assertEquals("legacyPw1", r.getById("legacy")!!.attributes[pwKey])
        assertEquals("x", dao.rows.value.getValue("legacy").attributes["other"])
        val updatesAfterFirst = dao.updates
        assertEquals(0, r.migrateLegacySecrets())
        assertEquals(updatesAfterFirst, dao.updates)
    }

    @Test
    fun migrationLeavesLegacyPlaintextInPlaceWhenTheCipherFails() = runBlocking {
        dao.insert(row("legacy", mapOf(pwKey to "legacyPw1")))
        val down = object : com.fem.linexa.domain.security.SecretCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = error("down")
            override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = error("down")
        }
        assertEquals(0, repo(down).migrateLegacySecrets())
        assertEquals("legacyPw1", storedPw("legacy"))
    }
}
