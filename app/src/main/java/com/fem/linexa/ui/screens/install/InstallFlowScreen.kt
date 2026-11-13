package com.fem.linexa.ui.screens.install

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.service.installer.InstallStep
import com.fem.linexa.service.installer.InstallSummary
import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.ui.components.DistroLogo
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HFactRow
import com.fem.linexa.ui.components.haven.HField
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HMonoBlock
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HRic
import com.fem.linexa.ui.components.haven.HSectionHeader
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.havenDistroColor
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The one-screen RootFS install flow — HAVEN (concept section 05, frames
 * `fr-inst` + `fr-inst2`).
 *
 * The concept's shape, kept exactly:
 *
 *  - a quiet header (back circle left, close circle right) over a big title +
 *    phase subtitle — "Review before installing" → "Do not close the app";
 *  - the identity card: distro glyph + name + source line, then SIX
 *    icon-labeled facts (distribution, architecture, archive size, files,
 *    required storage, free storage — the last one tinted green);
 *  - the confirm card: "Confirm installation" + one mono workspace-name field
 *    + end-aligned Cancel (ghost) / Confirm Install (primary);
 *  - the progress view that REPLACES the confirm card in place: ring spinner +
 *    stage title/description + big mono percent, `.prog.big` rail, the live
 *    "Now processing:" mono block, and the "If installation fails" reassurance
 *    card underneath.
 *
 * All state wiring is unchanged: the flow logic still lives entirely in
 * [InstallFlowViewModel] (backed by [com.fem.linexa.service.installer
 * .InstallCoordinator]); this composable only renders [InstallFlowUiState].
 */
