package com.fem.linexa.ui.screens.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.workspace.WorkspaceReadiness
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HDialog
import com.fem.linexa.ui.components.haven.HExpandablePanel
import com.fem.linexa.ui.components.haven.HHairline
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HMenu
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.HMenuItem
import com.fem.linexa.ui.components.haven.HMonoBlock
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HRow
import com.fem.linexa.ui.components.haven.HSearchField
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HSubHeader
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §1.53: Engineering-grade diagnostic viewer for error reports — HAVEN
 * (concept frame `fr-diag`).
 *
 * Chrome is the sub-screen header (back circle + workspace title + "Diagnostics
 * Report" subtitle + a more-circle whose Haven menu carries Save and the red
 * Delete), a row of mono metadata chips, the Haven search field, expandable
 * section cards, the Persistent Logs card, and a sticky bottom bar of Haven
 * buttons (Copy / Share / Retry). No Material scaffold, buttons, fields,
 * menus or indicators remain.
 *
 * All existing diagnostics are preserved — this viewer only adds presentation
 * and export capabilities:
 * - Vertical scrolling (full report, no truncation)
 * - Selectable text (SelectionContainer)
 * - Monospace font for command output
 * - Sticky/floating Copy, Share, Retry buttons (Save/Delete moved to the
 *   header menu so the bar never overflows on narrow screens — § P1)
 * - Expand/Collapse sections
 * - Jump-to-section navigation
 * - Search inside report
 * - Report metadata: size, timestamp, workspace id, distro name,
 *   installation stage, elapsed time
 * - Log file location display
 */
