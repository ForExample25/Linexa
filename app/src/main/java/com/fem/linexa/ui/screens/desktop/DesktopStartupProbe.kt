package com.fem.linexa.ui.screens.desktop

/**
 * §16.3.1 — the **combined cold-start discovery probe**: `which Xvnc` and the
 * desktop-session `which` in a single guest exec instead of two.
 *
 * ## Why
 *
 * The forensic audit's §10.1/§10.2 counted the cold Desktop start as four
 * independent one-shot PRoot launches before Xvnc is even asked to start. Each
 * one is a full `libproot.so` process: `BootstrapManager.setup()` re-runs, a
 * `busybox chmod` spawns for guest `/tmp`, a scoped `PROOT_TMP_DIR` is created
 * and deleted, `config.yaml` is read and the launch argv/env is rebuilt. Two of
 * those four launches — `which Xvnc` ([DesktopViewModel.startDesktop]) and the
 * session probe ([DesktopSessionProvisioner.detectSessionCommand]) — are pure
 * read-only lookups with no ordering relationship to each other: neither reads
 * anything the other writes, and the second's result is not consulted until the
 * first has already been decided. Merging them removes one complete PRoot launch
 * from the critical path with no change to what either lookup answers.
 *
 * ## What is deliberately **not** merged
 *
 * `pkill Xvnc || true` stays a separate exec. The audit's original §16.3
 * recommendation proposed a three-way merge including it, and the follow-up
 * review established that this is **not semantically neutral**: `pkill` runs
 * only when a session was found (`DesktopViewModel` returns
 * [DesktopUiState.NoSession] before reaching it), so folding a *mutation* into
 * the *discovery* exec would kill a running X server in exactly the case the
 * current code refuses to touch anything — a user with no desktop session
 * installed would lose an Xvnc they started by other means, as a side effect of
 * a failed probe. Discovery is idempotent; `pkill` is not. Only the idempotent
 * half is merged.
 *
 * ## The wire protocol
 *
 * Three lookups share one stdout, so the output needs to be self-describing. It is
 * framed by fixed markers rather than by line positions:
 *
 * ```
 * /usr/bin/Xvnc                 <- zero or more lines, or none when missing
 * __LINEXA_XVNC_RC__0           <- which Xvnc's OWN exit status, verbatim
 * __LINEXA_SESSION__            <- everything after this line is the session probe
 * /usr/bin/startxfce4
 * /usr/bin/xterm
 * __LINEXA_XVNC_CAPS__          <- everything after this line is Xvnc capability text
 * -localhost [yes|no]           <- zero or more lines from `Xvnc -help`, or none
 * ```
 *
 * Four properties this buys, each of which a positional or exit-code-based
 * encoding would lose:
 *
 *  * **The two failures stay independent.** The combined command's own exit
 *    status is the *last* command's, so it can no longer answer "was Xvnc
 *    found?" — a guest with Xvnc but no session, and a guest with a session but
 *    no Xvnc, would produce the same aggregate status. `which Xvnc`'s real `$?`
 *    is therefore captured into [XVNC_STATUS_MARKER] the instant it is produced,
 *    before any other command can overwrite `$?`.
 *  * **Xvnc's own path cannot be mistaken for a session.** The session section is
 *    sliced strictly *after* [SESSION_MARKER], so `/usr/bin/Xvnc` is never
 *    offered to [DesktopSessionProvisioner.parseSessionPath] — which would
 *    otherwise have to reject it by name, i.e. by knowing about Xvnc.
 *  * **Interleaved noise is survivable.** PRoot warnings and `which`'s own
 *    diagnostics land in whichever section they were emitted in and are filtered
 *    exactly as they are today (`parseSessionPath` already ignores anything that
 *    is not an absolute whitespace-free path).
 *  * **The capability section is read, never trusted.** `Xvnc -help` is the
 *    server's own option list; grepping it for `-localhost` is how the start
 *    path learns whether THIS installed Xvnc accepts the flag. TigerVNC ≥ 1.11
 *    has it; 1.10 and older reject an unknown option with a fatal error at
 *    startup — i.e. exit code 1 on every start — so the flag must be EARNED
 *    from the binary, not assumed from the distro name. A missing/empty
 *    section (older probe output, a truncated exec) reports "unsupported",
 *    which degrades to a command the old Xvnc accepts.
 *
 * A malformed or truncated output — the timeout case, where the exec is killed
 * mid-stream and [XVNC_STATUS_MARKER] never arrives — reports Xvnc as **not
 * found** ([Result.xvncFound] = false, [Result.wellFormed] = false). That is the
 * fail-safe direction and reproduces the pre-§16.3.1 behaviour exactly: the old
 * `which.exitCode != 0` was also true for a timeout (`exitCode` = -1), and the
 * auto-install path it leads to surfaces the timeout as its own error.
 *
 * ## Shell safety
 *
 * No value from outside this object is interpolated. The session half is
 * [DesktopSessionProvisioner.detectSessionCommand]'s output used verbatim — that
 * function remains the single source of truth for the candidate list and the
 * only place a declared session binary is validated and spliced (§1.14), so the
 * 19 candidates are not restated here and a declaration cannot reach the guest
 * through a second, unvalidated path. Everything else is a compile-time
 * constant.
 */
object DesktopStartupProbe {

    /**
     * Prefix of the line carrying `which Xvnc`'s own exit status.
     *
     * The status is appended directly (`__LINEXA_XVNC_RC__0`) rather than
     * separated by a space, so a shell that word-splits differently, or a guest
     * whose `echo` collapses whitespace, cannot change how the line parses.
     */
    const val XVNC_STATUS_MARKER = "__LINEXA_XVNC_RC__"

