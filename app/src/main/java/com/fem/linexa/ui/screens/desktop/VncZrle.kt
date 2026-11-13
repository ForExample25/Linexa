package com.fem.linexa.ui.screens.desktop

/**
 * §1.151 — ZRLE tile decoding (RFB 3.8 §7.7.6), pure and JVM-testable.
 *
 * ## Why ZRLE and not Hextile or RRE
 *
 * Pre-§1.151 the client advertised only Raw and CopyRect, so every changed pixel
 * crossed the socket uncompressed: a full 1920×1080 repaint is 8.3 MB on the wire
 * per frame, and bandwidth scaled linearly with dirty-pixel count. ZRLE is the
 * cheapest real fix available here — it is *zlib* over a run-length/palette tile
 * encoding, and `java.util.zip.Inflater` is in the platform, so the compression
 * half costs no new dependency. Hextile (the acceptable fallback) has no
 * compression at all and only tiles; Tight needs JPEG plus its own zlib stream
 * set; RRE is strictly weaker than both. ZRLE is also what every server this app
 * can plausibly talk to (TigerVNC/TightVNC/x11vnc via `Xvnc`) implements.
 *
 * ## Why the tile decode is separated from the inflate
 *
 * The zlib stream is **per connection**, not per rectangle, so it must live in
 * [VncClient] alongside the socket ([VncClient] owns the `Inflater`; a fresh one
 * per rectangle would fail on the second rectangle, because a ZRLE server flushes
 * but never restarts the stream). Everything *after* inflation is arithmetic, and
 * arithmetic is exactly the part of a VNC client that fails silently — a
 * mis-packed palette index or an off-by-one run length produces a picture that
 * looks like a glitchy connection rather than an error. Same rule and same reason
 * as [VncPixels] (§1.114).
 *
 * ## Pixel format
 *
 * Tiles carry CPIXELs, not PIXELs. [VncClient.sendSetPixelFormat] asks for
 * 32 bpp / depth 24 / little-endian with shifts 16-8-0, which is precisely the
 * case RFB defines as a **3-byte** CPIXEL: the unused fourth byte is dropped, and
 * the remaining three are the little-endian least-significant bytes, i.e. B, G, R
 * — the same order (minus the padding byte) that [VncPixels.decodeArgbInto] reads
 * for Raw. Alpha is forced opaque for the same reason it is there: at depth 24 there
 * is no transparency on the wire, and a zero-filled alpha renders a correctly
 * received frame as a black screen.
 */
internal object VncZrle {

    /** RFB fixes the ZRLE tile grid at 64×64 (§7.7.6). Not negotiable. */
    const val TILE_SIZE = 64

    /** Bytes per CPIXEL in the format [VncClient] requests. See the class kdoc. */
    const val BYTES_PER_CPIXEL = 3

