package com.fem.linexa.ui.screens.desktop

/**
 * §1.147 — a half-open rectangle of framebuffer pixels.
 *
 * Deliberately not `android.graphics.Rect`: this value travels from the RFB
 * receive loop to the view and is asserted from JVM tests, and pulling an
 * Android type into [VncPixels]/[VncFrame] would make the frame contract
 * untestable without Robolectric — the same rule [VncTouchGesture] and
 * [VncRelativeCursor] follow.
 *
 * Public for the same reason [VncFrame] is: it is part of the frame contract
 * [VncClient.frame] publishes, and a public flow cannot carry an internal type.
 *
 * @param x left edge, inclusive.
 * @param y top edge, inclusive.
 * @param width columns covered; `0` means the rectangle is empty.
 * @param height rows covered; `0` means the rectangle is empty.
 */
data class VncRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        /**
         * "Nothing is known to have changed."
         *
         * Read by [VncSurfaceView] as *upload everything*, not as *upload
         * nothing*. An empty region reaching the view means the producer did not
         * describe its change (a resize, a legacy publish, a test-constructed
         * frame), and the only safe interpretation of an unknown change is the
         * whole framebuffer. Choosing the opposite default would turn any future
         * publish path that forgets to record its rectangles into a frozen
         * desktop — the §1.114 defect class, reintroduced silently.
         */
        val EMPTY = VncRect(0, 0, 0, 0)
    }
}

/**
 * §1.147 — the framebuffer rectangles changed since the view last confirmed an
 * upload, revised by §1.154 to release them **generation by generation**.
 *
 * ## Why an *acknowledged* region and not a per-frame one
 *
 * The obvious design — each [VncFrame] carries the rectangles that changed since
 * the previous frame — is wrong here, and wrong in a way that shows up as visible
 * corruption rather than as a test failure.
 *
 * [VncClient.frame] is a `StateFlow`, so it **conflates**: when the collector is
 * slower than the producer, intermediate values are dropped and never seen. The
 * collector *is* slower, structurally: [VncSurfaceView.submitFrame] copies one frame
 * at a time and holds only the newest of anything that arrives during a copy. A
 * per-frame dirty rectangle would therefore describe only the *last* update in a
 * burst, while the pixels of every dropped update sit in the shared framebuffer
 * unuploaded, permanently, until something else happens to touch those coordinates.
 *
 * So what the producer publishes is cumulative — everything not yet uploaded — and
 * it is cleared by the **consumer**, via [acknowledge], naming the generation it
 * actually uploaded.
 *
 * ## §1.154 — why the history is per generation
 *
 * §1.147 kept one bounding box and a single stamp, and [acknowledge] cleared it only
 * when the acknowledged generation was still the most recently published one. That
 * equality is the ghosting mechanism, and the failure is not a
 * lost frame but a **ratchet**:
 *
 *  - the interval between publishing *G* and acknowledging it spans two Main-queue
 *    turns, two thread hops and the pixel copy itself, and the receive loop
 *    re-requests an incremental update before it returns — so a publish landing
 *    inside that interval needs only one IO-side decode to beat it;
 *  - when one does, the acknowledgement was discarded **whole**, not rewound by one
 *    update, so the entire accumulated box survived;
 *  - the box only ever grew, so the next upload was larger, so the interval was
 *    wider, so the next acknowledgement was likelier to be discarded. The recovery
 *    path was the saturated path, which is what made it a degradation rather than a
 *    transient.
 *
 * The missing ingredient was named in this class's own §1.147 kdoc: "there is no
 * per-generation history to subtract the older ones from". §1.154 adds it. Each
 * [publish] closes the rectangles accumulated since the previous one into an entry
 * stamped with that generation, and [acknowledge] drops every entry at or below the
 * acknowledged generation. Rectangles from *newer* publishes are held, because the
 * view has not uploaded them; rectangles from older ones are released, because it
 * has. The ratchet cannot form: an acknowledgement that arrives late still retires
 * the work it covers.
 *
 * Ordering is what makes the sweep a `while` and not a search: [publish] is called
 * only from [VncClient.publishFrame] and [VncClient.resizeFramebuffer], both of
 * which pre-increment a single counter, so entries are appended in strictly
 * increasing generation order.
 *
 * ## Why over-reporting is still the deliberate bias
 *
 * Three places round *up*, and all three are the same choice:
 *
 *  - an entry is a bounding box, not a region list, so a cursor at one corner and a
 *    repaint at the other cover the screen between them. Uploading a superset of
 *    what changed costs pixels; uploading a subset is a wrong screen;
 *  - rectangles arriving after the most recent [publish] sit in an unstamped open
 *    entry that no [acknowledge] can release, because no consumer has been offered
 *    them yet;
 *  - the ring holds [MAX_PENDING_GENERATIONS] entries. On overflow the two oldest
 *    are merged and the merged entry takes the *newer* of the two generations, so an
 *    acknowledgement of the older one no longer releases it. The collapse loses
 *    precision, never coverage.
 *
 * That last case is bounded rather than instrumented on purpose. It degrades to
 * exactly §1.147's behaviour — a box that is too large — and the two counters
 * §1.153 already reports would show it: sustained collapse pushes
 * [VncRenderStats.Snapshot.meanUploadedFraction] back towards 1.0, which is the
 * number the fix is judged by.
 *
 * The same bias makes the pre-existing producer/consumer race on the framebuffer
 * array **self-healing**. `VncClient` blits into `pixels` on `Dispatchers.IO`
 * while the view copies out of it on its upload thread (a race that predates §1.147
 * and is not introduced by it — the array is shared by design, because copying
 * 3.6 MB per dirty rectangle costs more than drawing it, see [VncFrame]). A scoped
 * upload can therefore read a rectangle mid-blit. Because the update being written
 * will union its rectangles into the open entry afterwards, and that entry is
 * published under a later generation than the one being acknowledged, the region is
 * copied again on the very next upload.
 *
 * ## Thread safety
 *
 * [union] and [publish] run on the RFB receive loop (`Dispatchers.IO`);
 * [acknowledge] runs on Main, from the buffer swap that installs a completed copy
 * ([VncSurfaceView.promoteUploadedFrame]). Every method is therefore `synchronized`
 * on the instance — a single lock, held for a handful of integer comparisons, never
 * across IO. The ring lives inside that same lock rather than beside a `@Volatile`
 * stamp in `VncClient` so that "which generations does this region owe" and "what do
 * they cover" cannot be read inconsistently with respect to each other.
 */
