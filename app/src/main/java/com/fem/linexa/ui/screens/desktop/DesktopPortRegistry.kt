package com.fem.linexa.ui.screens.desktop

import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-lifetime record of which **loopback port** each workspace's
 * desktop is actually running on (Desktop Modes work, 2026-08).
 *
 * ## Why this exists — the fixed-port defect it replaces
 *
 * The pre-Desktop-Modes desktop always started Xvnc on `5901` and the client
 * always connected to `5901`, as two private constants. That works for one
 * workspace and breaks the moment two run desktops at once: the second Xvnc
 * binds nothing (TigerVNC refuses a taken `-rfbport`, or falls back to a
 * display-derived port the client never learns), and the screen polls a port
 * whose listener belongs to a *different workspace's* X server — connecting
 * the user to the wrong desktop with no error anywhere.
 *
 * A per-workspace **dynamic** port fixes both, but creates a new problem:
 * `DesktopViewModel` is scoped to the Desktop `NavBackStackEntry` (§1.29), so
 * re-entering the screen constructs a fresh instance whose `resumeIfAlreadyRunning`
 * (§1.114) path must reconnect to the *existing* daemons without restarting
 * them. The port therefore has to outlive the ViewModel — but not the process,
 * because PRoot children die with the app (`--kill-on-exit`), so there is no
 * cross-process desktop to remember and nothing to persist to Room.
 *
 * A `@Singleton` in-memory map is exactly that lifetime, and it is why the
 * record needs no storage, no invalidation and no migration.
 *
 * ## Allocation
 *
 * [allocate] keeps the historical `5901` as the *preferred* port so the
 * single-workspace case stays byte-identical to the old behaviour (including
 * any `adb forward` a power user has scripted), and only walks to the next
 * free loopback port when 5901 is genuinely taken. Free-ness is probed by
 * binding `ServerSocket(0, …, loopback)` — the same race every ephemeral-port
 * chooser has between close and Xvnc's bind, accepted here because the
 * contenders are all inside this app and Xvnc binds within seconds of start.
 *
 * Not a `data object`: it holds state, and it is `@Singleton`-injected into
 * [DesktopViewModel] so tests can substitute an isolated instance (it has no
 * Android dependencies, so JVM tests exercise the real thing).
 */
@Singleton
class DesktopPortRegistry @Inject constructor() {

    private val vncPorts = mutableMapOf<String, Int>()
    private val webPorts = mutableMapOf<String, Int>()

    /**
     * Reserve [port] as the VNC endpoint of [workspaceId], returning it.
     * Recording the *actual* port (not merely the preference) is what lets
     * [vncPortFor] answer the resume path truthfully after a fallback.
     */
    fun recordVnc(workspaceId: String, port: Int) {
        vncPorts[workspaceId] = port
    }

    /** Reserve [port] as the WebView run mode's endpoint of [workspaceId]. */
    fun recordWeb(workspaceId: String, port: Int) {
        webPorts[workspaceId] = port
    }

    /**
     * The recorded VNC port for [workspaceId], or [default] when none is
     * recorded — the pre-Desktop-Modes constant keeps an unrecorded workspace
     * (started before this registry existed) reconnecting as it always did.
     */
    fun vncPortFor(workspaceId: String, default: Int = DEFAULT_VNC_PORT): Int =
        vncPorts[workspaceId] ?: default

    /**
     * The recorded WebView port for [workspaceId], or [default]. Unlike the
     * VNC port this falls back to the *setting* rather than a constant; the
     * caller passes its configured value in.
     */
    fun webPortFor(workspaceId: String, default: Int): Int =
        webPorts[workspaceId] ?: default

    /**
     * Pick a free loopback port for [workspaceId], preferring [preferred],
     * record it, and return it.
     *
     * Pre-release audit fix (2026-09-02, finding B3): the scan used to test
     * bind-freedom only. A port recorded seconds earlier for another
     * workspace — Xvnc not yet bound — still probed "free", so two desktop
     * starts could receive the SAME port and one of the two Xvncs then died
     * on a taken -rfbport. The scan now also skips every port already
     * recorded for a different workspace, and exhaustion throws instead of
     * silently returning a busy port.
     */
    fun allocateVnc(workspaceId: String, preferred: Int = DEFAULT_VNC_PORT): Int {
        val reserved = synchronized(this) { (vncPorts.values + webPorts.values).toSet() }
        val port = findFreeLoopbackPort(preferred, skipPorts = reserved)
            ?: throw IllegalStateException(
                "No free loopback port within $PORT_SCAN_LIMIT of $preferred for workspace $workspaceId"
            )
        recordVnc(workspaceId, port)
        return port
    }

    /** Forget everything for [workspaceId] — the desktop was stopped. */
    fun clear(workspaceId: String) {
        vncPorts.remove(workspaceId)
        webPorts.remove(workspaceId)
    }

    companion object {

        /**
         * The historical VNC port, kept as the allocation *preference* —
         * see the class KDoc for why single-workspace behaviour must not
         * drift from the pre-Desktop-Modes constant.
         */
        const val DEFAULT_VNC_PORT = 5901

        /** How far past a busy port to keep looking before giving up. */
        const val PORT_SCAN_LIMIT = 64

        /**
         * The first free loopback port at or after [start]. Port 0 and
         * negative ports never match: [ServerSocket] would treat them as
         * "pick ephemeral", which would record a number Xvnc was never told.
         *
         * B3 fix (2026-09-02): [skipPorts] are treated as busy even when the
         * bind-probe succeeds (a recorded-but-not-yet-bound port), and
         * exhaustion returns null instead of the busy [start] port.
         */
        fun findFreeLoopbackPort(start: Int, skipPorts: Set<Int> = emptySet()): Int? {
            require(start in 1..65535) { "start must be a concrete TCP port, got $start" }
            var candidate = start
            val end = minOf(65535, start + PORT_SCAN_LIMIT)
            while (candidate <= end) {
                if (candidate !in skipPorts) {
                    try {
                        ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).use { return it.localPort }
                    } catch (_: Exception) {
                        // busy — keep scanning
                    }
                }
                candidate++
            }
            return null
        }
    }
}
