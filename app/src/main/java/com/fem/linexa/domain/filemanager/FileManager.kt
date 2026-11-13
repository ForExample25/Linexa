package com.fem.linexa.domain.filemanager

import com.fem.linexa.domain.runtime.CommandExecutor

/**
 * One entry of a guest directory listing.
 *
 * §1.115 added [isSymlink]/[linkTarget]/[hasByteSize]: `ls -la` reports all
 * three and the old model discarded them, so a symlink was rendered as a
 * regular file whose name included `-> /target`, and a device node was
 * rendered as a 0-byte file.
 */
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val lastModified: String,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null,
    /**
     * Whether [size] is a real byte count. False for device nodes, whose size
     * column is a `major, minor` pair — the UI must not print "0 B" for those
     * as though the file were empty.
     */
    val hasByteSize: Boolean = true
)

/**
 * §1.115 — outcome of a guest file operation.
 *
 * Before this, every operation collapsed failure into a plausible-looking
 * success value: `listFiles` returned `emptyList()` (indistinguishable from an
 * empty directory), `readFile` returned `""` (a blank file viewer), and the
 * boolean-returning mutations reported only "it didn't work" with no reason.
 * The guest's own stderr is the most useful thing available here, so it is
 * carried through to the UI.
 */
sealed interface FileOpResult<out T> {
    data class Ok<T>(val value: T) : FileOpResult<T>

    /**
     * @param exitCode the guest command's exit status; `-1` marks a timeout
     *   (see `PRootContainerRuntime`), which is reported differently.
     * @param message a short, already-trimmed reason suitable for a snackbar.
     */
    data class Failure(val exitCode: Int, val message: String) : FileOpResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
}

interface FileManager {
    suspend fun listFiles(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<List<FileInfo>>
    suspend fun readFile(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<String>
    suspend fun writeFile(runtimeBinder: CommandExecutor, workspaceId: String, path: String, content: String): FileOpResult<Unit>
    suspend fun createDirectory(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<Unit>

    /**
     * Issue #9 (2026-09-03): creates a new EMPTY file at [path]. Refuses to
     * overwrite (existence is checked first, like [delete]) — "create new
     * file" means a new file, never "truncate an existing one". The UI
     * layers template content on top via [writeFile] when the user picks a
     * preset.
     */
    suspend fun createFile(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<Unit>

    /**
     * Issue #9: one entry's own metadata (size, permissions, mtime, symlink
     * target) via `ls -ld` — the file-inspection surface of the
     * QuickEdit/ZArchiver-style manager. Parsed by the same
     * [LsLongFormatParser] rules as [listFiles], so the two never disagree.
     */
    suspend fun statFile(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<FileInfo>

    suspend fun delete(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<Unit>
    suspend fun rename(runtimeBinder: CommandExecutor, workspaceId: String, oldPath: String, newPath: String): FileOpResult<Unit>
    suspend fun copy(runtimeBinder: CommandExecutor, workspaceId: String, source: String, dest: String): FileOpResult<Unit>
    suspend fun exists(runtimeBinder: CommandExecutor, workspaceId: String, path: String): Boolean
    suspend fun getSize(runtimeBinder: CommandExecutor, workspaceId: String, path: String): FileOpResult<Long>
}
