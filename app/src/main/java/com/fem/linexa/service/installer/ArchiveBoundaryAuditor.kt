package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `RFS-03` post-extraction archive boundary audit.
 *
 * Extraction runs bundled GNU `tar` through PRoot and can materialize a
 * symlink whose target is a host-absolute path (for example
 * `etc/passwd -> /data/user/0/com.fem.linexa/files/victim`). A host-side
 * chmod/sed/read through that link would modify a file outside the workspace
 * staging directory. This pass runs **after extraction and before any
 * fixup**, walks the staging tree without following links, and:
 *
 * - rewrites an absolute link whose target lexically resolves inside staging
 *   to a relative target (preserving guest semantics across activation, when
 *   staging is renamed to `rootfs`);
 * - rejects the archive when an absolute or relative target lexically escapes
 *   staging, contains a NUL byte, or cannot be inspected;
 * - rejects `/etc/passwd`, `/etc/shadow`, `/etc/group` or `/etc/gshadow` when
 *   the extracted entry is a symlink rather than a regular file.
 *
 * The walk is lexical on purpose: a dangling in-root absolute link is still
 * rewritten (a guest may create the target later), and a link cycle is never
 * descended into. The caller owns [staging] exclusively at this point, so no
 * writer can race the walk.
 */
@Singleton
class ArchiveBoundaryAuditor @Inject constructor(
    private val diagnosticLogManager: DiagnosticLogManager
) {

    /**
     * Audits [staging] and normalizes safe absolute links in place. Returns
     * null when the tree is safe to fix up and activate, or a precise
     * user-facing failure that makes the caller discard staging.
     */
    fun auditAndNormalize(staging: File, workspaceId: String): String? {
        if (!staging.isDirectory) {
            return failure(
                workspaceId,
                "archive boundary audit could not run: staging is not a directory (${staging.absolutePath})"
            )
        }

        val root = try {
            staging.absoluteFile.toPath().normalize()
        } catch (e: Exception) {
            return failure(workspaceId, "archive boundary audit could not resolve staging: ${e.message}")
        }

        val rewrites = ArrayList<Rewrite>()
        var checkedLinks = 0
        var inspectionFailure: String? = null

        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                        checkedLinks++

                        val target = try {
                            Files.readSymbolicLink(file)
                        } catch (e: Exception) {
                            inspectionFailure =
                                "archive boundary audit could not read symlink '${relative(root, file)}': ${e.message}"
                            return FileVisitResult.TERMINATE
                        }
                        val targetText = target.toString()
                        val linkRel = relative(root, file)

                        if (targetText.isEmpty()) {
                            inspectionFailure =
                                "archive boundary audit rejected '$linkRel': empty symlink target"
                            return FileVisitResult.TERMINATE
                        }
                        if (targetText.indexOf('\u0000') >= 0) {
                            inspectionFailure =
                                "archive boundary audit rejected '$linkRel': symlink target contains a NUL byte"
                            return FileVisitResult.TERMINATE
                        }

                        if (target.isAbsolute) {
                            // Lexical resolution against the staging root: the
                            // guest's `/` is the staging directory, so an
                            // absolute target must stay under it.
                            val resolved = root.resolve(targetText.trimStart('/')).normalize()
                            if (!resolved.startsWith(root)) {
                                inspectionFailure =
                                    "archive boundary audit rejected '$linkRel' -> '$targetText': " +
                                        "absolute target escapes the staging rootfs"
                                return FileVisitResult.TERMINATE
                            }
                            val relativeTarget = file.parent.relativize(resolved)
                            if (relativeTarget.toString().isEmpty()) {
                                inspectionFailure =
                                    "archive boundary audit rejected '$linkRel' -> '$targetText': " +
                                        "target resolves to the staging root itself"
                                return FileVisitResult.TERMINATE
                            }
                            rewrites += Rewrite(file, linkRel, relativeTarget)
                        } else {
                            // Relative links are already rename-safe. Reject
                            // only those that climb above the staging root.
                            val resolved = file.parent.resolve(targetText).normalize()
                            if (!resolved.startsWith(root)) {
                                inspectionFailure =
                                    "archive boundary audit rejected '$linkRel' -> '$targetText': " +
                                        "relative target escapes the staging rootfs"
                                return FileVisitResult.TERMINATE
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        inspectionFailure =
                            "archive boundary audit could not inspect '${relative(root, file)}': ${exc.message}"
                        return FileVisitResult.TERMINATE
                    }
                }
            )
        } catch (e: Exception) {
            inspectionFailure = "archive boundary audit failed while walking staging: ${e.message}"
        }

        if (inspectionFailure != null) {
            return failure(workspaceId, inspectionFailure!!)
        }

        // RFS-03 account-file contract: these must be regular files at fixup
        // time. A symlink is rejected here even when it points inside staging,
        // because the fixup's no-follow writes are intentionally strict.
        for (name in ACCOUNT_FILE_NAMES) {
            val accountFile = root.resolve("etc").resolve(name)
            if (Files.isSymbolicLink(accountFile)) {
                return failure(
                    workspaceId,
                    "archive boundary audit rejected 'etc/$name': account files must not be symlinks"
                )
            }
            if (Files.exists(accountFile) && !Files.isRegularFile(accountFile)) {
                return failure(
                    workspaceId,
                    "archive boundary audit rejected 'etc/$name': account file is not a regular file"
                )
            }
        }

        // Apply rewrites after the scan, in deterministic order, so a failure
        // never leaves a half-normalized tree silently promoted. Each rewrite
        // is staged beside the original and moved over it, and a parent
        // directory restored by the archive as read-only (Fedora's
        // `/usr/lib64`) is widened only for that one replacement.
        rewrites.sortBy { it.linkRel }
        for (rewrite in rewrites) {
            val error = replaceSymlink(rewrite)
            if (error != null) return failure(workspaceId, error)
        }

        val summary = "RFS-03 boundary audit OK: $checkedLinks symlink(s) inspected, " +
            "${rewrites.size} absolute link(s) normalized to relative targets"
        Logger.i(TAG, summary)
        logEvent(workspaceId, summary)
        return null
    }

    /**
     * Replaces one link with a symlink to its relative target.
     *
     * The replacement is created under a temporary name in the same directory
     * and moved over the old link, so the path never disappears. A parent
     * directory whose archive mode denies owner-write is temporarily widened
     * and restored in `finally`; a failed restore fails the audit rather than
     * leaving the installed rootfs more writable than its archive declared.
     */
    private fun replaceSymlink(rewrite: Rewrite): String? {
        val parent = rewrite.link.parent
            ?: return "archive boundary audit could not normalize '${rewrite.linkRel}': link has no parent directory"
        val temp = parent.resolve("$TEMP_PREFIX${rewrite.link.fileName}")

        val originalPermissions = try {
            Files.getPosixFilePermissions(parent)
        } catch (_: Exception) {
            null
        }
        var widened = false
        if (originalPermissions != null && PosixFilePermission.OWNER_WRITE !in originalPermissions) {
            try {
                Files.setPosixFilePermissions(
                    parent,
                    originalPermissions + PosixFilePermission.OWNER_WRITE
                )
                widened = true
            } catch (e: Exception) {
                return "archive boundary audit could not normalize '${rewrite.linkRel}': " +
                    "parent directory is not writable: ${e.message}"
            }
        }

        var failure: String? = null
        try {
            Files.deleteIfExists(temp)
            Files.createSymbolicLink(temp, rewrite.relativeTarget)
            try {
                Files.move(temp, rewrite.link, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, rewrite.link, StandardCopyOption.REPLACE_EXISTING)
            }
            val written = Files.readSymbolicLink(rewrite.link)
            if (written != rewrite.relativeTarget) {
                failure = "archive boundary audit could not normalize '${rewrite.linkRel}': " +
                    "link reads back as '$written' instead of '${rewrite.relativeTarget}'"
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: Exception) {
                // The move failed; a leftover temp is discarded with staging.
            }
            failure = "archive boundary audit could not normalize '${rewrite.linkRel}': ${e.message}"
        } finally {
            if (widened) {
                try {
                    Files.setPosixFilePermissions(parent, originalPermissions!!)
                } catch (e: Exception) {
                    val restore = "archive boundary audit could not restore permissions of " +
                        "'${rewrite.linkRel}': ${e.message}"
                    failure = failure?.let { "$it; $restore" } ?: restore
                }
            }
        }
        return failure
    }

    private fun relative(root: Path, file: Path): String = try {
        root.relativize(file).toString().ifEmpty { "." }
    } catch (_: Exception) {
        file.toString()
    }

    private fun failure(workspaceId: String, message: String): String {
        Logger.e(TAG, message)
        logEvent(workspaceId, "RFS-03 BOUNDARY AUDIT FAILED: $message")
        return message
    }

    private fun logEvent(workspaceId: String, content: String) {
        try {
            diagnosticLogManager.appendLog(workspaceId, "extraction.log", content)
        } catch (_: Exception) {
            // Diagnostics are best effort and must not mask the audit result.
        }
    }

    private data class Rewrite(
        val link: Path,
        val linkRel: String,
        val relativeTarget: Path
    )

    private companion object {
        const val TAG = "ArchiveBoundaryAuditor"
        const val TEMP_PREFIX = ".linexa-rfs03-"
        val ACCOUNT_FILE_NAMES = listOf("passwd", "shadow", "group", "gshadow")
    }
}
