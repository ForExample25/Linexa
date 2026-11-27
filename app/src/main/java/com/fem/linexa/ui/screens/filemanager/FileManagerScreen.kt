package com.fem.linexa.ui.screens.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.components.haven.ItemPhase
import com.fem.linexa.ui.components.haven.motionItem
import com.fem.linexa.ui.components.haven.rememberEnteringKeys
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.filemanager.FileInfo
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HCrumbs
import com.fem.linexa.ui.components.haven.HDialog
import com.fem.linexa.ui.components.haven.HEmpty
import com.fem.linexa.ui.components.haven.HError
import com.fem.linexa.ui.components.haven.HField
import com.fem.linexa.ui.components.haven.HHairline
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HLoading
import com.fem.linexa.ui.components.haven.HMenu
import com.fem.linexa.ui.components.haven.HMenuItem
import com.fem.linexa.ui.components.haven.HRow
import com.fem.linexa.ui.components.haven.HRic
import com.fem.linexa.ui.components.haven.HSheet
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HStorageBar
import com.fem.linexa.ui.components.haven.HSubHeader
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.service.runtime.container.WorkspaceHomeLayout
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlinx.coroutines.delay

/**
 * File Manager — HAVEN (concept frame `fr-files`).
 *
 * Chrome is the sub-screen header (back circle + title + workspace subtitle +
 * refresh / import / paste circle actions), concept `.crumbs` for the path,
 * and one tight [HCard] whose [HRow]s carry every entry. Issue #10
 * (2026-09-04): ONE floating plus button owns creation — tapping it expands
 * "New Folder"/"New File" options above it — and long-pressing an entry opens
 * the ZArchiver-style bottom action sheet, so rows carry no action buttons.
 * Operation feedback renders as a Haven toast pill.
 *
 * Logic is untouched: same [FileManagerViewModel] wiring, same SAF launchers,
 * same BackHandler contract (back closes the in-place file viewer first).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    viewModel: FileManagerViewModel,
    workspaceName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // Phase 08 (audit OPT-PERF-001): ONE consolidated state collected once,
    // replacing 17 root-level `collectAsState()` subscriptions (one of them,
    // `mode`, was collected but never read anywhere in this file — the
    // browser mode drives ViewModel behavior, not this screen) plus 2 more
    // inside the overview branch. The locals below keep the body unchanged:
    // every read that used to be its own subscription is now a field of
    // [state]. Children still receive individual fields, so an unchanged
    // child skips instead of recomposing because some other flow moved.
    val state by viewModel.screenState.collectAsState()
    // 2026-11-13 unified home architecture: Home and Root Home are resolved
    // from the workspace's passwd identity, not from "/root" and not from the
    // Android process environment. This separate one-time subscription cannot
    // paint a wrong path: the flow starts at the normal-user default and only
    // changes once the resolved account arrives.
    val homes by viewModel.homes.collectAsState()
    val uiState = state.uiState
    val currentPath = state.currentPath
    val androidStorage = state.androidStorage
    var androidPlaces by androidx.compose.runtime.remember { mutableStateOf<List<com.fem.linexa.ui.screens.filemanager.FileManagerViewModel.AndroidPlace>>(emptyList()) }
    val showOverview = state.showOverview
    // The overview owns the Android folder list and the StatFs reading: load
    // both whenever it is shown (they were previously only loaded after the
    // user had already switched to Android mode — i.e. never visible here).
    LaunchedEffect(showOverview) {
        if (showOverview) {
            androidPlaces = viewModel.androidPlaces()
            viewModel.refreshAndroidStorage()
        }
    }
    val files = state.files
    val visibleFiles = state.visibleFiles
    // Entrance motion for rows that appear within one directory. The scope is
    // the current path: navigating folders swaps the whole list with no
    // enter/leave (that is navigation, not an edit of the collection).
    val fileMotions = com.fem.linexa.ui.components.haven.LocalHavenMotions.current
    val fileEntering = rememberEnteringKeys(visibleFiles, scope = currentPath) { it.path }
    val searchQuery = state.searchQuery
    val sort = state.sort
    val breadcrumbs = state.breadcrumbs
    val operationMessage = state.operationMessage
    val viewingFile = state.viewingFile
    val fileContent = state.fileContent
    val pendingTransfer = state.pendingTransfer
    // Issue #9 (2026-09-03): selection mode, editor and inspection state.
    val selectedPaths = state.selectedPaths
    val editorState = state.editorState
    val inspectedFile = state.inspectedFile
    val pendingFirst = pendingTransfer?.first
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<FileInfo?>(null) }
    var showRenameDialog by remember { mutableStateOf<FileInfo?>(null) }
    // Issue #10 (2026-09-04): ZArchiver-style interaction — long-pressing an
    // entry opens THIS bottom action sheet instead of per-row action buttons.
    var actionSheetFile by remember { mutableStateOf<FileInfo?>(null) }
    // §1.115: the entry whose export destination is being picked. SAF returns
    // only a Uri, so the target has to be remembered across the launcher.
    var exportTarget by remember { mutableStateOf<FileInfo?>(null) }
    // HAVEN: the kit has no snackbar host, so the operation message renders as
    // an animated toast pill; [toastLine] keeps the text visible during the
    // exit animation after the ViewModel clears the message.
    var toastLine by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else "imported_file"
            } ?: "imported_file"
            viewModel.importFile(uri, fileName, context.contentResolver)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            viewModel.exportFile(target, uri, context.contentResolver)
        }
    }

    LaunchedEffect(operationMessage) {
        val message = operationMessage
        if (message != null) {
            toastLine = message
            delay(2600)
            viewModel.clearMessage()
        }
    }

    // §1.115 + Phase 7 consistency pass: system back must agree with the header
    // back circle. Before this BackHandler, system back bypassed closeFileViewer,
    // popped the FILE_MANAGER entry, and the next re-entry landed back inside the
    // file viewer because viewingFile was still set (the ViewModel is scoped to the
    // back-stack entry). Now: back closes the viewer if open, else falls through to
    // the NavController pop (which is what onBack does for this screen).
    // Issue #9: back unwinds editor first, then the viewer, then selection.
    var showEditorDiscardDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = editorState != null) {
        if (editorState?.isDirty == true) {
            // Discarding unsaved edits silently would be data loss; confirm.
            showEditorDiscardDialog = true
        } else {
            viewModel.closeEditor()
        }
    }
    BackHandler(enabled = editorState == null && viewingFile != null) {
        viewModel.closeFileViewer()
    }
    BackHandler(enabled = editorState == null && viewingFile == null && viewModel.selectionActive) {
        viewModel.clearSelection()
    }

    // Hierarchical back: inside a listing, back goes UP a directory and, from
    // a storage root, returns to the overview — only the overview leaves the screen.
    BackHandler(
        enabled = editorState == null && viewingFile == null &&
            !viewModel.selectionActive && !showOverview
    ) {
        viewModel.navigateUp()
    }

    HavenScreen {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                if (editorState != null) {
                    // Issue #9: the text editor is a full-screen layer with
                    // its own header (dirty indicator + save), rendered ahead
                    // of both the viewer and the browser.
                    FileEditor(
                        state = editorState!!,
                        onTextChange = { viewModel.updateEditorText(it) },
                        onSave = { viewModel.saveEditor() },
                        onRequestClose = {
                            if (editorState?.isDirty == true) showEditorDiscardDialog = true
                            else viewModel.closeEditor()
                        }
                    )
                } else if (viewingFile != null) {
                    val viewerPath = viewingFile!!
                    // The subtitle is the mono `path · size` line: the size is
                    // derived from the loaded content, so it appears only once
                    // the text is actually in memory.
                    val viewerSubtitle = remember(viewerPath, fileContent) {
                        buildString {
                            append(viewerPath)
                            fileContent?.let {
                                append(" · ")
                                append(FileSizeFormatter.format(it.toByteArray().size.toLong()))
                            }
                        }
                    }
                    val clipLabel = stringResource(R.string.file_manager_clip_label)
                    HSubHeader(
                        title = viewerPath.substringAfterLast('/'),
                        subtitle = viewerSubtitle,
                        subtitleMono = true,
                        onBack = { viewModel.closeFileViewer() },
                        backContentDescription = stringResource(R.string.common_back),
                        trailing = {
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_copy,
                                contentDescription = stringResource(R.string.file_manager_copy),
                                enabled = fileContent != null,
                                onClick = {
                                    fileContent?.let { content ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, content))
                                        Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    )
                    FileViewer(
                        path = viewerPath,
                        content = fileContent,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    )
                } else {
                    val transfer = pendingTransfer
                    val pendingSubtitle = when {
                        transfer == null -> workspaceName
                        transfer.items.size == 1 -> transfer.first?.name ?: workspaceName
                        else -> pluralStringResource(
                            R.plurals.file_manager_item_count,
                            transfer.items.size,
                            transfer.items.size
                        )
                    }
                    HSubHeader(
                        title = stringResource(R.string.file_manager_title),
                        subtitle = pendingSubtitle,
                        onBack = { if (showOverview) onBack() else viewModel.navigateUp() },
                        backContentDescription = stringResource(R.string.common_back),
                        trailing = {
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_refresh,
                                contentDescription = stringResource(R.string.common_refresh),
                                onClick = {
                                    if (!showOverview) viewModel.navigateTo(currentPath, force = true)
                                    // App-grid redesign: refresh also re-reads
                                    // the storage card — one action, whole view.
                                    viewModel.refreshStorage()
                                    viewModel.refreshAndroidStorage()
                                }
                            )
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_download,
                                contentDescription = stringResource(R.string.file_manager_import),
                                onClick = { importLauncher.launch(arrayOf("*/*")) }
                            )
                            // §1.115: paste is enabled + accent-tinted only when
                            // something is pending, so the action's look tells the
                            // user the clipboard has content rather than being a
                            // dead icon.
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_clipboard,
                                contentDescription = stringResource(R.string.file_manager_paste),
                                accent = pendingTransfer != null,
                                enabled = pendingTransfer != null,
                                onClick = { viewModel.pasteInto(currentPath) }
                            )
                        }
                    )

                    // Concept `.crumbs` — the current crumb carries the primary
                    // tint; display labels keep the §-era "/" root convention.
                    if (!showOverview) {
                        val crumbLabels = breadcrumbs.mapIndexed { index, crumb ->
                            if (index == 0) "/" else crumb.substringAfterLast('/')
                        }
                        HCrumbs(
                            crumbs = crumbLabels,
                            current = breadcrumbs.lastIndex,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                            onCrumbClick = { index -> viewModel.navigateTo(breadcrumbs[index]) }
                        )
                        // FM-1: search + sort are browse-mode affordances. The
                        // folder identity above (HSubHeader + HCrumbs) stays
                        // visible, so the scope is never ambiguous.
                        if (files.isNotEmpty() || searchQuery.isNotEmpty()) {
                            BrowseToolbar(
                                query = searchQuery,
                                sort = sort,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onCycleSort = viewModel::cycleSort,
                                onToggleDirection = viewModel::toggleSortDirection
                            )
                        }
                    } else {
                        // The overview: Android storage first, the Linux
                        // workspace second — compact, scrollable, never a
                        // fixed-height panel stack.
                        // Phase 08: these two used to be collected HERE (a
                        // branch-local subscription that still invalidated the
                        // whole screen function); they are now fields of the
                        // consolidated [state] — no second subscription slot,
                        // and the browse branch has no storage reads at all.
                        FileManagerOverview(
                            androidStorage = androidStorage,
                            androidPlaces = androidPlaces,
                            homes = homes,
                            workspaceStorage = state.storage,
                            workspaceStorageLoading = state.storageLoading,
                            onOpenAndroid = { viewModel.openAndroidStorage(it) },
                            onOpenLinux = { viewModel.openLinuxPlace(it) },
                            onGrantAccess = {
                                val pkg = android.net.Uri.parse("package:${context.packageName}")
                                try {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            pkg
                                        )
                                    )
                                } catch (e: Exception) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                            )
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // `ui` (not `state`): the screen-level `state` is the
                    // Phase 08 consolidated snapshot; this subject narrows it
                    // to the browser's own sealed uiState.
                    when (val ui = if (showOverview) null else uiState) {
                        null -> Unit

                        is FileManagerUiState.Unbound -> HLoading(
                            fill = true,
                            label = stringResource(R.string.file_manager_unbound)
                        )

                        is FileManagerUiState.Loading -> HLoading(fill = true)

                        // §1.115: a failed listing is no longer indistinguishable
                        // from an empty directory — the guest's reason is shown.
                        is FileManagerUiState.Error -> HError(title = ui.message, fill = true)

                        is FileManagerUiState.Ready -> when {
                            files.isEmpty() -> HEmpty(
                                title = stringResource(R.string.file_manager_empty),
                                iconRes = R.drawable.ic_haven_folder,
                                fill = true
                            )

                            // A search that matches nothing is a different
                            // dead end from an empty folder: name the search
                            // and offer the one action that resolves it.
                            visibleFiles.isEmpty() && searchQuery.isNotBlank() -> HEmpty(
                                title = stringResource(R.string.fm_no_results_title),
                                description = stringResource(R.string.fm_no_results_description),
                                iconRes = R.drawable.ic_haven_folder,
                                actionLabel = stringResource(R.string.fm_no_results_clear),
                                onAction = viewModel::clearSearch,
                                fill = true
                            )

                            // The listing changed and the off-thread pass has
                            // not published yet; never flash "no results" for
                            // an empty query.
                            visibleFiles.isEmpty() -> HLoading(fill = true)

                            else -> {
                                // Concept `.list`: one tight card, hairline-separated
                                // rows. The card owns the remaining height and the
                                // LazyColumn scrolls inside it, so the 20dp radius
                                // clips the rows on both ends while items stay lazy.
                                HCard(
                                    tight = true,
                                    modifier = Modifier
                                        .padding(start = 20.dp, end = 20.dp)
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        // Clears the floating New Folder pill.
                                        contentPadding = PaddingValues(bottom = 120.dp)
                                    ) {
                                        itemsIndexed(visibleFiles, key = { _, item -> item.path }) { index, fileInfo ->
                                          // Placement + fade-in for rows that
                                          // appear (search typing, create,
                                          // paste). Hairline rows fade only.
                                          Column(
                                            modifier = Modifier
                                                .animateItemPlacement(animationSpec = fileMotions.enterSpec())
                                                .motionItem(
                                                    phase = if (fileEntering.containsKey(fileInfo.path)) ItemPhase.ENTERING else ItemPhase.PRESENT,
                                                    enterOrdinal = fileEntering[fileInfo.path] ?: 0,
                                                    scaleFrom = 1f,
                                                    liftFrom = 0.dp
                                                )
                                          ) {
                                            FileRow(
                                                fileInfo = fileInfo,
                                                selected = fileInfo.path in selectedPaths,
                                                selectionMode = selectedPaths.isNotEmpty(),
                                                onOpen = {
                                                    if (selectedPaths.isNotEmpty()) {
                                                        viewModel.toggleSelection(fileInfo)
                                                    } else if (fileInfo.isDirectory) {
                                                        viewModel.navigateTo(fileInfo.path)
                                                    } else if (FileTypes.isEditable(fileInfo.name)) {
                                                        // Issue #9: text/code files open
                                                        // straight into the editor.
                                                        viewModel.editFile(fileInfo)
                                                    } else {
                                                        viewModel.viewFile(fileInfo)
                                                    }
                                                },
                                                // Issue #10: long-press opens the bottom
                                                // action sheet (ZArchiver-like) — every
                                                // operation lives there, the row itself
                                                // stays clean.
                                                onLongPress = { actionSheetFile = fileInfo }
                                            )
                                            if (index != visibleFiles.lastIndex) {
                                                HHairline()
                                            }
                                          }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (editorState == null && viewingFile == null && !showOverview) {
                if (selectedPaths.isNotEmpty()) {
                    // Issue #9: selection action bar replaces the create pills.
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(HavenTheme.colors.surface2)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.file_manager_selection_title, selectedPaths.size),
                            style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                            color = HavenTheme.colors.text,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        HIconGhost(
                            iconRes = R.drawable.ic_haven_copy,
                            contentDescription = stringResource(R.string.file_manager_copy),
                            onClick = { viewModel.copySelectedToClipboard() }
                        )
                        HIconGhost(
                            iconRes = R.drawable.ic_haven_clipboard,
                            contentDescription = stringResource(R.string.file_manager_cut),
                            onClick = { viewModel.cutSelectedToClipboard() }
                        )
                        HIconGhost(
                            iconRes = R.drawable.ic_haven_trash,
                            contentDescription = stringResource(R.string.file_manager_delete_selected),
                            tint = HavenTheme.colors.red,
                            onClick = { viewModel.deleteSelected() }
                        )
                        HIconGhost(
                            iconRes = R.drawable.ic_haven_arrow_back,
                            contentDescription = stringResource(R.string.file_manager_selection_cancel),
                            onClick = { viewModel.clearSelection() }
                        )
                    }
                } else {
                    // Issue #10 (2026-09-04): ONE plus button instead of two
                    // floating pills. Tapping it expands two labeled options
                    // above the button (New Folder / New File) and rotates the
                    // glyph into a close — the ZArchiver "single add entry"
                    // pattern, in HAVEN surfaces.
                    var createMenuOpen by remember { mutableStateOf(false) }
                    val plusRotation by animateFloatAsState(
                        targetValue = if (createMenuOpen) 45f else 0f,
                        animationSpec = fileMotions.selectionSpec(),
                        label = "fileManagerFabPlus"
                    )
                    // While the create menu is open, taps outside the options
                    // dismiss it instead of falling through to the rows (or
                    // header actions) underneath — the deterministic close a
                    // floating menu owes. The scrim only exists while the
                    // menu is open, so list scrolling is untouched otherwise,
                    // and it composes BEFORE the pill column so the menu and
                    // button stay on top of it.
                    if (createMenuOpen) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { createMenuOpen = false }
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedVisibility(
                            visible = createMenuOpen,
                            enter = fadeIn(fileMotions.enter) +
                                expandVertically(fileMotions.enterSpec()),
                            exit = fadeOut(fileMotions.exit) +
                                shrinkVertically(fileMotions.exitSpec())
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(HavenTheme.colors.surface2)
                                        .clickable {
                                            createMenuOpen = false
                                            showCreateFolderDialog = true
                                        }
                                        .padding(horizontal = 22.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_haven_folder),
                                        contentDescription = null,
                                        tint = HavenTheme.colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.file_manager_new_folder),
                                        style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = HavenTheme.colors.text,
                                        maxLines = 2
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(HavenTheme.colors.surface2)
                                        .clickable {
                                            createMenuOpen = false
                                            showCreateFileDialog = true
                                        }
                                        .padding(horizontal = 22.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_haven_file),
                                        contentDescription = null,
                                        tint = HavenTheme.colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.file_manager_new_file),
                                        style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = HavenTheme.colors.text,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(50))
                                .background(HavenTheme.colors.primary)
                                .clickable { createMenuOpen = !createMenuOpen },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_haven_add),
                                contentDescription = stringResource(R.string.file_manager_add),
                                tint = HavenTheme.colors.onPrimary,
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(plusRotation)
                            )
                        }
                    }
                }
            }

            HavenToastPill(
                line = toastLine,
                visible = operationMessage != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp)
            )
        }
    }

    if (showCreateFolderDialog) {
        // HAVEN: the LinexaInputDialog is replaced by the design-system dialog +
        // field pair; the confirm button stays enabled iff the text is non-blank.
        var folderName by remember { mutableStateOf("") }
        HDialog(
            title = stringResource(R.string.file_manager_new_folder),
            onDismiss = { showCreateFolderDialog = false },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showCreateFolderDialog = false },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_create),
                    onClick = {
                        viewModel.createFolder(folderName)
                        showCreateFolderDialog = false
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL,
                    enabled = folderName.isNotBlank()
                )
            },
            content = {
                HField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = stringResource(R.string.file_manager_folder_name_hint)
                )
            }
        )
    }

    showDeleteDialog?.let { fileInfo ->
        HDialog(
            title = stringResource(R.string.file_manager_delete_title),
            body = stringResource(R.string.file_manager_delete_message, fileInfo.name),
            onDismiss = { showDeleteDialog = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showDeleteDialog = null },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_delete),
                    onClick = {
                        viewModel.deleteItem(fileInfo)
                        showDeleteDialog = null
                    },
                    variant = HavenButtonVariant.DANGER,
                    size = HavenButtonSize.SMALL
                )
            }
        )
    }

    showRenameDialog?.let { fileInfo ->
        // Renaming a file to itself is a no-op the user already sees — the
        // confirm button is visibly disabled (same predicate as the old input
        // dialog's isValid).
        var newName by remember(fileInfo.path) { mutableStateOf(fileInfo.name) }
        HDialog(
            title = stringResource(R.string.file_manager_rename),
            onDismiss = { showRenameDialog = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showRenameDialog = null },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_rename),
                    onClick = {
                        viewModel.renameItem(fileInfo.name, newName)
                        showRenameDialog = null
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL,
                    enabled = newName.isNotBlank() && newName != fileInfo.name
                )
            },
            content = {
                HField(
                    value = newName,
                    onValueChange = { newName = it }
                )
            }
        )
    }

    if (showCreateFileDialog) {
        // Issue #9: name + template preset. The template only seeds content;
        // the user keeps full control of the name (they may type their own
        // extension, in which case the template's extension is not appended).
        var fileName by remember { mutableStateOf("") }
        var template by remember { mutableStateOf<FileTypes.FileTemplate?>(null) }
        var templateMenu by remember { mutableStateOf(false) }
        HDialog(
            title = stringResource(R.string.file_manager_new_file),
            onDismiss = { showCreateFileDialog = false },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showCreateFileDialog = false },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_create),
                    onClick = {
                        val name = fileName.trim()
                        val chosen = template
                        // Append the template's extension when the user typed
                        // a bare name — `script` + Shell script → script.sh.
                        val finalName = if (chosen != null && name.isNotEmpty() &&
                            !name.contains('.') && chosen.extension.isNotEmpty()
                        ) {
                            "$name.${chosen.extension}"
                        } else name
                        viewModel.createFile(finalName, template)
                        showCreateFileDialog = false
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL,
                    enabled = fileName.isNotBlank() && !fileName.contains('/')
                )
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        placeholder = stringResource(R.string.file_manager_file_name_hint)
                    )
                    Box {
                        HRow(
                            title = template?.let { stringResource(it.labelRes) }
                                ?: stringResource(R.string.file_manager_template_none),
                            description = stringResource(R.string.file_manager_template),
                            onClick = { templateMenu = true },
                            trailing = { HChevron() }
                        )
                        HMenu(
                            expanded = templateMenu,
                            onDismiss = { templateMenu = false },
                            items = buildList {
                                add(
                                    HMenuItem(
                                        label = stringResource(R.string.file_manager_template_none),
                                        iconRes = R.drawable.ic_haven_file,
                                        onClick = {
                                            template = null
                                            templateMenu = false
                                        }
                                    )
                                )
                                FileTypes.templates.forEach { preset ->
                                    add(
                                        HMenuItem(
                                            label = "${stringResource(preset.labelRes)} (.${preset.extension})",
                                            iconRes = R.drawable.ic_haven_file,
                                            onClick = {
                                                template = preset
                                                templateMenu = false
                                            }
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        )
    }

    // Issue #10 (2026-09-04) BUGFIX: this was `showEditorDiscardDialog?.let { … }`
    // against a NON-nullable Boolean — the safe call never short-circuits on a
    // non-null receiver, so the dialog block ran on EVERY composition: the
    // "Modified / Discard unsaved changes?" dialog appeared the moment the
    // Files screen opened, and neither button could dismiss it (both only
    // flipped the flag). Plain `if` restores the intended conditional.
    if (showEditorDiscardDialog) {
        HDialog(
            title = stringResource(R.string.file_manager_editor_modified),
            body = stringResource(R.string.file_manager_editor_discard_body),
            onDismiss = { showEditorDiscardDialog = false },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showEditorDiscardDialog = false },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_delete),
                    onClick = {
                        showEditorDiscardDialog = false
                        viewModel.closeEditor()
                    },
                    variant = HavenButtonVariant.DANGER,
                    size = HavenButtonSize.SMALL
                )
            }
        )
    }

    // Issue #10 (2026-09-04): the ZArchiver-style bottom action sheet. Every
    // per-entry operation lives here (open/edit/copy/move/rename/properties/
    // export/select/delete) instead of as crowded buttons on each row; the
    // sheet titles itself with the entry's name so the target is unambiguous.
    actionSheetFile?.let { target ->
        HSheet(
            title = target.name,
            subtitle = target.path,
            onDismiss = { actionSheetFile = null },
            // The per-entry action list can exceed the screen at a large font
            // scale with wrapped Persian labels; a partially-expanded sheet
            // makes its body scroll so every action stays reachable.
            skipPartiallyExpanded = false
        ) {
            Column(
                modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SheetAction(
                    label = stringResource(R.string.common_open),
                    iconRes = R.drawable.ic_haven_ext,
                    onClick = {
                        actionSheetFile = null
                        if (target.isDirectory) {
                            viewModel.navigateTo(target.path)
                        } else if (FileTypes.isEditable(target.name)) {
                            viewModel.editFile(target)
                        } else {
                            viewModel.viewFile(target)
                        }
                    }
                )
                if (!target.isDirectory) {
                    SheetAction(
                        label = stringResource(R.string.file_manager_edit),
                        iconRes = R.drawable.ic_haven_edit,
                        onClick = {
                            actionSheetFile = null
                            viewModel.editFile(target)
                        }
                    )
                }
                SheetAction(
                    label = stringResource(R.string.file_manager_copy),
                    iconRes = R.drawable.ic_haven_copy,
                    onClick = {
                        actionSheetFile = null
                        viewModel.copyToClipboard(target)
                    }
                )
                SheetAction(
                    label = stringResource(R.string.file_manager_cut),
                    iconRes = R.drawable.ic_haven_clipboard,
                    onClick = {
                        actionSheetFile = null
                        viewModel.cutToClipboard(target)
                    }
                )
                SheetAction(
                    label = stringResource(R.string.file_manager_rename),
                    iconRes = R.drawable.ic_haven_edit,
                    onClick = {
                        actionSheetFile = null
                        showRenameDialog = target
                    }
                )
                SheetAction(
                    label = stringResource(R.string.file_manager_info),
                    iconRes = R.drawable.ic_haven_info,
                    onClick = {
                        actionSheetFile = null
                        viewModel.inspectFile(target)
                    }
                )
                if (!target.isDirectory) {
                    SheetAction(
                        label = stringResource(R.string.file_manager_export),
                        iconRes = R.drawable.ic_haven_download,
                        onClick = {
                            actionSheetFile = null
                            exportTarget = target
                            exportLauncher.launch(target.name)
                        }
                    )
                }
                SheetAction(
                    label = stringResource(R.string.file_manager_select),
                    iconRes = R.drawable.ic_haven_check,
                    onClick = {
                        actionSheetFile = null
                        viewModel.toggleSelection(target)
                    }
                )
                SheetAction(
                    label = stringResource(R.string.common_delete),
                    iconRes = R.drawable.ic_haven_trash,
                    danger = true,
                    onClick = {
                        actionSheetFile = null
                        showDeleteDialog = target
                    }
                )
            }
        }
    }

    inspectedFile?.let { info ->
        // Issue #9: the file-properties dialog — the metadata a file manager
        // owes the user (type/size/permissions/mtime/symlink target).
        HDialog(
            title = info.name,
            onDismiss = { viewModel.closeInspector() },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { viewModel.closeInspector() },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileInfoLine(stringResource(R.string.file_manager_info_path), info.path)
                    FileInfoLine(
                        stringResource(R.string.file_manager_info_type),
                        when {
                            info.isDirectory -> stringResource(R.string.file_manager_type_directory)
                            info.isSymlink -> stringResource(R.string.file_manager_type_symlink)
                            else -> info.name.substringAfterLast('.', missingDelimiterValue = "")
                                .ifEmpty { stringResource(R.string.file_manager_type_file) }
                        }
                    )
                    if (!info.isDirectory && info.hasByteSize) {
                        FileInfoLine(stringResource(R.string.file_manager_info_size), formatFileSize(info.size))
                    }
                    FileInfoLine(stringResource(R.string.file_manager_info_permissions), info.permissions)
                    FileInfoLine(stringResource(R.string.file_manager_info_modified), info.lastModified)
                    if (info.isSymlink && info.linkTarget != null) {
                        FileInfoLine(stringResource(R.string.file_manager_info_target), info.linkTarget)
                    }
                }
            }
        )
    }
}