@Composable
fun DiagnosticViewerScreen(
    workspace: Workspace,
    readiness: WorkspaceReadiness,
    canRetry: Boolean,
    diagnostics: String,
    logPath: String?,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motions = LocalHavenMotions.current

    // Parse sections from the diagnostic string
    val sections = remember(diagnostics) { parseDiagnosticSections(diagnostics) }

    // Track which sections are expanded (all expanded by default)
    val expandedSections = remember {
        mutableStateListOf<Boolean>().apply {
            repeat(sections.size) { add(true) }
        }
    }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

    // Jump-to-section state
    var showJumpMenu by remember { mutableStateOf(false) }

    // Header overflow menu (Save / Delete)
    var menuExpanded by remember { mutableStateOf(false) }

    // Phase 7 consistency pass: system back must agree with the header back
    // circle. Before this BackHandler, the search overlay and jump menu were local
    // UI state with no system-back interception — system back popped the outer
    // WORKSPACE route directly, leaving the Blocked workspace the user was just
    // looking at. Now: back dismisses the jump menu (if open) then the search
    // overlay (if active) before falling through to the parent's onBack.
    BackHandler(enabled = searchActive || showJumpMenu) {
        when {
            showJumpMenu -> showJumpMenu = false
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
        }
    }

    // Metadata
    val reportSize = remember(diagnostics) { formatBytes(diagnostics.toByteArray().size.toLong()) }
    val timestamp = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    HavenScreen {
        when (readiness) {
            is WorkspaceReadiness.Installing -> InstallingState(
                progress = readiness.progress,
                label = workspace.description
            )
            is WorkspaceReadiness.Busy -> BusyState(operation = readiness.operation)
            is WorkspaceReadiness.Blocked -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    HSubHeader(
                        title = workspace.name,
                        subtitle = stringResource(R.string.workspace_diagnostics_report),
                        onBack = onBack,
                        backContentDescription = stringResource(R.string.common_back),
                        trailing = {
                            Box {
                                HIconCircle(
                                    iconRes = R.drawable.ic_haven_more_vert,
                                    contentDescription = stringResource(R.string.diagnostic_actions),
                                    onClick = { menuExpanded = true }
                                )
                                HMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    items = listOf(
                                        HMenuItem(
                                            label = stringResource(R.string.common_save),
                                            iconRes = R.drawable.ic_haven_save,
                                            onClick = {
                                                scope.launch {
                                                    saveDiagnosticsToFile(context, diagnostics, workspace.name, logPath)
                                                }
                                            }
                                        ),
                                        HMenuItem(
                                            label = stringResource(R.string.common_delete),
                                            iconRes = R.drawable.ic_haven_trash,
                                            danger = true,
                                            onClick = onDelete
                                        )
                                    )
                                )
                            }
                        }
                    )

                    // ── Metadata chips (mono) ──
                    DiagnosticMetadataBar(
                        workspaceId = workspace.id,
                        distroName = workspace.description,
                        timestamp = timestamp,
                        reportSize = reportSize,
                        stage = readiness.reason.take(60),
                        sectionCount = sections.size,
                        jumpShown = showJumpMenu,
                        onJumpToSection = { showJumpMenu = !showJumpMenu }
                    )

                    // ── Jump-to-section menu ──
                    AnimatedVisibility(
                        visible = showJumpMenu,
                        enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
                        exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
                    ) {
                        JumpToSectionMenu(
                            sections = sections,
                            expandedSections = expandedSections,
                            onJump = { index ->
                                showJumpMenu = false
                                // Expand the section and scroll to it
                                if (index in expandedSections.indices) {
                                    expandedSections[index] = true
                                }
                            }
                        )
                    }

                    // ── Search bar ──
                    DiagnosticSearchBar(
                        query = searchQuery,
                        onQueryChange = { query ->
                            searchQuery = query
                            // Live search: highlighting is on whenever there is
                            // a query, replacing the IME search action the old
                            // OutlinedTextField had.
                            searchActive = query.isNotEmpty()
                        },
                        onClear = { searchQuery = ""; searchActive = false }
                    )

                    // ── Diagnostic sections (scrollable) ──
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = rememberLazyListState(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sections.size) { index ->
                            DiagnosticSectionCard(
                                section = sections[index],
                                isExpanded = expandedSections[index],
                                onToggle = {
                                    expandedSections[index] = !expandedSections[index]
                                },
                                searchQuery = if (searchActive) searchQuery else null
                            )
                        }

                        // ── Log file location footer ──
                        if (logPath != null) {
                            item {
                                DiagnosticLogFooter(logPath = logPath)
                            }
                        }
                    }

                    // ── Sticky action bar ──
                    HHairline()
                    DiagnosticActionBar(
                        diagnostics = diagnostics,
                        workspaceName = workspace.name,
                        context = context,
                        scope = scope,
                        onRetry = onRetry,
                        canRetry = canRetry
                    )
                }
            }
            is WorkspaceReadiness.Ready -> {
                // Unreachable
            }
        }
    }

    if (showDeleteConfirm) {
        HDialog(
            title = stringResource(R.string.workspace_delete_title),
            body = stringResource(R.string.workspace_delete_message, workspace.name),
            onDismiss = { showDeleteConfirm = false },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { showDeleteConfirm = false },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_delete),
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    variant = HavenButtonVariant.DANGER,
                    size = HavenButtonSize.SMALL
                )
            }
        )
    }
}

// ── Section parser ──

private data class DiagnosticSection(
    val title: String,
    val content: String,
    val level: Int = 0
)

private fun parseDiagnosticSections(diagnostics: String): List<DiagnosticSection> {
    val sections = mutableListOf<DiagnosticSection>()
    val lines = diagnostics.lines()
    var currentTitle = "Report"
    val currentContent = StringBuilder()

    for (line in lines) {
        when {
            // Match === Section === headers
            line.matches(Regex("^===\\s+.+\\s+===$")) -> {
                if (currentContent.isNotEmpty()) {
                    sections.add(DiagnosticSection(currentTitle, currentContent.toString().trimEnd()))
                    currentContent.clear()
                }
                currentTitle = line.trim('=', ' ')
            }
            // Match --- Section --- headers
            line.matches(Regex("^---\\s+.+\\s+---$")) -> {
                if (currentContent.isNotEmpty()) {
                    sections.add(DiagnosticSection(currentTitle, currentContent.toString().trimEnd()))
                    currentContent.clear()
                }
                currentTitle = line.trim('-', ' ')
            }
            else -> {
                currentContent.appendLine(line)
            }
        }
    }

    // Add the last section
    if (currentContent.isNotEmpty() || currentTitle != "Report") {
        sections.add(DiagnosticSection(currentTitle, currentContent.toString().trimEnd()))
    }

    // If no sections were found, treat the whole thing as one section
    if (sections.isEmpty() && diagnostics.isNotBlank()) {
        sections.add(DiagnosticSection("Full Report", diagnostics))
    }

    return sections
}

