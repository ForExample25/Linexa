package com.fem.linexa.domain.security

import java.util.Base64

/**
 * Authenticated encryption of a small secret, with the key held elsewhere.
 *
 * The contract is deliberately tiny so the platform implementation (the Android
 * Keystore, `data/security/AndroidKeystoreSecretCipher.kt`) and a JVM test
 * implementation are interchangeable. [encrypt] returns an opaque blob that carries
 * whatever [decrypt] needs (IV and authentication tag). [aad] is authenticated but not
 * stored: decrypting with a different [aad] must fail.
 *
 * Implementations throw on any failure; the codec turns that into a typed result.
 */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** Result of [SecretAttributeCodec.decode]. */
sealed interface SecretDecode {
    /** A well-formed envelope that decrypted. */
    data class Decrypted(val value: String) : SecretDecode

    /** A value that was never encrypted (written before encryption existed). */
    data class Legacy(val value: String) : SecretDecode

    /** An envelope that cannot be read: key gone, tampered, truncated, or an unknown version. */
    object Unreadable : SecretDecode
}

/** Result of [SecretAttributeCodec.encode]. */
sealed interface SecretEncode {
    data class Encoded(val stored: String) : SecretEncode

    /** The cipher failed. The caller must NOT fall back to storing the plaintext. */
    object Failed : SecretEncode
}

/**
 * Turns a secret attribute value into its stored form and back.
 *
 * Stored form: `enc:v1:` + Base64(`blob`). The empty string is never encrypted — it
 * means "no secret", and keeping it literal keeps that meaning readable without a key.
 *
 * Each ciphertext is bound to `workspaceId|attributeKey` through the cipher's AAD, so a
 * value copied into another workspace's row (or another attribute) does not decrypt.
 *
 * Pure Kotlin: no `android.*` imports, so every branch is JVM-testable.
 */
class SecretAttributeCodec(private val cipher: SecretCipher) {

    /** `true` for any value this codec produced, in any version. */
    fun isEnvelope(stored: String): Boolean = stored.startsWith(FAMILY_PREFIX)

    fun encode(workspaceId: String, attributeKey: String, plaintext: String): SecretEncode {
        if (plaintext.isEmpty()) return SecretEncode.Encoded("")
        return try {
            val blob = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad(workspaceId, attributeKey))
            SecretEncode.Encoded(V1_PREFIX + Base64.getEncoder().encodeToString(blob))
        } catch (_: Exception) {
            SecretEncode.Failed
        }
    }

    fun decode(workspaceId: String, attributeKey: String, stored: String): SecretDecode {
        if (stored.isEmpty()) return SecretDecode.Decrypted("")
        if (!isEnvelope(stored)) return SecretDecode.Legacy(stored)
        // An envelope of a version this build does not know is unreadable, not legacy
        // plaintext: treating it as plaintext would hand ciphertext to the VNC server.
        if (!stored.startsWith(V1_PREFIX)) return SecretDecode.Unreadable
        return try {
            val blob = Base64.getDecoder().decode(stored.substring(V1_PREFIX.length))
            val plain = cipher.decrypt(blob, aad(workspaceId, attributeKey))
            SecretDecode.Decrypted(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            SecretDecode.Unreadable
        }
    }

    private fun aad(workspaceId: String, attributeKey: String): ByteArray =
        "$workspaceId|$attributeKey".toByteArray(Charsets.UTF_8)

    companion object {
        /** Matches every envelope version, so an unknown future one is recognised as ours. */
        const val FAMILY_PREFIX = "enc:v"
        const val V1_PREFIX = "enc:v1:"
    }
}
