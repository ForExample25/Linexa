package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.degradations

/**
 * Desktop failure-surfacing (Desktop Zoom work, 2026-08) — the pure decision
 * behind "a desktop daemon died while the user is looking at that desktop".
 *
 * ## The problem
 *
 * An unexpected Xvnc / desktop-session / termux-x11-helper death is reported
 * correctly by the runtime — the exit watcher degrades the workspace's health
 * (`RuntimeState.Running.health = Degraded`) while the *workspace* keeps
 * running — but the desktop screen used to keep saying `Running`: the viewer
 * showed a connection error over a black surface, the bounded reconnect
 * budget expired, and the user was left inside a half-dead UI whose only
 * honest exit was pressing Stop and then Start. The failure fact existed; no
 * one translated it into the screen the user is on.
 *
 * ## The rule
 *
 * While the desktop screen shows a *live desktop* ([DesktopUiState.Running] /
 * [DesktopUiState.RunningExternal]), any unexpected death of one of the
 * daemons the desktop itself started is surfaced as a desktop
 * [DesktopUiState.Error] carrying the runtime's own detail ("daemon 'xvnc'
 * exited unexpectedly (exit code …)"). The Start button on that state is the
 * recovery path; `startDaemon` clears the predecessor's degradation on the
 * restart, so the restarted desktop is healthy by construction.
 *
 * Deliberately narrow: an sshd or notification daemon dying degrades the
 * *workspace*, not the *desktop*, and must not interrupt a live session —
 * so only the desktop's own daemon names qualify. That set is supplied by
 * the caller ([DesktopViewModel] owns the constants) so this object stays
 * pure, JVM-testable, and free of the runtime's internals.
 *
 * Deliberately one-shot per emission: this reads the *aggregate* health of
 * the runtime state the ViewModel is already collecting — no new flow, no new
 * subscription, and the state write is idempotent because the same emission
 * produces the same answer until the degradation is cleared.
 */
internal object DesktopHealthGate {

    /**
     * The desktop failure the screen should surface, or null when the current
     * desktop state may stay as it is.
     *
     * @param runtimeState the workspace's runtime state for this emission.
     * @param watchedDaemons the daemon names the desktop depends on (without
     *   the [RuntimeDegradation.DAEMON_COMPONENT_PREFIX] prefix).
     * @param desktopState what the desktop screen is currently showing.
     * @return the degradation's detail text, deterministic across identical
     *   inputs (lowest component name wins if several watched daemons died).
     */
    fun failureDetail(
        runtimeState: RuntimeState?,
        watchedDaemons: Set<String>,
        desktopState: DesktopUiState?
    ): String? {
        if (runtimeState == null) return null
        val desktopAlive = desktopState is DesktopUiState.Running ||
            desktopState is DesktopUiState.RunningExternal
        if (!desktopAlive) return null
        return watchedDaemonDeath(runtimeState, watchedDaemons)
    }

    /**
     * 2026-09-05 (state-machine correctness): the same death detected while a
     * START is in flight. An X server that dies during X11_STARTING can never
     * reach READY, so the machine must move to X11_FAILED now — not when the
     * port poll's budget expires. Only the daemon names the caller supplies
     * qualify (the ViewModel passes the X-server daemons: xvnc / x11server),
     * so an sshd or notification failure still degrades only the workspace.
     *
     * @return the degradation's detail text, or null when no watched X-server
     *   daemon has died unexpectedly.
     */
    fun startingFailureDetail(
        runtimeState: RuntimeState?,
        watchedXServerDaemons: Set<String>
    ): String? {
        if (runtimeState == null) return null
        return watchedDaemonDeath(runtimeState, watchedXServerDaemons)
    }

    /** The shared death lookup; both entry points are filters over this. */
    private fun watchedDaemonDeath(
        runtimeState: RuntimeState,
        watchedDaemons: Set<String>
    ): String? {
        val desktopDaemonDeaths = runtimeState.degradations()
            .filter { it.component.startsWith(RuntimeDegradation.DAEMON_COMPONENT_PREFIX) }
            .filter { it.component.removePrefix(RuntimeDegradation.DAEMON_COMPONENT_PREFIX) in watchedDaemons }
        return desktopDaemonDeaths.minByOrNull { it.component }?.detail
    }
}
