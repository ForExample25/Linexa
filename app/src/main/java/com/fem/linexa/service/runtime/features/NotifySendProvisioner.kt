package com.fem.linexa.service.runtime.features

/**
 * §1.111 — Pure (JVM-testable) logic for the Workspace **Notifications**
 * feature: the guest-side consumer of the `notifications` feature toggle
 * ([com.fem.linexa.data.WorkspaceContent] id `"notifications"`).
 *
 * Same convention as [SshdProvisioner] / `XvncProvisioner` / `TmuxPersist`:
 * everything here is deterministic string/byte work (no Android, no
 * coroutines), so the whole shim → spool → parse → sanitize chain is asserted
 * in plain JUnit tests. The suspend orchestration lives in
 * [WorkspaceNotificationSupervisor]; the Android posting lives in
 * [AndroidGuestNotificationBridge].
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.111):
 *
 * - **A `notify-send` shim + a spool directory, not a daemon.** §4's plan for
 *   this feature is "a small `notify-send` → Android `Notification` shim". The
 *   guest half is therefore a *script*, not a long-lived process: there is no
 *   daemon to supervise, nothing to leak if the app dies, and a guest that
 *   never calls `notify-send` costs exactly nothing. The `ssh` feature needs
 *   [com.fem.linexa.service.runtime.container.ContainerRuntime.startDaemon]
 *   because sshd must listen; a notification producer is inherently one-shot.
 *
 * - **The guest never talks to Android — it writes a file.** The shim drops one
 *   record into [SPOOL_GUEST_DIR] and exits. That directory lives *inside the
 *   rootfs*, so the host can read it directly from
 *   `{workspace}/rootfs/[SPOOL_RELATIVE_PATH]` with no extra bind mount, no
 *   socket, and no port. A guest process cannot post an Android notification,
 *   cannot address another workspace, and cannot keep a channel open — the only
 *   thing it can do is leave bytes in one directory the host polls.
 *
 * - **The shim dumps argv verbatim (NUL-separated); all parsing is Kotlin.**
 *   Parsing `notify-send`'s option grammar in POSIX `sh` would be both fragile
 *   and untestable. `printf '%s\0' "$@"` is lossless (an argv element cannot
 *   contain NUL), so [decodeRecord] recovers the exact argv the guest passed
 *   and [parseNotifySend] applies the grammar in a pure function.
 *
 * - **Guest content is untrusted input** (§1.14). Summary/body come from
 *   arbitrary code the user ran inside their workspace, so [sanitizeText]
 *   strips control characters and bounds every field before it can reach a
 *   notification; [decodeRecord] rejects an over-sized record outright. A
 *   workspace must not be able to spam megabytes into the system UI or smuggle
 *   control frames through it.
 *
 * - **The shim always exits 0.** A guest script that calls `notify-send` must
 *   not fail because Linexa's spool is missing or unwritable — the whole
 *   feature is an add-on to someone else's script, so every failure path is
 *   swallowed (fail-open, matching §1.27's config-application policy).
 */
object NotifySendProvisioner {

    /** The workspace feature id this provisioner consumes (`WorkspaceContent`). */
    const val FEATURE_NOTIFICATIONS = "notifications"

    /** Absolute guest path of the installed shim. */
    const val SHIM_GUEST_PATH = "/usr/local/bin/notify-send"

    /** Absolute guest path of the spool directory the shim writes into. */
    const val SPOOL_GUEST_DIR = "/var/spool/linexa-notify"

    /**
     * The spool directory relative to the rootfs root — how the *host* resolves
     * it (`{workspace}/rootfs/var/spool/linexa-notify`). Kept next to
     * [SPOOL_GUEST_DIR] so the two can never drift apart.
     */
    const val SPOOL_RELATIVE_PATH = "var/spool/linexa-notify"

    /** Extension of a complete, readable record (see [installCommand]'s rename). */
    const val RECORD_EXTENSION = ".notify"

    /** Extension of a record still being written. Never read by the host. */
    const val RECORD_TEMP_EXTENSION = ".tmp"

