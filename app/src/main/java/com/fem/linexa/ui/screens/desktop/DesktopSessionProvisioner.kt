package com.fem.linexa.ui.screens.desktop

/**
 * §1.114 — Pure (JVM-testable) resolution of the guest's **desktop session**:
 * the X client that actually draws something for Xvnc to serve.
 *
 * Why this exists: before §1.114 the Desktop feature started `Xvnc :1` and
 * nothing else. An X server with no clients connected renders the bare root
 * window, so a user who tapped "Start Desktop" reached a working VNC stream of
 * an empty grey screen — every hop in the chain "succeeded" and the feature was
 * still unusable. `DISPLAY` was likewise never exported anywhere
 * ([com.fem.linexa.service.runtime.container.PRootLauncher]'s launch env is
 * `HOME/USER/TERM/LANG/PATH` only), so even a manually started client had no
 * display to attach to.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.114):
 *
 * - **Detect and run; never install.** Unlike Xvnc (§1.85, auto-installed
 *   because it is the feature's own dependency and has no substitute), a
 *   desktop environment is a large, opinionated, multi-hundred-megabyte choice
 *   that belongs to the user. This object only *finds* what the workspace
 *   already has; when nothing is found the UI reports it and names candidate
 *   packages ([SUGGESTED_PACKAGES_HINT]) instead of silently reshaping the
 *   guest. Same rule §1.113 applied to PulseAudio.
 *
 * - **Session preference is full-DE → window-manager → bare terminal**
 *   ([SESSION_CANDIDATES]). A session manager (`xfce4-session`, `startxfce4`,
 *   `startlxde`, `mate-session`, `startplasma-x11`) brings its own panel and
 *   window manager, so it is strictly better than a WM alone when present; a
 *   plain WM (`openbox`, `fluxbox`, `i3`, `jwm`, `icewm`, `twm`) is a usable
 *   desktop; `xterm`/`xclock` are the last resort, deliberately kept because a
 *   *visible window* is what proves the whole pipeline end-to-end and is far
 *   better feedback than a grey screen. `x-window-manager` and
 *   `x-session-manager` (Debian alternatives symlinks) are probed first of
 *   their class so a Debian-like guest gets whatever its admin selected.
 *
 * - **Every command is a constant or built only from values this object owns.**
 *   The daemon command interpolates only [DISPLAY] and a path that came out of
 *   [parseSessionPath], which rejects anything relative or containing
 *   whitespace — no user-supplied string ever reaches a guest command (§1.14).
 *
 * - **The session runs as its own supervised guest daemon** (§1.83), non-forking
 *   by construction: `env DISPLAY=:1 exec`-ed by the daemon host, so the session
 *   process *is* the initial tracee of its PRoot host. A session that exits
 *   therefore tears down only its own host, exactly like sshd (§1.109) — and
 *   critically not Xvnc's, so the X server survives a window manager crash.
 */
object DesktopSessionProvisioner {

    /** Stable guest-daemon name for the desktop session (see `startDaemon`). */
    const val DAEMON_NAME = "xsession"

    /** The X display Xvnc serves, and the only display Linexa ever starts. */
    const val DISPLAY = ":1"

    /**
     * Candidate session binaries, in the order they should be preferred.
     * Session/DE managers first, then window managers, then a bare X client.
     *
     * §E fix (transformation spec §16): LXQt's session binaries
     * (`startlxqt`, `lxqt-session`) are in the list — the old list only knew
     * LXDE's (`startlxde`/`lxsession`), so a workspace that installed the
     * LXQt preset re-probed to nothing and the desktop refused to start
     * despite a successful install.
     */
    val SESSION_CANDIDATES: List<String> = listOf(
        // Debian-likes' "whatever this system selected" alternatives.
        "x-session-manager",
        "x-window-manager",
        // Full desktop environments / session managers.
        "startxfce4",
        "xfce4-session",
        "startlxqt",
        "lxqt-session",
        "startlxde",
        "lxsession",
        "mate-session",
        "startplasma-x11",
        // Plain window managers.
        "openbox-session",
        "openbox",
        "fluxbox",
        "icewm-session",
        "icewm",
        "i3",
        "jwm",
        "twm",
        // Last resort: a single visible window still proves the pipeline.
        "xterm",
        "xclock"
    )

