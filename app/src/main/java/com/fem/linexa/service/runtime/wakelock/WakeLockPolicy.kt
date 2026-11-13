package com.fem.linexa.service.runtime.wakelock

/**
 * §1.142 — the pure half of the Workspace-runtime wake lock (§1.100(5)).
 *
 * Everything that decides *whether* a lock is taken, *whose* it is and *when it
 * must be gone* lives here, with no Android type in sight, because the whole
 * risk of this feature is a lock that outlives the thing it was taken for. A
 * bound that is only expressed as a `delay(...)` inside a coroutine cannot be
 * asserted; a bound expressed as arithmetic can.
 *
 * Three rules, and all three are load-bearing:
 *
 * 1. **There is at most ONE holder, process-wide.** Not "one per workspace" —
 *    §3.2's settled policy is that exactly one workspace runs at a time, so a
 *    second holder could only ever be a leak from a workspace that is already
 *    gone. A request naming a different workspace is therefore a [Decision.Replace]
 *    (release then acquire), never an addition.
 * 2. **The lock is a toggle, and the same request twice means off.** The entry
 *    point is a notification action the user taps, so the second tap has to mean
 *    "stop"; there is no separate "release" affordance to discover.
 * 3. **A hold expires on its own.** [MAX_HOLD_MS] is a hard ceiling measured
 *    from the acquisition, not a sliding window — a workspace that keeps working
 *    does not keep the phone awake indefinitely, it makes the user re-arm.
 *
 * The ceiling is deliberately not "until the workspace stops": a workspace
 * session is entered and left by hand and can legitimately stay Running for
 * hours (§1.100(5)), so binding the lock to the session's lifetime would be a
 * lock with no bound at all, which is the outcome this record exists to refuse.
 */
object WakeLockPolicy {

    /**
     * The hard ceiling on a single hold: 60 minutes.
     *
     * Chosen against the operation the lock actually protects — a long guest
     * command (`apt full-upgrade`, a compile, a large `pip install`) continuing
     * while the screen is off. Under an hour covers those; a value small enough
     * to be "safe" (5–10 min) would expire in the middle of exactly the job the
     * user took the lock for, and they would learn to re-tap it in a loop, which
     * is worse than one honest ceiling. Re-arming is one tap on a notification
     * that is already on screen.
     *
     * This is a *maximum*, not a target: every teardown path releases earlier
     * (see [WorkspaceWakeLockManager]).
     */
    const val MAX_HOLD_MS: Long = 60L * 60L * 1000L

    /** When a hold acquired at [acquiredAtMs] must be gone. */
    fun expiryAtMs(acquiredAtMs: Long): Long = acquiredAtMs + MAX_HOLD_MS

    /**
     * Milliseconds left on a hold acquired at [acquiredAtMs], clamped at 0.
     *
     * Clamped rather than allowed to go negative because the only consumer is
     * user-facing ("Awake 42m") and a negative remainder would render as a
     * countdown running backwards past zero if a refresh ever arrived late.
     */
    fun remainingMs(nowMs: Long, acquiredAtMs: Long): Long =
        (expiryAtMs(acquiredAtMs) - nowMs).coerceAtLeast(0L)

    /** Whether a hold acquired at [acquiredAtMs] has reached its ceiling. */
    fun isExpired(nowMs: Long, acquiredAtMs: Long): Boolean = remainingMs(nowMs, acquiredAtMs) == 0L

    /**
     * What a user tap for [requested] means, given the current [heldBy]
     * (null = nothing held).
     *
     * Total by construction — every combination maps to exactly one decision, so
     * the manager has no branch of its own to get wrong.
     */
    fun toggle(heldBy: String?, requested: String): Decision = when {
        heldBy == null -> Decision.Acquire(requested)
        heldBy == requested -> Decision.Release(requested)
        else -> Decision.Replace(release = heldBy, acquire = requested)
    }

    /** The outcome of a [toggle]. */
    sealed interface Decision {
        /** Nothing was held; take a lock for this workspace. */
        data class Acquire(val workspaceId: String) : Decision

        /** This workspace already held it; the tap means stop. */
        data class Release(val workspaceId: String) : Decision

        /**
         * A *different* workspace held it — release that one and take a fresh
         * hold. Rule 1: never two holders.
         */
        data class Replace(val release: String, val acquire: String) : Decision
    }
}
