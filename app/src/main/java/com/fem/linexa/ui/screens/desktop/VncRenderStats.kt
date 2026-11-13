package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger

/**
 * §1.153 — the desktop render path's measurement, and nothing else.
 *
 * ## Why this exists
 *
 * An investigation of the three reported desktop regressions
 * could prove three mechanisms from source but could not measure any of them,
 * because the render path
 * emits no per-frame or per-phase timing at all. Every fix to that path was
 * therefore unfalsifiable: the code compiles, the desktop draws, and whether the
 * change helped is an opinion. Landing these counters was the second
 * highest-value action identified, ahead of most of the fixes.
 *
 * The three quantities separate the three candidate causes from each other,
 * which is why they are these three and not a general
 * metrics facility:
 *
 *  - **Inter-update interval** ([recordServerUpdate]) — how often the server
 *    actually delivers a FramebufferUpdate. This is what the §1.153 dispatcher
 *    change is predicted to improve, and it is measured on the receive loop, before
 *    the publish, so it reports the *wire* rate and not the consumer's pace.
 *  - **Uploaded area as a fraction of the framebuffer** ([recordUpload]) — whether
 *    the scoped upload (§1.147) is actually scoped in practice. The audit's
 *    ghosting mechanism predicts this drifting towards 1.0 under load, because a
 *    dirty union that is never cleared grows to full-screen. §1.154 made the release
 *    subtractive, so this is now also the number that says whether that worked: if
 *    the mechanism was real, the fraction stops drifting.
 *  - **Unproductive-acknowledgement rate** ([recordAcknowledge]) — under §1.147 this
 *    was the *cause* of that drift, because an acknowledgement that lost the race to
 *    a newer publish released nothing at all. §1.154 retires everything the
 *    acknowledged generation covers, so a miss here now means only "that generation
 *    was already retired", which is benign. The counter is kept precisely because it
 *    was the evidence: a rate that stays high while the uploaded fraction stays near
 *    1.0 says the ratchet was never the mechanism and the search moves elsewhere.
 *
 * The last two are a pair on purpose. A high miss rate with a low uploaded
 * fraction is harmless bookkeeping noise; a high rate *with* a fraction near
 * 1.0 is the audit's §7.2 mechanism still firing after §1.154, and neither number
 * alone can tell those apart.
 *
 * ## Why it is gated on [DebugCapture]
 *
 * Capture is off in a fresh process (UI/UX Phase 7's fail-closed default), so on a
 * normal run every method here is a volatile read and a return. That is the same
 * precedent §1.99 set for the PRoot forensic dumps and Phase 7 set for
 * `FileLogSink` and `RuntimeTrace`: instrumentation the user has not asked for does
 * not run. It matters more here than in either of those — this is the frame path,
 * and an ungated `Logger.d` per update would be a new instance of the very cost the
 * audit was investigating.
 *
 * The consequence is deliberate and worth stating plainly: **these numbers only
 * exist when "Enable debug info" is on.** They are a diagnostic instrument, not
 * telemetry.
 *
 * ## Why a summary line and not a line per frame
 *
 * At 60 updates a second a per-update log is 60 formatted strings and 60 file
 * appends a second, which would degrade the thing being measured. Instead the
 * counters accumulate and one line is emitted every [LOG_EVERY_UPDATES] updates,
 * plus one on [logSessionSummary] at disconnect so a short session still reports.
 *
 * ## §1.165 — why the counters are also readable, not only loggable
 *
 * The device-verification pass these counters exist to serve could not run, for a
 * reason that had nothing to do with the counters being wrong: they are emitted
 * through [Logger] only, and `Logger`'s two destinations are logcat (which Android
 * restricts to the app's own UID) and `{filesDir}/logs/linexa.log` (which is
 * app-private). On an unrooted device with no adb — the configuration the app
 * actually ships into — the numbers were unobtainable. [snapshot] was already the
 * right shape for a reader; §1.165 adds the three things a *human* reader needs and
 * a log line did not have to carry:
 *
 *  - [resetAtNanos] and [Snapshot.elapsedMillis], so a reading states the window it
 *    covers. Every counter here is a total since reset, and the previous report's
 *    "cumulative counters make scenario analysis difficult" is precisely this;
 *  - [Snapshot.updatesPerSecond] and [Snapshot.fullFrameRate], derived from fields
 *    that already existed, because a rate is what the reported symptoms are about;
 *  - [Snapshot.releasedAcknowledgements], stating §1.154's semantics in the
 *    direction they now matter, so a reader cannot accidentally interpret the
 *    stale counter under §1.147's meaning.
 *
 * None of these adds a measurement. No new counter is incremented on the frame
 * path, no field is written per update that was not written before, and the three
 * `record*` methods are byte-for-byte unchanged. This is the same instrumentation
 * with an accessor a UI can call — deliberately not a second measurement system,
 * which is what a per-scenario ring buffer or a windowed histogram would have been.
 *
 * ## Purity
 *
 * No Android type, and the clock is a constructor parameter, so this is fully
 * JVM-testable — the same rule [VncDirtyRegion], [VncPixels] and [VncTouchGesture]
 * follow, and the reason [VncSurfaceView] receives its measurements through a
 * callback rather than holding one of these directly.
 */
