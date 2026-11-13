package com.fem.linexa.core.filesystem

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [RootfsWriteWindow] — the shared owner-write window used by
 * the runtime bridge and the installer for rootfs directories that an archive
 * restored as read-only (Arch `/`, Arch/Fedora `/proc`, Fedora `/usr/bin`).
 *
 * The window must be narrow: it widens only the named directory, restores the
 * archive's exact permission set, and never follows a symbolic link. Those
 * properties are what keep a provisioning write from silently changing the
 * installed system's security posture or escaping the rootfs.
 */
class RootfsWriteWindowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writableDirectoryIsNotModified() {
        val dir = tmp.newFolder("writable")
        val before = Files.getPosixFilePermissions(dir.toPath())

        RootfsWriteWindow.open(dir).close()

        assertEquals(
            "a directory that already has owner-write must be left byte-identical",
            before,
            Files.getPosixFilePermissions(dir.toPath())
        )
    }

    @Test
    fun readOnlyDirectoryIsWidenedForTheWindowAndRestoredExactly() {
        val dir = tmp.newFolder("readonly")
        val original = Files.getPosixFilePermissions(dir.toPath())
        val readOnly = original - PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(dir.toPath(), readOnly)

        try {
            RootfsWriteWindow.open(dir).use { window ->
                val widened = Files.getPosixFilePermissions(dir.toPath())
                assertTrue(
                    "the window must grant owner-write so a provisioning write can land",
                    PosixFilePermission.OWNER_WRITE in widened
                )
                File(dir, "overlay").writeText("content")
            }

            assertEquals(
                "close() must restore the archive's exact mode",
                readOnly,
                Files.getPosixFilePermissions(dir.toPath())
            )
            assertEquals("the write itself must persist", "content", File(dir, "overlay").readText())
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), original)
        }
    }

    @Test
    fun symlinkedDirectoryIsRefused() {
        val target = tmp.newFolder("target")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        try {
            RootfsWriteWindow.open(link)
            throw AssertionError("a symlinked directory must be refused, never widened")
        } catch (_: IOException) {
            // Expected: the window is scoped to a real rootfs directory.
        }

        val targetMode = Files.getPosixFilePermissions(target.toPath())
        assertTrue(
            "the symlink target must not have been touched",
            PosixFilePermission.OWNER_WRITE in targetMode
        )
    }

    @Test
    fun missingDirectoryIsRefused() {
        val missing = File(tmp.root, "does-not-exist")
        try {
            RootfsWriteWindow.open(missing)
            throw AssertionError("a missing directory must be refused")
        } catch (_: IOException) {
            // Expected.
        }
        assertFalse(missing.exists())
    }
}
