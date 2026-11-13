package com.fem.linexa.service.runtime.features

/**
 * §1.112 — Pure (JVM-testable) logic for the Workspace **Clipboard** feature:
 * the guest-side consumer of the `clipboard` feature toggle
 * ([com.fem.linexa.data.WorkspaceContent] id `"clipboard"`).
 *
 * Same convention as [SshdProvisioner] and [NotifySendProvisioner]: everything
 * here is deterministic string/byte work (no Android, no coroutines), so the
 * whole shim → spool → sanitize chain is asserted in plain JUnit tests. The
 * suspend orchestration lives in [WorkspaceClipboardSupervisor]; the Android
 * `ClipboardManager` access lives behind [HostClipboard].
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.112):
 *
 * - **Two directions, two mechanisms, because they are not symmetric.**
 *   Guest→host (a guest "copy") is a *stream of events*: each copy is a distinct
 *   act, so it uses the §1.111 spool pattern — one record per copy, write-then-
 *   rename, host drains and deletes. Host→guest (a guest "paste") is *current
 *   state*: a paste must read whatever is on the host clipboard **now**, and a
 *   guest cannot block waiting for the host, so the host keeps one mirror file
 *   ([IN_GUEST_FILE]) that the paste shim simply `cat`s. A spool would be wrong
 *   for paste (which record is "current"?) and a mirror wrong for copy (two
 *   copies in one poll interval would collapse into one).
 *
 * - **The guest talks to files, never to Android.** Both directions live inside
 *   the rootfs under [BRIDGE_GUEST_DIR], so — exactly as in §1.111 — there is no
 *   socket, no port, no bind mount, and no way for a guest process to reach
 *   `ClipboardManager`, address another workspace, or hold anything open.
 *
 * - **The shims do not shadow a real `xclip`/`xsel`/`wl-copy`.** They install
 *   into `/usr/local/bin` and each alias is installed **only if that command
 *   does not already resolve** ([aliasInstallCommand]). A workspace running a
 *   real X server (§1.83's Xvnc) has a working `xclip` talking to a real X
 *   selection; replacing it with a file-based shim would *break* clipboard
 *   inside that desktop rather than bridging it. The canonical
 *   [COPY_SHIM_GUEST_PATH]/[PASTE_SHIM_GUEST_PATH] are always installed, so the
 *   bridge is reachable regardless.
 *
 * - **`xclip`/`xsel` are one binary with two directions, so the alias dispatches
 *   on argv.** `xclip -o` and `xsel -o` read; `xclip`/`xsel -i` write. Getting
 *   this wrong silently swaps copy and paste, which is why [aliasScript]'s
 *   dispatch is asserted per alias in the tests.
 *
 * - **Clipboard text is bidirectionally untrusted, in different ways.** Guest→
 *   host content is arbitrary output from code running in the workspace, so
 *   [sanitizeForHost] bounds it and strips control characters before it can
 *   reach the host clipboard. Host→guest content is the *user's own* data — and
 *   that is precisely why it is sensitive: see §1.112's "exposure" note, because
 *   any process in the workspace can read the mirror while the feature is on.
 *   Unlike [NotifySendProvisioner.sanitizeText], newlines and tabs are
 *   **preserved** in both directions: a clipboard that mangles a copied
 *   multi-line snippet is useless, and clipboard content is never rendered as
 *   notification-like structure.
 *
 * - **Every shim exits 0 on a bridge failure, but a paste with no clipboard
 *   prints nothing.** The shims stand in for commands inside someone else's
 *   script (fail-open, as §1.27/§1.111), yet a paste must not invent content:
 *   an absent mirror produces empty output, not an error and not stale text.
 */
object ClipboardBridgeProvisioner {

    /** The workspace feature id this provisioner consumes (`WorkspaceContent`). */
    const val FEATURE_CLIPBOARD = "clipboard"

    /** Root of the guest-visible bridge, inside the rootfs. */
    const val BRIDGE_GUEST_DIR = "/var/spool/linexa-clip"

    /** Guest→host: one record per guest copy (drained and deleted by the host). */
    const val OUT_GUEST_DIR = "$BRIDGE_GUEST_DIR/out"

    /** Host→guest: the single mirror file a paste reads. */
    const val IN_GUEST_DIR = "$BRIDGE_GUEST_DIR/in"
    const val IN_GUEST_FILE = "$IN_GUEST_DIR/clipboard"