internal class VncDirtyRegion {

    private var boundsWidth = 0
    private var boundsHeight = 0

    /**
     * Rectangles unioned since the last [publish], not yet offered to any consumer.
     *
     * Held apart from the ring because it has no generation to be acknowledged
     * against: releasing it would discard pixels the view was never told about.
     * Half-open [x0, x1) × [y0, y1), with sentinels chosen so the first union wins
     * every comparison and [isEmptyBox] reads false only after one.
     */
    private var openX0 = Int.MAX_VALUE
    private var openY0 = Int.MAX_VALUE
    private var openX1 = Int.MIN_VALUE
    private var openY1 = Int.MIN_VALUE

    /**
     * Published-but-unacknowledged entries, oldest first, as a ring of parallel
     * arrays.
     *
     * Parallel `IntArray`s and one `LongArray` rather than a list of objects: this is
     * allocated once per connection and touched on every published frame, and §1.150
     * exists to keep per-frame allocation off this path. Five arrays of
     * [MAX_PENDING_GENERATIONS] is under half a kilobyte for the life of the client.
     */
    private val entryGeneration = LongArray(MAX_PENDING_GENERATIONS)
    private val entryX0 = IntArray(MAX_PENDING_GENERATIONS)
    private val entryY0 = IntArray(MAX_PENDING_GENERATIONS)
    private val entryX1 = IntArray(MAX_PENDING_GENERATIONS)
    private val entryY1 = IntArray(MAX_PENDING_GENERATIONS)

