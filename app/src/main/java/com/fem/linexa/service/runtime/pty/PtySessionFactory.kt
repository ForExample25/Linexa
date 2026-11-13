package com.fem.linexa.service.runtime.pty

/**
 * Creates [PtySession]s. The factory seam (§1.20) exists so
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime]
 * never constructs a [TermuxPtySession] — or imports anything from the
 * terminal implementation (`com.termux.*`, the vendored Apache-2.0 modules) —
 * directly.
 */
interface PtySessionFactory {

    /**
     * Allocate a real pty and start [executable] with [args] inside it.
     *
     * §1.30 exec convention: [args] are the *arguments only* — the
     * implementation always prepends [executable] as the child's argv[0]
     * (see [TermuxPtySession]). Do NOT include argv[0] in [args]: the
     * session's native `execvp(executable, argv)` builds argv verbatim from
     * this array, so an args array like `["-l"]` would leave the child with
     * `argv[0] = "-l"` — BusyBox dispatches applets by basename(argv[0]),
     * so it would fail with `applet not found` / exit 127 and the session
     * would close instantly.
     *
     * [columns]/[rows] must be a real initial terminal size, not a
     * placeholder: the underlying library only starts the child process
     * once a size is known (see [TermuxPtySession]), so this is load-bearing,
     * not cosmetic.
     */
    fun create(
        workspaceId: String,
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        columns: Int = 80,
        rows: Int = 24,
        /**
         * 2026-08-29 (appearance fix): HOST-side path of the terminal's Linux
         * HOME directory (e.g. `<rootfs>/root` for a workspace whose guest
         * HOME is /root), or null when there is none (host management shell).
         * The terminal layer owns its appearance config from there:
         * `.termux/colors.properties` (Termux format) and `.termux/font.ttf`.
         * This is terminal-layer configuration — NOT Linexa app settings.
         */
        terminalConfigHome: String? = null
    ): PtySession
}