/** One mono label/value line of the properties dialog. */
@Composable
private fun FileInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = HavenType.small,
            color = HavenTheme.colors.muted,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = HavenType.descMono,
            color = HavenTheme.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One compact destination tile (icon, title, optional mono path). */
private data class OverviewTile(
    val title: String,
    val subtitle: String?,
    val iconRes: Int,
    val path: String
)

/**
 * FM-1 — the browse toolbar: full-width search, then the current sort and its
 * direction. It follows §22.7's list-toolbar contract — search above the chip
 * row, one sort chip naming the CURRENT order, right-aligned — and Home's
 * `HomeListToolbar` idiom. File Manager has no filter chips, so the row holds
 * only the sort control (and, when it applies, the direction control).
 *
 * The direction chip is hidden under Native because Native has no direction:
 * it is the source's own order. Cycling the sort chip reaches Name and then
 * the arrow appears. The arrow sits to the LEFT of the sort chip so the sort
 * chip itself stays at the trailing edge, as the contract specifies.
 */
@Composable
private fun BrowseToolbar(
    query: String,
    sort: FileListSort,
    onQueryChange: (String) -> Unit,
    onCycleSort: () -> Unit,
    onToggleDirection: () -> Unit
) {
    val criterion = stringResource(sort.criterion.labelRes())
    val direction = stringResource(
        if (sort.direction == FileSortDirection.ASCENDING) {
            R.string.fm_sort_ascending
        } else {
            R.string.fm_sort_descending
        }
    )
    // Search is an ICON in the toolbar that morphs into the field (the sort
    // chips fade out underneath). An already-filtered folder (query kept by the
    // ViewModel across rotation) re-enters in the active state.
    var searchActive by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(query.isNotBlank())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp)
    ) {
        com.fem.linexa.ui.components.haven.HSearchMorphBar(
            active = searchActive,
            onActiveChange = { searchActive = it },
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.fm_search_hint)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                if (sort.criterion != FileSortCriterion.NATIVE) {
                    HChip(
                        label = if (sort.direction == FileSortDirection.ASCENDING) "↑" else "↓",
                        selected = false,
                        onClick = onToggleDirection,
                        modifier = Modifier.semantics { contentDescription = direction }
                    )
                }
                HChip(
                    label = stringResource(R.string.home_sort_label, criterion),
                    selected = sort.criterion != FileSortCriterion.NATIVE,
                    onClick = onCycleSort
                )
            }
        }
    }
}

