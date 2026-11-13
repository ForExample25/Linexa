package com.fem.linexa.core.filesystem

import java.io.File
import java.nio.file.Files

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
 * mode; nothing outside [this] is touched, and symbolic links are never
 * followed (the walk reports them as entries, and the chmod is applied only
 * to real directories).
 */
fun File.deleteRecursivelyAllowingReadOnly(): Boolean {
    if (!exists()) return true

    walkBottomUp().forEach { entry ->
        val path = entry.toPath()
        if (Files.isSymbolicLink(path)) return@forEach
        if (!entry.isDirectory) return@forEach
        // Write+execute are what unlink(2) requires of the directory; read is
        // needed to enumerate children on filesystems that enforce it.
        entry.setReadable(true, true)
        entry.setWritable(true, true)
        entry.setExecutable(true, true)
    }

    return deleteRecursively()
}