    /**
     * Hard cap on a single record's size. A `notify-send` invocation is a
     * handful of short strings; anything larger is either a bug or an attempt
     * to make the host allocate. Enforced by [decodeRecord].
     */
    const val MAX_RECORD_BYTES = 16 * 1024

    /** Bound on the notification title (guest `summary`). */
    const val MAX_SUMMARY_CHARS = 200

    /** Bound on the notification body. */
    const val MAX_BODY_CHARS = 1000

    /** Bound on the guest-supplied app name / category. */
    const val MAX_LABEL_CHARS = 60

    /**
     * The `notify-send` replacement installed at [SHIM_GUEST_PATH].
     *
     * POSIX `sh` only (a rootfs may ship nothing but busybox ash — the §0
     * supported set includes Alpine). Notable properties, all deliberate:
     *
     * - **Writes then renames.** The host only reads `*[RECORD_EXTENSION]`, so
     *   it can never observe a half-written record — the rename is the commit.
     * - **`printf '%s\0' "$@"`** preserves argv exactly, including empty
     *   arguments, spaces and newlines inside a summary/body.
     * - **Unique name without `mktemp`/`$RANDOM`.** `$$` plus a probe loop is
     *   portable and needs no external binary beyond the shell itself; two
     *   concurrent callers have different pids, and one caller looping gets a
     *   fresh index.
     * - **Every failure exits 0.** See the class kdoc: this shim replaces a
     *   command in someone else's script.
     */
    fun shimScript(
        spoolDir: String = SPOOL_GUEST_DIR,
        recordExtension: String = RECORD_EXTENSION,
        tempExtension: String = RECORD_TEMP_EXTENSION
    ): String = """
        #!/bin/sh
        # Linexa notify-send shim (§1.111) — forwards a guest notification to
        # the Android host by dropping one NUL-separated argv record into the
        # spool directory. Never fails the calling script.
        dir='$spoolDir'
        mkdir -p "${'$'}dir" 2>/dev/null || exit 0
        n=0
        while [ -e "${'$'}dir/${'$'}${'$'}-${'$'}n$recordExtension" ] || [ -e "${'$'}dir/${'$'}${'$'}-${'$'}n$tempExtension" ]; do
          n=${'$'}((n+1))
          [ "${'$'}n" -gt 4096 ] && exit 0
        done
        tmp="${'$'}dir/${'$'}${'$'}-${'$'}n$tempExtension"
        printf '%s\0' "${'$'}@" > "${'$'}tmp" 2>/dev/null || { rm -f "${'$'}tmp" 2>/dev/null; exit 0; }
        mv "${'$'}tmp" "${'$'}dir/${'$'}${'$'}-${'$'}n$recordExtension" 2>/dev/null || rm -f "${'$'}tmp" 2>/dev/null
        exit 0
    """.trimIndent() + "\n"

    /**
     * The one-shot guest command that installs the shim and the spool
     * directory. Idempotent: re-running it overwrites the shim with the current
     * version (so an app update refreshes an old shim) and leaves any pending
     * records alone.
     *
     * The script is delivered through a quoted heredoc (`<<'LINEXA_EOF'`) so
     * the guest shell performs **no** expansion on its body — the `$@`/`$$` in
     * [shimScript] must reach the file verbatim. The command is built only from
     * values this object owns; no user-supplied string is interpolated (§1.14).
     */
    fun installCommand(): String = buildString {
        append("mkdir -p ")
        append(SPOOL_GUEST_DIR)
        append(" /usr/local/bin && chmod 0777 ")
        append(SPOOL_GUEST_DIR)
        append(" && cat > ")
        append(SHIM_GUEST_PATH)
        append(" <<'LINEXA_EOF'\n")
        append(shimScript())
        append("LINEXA_EOF\n")
        append("chmod 0755 ")
        append(SHIM_GUEST_PATH)
    }

