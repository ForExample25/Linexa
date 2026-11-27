package com.fem.linexa.service.runtime.desktop

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.RuntimeTrace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Workspace-scoped X11 lifecycle state (transformation spec §13) — display
 * allocation, host-side socket readiness, and the observable per-workspace
 * [X11SessionState] machine.
 *
 * ## Why a runtime-owned manager, not ViewModel state
 *
 * The pre-transformation X11 plumbing lived as `internal` objects under
 * `ui.screens.desktop`, which made "X11 readiness" a *UI* fact the runtime
 * could not answer — the exact "UI-driven runtime state" the spec forbids
 * (§36). This manager is a `@Singleton` like [com.fem.linexa.service.runtime.SessionManager]:
 * process-lifetime, workspace-keyed, injectable into the diagnostics surface,
 * and JVM-testable (the only Android-free seam the desktop stack needs).
 *
 * ## Display allocation (§7 — workspace-owned resources)
 *
 * Every workspace that starts a desktop gets its OWN display number from
 * [allocateDisplay] (starting at `:1`, the historical value, so the
 * single-workspace case stays byte-identical). Numbers are released on stop
 * and reused lowest-first, so N concurrent desktops can never collide — the
 * old shared `":1"` constant made any second desktop (or a parallel
 * termux-x11 session) address the wrong server.
 *
 * ## Readiness (§13 — no magic delays)
 *
 * [awaitSocketReady] polls the HOST-side socket file at the workspace's own
 * rootfs (`{rootfs}/tmp/.X11-unix/X{n}` — the guest's /tmp is a real folder
 * inside app storage), with an explicit timeout and coroutine cancellation.
 * One `stat` per poll; no guest exec, no PRoot launch, no sleep-based
 * synchronization.
 */
