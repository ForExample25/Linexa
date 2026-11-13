package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.151 — JVM tests for [VncZrle].
 *
 * ZRLE is the part of this change that fails *silently*: a mis-packed palette
 * index, a run length off by one, or a missed row-padding byte all produce a
 * picture rather than an error — "the desktop looks glitchy" is the only symptom,
 * and it is indistinguishable from a slow connection on a device. So every
 * subencoding is asserted against a hand-built tile stream here, which is the same
 * argument [VncPixelsTest] makes for the composition rules (§1.114).
 *
 * The streams are built with [Tile], a deliberately dumb byte-list writer: using
 * the decoder's own helpers to build its input would make an off-by-one in a
 * helper invisible to every test that uses it.
 */
class VncZrleTest {

    /** ARGB the decoder must produce for wire CPIXEL bytes B, G, R. */
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private class Tile {
        val bytes = mutableListOf<Byte>()

        fun byte(value: Int): Tile {
            bytes.add((value and 0xFF).toByte())
            return this
        }

        /** One CPIXEL: 3 bytes, little-endian least-significant-first = B, G, R. */
        fun cpixel(r: Int, g: Int, b: Int): Tile = byte(b).byte(g).byte(r)

        /** A run length, encoded as `length - 1` in 255-terminated chunks. */
        fun runLength(length: Int): Tile {
            var remaining = length - 1
            while (remaining >= 255) {
                byte(255)
                remaining -= 255
            }
            return byte(remaining)
        }

        fun build(): ByteArray = bytes.toByteArray()
    }

    private fun decodeInto(
        data: ByteArray,
        destWidth: Int,
        destHeight: Int,
        x: Int = 0,
        y: Int = 0,
        width: Int = destWidth,
        height: Int = destHeight
    ): Pair<IntArray, Int> {
        val dest = IntArray(destWidth * destHeight)
        val written = VncZrle.decode(
            data = data,
            dataLength = data.size,
            dest = dest,
            destWidth = destWidth,
            destHeight = destHeight,
            x = x,
            y = y,
            width = width,
            height = height
        )
        return dest to written
    }

    // ── the grid ─────────────────────────────────────────────────────────

    @Test
    fun theTileGridIsSixtyFour() {
        // RFB 3.8 §7.7.6 fixes it. A different value silently mis-slices every
        // rectangle wider than one tile.
        assertEquals(64, VncZrle.TILE_SIZE)
    }

    @Test
    fun aCpixelIsThreeBytesForTheRequestedPixelFormat() {
        // SetPixelFormat asks for 32bpp/depth24 with shifts 16-8-0, which is exactly
        // the case RFB defines as a 3-byte CPIXEL. Reading 4 would consume the next
        // pixel's first byte and shift the whole tile.
        assertEquals(3, VncZrle.BYTES_PER_CPIXEL)
    }

    // ── solid tile (subencoding 1) ───────────────────────────────────────

    @Test
    fun aSolidTileFillsItsWholeExtentFromOneCpixel() {
        val data = Tile().byte(1).cpixel(0x11, 0x22, 0x33).build()
        val (dest, written) = decodeInto(data, 2, 2)
        assertEquals(4, written)
        dest.forEach { assertEquals(argb(0x11, 0x22, 0x33), it) }
    }

    @Test
    fun cpixelBytesAreLittleEndianBgrNotRgb() {
        // The one-byte-order mistake that produces a plausible picture with the
        // colours swapped, i.e. a bug reported as "the desktop looks blue".
        val data = Tile().byte(1).cpixel(r = 0xFF, g = 0x00, b = 0x00).build()
        val (dest, _) = decodeInto(data, 1, 1)
        assertEquals(argb(0xFF, 0x00, 0x00), dest[0])
    }

    @Test
    fun alphaIsForcedOpaque() {
        // Depth 24 carries no alpha, and a zero alpha renders a correctly received
        // frame as a black screen — the §1.114 defect (VncPixels.decodeArgbInto).
        val data = Tile().byte(1).cpixel(0x01, 0x02, 0x03).build()
        val (dest, _) = decodeInto(data, 1, 1)
        assertEquals(0xFF, (dest[0] ushr 24) and 0xFF)
    }

