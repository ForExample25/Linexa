package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager.TerminfoTestSeam
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.75 regression tests for the `chdir("/root"): No such file or
 * directory` + `can't find terminfo database` PTY-launch failure, through
 * the REAL [PRootContainerRuntime] against the fake PRoot stand-in
 * ([FakeProot]).
 *
 * The pty path's native `forkpty` chdirs its child on the HOST before
 * proot/tmux are even exec'd — so the GUEST working directory
 * (`-w /root`, `HOME=/root`, still byte-identical in argv/env) must NEVER
 * be reused as the host cwd; the host cwd must be a guaranteed host
 * directory (`filesDir`). And the host-side tmux needs the vendored
 * host terminfo (TERMINFO from `prootEnv`), which is a HOST-side value —
 * `TERMINFO_DIRS` stays unset so the guest keeps resolving its own
 * terminfo from inside the rootfs (§1.75 must-not-break).
 */
class PRootOpenSessionHostWorkingDirectoryTest {

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
        // Seed the terminfo seam so the REAL setup() extracts the vendored
        // entries and TERMINFO lands in the launch env (getAssets() cannot
        // be stubbed on the mock Application).
        TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries
        harness.create()
    }

    @After
    fun tearDown() {
        TerminfoTestSeam.overrideBytes = null
    }

    private fun openSession(workspaceId: String = "ws-cwd"): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    @Test
    fun ptyHostWorkingDirectoryIsGuaranteedHostDirAndGuestCwdStaysGuest() {
        openSession()

        val call = harness.createCalls.single()
        assertEquals(
            "forkpty child cwd must be a HOST dir that exists (filesDir), never /root",
            harness.filesDir.absolutePath,
            call.workingDirectory
        )
        assertTrue("host pty cwd must be a real host directory", File(call.workingDirectory).isDirectory)
        assertNotEquals("guest path /root must never be the host cwd", "/root", call.workingDirectory)

        // Guest contract (issue #4 + issue #1, 2026-09-03): -w points at the
        // workspace, which for an undeclared image defaults to the HOME
        // itself (`/home/linexa` — the normal-user default), so a fresh
        // prompt reads `~`. HOME is the same normal-user home.
        val wIndex = call.args.indexOf("-w")
        assertTrue("argv must still carry the guest -w flag", wIndex >= 0)
        assertEquals(
            "guest -w must be the default workspace directory",
            "/home/linexa", call.args[wIndex + 1]
        )
        assertEquals("guest HOME must be the normal-user home", "/home/linexa", call.env["HOME"])

        // §1.75: host-side TERMINFO wired through prootEnv for the host tmux.
        assertEquals(
            "TERMINFO must point at the vendored host DB",
            File(harness.filesDir, "terminfo").absolutePath,
            call.env["TERMINFO"]
        )
        assertNull("TERMINFO_DIRS must stay unset — guest resolves from its rootfs", call.env["TERMINFO_DIRS"])
    }

    @Test
    fun missingHostTerminfoDegradesWithoutBreakingGuestCwdContract() {
        TerminfoTestSeam.overrideBytes = null

        openSession()

        val call = harness.createCalls.single()
        assertEquals("host cwd fix must not depend on terminfo", harness.filesDir.absolutePath, call.workingDirectory)
        assertNull("fail-open: no TERMINFO when the DB is unavailable", call.env["TERMINFO"])
        val wIndex = call.args.indexOf("-w")
        assertTrue(wIndex >= 0)
        assertEquals("guest -w workspace contract holds either way", "/home/linexa", call.args[wIndex + 1])
    }
}
