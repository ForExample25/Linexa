package com.fem.linexa.ui.screens.desktop

import java.util.zip.Deflater
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.151 — JVM tests for [VncZlibStream].
 *
 * These exist for one defect in particular. RFB 3.8 §7.7.6 makes the ZRLE zlib
 * stream continuous across the whole connection: the server flushes at each
 * rectangle boundary but never resets the dictionary, so a rectangle's payload is a
 * *slice* of one stream and not a self-contained zlib document. A per-rectangle
 * `Inflater` decodes the first rectangle perfectly and then degrades — which is why
 * it survives a green build, a successful handshake, and a correct first frame.
 *
 * The tests drive a real [Deflater] with the same `SYNC_FLUSH` discipline a server
 * uses, so "continuous stream" is asserted rather than asserted-about: a decoder
 * that reset per call fails [aSecondRectangleDecodesAgainstTheFirstsDictionary],
 * and one that ignored the flush boundary fails
 * [aFlushedRectangleIsFullyInflatedBeforeTheNextArrives].
 */
class VncZlibStreamTest {

    private val stream = VncZlibStream()

    /** A server-side ZRLE stream: one `Deflater`, flushed per rectangle. */
    private class Server {
        private val deflater = Deflater()
        private val chunk = ByteArray(16 * 1024)

        /**
         * Compresses one rectangle's payload and flushes, as a ZRLE server does.
         *
         * The drain loop matters: a single `deflate` call into a fixed buffer
         * silently truncates a payload larger (or less compressible) than that
         * buffer, which would make a *harness* bug look like a decoder bug in the
         * large-rectangle test.
         */
        fun rectangle(payload: ByteArray): ByteArray {
            deflater.setInput(payload)
            val wire = java.io.ByteArrayOutputStream()
            while (true) {
                val produced = deflater.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
                if (produced == 0) break
                wire.write(chunk, 0, produced)
            }
            return wire.toByteArray()
        }

        fun end() = deflater.end()
    }

    private val server = Server()

    @After
    fun release() {
        server.end()
        stream.end()
    }

    private fun inflated(compressed: ByteArray): ByteArray {
        val length = stream.inflate(compressed)
        return stream.buffer.copyOf(length)
    }

    @Test
    fun aRectanglePayloadRoundTrips() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        assertArrayEquals(payload, inflated(server.rectangle(payload)))
    }

    @Test
    fun aSecondRectangleDecodesAgainstTheFirstsDictionary() {
        // THE test in this file. The second rectangle's compressed bytes are only
        // meaningful given the dictionary the first one left behind, so a stream
        // recreated per rectangle either throws or returns garbage here.
        val first = ByteArray(400) { (it % 97).toByte() }
        val second = ByteArray(400) { ((it * 3) % 97).toByte() }
        val firstWire = server.rectangle(first)
        val secondWire = server.rectangle(second)

        assertArrayEquals(first, inflated(firstWire))
        assertArrayEquals(second, inflated(secondWire))
    }

    @Test
    fun aRepeatedRectangleCompressesBetterTheSecondTime() {
        // Evidence that the dictionary is genuinely shared and not merely that two
        // independent documents happened to decode. Identical content sent twice must
        // cost less on the wire the second time.
        val payload = ByteArray(2048) { (it % 64).toByte() }
        val firstWire = server.rectangle(payload)
        val secondWire = server.rectangle(payload)
        assertTrue(
            "a shared dictionary must shrink a repeat: ${firstWire.size} then ${secondWire.size}",
            secondWire.size < firstWire.size
        )
        assertArrayEquals(payload, inflated(firstWire))
        assertArrayEquals(payload, inflated(secondWire))
    }

    @Test
    fun aFlushedRectangleIsFullyInflatedBeforeTheNextArrives() {
        // A ZRLE rectangle must be complete when its bytes are consumed — the tile
        // decoder is handed the result immediately. Stopping short (treating the
        // flush as end-of-stream, or a short read as done) would decode a truncated
        // tile stream, which is a visible glitch and then a desync.
        val payload = ByteArray(5000) { (it % 211).toByte() }
        assertEquals(payload.size, stream.inflate(server.rectangle(payload)))
    }

    @Test
    fun theBufferGrowsPastItsInitialSizeForALargeRectangle() {
        // A full-screen 1920x1080 ZRLE rectangle inflates to several MB of CPIXELs,
        // well past the 64 KB start. Incompressible content is used so the inflated
        // size really is the payload size.
        val random = java.util.Random(20260819)
        val payload = ByteArray(300_000).also { random.nextBytes(it) }
        val out = inflated(server.rectangle(payload))
        assertArrayEquals(payload, out)
        assertTrue("the buffer must have grown", stream.buffer.size >= payload.size)
    }

    @Test
    fun theBufferIsReusedAcrossRectangles() {
        // Reallocating megabytes per framebuffer update would hand the per-frame cost
        // back to the allocator that §1.150 exists to take work away from.
        val payload = ByteArray(1000) { it.toByte() }
        inflated(server.rectangle(payload))
        val first = stream.buffer
        inflated(server.rectangle(payload))
        assertTrue("the same buffer must be reused", first === stream.buffer)
    }

    @Test
    fun onlyTheReturnedLengthIsMeaningful() {
        // The buffer keeps the previous rectangle's tail, which is why VncZrle is
        // given an explicit dataLength rather than the array's size.
        val long = ByteArray(4000) { (it % 113).toByte() }
        inflated(server.rectangle(long))
        val short = ByteArray(16) { 7 }
        val length = stream.inflate(server.rectangle(short))
        assertEquals(short.size, length)
        assertTrue(
            "stale bytes must still be present past the length",
            stream.buffer.size > length
        )
    }

    @Test
    fun anEmptyRectanglePayloadYieldsNothingAndKeepsTheStreamUsable() {
        // A zero-extent rectangle still carries a payload that belongs to the shared
        // stream; consuming it must not disturb what follows.
        assertEquals(0, stream.inflate(server.rectangle(ByteArray(0))))
        val payload = ByteArray(64) { (it * 5).toByte() }
        assertArrayEquals(payload, inflated(server.rectangle(payload)))
    }

    @Test
    fun corruptBytesFailLoudRatherThanReturningPartialData() {
        // The stream is shared by the whole session, so a corrupt rectangle cannot be
        // skipped past — §1.114 settled that a lost framing is fatal.
        val e = assertThrows(IllegalStateException::class.java) {
            stream.inflate(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        }
        assertTrue("the message must name the encoding: ${e.message}", e.message!!.contains("ZRLE"))
    }

    @Test
    fun aFinishedStreamFailsLoud() {
        // A ZRLE server flushes and never finishes until the connection ends, so a
        // finished stream means the session is over, not that this rectangle is done.
        //
        // The check has to precede `needsInput()`, which is also true here (the input
        // is exhausted): the other order makes this case unreachable and turns a
        // terminated stream into an endless run of empty rectangles — a frozen
        // desktop with no error, which is the failure mode §1.114 legislated against.
        val deflater = Deflater()
        deflater.setInput(ByteArray(32) { 3 })
        deflater.finish()
        val out = ByteArray(256)
        val produced = deflater.deflate(out)
        deflater.end()

        val e = assertThrows(IllegalStateException::class.java) {
            stream.inflate(out.copyOf(produced))
        }
        assertTrue("${e.message}", e.message!!.contains("ended mid-session"))
    }
}
