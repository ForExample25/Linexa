package com.fem.linexa.service.runtime.wakelock

/**
 * §1.142 — a recording [HostWakeLock] for the JVM tests.
 *
 * Fakes the one seam of this feature that cannot exist off-device
 * (`PowerManager.WakeLock`), and does so *statefully*: the property every test
 * here exists to prove is "nothing is still held after path X", which is only
 * assertable against something that can answer [isHeld].
 *
 * Deliberately models two real behaviours:
 *
 * - **Non-reference-counted**, like [AndroidWakeLock] — a second `release()` is a
 *   no-op, not a negative count, so the manager's overlapping release paths are
 *   exercised as they behave in production.
 * - **[granted] = false** reproduces a platform that refuses (missing permission,
 *   OEM policy), which is the case where the manager must record *no* hold and arm
 *   *no* timer.
 */
class RecordingWakeLock(
    /** When false, [acquire] refuses like a platform that will not give a lock. */
    var granted: Boolean = true
) : HostWakeLock {

    /** Timeouts passed to each successful acquire, in order. */
    val acquireTimeouts = mutableListOf<Long>()

    /** Every acquire attempt, including refused ones. */
    var acquireAttempts: Int = 0
        private set

    /** Every release call, including the no-op ones. */
    var releaseCalls: Int = 0
        private set

    /** Set to throw from [acquire] — the manager must degrade, not propagate. */
    var acquireThrows: Exception? = null

    private var held = false

    override fun acquire(timeoutMs: Long): Boolean {
        acquireAttempts += 1
        acquireThrows?.let { throw it }
        if (!granted) return false
        acquireTimeouts += timeoutMs
        held = true
        return true
    }

    override fun release() {
        releaseCalls += 1
        held = false
    }

    override fun isHeld(): Boolean = held
}
