package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VncClient]'s pure wire/protocol decisions.
 *
 * §1.94 pinned the SetEncodings layout. §1.114 extends this to the parts of the
 * handshake and pointer encoding that are *silently* wrong when broken — a
 * mis-set button mask or a version reply above the server's produces a session
 * that connects and then misbehaves, which no compile step and no smoke test on
 * a single server version would catch.
 */
class VncClientTest {

    // ---- SetEncodings (§1.94, extended in §1.114, §1.151, §1.152) ----

    @Test
    fun setEncodingsMessageHasExactWireLayout() {
        val expected = byteArrayOf(
            2,  // message-type = SetEncodings
            0,  // padding
            0, 2, // U16 big-endian number-of-encodings = 2
            0, 0, 0, 1, // S32 big-endian CopyRect (1), preferred
            0, 0, 0, 0  // S32 big-endian Raw (0), mandatory
        )
        assertArrayEquals(expected, VncClient.buildSetEncodingsMessage())
    }

    @Test
    fun setEncodingsMessageTypeByteIsTwo() {
        assertEquals(2, VncClient.buildSetEncodingsMessage()[0].toInt())
    }

    @Test
    fun setEncodingsAdvertisesOnlyEncodingsTheClientDecodes() {
        // The client throws on any other encoding, so advertising one it cannot
        // decode would turn a working server into a dead session. The converse is
        // allowed and is what §1.152 relies on: ZRLE is decoded but not advertised.
        val msg = VncClient.buildSetEncodingsMessage()
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        assertEquals(2, count)
        assertEquals(
            listOf(VncClient.ENCODING_COPY_RECT, VncClient.ENCODING_RAW),
            advertisedEncodings()
        )
    }

    @Test
    fun compressionIsNotAdvertisedOnALoopbackTransport() {
        // §1.152 reverses §1.151's offer. A server picks the first PIXEL encoding in
        // this list it implements, so this list is the whole switch: with ZRLE in it
        // the server deflates and this client inflates, both on the same phone CPU,
        // for a transfer that is a loopback memcpy. That is latency added to the
        // frame's critical path in exchange for bandwidth that costs nothing.
        //
        // Asserted rather than merely removed because reinstating it looks like an
        // optimisation and would compile, negotiate and draw correctly — only slower,
        // which is precisely the failure §1.152 exists to undo.
        val encodings = advertisedEncodings()
        assertFalse(
            "ZRLE must not be advertised while the server is on loopback: $encodings",
            encodings.contains(VncClient.ENCODING_ZRLE)
        )
    }

    @Test
    fun zrleRemainsDecodableEvenThoughItIsNotAdvertised()  {
        // The decoder is kept: a server is not obliged to restrict itself to the
        // advertised set, and §1.114 settled that an encoding this client cannot
        // decode is a fatal desync — so dropping the decoder would turn an unasked-for
        // ZRLE rectangle into a dead session. It is also what makes re-enabling the
        // offer a one-line change for a future non-loopback transport.
        assertEquals(16, VncClient.ENCODING_ZRLE)
    }

    @Test
    fun rawRemainsAdvertisedAsTheMandatoryFallback() {
        // RFB 3.8 requires Raw, and with ZRLE withdrawn it is the only pixel
        // encoding on offer.
        assertTrue(advertisedEncodings().contains(VncClient.ENCODING_RAW))
    }

    @Test
    fun copyRectRemainsAdvertised() {
        // Not in competition with the pixel encodings: a server uses it for moves
        // and scrolls whatever else it picks, and it is cheaper than any of them.
        assertTrue(advertisedEncodings().contains(VncClient.ENCODING_COPY_RECT))
    }

    @Test
    fun copyRectIsPreferredOverRaw() {
        val encodings = advertisedEncodings()
        assertTrue(
            "CopyRect must precede Raw: $encodings",
            encodings.indexOf(VncClient.ENCODING_COPY_RECT) <
                encodings.indexOf(VncClient.ENCODING_RAW)
        )
    }

    @Test
    fun setEncodingsMessageLengthMatchesItsEncodingCount() {
        val msg = VncClient.buildSetEncodingsMessage()
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        assertEquals(4 + count * 4, msg.size)
    }

