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
 * R-5 / R-6 (2026-08-26 runtime parity audit) integration tests for
 * [PRootContainerRuntime.openTerminal] / [PRootContainerRuntime.exec].
 *
 * Mirrors the shape of [PRootOpenSessionEnsuresGuestTmpTest] — the
 * regression surface grows uniformly with each new guest-side self-heal:
 *
 *  - `openTerminal` must prepare `/run` (1777) and the /proc bind-over
 *    snapshot files BEFORE bash is launched, so the very first session
 *    of an imported workspace self-heals without manual intervention.
 *  - `exec` (one-shot) must do the same, so a Package Manager UI's
 *    first apt-get invocation finds a working /run and /proc snapshots.
 *  - A fresh workspace (no /run, no /proc at all) must self-heal.
 *  - An imported workspace that has a symlinked /run (Debian's historical
 *    /var/run -> /run) must REPLACE the symlink — never write through.
 */
class PRootOpenSessionEnsuresRunAndProcTest {

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
    fun freshWorkspaceHasUsableRunBeforeBashIsLaunched() {
        val rootfs = harness.fakeRootfs()
        assertFalse("fresh workspace rootfs has no /run", File(rootfs, "run").exists())

        openSession("ws-run", rootfs)

        val runDir = File(rootfs, "run")
        assertTrue("openTerminal must create guest /run before launching bash",
            runDir.isDirectory)
        val mode = Files.getAttribute(runDir.toPath(), "unix:mode") as Number
        assertEquals("guest /run must be exactly 1777 (drwxrwxrwt)",
            0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun freshWorkspaceHasProcSnapshotsBeforeBashIsLaunched() {
        val rootfs = harness.fakeRootfs()
        // Pre-create /proc — distro tarballs ship it as an empty dir.
        require(File(rootfs, "proc").mkdirs())

        openSession("ws-proc", rootfs)

        // The INSTALL-time writer owns the R-6 baseline five. The 2026-08-27
        // conditionals (cpuinfo/meminfo/swaps + net subtree) belong to the
        // runtime bridge's probe-gated synthesis and are covered by
        // HostInfoRuntimeBridgeTest — they are bound only where the host
        // denies native reads, which an install sweep cannot presume.
        val rSixBaseline = listOf("loadavg", "stat", "uptime", "version", "vmstat")
        val procDir = File(rootfs, "proc")
        for (name in rSixBaseline) {
            val snap = File(procDir, ".$name")
            assertTrue(
                "openTerminal must write /proc snapshot .$name before launching bash",
                snap.isFile
            )
            assertTrue(
                "/proc snapshot .$name must be non-empty",
                snap.length() > 0
            )
        }
    }

    @Test
    fun execAlsoEnsuresGuestRunForOneShotCommands() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val rootfs = harness.fakeRootfs()
        assertFalse(File(rootfs, "run").exists())

        // exec alone (no prior session) must still prepare /run, so even
        // the very first one-shot command finds a working /run.
        runBlocking {
            harness.runtime.exec("ws-run-exec", rootfs.absolutePath, "true")
        }

        val runDir = File(rootfs, "run")
        assertTrue("exec must prepare guest /run too", runDir.isDirectory)
        val mode = Files.getAttribute(runDir.toPath(), "unix:mode") as Number
        assertEquals("guest /run must be exactly 1777 after exec",
            0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun execAlsoEnsuresProcSnapshotsForOneShotCommands() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val rootfs = harness.fakeRootfs()
        require(File(rootfs, "proc").mkdirs())

        runBlocking {
            harness.runtime.exec("ws-proc-exec", rootfs.absolutePath, "true")
        }

        val procDir = File(rootfs, "proc")
        for (name in listOf("loadavg", "stat", "uptime", "version", "vmstat")) {
            assertTrue("exec must prepare /proc snapshot .$name",
                File(procDir, ".$name").isFile)
        }
    }

    @Test
    fun symlinkedRunIsReplacedOnOpenSession() {
        // Imported workspace with Debian's historical /var/run -> /run
        // (or /run -> /var/run) — openTerminal must REPLACE the symlink,
        // never write through it (security: defense-in-depth against a
        // hostile rootfs that ships /run -> /sdcard/...).
        val rootfs = harness.fakeRootfs()
        val targetOutside = tmp.newFolder("outside-target")
        Files.createSymbolicLink(File(rootfs, "run").toPath(), targetOutside.toPath())

        openSession("ws-run-symlink", rootfs)

        // The symlink target must not have been touched.
        assertTrue("the symlink target must not be removed",
            targetOutside.exists())
        // /run is now a real directory with the right mode.
        val runDir = File(rootfs, "run")
        assertFalse("guest /run must not be a symlink after openTerminal",
            Files.isSymbolicLink(runDir.toPath()))
        assertTrue("guest /run must be a directory after openTerminal",
            runDir.isDirectory)
        val mode = Files.getAttribute(runDir.toPath(), "unix:mode") as Number
        assertEquals("guest /run must be exactly 1777 (drwxrwxrwt)",
            0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun missingProcDirMeansNoSnapshotsAndSessionStillOpens() {
        // If /proc is absent in the rootfs (a malformed import), the
        // session must still open — procBindOvers fails open too.
        val rootfs = harness.fakeRootfs()
        assertFalse("fresh workspace rootfs has no /proc",
            File(rootfs, "proc").exists())

        openSession("ws-proc-missing", rootfs)

        // Session opened successfully (no exception). No /proc snapshots
        // were created.
        assertFalse(File(rootfs, "proc").exists())
    }
}
