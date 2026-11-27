package com.fem.linexa.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.update.GithubRelease
import com.fem.linexa.domain.update.GithubReleaseAsset
import com.fem.linexa.domain.update.ReleaseNotesSummary
import com.fem.linexa.service.update.AppUpdateUiState
import com.fem.linexa.service.update.CheckFailureKind
import com.fem.linexa.service.update.DownloadFailureKind
import com.fem.linexa.service.update.UpdateServerConfig
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HExpandablePanel
import com.fem.linexa.ui.components.haven.HHairline
import com.fem.linexa.ui.components.haven.HPill
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HRic
import com.fem.linexa.ui.components.haven.HSectionHeader
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HToggle
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import java.text.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.fem.linexa.ui.components.haven.LocalHavenMotions

/**
 * The Application Updates section (Settings tab, above About) — the surface
 * of the in-app update system.
 *
 * Structure is deliberate: ONE tight card whose fixed rows (current version,
 * check button, last-checked stamp, pre-release toggle) frame a
 * state-dependent middle block. The middle block is the state machine's
 * mirror — one composable per [AppUpdateUiState] case, all rendering through
 * Haven primitives, so the section reads as part of the settings screen and
 * the state transitions never change the card's outer shape.
 *
 * The update-available block is the section's showpiece: a tinted banner
 * (the accent-tinted surface the rest of Haven reserves for "this matters")
 * with the version transition in mono, publication date, download size, a
 * one-line summary, the expandable full notes, and the single Download
 * action. Layout direction is untouched — the global LTR policy applies;
 * mono/numeric styles pin LTR exactly like every other technical string.
 *
 * Returning from Android's "install unknown apps" page re-opens the install
 * gate: an ON_RESUME hook asks the coordinator to re-check
 * `canRequestPackageInstalls()`, so the Install button is back the moment
 * the user returns.
 */
@Composable
fun AppUpdateGroup(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val includePrereleases by viewModel.includePrereleases.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val checking = state is AppUpdateUiState.Checking

    Column(modifier = Modifier.fillMaxWidth()) {
        HSectionHeader(title = stringResource(R.string.app_update_group_title))
        HCard(tight = true) {
            // ── Identity row: icon, current version, live state pill ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HRic(
                    iconRes = R.drawable.ic_haven_download,
                    tinted = hasActiveOffer(state)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_update_current_version),
                        style = HavenType.small,
                        color = HavenTheme.colors.muted
                    )
                    Text(
                        text = viewModel.versionLabel,
                        style = HavenType.descMono,
                        color = HavenTheme.colors.text,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (checking) {
                    HSpinner(small = true)
                } else {
                    StatusPill(state)
                }
            }

            HHairline()

            // ── The state-dependent middle: status lines or the offer ──
            // One continuous transition between state KINDS (crossfade + the
            // card growing/shrinking on the shared container token) instead of
            // an instant swap. Ticks inside one kind (download progress, a
            // refreshed offer) share a content key, so they recompose in place
            // and never restart the animation.
            val motions = LocalHavenMotions.current
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(animationSpec = motions.enter) togetherWith
                        fadeOut(animationSpec = motions.exit)) using
                        SizeTransform(clip = false) { _, _ ->
                            motions.containerSpec<androidx.compose.ui.unit.IntSize>()
                        }
                },
                label = "app-update-state"
            ) { shown ->
                StateContent(shown, viewModel)
            }

            HHairline()

            // ── The check control: user-initiated, always explicit ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowCompactV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HButton(
                    label = stringResource(
                        if (state is AppUpdateUiState.CheckFailed) {
                            R.string.app_update_check_button_retry
                        } else {
                            R.string.app_update_check_button
                        }
                    ),
                    onClick = viewModel::checkForUpdates,
                    variant = HavenButtonVariant.SECONDARY,
                    enabled = !checking &&
                        state !is AppUpdateUiState.Downloading &&
                        state !is AppUpdateUiState.Installing
                )
                // Localized prose ("آخرین بررسی …"), so it uses the prose style —
                // the mono style pins LTR and would force Persian words into an
                // LTR paragraph. It takes the remaining width and wraps rather
                // than squeezing the button; Right pins it to the physical edge
                // in both languages (End would flip with the paragraph direction).
                Text(
                    text = lastCheckedLabel(state.lastCheckAtMs),
                    style = HavenType.small,
                    color = HavenTheme.colors.muted,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.weight(1f)
                )
            }

            HHairline()

            // ── The pre-release channel toggle ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowCompactV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_update_include_prereleases),
                        style = HavenType.body.copy(fontWeight = FontWeight.Medium),
                        color = HavenTheme.colors.text
                    )
                    Text(
                        text = stringResource(R.string.app_update_include_prereleases_desc),
                        style = HavenType.desc,
                        color = HavenTheme.colors.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                HToggle(
                    checked = includePrereleases,
                    onCheckedChange = viewModel::setIncludePrereleases
                )
            }
        }
    }
}

