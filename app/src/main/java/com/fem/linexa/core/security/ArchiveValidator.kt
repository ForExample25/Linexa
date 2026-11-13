package com.fem.linexa.core.security

import java.io.File
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

/**
 * Validates archive safety during Import (§1.14, §1.15).
 * Checks for path traversal / zip-slip before extracting any tar/tar.zst
 * into a Workspace's rootfs.
 *
 * Phase 0: interface + stub. Phase 9: real implementation with
 * path-traversal, zip-slip, and archive-bomb checks. §1.20: added TAR.ZST
 * (the format Linexa's installer actually uses) and [checkEntry], a pure
 * per-entry check with no stream handling — this lets
 * `service.installer.StagedRootFsInstaller` validate each entry in the
 * *same* pass it extracts, instead of decompressing the whole archive
 * twice (once to validate, once to extract).
 */
/**
 * The tar entry kind a caller is validating. `null` keeps the pre-Phase-3
 * behaviour for callers that only have a name and a symlink flag (the
 * exporter, tests, ZIP entries).
 */
enum class ArchiveEntryType { REGULAR, DIRECTORY, SYMLINK, HARDLINK, SPECIAL }

/**
 * One classifier shared by every tar walk that feeds extraction, so the
 * streaming scan, the full-archive validation pass and the OCI layer scan can
 * never disagree about what a hardlink or a special file is.
 */
fun TarArchiveEntry.toArchiveEntryType(): ArchiveEntryType = when {
    isSymbolicLink -> ArchiveEntryType.SYMLINK
    isLink -> ArchiveEntryType.HARDLINK
    isDirectory -> ArchiveEntryType.DIRECTORY
    // POSIX tar has no socket typeflag and Apache Commons Compress 1.26.1
    // exposes no isSocket predicate, so character/block devices and FIFOs are
    // the complete set of special members a tar walk can actually encounter.
    isCharacterDevice || isBlockDevice || isFIFO -> ArchiveEntryType.SPECIAL
    else -> ArchiveEntryType.REGULAR
}

interface ArchiveValidator {
    fun validate(archiveFile: File): ValidationResult

    /**
     * Streaming validation during extraction. Reads from [inputStream] and
     * checks each entry's name for path traversal / zip-slip, while enforcing
     * an uncompressed size limit to catch archive bombs.
     *
     * @param inputStream Archive input stream (ZIP or TAR.GZ/XZ/ZST)
     * @param maxTotalSize Maximum allowed total uncompressed bytes (default 8 GB)
     * @return Valid if the stream passes all checks, Invalid with reason otherwise
     */
    fun validateDuringExtraction(
        inputStream: InputStream,
        maxTotalSize: Long = DEFAULT_MAX_UNCOMPRESSED_SIZE
    ): ValidationResult

    /**
     * Pure per-entry check (path traversal, zip-slip, symlink/hardlink escape,
     * special-file rejection) with no I/O — [name] is a raw archive entry path.
     *
     * [linkTarget] is meaningful for `SYMLINK` (when [isSymlink] is true) and
     * for `HARDLINK` (when [entryType] is [ArchiveEntryType.HARDLINK]).
     * [entryType] is optional so legacy callers keep their exact behaviour;
     * extraction scans pass it explicitly. Returns `null` if the entry is
     * safe, or an [ValidationResult.Invalid] with the reason otherwise.
     * Callers doing their own single-pass extraction (rather than a
     * separate validate-then-extract pass) call this once per entry.
     */
    fun checkEntry(
        name: String,
        isSymlink: Boolean = false,
        linkTarget: String? = null,
        entryType: ArchiveEntryType? = null
    ): ValidationResult?

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String, val path: String? = null) : ValidationResult
    }

    companion object {
        const val DEFAULT_MAX_UNCOMPRESSED_SIZE = 8L * 1024 * 1024 * 1024 // 8 GB
    }
}

/**
 * Stub implementation for Phase 0. Always returns Valid.
 */
class StubArchiveValidator : ArchiveValidator {
    override fun validate(archiveFile: File): ArchiveValidator.ValidationResult {
        return ArchiveValidator.ValidationResult.Valid
    }

    override fun validateDuringExtraction(
        inputStream: InputStream,
        maxTotalSize: Long
    ): ArchiveValidator.ValidationResult {
        return ArchiveValidator.ValidationResult.Valid
    }

    override fun checkEntry(
        name: String,
        isSymlink: Boolean,
        linkTarget: String?,
        entryType: ArchiveEntryType?
    ): ArchiveValidator.ValidationResult? {
        return null
    }
}
