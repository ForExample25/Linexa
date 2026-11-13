package com.fem.linexa.service.exporter

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.service.rootfs.CompressedArchiveStream
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TAR.GZ implementation of [RootFsExporter] — the app's first archive
 * *writer*, built as the exact mirror of the install pipeline's reader:
 *
 *  - the compressed container is gzip, one of the three formats
 *    [CompressedArchiveStream.open] sniffs by magic bytes, so a re-import
 *    detects it exactly the way it detects any downloaded image;
 *  - every entry name and symlink target is run through
 *    [ArchiveValidator.checkEntry] — the same per-entry security validation
 *    the installer's scan pass runs (§1.14) — so an entry that would be
 *    rejected on re-import is refused here, before anything is called a
 *    backup;
 *  - entries carry the guest's real permission bits (`--preserve-permissions`
 *    restores them on extraction), tar directory entries carry the trailing
 *    `/`, symlinks are packed as symlinks with their stored targets (never
 *    followed — usr-merge links and the `.l2s.*` hardlink artifacts of PRoot
 *    extraction survive byte-for-byte), and modification times are preserved.
 *    uid/gid are written as 0/root:root: Android app-private storage has a
 *    single uid, so extraction never performs a chown and no other value
 *    would survive the round-trip anyway.
 *
 * Streaming and memory: the tree is walked without ever following symlinks
 * (cycle-safe by construction — the same discipline
 * `WorkspaceFileManager.calculateDirSize` is documented around), file contents
 * flow through a fixed 64 KiB copy buffer, and the only in-memory
 * accumulations are the SHA-256 digest state and the skipped-entries list. A
 * multi-GB rootfs exports in constant memory.
 *
 * Verification: when [ExportRequest.verifyInput] is supplied, the finished
 * archive is re-opened through [CompressedArchiveStream.open] — the same
 * magic-sniffing entry point a re-import will use — parsed entry by entry to
 * EOF, and its compressed bytes are SHA-256-compared with what was written.
 * A truncated or silently-corrupted provider write therefore fails here and
 * the partial file is removed by the coordinator, never surfaced as success.
 *
 * Failure honesty, per file type:
 *  - a file that cannot be *opened* (guest chmod 000) is skipped and recorded
 *    in [ExportResult.Success.skippedEntries] — the tar stream stays clean
 *    because the skip happens before its entry is started;
 *  - a file that fails *mid-copy* (readable at open, then I/O error) aborts
 *    the export: its tar entry is already half-written and cannot be
 *    un-written, so continuing would produce a structurally torn archive;
 *  - a destination write failure always aborts.
 *
 * Cancellation: `coroutineContext.ensureActive()` runs between entries and on
 * every buffer refill and progress emission. A [CancellationException]
 * propagates (the stream is closed, the coordinator cleans up) and is never
 * converted to a [ExportResult.Failure] — the same discipline the installer's
 * scan pass documents.
 */
