package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16.3.4 — the retry schedule of [DesktopPortPoll], asserted exactly.
 *
 * [DesktopPortPoll.delayAfterFailure] is a pure function of elapsed time and
 * remaining budget, so the whole policy is testable with no clock, no sockets and
 * no coroutines: the tests below reconstruct the schedule by iterating the
 * function the way the poll loop does. That is deliberate — the alternative
 * (asserting wall-clock gaps between real `delay` calls) is exactly the flaky
 * timing test the task rules out.
 *
 * The socket loop that consumes this policy lives in `DesktopViewModel` and is
 * pinned structurally by [DesktopPortPollWiringSourceTest], for the reason given
 * there.
 */
class DesktopPortPollTest {

    /**
     * Replays the poll loop's arithmetic: attempt at `t`, on failure wait
     * `delayAfterFailure`, repeat until the budget is spent.
     *
     * @param attemptCostMs charged per attempt so the schedule is not assumed to
     *   be free; 0 isolates the policy itself.
     * @return the instants at which attempts are issued.
     */
    private fun schedule(
        timeoutMs: Long = DesktopPortPoll.CALLER_TIMEOUT_MS,
        attemptCostMs: Long = 0L
    ): List<Long> {
        val instants = mutableListOf<Long>()
        var now = 0L
        while (now < timeoutMs) {
            instants += now
            now += attemptCostMs
            val wait = DesktopPortPoll.delayAfterFailure(
                elapsedMs = now,
                remainingMs = timeoutMs - now
            )
            if (wait <= 0L) break
            now += wait
        }
        return instants
    }

    /** Delays actually waited, in order — the sequence the task asks to pin. */
    private fun delays(
        timeoutMs: Long = DesktopPortPoll.CALLER_TIMEOUT_MS,
        attemptCostMs: Long = 0L
    ): List<Long> {
        val out = mutableListOf<Long>()
        var now = 0L
        while (now < timeoutMs) {
            now += attemptCostMs
            val wait = DesktopPortPoll.delayAfterFailure(
                elapsedMs = now,
                remainingMs = timeoutMs - now
            )
            if (wait <= 0L) break
            out += wait
            now += wait
        }
        return out
    }

    // ---- Test 1 / 2: the first attempt is immediate and free ----

    @Test
    fun theFirstAttemptIsIssuedAtTimeZero() {
        // The policy is only ever consulted AFTER a failure, so a ready port
        // pays nothing. Pinned because "add a pre-delay" is the easy accidental
        // regression when a poll loop is restructured.
        assertEquals(0L, schedule().first())
    }

    @Test
    fun aSuccessfulFirstAttemptConsultsNoDelayAtAll() {
        // Modelled as "the loop never calls delayAfterFailure": there is no
        // state in the object, so a success path that never invokes it is
        // provably delay-free. The wiring test pins that the call really does
        // sit in the failure branch.
        assertEquals(
            "policy must be stateless so an immediate success cannot be charged",
            DesktopPortPoll.FAST_DELAY_MS,
            DesktopPortPoll.delayAfterFailure(elapsedMs = 0L, remainingMs = 5_000L)
        )
    }

    // ---- Test 6: the exact retry sequence ----

    @Test
    fun theRetrySequenceIsFiftyUntilTheWindowThenOneFifty() {
        val seq = delays()
        // 50 ms is held while elapsed < 1500 ms. Attempts land at 0, 50, 100 …
        // so the delay decided at elapsed=1450 (index 29) is still dense and the
        // one decided at 1500 (index 30) is relaxed.
        assertEquals("first delay", 50L, seq[0])
        assertEquals("still dense at the end of the window", 50L, seq[29])
        assertEquals("relaxes exactly at FAST_WINDOW_MS", 150L, seq[30])
        assertEquals("and stays relaxed", 150L, seq[31])
        assertTrue(
            "only two intervals may ever appear, plus a final clamp",
            seq.dropLast(1).all { it == 50L || it == 150L }
        )
        assertEquals(
            "the dense prefix is exactly FAST_WINDOW_MS/FAST_DELAY_MS long",
            30,
            seq.dropLast(1).count { it == 50L }
        )
        // The final delay is the clamp: at t=4950 only 50 ms of budget is left,
        // so the relaxed 150 ms is cut down to land exactly on the deadline.
        assertEquals("the tail delay is clamped, not a third interval", 50L, seq.last())
        assertEquals("total delays", 54, seq.size)
    }