    // ── raw tile (subencoding 0) ─────────────────────────────────────────

    @Test
    fun aRawTileIsReadInRowMajorOrder() {
        val data = Tile()
            .byte(0)
            .cpixel(1, 0, 0).cpixel(2, 0, 0)
            .cpixel(3, 0, 0).cpixel(4, 0, 0)
            .build()
        val (dest, written) = decodeInto(data, 2, 2)
        assertEquals(4, written)
        assertEquals(argb(1, 0, 0), dest[0])
        assertEquals(argb(2, 0, 0), dest[1])
        assertEquals(argb(3, 0, 0), dest[2])
        assertEquals(argb(4, 0, 0), dest[3])
    }

    // ── packed palette (subencodings 2..16) ──────────────────────────────

    @Test
    fun aTwoColourTilePacksOneBitPerPixelMostSignificantFirst() {
        // 4 pixels: indices 0,1,1,0 → 0b0110_0000 with MSB-first packing.
        val data = Tile()
            .byte(2)
            .cpixel(0, 0, 0)
            .cpixel(0xFF, 0xFF, 0xFF)
            .byte(0b0110_0000)
            .build()
        val (dest, written) = decodeInto(data, 4, 1)
        assertEquals(4, written)
        assertEquals(argb(0, 0, 0), dest[0])
        assertEquals(argb(0xFF, 0xFF, 0xFF), dest[1])
        assertEquals(argb(0xFF, 0xFF, 0xFF), dest[2])
        assertEquals(argb(0, 0, 0), dest[3])
    }

    @Test
    fun aFourColourTilePacksTwoBitsPerPixel() {
        // indices 0,1,2,3 → 0b00_01_10_11
        val data = Tile()
            .byte(4)
            .cpixel(0, 0, 0).cpixel(1, 0, 0).cpixel(2, 0, 0).cpixel(3, 0, 0)
            .byte(0b00_01_10_11)
            .build()
        val (dest, _) = decodeInto(data, 4, 1)
        assertEquals(listOf(0, 1, 2, 3).map { argb(it, 0, 0) }, dest.toList())
    }

    @Test
    fun aSixteenColourTilePacksFourBitsPerPixel() {
        val tile = Tile().byte(16)
        repeat(16) { tile.cpixel(it, 0, 0) }
        tile.byte(0x0F).byte(0xA3) // indices 0,15,10,3
        val (dest, _) = decodeInto(tile.build(), 4, 1)
        assertEquals(listOf(0, 15, 10, 3).map { argb(it, 0, 0) }, dest.toList())
    }

    @Test
    fun eachPackedRowRestartsOnAByteBoundary() {
        // The trap in the packed subencoding: RFB pads every ROW to a byte, so a
        // 3-wide 2-colour tile uses one byte per row with 5 bits wasted. A decoder
        // that carried the bit cursor across rows would read row 2's pixels from
        // row 1's tail bits and shift every row but the first — a picture that looks
        // almost right, which is why it needs an explicit test.
        val data = Tile()
            .byte(2)
            .cpixel(0, 0, 0)
            .cpixel(9, 0, 0)
            .byte(0b101_00000) // row 0: 9, 0, 9
            .byte(0b010_00000) // row 1: 0, 9, 0
            .build()
        val (dest, written) = decodeInto(data, 3, 2)
        assertEquals(6, written)
        assertEquals(
            listOf(
                argb(9, 0, 0), argb(0, 0, 0), argb(9, 0, 0),
                argb(0, 0, 0), argb(9, 0, 0), argb(0, 0, 0)
            ),
            dest.toList()
        )
    }

    // ── plain RLE (subencoding 128) ──────────────────────────────────────

    @Test
    fun plainRleFillsRunsOfCpixels() {
        val data = Tile()
            .byte(128)
            .cpixel(1, 0, 0).runLength(3)
            .cpixel(2, 0, 0).runLength(1)
            .build()
        val (dest, written) = decodeInto(data, 4, 1)
        assertEquals(4, written)
        assertEquals(
            listOf(argb(1, 0, 0), argb(1, 0, 0), argb(1, 0, 0), argb(2, 0, 0)),
            dest.toList()
        )
    }

