package com.fem.linexa.core.security

import com.fem.linexa.core.logging.Logger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real archive validator implementation (§1.14, Phase 9; §1.20 added TAR.ZST).
 * Checks for path-traversal / zip-slip attacks and archive bombs
 * before extracting any archive into a Workspace's rootfs.
 *
 * Supports: ZIP, TAR.GZ, TAR.XZ, TAR.ZST
 */
@Singleton
class RealArchiveValidator @Inject constructor() : ArchiveValidator {

    companion object {
        private const val TAG = "ArchiveValidator"

        // Symlink-related path indicators
        private val SYMLINK_PATTERNS = listOf(
            "symlink", "link", "lnk"
        )
    }

    override fun checkEntry(
        name: String,
        isSymlink: Boolean,
        linkTarget: String?,
        entryType: ArchiveEntryType?
    ): ArchiveValidator.ValidationResult? {
        checkPathTraversal(name)?.let { return it }
        if (entryType == ArchiveEntryType.SPECIAL) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Special file type (device/FIFO/socket) is not allowed in a rootfs archive",
                path = name
            )
        }
        if (entryType == ArchiveEntryType.HARDLINK) {
            return checkHardlinkTarget(name, linkTarget)
        }
        if ((isSymlink || entryType == ArchiveEntryType.SYMLINK) && linkTarget != null) {
            checkSymlinkEscape(name, linkTarget)?.let { return it }
        }
        return null
    }

    override fun validate(archiveFile: File): ArchiveValidator.ValidationResult {
        if (!archiveFile.exists()) {
            Logger.w(TAG, "Archive file does not exist: ${archiveFile.absolutePath}")
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive file does not exist",
                path = archiveFile.absolutePath
            )
        }

        if (!archiveFile.canRead()) {
            Logger.w(TAG, "Archive file is not readable: ${archiveFile.absolutePath}")
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive file is not readable",
                path = archiveFile.absolutePath
            )
        }

        if (archiveFile.length() == 0L) {
            Logger.w(TAG, "Archive file is empty: ${archiveFile.absolutePath}")
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive file is empty",
                path = archiveFile.absolutePath
            )
        }

        Logger.d(TAG, "Validating archive: ${archiveFile.name} (${archiveFile.length()} bytes)")

        val result = when {
            isZipFile(archiveFile) -> validateZipEntries(archiveFile)
            isTarGzFile(archiveFile) -> validateTarEntries(archiveFile, ArchiveType.TAR_GZ)
            isTarXzFile(archiveFile) -> validateTarEntries(archiveFile, ArchiveType.TAR_XZ)
            isTarZstFile(archiveFile) -> validateTarEntries(archiveFile, ArchiveType.TAR_ZST)
            else -> {
                Logger.w(TAG, "Unsupported archive format: ${archiveFile.name}")
                ArchiveValidator.ValidationResult.Invalid(
                    reason = "Unsupported archive format. Supported: .zip, .tar.gz, .tar.xz, .tar.zst"
                )
            }
        }

        if (result is ArchiveValidator.ValidationResult.Valid) {
            Logger.i(TAG, "Archive validation passed: ${archiveFile.name}")
        } else {
            Logger.w(TAG, "Archive validation failed: ${(result as ArchiveValidator.ValidationResult.Invalid).reason}")
        }

        return result
    }

    override fun validateDuringExtraction(
        inputStream: InputStream,
        maxTotalSize: Long
    ): ArchiveValidator.ValidationResult {
        Logger.d(TAG, "Starting streaming validation (max size: $maxTotalSize bytes)")

        // Detect format from the input stream. 6 bytes covers every magic
        // number checked below (XZ's is the longest, at 6) — the buffer was
        // previously only 4 bytes, which silently made isXzMagic's `size >=
        // 6` guard always false and TAR.XZ undetectable via this streaming
        // path (file-extension-based detect() was unaffected). Fixed here
        // while adding zstd detection, since both needed a correct buffer.
        val bufferedStream = inputStream.buffered(8192)
        bufferedStream.mark(8)
        val magicBytes = ByteArray(6)
        bufferedStream.read(magicBytes)
        bufferedStream.reset()

        return when {
            isZipMagic(magicBytes) -> validateZipStream(bufferedStream, maxTotalSize)
            isGzipMagic(magicBytes) -> validateTarStream(bufferedStream, maxTotalSize, ArchiveType.TAR_GZ)
            isXzMagic(magicBytes) -> validateTarStream(bufferedStream, maxTotalSize, ArchiveType.TAR_XZ)
            isZstdMagic(magicBytes) -> validateTarStream(bufferedStream, maxTotalSize, ArchiveType.TAR_ZST)
            else -> {
                Logger.w(TAG, "Cannot detect archive format from stream header")
                ArchiveValidator.ValidationResult.Invalid(
                    reason = "Cannot detect archive format. Expected ZIP, TAR.GZ, TAR.XZ, or TAR.ZST"
                )
            }
        }
    }

    // ── Format Detection ──────────────────────────────────────────────

    private fun isZipFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                isZipMagic(header)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isTarGzFile(file: File): Boolean {
        return file.name.endsWith(".tar.gz", ignoreCase = true) ||
                file.name.endsWith(".tgz", ignoreCase = true) ||
                isGzipFile(file)
    }

    private fun isTarXzFile(file: File): Boolean {
        return file.name.endsWith(".tar.xz", ignoreCase = true) ||
                file.name.endsWith(".txz", ignoreCase = true) ||
                isXzFile(file)
    }

    private fun isTarZstFile(file: File): Boolean {
        return file.name.endsWith(".tar.zst", ignoreCase = true) ||
                file.name.endsWith(".tzst", ignoreCase = true) ||
                isZstdFile(file)
    }

    private fun isGzipFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(2)
                stream.read(header)
                isGzipMagic(header)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isXzFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(6)
                stream.read(header)
                isXzMagic(header)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isZstdFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                isZstdMagic(header)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isZipMagic(header: ByteArray): Boolean {
        return header.size >= 4 &&
                header[0] == 0x50.toByte() && // P
                header[1] == 0x4B.toByte() && // K
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
    }

    private fun isGzipMagic(header: ByteArray): Boolean {
        return header.size >= 2 &&
                header[0] == 0x1F.toByte() &&
                header[1] == 0x8B.toByte()
    }

    private fun isXzMagic(header: ByteArray): Boolean {
        return header.size >= 6 &&
                header[0] == 0xFD.toByte() &&
                header[1] == 0x37.toByte() &&
                header[2] == 0x7A.toByte() &&
                header[3] == 0x58.toByte() &&
                header[4] == 0x5A.toByte() &&
                header[5] == 0x00.toByte()
    }

    private fun isZstdMagic(header: ByteArray): Boolean {
        // Zstandard frame magic number, RFC 8878 §3.1.1: 0xFD2FB528 (little-endian on disk).
        return header.size >= 4 &&
                header[0] == 0x28.toByte() &&
                header[1] == 0xB5.toByte() &&
                header[2] == 0x2F.toByte() &&
                header[3] == 0xFD.toByte()
    }

    // ── ZIP Validation ────────────────────────────────────────────────

    private fun validateZipEntries(file: File): ArchiveValidator.ValidationResult {
        return try {
            file.inputStream().use { stream ->
                validateZipStream(stream, ArchiveValidator.DEFAULT_MAX_UNCOMPRESSED_SIZE)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read ZIP archive: ${file.name}", e)
            ArchiveValidator.ValidationResult.Invalid(
                reason = "Failed to read ZIP archive: ${e.message}"
            )
        }
    }

    private fun validateZipStream(
        inputStream: InputStream,
        maxTotalSize: Long
    ): ArchiveValidator.ValidationResult {
        var totalUncompressed = 0L

        try {
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry

                while (entry != null) {
                    val name = entry.name

                    // Path traversal / zip-slip check (ZIP doesn't have
                    // native symlink support, so isSymlink is always false)
                    val traversalResult = checkEntry(name)
                    if (traversalResult != null) {
                        Logger.w(TAG, "Path traversal detected in ZIP: $name")
                        return traversalResult
                    }

                    // Archive bomb check
                    if (!entry.isDirectory) {
                        totalUncompressed += entry.size
                        if (totalUncompressed > maxTotalSize) {
                            Logger.w(TAG, "ZIP archive bomb detected: total $totalUncompressed > $maxTotalSize")
                            return ArchiveValidator.ValidationResult.Invalid(
                                reason = "Archive bomb: uncompressed size exceeds limit ($maxTotalSize bytes)",
                                path = name
                            )
                        }
                    }

                    entry = zipStream.nextEntry
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error reading ZIP entries", e)
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Failed to read ZIP archive: ${e.message}"
            )
        }

        Logger.d(TAG, "ZIP validation passed. Total uncompressed: $totalUncompressed bytes")
        return ArchiveValidator.ValidationResult.Valid
    }

    // ── TAR.GZ / TAR.XZ Validation ───────────────────────────────────

    private enum class ArchiveType(val extension: String) {
        TAR_GZ(".tar.gz"),
        TAR_XZ(".tar.xz"),
        TAR_ZST(".tar.zst")
    }

    private fun validateTarEntries(
        file: File,
        type: ArchiveType
    ): ArchiveValidator.ValidationResult {
        return try {
            file.inputStream().use { stream ->
                validateTarStream(stream, ArchiveValidator.DEFAULT_MAX_UNCOMPRESSED_SIZE, type)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read ${type.extension} archive: ${file.name}", e)
            ArchiveValidator.ValidationResult.Invalid(
                reason = "Failed to read ${type.extension} archive: ${e.message}"
            )
        } catch (e: LinkageError) {
            // §1.107: zstd-jni / xz are native-backed. A missing or
            // unloadable native library throws UnsatisfiedLinkError /
            // NoClassDefFoundError — an Error, not an Exception, so the catch
            // above let it escape validate() and crash the caller. A
            // validator must fail closed: no decompressor means the archive
            // cannot be proven safe, so it is Invalid.
            Logger.e(TAG, "Codec unavailable for ${type.extension} archive: ${file.name}", e)
            ArchiveValidator.ValidationResult.Invalid(
                reason = "Cannot validate ${type.extension} archive: decompressor unavailable (${e.message})"
            )
        }
    }

    private fun validateTarStream(
        inputStream: InputStream,
        maxTotalSize: Long,
        type: ArchiveType
    ): ArchiveValidator.ValidationResult {
        var totalUncompressed = 0L

        try {
            val decompressedStream: InputStream = when (type) {
                ArchiveType.TAR_GZ -> GzipCompressorInputStream(inputStream)
                ArchiveType.TAR_XZ -> XZCompressorInputStream(inputStream)
                ArchiveType.TAR_ZST -> ZstdCompressorInputStream(inputStream)
            }

            TarArchiveInputStream(decompressedStream).use { tarStream ->
                var entry: TarArchiveEntry? = tarStream.nextEntry

                while (entry != null) {
                    val name = entry.name

                    // Path traversal / zip-slip + symlink/hardlink escape and
                    // special-file rejection, in one call — RootFS archives
                    // are full of legitimate symlinks and hardlinks (e.g.
                    // /bin -> usr/bin), so this must run on every entry.
                    val entryResult = checkEntry(
                        name = name,
                        isSymlink = entry.isSymbolicLink,
                        linkTarget = entry.linkName,
                        entryType = entry.toArchiveEntryType()
                    )
                    if (entryResult != null) {
                        Logger.w(TAG, "Unsafe entry detected in ${type.extension}: $name")
                        return entryResult
                    }

                    // Archive bomb check
                    if (!entry.isDirectory) {
                        totalUncompressed += entry.size
                        if (totalUncompressed > maxTotalSize) {
                            Logger.w(TAG, "${type.extension} archive bomb detected: total $totalUncompressed > $maxTotalSize")
                            return ArchiveValidator.ValidationResult.Invalid(
                                reason = "Archive bomb: uncompressed size exceeds limit ($maxTotalSize bytes)",
                                path = name
                            )
                        }
                    }

                    entry = tarStream.nextEntry
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error reading ${type.extension} entries", e)
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Failed to read ${type.extension} archive: ${e.message}"
            )
        } catch (e: LinkageError) {
            // §1.107: see validateTarEntries — fail closed when the native
            // zstd/xz codec cannot be loaded rather than propagating an Error.
            Logger.e(TAG, "Codec unavailable while reading ${type.extension} entries", e)
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Cannot validate ${type.extension} archive: decompressor unavailable (${e.message})"
            )
        }

        Logger.d(TAG, "${type.extension} validation passed. Total uncompressed: $totalUncompressed bytes")
        return ArchiveValidator.ValidationResult.Valid
    }

    // ── Path Traversal / Zip-Slip Checks ──────────────────────────────

    /**
     * Validates that an archive entry name does not escape the extraction
     * directory via path traversal or zip-slip techniques.
     *
     * Checks:
     * - Absolute paths (starting with /)
     * - Parent directory references (`..` as a whole path segment)
     * - Backslash used *as a path separator* (Windows-style relative paths,
     *   UNC prefixes) — not a bare literal backslash character, which is a
     *   valid, unremarkable byte in a Linux filename (see below)
     * - Null bytes
     * - Backtracking after normalization
     */
    private fun checkPathTraversal(entryName: String): ArchiveValidator.ValidationResult? {
        // Null byte injection
        if (entryName.contains('\u0000')) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive entry contains null byte",
                path = entryName
            )
        }

        // Absolute path. Also catches UNC-style paths ("\\server\share"),
        // since those start with a backslash too.
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive entry has absolute path",
                path = entryName
            )
        }

        // §1.147: '/' is the ONE and ONLY path separator in a POSIX tar
        // entry name. '\' is an ordinary filename byte with no structural
        // meaning, so host-side code must never split an archive name on it
        // and must never count backslashes as evidence of anything.
        //
        // This is the single source of truth for the name's structure: split
        // on '/' once and share the result with every check below.
        val segments = entryName.split('/')

        // Windows-style path hardening, kept but made *structural* rather
        // than quantitative. The predicate this replaced rejected any name
        // holding 2+ backslashes anywhere, which is a quantity that carries
        // no structural information: systemd ships auto-generated unit names
        // using its own "\xNN" escaping of characters like '-', and a unit
        // whose name escapes two characters therefore contains two literal
        // backslashes inside ONE segment with zero separator semantics — e.g.
        // "usr/lib/systemd/system/system-systemd\x2dmute\x2dconsole.slice",
        // a normal file in any current systemd rootfs. Counting rejected it.
        //
        // What actually distinguishes the attack shape from the legitimate
        // shape is structure, not count: a Windows relative path
        // ("usr\bin\sh") uses '\' *instead of* '/' and so contains no '/' at
        // all, while every real escaped-unit path is a normal '/'-delimited
        // path that merely happens to contain '\' inside a leaf segment.
        // Flag only those two shapes, both immune to adding more escapes:
        //   1. a name that contains '\' but no '/' whatsoever, and
        //   2. a backslash-delimited sub-part that is exactly "..", i.e. an
        //      attempt to walk up through backslash-as-separator.
        val hasBackslash = entryName.contains('\\')
        if (hasBackslash && segments.size == 1) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive entry uses backslash instead of '/' as path separator",
                path = entryName
            )
        }
        if (hasBackslash && segments.any { segment -> segment.split('\\').any { it == ".." } }) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive entry contains backslash-delimited parent directory reference",
                path = entryName
            )
        }

        // Parent directory traversal. Compared per segment, not as a
        // substring: "usr/lib/libfoo..so" is a legitimate filename that
        // merely contains two dots, while a segment equal to ".." is the
        // actual traversal construct.
        if (segments.any { it == ".." }) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Archive entry contains parent directory traversal",
                path = entryName
            )
        }

        // Normalize and verify no traversal after normalization. Kept as the
        // load-bearing traversal check: it is independent of the backslash
        // rules above and would reject an escape even if both were absent.
        val normalizedPath = segments.filter { it.isNotEmpty() && it != "." }

        var depth = 0
        for (segment in normalizedPath) {
            if (segment == "..") {
                depth--
            } else {
                depth++
            }
            if (depth < 0) {
                return ArchiveValidator.ValidationResult.Invalid(
                    reason = "Archive entry escapes extraction directory",
                    path = entryName
                )
            }
        }

        return null
    }

    /**
     * Checks a tar hardlink's target before extraction.
     *
     * POSIX tar stores a hardlink's `linkname` as another archive member's
     * name; GNU tar resolves it relative to the archive root, but producers
     * are not perfectly uniform and some tools store a path relative to the
     * entry's own directory. Rather than pick an anchor that might disagree
     * with the extractor, this check is deliberately fail-closed:
     *
     * - an empty/blank target is rejected (nothing legitimate to link to),
     * - an absolute target (`/etc/passwd`, `\server\share`, `C:\...`) is
     *   rejected,
     * - any `..` segment — `/`- or `\`-delimited — is rejected,
     * - a target that normalizes to nothing (`.`, `./`) is rejected.
     *
     * Every remaining relative target is a plain path below the extraction
     * root under either anchoring rule, so accepting it cannot escape. A
     * hardlink is strictly more dangerous than a symlink here: the
     * post-extraction [com.fem.linexa.service.installer.ArchiveBoundaryAuditor]
     * rewrites symlink targets but has no hardlink equivalent, so this is the
     * only boundary check for them.
     */
    private fun checkHardlinkTarget(
        entryName: String,
        target: String?
    ): ArchiveValidator.ValidationResult? {
        if (target.isNullOrBlank()) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink entry has no target",
                path = entryName
            )
        }
        if (target.contains('\u0000')) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink target contains null byte",
                path = "$entryName -> $target"
            )
        }
        // Absolute POSIX/UNC/Windows-drive paths.
        if (target.startsWith("/") || target.startsWith("\\") ||
            (target.length >= 2 && target[1] == ':' && target[0].isLetter())
        ) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink target has absolute path",
                path = "$entryName -> $target"
            )
        }

        val segments = target.split('/')
        // Windows-style relative path: '\' used instead of '/'.
        if (target.contains('\\') && segments.size == 1) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink target uses backslash instead of '/' as path separator",
                path = "$entryName -> $target"
            )
        }
        if (segments.any { it == ".." } ||
            (target.contains('\\') && segments.any { segment -> segment.split('\\').any { it == ".." } })
        ) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink target contains parent directory traversal",
                path = "$entryName -> $target"
            )
        }

        // ".", "./" and repeated separators normalize away to nothing.
        if (segments.none { it.isNotEmpty() && it != "." }) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Hardlink target does not name a file",
                path = "$entryName -> $target"
            )
        }

        return null
    }

    /**
     * Checks for symlink-based escape attacks where a symlink points
     * outside the extraction directory.
     *
     * The rootfs is the extraction boundary. A symlink at `entryName`
     * with target `linkTarget` is safe if and only if the resolved path
     * stays inside the rootfs root (`/`). The resolution logic:
     *
     * - **Relative target** (`bin/busybox`): resolved relative to the
     *   symlink's parent directory. If the parent is `usr/bin`, the
     *   resolved path is `usr/bin/busybox`.
     * - **Absolute target** (`/bin/busybox`): anchored at the rootfs
     *   root (`/`). Under chroot/PRoot the guest `/` maps to the
     *   rootfs directory, so this resolves to `rootfs/bin/busybox`
     *   — safe, normal, and common (Alpine BusyBox uses absolute
     *   symlinks heavily).
     *
     * In both cases, the resolved path is normalized (segments `.` and
     * `..` are resolved) and then checked: if any `..` would climb
     * above the rootfs root (depth < 0), the symlink is rejected.
     *
     * This is deliberately more permissive than the pre-§1.35 blanket
     * refusal of all absolute targets (which broke Alpine/Debian Trixie
     * imports — §5 §1.26 finding). The old check was: absolute target
     * → reject; `..` anywhere → reject. The new check: resolve the
     * target relative to the rootfs root and reject only if the
     * resolution escapes the root. This catches path-traversal attacks
     * (`../../etc/passwd`, `../../../outside`) while allowing the
     * legitimate absolute symlinks that real rootfs images use.
     *
     * **This is a guest-semantics check, not a host-safety guarantee.**
     * After extraction an absolute target is still host-absolute on disk,
     * so a host-side `chmod`/`sed`/read would follow it outside staging.
     * Host safety is enforced by the post-extraction
     * [com.fem.linexa.service.installer.ArchiveBoundaryAuditor] (`RFS-03`):
     * it rewrites in-root absolute targets to relative and rejects escapes
     * before any fixup or activation.
     */
    private fun checkSymlinkEscape(entryName: String, target: String): ArchiveValidator.ValidationResult? {
        if (target.isBlank()) {
            return null
        }

        // Null byte injection in target
        if (target.contains('\u0000')) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Symlink target contains null byte",
                path = "$entryName -> $target"
            )
        }

        // Resolve the symlink to a path relative to the rootfs root.
        //
        // For relative targets: anchor = dirname(entryName).
        // For absolute targets: anchor = "/" (rootfs root).
        val anchorSegments = if (target.startsWith("/")) {
            emptyList() // absolute: anchored at root
        } else {
            // entryName may be "./usr/bin/yes" — strip leading "./"
            val entryParent = entryName
                .removePrefix("./")
                .substringBeforeLast("/", missingDelimiterValue = "")
            entryParent.split("/").filter { it.isNotEmpty() && it != "." }
        }

        // Split the target itself into path segments.
        val targetSegments = target
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotEmpty() && it != "." }

        // Walk the anchor + target segments, tracking depth.
        // depth counts net upward moves (..) vs downward moves.
        var depth = 0
        for (segment in anchorSegments + targetSegments) {
            if (segment == "..") {
                depth--
            } else {
                depth++
            }
            if (depth < 0) {
                // The resolution climbs above the rootfs root — escape.
                return ArchiveValidator.ValidationResult.Invalid(
                    reason = "Symlink escapes extraction rootfs boundary",
                    path = "$entryName -> $target"
                )
            }
        }

        // Reject absolute targets containing `..` segments — while the
        // depth check above catches most escapes, an absolute path like
        // `/../etc/passwd` resolves to `/etc/passwd` in a chroot but
        // could be misinterpreted by host-side tools that don't chroot.
        // Being conservative: if the anchor is root (absolute target) and
        // there are any `..` segments in the target itself, reject.
        if (target.startsWith("/") && target.contains("..")) {
            return ArchiveValidator.ValidationResult.Invalid(
                reason = "Absolute symlink contains parent directory traversal",
                path = "$entryName -> $target"
            )
        }

        return null
    }
}
