package com.fem.linexa.ui.screens.desktop

import java.security.SecureRandom

/**
 * Everything the VNC run mode needs to turn a **password setting** into a
 * running Xvnc that requires it (Desktop Modes work, 2026-08).
 *
 * Split from [DesktopGeometry] because the two carry different risks: the
 * geometry string is a constant per device, while these strings embed a
 * secret and run through a shell. The rules that keep that safe:
 *
 * - The password reaches the guest **only** through [provisionCommand], which
 *   single-quotes it with [shellSingleQuoted]. A password that survived that
 *   quoting cannot inject shell syntax — but [generatePassword] also only
 *   ever emits `[a-zA-Z0-9]`, so the generated default (the intended flow,
 *   per [com.fem.linexa.service.runtime.features.FeatureSettings.desktopVncPassword])
 *   contains nothing for the quoting to fight in the first place. A hand-typed
 *   password with a quote inside is still safe, merely escaped.
 * - The `vncpasswd -f` step is guarded by `command -v vncpasswd`: the passwd
 *   file is written only when the guest's TigerVNC install actually ships the
 *   helper, and the ViewModel treats a non-zero exit as "proceed without
 *   authentication" rather than "start nothing" — a missing helper must
 *   degrade to the pre-Desktop-Modes behaviour, not break the desktop.
 * - The keyfile lives at [PASSWD_PATH], inside the workspace's own rootfs.
 *   It is per-workspace by construction and never leaves the guest FS.
 *
 * Pure and JVM-testable, per the `XvncProvisioner` convention: no Android
 * types, every string deterministic.
 */
internal object DesktopVncAuthProvisioner {

    /**
     * Where the encrypted VNC password file is placed inside the guest, for
     * the workspace's resolved home. `~/.vnc/` is Xvnc's own convention; a
     * Linexa-specific file name keeps it apart from a passwd file the user may
     * have made by hand.
     *
     * 2026-09-05 (/home/tiny → /home/linexa migration): the path is DERIVED
     * from the workspace's resolved guest home instead of assuming one. A
     * rootfs that declares a different `linexa.environment.home` must not
     * silently grow a second, half-owned `.vnc` tree under a home the session
     * never uses. [PASSWD_PATH] remains the canonical default for the standard
     * home (and for existing tests/pins).
     */
    // Issue #1 (2026-09-03): Xvnc runs as the workspace's normal user now,
    // so the keyfile lives under that account's home (the launcher's
    // HOME=/home/linexa), not under /root. Provisioning (root exec) and
    // the Xvnc argv (user session) derive from the same resolved home, so
    // the pair can never disagree.
    const val PASSWD_PATH = "/home/linexa/.vnc/linexa-vnc-passwd"

    /** The home-relative tail of [passwdPathFor]; also Xvnc's own convention. */
    const val PASSWD_RELATIVE_PATH = ".vnc/linexa-vnc-passwd"

    /**
     * The passwd file path inside [guestHome] — the resolved guest home from
     * [com.fem.linexa.service.runtime.container.GuestEnvironmentResolver].
     * A blank or malformed home degrades to [PASSWD_PATH], which the standard
     * profiles declare anyway.
     */
    fun passwdPathFor(guestHome: String?): String {
        val home = guestHome?.trim()?.trimEnd('/')
        if (home.isNullOrEmpty() || !home.startsWith("/") || home == "/") return PASSWD_PATH
        return "$home/$PASSWD_RELATIVE_PATH"
    }

