package com.fem.linexa.domain.filemanager

import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.CommandExecutor
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guest-side file operations, executed as one-shot commands inside the
 * workspace's PRoot rootfs.
 *
 * §1.115 changed two things about every method here:
 *
 * 1. **Failures are returned, not disguised.** Each operation used to map a
 *    non-zero exit to a plausible success value (`emptyList()`, `""`,
 *    `false`), so a broken runtime looked like an empty directory and a
 *    blank file. Now every method returns [FileOpResult] carrying the guest's
 *    own error text.
 * 2. **Listing is parsed by [LsLongFormatParser]**, a pure function, instead
 *    of by fixed field index inline (which read the filename from the *time*
 *    column — see that class's KDoc).
 *
 * Every path is still single-quoted for the guest shell ([sh]), and every
 * command that takes a path now also passes `--` so a name beginning with `-`
 * cannot be read as an option (`rm -rf '-rf'` is a quoted operand, but
 * `rm -rf -- '-rf'` is unambiguous, and busybox and coreutils both honour it).
 */
@Singleton
class WorkspaceFileManagerImpl @Inject constructor(
    private val rootfsLocator: WorkspaceRootfsLocator
) : FileManager {

    /**
     * F-003: the host-side fast path, or null when the workspace has no live
     * rootfs (or the caller is a JVM test). Every read-only operation tries
     * it first and falls back to the guest command on null.
     */
    private fun hostAccess(workspaceId: String): HostFileAccess? =
        rootfsLocator.viewFor(workspaceId)
            ?.let(::HostFileAccess)
            ?.takeIf { it.available }

    /**
     * Single-quote a path for safe interpolation into a shell command.
     * Fixes a shell-injection gap found in the Phase 9 security audit: paths
     * coming from file-manager navigation were previously interpolated
     * unescaped into `ls`/`cat`/`rm`/`mv`/`cp` commands.
     */
    private fun sh(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    override suspend fun listFiles(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<List<FileInfo>> {
        // F-003: an app-private rootfs needs no PRoot process to be listed.
        hostAccess(workspaceId)?.list(path)?.let { return FileOpResult.Ok(it) }
        // -A: include dotfiles but not `.`/`..`. The parser drops those two
        // anyway; asking `ls` not to emit them keeps the output honest.
        // LC_ALL=C: pins the date columns the parser matches to the POSIX
        // month names regardless of the guest's locale.
        val result = runtimeBinder.execCommand(workspaceId, "LC_ALL=C ls -lA -- ${sh(path)}")
        if (result.exitCode != 0) {
            return failure(result.exitCode, result, "Cannot open $path")
        }
        return FileOpResult.Ok(LsLongFormatParser.parse(path, result.output))
    }

    /**
     * Read [path]'s bytes exactly.
     *
     * The exec bridge captures one-shot output as a TRIMMED string
     * (`PRootContainerRuntime.runProcess` calls `readText().trim()`), so a
     * plain `cat` cannot round-trip the file's own trailing whitespace: a
     * saved file ending in one or more blank lines came back without them,
     * the editor rendered a file that differed from what was just written,
     * and the next save persisted that loss to disk.
     *
     * base64 has no significant trailing whitespace, so the host-side trim
     * cannot cost payload: the body travels base64-encoded and is decoded on
     * the host for a byte-exact string. Decoding (line wraps included) is
     * whitespace-insensitive; an empty file reads as "" both ways. If the
     * captured output is not valid base64 (stderr merged into stdout is the
     * only realistic route here), the read FAILS loudly instead of returning
     * corrupted content — a corrupt cell must never render as a blank file.
     */
    override suspend fun readFile(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<String> {
        // F-003: byte-exact host read; no base64 round-trip needed.
        hostAccess(workspaceId)?.read(path)?.let { return FileOpResult.Ok(it) }
        val result = runtimeBinder.execCommand(workspaceId, "base64 -- ${sh(path)}")
        if (result.exitCode != 0) {
            return failure(result.exitCode, result, "Cannot read $path")
        }
        val compact = result.output.filterNot { it == '\n' || it == '\r' }.trim()
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            return FileOpResult.Failure(-2, "Cannot read $path: unreadable command output")
        }
        return FileOpResult.Ok(bytes.decodeToString())
    }

    /**
     * Write [content] to [path] via a quoted heredoc.
     *
     * `printf`/`echo` were both wrong here: `echo` in dash interprets
     * backslash escapes, so importing a file containing `\n` or `\t` silently
     * corrupted it, and both would need the content escaped into an argument.
     * A **quoted** heredoc delimiter (`<<'EOF'`) makes the shell pass the body
     * through verbatim with no expansion of `$`, backticks or backslashes.
     *
     * The delimiter is chosen so it cannot appear at the start of a line in
     * the content; if content somehow contains every candidate delimiter the
     * write is refused rather than truncated.
     *
     * **Empty content writes an empty file.** The body used to be
     * unconditionally newline-terminated, which turned an empty buffer into a
     * one-`\n` file on disk: "the file is empty" in the editor and "the file
     * has one blank line" in the filesystem. Trailing-line fidelity matters
     * in the other direction too: content already ending in `\n` is passed
     * through verbatim (never doubled), and content ending mid-line gets
     * exactly one added — the heredoc requires the delimiter on its own line,
     * so a final newline is the format's only legitimate addition.
     */
    override suspend fun writeFile(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String,
        content: String
    ): FileOpResult<Unit> {
        val delimiter = pickHeredocDelimiter(content)
            ?: return FileOpResult.Failure(-2, "Cannot write $path: unsupported file content")
        val body = when {
            content.isEmpty() -> ""
            content.endsWith("\n") -> content
            else -> content + "\n"
        }
        val command = "cat > ${sh(path)} <<'$delimiter'\n$body$delimiter\n"
        val result = runtimeBinder.execCommand(workspaceId, command)
        return unit(result, "Cannot write $path")
    }

    override suspend fun createDirectory(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<Unit> {
        // No `-p`: silently succeeding on an existing directory would report
        // "Created folder" for a folder the user did not create.
        val result = runtimeBinder.execCommand(workspaceId, "mkdir -- ${sh(path)}")
        return unit(result, "Cannot create $path")
    }

    override suspend fun createFile(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<Unit> {
        // Existence is asserted first so "Created file" means a NEW file —
        // a plain `touch` would exit 0 on an existing path and silently
        // rewrite only its mtime while the UI claimed success.
        if (exists(runtimeBinder, workspaceId, path)) {
            return FileOpResult.Failure(-5, "Already exists: $path")
        }
        val result = runtimeBinder.execCommand(workspaceId, "touch -- ${sh(path)}")
        return unit(result, "Cannot create $path")
    }

    override suspend fun statFile(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<FileInfo> {
        // F-003: `ls -ld` without a guest process.
        hostAccess(workspaceId)?.stat(path)?.let { return FileOpResult.Ok(it) }
        // Same listing machinery as [listFiles] with `-d` (the entry itself,
        // not its children) — one parser, one truth about the columns.
        val result = runtimeBinder.execCommand(workspaceId, "LC_ALL=C ls -ld -- ${sh(path)}")
        if (result.exitCode != 0) {
            return failure(result.exitCode, result, "Cannot inspect $path")
        }
        val parsed = LsLongFormatParser.parse(parentDirOf(path), result.output)
        val entry = parsed.firstOrNull()
            ?: return FileOpResult.Failure(result.exitCode, "Cannot inspect $path")
        return FileOpResult.Ok(entry)
    }

    /** The displayed parent directory for a stat path (only used for joinPath). */
    private fun parentDirOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return when {
            idx <= 0 -> "/"
            else -> trimmed.substring(0, idx)
        }
    }

    override suspend fun delete(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<Unit> {
        // `rm -rf` exits 0 on a path that does not exist, so a wrong path
        // reported a successful delete (the §1.115 listing bug made every path
        // wrong). Existence is asserted first so "Deleted" means it was there.
        if (!exists(runtimeBinder, workspaceId, path)) {
            return FileOpResult.Failure(-3, "No longer exists: $path")
        }
        val result = runtimeBinder.execCommand(workspaceId, "rm -rf -- ${sh(path)}")
        return unit(result, "Cannot delete $path")
    }

    override suspend fun rename(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        oldPath: String,
        newPath: String
    ): FileOpResult<Unit> {
        // -n: never overwrite an existing destination. A rename that silently
        // clobbers another file is data loss the user did not ask for.
        val result = runtimeBinder.execCommand(workspaceId, "mv -n -- ${sh(oldPath)} ${sh(newPath)}")
        if (result.exitCode != 0) {
            return failure(result.exitCode, result, "Cannot rename to $newPath")
        }
        // busybox `mv -n` exits 0 when it refuses, so confirm the move happened.
        if (exists(runtimeBinder, workspaceId, oldPath)) {
            return FileOpResult.Failure(-4, "Already exists: $newPath")
        }
        return FileOpResult.Ok(Unit)
    }

    override suspend fun copy(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        source: String,
        dest: String
    ): FileOpResult<Unit> {
        val result = runtimeBinder.execCommand(workspaceId, "cp -r -- ${sh(source)} ${sh(dest)}")
        return unit(result, "Cannot copy to $dest")
    }

    override suspend fun exists(runtimeBinder: CommandExecutor, workspaceId: String, path: String): Boolean {
        // F-003: host existence is authoritative for non-bind-mounted paths.
        hostAccess(workspaceId)?.exists(path)?.let { return it }
        // -e follows symlinks, so a dangling link would read as absent; -L
        // catches the link itself, which for delete/rename purposes exists.
        val result = runtimeBinder.execCommand(workspaceId, "test -e ${sh(path)} -o -L ${sh(path)}")
        return result.exitCode == 0
    }

    override suspend fun getSize(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        path: String
    ): FileOpResult<Long> {
        // F-003: regular files have an exact byte size on the host. Directories
        // keep `du -sk` (guest block accounting) via the fallback below.
        hostAccess(workspaceId)?.size(path)?.let { return FileOpResult.Ok(it) }
        // `du -sb` is GNU-only; busybox du has no -b. `du -sk` is POSIX and
        // present in both, and KiB * 1024 is an apparent-size approximation
        // that is at least correct about magnitude on every rootfs.
        val result = runtimeBinder.execCommand(workspaceId, "du -sk -- ${sh(path)}")
        if (result.exitCode != 0) {
            return failure(result.exitCode, result, "Cannot measure $path")
        }
        val kib = result.output.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            ?: return FileOpResult.Failure(result.exitCode, "Cannot measure $path")
        return FileOpResult.Ok(kib * 1024)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun unit(
        result: CommandResult,
        fallback: String
    ): FileOpResult<Unit> =
        if (result.exitCode == 0) FileOpResult.Ok(Unit) else failure(result.exitCode, result, fallback)

    /**
     * Build a user-facing failure. The guest's own message is far more useful
     * than a generic string ("Permission denied", "No such file or
     * directory"), so it is preferred when present, bounded, and stripped of
     * the `ls: ` / `rm: ` tool prefix that duplicates context the UI has.
     */
    private fun failure(
        exitCode: Int,
        result: CommandResult,
        fallback: String
    ): FileOpResult.Failure {
        if (exitCode == TIMEOUT_EXIT_CODE) {
            return FileOpResult.Failure(exitCode, "$fallback: timed out")
        }
        val guestMessage = (result.error.ifBlank { result.output })
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_ERROR_CHARS)
        return FileOpResult.Failure(
            exitCode,
            if (guestMessage.isNullOrEmpty()) fallback else "$fallback: $guestMessage"
        )
    }

    private fun pickHeredocDelimiter(content: String): String? =
        HEREDOC_DELIMITERS.firstOrNull { delimiter ->
            content.lineSequence().none { it.trimEnd() == delimiter }
        }

    private companion object {
        /** `PRootContainerRuntime` reports a timed-out command as -1. */
        const val TIMEOUT_EXIT_CODE = -1
        const val MAX_ERROR_CHARS = 200
        val HEREDOC_DELIMITERS = listOf(
            "LINEXA_EOF",
            "LINEXA_EOF_2",
            "LINEXA_EOF_3_9f2c1b",
        )
    }
}
