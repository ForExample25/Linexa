package com.fem.linexa.service.runtime.wakelock

import com.fem.linexa.core.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §1.142 — the single owner of the Workspace-runtime wake lock (§1.100(5)).
 *
 * ### What it protects
 *
 * A wake lock here buys exactly one thing: the guest keeps getting CPU while the
 * screen is off, so a long command the user started in a Workspace terminal (an
 * `apt full-upgrade`, a build, a large download) finishes instead of stalling
 * when the device dozes. That is why it is a `PARTIAL_WAKE_LOCK` and why it is
 * **opt-in per hold** rather than implied by a Running workspace: most sessions
 * are interactive and want no lock at all, and taking one for every session would
 * make Linexa a background battery cost the user never asked for.
 *
 * ### Why it cannot be tied to the session's lifetime
 *
 * The obvious design — hold while `RuntimeState.Running` — is the one this record
 * refuses. §1.100 makes workspace lifetime a *manual* enter/leave decision, so a
 * user who leaves a workspace entered has an indefinite hold, which is precisely
 * "an indefinite battery drain" restated. The lock is therefore bounded by its own
 * clock ([WakeLockPolicy.MAX_HOLD_MS]) and is *additionally* released by every
 * teardown path — the session bounds it from above, never from below.
 *
 * ### Every path that lets go
 *
 * 1. **The user taps again.** [toggle] on the holding workspace releases
 *    ([WakeLockPolicy] rule 2 — the notification action is the only affordance, so
 *    the second tap has to mean off).
 * 2. **The ceiling.** An expiry coroutine releases at
 *    [WakeLockPolicy.MAX_HOLD_MS], and the platform lock carries the same timeout
 *    as a backstop for a process that never gets to run its timer.
 * 3. **The workspace stops.** [releaseFor] is called from the runtime teardown, so
 *    stopping/leaving/deleting a workspace cannot leave its lock behind.
 * 4. **Another workspace takes it.** [WakeLockPolicy.Decision.Replace] — there is
 *    never a second holder (§3.2: one workspace runs at a time, so a second holder
 *    could only be a leak).
 * 5. **The service dies.** [releaseAll] from `RuntimeService.onDestroy`.
 * 6. **The acquire itself fails.** A platform refusal leaves no hold recorded, so
 *    nothing later "releases" a lock that was never taken and no timer is armed.
 *
 * ### Generation guard
 *
 * The expiry coroutine can outlive the hold it was armed for (a release, then a
 * re-arm, all inside one `MAX_HOLD_MS` window). It therefore captures a
 * generation, bumped on every acquire and every release, and does nothing unless
 * it is still current — the same technique §P0 used for the stale launch
 * coroutine, and for the same reason: a stale timer that fired blind would drop a
 * hold the user had just re-taken.
 *
 * Every method is synchronous and non-suspending: the release paths include
 * `Service.onDestroy`, which cannot wait on a coroutine (§1.20's `runBlocking`
 * teardown exists for precisely that reason).
 */
