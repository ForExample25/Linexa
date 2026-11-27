package com.fem.linexa.service.exporter

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Exports an installed Workspace's RootFS as a RootFS archive (§ export).
 *
 * The counterpart of
 * [com.fem.linexa.service.installer.RootFsInstaller]: install consumes a
 * TAR.GZ / TAR.XZ / TAR.ZST archive and produces a live rootfs tree; export
 * consumes a live rootfs tree and produces an archive the same install
 * pipeline can consume again (Add a Workspace → Import RootFS). The two
 * share the entry rules that make that round-trip work:
 *
 *  - the container is a tar stream compressed with one of the formats
 *    [com.fem.linexa.service.rootfs.CompressedArchiveStream] sniffs (this
 *    implementation emits TAR.GZ — gzip 0x1F 0x8B — the safest and most
 *    widely toolable of the three);
 *  - every entry name and symlink target passes
 *    [com.fem.linexa.core.security.ArchiveValidator.checkEntry], the exact
 *    validation the installer's scan pass runs — so an export that would
 *    fail re-import validation fails *here*, before it is ever called a
 *    backup;
 *  - permissions are carried on the entries (`--preserve-permissions` on
 *    the way back in), ownership is written as the archive's root/0:0
 *    (a non-root Android app cannot chown on extraction either way, so
 *    install never needed real uid/gid), and symlinks are packed as
 *    symlinks — never followed, so usr-merge links and the `.l2s.*`
 *    hardlink artifacts survive byte-for-byte.
 *
 * The writer is streaming end to end (directory walk → tar → gzip → the
 * caller's output stream) and never buffers a whole rootfs in memory; the
 * working set is one small copy buffer plus the current tar entry.
 *
 * Cancellation: cooperative, like the rest of the app's long operations —
 * the walk checks coroutine activity between entries and on every progress
 * emission, so cancelling the caller's coroutine (the coordinator's job)
 * stops the work at the next entry boundary. A [kotlinx.coroutines.CancellationException]
 * is never converted to a [ExportResult.Failure] — the same discipline the
 * installer documents for its scan pass.
 */
interface RootFsExporter {

    /**
     * Packs [request]'s rootfs tree into the archive stream.
     *
     * @param request the packed [ExportRequest].
     * @param onProgress periodic progress during packing and verification —
     *   [ExportProgress.phase] distinguishes the two. Emitted at most every
     *   few hundred KiB of source bytes, never per entry.
     */
    suspend fun export(
        request: ExportRequest,
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): ExportResult
}

/** Everything one export run needs — all plain JVM types, so it is unit-testable. */
data class ExportRequest(
    /** The live rootfs directory to pack (`{workspace}/rootfs`). Must exist. */
    val rootfs: File,
    /**
     * The destination stream. Written once, from the first tar block to the
     * last gzip trailer, then closed by [RootFsExporter.export] — the caller
     * must not close it first. On failure the stream is closed too; *removing*
     * the failed output (a SAF document, usually) is the coordinator's job,
     * because only it knows how to delete the thing behind the stream.
     */
    val output: OutputStream,
    /**
     * Re-opens the written output for the verification pass. SAF documents
     * cannot be re-read through the stream that was written, so the caller
     * supplies a fresh opener (e.g. `contentResolver::openInputStream`).
     * Null skips verification — discouraged; see [ExportPhase.VERIFYING].
     */
    val verifyInput: (() -> InputStream?)? = null,
    /**
     * The workspace's native `linexa:` metadata (`{workspace}/config.yaml`,
     * captured verbatim at install). When the rootfs tree carries no
     * root-level metadata file of its own — a guest may delete it — these
     * bytes are written back as the archive's root-level `.linexa.yaml`, the
     * highest-priority native declaration name, so the backup stays
     * self-describing for the re-import. Null (or an in-tree file present)
     * packs no synthetic entry.
     */
    val embeddedConfig: ByteArray? = null,
    /**
     * The rootfs' byte total, from the cached [com.fem.linexa.service.runtime.WorkspaceFileManager]
     * measurement. Purely the packing-progress denominator; null (or zero)
     * makes packing progress indeterminate. Never used as a correctness check.
     */
    val expectedBytes: Long? = null,
    /**
     * Whether guest credential material is packed (finding 022). Defaults to
     * [ExportCredentialPolicy.REMOVE_CREDENTIALS]: an export never leaks the
     * VNC password or SSH private keys unless the caller explicitly passes
     * [ExportCredentialPolicy.INCLUDE_CREDENTIALS] after warning the user.
     */
    val credentialPolicy: ExportCredentialPolicy = ExportCredentialPolicy.REMOVE_CREDENTIALS,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Which stage of the export pipeline a progress event belongs to. */
enum class ExportPhase {
    /** Nothing packed yet — cheap pre-flight (walkability of the rootfs). */
    PREPARING,

    /** The streaming tar+gzip walk over the live rootfs. */
    PACKING,

    /** The tar stream is finished; the gzip trailer is being written. */
    FINALIZING,

    /** The written archive is re-read end to end and byte-compared. */
    VERIFYING,
}

/** One export progress event — mirrors [com.fem.linexa.service.installer.InstallProgress]. */
data class ExportProgress(
    /** Source bytes packed (PACKING) or compressed bytes re-read (VERIFYING). */
    val bytesRead: Long,
    /**
     * The denominator for [bytesRead]: the rootfs total while packing, the
     * archive size while verifying. Null = the stage cannot measure (or the
     * caller supplied no estimate) — UI shows indeterminate.
     */
    val totalBytes: Long?,
    val currentEntryName: String?,
    val phase: ExportPhase,
    /** Real 0f..1f fraction when measurable, null otherwise (installer convention). */
    val fraction: Float? = null,
)

/** Outcome of [RootFsExporter.export]. */
sealed interface ExportResult {

    /**
     * The archive was fully written AND verified (structure re-parsed, bytes
     * checksum-identical). Only this shape may be shown to the user as a
     * successful export.
     */
    data class Success(
        /** SHA-256 over the compressed archive bytes, hex — what a re-import scan would compute. */
        val sha256: String,
        /** Final size of the archive. */
        val outputBytes: Long,
        /** Entries packed (excluding the synthetic embedded config when present). */
        val entryCount: Long,
        /**
         * Names of entries that were skipped as unpresentable special files
         * (sockets/devices/fifos — none of which a PRoot rootfs is expected
         * to carry, but a guest can create a fifo). Empty in the normal case;
         * non-empty means the backup is complete *minus these* and the UI
         * should say so rather than stay silent.
         */
        val skippedEntries: List<String> = emptyList(),
        /**
         * Archive-relative paths omitted because they hold guest credentials
         * and the request chose [ExportCredentialPolicy.REMOVE_CREDENTIALS].
         * Empty for [ExportCredentialPolicy.INCLUDE_CREDENTIALS] (or when the
         * guest had none). A `.gnupg` directory is reported once, not per
         * descendant, because its whole subtree is pruned.
         */
        val removedCredentials: List<String> = emptyList(),
    ) : ExportResult

    /**
     * The export did not complete. [reason] is user-presentable and names the
     * underlying cause (I/O error, validation refusal, verification mismatch).
     * The coordinator removes the partial output before surfacing this.
     */
    data class Failure(val reason: String) : ExportResult
}