    /**
     * The one-shot guest command that materialises the passwd file (in the
     * workspace's resolved home, see [passwdPathFor]) from [password], or a
     * command whose non-zero exit signals "no vncpasswd in this guest".
     *
     * Shape: `mkdir -p` (TigerVNC does not create the directory itself) →
     * `command -v vncpasswd` guard → `printf '%s\n' '<pw>' | vncpasswd -f`
     * (reads one line from stdin, writes the DES-encrypted keyfile format) →
     * `chmod 600` (the file is secret; Xvnc reads it as the same emulated
     * root, but the mode is what a passwd file owes its surroundings).
     *
     * Pre-release audit fix (2026-09-02): [shellSingleQuoted] already returns
     * the fully-quoted token (`'…'`), so the previous template's extra quote
     * pair around it produced `''<quoted>''` — the segments between the
     * outer quote-pairs are then *unquoted shell text*. A password like
     * `a;b$(id)` executed command substitution in the guest shell, and a
     * password containing `'` terminated the parse early (unterminated
     * string → provisioning failure → the start path degraded to an
     * unauthenticated desktop). The token must be interpolated bare.
     */
    fun provisionCommand(password: String, guestHome: String? = null): String {
        val passwdPath = passwdPathFor(guestHome)
        val vncDir = passwdPath.substringBeforeLast('/')
        return "mkdir -p $vncDir && command -v vncpasswd >/dev/null 2>&1 && " +
            "printf '%s\\n' ${shellSingleQuoted(password)} | vncpasswd -f > $passwdPath && " +
            "chmod 600 $passwdPath"
    }

    /**
     * The `-SecurityTypes …` arguments Xvnc must be started with. When
     * [hasPassword] the server offers **only** VNC-Auth (an offered `None`
     * alongside it would let any local app bypass the secret), and points at
     * the keyfile in [guestHome]; without a password the pre-Desktop-Modes
     * `None` stands.
     */
    fun securityArgs(hasPassword: Boolean, guestHome: String? = null): String {
        val passwdPath = passwdPathFor(guestHome)
        return if (hasPassword) "-SecurityTypes VncAuth -rfbauth $passwdPath"
        else SECURITY_ARGS_NONE
    }

    /**
     * Phase 07 (OPT-SEC-004, Step B2): whether an unauthenticated Xvnc start
     * must be REFUSED outright.
     *
     * `securityArgs` is [SECURITY_ARGS_NONE] exactly when no password was
     * provisioned. For such a start the only thing standing between the
     * desktop and every other app on the device is Xvnc's own `-localhost
     * yes` — and the guest's Xvnc only accepts that flag from TigerVNC ≥ 1.11
     * (older versions exit 1 on the unknown option, which is why
     * [com.fem.linexa.ui.screens.desktop.DesktopGeometry.xvncCommand] only
     * sends it when [localhostSupported] was probed, never assumed). An
     * Xvnc that cannot be restricted to loopback AND has no password is
     * reachable, unauthenticated, by any local app: the start must fail
     * closed, before any process exists.
     *
     * Pure and JVM-testable; the ViewModel consults it with the SAME
     * [localhostSupported] value that builds the command — never a guess.
     */
    fun unauthenticatedStartBlocked(localhostSupported: Boolean, securityArgs: String): Boolean =
        !localhostSupported && securityArgs == SECURITY_ARGS_NONE

    /**
     * Phase 07 (OPT-SEC-004, Step B3): the guest command that lists the
     * host's TCP LISTEN sockets, so the ACTUAL bind address of a started Xvnc
     * can be verified instead of assumed.
     *
     * Tool availability is feature-detected, never assumed: `ss` (iproute2)
     * first, then `netstat` (net-tools), then BusyBox's built-in netstat —
     * minimal rootfs images ship none of the first two. Every probe is
     * guarded by `command -v`, stderr is dropped, and the `|| true` tail
     * keeps the exit code 0 when nothing is available: an EMPTY output is
     * "cannot verify", which the caller must treat as a warning (keep the
     * password requirement), never as evidence of loopback-only.
     */
    const val BIND_PROBE_COMMAND =
        "(command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null) || " +
            "(command -v netstat >/dev/null 2>&1 && netstat -ltn 2>/dev/null) || " +
            "(command -v busybox >/dev/null 2>&1 && busybox netstat -ltn 2>/dev/null) || true"

