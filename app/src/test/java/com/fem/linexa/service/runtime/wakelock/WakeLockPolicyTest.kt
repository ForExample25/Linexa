package com.fem.linexa.service.runtime.wakelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.142 — [WakeLockPolicy] is pure arithmetic + a total decision function, so
 * the bound and the toggle semantics are assertable without a device or a clock.
 *
 * The point of testing this separately from [WorkspaceWakeLockManager] is that
 * the *bound itself* is the safety property. A ceiling that only exists as a
 * `delay(...)` inside a coroutine can be silently removed by a refactor; a
 * ceiling that is arithmetic here fails a test.
 */
class WakeLockPolicyTest {

    // ── the bound ───────────────────────────────────────────────────────────

    @Test
    fun theCeilingIsSixtyMinutes() {
        assertEquals(60L * 60L * 1000L, WakeLockPolicy.MAX_HOLD_MS)
    }

    @Test
    fun theCeilingIsBoundedAndPositive() {
        // The whole requirement in one assertion: a hold cannot be indefinite.
        assertTrue("the hold must have a positive bound", WakeLockPolicy.MAX_HOLD_MS > 0L)
        assertTrue(
            "the hold must not be so long it is effectively indefinite",
            WakeLockPolicy.MAX_HOLD_MS <= 2L * 60L * 60L * 1000L
        )
    }

    @Test
    fun expiryIsMeasuredFromTheAcquisition() {
        assertEquals(1_000L + WakeLockPolicy.MAX_HOLD_MS, WakeLockPolicy.expiryAtMs(1_000L))
    }

    @Test
    fun remainingCountsDownFromTheCeiling() {
        val acquired = 10_000L
        assertEquals(WakeLockPolicy.MAX_HOLD_MS, WakeLockPolicy.remainingMs(acquired, acquired))
        assertEquals(
            WakeLockPolicy.MAX_HOLD_MS - 60_000L,
            WakeLockPolicy.remainingMs(acquired + 60_000L, acquired)
        )
    }

    @Test
    fun remainingIsClampedAtZeroAndNeverNegative() {
        val acquired = 0L
        // A late refresh must not render a countdown running past zero.
        assertEquals(0L, WakeLockPolicy.remainingMs(WakeLockPolicy.MAX_HOLD_MS, acquired))
        assertEquals(0L, WakeLockPolicy.remainingMs(WakeLockPolicy.MAX_HOLD_MS + 5_000L, acquired))
    }

    @Test
    fun expiryIsReachedExactlyAtTheCeilingNotBefore() {
        val acquired = 500L
        assertFalse(
            "a hold one ms short of the ceiling is still live",
            WakeLockPolicy.isExpired(acquired + WakeLockPolicy.MAX_HOLD_MS - 1L, acquired)
        )
        assertTrue(
            "a hold at the ceiling is expired",
            WakeLockPolicy.isExpired(acquired + WakeLockPolicy.MAX_HOLD_MS, acquired)
        )
    }

    @Test
    fun theCeilingDoesNotSlideWithActivity() {
        // remainingMs depends ONLY on the acquisition instant: there is no input
        // by which continued use could extend a hold.
        val acquired = 1_000L
        val halfway = acquired + WakeLockPolicy.MAX_HOLD_MS / 2
        assertEquals(
            WakeLockPolicy.remainingMs(halfway, acquired),
            WakeLockPolicy.remainingMs(halfway, acquired)
        )
        assertTrue(
            "later reads must report strictly less time left",
            WakeLockPolicy.remainingMs(halfway + 1L, acquired) <
                WakeLockPolicy.remainingMs(halfway, acquired)
        )
    }

    // ── the toggle ──────────────────────────────────────────────────────────

    @Test
    fun nothingHeldMeansAcquire() {
        assertEquals(
            WakeLockPolicy.Decision.Acquire("ws-a"),
            WakeLockPolicy.toggle(heldBy = null, requested = "ws-a")
        )
    }

    @Test
    fun theSameWorkspaceTwiceMeansRelease() {
        // Rule 2: the notification action is the only affordance, so the second
        // tap must mean off — there is no separate release control to find.
        assertEquals(
            WakeLockPolicy.Decision.Release("ws-a"),
            WakeLockPolicy.toggle(heldBy = "ws-a", requested = "ws-a")
        )
    }

    @Test
    fun aDifferentWorkspaceReplacesRatherThanAdds() {
        // Rule 1: never two holders (§3.2 runs one workspace at a time, so a
        // second holder could only be a leak from a workspace already gone).
        assertEquals(
            WakeLockPolicy.Decision.Replace(release = "ws-a", acquire = "ws-b"),
            WakeLockPolicy.toggle(heldBy = "ws-a", requested = "ws-b")
        )
    }

    @Test
    fun toggleIsTotalOverEveryHolderState() {
        // Every combination resolves to exactly one decision, so the manager has
        // no branch of its own that could miss a case.
        val holders = listOf(null, "ws-a", "ws-b")
        for (holder in holders) {
            val decision = WakeLockPolicy.toggle(holder, "ws-a")
            assertTrue(
                "holder=$holder produced no decision",
                decision is WakeLockPolicy.Decision.Acquire ||
                    decision is WakeLockPolicy.Decision.Release ||
                    decision is WakeLockPolicy.Decision.Replace
            )
        }
    }

    @Test
    fun aReplaceAlwaysNamesBothWorkspaces() {
        val decision = WakeLockPolicy.toggle("ws-a", "ws-b") as WakeLockPolicy.Decision.Replace
        // The released one must be the previous holder, not the requested one —
        // reversing these would release the lock just taken.
        assertEquals("ws-a", decision.release)
        assertEquals("ws-b", decision.acquire)
    }
}