// ── Installing / Busy states ──

@Composable
private fun InstallingState(progress: Float, label: String) {
    val h = HavenTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.workspace_installing, label),
                style = HavenType.body,
                color = h.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            HProgress(fraction = progress.coerceIn(0f, 1f), big = true)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = HavenType.num14,
                color = h.muted
            )
        }
    }
}

@Composable
private fun BusyState(operation: String) {
    val h = HavenTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HSpinner()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = operation,
                style = HavenType.body,
                color = h.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Metadata bar ──

/** Mono metadata chips: ID · size · sections · distro · stage · time. */
@Composable
private fun DiagnosticMetadataBar(
    workspaceId: String,
    distroName: String,
    timestamp: String,
    reportSize: String,
    stage: String,
    sectionCount: Int,
    jumpShown: Boolean,
    onJumpToSection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetaChip(label = stringResource(R.string.diagnostic_meta_id), value = workspaceId.take(12))
        MetaChip(label = stringResource(R.string.diagnostic_meta_size), value = reportSize)
        MetaChip(label = stringResource(R.string.diagnostic_meta_sections), value = "$sectionCount")
        MetaChip(label = stringResource(R.string.diagnostic_meta_distro), value = distroName.take(20))
        MetaChip(label = stringResource(R.string.diagnostic_meta_stage), value = stage.take(20))
        MetaChip(label = stringResource(R.string.diagnostic_meta_time), value = timestamp)
        HChip(
            label = stringResource(R.string.workspace_jump_to_section),
            selected = jumpShown,
            onClick = onJumpToSection
        )
    }
}

@Composable
private fun MetaChip(label: String, value: String) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(h.surface2)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            style = HavenType.mono12,
            color = h.muted,
            maxLines = 1
        )
        Text(
            text = value,
            style = HavenType.mono12,
            color = h.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Jump-to-section menu ──

@Composable
private fun JumpToSectionMenu(
    sections: List<DiagnosticSection>,
    expandedSections: MutableList<Boolean>,
    onJump: (Int) -> Unit
) {
    HCard(
        tight = true,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
    ) {
        sections.forEachIndexed { index, section ->
            HRow(
                title = section.title,
                onClick = { onJump(index) },
                minRow = 46.dp,
                trailing = { HChevron(expanded = expandedSections.getOrNull(index) == true) }
            )
            if (index != sections.lastIndex) {
                HHairline()
            }
        }
    }
}

// ── Search bar ──

@Composable
private fun DiagnosticSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HSearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.workspace_search_report)
        )
        if (query.isNotEmpty()) {
            HIconGhost(
                iconRes = R.drawable.ic_haven_close,
                contentDescription = stringResource(R.string.diagnostic_clear),
                onClick = onClear
            )
        }
    }
}

// ── Diagnostic section ──

@Composable
private fun DiagnosticSectionCard(
    section: DiagnosticSection,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    searchQuery: String?
) {
    val h = HavenTheme.colors
    HCard(tight = true) {
        // Section header (clickable to expand/collapse): mono 13sp title,
        // line count, and the Haven chevron state.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HChevron(expanded = isExpanded)
            Text(
                text = section.title,
                style = HavenType.mono12.copy(fontSize = 13.sp),
                color = h.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.diagnostic_section_lines, section.content.lines().size),
                style = HavenType.small,
                color = h.muted,
                maxLines = 1
            )
        }

        // Section content (expandable)
        HExpandablePanel(expanded = isExpanded) {
            SelectionContainer {
                if (searchQuery.isNullOrBlank()) {
                    HMonoBlock(
                        text = section.content,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    )
                } else {
                    // HMonoBlock takes a plain String, so the highlighted search
                    // view builds the same block from the same tokens to keep
                    // the AnnotatedString highlight.
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(HavenDim.rMonoBlock))
                            .background(h.surface2)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = highlightSearch(
                                text = section.content,
                                query = searchQuery,
                                highlight = h.primary.copy(alpha = 0x55 / 255f)
                            ),
                            style = HavenType.monoBlock,
                            color = h.text2
                        )
                    }
                }
            }
        }
    }
}