@Singleton
class X11SessionManager @Inject constructor(
    private val runtimeTrace: RuntimeTrace,
) {

    private val displays = ConcurrentHashMap<String, String>()
    private val _states = MutableStateFlowMap()

    /** B4 fix (2026-09-02): serializes display-number allocation (check-then-act). */
    private val allocationLock = Any()

    /** Observe every workspace's X11 lifecycle state. Missing entry = IDLE. */
    val states: kotlinx.coroutines.flow.StateFlow<Map<String, X11SessionState>> = _states.flow

    /** The X11 state of [workspaceId] (IDLE when none). */
    fun stateFor(workspaceId: String): X11SessionState =
        _states.flow.value[workspaceId] ?: X11SessionState.IDLE

    /** Record a state transition (trace-recorded; observable by UI/diagnostics). */
    fun setState(workspaceId: String, state: X11SessionState) {
        val previous = stateFor(workspaceId)
        _states.put(workspaceId, state)
        if (previous != state) {
            Logger.i(TAG, "$workspaceId: X11 $previous → $state")
            runtimeTrace.record(workspaceId, "X11 state $previous → $state")
        }
    }

    /** Forget [workspaceId]'s state — the desktop stopped. */
    fun clearState(workspaceId: String) {
        _states.remove(workspaceId)
    }

    /**
     * Allocate the lowest free display number for [workspaceId] and return
     * its display string (`:1` first — the historical single-desktop value).
     * Idempotent: an allocation for an already-registered workspace returns
     * the existing display.
     *
     * Pre-release audit fix (2026-09-02, finding B4): the scan used to be a
     * check-then-act over the concurrent map without serialization (two
     * concurrent allocations could both claim the same `:n`) and had no
     * ceiling — [MAX_DISPLAY_NUMBER] was declared but never enforced. Both
     * fixed: allocation is serialized on [allocationLock] and the scan fails
     * loudly past the cap instead of running away.
     */
    fun allocateDisplay(workspaceId: String, rootfsPath: String): String {
        synchronized(allocationLock) {
            displays[workspaceId]?.let { return it }
            val used = displays.values.map { it.removePrefix(":").toIntOrNull() ?: 0 }.toSet()
            var n = FIRST_DISPLAY_NUMBER
            while (n in used && n < MAX_DISPLAY_NUMBER) n++
            if (n in used) {
                throw IllegalStateException(
                    "No free X11 display number within $FIRST_DISPLAY_NUMBER..$MAX_DISPLAY_NUMBER"
                )
            }
            // A display this workspace previously crashed on must not hand the
            // new server a taken socket (X servers refuse an existing socket).
            X11SocketReadiness.removeStaleSocket(rootfsPath, ":$n")
            val display = ":$n"
            displays[workspaceId] = display
            runtimeTrace.record(workspaceId, "X11 display allocated $display")
            return display
        }
    }

    /** The display allocated for [workspaceId], or null. */
    /**
     * Allocates a display for a **new start** of [workspaceId]'s desktop and
     * always sweeps the stale socket + lock file of the chosen display.
     *
     * [allocateDisplay] returns a workspace's already-recorded display
     * without touching the filesystem. That is correct for a reattach, but it
     * made every RETRY after a failed or killed start reuse the recorded
     * display while skipping [X11SocketReadiness.removeStaleSocket] — so the
     * socket/lock the previous Xvnc left behind made the next Xvnc abort
     * ("Server is already active for display N"). Start paths call this
     * instead, after stopping this workspace's own X server daemon (the
     * caller's contract: nothing of THIS workspace is serving the display, and
     * displays are per-workspace, so no other workspace's server is affected).
     */
    fun allocateFreshDisplay(workspaceId: String, rootfsPath: String): String {
        synchronized(allocationLock) {
            displays.remove(workspaceId)
            return allocateDisplay(workspaceId, rootfsPath)
        }
    }

    fun displayFor(workspaceId: String): String? = displays[workspaceId]

    /** Release [workspaceId]'s display (stop/delete). */
    fun releaseDisplay(workspaceId: String) {
        displays.remove(workspaceId)
    }

    /**
     * Wait until the workspace's X11 unix socket exists host-side.
     *
     * @param timeoutMs hard bound — a server that has not created its socket
     *   within the window is FAILED, never "still starting" forever.
     * @return true when the socket appeared (readiness fact), false on
     *   timeout or cancellation (callers clean up and report [X11SessionState.X11_FAILED]).
     */
    suspend fun awaitSocketReady(
        rootfsPath: String,
        display: String,
        timeoutMs: Long = DEFAULT_SOCKET_TIMEOUT_MS
    ): Boolean {
        val socket = X11SocketReadiness.hostSocketFile(rootfsPath, display)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) return true
            delay(POLL_INTERVAL_MS)
        }
        Logger.w(TAG, "X11 socket never appeared: ${socket.absolutePath} (${timeoutMs}ms budget)")
        return false
    }

    private class MutableStateFlowMap {
        private val backing = ConcurrentHashMap<String, X11SessionState>()
        private val _flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, X11SessionState>>(emptyMap())
        val flow: kotlinx.coroutines.flow.StateFlow<Map<String, X11SessionState>> = _flow

        fun put(workspaceId: String, state: X11SessionState) {
            backing[workspaceId] = state
            _flow.value = backing.toMap()
        }

        fun remove(workspaceId: String) {
            backing.remove(workspaceId)
            _flow.value = backing.toMap()
        }
    }

    companion object {
        private const val TAG = "X11SessionManager"

        /** First display number — the historical value, kept for compat. */
        const val FIRST_DISPLAY_NUMBER = 1

        /** Highest display number Linexa will allocate (per-process). */
        const val MAX_DISPLAY_NUMBER = 32

        /** Default socket-readiness budget (§13: explicit timeout). */
        const val DEFAULT_SOCKET_TIMEOUT_MS = 15_000L

        /** Poll cadence — dense enough to feel instant, one cheap stat each. */
        const val POLL_INTERVAL_MS = 100L
    }
}
