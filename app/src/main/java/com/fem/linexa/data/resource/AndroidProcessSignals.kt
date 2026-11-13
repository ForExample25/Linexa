package com.fem.linexa.data.resource

import com.fem.linexa.core.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signals a workspace process (the Sessions task manager's per-row kill).
 *
 * Behind an interface for the same reason every other /proc-adjacent
 * capability here is: the JVM tests exercise the decision logic, never the
 * platform call. Workspace processes run as this app's own uid (PRoot is
 * userspace translation — no setuid, no separate POSIX identity), so the app
 * may signal them directly; `/proc`'s hidepid already guarantees the sampler
 * (and therefore this action) can only ever address the app's own process
 * group — another app's pid is not even visible to be killed.
 */
interface ProcessSignals {

    /**
     * Sends SIGTERM to [pid]. SIGTERM, not SIGKILL, on purpose: the row is a
     * task the guest may want to shut down cleanly (a shell runs its
     * `trap`/history write), and a process that ignores TERM is dealt with by
     * closing its session — the X on a session card — rather than by the task
     * manager escalating on the user's behalf.
     *
     * Returns whether the signal was dispatched; a vanished pid (already
     * exited between the sample and the tap) is a normal `false`.
     */
    fun terminate(pid: Int): Boolean
}

@Singleton
class AndroidProcessSignals @Inject constructor() : ProcessSignals {

    override fun terminate(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            android.system.Os.kill(pid, android.system.OsConstants.SIGTERM)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "SIGTERM to $pid failed: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "ProcessSignals"
    }
}
