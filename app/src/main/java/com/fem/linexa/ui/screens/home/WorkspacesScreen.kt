package com.fem.linexa.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.BuildConfig
import com.fem.linexa.ui.components.haven.ItemPhase
import com.fem.linexa.ui.components.haven.motionItem
import com.fem.linexa.ui.components.haven.rememberEnteringKeys
import com.fem.linexa.ui.components.haven.rememberMotionItems
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.distro.DistroCatalog
import com.fem.linexa.domain.distro.DistroManifest
import com.fem.linexa.domain.workspace.WorkspaceListOptions
import com.fem.linexa.domain.workspace.WorkspaceListPolicy
import com.fem.linexa.domain.workspace.WorkspaceSort
import com.fem.linexa.domain.workspace.WorkspaceStatusFilter
import com.fem.linexa.service.exporter.ExportFlowState
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.ui.components.DistroIdentity
import com.fem.linexa.ui.components.haven.HBanner
import com.fem.linexa.ui.components.haven.HavenDestination
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HPageHeader
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.components.haven.HBottomNav
import com.fem.linexa.ui.components.haven.havenTabSwipe
import com.fem.linexa.ui.components.haven.HCatChip
import com.fem.linexa.ui.components.haven.HCatKindOf
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HChipRow
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HDialog
import com.fem.linexa.ui.components.haven.HEmpty
import com.fem.linexa.ui.components.haven.HError
import com.fem.linexa.ui.components.haven.HField
import com.fem.linexa.ui.components.haven.HFootBrand
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HLoading
import com.fem.linexa.ui.components.haven.HMenu
import com.fem.linexa.ui.components.haven.HMenuItem
import com.fem.linexa.ui.components.haven.HMonoBlock
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HRow
import com.fem.linexa.ui.components.haven.HSearchField
import com.fem.linexa.ui.components.haven.HSheet
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HStatus
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.havenPressScale
import com.fem.linexa.ui.components.haven.HStorageBar
import com.fem.linexa.ui.components.haven.workspaceStatusPresentation
import com.fem.linexa.ui.screens.terminal.HavenManagementTerminalPanel
import com.fem.linexa.ui.screens.terminal.ManagementTerminalViewModel
import com.fem.linexa.ui.theme.haven.HavenType as HType

/**
 * The Workspaces tab — HAVEN home (concept section 01).
 *
 * Quiet header (eyebrow "LINEXA", h1 "Workspaces N", Management-Terminal and
 * accent + circle actions — no FAB), the dismissible tinted What's New banner,
 * and the workspace cards: glyph, name, distro · size meta, dot + one-word
 * status, optional 3px storage bar, ⋯ menu (Rename / Export / Delete) and a
 * chevron. Empty / loading / error states are the shared Haven shapes.
 *
 * § export: the card menu's Export entry opens the system save picker
 * ([androidx.activity.result.contract.ActivityResultContracts.CreateDocument],
 * the same pattern the file manager's export uses) and the resulting flow —
 * progress, completion, failure — renders here as [HDialog]s over the list,
 * driven by [HomeViewModel.exportState] (owned by the ExportCoordinator).
 */
