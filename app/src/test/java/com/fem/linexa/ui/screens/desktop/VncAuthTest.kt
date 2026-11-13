package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Modes (2026-08) — the [VncAuth] challenge/response, pinned against
 * reference vectors.
 *
 * The vectors below were generated with **two independent DES implementations
 * agreeing** — pycryptodome's DES and OpenSSL's `des-ecb` (legacy provider) —
 * both fed the RFC 6143 §7.2.2 recipe: the password's ISO-8859-1 bytes
 * truncated/padded to 8 with each byte's bits reflected, ECB, no padding,
 * applied to the 16-byte challenge. On-device the same computation runs
 * through the platform JCE (`javax.crypto` "DES"), which is the standard
 * recipe every open-source VNC client uses against real servers.
 *
 * The challenge is the deterministic `0x00..0x0F` sequence in all vectors, so
 * the constants are reproducible offline with:
 *
 * ```
 * challenge = bytes(range(16))
 * key = bits_reflected(password.encode('latin-1')[:8].ljust(8, b'\\0'))
 * response = DES_ECB_encrypt(key, challenge)
 * ```
 */
class VncAuthTest {

    private val challenge = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    )

    @Test
    fun sixCharacterPasswordVector() {
        val expected = hexToBytes("4e77e4979af45288597c5958414fe9c7")
        assertArrayEquals(expected, VncAuth.challengeResponse("123456", challenge))
    }

    @Test
    fun projectPasswordVector() {
        val expected = hexToBytes("e06c76b88c64d796aee797fa758c1a36")
        assertArrayEquals(expected, VncAuth.challengeResponse("linexa", challenge))
    }

    @Test
    fun passwordsLongerThanEightBytesAreTruncatedLikeEveryVncClient() {
        // VNC-Auth keys are 8 bytes; "longpassword123" uses only "longpass".
        // The vector equals the one "longpass" produces — truncation, not a
        // hash or a rejection.
        val expected = hexToBytes("5931256585fd62106d317e09fc963baf")
        assertArrayEquals(expected, VncAuth.challengeResponse("longpassword123", challenge))
        assertArrayEquals(expected, VncAuth.challengeResponse("longpass", challenge))
    }

    @Test
    fun keyIsAlwaysEightBytes() {
        assertEquals(8, VncAuth.passwordKeyBytes("").size)
        assertEquals(8, VncAuth.passwordKeyBytes("a").size)
        assertEquals(8, VncAuth.passwordKeyBytes("sixteen-char-key").size)
    }

    @Test
    fun keyBitsAreReflectedPerByte() {
        // 0x01 reflected is 0x80 — the RFC's bit-mirroring, checked on the
        // simplest possible byte so a wrong shift direction cannot pass.
        val key = VncAuth.passwordKeyBytes("\u0001")
        assertEquals(0x80.toByte(), key[0])
    }

    @Test
    fun aWrongSizedChallengeIsRejectedLoudly() {
        // A mis-framed handshake must fail here, not produce a bogus answer.
        assertThrows(IllegalArgumentException::class.java) {
            VncAuth.challengeResponse("linexa", ByteArray(8))
        }
    }

    @Test
    fun responseIsDeterministicPerPassword() {
        val first = VncAuth.challengeResponse("linexa", challenge)
        val second = VncAuth.challengeResponse("linexa", challenge)
        assertArrayEquals(first, second)
        // And a different password gives a different answer for the same
        // challenge — the property the whole mechanism rests on.
        assertTrue(
            !first.contentEquals(VncAuth.challengeResponse("other1", challenge))
        )
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