    /**
     * The one-shot guest command that removes the shim and drains the spool,
     * used when the toggle is turned off. Idempotent; `-f`/`|| true` shapes mean
     * a guest that never had the shim still exits 0.
     *
     * The spool directory itself is left in place — it is empty, harmless, and
     * keeping it means re-enabling does not depend on `mkdir` succeeding a
     * second time.
     */
    fun uninstallCommand(): String =
        "rm -f $SHIM_GUEST_PATH; rm -f $SPOOL_GUEST_DIR/*$RECORD_EXTENSION " +
            "$SPOOL_GUEST_DIR/*$RECORD_TEMP_EXTENSION 2>/dev/null; exit 0"

    /**
     * Recovers the argv the guest passed to `notify-send` from one spool
     * record's bytes, or null when the record is unusable (empty, or larger
     * than [MAX_RECORD_BYTES]).
     *
     * `printf '%s\0'` writes a trailing NUL after every element, so the split
     * yields one empty trailing field that is dropped — an argv element that is
     * itself the empty string is preserved (it is a legal, if odd, summary).
     */
    fun decodeRecord(bytes: ByteArray): List<String>? {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) return null
        val text = bytes.toString(Charsets.UTF_8)
        val parts = text.split('\u0000')
        // Drop only the final field when it is the printf trailing separator.
        val argv = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        return argv.ifEmpty { null }
    }

    /**
     * Applies `notify-send`'s option grammar to [argv], or null when there is no
     * summary (a bare `notify-send` with no arguments prints usage and posts
     * nothing — so must post nothing here either).
     *
     * Supports the flags real `notify-send` scripts actually use, in both
     * `--flag=value` and `--flag value` forms plus the short aliases:
     * `-u/--urgency`, `-t/--expire-time`, `-i/--icon`, `-a/--app-name`,
     * `-c/--category`, `-r/--replace-id`, and the valueless
     * `-e/--transient`, `-p/--print-id`, `-w/--wait`. `-h/--hint` is accepted
     * and ignored (its `TYPE:NAME:VALUE` payload has no Android analog).
     *
     * Unknown `-`-prefixed tokens are ignored rather than treated as the
     * summary: a newer flag from a future `notify-send` must not silently become
     * the notification's title. Everything else is positional —
     * `summary`, then `body`; extra positionals are ignored, as upstream does.
     *
     * All text is [sanitizeText]-bounded here, so a caller cannot forget to.
     */
    fun parseNotifySend(argv: List<String>): GuestNotification? {
        var summary: String? = null
        var body: String? = null
        var urgency = NotificationUrgency.NORMAL
        var appName: String? = null
        var category: String? = null
        var replaceId: String? = null
        var expireMillis: Long? = null

        var i = 0
        while (i < argv.size) {
            val token = argv[i]
            when {
                token == "--" -> {
                    // Everything after `--` is positional.
                    i++
                    while (i < argv.size) {
                        if (summary == null) summary = argv[i] else if (body == null) body = argv[i]
                        i++
                    }
                }
                token.startsWith("--") && token.contains('=') -> {
                    val name = token.substringBefore('=')
                    val value = token.substringAfter('=')
                    when (name) {
                        "--urgency" -> urgency = NotificationUrgency.parse(value)
                        "--expire-time" -> expireMillis = value.toLongOrNull()?.takeIf { it > 0 }
                        "--app-name" -> appName = value
                        "--category" -> category = value
                        "--replace-id" -> replaceId = value
                        // --icon/--hint carry no Android analog; accepted, ignored.
                    }
                    i++
                }
                token in VALUE_FLAGS -> {
                    val value = argv.getOrNull(i + 1)
                    if (value != null) {
                        when (token) {
                            "-u", "--urgency" -> urgency = NotificationUrgency.parse(value)
                            "-t", "--expire-time" -> expireMillis = value.toLongOrNull()?.takeIf { it > 0 }
                            "-a", "--app-name" -> appName = value
                            "-c", "--category" -> category = value
                            "-r", "--replace-id" -> replaceId = value
                        }
                        i += 2
                    } else {
                        i++
                    }
                }
                token in IGNORED_VALUE_FLAGS -> i += 2
                token in VALUELESS_FLAGS -> i++
                token.startsWith("-") && token.length > 1 -> i++ // unknown flag: never a summary
                else -> {
                    if (summary == null) summary = token else if (body == null) body = token
                    i++
                }
            }
        }

        val cleanSummary = sanitizeText(summary, MAX_SUMMARY_CHARS) ?: return null
        return GuestNotification(
            summary = cleanSummary,
            body = sanitizeText(body, MAX_BODY_CHARS),
            urgency = urgency,
            appName = sanitizeText(appName, MAX_LABEL_CHARS),
            category = sanitizeText(category, MAX_LABEL_CHARS),
            replaceId = sanitizeText(replaceId, MAX_LABEL_CHARS),
            expireMillis = expireMillis
        )
    }

    /**
     * Bounds and de-fangs one guest-supplied string: control characters
     * (including the C1 range and DEL) become spaces, runs of whitespace
     * collapse, the result is trimmed and truncated to [maxChars], and a string
     * with nothing left is null rather than an empty notification line.
     *
     * Newlines are *not* preserved: a multi-line body would let a guest fake
     * additional notification structure in the system UI, and Android renders a
     * collapsed single line anyway.
     */
    fun sanitizeText(raw: String?, maxChars: Int): String? {
        if (raw.isNullOrEmpty()) return null
        val cleaned = raw
            .map { c -> if (c.isISOControl() || c == '\uFEFF') ' ' else c }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars).trimEnd() + "…"
    }

    /**
     * The stable notification tag for [notification] within one workspace.
     *
     * A guest-supplied `--replace-id` is honored (that is exactly its purpose:
     * `notify-send -r 42` twice must update one notification, not stack two),
     * namespaced per workspace so two workspaces cannot overwrite each other's.
     * Without one, the summary is used, matching the de-facto behavior of
     * desktop notification daemons that coalesce identical titles from the same
     * app.
     */
    fun notificationTag(workspaceId: String, notification: GuestNotification): String {
        val discriminator = notification.replaceId ?: notification.summary
        return "linexa-notify:$workspaceId:$discriminator"
    }

    /** Flags whose value is the following argv element and that we act on. */
    private val VALUE_FLAGS = setOf(
        "-u", "--urgency",
        "-t", "--expire-time",
        "-a", "--app-name",
        "-c", "--category",
        "-r", "--replace-id"
    )

    /** Flags whose value must be *skipped* so it can't be read as the summary. */
    private val IGNORED_VALUE_FLAGS = setOf("-i", "--icon", "-h", "--hint")

    /** Flags that take no value. */
    private val VALUELESS_FLAGS = setOf("-e", "--transient", "-p", "--print-id", "-w", "--wait")

    private val WHITESPACE_RUN = Regex("\\s{2,}")
}

/**
 * §1.111 — one notification a guest asked for, already parsed, bounded and
 * sanitized by [NotifySendProvisioner.parseNotifySend].
 *
 * Deliberately a host-side value type with no Android imports so the parse
 * chain stays JVM-testable; [AndroidGuestNotificationBridge] maps it onto a
 * `NotificationCompat`.
 */
data class GuestNotification(
    val summary: String,
    val body: String? = null,
    val urgency: NotificationUrgency = NotificationUrgency.NORMAL,
    val appName: String? = null,
    val category: String? = null,
    val replaceId: String? = null,
    val expireMillis: Long? = null
)

/**
 * §1.111 — freedesktop urgency levels, mapped to Android priority by the
 * bridge. Unknown/garbage values resolve to [NORMAL] rather than failing the
 * notification: urgency is a hint, not a gate.
 */
enum class NotificationUrgency {
    LOW,
    NORMAL,
    CRITICAL;

    companion object {
        fun parse(raw: String): NotificationUrgency = when (raw.trim().lowercase()) {
            "0", "low" -> LOW
            "2", "critical" -> CRITICAL
            else -> NORMAL
        }
    }
}
