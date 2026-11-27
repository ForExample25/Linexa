package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.data.Feature
import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.features.AudioBridgeProvisioner
import com.fem.linexa.service.runtime.features.AudioFeatureState
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.ClipboardFeatureState
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.NotificationFeatureState
import com.fem.linexa.service.runtime.features.NotifySendProvisioner
import com.fem.linexa.service.runtime.features.SshFeatureState
import com.fem.linexa.service.runtime.features.SshdProvisioner
import com.fem.linexa.ui.components.FeatureItem
import com.fem.linexa.service.runtime.features.DesktopRunMode

/**
 * §1.81: the Features tab is a *controlled* list — the toggle state lives in
 * the ViewModel/repository (persisted per workspace), not in local composable
 * state. [toggles] is the effective map (id → enabled, resolved from built-in
 * defaults + config.yaml `linexa.features` + persisted user toggles) and
 * [onToggleChange] persists every change. Keeping this stateless means a
 * toggle can never silently diverge from what the runtime actually applies.
 *
 * §1.109: the `ssh` toggle now has a real guest-side half. Its persisted
 * intent still flows through [onToggleChange] (Room, single source of truth);
 * the guest is converged by [FeaturesViewModel], whose [SshFeatureState] is
 * rendered as this row's status line. The two are deliberately separate: a
 * failed enable leaves the user's toggle as they set it and shows the failure,
 * rather than silently flipping the switch back.
 *
 * §1.111: the same for `notifications` — its status line reports whether
 * `notify-send` is actually bridged, including the case where the shim is
 * installed but Android won't let the app post
 * ([NotificationFeatureState.Blocked], distinct from a failure).
 *
 * §1.112: and for `clipboard`, whose status line names the guest commands that
 * were actually provided. That list is genuinely variable — an alias is skipped
 * when a real tool of that name already exists in the workspace — so promising
 * `xclip` unconditionally would be a lie.
 *
 * §1.113: and for `audio`, whose status line names the guest-side routes actually
 * wired (ALSA, PulseAudio) and distinguishes two non-failures the user can act on:
 * a device that gives no playback stream ([AudioFeatureState.Unsupported]) and a
 * workspace whose own ALSA configuration Linexa deliberately did not overwrite
 * ([AudioFeatureState.Conflict]).
 *
 * UI/UX Phase 6: two of the five rows also expand to a settings panel. Like the
 * toggles, that configuration is *controlled* — [settings] is the resolved value
 * from the workspace's persisted attributes and [onSettingsChange] writes every
 * edit through, so a panel can no more diverge from what the runtime applies than
 * a toggle can. Which rows expand comes from [FeatureConfig], never from a
 * condition written here; the audit behind that whitelist is in
 * [FeatureSettings]'s KDoc.
 *
 * The two features differ in *when* an edit reaches the guest, and the difference
 * is real rather than an inconsistency: an alias change is re-applied immediately
 * (writing a shim file disturbs nothing), while a port change is only saved
 * (applying it means killing a live sshd, and with it the user's connections).
 */
