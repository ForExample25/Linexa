package com.fem.linexa.ui.screens.desktop

/**
 * §1.165 — turns a [VncRenderStatsRegistry.Reading] into labelled rows for display.
 *
 * Pure, and separate from the composable that shows it, for the reason every other
 * pure piece of this package is separate ([VncDirtyRegion], [VncPixels],
 * [DesktopGeometry]): there is no Compose or Robolectric test harness in this module
 * (§1.124(a)), so anything left inside a `@Composable` is untestable here. What a
 * diagnostic panel is *for* is being read correctly, and a panel that mislabels a
 * counter — or reports a rate over the wrong window — is worse than no panel,
 * because the reader has no way to notice. So the labelling and the number
 * formatting are here, under test, and the composable only lays the rows out.
 *
 * The row set is deliberately exactly the counters §1.153 already collects, in the
 * order the audit reads them:
 *
 *  1. the window, because every number below it is a total over that window;
 *  2. the wire cadence (§16.1's predicted effect);
 *  3. the uploaded fraction and full-frame rate (§16.2's, and the report's most
 *     important metric);
 *  4. the acknowledgement outcome, stated in §1.154's semantics.
 *
 * No value here is recomputed from anything but the snapshot's own fields — the
 * derived quantities are the `Snapshot` properties, so this file cannot disagree with
 * the log line about what the numbers mean.
 */
internal object VncRenderStatsReport {

    /** One labelled row. [hint] is the interpretation, shown smaller by the UI. */
    data class Row(val label: String, val value: String, val hint: String? = null)

    /**
     * The rows for [reading], or the empty list when there is nothing to show.
     *
     * An empty list rather than a placeholder row: the caller distinguishes "no
     * session yet" (null reading) from "a session with nothing in it", and it can say
     * so better in a sentence than a table can in a cell.
     */
    fun rows(reading: VncRenderStatsRegistry.Reading): List<Row> {
        val s = reading.snapshot
        return listOf(
            Row(
                label = "Window",
                value = "${seconds(s.elapsedMillis)} s",
                hint = if (reading.live) "live — still recording" else "ended — final values"
            ),
            Row(
                label = "Server updates",
                value = "${s.updates}  (${oneDecimal(s.updatesPerSecond)}/s)",
                hint = "FramebufferUpdates read off the wire"
            ),
            Row(
                label = "Rectangles / update",
                value = oneDecimal(s.meanRectanglesPerUpdate)
            ),
            Row(
                label = "Interval mean / max",
                value = "${oneDecimal(s.meanIntervalMillis)} / ${oneDecimal(s.maxIntervalMillis)} ms",
                // Stated because a mean over one sample is not a mean, and `updates=1`
                // renders as 0.0/0.0 — an absence of data that looks like a very fast
                // session.
                hint = "${s.intervals} interval(s) sampled"
            ),
            Row(
                label = "Uploads",
                value = s.uploads.toString(),
                hint = "pixel copies into the view's bitmap"
            ),
            Row(
                label = "Uploaded fraction",
                value = twoDecimals(s.meanUploadedFraction),
                // The §16.2 question, phrased so the reader does not have to remember
                // which direction is bad.
                hint = "share of the framebuffer copied per upload; near 1.00 means full-frame"
            ),
            Row(
                label = "Full-frame uploads",
                value = "${s.fullFrameUploads}  (${twoDecimals(s.fullFrameRate)})",
                hint = "uploads that copied everything"
            ),
            Row(
                label = "Acknowledgements",
                value = s.acknowledgements.toString()
            ),
            Row(
                label = "Released / no-op",
                value = "${s.releasedAcknowledgements} / ${s.staleAcknowledgements}",
                // §1.154 inverted the interesting direction. Spelling out what a no-op
                // means now is the whole point of this hint: read under §1.147's
                // semantics the same number would look like a fault.
                hint = "no-op = that generation was already retired (benign since §1.154)"
            )
        )
    }

    /**
     * The same rows as one clipboard-ready block.
     *
     * Exists because the realistic way these numbers leave the device is a user
     * copying them into a report — the panel is on a phone, and transcribing nine
     * values by hand is how a measurement acquires a typo.
     */
    fun asText(reading: VncRenderStatsRegistry.Reading): String = buildString {
        appendLine("Linexa VNC render stats (§1.153 counters)")
        appendLine(if (reading.live) "state: live" else "state: session ended")
        for (row in rows(reading)) {
            append(row.label).append(": ").appendLine(row.value)
        }
    }

    /**
     * Seconds with one decimal.
     *
     * `String.format` is avoided for the same reason [VncRenderStats] avoids it — it
     * allocates a `Formatter` and parses the pattern per call. That reason is weaker
     * here (this runs on a button press, not the frame path), but two spellings of
     * "one decimal place" in one feature is how they drift apart.
     */
    private fun seconds(millis: Double): String = oneDecimal(millis / 1000.0)

    private fun oneDecimal(value: Double): String = scaled(value, 10)

    private fun twoDecimals(value: Double): String = scaled(value, 100)

    private fun scaled(value: Double, factor: Int): String {
        if (value.isNaN() || value.isInfinite()) return "n/a"
        val units = kotlin.math.round(value.coerceAtLeast(0.0) * factor).toLong()
        val whole = units / factor
        val frac = units % factor
        val digits = if (factor == 100 && frac < 10L) "0$frac" else frac.toString()
        return "$whole.$digits"
    }
}