/** True while the section is actively carrying an update offer or its result. */
private fun hasActiveOffer(state: AppUpdateUiState): Boolean =
    state is AppUpdateUiState.UpdateAvailable ||
        state is AppUpdateUiState.Downloading ||
        state is AppUpdateUiState.Downloaded ||
        state is AppUpdateUiState.InstallPermissionRequired ||
        state is AppUpdateUiState.Installing ||
        state is AppUpdateUiState.InstallComplete

// ── Header trailing pill ───────────────────────────────────────────────────

/**
 * A quiet "update in play" pill in the header row. Failures and the
 * up-to-date states render NO pill — their dedicated blocks below say what
 * happened; a red pill would only repeat it.
 */
@Composable
private fun StatusPill(state: AppUpdateUiState) {
    when {
        hasActiveOffer(state) -> HPill(
            text = stringResource(R.string.app_update_badge_new),
            color = HavenTheme.colors.primary,
            container = HavenTheme.colors.surface2
        )
    }
}

// ── State content ─────────────────────────────────────────────────────────

@Composable
private fun StateContent(state: AppUpdateUiState, viewModel: AppUpdateViewModel) {
    when (state) {
        is AppUpdateUiState.Idle -> QuietStatus(
            iconRes = R.drawable.ic_haven_refresh,
            title = stringResource(R.string.app_update_check_button),
            titleColor = HavenTheme.colors.text2
        )
        is AppUpdateUiState.Checking -> CheckingStatus()
        is AppUpdateUiState.CheckFailed -> FailedStatus(
            title = stringResource(R.string.app_update_check_failed_title),
            detail = checkFailureDetail(state.kind)
        )
        is AppUpdateUiState.UpToDate -> SuccessStatus(
            title = stringResource(
                if (state.installedIsNewer) {
                    R.string.app_update_newer_installed_title
                } else {
                    R.string.app_update_up_to_date_title
                }
            ),
            detail = stringResource(
                if (state.installedIsNewer) {
                    R.string.app_update_newer_installed_desc
                } else {
                    R.string.app_update_up_to_date_desc
                },
                state.release.tagName
            )
        )
        is AppUpdateUiState.NoRelease -> QuietStatus(
            iconRes = R.drawable.ic_haven_info,
            title = stringResource(R.string.app_update_no_release_title),
            detail = stringResource(R.string.app_update_no_release_desc)
        )
        is AppUpdateUiState.ReleaseWithoutApk -> FailedStatus(
            title = stringResource(R.string.app_update_no_apk_title),
            detail = stringResource(R.string.app_update_no_apk_desc),
            action = {
                GhostLinkButton(
                    label = stringResource(R.string.app_update_releases_page),
                    url = UpdateServerConfig.RELEASES_PAGE_URL
                )
            }
        )
        is AppUpdateUiState.UpdateAvailable -> UpdateAvailableBanner(
            release = state.release,
            asset = state.asset,
            currentVersionName = state.currentVersionName,
            onDownload = viewModel::startDownload
        )
        is AppUpdateUiState.Downloading -> DownloadingBlock(state, viewModel)
        is AppUpdateUiState.DownloadFailed -> FailedStatus(
            title = stringResource(R.string.app_update_download_failed_title),
            detail = downloadFailureDetail(state.kind),
            action = {
                HButton(
                    label = stringResource(R.string.app_update_retry),
                    onClick = viewModel::startDownload,
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL
                )
            }
        )
        is AppUpdateUiState.Downloaded -> DownloadedBlock(state, viewModel)
        is AppUpdateUiState.InstallPermissionRequired -> PermissionRequiredBlock(viewModel)
        is AppUpdateUiState.Installing -> InstallingStatus(state.release.tagName)
        is AppUpdateUiState.InstallFailed -> FailedStatus(
            title = stringResource(R.string.app_update_install_failed_title),
            detail = stringResource(
                R.string.app_update_install_failed_desc,
                state.detail
                    ?.let { stringResource(R.string.app_update_install_failed_detail_suffix, it) }
                    .orEmpty()
            ),
            action = {
                HButton(
                    label = stringResource(R.string.app_update_retry),
                    onClick = viewModel::installUpdate,
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL
                )
            }
        )
        is AppUpdateUiState.InstallComplete -> SuccessStatus(
            title = stringResource(R.string.app_update_install_success_title),
            detail = stringResource(R.string.app_update_install_success_desc, state.release.tagName)
        )
    }
}

// ── The update-available banner ───────────────────────────────────────────

