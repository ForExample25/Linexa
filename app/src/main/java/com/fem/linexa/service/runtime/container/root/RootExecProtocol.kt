package com.fem.linexa.service.runtime.container.root

/**
 * The on-disk contract between the guest `sudo` helper
 * (`/usr/local/bin/linexa-root-exec`) and the app-side [RootExecBridge].
 *
 * ## Why a mailbox and not a setuid binary
 *
 * A PRoot guest cannot perform a kernel privilege transition: every process
 * runs under the Android app's real uid, and PRoot's fake-id layer answers a
 * `setuid(0)` from an emulated non-root identity with `EPERM`. The only
 * genuine elevation available to Linexa is a SEPARATE PRoot process launched
 * with `-0` — exactly what the Root Shell, package manager and every
 * privileged one-shot exec already use.
 *
 * This protocol lets a normal-user shell ask the app to run one non-interactive
 * command in that separate root-emulated context. It does not fake a uid
 * change inside the caller: the command really runs in a root-emulated process,
 * and its output is streamed back to the terminal that asked for it.
 *
 * ## Directory layout
 *
 * The bridge owns `<rootfs>/run/linexa-root/bridge` as a liveness marker.
 * Requests live under the guest's already-1777 `/tmp` (host path
 * `<rootfs>/tmp`) so no extra writable directory or permission relaxation is
 * needed:
 *
 * ```
 * /tmp/.linexa-root-req.<random>/
 *   cmd       guest → host: the exact command string to run (UTF-8)
 *   claimed   host → guest: created atomically before execution (prevents replay)
 *   out       host → guest: append-only streamed stdout+stderr
 *   status    host → guest: final exit code, written atomically (absence = still running)
 * ```
 *
 * The guest never has to be trusted with anything the app writes outside its
 * own rootfs: the bridge validates that a request directory is a real child of
 * that rootfs's `/tmp` before reading it.
 */
object RootExecProtocol {

    /** Guest-visible directory holding the bridge liveness marker. */
    const val CONTROL_DIR = "run/linexa-root"

    /** Liveness marker inside [CONTROL_DIR]. */
    const val MARKER_FILE = "bridge"

    /**
     * Heartbeat timestamp rewritten by the bridge while its poller is alive.
     * A claimed request whose heartbeat stops changing means the app process
     * died mid-command; the guest helper then fails with guidance instead of
     * waiting forever for a `status` file that will never appear.
     */
    const val HEARTBEAT_FILE = "heartbeat"

    /** How often the bridge refreshes [HEARTBEAT_FILE]. */
    const val HEARTBEAT_INTERVAL_MS = 2_000L

    /** Guest-side silence budget before it declares the bridge unresponsive. */
    const val HEARTBEAT_TIMEOUT_SECONDS = 30

    /** First line of the marker; lets a guest reject a protocol mismatch clearly. */
    const val MARKER_BANNER = "linexa-root-bridge v1"

    /** Prefix of a request directory under the guest's `/tmp`. */
    const val REQUEST_PREFIX = ".linexa-root-req."

    const val COMMAND_FILE = "cmd"
    const val CLAIMED_FILE = "claimed"
    const val OUTPUT_FILE = "out"
    const val STATUS_FILE = "status"

    /** Refuse a request whose command text is larger than this. */
    const val MAX_COMMAND_BYTES = 64 * 1024

    /** `sudo` convention for a command that could not be executed by the bridge. */
    const val EXIT_BRIDGE_ERROR = 125

    /** `timeout(1)` convention for a command killed by the operation timeout. */
    const val EXIT_TIMEOUT = 124

    /** The bridge marker's full text. The token is diagnostic only. */
    fun markerContents(workspaceId: String, token: String): String =
        buildString {
            appendLine(MARKER_BANNER)
            appendLine("workspace=$workspaceId")
            appendLine("token=$token")
        }

    /** True when [name] is a request directory name this protocol owns. */
    fun isRequestName(name: String): Boolean =
        name.startsWith(REQUEST_PREFIX) && name.length > REQUEST_PREFIX.length

    /**
     * Maps a guest process's exit code to a `sudo`-compatible status:
     * the timeout sentinel becomes [EXIT_TIMEOUT], any other out-of-range
     * value becomes [EXIT_BRIDGE_ERROR].
     */
    fun normalizeExitCode(exitCode: Int): Int = when {
        exitCode == -1 -> EXIT_TIMEOUT
        exitCode in 0..255 -> exitCode
        else -> EXIT_BRIDGE_ERROR
    }

    /** Parses the decimal exit code in a `status` file; null when malformed. */
    fun parseStatus(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        val code = trimmed.toIntOrNull() ?: return null
        return code.takeIf { it in 0..255 }
    }

    /**
     * True when [arg] is a conservative "safe shell word" that needs no
     * quoting: ASCII letters, digits and `_ . / : = + @ % , ^ -`. This set
     * deliberately excludes glob characters, whitespace, quotes, `$`, `~`,
     * `#`, `!`, `&`, `|`, `;`, parentheses and angle brackets.
     *
     * Keeping safe words bare is not cosmetic: the runtime classifies
     * package operations by whitespace tokens, so `sudo apt install -y htop`
     * must hand back `apt install -y htop`, not `'apt' 'install' …`.
     */
    fun isSafeShellWord(arg: String): Boolean =
        arg.isNotEmpty() && arg.all { c ->
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_./:=+@%,^-"
        }

    /**
     * Quotes one argv element for a POSIX shell. This is the Kotlin twin of
     * the guest helper's `quote_arg`; both must apply the same rule: a safe
     * word stays bare, anything else is wrapped in `'…'` with every embedded
     * `'` replaced by `'\''`. Tested against spaces, quotes, newlines and
     * empty arguments.
     */
    fun shellQuote(arg: String): String =
        if (isSafeShellWord(arg)) arg
        else "'" + arg.replace("'", "'\\''") + "'"

    /** Quotes one argv element unconditionally (diagnostics/tests). */
    fun shellQuoteAlways(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    /** Joins an argv list into one shell command string using [shellQuote]. */
    fun shellJoin(argv: List<String>): String =
        argv.joinToString(" ") { shellQuote(it) }
}
