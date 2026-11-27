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
 * - The password reaches the guest **only** through [provisionRequest], and
 *   only on the guest process's **stdin**. It is never part of the command
 *   string that becomes PRoot's argv, so it cannot be read from
 *   `/proc/<pid>/cmdline` by another process of the same UID while provisioning
 *   runs (finding 031). The generated default is still `[a-zA-Z0-9]`, but stdin
 *   means a hand-typed password with a quote or `$()` is not shell syntax at
 *   all — there is no shell interpolation of the secret left to defend.
 * - The passwd file is written through a fresh temporary file opened under
 *   `set -C` (noclobber): the shell's redirection is `O_CREAT|O_EXCL`, so a
 *   symlink planted at the temp path makes provisioning fail instead of
 *   redirecting the (root-emulated) write elsewhere. `umask 077` gives the file
 *   mode 0600 from the instant it exists, and `mv -f` is `rename(2)`, which
 *   replaces the destination entry atomically without following a symlink
 *   there. A destination that is a directory is refused rather than moved into.
 * - The `vncpasswd -f` step is guarded by `command -v vncpasswd`: the passwd
 *   file is written only when the guest's TigerVNC install actually ships the
 *   helper. A non-zero exit is **fail-closed**: when a password was configured
 *   but could not be provisioned, [com.fem.linexa.ui.screens.desktop.DesktopViewModel]
 *   refuses to start Xvnc instead of silently falling back to an
 *   unauthenticated server. A missing helper must never turn a
 *   password-protected desktop into one any local app can attach to.
 *   `DesktopVncAuthProvisionerTest` and the fail-closed branches in both desktop
 *   start paths pin this policy. (Finding 038: this KDoc previously described
 *   the old fail-open behaviour.)
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
        // A declared home is metadata, not a trusted literal: a `..` segment
        // would let it escape the home it names. Fall back to the standard path
        // instead of provisioning outside it.
        if (home.split('/').any { it == ".." }) return PASSWD_PATH
        return "$home/$PASSWD_RELATIVE_PATH"
    }

    /**
     * One provisioning request: the guest command and the bytes to write to
     * its stdin.
     *
     * [command] is safe to log and never contains the password (finding 031).
     * [stdin] is the secret itself; callers hand it to
     * [com.fem.linexa.service.runtime.features.GuestFeatureHost.execCommandWithInput]
     * and must not log it.
     */
    data class ProvisionRequest(val command: String, val stdin: String)

    /**
     * The one-shot guest request that materialises the passwd file (in the
     * workspace's resolved home, see [passwdPathFor]) from [password], or a
     * command whose non-zero exit signals "no vncpasswd in this guest".
     *
     * Shape: `umask 077` (the file is 0600 from creation) → `mkdir -p`
     * (TigerVNC does not create the directory itself) → `command -v vncpasswd`
     * guard → `set -C; vncpasswd -f > "$tmp"` (reads the password from the
     * process's stdin, writes the DES-encrypted keyfile format) → `chmod 600`
     * → refuse a directory destination → `mv -f` (atomic rename, does not
     * follow a destination symlink) into place.
     *
     * Finding 031: the password is in [ProvisionRequest.stdin], never in this
     * string. The previous template embedded it as a `printf … | vncpasswd`
     * argv, readable from `/proc/<pid>/cmdline` by same-UID processes for the
     * duration of the exec.
     *
     * The noclobber temp file is what makes the destination write safe against
     * a symlink the guest user planted at the predictable temp path: `O_EXCL`
     * makes the redirection fail instead of following it, and the rename
     * replaces any symlink at the final path rather than writing through it.
     * A stale temp from an earlier failed run is removed first, deliberately
     * before `set -C` (removing a symlink does not follow it).
     */
    fun provisionRequest(password: String, guestHome: String? = null): ProvisionRequest {
        val passwdPath = passwdPathFor(guestHome)
        val quotedPasswd = shellSingleQuoted(passwdPath)
        val quotedDir = shellSingleQuoted(passwdPath.substringBeforeLast('/'))
        val quotedTmp = shellSingleQuoted("$passwdPath.tmp")
        val command = "umask 077 && mkdir -p $quotedDir && " +
            "command -v vncpasswd >/dev/null 2>&1 && " +
            "rm -f $quotedTmp && " +
            "( set -C; vncpasswd -f > $quotedTmp ) && " +
            "chmod 600 $quotedTmp && " +
            "[ ! -d $quotedPasswd ] && " +
            "mv -f $quotedTmp $quotedPasswd"
        return ProvisionRequest(command = command, stdin = password + "\n")
    }

    /**
     * The `-SecurityTypes …` arguments Xvnc must be started with. When
     * [hasPassword] the server offers **only** VNC-Auth (an offered `None`
     * alongside it would let any local app bypass the secret), and points at
     * the keyfile in [guestHome]. Without a password the server runs with
     * `None` **only when the caller has already checked the user's explicit
     * consent** ([unauthenticatedStartBlocked]); this function describes the
     * command, not the permission to run it.
     */
    fun securityArgs(hasPassword: Boolean, guestHome: String? = null): String {
        val passwdPath = passwdPathFor(guestHome)
        return if (hasPassword) "-SecurityTypes VncAuth -rfbauth $passwdPath"
        else SECURITY_ARGS_NONE
    }

    /**
     * Phase 07 (OPT-SEC-004, Step B2) + finding 007: whether a start with
     * `-SecurityTypes None` must be REFUSED outright.
     *
     * Android has no per-app loopback isolation: `-localhost yes` does not
     * authenticate anything, because every app on the device shares the host
     * network namespace and can connect to `127.0.0.1`. A no-password desktop
     * is therefore reachable by any local app regardless of the loopback bind,
     * so the user must give **explicit consent**
     * ([com.fem.linexa.service.runtime.features.FeatureSettings.desktopVncAllowUnauthenticated])
     * before such a start is allowed. Without a password and without consent
     * the start fails closed, before any process exists.
     *
     * The loopback capability is deliberately NOT part of this decision: it
     * only selects which user-facing reason the ViewModel shows. It is not an
     * authentication boundary and must never be treated as one.
     *
     * Pure and JVM-testable.
     */
    fun unauthenticatedStartBlocked(
        securityArgs: String,
        allowUnauthenticated: Boolean
    ): Boolean = securityArgs == SECURITY_ARGS_NONE && !allowUnauthenticated

    /**
     * Finding 007: the password a desktop **enable** should generate, or null
     * when none is needed.
     *
     * The secure default is "a password exists before the first start", so
     * enabling the desktop with no stored password (and no explicit consent to
     * run without one) generates one and persists it. It does not overwrite a
     * password the user chose, does not replace an unreadable secret (that
     * must fail closed until the user acts), and does not fight an explicit
     * no-authentication choice. Pure, so the rule is JVM-testable without the
     * ViewModel.
     */
    fun passwordToGenerateOnEnable(
        password: String,
        unreadable: Boolean,
        allowUnauthenticated: Boolean
    ): String? = if (needsGeneratedPassword(password, unreadable, allowUnauthenticated)) {
        generatePassword()
    } else {
        null
    }

    /**
     * The predicate behind [passwordToGenerateOnEnable], without the side
     * effect of drawing a random secret. The direct-launch path asks it first:
     * a workspace whose desktop was auto-detected at import never went through
     * the enable toggle, so it has no password, and the fail-closed start
     * policy would otherwise refuse it ("does not support restricting the
     * server to loopback") although nothing is wrong with the guest's Xvnc.
     */
    fun needsGeneratedPassword(
        password: String,
        unreadable: Boolean,
        allowUnauthenticated: Boolean
    ): Boolean = password.isEmpty() && !unreadable && !allowUnauthenticated

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
     * Finding 016: the three states a listener listing can prove.
     *
     * The old API returned a nullable violation, which conflated
     * "loopback-only" with "the probe told us nothing" — both became `null`.
     * The caller then treated "cannot verify" as a pass. This type keeps the
     * distinction explicit so a missing tool (or a listing that does not
     * mention the port at all) can require a password or consent instead of
     * silently standing in for evidence.
     */
    sealed class LoopbackProof {
        /** A listener on the port was seen, and every such listener is loopback. */
        object Verified : LoopbackProof()

        /** No usable evidence: blank output, or no listener for the port in it. */
        object Unverified : LoopbackProof()

        /** At least one listener on the port answers on a routable address. */
        data class Violation(val offenders: String) : LoopbackProof()
    }

    /**
     * Phase 07 (OPT-SEC-004, Step B3) + finding 016: classify a listener
     * listing ([BIND_PROBE_COMMAND]'s output) as [LoopbackProof.Verified],
     * [LoopbackProof.Violation] or [LoopbackProof.Unverified].
     *
     * A null/blank listing is **Unverified**, never "verified". So is a
     * non-blank listing that contains no listener for [port] at all (a probe
     * race, a namespace mismatch, or a tool that printed only a header): the
     * absence of a matching line is not evidence of a loopback bind. Only a
     * listing that actually shows at least one socket on [port] and no
     * routable one is `Verified`.
     *
     * Parsing notes: `ss -ltn` / `netstat -ltn` listeners carry the port only
     * in the LOCAL address column (the peer is `*:*`), and the trailing
     * `(?!\d)` keeps e.g. `:59011` from matching a `5901` probe. IPv6 shapes
     * covered: `::1:5901`, `[::1]:5901` (loopback) vs `[::]:5901`, `:::5901`,
     * `*:5901`, `0.0.0.0:5901` (violations).
     */
    fun loopbackProof(listenerOutput: String?, port: Int): LoopbackProof {
        if (listenerOutput.isNullOrBlank()) return LoopbackProof.Unverified
        val addresses = Regex("""(\S+):$port(?!\d)""")
            .findAll(listenerOutput)
            .map { it.groupValues[1] }
            .toList()
        // No listener for the port in the listing: the probe did not prove
        // anything about THIS server, so it cannot be called verified.
        if (addresses.isEmpty()) return LoopbackProof.Unverified
        val offenders = addresses.filterNot { isLoopbackListener(it) }.distinct()
        return if (offenders.isEmpty()) {
            LoopbackProof.Verified
        } else {
            LoopbackProof.Violation(offenders.joinToString(", "))
        }
    }

    /**
     * The offending `address:port` list for a [LoopbackProof.Violation], or
     * null for every other state.
     *
     * Kept as the narrow projection existing callers/tests used before the
     * tri-state existed; new code should branch on [loopbackProof] so
     * "Unverified" can never be mistaken for a pass.
     */
    fun loopbackBindViolation(listenerOutput: String?, port: Int): String? =
        (loopbackProof(listenerOutput, port) as? LoopbackProof.Violation)?.offenders

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
     * `'\''` way — the only construct a single-quoted string needs.
     *
     * Used for the guest paths [provisionRequest] interpolates. The password
     * itself no longer reaches a shell at all (finding 031): it travels on
     * stdin, so there is no quoting for it to escape.
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
