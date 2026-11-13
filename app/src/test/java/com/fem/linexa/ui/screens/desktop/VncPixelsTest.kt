package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.114 — JVM tests for [VncPixels], the framebuffer composition rules.
 *
 * These exist because every defect in this area is *silent*: a mis-positioned
 * blit, a zero alpha, or a backwards overlapping CopyRect all produce a session
 * that connects, authenticates, and then simply looks wrong or frozen. There is
 * no exception and no log line to notice.
 *
 * §1.164 merged the Raw decode with the copy that followed it. Every property the
 * two separate functions asserted is re-asserted below against the merged one —
 * wire byte order, forced opacity, placement at the rectangle's own offset,
 * clipping, the written count — because the merge is only safe if the rules did not
 * move. The cases that are *new* are the ones the merge makes reachable: a
 * partially clipped row must convert exactly the pixels it keeps, and a payload
 * shorter than the header claims must fail loud rather than decode part of a frame
 * from a desynced stream.
 */
class VncPixelsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Little-endian BGRX wire bytes for one pixel, with an explicit 4th byte. */
    private fun wirePixel(r: Int, g: Int, b: Int, fourth: Int) =
        byteArrayOf(b.toByte(), g.toByte(), r.toByte(), fourth.toByte())

    /**
     * Wire bytes for a rectangle whose pixels are `(r, g, b) = (i, i, i)`.
     *
     * The index is the value, so a mis-ordered or mis-offset row is visible as the
     * wrong number rather than as a plausible colour.
     */
    private fun wireRect(count: Int, fourth: Int = 0): ByteArray {
        val bytes = ByteArray(count * VncPixels.BYTES_PER_PIXEL)
        for (i in 0 until count) {
            val offset = i * VncPixels.BYTES_PER_PIXEL
            bytes[offset] = i.toByte()
            bytes[offset + 1] = i.toByte()
            bytes[offset + 2] = i.toByte()
            bytes[offset + 3] = fourth.toByte()
        }
        return bytes
    }

    // ---- decodeArgbInto: the wire format ----

    @Test
    fun decodesBgrxWireOrderIntoArgb() {
        val dest = IntArray(1)
        VncPixels.decodeArgbInto(
            dest, 1, 1, x = 0, y = 0, srcWidth = 1, srcHeight = 1,
            bytes = wirePixel(r = 0x12, g = 0x34, b = 0x56, fourth = 0)
        )
        assertEquals(argb(0xFF, 0x12, 0x34, 0x56), dest[0])
    }

    @Test
    fun forcesOpaqueAlphaWhenTheServerZeroFillsTheUndefinedByte() {
        // depth 24 leaves the 4th byte undefined; a zero-filling server made the
        // pre-§1.114 viewer draw a fully transparent (black) desktop.
        val dest = IntArray(1)
        VncPixels.decodeArgbInto(
            dest, 1, 1, x = 0, y = 0, srcWidth = 1, srcHeight = 1,
            bytes = wirePixel(r = 0xFF, g = 0xFF, b = 0xFF, fourth = 0x00)
        )
        assertEquals(0xFF, dest[0] ushr 24)
        assertEquals(argb(0xFF, 0xFF, 0xFF, 0xFF), dest[0])
    }

    @Test
    fun ignoresTheWireFourthByteEntirely() {
        val zeroFilled = IntArray(1)
        val oneFilled = IntArray(1)
        VncPixels.decodeArgbInto(
            zeroFilled, 1, 1, 0, 0, 1, 1, wirePixel(1, 2, 3, 0x00)
        )
        VncPixels.decodeArgbInto(
            oneFilled, 1, 1, 0, 0, 1, 1, wirePixel(1, 2, 3, 0x7F)
        )
        assertEquals(zeroFilled[0], oneFilled[0])
    }

    @Test
    fun decodesEveryPixelOfAMultiPixelRectangle() {
        val dest = IntArray(2)
        val bytes = wirePixel(1, 2, 3, 0) + wirePixel(4, 5, 6, 0)
        val written = VncPixels.decodeArgbInto(dest, 2, 1, 0, 0, srcWidth = 2, srcHeight = 1, bytes = bytes)
        assertEquals(2, written)
        assertEquals(argb(0xFF, 1, 2, 3), dest[0])
        assertEquals(argb(0xFF, 4, 5, 6), dest[1])
    }

    @Test
    fun readsTheWirePayloadInRowMajorOrder() {
        // The row stride on the SOURCE side is the rectangle's width, not the
        // framebuffer's. Using the destination width would read each row from the
        // wrong byte offset — a skew that still produces a plausible-looking image.
        val dest = IntArray(4 * 4)
        VncPixels.decodeArgbInto(
            dest, destWidth = 4, destHeight = 4, x = 1, y = 1,
            srcWidth = 2, srcHeight = 2, bytes = wireRect(4)
        )
        assertEquals(argb(0xFF, 0, 0, 0), dest[1 * 4 + 1])
        assertEquals(argb(0xFF, 1, 1, 1), dest[1 * 4 + 2])
        assertEquals(argb(0xFF, 2, 2, 2), dest[2 * 4 + 1])
        assertEquals(argb(0xFF, 3, 3, 3), dest[2 * 4 + 2])
    }

    // ---- decodeArgbInto: placement and clipping (the former blit contract) ----

    @Test
    fun decodePlacesTheRectangleAtItsOwnOffset() {
        // The core §1.114 bug: rectangles were published as whole frames, so
        // every incremental update after the first was dropped by the viewer.
        val dest = IntArray(4 * 4)
        val seven = argb(0xFF, 7, 7, 7)
        val bytes = wirePixel(7, 7, 7, 0) + wirePixel(7, 7, 7, 0) +
            wirePixel(7, 7, 7, 0) + wirePixel(7, 7, 7, 0)
        val written = VncPixels.decodeArgbInto(
            dest, 4, 4, x = 1, y = 2, srcWidth = 2, srcHeight = 2, bytes = bytes
        )

        assertEquals(4, written)
        assertEquals(seven, dest[2 * 4 + 1])
        assertEquals(seven, dest[2 * 4 + 2])
        assertEquals(seven, dest[3 * 4 + 1])
        assertEquals(seven, dest[3 * 4 + 2])
        // Everything outside the rectangle is untouched.
        assertEquals(0, dest[0])
        assertEquals(0, dest[2 * 4 + 0])
        assertEquals(0, dest[2 * 4 + 3])
    }

    @Test
    fun decodeLeavesPreviouslyReceivedPixelsIntact() {
        // A dirty-rectangle update must not erase the rest of the desktop.
        val dest = IntArray(3 * 3) { 5 }
        VncPixels.decodeArgbInto(
            dest, 3, 3, x = 0, y = 0, srcWidth = 1, srcHeight = 1,
            bytes = wirePixel(9, 9, 9, 0)
        )
        assertEquals(argb(0xFF, 9, 9, 9), dest[0])
        assertTrue(dest.drop(1).all { it == 5 })
    }

    @Test
    fun decodeClipsRatherThanThrowingWhenARectangleOverrunsTheFramebuffer() {
        val dest = IntArray(2 * 2)
        val written = VncPixels.decodeArgbInto(
            dest, 2, 2, x = 1, y = 1, srcWidth = 2, srcHeight = 2, bytes = wireRect(4)
        )
        assertEquals(1, written)
        // Only the rectangle's first pixel fits, at the framebuffer's last slot.
        assertEquals(argb(0xFF, 0, 0, 0), dest[3])
        assertTrue(dest.take(3).all { it == 0 })
    }

    @Test
    fun aPartiallyClippedRowConvertsOnlyThePixelsItKeeps() {
        // Made reachable by the merge: the decode now stops at the clip instead of
        // converting the whole rectangle and letting the copy discard the surplus.
        // A loop that kept converting past `copyWidth` would write a later row's
        // pixel into this row's tail.
        val dest = IntArray(3 * 2)
        val written = VncPixels.decodeArgbInto(
            dest, destWidth = 3, destHeight = 2, x = 1, y = 0,
            srcWidth = 3, srcHeight = 2, bytes = wireRect(6)
        )
        assertEquals(4, written)
        // Row 0 of the rectangle: pixels 0 and 1 land at x=1,2; pixel 2 is clipped.
        assertEquals(argb(0xFF, 0, 0, 0), dest[1])
        assertEquals(argb(0xFF, 1, 1, 1), dest[2])
        // Row 1 starts at wire pixel 3 — NOT at pixel 2, which was dropped.
        assertEquals(argb(0xFF, 3, 3, 3), dest[3 + 1])
        assertEquals(argb(0xFF, 4, 4, 4), dest[3 + 2])
        assertEquals(0, dest[0])
        assertEquals(0, dest[3 + 0])
    }

    @Test
    fun aRowBelowTheFramebufferIsSkippedWithoutShiftingTheRowsAboveIt() {
        // The source offset must be derived from the row index, not from a cursor
        // that only advances on rows that were written — otherwise a rectangle
        // straddling the bottom edge draws its lower rows' pixels in its upper rows.
        val dest = IntArray(2 * 2)
        val written = VncPixels.decodeArgbInto(
            dest, destWidth = 2, destHeight = 2, x = 0, y = 1,
            srcWidth = 2, srcHeight = 2, bytes = wireRect(4)
        )
        assertEquals(2, written)
        assertEquals(argb(0xFF, 0, 0, 0), dest[2])
        assertEquals(argb(0xFF, 1, 1, 1), dest[3])
        assertTrue(dest.take(2).all { it == 0 })
    }

    @Test
    fun decodeOfAFullFrameWritesEveryPixel() {
        val dest = IntArray(2 * 2)
        val written = VncPixels.decodeArgbInto(
            dest, 2, 2, x = 0, y = 0, srcWidth = 2, srcHeight = 2, bytes = wireRect(4)
        )
        assertEquals(4, written)
        assertEquals(
            listOf(
                argb(0xFF, 0, 0, 0),
                argb(0xFF, 1, 1, 1),
                argb(0xFF, 2, 2, 2),
                argb(0xFF, 3, 3, 3)
            ),
            dest.toList()
        )
    }

    @Test
    fun decodeOfAnEmptyRectangleWritesNothing() {
        val dest = IntArray(4)
        assertEquals(0, VncPixels.decodeArgbInto(dest, 2, 2, 0, 0, 0, 0, ByteArray(0)))
        assertTrue(dest.all { it == 0 })
    }

    @Test
    fun decodeIntoAnUnallocatedFramebufferWritesNothing() {
        // ServerInit allocates before any rectangle arrives, so this is a guard
        // rather than a live path — but it must not index into an empty array.
        assertEquals(
            0,
            VncPixels.decodeArgbInto(IntArray(0), 0, 0, 0, 0, 1, 1, wirePixel(1, 2, 3, 0))
        )
    }

    @Test
    fun aTruncatedPayloadFailsLoudRatherThanDecodingPartOfAFrame() {
        // Not the same case as an out-of-bounds pixel, which is dropped. A payload
        // shorter than the header claims means the bytes read off the socket do not
        // match the rectangle described, so the stream position is already wrong and
        // every rectangle after it would decode from a wrong offset — §1.114's
        // silent-desync mode, which that section settled by failing loud.
        val dest = IntArray(4)
        assertThrows(IllegalStateException::class.java) {
            VncPixels.decodeArgbInto(
                dest, 2, 2, x = 0, y = 0, srcWidth = 2, srcHeight = 2,
                bytes = wireRect(3)
            )
        }
    }

    @Test
    fun aLaterRectangleOverwritesAnEarlierOneWhereTheyOverlap() {
        // RFB orders the rectangles within one update, and the last write wins. The
        // merge must not reorder anything: the decode writes as it reads.
        val dest = IntArray(2 * 1)
        VncPixels.decodeArgbInto(dest, 2, 1, 0, 0, 2, 1, wirePixel(1, 1, 1, 0) + wirePixel(2, 2, 2, 0))
        VncPixels.decodeArgbInto(dest, 2, 1, 1, 0, 1, 1, wirePixel(9, 9, 9, 0))
        assertEquals(argb(0xFF, 1, 1, 1), dest[0])
        assertEquals(argb(0xFF, 9, 9, 9), dest[1])
    }

    // ---- copyRect ----

    @Test
    fun copyRectMovesAnExistingRegion() {
        // dest is 4x2:  row0 = 1 2 3 4, row1 = 0 0 0 0
        val dest = intArrayOf(1, 2, 3, 4, 0, 0, 0, 0)
        VncPixels.copyRect(dest, 4, 2, srcX = 0, srcY = 0, dstX = 0, dstY = 1, width = 4, height = 1)
        assertEquals(listOf(1, 2, 3, 4), dest.toList().subList(4, 8))
    }

    @Test
    fun copyRectHandlesADownwardOverlappingMoveWithoutSmearing() {
        // Scrolling a window is an overlapping copy; copying rows top-down here
        // would read rows this same call had already overwritten.
        val dest = intArrayOf(
            1, 1,
            2, 2,
            3, 3
        )
        VncPixels.copyRect(dest, 2, 3, srcX = 0, srcY = 0, dstX = 0, dstY = 1, width = 2, height = 2)
        assertEquals(listOf(1, 1, 1, 1, 2, 2), dest.toList())
    }

    @Test
    fun copyRectHandlesAnUpwardOverlappingMoveWithoutSmearing() {
        val dest = intArrayOf(
            1, 1,
            2, 2,
            3, 3
        )
        VncPixels.copyRect(dest, 2, 3, srcX = 0, srcY = 1, dstX = 0, dstY = 0, width = 2, height = 2)
        assertEquals(listOf(2, 2, 3, 3, 3, 3), dest.toList())
    }

    @Test
    fun copyRectIgnoresARectangleOutsideTheFramebuffer() {
        val dest = IntArray(4) { 1 }
        VncPixels.copyRect(dest, 2, 2, srcX = 0, srcY = 5, dstX = 0, dstY = 9, width = 2, height = 1)
        assertTrue(dest.all { it == 1 })
    }

    @Test
    fun copyRectOfZeroAreaIsANoOp() {
        val dest = intArrayOf(1, 2, 3, 4)
        VncPixels.copyRect(dest, 2, 2, 0, 0, 1, 1, width = 0, height = 0)
        assertEquals(listOf(1, 2, 3, 4), dest.toList())
    }

    // ---- VncFrame ----

    @Test
    fun framesWithTheSameArrayButNewGenerationsAreNotEqual() {
        // A StateFlow<IntArray> would deduplicate an in-place framebuffer update
        // and the screen would freeze; the generation counter is what prevents it.
        val pixels = IntArray(4)
        assertNotEquals(
            VncFrame(pixels, 2, 2, generation = 1),
            VncFrame(pixels, 2, 2, generation = 2)
        )
    }

    @Test
    fun framesWithTheSameGenerationAndArrayAreEqual() {
        val pixels = IntArray(4)
        assertEquals(
            VncFrame(pixels, 2, 2, generation = 3),
            VncFrame(pixels, 2, 2, generation = 3)
        )
    }

    @Test
    fun frameEqualityDoesNotComparePixelContents() {
        // Comparing ~1M ints per emission would defeat the point of the counter.
        assertNotEquals(
            VncFrame(IntArray(4), 2, 2, generation = 1),
            VncFrame(IntArray(4), 2, 2, generation = 1)
        )
    }
}
