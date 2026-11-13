package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager.TerminfoTestSeam
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-11-13 — terminal/desktop HOME follows the passwd identity, and Root
 * Shell stays root.
 *
 * This is the launch-side half of the File Manager Home fix: the UI reads the
 * same `/etc/passwd` record, so an imported/custom home cannot make the tile
 * and the session disagree. The test runs the REAL [PRootContainerRuntime]
 * against the fake PRoot stand-in.
 */
class PRootOpenSessionHomeIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val prootDir = FakeProot.install(tmp.newFolder("fake-proot-root"), FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries
        harness.create()
    }

    @After
    fun tearDown() {
        TerminfoTestSeam.overrideBytes = null
    }

    private fun rootfs(
        userHome: String = "/home/linexa",
        uid: Int = 1000,
        shell: String = "/bin/bash"
    ): File {
        val rootfs = harness.fakeRootfs()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/bash\n" +
                "linexa:x:$uid:$uid:Linexa User:$userHome:$shell\n"
        )
        File(rootfs, "etc/group").writeText("root:x:0:\nlinexa:x:$uid:\n")
        File(rootfs, "etc/shadow").writeText(
            "root:*:19000:0:99999:7:::\nlinexa:*:19000:0:99999:7:::\n"
        )
        return rootfs
    }

    private fun open(rootfs: File, asRoot: Boolean): com.fem.linexa.test.support.RecordingPtySessionFactory.CreateCall =
        runBlocking {
            harness.runtime.openTerminal(
                "ws-home",
                rootfs.absolutePath,
                DEFAULT_TERMINAL_ID,
                asRoot = asRoot
            )
            harness.createCalls.single()
        }

    @Test
    fun `a normal session uses the passwd home, uid and gid`() {
        val call = open(rootfs(userHome = "/home/linexa", uid = 1000), asRoot = false)

        val w = call.args.indexOf("-w")
        assertTrue("the launch spec must carry a guest cwd", w >= 0)
        assertEquals("/home/linexa", call.args[w + 1])
        assertEquals("/home/linexa", call.env["HOME"])
        assertEquals("linexa", call.env["USER"])
        assertTrue(
            "the session must fake the account's real uid/gid",
            call.args.contains("--change-id=1000:1000")
        )
        assertFalse("a normal session must not run as emulated root", call.args.contains("-0"))
    }

    @Test
    fun `a custom passwd home drives HOME and the startup directory`() {
        val call = open(rootfs(userHome = "/srv/home", uid = 1042), asRoot = false)

        val w = call.args.indexOf("-w")
        assertTrue(w >= 0)
        assertEquals("the session must open in the account's real home", "/srv/home", call.args[w + 1])
        assertEquals("/srv/home", call.env["HOME"])
        assertTrue(call.args.contains("--change-id=1042:1042"))
    }

    @Test
    fun `a root shell keeps root's home even when the image declares linexa`() {
        val call = open(rootfs(userHome = "/home/linexa", uid = 1000), asRoot = true)

        val w = call.args.indexOf("-w")
        assertTrue(w >= 0)
        assertEquals("Root Shell must not be redirected into the user's home", "/root", call.args[w + 1])
        assertEquals("/root", call.env["HOME"])
        assertEquals("root", call.env["USER"])
        assertTrue("Root Shell runs in the emulated root identity", call.args.contains("-0"))
        assertFalse(call.args.contains("--change-id=1000:1000"))
    }
}