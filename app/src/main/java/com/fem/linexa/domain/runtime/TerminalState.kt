package com.fem.linexa.domain.runtime

/**
 * Ephemeral per-terminal process state for one terminal inside a running
 * Workspace (§1.97/W2).
 *
 * Lives only inside [com.fem.linexa.service.runtime.WorkspaceTerminalManager]
 * while the app process is alive. Models the terminal's **process** lifetime
 * only (audit §9.2): UI attachment is deliberately NOT part of it — a terminal
 * is [Running] whether or not a TerminalView is attached, and a screen pop
 * detaches the UI client without touching the process.
 *
 * This is an independent state machine from the workspace-level [RuntimeState]
 * (§1.16 discipline): a workspace can be Running while one of its terminals is
 * [Error], and a terminal [Error] never drives the workspace to
 * [RuntimeState.Error].
 */
sealed interface TerminalState {
    /** Pty being created, guest starting. */
    data object Starting : TerminalState

    /** Pty alive, guest running. */
    data object Running : TerminalState

    /** Teardown in progress (explicit close). */
    data object Stopping : TerminalState

    /** Terminal torn down (explicit close completed), or exited cleanly. */
    data object Stopped : TerminalState

    /**
     * Terminal died unexpectedly, or failed to open.
     *
     * @param reason human-readable cause, already surfaced in the UI.
     * @param exitCode §1.191: the child's raw exit status when the terminal died
     *   from a *process exit* — null when there is no exit status to report
     *   (a failure to open, where no child ever ran). The terminal backend's
     *   `TerminalSession.getExitStatus()` (inherited from the original
     *   Android-Terminal-Emulator lineage, vendored Termux module included)
     *   reports a signal death as the **negated signal number** (its own
     *   `getExitDescription` renders `exitStatus < 0` as
     *   "signal ${-exitStatus}"), so `-9` here means SIGKILL. Carried so the UI
     *   can distinguish an OS kill from an ordinary crash; it deliberately does
     *   not affect any state transition.
     */
    data class Error(val reason: String, val exitCode: Int? = null) : TerminalState {

        val killedBySigkill: Boolean get() = exitCode == SIGKILL_EXIT_STATUS
    }

    companion object {
        /**
         * §1.191: the terminal backend's exit status for a SIGKILLed child
         * (negated signal number — see [Error.exitCode]).
         */
        const val SIGKILL_EXIT_STATUS: Int = -9
    }
}
