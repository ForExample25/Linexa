package com.fem.linexa.ui.screens.desktop

/**
 * §1.165 — the one place a UI can reach the live [VncRenderStats].
 *
 * ## Why this exists
 *
 * §1.153 landed the render counters and §16's recommendations were meant to be
 * judged by them. The device-verification pass could not read a single one, and the
 * reason was not that they were wrong: [VncRenderStats] emits through [
 * com.fem.linexa.core.logging.Logger], whose two destinations are logcat — which
 * Android restricts to the calling UID, so the app's own lines are invisible to
 * anything but adb — and `{filesDir}/logs/linexa.log`, which is app-private. On an
 * unrooted device with no adb, which is what the app ships into, the numbers existed
 * and could not be looked at. This object closes that gap and nothing else.
 *
 * ## Why a registry and not a parameter
 *
 * The counters live on [VncClient], which `DesktopScreen` constructs with
 * `remember { VncClient() }` — so the instance is scoped to one composition of one
 * screen, and the debug surface that should display them (the Workspace Settings
 * "Debug" group, where the runtime trace already lives) is a *different* screen with
 * no reference to it. Threading a client through the workspace's ViewModel to a
 * settings tab would put a live socket-owning object into the workspace UI's state
 * for the sake of a diagnostic readout; that is a much larger change than the
 * problem deserves, and it would couple two screens that are otherwise independent.
 *
 * So this follows the precedent [com.fem.linexa.core.logging.DebugCapture] set for
 * exactly the same shape of problem (a process-wide fact that ~500 call sites and
 * two screens need, with nowhere to inject it): an `object` with `@Volatile` state
 * and **one writer**. [VncClient] is that writer — [attach] on connect, [retire] on
 * disconnect — and every reader goes through [read].
 *
 * ## Why the last session is retained
 *
 * The measurement procedure is "run a scenario on the desktop, then go and read the
 * numbers", and leaving the desktop screen disposes the client (`DisposableEffect`,
 * §1.140 → [VncClient.disconnect]). If this only held a live reference, the reading
 * would be gone at precisely the moment the user went to look at it.
 *
 * [retire] therefore stores the *final* snapshot as a value. That also fixes a
 * subtler problem: [VncRenderStats.Snapshot.elapsedMillis] is computed when the
 * snapshot is taken, so a live read five minutes after the session ended would
 * report a five-minute window over a twenty-second session, and every derived rate
 * would be wrong in the flattering direction. A frozen snapshot cannot drift.
 *
 * ## Cost when debug is off
 *
 * [attach] and [retire] are two volatile writes per *connection*, not per frame, and
 * they are deliberately not gated on `DebugCapture`: the user may enable debug info
 * while a desktop is already running, and a gate here would leave the panel
 * permanently empty for that session with no way to tell why. The counters
 * themselves stay gated — with capture off, [read] returns a snapshot of zeroes,
 * because nothing recorded anything. Nothing in this file runs on the frame path.
 */
internal object VncRenderStatsRegistry {

    /**
     * What a reader gets: the numbers, plus whether they are still moving.
     *
     * [live] is part of the reading rather than a separate query because the two are
     * only meaningful together — "uploadedFraction 0.98" invites a completely
     * different conclusion depending on whether the session is running or ended, and
     * a UI that showed the value without the state would be inviting the reader to
     * guess.
     */
    data class Reading(
        val snapshot: VncRenderStats.Snapshot,
        /** True while a connected client is still recording into these counters. */
        val live: Boolean
    )

    @Volatile
    private var source: VncRenderStats? = null

    @Volatile
    private var retired: VncRenderStats.Snapshot? = null

    /**
     * Registers the connecting client's counters as the ones to read.
     *
     * Called from [VncClient.connect] *after* `stats.reset()`, so the window this
     * publishes starts at the connection and not at whatever the previous session
     * left behind. Replacing an existing source rather than rejecting the second one
     * is deliberate: exactly one desktop is connected at a time, and if that ever
     * stopped being true the newest connection is the one a reader means.
     *
     * Clears [retired] — the previous session's frozen numbers are exactly what a
     * reader must *not* be shown once a new session is producing its own.
     */
    fun attach(stats: VncRenderStats) {
        retired = null
        source = stats
    }

    /**
     * Freezes the final numbers and drops the reference to the client's counters.
     *
     * Called from [VncClient.disconnect]. The snapshot is taken here, at the moment
     * the session ends, for the reason in the class doc: elapsed time is computed at
     * snapshot time, so a reading taken later would silently widen the window.
     *
     * Tolerates being called without a matching [attach] — `disconnect` runs on paths
     * where the connection never succeeded — and in that case leaves whatever was
     * already retired alone rather than replacing a real reading with an empty one.
     */
    fun retire() {
        val current = source ?: return
        retired = current.snapshot()
        source = null
    }

    /**
     * The current reading, or null when no session has been observed this process.
     *
     * Null rather than a zeroed snapshot: "no desktop has run yet" and "a desktop ran
     * and recorded nothing" are different facts, and the second one is a finding while
     * the first is not.
     */
    fun read(): Reading? {
        source?.let { return Reading(it.snapshot(), live = true) }
        return retired?.let { Reading(it, live = false) }
    }

    /**
     * Resets the live counters, restarting the measurement window.
     *
     * This is the "reset before a scenario" control, and it is scoped as narrowly as
     * that requires: it calls [VncRenderStats.reset], which writes only counter
     * fields. It does not touch the socket, the framebuffer, the dirty region or the
     * frame flow — a reset is invisible to the picture on screen, by construction,
     * because [VncRenderStats] has no reference to any of them.
     *
     * @return false when there is no live session to reset, so the caller can say so
     *   instead of appearing to have done something. A retired reading is deliberately
     *   *not* cleared: the button's job is to start a new window, and discarding the
     *   only numbers a user has while no session is running is pure data loss.
     */
    fun reset(): Boolean {
        val current = source ?: return false
        current.reset()
        return true
    }

    /** Drops all state. For tests — this object outlives every one of them. */
    fun resetForTesting() {
        source = null
        retired = null
    }
}
