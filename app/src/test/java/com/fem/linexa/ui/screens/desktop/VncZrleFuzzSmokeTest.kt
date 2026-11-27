package com.fem.linexa.ui.screens.desktop

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-043 — seeded fuzz smoke test for the ZRLE decoder.
 *
 * The decoder consumes attacker-controlled tile bytes from a VNC peer. Its
 * contract is: malformed input may throw `IllegalStateException` (the
 * documented "fail loud, never desync" signal), but it must never hang, write
 * out of bounds, or throw an unexpected error. Pixels outside the destination
 * are clipped, so a successful decode's return value is bounded by the
 * rectangle it was given.
 */
class VncZrleFuzzSmokeTest {

    @Test
    fun `random tile streams either decode or fail loudly`() {
        val random = Random(0x5EED)
        repeat(1_000) {
            val length = random.nextInt(0, 257)
            val data = ByteArray(length).also(random::nextBytes)
            val width = random.nextInt(0, 17)
            val height = random.nextInt(0, 17)
            val destWidth = random.nextInt(0, 20)
            val destHeight = random.nextInt(0, 20)
            val dest = IntArray(destWidth * destHeight)

            try {
                val written = VncZrle.decode(
                    data = data,
                    dataLength = length,
                    dest = dest,
                    destWidth = destWidth,
                    destHeight = destHeight,
                    x = random.nextInt(0, 8),
                    y = random.nextInt(0, 8),
                    width = width,
                    height = height
                )
                assertTrue("wrote $written for a ${width}x$height rect", written in 0..(width * height))
            } catch (_: IllegalStateException) {
                // The documented malformed-stream signal.
            }
        }
    }

    @Test
    fun `a valid all-zero tile decodes without throwing`() {
        val data = ByteArray(193) // raw tile header + 64 pixels * 3-byte CPIXEL
        val dest = IntArray(64 * 64)

        val written = VncZrle.decode(data, data.size, dest, 64, 64, 0, 0, 8, 8)

        assertTrue(written in 0..64)
    }
}
