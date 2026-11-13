package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.fem.linexa.R
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.ui.theme.Spacing

/**
 * The workspace Network section's panel (§26/§27 of the transformation spec):
 * how the guest resolver is provisioned, which package mirror the guest's
 * repositories are switched to, and which RootFS download mirror this
 * workspace's (re)installs use.
 *
 * ## Why the fields hold their own text
 *
 * The committed value is a `NetworkSettings` (a parsed DNS list, a URL), but a
 * text field cannot be driven from one: the user must be able to clear a field
 * to retype it, and a half-typed URL is not a valid URL. So the *text* is local
 * (`rememberSaveable`, keyed on the committed value so an external change still
 * reaches it — the same shape `SshSettingsPanel` uses for the port) and every
 * keystroke is classified by the pure validators
 * [NetworkSettings.validateDnsServers] / [NetworkSettings.validateMirrorUrl].
 * Only a valid value is committed — the panel never persists a rejected value
 * and never silently rewrites what the user typed.
 *
 * ## Committing on every valid keystroke
 *
 * Deliberate, and it is why there is no Save button. All three settings are
 * read at **workspace start** (`WorkspaceNetworkProvisioner` re-provisions
 * resolv.conf/hosts/mirrors on every launch), so an intermediate valid value
 * costs nothing — the runtime is never restarted underneath the user, and the
 * next start simply picks up the latest committed state. The footer note says
 * exactly that.
 *
 * ## Why the write path is the validated one
 *
 * `NetworkSettings` reads fail-open (a corrupt stored entry degrades to the
 * safe default), but a *typo* entered here would be persisted verbatim and
 * then silently ignored at start — a setting that does nothing and says
 * nothing. Validation lives where the person is, so an invalid DNS entry or
 * mirror URL is refused with the reason on screen instead.
 */
@Composable
fun NetworkSettingsPanel(
    settings: NetworkSettings,
    onSettingsChange: (NetworkSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // ── DNS: the custom-DNS feature, an explicit optional switch ────────
        //
        // Disabled (default): the workspace follows the host's live network
        // DNS — the normal/default Linexa networking path; any servers below
        // are ignored. Enabled: the configured servers are applied to THIS
        // workspace at every start. The value the chips write is the
        // existing DnsMode (SYSTEM=disabled / CUSTOM=enabled) so old
        // workspace records resolve to "disabled" and nothing migrates.
        Text(
            text = stringResource(R.string.settings_network_dns_mode_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = settings.dnsMode == NetworkSettings.DnsMode.SYSTEM,
                onClick = { onSettingsChange(settings.copy(dnsMode = NetworkSettings.DnsMode.SYSTEM)) },
                label = { Text(stringResource(R.string.settings_network_dns_disabled)) }
            )
            FilterChip(
                selected = settings.dnsMode == NetworkSettings.DnsMode.CUSTOM,
                onClick = { onSettingsChange(settings.copy(dnsMode = NetworkSettings.DnsMode.CUSTOM)) },
                label = { Text(stringResource(R.string.settings_network_dns_enabled)) }
            )
        }
        Text(
            text = stringResource(R.string.settings_network_dns_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.dnsMode == NetworkSettings.DnsMode.CUSTOM) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            DnsServersField(settings = settings, onSettingsChange = onSettingsChange)
        }

        // ── Guest package mirror (§22) ─────────────────────────────────────
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.settings_network_mirror_mode_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = settings.packageMirrorMode == NetworkSettings.PackageMirrorMode.DEFAULT,
                onClick = {
                    onSettingsChange(settings.copy(packageMirrorMode = NetworkSettings.PackageMirrorMode.DEFAULT))
                },
                label = { Text(stringResource(R.string.settings_network_mirror_default)) }
            )
            FilterChip(
                selected = settings.packageMirrorMode == NetworkSettings.PackageMirrorMode.CUSTOM,
                onClick = {
                    onSettingsChange(settings.copy(packageMirrorMode = NetworkSettings.PackageMirrorMode.CUSTOM))
                },
                label = { Text(stringResource(R.string.settings_network_mirror_custom)) }
            )
        }
        Text(
            text = stringResource(R.string.settings_network_mirror_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.packageMirrorMode == NetworkSettings.PackageMirrorMode.CUSTOM) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            PackageMirrorField(settings = settings, onSettingsChange = onSettingsChange)
        }

        // ── RootFS download mirror (§25): empty = catalog default ──────────
        Spacer(modifier = Modifier.height(Spacing.md))
        RootfsMirrorField(settings = settings, onSettingsChange = onSettingsChange)

        // ── The one timing fact every section shares ───────────────────────
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.settings_network_apply_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The custom DNS servers field. The text is local; the committed list is only
 * replaced when the whole field classifies as valid, parsed with
 * [NetworkSettings.parseDnsServers] so the stored form is the server list
 * itself, not the raw text.
 */
