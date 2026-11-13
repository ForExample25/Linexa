package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.151 structural guard for the desktop's **encoding** path, revised by §1.152.
 *
 * [VncZrleTest] covers the tile arithmetic and [VncZlibStreamTest] the stream's
 * behaviour — both pure. What neither can reach is the *wiring*: that the client
 * keeps one stream per connection rather than per rectangle, ends it with the
 * socket, and consumes a rectangle's payload even when it decodes nothing. Those
 * are one-line properties whose loss produces a session that connects, draws a
 * correct first frame, and then degrades — the §1.114 failure shape, invisible to a
 * compile and to any test that does not have a socket.
 *
 * §1.152 adds the inverse property: that ZRLE is **decoded but not advertised**.
 * The decoder's correctness is still worth guarding (a server may send it unasked),
 * but offering it on a loopback transport is a measured pessimisation, and putting
 * it back looks exactly like an improvement.
 *
 * Same technique and rationale as [DesktopRenderWiringSourceTest] (§1.147) and
 * [DesktopInputWiringSourceTest] (§1.138).
 */
class DesktopEncodingWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val client: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/desktop/VncClient.kt"
        ).readText()

    private val rectangleBody: String
        get() = client
            .substringAfter("private fun readRectangle(input: BufferedInputStream): Boolean")
            .substringBefore("private fun publishFrame()")

    private val zrleBody: String
        get() = rectangleBody
            .substringAfter("ENCODING_ZRLE ->")
            .substringBefore("else -> throw")

    // ── the zlib stream's lifetime ───────────────────────────────────────

    @Test
    fun theZlibStreamIsPerConnectionNotPerRectangle() {
        // RFB 3.8 §7.7.6: the server flushes after each rectangle but never resets
        // the dictionary. A per-rectangle stream therefore decodes rectangle 1
        // correctly and produces garbage or throws from rectangle 2 onwards — a
        // session that connects, draws, and then falls apart.
        assertTrue(
            "the stream must be a client field",
            client.contains("private var zlibStream: VncZlibStream? = null")
        )
        assertTrue(
            "and be reused rather than reconstructed: $zrleBody",
            zrleBody.contains("zlibStream ?: VncZlibStream().also { zlibStream = it }")
        )
        assertEquals(
            "there must be exactly one VncZlibStream construction",
            1,
            client.split("VncZlibStream()").size - 1
        )
    }

    @Test
    fun theZlibStreamIsEndedWithTheSocket() {
        // The inflater inside it holds native memory GC does not urgently reclaim,
        // and a reconnect that reused the old dictionary would decode garbage.
        val closeBody = client.substringAfter("private fun closeSocketQuietly()")
        assertTrue(
            "closing the socket must end the stream: $closeBody",
            closeBody.contains("zlibStream?.end()")
        )
        assertTrue(
            "and drop it so a reconnect starts a new one: $closeBody",
            closeBody.contains("zlibStream = null")
        )
    }

    @Test
    fun aZeroExtentZrleRectangleStillConsumesItsPayload() {
        // Its bytes belong to the shared stream. Consuming them without inflating —
        // or skipping them — desyncs the dictionary for every rectangle after it,
        // which is the §1.114 silent-desync failure mode one layer down.
        val inflateAt = zrleBody.indexOf("stream.inflate(compressed)")
        val extentGuardAt = zrleBody.indexOf("if (width == 0 || height == 0) return false")
        assertTrue("the ZRLE path must inflate its payload: $zrleBody", inflateAt >= 0)
        assertTrue("and check its extent: $zrleBody", extentGuardAt >= 0)
        assertTrue(
            "the inflate must happen BEFORE the extent guard: $zrleBody",
            inflateAt < extentGuardAt
        )
        assertFalse(
            "the payload must never be skipped: $zrleBody",
            zrleBody.contains("skipFully(")
        )
    }

    @Test
    fun theDeclaredLengthBoundsTheTileDecode() {
        // The stream's buffer keeps the PREVIOUS rectangle's tail, so handing the
        // decoder the array without its valid length would decode tiles from stale
        // bytes instead of failing.
        assertTrue(
            "the decode must be bounded by the inflated length: $zrleBody",
            zrleBody.contains("dataLength = inflatedLength")
        )
    }

    @Test
    fun theCompressedLengthIsValidatedBeforeItIsAllocated() {
        // The length comes off the wire and is allocated immediately. `u32` returns a
        // signed Int, so a high bit reads negative (NegativeArraySizeException from
        // inside the receive loop) and a merely large value is an OutOfMemoryError —
        // both are "the stream is not what we think it is", which §1.114 settled is
        // reported rather than crashed on.
        val guardAt = zrleBody.indexOf("compressedLength < 0")
        val allocAt = zrleBody.indexOf("ByteArray(compressedLength)")
        assertTrue("a negative length must be rejected: $zrleBody", guardAt >= 0)
        assertTrue("the payload must be allocated from it: $zrleBody", allocAt >= 0)
        assertTrue(
            "the guard must precede the allocation: $zrleBody",
            guardAt < allocAt
        )
        assertTrue(
            "and an implausibly large length must be rejected too: $zrleBody",
            zrleBody.contains("compressedLength > MAX_ZRLE_RECTANGLE_BYTES")
        )
    }

    @Test
    fun theRectangleLengthBoundIsAboveAnyRealRectangle() {
        // A bound tight enough to reject a legitimate full-screen update would turn a
        // working server into a dead session on its first frame — the mirror of the
        // failure the bound exists to prevent. A 1920x1080 ZRLE rectangle compresses
        // to a few hundred KB; even uncompressed 32bpp it is 8.3 MB.
        assertTrue(
            "the bound must exceed an uncompressed 4K frame",
            VncClient.MAX_ZRLE_RECTANGLE_BYTES > 3840 * 2160
        )
    }

    // ── the decode path ──────────────────────────────────────────────────

    @Test
    fun theZrleRectangleIsDecodedIntoThePersistentFramebufferAtItsOwnOffset() {
        // §1.114 defect 2, in a new encoding: a rectangle decoded as if it were the
        // whole framebuffer draws once and then freezes.
        assertTrue(
            "the decode must receive the rectangle's origin: $zrleBody",
            zrleBody.contains("x = x,") && zrleBody.contains("y = y,")
        )
        assertTrue(
            "and write the shared framebuffer: $zrleBody",
            zrleBody.contains("dest = pixels,")
        )
    }

    @Test
    fun theZrleRectangleRecordsItsDirtyRegionOnlyWhenPixelsLanded() {
        // §1.147's rule, which the new encoding has to obey too: an unrecorded
        // rectangle is permanently stale on screen, and a recorded one that wrote
        // nothing enlarges every later upload.
        assertTrue(
            "the ZRLE path must union its rectangle when written: $zrleBody",
            zrleBody.contains("if (written) dirty.union(x, y, width, height)")
        )
    }

    @Test
    fun rawAndCopyRectAreStillDecoded() {
        // Raw is mandatory per the spec and, since §1.152 withdrew the ZRLE offer,
        // the only pixel encoding advertised; CopyRect is what servers emit for
        // window moves and scrolls.
        assertTrue("the Raw path must remain", rectangleBody.contains("ENCODING_RAW ->"))
        assertTrue(
            "the CopyRect path must remain",
            rectangleBody.contains("ENCODING_COPY_RECT ->")
        )
        assertTrue(
            "Raw must still decode through VncPixels: $rectangleBody",
            rectangleBody.contains("VncPixels.decodeArgbInto(")
        )
        assertTrue(
            "CopyRect must still blit in place: $rectangleBody",
            rectangleBody.contains("VncPixels.copyRect(")
        )
    }

    // ── §1.164: the Raw path allocates no intermediate pixel buffer ──────

    /**
     * The Raw branch with its comments removed.
     *
     * The allocation assertions below are about what the code *does*, and this
     * branch's comments name the removed `IntArray(width * height)` in order to say
     * why it is gone — so matching against the raw text would fail on the very
     * explanation of the change.
     */
    private val rawCode: String
        get() = rectangleBody
            .substringAfter("ENCODING_RAW ->")
            .substringBefore("ENCODING_COPY_RECT ->")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    @Test
    fun theRawPathDecodesStraightIntoTheFramebuffer() {
        // §16.4. The Raw branch used to allocate an IntArray(width * height), fill it,
        // and then copy its rows into the framebuffer — 8.3 MB of garbage and a second
        // full traversal per full-screen update, on the receive loop. Asserted
        // structurally because re-introducing a temporary buffer is the natural shape
        // to write this in and would draw a correct picture, only more expensively.
        assertTrue(
            "the decode must write the shared framebuffer: $rawCode",
            rawCode.contains("dest = pixels,")
        )
        assertTrue(
            "and take the wire bytes directly: $rawCode",
            rawCode.contains("bytes = pixelData")
        )
        assertFalse(
            "the Raw path must not allocate an intermediate IntArray: $rawCode",
            rawCode.contains("IntArray(")
        )
        assertFalse(
            "and the split decode/copy pair must not come back: $client",
            client.contains("VncPixels.decodeArgb(") || client.contains("VncPixels.blit(")
        )
    }

    @Test
    fun theOnlyAllocationTheRawPathMakesIsTheWirePayload() {
        // One ByteArray per rectangle is irreducible — the bytes have to be read off
        // the socket before they can be converted — so it is the floor, not a defect.
        // Anything beyond it is a copy that the destination offsets already made
        // unnecessary.
        assertEquals(
            "the Raw path must allocate exactly one buffer: $rawCode",
            1,
            rawCode.split("ByteArray(").size - 1
        )
    }

    @Test
    fun anUnadvertisedEncodingIsStillAHardFailure() {
        // §1.114: an unknown encoding has an unknown payload length, so the framing
        // is already lost and continuing decodes garbage as pixels indefinitely.
        assertTrue(
            "an unknown encoding must throw: $rectangleBody",
            rectangleBody.contains("else -> throw IllegalStateException(")
        )
    }

    @Test
    fun everyAdvertisedEncodingHasADecodePath() {
        // The failure this catches is advertising an encoding the decoder rejects,
        // which turns a working server into a dead session on its first frame. The
        // converse — a decoder for an encoding that is not advertised — is deliberate
        // since §1.152 and is asserted separately below.
        assertEquals(listOf("ENCODING_COPY_RECT", "ENCODING_RAW"), advertisedEncodings())
        advertisedEncodings().forEach { encoding ->
            assertTrue(
                "advertised $encoding must have a branch in readRectangle",
                rectangleBody.contains("$encoding ->")
            )
        }
    }

    // ── §1.152: the offer is withdrawn, the decoder is not ───────────────

    @Test
    fun zrleIsNotAdvertisedByDefault() {
        // §1.151 offered it to save bandwidth. §1.152 withdrew the offer because the
        // server is Xvnc on 127.0.0.1 inside the same guest: the transfer is a
        // loopback memcpy, while the deflate and the inflate both run on the one phone
        // CPU the X server and this app's render path are already contending for. So
        // compression is added latency on the frame's critical path, paid twice, for
        // bandwidth that is free.
        //
        // Desktop Modes (2026-08) keeps that decision as the DEFAULT and turns the
        // offer into an explicit, user-facing opt-in (FeatureSettings.desktopVncPreferZrle)
        // for non-loopback transports (an `adb reverse` tunnel, a future remote
        // viewer) where the bandwidth it saves is real. The default advertisement —
        // what sendSetEncodings sends with the setting off — is still §1.152's list.
        //
        // This is asserted at the source level as well as in VncClientTest because
        // flipping the default *looks* like an optimisation and would negotiate and
        // draw correctly — only slower.
        assertFalse(
            "ZRLE must not be in the default advertised list: ${advertisedEncodings()}",
            advertisedEncodings().contains("ENCODING_ZRLE")
        )
    }

    @Test
    fun zrleOptInIsAdvertisedBetweenCopyRectAndRaw() {
        // Desktop Modes: the opt-in overload's order is load-bearing the same way
        // the default's is — CopyRect first (capability flag), then the pixel
        // encodings in preference order, Raw last and never dropped. A server that
        // implements ZRLE picks it before Raw; one that does not falls through to
        // Raw exactly as before.
        val advertised = sourceAdvertisedEncodings(
            VncClient.buildSetEncodingsMessage(preferZrle = true)
        )
        // sourceAdvertisedEncodings decodes each wire word back to its
        // "ENCODING_*" name; the order — CopyRect, ZRLE, Raw — is the assertion.
        assertEquals(
            listOf("ENCODING_COPY_RECT", "ENCODING_ZRLE", "ENCODING_RAW"),
            advertised
        )
    }

    @Test
    fun sendSetEncodingsPassesTheSettingThrough() {
        // The wiring half of the opt-in: the client must actually SEND the
        // parameterized message, not build the default in a second place.
        val body = client
            .substringAfter("private suspend fun sendSetEncodings()")
            .substringBefore("private suspend fun requestFramebufferUpdate")
        assertTrue(
            "sendSetEncodings must forward preferZrle to buildSetEncodingsMessage: $body",
            body.contains("buildSetEncodingsMessage(preferZrle)")
        )
    }

    @Test
    fun theZrleDecodePathIsKeptForAnUnaskedForRectangle() {
        // Withdrawing the offer is not the same as dropping the decoder, and dropping
        // it would be worse than either: a server is not obliged to restrict itself to
        // the advertised set, and §1.114 settled that an encoding without a branch is a
        // fatal desync. Keeping the branch also makes re-enabling the offer for a
        // future remote (non-loopback) transport a one-line change.
        assertTrue(
            "the ZRLE branch must remain in readRectangle: $rectangleBody",
            rectangleBody.contains("ENCODING_ZRLE ->")
        )
        assertTrue(
            "and the tile decoder must still be reachable from it: $zrleBody",
            zrleBody.contains("VncZrle.decode(")
        )
    }

    /**
     * Desktop Modes: the default advertisement is now asserted from the BUILT
     * message rather than a source scan of `val encodings = listOf(...)` — the
     * builder grew an opt-in branch, and a text scan would have to re-parse the
     * branch condition to stay truthful. Decoding the actual message keeps the
     * assertion exact (it IS what sendSetEncodings writes) and the wiring pin
     * moved to [sendSetEncodingsPassesTheSettingThrough].
     */
    private fun advertisedEncodings(): List<String> =
        sourceAdvertisedEncodings(VncClient.buildSetEncodingsMessage())

    private fun sourceAdvertisedEncodings(msg: ByteArray): List<String> =
        (0 until ((msg[2].toInt() and 0xFF) shl 8 or (msg[3].toInt() and 0xFF))).map { i ->
            val o = 4 + i * 4
            val encoding = ((msg[o].toInt() and 0xFF) shl 24) or
                ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or
                (msg[o + 3].toInt() and 0xFF)
            "ENCODING_" + when (encoding) {
                VncClient.ENCODING_RAW -> "RAW"
                VncClient.ENCODING_COPY_RECT -> "COPY_RECT"
                VncClient.ENCODING_ZRLE -> "ZRLE"
                else -> "UNKNOWN"
            }
        }

    @Test
    fun theCpixelFormatMatchesTheRequestedPixelFormat() {
        // ZRLE CPIXELs are 3 bytes only because SetPixelFormat asks for 32bpp/
        // depth24 with shifts 16-8-0. Changing that request without changing the
        // decoder (or vice versa) shifts every tile by a byte per pixel.
        val pixelFormatBody = client
            .substringAfter("private suspend fun sendSetPixelFormat()")
            .substringBefore("/**")
        assertTrue(
            "bits-per-pixel must stay 32: $pixelFormatBody",
            pixelFormatBody.contains("msg[4] = 32")
        )
        assertTrue(
            "depth must stay 24: $pixelFormatBody",
            pixelFormatBody.contains("msg[5] = 24")
        )
        assertTrue(
            "the format must stay little-endian: $pixelFormatBody",
            pixelFormatBody.contains("msg[6] = 0")
        )
        assertEquals(3, VncZrle.BYTES_PER_CPIXEL)
    }
}
