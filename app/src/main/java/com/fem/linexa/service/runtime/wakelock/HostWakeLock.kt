package com.fem.linexa.service.runtime.wakelock

/**
 * §1.142 — the Android seam the wake lock is actually taken through.
 *
 * Separate from [WorkspaceWakeLockManager] for the same reason §1.113 split
 * `AudioSink` off its supervisor: `PowerManager.WakeLock` cannot be constructed
 * on the JVM, and the *one* property this feature must guarantee — that nothing
 * is still held after every release path has run — is only assertable against a
 * fake that can be asked "are you held?".
 *
 * The contract is narrow on purpose:
 *
 * - [acquire] takes a `PARTIAL_WAKE_LOCK` and **must** pass a timeout to the
 *   platform as well. Belt and braces: the manager schedules its own expiry, but
 *   if the process is frozen or its scope cancelled between acquire and expiry,
 *   the manager's timer never fires and only the platform timeout can still let
 *   go. A partial lock keeps the CPU running with the screen off — it is not a
 *   screen lock, and this seam has no way to express one.
 * - [release] is **idempotent**. It is called from the toggle, from the expiry
 *   timer, from workspace teardown and from service destruction, and those can
 *   overlap; an implementation that threw or under-counted on a second call would
 *   turn a duplicated release into a crash or a leak.
 * - [isHeld] exists for tests and for the diagnostics line, not as a decision
 *   input: the manager tracks its own holder (see [WakeLockHold]) rather than
 *   asking the platform, because the platform cannot say *which workspace* a lock
 *   was taken for.
 */
interface HostWakeLock {

    /**
     * Take a partial wake lock, also handing [timeoutMs] to the platform as a
     * hard backstop. Returns false when the platform refused or is unavailable —
     * a refusal is reported, never thrown (§1.142: the wake lock must never be
     * able to cost a user their session).
     */
    fun acquire(timeoutMs: Long): Boolean

    /** Release the lock if held. Safe to call when nothing is held. */
    fun release()

    /** Whether a lock is currently held (diagnostics + tests). */
    fun isHeld(): Boolean
}