@Composable
fun WorkspacesScreen(
    onOpenWorkspace: (Workspace) -> Unit,
    onInstallDistro: (String) -> Unit,
    onInstallFile: (uri: String, name: String, version: String) -> Unit,
    onSelectTab: (HavenDestination) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    terminalViewModel: ManagementTerminalViewModel = hiltViewModel(),
    exportViewModel: ExportFlowViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // `workspaces` is the unfiltered TOTAL: the header count and the bottom-nav badge read
    // it and must not change as the user narrows the list. The list itself renders
    // `visibleWorkspaces` (search + filter + sort applied) — see HomeViewModel.
    val workspaces by viewModel.workspacesUi.collectAsState()
    val visibleWorkspaces by viewModel.visibleWorkspaces.collectAsState()
    val listOptions by viewModel.listOptions.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val terminalSession by terminalViewModel.session.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    val whatsNew by viewModel.whatsNew.collectAsState()
    val exportState by exportViewModel.state.collectAsState()

    var terminalOpen by rememberSaveable { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showDistroCatalog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renamingWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deletingWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var menuForWorkspace by remember { mutableStateOf<String?>(null) }
    // § export: the workspace whose export destination is being picked — SAF
    // returns only a Uri, so the target must be remembered across the launcher
    // (the same pattern the file manager's export uses).
    var exportTarget by remember { mutableStateOf<Workspace?>(null) }
    // § export: a refused pre-gate (running workspace, no rootfs, …) surfaces
    // here instead of opening a destination pick that could never succeed.
    var exportBlockReason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(terminalOpen) {
        if (terminalOpen) terminalViewModel.start()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = queryDisplayName(context, uri) ?: "workspace"
            val distroName = fileName.substringBeforeLast(".tar").substringBeforeLast(".")
            onInstallFile(uri.toString(), distroName.ifBlank { "workspace" }, "unknown")
        }
        showImportSheet = false
    }

    // § export — the backup destination. CreateDocument creates the document
    // immediately and hands back the write Uri; the packing starts only here,
    // on the result, so a cancelled pick leaves nothing behind.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            val suggested = exportViewModel.suggestedFileName(target)
            exportViewModel.start(target, uri, suggested)
        }
    }

    // System back closes the terminal overlay before it leaves the tab.
    BackHandler(enabled = terminalOpen) { terminalOpen = false }

    Scaffold(
        containerColor = HavenTheme.colors.bg,
        bottomBar = {
            // Always composed; alpha/disabled while the overlay is open so the
            // terminal covers its band without the content re-measuring.
            HBottomNav(
                selected = HavenDestination.WORKSPACES,
                onSelect = onSelectTab,
                modifier = Modifier.graphicsLayer { alpha = if (terminalOpen) 0f else 1f },
                workspaceCount = workspaces.size.takeIf { it > 0 }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .havenTabSwipe(
                    current = HavenDestination.WORKSPACES,
                    // The management terminal owns horizontal gestures while
                    // it is open; page swipes resume when it closes.
                    enabled = !terminalOpen,
                    onSelectTab = onSelectTab
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                HavenScreen(statusBarInsets = false) {
                Column(modifier = Modifier.fillMaxSize()) {
                    HPageHeader(
                        eyebrow = "LINEXA",
                        title = stringResource(R.string.haven_workspaces_title),
                        count = workspaces.size.toString(),
                        trailing = {
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_terminal,
                                contentDescription = stringResource(R.string.home_mgmt_terminal),
                                onClick = { terminalOpen = !terminalOpen }
                            )
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_add,
                                contentDescription = stringResource(R.string.home_add_workspace),
                                accent = true,
                                onClick = {
                                    showDistroCatalog = false
                                    showImportSheet = true
                                }
                            )
                        }
                    )

                    when (val state = uiState) {
                        is HomeUiState.Loading -> HLoading(
                            label = stringResource(R.string.home_loading)
                        )
                        is HomeUiState.Error -> HError(
                            title = stringResource(R.string.home_error_title),
                            detail = state.message ?: stringResource(R.string.home_error_body),
                            retryLabel = stringResource(R.string.common_retry),
                            onRetry = viewModel::refresh
                        )
                        is HomeUiState.Empty -> {
                            HavenWorkspaceList(
                                workspaces = emptyList(),
                                whatsNew = whatsNew,
                                onToggleWhatsNew = viewModel::toggleWhatsNewExpanded,
                                onDismissWhatsNew = viewModel::acknowledgeWhatsNew,
                                onOpen = {},
                                onMenu = { _, _ -> },
                                footVersion = null,
                                contentPadding = PaddingValues(),
                                header = {},
                                emptyAction = {
                                    HButton(
                                        label = stringResource(R.string.home_import_title),
                                        onClick = {
                                            showDistroCatalog = false
                                            showImportSheet = true
                                        },
                                        size = HavenButtonSize.SMALL
                                    )
                                }
                            )
                        }
                        is HomeUiState.HasWorkspaces -> HavenWorkspaceList(
                            workspaces = visibleWorkspaces,
                            whatsNew = whatsNew,
                            onToggleWhatsNew = viewModel::toggleWhatsNewExpanded,
                            onDismissWhatsNew = viewModel::acknowledgeWhatsNew,
                            onOpen = onOpenWorkspace,
                            onMenu = { workspace, action ->
                                when (action) {
                                    WorkspaceMenuAction.RENAME -> {
                                        renamingWorkspace = workspace
                                        showRenameDialog = true
                                    }
                                    WorkspaceMenuAction.EXPORT -> {
                                        // Pre-gate before the picker: a workspace
                                        // that cannot be exported (running, no
                                        // rootfs, …) never asks the user to
                                        // choose a destination first. The
                                        // coordinator re-validates on start.
                                        val blockReason = exportViewModel.blockReason(workspace)
                                        if (blockReason != null) {
                                            exportBlockReason = blockReason
                                        } else {
                                            exportTarget = workspace
                                            exportLauncher.launch(exportViewModel.suggestedFileName(workspace))
                                        }
                                    }
                                    WorkspaceMenuAction.DELETE -> {
                                        deletingWorkspace = workspace
                                        showDeleteConfirmation = true
                                    }
                                }
                            },
                            footVersion = AppVersionLine,
                            contentPadding = PaddingValues(),
                            header = {},
                            emptyAction = null,
                            // P3: the toolbar needs enough workspaces to be worth its height,
                            // judged on the TOTAL so it stays put while a filter narrows the
                            // list to one card (or none).
                            toolbar = if (WorkspaceListPolicy.showsToolbar(workspaces.size)) {
                                {
                                    HomeListToolbar(
                                        options = listOptions,
                                        onQueryChange = viewModel::onQueryChange,
                                        onFilterChange = viewModel::onFilterChange,
                                        onSortCycle = viewModel::onSortCycle
                                    )
                                }
                            } else {
                                null
                            },
                            // Only a search/filter that really hid every card gets the
                            // "no matches" state; the ordinary "No workspaces yet" state is
                            // untouched and still belongs to HomeUiState.Empty.
                            noResults = if (
                                WorkspaceListPolicy.showsToolbar(workspaces.size) &&
                                listOptions.narrowsList &&
                                visibleWorkspaces.isEmpty()
                            ) {
                                {
                                    HEmpty(
                                        title = stringResource(R.string.home_no_results_title),
                                        description = stringResource(R.string.home_no_results_subtitle),
                                        iconRes = R.drawable.ic_haven_search,
                                        actionLabel = stringResource(R.string.home_no_results_clear),
                                        onAction = viewModel::clearSearchAndFilters,
                                        fill = false
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
            }

            // Management terminal overlay — FULLSCREEN takeover (the
            // concept's terminal is a page: its own session bar is the only
            // chrome; the surface clears the status bar itself).
            //
            // 2026-08-31 (Phase 1, full-available-area fix): the overlay is a
            // SIBLING of the Scaffold-padded box, not a child of it. Inside
            // that box the Material3 Scaffold's content padding (status-bar
            // top inset + the bottom-bar band, kept composed under alpha 0)
            // shrank the overlay on both ends — blank strips above and below
            // the terminal — and the panel's own bar then re-padded the
            // status inset inside already-inset space. At this level the
            // overlay spans the whole window edge-to-edge; inset handling
            // stays where it is owned (bar statusBarsPadding, extra keys
            // navigationBarsPadding, surface imePadding), so rotation,
            // keyboard show/hide and configuration changes all keep flowing
            // through real window insets. No hardcoded dimensions anywhere.
            com.fem.linexa.ui.components.TerminalOverlay(
                visible = terminalOpen,
                modifier = Modifier.fillMaxSize()
            ) {
                HavenManagementTerminalPanel(
                    session = terminalSession,
                    onDismiss = { terminalOpen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showImportSheet) {
        BackHandler(enabled = showDistroCatalog) { showDistroCatalog = false }
        com.fem.linexa.ui.components.haven.HSheet(
            onDismiss = {
                showImportSheet = false
                showDistroCatalog = false
            },
            title = stringResource(R.string.home_add_workspace),
            subtitle = stringResource(R.string.home_import_subtitle)
        ) {
            if (showDistroCatalog) {
                DistroCatalogPane(
                    distros = DistroCatalog.all,
                    onDistroSelected = { distro ->
                        onInstallDistro(distro.id)
                        showImportSheet = false
                        showDistroCatalog = false
                    },
                    onBack = { showDistroCatalog = false }
                )
            } else {
                ImportPane(
                    onImportClicked = { importLauncher.launch(arrayOf("*/*")) },
                    onDownloadClicked = { showDistroCatalog = true }
                )
            }
        }
    }

    if (showRenameDialog && renamingWorkspace != null) {
        var renameText by remember(renamingWorkspace!!.id) {
            mutableStateOf(renamingWorkspace!!.name)
        }
        HDialog(
            title = stringResource(R.string.home_rename_title),
            body = stringResource(R.string.haven_rename_hint),
            onDismiss = {
                showRenameDialog = false
                renamingWorkspace = null
            },
            content = {
                HField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    mono = true,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth()
            ) {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = {
                        showRenameDialog = false
                        renamingWorkspace = null
                    },
                    variant = HavenButtonVariant.GHOST,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_rename),
                    onClick = {
                        val target = renamingWorkspace
                        if (target != null && renameText.isNotBlank() && renameText != target.name) {
                            viewModel.renameWorkspace(target.id, renameText.trim())
                        }
                        renamingWorkspace = null
                        showRenameDialog = false
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL,
                    enabled = renameText.isNotBlank() && renameText.trim() != renamingWorkspace!!.name
                )
            }
        }
    }

    if (showDeleteConfirmation && deletingWorkspace != null) {
        HDialog(
            title = stringResource(R.string.dialog_delete_workspace_title),
            body = stringResource(R.string.dialog_delete_workspace_message, deletingWorkspace!!.name),
            onDismiss = {
                showDeleteConfirmation = false
                deletingWorkspace = null
            }
        ) {
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = {
                    showDeleteConfirmation = false
                    deletingWorkspace = null
                },
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL
            )
            HButton(
                label = stringResource(R.string.common_delete),
                onClick = {
                    val ws = deletingWorkspace
                    showDeleteConfirmation = false
                    deletingWorkspace = null
                    if (ws != null) viewModel.deleteWorkspace(ws.id)
                },
                variant = HavenButtonVariant.DANGER,
                size = HavenButtonSize.SMALL
            )
        }
    }

    deleteError?.let { reason ->
        com.fem.linexa.ui.components.haven.HDialog(
            title = stringResource(R.string.dialog_delete_workspace_failed_title),
            body = reason,
            onDismiss = viewModel::clearDeleteError
        ) {
            HButton(
                label = stringResource(R.string.common_ok),
                onClick = viewModel::clearDeleteError,
                size = HavenButtonSize.SMALL
            )
        }
    }

    // ── § export — progress, completion, failure ─────────────────────────
    //
    // One dialog per outcome, all HDialog (the Home kit's dialog shape):
    // progress is non-dismissable while the packing runs (the install flow
    // locks its screen the same way, and cancelling is an explicit button —
    // the coordinator removes the partial file when it fires), and both
    // terminal states dismiss back to Idle through consumeExportResult().

    when (val state = exportState) {
        is ExportFlowState.Active -> ExportProgressDialog(
            state = state,
            onCancel = exportViewModel::cancel
        )
        is ExportFlowState.Completed -> HDialog(
            title = stringResource(R.string.export_success_title),
            body = buildString {
                append(stringResource(R.string.export_success_body, state.fileName, formatBytes(state.outputBytes)))
                if (state.skippedEntries.isNotEmpty()) {
                    append('\n')
                    append(stringResource(R.string.export_success_skipped, state.skippedEntries.size))
                }
            },
            onDismiss = exportViewModel::consumeResult
        ) {
            HButton(
                label = stringResource(R.string.common_ok),
                onClick = exportViewModel::consumeResult,
                size = HavenButtonSize.SMALL
            )
        }
        is ExportFlowState.Failed -> HDialog(
            title = stringResource(R.string.export_failed_title),
            body = buildString {
                append(state.reason)
                if (state.partialFileCleanupFailed) {
                    append('\n')
                    append(stringResource(R.string.export_cleanup_note))
                }
            },
            onDismiss = exportViewModel::consumeResult
        ) {
            HButton(
                label = stringResource(R.string.common_ok),
                onClick = exportViewModel::consumeResult,
                size = HavenButtonSize.SMALL
            )
        }
        ExportFlowState.Idle -> Unit
    }

    // § export: the pre-gate's refusal — shown instead of a destination pick
    // that could never have succeeded.
    exportBlockReason?.let { reason ->
        HDialog(
            title = stringResource(R.string.export_failed_title),
            body = reason,
            onDismiss = { exportBlockReason = null }
        ) {
            HButton(
                label = stringResource(R.string.common_ok),
                onClick = { exportBlockReason = null },
                size = HavenButtonSize.SMALL
            )
        }
    }
}

/**
 * The export progress dialog — the install screen's InstallingCard idiom
 * (spinner + stage title, animated percent, big rail, live "Now processing:"
 * mono block) compacted into the Home kit's dialog. Every value is a real
 * coordinator event; nothing animates forward on a timer.
 */
@Composable
private fun ExportProgressDialog(
    state: ExportFlowState.Active,
    onCancel: () -> Unit
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    // content / actions MUST be passed by name: HDialog's trailing lambda
    // binds to its `actions` Row, and a body landing there collapses to a
    // zero-width, breathing, cancel-less layout (the v1.1 export bug).
    HDialog(
        title = stringResource(R.string.export_dialog_progress_title, state.workspaceName),
        onDismiss = { /* deliberate: an in-flight export exits only via Cancel */ },
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                HSpinner()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exportStepLabel(state.step),
                        style = HavenType.h3,
                        color = h.text
                    )
                    Text(
                        text = state.workspaceName,
                        style = HavenType.desc,
                        color = h.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                state.progress?.let { fraction ->
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = HavenType.num22,
                        color = h.text
                    )
                }
            }

            HProgress(
                fraction = state.progress,
                big = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )

            AnimatedVisibility(
                visible = state.currentEntryName != null,
                enter = fadeIn(animationSpec = motions.enter) +
                    expandVertically(animationSpec = motions.enterSpec()),
                exit = fadeOut(animationSpec = motions.exit) +
                    shrinkVertically(animationSpec = motions.exitSpec())
            ) {
                // One ellipsised line: the dialog's height must not breathe as
                // entry names fly by — the install flow's card can wrap, a
                // dialog cannot.
                HMonoBlock(
                    text = stringResource(R.string.install_processing_label) + " " + state.currentEntryName.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL
            )
        }
    )
}

/** The stage line's text — same vocabulary the install flow's steps use. */
@Composable
private fun exportStepLabel(step: com.fem.linexa.service.exporter.ExportStep): String = when (step) {
    com.fem.linexa.service.exporter.ExportStep.PREPARING -> stringResource(R.string.export_step_preparing)
    com.fem.linexa.service.exporter.ExportStep.PACKING -> stringResource(R.string.export_step_packing)
    com.fem.linexa.service.exporter.ExportStep.FINALIZING -> stringResource(R.string.export_step_finalizing)
    com.fem.linexa.service.exporter.ExportStep.VERIFYING -> stringResource(R.string.export_step_verifying)
}

/** Bytes for the completion dialog — the same formatter the cards use. */
private fun formatBytes(bytes: Long): String = FileSizeFormatter.format(bytes)

private val AppVersionLine = "Linexa · ${BuildConfig.VERSION_NAME}"

/** The install screen's enter/exit animation timings, reused for the export dialog. */

internal enum class WorkspaceMenuAction { RENAME, EXPORT, DELETE }

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * The workspace list body shared by every state: banner (when present), cards,
 * the empty state and the footbrand. The loaded state renders inside one
 * LazyColumn so the cards, banner and footbrand scroll identically.
 *
 * 2026-09-05 ("not workspace yet" sits too high): the EMPTY state no longer
 * renders as a LazyColumn item. A LazyColumn stacks items from the top and
 * has no remaining-height concept for a middle item, so the icon + title +
 * subtitle block hugged the page header no matter the screen size. When
 * [workspaces] is empty the body is instead a full-height column: the What's
 * New banner (when present) stays at the top, and the empty indicator centers
 * itself in the remaining main content area — between the page header and the
 * bottom navigation — through ordinary layout weights, so it follows system
 * insets, app bars and screen size with no hardcoded coordinates. The loaded
 * state below is byte-for-byte unchanged.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HavenWorkspaceList(
    workspaces: List<Workspace>,
    whatsNew: WhatsNewUiState?,
    onToggleWhatsNew: () -> Unit,
    onDismissWhatsNew: () -> Unit,
    onOpen: (Workspace) -> Unit,
    onMenu: (Workspace, WorkspaceMenuAction) -> Unit,
    footVersion: String?,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
    emptyAction: (@Composable () -> Unit)?,
    toolbar: (@Composable () -> Unit)? = null,
    noResults: (@Composable () -> Unit)? = null
) {
    // Hoisted above the empty-state early return so the diff state survives it.
    val listMotions = com.fem.linexa.ui.components.haven.LocalHavenMotions.current
    val motionWorkspaces = rememberMotionItems(workspaces) { it.id }
    val h = HavenTheme.colors
    // P3: an empty list with a `noResults` slot is a search that hid everything, not a
    // workspace-less install — it falls through to the LazyColumn below (toolbar still
    // visible, no-results block under it) instead of this centered empty state.
    if (workspaces.isEmpty() && noResults == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = HavenDim.screenH),
            verticalArrangement = Arrangement.spacedBy(HavenDim.cardGap)
        ) {
            if (whatsNew != null) {
                if (whatsNew.expanded) {
                    HavenWhatsNewFullCard(
                        notes = whatsNew.notes,
                        onDismiss = onDismissWhatsNew,
                        onCollapse = onToggleWhatsNew
                    )
                } else {
                    HavenWhatsNewBanner(
                        notes = whatsNew.notes,
                        onDismiss = onDismissWhatsNew,
                        onViewAll = onToggleWhatsNew
                    )
                }
            }
            // weight(1f) = exactly the space below the header (and banner):
            // the empty indicator is centered in it, so it reads as centered
            // in the main content area for every screen size and orientation.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HEmpty(
                        title = stringResource(R.string.home_empty_title),
                        description = stringResource(R.string.home_empty_subtitle),
                        iconRes = R.drawable.ic_haven_box,
                        fill = false
                    )
                    if (emptyAction != null) emptyAction()
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HavenDim.screenH,
            end = HavenDim.screenH,
            top = 12.dp,
            bottom = 40.dp
        ),
        verticalArrangement = Arrangement.spacedBy(HavenDim.cardGap)
    ) {
        if (whatsNew != null) {
            item(key = "whats-new-${whatsNew.notes.versionCode}") {
                // Collapsed → the tinted banner (summary or first change).
                // Expanded ("View all") → the full card with every change on
                // its own category chip, as the concept's What's New frame.
                if (whatsNew.expanded) {
                    HavenWhatsNewFullCard(
                        notes = whatsNew.notes,
                        onDismiss = onDismissWhatsNew,
                        onCollapse = onToggleWhatsNew
                    )
                } else {
                    HavenWhatsNewBanner(
                        notes = whatsNew.notes,
                        onDismiss = onDismissWhatsNew,
                        onViewAll = onToggleWhatsNew
                    )
                }
            }
        }

        // P3: search / status filter / sort. A list item (not a pinned bar) so it scrolls
        // away like the What's New banner and costs no permanent screen height; it keeps its
        // key while results change, so typing never loses focus.
        if (toolbar != null) {
            item(key = "list-toolbar") { toolbar() }
        }

        // 2026-09-05: the workspace-less empty state no longer renders here — the empty
        // branch above returns a centered full-height layout before the LazyColumn. The
        // only empty list that reaches this point is a search that matched nothing.
        if (workspaces.isEmpty() && noResults != null) {
            item(key = "list-no-results") { noResults() }
        }

        items(motionWorkspaces, key = { it.key }) { mi ->
            val workspace = mi.value
            HavenWorkspaceCard(
                // Add / delete / filter / search: neighbours glide, new cards
                // fade+lift in, a removed card fades out before its slot closes.
                modifier = Modifier
                    .animateItemPlacement(animationSpec = listMotions.enterSpec())
                    .motionItem(mi.phase, mi.enterOrdinal, scaleFrom = 0.97f, liftFrom = 6.dp),
                workspace = workspace,
                onClick = { onOpen(workspace) },
                onRename = { onMenu(workspace, WorkspaceMenuAction.RENAME) },
                onExport = { onMenu(workspace, WorkspaceMenuAction.EXPORT) },
                onDelete = { onMenu(workspace, WorkspaceMenuAction.DELETE) }
            )
        }

        if (footVersion != null && workspaces.isNotEmpty()) {
            item(key = "footbrand") {
                HFootBrand(versionLine = footVersion)
            }
        }
    }
}

/**
 * P3 — the Home list's search field, status filter chips and the single sort chip.
 *
 * Built from the existing Haven pieces and nothing new: [HSearchField] as the field,
 * [HChip]s in an [HChipRow] for the filters (the row is a plain `Row`, so it is made
 * horizontally scrollable here rather than changing the shared component), and one chip
 * that cycles Newest → Oldest → Name. The sort chip names the *current* order, so the
 * state is readable without a tooltip.
 */
@Composable
private fun HomeListToolbar(
    options: WorkspaceListOptions,
    onQueryChange: (String) -> Unit,
    onFilterChange: (WorkspaceStatusFilter) -> Unit,
    onSortCycle: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HSearchField(
            value = options.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.home_search_hint),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HChipRow(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                WorkspaceStatusFilter.entries.forEach { filter ->
                    HChip(
                        label = stringResource(filter.labelRes()),
                        selected = options.filter == filter,
                        onClick = { onFilterChange(filter) }
                    )
                }
            }
            HChip(
                label = stringResource(
                    R.string.home_sort_label,
                    stringResource(options.sort.labelRes())
                ),
                selected = false,
                onClick = onSortCycle
            )
        }
    }
}

