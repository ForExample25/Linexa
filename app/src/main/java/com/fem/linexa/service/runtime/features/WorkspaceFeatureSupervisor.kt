package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import kotlinx.coroutines.CancellationException

/**
 * §1.109 — the guest-side consumer of the `ssh` workspace feature toggle.
 *
 * §1.81 made feature toggles real *state* (built-in default < config.yaml
 * `linexa.features` < persisted user toggle) but left the guest-side
 * mechanisms open; §1.82 wired the first one (Shared Storage → the
 * `/mnt/shared` bind). This is the second: turning `ssh` on actually runs an
 * sshd inside the workspace, turning it off actually stops it.
 *
 * **Reconciliation, not event handling.** The single entry point
 * ([reconcileSsh]) takes the *desired* state and converges the guest to it,
 * so it is correct to call on a toggle tap, on workspace entry, and after a
 * config.yaml import that pre-enabled the feature — and calling it twice is
 * harmless. Nothing here remembers state of its own; the desired state is
 * the persisted toggle and the actual state is
 * [GuestFeatureHost.isDaemonAlive], so this class can never drift out of
 * sync with either.
 *
 * **It cannot touch the Workspace lifecycle.** [GuestFeatureHost] exposes
 * exec + the §1.83 daemon host and nothing else, so a feature can never
 * start or stop a Workspace session (§1.2: the graph owns that). A feature
 * asked to enable itself while the session is down reports
 * [SshFeatureState.Unavailable] instead of starting anything.
 *
 * All command strings come from [SshdProvisioner] (constants, or built only
 * from values it owns), so no user-supplied value is ever interpolated into
 * a guest command (§1.14).
 */
