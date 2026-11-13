package com.fem.linexa.ui.screens.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.packagemanager.AptSimulateParser
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.features.AudioFeatureState
import com.fem.linexa.service.runtime.features.ClipboardAlias
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.ClipboardFeatureState
import com.fem.linexa.service.runtime.features.DesktopInstallPolicy
import com.fem.linexa.service.runtime.features.DesktopPreset
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.NotificationFeatureState
import com.fem.linexa.service.runtime.features.SshFeatureState
import com.fem.linexa.service.runtime.features.SshdProvisioner
import com.fem.linexa.service.runtime.features.WorkspaceAudioSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceClipboardSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceFeatureSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceNotificationSupervisor
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.installer.DesktopEnvironmentProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §1.109 — drives the guest-side half of the Features tab.
 *
 * §1.81 made the toggles persistent state; the *intent* still lives in
 * [WorkspaceDetailViewModel.setFeatureToggle] (Room, the single source of
 * truth). This ViewModel owns the separate concern of making the guest match
 * that intent, and it exposes the guest's actual condition
 * ([SshFeatureState]) so a failed enable is visible instead of a toggle that
 * merely *looks* on.
 *
 * Mirrors [ShortcutsViewModel]'s binder convention: the graph-scoped
 * [RuntimeBinder] is attached by the tab, never bound here (§1.2 — a
 * sub-screen must not bind the service or touch the session lifecycle).
 *
 * §1.111: the same treatment for `notifications`. Its supervisor is *injected*
 * rather than constructed per binder like [WorkspaceFeatureSupervisor], because
 * it owns host-side state (the spool watcher) that must outlive this screen —
 * the launch path (§1.110) and this tab therefore share one instance, so a
 * toggle tap here can never end up with a second poller draining the same spool.
 *
 * §1.112: and for `clipboard`, injected for the same reason — plus one more: the
 * clipboard change listener is process-wide state with exactly one owner, so a
 * screen must never construct its own supervisor.
 *
 * §1.113: and for `audio`, where the shared-instance rule is strongest of all —
 * its pump is a thread blocked reading the workspace's FIFO, and two of them
 * would split the guest's PCM stream between them and play garbage.
 */