@Composable
fun FeaturesTab(
    features: List<Feature>,
    toggles: Map<String, Boolean>,
    onToggleChange: (featureId: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    settings: FeatureSettings = FeatureSettings(),
    onSettingsChange: (FeatureSettings) -> Unit = {},
    workspaceId: String? = null,
    binder: RuntimeBinder? = null,
    /** 2026-09-02: true while the last desktop-enable was refused (no desktop found). */
    desktopToggleBlocked: Boolean = false,
    viewModel: FeaturesViewModel = hiltViewModel()
) {
    val sshState by viewModel.sshState.collectAsState()
    val notificationState by viewModel.notificationState.collectAsState()
    val clipboardState by viewModel.clipboardState.collectAsState()
    val audioState by viewModel.audioState.collectAsState()
    // 2026-09-02 (guided desktop installer, Phase 3): the guided install
    // state machine, rendered by the Desktop settings panel.
    val desktopInstallState by viewModel.desktopInstallState.collectAsState()
    val sshToggled = toggles[SshdProvisioner.FEATURE_SSH]
        ?: features.firstOrNull { it.id == SshdProvisioner.FEATURE_SSH }?.enabledByDefault
        ?: false
    val notificationsToggled = toggles[NotifySendProvisioner.FEATURE_NOTIFICATIONS]
        ?: features.firstOrNull { it.id == NotifySendProvisioner.FEATURE_NOTIFICATIONS }?.enabledByDefault
        ?: false
    val clipboardToggled = toggles[ClipboardBridgeProvisioner.FEATURE_CLIPBOARD]
        ?: features.firstOrNull { it.id == ClipboardBridgeProvisioner.FEATURE_CLIPBOARD }?.enabledByDefault
        ?: false
    val audioToggled = toggles[AudioBridgeProvisioner.FEATURE_AUDIO]
        ?: features.firstOrNull { it.id == AudioBridgeProvisioner.FEATURE_AUDIO }?.enabledByDefault
        ?: false

    // Attach the graph-scoped binder (never bound here — §1.2) and converge the
    // guest to the PERSISTED toggle: an already-running daemon shows as
    // Running, and a toggle that was enabled while the workspace was stopped
    // (SshFeatureState.Unavailable) starts now that a session is live.
    //
    // Keyed on the binder identity only — deliberately NOT on `sshToggled`. A
    // toggle tap is already handled by `onCheckedChange` below; re-keying on
    // the persisted value would fire a second reconcile right after it and
    // overwrite a `Failed` reason with a silent retry. Phase 6's settings are
    // read here for the same reason and are likewise not part of the key: an
    // edit is applied (or not) by its own handler, per the KDoc.
    LaunchedEffect(workspaceId, binder) {
        if (workspaceId != null && binder != null) {
            // Fail-open at the last boundary: the attach + one-shot syncs below
            // already reconcile defensively, but a REAL binder on a live
            // workspace touches guest exec and daemon probes no JVM harness
            // can reproduce. An unexpected throw here must never take the app
            // down — the tab renders with Disabled states instead (2026-08-28
            // on-device Features-crash report; the composition itself is
            // pinned clean by FeaturesTabCrashReproTest +
            // WorkspaceSegmentsCrashReproTest).
            try {
                viewModel.attachBinder(workspaceId, binder)
                viewModel.syncSshOnAttach(sshToggled, settings.sshPort)
                // §1.111: same one-shot convergence for the notification shim. Its
                // supervisor is injected (not binder-derived), but the attach is
                // still gated on a binder so the tab only acts once the workspace
                // session it belongs to is actually bound.
                viewModel.syncNotificationsOnAttach(notificationsToggled)
                // §1.112: same for the clipboard bridge.
                viewModel.syncClipboardOnAttach(clipboardToggled, settings.selectedAliases)
                // §1.113: same for the audio bridge.
                viewModel.syncAudioOnAttach(audioToggled)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.fem.linexa.core.logging.Logger.e(
                    "FeaturesTab",
                    "Feature sync on attach failed for $workspaceId — continuing with default states",
                    e
                )
            }
        }
    }

    // Phase 6: which row is expanded. Local — it is pure view state with no
    // meaning to the runtime — but `rememberSaveable` so a rotation does not
    // collapse a panel mid-edit, and single-valued so two panels are never open
    // competing for a small screen.
    var expandedFeatureId by rememberSaveable { mutableStateOf<String?>(null) }

    // HAVEN fix: this segment now lives INSIDE WorkspaceScreen's vertical
    // scroll. A LazyColumn there would measure with infinite height and crash
    // the tab (the reported Features crash) — and the list is small enough
    // that a plain Column is both correct and cheaper.
    //
    // Concept frame `fr-feat`: all rows live in ONE `card tight` with
    // hairline dividers between them — not a stack of separate cards.
    //
    // UI/UX spacing fix: no extra padding here. The workspace screen already
    // insets this scroll column by HavenDim.screenH horizontally (the SAME
    // inset the Commands and Settings segments use); a second 20dp horizontal
    // padding on this Column doubled the inset to 40dp and made the card read
    // as squeezed toward the middle, and the vertical 16dp fought the
    // segment control's own 20dp bottom gap. Rhythm comes from the shared
    // layers, exactly like every other segment.
    Column(modifier = modifier.fillMaxWidth()) {
        com.fem.linexa.ui.components.haven.HCard(tight = true) {
            features.forEachIndexed { index, feature ->
                if (index > 0) {
                    com.fem.linexa.ui.components.haven.HHairline()
                }
            val isSsh = feature.id == SshdProvisioner.FEATURE_SSH
            val isNotifications = feature.id == NotifySendProvisioner.FEATURE_NOTIFICATIONS
            val isClipboard = feature.id == ClipboardBridgeProvisioner.FEATURE_CLIPBOARD
            val isAudio = feature.id == AudioBridgeProvisioner.FEATURE_AUDIO
            // Desktop Modes: the desktop row needs no guest-side convergence
            // (no daemon is reconciled here — the desktop starts on demand from
            // the Desktop screen, which reads these very settings), so it has
            // no status line and no busy spinner; its mechanism is entirely in
            // the settings panel and the toggle gating the top-bar entry.
            val isDesktop = feature.id == FEATURE_DESKTOP
            val configurable = FeatureConfig.isConfigurable(feature.id)
            FeatureItem(
                iconRes = feature.iconRes,
                title = stringResource(feature.titleRes),
                description = stringResource(feature.descriptionRes),
                checked = toggles[feature.id] ?: feature.enabledByDefault,
                onCheckedChange = { enabled ->
                    onToggleChange(feature.id, enabled)
                    if (isSsh) viewModel.setSshEnabled(enabled, settings.sshPort)
                    if (isNotifications) viewModel.setNotificationsEnabled(enabled)
                    if (isClipboard) viewModel.setClipboardEnabled(enabled, settings.selectedAliases)
                    if (isAudio) viewModel.setAudioEnabled(enabled)
                },
                statusText = when {
                    isSsh -> sshStatusText(sshState)
                    isNotifications -> notificationStatusText(notificationState)
                    isClipboard -> clipboardStatusText(clipboardState)
                    isAudio -> audioStatusText(audioState)
                    // 2026-09-02 (desktop prerequisites): the refusal notice —
                    // the user is never left with a disabled feature and no
                    // route to resolve it; the panel below offers the flow.
                    isDesktop && desktopToggleBlocked ->
                        stringResource(R.string.feature_desktop_missing_status)
                    else -> null
                },
                statusIsError = (isSsh && sshState is SshFeatureState.Failed) ||
                    (isNotifications && notificationState is NotificationFeatureState.Failed) ||
                    (isClipboard && clipboardState is ClipboardFeatureState.Failed) ||
                    (isAudio && audioState is AudioFeatureState.Failed) ||
                    (isDesktop && desktopToggleBlocked),
                busy = isSsh && sshState.isInProgress(),
                summaryText = FeatureConfig.summary(feature.id, settings)?.let { summaryText(it) },
                expanded = expandedFeatureId == feature.id,
                onExpandedChange = if (configurable) {
                    { open -> expandedFeatureId = if (open) feature.id else null }
                } else {
                    null
                },
                settingsContent = when {
                    isSsh -> {
                        {
                            SshSettingsPanel(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                // Only a live daemon has a port to disagree
                                // with the setting; every other state has none.
                                runningPort = (sshState as? SshFeatureState.Running)?.port
                            )
                        }
                    }
                    isClipboard -> {
                        {
                            ClipboardSettingsPanel(
                                settings = settings,
                                onSettingsChange = { next ->
                                    onSettingsChange(next)
                                    // Applied on the spot, unlike the SSH port —
                                    // and gated on the toggle so a panel edit can
                                    // never enable a feature that is switched off.
                                    viewModel.applyClipboardAliases(
                                        toggleEnabled = clipboardToggled,
                                        aliases = next.selectedAliases
                                    )
                                }
                            )
                        }
                    }
                    isDesktop -> {
                        {
                            DesktopSettingsPanel(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                installState = desktopInstallState,
                                onStartInstall = viewModel::prepareDesktopInstall,
                                onConfirmInstall = viewModel::confirmDesktopInstall,
                                onCancelInstall = viewModel::dismissDesktopInstall,
                                onDismissInstall = viewModel::dismissDesktopInstall
                            )
                        }
                    }
                    else -> null
                }
            )
            }
        }
    }
}

/** The collapsed one-liner for a configurable row. */
@Composable
private fun summaryText(summary: FeatureConfig.Summary): String = when (summary) {
    is FeatureConfig.Summary.Port ->
        stringResource(R.string.feature_settings_ssh_port_summary, summary.port)
    FeatureConfig.Summary.AllAliases ->
        stringResource(R.string.feature_settings_clipboard_all, FeatureSettings.ALL_ALIAS_NAMES.size)
    FeatureConfig.Summary.NoAliases ->
        stringResource(R.string.feature_settings_clipboard_none)
    is FeatureConfig.Summary.SomeAliases ->
        stringResource(R.string.feature_settings_clipboard_some, summary.selected, summary.total)
    is FeatureConfig.Summary.DesktopMode -> desktopModeSummary(summary.mode)
}

/** Desktop Modes: the collapsed mode line on the Desktop row. */
@Composable
private fun desktopModeSummary(mode: DesktopRunMode): String = when (mode) {
    DesktopRunMode.VNC ->
        stringResource(R.string.feature_settings_desktop_summary_vnc)
    DesktopRunMode.WEBVIEW ->
        stringResource(R.string.feature_settings_desktop_summary_webview)
}

/**
 * The user-facing status line for the SSH row, or null when there is nothing
 * worth saying (the daemon is off and the toggle agrees).
 */
@Composable
private fun sshStatusText(state: SshFeatureState): String? = when (state) {
    is SshFeatureState.Disabled -> null
    is SshFeatureState.Unavailable -> stringResource(R.string.feature_ssh_unavailable)
    is SshFeatureState.Probing -> stringResource(R.string.feature_ssh_probing)
    is SshFeatureState.Installing -> stringResource(R.string.feature_ssh_installing, state.packageManagerKey)
    is SshFeatureState.Starting -> stringResource(R.string.feature_ssh_starting)
    is SshFeatureState.Running -> stringResource(R.string.feature_ssh_running, state.port)
    is SshFeatureState.Failed -> state.reason
}

/**
 * §1.111 — the status line for the Notifications row. `Active` says so
 * explicitly (this feature is on by default, so silent success would leave the
 * user unsure whether `notify-send` is bridged at all), and `Blocked` explains
 * the Android-permission case rather than looking broken.
 */
@Composable
private fun notificationStatusText(state: NotificationFeatureState): String? = when (state) {
    is NotificationFeatureState.Disabled -> null
    is NotificationFeatureState.Unavailable -> stringResource(R.string.feature_notifications_unavailable)
    is NotificationFeatureState.Active -> stringResource(R.string.feature_notifications_active)
    is NotificationFeatureState.Blocked -> stringResource(R.string.feature_notifications_blocked)
    is NotificationFeatureState.Failed -> state.reason
}

/**
 * §1.112 — the status line for the Clipboard row.
 *
 * `Active` names the commands that were actually provided, because the alias set
 * varies per workspace (a name already taken by a real tool is deliberately not
 * shadowed) and the canonical `linexa-clip-copy`/`-paste` alone are not what a
 * user would think to type. With no aliases at all it still says the bridge is up
 * and names the canonical pair.
 */
@Composable
private fun clipboardStatusText(state: ClipboardFeatureState): String? = when (state) {
    is ClipboardFeatureState.Disabled -> null
    is ClipboardFeatureState.Unavailable -> stringResource(R.string.feature_clipboard_unavailable)
    is ClipboardFeatureState.Active ->
        if (state.aliases.isEmpty()) {
            stringResource(R.string.feature_clipboard_active)
        } else {
            stringResource(R.string.feature_clipboard_active_aliases, state.aliases.joinToString(", "))
        }
    is ClipboardFeatureState.Failed -> state.reason
}

/**
 * §1.113 — the status line for the Audio row.
 *
 * `Active` names the routes actually wired, because they vary per workspace (a
 * PulseAudio sink is only added when a server is already running, and it never
 * installs one). The two non-failure states each get their own line: `Unsupported`
 * is the device refusing a playback stream, `Conflict` is the workspace's own ALSA
 * configuration being left in place — the user can fix the latter and cannot fix
 * the former, so collapsing them into one message would be unhelpful.
 */
@Composable
private fun audioStatusText(state: AudioFeatureState): String? = when (state) {
    is AudioFeatureState.Disabled -> null
    is AudioFeatureState.Unavailable -> stringResource(R.string.feature_audio_unavailable)
    is AudioFeatureState.Unsupported -> stringResource(R.string.feature_audio_unsupported)
    is AudioFeatureState.Conflict -> stringResource(R.string.feature_audio_conflict)
    is AudioFeatureState.Active ->
        if (state.mechanisms.isEmpty()) {
            stringResource(R.string.feature_audio_active)
        } else {
            stringResource(R.string.feature_audio_active_via, state.mechanisms.joinToString(", "))
        }
    is AudioFeatureState.Failed -> state.reason
}

/** Whether a spinner belongs next to the switch. */
private fun SshFeatureState.isInProgress(): Boolean =
    this is SshFeatureState.Probing ||
        this is SshFeatureState.Installing ||
        this is SshFeatureState.Starting
