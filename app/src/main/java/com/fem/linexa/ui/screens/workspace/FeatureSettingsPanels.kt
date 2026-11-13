package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.service.runtime.features.ClipboardAliasKind
import com.fem.linexa.service.runtime.features.DesktopInstallPolicy
import com.fem.linexa.service.runtime.features.DesktopResolution
import com.fem.linexa.service.runtime.features.DesktopPreset
import com.fem.linexa.service.runtime.features.DesktopTouchMode
import com.fem.linexa.service.runtime.features.GuiBackend
import com.fem.linexa.service.runtime.features.DesktopRunMode
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.screens.desktop.DesktopVncAuthProvisioner
import com.fem.linexa.ui.screens.workspace.DesktopInstallPhase
import com.fem.linexa.ui.theme.Spacing

/**
 * The SSH row's settings panel (UI/UX Phase 6): the guest sshd's listen port.
 *
 * ## Why the field holds its own text
 *
 * The committed value is an `Int` in [FeatureSettings], but a text field cannot
 * be driven from one: the user must be able to clear it to type a new number,
 * and "" is not an `Int`. So the *text* is local (`rememberSaveable`, keyed on
 * the committed port so an external change still reaches it) and every keystroke
 * is classified by the pure [FeatureConfig.editPort]. Only a
 * [FeatureConfig.PortEdit.Valid] is committed — the panel never persists a
 * rejected value and never silently rewrites what the user typed.
 *
 * ## Committing on every valid keystroke
 *
 * Deliberate, and it is why there is no Save button. The port only takes effect
 * when sshd next starts (see `WorkspaceFeatureSupervisor.reconcileSsh`), so an
 * intermediate valid value costs nothing — whereas a Save button in an
 * expandable panel is a button a user leaves unpressed, and the panel collapsing
 * would then discard their edit with no indication. `1024` on the way to `10240`
 * is a real port, briefly stored, and superseded before it is ever used.
 *
 * @param runningPort the port the *live* daemon is actually listening on, when
 *   there is one. Shown as a pending-change notice when it differs from the
 *   committed setting, because at that moment the setting and reality genuinely
 *   disagree and neither the field nor the status line alone can say so.
 */