    private var head = 0
    private var pending = 0

    /**
     * Adopts a new framebuffer geometry and marks the whole of it dirty.
     *
     * Every part is load-bearing. The bounds are what [union] clamps against; a
     * resize invalidates every pixel the view holds — its bitmap is reallocated — so
     * the first frame after one must be a full upload; and the pending entries are
     * dropped because their coordinates were measured against the old geometry and
     * the new geometry is fully dirty anyway.
     */
    @Synchronized
    fun resize(width: Int, height: Int) {
        boundsWidth = width
        boundsHeight = height
        head = 0
        pending = 0
        clearOpen()
        unionLocked(0, 0, width, height)
    }

    /**
     * Merges one changed rectangle, clipped to the framebuffer.
     *
     * Clipping rather than rejecting, for the same reason [VncPixels.decodeArgbInto]
     * clips: a rectangle overrunning the framebuffer means the server and this client
     * disagree about geometry, and the pixels that *did* land still have to be
     * uploaded. An entirely out-of-bounds rectangle contributes nothing.
     */
    @Synchronized
    fun union(x: Int, y: Int, width: Int, height: Int) = unionLocked(x, y, width, height)

    /**
     * Closes the rectangles accumulated since the last call into an entry stamped
     * with [generation], and returns everything this region still owes.
     *
     * The result is the union of *all* unacknowledged entries, not just this
     * generation's: the consumer conflates, so the frame it eventually uploads has to
     * carry the work of every publish it skipped past.
     *
     * Returns [VncRect.EMPTY] when nothing is owed, which the view reads as "upload
     * everything" — see [VncRect.EMPTY]. In practice `VncClient` only publishes when
     * at least one rectangle changed, so an empty result here is a belt-and-braces
     * path rather than a normal one.
     *
     * @param generation must be greater than every generation published before it.
     *   Both callers derive it from one monotonically increasing counter.
     */
    @Synchronized
    fun publish(generation: Long): VncRect {
        if (!isEmptyBox(openX0, openY0, openX1, openY1)) {
            appendEntryLocked(generation, openX0, openY0, openX1, openY1)
            clearOpen()
        }
        return pendingUnionLocked()
    }

    /**
     * Called by the consumer once [generation]'s pixels are on the bitmap.
     *
     * Releases every entry at or below [generation] — the view uploaded the union
     * [publish] returned for it, which is exactly those — and keeps everything
     * published later. §1.154: an acknowledgement that lost the race to a newer
     * publish therefore still retires the work it covers, instead of being discarded
     * whole and leaving the region to ratchet upwards (§7.2).
     *
     * @return whether anything was released. §1.153 made this the return value
     *   because "the acknowledgement did nothing" is invisible from the outside — a
     *   released ack and an idle one are the same call with the same effect on the
     *   screen, differing only in a cost that accumulates. [VncRenderStats] counts
     *   them. §1.154 narrows what a `false` means: before it, `false` was the
     *   ghosting mechanism firing; now it is only "this generation was already
     *   retired, or nothing was owed", because a late acknowledgement is no longer a
     *   discarded one. A rate that does *not* fall after §1.154 is the signal that
     *   the ratchet was never the mechanism.
     */
    @Synchronized
    fun acknowledge(generation: Long): Boolean {
        var released = false
        while (pending > 0 && entryGeneration[head] <= generation) {
            head = next(head)
            pending--
            released = true
        }
        return released
    }

