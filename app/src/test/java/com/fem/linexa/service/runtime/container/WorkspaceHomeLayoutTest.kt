package com.fem.linexa.service.runtime.container

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-11-13 — unified Workspace home architecture.
 *
 * The reported defect was "File Manager Home opens /root even though the
 * terminal's HOME is /home/linexa". On the connected device `/etc/passwd` was
 * correct; the File Manager shortcut was hardcoded. These tests pin the shared
 * layout object that now owns the answer for every surface:
 *
 *  - resolution is identity-aware and never calls a normal user's home `/root`;
 *  - the nine directories (eight XDG + the extra `Photos`) are created
 *    idempotently and non-destructively;
 *  - `user-dirs.dirs` keeps unmanaged/custom content and normalizes only the
 *    eight managed keys;
 *  - root's home stays root's, and ambiguous `/root` data is reported but
 *    never moved.
 */
class WorkspaceHomeLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── identity-aware resolution ─────────────────────────────────────────

    /**
     * The five supported images, with the uid layout observed on the real
     * device: Arch/Debian 13 use 1001 (1000 was taken), Ubuntu/Debian 12/
     * Alpine/Fedora use 1000. Resolution must follow the account database,
     * never a hardcoded 1000.
     */
    private val distroPasswd = mapOf(
        "arch" to "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1001:1001:Linexa User:/home/linexa:/bin/bash\n",
        "debian-12" to "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n",
        "debian-13" to "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1001:1001:Linexa User:/home/linexa:/bin/bash\n",
        "ubuntu-22.04" to "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n",
        "alpine" to "root:x:0:0:root:/root:/bin/ash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/sh\n",
        "fedora" to "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
    )

    private fun rootfsWith(passwd: String): File {
        val rootfs = tmp.newFolder("rootfs-${System.nanoTime()}")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(passwd)
        return rootfs
    }

    @Test
    fun `every supported distribution resolves the passwd home, not root`() {
        for ((distro, passwd) in distroPasswd) {
            val homes = WorkspaceHomeLayout.resolveHomes(rootfsWith(passwd), "linexa", "/home/linexa")
            assertEquals("$distro user home", "/home/linexa", homes.userHome)
            assertEquals("$distro root home", "/root", homes.rootHome)
            assertEquals("$distro identity", "linexa", homes.userName)
            assertFalse("$distro is not a root identity", homes.isRootIdentity)
            assertTrue(
                "$distro must still expose /root as a separate place",
                homes.showRootHomeShortcut
            )
        }
    }

    @Test
    fun `a custom passwd home is respected`() {
        val rootfs = rootfsWith(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1042:1042:Custom:/srv/home:/usr/bin/zsh\n"
        )
        val homes = WorkspaceHomeLayout.resolveHomes(rootfs, "linexa", "/home/linexa")
        assertEquals("/srv/home", homes.userHome)
        assertEquals("/root", homes.rootHome)
    }

    @Test
    fun `a normal account pointing at root is not accepted as Home`() {
        val rootfs = rootfsWith(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/root:/bin/bash\n"
        )
        val homes = WorkspaceHomeLayout.resolveHomes(rootfs, "linexa", "/home/linexa")
        assertEquals("the root account owns /root", "/home/linexa", homes.userHome)
        assertTrue(homes.showRootHomeShortcut)
    }

    @Test
    fun `unusable normal homes fall back to the declared home`() {
        val unusable = listOf("/", "relative/home", "/home/../root", "/home/../etc", " ", "")
        for (home in unusable) {
            val rootfs = rootfsWith(
                "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:$home:/bin/bash\n"
            )
            val homes = WorkspaceHomeLayout.resolveHomes(rootfs, "linexa", "/srv/declared")
            assertEquals("home='$home' must not be used", "/srv/declared", homes.userHome)
        }
    }

    @Test
    fun `an explicit root identity resolves to root's own home`() {
        val rootfs = rootfsWith("root:x:0:0:root:/root:/bin/bash\n")
        val homes = WorkspaceHomeLayout.resolveHomes(rootfs, "root", "/home/linexa")
        assertEquals("root", homes.userName)
        assertEquals("/root", homes.userHome)
        assertEquals("/root", homes.rootHome)
        assertTrue(homes.isRootIdentity)
        assertFalse("root must not get a duplicate Root Home tile", homes.showRootHomeShortcut)
    }

    @Test
    fun `a missing passwd degrades to the declared normal home`() {
        val bare = tmp.newFolder("bare")
        val homes = WorkspaceHomeLayout.resolveHomes(bare, "linexa", "/home/linexa")
        assertEquals("/home/linexa", homes.userHome)
        assertEquals("/root", homes.rootHome)
        assertFalse(homes.isRootIdentity)
    }

    @Test
    fun `the default placeholder is the normal-user home never root`() {
        val homes = WorkspaceHomeLayout.WorkspaceHomes.default()
        assertEquals("/home/linexa", homes.userHome)
        assertEquals("linexa", homes.userName)
        assertFalse(homes.isRootIdentity)
        assertTrue(homes.showRootHomeShortcut)
    }

    // ── idempotent, non-destructive provisioning ──────────────────────────

    @Test
    fun `a fresh home gets the nine directories and the eight XDG entries`() {
        val home = tmp.newFolder("home")
        val report = WorkspaceHomeLayout.provision(home)

        assertEquals(WorkspaceHomeLayout.STANDARD_DIRECTORIES.toSet(), report.createdDirectories.toSet())
        assertTrue(report.configDirectoryCreated)
        assertTrue(report.userDirsCreated)
        assertTrue(report.changed)

        for (name in WorkspaceHomeLayout.STANDARD_DIRECTORIES) {
            assertTrue("$name must be a directory", File(home, name).isDirectory)
        }
        assertEquals(
            "Photos is extra and must never appear in XDG user dirs",
            WorkspaceHomeLayout.STANDARD_DIRECTORIES.size,
            WorkspaceHomeLayout.XDG_DIRECTORIES.size + 1
        )
        assertFalse(WorkspaceHomeLayout.XDG_DIRECTORIES.any { it.second == "Photos" })

        val userDirs = File(home, ".config/user-dirs.dirs")
        assertTrue(userDirs.isFile)
        assertTrue(
            "the written file must declare all eight canonical entries",
            WorkspaceHomeLayout.readsAsCanonicalUserDirs(userDirs.readText())
        )
        assertEquals(8, userDirs.readText().lines().count { it.startsWith("XDG_") })
    }

    @Test
    fun `a second pass is a no-op and preserves every file`() {
        val home = tmp.newFolder("home")
        WorkspaceHomeLayout.provision(home)
        File(home, "Documents/notes.txt").writeText("keep me\n")
        File(home, "Photos/2026.jpg").writeText("image\n")
        val before = File(home, ".config/user-dirs.dirs").readText()

        val report = WorkspaceHomeLayout.provision(home)

        assertFalse("a healthy home must not be rewritten", report.changed)
        assertTrue(report.userDirsUnchanged)
        assertEquals(WorkspaceHomeLayout.STANDARD_DIRECTORIES.toSet(), report.existingDirectories.toSet())
        assertEquals(before, File(home, ".config/user-dirs.dirs").readText())
        assertEquals("keep me\n", File(home, "Documents/notes.txt").readText())
        assertEquals("image\n", File(home, "Photos/2026.jpg").readText())
    }

    @Test
    fun `an existing file where a directory belongs is never replaced`() {
        val home = tmp.newFolder("home")
        File(home, "Music").writeText("not a directory\n")

        val report = WorkspaceHomeLayout.provision(home)

        assertTrue(report.blockedEntries.contains("Music"))
        assertTrue("the payload must survive", File(home, "Music").isFile)
        assertEquals("not a directory\n", File(home, "Music").readText())
        assertFalse(report.createdDirectories.contains("Music"))
        // The other eight are still created: one blocked entry is not fatal.
        assertTrue(File(home, "Documents").isDirectory)
    }

    @Test
    fun `symlinked entries are not followed or modified`() {
        val home = tmp.newFolder("home")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(home, "Videos").toPath(), outside.toPath())

        val report = WorkspaceHomeLayout.provision(home)

        assertTrue(report.blockedEntries.contains("Videos"))
        assertTrue("the link must remain a link", Files.isSymbolicLink(File(home, "Videos").toPath()))
        assertEquals("the link target must stay empty", 0, outside.listFiles()?.size ?: -1)
    }

    @Test
    fun `a file named config blocks XDG writing without failing`() {
        val home = tmp.newFolder("home")
        File(home, ".config").writeText("a file, not a directory\n")

        val report = WorkspaceHomeLayout.provision(home)

        assertTrue(report.blockedEntries.contains(".config"))
        assertTrue(File(home, ".config").isFile)
        assertFalse(File(home, ".config/user-dirs.dirs").exists())
    }

    @Test
    fun `a symlinked user-dirs file is left untouched`() {
        val home = tmp.newFolder("home")
        val config = File(home, ".config").apply { mkdirs() }
        val outside = File(tmp.newFolder("outside"), "user-dirs.dirs")
        outside.writeText("DO NOT TOUCH\n")
        Files.createSymbolicLink(File(config, "user-dirs.dirs").toPath(), outside.toPath())

        val report = WorkspaceHomeLayout.provision(home)

        assertTrue(report.blockedEntries.contains(".config/user-dirs.dirs"))
        assertEquals("DO NOT TOUCH\n", outside.readText())
        assertTrue(Files.isSymbolicLink(File(config, "user-dirs.dirs").toPath()))
    }

    @Test
    fun `a missing home is refused rather than created implicitly`() {
        val missing = File(tmp.root, "does-not-exist")
        val report = WorkspaceHomeLayout.provision(missing)
        assertTrue(report.changed.not())
        assertTrue(report.blockedEntries.isNotEmpty())
        assertFalse(missing.exists())
    }

    @Test
    fun `created directories are 0755 and user-dirs is 0644`() {
        val home = tmp.newFolder("home")
        WorkspaceHomeLayout.provision(home)

        for (name in WorkspaceHomeLayout.STANDARD_DIRECTORIES) {
            assertEquals(
                "$name mode",
                WorkspaceHomeLayout.DIRECTORY_MODE,
                Files.getPosixFilePermissions(File(home, name).toPath())
            )
        }
        assertEquals(
            WorkspaceHomeLayout.DIRECTORY_MODE,
            Files.getPosixFilePermissions(File(home, ".config").toPath())
        )
        assertEquals(
            WorkspaceHomeLayout.USER_DIRS_MODE,
            Files.getPosixFilePermissions(File(home, ".config/user-dirs.dirs").toPath())
        )
    }

    // ── XDG merge policy ──────────────────────────────────────────────────

    @Test
    fun `the merge preserves comments and unmanaged keys`() {
        val existing = buildString {
            append("# my own comment\n")
            append("XDG_DOWNLOAD_DIR=\"\$HOME/\"\n")
            append("XDG_TEMPLATES_DIR=\"/srv/templates\"\n")
            append("GTK_THEME=\"Adwaita\"\n")
            append("XDG_MUSIC_DIR=\"\$HOME/Music\"\n")
        }

        val merged = WorkspaceHomeLayout.mergeUserDirs(existing)

        assertTrue(merged.contains("# my own comment"))
        assertTrue(merged.contains("GTK_THEME=\"Adwaita\""))
        assertTrue("the managed Download key is repaired", merged.contains("XDG_DOWNLOAD_DIR=\"\$HOME/Downloads\""))
        assertTrue("the managed Templates key is canonical", merged.contains("XDG_TEMPLATES_DIR=\"\$HOME/Templates\""))
        assertTrue(merged.contains("XDG_MUSIC_DIR=\"\$HOME/Music\""))
        assertTrue(WorkspaceHomeLayout.readsAsCanonicalUserDirs(merged))
    }

    @Test
    fun `duplicate managed keys collapse to the first, normalized`() {
        val existing = "XDG_MUSIC_DIR=\"\$HOME/First\"\nXDG_MUSIC_DIR=\"\$HOME/Second\"\n"
        val merged = WorkspaceHomeLayout.mergeUserDirs(existing)

        assertEquals(
            "exactly one managed Music line survives",
            1,
            merged.lines().count { it.startsWith("XDG_MUSIC_DIR=") }
        )
        assertTrue(merged.contains("XDG_MUSIC_DIR=\"\$HOME/Music\""))
        assertFalse(merged.contains("Second"))
        assertFalse(merged.contains("First"))
    }

    @Test
    fun `missing keys are appended in canonical order`() {
        val merged = WorkspaceHomeLayout.mergeUserDirs("CUSTOM=1\n")
        val keys = merged.lines().filter { it.startsWith("XDG_") }.map { it.substringBefore('=') }
        assertEquals(WorkspaceHomeLayout.XDG_DIRECTORIES.map { it.first }, keys)
    }

    @Test
    fun `an already canonical file is byte-identical after a merge`() {
        val canonical = WorkspaceHomeLayout.renderUserDirs()
        assertEquals(canonical, WorkspaceHomeLayout.mergeUserDirs(canonical))
    }

    @Test
    fun `provision repairs an existing XDG file in place`() {
        val home = tmp.newFolder("home")
        File(home, ".config").mkdirs()
        val target = File(home, ".config/user-dirs.dirs")
        target.writeText("# keep\nXDG_DOWNLOAD_DIR=\"\$HOME/\"\n")

        val report = WorkspaceHomeLayout.provision(home)

        assertTrue(report.userDirsUpdated)
        assertFalse(report.userDirsCreated)
        val text = target.readText()
        assertTrue(text.contains("# keep"))
        assertTrue(WorkspaceHomeLayout.readsAsCanonicalUserDirs(text))
        assertEquals(
            WorkspaceHomeLayout.USER_DIRS_MODE,
            Files.getPosixFilePermissions(target.toPath())
        )
    }

    @Test
    fun `renderUserDirs declares Photos nowhere`() {
        val rendered = WorkspaceHomeLayout.renderUserDirs()
        assertFalse(rendered.contains("Photos"))
        assertTrue(rendered.contains("XDG_PICTURES_DIR=\"\$HOME/Pictures\""))
    }

    // ── /root audit (report, never migrate) ───────────────────────────────

    @Test
    fun `unexpected root data is reported but stock dotfiles are ignored`() {
        val rootfs = tmp.newFolder("rootfs")
        val root = File(rootfs, "root")
        root.mkdirs()
        File(root, ".bashrc").writeText("# stock\n")
        File(root, ".profile").writeText("# stock\n")
        File(root, ".ssh").mkdirs()
        File(root, "project-notes.txt").writeText("possibly user data\n")
        File(root, "backup.tar").writeText("possibly user data\n")

        val unexpected = WorkspaceHomeLayout.unexpectedRootHomeEntries(rootfs)

        assertEquals(listOf("backup.tar", "project-notes.txt"), unexpected)
        assertEquals("nothing may be moved or deleted", "possibly user data\n", File(root, "project-notes.txt").readText())
    }

    @Test
    fun `a missing root home has nothing to report`() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(WorkspaceHomeLayout.unexpectedRootHomeEntries(rootfs).isEmpty())
    }

    @Test
    fun `root and normal homes are independent`() {
        val rootfs = rootfsWith(
            "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
        )
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/root-only.txt").writeText("root\n")
        val home = File(rootfs, "home/linexa").apply { mkdirs() }

        WorkspaceHomeLayout.provision(home)

        val homes = WorkspaceHomeLayout.resolveHomes(rootfs, "linexa", "/home/linexa")
        assertNotEquals(homes.userHome, homes.rootHome)
        assertFalse("a normal home must not appear under /root", File(rootfs, "root/Desktop").exists())
        assertFalse("root data must not appear in the user home", File(home, "root-only.txt").exists())
        assertEquals("root\n", File(rootfs, "root/root-only.txt").readText())
    }
}