@Composable
fun SshSettingsPanel(
    settings: FeatureSettings,
    onSettingsChange: (FeatureSettings) -> Unit,
    runningPort: Int?,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable(settings.sshPort) { mutableStateOf(settings.sshPort.toString()) }
    val edit = remember(text) { FeatureConfig.editPort(text) }
    val errorText = when (edit) {
        is FeatureConfig.PortEdit.Valid -> null
        // Mid-edit, not a mistake — see FeatureConfig.editPort.
        FeatureConfig.PortEdit.Empty -> null
        is FeatureConfig.PortEdit.Invalid -> when (edit.reason) {
            FeatureSettings.PortRejection.NOT_A_NUMBER ->
                stringResource(R.string.feature_settings_ssh_port_error_number)
            FeatureSettings.PortRejection.PRIVILEGED ->
                stringResource(R.string.feature_settings_ssh_port_error_privileged, FeatureSettings.MIN_PORT)
            FeatureSettings.PortRejection.OUT_OF_RANGE ->
                stringResource(R.string.feature_settings_ssh_port_error_range, FeatureSettings.MAX_PORT)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val next = FeatureConfig.editPort(raw)
                if (next is FeatureConfig.PortEdit.Valid) {
                    onSettingsChange(settings.copy(sshPort = next.port))
                }
            },
            label = { Text(stringResource(R.string.feature_settings_ssh_port_label)) },
            singleLine = true,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            supportingText = {
                Text(
                    text = errorText
                        ?: stringResource(R.string.feature_settings_ssh_port_help, settings.sshPort)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // The one case where the setting and the guest disagree: a daemon is up
        // on a different port than the one now saved. Stated plainly rather than
        // resolved by restarting sshd underneath the user's open connections.
        if (runningPort != null && runningPort != settings.sshPort) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.feature_settings_ssh_port_pending, runningPort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The Clipboard row's settings panel (UI/UX Phase 6): which guest command names
 * the bridge provides.
 *
 * Rows come from [FeatureConfig.aliasRows], i.e. from
 * `ClipboardBridgeProvisioner.ALIASES` — never from a list written here, so a
 * name the backend cannot install cannot be offered. Each row states what the
 * alias *does*, because the catalog is asymmetric in a way a bare checkbox
 * hides: `xclip` writes unless given `-o`, `xsel` reads unless given `-i`.
 *
 * Unchecking everything is a legitimate end state, not an empty error — the
 * canonical `linexa-clip-copy`/`-paste` shims are always installed and are not
 * listed here, since they are not optional and a checkbox that cannot be
 * unchecked is furniture.
 */
@Composable
fun ClipboardSettingsPanel(
    settings: FeatureSettings,
    onSettingsChange: (FeatureSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.feature_settings_clipboard_aliases_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.feature_settings_clipboard_aliases_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        FeatureConfig.aliasRows(settings).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row toggles, so the label is part of the target
                    // rather than a 20dp box next to it.
                    // UI/UX a11y: announce as a Button (not plain "clickable")
                    // so TalkBack reads the alias row as a single actionable control.
                    .clickable(role = Role.Button, onClickLabel = row.name) {
                        onSettingsChange(FeatureConfig.withAlias(settings, row.name, !row.selected))
                    }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Checkbox(
                    checked = row.selected,
                    // Handled by the row's clickable; a second handler here
                    // would fire twice on a tap that lands on the box.
                    onCheckedChange = null
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = aliasKindText(row.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** What an alias does, in the user's words. */
@Composable
private fun aliasKindText(kind: ClipboardAliasKind): String = when (kind) {
    ClipboardAliasKind.COPY -> stringResource(R.string.feature_settings_clipboard_kind_copy)
    ClipboardAliasKind.PASTE -> stringResource(R.string.feature_settings_clipboard_kind_paste)
    ClipboardAliasKind.DISPATCH_XCLIP -> stringResource(R.string.feature_settings_clipboard_kind_xclip)
    ClipboardAliasKind.DISPATCH_XSEL -> stringResource(R.string.feature_settings_clipboard_kind_xsel)
}

/**
 * The Desktop row's settings panel (Desktop Modes work, 2026-08): which run
 * mode the Desktop screen uses, and that mode's parameters.
 *
 * Structure mirrors the other panels — every edit commits through
 * [onSettingsChange] the moment it is valid, because all of these values take
 * effect the *next* time the desktop starts ([DesktopViewModel] reads them at
 * start time, exactly like the SSH port at sshd-start time), so an intermediate
 * valid value costs nothing and there is no Save button to leave unpressed.
 *
 * The two modes expose different fields, and only the active mode's fields are
 * shown: a WebView port behind a VNC panel (or vice versa) would be a control
 * the backend does not read *in the state the user is looking at*. Switching
 * the mode chips is itself a committed setting — the mode is what the panel is
 * about, not a view filter.
 *
 * 2026-09-02 (guided desktop installer, Phase 3): the VNC mode's preset
 * section is also where the guided Debian/Ubuntu + APT installation flow
 * lives. When [onStartInstall] is provided, selecting a preset reveals an
 * explicit "Install …" action that runs the preconditions + real apt quote
 * + confirmation flow ([DesktopInstallUiState]); without it the panel keeps
 * the pre-Phase-3 shape (the flow is additive, never automatic).
 */
@Composable
fun DesktopSettingsPanel(
    settings: FeatureSettings,
    onSettingsChange: (FeatureSettings) -> Unit,
    modifier: Modifier = Modifier,
    installState: DesktopInstallUiState? = null,
    onStartInstall: (DesktopPreset) -> Unit = {},
    onConfirmInstall: () -> Unit = {},
    onCancelInstall: () -> Unit = {},
    onDismissInstall: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.feature_settings_desktop_mode_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DesktopRunMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.desktopMode == mode,
                    onClick = { onSettingsChange(settings.copy(desktopMode = mode)) },
                    label = { Text(desktopModeLabel(mode)) }
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))

        when (settings.desktopMode) {
            DesktopRunMode.VNC -> VncModeSettings(settings, onSettingsChange)
            DesktopRunMode.WEBVIEW -> WebModeSettings(settings, onSettingsChange)
        }

        // Desktop Backends (§15) + Desktop Provisioning (§16): only shown for
        // the VNC run mode — the WebView mode has neither an X backend nor a
        // desktop session. Every option here has a real consumer in
        // DesktopViewModel (backend dispatch, preset install); the panel
        // never exposes an unwired knob.
        if (settings.desktopMode == DesktopRunMode.VNC) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.feature_settings_desktop_backend_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GuiBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = settings.desktopBackend == backend,
                        onClick = { onSettingsChange(settings.copy(desktopBackend = backend)) },
                        label = { Text(desktopBackendLabel(backend)) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.feature_settings_desktop_backend_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.feature_settings_desktop_preset_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                DesktopPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.desktopPreset == preset,
                        onClick = { onSettingsChange(settings.copy(desktopPreset = preset)) },
                        label = { Text(desktopPresetLabel(preset)) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.feature_settings_desktop_preset_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 2026-09-02 (guided desktop installer, Phase 3): the explicit
            // opt-in flow. Reachable only for an installable preset; nothing
            // here runs without the user's tap and confirmation.
            if (settings.desktopPreset != DesktopPreset.NONE || installState != null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                DesktopInstallSection(
                    selectedPreset = settings.desktopPreset,
                    state = installState,
                    onStartInstall = onStartInstall,
                    onConfirmInstall = onConfirmInstall,
                    onCancelInstall = onCancelInstall,
                    onDismissInstall = onDismissInstall
                )
            }
        }
    }
}

/**
 * 2026-09-02 (guided desktop installer, Phase 3): the guided
 * Debian/Ubuntu + APT desktop installation flow, inside the Features
 * screen's explicit opt-in surface.
 *
 * Reuses the install screen's shared progress language (spinner + phase
 * label + big rail + "Now processing" mono block) so the three progress
 * surfaces — RootFS install, export, desktop install — read identically.
 * Every value is real: the quote comes from `apt-get -s`, package names
 * from dpkg output, and phases from deterministic markers.
 */
@Composable
private fun DesktopInstallSection(
    selectedPreset: DesktopPreset,
    state: DesktopInstallUiState?,
    onStartInstall: (DesktopPreset) -> Unit,
    onConfirmInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onDismissInstall: () -> Unit
) {
    when (val s = state) {
        null, DesktopInstallUiState.Idle -> {
            // Entry point — only for a real preset (NONE has nothing to install).
            if (selectedPreset == DesktopPreset.NONE) return
            HButton(
                label = stringResource(R.string.desktop_install_check, selectedPreset.displayName),
                onClick = { onStartInstall(selectedPreset) },
                variant = HavenButtonVariant.SECONDARY,
                size = HavenButtonSize.SMALL
            )
        }

        is DesktopInstallUiState.Preparing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HSpinner(small = true)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.desktop_install_checking, s.preset.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is DesktopInstallUiState.Quote -> {
            DesktopInstallQuoteCard(s, onCancelInstall, onConfirmInstall, onDismissInstall)
        }

        is DesktopInstallUiState.Installing -> {
            DesktopInstallProgressCard(s, onCancelInstall)
        }

        is DesktopInstallUiState.Completed -> {
            Column {
                Text(
                    text = stringResource(R.string.desktop_install_complete, s.preset.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.desktop_install_complete_body,
                        s.sessionPath ?: s.preset.displayName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                HButton(
                    label = stringResource(R.string.common_done),
                    onClick = onDismissInstall,
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
            }
        }

        is DesktopInstallUiState.Failed -> {
            Column {
                Text(
                    text = stringResource(R.string.desktop_install_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                // The verify-failure case gets its own localized message;
                // every other failure shows the real captured output tail.
                Text(
                    text = s.verifyFailedPresetName?.let {
                        stringResource(R.string.desktop_install_verify_failed, it)
                    } ?: s.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                HButton(
                    label = stringResource(R.string.common_close),
                    onClick = onDismissInstall,
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
            }
        }
    }
}

/** The confirmation card: real facts, then the explicit confirm action. */
@Composable
private fun DesktopInstallQuoteCard(
    quote: DesktopInstallUiState.Quote,
    onCancelInstall: () -> Unit,
    onConfirmInstall: () -> Unit,
    onDismissInstall: () -> Unit
) {
    val prereqs = quote.prerequisites
    Column {
        Text(
            text = stringResource(R.string.desktop_install_quote_title, quote.preset.displayName),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (quote.alreadyInstalled) {
            // Phase-3 idempotency: installed and ready — no reinstall offered.
            Text(
                text = stringResource(
                    R.string.desktop_install_already,
                    prereqs.sessionPath ?: quote.preset.displayName
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            HButton(
                label = stringResource(R.string.common_done),
                onClick = onDismissInstall,
                variant = HavenButtonVariant.SECONDARY,
                size = HavenButtonSize.SMALL
            )
            return
        }

        // The facts, each labeled as what it is (estimate vs measured).
        QuoteRow(stringResource(R.string.desktop_install_quote_download), quote.downloadBytes)
        QuoteRow(stringResource(R.string.desktop_install_quote_installed), quote.installedBytes)
        QuoteRow(
            stringResource(R.string.desktop_install_quote_free),
            prereqs.freeBytes.takeIf { it > 0 }
        )
        quote.newPackages?.let { count ->
            Text(
                text = stringResource(R.string.desktop_install_quote_packages, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (quote.downloadBytes == null && quote.installedBytes == null) {
            Text(
                text = stringResource(R.string.desktop_install_quote_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (quote.blockReason) {
            DesktopInstallPolicy.BlockReason.NO_SESSION ->
                Text(
                    text = stringResource(R.string.desktop_install_block_no_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            DesktopInstallPolicy.BlockReason.NOT_APT_DISTRO ->
                Text(
                    text = stringResource(R.string.desktop_install_block_distro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            DesktopInstallPolicy.BlockReason.NO_APT ->
                Text(
                    text = stringResource(R.string.desktop_install_block_no_apt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            DesktopInstallPolicy.BlockReason.NO_ROOTFS ->
                Text(
                    text = stringResource(R.string.desktop_install_block_no_rootfs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            DesktopInstallPolicy.BlockReason.NO_PRESET ->
                Text(
                    text = stringResource(R.string.desktop_install_block_no_preset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            DesktopInstallPolicy.BlockReason.INSUFFICIENT_STORAGE ->
                Text(
                    text = stringResource(R.string.desktop_install_block_storage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            null -> Unit
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = onCancelInstall,
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL
            )
            if (quote.blockReason == null) {
                HButton(
                    label = stringResource(R.string.desktop_install_confirm, quote.preset.displayName),
                    onClick = onConfirmInstall,
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL
                )
            }
        }
    }
}

/** One labeled size row — real bytes or an explicit em dash. */
@Composable
private fun QuoteRow(label: String, bytes: Long?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = bytes?.let { com.fem.linexa.core.format.FileSizeFormatter.format(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The live progress card — the shared idiom (spinner + phase + big rail +
 * "Now processing" mono block). The rail is indeterminate: apt prints no
 * parseable percentage, and a fabricated one is forbidden.
 */
@Composable
private fun DesktopInstallProgressCard(
    installing: DesktopInstallUiState.Installing,
    onCancelInstall: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HSpinner(small = true)
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = when (installing.phase) {
                    DesktopInstallPhase.UPDATING_INDEX ->
                        stringResource(R.string.desktop_install_phase_updating)
                    DesktopInstallPhase.DOWNLOADING ->
                        stringResource(R.string.desktop_install_phase_downloading)
                    DesktopInstallPhase.INSTALLING ->
                        stringResource(R.string.desktop_install_phase_installing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        com.fem.linexa.ui.components.haven.HProgress(
            fraction = null,
            big = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm)
        )
        if (installing.currentPackage != null) {
            com.fem.linexa.ui.components.haven.HMonoBlock(
                text = stringResource(R.string.install_processing_label) + " " + installing.currentPackage.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        HButton(
            label = stringResource(R.string.desktop_install_cancel),
            onClick = onCancelInstall,
            variant = HavenButtonVariant.GHOST,
            size = HavenButtonSize.SMALL
        )
    }
}

@Composable
private fun desktopBackendLabel(backend: GuiBackend): String = when (backend) {
    GuiBackend.X11 -> stringResource(R.string.feature_settings_desktop_backend_x11)
    GuiBackend.VNC -> stringResource(R.string.feature_settings_desktop_backend_vnc)
}

@Composable
private fun desktopPresetLabel(preset: DesktopPreset): String = when (preset) {
    DesktopPreset.NONE -> stringResource(R.string.feature_settings_desktop_preset_none)
    DesktopPreset.XFCE -> stringResource(R.string.feature_settings_desktop_preset_xfce)
    DesktopPreset.LXQT -> stringResource(R.string.feature_settings_desktop_preset_lxqt)
    DesktopPreset.OPENBOX -> stringResource(R.string.feature_settings_desktop_preset_openbox)
}

@Composable
private fun VncModeSettings(
    settings: FeatureSettings,
    onSettingsChange: (FeatureSettings) -> Unit
) {
    Text(
        text = stringResource(R.string.feature_settings_desktop_resolution_label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(Spacing.xs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        DesktopResolution.entries.forEach { preset ->
            FilterChip(
                selected = settings.desktopResolution == preset,
                onClick = { onSettingsChange(settings.copy(desktopResolution = preset)) },
                label = { Text(desktopResolutionLabel(preset)) }
            )
        }
    }
    Spacer(modifier = Modifier.height(Spacing.sm))

    // The ZRLE opt-in: a deliberate §1.152-conforming default (off) with the
    // escape hatch stated in the help text, because "compression on loopback"
    // *looks* like an improvement and is not one — see buildSetEncodingsMessage.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) {
                onSettingsChange(settings.copy(desktopVncPreferZrle = !settings.desktopVncPreferZrle))
            }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.feature_settings_desktop_zrle_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.feature_settings_desktop_zrle_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.desktopVncPreferZrle,
            onCheckedChange = { onSettingsChange(settings.copy(desktopVncPreferZrle = it)) }
        )
    }
    Spacer(modifier = Modifier.height(Spacing.sm))

    // Desktop Touch work (2026-08): how touches map onto the remote pointer.
    // Live-applied by the viewer (no desktop restart needed), which is why the
    // help text says so — the only desktop setting that does not wait for the
    // next start.
    Text(
        text = stringResource(R.string.feature_settings_desktop_touch_label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(Spacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DesktopTouchMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.desktopTouchMode == mode,
                onClick = { onSettingsChange(settings.copy(desktopTouchMode = mode)) },
                label = { Text(desktopTouchModeLabel(mode)) }
            )
        }
    }
    Text(
        text = stringResource(R.string.feature_settings_desktop_touch_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(Spacing.sm))

    OutlinedTextField(
        value = settings.desktopVncPassword,
        // P11: any edit replaces the unreadable stored password, so it also clears the flag.
        onValueChange = { raw ->
            onSettingsChange(settings.copy(desktopVncPassword = raw, desktopVncPasswordUnreadable = false))
        },
        label = { Text(stringResource(R.string.feature_settings_desktop_password_label)) },
        singleLine = true,
        isError = settings.desktopVncPasswordUnreadable,
        supportingText = {
            Text(
                text = stringResource(
                    if (settings.desktopVncPasswordUnreadable) {
                        R.string.feature_settings_desktop_password_unreadable
                    } else {
                        R.string.feature_settings_desktop_password_help
                    }
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = {
        onSettingsChange(
            settings.copy(
                desktopVncPassword = DesktopVncAuthProvisioner.generatePassword(),
                desktopVncPasswordUnreadable = false
            )
        )
    }) {
        Text(stringResource(R.string.feature_settings_desktop_password_generate))
    }
}

@Composable
private fun desktopTouchModeLabel(mode: DesktopTouchMode): String = when (mode) {
    DesktopTouchMode.RELATIVE -> stringResource(R.string.feature_settings_desktop_touch_relative)
    DesktopTouchMode.DIRECT -> stringResource(R.string.feature_settings_desktop_touch_direct)
}

@Composable
private fun WebModeSettings(
    settings: FeatureSettings,
    onSettingsChange: (FeatureSettings) -> Unit
) {
    var portText by rememberSaveable(settings.desktopWebPort) {
        mutableStateOf(settings.desktopWebPort.toString())
    }
    val portEdit = remember(portText) { FeatureConfig.editPort(portText) }
    OutlinedTextField(
        value = portText,
        onValueChange = { raw ->
            portText = raw
            val next = FeatureConfig.editPort(raw)
            if (next is FeatureConfig.PortEdit.Valid) {
                onSettingsChange(settings.copy(desktopWebPort = next.port))
            }
        },
        label = { Text(stringResource(R.string.feature_settings_desktop_web_port_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            Text(
                text = when (portEdit) {
                    is FeatureConfig.PortEdit.Valid ->
                        stringResource(
                            R.string.feature_settings_desktop_web_port_help,
                            FeatureSettings.DEFAULT_WEB_PORT
                        )
                    FeatureConfig.PortEdit.Empty ->
                        stringResource(
                            R.string.feature_settings_desktop_web_port_help,
                            FeatureSettings.DEFAULT_WEB_PORT
                        )
                    is FeatureConfig.PortEdit.Invalid -> stringResource(
                        R.string.feature_settings_desktop_web_port_error,
                        FeatureSettings.MIN_PORT, FeatureSettings.MAX_PORT
                    )
                }
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(Spacing.sm))

    OutlinedTextField(
        value = settings.desktopWebCommand,
        onValueChange = { raw -> onSettingsChange(settings.copy(desktopWebCommand = raw)) },
        label = { Text(stringResource(R.string.feature_settings_desktop_web_command_label)) },
        singleLine = true,
        supportingText = {
            Text(text = stringResource(R.string.feature_settings_desktop_web_command_help))
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun desktopModeLabel(mode: DesktopRunMode): String = when (mode) {
    DesktopRunMode.VNC -> stringResource(R.string.feature_settings_desktop_mode_vnc)
    DesktopRunMode.WEBVIEW -> stringResource(R.string.feature_settings_desktop_mode_webview)
}

@Composable
private fun desktopResolutionLabel(preset: DesktopResolution): String = when (preset) {
    DesktopResolution.AUTO -> stringResource(R.string.feature_settings_desktop_resolution_auto)
    DesktopResolution.P720 -> stringResource(R.string.feature_settings_desktop_resolution_720p)
    DesktopResolution.P900 -> stringResource(R.string.feature_settings_desktop_resolution_900p)
    DesktopResolution.P1080 -> stringResource(R.string.feature_settings_desktop_resolution_1080p)
}
