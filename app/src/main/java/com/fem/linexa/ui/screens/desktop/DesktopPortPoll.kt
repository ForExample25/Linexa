package com.fem.linexa.ui.screens.desktop

/**
 * §16.3.4 — the retry policy for `DesktopViewModel.waitForVncPort`, as a pure
 * function of elapsed time and remaining budget.
 *
 * ## Why this is its own object
 *
 * The poll itself cannot be unit tested. `waitForVncPort` is a private member of
 * an `AndroidViewModel` that needs a real `Application`, and this module has no
 * Robolectric harness (§1.124(a)) — the same constraint that forced
 * [DesktopStartupProbe] and [DesktopGeometry] out of the ViewModel. The *policy*,
 * however, is arithmetic, so it is extracted here where it can be asserted
 * exactly and without a clock. What stays in the ViewModel is the socket loop,
 * which has no decisions left in it.
 *
 * ## Why the interval is not simply "as short as possible"
 *
 * The poll sits on the serial startup chain — Xvnc launch → port poll → X
 * session launch → RFB connect (§16.3) — so every millisecond between Xvnc
 * binding and the poll noticing is a millisecond the user waits. For a schedule
 * with attempt instants `t₀ < t₁ < …`, a server that begins listening at time `T`
 * is detected at the first `tᵢ ≥ T`, so the lateness is
 * `min{tᵢ : tᵢ ≥ T} − T`. Averaged over `T`, a constant interval `d` costs
 * `(d + c)/2` where `c` is the cost of one attempt — i.e. the *expected* penalty
 * is half the interval, and the worst case is all of it.
 *
 * A refused `connect()` to a closed loopback port was measured at ~1.1 ms on the
 * development device, so `c` is negligible next to any sane `d` and the interval
 * alone sets both the latency and the cost.
 *
 * ## Why a dense window rather than exponential backoff
 *
 * The audit proposed growing the delay from a short start (25 → 50 → 100 → 150).
 * Measured against a distribution of bind times that is **worse than useless**:
 * it relaxes to 150 ms after ~175 ms, which is *before* Xvnc plausibly finishes
 * binding inside PRoot, so it pays the full 150 ms penalty in exactly the region
 * that matters while spending extra attempts early where nothing is listening
 * yet. Expected lateness over `T ~ U(0, 2000 ms)` came out at 72.5 ms against the
 * current 76.6 ms — no improvement, and the worst case stayed at 151 ms.
 *
 * Backoff is the right shape when the wait may be arbitrarily long and the cost
 * per probe is high. Here the wait is bounded at [CALLER_TIMEOUT_MS] and a probe
 * costs a millisecond, so the useful shape is the opposite one: poll densely
 * across the window where the answer is likely to change, then relax.
 *
 * Hence [FAST_DELAY_MS] for the first [FAST_WINDOW_MS], then [RELAXED_DELAY_MS].
 * Measured expected lateness 37.7 ms (from 76.6 ms) and worst case within the
 * first second 51 ms (from 151 ms), for 53 attempts instead of 34 in the case
 * where the port never opens at all — about 59 ms of cumulative connect work
 * spread over five seconds, or ~1.2% of one core. The tail exists so that a
 * guest which is genuinely broken is not polled 20 times a second for the rest
 * of the timeout.
 *
 * A flat 50 ms would be very slightly better on latency (25.6 ms expected) but
 * costs 98 attempts, and buys that with density in the second half of the budget
 * where a server that has not bound in 1.5 s is not about to. The dense window
 * keeps the part of the curve that pays.
 */
internal object DesktopPortPoll {

    /**
     * Per-attempt `Socket.connect` timeout. Unchanged from the pre-§16.3.4 code:
     * on loopback a refused connection returns immediately, so this ceiling only
     * ever applies to a pathological stack, and lowering it could only turn a
     * slow success into a spurious failure.
     */
    const val CONNECT_TIMEOUT_MS = 300

    /**
     * Overall budget the caller allows the poll, mirrored here because the
     * policy is designed against it ([FAST_WINDOW_MS] is a fraction of it) and
     * because the tests need it to prove the schedule stays inside it.
     *
     * Deliberately **not** substituted into `startDesktop`'s call: that call also
     * feeds `R.string.desktop_xvnc_port_timeout`, and §16.3.4's scope is the poll
     * itself. `DesktopPortPollTest.callerBudgetMatchesTheDocumentedTimeout` pins
     * the two together so they cannot drift silently.
     */
    const val CALLER_TIMEOUT_MS = 5_000L

    /** Retry interval while Xvnc is plausibly still binding. */
    const val FAST_DELAY_MS = 50L

    /** How long the dense interval is held before relaxing to [RELAXED_DELAY_MS]. */
    const val FAST_WINDOW_MS = 1_500L

    /** Retry interval once a fast bind is no longer plausible. */
    const val RELAXED_DELAY_MS = 150L

    /**
     * The delay to wait after a failed attempt, or `0` when the budget is spent
     * and the caller should stop.
     *
     * Clamped to [remainingMs] so the backoff can never push the loop past its
     * deadline: the pre-§16.3.4 code slept a full 150 ms after an attempt begun
     * one millisecond before the deadline, overshooting the advertised timeout.
     * The clamp makes the bound exact instead of approximate, and can only ever
     * shorten the wait.
     *
     * @param elapsedMs time since the first attempt was issued.
     * @param remainingMs time left before the deadline.
     */
    fun delayAfterFailure(elapsedMs: Long, remainingMs: Long): Long {
        if (remainingMs <= 0L) return 0L
        val interval = if (elapsedMs < FAST_WINDOW_MS) FAST_DELAY_MS else RELAXED_DELAY_MS
        return if (interval < remainingMs) interval else remainingMs
    }
}
