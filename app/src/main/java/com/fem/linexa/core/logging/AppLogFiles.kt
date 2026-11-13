package com.fem.linexa.core.logging

/**
 * UI/UX Phase 4 — the identity of the app-wide log file set, in one place.
 *
 * [FileLogSink] writes `linexa.log` and rotates it to `linexa.log.1`/`.2`, and
 * Phase 4 added a Global Settings row that shows how much those files weigh and
 * can clear them. That is two components that must agree on exactly which files
 * are "the app log" — and they must also agree on a *lock*, because clearing runs
 * on a settings tap while the sink may be appending from any thread. Both facts
 * live here rather than being duplicated:
 *
 *  * [LOG_FILE_NAME] / [MAX_GENERATIONS] were `private const`s inside
 *    [FileLogSink]. A deleter that hardcoded the same strings would keep working
 *    right up until the rotation scheme changed, then silently start leaving files
 *    behind — the kind of drift that shows up as "Clear didn't clear everything".
 *  * [writeLock] is the single monitor both the append/rotate path and the clear
 *    path synchronize on. Without a shared lock, clearing could delete the active
 *    file between the sink's length check and its append, or mid-rotation, and the
 *    sink would then recreate a file the user just asked to be removed.
 *
 * The log directory itself is `{filesDir}/logs` (see [Logger.init]) — deliberately
 * separate from a workspace's own `{workspace}/logs/` (§1.53), which is per-install
 * diagnostic evidence and is **not** touched by clearing this one.
 */
object AppLogFiles {

    /** The active log file's name. */
    const val LOG_FILE_NAME: String = "linexa.log"

    /** Size at which the active file is rotated. */
    const val MAX_FILE_BYTES: Long = 512L * 1024L

    /**
     * How many rotated generations are kept: `linexa.log.1` … `linexa.log.3`.
     *
     * The name and value are carried over verbatim from [FileLogSink] so the
     * rotation arithmetic reads the same. Worth stating explicitly, because the
     * arithmetic is easy to misread and getting it wrong is exactly how a clear
     * path silently leaves a file behind: the loop runs `MAX_GENERATIONS - 1 downTo
     * 1` renaming `.n` → `.n+1`, so the **highest suffix produced is
     * `MAX_GENERATIONS`**, and the full set is the active file plus
     * `MAX_GENERATIONS` archives — four files, ~2 MB, not three.
     */
    const val MAX_GENERATIONS: Int = 3

    /**
     * The shared monitor for every read, append, rotate and delete of these files.
     *
     * Held by [FileLogSink.write] and by [AppLogStore.clear]. Coarse on purpose:
     * the contended case is a settings tap racing a log line, which is rare and
     * cheap, and a finer scheme would have to reason about rotation renames.
     */
    val writeLock = Any()

    /**
     * Every file name that belongs to the app log, active first.
     *
     * Generated from [MAX_GENERATIONS] rather than listed, so adding a generation
     * cannot leave the clear path one file short — and the range is
     * `1..MAX_GENERATIONS` inclusive, matching what the rotation actually produces
     * (see the note on [MAX_GENERATIONS]). Writing the plausible-looking
     * `1 until MAX_GENERATIONS` here would omit `linexa.log.3` and leave the
     * oldest ~512 KiB behind on every clear.
     */
    fun names(): List<String> = buildList {
        add(LOG_FILE_NAME)
        for (generation in 1..MAX_GENERATIONS) {
            add("$LOG_FILE_NAME.$generation")
        }
    }

    /**
     * Whether [fileName] is part of the app log set.
     *
     * Used instead of a prefix match: `startsWith("linexa.log")` would also claim
     * an unrelated `linexa.log.bak` or a future `linexa.log.gz` that this object
     * does not manage, and clearing must only ever delete files it owns.
     */
    fun isAppLogFile(fileName: String): Boolean = fileName in names()
}
