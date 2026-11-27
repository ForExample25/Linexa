package com.fem.linexa.domain.filemanager

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-003 — the host-side read path must be fast AND unable to escape the
 * workspace rootfs through absolute symlinks, relative `..`, or cycles.
 */
class HostFileAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun access(vararg excluded: String): Pair<HostFileAccess, File> {
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        return HostFileAccess(RootfsView(rootfs, excluded.toList())) to rootfs
    }

    private fun File.write(relative: String, content: String): File =
        File(this, relative).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    @Test
    fun `list is sorted, includes dotfiles, and reports metadata`() {
        val (access, rootfs) = access()
        rootfs.write("b.txt", "bb")
        rootfs.write("a.txt", "a")
        rootfs.write(".hidden", "")

        val entries = access.list("/")!!

        assertEquals(listOf(".hidden", "a.txt", "b.txt"), entries.map { it.name })
        val a = entries.first { it.name == "a.txt" }
        assertEquals("/a.txt", a.path)
        assertEquals(1L, a.size)
        assertFalse(a.isDirectory)
        assertTrue(a.permissions.startsWith("-"))
        assertEquals(10, a.permissions.length)
    }

    @Test
    fun `read preserves trailing whitespace and does not trim`() {
        val (access, rootfs) = access()
        rootfs.write("notes.txt", "line\n\n\n")

        assertEquals("line\n\n\n", access.read("/notes.txt"))
    }

    @Test
    fun `an absolute symlink is re-anchored at the rootfs, never the host`() {
        val (access, rootfs) = access()
        rootfs.write("opt/real/payload.txt", "inside")
        Files.createSymbolicLink(File(rootfs, "link").toPath(), Paths.get("/opt/real"))

        assertEquals("inside", access.read("/link/payload.txt"))
        val listed = access.list("/link")!!.map { it.name }
        assertEquals(listOf("payload.txt"), listed)
    }

    @Test
    fun `dot-dot cannot climb out of the rootfs`() {
        val (access, rootfs) = access()
        rootfs.write("etc/passwd", "rootfs-passwd")

        // Normalizes to "/etc/passwd" at the guest root.
        assertEquals("rootfs-passwd", access.read("/../../etc/passwd"))
    }

    @Test
    fun `a relative symlink with dot-dot is clamped at the root`() {
        val (access, rootfs) = access()
        rootfs.write("etc/passwd", "rootfs-passwd")
        Files.createSymbolicLink(File(rootfs, "up").toPath(), Paths.get("../../etc/passwd"))

        assertEquals("rootfs-passwd", access.read("/up"))
    }

    @Test
    fun `a symlink cycle falls back instead of looping`() {
        val (access, rootfs) = access()
        Files.createSymbolicLink(File(rootfs, "a").toPath(), Paths.get("/b"))
        Files.createSymbolicLink(File(rootfs, "b").toPath(), Paths.get("/a"))

        assertNull(access.read("/a"))
        assertTrue(access.stat("/a")!!.isSymlink)
    }

    @Test
    fun `bind-mounted prefixes are never served from the host`() {
        val (access, rootfs) = access("/mnt/shared")
        rootfs.write("mnt/shared/visible-to-host.txt", "stale")

        assertNull(access.list("/mnt/shared"))
        assertNull(access.read("/mnt/shared/visible-to-host.txt"))
    }

    @Test
    fun `stat reports a symlink itself rather than its target`() {
        val (access, rootfs) = access()
        rootfs.write("target.txt", "12345")
        Files.createSymbolicLink(File(rootfs, "link").toPath(), Paths.get("target.txt"))

        val info = access.stat("/link")!!

        assertTrue(info.isSymlink)
        assertEquals("target.txt", info.linkTarget)
        assertEquals("/link", info.path)
    }

    @Test
    fun `a dangling symlink exists but cannot be read`() {
        val (access, rootfs) = access()
        Files.createSymbolicLink(File(rootfs, "dangling").toPath(), Paths.get("/nowhere"))

        assertEquals(true, access.exists("/dangling"))
        assertNull(access.read("/dangling"))
    }

    @Test
    fun `size is exact for regular files and deferred for directories`() {
        val (access, rootfs) = access()
        rootfs.write("data.bin", "1234567")
        File(rootfs, "dir").mkdirs()

        assertEquals(7L, access.size("/data.bin"))
        assertNull(access.size("/dir"))
    }

    @Test
    fun `root stat has the guest name, not the host directory name`() {
        val (access, _) = access()

        val info = access.stat("/")!!

        assertEquals("/", info.name)
        assertEquals("/", info.path)
        assertTrue(info.isDirectory)
    }

    @Test
    fun `a missing rootfs disables the fast path entirely`() {
        val access = HostFileAccess(RootfsView(File(tmp.root, "absent")))

        assertFalse(access.available)
        assertNull(access.list("/"))
    }

    @Test
    fun `a relative path is not resolved against the host`() {
        val (access, _) = access()

        assertNull(access.list("etc"))
        assertNull(access.read("etc/passwd"))
    }

    @Test
    fun `an empty directory is an empty list, not a fallback`() {
        val (access, rootfs) = access()
        File(rootfs, "empty").mkdirs()

        assertNotNull(access.list("/empty"))
        assertTrue(access.list("/empty")!!.isEmpty())
    }
}