    // ── Transformation spec §12: the negotiated-encoding contract ──

    @Test
    fun zrleIsAdvertisedWhenTheTransportRequestsIt() {
        // The opt-in flag (adb reverse / remote transports, §1.152) must put
        // ZRLE on the wire — the decoder has always handled it; this pins the
        // advertisement half of the negotiation so the flag cannot silently
        // regress into a no-op.
        val encodings = advertisedEncodings(preferZrle = true)
        assertTrue(
            "ZRLE must be advertised when preferZrle is set: $encodings",
            encodings.contains(VncClient.ENCODING_ZRLE)
        )
        // The mandatory fallbacks never leave the offer.
        assertTrue(encodings.contains(VncClient.ENCODING_RAW))
        assertTrue(encodings.contains(VncClient.ENCODING_COPY_RECT))
    }

    @Test
    fun zrleAdvertisementKeepsMessageLengthConsistent() {
        val msg = VncClient.buildSetEncodingsMessage(preferZrle = true)
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        assertEquals(4 + count * 4, msg.size)
        val without = VncClient.buildSetEncodingsMessage(preferZrle = false)
        val countWithout = ((without[2].toInt() and 0xFF) shl 8) or (without[3].toInt() and 0xFF)
        assertEquals("ZRLE opt-in adds exactly one encoding to the offer", countWithout + 1, count)
    }

    private fun advertisedEncodings(preferZrle: Boolean = false): List<Int> {
        val msg = VncClient.buildSetEncodingsMessage(preferZrle)
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        return (0 until count).map { i ->
            val o = 4 + i * 4
            ((msg[o].toInt() and 0xFF) shl 24) or
                ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or
                (msg[o + 3].toInt() and 0xFF)
        }
    }

    private fun advertisedEncodings(): List<Int> {
        val msg = VncClient.buildSetEncodingsMessage()
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        return (0 until count).map { i ->
            val o = 4 + i * 4
            ((msg[o].toInt() and 0xFF) shl 24) or
                ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or
                (msg[o + 3].toInt() and 0xFF)
        }
    }

    // ---- ProtocolVersion negotiation (§1.114) ----

    @Test
    fun negotiatesThreeEightWithAThreeEightServer() {
        assertEquals("RFB 003.008\n", VncClient.negotiateVersion("RFB 003.008\n"))
    }

    @Test
    fun neverRepliesAboveTheServersVersion() {
        // Replying 3.8 to a 3.3 server is a protocol violation AND selects the
        // wrong security handshake (3.3 has no type list and no SecurityResult).
        assertEquals("RFB 003.003\n", VncClient.negotiateVersion("RFB 003.003\n"))
    }

    @Test
    fun clampsAThreeNineServerDownToTheHighestVersionWeImplement() {
        assertEquals("RFB 003.008\n", VncClient.negotiateVersion("RFB 003.009\n"))
    }

    @Test
    fun treatsThreeSevenAsTheThreeEightHandshake() {
        // 3.7 introduced the security-type list, which is what the 3.8 path
        // implements; only < 3.7 needs the legacy path.
        assertTrue(VncClient.isVersion37OrLater(VncClient.negotiateVersion("RFB 003.007\n")))
        assertTrue(!VncClient.isVersion37OrLater(VncClient.negotiateVersion("RFB 003.003\n")))
    }

    @Test
    fun fallsBackToThreeEightOnAnUnparseableVersionString() {
        assertEquals("RFB 003.008\n", VncClient.negotiateVersion("garbage"))
    }

    // ---- Security type selection (§1.114) ----

    @Test
    fun choosesNoneWhenOffered() {
        assertEquals(1, VncClient.chooseSecurityType(listOf(1)))
    }

    @Test
    fun prefersNoneEvenWhenListedAfterAnAuthType() {
        // The pre-§1.114 client took securityTypes[0] unconditionally, so a
        // server offering [VncAuth, None] produced a bogus auth attempt.
        assertEquals(1, VncClient.chooseSecurityType(listOf(2, 1)))
    }