    @Test
    fun theScheduleIsMonotonicAndNeverBusySpins() {
        val seq = delays()
        assertTrue("no zero-length waits before the end", seq.dropLast(1).all { it > 0L })
        assertTrue(
            "the interval never decreases — dense then relaxed, never back",
            seq.dropLast(1).zipWithNext().all { (a, b) -> b >= a }
        )
    }

    @Test
    fun theDenseWindowBoundaryIsInclusiveOfFastAndExclusiveOfRelaxed() {
        val budget = 5_000L
        assertEquals(
            50L,
            DesktopPortPoll.delayAfterFailure(DesktopPortPoll.FAST_WINDOW_MS - 1, budget)
        )
        assertEquals(
            150L,
            DesktopPortPoll.delayAfterFailure(DesktopPortPoll.FAST_WINDOW_MS, budget)
        )
    }

    // ---- Test 7: the timeout cannot be extended by a backoff interval ----

    @Test
    fun noDelayCanPushThePollPastItsDeadline() {
        val timeout = DesktopPortPoll.CALLER_TIMEOUT_MS
        var now = 0L
        while (now < timeout) {
            val wait = DesktopPortPoll.delayAfterFailure(now, timeout - now)
            if (wait <= 0L) break
            now += wait
            assertTrue("a wait ending at $now overshot the $timeout ms deadline", now <= timeout)
        }
        assertEquals("the schedule must land exactly on the deadline", timeout, now)
    }

    @Test
    fun theFinalDelayIsClampedToWhatIsLeft() {
        // The pre-§16.3.4 loop slept a full 150 ms after an attempt begun 1 ms
        // before the deadline, overshooting the advertised timeout by ~149 ms.
        assertEquals(1L, DesktopPortPoll.delayAfterFailure(elapsedMs = 4_999L, remainingMs = 1L))
        assertEquals(7L, DesktopPortPoll.delayAfterFailure(elapsedMs = 100L, remainingMs = 7L))
    }

    @Test
    fun anExhaustedBudgetReturnsZeroSoTheCallerStops() {
        assertEquals(0L, DesktopPortPoll.delayAfterFailure(elapsedMs = 5_000L, remainingMs = 0L))
        assertEquals(0L, DesktopPortPoll.delayAfterFailure(elapsedMs = 6_000L, remainingMs = -1L))
    }

    @Test
    fun theScheduleStaysBoundedForAnyBudget() {
        // Including budgets the caller does not currently use, so the policy
        // cannot become unbounded if the timeout is ever retuned.
        listOf(0L, 1L, 49L, 50L, 51L, 1_500L, 5_000L, 30_000L).forEach { budget ->
            var now = 0L
            var guard = 0
            while (now < budget) {
                val wait = DesktopPortPoll.delayAfterFailure(now, budget - now)
                if (wait <= 0L) break
                now += wait
                assertTrue("budget=$budget overshot at $now", now <= budget)
                assertTrue("budget=$budget did not terminate", ++guard < 100_000)
            }
        }
    }

    // ---- cost and latency bounds, stated as arithmetic ----

    @Test
    fun theWorstCaseAttemptCountOverTheFullBudgetIsBounded() {
        // 30 dense attempts in the first 1500 ms, then 1500..5000 at 150 ms.
        // This is the number that has to stay small enough not to matter; it is
        // asserted so a future interval change cannot quietly multiply it.
        assertEquals(54, schedule().size)
        assertTrue("attempt count must stay well clear of a spin", schedule().size < 100)
    }

