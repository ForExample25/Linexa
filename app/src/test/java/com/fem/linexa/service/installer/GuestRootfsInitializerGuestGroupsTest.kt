package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * LX-1 (2026-08-29) regression tests for
 * [GuestRootfsInitializer.ensureGuestGroups] — the runtime-side reconciliation
 * of the guest account database with the Android host's LIVE supplementary
 * group set.
 *
 * The production bug: the installer's account fixup
 * ([RootfsFixupService.fixUpAccounts]) mirrors the app process's supplementary
 * groups into the guest `/etc/group` exactly once, at install time, while
 * PRoot propagates the process's LIVE group set into every guest session.
 * Android grants/revokes supplementary groups across the app's lifetime
 * (storage-grant GIDs like 1077 `ext_data_rw`, per-user and OEM groups), so
 * the mirrored database drifts and every new shell prints
 * `groups: cannot find name for group ID 1077` — the reported symptom.
 *
 * These tests pin the reconciler contract:
 *  - drifted GIDs get well-formed `aid_<gid>` entries (the fixup's own
 *    naming/format convention), with the fixup's membership derivation;
 *  - already-present GIDs are never duplicated;
 *  - the operation is idempotent (a converged database is byte-untouched);
 *  - the fail-open posture (no /etc/group, missing `id` applet, failing
 *    `id`) never writes and never throws.
 */
class GuestRootfsInitializerGuestGroupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var initializer: GuestRootfsInitializer
    private lateinit var filesDir: File
    private lateinit var binDir: File
    private lateinit var rootfs: File

    /** The live group list the fake `id -G` reports (written per test). */
    private fun setLiveGroups(vararg gids: String) {
        controlFile(ID_G_CONTROL).writeText(gids.joinToString(" "))
    }

    private fun setLiveUser(name: String) {
        controlFile(ID_UN_CONTROL).writeText(name)
    }

    private fun setLiveUid(uid: String) {
        controlFile(ID_U_CONTROL).writeText(uid)
    }

    private fun controlFile(name: String): File =
        File(File(binDir, ID_CONTROL_DIR).apply { mkdirs() }, name)

    private fun writeWorkingIdApplet() {
        File(binDir, "id").writeText(
            """
            #!/bin/sh
            DIR=${'$'}{0%/*}
            case "${'$'}1" in
              -G)  [ -r "${'$'}DIR/$ID_CONTROL_DIR$ID_G_CONTROL" ] && { read -r v < "${'$'}DIR/$ID_CONTROL_DIR$ID_G_CONTROL"; echo "${'$'}v"; } ;;
              -un) [ -r "${'$'}DIR/$ID_CONTROL_DIR$ID_UN_CONTROL" ] && { read -r v < "${'$'}DIR/$ID_CONTROL_DIR$ID_UN_CONTROL"; echo "${'$'}v"; } ;;
              -u)  [ -r "${'$'}DIR/$ID_CONTROL_DIR$ID_U_CONTROL" ] && { read -r v < "${'$'}DIR/$ID_CONTROL_DIR$ID_U_CONTROL"; echo "${'$'}v"; } ;;
            esac
            exit 0
            """.trimIndent()
        )
    }

    /** Plants a guest /etc/group shaped like a post-fixup Ubuntu one. */
    private fun plantGroupFile(vararg lines: String): File {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        return File(etc, "group").apply { writeText(lines.joinToString(separator = "", postfix = "") { "$it\n" }) }
    }

    private fun plantGshadow(vararg lines: String): File {
        val etc = File(rootfs, "etc")
        return File(etc, "gshadow").apply { writeText(lines.joinToString(separator = "", postfix = "") { "$it\n" }) }
    }

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        binDir = File(filesDir, "bootstrap/bin").apply { mkdirs() }
        // The fake `id` applet: dispatches on the flag and reports the
        // values the test planted in the control files. Deliberately
        // external-command-free (POSIX builtins + parameter expansion only):
        // BootstrapManager.processEnv() sets PATH to binDir ALONE, so a fake
        // script that shells out to dirname/cat would silently produce empty
        // output in exactly the environment under test.
        val id = File(binDir, "id")
        writeWorkingIdApplet()
        assertTrue(id.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))

        rootfs = tmp.newFolder("rootfs")
    }

    @Test
    fun `drifted gid is appended as an aid entry - the issue-1 repro`() {
        // Post-install guest db names the install-time group set…
        val group = plantGroupFile(
            "root:x:0:",
            "aid_3003:x:3003:root,aid_10511",
            "aid_9997:x:9997:root,aid_10511"
        )
        // …but the host now propagates a drifted set that includes 1077.
        setLiveGroups("10511", "1077", "3003", "9997")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-1"))

        val content = group.readText()
        val lines = content.lines().filter { it.isNotBlank() }
        // Existing entries untouched, in order.
        assertEquals("root:x:0:", lines[0])
        assertEquals("aid_3003:x:3003:root,aid_10511", lines[1])
        assertEquals("aid_9997:x:9997:root,aid_10511", lines[2])
        // The drifted GIDs got well-formed aid_ entries (sorted numerically).
        assertEquals("aid_1077:x:1077:root,aid_u0_a511", lines[3])
        assertEquals("aid_10511:x:10511:root,aid_u0_a511", lines[4])
        // 3003/9997 (already named) were NOT duplicated.
        assertEquals(5, lines.size)
        assertEquals(1, lines.count { it.contains("aid_3003") })
    }

    @Test
    fun `reconciliation is idempotent`() {
        val group = plantGroupFile("root:x:0:", "aid_3003:x:3003:root,aid_10511")
        setLiveGroups("1077", "3003")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-2"))
        val afterFirst = group.readText()
        assertTrue("aid_1077 must be present after the first run", afterFirst.contains("aid_1077:x:1077"))

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-2"))
        assertEquals("a second run must not modify the file", afterFirst, group.readText())
    }

    @Test
    fun `converged database performs no write`() {
        val group = plantGroupFile("root:x:0:", "aid_3003:x:3003:root,aid_10511", "aid_1077:x:1077:root,aid_10511")
        val before = group.readText()
        setLiveGroups("0", "1077", "3003")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-3"))
        assertEquals("a converged database must stay byte-identical", before, group.readText())
    }

    @Test
    fun `membership falls back to numeric uid when the name is unusable`() {
        val group = plantGroupFile("root:x:0:")
        setLiveGroups("1077")
        // §1.65: SELinux context text is not an account name — the fixup's
        // fallback (numeric uid) must be replicated, not the raw token.
        setLiveUser("u:r:untrusted_app:s0:c512,c768,c1024,c2048")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-4"))

        assertEquals(
            "aid_1077:x:1077:root,aid_10511",
            group.readText().lines().first { it.startsWith("aid_") }
        )
    }

    @Test
    fun `membership falls back to root when neither name nor uid resolves`() {
        val group = plantGroupFile("root:x:0:")
        setLiveGroups("1077")
        setLiveUser("")
        setLiveUid("")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-5"))

        assertEquals(
            "aid_1077:x:1077:root",
            group.readText().lines().first { it.startsWith("aid_") }
        )
    }

    @Test
    fun `gshadow is mirrored when present`() {
        plantGroupFile("root:x:0:")
        val gshadow = plantGshadow("root:*::")
        setLiveGroups("1077")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-6"))

        assertTrue(gshadow.readText().contains("aid_1077:*::root,aid_u0_a511"))
    }

    @Test
    fun `gshadow absent is fine`() {
        plantGroupFile("root:x:0:")
        setLiveGroups("1077")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-7"))
        assertFalse(File(rootfs, "etc/gshadow").exists())
    }

    @Test
    fun `missing etc group is a no-op`() {
        setLiveGroups("1077")
        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-8"))
        assertFalse(File(rootfs, "etc/group").exists())
    }

    @Test
    fun `missing id applet fails open without writing`() {
        val group = plantGroupFile("root:x:0:")
        File(binDir, "id").delete()
        val before = group.readText()

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-9"))
        assertEquals(before, group.readText())
    }

    @Test
    fun `failing id applet fails open without writing`() {
        val group = plantGroupFile("root:x:0:")
        // Make the applet exit non-zero for -G.
        val id = File(binDir, "id")
        id.writeText("#!/bin/sh\nexit 1\n")
        assertTrue(id.setExecutable(true))
        val before = group.readText()

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-10"))
        assertEquals(before, group.readText())
    }

    @Test
    fun `duplicate gids in host output are collapsed and sorted numerically`() {
        val group = plantGroupFile("root:x:0:")
        setLiveGroups("9997 1077 1077 3003")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-11"))

        val added = group.readText().lines().filter { it.startsWith("aid_") }
        assertEquals(listOf("aid_1077:x:1077:root,aid_u0_a511", "aid_3003:x:3003:root,aid_u0_a511", "aid_9997:x:9997:root,aid_u0_a511"), added)
    }

    @Test
    fun `non-whitelisted tokens in id output never reach the file`() {
        val group = plantGroupFile("root:x:0:")
        // A diagnostic fragment in the -G output must be rejected by the
        // §1.65 whitelist, not written into /etc/group.
        setLiveGroups("1077 id: unknown user")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-12"))

        val added = group.readText().lines().filter { it.startsWith("aid_") }
        assertEquals(listOf("aid_1077:x:1077:root,aid_u0_a511"), added)
    }

    @Test
    fun `the host group read is memoized for the process`() {
        val group = plantGroupFile("root:x:0:")
        setLiveGroups("1077")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-cache-1"))
        assertTrue(group.readText().contains("aid_1077:x:1077"))

        // Android fixes a process's supplementary groups at process start;
        // changing the fake `id` output here would be impossible in
        // production without a process restart. The memoized read must keep
        // reporting the first (real) set instead of spawning `id -G` again.
        setLiveGroups("1088")
        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-cache-1"))
        assertFalse(
            "a second reconciliation must not pick up a new host read in the same process",
            group.readText().contains("aid_1088")
        )
        assertEquals(1, group.readText().lines().count { it.startsWith("aid_") })
    }

    @Test
    fun `a failed host group read is retried`() {
        val group = plantGroupFile("root:x:0:")
        val id = File(binDir, "id")
        id.writeText("#!/bin/sh\nexit 1\n")
        assertTrue(id.setExecutable(true))
        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-cache-2"))
        assertFalse(group.readText().contains("aid_"))

        // Restore a working applet: the failed read must not have been cached.
        writeWorkingIdApplet()
        assertTrue(id.setExecutable(true))
        setLiveGroups("1077")
        setLiveUser("u0_a511")
        setLiveUid("10511")

        assertNull(initializer.ensureGuestGroups(rootfs, "ws-groups-cache-2"))
        assertTrue(group.readText().contains("aid_1077:x:1077"))
    }

    private companion object {
        const val ID_CONTROL_DIR = ".id-control/"
        const val ID_G_CONTROL = "g"
        const val ID_UN_CONTROL = "un"
        const val ID_U_CONTROL = "u"
    }
}