private fun FileSortCriterion.labelRes(): Int = when (this) {
    FileSortCriterion.NATIVE -> R.string.fm_sort_native
    FileSortCriterion.NAME -> R.string.fm_sort_name
    FileSortCriterion.SIZE -> R.string.fm_sort_size
    FileSortCriterion.MODIFIED -> R.string.fm_sort_modified
    FileSortCriterion.TYPE -> R.string.fm_sort_type
}

/**
 * The File Manager overview (home) — two clearly separated storage sources:
 *
 *  A. ANDROID STORAGE (primary): a compact capacity card (measured with
 *     StatFs, shown only when All-files access is granted) and the standard
 *     folders that actually exist on this device.
 *  B. LINUX WORKSPACE (secondary): the workspace storage card and the
 *     guaranteed guest locations.
 *
 * Every tap leaves the overview and opens a real listing. Android folders are
 * listed host-side; Linux locations go through the runtime binder (PRoot's
 * view), so the two filesystems are never conflated.
 */
@Composable
private fun FileManagerOverview(
    androidStorage: FileManagerViewModel.AndroidStorage?,
    androidPlaces: List<FileManagerViewModel.AndroidPlace>,
    homes: WorkspaceHomeLayout.WorkspaceHomes,
    workspaceStorage: WorkspaceStorage?,
    workspaceStorageLoading: Boolean,
    onOpenAndroid: (String?) -> Unit,
    onOpenLinux: (String) -> Unit,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val granted = androidStorage?.accessGranted == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        // ── A. Android storage ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val used = androidStorage?.usedBytes
            val total = androidStorage?.totalBytes
            StorageSummaryCard(
                title = stringResource(R.string.fm_android_title),
                caption = if (granted && used != null) {
                    stringResource(
                        R.string.fm_storage_used_of_free,
                        FileSizeFormatter.format(used),
                        FileSizeFormatter.format(androidStorage?.availableBytes ?: 0L)
                    )
                } else {
                    stringResource(R.string.fm_android_access_hint)
                },
                trailing = if (granted && total != null) FileSizeFormatter.format(total) else null,
                fraction = if (granted) androidStorage?.fraction else null,
                loading = false,
                onClick = if (granted) ({ onOpenAndroid(null) }) else null
            )
            if (!granted) {
                HButton(
                    label = stringResource(R.string.fm_android_grant),
                    onClick = onGrantAccess,
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
            } else {
                val standard = listOf("Download", "Movies", "Music", "Pictures", "Documents", "DCIM")
                val tiles = standard.mapNotNull { label ->
                    androidPlaces.firstOrNull { it.label == label }?.let { place ->
                        OverviewTile(
                            title = when (label) {
                                "Download" -> stringResource(R.string.fm_dir_downloads)
                                "Movies" -> stringResource(R.string.fm_dir_movies)
                                "Music" -> stringResource(R.string.fm_dir_music)
                                "Pictures" -> stringResource(R.string.fm_dir_pictures)
                                "Documents" -> stringResource(R.string.fm_dir_documents)
                                else -> stringResource(R.string.fm_dir_dcim)
                            },
                            subtitle = null,
                            iconRes = when (label) {
                                "Download" -> R.drawable.ic_haven_download
                                "Movies" -> R.drawable.ic_haven_play
                                "Music" -> R.drawable.ic_haven_audio
                                "Pictures" -> R.drawable.ic_haven_grid
                                "Documents" -> R.drawable.ic_haven_file
                                else -> R.drawable.ic_haven_monitor
                            },
                            path = place.path
                        )
                    }
                }
                TileGrid(tiles = tiles, onClick = { onOpenAndroid(it.path) })
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── B. Linux workspace ──
        Spacer(modifier = Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSummaryCard(
                title = stringResource(R.string.fm_storage_title),
                caption = workspaceStorage?.let { reading ->
                    reading.freeLabel?.let {
                        stringResource(R.string.fm_storage_used_of_free, reading.usedLabel, it)
                    } ?: stringResource(R.string.fm_storage_used_only, reading.usedLabel)
                } ?: stringResource(R.string.fm_storage_caption),
                trailing = null,
                fraction = workspaceStorage?.fraction,
                loading = workspaceStorageLoading && workspaceStorage == null,
                onClick = { onOpenLinux("/") }
            )
            // Home follows the SELECTED Workspace identity's passwd record.
            // A normal-user workspace also gets a separate Root Home place,
            // so "/root" stays reachable without being called "Home".
            val tiles = buildList {
                add(OverviewTile(stringResource(R.string.fm_place_root), "/", R.drawable.ic_haven_db, "/"))
                add(
                    OverviewTile(
                        stringResource(R.string.fm_place_home),
                        homes.userHome,
                        R.drawable.ic_haven_home,
                        homes.userHome
                    )
                )
                if (homes.showRootHomeShortcut) {
                    add(
                        OverviewTile(
                            stringResource(R.string.fm_place_root_home),
                            homes.rootHome,
                            R.drawable.ic_haven_lock,
                            homes.rootHome
                        )
                    )
                }
                add(OverviewTile(stringResource(R.string.fm_place_tmp), "/tmp", R.drawable.ic_haven_clock, "/tmp"))
                add(OverviewTile(stringResource(R.string.fm_place_etc), "/etc", R.drawable.ic_haven_sliders, "/etc"))
                add(OverviewTile(stringResource(R.string.fm_place_opt), "/opt", R.drawable.ic_haven_box, "/opt"))
                add(OverviewTile(stringResource(R.string.fm_place_usr), "/usr", R.drawable.ic_haven_folder, "/usr"))
            }
            TileGrid(tiles = tiles, onClick = { onOpenLinux(it.path) })
        }
    }
}

/** A compact storage card: icon, title, one-line caption, optional bar. */
@Composable
private fun StorageSummaryCard(
    title: String,
    caption: String,
    trailing: String?,
    fraction: Float?,
    loading: Boolean,
    onClick: (() -> Unit)?
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.tint)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_haven_storage),
                contentDescription = null,
                tint = h.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HavenType.rowTitle,
                    color = h.text,
                    maxLines = 2
                )
                Text(
                    text = caption,
                    style = HavenType.small,
                    color = h.text2
                )
            }
            if (loading) {
                HSpinner(small = true)
            } else if (trailing != null) {
                Text(text = trailing, style = HavenType.num14, color = h.text2)
            }
            if (onClick != null) {
                Spacer(modifier = Modifier.width(6.dp))
                HChevron()
            }
        }
        if (fraction != null) {
            Spacer(modifier = Modifier.height(10.dp))
            HStorageBar(fraction = fraction, color = h.primary)
        }
    }
}