// ── Log file footer ──

@Composable
private fun DiagnosticLogFooter(logPath: String) {
    val h = HavenTheme.colors
    HCard {
        Text(
            text = stringResource(R.string.workspace_persistent_logs),
            style = HavenType.rowTitle,
            color = h.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.workspace_logs_location, logPath),
            style = HavenType.mono12,
            color = h.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = stringResource(R.string.diagnostic_log_files),
            style = HavenType.descMono,
            color = h.text2,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ── Action bar (sticky) ──

@Composable
private fun DiagnosticActionBar(
    diagnostics: String,
    workspaceName: String,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onRetry: () -> Unit,
    canRetry: Boolean
) {
    val h = HavenTheme.colors
    val shareChooserTitle = stringResource(R.string.diagnostic_share_chooser)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Copy button
        HButton(
            label = stringResource(R.string.common_copy),
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Linexa Diagnostics", diagnostics)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, R.string.workspace_error_copied, Toast.LENGTH_SHORT).show()
            },
            variant = HavenButtonVariant.SECONDARY,
            size = HavenButtonSize.SMALL,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_copy),
                    contentDescription = null,
                    tint = h.text2,
                    modifier = Modifier.size(15.dp)
                )
            },
            modifier = Modifier.weight(1f)
        )

        // Share button
        HButton(
            label = stringResource(R.string.common_share),
            onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Linexa Diagnostic Report — $workspaceName")
                    putExtra(Intent.EXTRA_TEXT, diagnostics)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, shareChooserTitle)
                )
            },
            variant = HavenButtonVariant.SECONDARY,
            size = HavenButtonSize.SMALL,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_share),
                    contentDescription = null,
                    tint = h.text2,
                    modifier = Modifier.size(15.dp)
                )
            },
            modifier = Modifier.weight(1f)
        )

        // Retry button (primary, visible action)
        if (canRetry) {
            HButton(
                label = stringResource(R.string.common_retry),
                onClick = onRetry,
                variant = HavenButtonVariant.PRIMARY,
                size = HavenButtonSize.SMALL,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_haven_refresh),
                        contentDescription = null,
                        tint = h.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Helpers ──

private fun highlightSearch(
    text: String,
    query: String?,
    highlight: Color
): androidx.compose.ui.text.AnnotatedString {
    if (query.isNullOrBlank()) {
        return androidx.compose.ui.text.AnnotatedString(text)
    }
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    var lastIndex = 0
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    while (true) {
        val index = lowerText.indexOf(lowerQuery, lastIndex)
        if (index == -1) break
        builder.append(text.substring(lastIndex, index))
        builder.pushStyle(
            style = androidx.compose.ui.text.SpanStyle(
                background = highlight
            )
        )
        builder.append(text.substring(index, index + query.length))
        builder.pop()
        lastIndex = index + query.length
    }
    builder.append(text.substring(lastIndex))
    return builder.toAnnotatedString()
}

private fun formatBytes(bytes: Long): String = FileSizeFormatter.format(bytes)

private suspend fun saveDiagnosticsToFile(
    context: Context,
    diagnostics: String,
    workspaceName: String,
    logPath: String?
) {
    withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeName = workspaceName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "diagnostics-${safeName}-${timestamp}.txt"

            // Save to the workspace's logs directory if available, otherwise Downloads
            val targetDir = if (logPath != null) {
                File(logPath)
            } else {
                File(context.getExternalFilesDir(null), "diagnostics")
            }
            targetDir.mkdirs()
            val targetFile = File(targetDir, fileName)
            targetFile.writeText(diagnostics)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.workspace_diagnostics_saved, targetFile.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.diagnostic_save_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
