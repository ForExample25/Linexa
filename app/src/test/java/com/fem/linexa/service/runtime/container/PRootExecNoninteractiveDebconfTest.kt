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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-7 (2026-08-25 audit) regression tests: the one-shot exec bridge (no TTY,
 * stdin a pipe nobody writes to) must run package-manager commands with
 * `DEBIAN_FRONTEND=noninteractive` so debconf takes documented defaults
 * instead of stalling until the operation timeout — while INTERACTIVE
 * terminal sessions must keep the variable absent (full debconf
 * interactivity).
 *
 * Verified through the REAL [PRootContainerRuntime] against the fake PRoot
 * stand-in, asserting the exact environment recorded by the fake for:
 *
 *  - a plain one-shot exec (the Package Manager UI / provisioner path),
 *  - the `dpkg --configure -a` recovery preflight exec,
 *  - the seccomp-fallback retry exec (env must survive the retry),
 *  - an interactive openTerminal launch (variable must be ABSENT),
 *  - a config.yaml env override still wins over the layered default.
 */
class PRootExecNoninteractiveDebconfTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        ProotRuntimeTestHarness.markSeccompProbeDone()
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

    /** The fake-proot invocation whose /bin/sh -c command contains [needle]. */
    private fun invocationFor(needle: String): FakeProot.Invocation? =
        FakeProot.invocations(prootDir).firstOrNull { inv ->
            inv.argv.any { it.contains(needle) }
        }

    @Test
    fun oneShotExecCarriesNoninteractiveDebconfFrontend() {
        val rootfs = harness.fakeRootfs()
        val result = runBlocking {
            harness.runtime.exec("ws-f7-1", rootfs.absolutePath, "apt-get install -y unzip")
        }
        assertEquals(0, result.exitCode)

        val inv = invocationFor("apt-get install -y unzip")
        assertNotNull("the exec invocation must be recorded", inv)
        assertEquals(
            "the one-shot bridge must set DEBIAN_FRONTEND=noninteractive (F-7)",
            "noninteractive",
            inv!!.env["DEBIAN_FRONTEND"]
        )
    }

    @Test
    fun dpkgConfigurePreflightCarriesNoninteractiveDebconfFrontend() {
        val rootfs = harness.fakeRootfs()
        // Interrupted dpkg state → openTerminal prepends a
        // `dpkg --configure -a` preflight exec (Phase 6b), which itself can
        // hit debconf prompts during package configuration.
        val updates = File(rootfs, "var/lib/dpkg/updates")
        require(updates.mkdirs())
        File(updates, "0000").writeText("journal")

        runBlocking {
            harness.runtime.openTerminal("ws-f7-2", rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
        }

        val inv = invocationFor("dpkg --configure -a")
        assertNotNull("the recovery preflight exec must be recorded", inv)
        assertEquals(
            "the dpkg --configure -a preflight must be noninteractive too (F-7)",
            "noninteractive",
            inv!!.env["DEBIAN_FRONTEND"]
        )
    }

    @Test
    fun interactiveTerminalSessionDoesNotCarryDebconfFrontend() {
        val rootfs = harness.fakeRootfs()

        runBlocking {
            harness.runtime.openTerminal("ws-f7-3", rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
        }

        val call = harness.createCalls.single()
        assertFalse(
            "interactive terminal sessions must NOT set DEBIAN_FRONTEND — debconf prompts stay answerable",
            call.env.containsKey("DEBIAN_FRONTEND")
        )
    }

    @Test
    fun noninteractiveEnvSurvivesSeccompRetry() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ONCE)
        val rootfs = harness.fakeRootfs()

        val result = runBlocking {
            harness.runtime.exec("ws-f7-4", rootfs.absolutePath, "apt-get update")
        }
        assertEquals(0, result.exitCode)
        assertEquals("retry must have happened", 2, FakeProot.invocationCount(prootDir))

        val invocations = FakeProot.invocations(prootDir)
        assertTrue(
            "the seccomp-fallback retry exec must still carry DEBIAN_FRONTEND=noninteractive",
            invocations.all { it.env["DEBIAN_FRONTEND"] == "noninteractive" }
        )
        assertTrue(
            "and the retry must carry PROOT_NO_SECCOMP=1 as before",
            invocations[1].hadNoSeccompEnv
        )
    }

    @Test
    fun configYamlCannotReintroduceAnUnanswerablePrompt() {
        val rootfs = harness.fakeRootfs()
        val workspaceBase = File(harness.filesDir, "workspaces/ws-f7-5")
        require(workspaceBase.mkdirs())
        // A workspace whose author sets a prompt-capable debconf frontend in
        // config.yaml. On the INTERACTIVE session that is their right; on the
        // one-shot bridge it would reintroduce the F-7 stall (a prompt there
        // can never be answered), so the bridge default must win.
        File(workspaceBase, "config.yaml").writeText(
            """
            linexa:
              env:
                DEBIAN_FRONTEND: readline
            """.trimIndent()
        )

        val result = runBlocking {
            harness.runtime.exec("ws-f7-5", rootfs.absolutePath, "apt-get update")
        }
        assertEquals(0, result.exitCode)

        val inv = invocationFor("apt-get update")
        assertNotNull(inv)
        assertEquals(
            "the bridge's noninteractive frontend must win over config.yaml (F-7)",
            "noninteractive",
            inv!!.env["DEBIAN_FRONTEND"]
        )
    }

    @Test
    fun failureSurfacingIsUnchanged() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.GENERIC_FAILURE_ALWAYS)
        val rootfs = harness.fakeRootfs()

        val result = runBlocking {
            harness.runtime.exec("ws-f7-6", rootfs.absolutePath, "apt-get install -y unzip")
        }
        assertEquals("exit code must still surface", 1, result.exitCode)
        assertEquals(
            "failure output must still be surfaced as the error",
            result.output,
            result.error
        )
    }
}
