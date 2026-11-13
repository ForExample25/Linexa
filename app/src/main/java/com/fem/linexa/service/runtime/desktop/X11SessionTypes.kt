package com.fem.linexa.service.runtime.desktop

/**
 * The X11 readiness lifecycle (transformation spec §13), moved from the UI
 * layer to the runtime so both the Desktop screen and the diagnostics
 * surface observe the SAME state machine.
 *
 * The X server must never be reported ready just because a start command
 * returned: readiness is a *detected* fact (the display's unix socket exists
 * host-side and the desktop session process is alive).
 *
 * All members are workspace-scoped: a map entry per workspaceId in
 * [X11SessionManager.states]. A missing entry means IDLE.
 */
enum class X11SessionState {
    /** No X11 lifecycle is in flight. */
    IDLE,

    /** Start requested; X server / environment / readiness pending. */
    X11_STARTING,

    /** X server listening AND the X11 unix socket detected AND session up. */
    X11_READY,

    /** A stage failed; cleanup ran and the reason is on the UI state. */
    X11_FAILED,

    /** Stop requested; teardown in progress. */
    X11_STOPPING
}

/**
 * Which server implementation backs the workspace's X11 display.
 *
 * Transformation spec §11/§13: X11 must be the preferred local GUI backend
 * and VNC the compatibility fallback. Two providers implement that:
 *
 * - [TERMUX_X11] — the real local-X11 transport. The guest runs the
 *   `termux-x11` helper daemon (the integration entry point of the
 *   termux-x11 app — the same mechanism Tiny Container drives through
 *   `ACTION_START` + `EXTRA_ARGS`/`EXTRA_ENV_KEYS`/`EXTRA_ENV_VALUES` with a
 *   host-side socket-file readiness check). The app renders the display in
 *   its own activity; the guest's X clients connect to
 *   `/tmp/.X11-unix/X<n>` exactly as on a desktop Linux. Requires (a) the
 *   termux-x11 Android app installed and (b) the `termux-x11` helper binary
 *   in the guest — both detected at start time, never assumed.
 * - [XVNC] — the always-available compatibility path. The guest's own
 *   `Xvnc` (a complete X11 server) serves the display; Linexa's embedded
 *   RFB viewer renders it. This is real X11 *for the guest* (guest clients
 *   attach to the same unix socket), with VNC as the transport to the
 *   host screen.
 *
 * Provider selection is deterministic: the X11 backend uses [TERMUX_X11]
 * when both prerequisites are present, else [XVNC] — and the selected
 * provider is reported to the user (§28: never a silent switch; the UI says
 * which transport the desktop is using).
 */
enum class X11Provider(val id: String) {
    XVNC("xvnc"),
    TERMUX_X11("termux-x11");

    /** Short user-surfaceable label. */
    val label: String
        get() = when (this) {
            TERMUX_X11 -> "termux-x11"
            XVNC -> "Xvnc (RFB)"
        }

    companion object {
        fun fromId(raw: String?): X11Provider? =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
    }
}

/**
 * Everything Linexa knows about driving the termux-x11 app from a guest
 * (transformation spec §13's reference mechanism, expressed as Linexa-native
 * helpers). Pure strings/decisions only — the Android bits (package probing,
 * activity launch) live in the ViewModel, per the pure-provisioner +
 * Android-orchestrator convention the desktop codebase already follows.
 */
object TermuxX11 {

    /** The termux-x11 Android application package. */
    const val PACKAGE = "com.termux.x11"

    /** The helper binary the integration expects inside the guest. */
    const val HELPER_BINARY = "termux-x11"

    /**
     * Stable guest-daemon name for the helper (see `ContainerRuntime.startDaemon`).
     * Distinct from `xvnc`/`xsession` so all three desktop daemons can be
     * addressed independently.
     */
    const val DAEMON_NAME = "x11server"

    /**
     * One-shot probe: does the guest have the helper? Output non-empty means
     * the absolute path of the binary was printed.
     */
    const val HELPER_PROBE_COMMAND = "command -v termux-x11 2>/dev/null"

