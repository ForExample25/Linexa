package com.fem.linexa.ui.screens.desktop

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * §1.151 — the ZRLE zlib stream of one VNC connection.
 *
 * ## Why this is a class and not two lines in [VncClient]
 *
 * RFB 3.8 §7.7.6 makes the ZRLE zlib stream **continuous for the lifetime of the
 * connection**: the server flushes at each rectangle boundary (`Z_SYNC_FLUSH`) but
 * never resets the dictionary, and each rectangle's payload is a *slice* of that
 * one stream rather than a self-contained zlib document. A per-rectangle
 * `Inflater` therefore decodes the first rectangle perfectly and produces garbage
 * or throws from the second onwards.
 *
 * That is the whole reason this is a named, testable type. The defect it guards
 * against compiles, negotiates, connects, and draws a correct first frame — the
 * §1.114 failure shape, where everything looks right until it doesn't — and it is
 * unreachable from a test as long as the inflate lives inline in the socket loop.
 * Here it can be driven from a `Deflater` in [VncZlibStreamTest] with the same
 * flush discipline a real server uses.
 *
 * ## Ownership
 *
 * One instance per connection, touched only from the RFB receive loop (a single
 * coroutine on `Dispatchers.IO`), so nothing here is synchronised. [end] releases
 * the `Inflater`'s native memory and must be called when the socket closes;
 * reusing a stream across connections would decode the new server's bytes against
 * the old one's dictionary.
 */
internal class VncZlibStream {

    private val inflater = Inflater()

    /**
     * The inflated bytes of the most recent [inflate] call, valid up to its return
     * value.
     *
     * Reused and grown rather than reallocated per rectangle: ZRLE does not
     * transmit the *inflated* length (only the compressed one), so the size cannot
     * be known in advance, and reallocating a few MB per framebuffer update would
     * hand the per-frame cost straight back to the allocator that §1.150 exists to
     * take work away from. Exposed rather than copied out for the same reason
     * [VncFrame.pixels] is the live array.
     */
    var buffer: ByteArray = ByteArray(0)
        private set

    /**
     * Feeds one rectangle's [compressed] payload into the stream and inflates
     * everything it yields into [buffer].
     *
     * @return the number of valid bytes in [buffer].
     *
     * The loop is not a formality. `Inflater.inflate` returning 0 is ambiguous —
     * it means "out of input" (the normal end of a rectangle, since the server
     * flushed there), "the stream finished", or "needs a dictionary" — and only the
     * interrogation methods separate them. Treating every 0 as end-of-data
     * truncates any rectangle whose output happens to land exactly on a buffer
     * boundary; treating none as end-of-data spins forever, doubling the buffer.
     *
     * The interrogation **order** matters and is not obvious: `needsInput()` is
     * true whenever the input buffer is empty, which includes the case where the
     * stream also ended, so checking it first makes [Inflater.finished] unreachable
     * and turns a terminated stream into a silent run of empty rectangles — a frozen
     * desktop with no error. `finished()` is therefore checked first.
     */
    fun inflate(compressed: ByteArray): Int {
        inflater.setInput(compressed)
        if (buffer.isEmpty()) buffer = ByteArray(INITIAL_BUFFER_BYTES)

        var produced = 0
        while (true) {
            if (produced == buffer.size) {
                // Pre-release audit cap (2026-09-02, finding A2): the
                // compressed payload is bounded at 32 MB, but the INFLATED
                // output was unbounded — a 32 MB adversarial payload can
                // legally expand to gigabytes here (zlib-bomb shape), all on
                // the receive thread. Cap the output and fail the session
                // with an IllegalStateException (an Exception the receive
                // loop actually catches) instead of dying to OOM.
                if (buffer.size >= MAX_INFLATED_BUFFER_BYTES) {
                    throw IllegalStateException(
                        "ZRLE inflated output exceeds the ${MAX_INFLATED_BUFFER_BYTES / 1_000_000} MB cap — refusing to continue"
                    )
                }
                buffer = buffer.copyOf(buffer.size * 2)
            }
            val count = try {
                inflater.inflate(buffer, produced, buffer.size - produced)
            } catch (e: DataFormatException) {
                // The stream is shared by every rectangle of the session, so a
                // corrupt one cannot be recovered from by skipping it — §1.114
                // settled that a lost framing is fatal rather than something to
                // decode through.
                throw IllegalStateException("ZRLE zlib stream corrupt: ${e.message}", e)
            }
            produced += count
            if (count > 0) continue
            if (inflater.finished()) {
                // A ZRLE server flushes, never finishes, until the connection ends.
                // Whatever this rectangle produced is discarded with the session: no
                // further rectangle can be decoded, so drawing this one first would
                // only delay a failure the caller has to handle anyway.
                throw IllegalStateException("ZRLE zlib stream ended mid-session")
            }
            if (inflater.needsInput()) return produced
            throw IllegalStateException(
                "ZRLE zlib stream stalled (needsDictionary=${inflater.needsDictionary()})"
            )
        }
    }

    /** Releases the inflater's native memory. Safe to call more than once. */
    fun end() {
        inflater.end()
    }

    private companion object {
        /**
         * Initial scratch size, then doubled on demand. 64 KB is roughly five full
         * 64×64 tiles of CPIXELs — a typical incremental update — so the common case
         * never grows, and a full-screen rectangle grows it once and keeps it.
         */
        const val INITIAL_BUFFER_BYTES = 64 * 1024

        /**
         * A2 cap (2026-09-02): ceiling on the INFLATED scratch buffer. The
         * compressed input is bounded at 32 MB by the caller; without this
         * cap the doubling loop could expand it into gigabytes of heap.
         */
        const val MAX_INFLATED_BUFFER_BYTES = 256 * 1024 * 1024
    }
}
