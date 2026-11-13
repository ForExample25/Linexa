package com.fem.linexa.test.support

import com.fem.linexa.core.logging.LogLevel
import com.fem.linexa.core.logging.LogSink
import com.fem.linexa.core.logging.Logger

/**
 * §1.145: records the **thread** each log line was written on.
 *
 * The §1.145 fix is a dispatch fix: no argv, no env, no ordering and no failure
 * semantics change, only which thread each step of
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime.openTerminal]
 * runs on. That makes it exactly the kind of change every existing runtime test
 * passes both before and after — `PRootOpenSessionConfigOverridesTest`,
 * `…DeclaredShellTest`, `…SharedStorageToggleTest` and `PRootTerminalKeyingTest`
 * assert the *product* (argv/env/session map), which the fix deliberately leaves
 * identical. Without a thread observation, a later refactor could quietly move
 * the guest-`/tmp` repair or the config parse back onto the caller's dispatcher
 * and reintroduce the freeze with a green suite.
 *
 * The observation seam is the production log itself rather than a test-only hook
 * in the runtime: `openTerminal` already emits a line from inside each step, so
 * "which thread ran this step" is answerable from outside. Marker lines used by
 * the tests:
 *
 *  * `guest /tmp ready:` — emitted by `GuestRootfsInitializer.ensureGuestTmp`,
 *    inside the hoisted scratch/guest-`/tmp` block;
 *  * `TERMINAL_CHAIN: openTerminal start` — emitted from inside the hoisted
 *    argv/env construction block, after the config load and the shell probe;
 *  * `TerminalView attach` is a UI-side line and is *not* used here.
 *
 * [Logger] has no sink removal (its sink list is append-only by design), so this
 * installs itself exactly once per process and is instead *disarmed* by
 * [reset]: an unarmed sink records nothing, so a test class that installs it
 * cannot leak observations into any other test. Registration is idempotent via
 * [install].
 */
object ThreadRecordingLogSink : LogSink {

    /** One recorded line: what was logged, and the thread that logged it. */
    data class Entry(val tag: String, val message: String, val thread: String)

    private val entries = mutableListOf<Entry>()

    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var installed: Boolean = false

    /** Register with [Logger] (idempotent) and start recording from empty. */
    @Synchronized
    fun install() {
        if (!installed) {
            Logger.addSink(this)
            installed = true
        }
        entries.clear()
        armed = true
    }

    /** Stop recording and drop everything captured. */
    @Synchronized
    fun reset() {
        armed = false
        entries.clear()
    }

    @Synchronized
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!armed) return
        entries += Entry(tag, message, Thread.currentThread().name)
    }

    /** Every recorded line, in order. */
    @Synchronized
    fun all(): List<Entry> = entries.toList()

    /**
     * The thread that logged the first line containing [marker], or null if no
     * line matched. Tests assert on null explicitly rather than defaulting, so a
     * renamed log line fails as "marker not found" instead of silently passing.
     */
    @Synchronized
    fun threadOfLineContaining(marker: String): String? =
        entries.firstOrNull { it.message.contains(marker) }?.thread
}