    /**
     * The helper daemon command for [display] (`:N`). The helper connects to
     * the termux-x11 app over Android's binder and creates the X11 unix
     * socket at the path [X11SocketReadiness.guestSocketPath] — the file the
     * readiness check watches host-side. Run as its own supervised daemon so
     * a helper crash never takes the desktop session (or Xvnc) down with it.
     */
    fun helperDaemonCommand(display: String): String = "$HELPER_BINARY $display"
}

/**
 * Pure host-side readiness detection for the X11 unix socket
 * (transformation spec §13, and the audit's "readiness must not be a
 * magic delay").
 *
 * The workspace rootfs lives inside Linexa's private storage, so the guest's
 * `/tmp/.X11-unix/X<n>` IS a host-side path: `{rootfs}/tmp/.X11-unix/X<n>`.
 * Watching that file from the app costs one stat per poll — no guest exec,
 * no PRoot launch, no shell — which is the exact integration pattern Tiny
 * Container uses for its embedded server (`waitForFile(filesDir/tmp/.X11-unix/X0)`).
 *
 * This replaces the previous per-poll `test -S` PRoot exec (a full guest
 * launch per probe, seconds of latency on the critical path).
 */
object X11SocketReadiness {

    /** Guest-side socket path for a display (`:1` → `/tmp/.X11-unix/X1`). */
    fun guestSocketPath(display: String): String {
        val number = display.removePrefix(":").trim()
        return "/tmp/.X11-unix/X$number"
    }

    /**
     * Guest-side X display lock file for a display (`:1` → `/tmp/.X1-lock`).
     *
     * Every X server creates this file and — critically — CHECKS it at
     * startup: a lock file whose recorded pid still answers `kill(pid, 0)`
     * makes the new server abort with "Server is already active for display"
     * (exit code 1). Under PRoot the recorded pid is a HOST pid, so a previous
     * Xvnc that was SIGKILLed (grace-expiry teardown, app death) leaves a file
     * whose stale pid Android can reassign to an unrelated process within
     * minutes — and the next desktop start then fails with exit 1 for a
     * display nothing is serving. Same class of stale state as the unix
     * socket below, one directory over.
     */
    fun guestLockFilePath(display: String): String {
        val number = display.removePrefix(":").trim()
        return "/tmp/.X$number-lock"
    }

    /** The HOST-side file backing the guest socket for a workspace rootfs. */
    fun hostSocketFile(rootfsPath: String, display: String): java.io.File =
        java.io.File(rootfsPath, guestSocketPath(display).trimStart('/'))

    /** The HOST-side file backing the guest X display lock for a workspace rootfs. */
    fun hostLockFile(rootfsPath: String, display: String): java.io.File =
        java.io.File(rootfsPath, guestLockFilePath(display).trimStart('/'))

    /**
     * The guest-side directory the X server creates its socket in — used by
     * the one-time cleanup and by diagnostics text.
     */
    const val SOCKET_DIR = "/tmp/.X11-unix"

    /**
     * Stale-display cleanup: a workspace whose last desktop died hard (process
     * kill) can leave the display's unix socket AND its lock file behind; an
     * X server refuses a display whose socket already exists, and aborts with
     * exit 1 when the lock file's stale pid appears alive (see
     * [guestLockFilePath] for why pid reuse makes that a real restart
     * failure, not a theoretical one). Best-effort, idempotent, host-side.
     *
     * Called from [X11SessionManager.allocateDisplay] — i.e. only for a
     * display just allocated to THIS workspace, after the workspace's own
     * stale daemon was stopped — so the cleanup can never race a live server
     * of another workspace (displays are per-workspace allocated).
     */
    fun removeStaleSocket(rootfsPath: String, display: String): Boolean {
        val socket = hostSocketFile(rootfsPath, display)
        val lock = hostLockFile(rootfsPath, display)
        val socketGone = if (socket.exists()) socket.delete() else true
        val lockGone = if (lock.exists()) lock.delete() else true
        return socketGone && lockGone
    }
}