    /**
     * Probe that locates a desktop session inside the guest. `which` is given
     * every candidate at once (one exec, same shape as
     * [com.fem.linexa.domain.packagemanager.PackageManagerDetector.PROBE_COMMAND])
     * and prints one absolute path per hit; [parseSessionPath] applies the
     * preference order, so the probe deliberately does **not** `head -1`.
     *
     * This is the no-declaration form of [detectSessionCommand] — that
     * function is the single source of truth so a declared session binary and
     * the built-in candidates cannot drift apart.
     */
    val DETECT_SESSION_COMMAND: String get() = detectSessionCommand()

    /**
     * The preferred session's absolute guest path from [DETECT_SESSION_COMMAND]'s
     * stdout, or null when the guest has no usable X client.
     *
     * Ordering comes from [SESSION_CANDIDATES], never from the order `which`
     * happened to print — `which` echoes results in *argument* order, which is
     * the same list, but a guest whose `which` reorders or whose PATH yields
     * duplicates must still produce a deterministic answer.
     *
     * A line that is not an absolute path, or that contains whitespace, is
     * ignored rather than returned: it would be handed to the daemon host as a
     * command, where a space would split into arguments and a relative path
     * would resolve against the guest's `PATH` (the ambiguity the absolute
     * probe exists to remove).
     *
     * §1.118: [preferred] jumps the queue. It carries the basenames implied by
     * the rootfs's declared `linexa.desktop` block, so a workspace that says it
     * ships XFCE gets `startxfce4` even on a guest that also has `openbox`
     * installed — which the fixed [SESSION_CANDIDATES] order would otherwise
     * decide for it. The declaration only *reorders* candidates: a declared
     * session that isn't actually installed still falls through to whatever the
     * guest really has, so a stale declaration can't make the desktop
     * unstartable.
     */
    fun parseSessionPath(probeOutput: String, preferred: List<String> = emptyList()): String? {
        val paths = probeOutput.lines()
            .map { it.trim() }
            .filter { it.startsWith("/") && !it.any { ch -> ch.isWhitespace() } }
        if (paths.isEmpty()) return null
        val order = (preferred.filter { it.isNotBlank() } + SESSION_CANDIDATES).distinct()
        return order.firstNotNullOfOrNull { candidate ->
            paths.firstOrNull { it.substringAfterLast('/') == candidate }
        }
    }

    /**
     * §1.118: the probe command for a run that also has to consider a declared
     * session binary. A declared `linexa.desktop.session` may name something
     * outside [SESSION_CANDIDATES] entirely (a custom session script is a
     * legitimate thing for a rootfs author to ship), so it has to be added to
     * the `which` argument list or the probe would never see it.
     *
     * [declaredSession] is validated by [RootFsMetadataValidator] to a single
     * whitespace-free token before it ever reaches here (§1.14) — this function
     * re-checks that rather than assuming, because it is the point where the
     * value enters a command string.
     */
    fun detectSessionCommand(declaredSession: String? = null): String {
        val extra = declaredSession
            ?.takeIf { it.isNotBlank() && it.none { ch -> ch.isWhitespace() } && it !in SESSION_CANDIDATES }
            ?.let { listOf(it) }
            .orEmpty()
        return "which ${(extra + SESSION_CANDIDATES).joinToString(" ")} 2>/dev/null"
    }