    @Test
    fun aRunSpansRowsRatherThanStoppingAtTheRowEnd() {
        // Runs are over the tile's linear pixel order, which is where RLE earns its
        // keep on a flat tile. Clamping a run to a row would leave the rest of the
        // tile unwritten AND consume the wrong number of bytes.
        val data = Tile().byte(128).cpixel(7, 0, 0).runLength(6).build()
        val (dest, written) = decodeInto(data, 3, 2)
        assertEquals(6, written)
        dest.forEach { assertEquals(argb(7, 0, 0), it) }
    }

    @Test
    fun aRunLengthOfExactlyTwoHundredFiftySixIsDecoded() {
        // 256 encodes as `255, 0`: the 255 says "continue", the 0 adds nothing. A
        // decoder that treated a 0 as a terminator-with-no-continuation, or dropped
        // the +1, is wrong only at this exact length.
        val data = Tile().byte(128).cpixel(5, 0, 0).byte(255).byte(0).build()
        val (dest, written) = decodeInto(data, 16, 16)
        assertEquals(256, written)
        dest.forEach { assertEquals(argb(5, 0, 0), it) }
    }

    @Test
    fun aSingleByteRunLengthIsOffsetByOne() {
        // Length is transmitted as length-1, so byte 0 means a run of ONE.
        val data = Tile()
            .byte(128)
            .cpixel(1, 0, 0).byte(0)
            .cpixel(2, 0, 0).byte(0)
            .build()
        val (dest, _) = decodeInto(data, 2, 1)
        assertEquals(listOf(argb(1, 0, 0), argb(2, 0, 0)), dest.toList())
    }

    // ── palette RLE (subencodings 130..255) ──────────────────────────────

    @Test
    fun paletteRleReadsARunOnlyWhenTheHighBitIsSet() {
        // 130 = RLE with a 2-entry palette. Index byte 0x00 is "entry 0, run of 1";
        // 0x81 is "entry 1, run length follows".
        val data = Tile()
            .byte(130)
            .cpixel(1, 0, 0)
            .cpixel(2, 0, 0)
            .byte(0x00)
            .byte(0x81).runLength(3)
            .build()
        val (dest, written) = decodeInto(data, 4, 1)
        assertEquals(4, written)
        assertEquals(
            listOf(argb(1, 0, 0), argb(2, 0, 0), argb(2, 0, 0), argb(2, 0, 0)),
            dest.toList()
        )
    }

    @Test
    fun theRunFlagIsMaskedOffBeforeIndexingThePalette() {
        // The classic ZRLE defect: reading 0x81 as index 129 instead of index 1
        // reads past the palette, so precisely the runs that compress best come out
        // the wrong colour.
        val data = Tile()
            .byte(131) // 3-entry palette
            .cpixel(1, 0, 0).cpixel(2, 0, 0).cpixel(3, 0, 0)
            .byte(0x82).runLength(2) // entry 2, run of 2
            .build()
        val (dest, _) = decodeInto(data, 2, 1)
        assertEquals(listOf(argb(3, 0, 0), argb(3, 0, 0)), dest.toList())
    }

    // ── multi-tile rectangles ────────────────────────────────────────────

    @Test
    fun aRectangleWiderThanOneTileIsSlicedIntoTilesLeftToRightThenDown() {
        // 65×1 is two tiles: 64 wide then 1 wide. Getting the slice order or the
        // final tile's width wrong writes the second tile at the wrong x.
        val data = Tile()
            .byte(1).cpixel(1, 0, 0)  // tile 0: 64 px
            .byte(1).cpixel(2, 0, 0)  // tile 1: 1 px
            .build()
        val (dest, written) = decodeInto(data, 65, 1)
        assertEquals(65, written)
        (0 until 64).forEach { assertEquals(argb(1, 0, 0), dest[it]) }
        assertEquals(argb(2, 0, 0), dest[64])
    }

