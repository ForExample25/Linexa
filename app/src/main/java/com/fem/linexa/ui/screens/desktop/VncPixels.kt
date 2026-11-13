package com.fem.linexa.ui.screens.desktop

/**
 * §1.114 — Pure (JVM-testable) RFB pixel handling for [VncClient].
 *
 * Extracted for the same reason `XKeysyms`/[DesktopSessionProvisioner] are pure
 * objects: the framebuffer composition rules are exactly the part of a VNC
 * client that fails *silently* when wrong (a wrong keysym is obvious, a
 * mis-composed frame just looks like a slow or glitchy connection), so they must
 * be assertable without a socket.
 *
 * What this fixes, provable from the pre-§1.114 source: `readRectangle` read a
 * rectangle's `x`/`y`/`width`/`height` and then published the rectangle's pixels
 * as if they were the *whole* framebuffer. RFB's very first update is usually one
 * full-screen rectangle, so a first frame appeared to work — but every
 * subsequent incremental update is a small dirty rectangle, and publishing it as
 * a whole frame produced a buffer smaller than `width * height * 4`, which the
 * viewer's size guard then skipped entirely. The visible result is a desktop that
 * draws once and then never updates again.
 *
 * §1.164 merged the Raw decode and the copy that followed it into
 * [decodeArgbInto]. See that function for why the intermediate buffer was
 * removable; the composition *rules* — where a rectangle lands, what happens when it
 * overruns, how many pixels count as written — are unchanged, which is what the
 * pre-existing tests in [VncPixelsTest] hold in place.
 */
internal object VncPixels {

    /** Bytes per pixel in the format [VncClient] requests via SetPixelFormat. */
    const val BYTES_PER_PIXEL = 4