    /**
     * The same three paths as the *host* resolves them, relative to the rootfs
     * root. Kept adjacent to the guest paths so the two views can never drift
     * (pinned equal by a test).
     */
    const val OUT_RELATIVE_PATH = "var/spool/linexa-clip/out"
    const val IN_RELATIVE_PATH = "var/spool/linexa-clip/in"
    const val IN_FILE_RELATIVE_PATH = "var/spool/linexa-clip/in/clipboard"

    /** Extension of a complete, readable copy record (see [copyShimScript]). */
    const val RECORD_EXTENSION = ".clip"

    /** Extension of a record still being written. Never read by the host. */
    const val RECORD_TEMP_EXTENSION = ".tmp"

    /** The canonical shims, always installed. */
    const val COPY_SHIM_GUEST_PATH = "/usr/local/bin/linexa-clip-copy"
    const val PASTE_SHIM_GUEST_PATH = "/usr/local/bin/linexa-clip-paste"

    /**
     * Hard cap on one clipboard transfer, both directions. A clipboard is for
     * text a human copied; anything larger is either a mistake (`cat`ing a file
     * into `xclip`) or an attempt to make the other side allocate. 64 KiB is
     * roughly 1000 lines of code — generous for the real use case.
     */
    const val MAX_CLIP_BYTES = 64 * 1024

    /**
     * Character bound applied after decoding. Separate from [MAX_CLIP_BYTES]
     * because the byte cap protects the *reader* (reject before allocating)
     * while this bounds what reaches the system clipboard.
     */
    const val MAX_CLIP_CHARS = 32_000

    /**
     * The aliases the bridge can provide, mapped to whether the alias is a
     * *reader* (paste), a *writer* (copy), or dispatches on argv.
     *
     * `xclip`/`xsel` are one binary with both directions; the Wayland tools are
     * split. Only these five are provided: they cover the tools that actually
     * appear in scripts, and each has an unambiguous mapping onto the bridge.
     */
    val ALIASES: List<ClipboardAlias> = listOf(
        ClipboardAlias("xclip", ClipboardAliasKind.DISPATCH_XCLIP),
        ClipboardAlias("xsel", ClipboardAliasKind.DISPATCH_XSEL),
        ClipboardAlias("wl-copy", ClipboardAliasKind.COPY),
        ClipboardAlias("wl-paste", ClipboardAliasKind.PASTE),
        ClipboardAlias("pbcopy", ClipboardAliasKind.COPY)
    )

    /**
     * The guest→host copy shim: read stdin, drop one record in [OUT_GUEST_DIR].
     *
     * POSIX `sh` only (a rootfs may ship nothing but busybox ash). Identical
     * commit discipline to §1.111: **write then rename**, so the host — which
     * only reads `*[RECORD_EXTENSION]` — can never see a partial clipboard.
     * A record is the raw bytes of the clipboard text, with no framing: unlike a
     * `notify-send` invocation there is no argv to preserve, and framing would
     * only add a way for the two sides to disagree.
     */
    fun copyShimScript(
        outDir: String = OUT_GUEST_DIR,
        recordExtension: String = RECORD_EXTENSION,
        tempExtension: String = RECORD_TEMP_EXTENSION
    ): String = """
        #!/bin/sh
        # Linexa clipboard copy shim (§1.112) — sends stdin to the Android
        # clipboard by dropping one record into the bridge's out directory.
        # Never fails the calling script.
        dir='$outDir'
        mkdir -p "${'$'}dir" 2>/dev/null || exit 0
        n=0
        while [ -e "${'$'}dir/${'$'}${'$'}-${'$'}n$recordExtension" ] || [ -e "${'$'}dir/${'$'}${'$'}-${'$'}n$tempExtension" ]; do
          n=${'$'}((n+1))
          [ "${'$'}n" -gt 4096 ] && exit 0
        done
        tmp="${'$'}dir/${'$'}${'$'}-${'$'}n$tempExtension"
        cat > "${'$'}tmp" 2>/dev/null || { rm -f "${'$'}tmp" 2>/dev/null; exit 0; }
        mv "${'$'}tmp" "${'$'}dir/${'$'}${'$'}-${'$'}n$recordExtension" 2>/dev/null || rm -f "${'$'}tmp" 2>/dev/null
        exit 0
    """.trimIndent() + "\n"

    /**
     * The host→guest paste shim: print the mirror file, or nothing.
     *
     * Deliberately **not** fail-open in the sense of inventing output — a paste
     * with an empty host clipboard must produce empty output, never stale text
     * and never an error that breaks a pipeline. `cat` of a missing file would
     * write to stderr, so the existence check is explicit.
     */
    fun pasteShimScript(mirrorFile: String = IN_GUEST_FILE): String = """
        #!/bin/sh
        # Linexa clipboard paste shim (§1.112) — prints the host clipboard as the
        # Android side last mirrored it. No clipboard means no output, not an error.
        f='$mirrorFile'
        [ -r "${'$'}f" ] || exit 0
        cat "${'$'}f" 2>/dev/null
        exit 0
    """.trimIndent() + "\n"

