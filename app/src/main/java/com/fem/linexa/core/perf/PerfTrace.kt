package com.fem.linexa.core.perf

import android.os.SystemClock
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger

/**
 * Phase M0-A — the production face of [PerfTraceEngine].
 *
 * Capture is gated on [DebugCapture] (off by default, the user's "Enable debug
 * info" switch). When it is off every method here returns after a single
 * volatile read: no timestamp is read, no string is built, no [android.os.Trace]
 * call is made. When it is on, startup events are logged under the `PerfTrace`
 * logcat tag and the Android trace sections named `Linexa:*` are emitted.
 *
 * The monotonic clock is [SystemClock.elapsedRealtimeNanos] in production
 * (installed by `LinexaApplication.onCreate`). The default `System.nanoTime()`
 * keeps the object usable from plain JVM tests; the engine itself is tested
 * directly with an injected clock.
 */
object PerfTrace {

    private const val TAG = "PerfTrace"

    @Volatile
    private var clock: () -> Long = { System.nanoTime() }

    private val engine = PerfTraceEngine(
        clockNanos = { clock() },
        enabled = { DebugCapture.isEnabled() },
        sink = { line -> Logger.i(TAG, line) },
        asyncBegin = { name -> beginAsync(name) },
        asyncEnd = { name, cookie -> endAsync(name, cookie) }
    )

    /** Installs the Android monotonic clock. Called once from `Application.onCreate`. */
    fun installAndroidClock() {
        clock = { SystemClock.elapsedRealtimeNanos() }
    }

    /** Test seam: replaces the clock (must be restored by the test). */
    internal fun installClockForTesting(testClock: () -> Long) {
        clock = testClock
    }

    /** Test seam: restores `System.nanoTime()`. */
    internal fun resetClockForTesting() {
        clock = { System.nanoTime() }
    }

    fun isEnabled(): Boolean = DebugCapture.isEnabled()

    /** E0 — workspace start request accepted. */
    fun beginWorkspaceStart(workspaceId: String): Boolean =
        engine.beginWorkspaceStart(workspaceId)

    /** Associates an (additional) terminal open with the workspace trace. */
    fun beginTerminalOpen(workspaceId: String, terminalId: String) =
        engine.beginTerminalOpen(workspaceId, terminalId)

    /** Binds the backend pty session id before the child is forked. */
    fun attachPty(workspaceId: String, ptyId: String) =
        engine.attachPty(workspaceId, ptyId)

    /** E1–E5 (E2–E4 may be skipped). */
    fun markPhase(
        workspaceId: String,
        terminalId: String?,
        event: StartupEvent,
        skipped: Boolean = false,
        ptyId: String? = null,
        detail: String? = null
    ): Boolean = engine.markPhase(workspaceId, terminalId, event, skipped, ptyId, detail)

    /** E6 — first pty output received. */
    fun markFirstOutput(ptyId: String, bytesRead: Int): Boolean =
        engine.markFirstOutput(ptyId, bytesRead)

    /** E7 — first screen revision delivered to a bound terminal view. */
    fun markFirstScreenUpdate(
        ptyId: String,
        screenRevision: Int,
        bytesRead: Long,
        handlerMessages: Long,
        screenUpdates: Long
    ): Boolean = engine.markScreenUpdate(
        ptyId,
        screenRevision,
        bytesRead,
        handlerMessages,
        screenUpdates
    )

    /** Ends a workspace trace that will never reach E7 (stop/failure). */
    fun abandonWorkspace(workspaceId: String, reason: String) =
        engine.abandonWorkspace(workspaceId, reason)

    /** Ends only an extra-terminal-open trace (never the workspace-start one). */
    fun abandonTerminalOpen(workspaceId: String, reason: String) =
        engine.abandonTerminalOpen(workspaceId, reason)

    /** Ends the trace correlated with a destroyed pty. */
    fun abandonTerminal(ptyId: String, reason: String) =
        engine.abandonPty(ptyId, reason)

    /** Clears live traces when the user turns capture off. */
    fun onCaptureChanged(enabled: Boolean) {
        if (!enabled) engine.onCaptureDisabled()
    }

    /**
     * Opens a synchronous Android trace section on the CURRENT thread. Returns
     * false (and does nothing) when capture is off; always pair with
     * [endSync] in a `try/finally`.
     */
    fun beginSync(name: String): Boolean {
        if (!DebugCapture.isEnabled()) return false
        AndroidTraceSink.beginSync(name)
        return true
    }

    fun endSync(active: Boolean) {
        if (active) AndroidTraceSink.endSync()
    }

    /**
     * Opens an ASYNC Android trace section. Safe across suspension points and
     * dispatcher changes; returns -1 when capture is off.
     */
    fun beginAsync(name: String): Int {
        if (!DebugCapture.isEnabled()) return -1
        return AndroidTraceSink.beginAsync(name)
    }

    fun endAsync(name: String, cookie: Int) {
        if (cookie >= 0) AndroidTraceSink.endAsync(name, cookie)
    }

    /** Monotonic nanoseconds; callers must check [isEnabled] before using it. */
    fun nowNanos(): Long = clock()

    /** Test/diagnostic surface. */
    internal fun activeTraceCount(): Int = engine.activeTraceCount()

    /** Test/diagnostic surface. */
    internal fun snapshot(workspaceId: String): PerfTraceEngine.TraceSnapshot? =
        engine.snapshot(workspaceId)
}
