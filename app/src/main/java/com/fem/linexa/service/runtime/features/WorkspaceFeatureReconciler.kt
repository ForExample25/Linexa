package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.resolveFeatureToggles
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * §1.110 — applies a workspace's *persisted* feature toggles to its guest at
 * launch, and tears the guest-side halves down at stop.
 *
 * §1.109 built the reconciliation (`WorkspaceFeatureSupervisor`) but gave it
 * exactly one caller: the Features tab. That left the toggle honored only while
 * the user was looking at it — a workspace whose `ssh` toggle was on (set on a
 * previous run, or seeded by an imported archive's `config.yaml
 * linexa.features`) started with no sshd unless someone opened the tab. §1.82's
 * Shared Storage toggle has no such gap because the runtime reads it on the
 * launch path; this class gives the *daemon-backed* features the same property.
 *
 * Placement: this is the launch path's counterpart to
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime]'s
 * `isSharedStorageEnabled`. It reads the toggle from the same single source of
 * truth (Room, §1.81) through the same pure resolver
 * ([resolveFeatureToggles] over [WorkspaceContent.builtinFeatureDefaults]), so
 * the launch path, the Features tab and the runtime can never disagree about
 * what a workspace's toggles say.
 *
 * **Fail-open, exactly like §1.27's config application.** A feature that cannot
 * be brought up must never prevent a workspace from running — the session is
 * already Running when this executes, and the user's terminal must not be taken
 * away because an `apt-get` failed. Every outcome is logged; nothing is thrown.
 *
 * §1.111: this is now the launch path for **every** guest-side feature, not just
 * `ssh` — the `notifications` toggle installs/removes the `notify-send` shim and
 * starts/stops its spool watcher here too. Each feature is applied
 * independently: one feature's failure must not skip the next one's application
 * (they are unrelated capabilities), which is why each has its own guarded
 * block rather than sharing one try.
 *
 * §1.112: the `clipboard` toggle joins them, with the same independence rule.
 *
 * §1.113: and `audio`, which completes Phase 5's guest-side consumers (Mic, GPU,
 * USB, Camera and Location remain intentionally out of scope — §3.3).
 *
 * Phase 2E-1: the launch application is split in two — [onSessionStarted]
 * (network, awaited inline) and [onSessionStartedDeferred] (ssh →
 * notifications → clipboard → audio, run by the supervisor on a
 * session-lifecycle coroutine once the desktop start path has settled, or a
 * bounded fallback). The deferred features are fail-open with no consumer on
 * the desktop path, so the deferral trades nothing but a bounded provisioning
 * window for the desktop's spawn capacity.
 */
@Singleton
class WorkspaceFeatureReconciler @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val host: GuestFeatureHost,
    private val notificationSupervisor: WorkspaceNotificationSupervisor,
    private val clipboardSupervisor: WorkspaceClipboardSupervisor,
    private val audioSupervisor: WorkspaceAudioSupervisor,
    private val networkProvisioner: WorkspaceNetworkProvisioner,
    /**
     * Transformation spec §6/§29: component outcomes are reported here so a
     * fail-open feature failure is observable (Degraded health + diagnostics
     * surface) instead of visible only in logs. Reporting is part of the
     * fail-open contract: a report can never throw into the launch path.
     */
    private val healthRegistry: WorkspaceHealthRegistry,
) {

    private val supervisor = WorkspaceFeatureSupervisor(host)

    /**
     * Inline launch phase: apply [workspaceId]'s *network* provisioning from
     * its persisted settings.
     *
     * Phase 2E-1 split: this is the ONLY reconcile work the launch path still
     * awaits. Network stays inline because the desktop start path's Xvnc
     * install consumes the guest's `/etc/resolv.conf` — it is the one feature
     * with a true consumer on the desktop path, and its execs are few. The
     * remaining features moved to [onSessionStartedDeferred] so their execs
     * stop competing with the desktop's spawns for CPU (Phase 2D measured
     * T0→T4 degrading with every provisioning exec in the window).
     *
     * Called once per workspace session, right after the session reaches
     * `Running` (a guest must exist before anything can exec in it).
     * Fail-open: the last outcome is recorded on the provisioner and the
     * health registry, never thrown into the launch path.
     */
    suspend fun onSessionStarted(workspaceId: String) {
        // Network Configuration: apply the workspace's persisted network
        // settings (DNS / hosts / package mirror) at every start — boot-time
        // provisioning, not a one-time install seed. Fail-open like every
        // feature here: the last outcome is recorded on the provisioner for
        // the diagnostics surface, never thrown into the launch path.
        applyNetwork(workspaceId, readMetadata(workspaceId)?.attributes.orEmpty())
    }

    /**
     * Deferred launch phase: apply [workspaceId]'s daemon-backed *feature*
     * toggles — ssh → notifications → clipboard → audio, the §1.110 order.
     *
     * Phase 2E-1: the supervisor runs this AFTER the desktop start path has
     * settled (or a bounded fallback), on a coroutine tied to the session
     * lifecycle — never inline on the launch path. Every feature here is
     * fail-open and idempotent (a second reconcile with an already-running
     * daemon or an already-installed shim executes nothing new), which is
     * what makes the deferral and a concurrent Features-tab reconcile safe:
     * the supervisors converge on the guest's actual state, never on their
     * own bookkeeping.
     *
     * Only features that are *enabled* are acted on here. A disabled toggle
     * deliberately runs no guest command: a fresh session has no daemons, so
     * a stop-reconcile would be a pointless exec per launch, and
     * [onSessionStopping] is what cleans up an existing one.
     */
    suspend fun onSessionStartedDeferred(workspaceId: String) {
        val metadata = readMetadata(workspaceId)
        val toggles = resolveFeatureToggles(
            WorkspaceContent.builtinFeatureDefaults,
            metadata?.featureToggles.orEmpty()
        )
        // UI/UX Phase 6: the per-feature *configuration*, read from the same
        // record as the toggles and through the same total resolver the Features
        // tab uses — so the launch path and the tab can no more disagree about a
        // port than §1.110 let them disagree about a toggle. Absent attributes
        // (every pre-Phase-6 workspace) resolve to the previous defaults.
        val settings = FeatureSettings.from(metadata?.attributes.orEmpty())
        applySsh(
            workspaceId = workspaceId,
            enabled = toggles[SshdProvisioner.FEATURE_SSH] ?: false,
            port = settings.sshPort
        )
        applyNotifications(
            workspaceId = workspaceId,
            enabled = toggles[NotifySendProvisioner.FEATURE_NOTIFICATIONS] ?: false,
            displayName = metadata?.displayName
        )
        applyClipboard(
            workspaceId = workspaceId,
            enabled = toggles[ClipboardBridgeProvisioner.FEATURE_CLIPBOARD] ?: false,
            aliases = settings.selectedAliases
        )
        applyAudio(
            workspaceId = workspaceId,
            enabled = toggles[AudioBridgeProvisioner.FEATURE_AUDIO] ?: false
        )
    }

    /**
     * Network Configuration: provision the guest's network from its
     * persisted settings. Deliberately inside the reconciler's fail-open
     * family — a failed DNS write must not cost the user their session.
     */
    private suspend fun applyNetwork(workspaceId: String, attributes: Map<String, String>) {
        try {
            val result = networkProvisioner.provisionOnStart(workspaceId, NetworkSettings.from(attributes))
            // Transformation spec §6/§29: the network outcome is a component
            // fact — a failed DNS/mirror write leaves the workspace running
            // but Degraded, and the diagnostics surface can show which step
            // failed (the provisioner's own per-step results drive the detail).
            val failures = buildList {
                result.dnsFailure?.let { add("DNS: $it") }
                result.hostsFailure?.let { add("hosts: $it") }
                result.mirrorFailure?.let { add("package mirror: $it") }
            }
            if (failures.isEmpty()) {
                healthRegistry.reportHealthy(workspaceId, COMPONENT_NETWORK)
            } else {
                healthRegistry.reportDegraded(
                    workspaceId,
                    COMPONENT_NETWORK,
                    "network provisioning: " + failures.joinToString(", ")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: network provisioning threw — workspace keeps running: ${e.message}")
            healthRegistry.reportDegraded(workspaceId, COMPONENT_NETWORK, "network provisioning threw: ${e.message}")
        }
    }

    private suspend fun applySsh(workspaceId: String, enabled: Boolean, port: Int) {
        if (!enabled) {
            Logger.i(TAG, "$workspaceId: ssh toggle off — no guest-side SSH work at launch")
            return
        }
        // Fail-open: reconcileSsh already converts every failure into a
        // SshFeatureState.Failed, but a host-level throw (binder/runtime death)
        // must not escape into the launch coroutine either.
        val state = try {
            supervisor.reconcileSsh(workspaceId, enabled = true, port = port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$workspaceId: SSH launch reconcile threw — workspace keeps running", e)
            return
        }
        when (state) {
            is SshFeatureState.Running -> {
                Logger.i(TAG, "$workspaceId: ssh enabled at launch — listening on ${state.port}")
                healthRegistry.reportHealthy(workspaceId, COMPONENT_SSH)
            }
            is SshFeatureState.Failed -> {
                Logger.w(TAG, "$workspaceId: ssh enabled but could not start: ${state.reason}")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_SSH, "sshd could not start: ${state.reason}")
            }
            else -> {
                Logger.w(TAG, "$workspaceId: ssh launch reconcile ended in $state")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_SSH, "sshd launch reconcile ended in $state")
            }
        }
    }

    /**
     * §1.111: install the `notify-send` shim and start watching the spool when
     * the `notifications` toggle is on.
     *
     * Unlike SSH this feature's built-in default is *enabled*
     * ([WorkspaceContent.builtinFeatureDefaults]), so this runs for a workspace
     * that never touched the Features tab — which is precisely why it must be
     * cheap and fail-open: one idempotent guest exec that writes a script, plus
     * a poll of a normally empty directory.
     */
    private suspend fun applyNotifications(
        workspaceId: String,
        enabled: Boolean,
        displayName: String?
    ) {
        if (!enabled) {
            Logger.i(TAG, "$workspaceId: notifications toggle off — no shim installed at launch")
            return
        }
        val state = try {
            // Attribution before the first post can happen: a user with several
            // workspaces must be able to tell which one is talking, and the raw
            // id is a UUID. This is the only place that already has the metadata.
            displayName?.let { notificationSupervisor.setDisplayName(workspaceId, it) }
            notificationSupervisor.reconcileNotifications(workspaceId, enabled = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$workspaceId: notification launch reconcile threw — workspace keeps running", e)
            return
        }
        when (state) {
            is NotificationFeatureState.Active -> {
                Logger.i(TAG, "$workspaceId: notifications enabled at launch (notify-send bridged)")
                healthRegistry.reportHealthy(workspaceId, COMPONENT_NOTIFICATIONS)
            }
            is NotificationFeatureState.Blocked -> {
                Logger.w(TAG, "$workspaceId: notification shim installed but the host cannot post (permission/channel)")
                healthRegistry.reportDegraded(
                    workspaceId,
                    COMPONENT_NOTIFICATIONS,
                    "notify-send shim installed but the host cannot post (permission/channel)"
                )
            }
            is NotificationFeatureState.Failed -> {
                Logger.w(TAG, "$workspaceId: notifications enabled but the shim failed: ${state.reason}")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_NOTIFICATIONS, "notification shim failed: ${state.reason}")
            }
            else -> {
                Logger.w(TAG, "$workspaceId: notification launch reconcile ended in $state")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_NOTIFICATIONS, "notification reconcile ended in $state")
            }
        }
    }

    /**
     * §1.112: install the clipboard shims, seed the paste mirror and start the
     * bridge watcher when the `clipboard` toggle is on.
     *
     * Like `notifications` (and unlike `ssh`) this feature's built-in default is
     * *enabled*, so it runs for a workspace that never opened the Features tab —
     * hence the same insistence that it be cheap and fail-open: a few idempotent
     * guest execs that write small scripts, plus a poll of a normally empty
     * directory.
     */
    private suspend fun applyClipboard(
        workspaceId: String,
        enabled: Boolean,
        aliases: List<ClipboardAlias>
    ) {
        if (!enabled) {
            Logger.i(TAG, "$workspaceId: clipboard toggle off — no bridge installed at launch")
            return
        }
        val state = try {
            clipboardSupervisor.reconcileClipboard(workspaceId, enabled = true, aliases = aliases)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$workspaceId: clipboard launch reconcile threw — workspace keeps running", e)
            return
        }
        when (state) {
            is ClipboardFeatureState.Active -> {
                Logger.i(TAG, "$workspaceId: clipboard bridged at launch (aliases: ${state.aliases})")
                healthRegistry.reportHealthy(workspaceId, COMPONENT_CLIPBOARD)
            }
            is ClipboardFeatureState.Failed -> {
                Logger.w(TAG, "$workspaceId: clipboard enabled but the bridge failed: ${state.reason}")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_CLIPBOARD, "clipboard bridge failed: ${state.reason}")
            }
            else -> {
                Logger.w(TAG, "$workspaceId: clipboard launch reconcile ended in $state")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_CLIPBOARD, "clipboard reconcile ended in $state")
            }
        }
    }

    /**
     * §1.113: create the audio channel, start the pump and point the guest's
     * audio stack at it when the `audio` toggle is on.
     *
     * Also default-**on**, so the same cheap-and-fail-open insistence applies —
     * with one extra consideration the other two don't have: the pump blocks in a
     * FIFO read for as long as the workspace plays nothing, so it must be started
     * on the supervisor's own IO scope (it is) and never awaited here.
     */
    private suspend fun applyAudio(workspaceId: String, enabled: Boolean) {
        if (!enabled) {
            Logger.i(TAG, "$workspaceId: audio toggle off — no bridge installed at launch")
            return
        }
        val state = try {
            audioSupervisor.reconcileAudio(workspaceId, enabled = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$workspaceId: audio launch reconcile threw — workspace keeps running", e)
            return
        }
        when (state) {
            is AudioFeatureState.Active -> {
                Logger.i(TAG, "$workspaceId: audio bridged at launch (${state.mechanisms})")
                healthRegistry.reportHealthy(workspaceId, COMPONENT_AUDIO)
            }
            is AudioFeatureState.Conflict -> {
                Logger.w(TAG, "$workspaceId: audio bridge up but the guest has its own audio configuration")
                // A guest-side audio configuration is not a failure — the
                // feature reconciled to a working state, just not ours.
                healthRegistry.reportHealthy(workspaceId, COMPONENT_AUDIO)
            }
            is AudioFeatureState.Unsupported -> {
                Logger.w(TAG, "$workspaceId: this device gave no playback stream — audio not bridged")
                healthRegistry.reportDegraded(
                    workspaceId,
                    COMPONENT_AUDIO,
                    "this device gave no playback stream — audio not bridged"
                )
            }
            is AudioFeatureState.Failed -> {
                Logger.w(TAG, "$workspaceId: audio enabled but the bridge failed: ${state.reason}")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_AUDIO, "audio bridge failed: ${state.reason}")
            }
            else -> {
                Logger.w(TAG, "$workspaceId: audio launch reconcile ended in $state")
                healthRegistry.reportDegraded(workspaceId, COMPONENT_AUDIO, "audio reconcile ended in $state")
            }
        }
    }

    /**
     * Stop every guest-side feature half for [workspaceId].
     *
     * The runtime already stops all daemons as part of its own teardown
     * ([com.fem.linexa.service.runtime.container.ContainerRuntime.stopAllDaemons],
     * called from `closeWorkspaceSessions`), so this is not the mechanism that
     * prevents a leak — it exists so a feature with host-side state (§1.111's
     * spool watcher and posted notifications are the first) has one ordered place
     * to release it, and so a daemon is stopped *before* the PRoot hosts are torn
     * down rather than as a side effect of it. Idempotent and fail-open.
     */
    suspend fun onSessionStopping(workspaceId: String) {
        try {
            supervisor.reconcileSsh(workspaceId, enabled = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: SSH stop reconcile failed (teardown continues): ${e.message}")
        }
        // §1.111: release host-side state only — the guest may already be gone by
        // now, so running the shim uninstall would just log a failure. The
        // watcher must stop and the workspace's notifications must be withdrawn
        // regardless (a stopped workspace implying it is still running is the bug
        // this prevents); the shim itself stays for the next session.
        try {
            notificationSupervisor.releaseHostState(workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: notification release failed (teardown continues): ${e.message}")
        }
        // §1.112: same for the clipboard bridge — and here the mirror file is
        // *deleted*, not left behind like the notify-send shim: it holds a copy of
        // the USER's clipboard, which has no business sitting in the rootfs of a
        // workspace that is no longer running. The shims carry no data and stay.
        try {
            clipboardSupervisor.releaseHostState(workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: clipboard release failed (teardown continues): ${e.message}")
        }
        // §1.113: stop the audio pump. Nothing is deleted — a FIFO holds no data
        // at rest (bytes live in the kernel buffer only while both ends are open),
        // so unlike §1.112's mirror there is no leftover copy to clean up. The
        // pump MUST stop though: it is a thread blocked on a FIFO read, and a
        // stopped workspace has no writer to wait for.
        try {
            audioSupervisor.releaseHostState(workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: audio release failed (teardown continues): ${e.message}")
        }
    }

    /**
     * The workspace's persisted metadata, or null when it cannot be read.
     *
     * Read once per launch and shared by every feature: the toggles come from it
     * (§1.81's Room map, still the single source of truth) and so does the
     * display name §1.111 attributes notifications with. A throwing repository
     * resolves to null — which means the built-in defaults apply and nothing
     * fails, which is what makes this fail-open.
     */
    private suspend fun readMetadata(workspaceId: String): WorkspaceMetadata? = try {
        workspaceRepository.getById(workspaceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$workspaceId: could not read workspace metadata: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "FeatureReconciler"

        /** §6 component keys (WorkspaceHealthRegistry entries). */
        const val COMPONENT_SSH = "ssh"
        const val COMPONENT_NOTIFICATIONS = "notifications"
        const val COMPONENT_CLIPBOARD = "clipboard"
        const val COMPONENT_AUDIO = "audio"
        const val COMPONENT_NETWORK = "network"
    }
}
