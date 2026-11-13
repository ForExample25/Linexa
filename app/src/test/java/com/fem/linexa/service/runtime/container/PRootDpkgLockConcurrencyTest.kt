package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager.TerminfoTestSeam
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-6 (2026-08-25 audit) regression tests for dpkg-lock safety across the
 * REAL [PRootContainerRuntime], fake PRoot and controllable stub sessions.
 *
 * The audit's scenario: `ensureDpkgHealthy` deleted `var/lib/dpkg/lock*`
 * while another live guest process of the SAME workspace could be mid-dpkg —
 * opening T1 doesn't stop T0, and Package-Manager-UI one-shot execs run
 * while interactive terminals are alive. Deleting the file a live dpkg holds
 * fcntl-locked lets a second dpkg lock a fresh inode and corrupt the
 * database.
 *
 * The runtime now keeps a per-workspace activity registry (live PTY sessions
 * + in-flight one-shot execs) and passes `allowLockCleanup` to
 * [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureDpkgHealthy]
 * accordingly. These tests pin:
 *
 *  1. no live activity → stale locks ARE recovered (recovery preserved),
 *  2. a live terminal → locks are preserved for both a second terminal and
 *     a Package-Manager-UI exec,
 *  3. an in-flight exec → locks are preserved for a concurrent exec,
 *  4. after ALL activity ends → recovery returns,
 *  5. normal terminal startup never deadlocks on the new guard mutex.
 */