    /**
     * Decodes the inflated tile stream for one rectangle into [dest].
     *
     * @param data inflated tile bytes; only the first [dataLength] are read.
     * @param dest the persistent framebuffer, in ARGB_8888.
     * @param x rectangle origin in framebuffer coordinates.
     * @param width rectangle extent; the tile grid is relative to `(x, y)`.
     * @return the number of pixels actually written, so the caller can record a
     *   dirty region only when something landed — the [VncPixels.decodeArgbInto]
     *   contract.
     *
     * Out-of-bounds pixels are dropped rather than throwing, exactly as
     * [VncPixels.decodeArgbInto] clips: the payload has already been read, the
     * stream is still aligned, and killing a usable session over cosmetic damage is
     * worse than the damage. A **malformed** stream is a different matter and does
     * throw — see [Decoder.readByte].
     */
    fun decode(
        data: ByteArray,
        dataLength: Int,
        dest: IntArray,
        destWidth: Int,
        destHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Int {
        if (width <= 0 || height <= 0) return 0
        if (destWidth <= 0 || destHeight <= 0) return 0
        return Decoder(data, dataLength, dest, destWidth, destHeight).decodeRect(x, y, width, height)
    }

    /**
     * One rectangle's decode in progress.
     *
     * A class rather than a set of functions threading an offset through their
     * return values: every subencoding advances the same cursor, and a cursor
     * passed by value is the shape that lets one branch forget to return its new
     * position — which desyncs the *rest of the rectangle* rather than failing.
     * Instance-per-call, so the shared [VncZrle] object stays stateless and the
     * receive loop needs no lock.
     */
    private class Decoder(
        private val data: ByteArray,
        private val dataLength: Int,
        private val dest: IntArray,
        private val destWidth: Int,
        private val destHeight: Int
    ) {
        private var offset = 0
        private var written = 0

        /** Reused across every tile of this rectangle: 16 CPIXELs is the maximum
         * packed-palette size, and 127 the maximum RLE palette size. */
        private val palette = IntArray(128)

        fun decodeRect(x: Int, y: Int, width: Int, height: Int): Int {
            var tileY = y
            while (tileY < y + height) {
                val tileHeight = minOf(TILE_SIZE, y + height - tileY)
                var tileX = x
                while (tileX < x + width) {
                    val tileWidth = minOf(TILE_SIZE, x + width - tileX)
                    decodeTile(tileX, tileY, tileWidth, tileHeight)
                    tileX += TILE_SIZE
                }
                tileY += TILE_SIZE
            }
            return written
        }

        private fun decodeTile(x: Int, y: Int, width: Int, height: Int) {
            val subencoding = readByte()
            val rle = (subencoding and 0x80) != 0
            val paletteSize = subencoding and 0x7F

            when {
                // Raw: the tile verbatim, in CPIXELs. Still a win over the Raw
                // *encoding* because the whole tile stream is zlib-compressed.
                !rle && paletteSize == 0 -> decodeRawTile(x, y, width, height)

                // Solid: one CPIXEL for the entire tile. The commonest tile on a
                // real desktop, and 4 bytes for up to 4096 pixels.
                !rle && paletteSize == 1 -> {
                    val colour = readCpixel()
                    for (row in 0 until height) {
                        for (col in 0 until width) setPixel(x + col, y + row, colour)
                    }
                }

                !rle && paletteSize <= 16 -> decodePackedPaletteTile(x, y, width, height, paletteSize)

                // §7.7.6 leaves 17..127 (non-RLE) unused, and RLE with a
                // one-entry palette (129) is meaningless — a solid tile already
                // covers it. Both mean the stream is not what we think it is, and
                // the remaining tile lengths are therefore unknowable: fail loud
                // rather than decode the rest of the frame from a wrong offset.
                !rle -> throw IllegalStateException(
                    "ZRLE tile uses reserved subencoding $subencoding"
                )

                // Plain RLE: (CPIXEL, run-length) pairs, no palette.
                paletteSize == 0 -> decodeRleTile(x, y, width, height)

                paletteSize == 1 -> throw IllegalStateException(
                    "ZRLE tile uses reserved subencoding $subencoding"
                )

                else -> decodePaletteRleTile(x, y, width, height, paletteSize)
            }
        }

        private fun decodeRawTile(x: Int, y: Int, width: Int, height: Int) {
            for (row in 0 until height) {
                for (col in 0 until width) setPixel(x + col, y + row, readCpixel())
            }
        }

        /**
         * Palette sizes 2..16: the palette, then one index per pixel packed into
         * 1, 2 or 4 bits, most-significant bits first, with **each row padded to a
         * byte boundary**.
         *
         * The padding is the trap: a tile whose width is not a multiple of the
         * indices-per-byte would otherwise carry the next row's first pixels in the
         * tail bits of the current row's last byte, and every row after the first
         * would be shifted. Resetting the bit cursor per row is what avoids it.
         */
        private fun decodePackedPaletteTile(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            paletteSize: Int
        ) {
            readPalette(paletteSize)
            val bitsPerIndex = when {
                paletteSize <= 2 -> 1
                paletteSize <= 4 -> 2
                else -> 4
            }
            val mask = (1 shl bitsPerIndex) - 1
            for (row in 0 until height) {
                var current = 0
                var bitsLeft = 0
                for (col in 0 until width) {
                    if (bitsLeft == 0) {
                        current = readByte()
                        bitsLeft = 8
                    }
                    bitsLeft -= bitsPerIndex
                    val index = (current shr bitsLeft) and mask
                    setPixel(x + col, y + row, palette[index.coerceIn(0, paletteSize - 1)])
                }
            }
        }

        /** Subencoding 128: runs of (CPIXEL, length) until the tile is full. */
        private fun decodeRleTile(x: Int, y: Int, width: Int, height: Int) {
            val total = width * height
            var filled = 0
            while (filled < total) {
                val colour = readCpixel()
                val run = minOf(readRunLength(), total - filled)
                fillRun(x, y, width, filled, run, colour)
                filled += run
            }
        }

        /**
         * Subencodings 130..255: a palette of `subencoding - 128` CPIXELs, then
         * index bytes where **bit 7 means "a run length follows"** and its absence
         * means a run of exactly one pixel. Reading the index without masking that
         * bit off is the classic ZRLE defect: it silently picks palette entry
         * `index + 128`, i.e. garbage colours on precisely the runs that compress
         * best.
         */
        private fun decodePaletteRleTile(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            paletteSize: Int
        ) {
            readPalette(paletteSize)
            val total = width * height
            var filled = 0
            while (filled < total) {
                val byte = readByte()
                val index = (byte and 0x7F).coerceIn(0, paletteSize - 1)
                val run = if ((byte and 0x80) != 0) {
                    minOf(readRunLength(), total - filled)
                } else {
                    1
                }
                fillRun(x, y, width, filled, run, palette[index])
                filled += run
            }
        }

        /**
         * Writes [run] pixels starting at the [start]-th pixel of the tile, in
         * row-major order.
         *
         * A run legitimately spans rows — that is where RLE earns its keep on a
         * flat-coloured tile — so the linear tile index is converted per pixel
         * rather than the run being assumed to fit one row.
         */
        private fun fillRun(
            tileX: Int,
            tileY: Int,
            tileWidth: Int,
            start: Int,
            run: Int,
            colour: Int
        ) {
            for (i in start until start + run) {
                setPixel(tileX + i % tileWidth, tileY + i / tileWidth, colour)
            }
        }

        private fun readPalette(size: Int) {
            for (i in 0 until size) palette[i] = readCpixel()
        }

        /**
         * A run length is encoded as `length - 1` in a series of bytes, each 255
         * meaning "add 255 and continue". So a run of 256 is `255, 0` — and
         * treating a trailing 0 as a terminator-with-no-value (or forgetting the
         * `+ 1`) is an off-by-one that only shows up on runs of exactly 256.
         */
        private fun readRunLength(): Int {
            var length = 1
            while (true) {
                val byte = readByte()
                length += byte
                if (byte != 0xFF) return length
            }
        }

        private fun readCpixel(): Int {
            if (offset + BYTES_PER_CPIXEL > dataLength) {
                throw IllegalStateException(
                    "ZRLE tile data truncated: need $BYTES_PER_CPIXEL bytes at $offset of $dataLength"
                )
            }
            val b = data[offset].toInt() and 0xFF
            val g = data[offset + 1].toInt() and 0xFF
            val r = data[offset + 2].toInt() and 0xFF
            offset += BYTES_PER_CPIXEL
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Throws on a short stream rather than returning a filler byte.
         *
         * Unlike an out-of-bounds *pixel* (dropped, see [decode]), running out of
         * tile bytes means the inflated stream is not the shape the tile headers
         * describe. There is no way to find the next tile boundary from there, so
         * every pixel after it would be decoded from a wrong offset — the §1.114
         * silent-desync failure mode, which that section settled by failing loud.
         */
        private fun readByte(): Int {
            if (offset >= dataLength) {
                throw IllegalStateException("ZRLE tile data truncated at $offset of $dataLength")
            }
            return data[offset++].toInt() and 0xFF
        }

        private fun setPixel(x: Int, y: Int, colour: Int) {
            if (x < 0 || x >= destWidth || y < 0 || y >= destHeight) return
            dest[y * destWidth + x] = colour
            written++
        }
    }
}
