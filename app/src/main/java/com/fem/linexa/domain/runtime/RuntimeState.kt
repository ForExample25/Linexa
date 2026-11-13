package com.fem.linexa.domain.runtime

import java.time.Instant

/**
 * Ephemeral process state for a running Workspace's PRoot process.
 * Lives only inside SessionManager while the app process is alive.
 * Resets to [Stopped] on a fresh process (§1.13, §1.16).
 *
 * Transformation spec §6: `Running` now carries the workspace's component
 * [health] so "running" is never a mere "some process exists" — a workspace
 * whose ssh/audio/network/desktop component failed stays [Running] (its
 * terminals keep working) but reports `Degraded` health, which the UI and
 * diagnostics surface. See [RuntimeHealth] for the model.
 */
sealed interface RuntimeState {
    data object Stopped : RuntimeState
    data object Starting : RuntimeState

    data class Running(
        val pid: Int,
        val since: Instant,
        val health: RuntimeHealth = RuntimeHealth.Healthy,
    ) : RuntimeState

    data object Stopping : RuntimeState
    data class Error(val reason: String) : RuntimeState
}
