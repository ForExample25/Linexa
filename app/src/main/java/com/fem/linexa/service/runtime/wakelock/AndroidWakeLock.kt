package com.fem.linexa.service.runtime.wakelock

import android.app.Application
import android.os.PowerManager
import com.fem.linexa.core.logging.Logger

/**
 * §1.142 — the real [HostWakeLock]: one `PARTIAL_WAKE_LOCK` from the platform
 * `PowerManager`.
 *
 * The lock object is created lazily and reused, and it is created
 * **non-reference-counted** (`setReferenceCounted(false)`) on purpose: with
 * reference counting on, the several release paths this feature has (toggle,
 * expiry timer, workspace teardown, service destroy) would each decrement a
 * counter, so a duplicated acquire would need a matching number of releases and
 * one missed release would keep the CPU awake. With counting off, `release()` is
 * absolute — exactly the idempotence [HostWakeLock] promises.
 *
 * Every platform call is wrapped: a `PowerManager` that refuses (no permission,
 * an OEM policy, a stubbed platform) must degrade to "the lock is not held",
 * never to an exception on the runtime's teardown path.
 */
class AndroidWakeLock(
    private val application: Application
) : HostWakeLock {

    private val lock: PowerManager.WakeLock? by lazy {
        try {
            application.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKE_LOCK)
                ?.apply { setReferenceCounted(false) }
        } catch (e: Exception) {
            Logger.w(TAG, "PowerManager wake lock unavailable: ${e.message}")
            null
        }
    }

    override fun acquire(timeoutMs: Long): Boolean {
        val wakeLock = lock ?: return false
        return try {
            // The platform timeout is a backstop for the manager's own timer, not
            // a replacement for it — see HostWakeLock's kdoc.
            wakeLock.acquire(timeoutMs)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "wake lock acquire failed: ${e.message}")
            false
        }
    }

    override fun release() {
        val wakeLock = lock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            // A release that throws must never propagate: this runs from
            // workspace teardown and from Service.onDestroy.
            Logger.w(TAG, "wake lock release failed: ${e.message}")
        }
    }

    override fun isHeld(): Boolean = try {
        lock?.isHeld == true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "AndroidWakeLock"

        /** Shown in `dumpsys power`; names the owner so a leak is attributable. */
        const val TAG_WAKE_LOCK = "linexa:workspace-runtime"
    }
}