    /**
     * §1.118: the basenames a declared `linexa.desktop` block prefers, most
     * specific first — the explicitly declared `session` (its basename, so a
     * declared absolute path still matches the probe's output), then the
     * session/DE binaries implied by the declared `type`.
     *
     * Returns an empty list when nothing was declared, which leaves the fixed
     * [SESSION_CANDIDATES] order in charge.
     */
    fun preferredCandidates(declaredType: String?, declaredSession: String?): List<String> {
        val fromSession = declaredSession?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
        val fromType = declaredType?.lowercase()?.let { DESKTOP_TYPE_SESSIONS[it] }.orEmpty()
        return (listOfNotNull(fromSession) + fromType).distinct()
    }

    /**
     * Declared `linexa.desktop.type` → the session binaries that type ships,
     * in preference order. Only types with a *known* session entry point are
     * listed; an unrecognized type contributes nothing and the probe order
     * decides, which is why validation accepts any safe token for `type`
     * (rejecting types would make the schema a closed list that ages badly,
     * while an unknown one costs nothing here).
     */
    private val DESKTOP_TYPE_SESSIONS: Map<String, List<String>> = mapOf(
        "xfce" to listOf("startxfce4", "xfce4-session"),
        "xfce4" to listOf("startxfce4", "xfce4-session"),
        "lxde" to listOf("startlxde", "lxsession"),
        "lxqt" to listOf("startlxqt", "lxqt-session"),
        "mate" to listOf("mate-session"),
        "kde" to listOf("startplasma-x11"),
        "plasma" to listOf("startplasma-x11"),
        "gnome" to listOf("gnome-session"),
        "cinnamon" to listOf("cinnamon-session"),
        "openbox" to listOf("openbox-session", "openbox"),
        "fluxbox" to listOf("fluxbox"),
        "icewm" to listOf("icewm-session", "icewm"),
        "i3" to listOf("i3"),
        "jwm" to listOf("jwm"),
        "twm" to listOf("twm")
    )

    /**
     * The human-readable name of the session at [sessionPath] — its basename,
     * used for the Desktop status line so the user can see *what* is running
     * (an `xterm`-only fallback looks very different from `startxfce4`, and
     * they should not be reported identically).
     */
    fun sessionDisplayName(sessionPath: String): String = sessionPath.substringAfterLast('/')

    /**
     * The daemon command that starts [sessionPath] against [display].
     *
     * `env DISPLAY=:1 <session>` rather than a shell assignment: the daemon
     * host wraps this in `/bin/sh -c 'exec …'`, and `exec env …` keeps the
     * session as the initial tracee (a `DISPLAY=:1 cmd` prefix would too, but
     * `env` is explicit and cannot be mistaken for a shell variable that only
     * exists in the wrapping shell).
     *
     * Transformation spec §14: [extraEnv] entries (TMPDIR, XKB_CONFIG_ROOT,
     * …) join the prefix — the same key set the termux-x11 reference
     * integration passes through EXTRA_ENV_KEYS/EXTRA_ENV_VALUES, now
     * resolved to guest-native paths. The legacy single-argument call keeps
     * the exact historical shape (`env DISPLAY=:1 <session>`), pinned by
     * tests.
     */
    fun daemonCommand(
        sessionPath: String,
        display: String = DISPLAY,
        extraEnv: Map<String, String> = emptyMap()
    ): String {
        val env = extraEnv.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val prefix = if (env.isEmpty()) "env DISPLAY=$display" else "env DISPLAY=$display $env"
        return "$prefix $sessionPath"
    }

    /**
     * The `DISPLAY` value guest one-shot commands should use when they need to
     * talk to the desktop's X server. Exposed as a helper so callers don't
     * re-spell the display.
     */
    fun exportDisplayPrefix(display: String = DISPLAY): String = "DISPLAY=$display"

    /**
     * Packages worth suggesting when no session was found, as a short
     * user-facing hint. Deliberately not a command: Linexa does not install
     * these (see the class kdoc), so presenting them as something to run in the
     * workspace's own terminal is the honest framing.
     */
    const val SUGGESTED_PACKAGES_HINT = "openbox, xfce4, or xterm"
}