@HiltViewModel
class FeaturesViewModel @Inject constructor(
    private val notificationSupervisor: WorkspaceNotificationSupervisor,
    private val clipboardSupervisor: WorkspaceClipboardSupervisor,
    private val audioSupervisor: WorkspaceAudioSupervisor,
    /**
     * 2026-09-02 (guided desktop installer, Phase 3): the workspace path
     * layout, for the rootfs/static-prerequisite reads. Same singleton the
     * runtime and installers use — no parallel path system.
     */
    private val fileManager: WorkspaceFileManager,
    /**
     * 2026-09-02 (guided desktop installer, Phase 3): the workspace
     * repository, so a completed install refreshes the persisted
     * desktop-capability attribute from the live probe (same single source
     * of truth `WorkspaceDetailViewModel` writes through).
     */
    private val workspaceRepository: com.fem.linexa.domain.workspace.WorkspaceRepository? = null
) : ViewModel() {

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null
    private var supervisor: WorkspaceFeatureSupervisor? = null
    private var sshJob: Job? = null
    private var notificationJob: Job? = null
    private var clipboardJob: Job? = null
    private var audioJob: Job? = null

    private val _sshState = MutableStateFlow<SshFeatureState>(SshFeatureState.Disabled)
    val sshState: StateFlow<SshFeatureState> = _sshState.asStateFlow()

    private val _notificationState =
        MutableStateFlow<NotificationFeatureState>(NotificationFeatureState.Disabled)
    val notificationState: StateFlow<NotificationFeatureState> = _notificationState.asStateFlow()

    private val _clipboardState =
        MutableStateFlow<ClipboardFeatureState>(ClipboardFeatureState.Disabled)
    val clipboardState: StateFlow<ClipboardFeatureState> = _clipboardState.asStateFlow()

    private val _audioState = MutableStateFlow<AudioFeatureState>(AudioFeatureState.Disabled)
    val audioState: StateFlow<AudioFeatureState> = _audioState.asStateFlow()

    /**
     * 2026-09-02 (guided desktop installer, Phase 3): the guided Debian/
     * Ubuntu + APT desktop installation state machine, rendered by the
     * Desktop settings panel. See [DesktopInstallUiState] for the flow and
     * the honesty contract; see [DesktopInstallPolicy] for the decisions.
     */
    private val _desktopInstallState = MutableStateFlow<DesktopInstallUiState>(DesktopInstallUiState.Idle)
    val desktopInstallState: StateFlow<DesktopInstallUiState> = _desktopInstallState.asStateFlow()
    private var desktopInstallJob: Job? = null

    /**
     * Attach the workspace's shared binder. Idempotent for the same
     * `(workspaceId, binder)` pair; a late-arriving or replaced binder swaps
     * in a fresh supervisor.
     */
    fun attachBinder(wsId: String, runtimeBinder: RuntimeBinder) {
        if (workspaceId == wsId && binder === runtimeBinder) return
        workspaceId = wsId
        binder = runtimeBinder
        supervisor = WorkspaceFeatureSupervisor(runtimeBinder)
    }

    fun detachBinder() {
        sshJob?.cancel()
        sshJob = null
        notificationJob?.cancel()
        notificationJob = null
        clipboardJob?.cancel()
        clipboardJob = null
        audioJob?.cancel()
        audioJob = null
        binder = null
        supervisor = null
        workspaceId = null
        _sshState.value = SshFeatureState.Disabled
        _notificationState.value = NotificationFeatureState.Disabled
        _clipboardState.value = ClipboardFeatureState.Disabled
        _audioState.value = AudioFeatureState.Disabled
    }

    /**
     * Reflect the guest's current sshd condition without changing anything —
     * called when the tab opens (and after a binder swap) so an already
     * running daemon shows as [SshFeatureState.Running] rather than
     * defaulting to Disabled.
     *
     * @param toggleEnabled the persisted `ssh` toggle, used only to
     *   distinguish "off" from "on but the session isn't running".
     * @param port the persisted [FeatureSettings.sshPort]. Only used to
     *   *describe* an already-running daemon, which may in fact have been
     *   started on a previous value — see
     *   [com.fem.linexa.service.runtime.features.WorkspaceFeatureSupervisor.reconcileSsh]'s
     *   note on why a live daemon is not restarted to adopt a new port.
     */
    fun refreshSshState(toggleEnabled: Boolean, port: Int = SshdProvisioner.SSH_PORT) {
        val ws = workspaceId ?: return
        val b = binder ?: return
        _sshState.value = when {
            b.isDaemonAlive(ws, SshdProvisioner.DAEMON_NAME) -> SshFeatureState.Running(port)
            !toggleEnabled -> SshFeatureState.Disabled
            !b.hasActiveSession(ws) -> SshFeatureState.Unavailable
            else -> SshFeatureState.Disabled
        }
    }

    /**
     * Converge the guest to the *persisted* toggle on attach.
     *
     * This is what makes the toggle mean something across app restarts and
     * across the "enabled while the workspace was stopped" case
     * ([SshFeatureState.Unavailable]): the intent is already saved in Room, so
     * the guest is brought in line with it rather than waiting for the user to
     * flip the switch again.
     *
     * Deliberately narrow — it only ever *starts* a daemon that the user has
     * already enabled, and only when there is a live session and nothing is
     * running yet. A disabled toggle triggers no guest command at all (an
     * unconditional disable-reconcile here would run `stopDaemon` on every tab
     * open, and would fight a start that the user just requested).
     */
    fun syncSshOnAttach(toggleEnabled: Boolean, port: Int = SshdProvisioner.SSH_PORT) {
        refreshSshState(toggleEnabled, port)
        val ws = workspaceId ?: return
        val b = binder ?: return
        if (!toggleEnabled) return
        if (b.isDaemonAlive(ws, SshdProvisioner.DAEMON_NAME)) return
        if (!b.hasActiveSession(ws)) return
        if (sshJob?.isActive == true) return
        setSshEnabled(true, port)
    }

    /**
     * Converge the guest to [enabled]. Safe to call repeatedly — the
     * supervisor reconciles rather than handling events — and a new call
     * replaces an in-flight one (the user flipping the switch back before the
     * install finished must not be overridden by the stale result).
     *
     * @param port the persisted [FeatureSettings.sshPort] to start sshd on.
     */
    fun setSshEnabled(enabled: Boolean, port: Int = SshdProvisioner.SSH_PORT) {
        val ws = workspaceId ?: return
        val featureSupervisor = supervisor ?: return
        sshJob?.cancel()
        sshJob = viewModelScope.launch {
            val finalState = featureSupervisor.reconcileSsh(
                workspaceId = ws,
                enabled = enabled,
                port = port,
                onProgress = { _sshState.value = it }
            )
            _sshState.value = finalState
            if (finalState is SshFeatureState.Failed) {
                Logger.w(TAG, "SSH reconcile for $ws ended in Failed: ${finalState.reason}")
            }
        }
    }

    /**
     * §1.111: converge the guest's `notify-send` shim to the persisted
     * `notifications` toggle on attach.
     *
     * Symmetric with [syncSshOnAttach] but *not* narrowed to enable-only: unlike
     * `ssh`, this feature's built-in default is **on**, so a user turning it off
     * is the interesting case and the disable must actually run (remove the shim,
     * stop the watcher, withdraw notifications) rather than being skipped. It is
     * idempotent either way.
     */
    fun syncNotificationsOnAttach(toggleEnabled: Boolean) {
        if (workspaceId == null) return
        if (notificationJob?.isActive == true) return
        setNotificationsEnabled(toggleEnabled)
    }

    /**
     * §1.111: converge the guest to [enabled] for the Notifications feature.
     * Safe to call repeatedly (the supervisor reconciles), and a new call
     * replaces an in-flight one so flipping the switch back mid-install wins.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        val ws = workspaceId ?: return
        notificationJob?.cancel()
        notificationJob = viewModelScope.launch {
            val finalState = notificationSupervisor.reconcileNotifications(ws, enabled)
            _notificationState.value = finalState
            if (finalState is NotificationFeatureState.Failed) {
                Logger.w(TAG, "Notification reconcile for $ws ended in Failed: ${finalState.reason}")
            }
        }
    }

    /**
     * §1.112: converge the guest's clipboard bridge to the persisted `clipboard`
     * toggle on attach.
     *
     * Same shape as [syncNotificationsOnAttach] and for the same reason — this
     * feature also defaults to **on**, so the disable must actually run rather
     * than being skipped.
     */
    fun syncClipboardOnAttach(
        toggleEnabled: Boolean,
        aliases: List<ClipboardAlias> = ClipboardBridgeProvisioner.ALIASES
    ) {
        if (workspaceId == null) return
        if (clipboardJob?.isActive == true) return
        setClipboardEnabled(toggleEnabled, aliases)
    }

    /**
     * §1.112: converge the guest to [enabled] for the Clipboard feature. Safe to
     * call repeatedly (the supervisor reconciles), and a new call replaces an
     * in-flight one so flipping the switch back mid-install wins.
     *
     * @param aliases the persisted [FeatureSettings.selectedAliases]. Unlike the
     *   SSH port, an alias change **does** take effect on the spot: the supervisor
     *   is idempotent, installing a newly-selected alias is a file write, and the
     *   uninstall sweep removes a deselected one — no process is killed, so there
     *   is nothing to surprise the user with.
     */
    fun setClipboardEnabled(
        enabled: Boolean,
        aliases: List<ClipboardAlias> = ClipboardBridgeProvisioner.ALIASES
    ) {
        val ws = workspaceId ?: return
        clipboardJob?.cancel()
        clipboardJob = viewModelScope.launch {
            val finalState = clipboardSupervisor.reconcileClipboard(ws, enabled, aliases)
            _clipboardState.value = finalState
            if (finalState is ClipboardFeatureState.Failed) {
                Logger.w(TAG, "Clipboard reconcile for $ws ended in Failed: ${finalState.reason}")
            }
        }
    }

    /**
     * UI/UX Phase 6: re-apply the clipboard bridge after its alias selection
     * changed, but only when the feature is on.
     *
     * Separate from [setClipboardEnabled] because the toggle and the selection are
     * different intents: this must never *enable* a feature the user has switched
     * off — an alias checkbox is reachable while the switch is off (see
     * `FeatureConfig.isConfigurable`), and installing shims into the guest because
     * someone ticked a box in a disabled panel would be the panel acting as a
     * second, hidden toggle.
     */
    fun applyClipboardAliases(toggleEnabled: Boolean, aliases: List<ClipboardAlias>) {
        if (!toggleEnabled) return
        setClipboardEnabled(true, aliases)
    }

    /**
     * §1.113: converge the guest's audio bridge to the persisted `audio` toggle on
     * attach. Same shape as [syncClipboardOnAttach] and for the same reason — this
     * feature also defaults to **on**, so the disable must actually run.
     */
    fun syncAudioOnAttach(toggleEnabled: Boolean) {
        if (workspaceId == null) return
        if (audioJob?.isActive == true) return
        setAudioEnabled(toggleEnabled)
    }

    /**
     * §1.113: converge the guest to [enabled] for the Audio feature. Safe to call
     * repeatedly (the supervisor reconciles), and a new call replaces an in-flight
     * one so flipping the switch back mid-install wins.
     */
    fun setAudioEnabled(enabled: Boolean) {
        val ws = workspaceId ?: return
        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            val finalState = audioSupervisor.reconcileAudio(ws, enabled)
            _audioState.value = finalState
            if (finalState is AudioFeatureState.Failed) {
                Logger.w(TAG, "Audio reconcile for $ws ended in Failed: ${finalState.reason}")
            }
        }
    }

    // ── 2026-09-02: guided desktop installer (Phase 3) ────────────────────

    /**
     * Gathers the guided installer's preconditions and, when they allow it,
     * the REAL size quote, and stops at [DesktopInstallUiState.Quote] —
     * nothing is installed here. The user's confirmation
     * ([confirmDesktopInstall]) is a separate, explicit action.
     *
     * Preconditions (in order, each a hard block when unsatisfied):
     * 1. a live binder/session (the install execs run through the same
     *    shared channel as every other guest operation — no parallel exec);
     * 2. the workspace rootfs exists;
     * 3. the rootfs identifies as Debian or Ubuntu (`/etc/os-release`);
     * 4. an APT binary exists in the rootfs;
     * 5. a preset is selected;
     * 6. the apt-simulated installed requirement fits the free space.
     *
     * The simulated sizes are REAL `apt-get -s` output parsed by
     * [AptSimulateParser]; when the simulation fails they stay null and the
     * UI shows "size unknown" — an estimate is never dressed as a number,
     * and an unknown requirement never hard-blocks (apt itself enforces
     * storage mid-install, and the install is retryable).
     *
     * Idempotency: the static probe runs FIRST — an already-installed
     * preset lands in [DesktopInstallUiState.Quote] with
     * `alreadyInstalled=true` and no confirm action, instead of a
     * needless reinstall.
     */
    fun prepareDesktopInstall(preset: DesktopPreset) {
        val ws = workspaceId ?: return
        val b = binder
        val fm = fileManager
        desktopInstallJob?.cancel()
        desktopInstallJob = viewModelScope.launch {
            _desktopInstallState.value = DesktopInstallUiState.Preparing(preset)
            val prereqs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DesktopInstallPolicy.staticPrerequisites(
                    rootfs = fm.getRootfsPath(ws),
                    workspaceDir = fm.getWorkspaceBasePath(ws),
                    preset = preset
                )
            }
            // The execs need the live session; without it the flow refuses
            // explicitly instead of a silent no-op (the static checks still
            // ran, so the quote renders with whatever facts exist).
            if (b == null) {
                _desktopInstallState.value = DesktopInstallUiState.Quote(
                    preset = preset,
                    prerequisites = prereqs,
                    downloadBytes = null,
                    installedBytes = null,
                    newPackages = null,
                    alreadyInstalled = false,
                    blockReason = DesktopInstallPolicy.BlockReason.NO_SESSION
                )
                return@launch
            }
            val staticBlock = prereqs.hardBlockReason(preset)
            if (staticBlock != null) {
                _desktopInstallState.value = DesktopInstallUiState.Quote(
                    preset = preset,
                    prerequisites = prereqs,
                    downloadBytes = null,
                    installedBytes = null,
                    newPackages = null,
                    alreadyInstalled = false,
                    blockReason = staticBlock
                )
                return@launch
            }
            if (prereqs.sessionPath != null) {
                // Phase-3 idempotency: installed and ready — report, don't reinstall.
                _desktopInstallState.value = DesktopInstallUiState.Quote(
                    preset = preset,
                    prerequisites = prereqs,
                    downloadBytes = null,
                    installedBytes = null,
                    newPackages = null,
                    alreadyInstalled = true,
                    blockReason = null
                )
                return@launch
            }

            // Real size quote from the guest's own APT metadata. A failed
            // simulation (no network, broken index) is NOT a block: the
            // sizes stay null and the UI labels them unknown.
            val simulate = DesktopPreset.simulateCommand(preset, "apt")
            val quote = simulate?.let { runCatching { b.execCommand(ws, it) }.getOrNull() }
            val info = quote
                ?.takeIf { it.exitCode == 0 }
                ?.let { AptSimulateParser.parseSimulate(it.output) }
            val download = info?.downloadSizeBytes
            val installed = info?.installedSizeBytes
            val storageBlock = DesktopInstallPolicy.storageBlock(download, installed, prereqs.freeBytes)

            _desktopInstallState.value = DesktopInstallUiState.Quote(
                preset = preset,
                prerequisites = prereqs,
                downloadBytes = download,
                installedBytes = installed,
                newPackages = info?.newPackages,
                alreadyInstalled = false,
                blockReason = storageBlock
            )
        }
    }

    /**
     * Runs the confirmed install through the shared streaming exec channel
     * ([RuntimeBinder.execCommandStreaming]) — the same channel the Package
     * Manager uses, so the existing dpkg-health/lock-safety machinery
     * applies unchanged. Output lines are classified by
     * [AptSimulateParser.parseInstallProgress]; only deterministic dpkg
     * markers set [DesktopInstallPhase.INSTALLING] with a package name, and
     * no percentage is ever emitted (apt prints none that is reliably
     * parseable here).
     *
     * On success the rootfs is re-probed statically before anything is
     * reported complete, and the persisted desktop-capability attribute is
     * refreshed from that probe so the Desktop button gating converges.
     */
    fun confirmDesktopInstall() {
        val current = _desktopInstallState.value as? DesktopInstallUiState.Quote ?: return
        if (current.blockReason != null || current.alreadyInstalled) return
        val ws = workspaceId ?: return
        val b = binder ?: return
        val fm = fileManager
        val command = DesktopPreset.installCommand(current.preset, "apt") ?: return

        desktopInstallJob?.cancel()
        desktopInstallJob = viewModelScope.launch {
            var phase = DesktopInstallPhase.UPDATING_INDEX
            var currentPackage: String? = null
            val tail = ArrayDeque<String>()

            _desktopInstallState.value =
                DesktopInstallUiState.Installing(current.preset, phase, null)
            val result = try {
                b.execCommandStreaming(ws, command) { line ->
                    // Bounded tail for the failure message (real output, never invented).
                    if (line.isNotBlank()) {
                        tail.addLast(line.trim())
                        while (tail.size > FAILURE_TAIL_LINES) tail.removeFirst()
                    }
                    // Phase transitions from deterministic markers only.
                    val progress = AptSimulateParser.parseInstallProgress(line)
                    if (progress != null) {
                        phase = DesktopInstallPhase.INSTALLING
                        currentPackage = progress.packageName
                    } else if (phase == DesktopInstallPhase.UPDATING_INDEX &&
                        (
                            line.startsWith("Reading package lists") ||
                                line.startsWith("Building dependency tree") ||
                                line.startsWith("The following") ||
                                line.startsWith("Need to get")
                            )
                    ) {
                        phase = DesktopInstallPhase.DOWNLOADING
                    }
                    _desktopInstallState.value = DesktopInstallUiState.Installing(
                        current.preset, phase, currentPackage
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Desktop install exec threw for $ws", e)
                _desktopInstallState.value =
                    DesktopInstallUiState.Failed(e.message ?: "Desktop installation failed")
                return@launch
            }

            if (result.exitCode != 0) {
                val reason = result.error.ifBlank { result.output }.ifBlank {
                    "apt-get exited with code ${result.exitCode}"
                }
                _desktopInstallState.value = DesktopInstallUiState.Failed(
                    (reason.lines().filter { it.isNotBlank() }.takeLast(FAILURE_TAIL_LINES) +
                        tail.toList()).distinct().joinToString("\n").take(MAX_REASON_CHARS)
                )
                Logger.w(TAG, "Desktop install failed for $ws (exit=${result.exitCode})")
                return@launch
            }

            // Verify before reporting success — same rule the RootFS flow
            // pins (§1.86): an "exit 0" without the artifact on disk is a
            // failure, never a completed state.
            val sessionPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DesktopEnvironmentProbe.anySessionPresent(fm.getRootfsPath(ws), current.preset)
            }
            if (sessionPath == null) {
                // Typed verify-failure fact; the UI renders the localized
                // message (the ViewModel layer holds no string resources).
                _desktopInstallState.value = DesktopInstallUiState.Failed(
                    reason = "apt-get reported success but no session was found in the workspace",
                    verifyFailedPresetName = current.preset.displayName
                )
                return@launch
            }

            // Refresh the persisted capability attribute from the live probe
            // (desktop prerequisites §4.6) so the Desktop button converges.
            val capable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fem.linexa.service.installer.RootFsCapabilityProbe
                    .hasDesktopCapability(fm.getRootfsPath(ws))
            }
            runCatching {
                workspaceRepository?.updateAttributes(
                    ws,
                    mapOf(
                        com.fem.linexa.domain.workspace.WorkspaceCapabilities.ATTR_DESKTOP to
                            capable.toString()
                    )
                )
            }.onFailure { Logger.w(TAG, "Could not refresh desktop capability for $ws: ${it.message}") }

            _desktopInstallState.value = DesktopInstallUiState.Completed(current.preset, sessionPath)
            Logger.i(TAG, "Desktop install completed for $ws (${current.preset.id}, found $sessionPath)")
        }
    }

    /** Dismisses the guided flow back to [DesktopInstallUiState.Idle]. */
    fun dismissDesktopInstall() {
        desktopInstallJob?.cancel()
        desktopInstallJob = null
        _desktopInstallState.value = DesktopInstallUiState.Idle
    }

    override fun onCleared() {
        sshJob?.cancel()
        notificationJob?.cancel()
        clipboardJob?.cancel()
        audioJob?.cancel()
        desktopInstallJob?.cancel()
        super.onCleared()
    }

    /**
     * Crash-repro harness only (FeaturesTabCrashReproTest): publishes guest
     * states directly, bypassing the reconcile paths that need a live session.
     * The exact publish points the supervisors use, so the harness exercises
     * every status-line branch the real tabs render.
     */
    @androidx.annotation.VisibleForTesting
    fun publishForTest(
        ssh: SshFeatureState,
        notifications: NotificationFeatureState,
        clipboard: ClipboardFeatureState,
        audio: AudioFeatureState
    ) {
        _sshState.value = ssh
        _notificationState.value = notifications
        _clipboardState.value = clipboard
        _audioState.value = audio
    }

    private companion object {
        const val TAG = "FeaturesViewModel"

        /** Bounded real-output tail kept for the failure message. */
        const val FAILURE_TAIL_LINES = 8

        /** Hard cap on the failure reason surfaced to the UI. */
        const val MAX_REASON_CHARS = 600
    }
}
