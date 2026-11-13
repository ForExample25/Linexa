package com.fem.linexa.data.repository

import com.fem.linexa.domain.security.SecretAttributeCodec
import com.fem.linexa.domain.security.SecretCipher
import com.fem.linexa.domain.security.SecretAttributeCodecTest
import com.fem.linexa.service.runtime.features.FeatureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSecretAttributesTest {

    private val pwKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD
    private val markerKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD_UNREADABLE

    private class Switchable : SecretCipher {
        val inner = SecretAttributeCodecTest.SoftwareAesGcmCipher()
        var down = false
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            check(!down) { "down" }; return inner.encrypt(plaintext, aad)
        }
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            check(!down) { "down" }; return inner.decrypt(blob, aad)
        }
    }

    private val cipher = Switchable()
    private val secrets = WorkspaceSecretAttributes(SecretAttributeCodec(cipher))

    private fun protect(plain: String, existing: Map<String, String>? = null, marker: String = "false") =
        secrets.protect("ws1", mapOf(pwKey to plain, markerKey to marker, "other" to "x"), existing)

    @Test
    fun protectEncryptsTheSecretAndLeavesOtherKeysAlone() {
        val raw = protect("pw12345678")
        assertTrue(raw.getValue(pwKey).startsWith("enc:v1:"))
        assertEquals("x", raw["other"])
        assertEquals("false", raw[markerKey])
    }

    @Test
    fun protectIgnoresMapsWithoutTheSecretKey() {
        val attrs = mapOf("other" to "x")
        assertEquals(attrs, secrets.protect("ws1", attrs, null))
    }

    @Test
    fun revealRoundTripsProtect() {
        val raw = protect("pw12345678")
        assertEquals("pw12345678", secrets.reveal("ws1", raw)[pwKey])
        assertEquals("false", secrets.reveal("ws1", raw)[markerKey])
    }

    @Test
    fun anUnchangedSecretKeepsItsExistingCiphertext() {
        val first = protect("pw12345678")
        val second = protect("pw12345678", existing = first)
        assertEquals(first[pwKey], second[pwKey])
    }

    @Test
    fun aChangedSecretIsReEncrypted() {
        val first = protect("pw12345678")
        val second = protect("different1", existing = first)
        assertNotEquals(first[pwKey], second[pwKey])
        assertEquals("different1", secrets.reveal("ws1", second)[pwKey])
    }

    @Test
    fun clearingThePasswordWritesEmpty() {
        val first = protect("pw12345678")
        val cleared = protect("", existing = first, marker = "false")
        assertEquals("", cleared[pwKey])
    }

    @Test
    fun revealOfAnUnreadableValueIsEmptyPlusTheMarker() {
        val raw = protect("pw12345678")
        val other = WorkspaceSecretAttributes(SecretAttributeCodec(SecretAttributeCodecTest.SoftwareAesGcmCipher()))
        val revealed = other.reveal("ws1", raw)
        assertEquals("", revealed[pwKey])
        assertEquals("true", revealed[markerKey])
    }

    @Test
    fun theUnreadablePlaceholderNeverOverwritesTheStoredCiphertext() {
        val stored = protect("pw12345678")
        // What a caller writes back after reading an unreadable row.
        val written = protect("", existing = stored, marker = "true")
        assertEquals(stored[pwKey], written[pwKey])
        assertEquals("true", written[markerKey])
    }

    @Test
    fun aNewSecretThatCannotBeEncryptedIsNeverStoredInTheClear() {
        cipher.down = true
        val raw = protect("pw12345678")
        assertEquals("", raw[pwKey])
        assertEquals("true", raw[markerKey])
        assertFalse(raw.values.any { it.contains("pw12345678") })
    }

    @Test
    fun anUnchangedSecretSurvivesAnUnrelatedWriteWhileTheCipherIsDown() {
        val stored = protect("pw12345678")
        cipher.down = true
        val raw = protect("pw12345678", existing = stored)
        assertEquals(stored[pwKey], raw[pwKey])
    }

    @Test
    fun aChangedSecretThatCannotBeEncryptedDoesNotDestroyTheStoredCiphertext() {
        val stored = protect("pw12345678")
        cipher.down = true
        val raw = protect("brandNewPw", existing = stored)
        assertEquals(stored[pwKey], raw[pwKey])
        assertEquals("true", raw[markerKey])
    }

    @Test
    fun aLegacyPlaintextValueIsEncryptedOnWrite() {
        val legacy = mapOf(pwKey to "legacyPw1")
        val raw = secrets.protect("ws1", mapOf(pwKey to "legacyPw1"), legacy)
        assertTrue(raw.getValue(pwKey).startsWith("enc:v1:"))
    }

    @Test
    fun migrateEncryptsLegacyAndIsIdempotent() {
        val migrated = secrets.migrate("ws1", mapOf(pwKey to "legacyPw1", "other" to "x"))!!
        assertTrue(migrated.getValue(pwKey).startsWith("enc:v1:"))
        assertEquals("x", migrated["other"])
        assertNull(secrets.migrate("ws1", migrated))
    }

    @Test
    fun migrateSkipsEmptyAndAbsentSecrets() {
        assertNull(secrets.migrate("ws1", mapOf(pwKey to "")))
        assertNull(secrets.migrate("ws1", mapOf("other" to "x")))
    }

    @Test
    fun migrateLeavesTheLegacyValueInPlaceWhenTheCipherIsDown() {
        cipher.down = true
        assertNull(secrets.migrate("ws1", mapOf(pwKey to "legacyPw1")))
    }

    @Test
    fun theStoredRowIsOnlyNeededWhenThereIsASecretOrAnUnreadablePlaceholder() {
        assertTrue(secrets.needsStoredRow(mapOf(pwKey to "x")))
        assertTrue(secrets.needsStoredRow(mapOf(pwKey to "", markerKey to "true")))
        assertFalse(secrets.needsStoredRow(mapOf(pwKey to "", markerKey to "false")))
        assertFalse(secrets.needsStoredRow(mapOf(pwKey to "")))
        assertFalse(secrets.needsStoredRow(emptyMap()))
    }
}
