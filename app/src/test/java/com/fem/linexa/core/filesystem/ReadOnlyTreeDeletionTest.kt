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

    /**
     * A directory symlink must be unlinked, never traversed. Kotlin's
     * `deleteRecursively()` walks with `File.isDirectory`/`listFiles`, both of
     * which follow links, so this is the out-of-sandbox data-loss case from
     * RF-020.
     */
    @Test
    fun aDirectorySymlinkIsUnlinkedWithoutDeletingItsTarget() {
        val victim = tmp.newFolder("victim")
        val victimFile = File(victim, "keep.txt").apply { writeText("keep") }
        val victimNested = File(victim, "nested").apply { mkdirs() }
        File(victimNested, "deep.txt").writeText("deep")

        val tree = tmp.newFolder("tree")
        File(tree, "ordinary").apply { mkdirs() }
        Files.createSymbolicLink(File(tree, "escape").toPath(), victim.toPath())

        assertTrue(tree.deleteRecursivelyAllowingReadOnly())
        assertFalse("the tree itself must be gone", tree.exists())
        assertTrue("directory symlink target must survive", victimFile.isFile)
        assertTrue(victimFile.readText() == "keep")
        assertTrue("nested target content must survive", File(victimNested, "deep.txt").isFile)
    }

    @Test
    fun nestedDirectorySymlinksAreUnlinkedWithoutTouchingTargets() {
        val victim = tmp.newFolder("nested-victim")
        val keep = File(victim, "keep.bin").apply { writeText("keep") }

        val tree = tmp.newFolder("nested-tree")
        val deep = File(tree, "usr/lib/deep").apply { mkdirs() }
        Files.createSymbolicLink(File(deep, "up").toPath(), victim.toPath())
        // A relative link that also points at the victim.
        Files.createSymbolicLink(
            File(tree, "relative").toPath(),
            tree.toPath().relativize(victim.toPath())
        )

        assertTrue(tree.deleteRecursivelyAllowingReadOnly())
        assertFalse(tree.exists())
        assertTrue(keep.isFile)
    }

    @Test
    fun aLinkToTheParentOfTheTreeIsUnlinkedWithoutDeletingSiblings() {
        val parent = tmp.newFolder("parent")
        val sibling = File(parent, "sibling").apply { mkdirs() }
        val siblingFile = File(sibling, "keep.txt").apply { writeText("keep") }
        val tree = File(parent, "tree").apply { mkdirs() }
        File(tree, "payload").writeText("bytes")
        Files.createSymbolicLink(File(tree, "parent-link").toPath(), parent.toPath())

        assertTrue(tree.deleteRecursivelyAllowingReadOnly())
        assertFalse(tree.exists())
        assertTrue("a link to the parent must not delete the parent", parent.isDirectory)
        assertTrue(siblingFile.isFile)
    }

    @Test
    fun danglingAndSymlinkLoopEntriesAreRemovedAsLinks() {
        val tree = tmp.newFolder("dangling-tree")
        val dir = File(tree, "dir").apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "dangling").toPath(), File(tmp.root, "missing").toPath())
        Files.createSymbolicLink(File(dir, "loop-a").toPath(), File(dir, "loop-b").toPath())
        Files.createSymbolicLink(File(dir, "loop-b").toPath(), File(dir, "loop-a").toPath())

        assertTrue(tree.deleteRecursivelyAllowingReadOnly())
        assertFalse(tree.exists())
        assertFalse("the dangling target must not be created", File(tmp.root, "missing").exists())
    }
}