class WorkspaceFeatureSupervisor(
    private val host: GuestFeatureHost
) {

    /**
     * UI/UX Phase 6 — the port each workspace's *running* sshd was started with.
     *
     * This is not state that could drift out of sync with the guest, which the
     * class KDoc rules out: it is only ever read when
     * [GuestFeatureHost.isDaemonAlive] has already confirmed a daemon is up, and
     * an entry is removed the moment one is stopped. It exists because the port a
     * live daemon bound is a fact about that process which nothing else records —
     * `sshd` is not going to be asked, and the persisted setting is the *desired*
     * port, which may have been edited since. An absent entry (a daemon started
     * by a previous process, before this instance existed) falls back to the
     * requested port, which is the best available answer and matches the
     * pre-Phase-6 report.
     */
    private val runningPorts = mutableMapOf<String, Int>()

    /**
     * Converge [workspaceId]'s sshd to [enabled].
     *
     * @param onProgress called with each intermediate state (probing,
     *   installing, starting) so the UI can show live progress; the returned
     *   value is the final state and is NOT re-emitted through this callback.
     * @param port the port the guest's sshd should listen on (UI/UX Phase 6 —
     *   the workspace's persisted [FeatureSettings.sshPort]). Defaults to
     *   [SshdProvisioner.SSH_PORT], which is the pre-Phase-6 behaviour, so a
     *   caller that has no settings to apply is unchanged.
     *
     *   A **running daemon is not restarted** to adopt a new port: the port is
     *   read where the daemon is actually started, so a change takes effect on
     *   the next enable. Silently killing a live sshd — and with it the user's
     *   open ssh connections — as a side effect of editing a text field would be
     *   a much worse surprise than a setting that applies when the daemon next
     *   starts, and the UI says which port is live in its status line either way.
     */
    suspend fun reconcileSsh(
        workspaceId: String,
        enabled: Boolean,
        port: Int = SshdProvisioner.SSH_PORT,
        onProgress: (SshFeatureState) -> Unit = {}
    ): SshFeatureState = try {
        if (!enabled) disableSsh(workspaceId) else enableSsh(workspaceId, port, onProgress)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "SSH reconcile failed for $workspaceId (enabled=$enabled)", e)
        SshFeatureState.Failed(e.message ?: "Unknown error")
    }

    /**
     * Stop the daemon unconditionally — [GuestFeatureHost.stopDaemon] is
     * idempotent, so this is also the correct action for "already off"
     * (which is what makes a disable reconcile safe to repeat, and what
     * cleans up a daemon left behind by a toggle that was flipped off while
     * the app was dead).
     */
    private suspend fun disableSsh(workspaceId: String): SshFeatureState {
        host.stopDaemon(workspaceId, SshdProvisioner.DAEMON_NAME)
        runningPorts.remove(workspaceId)
        Logger.i(TAG, "SSH disabled for $workspaceId (sshd stopped)")
        return SshFeatureState.Disabled
    }

    private suspend fun enableSsh(
        workspaceId: String,
        port: Int,
        onProgress: (SshFeatureState) -> Unit
    ): SshFeatureState {
        if (!host.hasActiveSession(workspaceId)) {
            // Not a failure: the toggle stays on and the next reconcile (on
            // workspace entry) starts the daemon. Reporting Failed here would
            // make "enabled while stopped" look broken.
            return SshFeatureState.Unavailable
        }
        if (host.isDaemonAlive(workspaceId, SshdProvisioner.DAEMON_NAME)) {
            // Deliberately reports the port the RUNNING daemon was started with,
            // which a just-edited setting may no longer match — see the port
            // parameter's note on why a live daemon is not restarted. Reporting
            // the requested port here would claim a listener that isn't there.
            return SshFeatureState.Running(runningPorts[workspaceId] ?: port)
        }

        onProgress(SshFeatureState.Probing)
        var sshdPath = probeSshdPath(workspaceId)

        if (sshdPath == null) {
            val installed = installSshd(workspaceId, onProgress)
            if (installed is SshFeatureState.Failed) return installed
            sshdPath = probeSshdPath(workspaceId)
            if (sshdPath == null) {
                // Install reported success but the binary still isn't there —
                // report it rather than handing a null path to the daemon host.
                return SshFeatureState.Failed(REASON_MISSING_AFTER_INSTALL)
            }
        }

        onProgress(SshFeatureState.Starting)

        // Privilege-separation dirs + host keys. A guest that already has both
        // still runs this (it is idempotent and never rotates existing keys).
        val prepare = host.execCommand(workspaceId, SshdProvisioner.PREPARE_COMMAND)
        if (prepare.exitCode != 0) {
            val detail = prepare.error.ifBlank { prepare.output }.trim().take(DETAIL_CHARS)
            Logger.e(TAG, "sshd prepare failed for $workspaceId (rc=${prepare.exitCode}): $detail")
            return SshFeatureState.Failed("Could not prepare sshd: $detail")
        }

        val started = host.startDaemon(
            workspaceId,
            SshdProvisioner.DAEMON_NAME,
            SshdProvisioner.daemonCommand(sshdPath, port)
        )
        if (!started) {
            Logger.e(TAG, "sshd daemon failed to start for $workspaceId (path=$sshdPath, port=$port)")
            return SshFeatureState.Failed(REASON_START_FAILED)
        }

        runningPorts[workspaceId] = port
        Logger.i(TAG, "SSH enabled for $workspaceId — sshd listening on port $port")
        return SshFeatureState.Running(port)
    }

    /** Resolve the guest's sshd binary, or null when it isn't installed. */
    private suspend fun probeSshdPath(workspaceId: String): String? {
        val probe = host.execCommand(workspaceId, SshdProvisioner.SSHD_PROBE_COMMAND)
        // The probe is `||`-chained, so a non-zero rc means "not found" — but
        // parse the output regardless, since a shell that reports oddly still
        // prints the path it resolved.
        return SshdProvisioner.parseSshdPath(probe.output)
    }

    /**
     * Install the distro's OpenSSH server. Returns [SshFeatureState.Failed]
     * on any failure, or [SshFeatureState.Installing] to signal "the install
     * command exited 0" (the caller re-probes to decide the real outcome).
     */
    private suspend fun installSshd(
        workspaceId: String,
        onProgress: (SshFeatureState) -> Unit
    ): SshFeatureState {
        val which = host.execCommand(workspaceId, SshdProvisioner.DETECT_PACKAGE_MANAGER_COMMAND)
        val osRelease = host.execCommand(workspaceId, SshdProvisioner.DETECT_OS_RELEASE_COMMAND)
        val pmKey = SshdProvisioner.packageManagerKey(which.output, osRelease.output)
        if (pmKey == null) {
            Logger.e(
                TAG,
                "No supported package manager in $workspaceId (probe='${which.output.take(120)}') — cannot install sshd"
            )
            return SshFeatureState.Failed(REASON_NO_PACKAGE_MANAGER)
        }

        val installCommand = SshdProvisioner.installCommand(pmKey)
            ?: return SshFeatureState.Failed(REASON_NO_PACKAGE_MANAGER)

        onProgress(SshFeatureState.Installing(pmKey))
        Logger.i(TAG, "Installing sshd via '$pmKey' in $workspaceId: $installCommand")
        val result = host.execCommand(workspaceId, installCommand)
        if (result.exitCode != 0) {
            val detail = result.error.ifBlank { result.output }.trim().take(DETAIL_CHARS)
            Logger.e(TAG, "sshd install via '$pmKey' failed for $workspaceId (rc=${result.exitCode}): $detail")
            return SshFeatureState.Failed("Could not install sshd: $detail")
        }
        return SshFeatureState.Installing(pmKey)
    }

    private companion object {
        const val TAG = "FeatureSupervisor"

        /** Bounded length of a guest error tail carried into a UI reason. */
        const val DETAIL_CHARS = 300

        const val REASON_NO_PACKAGE_MANAGER =
            "This workspace has no supported package manager, so an SSH server could not be installed automatically."
        const val REASON_MISSING_AFTER_INSTALL =
            "The SSH server still isn't present after installation."
        const val REASON_START_FAILED = "The SSH server failed to start."

        /** Kept for readability of the supported-key list in reviews/logs. */
        @Suppress("unused")
        val SUPPORTED_KEYS = PackageManagerDetector.supportedKeys
    }
}

/**
 * §1.109 — observable state of a workspace's SSH feature. Separate from the
 * persisted toggle (§1.16's rule applied at feature scope: the toggle is the
 * user's *intent*, this is the guest's *actual* condition) so a failed enable
 * doesn't silently rewrite the user's setting.
 */
sealed interface SshFeatureState {

    /** The toggle is off and no sshd is running. */
    data object Disabled : SshFeatureState

    /**
     * The toggle is on but the Workspace session isn't running, so there is
     * no guest to start sshd in. Not an error — entering the workspace
     * reconciles it.
     */
    data object Unavailable : SshFeatureState

    /** Looking for an existing sshd in the guest. */
    data object Probing : SshFeatureState

    /** Installing the OpenSSH server through the guest's package manager. */
    data class Installing(val packageManagerKey: String) : SshFeatureState

    /** Preparing host keys / privilege-separation dirs and launching sshd. */
    data object Starting : SshFeatureState

    /** sshd is alive and listening on [port] inside the guest. */
    data class Running(val port: Int) : SshFeatureState

    /** Enabling failed; [reason] is user-facing. */
    data class Failed(val reason: String) : SshFeatureState
}