    /**
     * The script installed for [alias].
     *
     * The two dispatching kinds encode each tool's real convention:
     * - `xclip`: `-o`/`-out`/`--output` reads, anything else writes.
     * - `xsel`: `-i`/`--input` writes, anything else (including a bare `xsel`
     *   and `xsel -b`) reads — the opposite default to `xclip`, which is exactly
     *   the sort of detail a hand-written shim gets wrong silently.
     *
     * Both delegate to the canonical shims rather than reimplementing the
     * record/mirror handling, so there is one copy path and one paste path no
     * matter how many aliases exist.
     */
    fun aliasScript(
        alias: ClipboardAlias,
        copyShim: String = COPY_SHIM_GUEST_PATH,
        pasteShim: String = PASTE_SHIM_GUEST_PATH
    ): String = when (alias.kind) {
        ClipboardAliasKind.COPY -> """
            #!/bin/sh
            # Linexa clipboard bridge (§1.112): ${alias.name} → host clipboard.
            exec $copyShim
        """.trimIndent() + "\n"

        ClipboardAliasKind.PASTE -> """
            #!/bin/sh
            # Linexa clipboard bridge (§1.112): ${alias.name} ← host clipboard.
            exec $pasteShim
        """.trimIndent() + "\n"

        ClipboardAliasKind.DISPATCH_XCLIP -> """
            #!/bin/sh
            # Linexa clipboard bridge (§1.112): xclip. Reads with -o/-out/--output,
            # writes otherwise (xclip's own default).
            for a in "${'$'}@"; do
              case "${'$'}a" in
                -o|-out|--output) exec $pasteShim ;;
              esac
            done
            exec $copyShim
        """.trimIndent() + "\n"

        ClipboardAliasKind.DISPATCH_XSEL -> """
            #!/bin/sh
            # Linexa clipboard bridge (§1.112): xsel. Writes only with -i/--input;
            # a bare xsel READS, which is the opposite of xclip's default.
            for a in "${'$'}@"; do
              case "${'$'}a" in
                -i|--input) exec $copyShim ;;
              esac
            done
            exec $pasteShim
        """.trimIndent() + "\n"
    }

    /**
     * The one-shot guest command that installs the bridge directories and the
     * two canonical shims. Idempotent: re-running overwrites the shims with the
     * current version (so an app update refreshes an old one) and leaves any
     * pending records and the mirror alone.
     *
     * Quoted heredocs (`<<'LINEXA_EOF'`) so the guest shell expands nothing —
     * the `$@`/`$$` in the scripts must reach disk verbatim. Built only from
     * values this object owns; no user-supplied string is interpolated (§1.14).
     */
    fun installCommand(): String = buildString {
        append("mkdir -p ")
        append(OUT_GUEST_DIR)
        append(' ')
        append(IN_GUEST_DIR)
        append(" /usr/local/bin && chmod 0777 ")
        append(OUT_GUEST_DIR)
        append(" && ")
        appendScriptWrite(COPY_SHIM_GUEST_PATH, copyShimScript())
        append(" && ")
        appendScriptWrite(PASTE_SHIM_GUEST_PATH, pasteShimScript())
    }

    /**
     * The one-shot guest command that installs [alias] **only if the command
     * does not already resolve** to something else.
     *
     * Separate from [installCommand] (rather than folded into it) for two
     * reasons: the conditional makes the alias set assertable per alias, and a
     * failure to provide one alias must not prevent the canonical shims — or the
     * other aliases — from being installed.
     */
    fun aliasInstallCommand(alias: ClipboardAlias): String {
        val path = "/usr/local/bin/${alias.name}"
        return buildString {
            // Not `[ -e path ]`: the point is whether the NAME resolves for the
            // guest, which is what a script invoking it will get.
            append("if command -v ")
            append(alias.name)
            append(" >/dev/null 2>&1; then exit 0; fi; ")
            appendScriptWrite(path, aliasScript(alias))
        }
    }

