package com.fem.linexa.core.filesystem

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Deletes this tree even when directories inside it carry modes that deny
 * their owner write access.
 *
 * ## Why the stock `deleteRecursively()` is not enough
 *
 * A rootfs archive faithfully restores its directory modes, and some
 * distributions ship directories without owner-write — Arch's root and
 * `/proc` are `0555`, Fedora's `/usr/bin` is `0555`. On POSIX a directory
 * must be writable by its owner to unlink entries inside it, so a plain
 * recursive delete walks into the read-only child, fails to remove its
 * contents, and leaves the whole tree behind. Two consequences matter:
 *
 *  - a failed install's staging tree is not discarded, so the next attempt
 *    extracts into stale content and the "failure discards staging" contract
 *    is false; and
 *  - failed re-installs can leave multi-gigabyte `rootfs.previous-*` debris.
 *
 * This helper widens each directory only for the duration of the delete.
 * The tree is being removed, so no installed file can observe the widened
 * mode and nothing outside [this] is touched.
 *
 * ## Symlink safety (RF-020)
 *
 * Kotlin's `deleteRecursively()` walks with `File.isDirectory`/`listFiles`,
 * which resolve symlinks; a guest- or archive-authored directory link would
 * therefore cause the *target's* contents to be deleted. This implementation
 * uses [Files.walkFileTree] **without** `FOLLOW_LINKS`: links are reported as
 * files and are unlinked themselves, including dangling links and link
 * loops. Their targets are never entered or modified.
 */
fun File.deleteRecursivelyAllowingReadOnly(): Boolean {
    val root = toPath()
    // `File.exists()` follows links; a dangling link must still be unlinked.
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true

    return try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // unlink(2) needs write+execute on the directory; read is
                    // needed to enumerate children on restrictive filesystems.
                    widenDirectoryForDeletion(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // `file` may be a symlink; delete(2) removes the link and
                    // never touches its target.
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // Most commonly a raced removal or a dangling link. Failing
                    // to delete it must not abort the rest of the cleanup.
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun widenDirectoryForDeletion(dir: Path) {
    try {
        val current = Files.getPosixFilePermissions(dir)
        val widened = current + OWNER_TRAVERSE_AND_UPDATE
        if (widened != current) Files.setPosixFilePermissions(dir, widened)
    } catch (_: UnsupportedOperationException) {
        dir.toFile().setReadable(true, true)
        dir.toFile().setWritable(true, true)
        dir.toFile().setExecutable(true, true)
    } catch (_: IOException) {
        // Deletion will fail and the caller reports false; never widen the
        // failure into a link-following fallback.
    } catch (_: SecurityException) {
        // Same as above.
    }
}

private val OWNER_TRAVERSE_AND_UPDATE: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
