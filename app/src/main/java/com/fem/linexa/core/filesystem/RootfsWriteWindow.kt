package com.fem.linexa.core.filesystem

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Narrow, explicit write access to a rootfs directory whose archive mode is
 * read-only.
 *
 * ## Why this is necessary
 *
 * PRoot emulates uid 0 for the guest, but every guest process still runs
 * under the Android app's real uid at the kernel boundary. A directory that
 * the archive restored as `0555` therefore rejects a write from both a normal
 * session and a `-0` session: PRoot's fake root cannot override the host
 * DAC check. Real rootfs archives (Arch, Fedora container images) commonly
 * ship `/` and `/proc` as `0555`, so Linexa has to add owner-write around its
 * own provisioning writes.
 *
 * This is deliberately not a recursive chmod and never touches another
 * user's files: only the exact directory is widened, only the owner bits are
 * added, and [close] restores the archive's original mode. Callers must treat
 * a failed restore as a real failure so a provisioning bug cannot silently
 * leave the tree more writable than the archive declared.
 *
 * The class is intentionally small and JVM-testable. It does not decide
 * WHICH directories may be widened; callers own that policy.
 */
object RootfsWriteLock {
    /**
     * Process-wide monitor for the `/proc` overlay write window.
     *
     * The runtime bridge and the installer both widen the same read-only
     * directory and restore it afterwards. Without one shared lock an
     * installer restore can land between two bridge writes and turn the
     * second into EACCES. File I/O under this monitor is bounded to a handful
     * of small overlay files.
     */
    val monitor: Any = Any()
}

class RootfsWriteWindow private constructor(
    val directory: File,
    private val originalPermissions: Set<PosixFilePermission>,
    private val restoreRequired: Boolean
) : AutoCloseable {

    override fun close() {
        if (!restoreRequired) return

        try {
            Files.setPosixFilePermissions(directory.toPath(), originalPermissions)
        } catch (e: Exception) {
            throw IOException(
                "could not restore permissions on ${directory.absolutePath}: ${e.message}",
                e
            )
        }
    }

    companion object {
        /**
         * Opens a write window on [directory].
         *
         * Fails instead of following a symlink: all callers write inside a
         * rootfs, and a swapped link must never redirect the widened mode
         * outside it. The directory must already exist; creating it (and the
         * parent-write decision that implies) stays with the caller.
         */
        @Throws(IOException::class)
        fun open(directory: File): RootfsWriteWindow {
            if (!directory.isDirectory) {
                throw IOException("not a directory: ${directory.absolutePath}")
            }

            val path = directory.toPath()
            if (Files.isSymbolicLink(path)) {
                throw IOException("refusing to widen a symlinked directory: ${directory.absolutePath}")
            }

            val original = Files.getPosixFilePermissions(path)
            if (PosixFilePermission.OWNER_WRITE in original) {
                return RootfsWriteWindow(directory, original, restoreRequired = false)
            }

            val widened = original + setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
            Files.setPosixFilePermissions(path, widened)
            return RootfsWriteWindow(directory, original, restoreRequired = true)
        }
    }
}
