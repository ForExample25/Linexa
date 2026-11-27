package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult

/**
 * §1.109 — the narrow seam [WorkspaceFeatureSupervisor] uses to act on a
 * workspace's guest.
 *
 * Implemented by [com.fem.linexa.service.runtime.RuntimeBinder] (which
 * already had every one of these methods — this interface only names the
 * subset the feature layer needs). It exists so the supervisor can be driven
 * by a plain fake in a JVM unit test: `RuntimeBinder` extends
 * `android.os.Binder`, so a supervisor that depended on it directly would be
 * untestable off-device.
 *
 * Deliberately minimal: one-shot exec + the §1.83 guest-daemon host + the
 * session-liveness predicate. Nothing here exposes the pty, the terminal
 * manager, or the workspace lifecycle — a feature must never be able to
 * start or stop a Workspace session (§1.2: the graph owns that).
 */
interface GuestFeatureHost {

    /** Whether [workspaceId]'s runtime session is live (features need a guest). */
    fun hasActiveSession(workspaceId: String): Boolean

    /** Run a bounded one-shot command inside the guest. */
    suspend fun execCommand(workspaceId: String, command: String): CommandResult

    /**
     * Finding 031: like [execCommand], but [stdin] is written to the guest
     * process's standard input and then closed. VNC password provisioning
     * (`vncpasswd -f`) uses this so the secret never appears in argv.
     *
     * [stdin] is a secret: callers must not log it. A failure to deliver it is
     * an exec failure, never a run with empty stdin.
     */
    suspend fun execCommandWithInput(
        workspaceId: String,
        command: String,
        stdin: String
    ): CommandResult

    /** §1.83: start (or idempotently restart) a named long-lived guest daemon. */
    suspend fun startDaemon(workspaceId: String, name: String, command: String): Boolean

    /** §1.83: stop a named guest daemon. Idempotent. */
    suspend fun stopDaemon(workspaceId: String, name: String)

    /** §1.83: whether a named guest daemon is currently alive. */
    fun isDaemonAlive(workspaceId: String, name: String): Boolean
}