class PRootDpkgLockConcurrencyTest {

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
        // F-6: sessions must stay alive until the test completes them.
        harness.ptyFactory.controllable = true
    }

    @After
    fun tearDown() {
        TerminfoTestSeam.overrideBytes = null
    }

    /** Plants both dpkg lock files (a stale or live-held dpkg's leftovers). */
    private fun plantLocks(rootfs: File) {
        val dpkgDir = File(rootfs, "var/lib/dpkg")
        require(dpkgDir.mkdirs()) { "could not create $dpkgDir" }
        File(dpkgDir, "lock").writeText("held")
        File(dpkgDir, "lock-frontend").writeText("held")
    }

    private fun locksExist(rootfs: File): Boolean =
        File(rootfs, "var/lib/dpkg/lock").exists() &&
            File(rootfs, "var/lib/dpkg/lock-frontend").exists()

    private fun openSession(workspaceId: String, rootfs: File, terminalId: String = DEFAULT_TERMINAL_ID) =
        runBlocking {
            harness.runtime.openTerminal(workspaceId, rootfs.absolutePath, terminalId, asRoot = false)
        }

    @Test
    fun noLiveActivityRecoversStaleLocks() {
        val rootfs = harness.fakeRootfs()
        plantLocks(rootfs)
        assertTrue(locksExist(rootfs))

        // First terminal of the workspace: nothing else could be running
        // dpkg, so the historical stale-lock recovery must still fire.
        openSession("ws-f6-1", rootfs)

        assertFalse("stale locks must be removed when the workspace is idle", locksExist(rootfs))
    }

    @Test
    fun secondTerminalPreservesLocksHeldByFirstTerminal() {
        val rootfs = harness.fakeRootfs()

        // T0 is live (controllable session stays alive until completed).
        openSession("ws-f6-2", rootfs)
        plantLocks(rootfs)

        // Opening T1 must NOT delete the locks T0's dpkg could be holding.
        openSession("ws-f6-2", rootfs, terminalId = "T1")

        assertTrue("a live sibling terminal must protect dpkg lock files (F-6)", locksExist(rootfs))
    }

    @Test
    fun packageManagerExecDuringLiveTerminalPreservesLocks() {
        val rootfs = harness.fakeRootfs()

        // Interactive terminal T0 running (the user's apt could be mid-run).
        openSession("ws-f6-3", rootfs)
        plantLocks(rootfs)

        // The Package Manager UI's one-shot exec path.
        val result = runBlocking {
            harness.runtime.exec("ws-f6-3", rootfs.absolutePath, "apt-get install -y unzip")
        }
        assertEquals(0, result.exitCode)

        assertTrue(
            "a one-shot exec while a terminal is live must preserve dpkg locks (F-6)",
            locksExist(rootfs)
        )
    }

    @Test
    fun inFlightExecProtectsLocksAgainstConcurrentExec() {
        val rootfs = harness.fakeRootfs()

        // A long-running one-shot exec (Package Manager UI's apt install) on
        // its own scope: runBlocking would wait for the launched child, which
        // is exactly what must NOT happen here — the concurrent exec below
        // must run WHILE this one is in flight.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SLOW_EXIT)
        val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val execJob = execScope.launch {
            harness.runtime.exec("ws-f6-4", rootfs.absolutePath, "apt-get install -y big-package")
        }

        // Wait until the fake proot process is actually running — by which
        // point the exec's activity slot is registered (registration happens
        // under dpkgHealthLock BEFORE the process spawns).
        val marker = File(prootDir, FakeProot.SLOW_STARTED_FILE)
        runBlocking {
            withTimeout(30_000) {
                while (!marker.exists()) {
                    delay(50)
                }
            }
        }

        // While that exec is in flight, a concurrent exec must not clean
        // the dpkg locks under it.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        plantLocks(rootfs)
        val result = runBlocking {
            harness.runtime.exec("ws-f6-4", rootfs.absolutePath, "apt list --upgradable")
        }
        assertEquals(0, result.exitCode)
        assertTrue(
            "an in-flight exec must protect dpkg lock files for concurrent callers (F-6)",
            locksExist(rootfs)
        )

        // Let the slow exec finish so the test doesn't leak its process.
        runBlocking { withTimeout(30_000) { execJob.join() } }
    }

    @Test
    fun recoveryReturnsOnceAllActivityEnded() {
        val rootfs = harness.fakeRootfs()

        val session = openSession("ws-f6-5", rootfs)
        plantLocks(rootfs)
        assertTrue(locksExist(rootfs))

        // While T0 is alive, an exec preserves the locks…
        runBlocking { harness.runtime.exec("ws-f6-5", rootfs.absolutePath, "true") }
        assertTrue(locksExist(rootfs))

        // …T0's shell exits (user closes the terminal / natural exit)…
        (session as com.fem.linexa.test.support.ControllableStubPtySession).completeExit(0)
        // Give the awaitExit watcher a beat to release the activity slot.
        runBlocking {
            withTimeout(10_000) {
                while (locksExist(rootfs)) {
                    // Keep poking with execs until the registry observes the
                    // exit; bounded by withTimeout.
                    harness.runtime.exec("ws-f6-5", rootfs.absolutePath, "true")
                    delay(50)
                }
            }
        }

        assertFalse(
            "stale-lock recovery must return once every guest process ended (F-6)",
            locksExist(rootfs)
        )
    }

    @Test
    fun workspaceCloseReleasesActivityAndRestoresRecovery() {
        val rootfs = harness.fakeRootfs()

        openSession("ws-f6-6", rootfs)
        plantLocks(rootfs)
        assertTrue(locksExist(rootfs))

        runBlocking { harness.runtime.closeWorkspaceSessions("ws-f6-6") }
        // destroy() completes the controllable session's exit, releasing its
        // activity slot — a later exec must recover the stale locks.
        runBlocking {
            withTimeout(10_000) {
                while (locksExist(rootfs)) {
                    harness.runtime.exec("ws-f6-6", rootfs.absolutePath, "true")
                    delay(50)
                }
            }
        }
        assertFalse("workspace shutdown must re-enable stale-lock recovery (F-6)", locksExist(rootfs))
    }

    @Test
    fun normalStartupDoesNotDeadlock() {
        // Regression for the "do not create a deadlock in normal terminal
        // startup" requirement: two terminals of one workspace, opened
        // sequentially, both complete promptly (the F-6 guard mutex is a
        // leaf and bounded). If the guard deadlocked, withTimeout would fire.
        val rootfs = harness.fakeRootfs()
        runBlocking {
            withTimeout(60_000) {
                harness.runtime.openTerminal("ws-f6-7", rootfs.absolutePath, "T0", asRoot = false)
                harness.runtime.openTerminal("ws-f6-7", rootfs.absolutePath, "T1", asRoot = false)
            }
        }
        assertEquals("both terminals must be registered", 2, harness.createCalls.size)
    }

    // ── LX-2 (2026-08-29): Linexa must not fight live package operations ──
    //
    // The F-6 gate protected the lock FILES from deletion under a live dpkg,
    // but the `dpkg --configure -a` RECOVERY (openTerminal preflight + the
    // exec prepend) still fired on partial-state evidence that is ambiguous
    // while a live apt runs: apt writes `archives/partial` while downloading
    // and dpkg writes `updates/` mid-transaction. Starting recovery then made
    // Linexa itself contend for the dpkg locks — the reported
    // "Waiting for cache lock … held by process NNNN (apt)" between two
    // sessions. These tests pin the deferral: recovery runs ONLY when the
    // workspace is guest-idle, and Linexa-initiated package operations of one
    // workspace serialize instead of overlapping.

    /** Plants an interrupted-dpkg signature: locks + a partial journal. */
    private fun plantPartialDpkgState(rootfs: File) {
        val dpkgDir = File(rootfs, "var/lib/dpkg")
        require(dpkgDir.mkdirs()) { "could not create $dpkgDir" }
        File(dpkgDir, "lock").writeText("held")
        File(dpkgDir, "lock-frontend").writeText("held")
        val updates = File(dpkgDir, "updates")
        require(updates.mkdirs()) { "could not create $updates" }
        File(updates, "0000").writeText("partial journal")
    }

    private fun dpkgConfigureInvocationCount(): Int =
        FakeProot.invocations(prootDir).count { invocation ->
            invocation.argv.any { it.contains("dpkg --configure -a") }
        }

    @Test
    fun secondTerminalDefersDpkgConfigurePreflightWhileFirstIsLive() {
        val rootfs = harness.fakeRootfs()

        // T0 is live (controllable session stays alive until completed).
        openSession("ws-lx2-1", rootfs)
        plantPartialDpkgState(rootfs)
        val before = dpkgConfigureInvocationCount()

        // Opening T1 sees the partial state, but T0 could be mid-apt: the
        // recovery preflight must be DEFERRED, never started against a
        // possibly-live transaction.
        openSession("ws-lx2-1", rootfs, terminalId = "T1")

        assertEquals("both terminals must have opened", 2, harness.createCalls.size)
        assertEquals(
            "a live sibling terminal must defer the dpkg --configure -a preflight (LX-2)",
            before,
            dpkgConfigureInvocationCount()
        )
    }

    @Test
    fun idleOpenRunsDpkgConfigurePreflight() {
        val rootfs = harness.fakeRootfs()
        plantPartialDpkgState(rootfs)
        assertEquals(0, dpkgConfigureInvocationCount())

        // First terminal of a workspace with a genuinely interrupted
        // transaction: the workspace is provably idle, so the historical
        // recovery must still fire (deferral must not lose recovery).
        openSession("ws-lx2-2", rootfs)

        assertTrue(
            "an idle workspace's first open must run the dpkg --configure -a preflight (LX-2)",
            dpkgConfigureInvocationCount() >= 1
        )
        assertEquals("the session itself must still open", 1, harness.createCalls.size)
    }

    @Test
    fun execDefersConfigurePrependWhileTerminalIsLive() {
        val rootfs = harness.fakeRootfs()

        openSession("ws-lx2-3", rootfs)
        plantPartialDpkgState(rootfs)
        val before = dpkgConfigureInvocationCount()

        // A one-shot exec (Package Manager UI / feature provisioner shape)
        // while T0 is live: no Linexa-initiated recovery may join the race.
        val result = runBlocking {
            harness.runtime.exec("ws-lx2-3", rootfs.absolutePath, "true")
        }
        assertEquals(0, result.exitCode)
        assertEquals(
            "a live sibling terminal must defer the exec-path dpkg --configure -a prepend (LX-2)",
            before,
            dpkgConfigureInvocationCount()
        )
    }

    @Test
    fun execOnIdleWorkspacePrependsConfigureRecovery() {
        val rootfs = harness.fakeRootfs()
        plantPartialDpkgState(rootfs)

        // No live sessions: the exec path's recovery must still fire.
        val result = runBlocking {
            harness.runtime.exec("ws-lx2-4", rootfs.absolutePath, "true")
        }
        assertEquals(0, result.exitCode)
        assertTrue(
            "an idle workspace's exec must still prepend dpkg --configure -a recovery (LX-2)",
            dpkgConfigureInvocationCount() >= 1
        )
    }

    @Test
    fun linexaInitiatedPackageOperationsSerializePerWorkspace() {
        val rootfs = harness.fakeRootfs()

        // A long-running Linexa-initiated package operation (feature
        // provisioner / Package Manager UI shape) on its own scope.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SLOW_EXIT)
        val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstJob = execScope.launch {
            harness.runtime.exec("ws-lx2-5", rootfs.absolutePath, "apt-get install -y big-package")
        }
        val marker = File(prootDir, FakeProot.SLOW_STARTED_FILE)
        runBlocking {
            withTimeout(30_000) {
                while (!marker.exists()) {
                    delay(50)
                }
            }
        }

        // While the first package operation is in flight, exactly ONE guest
        // process may exist: a second Linexa-initiated package operation must
        // WAIT, not start (LX-2: two package operations are never started
        // concurrently by Linexa itself).
        assertEquals(
            "no second package process may spawn while the first owns the package-operation lock",
            1,
            FakeProot.invocationCount(prootDir)
        )

        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        runBlocking {
            harness.runtime.exec("ws-lx2-5", rootfs.absolutePath, "apt-get update")
        }
        assertEquals(
            "the second package operation must run after the first finished",
            2,
            FakeProot.invocationCount(prootDir)
        )

        runBlocking { withTimeout(30_000) { firstJob.join() } }
    }
}