/**
 * The offer itself — the accent-tinted block, the one deliberately louder
 * surface in the section. Content order is the reading order of the
 * decision: what (title + badge), from-what-to-what (mono version
 * transition), when (publication date), how heavy (size), why (summary,
 * expandable full notes), do-it (Download).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateAvailableBanner(
    release: GithubRelease,
    asset: GithubReleaseAsset,
    currentVersionName: String?,
    onDownload: () -> Unit
) {
    val h = HavenTheme.colors
    var notesExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(HavenDim.cardGap)
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.tint)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_haven_download),
                contentDescription = null,
                tint = h.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.app_update_available_title),
                style = HavenType.title,
                color = h.primary,
                modifier = Modifier.weight(1f)
            )
            if (release.isPrerelease) {
                HPill(
                    text = stringResource(R.string.app_update_badge_prerelease),
                    color = h.amber,
                    container = h.surface
                )
            } else {
                HPill(
                    text = stringResource(R.string.app_update_badge_new),
                    color = h.primary,
                    container = h.surface
                )
            }
        }

        // The version transition — mono, LTR-pinned by the style, exactly like
        // every other technical identifier in the app.
        Text(
            text = "${currentVersionName.orEmpty()}  →  ${release.tagName}",
            style = HavenType.num14,
            color = h.text
        )

        // FlowRow: the two meta labels sit side by side when they fit and stack
        // when a longer translation or a larger font scale needs the room,
        // instead of overflowing the banner.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            release.publishedAtMs?.let { published ->
                MetaLabel(
                    iconRes = R.drawable.ic_haven_clock,
                    text = stringResource(R.string.app_update_published, formatDate(published))
                )
            }
            asset.sizeBytes?.let { size ->
                MetaLabel(
                    iconRes = R.drawable.ic_haven_storage,
                    text = stringResource(R.string.app_update_download_size, FileSizeFormatter.format(size))
                )
            }
        }

        ReleaseNotesSummary.short(release.notes)?.let { summary ->
            Text(
                text = summary,
                style = HavenType.desc,
                color = h.text2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { notesExpanded = !notesExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HChevron(expanded = notesExpanded)
                Text(
                    text = stringResource(
                        if (notesExpanded) R.string.app_update_hide_notes else R.string.app_update_show_notes
                    ),
                    style = HavenType.small.copy(fontWeight = FontWeight.Medium),
                    color = h.primary
                )
            }
            HExpandablePanel(expanded = notesExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(HavenDim.rMonoBlock))
                        .background(h.surface)
                        .padding(14.dp)
                ) {
                    Text(
                        text = notes.trim(),
                        style = HavenType.desc,
                        color = h.text2
                    )
                }
            }
        }

        HButton(
            label = stringResource(R.string.app_update_download),
            onClick = onDownload,
            variant = HavenButtonVariant.PRIMARY,
            block = true
        )
    }
}

// ── Downloading ───────────────────────────────────────────────────────────

@Composable
private fun DownloadingBlock(state: AppUpdateUiState.Downloading, viewModel: AppUpdateViewModel) {
    val h = HavenTheme.colors
    val context = LocalContext.current
    val total = state.totalBytes
    val fraction = if (total != null && total > 0) state.downloadedBytes.toFloat() / total else null
    val percent = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardBlockV),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.app_update_downloading, state.release.tagName),
                style = HavenType.rowTitle,
                color = h.text,
                modifier = Modifier.weight(1f)
            )
            if (percent != null) {
                Text(
                    text = "$percent%",
                    style = HavenType.num14,
                    color = h.primary
                )
            }
        }
        HProgress(fraction = fraction, big = true)
        Text(
            text = sizeProgressLabel(context, state.downloadedBytes, total),
            style = HavenType.descMono,
            color = h.text2
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            HButton(
                label = stringResource(R.string.app_update_cancel),
                onClick = viewModel::cancelDownload,
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL
            )
        }
    }
}

// ── Downloaded / permission / installing ──────────────────────────────────

@Composable
private fun DownloadedBlock(state: AppUpdateUiState.Downloaded, viewModel: AppUpdateViewModel) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardBlockV),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HRic(iconRes = R.drawable.ic_haven_check, tinted = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_update_downloaded_title),
                    style = HavenType.rowTitle,
                    color = h.text
                )
                Text(
                    text = stringResource(R.string.app_update_downloaded_desc, state.release.tagName) +
                        (state.asset.sizeBytes?.let { "  ·  ${FileSizeFormatter.format(it)}" } ?: ""),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        HButton(
            label = stringResource(R.string.app_update_install),
            onClick = viewModel::installUpdate,
            variant = HavenButtonVariant.PRIMARY,
            block = true
        )
    }
}

@Composable
private fun PermissionRequiredBlock(viewModel: AppUpdateViewModel) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardBlockV),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HRic(iconRes = R.drawable.ic_haven_shield, danger = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_update_install_permission_title),
                    style = HavenType.rowTitle,
                    color = h.text
                )
                Text(
                    text = stringResource(R.string.app_update_install_permission_desc),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        HButton(
            label = stringResource(R.string.app_update_install_permission_button),
            onClick = viewModel::openInstallPermissionSettings,
            variant = HavenButtonVariant.SECONDARY,
            block = true
        )
    }
}

@Composable
private fun InstallingStatus(versionTag: String) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardBlockV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HSpinner(small = true)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_update_installing),
                style = HavenType.rowTitle,
                color = h.text
            )
            Text(
                text = stringResource(R.string.app_update_installing_desc, versionTag),
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

// ── Small shared status rows ──────────────────────────────────────────────

@Composable
private fun CheckingStatus() {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HSpinner(small = true)
        Text(
            text = stringResource(R.string.app_update_checking),
            style = HavenType.body,
            color = h.text2
        )
    }
}

@Composable
private fun SuccessStatus(title: String, detail: String) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_haven_check),
            contentDescription = null,
            tint = h.primary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = HavenType.rowTitle, color = h.text)
            Text(
                text = detail,
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun QuietStatus(
    iconRes: Int,
    title: String,
    detail: String? = null,
    titleColor: Color? = null
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = titleColor ?: h.muted,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = HavenType.rowTitle, color = titleColor ?: h.text)
            if (detail != null) {
                Text(
                    text = detail,
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/** The red failure row: what failed, the specific reason, and the recovery action. */
@Composable
private fun FailedStatus(title: String, detail: String?, action: (@Composable () -> Unit)? = null) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HavenDim.cardRowH, vertical = HavenDim.cardRowV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_haven_warning),
            contentDescription = null,
            tint = h.red,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = HavenType.rowTitle, color = h.text)
            if (detail != null) {
                Text(
                    text = detail,
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (action != null) {
                Box(modifier = Modifier.padding(top = 8.dp)) { action() }
            }
        }
    }
}