@Composable
private fun DnsServersField(
    settings: NetworkSettings,
    onSettingsChange: (NetworkSettings) -> Unit
) {
    val committed = settings.dnsServers.joinToString(NetworkSettings.SERVERS_SEPARATOR)
    var text by rememberSaveable(committed) { mutableStateOf(committed) }
    // Validated on every change — recomposition re-runs this on each keystroke,
    // so the error state can never lag the text.
    val rejection = NetworkSettings.validateDnsServers(text)
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            if (NetworkSettings.validateDnsServers(raw) == null) {
                onSettingsChange(settings.copy(dnsServers = NetworkSettings.parseDnsServers(raw)))
            }
        },
        label = { Text(stringResource(R.string.settings_network_dns_servers_label)) },
        singleLine = true,
        isError = rejection != null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            if (rejection != null) {
                Text(
                    text = stringResource(
                        R.string.settings_network_dns_servers_error,
                        NetworkSettings.MAX_DNS_SERVERS
                    )
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The guest package mirror URL field, shown only in CUSTOM mode. Committed
 * only when [NetworkSettings.validateMirrorUrl] passes — an invalid URL here
 * would end up embedded in the guest's repository commands.
 */
@Composable
private fun PackageMirrorField(
    settings: NetworkSettings,
    onSettingsChange: (NetworkSettings) -> Unit
) {
    var text by rememberSaveable(settings.packageMirrorUrl) { mutableStateOf(settings.packageMirrorUrl) }
    val valid = NetworkSettings.validateMirrorUrl(text)
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            if (NetworkSettings.validateMirrorUrl(raw)) {
                onSettingsChange(settings.copy(packageMirrorUrl = raw))
            }
        },
        label = { Text(stringResource(R.string.settings_network_mirror_url_label)) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            if (!valid) {
                Text(text = stringResource(R.string.settings_network_invalid_mirror))
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The RootFS download mirror field. Empty is a *legitimate* value here — it
 * means "use the catalog default" — so the blank field is committed as the
 * default rather than held as an error; anything non-blank must pass the same
 * [NetworkSettings.validateMirrorUrl] guard as the package mirror.
 */
@Composable
private fun RootfsMirrorField(
    settings: NetworkSettings,
    onSettingsChange: (NetworkSettings) -> Unit
) {
    var text by rememberSaveable(settings.rootfsMirrorUrl) { mutableStateOf(settings.rootfsMirrorUrl) }
    val valid = text.isBlank() || NetworkSettings.validateMirrorUrl(text)
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            if (raw.isBlank() || NetworkSettings.validateMirrorUrl(raw)) {
                onSettingsChange(settings.copy(rootfsMirrorUrl = raw))
            }
        },
        label = { Text(stringResource(R.string.settings_network_rootfs_mirror_label)) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            Text(
                text = if (valid) stringResource(R.string.settings_network_rootfs_mirror_help)
                else stringResource(R.string.settings_network_invalid_mirror)
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}