    /**
     * Test/diagnostic view of everything currently dirty, published or not, without
     * stamping a generation.
     *
     * Includes the open entry, unlike [publish]'s return value before the open entry
     * is closed into the ring: the question this answers is "what is not on screen",
     * which is not the same as "what has a consumer been offered".
     */
    @Synchronized
    fun peek(): VncRect {
        val union = pendingUnionLocked()
        if (isEmptyBox(openX0, openY0, openX1, openY1)) return union
        if (union.isEmpty) return VncRect(openX0, openY0, openX1 - openX0, openY1 - openY0)
        val x0 = minOf(union.x, openX0)
        val y0 = minOf(union.y, openY0)
        val x1 = maxOf(union.x + union.width, openX1)
        val y1 = maxOf(union.y + union.height, openY1)
        return VncRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * How many published generations are still unacknowledged.
     *
     * Test-only, and the property worth asserting directly: it is what distinguishes
     * §1.154's subtractive release from §1.147's all-or-nothing one, and a
     * rectangle-level assertion cannot see the difference between "released the older
     * entry" and "never recorded it".
     */
    @Synchronized
    fun pendingGenerations(): Int = pending

    private fun pendingUnionLocked(): VncRect {
        if (pending == 0) return VncRect.EMPTY
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        var index = head
        repeat(pending) {
            if (entryX0[index] < x0) x0 = entryX0[index]
            if (entryY0[index] < y0) y0 = entryY0[index]
            if (entryX1[index] > x1) x1 = entryX1[index]
            if (entryY1[index] > y1) y1 = entryY1[index]
            index = next(index)
        }
        return if (isEmptyBox(x0, y0, x1, y1)) VncRect.EMPTY else VncRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Appends one entry, collapsing the two oldest first if the ring is full.
     *
     * The collapse merges the oldest into its successor and keeps the **successor's**
     * generation, so an acknowledgement of the older generation can no longer release
     * the merged box. That is the conservative direction: the region gets less
     * precise, never smaller than the truth. Overflow needs
     * [MAX_PENDING_GENERATIONS] publishes inside one upload-to-acknowledge interval,
     * which the audit measures as two Main turns and a copy.
     */
    private fun appendEntryLocked(generation: Long, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (pending == MAX_PENDING_GENERATIONS) {
            val oldest = head
            val second = next(oldest)
            if (entryX0[oldest] < entryX0[second]) entryX0[second] = entryX0[oldest]
            if (entryY0[oldest] < entryY0[second]) entryY0[second] = entryY0[oldest]
            if (entryX1[oldest] > entryX1[second]) entryX1[second] = entryX1[oldest]
            if (entryY1[oldest] > entryY1[second]) entryY1[second] = entryY1[oldest]
            head = second
            pending--
        }
        val slot = (head + pending) % MAX_PENDING_GENERATIONS
        entryGeneration[slot] = generation
        entryX0[slot] = x0
        entryY0[slot] = y0
        entryX1[slot] = x1
        entryY1[slot] = y1
        pending++
    }

    private fun next(index: Int): Int = (index + 1) % MAX_PENDING_GENERATIONS

    private fun isEmptyBox(x0: Int, y0: Int, x1: Int, y1: Int): Boolean = x1 <= x0 || y1 <= y0

    private fun clearOpen() {
        openX0 = Int.MAX_VALUE
        openY0 = Int.MAX_VALUE
        openX1 = Int.MIN_VALUE
        openY1 = Int.MIN_VALUE
    }

    private fun unionLocked(x: Int, y: Int, width: Int, height: Int) {
        if (boundsWidth <= 0 || boundsHeight <= 0) return
        if (width <= 0 || height <= 0) return
        val left = x.coerceIn(0, boundsWidth)
        val top = y.coerceIn(0, boundsHeight)
        val right = (x + width).coerceIn(0, boundsWidth)
        val bottom = (y + height).coerceIn(0, boundsHeight)
        if (right <= left || bottom <= top) return
        if (left < openX0) openX0 = left
        if (top < openY0) openY0 = top
        if (right > openX1) openX1 = right
        if (bottom > openY1) openY1 = bottom
    }

    private companion object {
        /**
         * Generations that can be in flight before the ring collapses its oldest
         * two.
         *
         * 16 is far above the handful the audit's upload-to-acknowledge interval can
         * fit — two Main-queue turns, two thread hops and the pixel copy — and small
         * enough that the whole history is five arrays of 16 for the life of the
         * connection. Sizing it to the *expected* depth instead would make the
         * conservative collapse a routine path rather than the safety net it is meant
         * to be.
         */
        const val MAX_PENDING_GENERATIONS = 16
    }
}