    /**
     * Decodes one Raw rectangle straight from the wire bytes into [dest] at
     * `(x, y)`, clipping to the destination's bounds.
     *
     * ## Pixel format
     *
     * [bytes] holds `srcWidth × srcHeight` little-endian BGRX pixels, row-major.
     * The alpha byte is **not** read from the wire, deliberately: the requested
     * pixel format is 32 bpp / depth 24 with shifts 16/8/0, which leaves the fourth
     * byte of each pixel undefined — a server that zero-fills it (a normal choice)
     * made every pixel fully transparent in the pre-§1.114 viewer, i.e. a correctly
     * received frame rendered as a black screen. Depth 24 means there is no
     * transparency to preserve, so opacity is the only correct interpretation.
     *
     * ## Why this is one function and not a decode plus a copy
     *
     * §1.164 merged it. Until then the Raw path allocated an
     * `IntArray(width * height)`, filled it in one pass, and `System.arraycopy`d its
     * rows into the framebuffer in a second — so a full-screen 1920×1080 update
     * allocated 8.3 MB of garbage per frame on the receive loop and touched every
     * pixel twice, once to convert it and once to move it. The audit's §11.2 named
     * that allocation the dominant source of GC pressure on the frame path and §16.4
     * named the merge as the fix. The destination offsets were always known before
     * the first pixel was converted; the intermediate buffer bought nothing but a
     * second traversal.
     *
     * The clipping is load-bearing and is preserved exactly. A rectangle that
     * overruns the framebuffer means the server and this client disagree about
     * geometry, and dropping the out-of-bounds pixels degrades the image while
     * keeping the stream aligned (the payload has already been read). Throwing here
     * would kill an otherwise usable session over cosmetic damage. Note the saving
     * compounds with it: a clipped row is now never converted at all, where before
     * the whole rectangle was converted and the surplus then discarded by the copy.
     *
     * A **truncated payload** is the opposite case and does throw — see
     * [requireLength]. Rows are converted in the order the wire sends them, so a
     * later rectangle in the same update still overwrites an earlier one where they
     * overlap, which is what RFB requires.
     *
     * @param bytes the rectangle's wire payload; at least
     *   `srcWidth * srcHeight * BYTES_PER_PIXEL` bytes.
     * @return the number of pixels actually written, so the caller can record a
     *   dirty region only when something landed.
     */
    fun decodeArgbInto(
        dest: IntArray,
        destWidth: Int,
        destHeight: Int,
        x: Int,
        y: Int,
        srcWidth: Int,
        srcHeight: Int,
        bytes: ByteArray
    ): Int {
        if (destWidth <= 0 || destHeight <= 0 || srcWidth <= 0 || srcHeight <= 0) return 0
        requireLength(bytes, srcWidth, srcHeight)
        var written = 0
        for (row in 0 until srcHeight) {
            val destY = y + row
            if (destY < 0 || destY >= destHeight) continue
            val copyWidth = minOf(srcWidth, destWidth - x)
            if (copyWidth <= 0) continue
            var byteOffset = row * srcWidth * BYTES_PER_PIXEL
            var destIndex = destY * destWidth + x
            for (col in 0 until copyWidth) {
                val b = bytes[byteOffset].toInt() and 0xFF
                val g = bytes[byteOffset + 1].toInt() and 0xFF
                val r = bytes[byteOffset + 2].toInt() and 0xFF
                dest[destIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                byteOffset += BYTES_PER_PIXEL
                destIndex++
            }
            written += copyWidth
        }
        return written
    }

    /**
     * Rejects a payload shorter than the rectangle header claims.
     *
     * Checked once up front rather than per pixel, and it throws rather than
     * decoding what it can: unlike an out-of-bounds *pixel* (dropped, see
     * [decodeArgbInto]), a short payload means the byte count this client read from
     * the socket does not match the rectangle it was told to expect, so the stream
     * position is already wrong and every rectangle after it would decode from a
     * wrong offset — the §1.114 silent-desync failure mode, which that section
     * settled by failing loud. Same rule as [VncZrle] on a truncated tile stream.
     *
     * Unreachable from [VncClient] as it stands, which allocates the payload from
     * the same extents it passes here; it guards the function, not the current
     * caller.
     */
    private fun requireLength(bytes: ByteArray, srcWidth: Int, srcHeight: Int) {
        val required = srcWidth * srcHeight * BYTES_PER_PIXEL
        if (bytes.size < required) {
            throw IllegalStateException(
                "Raw rectangle truncated: ${srcWidth}x$srcHeight needs $required bytes, got ${bytes.size}"
            )
        }
    }

    /**
     * Copies a rectangle already present in [dest] from `(srcX, srcY)` to
     * `(dstX, dstY)` — the CopyRect encoding (RFB 3.8 §7.7.2).
     *
     * Rows are copied in an order chosen from the vertical direction of the move
     * so an *overlapping* source and destination cannot read a row this same
     * call already overwrote (scrolling a window is precisely an overlapping
     * copy, so getting this wrong smears the screen rather than failing).
     */
    fun copyRect(
        dest: IntArray,
        destWidth: Int,
        destHeight: Int,
        srcX: Int,
        srcY: Int,
        dstX: Int,
        dstY: Int,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        val rows = if (dstY > srcY) (height - 1) downTo 0 else 0 until height
        for (row in rows) {
            val from = srcY + row
            val to = dstY + row
            if (from < 0 || from >= destHeight || to < 0 || to >= destHeight) continue
            val copyWidth = minOf(width, destWidth - maxOf(srcX, dstX))
            if (copyWidth <= 0) continue
            System.arraycopy(
                dest,
                from * destWidth + srcX,
                dest,
                to * destWidth + dstX,
                copyWidth
            )
        }
    }
}

/**
 * §1.114 — one published desktop frame: the full framebuffer, plus a
 * monotonically increasing [generation].
 *
 * [pixels] is the client's live backing array, not a copy — a 1280×720 frame is
 * 3.6 MB and copying it on every dirty rectangle would cost more than drawing
 * it. [generation] exists so a `StateFlow` still emits when the *contents*
 * changed but the array identity did not (a `StateFlow<IntArray>` would
 * deduplicate an in-place update and the screen would freeze).
 *
 * §1.147 adds [dirty]: the region of [pixels] known to have changed since the
 * consumer last acknowledged an upload. It is the bounding box of every
 * rectangle in every update the consumer has not yet confirmed — **not** of this
 * one update — because `StateFlow` conflates and the consumer ([VncSurfaceView])
 * copies one frame at a time, so it does drop intermediate frames; a per-frame
 * rectangle would describe only the last update of a burst and leave the dropped
 * ones' pixels on screen as stale. See [VncDirtyRegion] for the full argument.
 *
 * [VncRect.EMPTY] means "not described" and must be read as *upload everything*.
 */
data class VncFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val generation: Long,
    val dirty: VncRect = VncRect.EMPTY
) {
    // Identity-based equals/hashCode: comparing 921 600 ints on every emission
    // would defeat the point of the generation counter. `dirty` is deliberately
    // NOT compared — two frames with the same generation are the same frame, and
    // the region is derived state that must not make one look like a new one.
    override fun equals(other: Any?): Boolean =
        other is VncFrame &&
            other.generation == generation &&
            other.width == width &&
            other.height == height &&
            other.pixels === pixels

    override fun hashCode(): Int = generation.hashCode() * 31 + width * 31 + height
}