    /** Sole line separating the Xvnc section from the session-probe section. */
    const val SESSION_MARKER = "__LINEXA_SESSION__"

    /** Sole line separating the session probe from the Xvnc capability section. */
    const val XVNC_CAPS_MARKER = "__LINEXA_XVNC_CAPS__"

    /**
     * The option token whose presence in `Xvnc -help` output proves the
     * installed server accepts `-localhost`. Grep is for the bare option name
     * (not `-localhost yes`): usage text renders the value bracketed or
     * default-annotated differently across builds.
     */
    const val LOCALHOST_OPTION_TOKEN = "-localhost"

    /**
     * The combined probe, for a run whose rootfs may declare a session binary.
     *
     * [declaredSession] is passed straight through to
     * [DesktopSessionProvisioner.detectSessionCommand], which owns both the
     * candidate list and the §1.14 validation of the declared value. This
     * function never inspects or interpolates it.
     *
     * `which Xvnc` runs **first** and its `$?` is captured immediately: `$?` is
     * clobbered by every subsequent command, so any ordering that echoes the
     * marker later would report the wrong status. The session `which` runs
     * before the capability section because both produce variable-length
     * output — the fixed markers, not the order, keep them separable.
     */
    fun command(declaredSession: String? = null): String {
        val sessionProbe = DesktopSessionProvisioner.detectSessionCommand(declaredSession)
        return "which Xvnc 2>/dev/null; " +
            "echo \"$XVNC_STATUS_MARKER\$?\"; " +
            "echo $SESSION_MARKER; " +
            sessionProbe + "; " +
            "echo $XVNC_CAPS_MARKER; " +
            "Xvnc -help 2>&1 | grep -m1 -- '$LOCALHOST_OPTION_TOKEN' || true"
    }

    /**
     * One combined probe's decoded result.
     *
     * @param xvncFound whether `which Xvnc` exited 0. False when the marker is
     *   absent, which is the truncated/timed-out case — see the class doc for
     *   why that is the correct fail-safe direction.
     * @param xvncPath Xvnc's absolute guest path when it printed one. Recorded
     *   for diagnostics only: the start path decides on [xvncFound], exactly as
     *   it decided on `which Xvnc`'s exit code before §16.3.1.
     * @param sessionProbeOutput the session `which`'s stdout, verbatim and
     *   unparsed, ready for [DesktopSessionProvisioner.parseSessionPath]. Empty
     *   when the section is missing.
     * @param wellFormed whether both markers were present and in order. Callers
     *   do not branch on this — [xvncFound] already carries the safe answer —
     *   but it is what makes "the guest returned something unexpected"
     *   distinguishable from "Xvnc is genuinely absent" in a log line.
     */
    data class Result(
        val xvncFound: Boolean,
        val xvncPath: String?,
        val sessionProbeOutput: String,
        val wellFormed: Boolean,
        /**
         * Whether the guest's own `Xvnc -help` output advertises `-localhost`.
         * Defaults to false when the capability section is absent or unreadable
         * — the fail-safe direction: a command without `-localhost` is accepted
         * by every TigerVNC build, while an unearned `-localhost yes` kills the
         * start on TigerVNC ≤ 1.10.
         */
        val localhostSupported: Boolean
    )

    /**
     * Decodes [command]'s stdout.
     *
     * Marker lookup is by content, never by index: the first line matching each
     * marker wins, and the session section is everything after
     * [SESSION_MARKER]. A guest that prepends a banner, a PRoot that warns, or a
     * `which` that prints nothing all shift every line number, so a positional
     * reading would be fragile in precisely the situations the framing exists to
     * survive.
     */
    fun parse(probeOutput: String): Result {
        val lines = probeOutput.lines()
        val statusIndex = lines.indexOfFirst { it.trim().startsWith(XVNC_STATUS_MARKER) }
        val sessionIndex = lines.indexOfFirst { it.trim() == SESSION_MARKER }
        // The session marker must exist AND follow the status marker. Either
        // absent, or out of order, means this is not output this function wrote
        // the command for — treat it as "Xvnc not found" rather than guessing.
        if (statusIndex < 0 || sessionIndex <= statusIndex) {
            return Result(
                xvncFound = false,
                xvncPath = null,
                sessionProbeOutput = "",
                wellFormed = false,
                localhostSupported = false
            )
        }
        val status = lines[statusIndex].trim()
            .removePrefix(XVNC_STATUS_MARKER)
            .trim()
            .toIntOrNull()
        val xvncPath = lines.subList(0, statusIndex)
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") && it.none { ch -> ch.isWhitespace() } }
        // The capability section is OPTIONAL: absent (older capture, truncated
        // exec) means "unknown", which resolves to unsupported — the Xvnc
        // command then omits -localhost, which every build accepts.
        val capsIndex = lines.indexOfFirst { it.trim() == XVNC_CAPS_MARKER }
        val localhostSupported = capsIndex > sessionIndex &&
            lines.subList(capsIndex + 1, lines.size)
                .any { it.contains(LOCALHOST_OPTION_TOKEN) }
        return Result(
            // A marker whose status is unparseable is a malformed status, not a
            // success: only a literal 0 means Xvnc was found.
            xvncFound = status == 0,
            xvncPath = xvncPath,
            sessionProbeOutput = lines.subList(sessionIndex + 1, maxOf(sessionIndex + 1, capsIndex.takeIf { it > sessionIndex } ?: lines.size)).joinToString("\n"),
            wellFormed = status != null,
            localhostSupported = localhostSupported
        )
    }
}
