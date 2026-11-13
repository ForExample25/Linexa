package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.GuestIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Issue #1 (2026-09-03): the host-side `linexa` account bootstrap —
 * [GuestRootfsInitializer.ensureGuestUser]. Everything here is plain file
 * I/O on a fake rootfs, exactly like the production path (which never runs
 * guest commands for account work either).
 */
class GuestRootfsInitializerUserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var initializer: GuestRootfsInitializer

    @Before
    fun setUp() {
        // Same construction shape as GuestRootfsInitializerWorkspaceTest:
        // ensureGuestUser performs only host-side file work, so no bootstrap
        // binary is ever resolved on this path.
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
    }

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

    @Test
    fun `a clean debian image gets a linexa account at uid 1000`() {
        val rootfs = rootfs()
        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals("linexa", identity.name)
        assertEquals(1000, identity.uid)
        assertEquals(1000, identity.gid)
        assertEquals("/home/linexa", identity.home)
        assertEquals("/bin/bash", identity.shell)
        assertFalse(identity.isRoot)

        val passwd = File(rootfs, "etc/passwd").readText()
        assertTrue(passwd.contains("linexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash"))
        val shadow = File(rootfs, "etc/shadow").readText()
        assertTrue("the account password must be locked (no password login)", shadow.contains("linexa:*:"))
        val group = File(rootfs, "etc/group").readText()
        assertTrue(group.contains("linexa:x:1000:"))
        assertTrue("the account must join the distro's sudo group", group.contains("sudo:x:27:linexa"))
    }

    @Test
    fun `an existing account is resolved with its own record`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1042:1042:Custom:/srv/home:/usr/bin/zsh\n"
        )
        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals(1042, identity.uid)
        assertEquals("/srv/home", identity.home)
        assertEquals("/usr/bin/zsh", identity.shell)
        // The passwd must NOT be duplicated.
        assertEquals(1, File(rootfs, "etc/passwd").readText().lines().count { it.startsWith("linexa:") })
    }

    @Test
    fun `a taken uid 1000 shifts to the next free pair`() {
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nubuntu:x:1000:1000::/home/ubuntu:/bin/bash\n",
            group = "root:x:0:\nubuntu:x:1000:\n"
        )
        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        assertEquals(1001, identity.uid)
        assertEquals(1001, identity.gid)
    }

    @Test
    fun `the home directory is created with skel seeds and NO linexa project dir`() {
        // Issue #2 (2026-09-04): the ~/linexa project directory is GONE —
        // the terminal opens in the home itself, so a "linexa" folder inside
        // the linexa home must no longer be created.
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val home = File(rootfs, "home/linexa")
        assertTrue(home.isDirectory)
        assertTrue("skel dotfiles seed the home", File(home, ".bashrc").isFile)
        assertFalse(
            "the ~/linexa project directory must NOT be created",
            File(home, "linexa").exists()
        )
    }

    @Test
    fun `a stale app-owned linexa project dir is swept away`() {
        // Issue #2 migration: workspaces provisioned by older builds carry a
        // ~/linexa holding only the managed README + shell history — removed.
        val rootfs = rootfs()
        val stale = File(rootfs, "home/linexa/linexa")
        stale.mkdirs()
        File(stale, "README.md").writeText("old managed readme\n")
        File(stale, ".bash_history").writeText("ls\n")

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertFalse("the stale project directory must be removed", stale.exists())
    }

    @Test
    fun `a stale linexa project dir with user content is preserved`() {
        // The migration must never destroy real user files: anything beyond
        // the app/session-owned set keeps the folder in place.
        val rootfs = rootfs()
        val stale = File(rootfs, "home/linexa/linexa")
        stale.mkdirs()
        File(stale, "README.md").writeText("old managed readme\n")
        File(stale, "my-notes.txt").writeText("user content\n")

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertTrue("user content keeps the folder", stale.isDirectory)
        assertTrue(File(stale, "my-notes.txt").isFile)
    }

    @Test
    fun `the privilege bridge is provisioned and executable`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val sudo = File(rootfs, "usr/local/bin/sudo")
        val helper = File(rootfs, "usr/local/bin/linexa-root-exec")
        assertTrue("the sudo wrapper must exist", sudo.isFile)
        assertTrue("the protocol helper must exist", helper.isFile)

        val sudoScript = sudo.readText()
        val helperScript = helper.readText()
        assertTrue("the wrapper must be a real script", sudoScript.startsWith("#!/bin/sh"))
        assertTrue("the wrapper must document itself honestly", sudoScript.contains("Linexa sudo"))
        assertTrue("the wrapper must exec the helper", sudoScript.contains("exec \"\$helper\""))
        assertTrue("the helper must document the protocol", helperScript.contains("Linexa root-exec protocol helper"))
        assertTrue("the helper must name the root-emulated context", helperScript.contains("root-emulated"))
        assertTrue("the helper must use the workspace mailbox", helperScript.contains("MARKER_DIR=/run/linexa-root"))
        assertTrue("the helper must name the bridge marker", helperScript.contains("MARKER=\$MARKER_DIR/bridge"))
        assertTrue("the helper must reject interactive sudo with guidance", helperScript.contains("Apps → Root shell"))

        val perms = java.nio.file.Files.getPosixFilePermissions(sudo.toPath())
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE))
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE))
        assertTrue(
            java.nio.file.Files.getPosixFilePermissions(helper.toPath())
                .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
        )
    }

    /**
     * 2026-11-13 (reported "dpkg: error: requested operation requires
     * superuser privilege"): the old bridge ran the command in the caller's
     * emulated identity and printed a warning. The new bridge must actually
     * route through the app's root-emulated mailbox — and must refuse the two
     * options it cannot honour (interactive shell and non-root identity)
     * rather than silently running something else.
     */
    @Test
    fun `the privilege helper routes through the root bridge and rejects unsupported options`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        val helper = File(rootfs, "usr/local/bin/linexa-root-exec").readText()
        // The command is handed to the bridge, not exec'd as the caller.
        assertTrue(helper.contains(".linexa-root-req."))
        assertTrue(helper.contains("quote_arg"))
        assertTrue(helper.contains("root bridge did not accept the request"))
        // Interactive sudo is refused with the Root Shell alternative.
        assertTrue(helper.contains("interactive root shell cannot run"))
        // Non-root identity switches are refused, not silently ignored.
        assertTrue(helper.contains("only offers the emulated root context"))
        assertTrue(helper.contains("is_root_identity"))
        // Already-root sessions execute directly (Root Shell's sudo works).
        assertTrue(helper.contains("uid=\$(id -u"))
        // Safe words stay bare so `apt` remains the classifier's first token.
        assertTrue(helper.contains("is_safe_word"))
        assertTrue(helper.contains("[!A-Za-z0-9_./:=+@%,^-]"))
        // A dead app process is distinguished from a long package operation.
        assertTrue(helper.contains("HEARTBEAT=\$MARKER_DIR/heartbeat"))
        assertTrue(helper.contains("root bridge stopped responding"))
    }

    @Test
    fun `re-provisioning the privilege bridge is byte-idempotent`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        val sudo = File(rootfs, "usr/local/bin/sudo")
        val helper = File(rootfs, "usr/local/bin/linexa-root-exec")
        val beforeSudo = sudo.readText()
        val beforeHelper = helper.readText()
        val helperMtime = helper.lastModified()

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        assertEquals(beforeSudo, sudo.readText())
        assertEquals(beforeHelper, helper.readText())
        assertEquals("an unchanged helper must not be rewritten", helperMtime, helper.lastModified())
    }

    @Test
    fun `root preference keeps the root identity`() {
        val rootfs = rootfs()
        val identity = initializer.ensureGuestUser(rootfs, "ws", "root", "/root")
        assertTrue(identity.isRoot)
        assertEquals(0, identity.uid)
        // Nothing was appended.
        assertEquals(1, File(rootfs, "etc/passwd").readText().lines().count { it.contains(":") && it.isNotBlank() } - 1 + 1)
    }

    @Test
    fun `missing passwd file degrades to the root identity`() {
        val rootfs = tmp.newFolder("bare")
        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        assertTrue("a launch must never fail over account work", identity.isRoot)
    }

    @Test
    fun `unsafe account name degrades to the root identity`() {
        val rootfs = rootfs()
        val identity = initializer.ensureGuestUser(rootfs, "ws", "bad name;rm", "/home/linexa")
        assertTrue(identity.isRoot)
        assertFalse(File(rootfs, "etc/passwd").readText().contains("bad name"))
    }

    // ── 2026-09-05: /home/tiny → /home/linexa migration (issue 3) ──────────

    @Test
    fun `a legacy tiny passwd home is rewritten and its data renamed into place`() {
        // The post-migration drift: an existing workspace's passwd record
        // still points at /home/tiny and the XFCE/terminal data physically
        // lives there. The session must run with HOME=/home/linexa AND find
        // the data there.
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/tiny:/bin/bash\n"
        )
        File(rootfs, "home/tiny/.config/xfce4/xfconf").mkdirs()
        File(rootfs, "home/tiny/.config/xfce4/xfconf/xfce-perchannel-xml.xml").writeText("<channel/>\n")
        File(rootfs, "home/tiny/.bashrc").writeText("# user bashrc\n")

        val identity = initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        // The identity (and therefore HOME, cwd and the desktop session) uses
        // the canonical home.
        assertEquals("/home/linexa", identity.home)
        val passwd = File(rootfs, "etc/passwd").readText()
        assertTrue(passwd.contains("linexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash"))
        assertFalse("the legacy home must not survive in passwd", passwd.contains("/home/tiny"))
        // The data followed, intact, and the legacy directory is gone.
        assertTrue(File(rootfs, "home/linexa/.config/xfce4/xfconf/xfce-perchannel-xml.xml").isFile)
        assertEquals("# user bashrc\n", File(rootfs, "home/linexa/.bashrc").readText())
        assertFalse(File(rootfs, "home/tiny").exists())
        // One-shot marker.
        assertTrue(File(rootfs, ".linexa/home-migration-tiny.done").isFile)
    }

    @Test
    fun `legacy tiny data merges without clobbering the canonical home`() {
        // Both homes exist (the user already used the new build): the
        // canonical home wins every collision, legacy-unique entries move.
        val rootfs = rootfs(
            passwd = "root:x:0:0:root:/root:/bin/bash\nlinexa:x:1000:1000:Linexa User:/home/tiny:/bin/bash\n"
        )
        File(rootfs, "home/tiny").mkdirs()
        File(rootfs, "home/tiny/.bashrc").writeText("# LEGACY\n")
        File(rootfs, "home/tiny/Documents").mkdirs()
        File(rootfs, "home/tiny/Documents/notes.txt").writeText("notes\n")
        File(rootfs, "home/linexa").mkdirs()
        File(rootfs, "home/linexa/.bashrc").writeText("# CURRENT\n")

        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")

        // Collision: the canonical home's file is untouched.
        assertEquals("# CURRENT\n", File(rootfs, "home/linexa/.bashrc").readText())
        // Legacy-unique content moved in.
        assertEquals("notes\n", File(rootfs, "home/linexa/Documents/notes.txt").readText())
        // The conflicted entry STAYS in the legacy directory (a migration
        // must never destroy data) — the legacy dir itself survives too.
        assertEquals("# LEGACY\n", File(rootfs, "home/tiny/.bashrc").readText())
    }

    @Test
    fun `a rootfs without legacy tiny data records the pass once`() {
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        assertTrue(File(rootfs, ".linexa/home-migration-tiny.done").isFile)
        assertFalse(File(rootfs, "home/tiny").exists())
    }

    @Test
    fun `the migration is one-shot per rootfs`() {
        // Second run must be a no-op even if a /home/tiny reappears (a
        // restored backup, an imported archive…): the marker governs.
        val rootfs = rootfs()
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        File(rootfs, "home/tiny").mkdirs()
        File(rootfs, "home/tiny/data.txt").writeText("late arrival\n")
        initializer.ensureGuestUser(rootfs, "ws", "linexa", "/home/linexa")
        assertTrue("late data must NOT be silently dropped", File(rootfs, "home/tiny/data.txt").isFile)
        assertFalse(File(rootfs, "home/linexa/data.txt").exists())
    }
}
