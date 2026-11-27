package com.fem.linexa.service.runtime

import android.app.Application
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * P0-2 / P0-3 (2026-11-23 performance remediation): real-tree measurements
 * behind [WorkspaceFileManager].
 *
 *  - one workspace-base walk answers both totals (no second recursive walk),
 *  - symlinks are counted once and never followed,
 *  - invalidation after promotion/replacement observes the new tree,
 *  - the readiness probe is short-circuiting and rejects directory-only and
 *    symlink-only roots.
 */
class WorkspaceFileManagerSizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: WorkspaceFileManager
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val app = Mockito.mock(Application::class.java)
        filesDir = tmp.newFolder("files")
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        manager = WorkspaceFileManager(app)
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `rootfs and workspace totals come from the same tree`() {
        val id = "ws-sizes"
        val base = manager.getWorkspaceBasePath(id).apply { mkdirs() }
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "bin/sh"), 10)
        write(File(rootfs, "usr/lib/libc.so"), 20)
        write(File(base, "config.yaml"), 7)
        write(File(base, "logs/install.log"), 3)

        assertEquals(30L, manager.getRootfsSize(id))
        assertEquals(40L, manager.getWorkspaceSize(id))
        assertEquals(30L, manager.getRootfsSize(id))
    }

    @Test
    fun `symlinks are not followed or double counted`() {
        val id = "ws-links"
        val base = manager.getWorkspaceBasePath(id).apply { mkdirs() }
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "usr/bin/tool"), 12)
        val outside = tmp.newFolder("outside")
        write(File(outside, "huge.bin"), 500)
        try {
            Files.createSymbolicLink(File(rootfs, "bin").toPath(), File("usr/bin").toPath())
            Files.createSymbolicLink(File(rootfs, "escape").toPath(), outside.toPath())
        } catch (_: Exception) {
            return // fixture filesystem cannot create symlinks
        }

        assertEquals("only the real file counts", 12L, manager.getRootfsSize(id))
        assertEquals("links are skipped in the workspace total too", 12L, manager.getWorkspaceSize(id))
    }

    @Test
    fun `invalidation observes a replaced rootfs`() {
        val id = "ws-replace"
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "old.bin"), 4)
        assertEquals(4L, manager.getRootfsSize(id))

        manager.invalidateRootfsSize(id)
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        write(File(rootfs, "new.bin"), 9)

        assertEquals("a new generation must measure the new tree", 9L, manager.getRootfsSize(id))
    }

    @Test
    fun `activate staged rootfs invalidates the old measurement`() {
        val id = "ws-activate"
        val base = manager.getWorkspaceBasePath(id).apply { mkdirs() }
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "old.bin"), 4)
        assertEquals(4L, manager.getRootfsSize(id))

        val staging = manager.getStagingRootfsPath(id).apply { mkdirs() }
        write(File(staging, "new.bin"), 15)
        assertTrue(manager.activateStagedRootfs(id))

        assertEquals(15L, manager.getRootfsSize(id))
        assertEquals(15L, manager.getWorkspaceSize(id))
    }

    @Test
    fun `missing directories report zero`() {
        assertEquals(0L, manager.getRootfsSize("absent"))
        assertEquals(0L, manager.getWorkspaceSize("absent"))
        assertFalse(manager.hasAnyRegularFile("absent"))
    }

    @Test
    fun `hasAnyRegularFile is false for directories only`() {
        val id = "ws-dirs"
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        File(rootfs, "etc").mkdirs()
        File(rootfs, "usr/bin").mkdirs()

        assertFalse(manager.hasAnyRegularFile(id))
    }

    @Test
    fun `hasAnyRegularFile is false for symlinks only`() {
        val id = "ws-symlinks"
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        val target = tmp.newFile("target")
        try {
            Files.createSymbolicLink(File(rootfs, "link").toPath(), target.toPath())
        } catch (_: Exception) {
            return
        }
        assertFalse(manager.hasAnyRegularFile(id))
    }

    @Test
    fun `hasAnyRegularFile finds a real file anywhere in the tree`() {
        val id = "ws-file"
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "usr/lib/deep/thing.so"), 1)
        assertTrue(manager.hasAnyRegularFile(id))
    }

    @Test
    fun `hasAnyRegularFile rejects a symlinked rootfs`() {
        val id = "ws-linked-rootfs"
        val base = manager.getWorkspaceBasePath(id).apply { mkdirs() }
        val real = tmp.newFolder("real-rootfs")
        write(File(real, "bin/sh"), 1)
        try {
            Files.createSymbolicLink(File(base, "rootfs").toPath(), real.toPath())
        } catch (_: Exception) {
            return
        }
        assertFalse(manager.hasAnyRegularFile(id))
    }

    @Test
    fun `hasAnyRegularFile remains true past an unreadable subtree`() {
        val id = "ws-perms"
        val rootfs = manager.getRootfsPath(id).apply { mkdirs() }
        write(File(rootfs, "readable.bin"), 2)
        val denied = File(rootfs, "denied").apply { mkdirs() }
        write(File(denied, "secret.bin"), 2)
        org.junit.Assume.assumeTrue("fixture must support denying read", denied.setReadable(false, false))
        try {
            assertTrue("an unreadable sibling must not hide the readable file", manager.hasAnyRegularFile(id))
        } finally {
            denied.setReadable(true, false)
        }
    }
}