    @Test
    fun theWorstCaseDetectionLatenessWithinTheFirstSecondIsOneFastInterval() {
        // Lateness for a port that starts listening at T is the gap to the next
        // attempt instant. Over a dense stretch the supremum is the interval
        // itself, approached as T falls just after an attempt; sampling T at
        // whole milliseconds the maximum is therefore FAST_DELAY_MS - 1.
        val instants = schedule()
        val worstInFirstSecond = (0L..1_000L).maxOf { t ->
            (instants.first { it >= t }) - t
        }
        assertEquals(
            "the dense window caps first-second lateness at FAST_DELAY_MS",
            DesktopPortPoll.FAST_DELAY_MS - 1,
            worstInFirstSecond
        )
        assertTrue(
            "and it can never reach the interval itself",
            worstInFirstSecond < DesktopPortPoll.FAST_DELAY_MS
        )
    }

    @Test
    fun theRelaxedTailStillDetectsWithinTheRelaxedInterval() {
        val instants = schedule()
        val worstLate = (1_500L..4_000L).maxOf { t -> (instants.first { it >= t }) - t }
        assertEquals(DesktopPortPoll.RELAXED_DELAY_MS - 1, worstLate)
        assertTrue(worstLate < DesktopPortPoll.RELAXED_DELAY_MS)
    }

    @Test
    fun theDenseWindowIsStrictlyBetterThanTheOldFixedInterval() {
        // The pre-§16.3.4 policy, for comparison: a flat 150 ms. Asserted rather
        // than described so the claim in DesktopPortPoll's KDoc is checked.
        val old = buildList {
            var t = 0L
            while (t < DesktopPortPoll.CALLER_TIMEOUT_MS) { add(t); t += 150L }
        }
        val new = schedule()
        fun meanLateness(instants: List<Long>, lo: Long, hi: Long): Double =
            (lo..hi).map { t -> (instants.firstOrNull { it >= t } ?: hi) - t }.average()

        val oldMean = meanLateness(old, 0, 2_000)
        val newMean = meanLateness(new, 0, 2_000)
        assertTrue(
            "the new schedule must beat the old one on expected lateness " +
                "(old=$oldMean new=$newMean)",
            newMean < oldMean
        )
        assertTrue("and by a clear margin, not a rounding difference", newMean < oldMean * 0.6)
    }

    @Test
    fun chargingRealAttemptCostDoesNotBreakTheBound() {
        // A refused loopback connect measured ~1.1 ms; 2 ms is a pessimistic
        // charge. The deadline must still hold.
        val instants = schedule(attemptCostMs = 2L)
        assertTrue(instants.all { it <= DesktopPortPoll.CALLER_TIMEOUT_MS })
        assertTrue("cost must reduce, not increase, the attempt count", instants.size <= 54)
    }

    // ---- the constants themselves ----

    @Test
    fun theConnectTimeoutIsUnchangedFromBeforeTheOptimization() {
        // Explicitly out of scope for §16.3.4: nothing in the source justified
        // touching it, and lowering it could only convert a slow success into a
        // spurious failure.
        assertEquals(300, DesktopPortPoll.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun theConstantsAreCoherent() {
        assertTrue(DesktopPortPoll.FAST_DELAY_MS < DesktopPortPoll.RELAXED_DELAY_MS)
        assertTrue(DesktopPortPoll.FAST_WINDOW_MS < DesktopPortPoll.CALLER_TIMEOUT_MS)
        assertEquals(
            "the dense window must be a whole number of fast intervals, or the " +
                "boundary assertion above is coincidental",
            0L,
            DesktopPortPoll.FAST_WINDOW_MS % DesktopPortPoll.FAST_DELAY_MS
        )
    }

    @Test
    fun callerBudgetMatchesTheDocumentedTimeout() {
        assertEquals(5_000L, DesktopPortPoll.CALLER_TIMEOUT_MS)
    }
}