class WorkspaceWakeLockManager(
    private val wakeLock: HostWakeLock,
    private val scope: CoroutineScope,
    /**
     * Wall clock, injected so the ceiling is testable without sleeping an hour.
     * Backed by the same virtual clock as [scope] in tests, which is what makes
     * "the hold is gone at MAX_HOLD_MS" a deterministic assertion.
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private val stateLock = Any()

    private val _hold = MutableStateFlow<WakeLockHold?>(null)

    /** The current hold, or null when nothing is held. */
    val hold: StateFlow<WakeLockHold?> = _hold.asStateFlow()

    /** Guarded by [stateLock]. Bumped on every acquire AND every release. */
    private var generation: Long = 0L

    /** The armed expiry timer for the current hold, if any. Guarded by [stateLock]. */
    private var expiryJob: Job? = null

    /**
     * The user asked for the wake lock on [workspaceId] (the notification's
     * long-press action). Returns the resulting hold, or null if the tap
     * released — or if the platform refused to give a lock at all.
     */
    fun toggle(workspaceId: String): WakeLockHold? {
        val decision = WakeLockPolicy.toggle(_hold.value?.workspaceId, workspaceId)
        return when (decision) {
            is WakeLockPolicy.Decision.Release -> {
                releaseInternal("user toggled off (${decision.workspaceId})")
                null
            }
            is WakeLockPolicy.Decision.Acquire -> acquireInternal(decision.workspaceId)
            is WakeLockPolicy.Decision.Replace -> {
                // Rule 1: never two holders. The previous holder's lock goes
                // first, so a failed acquire below cannot leave the old one held
                // for a workspace the user has moved on from.
                releaseInternal("replaced by ${decision.acquire}")
                acquireInternal(decision.acquire)
            }
        }
    }

    /**
     * Release the hold **if** it belongs to [workspaceId] — the runtime-teardown
     * path (stop / leave / delete / eviction).
     *
     * Scoped to the workspace rather than releasing unconditionally so a teardown
     * of workspace A cannot silently drop a lock the user just took for B. In
     * practice §3.2 makes that impossible, but a teardown that quietly reached
     * past its own workspace would be exactly the kind of cross-talk §1.98/§1.100
     * spent two records removing.
     */
    fun releaseFor(workspaceId: String) {
        if (_hold.value?.workspaceId != workspaceId) return
        releaseInternal("workspace $workspaceId stopped")
    }

    /** Release whatever is held — service destruction / process teardown. */
    fun releaseAll() {
        if (_hold.value == null && !wakeLock.isHeld()) return
        releaseInternal("runtime shutdown")
    }

    /** Whether [workspaceId] currently holds the lock (notification content, UI). */
    fun isHeldBy(workspaceId: String): Boolean = _hold.value?.workspaceId == workspaceId

    /**
     * Milliseconds left on the current hold, or null when nothing is held.
     * Clamped at 0 by [WakeLockPolicy.remainingMs].
     */
    fun remainingMs(): Long? = _hold.value?.let { WakeLockPolicy.remainingMs(nowMs(), it.acquiredAtMs) }

    private fun acquireInternal(workspaceId: String): WakeLockHold? {
        val acquiredAt = nowMs()
        val granted = try {
            wakeLock.acquire(WakeLockPolicy.MAX_HOLD_MS)
        } catch (e: Exception) {
            Logger.w(TAG, "wake lock acquire threw for $workspaceId: ${e.message}")
            false
        }
        if (!granted) {
            // No hold recorded and no timer armed — a refusal must not leave
            // bookkeeping claiming a lock the platform never gave.
            Logger.w(TAG, "wake lock refused for $workspaceId")
            return null
        }
        val hold = WakeLockHold(workspaceId, acquiredAt)
        synchronized(stateLock) {
            generation += 1L
            val armed = generation
            expiryJob?.cancel()
            expiryJob = scope.launch {
                delay(WakeLockPolicy.MAX_HOLD_MS)
                // Stale-timer guard: a release-then-re-arm inside one window
                // would otherwise let this timer drop the NEW hold.
                if (isCurrent(armed)) {
                    Logger.i(TAG, "wake lock expired for $workspaceId (${WakeLockPolicy.MAX_HOLD_MS} ms ceiling)")
                    releaseInternal("bounded lifetime reached")
                }
            }
            // Pre-release audit fix (2026-09-02, finding RT-14): publish the
            // hold INSIDE the state lock. It used to be assigned after the
            // synchronized block, so a concurrent releaseInternal could win
            // the race and clear it first — leaving _hold non-null with no
            // timer and no platform lock ("Awake 60m" shown, nothing held).
            _hold.value = hold
        }
        Logger.i(TAG, "wake lock acquired for $workspaceId (ceiling ${WakeLockPolicy.MAX_HOLD_MS} ms)")
        return hold
    }

    private fun releaseInternal(reason: String) {
        synchronized(stateLock) {
            // Bumping on release too is what makes an already-armed timer stale:
            // without it, a timer from the released hold would still read as
            // current and could drop a subsequent hold.
            generation += 1L
            expiryJob?.cancel()
            expiryJob = null
            // RT-14 fix: the mirror write moved under the same lock — see the
            // acquire path comment.
            _hold.value = null
        }
        // The platform release is last and unconditional — if bookkeeping and the
        // platform ever disagree, the safe direction is "not held".
        wakeLock.release()
        Logger.i(TAG, "wake lock released: $reason")
    }

    private fun isCurrent(armed: Long): Boolean = synchronized(stateLock) { generation == armed }

    private companion object {
        const val TAG = "WorkspaceWakeLock"
    }
}

/**
 * §1.142 — a live wake-lock hold: who took it and when.
 *
 * The acquisition instant is the value that matters: the ceiling is computed from
 * it ([WakeLockPolicy.expiryAtMs]) rather than tracked as a countdown, so a
 * missed tick can never extend a hold.
 */
data class WakeLockHold(
    val workspaceId: String,
    val acquiredAtMs: Long
)