    @Test
    fun tilesAreOffsetByTheRectangleOrigin() {
        // The §1.114 defect class: a rectangle decoded as if it were at the origin.
        val data = Tile().byte(1).cpixel(4, 0, 0).build()
        val (dest, written) = decodeInto(
            data, destWidth = 4, destHeight = 4, x = 1, y = 2, width = 2, height = 1
        )
        assertEquals(2, written)
        assertEquals(argb(4, 0, 0), dest[2 * 4 + 1])
        assertEquals(argb(4, 0, 0), dest[2 * 4 + 2])
        assertEquals(0, dest[0])
    }

    // ── bounds and malformed input ───────────────────────────────────────

    @Test
    fun pixelsOutsideTheFramebufferAreDroppedRatherThanThrowing() {
        // Same rule as VncPixels.decodeArgbInto: an overrunning rectangle means a geometry
        // disagreement, and the payload has already been read, so the stream is
        // still aligned. Killing a usable session over cosmetic damage is worse.
        val data = Tile().byte(1).cpixel(6, 0, 0).build()
        val (dest, written) = decodeInto(
            data, destWidth = 2, destHeight = 2, x = 1, y = 1, width = 2, height = 2
        )
        assertEquals(1, written)
        assertEquals(argb(6, 0, 0), dest[3])
    }

    @Test
    fun aFullyOutOfBoundsRectangleWritesNothing() {
        // written == 0 is what tells VncClient not to mark a dirty region, which
        // would otherwise enlarge every later upload for pixels that never landed.
        val data = Tile().byte(1).cpixel(6, 0, 0).build()
        val (_, written) = decodeInto(
            data, destWidth = 2, destHeight = 2, x = 10, y = 10, width = 2, height = 2
        )
        assertEquals(0, written)
    }

    @Test
    fun aTruncatedTileStreamFailsLoud() {
        // Unlike an out-of-bounds pixel, running out of bytes means the stream is
        // not the shape the tile headers describe — there is no way to find the next
        // tile boundary, so continuing decodes every later pixel from a wrong
        // offset. §1.114 settled that class of ambiguity by failing.
        val data = Tile().byte(1).byte(0x11).build() // solid tile, CPIXEL cut short
        val e = assertThrows(IllegalStateException::class.java) {
            decodeInto(data, 1, 1)
        }
        assertTrue("message should name the encoding: ${e.message}", e.message!!.contains("ZRLE"))
    }

    @Test
    fun aReservedSubencodingFailsLoud() {
        // 17..127 are unused, and their tile length is therefore unknowable.
        val data = Tile().byte(17).build()
        assertThrows(IllegalStateException::class.java) { decodeInto(data, 1, 1) }
    }

    @Test
    fun rleWithASingleEntryPaletteFailsLoud() {
        // 129 is reserved: a solid tile (subencoding 1) already covers it.
        val data = Tile().byte(129).cpixel(1, 0, 0).build()
        assertThrows(IllegalStateException::class.java) { decodeInto(data, 1, 1) }
    }

    @Test
    fun aZeroExtentRectangleWritesNothingAndReadsNothing() {
        val (_, written) = decodeInto(ByteArray(0), 4, 4, x = 0, y = 0, width = 0, height = 0)
        assertEquals(0, written)
    }

    @Test
    fun onlyTheDeclaredLengthOfTheBufferIsRead() {
        // VncClient reuses one inflate buffer across rectangles, so the bytes after
        // dataLength are the PREVIOUS rectangle's. Reading them would decode the
        // second tile of this rectangle from stale data instead of failing — a wrong
        // picture with no error, which is the whole failure mode this file guards.
        val oneTile = Tile().byte(1).cpixel(3, 0, 0).build()
        val padded = oneTile + Tile().byte(1).cpixel(9, 0, 0).build() // stale tail
        val dest = IntArray(65)
        // 65 wide = two tiles, but only one tile's bytes are declared valid.
        assertThrows(IllegalStateException::class.java) {
            VncZrle.decode(
                data = padded,
                dataLength = oneTile.size,
                dest = dest,
                destWidth = 65,
                destHeight = 1,
                x = 0,
                y = 0,
                width = 65,
                height = 1
            )
        }
    }
}