private fun WorkspaceStatusFilter.labelRes(): Int = when (this) {
    WorkspaceStatusFilter.ALL -> R.string.home_filter_all
    WorkspaceStatusFilter.READY -> R.string.home_filter_ready
    WorkspaceStatusFilter.IN_PROGRESS -> R.string.home_filter_in_progress
    WorkspaceStatusFilter.BROKEN -> R.string.home_filter_broken
}

private fun WorkspaceSort.labelRes(): Int = when (this) {
    WorkspaceSort.NEWEST -> R.string.home_sort_newest
    WorkspaceSort.OLDEST -> R.string.home_sort_oldest
    WorkspaceSort.NAME -> R.string.home_sort_name
}

/** `.wcard` — the workspace card (glyph, name, meta, status, sbar, ⋯, chev). */
@Composable
fun HavenWorkspaceCard(
    workspace: Workspace,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val status = workspaceStatusPresentation(workspace.status, workspace.isRunning)
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .havenPressScale(interaction)
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.surface)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            DistroIdentity(
                distroId = workspace.distroId,
                letter = workspace.avatarLetter,
                color = workspace.avatarColor,
                size = HavenDim.glyph
            )
            Column(modifier = Modifier.weight(1f, fill = true)) {
                Text(
                    text = workspace.name,
                    style = HavenType.title,
                    color = h.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    workspace.distroName?.let { distro ->
                        buildString {
                            append(distro)
                            workspace.distroVersion?.let { append(" ").append(it) }
                        }
                    },
                    workspace.storageUsedLabel
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                HIconGhost(
                    iconRes = R.drawable.ic_haven_more_vert,
                    contentDescription = stringResource(R.string.haven_workspace_options),
                    onClick = { menuOpen = true }
                )
                HMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    items = listOf(
                        HMenuItem(stringResource(R.string.common_rename), R.drawable.ic_haven_edit, onRename),
                        HMenuItem(stringResource(R.string.common_export), R.drawable.ic_haven_save, onExport),
                        HMenuItem(stringResource(R.string.common_delete), R.drawable.ic_haven_trash, onDelete, danger = true)
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HStatus(text = status.label, color = status.color, pulsing = status.pulsing)
            Spacer(modifier = Modifier.weight(1f))
            HChevron()
        }

        // The 3px storage bar only where a real size was measured — chart
        // clutter is worse than no chart (concept fnote on the Home frame).
        if (workspace.storageUsedLabel != null && workspace.storageUsedFraction > 0f) {
            HStorageBar(
                fraction = workspace.storageUsedFraction,
                modifier = Modifier.padding(top = 15.dp)
            )
        }
    }
}

