package com.fem.linexa.domain.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecretAttributeCodec] against a real AES-256-GCM [SecretCipher] with a software key.
 * The Android Keystore implementation differs only in where the key lives, so this
 * exercises the envelope, AAD binding, legacy handling and failure mapping for real.
 */
class SecretAttributeCodecTest {

    /** Same blob layout as the Keystore cipher: IV(12) || ciphertext || tag. */
    internal class SoftwareAesGcmCipher(
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    ) : SecretCipher {
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plaintext)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            require(blob.size > 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private val codec = SecretAttributeCodec(SoftwareAesGcmCipher())
    private val key = "feature.desktop.vnc.password"

    private fun stored(ws: String, plain: String): String =
        (codec.encode(ws, key, plain) as SecretEncode.Encoded).stored

    @Test
    fun roundTripsAndNeverStoresThePlaintext() {
        val s = stored("ws1", "s3cretPw")
        assertTrue(s.startsWith("enc:v1:"))
        assertFalse(s.contains("s3cretPw"))
        assertEquals(SecretDecode.Decrypted("s3cretPw"), codec.decode("ws1", key, s))
    }

    @Test
    fun roundTripsNonAsciiAndPunctuation() {
        val pw = "pä:ß'\"\$ w0rd✓"
        assertEquals(SecretDecode.Decrypted(pw), codec.decode("ws1", key, stored("ws1", pw)))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        assertNotEquals(stored("ws1", "same"), stored("ws1", "same"))
    }

    @Test
    fun theEmptySecretIsStoredLiterallyAndReadsBackEmpty() {
        assertEquals(SecretEncode.Encoded(""), codec.encode("ws1", key, ""))
        assertEquals(SecretDecode.Decrypted(""), codec.decode("ws1", key, ""))
    }

    @Test
    fun aCiphertextDoesNotDecryptForAnotherWorkspaceOrAttribute() {
        val s = stored("ws1", "pw12345678")
        assertEquals(SecretDecode.Unreadable, codec.decode("ws2", key, s))
        assertEquals(SecretDecode.Unreadable, codec.decode("ws1", "feature.other", s))
    }

    @Test
    fun aTamperedCiphertextIsUnreadable() {
        val s = stored("ws1", "pw12345678")
        val last = s.last()
        val flipped = s.dropLast(1) + (if (last == 'A') 'B' else 'A')
        assertEquals(SecretDecode.Unreadable, codec.decode("ws1", key, flipped))
    }

    @Test
    fun truncatedOrMalformedEnvelopesAreUnreadable() {
        for (bad in listOf("enc:v1:", "enc:v1:AAAA", "enc:v1:!!!not-base64!!!", "enc:v1:" + "A".repeat(8))) {
            assertEquals(bad, SecretDecode.Unreadable, codec.decode("ws1", key, bad))
        }
    }

    @Test
    fun anUnknownEnvelopeVersionIsUnreadableNeverLegacyPlaintext() {
        assertEquals(SecretDecode.Unreadable, codec.decode("ws1", key, "enc:v2:whatever"))
        assertTrue(codec.isEnvelope("enc:v2:whatever"))
    }

    @Test
    fun aValueWithoutTheEnvelopeIsLegacyPlaintext() {
        assertEquals(SecretDecode.Legacy("hunter22"), codec.decode("ws1", key, "hunter22"))
        assertFalse(codec.isEnvelope("hunter22"))
    }

    @Test
    fun aValueEncryptedWithAnotherKeyIsUnreadable() {
        val other = SecretAttributeCodec(SoftwareAesGcmCipher())
        assertEquals(SecretDecode.Unreadable, other.decode("ws1", key, stored("ws1", "pw12345678")))
    }

    @Test
    fun aThrowingCipherYieldsFailedNotPlaintext() {
        val broken = SecretAttributeCodec(object : SecretCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = error("keystore down")
            override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = error("keystore down")
        })
        assertEquals(SecretEncode.Failed, broken.encode("ws1", key, "pw12345678"))
        assertEquals(SecretDecode.Unreadable, broken.decode("ws1", key, stored("ws1", "pw12345678")))
    }
}
