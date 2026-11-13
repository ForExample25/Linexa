package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.WorkspaceHomeLayout
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * 2026-11-13 — unified Workspace home architecture, end-to-end through the
 * installer's account fixup.
 *
 * The device inspection that prompted this work found `/etc/passwd` already
 * correct on every installed distribution (`linexa`, uid 1000 or 1001,
 * `/home/linexa`); the defect was the File Manager hardcoding `/root`. These
 * tests still pin the installer side so the same layout is guaranteed for
 * fresh installs and repaired idempotently on existing workspaces:
 *
 *  - the five supported distributions keep their real uid/gid/home;
 *  - the nine directories and eight XDG entries exist after provisioning;
 *  - existing/custom homes are respected, a normal home pointing at `/root`
 *    is the only passwd value repaired;
 *  - root's account and `/root` stay untouched, and ambiguous `/root` data is
 *    reported — never migrated;
 *  - repeated calls (the restart path) do not rewrite or destroy anything.
 */
class GuestRootfsInitializerHomeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var initializer: GuestRootfsInitializer

    private data class Distro(val label: String, val uid: Int, val shell: String)

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = newInitializer()
    }

    /** A fresh instance, as a process restart would construct. */
    private fun newInitializer() =
        GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))

    private fun rootfs(
        passwd: String = "root:x:0:0:root:/root:/bin/bash\n",
        group: String = "root:x:0:\nsudo:x:27:\n",
        shadow: String? = "root:*:19000:0:99999:7:::\n",
        withSkel: Boolean = true
    ): File {
        val rootfs = tmp.newFolder("rootfs-${System.nanoTime()}")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(passwd)
        File(rootfs, "etc/group").writeText(group)
        shadow?.let { File(rootfs, "etc/shadow").writeText(it) }
        if (withSkel) {
            File(rootfs, "etc/skel").mkdirs()
            File(rootfs, "etc/skel/.bashrc").writeText("# stock bashrc\n")
        }
        return rootfs
    }

    private fun assertHealthyHome(home: File) {
        for (name in WorkspaceHomeLayout.STANDARD_DIRECTORIES) {
            assertTrue("$name must exist under ${home.path}", File(home, name).isDirectory)
        }
        val userDirs = File(home, ".config/user-dirs.dirs")
        assertTrue("user-dirs.dirs must exist", userDirs.isFile)
        assertTrue(
            "the eight XDG entries must be canonical",
            WorkspaceHomeLayout.readsAsCanonicalUserDirs(userDirs.readText())
        )
        assertEquals(
            "exactly eight XDG entries",
            8,
            userDirs.readText().lines().count { it.startsWith("XDG_") }
        )
        assertFalse("Photos is extra, never XDG", userDirs.readText().contains("Photos"))
    }

    // ── the five supported distributions ──────────────────────────────────

    @Test
    fun `all supported distributions keep their real uid and home`() {
        val distros = listOf(
            Distro("Arch Linux ARM", 1001, "/bin/bash"),
            Distro("Debian 12", 1000, "/bin/bash"),
            Distro("Debian 13", 1001, "/bin/bash"),
            Distro("Ubuntu 22.04", 1000, "/bin/bash"),
            Distro("Alpine", 1000, "/bin/sh"),
            Distro("Fedora 44", 1000, "/bin/bash")
        )

        for (distro in distros) {
            val rootfs = rootfs(
                passwd = "root:x:0:0:root:/root:/bin/bash\n" +
                    "linexa:x:${distro.uid}:${distro.uid}:Linexa User:/home/linexa:${distro.shell}\n"
            )

            val identity = initializer.ensureGuestUser(rootfs, "ws-${distro.label}", "linexa", "/home/linexa")

            assertEquals("${distro.label} user", "linexa", identity.name)
            assertEquals("${distro.label} uid", distro.uid, identity.uid)
            assertEquals("${distro.label} gid", distro.uid, identity.gid)
            assertEquals("${distro.label} home", "/home/linexa", identity.home)
            assertEquals("${distro.label} shell", distro.shell, identity.shell)
            assertFalse(identity.isRoot)

            assertHealthyHome(File(rootfs, "home/linexa"))
            // The account database is authoritative: no duplicate record.
            assertEquals(
                "${distro.label} passwd must not gain a duplicate",
                1,
                File(rootfs, "etc/passwd").readText().lines().count { it.startsWith("linexa:") }
            )
            // Root's record is never rewritten by normal-user provisioning.
            assertTrue(
                File(rootfs, "etc/passwd").readText().contains("root:x:0:0:root:/root:/bin/bash")
            )
        }
    }

    @Test
    fun `a fresh image gets the account and a complete home`() {
        val rootfs = rootfs()

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals(1000, identity.uid)
        assertHealthyHome(File(rootfs, "home/linexa"))
        // The skel seed still happens for a brand-new home.
        assertEquals("# stock bashrc\n", File(rootfs, "home/linexa/.bashrc").readText())
    }

    @Test
    fun `uid 1000 already taken shifts the account and home provisioning still works`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nubuntu:x:1000:1000::/home/ubuntu:/bin/bash\n",
            group = "root:x:0:\nubuntu:x:1000:\n"
        )

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("Linux images may ship uid 1000; Linexa must not hardcode it", 1001, identity.uid)
        assertHealthyHome(File(rootfs, "home/linexa"))
    }

    // ── existing workspaces and custom homes ──────────────────────────────

    @Test
    fun `existing user data survives provisioning`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
        )
        val documents = File(rootfs, "home/linexa/Documents").apply { mkdirs() }
        File(documents, "notes.txt").writeText("do not lose me\n")
        File(rootfs, "home/linexa/Pictures").mkdirs()
        File(rootfs, "home/linexa/Pictures/photo.jpg").writeText("jpeg\n")
        File(rootfs, "home/linexa/.bashrc").writeText("# user bashrc\n")

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("do not lose me\n", File(documents, "notes.txt").readText())
        assertEquals("jpeg\n", File(rootfs, "home/linexa/Pictures/photo.jpg").readText())
        assertEquals("# user bashrc\n", File(rootfs, "home/linexa/.bashrc").readText())
        assertHealthyHome(File(rootfs, "home/linexa"))
    }

    @Test
    fun `an existing XDG file keeps custom lines and repairs only managed keys`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
        )
        val config = File(rootfs, "home/linexa/.config").apply { mkdirs() }
        File(config, "user-dirs.dirs").writeText(
            "# user comment\nXDG_DOWNLOAD_DIR=\"\$HOME/\"\nMY_CUSTOM_DIR=\"/srv/custom\"\n"
        )

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val text = File(config, "user-dirs.dirs").readText()
        assertTrue(text.contains("# user comment"))
        assertTrue(text.contains("MY_CUSTOM_DIR=\"/srv/custom\""))
        assertTrue(text.contains("XDG_DOWNLOAD_DIR=\"\$HOME/Downloads\""))
        assertTrue(WorkspaceHomeLayout.readsAsCanonicalUserDirs(text))
    }

    @Test
    fun `a custom passwd home is provisioned there and home-linexa is not invented`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1042:1042:Custom:/srv/home:/usr/bin/zsh\n"
        )

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("/srv/home", identity.home)
        assertHealthyHome(File(rootfs, "srv/home"))
        assertFalse(
            "the declared default must not be created when the account chose another home",
            File(rootfs, "home/linexa").exists()
        )
    }

    // ── the one repaired passwd value, and root isolation ─────────────────

    @Test
    fun `a normal account pointing at root is repaired and root data stays put`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/root:/bin/bash\n"
        )
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/.bashrc").writeText("# stock root bashrc\n")
        File(rootfs, "root/important.txt").writeText("root-owned user data\n")

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("the normal account must not share root's home", "/home/linexa", identity.home)
        val passwd = File(rootfs, "etc/passwd").readText()
        assertTrue(passwd.contains("linexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash"))
        assertTrue("root's own record is untouched", passwd.contains("root:x:0:0:root:/root:/bin/bash"))
        assertHealthyHome(File(rootfs, "home/linexa"))

        // Nothing was moved out of /root, and no user dirs were planted there.
        assertEquals("root-owned user data\n", File(rootfs, "root/important.txt").readText())
        assertEquals("# stock root bashrc\n", File(rootfs, "root/.bashrc").readText())
        assertFalse(File(rootfs, "root/Desktop").exists())
        assertFalse(File(rootfs, "root/.config/user-dirs.dirs").exists())
    }

    @Test
    fun `ambiguous root data is reported and left in place`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
        )
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/.bashrc").writeText("# stock\n")
        File(rootfs, "root/.ssh").mkdirs()
        File(rootfs, "root/maybe-user-data.txt").writeText("keep\n")

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        val unexpected = initializer.reportAmbiguousRootHomeData(rootfs, "ws", identity)

        assertEquals(listOf("maybe-user-data.txt"), unexpected)
        assertEquals("keep\n", File(rootfs, "root/maybe-user-data.txt").readText())
        assertFalse(File(rootfs, "home/linexa/maybe-user-data.txt").exists())
    }

    @Test
    fun `an explicit root workspace stays entirely on root`() {
        val rootfs = rootfs()
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/keep.txt").writeText("root\n")
        val passwdBefore = File(rootfs, "etc/passwd").readText()

        val identity = initializer.ensureGuestUser(rootfs, "ws", "root", "/root")

        assertTrue(identity.isRoot)
        assertEquals(0, identity.uid)
        assertEquals("/root", identity.home)
        assertEquals("root's account file must not be modified", passwdBefore, File(rootfs, "etc/passwd").readText())
        assertFalse("a root workspace creates no normal user home", File(rootfs, "home/linexa").exists())
        assertFalse("no XDG provisioning for root", File(rootfs, "root/.config/user-dirs.dirs").exists())
        assertFalse(File(rootfs, "root/Desktop").exists())
        assertEquals("root\n", File(rootfs, "root/keep.txt").readText())
    }

    @Test
    fun `a normal workspace never creates anything under root`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash\n"
        )
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/.bashrc").writeText("# stock\n")
        val rootEntriesBefore = File(rootfs, "root").list()!!.toSet()

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals(
            "normal-user provisioning must not add anything to /root",
            rootEntriesBefore,
            File(rootfs, "root").list()!!.toSet()
        )
        assertNotEquals(
            File(rootfs, "home/linexa").absolutePath,
            File(rootfs, "root").absolutePath
        )
    }

    // ── permissions, idempotency, restart persistence ─────────────────────

    @Test
    fun `provisioned modes are 0755 for directories and 0644 for user-dirs`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val home = File(rootfs, "home/linexa")
        for (name in WorkspaceHomeLayout.STANDARD_DIRECTORIES) {
            assertEquals(
                "$name mode",
                WorkspaceHomeLayout.DIRECTORY_MODE,
                Files.getPosixFilePermissions(File(home, name).toPath())
            )
        }
        assertEquals(
            WorkspaceHomeLayout.USER_DIRS_MODE,
            Files.getPosixFilePermissions(File(home, ".config/user-dirs.dirs").toPath())
        )
    }

    @Test
    fun `a restart re-run is idempotent and preserves data and XDG content`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        val home = File(rootfs, "home/linexa")
        File(home, "Downloads/report.pdf").writeText("pdf\n")
        File(home, ".bashrc").writeText("# custom\n")
        val userDirsBefore = File(home, ".config/user-dirs.dirs").readText()

        // A new process constructs a new initializer and runs the same path.
        val identity = newInitializer().ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("/home/linexa", identity.home)
        assertEquals("pdf\n", File(home, "Downloads/report.pdf").readText())
        assertEquals("# custom\n", File(home, ".bashrc").readText())
        assertEquals(userDirsBefore, File(home, ".config/user-dirs.dirs").readText())
        assertHealthyHome(home)
        assertEquals(
            "no duplicate account after restart",
            1,
            File(rootfs, "etc/passwd").readText().lines().count { it.startsWith("linexa:") }
        )
    }

    @Test
    fun `the nine directories are exactly the documented set`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val names = File(rootfs, "home/linexa").list()!!.toSet()
        assertEquals(
            WorkspaceHomeLayout.STANDARD_DIRECTORIES.toSet() +
                setOf(".config", ".bashrc"),
            names
        )
        assertTrue("Pictures must not be replaced by Photos", "Pictures" in names && "Photos" in names)
    }
}