    /**
     * Phase 07 (OPT-SEC-004, Step B3): whether a listener listing
     * ([BIND_PROBE_COMMAND]'s output) proves the VNC [port] is reachable
     * from anything but loopback.
     *
     * Returns the offending `address:port` list (for the user-facing error),
     * or `null` when the evidence says loopback-only — **or says nothing at
     * all**: a null/blank listing is "unverified", and per the brief the
     * caller must log a warning and keep the password requirement rather
     * than claim a loopback-only bind without evidence. A single non-loopback
     * listener beside the loopback one is still a violation: what matters is
     * that ANY socket on the port answers on a routable address.
     *
     * Parsing notes: `ss -ltn` / `netstat -ltn` listeners carry the port only
     * in the LOCAL address column (the peer is `*:*`), and the trailing
     * `(?!\d)` keeps e.g. `:59011` from matching a `5901` probe. IPv6 shapes
     * covered: `::1:5901`, `[::1]:5901` (loopback) vs `[::]:5901`, `:::5901`,
     * `*:5901`, `0.0.0.0:5901` (violations).
     */
    fun loopbackBindViolation(listenerOutput: String?, port: Int): String? {
        if (listenerOutput.isNullOrBlank()) return null
        val offenders = Regex("""(\S+):$port(?!\d)""")
            .findAll(listenerOutput)
            .mapNotNull { it.groupValues[1] }
            .filterNot { isLoopbackListener(it) }
            .distinct()
            .toList()
        return if (offenders.isEmpty()) null else offenders.joinToString(", ")
    }

    /**
     * Whether [address] (the local-address token of a LISTEN line) can only
     * answer on loopback. IPv6 brackets are stripped; `localhost` is
     * accepted because a numeric listing (`-ltn`) would never produce it and
     * a symbolic one can only resolve to the loopback block.
     */
    private fun isLoopbackListener(address: String): Boolean {
        val normalized = address.trim().removePrefix("[").removeSuffix("]")
        return normalized == "localhost" || normalized.startsWith("127.") || normalized == "::1"
    }

    /**
     * P11: what the desktop start path must do given the stored password.
     *
     * An empty password means "start with no VNC authentication" — a deliberate choice
     * the user can make. But an empty password that is empty **because the stored one
     * could not be decrypted** is not that choice, and honouring it would silently turn
     * the desktop into an unauthenticated one. Pure, so the rule is JVM-testable
     * without the ViewModel.
     */
    fun authRequirement(password: String, unreadable: Boolean): VncAuthRequirement = when {
        password.isNotEmpty() -> VncAuthRequirement.Password
        unreadable -> VncAuthRequirement.BlockedUnreadable
        else -> VncAuthRequirement.None
    }

    /**
     * A generated password: 12 characters of `[a-zA-Z0-9]` from
     * [SecureRandom]. 62^12 ≈ 3×10^21 — far beyond any brute force a phone's
     * local apps can mount, and free of every character class that would make
     * shell quoting, Xvnc argv or the settings field interesting.
     */
    fun generatePassword(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val chars = CharArray(GENERATED_PASSWORD_LENGTH) { alphabet[random.nextInt(alphabet.length)] }
        return String(chars)
    }

    /**
     * Wrap [raw] in POSIX single quotes, escaping embedded single quotes the
     * `'\''` way — the only construct a single-quoted string needs, and the
     * reason an arbitrary password cannot turn into shell syntax.
     */
    fun shellSingleQuoted(raw: String): String = "'${raw.replace("'", "'\\''")}'"

    /** Length of [generatePassword] output; pinned by test. */
    const val GENERATED_PASSWORD_LENGTH = 12

    /**
     * The `-SecurityTypes` value of an unauthenticated server — the exact
     * string [securityArgs] emits without a password, shared with
     * [unauthenticatedStartBlocked] so the two can never drift apart.
     */
    const val SECURITY_ARGS_NONE = "-SecurityTypes None"
}

/** See [DesktopVncAuthProvisioner.authRequirement]. */
internal enum class VncAuthRequirement { None, Password, BlockedUnreadable }
