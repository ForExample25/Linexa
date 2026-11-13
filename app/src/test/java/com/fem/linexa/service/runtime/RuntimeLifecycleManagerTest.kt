package com.fem.linexa.service.runtime

import android.app.Application
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.wakelock.RecordingWakeLock
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import com.fem.linexa.test.support.ControllableStubPtySession
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
 * §1.97/W4 — [RuntimeLifecycleManager] unit tests: the service-side
 * single-active-workspace backstop, exercised against the REAL
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] through the
 * fake-PRoot harness (controllable stub ptys).
 *
 * Covered:
 *  - start B while A is active ⇒ A fully stopped (no terminals, no session)
 *    before B's first terminal opens;
 *  - A with T0+T1+T2 ⇒ all closed before B starts;
 *  - a stray stop of the evicted A never touches B's terminals;
 *  - idempotent STOP (active, idle, never-started);
 *  - serialized START/STOP/START and START(B)-during-STOP(A) races;
 *  - failed/timed-out A teardown deterministically blocks B
 *    ([StartOutcome.Blocked], B never opens).
 *
 * §1.165 adds the re-entrancy set: two (and ten) concurrent starts of the SAME
 * workspace collapse to one session with no phantom second terminal, a duplicate
 * carries the incumbent's pty and generation without bumping it, and every state
 * a start must still recover from (Stopped, Error, dead guest) still starts.
 *
 * §1.142 additionally pins the wake-lock release side here rather than in
 * [com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManagerTest], because
 * the property is about *this* class: `stopWorkspaceLocked` is the one teardown
 * path every stop trigger funnels through, so "no stop trigger can leave a hold
 * behind" is only true if it is true here.
 */
class RuntimeLifecycleManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var runtimeTrace: RuntimeTrace
    private lateinit var sessionManager: SessionManager
    private lateinit var healthRegistry: com.fem.linexa.service.runtime.WorkspaceHealthRegistry
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var terminals: WorkspaceTerminalManager
    private lateinit var wakeLock: RecordingWakeLock
    private lateinit var wakeLockManager: WorkspaceWakeLockManager
    private lateinit var manager: RuntimeLifecycleManager

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
        harness.create()
        harness.ptyFactory.controllable = true
        runtimeTrace = RuntimeTrace()
        healthRegistry = com.fem.linexa.service.runtime.WorkspaceHealthRegistry()
        sessionManager = SessionManager(runtimeTrace, healthRegistry)
        fileManager = WorkspaceFileManager(Mockito.mock(Application::class.java))
        terminals = WorkspaceTerminalManager(harness.runtime, runtimeTrace)
        wakeLock = RecordingWakeLock()
        wakeLockManager = newWakeLockManager()
        manager = RuntimeLifecycleManager(sessionManager, terminals, fileManager, runtimeTrace, healthRegistry, wakeLockManager)
    }

    /**
     * §1.142: a real manager over the recording lock. Its own scope (not the
     * test's) mirrors production — the expiry timer must outlive any single
     * caller's scope — and no test here advances an hour, so the timer never
     * fires; these cases are about the *explicit* release paths.
     */
    private fun newWakeLockManager(): WorkspaceWakeLockManager =
        WorkspaceWakeLockManager(
            wakeLock = wakeLock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

    private suspend fun start(workspaceId: String): StartOutcome =
        manager.requestStart(workspaceId, harness.fakeRootfs().absolutePath)

    private fun rootfs(): String = harness.fakeRootfs().absolutePath

    private suspend fun assertStopped(workspaceId: String) {
        assertFalse("workspace $workspaceId must have no live T0", harness.runtime.isTerminalAlive(workspaceId, DEFAULT_TERMINAL_ID))
        assertNull("workspace $workspaceId must resolve no pty", terminals.getPtySession(workspaceId, DEFAULT_TERMINAL_ID))
        assertTrue("workspace $workspaceId must be forgotten by the manager", terminals.terminalStates.value[workspaceId].isNullOrEmpty())
        assertEquals("workspace $workspaceId state must be Stopped", RuntimeState.Stopped, sessionManager.getState(workspaceId))
        assertNull("workspace $workspaceId session must be removed", sessionManager.getSession(workspaceId))
    }

    @Test
    fun startingWorkspaceBStopsActiveWorkspaceA() = runBlocking {
        assertTrue("A must start", start("ws-a") is StartOutcome.Started)
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))

        val b = start("ws-b")
        assertTrue("B must start", b is StartOutcome.Started)

        // A fully stopped before B's first terminal opened.
        assertStopped("ws-a")
        assertEquals("B becomes the active workspace", "ws-b", manager.activeWorkspaceId.value)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun launchConsumesT0ThroughAllocatorSoNextUiTerminalIsT1() = runBlocking {
        // §W6 bug regression: the launch path used to open the workspace's
        // first terminal directly with the default id "T0" and NEVER called
        // nextTerminalId, so the per-workspace counter stayed 0 while T0 was
        // live — the first UI "New Terminal" then got "T0" again and replaced
        // the running launch terminal (SIGKILL -9 → Error → closeWorkspace).
        assertTrue("A must start", start("ws-a") is StartOutcome.Started)
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))

        // The launch must have allocated T0 through the allocator, so the next
        // UI-created terminal is T1 and never collides with the live T0.
        assertEquals("first UI terminal after launch must be T1", "T1", terminals.nextTerminalId("ws-a"))
        assertTrue("launch terminal must be untouched", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun startingBClosesAllTerminalsOfA() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        val root = rootfs()
        terminals.openTerminal("ws-a", root, "T1", asRoot = false)
        terminals.openTerminal("ws-a", root, "T2", asRoot = false)
        assertEquals("A must have T0+T1+T2", 3, terminals.terminalStates.value["ws-a"]?.size)

        assertTrue(start("ws-b") is StartOutcome.Started)

        assertFalse("A T0 must be closed", harness.runtime.isTerminalAlive("ws-a", "T0"))
        assertFalse("A T1 must be closed", harness.runtime.isTerminalAlive("ws-a", "T1"))
        assertFalse("A T2 must be closed", harness.runtime.isTerminalAlive("ws-a", "T2"))
        assertNull(terminals.getPtySession("ws-a", "T1"))
        assertTrue("A must be forgotten entirely", terminals.terminalStates.value["ws-a"].isNullOrEmpty())
        assertTrue("B T0 must be open", harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun stoppingEvictedWorkspaceDoesNotTouchActiveWorkspace() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        assertTrue(start("ws-b") is StartOutcome.Started)
        val root = rootfs()
        terminals.openTerminal("ws-b", root, "T1", asRoot = false)
        terminals.openTerminal("ws-b", root, "T2", asRoot = false)

        // A stray STOP for the evicted A must not disturb B's terminals.
        assertTrue(manager.requestStop("ws-a"))

        assertEquals("ws-b", manager.activeWorkspaceId.value)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
        assertTrue(harness.runtime.isTerminalAlive("ws-b", "T1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-b", "T2"))
    }

    @Test
    fun stopIsIdempotent() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        assertTrue(manager.requestStop("ws-a"))
        assertTrue("second stop must succeed idempotently", manager.requestStop("ws-a"))
        assertNull(manager.activeWorkspaceId.value)
        assertStopped("ws-a")
    }

    @Test
    fun stopOfNeverStartedWorkspaceIsSafe() = runBlocking {
        assertTrue(manager.requestStop("never-started"))
        assertNull(manager.activeWorkspaceId.value)
    }

    @Test
    fun serializedStartStopStartRace() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)

        val stopJob = async { manager.requestStop("ws-a") }
        yield()
        val startJob = async { start("ws-b") }
        val stopRes = stopJob.await()
        val startRes = startJob.await()

        assertTrue("stop must succeed in either order", stopRes)
        assertTrue("B must start after A's stop settles (either order)", startRes is StartOutcome.Started)
        assertEquals("final active workspace is B", "ws-b", manager.activeWorkspaceId.value)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun startDuringStopIsSerialized() = runBlocking {
        // A is running; issue STOP(A) and START(B) "concurrently". The manager's
        // single transition mutex serializes them, so B can never open while A
        // is still active.
        assertTrue(start("ws-a") is StartOutcome.Started)
        val stopJob = async { manager.requestStop("ws-a") }
        yield()
        val startJob = async { start("ws-b") }
        startJob.await()
        stopJob.await()

        assertEquals("B settles as the active workspace", "ws-b", manager.activeWorkspaceId.value)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
        assertFalse("A must be fully stopped", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun failedTeardownBlocksNewStart() {
        runBlocking {
            // A teardown that hangs past the timeout must deterministically block B.
            val hang = CompletableDeferred<Unit>()
            val hangingRuntime = object : ContainerRuntime by harness.runtime {
                override suspend fun closeWorkspaceSessions(workspaceId: String) {
                    hang.await()
                }
            }
            val hangingTerminals = WorkspaceTerminalManager(hangingRuntime, runtimeTrace)
            val blockingManager = RuntimeLifecycleManager(
                sessionManager,
                hangingTerminals,
                fileManager,
                runtimeTrace,
                healthRegistry,
                wakeLockManager,
                teardownTimeoutMs = 100L
            )

            assertTrue(blockingManager.requestStart("ws-a", rootfs()) is StartOutcome.Started)
            assertTrue(hangingTerminals.terminalStates.value["ws-a"]?.isNotEmpty() == true)

            val b = blockingManager.requestStart("ws-b", rootfs())

            assertTrue("B must be blocked by the failed A teardown", b is StartOutcome.Blocked)
            assertNull("B must not become active", blockingManager.activeWorkspaceId.value.takeIf { it == "ws-b" })
            assertTrue("B must not have opened a terminal", hangingTerminals.terminalStates.value["ws-b"].isNullOrEmpty())
            // The gate stays on the failed workspace so a later start/stop retries its teardown.
            assertEquals("ws-a", blockingManager.activeWorkspaceId.value)

            // Release the hang so the (already cancelled) teardown coroutine unwinds.
            hang.complete(Unit)
        }
    }

    // ── §1.97/W5 startup recovery sweep (serialized once-per-process) ──

    private fun seedStaleScope(scope: String) {
        val dir = File(harness.bootstrapManager.prootTmpDir, scope)
        require(dir.mkdirs()) { "could not seed stale scope $scope" }
        File(dir, "marker").writeText("stale")
    }

    @Test
    fun startupSweepRunsBeforeFirstStart() = runBlocking {
        // Stale scratch from a "dead previous process"; no explicit sweep call —
        // the first START must run the sweep itself before opening a workspace.
        seedStaleScope("ws-stale/T0")
        seedStaleScope("ws-stale/T1")

        val a = start("ws-a")

        assertTrue("workspace must start normally", a is StartOutcome.Started)
        assertFalse("stale scope ws-stale/T0 must be swept before the start", File(harness.bootstrapManager.prootTmpDir, "ws-stale/T0").exists())
        assertFalse("stale scope ws-stale/T1 must be swept before the start", File(harness.bootstrapManager.prootTmpDir, "ws-stale/T1").exists())
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertEquals("ws-a", manager.activeWorkspaceId.value)
    }

    @Test
    fun startupSweepIsOncePerProcess() = runBlocking {
        // Two DISTINCT top-level scopes → both released on the first sweep.
        seedStaleScope("ws-stale/T0")
        seedStaleScope("ws-other/T0")

        val first = manager.startupSweep()
        val second = manager.startupSweep()

        assertEquals("first sweep releases the stale scopes", 2, first.releasedScopes)
        assertEquals(0, first.failures)
        assertEquals("second sweep is a no-op (once per process)", 0, second.releasedScopes)
        assertEquals(0, second.staleSocketsCleaned)
    }

    @Test
    fun sweepFailureDoesNotBlockStart() = runBlocking {
        // A sweep that throws must be reported, not thrown, and must not stop a
        // later START (best-effort recovery — never fatal to the lifecycle).
        val failingRuntime = object : ContainerRuntime by harness.runtime {
            override suspend fun startupRecoverySweep(): RecoverySweepReport {
                throw IllegalStateException("sweep exploded")
            }
        }
        val failingTerminals = WorkspaceTerminalManager(failingRuntime, runtimeTrace)
        val failingManager = RuntimeLifecycleManager(sessionManager, failingTerminals, fileManager, runtimeTrace, healthRegistry, wakeLockManager)

        val report = failingManager.startupSweep()

        assertEquals("failure must be represented deterministically", 1, report.failures)
        val a = failingManager.requestStart("ws-a", rootfs())
        assertTrue("a failed sweep must not block a start", a is StartOutcome.Started)
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertEquals("ws-a", failingManager.activeWorkspaceId.value)
    }

    @Test
    fun sweepCannotKillActiveWorkspace() = runBlocking {
        // Once a workspace is up, the sweep is already done (it ran before the
        // start) — a later sweep call is a no-op that cannot touch the active
        // workspace's live terminal or its fresh PROOT_TMP_DIR scope.
        val a = start("ws-a") as StartOutcome.Started

        val report = manager.startupSweep()

        assertEquals(0, report.releasedScopes)
        assertTrue("active workspace terminal must survive", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertTrue("fresh scope must survive the no-op sweep", File(harness.bootstrapManager.prootTmpDir, "ws-a/T0").exists())
        assertEquals(a.pty.pid, harness.runtime.getTerminal("ws-a", DEFAULT_TERMINAL_ID)?.pid)
    }

    @Test
    fun startupSweepIsSerializedWithConcurrentStart() = runBlocking {
        seedStaleScope("ws-stale/T0")
        val sweepJob = async { manager.startupSweep() }
        yield()
        val startJob = async { start("ws-b") }

        val report = sweepJob.await()
        val b = startJob.await()

        assertTrue("concurrent START must succeed", b is StartOutcome.Started)
        assertTrue("stale scope must be swept", File(harness.bootstrapManager.prootTmpDir, "ws-stale/T0").exists().not())
        assertEquals(1, report.releasedScopes)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
        assertEquals("ws-b", manager.activeWorkspaceId.value)
    }

    // ── §1.142: the wake lock cannot outlive the workspace ──────────────────

    @Test
    fun stoppingAWorkspaceReleasesItsWakeLock() = runBlocking {
        start("ws-a")
        wakeLockManager.toggle("ws-a")
        assertTrue(wakeLock.isHeld())

        assertTrue(manager.requestStop("ws-a"))

        assertFalse("a stopped workspace must not keep the CPU awake", wakeLock.isHeld())
        assertNull(wakeLockManager.hold.value)
    }

    @Test
    fun startingAnotherWorkspaceReleasesTheEvictedWorkspacesWakeLock() = runBlocking {
        start("ws-a")
        wakeLockManager.toggle("ws-a")

        // The eviction path (§1.97's backstop) is a stop the user never asked
        // for, so it is the easiest one to forget — A's hold must go with A.
        assertTrue(start("ws-b") is StartOutcome.Started)

        assertFalse("the evicted workspace's hold must be gone", wakeLock.isHeld())
        assertFalse(wakeLockManager.isHeldBy("ws-a"))
        assertFalse("B must not inherit a hold it never asked for", wakeLockManager.isHeldBy("ws-b"))
    }

    @Test
    fun shutdownReleasesTheWakeLock() = runBlocking {
        start("ws-a")
        wakeLockManager.toggle("ws-a")

        manager.shutdown()

        assertFalse("service teardown must release the lock", wakeLock.isHeld())
        assertNull(wakeLockManager.hold.value)
    }

    @Test
    fun shutdownReleasesAHoldWhoseWorkspaceHasNoSessionLeft() = runBlocking {
        start("ws-a")
        wakeLockManager.toggle("ws-a")
        // The workspace is already stopped by another path, then the hold is
        // re-taken (a stale notification tap): shutdown()'s per-session loop
        // iterates past it, so only the unconditional releaseAll can let go.
        manager.requestStop("ws-a")
        wakeLockManager.toggle("ws-a")
        assertTrue(wakeLock.isHeld())

        manager.shutdown()

        assertFalse("shutdown must release a hold with no session behind it", wakeLock.isHeld())
    }

    @Test
    fun aWedgedTeardownStillReleasesTheWakeLock() {
        runBlocking {
            // The worst case for this feature: the workspace is unusable AND the
            // phone never sleeps. The release is in a `finally`, so the timed-out
            // path lets go too.
            val hang = CompletableDeferred<Unit>()
            val hangingRuntime = object : ContainerRuntime by harness.runtime {
                override suspend fun closeWorkspaceSessions(workspaceId: String) {
                    hang.await()
                }
            }
            val hangingTerminals = WorkspaceTerminalManager(hangingRuntime, runtimeTrace)
            val blockingManager = RuntimeLifecycleManager(
                sessionManager,
                hangingTerminals,
                fileManager,
                runtimeTrace,
                healthRegistry,
                wakeLockManager,
                teardownTimeoutMs = 100L
            )
            blockingManager.requestStart("ws-a", rootfs())
            wakeLockManager.toggle("ws-a")

            assertFalse("a wedged teardown must report failure", blockingManager.requestStop("ws-a"))

            assertFalse("a wedged teardown must still release the lock", wakeLock.isHeld())
            hang.complete(Unit)
        }
    }

    @Test
    fun anAlreadyStoppedWorkspaceStillReleasesAStaleHold() = runBlocking {
        start("ws-a")
        manager.requestStop("ws-a")
        // A stale notification action could take a lock for a workspace with no
        // guest left; the next stop of that workspace must clear it.
        wakeLockManager.toggle("ws-a")
        assertTrue(wakeLock.isHeld())

        assertTrue("stopping an already-stopped workspace is a no-op success", manager.requestStop("ws-a"))

        assertFalse(wakeLock.isHeld())
    }

    @Test
    fun stoppingAWorkspaceThatNeverHeldALockLeavesTheHolderAlone() = runBlocking {
        start("ws-a")
        wakeLockManager.toggle("ws-a")

        // requestStop on a never-started workspace is idempotent, and must not
        // reach past its own workspace into the live holder's lock.
        assertTrue(manager.requestStop("ws-never"))

        assertTrue("ws-a's hold must survive an unrelated stop", wakeLock.isHeld())
        assertTrue(wakeLockManager.isHeldBy("ws-a"))
    }

    @Test
    fun noWakeLockIsTakenJustByRunningAWorkspace() = runBlocking {
        start("ws-a")

        // Opt-in per hold: a Running workspace is not a reason to keep the CPU
        // awake, or Linexa becomes a background cost nobody asked for.
        assertFalse(wakeLock.isHeld())
        assertEquals(0, wakeLock.acquireAttempts)
        assertNull(wakeLockManager.hold.value)
    }

    // ── §1.165: requestStart re-entrancy (phantom second terminal / SIGKILL) ──

    /**
     * The reported race, driven at this layer: two rapid starts for the SAME
     * workspace. The transition mutex always made them sequential, so the second
     * one ran the entire start body against a workspace that already had a live
     * session — allocating a fresh id (correctly! the allocator must not reuse a
     * live one) and opening a *second* terminal beside the healthy first. That
     * extra terminal is the phantom.
     */
    @Test
    fun twoConcurrentStartsOfTheSameWorkspaceOpenExactlyOneTerminal() = runBlocking {
        val first = async { start("ws-a") }
        val second = async { start("ws-a") }
        val a = first.await()
        val b = second.await()

        val outcomes = listOf(a, b)
        assertEquals(
            "exactly one of the two concurrent starts may actually start: $outcomes",
            1,
            outcomes.count { it is StartOutcome.Started }
        )
        assertEquals(
            "the other must be recognised as re-entrant: $outcomes",
            1,
            outcomes.count { it is StartOutcome.AlreadyStarted }
        )
        assertEquals(
            "no phantom second terminal: the workspace must have exactly one",
            1,
            terminals.terminalStates.value["ws-a"]?.size
        )
        assertTrue("the one terminal must be T0", terminals.terminalStates.value["ws-a"]?.containsKey(DEFAULT_TERMINAL_ID) == true)
        assertFalse("T1 must never have been opened", harness.runtime.isTerminalAlive("ws-a", "T1"))
        assertTrue("the live terminal must be T0", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
    }

    /**
     * The duplicate must hand back the *incumbent's* pty and the *incumbent's*
     * generation. Both matter: the pty so a caller can still resolve the session
     * it asked for, and the generation so the duplicate's own unwind cannot pass
     * the §P0 `isCurrentSession` guard on behalf of a session it did not start
     * (that pass is what let a duplicate call `closeWorkspace` on a live process
     * group — the SIGKILL).
     */
    @Test
    fun aReEntrantStartReturnsTheIncumbentPtyAndGenerationWithoutBumpingIt() = runBlocking {
        val started = start("ws-a") as StartOutcome.Started
        val generationAfterFirst = manager.currentGeneration("ws-a")

        val duplicate = start("ws-a")

        assertTrue("a duplicate start must be AlreadyStarted, was $duplicate", duplicate is StartOutcome.AlreadyStarted)
        duplicate as StartOutcome.AlreadyStarted
        assertEquals("the duplicate must resolve the incumbent's pty", started.pty.id, duplicate.pty.id)
        assertEquals("the duplicate must carry the incumbent's generation", started.generation, duplicate.generation)
        assertEquals(
            "a duplicate must NOT bump the generation — that would invalidate the live session's own unwind",
            generationAfterFirst,
            manager.currentGeneration("ws-a")
        )
        assertTrue(
            "the incumbent must still own the generation guard",
            manager.isCurrentSession("ws-a", started.generation)
        )
    }

    /** A duplicate start must not disturb the session it duplicates in any way. */
    @Test
    fun aReEntrantStartLeavesTheLiveSessionUntouched() = runBlocking {
        val started = start("ws-a") as StartOutcome.Started
        // Promote to Running like the supervisor does, so the guard is exercised
        // in the state a double-tap actually hits.
        sessionManager.updateState("ws-a", RuntimeState.Running(started.pty.pid, java.time.Instant.now()))
        val sessionBefore = sessionManager.getSession("ws-a")

        assertTrue(start("ws-a") is StartOutcome.AlreadyStarted)

        assertTrue("the live pty must survive a duplicate start", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertEquals("the pty must be the same object", started.pty.pid, harness.runtime.getTerminal("ws-a", DEFAULT_TERMINAL_ID)?.pid)
        assertTrue(
            "a duplicate must not reset Running back to Starting",
            sessionManager.getState("ws-a") is RuntimeState.Running
        )
        assertEquals("the session record must not be re-created", sessionBefore, sessionManager.getSession("ws-a"))
        assertEquals("the gate must be unchanged", "ws-a", manager.activeWorkspaceId.value)
        assertEquals(
            "the allocator must not have been consumed by the duplicate",
            "T1",
            terminals.nextTerminalId("ws-a")
        )
    }

    /**
     * The guard must not become a lock-out. Every state a start legitimately
     * recovers from — Stopped, Error, and a session whose pty died — must still
     * start. This is the difference between guarding a duplicate and breaking
     * restart.
     */
    @Test
    fun aStoppedWorkspaceStillStarts() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        assertTrue(manager.requestStop("ws-a"))

        val restarted = start("ws-a")

        assertTrue("a stopped workspace must be startable again, was $restarted", restarted is StartOutcome.Started)
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
    }

    @Test
    fun anErroredWorkspaceStillStarts() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        sessionManager.updateState("ws-a", RuntimeState.Error("previous launch blew up"))

        val restarted = start("ws-a")

        assertTrue("an errored workspace must be startable again, was $restarted", restarted is StartOutcome.Started)
    }

    @Test
    fun aWorkspaceWhoseGuestDiedStillStarts() = runBlocking {
        val started = start("ws-a") as StartOutcome.Started
        sessionManager.updateState("ws-a", RuntimeState.Running(started.pty.pid, java.time.Instant.now()))
        // The state still says Running, the session record still exists, but the
        // guest is gone. Treating that as "already started" would make one dead
        // guest a permanently unstartable workspace.
        (started.pty as ControllableStubPtySession).completeExit(137)

        val restarted = start("ws-a")

        assertTrue("a dead guest must not lock the workspace out, was $restarted", restarted is StartOutcome.Started)
        assertTrue(
            "the restart must mint a NEW generation",
            restarted is StartOutcome.Started && restarted.generation > started.generation
        )
    }

    /**
     * The guard is per-workspace, so it cannot leak across workspaces: starting B
     * while A is live is the legitimate eviction path (§1.97/W4), not a duplicate,
     * and must keep working exactly as before.
     */
    @Test
    fun theReEntrancyGuardDoesNotAffectADifferentWorkspace() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)

        val b = start("ws-b")

        assertTrue("starting a DIFFERENT workspace is never re-entrant, was $b", b is StartOutcome.Started)
        assertStopped("ws-a")
        assertEquals("ws-b", manager.activeWorkspaceId.value)
        assertTrue(harness.runtime.isTerminalAlive("ws-b", DEFAULT_TERMINAL_ID))
    }

    /**
     * The legitimate multi-terminal case is untouched: the guard sits on the
     * workspace-*start* path only, so opening extra terminals through the normal
     * allocator + [WorkspaceTerminalManager.openTerminal] route (what the UI's
     * "new terminal" does through [RuntimeBinder]) still works after a duplicate
     * start was refused.
     */
    @Test
    fun explicitExtraTerminalsStillOpenAfterAReEntrantStartIsRefused() = runBlocking {
        assertTrue(start("ws-a") is StartOutcome.Started)
        assertTrue(start("ws-a") is StartOutcome.AlreadyStarted)

        val root = rootfs()
        val t1 = terminals.nextTerminalId("ws-a")
        terminals.openTerminal("ws-a", root, t1, asRoot = false)
        val t2 = terminals.nextTerminalId("ws-a")
        terminals.openTerminal("ws-a", root, t2, asRoot = false)

        assertEquals("the user's own terminals must still open", listOf("T1", "T2"), listOf(t1, t2))
        assertEquals(3, terminals.terminalStates.value["ws-a"]?.size)
        assertTrue(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertTrue(harness.runtime.isTerminalAlive("ws-a", "T1"))
        assertTrue(harness.runtime.isTerminalAlive("ws-a", "T2"))
    }

    /**
     * A workspace whose T0 was closed and replaced (§1.106 never-zero-terminals)
     * is still *started* — the guard resolves the last-active terminal, not a
     * hardcoded T0, so a replacement session is not mistaken for a dead one.
     */
    @Test
    fun aWorkspaceWhoseT0WasReplacedIsStillRecognisedAsStarted() = runBlocking {
        val started = start("ws-a") as StartOutcome.Started
        sessionManager.updateState("ws-a", RuntimeState.Running(started.pty.pid, java.time.Instant.now()))
        val replacementId = terminals.closeTerminal("ws-a", DEFAULT_TERMINAL_ID)
        assertEquals("closing the only terminal must open a replacement", "T1", replacementId)

        val duplicate = start("ws-a")

        assertTrue("the replaced session is still a session, was $duplicate", duplicate is StartOutcome.AlreadyStarted)
        assertEquals(
            "still exactly one terminal — no phantom",
            1,
            terminals.terminalStates.value["ws-a"]?.size
        )
        assertTrue(harness.runtime.isTerminalAlive("ws-a", "T1"))
    }

    /** Many simultaneous starts collapse to one session, not N. */
    @Test
    fun tenConcurrentStartsOfTheSameWorkspaceOpenOneTerminal() = runBlocking {
        val outcomes = (1..10).map { async { start("ws-a") } }.map { it.await() }

        assertEquals(
            "exactly one real start out of ten: $outcomes",
            1,
            outcomes.count { it is StartOutcome.Started }
        )
        assertEquals(9, outcomes.count { it is StartOutcome.AlreadyStarted })
        assertEquals(1, terminals.terminalStates.value["ws-a"]?.size)
        assertEquals(
            "all duplicates must report the one real generation",
            setOf(manager.currentGeneration("ws-a")),
            outcomes.map { it.generation }.toSet()
        )
    }
}