/** `.banner` — the tinted What's New card with dismiss + View all. */
@Composable
private fun HavenWhatsNewBanner(
    notes: com.fem.linexa.domain.whatsnew.ReleaseNotes,
    onDismiss: () -> Unit,
    onViewAll: () -> Unit
) {
    val h = HavenTheme.colors
    HBanner {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.haven_whats_new),
                        style = HavenType.desc.copy(fontWeight = FontWeight.SemiBold, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = h.text
                    )
                    Text(
                        text = "${notes.versionName} (${notes.versionCode})",
                        style = HavenType.descMono,
                        color = h.text2
                    )
                }
                if (notes.summary != null) {
                    Text(
                        text = notes.summary,
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                } else {
                    notes.changes.firstOrNull()?.let { change ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 5.dp)
                        ) {
                            HCatChip(kind = HCatKindOf(change.category))
                            Text(
                                text = change.text,
                                style = HavenType.desc,
                                color = h.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            HIconGhost(
                iconRes = R.drawable.ic_haven_close,
                contentDescription = stringResource(R.string.haven_dismiss),
                onClick = onDismiss,
                small = true
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            HButton(
                label = stringResource(R.string.haven_got_it),
                onClick = onDismiss,
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL
            )
            HButton(
                label = stringResource(R.string.haven_view_all),
                onClick = onViewAll,
                variant = HavenButtonVariant.GHOST,
                size = HavenButtonSize.SMALL,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_haven_chevron),
                        contentDescription = null,
                        tint = HavenTheme.colors.text2,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

/** The expanded What's New card — every change on its own category chip. */
@Composable
private fun HavenWhatsNewFullCard(
    notes: com.fem.linexa.domain.whatsnew.ReleaseNotes,
    onDismiss: () -> Unit,
    onCollapse: () -> Unit
) {
    val h = HavenTheme.colors
    com.fem.linexa.ui.components.haven.HCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.haven_whats_new),
                    style = HavenType.title,
                    color = h.text
                )
                Text(
                    text = "${notes.versionName} (${notes.versionCode})",
                    style = HavenType.descMono,
                    color = h.text2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HIconGhost(
                iconRes = R.drawable.ic_haven_close,
                contentDescription = stringResource(R.string.haven_dismiss),
                onClick = onDismiss,
                small = true
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            notes.changes.forEachIndexed { index, change ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 13.dp)
                ) {
                    HCatChip(kind = HCatKindOf(change.category))
                    Text(
                        text = change.text,
                        style = HavenType.desc.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = h.text,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < notes.changes.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(HavenDim.hairline)
                            .background(h.border)
                    )
                }
            }
        }
        HButton(
            label = stringResource(R.string.haven_got_it),
            onClick = onDismiss,
            variant = HavenButtonVariant.PRIMARY,
            block = true,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}


/** Import pane — two quiet rows, one decision each (concept frame `sh-import`). */
@Composable
private fun ImportPane(
    onImportClicked: () -> Unit,
    onDownloadClicked: () -> Unit
) {
    val h = HavenTheme.colors
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        HRow(
            title = stringResource(R.string.home_download_title),
            description = stringResource(R.string.home_download_subtitle),
            onClick = onDownloadClicked,
            leading = {
                com.fem.linexa.ui.components.haven.HRic(
                    iconRes = R.drawable.ic_haven_download,
                    tinted = true
                )
            },
            trailing = { HChevron() }
        )
        HRow(
            title = stringResource(R.string.home_import_title),
            description = stringResource(R.string.home_choose_file),
            onClick = onImportClicked,
            leading = {
                com.fem.linexa.ui.components.haven.HRic(iconRes = R.drawable.ic_haven_folder)
            },
            trailing = {
                HButton(
                    label = stringResource(R.string.home_choose_file_button),
                    onClick = onImportClicked,
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
            }
        )
        Text(
            text = stringResource(R.string.haven_import_note),
            style = HavenType.small,
            color = h.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

/** Distro catalog pane — version · size in mono, pushed in from the right. */
@Composable
private fun DistroCatalogPane(
    distros: List<DistroManifest>,
    onDistroSelected: (DistroManifest) -> Unit,
    onBack: () -> Unit
) {
    val h = HavenTheme.colors
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            HIconCircle(
                iconRes = R.drawable.ic_haven_arrow_back,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
                size = 34
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.home_choose_distro),
                    style = HType.titleLg.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = h.text
                )
                Text(
                    text = stringResource(R.string.haven_catalog_from),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 2026-09-01 gate: ALL catalog entries render, but locked ones
        // (manifest.enabled == false) are dimmed, show a lock badge and
        // ignore taps — Debian/Ubuntu are the only install targets for now.
        distros.forEach { distro ->
            val locked = !distro.enabled
            HRow(
                title = distro.displayName,
                description = "${distro.version} · ${distro.approxDownloadSizeLabel}",
                descriptionMono = true,
                enabled = !locked,
                onClick = { if (!locked) onDistroSelected(distro) },
                leading = {
                    DistroIdentity(
                        distroId = distro.id,
                        letter = distro.avatarLetter,
                        color = try {
                            androidx.compose.ui.graphics.Color(
                                android.graphics.Color.parseColor(distro.avatarColorHex)
                            )
                        } catch (_: IllegalArgumentException) {
                            h.primary
                        },
                        size = HavenDim.glyphSm
                    )
                },
                trailing = {
                    if (locked) {
                        Text(
                            text = stringResource(R.string.home_distro_locked),
                            style = HavenType.desc,
                            color = h.muted,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        HChevron()
                    }
                }
            )
        }
    }
}
