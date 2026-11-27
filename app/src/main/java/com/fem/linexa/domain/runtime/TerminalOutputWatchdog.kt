package com.fem.linexa.domain.runtime

/**
 * Finding 019 — first-output watchdog state.
 *
 * The terminal UI already had a fallback for a pty that could not be resolved
 * and for a dead child, but a **live pty that produces no bytes** still showed
 * a black surface with no explanation. This is the pure half of the fix: it
 * answers "has this session produced output yet?" without owning a timer,
 * Android, or the pty itself. The ViewModel owns the timer and the pty
 * subscription; [TerminalViewModel] only asks this object when the deadline
 * expires.
 *
 * It is deliberately **not** a kill switch. A slow guest shell, a `tmux` cold
 * start, or a program that waits before printing is legitimate; the watchdog
 * only decides whether to show a non-blocking banner. The pty is never
 * destroyed by the watchdog, and once any output arrives the state stays
 * "seen" for the rest of that session.
 */
class TerminalOutputWatchdog(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS
) {

    var hasOutput: Boolean = false
        private set

    /** Records that the session produced output (or a title/escape sequence). */
    fun onOutput() {
        hasOutput = true
    }

    /**
     * True when [elapsedMs] has reached the threshold and no output has been
     * observed. False after [onOutput] regardless of elapsed time.
     */
    fun shouldWarn(elapsedMs: Long): Boolean =
        !hasOutput && elapsedMs >= thresholdMs

    companion object {
        /**
         * Cold starts on the supported guests are normally well under this;
         * 8 s leaves room for PRoot + tmux + a shell rc without hiding a real
         * black screen for too long.
         */
        const val DEFAULT_THRESHOLD_MS: Long = 8_000L
    }
}
