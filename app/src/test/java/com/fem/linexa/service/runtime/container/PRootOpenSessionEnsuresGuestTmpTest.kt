package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager.TerminfoTestSeam
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import java.nio.file.Files
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
 * §1.80 regression tests for the "apt update → unable to mkstemp
 * /tmp/apt.sig (2: No such file or directory)" runtime bug, through the
 * REAL [PRootContainerRuntime] against the fake PRoot stand-in
 * ([FakeProot]) and a fake busybox that really applies `chmod 1777`.
 *
 * The guest `/tmp` is missing because Debian/Ubuntu rootfs tarballs omit it
 * (it is a boot-time tmpfs mount). The runtime must create it with exactly
 * 1777 BEFORE bash is launched — every workspace, fresh or already
 * installed — so apt works with zero manual intervention.
 */
class PRootOpenSessionEnsuresGuestTmpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        // Seed the terminfo seam so setup() extracts entries (getAssets()
        // cannot be stubbed on the mock Application).
        TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries
        harness.create()
    }

    @After
    fun tearDown() {
        TerminfoTestSeam.overrideBytes = null
    }

    private fun openSession(workspaceId: String, rootfs: File): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    @Test
    fun freshWorkspaceHasUsableTmpBeforeBashIsLaunched() {
        val rootfs = harness.fakeRootfs()
        assertFalse("fresh workspace rootfs has no /tmp (Debian tarballs omit it)", File(rootfs, "tmp").exists())

        openSession("ws-tmp", rootfs)

        val tmpDir = File(rootfs, "tmp")
        assertTrue("openTerminal must create guest /tmp before launching bash", tmpDir.isDirectory)
        val mode = Files.getAttribute(tmpDir.toPath(), "unix:mode") as Number
        assertEquals("guest /tmp must be exactly 1777 (drwxrwxrwt)", 0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun aptUpdateSucceedsWithoutManualTmpSetup() {
        // The fake proot now behaves like apt: it exits 2 with the real
        // "unable to mkstemp /tmp/apt.sig" marker unless the rootfs has a
        // writable /tmp.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.APT_UPDATE_REQUIRES_TMP)
        val rootfs = harness.fakeRootfs()
        assertFalse(File(rootfs, "tmp").exists())

        // Session launch guarantees /tmp before the guest shell (and before
        // the seccomp/guest-fs probes that also run through the fake).
        openSession("ws-apt", rootfs)
        assertTrue(File(rootfs, "tmp").isDirectory)

        // A one-shot `apt-get update` (the exact failing command) must now
        // succeed with zero manual mkdir/chmod.
        val result = runBlocking {
            harness.runtime.exec("ws-apt", rootfs.absolutePath, "apt-get update")
        }
        assertEquals("apt update must succeed when the runtime prepared /tmp", 0, result.exitCode)
        assertEquals("", result.error)
    }

    @Test
    fun execAlsoEnsuresGuestTmpForOneShotCommands() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.APT_UPDATE_REQUIRES_TMP)
        val rootfs = harness.fakeRootfs()
        assertFalse(File(rootfs, "tmp").exists())

        // exec alone (no prior session) must still prepare /tmp, so even the
        // very first one-shot apt succeeds.
        val result = runBlocking {
            harness.runtime.exec("ws-apt-exec", rootfs.absolutePath, "apt-get update")
        }

        assertEquals("exec must prepare guest /tmp too", 0, result.exitCode)
        assertTrue(File(rootfs, "tmp").isDirectory)
        val mode = Files.getAttribute(File(rootfs, "tmp").toPath(), "unix:mode") as Number
        assertEquals("guest /tmp must be exactly 1777 after exec", 0x3FF, mode.toLong() and 0x3FF)
    }
}