internal class VncRenderStats(
    /** Monotonic nanosecond clock; a parameter so tests can drive it. */
    private val nowNanos: () -> Long = System::nanoTime
) {

    private var updates = 0L
    private var rectangles = 0L

    /**
     * Intervals measured. Equal to `max(0, updates - 1)` as the code stands, and
     * counted rather than derived anyway.
     *
     * The derivation is only correct while *every* recorded update also contributes a
     * timestamp, which is true today and is not a property this class can enforce.
     * The number this divides is a mean the §16 recommendations are meant to be judged
     * by, and a mean that silently divides by the wrong count is worse than no mean at
     * all — it would make a pessimisation look like an improvement with nothing on
     * screen to contradict it. One `Long` is a cheap way not to have that class of
     * bug.
     */
    private var intervals = 0L
    private var intervalSumNanos = 0L
    private var intervalMaxNanos = 0L
    private var lastUpdateNanos = 0L

    /**
     * Whether [lastUpdateNanos] holds a real reading.
     *
     * A separate flag rather than a `0L` sentinel: `System.nanoTime` has an arbitrary
     * origin and is explicitly allowed to return zero (or a negative value), so a
     * sentinel would silently discard the first interval of a session whose first
     * update happened to land on it. Found by [VncRenderStatsTest], which drives the
     * clock from zero — a real device would have hidden it indefinitely, in a counter
     * whose whole purpose is to be trusted.
     */
    private var hasLastUpdate = false

    private var uploads = 0L
    private var uploadedPixels = 0L
    private var framePixels = 0L
    private var fullFrameUploads = 0L

    private var acknowledgements = 0L
    private var staleAcknowledgements = 0L

    private var loggedUpdates = 0L

    /**
     * §1.165 — when the counters below started accumulating.
     *
     * Additive, and the reason is the device-verification pass that could not read
     * these numbers at all: every quantity here is a *total since reset*, so a
     * reading of `uploadedFraction=0.42` is uninterpretable without knowing whether
     * it covers two seconds of dragging or four minutes that happened to include
     * some. [Snapshot.elapsedMillis] is what turns a cumulative counter into a
     * measurement with a window, and it is the cheapest possible way to get one — a
     * subtraction at read time, not a second set of counters.
     *
     * Set here at construction as well as in [reset] so a snapshot taken before the
     * first `connect` reports an elapsed time from a real origin rather than from
     * `System.nanoTime`'s arbitrary zero.
     */
    private var resetAtNanos = nowNanos()

    /**
     * Records one FramebufferUpdate carrying [rectangleCount] rectangles.
     *
     * Called from the receive loop ([VncClient.readServerMessage]) before the frame
     * is published, so the interval is server-to-server and independent of whether
     * the consumer kept up.
     *
     * The first update of a session contributes no interval — there is nothing to
     * measure from — which is why [updates] and the interval count are not the same
     * thing.
     */
    @Synchronized
    fun recordServerUpdate(rectangleCount: Int) {
        if (!DebugCapture.isEnabled()) return
        val now = nowNanos()
        if (hasLastUpdate) {
            val interval = now - lastUpdateNanos
            intervals++
            intervalSumNanos += interval
            if (interval > intervalMaxNanos) intervalMaxNanos = interval
        }
        lastUpdateNanos = now
        hasLastUpdate = true
        updates++
        rectangles += rectangleCount
        maybeLogLocked()
    }

    /**
     * Records one acknowledgement and whether it actually released anything.
     *
     * @param applied false when the acknowledged generation was already retired, or
     *   nothing was owed. Under §1.147 this was the audit's §7.2 miss — a late
     *   acknowledgement released *nothing*, leaving the whole accumulated union in
     *   place and turning a scoped upload into a full-framebuffer one. §1.154 made
     *   the release per generation, so a late acknowledgement now retires the work it
     *   covers and this counter no longer measures that failure. It stays because it
     *   is the control: read against [recordUpload]'s fraction, a rate that did not
     *   improve is evidence the ratchet was not the cause.
     */
    @Synchronized
    fun recordAcknowledge(applied: Boolean) {
        if (!DebugCapture.isEnabled()) return
        acknowledgements++
        if (!applied) staleAcknowledgements++
    }

    /**
     * Records one pixel copy: [pixels] copied out of a [total]-pixel framebuffer.
     *
     * Reported by [VncSurfaceView] through a callback rather than measured here,
     * because the region it copies is not the region the producer published — it is
     * that union'd with the back buffer's outstanding debt (§1.150), and the cost
     * being measured is the copy that actually happened.
     */
    @Synchronized
    fun recordUpload(pixels: Long, total: Long) {
        if (!DebugCapture.isEnabled()) return
        if (total <= 0L) return
        // Clamped: the copied region is bounded by the framebuffer in practice
        // (§1.150 unions two rectangles that are both clipped to it), but a reported
        // fraction above 1.0 would be nonsense to read and clamping is cheaper than
        // reasoning about whether it can happen.
        val copied = pixels.coerceIn(0L, total)
        uploads++
        uploadedPixels += copied
        framePixels += total
        if (copied >= total) fullFrameUploads++
    }

    /** Immutable view of the counters, for the log line and for tests. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        updates = updates,
        rectangles = rectangles,
        meanIntervalMillis = if (intervals > 0L) {
            intervalSumNanos.toDouble() / intervals.toDouble() / 1_000_000.0
        } else {
            0.0
        },
        maxIntervalMillis = intervalMaxNanos.toDouble() / 1_000_000.0,
        uploads = uploads,
        meanUploadedFraction = if (framePixels > 0L) {
            uploadedPixels.toDouble() / framePixels.toDouble()
        } else {
            0.0
        },
        fullFrameUploads = fullFrameUploads,
        acknowledgements = acknowledgements,
        staleAcknowledgements = staleAcknowledgements,
        // §1.165 — deliberately *not* gated on DebugCapture, unlike every record
        // path. The elapsed time is a subtraction of two numbers this object already
        // holds; gating it would make the one field that says "over what window"
        // read zero in exactly the snapshot a reader is looking at.
        elapsedMillis = (nowNanos() - resetAtNanos).toDouble() / 1_000_000.0,
        intervals = intervals
    )

    /**
     * Emits the accumulated counters once, at disconnect.
     *
     * Unconditional on the update count (unlike [maybeLogLocked]) so that a session
     * shorter than [LOG_EVERY_UPDATES] updates — which is most of them while
     * diagnosing a startup or first-frame problem — still reports.
     */
    @Synchronized
    fun logSessionSummary() {
        if (!DebugCapture.isEnabled()) return
        if (updates == 0L && uploads == 0L) return
        Logger.d(TAG, "§1.153 render stats (session) ${format(snapshot())}")
    }

    /** Resets every counter. For tests, and for a reconnect on the same client. */
    @Synchronized
    fun reset() {
        updates = 0L
        rectangles = 0L
        intervals = 0L
        intervalSumNanos = 0L
        intervalMaxNanos = 0L
        lastUpdateNanos = 0L
        hasLastUpdate = false
        uploads = 0L
        uploadedPixels = 0L
        framePixels = 0L
        fullFrameUploads = 0L
        acknowledgements = 0L
        staleAcknowledgements = 0L
        loggedUpdates = 0L
        // §1.165: the window starts now. Not gated on DebugCapture — a reset while
        // capture is off must still move the origin, or enabling capture later would
        // report a window that began before the counters did.
        resetAtNanos = nowNanos()
    }

    private fun maybeLogLocked() {
        if (updates - loggedUpdates < LOG_EVERY_UPDATES) return
        loggedUpdates = updates
        Logger.d(TAG, "§1.153 render stats ${format(snapshot())}")
    }

    /**
     * The counters as one line.
     *
     * Deliberately one line with named fields rather than a formatted block: it is
     * read out of `logcat` or the debug panel on a phone, and it is grepped.
     */
    private fun format(s: Snapshot): String = buildString {
        // §1.165: the window first. Every number after it is a total over this
        // interval, and the previous format left a reader unable to tell a 2-second
        // sample from a 4-minute one.
        append("window=").append(oneDecimal(s.elapsedMillis / 1000.0)).append('s')
        append(" updates=").append(s.updates)
        append(" upd/s=").append(oneDecimal(s.updatesPerSecond))
        append(" rect/update=").append(oneDecimal(s.meanRectanglesPerUpdate))
        append(" interval(mean/max ms)=").append(oneDecimal(s.meanIntervalMillis))
        append('/').append(oneDecimal(s.maxIntervalMillis))
        append(" uploads=").append(s.uploads)
        append(" uploadedFraction=").append(twoDecimals(s.meanUploadedFraction))
        append(" fullFrame=").append(s.fullFrameUploads)
        append(" ack=").append(s.acknowledgements)
        append(" stale=").append(s.staleAcknowledgements)
        append(" staleRate=").append(twoDecimals(s.staleAcknowledgeRate))
    }

    /**
     * A point-in-time read of the counters.
     *
     * A value type, because the counters are mutated from three threads (the receive
     * loop, Main and — for the interval — whichever of them got there first) and a
     * consumer that read the fields one at a time could report a mean over one
     * update count and a fraction over another.
     */
    data class Snapshot(
        val updates: Long,
        val rectangles: Long,
        val meanIntervalMillis: Double,
        val maxIntervalMillis: Double,
        val uploads: Long,
        val meanUploadedFraction: Double,
        val fullFrameUploads: Long,
        val acknowledgements: Long,
        val staleAcknowledgements: Long,
        /**
         * §1.165 — milliseconds since the counters were last reset.
         *
         * The field that makes the rest of this type a measurement rather than a
         * running total. Defaulted so the many existing call sites that construct a
         * `Snapshot` for comparison do not have to care.
         */
        val elapsedMillis: Double = 0.0,
        /**
         * §1.165 — intervals actually measured, which is `max(0, updates - 1)`.
         *
         * Surfaced because the panel shows a mean and a max, and a reader needs to
         * know how many samples they average — `updates=1` reports
         * `interval(mean/max)=0.0/0.0`, which is an absence of data and not a fast
         * session. Also defaulted, for the same reason as [elapsedMillis].
         */
        val intervals: Long = 0L
    ) {
        val meanRectanglesPerUpdate: Double
            get() = if (updates > 0L) rectangles.toDouble() / updates.toDouble() else 0.0

        /**
         * §1.165 — server updates per second over the window, or 0 without one.
         *
         * Derived here rather than counted: it is the reciprocal of the mean interval
         * expressed the way a reader thinks about frame rate, and deriving it keeps
         * [recordServerUpdate] exactly as cheap as it was. Computed from
         * [elapsedMillis] rather than from [meanIntervalMillis] on purpose — the mean
         * interval only covers time between updates that arrived, so a session with a
         * two-second stall in the middle would report the same rate as one without it.
         */
        val updatesPerSecond: Double
            get() = if (elapsedMillis > 0.0) updates.toDouble() * 1000.0 / elapsedMillis else 0.0

        /**
         * §1.165 — the share of uploads that copied the whole framebuffer.
         *
         * The companion to [meanUploadedFraction], and the sharper of the two for
         * §16.2: a mean of 0.4 is consistent both with every upload copying 40% and
         * with two in five copying everything, and only the second is the ratchet the
         * audit predicted. [fullFrameUploads] alone could not say that, because it is
         * a count and the number of uploads varies with the scenario's length.
         */
        val fullFrameRate: Double
            get() = if (uploads > 0L) fullFrameUploads.toDouble() / uploads.toDouble() else 0.0

        /**
         * The fraction of acknowledgements that released nothing.
         *
         * The audit's §7.2 mechanism predicted this being persistently non-zero under
         * a sustained update stream, with [meanUploadedFraction] approaching 1.0 as a
         * consequence. §1.154 broke that link — a late acknowledgement now retires its
         * own generation — so the pair is read as a test of the fix rather than as the
         * fault itself. See the class kdoc.
         */
        val staleAcknowledgeRate: Double
            get() = if (acknowledgements > 0L) {
                staleAcknowledgements.toDouble() / acknowledgements.toDouble()
            } else {
                0.0
            }

        /**
         * §1.165 — acknowledgements that actually released a dirty entry.
         *
         * Stated as its own field because [staleAcknowledgements] counts the
         * *complement*, and §1.154 inverted which of the two is the interesting one:
         * under §1.147 the question was "how many releases were lost", now it is "are
         * releases happening at all". Reading that off a subtraction in a UI would
         * invite getting the direction wrong, which is the specific error the previous
         * verification report was warned against making.
         */
        val releasedAcknowledgements: Long
            get() = acknowledgements - staleAcknowledgements
    }

    private companion object {
        const val TAG = "VncRenderStats"

        /**
         * Updates between log lines.
         *
         * 120 is about two seconds of a busy desktop, which is short enough to see a
         * change while interacting with it and long enough that the logging is not
         * itself a cost on the frame path. A per-update line would be.
         */
        const val LOG_EVERY_UPDATES = 120L

        /**
         * `String.format` is avoided on this path: it allocates a `Formatter` and
         * parses the pattern on every call, and this runs inside the summary of a
         * frame-path measurement. Integer arithmetic is enough for a number a human
         * reads, and every value here is non-negative.
         */
        fun oneDecimal(value: Double): String = scaled(value, 10)

        fun twoDecimals(value: Double): String = scaled(value, 100)

        fun scaled(value: Double, factor: Int): String {
            if (value.isNaN() || value.isInfinite()) return "n/a"
            val units = kotlin.math.round(value.coerceAtLeast(0.0) * factor).toLong()
            val whole = units / factor
            val frac = units % factor
            val digits = if (factor == 100 && frac < 10L) "0$frac" else frac.toString()
            return "$whole.$digits"
        }
    }
}
