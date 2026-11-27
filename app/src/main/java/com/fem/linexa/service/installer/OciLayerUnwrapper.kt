package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.security.ArchiveSymlinkGuard
import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.core.security.toArchiveEntryType
import com.fem.linexa.service.rootfs.ArchiveEntryPath
import com.fem.linexa.service.rootfs.CompressedArchiveStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.146 — extracts the single rootfs layer blob out of an OCI image layout so
 * the native tar pass has an ordinary rootfs archive to work with.
 *
 * ## Why a separate pass instead of extracting the layer during the scan
 *
 * The scan pass ([StagedRootFsInstaller.analyze]) is deliberately a *reading*
 * pass: it buffers nothing larger than a marker file and writes only the teed
 * copy of the archive it was given. A rootfs layer is the whole distribution
 * (Fedora's aarch64 base layer is ~70 MB compressed, ~200 MB extracted), so
 * materializing it there would double the import cache's peak size for every
 * OCI image, and would do so *before* the user has confirmed the install on the
 * analysis screen.
 *
 * Instead the scan records only the layer's *path*
 * ([AnalyzeResult.Success.payloadFileName], resolved from the layout's own
 * `index.json` — never guessed), and this class re-streams the already-teed
 * outer archive once, during [StagedRootFsInstaller.install], copying that one
 * member to a file. The outer archive is a local file by then, so re-reading it
 * costs no network and no `ContentResolver` round-trip.
 *
 * ## Why the layer is written out rather than piped
 *
 * The native extractor runs `proot … tar -xf <file>`, which needs a real
 * seekable path — the same constraint that makes the scan pass tee its input in
 * the first place (see [StagedRootFsInstaller]'s class kdoc). Piping into tar's
 * stdin would mean giving up `--checkpoint` progress against a known size and
 * would make a tar failure indistinguishable from a broken pipe.
 *
 * ## Security
 *
 * [layerPath] is not attacker-chosen free text: it is built by
 * [com.fem.linexa.service.rootfs.profile.OciImageLayout.blobPathFor] from a
 * digest that was validated as a bare algorithm/hex token, and it is used here
 * only as an equality comparison against normalized entry names from the same
 * archive. This class writes to exactly one path it constructs itself inside the
 * import cache, so nothing derived from the archive reaches the filesystem as a
 * path.
 *
 * ## Why [unwrap] is two explicit passes
 *
 * **Copy pass** ([unwrap]) walks the outer image once and copies the matching
 * layer blob out byte-for-byte, then **validate pass** ([validateLayerEntries])
 * re-opens that file as its own tar stream and runs every entry inside it
 * through [ArchiveValidator.checkEntry].
 *
 * The second pass exists because the two passes see different things. The copy
 * pass only ever sees the *outer* image's entry names — `oci-layout`,
 * `index.json`, `blobs/sha256/<hex>` — and those are the only names
 * [StagedRootFsInstaller.scanEntries] validates for an OCI image. The rootfs
 * paths that actually get written to disk (`usr/lib/systemd/…`, and any
 * `../…` an image chose to pack) live *inside* the layer blob, and nothing on
 * the JVM side had ever looked at them: a plain rootfs tarball is checked by
 * `scanEntries` **and** by native GNU tar during Pass 2, while an OCI-sourced
 * one used to be checked by GNU tar alone. This class's kdoc previously claimed
 * the layer was "walked by the same ArchiveValidator rules as any other
 * rootfs", which was not true — the layer's internal entries were never handed
 * to the validator at all.
 *
 * That gap was not a live exploit path (GNU tar's own traversal protections
 * still applied at extraction time, and `layerPath` is derived from a validated
 * digest rather than attacker-influenced), but OCI images received strictly less
 * JVM-side scrutiny than plain tarballs. The validate pass closes it, so the
 * documented invariant is now the implemented one.
 *
 * The cost is one extra decompression of the layer with no extraction — cheap
 * next to the native tar pass that follows, and it happens before anything is
 * written into the staging rootfs.
 */
@Singleton
class OciLayerUnwrapper @Inject constructor(
    /**
     * §1.148: the same validator [StagedRootFsInstaller] uses for the outer
     * archive's entries, applied here to the layer's *internal* entries. Hilt
     * binds [com.fem.linexa.core.security.RealArchiveValidator] for this
     * interface, exactly as it does for [StagedRootFsInstaller].
     */
    private val archiveValidator: ArchiveValidator
) {

    sealed interface Result {
        /** [file] is the extracted layer, ready for the native tar pass. */
        data class Layer(val file: File) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * Copies [layerPath] out of [outerArchive] into [workDir] (copy pass), then
     * validates the copied layer's own entries (validate pass).
     *
     * @param outerArchive the teed OCI image archive (still compressed).
     * @param workDir the import cache — the layer lands beside the outer
     *   archive and is removed with it when the install finishes or fails.
     */
    fun unwrap(
        outerArchive: File,
        workDir: File,
        layerPath: String,
        /**
         * §1.147: leading components the outer archive wraps the whole image
         * layout in (`image/oci-layout`, `image/blobs/...`). Matched after
         * normalization so the resolved layer path — which is always relative
         * to the layout root — still names the same member.
         */
        stripComponents: Int = 0
    ): Result {
        val target = File(workDir, LAYER_FILE_NAME)
        // A leftover from an interrupted attempt must never be mistaken for this
        // run's layer: InstallRecoveryManager cleans the cache at startup, but an
        // install() retried within the same session would otherwise find a
        // truncated file here.
        target.delete()

        return try {
            outerArchive.inputStream().use { raw ->
                val opened = CompressedArchiveStream.open(raw)
                opened.stream.use { decompressed ->
                    TarArchiveInputStream(decompressed).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory &&
                                stripLeading(
                                    ArchiveEntryPath.normalize(entry.name),
                                    stripComponents
                                ) == layerPath
                            ) {
                                FileOutputStream(target).use { out -> tar.copyTo(out) }
                                Logger.i(
                                    TAG,
                                    "§1.146 unwrapped OCI layer '$layerPath' -> " +
                                        "${target.absolutePath} (${target.length()} bytes)"
                                )
                                // §1.148: the copy pass is done; the layer is only
                                // accepted once its own entries pass the validator.
                                // A read error inside the validate pass is caught
                                // by this function's own catch, which deletes the
                                // target — the same handling a mid-copy failure gets.
                                val verified = verify(target, layerPath)
                                if (verified is Result.Failure) return@unwrap verified
                                validateLayerEntries(target, layerPath)?.let { return@unwrap it }
                                return@unwrap verified
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            target.delete()
            Result.Failure(
                "OCI image layer '$layerPath' was named by index.json but is not present in the archive"
            )
        } catch (e: Exception) {
            target.delete()
            Logger.e(TAG, "§1.146 OCI layer unwrap failed", e)
            Result.Failure("Could not unwrap the OCI image's rootfs layer: ${e.message}")
        }
    }

    /**
     * Rejects an empty layer rather than handing tar a zero-byte file.
     *
     * Without this the failure surfaces as GNU tar's "Unexpected EOF in archive"
     * with proot's exit code attached, which points the user at the extractor
     * instead of at the malformed image.
     */
    /**
     * §1.147: drops exactly [components] leading path segments, or returns
     * [path] unchanged when it is shorter than that. The layout root is known
     * to be a single wrapper directory when the caller passes 1, so a member
     * outside it simply never matches — no heuristics here.
     */
    private fun stripLeading(path: String, components: Int): String {
        if (components <= 0) return path
        var remaining = path
        repeat(components) {
            if (!remaining.contains('/')) return path
            remaining = remaining.substringAfter('/')
        }
        return remaining
    }

    private fun verify(target: File, layerPath: String): Result {
        if (!target.isFile || target.length() == 0L) {
            target.delete()
            return Result.Failure("OCI image layer '$layerPath' is empty")
        }
        return Result.Layer(target)
    }

    /**
     * §1.148 validate pass — walks the copied layer as its own tar stream and
     * runs every entry through [ArchiveValidator.checkEntry], the same call
     * [StagedRootFsInstaller.scanEntries] makes for a plain rootfs tarball.
     * Returns null when every entry is safe, or the [Result.Failure] to return
     * instead of the layer.
     *
     * The check runs on the RAW entry name and never on a normalized one — see
     * [ArchiveEntryPath]'s kdoc: normalizing first would hand the validator an
     * already-laundered `../etc/passwd`. Symlink entries carry their target so
     * the escape check applies to those too; a rootfs is full of legitimate
     * symlinks, so they are validated rather than skipped.
     *
     * A rejected entry deletes [target]: the caller only inspects the [Result],
     * and leaving a half-validated archive in the import cache is exactly what
     * a later attempt reading the directory would pick up.
     */
    private fun validateLayerEntries(target: File, layerPath: String): Result.Failure? {
        target.inputStream().use { raw ->
            // An OCI layer may legitimately be an uncompressed tar
            // (`application/vnd.oci.image.layer.v1.tar`, no `+gzip`).
            // CompressedArchiveStream now recognizes a bare tar, so the normal
            // path opens it directly. The UnsupportedFormat fallback remains a
            // defensive path for a layer whose header detection fails: GNU
            // tar's `-xf` accepts such a file, so refusing it here would break
            // an install that used to succeed, and skipping validation would
            // reopen the gap this pass exists to close.
            val opened = try {
                CompressedArchiveStream.open(raw)
            } catch (_: CompressedArchiveStream.UnsupportedFormat) {
                null
            }
            val layerStream = opened?.stream ?: target.inputStream()
            layerStream.use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    var entries = 0L
                    val symlinkGuard = ArchiveSymlinkGuard()
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val safety = archiveValidator.checkEntry(
                            name = entry.name,
                            isSymlink = entry.isSymbolicLink,
                            linkTarget = entry.linkName,
                            entryType = entry.toArchiveEntryType()
                        )
                        if (safety is ArchiveValidator.ValidationResult.Invalid) {
                            target.delete()
                            Logger.w(
                                TAG,
                                "§1.148 rejected OCI layer '$layerPath': unsafe entry " +
                                    "'${entry.name}': ${safety.reason}"
                            )
                            return Result.Failure(
                                "Unsafe entry in the OCI image's rootfs layer " +
                                    "'${entry.name}': ${safety.reason}"
                            )
                        }
                        val normalized = ArchiveEntryPath.normalize(entry.name)
                        symlinkGuard.checkEntryPath(normalized, entry.name)?.let { reason ->
                            target.delete()
                            Logger.w(
                                TAG,
                                "§1.148 rejected OCI layer '$layerPath': $reason"
                            )
                            return Result.Failure(
                                "Unsafe entry in the OCI image's rootfs layer " +
                                    "'${entry.name}': $reason"
                            )
                        }
                        symlinkGuard.observe(
                            normalizedPath = normalized,
                            isSymlink = entry.isSymbolicLink,
                            linkTarget = entry.linkName
                        )
                        entries++
                        entry = tar.nextEntry
                    }
                    Logger.i(TAG, "§1.148 validated $entries entries inside OCI layer '$layerPath'")
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "StagedRootFsInstaller"

        /**
         * Deliberately extensionless: the layer's own compression is whatever
         * the image used (Fedora's is gzip, `application/vnd.oci.image.layer.v1.tar+gzip`),
         * and GNU tar's `-xf` auto-detects it from the magic bytes. Naming the
         * file `.tar.gz` would be a claim this class cannot verify.
         */
        const val LAYER_FILE_NAME = "oci-rootfs-layer"
    }
}