@Composable
fun InstallFlowScreen(
    onBack: () -> Unit,
    onInstalled: () -> Unit,
    onViewDiagnostics: (String) -> Unit,
    viewModel: InstallFlowViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val motions = LocalHavenMotions.current

    // Back is only an abandon action while nothing is committed. Once
    // Installing, the system-back default (leave the screen, install keeps
    // running) is correct and this handler stays disabled.
    BackHandler(enabled = state.cancellable) {
        viewModel.cancel()
        onBack()
    }

    LaunchedEffect(state.phase, state.completedWorkspaceId, state.failedWorkspaceId) {
        when {
            state.phase == InstallFlowPhase.COMPLETED -> onInstalled()
            // A post-commit failure already persisted a Broken workspace row —
            // hand off to the existing diagnostics route rather than inventing
            // an error screen here.
            state.failedWorkspaceId != null -> onViewDiagnostics(state.failedWorkspaceId!!)
            else -> Unit
        }
    }

    val summary = state.summary
    val title = if (state.phase == InstallFlowPhase.INSTALLING && summary != null) {
        stringResource(R.string.install_title_installing_format, summary.containerCode)
    } else {
        stringResource(R.string.install_flow_title)
    }

    HavenScreen {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header: back circle · spacer · close circle (concept `.hdr`) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.phase == InstallFlowPhase.INSTALLING ||
                    state.phase == InstallFlowPhase.COMPLETED
                ) {
                    // The concept's running frame carries NO back button — only
                    // a 40dp spacer where it would be, so the close sits alone.
                    Spacer(modifier = Modifier.size(40.dp))
                } else {
                    HIconCircle(
                        iconRes = R.drawable.ic_haven_arrow_back,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = {
                            if (state.cancellable) viewModel.cancel()
                            onBack()
                        }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.phase != InstallFlowPhase.COMPLETED) {
                    HIconCircle(
                        iconRes = R.drawable.ic_haven_close,
                        contentDescription = stringResource(R.string.install_close),
                        onClick = {
                            if (state.cancellable) viewModel.cancel()
                            onBack()
                        }
                    )
                }
            }

            if (state.showsFailureInPlace) {
                InstallFailureBody(
                    reason = state.failureReason.orEmpty(),
                    onClose = onBack,
                    modifier = Modifier.fillMaxSize()
                )
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = HavenDim.screenH)
            ) {
                Text(
                    text = title,
                    style = HavenType.h2.copy(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = HavenTheme.colors.text,
                    modifier = Modifier.padding(top = 2.dp)
                )
                phaseSubtitle(state.phase)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = HavenType.desc,
                        color = HavenTheme.colors.text2,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                    )
                } ?: Spacer(modifier = Modifier.height(18.dp))

                RootFilesystemCard(
                    summary = summary,
                    sourceLabel = viewModel.sourceLabel,
                    isLocalFile = viewModel.isLocalFile,
                    analyzing = state.phase == InstallFlowPhase.ANALYZING,
                    step = state.step,
                    progress = state.progress
                )

                AnimatedContent(
                    targetState = state.phase,
                    transitionSpec = {
                        (fadeIn(motions.enter) + expandVertically(motions.enterSpec())) togetherWith
                            (fadeOut(motions.exit) + shrinkVertically(motions.exitSpec()))
                    },
                    label = "installStage"
                ) { phase ->
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (phase) {
                            InstallFlowPhase.CONFIRM -> if (summary != null) {
                                ConfirmInstallationCard(
                                    summary = summary,
                                    onCancel = {
                                        viewModel.cancel()
                                        onBack()
                                    },
                                    onConfirm = viewModel::confirm
                                )
                            }

                            InstallFlowPhase.INSTALLING, InstallFlowPhase.COMPLETED -> {
                                InstallingCard(
                                    step = state.step ?: InstallStep.EXTRACTING,
                                    progress = state.progress,
                                    currentEntryName = state.currentEntryName
                                )
                                // The concept's reassurance card sits under the
                                // progress card while installing.
                                if (phase == InstallFlowPhase.INSTALLING) {
                                    InstallFailureHintCard()
                                }
                            }

                            else -> Unit
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// ── Section 1 — identity + facts ────────────────────────────────────────────

/**
 * What is about to be installed — the concept's identity card: glyph + name +
 * source line, a hairline, then the six icon-labeled facts. Rendered in every
 * phase, with the fact rows appearing as analysis produces them; it never
 * collapses or moves once analysis finishes.
 */
@Composable
private fun RootFilesystemCard(
    summary: InstallSummary?,
    sourceLabel: String?,
    isLocalFile: Boolean,
    analyzing: Boolean,
    step: InstallStep?,
    progress: Float?
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    HCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DistroGlyph(
                distroId = summary?.identity?.distroId,
                isLocalFile = isLocalFile
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary?.distroLabel
                        ?: sourceLabel
                        ?: stringResource(R.string.install_source_unknown),
                    style = HavenType.h3,
                    color = h.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (isLocalFile) R.string.install_source_local else R.string.install_source_catalog
                    ),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        // Analysis in flight: the real step + the real percentage, or an
        // explicit em dash when the source reported no size.
        AnimatedVisibility(
            visible = analyzing,
            enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
            exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
        ) {
            Row(
                modifier = Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HSpinner(small = true)
                Text(
                    text = stepLabel(step ?: InstallStep.ANALYZING),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = progress?.let { "${(it * 100).toInt()}%" } ?: "—",
                    style = HavenType.descMono,
                    color = h.text2
                )
            }
        }

        AnimatedVisibility(
            visible = summary != null,
            enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
            exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
        ) {
            if (summary != null) RootFilesystemFacts(summary)
        }
    }
}

@Composable
private fun RootFilesystemFacts(summary: InstallSummary) {
    val h = HavenTheme.colors
    Column {
        com.fem.linexa.ui.components.haven.HHairline(modifier = Modifier.padding(top = 16.dp))
        // The concept's six facts — each with its own `.ric` icon tile; the
        // free-storage row is the one tinted green (a quiet "you're fine").
        FactRow(R.drawable.ic_haven_globe, stringResource(R.string.install_distribution), summary.distroLabel)
        FactRow(R.drawable.ic_haven_cpu, stringResource(R.string.install_architecture), summary.identity.arch.abi, mono = true)
        FactRow(R.drawable.ic_haven_box, stringResource(R.string.install_archive_size), FileSizeFormatter.format(summary.archiveBytes))
        FactRow(R.drawable.ic_haven_file, stringResource(R.string.install_entries), stringResource(R.string.install_entries_value, summary.totalEntries))
        FactRow(R.drawable.ic_haven_storage, stringResource(R.string.install_required_storage), FileSizeFormatter.format(summary.requiredStorageBytes))
        FactRow(
            R.drawable.ic_haven_check,
            stringResource(R.string.install_free_storage),
            FileSizeFormatter.format(summary.availableBytes),
            tinted = true
        )

        // §1.118: the author's own description, when the image declared one.
        summary.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        // §1.118/§1.119: whether the facts above were READ from the image or
        // inferred — kept verbatim, including the native/legacy distinction.
        if (summary.declaredMetadata != null) {
            NoteRow(R.drawable.ic_haven_info, stringResource(R.string.install_declared_note))
        } else if (summary.metadataSource != null) {
            NoteRow(R.drawable.ic_haven_info, stringResource(R.string.install_legacy_declared_note))
        }
        if (!summary.detected) {
            NoteRow(R.drawable.ic_haven_warning, stringResource(R.string.install_undetected_note), warning = true)
        }
        summary.declarationWarnings.forEach { warning ->
            NoteRow(R.drawable.ic_haven_warning, warning, warning = true)
        }
    }
}

// ── Section 2 — confirm ─────────────────────────────────────────────────────

@Composable
private fun ConfirmInstallationCard(
    summary: InstallSummary,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val h = HavenTheme.colors
    // Keyed on the analyzed code so a *different* rootfs prepared after this
    // screen was first composed does not inherit the previous default name.
    var workspaceName by rememberSaveable(summary.containerCode) { mutableStateOf(summary.containerCode) }

    HCard {
        Text(
            text = stringResource(R.string.install_section_confirm),
            style = HavenType.h3,
            color = h.text
        )
        Text(
            text = stringResource(R.string.install_confirm_subtitle),
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Text(
            text = stringResource(R.string.install_workspace_name).uppercase(),
            style = HavenType.tileLabel,
            color = h.muted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        HField(
            value = workspaceName,
            onValueChange = { workspaceName = it },
            mono = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (summary.overwritesExisting) {
            NoteRow(
                R.drawable.ic_haven_warning,
                stringResource(R.string.install_overwrite_warning),
                warning = true
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.GHOST
            )
            Spacer(modifier = Modifier.weight(1f))
            HButton(
                label = stringResource(R.string.install_confirm_button),
                onClick = { onConfirm(workspaceName) },
                variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.PRIMARY
            )
        }
    }
}

// ── Section 3 — installing ──────────────────────────────────────────────────

/**
 * The progress view, in the slot the confirm card occupied: ring spinner +
 * stage title/description + big mono percent, the `.prog.big` rail, the live
 * "Now processing:" mono block, and the keep-open note. Every value is a real
 * coordinator event — nothing is animated forward on a timer.
 */
@Composable
private fun InstallingCard(
    step: InstallStep,
    progress: Float?,
    currentEntryName: String?
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    HCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HSpinner()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stepLabel(step),
                    style = HavenType.h3,
                    color = h.text
                )
                Text(
                    text = stepDescription(step),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            AnimatedContent(
                targetState = progress?.let { "${(it * 100).toInt()}%" },
                transitionSpec = {
                    fadeIn(motions.enter) togetherWith fadeOut(motions.exit)
                },
                label = "installPercent"
            ) { percent ->
                Text(
                    text = percent ?: "",
                    style = HavenType.num22,
                    color = h.text
                )
            }
        }

        HProgress(
            fraction = progress,
            big = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
        )

        AnimatedVisibility(
            visible = currentEntryName != null,
            enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
            exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
        ) {
            // Export-parity (2026-09-02): the Export dialog clamps its
            // "Now processing" line to a single ellipsised line so the
            // card's height never breathes as entry names fly by — the
            // install screen adopts the same refinement. The name is real
            // (parsed from tar -v); the clip is presentation only.
            HMonoBlock(
                text = stringResource(R.string.install_processing_label) + " " + currentEntryName.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = stringResource(R.string.install_keep_open_hint),
            style = HavenType.small,
            color = h.muted,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/**
 * The concept's quiet reassurance card under the progress card.
 *
 * 2026-09-02 (warning/notice review): this is an INFORMATIONAL notice — it
 * explains what happens if the install fails — not an active safety
 * warning, so its presentation is demoted to the neutral note styling
 * (info icon, muted text) rather than the danger-red alarm tile. The
 * genuine data-loss warning (overwrite) keeps its amber treatment
 * untouched; only the styling changes here, never the semantics.
 */
@Composable
private fun InstallFailureHintCard() {
    NoteRow(
        R.drawable.ic_haven_info,
        stringResource(R.string.install_fail_hint_body)
    )
}

// ── Shared pieces ───────────────────────────────────────────────────────────

/**
 * The distro identity when the analysis identified one: the distribution's
 * official logo, drawn directly on the card surface — no tile, no tint (see
 * [DistroLogo]). An unidentified image keeps the neutral source icon tile; no
 * generic "Linux" glyph for an image we can't name, the same rule
 * [com.fem.linexa.ui.components.DistroIcons] encodes.
 */
@Composable
private fun DistroGlyph(
    distroId: RootFsProbe.DistroId?,
    isLocalFile: Boolean
) {
    val catalogId = catalogIdOf(distroId)
    if (catalogId != null) {
        DistroLogo(distroId = catalogId, size = HavenDim.glyph)
    } else {
        val iconRes = if (isLocalFile) R.drawable.ic_haven_file else R.drawable.ic_haven_download
        HRic(iconRes = iconRes, size = HavenDim.glyph)
    }
}

@Composable
private fun FactRow(
    iconRes: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tinted: Boolean = false
) {
    val h = HavenTheme.colors
    HFactRow(
        icon = { HRic(iconRes = iconRes, tinted = tinted) },
        label = label,
        value = value,
        modifier = modifier
    )
    // Mono values (architecture) still read as terminal vocabulary.
    if (mono) {
        // HFactRow renders the value in its own style; the mono flag only
        // affects nothing today — kept for call-site parity with the concept's
        // mono fact values.
    }
}

@Composable
private fun NoteRow(
    iconRes: Int,
    text: String,
    warning: Boolean = false
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier.padding(top = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon18(iconRes, tint = if (warning) h.amber else h.text2)
        Text(
            text = text,
            style = HavenType.small,
            color = if (warning) h.amber else h.text2
        )
    }
}

@Composable
private fun Icon18(iconRes: Int, tint: Color) {
    androidx.compose.material3.Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun InstallFailureBody(
    reason: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(h.redTint),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_haven_warning),
                contentDescription = null,
                tint = h.red,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = stringResource(R.string.install_failed_title),
            style = HavenType.h3,
            color = h.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = reason,
            style = HavenType.desc,
            color = h.text2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .widthInMax()
        )
        HButton(
            label = stringResource(R.string.install_close),
            onClick = onClose,
            variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.SECONDARY,
            size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL,
            modifier = Modifier.padding(top = 22.dp)
        )
    }
}

private fun Modifier.widthInMax(): Modifier =
    this.then(Modifier.fillMaxWidth(0.82f))

/** Catalog id for a probed distro — the key [DistroIcons]/[havenDistroColor] index by. */
private fun catalogIdOf(distroId: RootFsProbe.DistroId?): String? = when (distroId) {
    RootFsProbe.DistroId.DEBIAN -> "debian"
    RootFsProbe.DistroId.UBUNTU -> "ubuntu"
    RootFsProbe.DistroId.ALPINE -> "alpine"
    RootFsProbe.DistroId.ARCH -> "archlinux"
    RootFsProbe.DistroId.FEDORA -> "fedora"
    RootFsProbe.DistroId.OTHER, null -> null
}

@Composable
private fun phaseSubtitle(phase: InstallFlowPhase): String? = when (phase) {
    InstallFlowPhase.ANALYZING -> stringResource(R.string.install_subtitle_analyzing)
    InstallFlowPhase.CONFIRM -> stringResource(R.string.install_subtitle_confirm)
    InstallFlowPhase.INSTALLING -> stringResource(R.string.install_subtitle_installing)
    else -> null
}

@Composable
private fun stepLabel(step: InstallStep): String = stringResource(
    when (step) {
        InstallStep.DOWNLOADING -> R.string.install_step_downloading
        InstallStep.ANALYZING -> R.string.install_step_analyzing
        InstallStep.EXTRACTING -> R.string.install_step_extracting
        InstallStep.FIXING -> R.string.install_step_fixing
        InstallStep.FINALIZING -> R.string.install_step_finalizing
        InstallStep.VERIFYING -> R.string.install_step_verifying
    }
)

@Composable
private fun stepDescription(step: InstallStep): String = stringResource(
    when (step) {
        InstallStep.DOWNLOADING -> R.string.install_step_desc_downloading
        InstallStep.ANALYZING -> R.string.install_step_desc_analyzing
        InstallStep.EXTRACTING -> R.string.install_step_desc_extracting
        InstallStep.FIXING -> R.string.install_step_desc_fixing
        InstallStep.FINALIZING -> R.string.install_step_desc_finalizing
        InstallStep.VERIFYING -> R.string.install_step_desc_verifying
    }
)

/**
 * Deliberately short: this screen's transitions are state changes inside one
 * surface, not navigations, so they should read as the content updating rather
 * than as something arriving.
 */