@Singleton
class TarRootFsExporter @Inject constructor(
    private val archiveValidator: ArchiveValidator
) : RootFsExporter {

    // ── the walk ────────────────────────────────────────────────────────────

    override suspend fun export(
        request: ExportRequest,
        onProgress: suspend (ExportProgress) -> Unit
    ): ExportResult {
        val rootfs = request.rootfs
        if (!rootfs.isDirectory || !rootfs.canRead()) {
            return ExportResult.Failure(
                "The workspace rootfs directory cannot be read: ${rootfs.absolutePath}"
            )
        }

        Logger.i(
            TAG,
            "export(): packing ${rootfs.absolutePath} " +
                "(expected ${request.expectedBytes ?: "unknown"} bytes, " +
                "embedded config ${request.embeddedConfig?.size ?: "none"})"
        )

        val walk = WalkState().also { it.totalBytes = request.expectedBytes?.takeIf { b -> b > 0 } }
        val packed: PackOutcome = try {
            pack(rootfs, request, walk, onProgress)
        } catch (e: CancellationException) {
            Logger.i(TAG, "export(): cancelled after ${walk.sourceBytes} source bytes")
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "export(): I/O failure after ${walk.sourceBytes} source bytes: ${e.message}")
            return ExportResult.Failure(ioReason("writing the archive", e))
        } catch (e: Exception) {
            Logger.w(TAG, "export(): unexpected failure: ${e.message}")
            return ExportResult.Failure("Export failed unexpectedly: ${e.message ?: e.javaClass.simpleName}")
        }

        if (packed is PackOutcome.Aborted) {
            return ExportResult.Failure(packed.reason)
        }
        val done = packed as PackOutcome.Packed

        if (request.verifyInput == null) {
            // Deliberate, and only for in-process callers that already hold the
            // bytes (tests); the coordinator always supplies a verify opener.
            Logger.w(TAG, "export(): no verify input supplied — archive accepted without re-read")
            return ExportResult.Success(done.sha256, done.outputBytes, done.entryCount, walk.skipped.toList())
        }

        onProgress(ExportProgress(0L, done.outputBytes.takeIf { it > 0 }, null, ExportPhase.VERIFYING))
        return when (val verification = verify(request, done, onProgress)) {
            is VerifyOutcome.Ok -> {
                Logger.i(
                    TAG,
                    "export(): verified ${done.entryCount} entries / ${done.outputBytes} bytes, " +
                        "sha256=${done.sha256.take(12)}…"
                )
                ExportResult.Success(done.sha256, done.outputBytes, done.entryCount, walk.skipped.toList())
            }
            is VerifyOutcome.Mismatch -> {
                Logger.w(TAG, "export(): verification failed: ${verification.reason}")
                ExportResult.Failure(verification.reason)
            }
        }
    }

    /** Accumulated walk state — one pass over the tree, no second lookups. */
    private class WalkState {
        var sourceBytes = 0L
        var lastProgressEmitAt = 0L
        var entryCount = 0L
        /** The caller's size estimate — the packing fraction's denominator. */
        var totalBytes: Long? = null
        val skipped = mutableListOf<String>()
    }

    private sealed interface PackOutcome {
        data class Packed(
            val sha256: String,
            val outputBytes: Long,
            val entryCount: Long,
        ) : PackOutcome

        data class Aborted(val reason: String) : PackOutcome
    }

    // ── packing ─────────────────────────────────────────────────────────────

    /**
     * Streams the tree into the tar/gzip pipeline. Validation refusals return
     * [PackOutcome.Aborted] with a *reason*; only stream I/O failures throw
     * (mapped by the caller). Resources close innermost-out so a failure at
     * any level still finishes (or tears down) the layers below it exactly
     * once.
     */
    private suspend fun pack(
        rootfs: File,
        request: ExportRequest,
        walk: WalkState,
        onProgress: suspend (ExportProgress) -> Unit
    ): PackOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = ByteCountingOutputStream(request.output)
        val digesting = DigestOutputStream(counting, digest)
        val buffered = BufferedOutputStream(digesting, COPY_BUFFER_BYTES)

        request.output.use {
            buffered.use {
                GzipCompressorOutputStream(buffered).use { gzip ->
                    TarArchiveOutputStream(gzip).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                        onProgress(
                            ExportProgress(0L, request.expectedBytes?.takeIf { it > 0 }, null, ExportPhase.PREPARING)
                        )

                        // The embedded declaration goes first, before any tree
                        // entry, so the re-import's scan finds it regardless of
                        // walk order — mirroring how the installer collects
                        // root-level metadata candidates position-independently.
                        // When the tree still carries its own declaration (the
                        // normal case — the install extracted it there), packing
                        // a second copy would be duplicate bytes.
                        if (request.embeddedConfig != null && rootLevelMetadataFile(rootfs) == null) {
                            writeEmbeddedConfig(tar, request.embeddedConfig)
                            walk.sourceBytes += request.embeddedConfig.size
                            walk.entryCount++
                        }

                        val abortReason = walkTree(rootfs, tar, walk, onProgress)
                        if (abortReason != null) return PackOutcome.Aborted(abortReason)

                        // finish() writes tar's two 512-byte EOF blocks; the
                        // closes then write the gzip trailer and flush every
                        // buffer out to the destination. All of it must succeed
                        // for the archive to exist at all.
                        onProgress(
                            ExportProgress(
                                walk.sourceBytes,
                                request.expectedBytes?.takeIf { it > 0 },
                                null,
                                ExportPhase.FINALIZING
                            )
                        )
                        tar.finish()
                    }
                }
            }
        }

        return PackOutcome.Packed(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            outputBytes = counting.bytesWritten,
            entryCount = walk.entryCount,
        )
    }

    /**
     * The tree walk — a hand-rolled breadth-first traversal rather than
     * [Files.walkFileTree], for one reason: every step must be a suspension
     * point (cancellation checks, progress emission), and a Java visitor's
     * callbacks can never suspend. The trade is deliberate: the walk keeps
     * only one directory's children in memory at a time, never follows
     * symlinks ([LinkOption.NOFOLLOW_LINKS] on every attribute read, so it is
     * cycle-safe by construction), and orders deterministically (directories
     * before files, names ascending) so an unchanged tree packs to
     * byte-identical archives.
     *
     * Returns a failure reason (aborts the archive — nothing further is
     * written), or null on success.
     */
    private suspend fun walkTree(
        rootfs: File,
        tar: TarArchiveOutputStream,
        walk: WalkState,
        onProgress: suspend (ExportProgress) -> Unit
    ): String? {
        val pending = ArrayDeque<DirTask>()
        pending.addLast(DirTask(rootfs, ""))

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val task = pending.removeFirst()

            val children = try {
                task.dir.listFiles()
            } catch (e: SecurityException) {
                null
            }
            if (children == null) {
                // listFiles returning null is the unreadable-directory case.
                if (task.relativePath.isNotEmpty()) {
                    recordSkipped(walk, task.relativePath, "directory unreadable")
                }
                continue
            }

            // Deterministic pre-order: a directory's own files are written
            // before its subdirectories are descended, and each listing is
            // sorted — the packing order is a property of the tree, not of
            // the filesystem's read order.
            for (child in children.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name }
            )) {
                coroutineContext.ensureActive()
                val name = if (task.relativePath.isEmpty()) {
                    child.name
                } else {
                    task.relativePath + "/" + child.name
                }

                val attrs = try {
                    Files.readAttributes(
                        child.toPath(),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS
                    )
                } catch (e: IOException) {
                    // Deleted or unstattable between listFiles and now —
                    // record and move on (same policy as the size walk).
                    recordSkipped(walk, name, e.message ?: "unreadable")
                    continue
                }

                var packedName: String? = null
                when {
                    attrs.isDirectory -> {
                        checkSafety(name, isSymlink = false, linkTarget = null)?.let { return it }
                        writeDirectoryEntry(tar, child, name, attrs)
                        walk.entryCount++
                        packedName = name
                        pending.addLast(DirTask(child, name))
                    }

                    attrs.isSymbolicLink -> {
                        val target = try {
                            Files.readSymbolicLink(child.toPath()).toString()
                        } catch (e: IOException) {
                            recordSkipped(walk, name, "symlink target unreadable")
                            continue
                        }
                        checkSafety(name, isSymlink = true, linkTarget = target)?.let { return it }
                        writeSymlinkEntry(tar, name, target, attrs)
                        walk.entryCount++
                        packedName = name
                    }

                    attrs.isRegularFile -> {
                        checkSafety(name, isSymlink = false, linkTarget = null)?.let { return it }
                        // Only a Written outcome may touch entryCount / packedName:
                        // the verification pass re-reads the archive and compares
                        // entry counts, so a skipped file (no tar entry written)
                        // must not be counted as packed — otherwise every export
                        // containing one unreadable file would fail its own
                        // verification and delete a perfectly good archive.
                        when (val outcome = writeFileEntry(tar, child.toPath(), name, attrs, walk, onProgress)) {
                            is FileEntryOutcome.Aborted -> return outcome.reason
                            is FileEntryOutcome.Written -> {
                                walk.entryCount++
                                packedName = name
                            }
                            is FileEntryOutcome.Skipped -> Unit // recorded, nothing written
                        }
                    }

                    else -> {
                        // Fifos, sockets, device nodes: a PRoot rootfs carries
                        // none of these (the guest's /dev is bound at runtime
                        // and mknod is not available to the app uid), but a
                        // guest process can create a fifo. They cannot be
                        // represented faithfully by a non-root packer, so they
                        // are excluded *and reported* — never silently dropped.
                        recordSkipped(walk, name, "special file (not a regular file, directory, or symlink)")
                    }
                }

                // Per-entry emission (the installer's scan emits on a byte
                // cadence; the export dialog's live "Now processing:" block
                // wants the entry granularity too). Large files additionally
                // emit mid-copy on the byte cadence, inside writeFileEntry.
                if (packedName != null) emitProgress(walk, packedName, onProgress)
            }
        }
        return null
    }

    /** One queued directory: the file plus its archive-relative path. */
    private class DirTask(val dir: File, val relativePath: String)

    // ── entry construction ──────────────────────────────────────────────────

    private fun writeDirectoryEntry(
        tar: TarArchiveOutputStream,
        dir: File,
        name: String,
        attrs: BasicFileAttributes
    ) {
        val entry = TarArchiveEntry("$name/", TarConstants.LF_DIR)
        entry.setModTime(attrs.lastModifiedTime().toMillis())
        entry.mode = posixModeOrFallback(dir.toPath(), DEFAULT_DIR_MODE)
        setOwner(entry)
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    private fun writeSymlinkEntry(
        tar: TarArchiveOutputStream,
        name: String,
        target: String,
        attrs: BasicFileAttributes
    ) {
        val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
        entry.linkName = target
        entry.setModTime(attrs.lastModifiedTime().toMillis())
        setOwner(entry)
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
    }

    /** The three ways packing one regular file can end — kept distinct so the
     *  walk's counters only ever describe entries that physically exist in
     *  the archive (the verification pass depends on that exactness). */
    private sealed interface FileEntryOutcome {
        /** The tar entry was written and closed — counted as packed. */
        data object Written : FileEntryOutcome

        /** Nothing was written (unreadable at open) — recorded as skipped.
         *  Deliberately NOT an abort and NOT a counted entry: the archive
         *  stays structurally clean and the verification entry count stays
         *  equal to the number of entries actually packed. */
        data object Skipped : FileEntryOutcome

        /** The entry started but could not finish — aborts the export. */
        data class Aborted(val reason: String) : FileEntryOutcome
    }

    /**
     * Packs one regular file, distinguishing [FileEntryOutcome.Written] (the
     * entry physically exists in the stream), [FileEntryOutcome.Skipped]
     * (nothing was written; the skip is recorded) and
     * [FileEntryOutcome.Aborted] (a reason that tears down the export).
     *
     * The input stream is opened *before* `putArchiveEntry`: an unreadable
     * file (guest chmod) then costs nothing but a skip-and-record, because
     * no tar entry has started. A failure *after* the entry began (mid-copy
     * read or destination write) tears the entry — there is no way to
     * un-write a half entry — so any such failure aborts the whole export
     * with a reason that names the file.
     */
    private suspend fun writeFileEntry(
        tar: TarArchiveOutputStream,
        file: Path,
        name: String,
        attrs: BasicFileAttributes,
        walk: WalkState,
        onProgress: suspend (ExportProgress) -> Unit
    ): FileEntryOutcome {
        val input: InputStream = try {
            Files.newInputStream(file)
        } catch (e: IOException) {
            recordSkipped(walk, name, e.message ?: "unreadable")
            return FileEntryOutcome.Skipped
        }

        val entry = TarArchiveEntry(name, TarConstants.LF_NORMAL)
        entry.size = attrs.size()
        entry.setModTime(attrs.lastModifiedTime().toMillis())
        entry.mode = posixModeOrFallback(file, DEFAULT_FILE_MODE)
        setOwner(entry)

        try {
            tar.putArchiveEntry(entry)
            input.use { stream ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    tar.write(buffer, 0, read)
                    walk.sourceBytes += read
                    maybeEmitProgress(walk, name, onProgress)
                }
            }
            tar.closeArchiveEntry()
            return FileEntryOutcome.Written
        } catch (e: IOException) {
            return FileEntryOutcome.Aborted("Could not pack '$name': ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun writeEmbeddedConfig(tar: TarArchiveOutputStream, configBytes: ByteArray) {
        val entry = TarArchiveEntry(RootFsMetadataFile.NATIVE_DOT.fileName, TarConstants.LF_NORMAL)
        entry.size = configBytes.size.toLong()
        entry.setModTime(System.currentTimeMillis())
        entry.mode = DEFAULT_FILE_MODE
        setOwner(entry)
        tar.putArchiveEntry(entry)
        tar.write(configBytes)
        tar.closeArchiveEntry()
    }

    // ── verification ────────────────────────────────────────────────────────

    private sealed interface VerifyOutcome {
        data object Ok : VerifyOutcome
        data class Mismatch(val reason: String) : VerifyOutcome
    }

    /**
     * Re-reads the written archive through the *import* pipeline's own entry
     * point ([CompressedArchiveStream.open]), consumes every entry to EOF,
     * and compares the compressed-byte digest with what was written. This is
     * what makes "Success" mean *a complete, valid, re-importable archive*,
     * not merely "no exception was thrown".
     */
    private suspend fun verify(
        request: ExportRequest,
        done: PackOutcome.Packed,
        onProgress: suspend (ExportProgress) -> Unit,
    ): VerifyOutcome {
        val stream: InputStream? = try {
            request.verifyInput?.invoke()
        } catch (e: Exception) {
            Logger.w(TAG, "export(): verify re-open threw: ${e.message}")
            null
        }
        if (stream == null) {
            return VerifyOutcome.Mismatch(
                "The exported archive could not be re-opened for verification — " +
                    "the storage provider refused a read after the write."
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var verifiedEntries = 0L
        var verifiedBytes = 0L
        var lastEmitAt = 0L

        fun verifyProgress(): ExportProgress = ExportProgress(
            bytesRead = verifiedBytes,
            totalBytes = done.outputBytes.takeIf { it > 0 },
            currentEntryName = null,
            phase = ExportPhase.VERIFYING,
            fraction = done.outputBytes.takeIf { it > 0 }
                ?.let { (verifiedBytes.toFloat() / it).coerceIn(0f, 1f) },
        )

        try {
            stream.use { raw ->
                val digesting = DigestInputStream(raw, digest)
                val counting = object : InputStream() {
                    override fun read(): Int {
                        val r = digesting.read()
                        if (r >= 0) verifiedBytes++
                        return r
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = digesting.read(b, off, len)
                        if (r > 0) verifiedBytes += r
                        return r
                    }

                    override fun close() = digesting.close()
                }
                val opened = CompressedArchiveStream.open(counting)
                opened.stream.use { decompressed ->
                    val tar = TarArchiveInputStream(decompressed)
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        coroutineContext.ensureActive()
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                        }
                        verifiedEntries++
                        if (verifiedBytes - lastEmitAt >= PROGRESS_EMIT_INTERVAL_BYTES) {
                            lastEmitAt = verifiedBytes
                            onProgress(verifyProgress())
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { stream.close() }
            throw e
        } catch (e: Exception) {
            return VerifyOutcome.Mismatch(
                "The exported archive could not be re-read: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val verifiedSha = digest.digest().joinToString("") { "%02x".format(it) }
        return when {
            verifiedSha != done.sha256 ->
                VerifyOutcome.Mismatch(
                    "Verification failed: the stored archive does not match what was written " +
                        "(checksum mismatch — the storage provider may have altered or truncated it)."
                )

            verifiedEntries != done.entryCount ->
                VerifyOutcome.Mismatch(
                    "Verification failed: the stored archive holds $verifiedEntries entries " +
                        "instead of the ${done.entryCount} that were packed."
                )

            else -> VerifyOutcome.Ok
        }
    }

    // ── small helpers ───────────────────────────────────────────────────────

    /**
     * Runs the installer's per-entry security validation. Returns the
     * user-presentable failure reason when the entry must not be packed.
     */
    private fun checkSafety(name: String, isSymlink: Boolean, linkTarget: String?): String? =
        when (val result = archiveValidator.checkEntry(name, isSymlink, linkTarget)) {
            is ArchiveValidator.ValidationResult.Invalid ->
                "Refusing to pack unsafe entry '$name': ${result.reason}"
            else -> null
        }

    private fun recordSkipped(walk: WalkState, name: String, cause: String) {
        Logger.w(TAG, "export(): skipping '$name' ($cause)")
        walk.skipped.add("$name ($cause)")
    }

    /**
     * One PACKING event: bytes packed so far, the entry just written (or the
     * one being written for the mid-file cadence), and the real fraction when
     * the caller supplied a denominator — the same "real stage progress"
     * convention the installer's extraction phase uses. The coordinator bands
     * the fraction into its UI rail; it never invents one.
     */
    private suspend fun emitProgress(
        walk: WalkState,
        currentEntry: String?,
        onProgress: suspend (ExportProgress) -> Unit
    ) {
        onProgress(
            ExportProgress(
                bytesRead = walk.sourceBytes,
                totalBytes = walk.totalBytes,
                currentEntryName = currentEntry,
                phase = ExportPhase.PACKING,
                fraction = walk.totalBytes?.let {
                    (walk.sourceBytes.toFloat() / it).coerceIn(0f, 1f)
                },
            )
        )
    }

    /** Mid-file cadence for large copies — bounded to the 512 KiB interval. */
    private suspend fun maybeEmitProgress(
        walk: WalkState,
        currentEntry: String?,
        onProgress: suspend (ExportProgress) -> Unit
    ) {
        if (walk.sourceBytes - walk.lastProgressEmitAt < PROGRESS_EMIT_INTERVAL_BYTES) return
        walk.lastProgressEmitAt = walk.sourceBytes
        emitProgress(walk, currentEntry, onProgress)
    }

    /** Root-level metadata file already present in the tree, if any. */
    private fun rootLevelMetadataFile(rootfs: File): RootFsMetadataFile? =
        RootFsMetadataFile.entries.firstOrNull { File(rootfs, it.fileName).isFile }

    private fun posixModeOrFallback(path: Path, fallback: Int): Int {
        return try {
            var mode = 0
            for (permission in Files.readAttributes(path, PosixFileAttributes::class.java).permissions()) {
                mode = mode or (PERM_BITS[permission] ?: 0)
            }
            mode
        } catch (e: Exception) {
            // Non-POSIX volumes keep a usable default rather than failing the
            // export over a mode bit.
            fallback
        }
    }

    private fun setOwner(entry: TarArchiveEntry) {
        entry.userId = 0
        entry.groupId = 0
        entry.userName = "root"
        entry.groupName = "root"
    }

    private fun ioReason(stage: String, e: IOException): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "An I/O error occurred while $stage: $detail"
    }

    /** Counts bytes that actually reached the destination stream. */
    private class ByteCountingOutputStream(
        private val wrapped: java.io.OutputStream
    ) : java.io.OutputStream() {

        var bytesWritten = 0L
            private set

        override fun write(b: Int) {
            wrapped.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            wrapped.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = wrapped.flush()
        override fun close() = wrapped.close()
    }

    companion object {
        private const val TAG = "TarRootFsExporter"

        /** Same cadence as the installer's scan pass. */
        private const val PROGRESS_EMIT_INTERVAL_BYTES = 512L * 1024L

        private const val COPY_BUFFER_BYTES = 64 * 1024

        private const val DEFAULT_FILE_MODE = 0b110_100_100 // octal 644
        private const val DEFAULT_DIR_MODE = 0b111_101_101 // octal 755

        private val PERM_BITS = mapOf(
            PosixFilePermission.OWNER_READ to 0b100_000_000,
            PosixFilePermission.OWNER_WRITE to 0b010_000_000,
            PosixFilePermission.OWNER_EXECUTE to 0b001_000_000,
            PosixFilePermission.GROUP_READ to 0b000_100_000,
            PosixFilePermission.GROUP_WRITE to 0b000_010_000,
            PosixFilePermission.GROUP_EXECUTE to 0b000_001_000,
            PosixFilePermission.OTHERS_READ to 0b000_000_100,
            PosixFilePermission.OTHERS_WRITE to 0b000_000_010,
            PosixFilePermission.OTHERS_EXECUTE to 0b000_000_001,
        )
    }
}