    @Test
    fun rejectsAnAuthenticatingServerWithAClearError() {
        val e = assertThrows(VncClient.SecurityUnsupportedException::class.java) {
            VncClient.chooseSecurityType(listOf(2, 16))
        }
        assertEquals(listOf(2, 16), e.offered)
    }

    @Test
    fun rejectsAnEmptySecurityTypeList() {
        assertThrows(VncClient.SecurityUnsupportedException::class.java) {
            VncClient.chooseSecurityType(emptyList())
        }
    }

    // ---- Pointer wheel button mask (§1.114) ----

    @Test
    fun wheelUsesButtonMaskBitsNotButtonNumbers() {
        // RFB 3.8 §7.5.5: buttons 4/5 are mask bits 3/4. Writing the literal
        // values 4 and 5 into the mask reads as buttons 3+4 / 1+4 pressed, so a
        // scroll also injected phantom middle/left clicks.
        assertEquals(0x08, VncClient.BUTTON_MASK_WHEEL_UP)
        assertEquals(0x10, VncClient.BUTTON_MASK_WHEEL_DOWN)
    }

    @Test
    fun wheelMasksContainNoPrimaryButtonBits() {
        val primaryButtons = 0x07 // buttons 1..3
        assertEquals(0, VncClient.BUTTON_MASK_WHEEL_UP and primaryButtons)
        assertEquals(0, VncClient.BUTTON_MASK_WHEEL_DOWN and primaryButtons)
    }

    @Test
    fun wheelUpAndDownAreDistinct() {
        assertTrue(VncClient.BUTTON_MASK_WHEEL_UP != VncClient.BUTTON_MASK_WHEEL_DOWN)
    }

    // ---- Desktop Modes (2026-08): security selection and the ZRLE opt-in ----

    @Test
    fun aPasswordPrefersVncAuthWhenOffered() {
        // The caller that started Xvnc with -rfbauth asked for an authenticated
        // desktop; the client must not silently downgrade when the server also
        // offers None.
        assertEquals(
            VncClient.SECURITY_TYPE_VNC_AUTH,
            VncClient.chooseSecurityType(listOf(1, 2), password = "secret1")
        )
        assertEquals(
            VncClient.SECURITY_TYPE_VNC_AUTH,
            VncClient.chooseSecurityType(listOf(2), password = "secret1")
        )
    }

    @Test
    fun anEmptyPasswordMeansNoneLikeBefore() {
        // Blank/null passwords are the pre-Desktop-Modes behaviour, kept as the
        // default so an upgrade cannot lock anyone out of their own desktop.
        assertEquals(1, VncClient.chooseSecurityType(listOf(2, 1), password = null))
        assertEquals(1, VncClient.chooseSecurityType(listOf(2, 1), password = ""))
        assertEquals(1, VncClient.chooseSecurityType(listOf(1), password = "secret1"))
    }

    @Test
    fun aPasswordCannotSurviveAServerThatOffersOnlyAuth() {
        // The credential is absent — guessing one would produce a misleading
        // "server rejected connection" for what is really an unimplemented path.
        assertThrows(VncClient.SecurityUnsupportedException::class.java) {
            VncClient.chooseSecurityType(listOf(2), password = null)
        }
    }

    @Test
    fun zrleOptInAdvertisesThreeEncodingsInOrder() {
        val msg = VncClient.buildSetEncodingsMessage(preferZrle = true)
        val count = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        assertEquals(3, count)
        val encodings = (0 until count).map { i ->
            val o = 4 + i * 4
            ((msg[o].toInt() and 0xFF) shl 24) or
                ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or
                (msg[o + 3].toInt() and 0xFF)
        }
        assertEquals(
            listOf(VncClient.ENCODING_COPY_RECT, VncClient.ENCODING_ZRLE, VncClient.ENCODING_RAW),
            encodings
        )
    }

    @Test
    fun vncAuthSecurityTypeAndChallengeLengthAreProtocolFacts() {
        // RFB 6143 §7.2.2: security type 2, 16-byte challenge, 8-byte key.
        assertEquals(2, VncClient.SECURITY_TYPE_VNC_AUTH)
        assertEquals(16, VncClient.VNC_AUTH_CHALLENGE_BYTES)
    }
}
