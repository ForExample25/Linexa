package com.fem.linexa.core.filesystem

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for [deleteRecursivelyAllowingReadOnly], the cleanup path
 * used for failed installs and superseded rootfs trees.
 *
 * The fixture is the Arch/Fedora shape: a directory tree whose own root and
 * one nested directory are `0555`. Stock `File.deleteRecursively()` leaves
 * such a tree in place, which is how a failed Arch install left 1.5 GB of
 * staging behind.
 */
class ReadOnlyTreeDeletionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aTreeWithReadOnlyDirectoriesIsFullyDeleted() {
        val tree = tmp.newFolder("staging")
        val locked = File(tree, "proc").apply { mkdirs() }
        val nested = File(locked, "deeper").apply { mkdirs() }
        File(locked, ".stat").writeText("snapshot")
        File(nested, "payload").writeText("bytes")

        val readOnly = Files.getPosixFilePermissions(locked.toPath()) -
            PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(locked.toPath(), readOnly)
        Files.setPosixFilePermissions(nested.toPath(), readOnly)
        // Informational only: a root JVM can delete regardless of mode, and
        // the production device runs as the app uid, where the mode is real.
        assertFalse(
            "fixture must not be accidentally writable by the test user",
            Files.isWritable(locked.toPath())
        )

        assertTrue("the read-only tree must be deleted", tree.deleteRecursivelyAllowingReadOnly())
        assertFalse("nothing may remain at ${tree.absolutePath}", tree.exists())
    }

    @Test
    fun aSymlinkInsideTheTreeIsDeletedWithoutTouchingItsTarget() {
        val outside = tmp.newFolder("outside")
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val tree = tmp.newFolder("tree")
        val locked = File(tree, "usr/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(locked, "escape").toPath(), keep.toPath())

        val readOnly = Files.getPosixFilePermissions(locked.toPath()) -
            PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(locked.toPath(), readOnly)

        assertTrue(tree.deleteRecursivelyAllowingReadOnly())
        assertFalse(tree.exists())
        assertTrue("a symlink target outside the tree must survive", keep.isFile)
        assertTrue(keep.readText() == "keep")
    }

    @Test
    fun aMissingTreeIsAlreadyDeleted() {
        val absent = File(tmp.root, "no-such-tree")
        assertTrue(absent.deleteRecursivelyAllowingReadOnly())
    }
}
