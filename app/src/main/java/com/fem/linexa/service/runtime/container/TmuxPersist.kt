package com.fem.linexa.service.runtime.container

import java.io.File

/**
 * Pure (JVM-testable) argv construction for the tmux-backed persistent
 * session layer (§1.20, §1.58). `PRootLauncher` keeps the Android-side
 * binary/socket plumbing; everything here is deterministic string/File
 * work so the full "tmux wraps proot wraps bash" chain can be asserted in
 * a plain JUnit test without an Application.
 *
 * Design notes (see `PRootLauncher.kt` for the fuller record):
 * - tmux runs **outside** proot, on the host: `tmux -S <sock> new-session
 *   -A -s <name> <proot...> <bash>` — so a stock guest rootfs never needs
 *   tmux installed.
 * - `new-session -A` makes create-or-reattach idempotent: if a live
 *   server already owns the socket, the client attaches to it and the
 *   inner command is ignored; otherwise a fresh session (and server) is
 *   created running the inner command.
 * - Stopping a workspace kills the pty, which only kills the tmux *client*
 *   (the process under forkpty); the detached server — and the proot +
 *   guest shell inside it — would otherwise keep running. Callers must
 *   therefore run [killServerArgs] when a session is stopped, so "stop"
 *   actually stops (see `PRootContainerRuntime`).
 *
 * §1.76: these builders are NOT used by the current launch path — the pty
 * execs proot directly (PTY → proot → bash), because the tmux client exits
 * 0 once the server accepts the session and takes the pty down with it.
 * Everything here is preserved verbatim for the future explicit
 * tmux-persistence feature; the JVM tests below keep it pinned.
 */
object TmuxPersist {

    /** Base session name used for a workspace's persistent sessions. */
    const val DEFAULT_SESSION_NAME = "linexa"

    /** Per-workspace tmux socket file, so workspaces never share a server. */
    fun socketFile(workspaceDir: File): File = File(workspaceDir, ".tmux.sock")

    fun socketPath(workspaceDir: File): String = socketFile(workspaceDir).absolutePath

    /**
     * The tmux session name for ONE terminal of a workspace (transformation
     * spec §9/§F — per-terminal persistence).
     *
     * The previous single shared name (`"linexa"`) meant every terminal of a
     * persistence-enabled workspace wrapped `tmux -A -s linexa` on the same
     * socket: the second terminal REATTACHED to the first one's session
     * instead of getting its own shell — two terminal tabs rendering one
     * session, each keystroke duplicated to both. With `-A` (reattach-or-
     * create) keyed by terminal id, each terminal gets its own session, and
     * a re-opened terminal id reattaches to ITS session — which is exactly
     * the reconnect semantics §9 requires.
     *
     * tmux session names may not contain `.` or `:`; terminal ids are
     * allocator-generated (`T0`, `T1`, …) so the sanitize is a formality —
     * but it keeps the function total for any future id shape.
     */
    fun sessionNameFor(terminalId: String): String {
        val safe = terminalId.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "${DEFAULT_SESSION_NAME}-${safe.ifEmpty { "default" }}"
    }

    /**
     * `tmux -S <sock> new-session -A -s <name> <inner>...` — wraps the
     * already-built proot [innerExecutable]/[innerArgs] so running it
     * creates-or-reattaches the persistent session.
     */
    fun newSessionArgs(
        socketPath: String,
        sessionName: String,
        innerExecutable: String,
        innerArgs: List<String>
    ): List<String> =
        listOf("-S", socketPath, "new-session", "-A", "-s", sessionName, innerExecutable) + innerArgs

    /**
     * `tmux -S <sock> kill-server` — tears down the workspace's tmux server
     * (and with it the proot + guest shell it hosts). Exit code is not
     * meaningful: "no server running" is a normal, inoffensive outcome.
     */
    fun killServerArgs(socketPath: String): List<String> =
        listOf("-S", socketPath, "kill-server")
}