    /**
     * The one-shot guest command that removes the bridge when the toggle is
     * turned off: both canonical shims, every alias **that is ours**, the
     * pending records and the mirror.
     *
     * Alias removal is guarded by a content check (`grep -q` for the §1.112
     * marker) so turning the feature off can never delete a real `xclip` the
     * user installed into `/usr/local/bin` themselves. Idempotent; a guest that
     * never had the bridge still exits 0.
     */
    fun uninstallCommand(): String = buildString {
        append("rm -f ")
        append(COPY_SHIM_GUEST_PATH)
        append(' ')
        append(PASTE_SHIM_GUEST_PATH)
        append("; ")
        ALIASES.forEach { alias ->
            val path = "/usr/local/bin/${alias.name}"
            append("if [ -f ")
            append(path)
            append(" ] && grep -q '")
            append(SHIM_MARKER)
            append("' ")
            append(path)
            append(" 2>/dev/null; then rm -f ")
            append(path)
            append("; fi; ")
        }
        append("rm -f ")
        append(OUT_GUEST_DIR)
        append("/*")
        append(RECORD_EXTENSION)
        append(' ')
        append(OUT_GUEST_DIR)
        append("/*")
        append(RECORD_TEMP_EXTENSION)
        append(' ')
        append(IN_GUEST_FILE)
        append(" 2>/dev/null; exit 0")
    }

    /**
     * Decodes one guest copy record, or null when it is unusable (empty, or
     * larger than [MAX_CLIP_BYTES] — rejected *without* being decoded).
     *
     * The record is the clipboard bytes verbatim, so this is a decode plus
     * [sanitizeForHost]; there is no framing to parse.
     */
    fun decodeRecord(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_CLIP_BYTES) return null
        return sanitizeForHost(bytes.toString(Charsets.UTF_8))
    }

    /**
     * Bounds and de-fangs guest-supplied text on its way to the host clipboard.
     *
     * Control characters are stripped **except** tab, newline and carriage
     * return: a clipboard exists to carry text a human will paste somewhere, and
     * a copy that silently loses the line breaks of a snippet is worse than no
     * bridge. Everything else in the C0/C1 range (and DEL, and a BOM) goes —
     * those cannot appear in text a user meant to copy, but *can* be used to
     * smuggle terminal escape sequences into whatever the user pastes into next.
     *
     * Trailing content beyond [MAX_CLIP_CHARS] is dropped rather than the whole
     * transfer being rejected: a truncated paste is recoverable, a silently
     * empty clipboard looks like a broken feature.
     */
    fun sanitizeForHost(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val cleaned = buildString(raw.length) {
            raw.forEach { c ->
                when {
                    c == '\t' || c == '\n' || c == '\r' -> append(c)
                    c == '\uFEFF' -> Unit
                    c.isISOControl() -> Unit
                    else -> append(c)
                }
            }
        }
        if (cleaned.isBlank()) return null
        return if (cleaned.length <= MAX_CLIP_CHARS) cleaned else cleaned.take(MAX_CLIP_CHARS)
    }

    /**
     * Bounds host clipboard text on its way *into* the guest mirror.
     *
     * The user's own text, so nothing is stripped — a host clipboard containing
     * an escape sequence is the user's business, and mangling it would make
     * paste lossy. Only the size bound applies, and only so a huge host
     * clipboard cannot be written into every running workspace's rootfs.
     */
    fun boundForGuest(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return if (raw.length <= MAX_CLIP_CHARS) raw else raw.take(MAX_CLIP_CHARS)
    }

    /**
     * The marker every shim carries, used by [uninstallCommand] to recognise its
     * own files. `§1.112` would be the obvious choice but a non-ASCII byte in a
     * `grep` pattern inside a generated shell command is a portability risk, so
     * the marker is plain ASCII.
     */
    const val SHIM_MARKER = "Linexa clipboard"

    /** Appends `cat > path <<'LINEXA_EOF' … && chmod 0755 path`. */
    private fun StringBuilder.appendScriptWrite(path: String, script: String) {
        append("cat > ")
        append(path)
        append(" <<'LINEXA_EOF'\n")
        append(script)
        append("LINEXA_EOF\n")
        append("chmod 0755 ")
        append(path)
    }
}

/** §1.112 — one clipboard command the bridge can provide inside a guest. */
data class ClipboardAlias(
    val name: String,
    val kind: ClipboardAliasKind
)

/** §1.112 — which direction(s) a [ClipboardAlias] maps onto. */
enum class ClipboardAliasKind {
    /** Always writes the host clipboard (stdin → clipboard). */
    COPY,

    /** Always reads the host clipboard (clipboard → stdout). */
    PASTE,

    /** `xclip`: reads with `-o`, writes otherwise. */
    DISPATCH_XCLIP,

    /** `xsel`: writes with `-i`, reads otherwise. */
    DISPATCH_XSEL
}
