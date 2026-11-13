package com.fem.linexa.core.logging

/**
 * UI/UX Phase 7 — the process-wide switch that decides whether Linexa **captures**
 * anything at all.
 *
 * Before Phase 7 the "Enable debug info" toggle only decided whether a panel was
 * *rendered*. Capture ran unconditionally: [FileLogSink] appended every
 * `Logger.*` call to a rotating ~2 MB file set, and
 * [com.fem.linexa.service.runtime.RuntimeTrace] kept a 400-line ring per
 * workspace that was never cleared — so a user who had never turned debug on
 * still paid for both, for the entire life of the process, and could not stop
 * paying. This object is what makes the toggle mean "stop collecting" rather
 * than "stop looking".
 *
 * Two properties are deliberate:
 *
 *  * **It is an `object`, not an injected dependency.** The obvious design is a
 *    constructor parameter, and it does not reach the biggest producer: [Logger]
 *    is an `object` with ~500 static call sites across the module, so it has
 *    nowhere to receive one. Introducing a flag for the sink *and* a separate
 *    flag for the trace would create the state where half the app is capturing —
 *    exactly the "turning the UI off must actually disable the underlying
 *    collection logic" failure Phase 7 exists to fix. One flag, read by both.
 *  * **The default is `false` (fail-closed).** A fresh process captures nothing
 *    until something explicitly enables it. If the wiring that mirrors the
 *    persisted setting into here were ever removed, the app would under-collect
 *    (a debug panel that stays empty — visible, harmless, reported) rather than
 *    over-collect (silent background logging with the switch off, which is the
 *    condition the user cannot see and the phase forbids). `DebugCaptureGate`
 *    is the one writer, and `DebugInfoWiringSourceTest` asserts it is actually
 *    started, because a gate nobody starts compiles perfectly and would leave
 *    debug info permanently unavailable.
 *
 * The flag is `@Volatile` rather than an `AtomicBoolean`: every logging call
 * reads it and only a settings toggle writes it, so what is needed is visibility
 * across threads, not atomic read-modify-write. A plain volatile read is the
 * cheapest thing that is still correct on the hot path.
 *
 * This is *not* a log-level filter and must not become one. It answers one
 * question — may the app record? — and the answer is the user's setting.
 */
object DebugCapture {

    @Volatile
    private var enabled: Boolean = DEFAULT_ENABLED

    /**
     * Whether capture is currently permitted.
     *
     * Checked by [FileLogSink] before touching disk, by
     * [com.fem.linexa.service.runtime.RuntimeTrace] before appending, and by the
     * two expensive PRoot diagnostic dumps before they do any work at all.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Mirrors the persisted "Enable debug info" setting into the process.
     *
     * Only `DebugCaptureGate` should call this in production — it is the single
     * writer, so there is exactly one path from the switch to the capture
     * mechanisms and no way for two subsystems to disagree. Tests call it
     * directly, which is the whole reason this class has no Android dependency.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * Restores the fail-closed default.
     *
     * Exists for tests: the flag is process-wide, so a test that enables capture
     * must put it back or it leaks into every test that runs afterwards in the
     * same JVM — including the many that construct a real `RuntimeTrace` and
     * would silently start buffering.
     */
    fun resetForTesting() {
        enabled = DEFAULT_ENABLED
    }

    /** Capture is off until something turns it on. See the class doc. */
    const val DEFAULT_ENABLED: Boolean = false
}