/** A quiet link-style button (the releases page, when no APK is offered). */
@Composable
private fun GhostLinkButton(label: String, url: String) {
    val context = LocalContext.current
    HButton(
        label = label,
        onClick = { openLink(context, url) },
        variant = HavenButtonVariant.GHOST,
        size = HavenButtonSize.SMALL
    )
}

@Composable
private fun MetaLabel(iconRes: Int, text: String) {
    val h = HavenTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = h.muted,
            modifier = Modifier.size(13.dp)
        )
        Text(text = text, style = HavenType.small, color = h.text2)
    }
}

// ── Formatting helpers (locale-aware via the localized context) ───────────

@Composable
private fun lastCheckedLabel(epochMs: Long?): String =
    if (epochMs == null) {
        stringResource(R.string.app_update_last_check_never)
    } else {
        stringResource(R.string.app_update_last_check, formatDate(epochMs))
    }

/** Publication/last-check date in the localized context's locale. */
@Composable
private fun formatDate(epochMs: Long): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    return format.format(java.util.Date(epochMs))
}

/** Bytes progress label; without a known total, just the downloaded size. */
private fun sizeProgressLabel(context: Context, downloaded: Long, total: Long?): String {
    val downloadedLabel = FileSizeFormatter.format(downloaded)
    return if (total != null && total > 0) {
        context.getString(
            R.string.app_update_download_progress,
            downloadedLabel,
            FileSizeFormatter.format(total)
        )
    } else {
        downloadedLabel
    }
}

@Composable
private fun checkFailureDetail(kind: CheckFailureKind): String = stringResource(
    when (kind) {
        CheckFailureKind.NETWORK -> R.string.app_update_check_failed_network
        CheckFailureKind.RATE_LIMITED -> R.string.app_update_check_failed_rate
        CheckFailureKind.SERVER -> R.string.app_update_check_failed_server
        CheckFailureKind.UNAVAILABLE -> R.string.app_update_check_failed_unavailable
        CheckFailureKind.MALFORMED -> R.string.app_update_check_failed_parse
        CheckFailureKind.UNKNOWN -> R.string.app_update_check_failed_unknown
    }
)

@Composable
private fun downloadFailureDetail(kind: DownloadFailureKind): String = stringResource(
    when (kind) {
        DownloadFailureKind.NETWORK -> R.string.app_update_download_failed_network
        DownloadFailureKind.DISK -> R.string.app_update_download_failed_disk
        DownloadFailureKind.VERIFY -> R.string.app_update_download_failed_verify
        DownloadFailureKind.UNTRUSTED -> R.string.app_update_download_failed_untrusted
    }
)

/** Same silent-failure contract as the Help/Community links (§1.130's openLink). */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // No browser — the section already says where the release lives.
    }
}