/** Two-across grid of compact destination tiles (a short list never needs lazy). */
@Composable
private fun TileGrid(tiles: List<OverviewTile>, onClick: (OverviewTile) -> Unit) {
    val h = HavenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(h.surface)
                            .clickable { onClick(tile) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(h.tint),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(tile.iconRes),
                                contentDescription = null,
                                tint = h.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tile.title,
                                style = HavenType.rowTitle,
                                color = h.text,
                                maxLines = 2
                            )
                            if (tile.subtitle != null) {
                                Text(
                                    text = tile.subtitle,
                                    style = HavenType.descMono,
                                    color = h.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** HAVEN toast — an animated pill at the bottom of the screen. */
@Composable
private fun HavenToastPill(
    line: String?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val motions = com.fem.linexa.ui.components.haven.LocalHavenMotions.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motions.fade) + slideInVertically(motions.segment) { it / 2 },
        exit = fadeOut(motions.fade) + slideOutVertically(motions.segment) { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(HavenTheme.colors.toast)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = line.orEmpty(),
                style = HavenType.small,
                color = HavenTheme.colors.onToast,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One entry of the file list (concept `.row`). Folders navigate on tap with a
 * chevron affordance; files open in the editor (editable types, issue #9) or
 * the viewer. Issue #10 (2026-09-04): the row carries NO action buttons —
 * long-pressing the entry opens the screen-level bottom action sheet
 * ([FileManagerScreen]'s `actionSheetFile`), ZArchiver-style, where every
 * operation (open/edit/copy/move/rename/properties/export/select/delete)
 * lives. In selection mode tap toggles the tick instead of opening.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    fileInfo: FileInfo,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    // §1.115: a symlink used to render as a regular file whose
    // name contained "-> /target"; the target is now its own line.
    val subtitle = when {
        fileInfo.isSymlink -> "→ ${fileInfo.linkTarget ?: "?"}"
        fileInfo.isDirectory -> null
        !fileInfo.hasByteSize -> stringResource(R.string.file_manager_device_node)
        else -> formatFileSize(fileInfo.size)
    }
    // Issue #9: the combinedClickable lives on the row modifier and HRow's own
    // onClick stays null, so long-press (action sheet) and tap (open/toggle)
    // are owned by ONE gesture handler instead of fighting the kit's internal
    // one.
    HRow(
        title = fileInfo.name,
        onClick = null,
        description = subtitle,
        descriptionMono = true,
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onLongPress
        ),
        leading = {
            if (selectionMode) {
                // Selection affordance: a tick circle in place of the icon.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) HavenTheme.colors.primary else HavenTheme.colors.surface2),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            painterResource(R.drawable.ic_haven_check),
                            contentDescription = stringResource(R.string.file_manager_select),
                            tint = HavenTheme.colors.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                HRic(
                    iconRes = if (fileInfo.isDirectory) R.drawable.ic_haven_folder else R.drawable.ic_haven_file,
                    tinted = fileInfo.isDirectory
                )
            }
        },
        trailing = {
            // Issue #10: no per-row buttons — a directory keeps only the
            // navigation chevron; everything else moved to the long-press
            // action sheet.
            if (fileInfo.isDirectory) {
                HChevron()
            }
        }
    )
}

/**
 * One full-width row of the bottom action sheet — the same visual language as
 * [HMenu] items, but stretched across the sheet so the operations read as a
 * menu "opened from the bottom" (the ZArchiver reference in issue #10).
 */
@Composable
private fun SheetAction(
    label: String,
    iconRes: Int,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (danger) h.red else h.text2,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = HavenType.body.copy(fontWeight = FontWeight.Medium),
            color = if (danger) h.red else h.text,
            maxLines = 2
        )
    }
}

@Composable
private fun FileViewer(
    path: String,
    content: String?,
    modifier: Modifier = Modifier
) {
    // Concept viewer body: one tight card holding the mono, pre-wrapped text.
    // A file's content is arbitrary length; without a scroll container
    // everything past one screen was unreachable. Monospace because
    // this views config/source files, where alignment carries meaning.
    HCard(tight = true, modifier = modifier.fillMaxWidth()) {
        if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = content,
                    style = HavenType.monoBlock,
                    color = HavenTheme.colors.text2
                )
                Spacer(modifier = Modifier.height(40.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                HSpinner()
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)

/**
 * Issue #9 (2026-09-03): the text editor — a monospace [BasicTextField] with
 * syntax-aware coloring, a dirty indicator and an explicit save. Header:
 * filename + Modified/Saved chip + save button. The buffer cap is enforced
 * by the ViewModel ([FileManagerViewModel.MAX_EDITOR_CHARS]); large or
 * binary files never reach this composable.
 *
 * The syntax highlighting recomputes on a debounced text change — an
 * immediate rehighlight on every keystroke re-annotates the whole buffer,
 * which is wasteful on long files; 250 ms after the last keystroke is
 * indistinguishable from instant for a human reader.
 */
@Composable
private fun FileEditor(
    state: FileEditorState,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onRequestClose: () -> Unit
) {
    val h = HavenTheme.colors
    // The field MUST initialize from the state that carries the loaded
    // content, not from the loading placeholder: [FileManagerViewModel.editFile]
    // emits a loading state (text empty) first and the file's content arrives
    // in a later state. Keying the remember on (path, loading) re-initializes
    // the field exactly once — the moment the content lands — so the editor
    // always shows what was read from disk. Afterwards the field is the
    // composition-local owner of selection/IME state and is never reset by
    // recomposition, by saving, or by the debounced highlighter (the
    // LaunchedEffect below only touches the rendered [highlighted] layer).
    // Keying on `path` alone left the field stuck on the empty placeholder
    // forever: the loaded content never reached the field, reopening a file
    // rendered blank, and a save then overwrote the file with just the typed
    // characters.
    var field by remember(state.path, state.loading) {
        mutableStateOf(TextFieldValue(state.text, TextRange(state.text.length)))
    }
    var highlighted by remember(state.path) { mutableStateOf(buildAnnotatedString { append(state.text) }) }
    var pending by remember(state.path, state.loading) { mutableStateOf(state.text) }

    // Debounced rehighlight: pending holds the latest text, a ticker flushes
    // it into the annotated string when the user pauses.
    LaunchedEffect(state.text) {
        pending = state.text
        delay(EDITOR_REHIGHLIGHT_MS)
        val language = FileTypes.languageOf(state.name)
        highlighted = if (language == FileTypes.Language.PLAIN) {
            buildAnnotatedString { append(state.text) }
        } else {
            buildAnnotatedString {
                append(state.text)
                SyntaxHighlighter.highlight(state.text, language).forEach { span ->
                    addStyle(
                        SpanStyle(color = spanColor(span.kind, h)),
                        span.start,
                        span.end
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HSubHeader(
            title = state.name,
            subtitle = when {
                state.loading -> null
                state.isDirty -> stringResource(R.string.file_manager_editor_modified)
                else -> stringResource(R.string.file_manager_editor_saved)
            },
            subtitleMono = false,
            // Header back mirrors the system back: the screen layer decides
            // between instant close and the discard confirmation.
            onBack = onRequestClose,
            backContentDescription = stringResource(R.string.common_back),
            trailing = {
                HIconCircle(
                    iconRes = R.drawable.ic_haven_save,
                    contentDescription = stringResource(R.string.file_manager_editor_save),
                    accent = state.isDirty,
                    enabled = state.isDirty && !state.saving && !state.loading,
                    onClick = onSave
                )
            }
        )
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HSpinner()
            }
        } else {
            HCard(
                tight = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .fillMaxWidth()
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        onTextChange(it.text)
                    },
                    readOnly = state.saving,
                    textStyle = HavenType.monoBlock.copy(color = h.text),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        // The colored string paints the syntax layer; the raw
                        // field keeps editing semantics. When the buffer was
                        // just changed (before the debounce flush) the plain
                        // text renders — colors catch up within 250 ms.
                        if (highlighted.text == field.text) {
                            Text(text = highlighted, style = HavenType.monoBlock.copy(color = h.text))
                        } else {
                            Text(text = field.text, style = HavenType.monoBlock.copy(color = h.text))
                        }
                        inner()
                    }
                )
            }
        }
    }
}

private const val EDITOR_REHIGHLIGHT_MS = 250L

/** Span kind → theme color (theme-supplied base + two fixed token hues). */
private fun spanColor(
    kind: SyntaxHighlighter.TokenKind,
    h: com.fem.linexa.ui.theme.haven.HavenColors
): androidx.compose.ui.graphics.Color = when (kind) {
    SyntaxHighlighter.TokenKind.COMMENT -> h.muted
    SyntaxHighlighter.TokenKind.STRING -> androidx.compose.ui.graphics.Color(0xFF9CCC9C)
    SyntaxHighlighter.TokenKind.NUMBER -> androidx.compose.ui.graphics.Color(0xFFB3A3E6)
    SyntaxHighlighter.TokenKind.KEYWORD -> h.primary
    SyntaxHighlighter.TokenKind.KEY -> androidx.compose.ui.graphics.Color(0xFF86B7D6)
    SyntaxHighlighter.TokenKind.TAG -> h.primary
}
