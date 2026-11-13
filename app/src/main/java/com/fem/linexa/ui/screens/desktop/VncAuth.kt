package com.fem.linexa.ui.screens.desktop

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The RFB **VNC Authentication** challenge/response, implemented from scratch
 * against RFC 6143 §7.2.2 (Desktop Modes work, 2026-08).
 *
 * Until now the client spoke only `SecurityTypes None` — which is why
 * `DesktopGeometry` had to start Xvnc with `-SecurityTypes None` and why every
 * app on the device could open a socket to the desktop's loopback port without
 * any permission at all (Android grants no localhost isolation between apps).
 * This object closes that hole with the protocol's own mechanism: the server
 * sends a 16-byte random challenge, and the client answers with
 * `DES_ECB(challenge[0..7], key) || DES_ECB(challenge[8..15], key)` where
 * `key` is the password (truncated/padded to 8 bytes) with the bits of each
 * byte **reflected**, exactly as the RFC specifies.
 *
 * ## Implementation notes
 *
 * - The cipher is the platform JCE (`javax.crypto` "DES"), which ships on
 *   every Android device and every JDK — no new dependency, matching the
 *   constraint that the desktop modes add no third-party libraries.
 * - JCE's DES ignores the low bit of each key byte (parity), and the RFC's
 *   bit-reflection is what makes the standard cipher reproduce VNC's
 *   key schedule. This is the same recipe every open-source client
 *   (TigerVNC's own viewer included) uses against real servers.
 * - [passwordKeyBytes] truncates to the first 8 bytes like every VNC client:
 *   VNC-Auth passwords are at most 8 characters, and longer input silently
 *   using only its head is the spec's behaviour, not a bug here.
 * - Pure and JVM-testable: the challenge→response mapping is pinned by
 *   `VncAuthTest` against vectors generated with two independent DES
 *   implementations (pycryptodome and OpenSSL's legacy provider agreeing).
 */
internal object VncAuth {

    /** The challenge length RFB VNC-Auth mandates (RFC 6143 §7.2.2). */
    const val CHALLENGE_BYTES = 16

    /** The key length: VNC-Auth passwords are 8 bytes, zero-padded. */
    const val KEY_BYTES = 8

    /**
     * The 8-byte DES key for [password]: ISO-8859-1 bytes, truncated to at
     * most 8, zero-padded to exactly 8, each byte's bits reflected.
     */
    fun passwordKeyBytes(password: String): ByteArray {
        val raw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(KEY_BYTES)
        System.arraycopy(raw, 0, key, 0, minOf(raw.size, KEY_BYTES))
        for (i in 0 until KEY_BYTES) {
            var v = key[i].toInt() and 0xFF
            var reflected = 0
            for (bit in 0 until 8) {
                reflected = reflected or (((v ushr bit) and 1) shl (7 - bit))
            }
            key[i] = reflected.toByte()
        }
        return key
    }

    /**
     * The 16-byte VNC-Auth response for [challenge] (exactly
     * [CHALLENGE_BYTES] bytes) using [password].
     *
     * @throws IllegalArgumentException when the challenge is the wrong size —
     *   a mis-framed handshake must fail loudly, not silently authenticate.
     */
    fun challengeResponse(password: String, challenge: ByteArray): ByteArray {
        require(challenge.size == CHALLENGE_BYTES) {
            "VNC auth challenge must be $CHALLENGE_BYTES bytes, got ${challenge.size}"
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(passwordKeyBytes(password), "DES")
        )
        return cipher.doFinal(challenge)
    }
}
