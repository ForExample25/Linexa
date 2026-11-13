package com.fem.linexa.service.runtime.wakelock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.142 — [WorkspaceWakeLockManager] lifecycle tests.
 *
 * Every case answers one question: **after path X, is the platform lock still
 * held?** That is the only failure mode of a wake lock that matters, and it is
 * why these tests assert [RecordingWakeLock.isHeld] rather than only the
 * manager's own bookkeeping — a manager that cleared its `hold` flow while the
 * platform lock stayed held would look correct and drain the battery.
 *
 * The ceiling is exercised on the test scheduler's **virtual** clock (the same
 * clock drives the manager's `nowMs`), so "the hold is gone at MAX_HOLD_MS" is a
 * deterministic assertion rather than an hour-long sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceWakeLockManagerTest {

    private fun TestScope.manager(lock: RecordingWakeLock): WorkspaceWakeLockManager =
        WorkspaceWakeLockManager(
            wakeLock = lock,
            scope = this,
            nowMs = { testScheduler.currentTime }
        )

    // ── acquire ─────────────────────────────────────────────────────────────

    @Test
    fun nothingIsHeldBeforeTheUserAsks() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        // The lock is opt-in per hold: a running workspace alone must not take one.
        assertNull(manager.hold.value)
        assertFalse(lock.isHeld())
        assertEquals(0, lock.acquireAttempts)
    }

    @Test
    fun aToggleAcquiresForThatWorkspace() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        val hold = manager.toggle("ws-a")

        assertNotNull(hold)
        assertEquals("ws-a", hold!!.workspaceId)
        assertTrue(lock.isHeld())
        assertTrue(manager.isHeldBy("ws-a"))
        assertFalse(manager.isHeldBy("ws-b"))
    }

    @Test
    fun theCeilingIsAlsoHandedToThePlatform() = runTest {
        val lock = RecordingWakeLock()
        manager(lock).toggle("ws-a")

        // Belt and braces: if this process never runs its own expiry timer (frozen,
        // scope cancelled), only the platform timeout can still let go.
        assertEquals(listOf(WakeLockPolicy.MAX_HOLD_MS), lock.acquireTimeouts)
    }

    // ── release paths ───────────────────────────────────────────────────────

    @Test
    fun aSecondToggleReleases() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        val second = manager.toggle("ws-a")

        assertNull("the second tap must mean off", second)
        assertNull(manager.hold.value)
        assertFalse(lock.isHeld())
    }

    @Test
    fun theHoldExpiresAtTheCeiling() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        assertTrue(lock.isHeld())

        // One millisecond short: still held. The bound must be the stated one,
        // not "some time around then".
        advanceTimeBy(WakeLockPolicy.MAX_HOLD_MS - 1L)
        assertTrue("the hold must survive until the ceiling", lock.isHeld())

        advanceTimeBy(2L)
        advanceUntilIdle()
        assertFalse("the hold must be gone at the ceiling", lock.isHeld())
        assertNull(manager.hold.value)
    }

    @Test
    fun remainingTimeCountsDownAndIsNullWhenNothingIsHeld() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        assertNull(manager.remainingMs())

        manager.toggle("ws-a")
        assertEquals(WakeLockPolicy.MAX_HOLD_MS, manager.remainingMs())

        advanceTimeBy(60_000L)
        assertEquals(WakeLockPolicy.MAX_HOLD_MS - 60_000L, manager.remainingMs())

        manager.toggle("ws-a")
        assertNull("a released hold reports no remaining time", manager.remainingMs())
    }

    @Test
    fun aWorkspaceStoppingReleasesItsOwnHold() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        manager.releaseFor("ws-a")

        assertFalse(lock.isHeld())
        assertNull(manager.hold.value)
    }

    @Test
    fun aWorkspaceStoppingDoesNotReleaseAnotherWorkspacesHold() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        manager.releaseFor("ws-b")

        // Scoped on purpose: a teardown reaching past its own workspace is the
        // cross-talk §1.98/§1.100 spent two records removing.
        assertTrue("ws-b's teardown must not drop ws-a's hold", lock.isHeld())
        assertTrue(manager.isHeldBy("ws-a"))
    }

    @Test
    fun releaseAllDropsWhateverIsHeld() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        manager.releaseAll()

        assertFalse(lock.isHeld())
        assertNull(manager.hold.value)
    }

    @Test
    fun releaseAllIsSafeWhenNothingIsHeld() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.releaseAll()
        manager.releaseAll()

        assertFalse(lock.isHeld())
        // Nothing was held and the platform agreed, so no release call is needed.
        assertEquals(0, lock.releaseCalls)
    }

    @Test
    fun releaseAllStillReleasesAPlatformLockTheManagerLostTrackOf() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        // Bookkeeping says nothing is held, the platform disagrees. Shutdown must
        // resolve that towards "not held" — the process is going away, and nothing
        // else would ever release it.
        lock.acquire(WakeLockPolicy.MAX_HOLD_MS)
        assertNull(manager.hold.value)

        manager.releaseAll()

        assertFalse(lock.isHeld())
    }

    @Test
    fun repeatedReleasesAreIdempotent() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        manager.releaseFor("ws-a")
        manager.releaseFor("ws-a")
        manager.releaseAll()

        assertFalse(lock.isHeld())
    }

    // ── never two holders ───────────────────────────────────────────────────

    @Test
    fun anotherWorkspaceReplacesTheHolder() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        val hold = manager.toggle("ws-b")

        assertNotNull(hold)
        assertEquals("ws-b", hold!!.workspaceId)
        assertTrue(manager.isHeldBy("ws-b"))
        assertFalse("ws-a must no longer hold anything", manager.isHeldBy("ws-a"))
        // Released once for the replacement, and the platform lock is live for B.
        assertTrue(lock.isHeld())
    }

    @Test
    fun aReplacementResetsTheCeilingRatherThanInheritingIt() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        advanceTimeBy(WakeLockPolicy.MAX_HOLD_MS / 2)
        manager.toggle("ws-b")

        assertEquals(WakeLockPolicy.MAX_HOLD_MS, manager.remainingMs())
    }

    // ── stale expiry timers ─────────────────────────────────────────────────

    @Test
    fun anExpiryTimerFromAReleasedHoldMustNotDropALaterHold() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        advanceTimeBy(1_000L)
        manager.toggle("ws-a") // release; its timer is still armed for the ceiling
        advanceTimeBy(1_000L)
        manager.toggle("ws-a") // re-arm inside the first timer's window

        // The first timer fires here. Without the generation guard it would drop
        // the hold the user just re-took.
        //
        // `runCurrent`, deliberately not `advanceUntilIdle`: the latter drains
        // EVERY scheduled task, including the re-armed hold's own hour-long
        // timer, so it would expire the hold this case exists to protect.
        advanceTimeBy(WakeLockPolicy.MAX_HOLD_MS - 2_000L + 1L)
        runCurrent()

        assertTrue("the re-armed hold must survive the stale timer", lock.isHeld())
        assertTrue(manager.isHeldBy("ws-a"))
    }

    @Test
    fun theReArmedHoldStillExpiresOnItsOwnClock() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        advanceTimeBy(1_000L)
        manager.toggle("ws-a")
        manager.toggle("ws-a")

        // Re-arming must not become a way to hold the lock forever: the new hold
        // has its own full ceiling and then goes.
        advanceTimeBy(WakeLockPolicy.MAX_HOLD_MS)
        advanceUntilIdle()

        assertFalse(lock.isHeld())
        assertNull(manager.hold.value)
    }

    @Test
    fun aReplacedHoldsTimerMustNotDropTheReplacement() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        manager.toggle("ws-a")
        advanceTimeBy(1_000L)
        manager.toggle("ws-b")

        advanceTimeBy(WakeLockPolicy.MAX_HOLD_MS - 1_000L + 1L)
        runCurrent()

        assertTrue("ws-a's stale timer must not release ws-b's hold", lock.isHeld())
        assertTrue(manager.isHeldBy("ws-b"))
    }

    // ── failure paths ───────────────────────────────────────────────────────

    @Test
    fun aRefusedAcquireRecordsNoHold() = runTest {
        val lock = RecordingWakeLock(granted = false)
        val manager = manager(lock)

        val hold = manager.toggle("ws-a")

        assertNull("a refusal must not be reported as a hold", hold)
        assertNull(manager.hold.value)
        assertFalse(lock.isHeld())
        assertNull(manager.remainingMs())
    }

    @Test
    fun aRefusedAcquireArmsNoTimerAndLeavesTheNextToggleWorking() = runTest {
        val lock = RecordingWakeLock(granted = false)
        val manager = manager(lock)

        manager.toggle("ws-a")
        advanceUntilIdle()

        // No hold was recorded, so the next tap is still an acquire (not a
        // release of a lock that never existed).
        lock.granted = true
        val hold = manager.toggle("ws-a")
        assertNotNull(hold)
        assertTrue(lock.isHeld())
    }

    @Test
    fun anAcquireThatThrowsDegradesToNoHold() = runTest {
        val lock = RecordingWakeLock()
        lock.acquireThrows = IllegalStateException("PowerManager unavailable")
        val manager = manager(lock)

        val hold = manager.toggle("ws-a")

        // Fail-open: a wake lock must never be able to cost the user their session.
        assertNull(hold)
        assertNull(manager.hold.value)
        assertFalse(lock.isHeld())
    }

    @Test
    fun theHoldFlowMirrorsThePlatformState() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        assertEquals(lock.isHeld(), manager.hold.value != null)
        manager.toggle("ws-a")
        assertEquals(lock.isHeld(), manager.hold.value != null)
        manager.releaseFor("ws-a")
        assertEquals(lock.isHeld(), manager.hold.value != null)
    }

    @Test
    fun theHoldRecordsWhenItWasTaken() = runTest {
        val lock = RecordingWakeLock()
        val manager = manager(lock)

        advanceTimeBy(5_000L)
        val hold = manager.toggle("ws-a")!!

        // The acquisition instant is the value the ceiling is computed from, so it
        // must be the real one — not zero, and not a countdown that could be
        // extended by a missed tick.
        assertEquals(testScheduler.currentTime, hold.acquiredAtMs)
        assertEquals(
            WakeLockPolicy.expiryAtMs(hold.acquiredAtMs),
            hold.acquiredAtMs + WakeLockPolicy.MAX_HOLD_MS
        )
    }
}
