package com.fem.linexa.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.resource.HostProcessSample
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.ui.components.DistroIdentity
import com.fem.linexa.ui.components.rememberIsForeground
import com.fem.linexa.ui.components.haven.HavenDestination
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.haven.HBottomNav
import com.fem.linexa.ui.components.haven.havenTabSwipe
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HTopBar
import com.fem.linexa.ui.components.haven.HPageHeader
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HDot
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HChipRow
import com.fem.linexa.ui.components.haven.HDialog
import com.fem.linexa.ui.components.haven.HMetricPair
import com.fem.linexa.ui.components.haven.HResourceCard
import com.fem.linexa.ui.components.haven.HResourceMetric
import com.fem.linexa.ui.components.haven.ResourceText
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The Activity tab — HAVEN (concept section 01, `fr-activity`).
 *
 * "Activity replaces a notifications drawer": one hero card answers "is
 * anything running?" (tap = open that workspace), three metric tiles carry the
 * host telemetry, the terminal-session rows list every open session of every
 * running workspace (tap = open that session), and the recent-activity feed
 * keeps an honest audit trail derived from persisted workspace facts.
 */
@Composable
fun ActivityScreen(
    onOpenWorkspace: (workspaceId: String, terminalId: String?) -> Unit,
    onSelectTab: (HavenDestination) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
    terminalViewModel: com.fem.linexa.ui.screens.terminal.ManagementTerminalViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val state by viewModel.uiState.collectAsState()
    val terminalSession by terminalViewModel.session.collectAsState()
    var hostEndTarget by remember { mutableStateOf<HostProcessSample?>(null) }
    var terminalOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // ACT-2: collapsed on first paint and saved across rotation/process death.
    // The section header is the ONE toggle — no separate Show/Hide control.
    var hostExpanded by rememberSaveable { mutableStateOf(false) }
    val hostRows = remember(state.hostProcesses?.processes) {
        HostProcessPresentation.ordered(state.hostProcesses?.processes.orEmpty())
    }
    val hostVisible = HostProcessPresentation.visible(hostRows, hostExpanded)

    androidx.compose.runtime.LaunchedEffect(terminalOpen) {
        if (terminalOpen) terminalViewModel.start()
    }

    androidx.activity.compose.BackHandler(enabled = terminalOpen) { terminalOpen = false }

    // Sampling + the read-only runtime bind live only while the screen is on
    // display: leaving the tab OR sending the app to the background stops the
    // poll loop (the composable stays composed in the background, so keying on
    // composition alone would keep reading /proc for nobody).
    val foreground by rememberIsForeground()
    DisposableEffect(viewModel, foreground) {
        if (foreground) viewModel.start() else viewModel.pause()
        onDispose { viewModel.pause() }
    }

    Scaffold(
        containerColor = h.bg,
        bottomBar = {
            HBottomNav(
                selected = HavenDestination.ACTIVITY,
                onSelect = onSelectTab,
                modifier = Modifier.graphicsLayer { alpha = if (terminalOpen) 0f else 1f }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .havenTabSwipe(
                    current = HavenDestination.ACTIVITY,
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
                    title = "Activity",
                    trailing = {
                        HIconCircle(
                            iconRes = R.drawable.ic_haven_terminal,
                            contentDescription = "Management Terminal",
                            onClick = { terminalOpen = !terminalOpen }
                        )
                    }
                )

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
                    // ── Resources: three scopes, never mixed ──
                    item(key = "resources-header") {
                        SectionHeader(
                            title = stringResource(R.string.activity_section_resources),
                            topPadding = 4.dp
                        )
                    }
                    item(key = "device") { DeviceCard(state.device) }
                    item(key = "app") { AppCard(state.app) }

                    // ── Active workspace ──
                    if (state.choices.isNotEmpty()) {
                        item(key = "workspace-picker") {
                            WorkspacePicker(
                                choices = state.choices,
                                selectedId = state.workspace?.workspaceId,
                                onSelect = viewModel::selectWorkspace
                            )
                        }
                    }
                    item(key = "workspace") {
                        val workspace = state.workspace
                        if (workspace != null) {
                            WorkspaceCard(
                                workspace = workspace,
                                onOpen = { onOpenWorkspace(workspace.workspaceId, null) }
                            )
                        } else {
                            HCard {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    HDot(color = h.muted, size = 6.5f)
                                    Text(
                                        text = stringResource(R.string.activity_nothing_running),
                                        style = HavenType.title,
                                        color = h.text2
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.activity_nothing_running_body),
                                    style = HavenType.desc,
                                    color = h.text2,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }

                    // ── Android host processes (unprivileged /proc view) ──
                    item(key = "host-header") {
                        HostProcessHeader(
                            count = hostRows.size,
                            expanded = hostExpanded,
                            onToggle = { hostExpanded = !hostExpanded }
                        )
                    }
                    item(key = "host-scope") {
                        val host = state.hostProcesses
                        HCard {
                            Text(
                                text = when {
                                    host == null -> stringResource(R.string.activity_measuring)
                                    !host.scopeLimited ->
                                        stringResource(R.string.activity_host_scope_complete, host.readableProcesses)
                                    else -> stringResource(
                                        R.string.activity_host_scope_limited,
                                        host.readableProcesses,
                                        host.inaccessibleProcesses
                                    )
                                },
                                style = HavenType.desc,
                                color = h.muted
                            )
                        }
                    }
                    if (hostRows.isEmpty()) {
                        item(key = "host-empty") {
                            HCard {
                                Text(
                                    text = stringResource(R.string.processes_empty),
                                    style = HavenType.rowTitle,
                                    color = h.text2
                                )
                            }
                        }
                    } else if (hostVisible.isNotEmpty()) {
                        // Lazy `items` are emitted only while expanded: a
                        // collapsed section composes zero process cards. A
                        // literal HExpandablePanel would compose every row
                        // inside its Column before animating, which is exactly
                        // the unbounded first-paint cost ACT-2 removes.
                        items(
                            count = hostVisible.size,
                            key = { HostProcessPresentation.key(hostVisible[it]) }
                        ) { index ->
                            HostProcessCard(
                                process = hostVisible[index],
                                onEnd = { hostEndTarget = hostVisible[index] }
                            )
                        }
                    }

                    // ── Terminal sessions ──
                    item(key = "sessions-header") {
                        SectionHeader(
                            title = stringResource(R.string.activity_section_sessions),
                            trailing = if (state.sessions.isNotEmpty()) {
                                stringResource(R.string.sessions_count, state.sessions.size)
                            } else {
                                null
                            }
                        )
                    }
                    if (state.sessions.isEmpty()) {
                        item(key = "sessions-empty") {
                            HCard {
                                Text(
                                    text = "No open sessions",
                                    style = HavenType.rowTitle,
                                    color = h.text2
                                )
                                Text(
                                    text = "Terminals you open inside a workspace appear here.",
                                    style = HavenType.desc,
                                    color = h.muted,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    } else {
                        items(
                            count = state.sessions.size,
                            key = { index ->
                                val row = state.sessions[index]
                                "session-${row.workspaceId}-${row.terminalId}"
                            }
                        ) { index ->
                            val session = state.sessions[index]
                            HCard(tight = true) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenWorkspace(session.workspaceId, session.terminalId)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                        .height(com.fem.linexa.ui.theme.haven.HavenDim.rowMin),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(HavenDim.ric)
                                            .clip(RoundedCornerShape(HavenDim.rRic))
                                            .background(h.tint),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            // 2026-08-31 (Phase 6): compact
                                            // display label; the row click
                                            // below stays on the internal id.
                                            text = session.displayId,
                                            style = HavenType.small.copy(
                                                fontFamily = HavenType.mono,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)
                                            ),
                                            color = h.primary
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            // 2026-08-31 (Phase 6): compact
                                            // display label in the title;
                                            // navigation stays on terminalId.
                                            text = "${session.displayId} · ${session.workspaceName}",
                                            style = HavenType.rowTitle,
                                            color = h.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val sessionStatus = if (session.isRunning) {
                                        stringResource(R.string.terminal_state_running)
                                    } else {
                                        stringResource(R.string.terminal_state_starting)
                                    }
                                    // One quiet dot carries the state; the old
                                    // row printed "Running" twice (once as a
                                    // subtitle and once in HStatus).
                                    HDot(
                                        color = if (session.isRunning) h.primary else h.blue,
                                        size = 6.5f,
                                        modifier = Modifier.semantics {
                                            contentDescription = sessionStatus
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── Recent activity ──
                    item(key = "events-header") {
                        SectionHeader(title = stringResource(R.string.activity_section_recent))
                    }
                    if (state.events.isEmpty()) {
                        item(key = "events-empty") {
                            HCard {
                                Text(
                                    text = "Nothing yet",
                                    style = HavenType.rowTitle,
                                    color = h.text2
                                )
                            }
                        }
                    } else {
                        item(key = "events") {
                            HCard(tight = true) {
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    state.events.forEachIndexed { index, event ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            HDot(
                                                color = when (event.kind) {
                                                    ActivityViewModel.EventKind.PRIMARY -> h.primary
                                                    ActivityViewModel.EventKind.BLUE -> h.blue
                                                    ActivityViewModel.EventKind.AMBER -> h.amber
                                                    ActivityViewModel.EventKind.RED -> h.red
                                                    ActivityViewModel.EventKind.MUTED -> h.muted
                                                },
                                                size = 7f
                                            )
                                            Text(
                                                text = event.label,
                                                style = HavenType.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                                color = h.text,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = event.relativeTime,
                                                style = HavenType.descMono,
                                                color = h.muted
                                            )
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

            // Management terminal overlay — FULLSCREEN takeover.
            // 2026-08-31 (Phase 1): a SIBLING of the Scaffold-padded box, not
            // a child — inside it the Scaffold padding (status-bar top inset +
            // the bottom-bar band under alpha 0) left blank strips above and
            // below the terminal. At this level the overlay spans the whole
            // window; the panel owns its own insets (bar statusBarsPadding,
            // extra keys navigationBarsPadding, surface imePadding).
            com.fem.linexa.ui.components.TerminalOverlay(
                visible = terminalOpen,
                modifier = Modifier.fillMaxSize()
            ) {
                com.fem.linexa.ui.screens.terminal.HavenManagementTerminalPanel(
                    session = terminalSession,
                    onDismiss = { terminalOpen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    hostEndTarget?.let { target ->
        HDialog(
            title = stringResource(R.string.tm_end_confirm_title, target.name),
            body = stringResource(R.string.tm_end_confirm_message, target.name, target.pid),
            onDismiss = { hostEndTarget = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { hostEndTarget = null },
                    variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.SECONDARY,
                    size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.tm_end_task),
                    onClick = {
                        viewModel.terminateHost(target)
                        hostEndTarget = null
                    },
                    variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.DANGER,
                    size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL
                )
            }
        )
    }
}

/** One readable host process; expands to the identity details the scan actually read. */
@Composable
private fun HostProcessCard(process: HostProcessSample, onEnd: () -> Unit) {
    val h = HavenTheme.colors
    var expanded by remember { mutableStateOf(false) }
    HCard(tight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HDot(
                    color = when (process.stateChar) {
                        'R' -> h.primary
                        'S', 'I' -> h.text2
                        'D', 'Z', 'T', 't' -> h.amber
                        else -> h.red
                    }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        text = process.name,
                        style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = h.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("PID ${process.pid}")
                            append(" · ${ResourceText.percent(process.cpuPercentOfDevice)}")
                            append(
                                " · " + (process.memKb
                                    ?.let { FileSizeFormatter.format(it * 1024L) }
                                    ?: "—")
                            )
                            append(" · ${WorkspaceProcessSample.stateLabel(process.stateChar)}")
                        },
                        style = HavenType.mono12,
                        color = h.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                HChevron(expanded = expanded)
            }
            if (expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 17.dp)) {
                    process.command?.let {
                        Text(
                            text = it,
                            style = HavenType.mono12,
                            color = h.text2,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val details = buildList {
                        process.uid?.let { add(stringResource(R.string.activity_host_uid, it)) }
                        process.parentPid?.let { add(stringResource(R.string.tm_process_parent, it)) }
                        process.threads?.let { add(stringResource(R.string.activity_host_threads, it)) }
                        process.lastCore?.let { add(stringResource(R.string.activity_host_core, it)) }
                        if (process.isSelf) add(stringResource(R.string.activity_host_self))
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            style = HavenType.mono12,
                            color = h.muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (process.canSignal && !process.isProtected) {
                        HButton(
                            label = stringResource(R.string.processes_terminate),
                            onClick = onEnd,
                            variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.DANGER,
                            size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            text = stringResource(
                                if (process.isProtected) {
                                    R.string.activity_host_protected
                                } else {
                                    R.string.activity_host_not_signalable
                                }
                            ),
                            style = HavenType.small,
                            color = h.muted,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * ACT-2 — the host-process section header is the single expand/collapse
 * control. It carries the live row count, rotates the chevron with the shared
 * [HChevron] affordance, and exposes button/state semantics for TalkBack.
 */
@Composable
private fun HostProcessHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val h = HavenTheme.colors
    val actionLabel = stringResource(
        if (expanded) R.string.activity_host_collapse else R.string.activity_host_expand
    )
    val stateLabel = stringResource(
        if (expanded) R.string.activity_host_expanded else R.string.activity_host_collapsed
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 12.dp)
            .clickable(onClickLabel = actionLabel, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = stateLabel
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.activity_host_header, count),
            style = HavenType.section,
            color = h.muted
        )
        Spacer(modifier = Modifier.weight(1f))
        HChevron(expanded = expanded)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String? = null,
    topPadding: androidx.compose.ui.unit.Dp = HavenDim.sectionTop
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = HavenType.section,
            color = h.muted
        )
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = HavenType.desc.copy(fontWeight = FontWeight.SemiBold),
                color = h.primary
            )
        }
    }
}

// ── Resource scopes ──────────────────────────────────────────────────────

private const val RAM_HIGH = 0.75f
private const val RAM_CRITICAL = 0.90f

/** Device scope: the whole phone. Nothing here comes from the app or the workspace. */
@Composable
private fun DeviceCard(device: ActivityViewModel.DeviceMetrics?) {
    val h = HavenTheme.colors
    val fraction = device?.ramFraction
    val pressure: Pair<String?, Color> = when {
        fraction == null -> null to h.muted
        fraction >= RAM_CRITICAL -> stringResource(R.string.activity_pressure_critical) to h.red
        fraction >= RAM_HIGH -> stringResource(R.string.activity_pressure_high) to h.amber
        else -> stringResource(R.string.activity_pressure_normal) to h.primary
    }
    val statusText = pressure.first
    val statusColor = pressure.second
    HResourceCard(
        title = stringResource(R.string.activity_device_title),
        caption = stringResource(R.string.activity_device_caption),
        iconRes = R.drawable.ic_haven_cpu,
        accent = h.primary,
        status = statusText,
        statusColor = statusColor
    ) {
        HMetricPair(
            start = {
                HResourceMetric(
                    label = stringResource(R.string.activity_cpu),
                    value = ResourceText.percent(device?.cpuPercent),
                    fraction = device?.cpuPercent?.let { it / 100f },
                    accent = h.primary,
                    detail = cpuDetail(
                        availability = device?.cpuAvailability,
                        availableDetail = device?.let {
                            stringResource(R.string.activity_cores, it.cores)
                        },
                        unavailableDetail = stringResource(R.string.activity_cpu_device_unavailable)
                    )
                )
            },
            end = {
                HResourceMetric(
                    label = stringResource(R.string.activity_ram),
                    value = ResourceText.bytes(device?.ramUsedBytes),
                    fraction = fraction,
                    accent = statusColor.takeIf { fraction != null } ?: h.primary,
                    detail = device?.ramTotalBytes?.let {
                        stringResource(R.string.activity_ram_of, ResourceText.bytes(it))
                    } ?: stringResource(R.string.activity_measuring)
                )
            }
        )
    }
}

/** App scope: the Linexa process itself — the Linux workspace is a different set of pids. */
@Composable
private fun AppCard(app: ActivityViewModel.AppMetrics?) {
    val h = HavenTheme.colors
    HResourceCard(
        title = stringResource(R.string.activity_app_title),
        caption = stringResource(R.string.activity_app_caption),
        iconRes = R.drawable.ic_haven_box,
        accent = h.blue
    ) {
        HMetricPair(
            start = {
                HResourceMetric(
                    label = stringResource(R.string.activity_cpu),
                    value = ResourceText.percent(app?.cpuPercent),
                    fraction = app?.cpuPercent?.let { it / 100f },
                    accent = h.blue,
                    detail = cpuDetail(
                        availability = app?.cpuAvailability,
                        availableDetail = stringResource(R.string.activity_of_device_cpu),
                        unavailableDetail = stringResource(R.string.activity_cpu_unavailable)
                    )
                )
            },
            end = {
                HResourceMetric(
                    label = stringResource(R.string.activity_ram),
                    value = ResourceText.bytes(app?.ramBytes, approximate = app?.ramIsProportional == false),
                    fraction = app?.ramFraction,
                    accent = h.blue,
                    detail = app?.ramFraction?.let {
                        stringResource(R.string.activity_of_device_ram, ResourceText.percent(it * 100f))
                    } ?: stringResource(R.string.activity_measuring)
                )
            }
        )
    }
}

/** Workspace scope: the monitored workspace's own process tree. Tap opens it. */
@Composable
private fun WorkspaceCard(
    workspace: ActivityViewModel.WorkspaceMetrics,
    onOpen: () -> Unit
) {
    val h = HavenTheme.colors
    val footer = buildList {
        add(stringResource(R.string.sessions_processes_count, workspace.processCount))
        if (workspace.terminalCount > 0) {
            add(
                if (workspace.terminalCount == 1) {
                    stringResource(R.string.activity_terminal_one)
                } else {
                    stringResource(R.string.activity_terminals_n, workspace.terminalCount)
                }
            )
        }
        workspace.uptimeLabel?.let { add(stringResource(R.string.activity_uptime, it)) }
    }.joinToString(" · ")

    HResourceCard(
        title = workspace.name,
        caption = stringResource(R.string.activity_workspace_caption),
        iconRes = R.drawable.ic_linexa_container,
        accent = h.amber,
        status = stringResource(
            if (workspace.isStarting) R.string.activity_status_starting else R.string.activity_status_running
        ),
        statusColor = if (workspace.isStarting) h.blue else h.primary,
        leading = {
            DistroIdentity(
                distroId = workspace.distroId,
                letter = workspace.avatarLetter,
                color = h.primary.copy(alpha = 0.85f),
                size = HavenDim.glyph
            )
        },
        onClick = onOpen
    ) {
        HMetricPair(
            start = {
                HResourceMetric(
                    label = stringResource(R.string.activity_cpu),
                    value = ResourceText.percent(workspace.cpuPercent),
                    fraction = workspace.cpuPercent?.let { it / 100f },
                    accent = h.amber,
                    detail = cpuDetail(
                        availability = workspace.cpuAvailability,
                        availableDetail = stringResource(R.string.activity_of_device_cpu),
                        unavailableDetail = stringResource(R.string.activity_workspace_unreadable)
                    ),
                    prominent = true
                )
            },
            end = {
                HResourceMetric(
                    label = stringResource(R.string.activity_ram),
                    value = ResourceText.kb(workspace.ramKb, approximate = !workspace.ramIsProportional),
                    fraction = workspace.ramFraction,
                    accent = h.amber,
                    detail = when {
                        workspace.ramKb == null -> stringResource(R.string.activity_measuring)
                        !workspace.ramIsProportional -> stringResource(R.string.activity_ram_upper_bound)
                        else -> workspace.ramFraction?.let {
                            stringResource(R.string.activity_of_device_ram, ResourceText.percent(it * 100f))
                        }
                    },
                    prominent = true
                )
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = footer,
                style = HavenType.mono12,
                color = h.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            HChevron()
        }
    }
}

/** Shown only while several workspaces run: which one the Workspace card describes. */
@Composable
private fun WorkspacePicker(
    choices: List<ActivityViewModel.MonitorChoice>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    HChipRow(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        choices.forEach { choice ->
            HChip(
                label = choice.name,
                selected = choice.workspaceId == selectedId,
                onClick = { onSelect(choice.workspaceId) }
            )
        }
    }
}

/**
 * The line under a CPU value: the qualifier when there is a number, otherwise
 * *why* there is not — still measuring (CPU is a delta; the first sample has no
 * answer) versus not measurable on this device. A bare dash would not say which.
 */
@Composable
private fun cpuDetail(
    availability: ActivityViewModel.Availability?,
    availableDetail: String?,
    unavailableDetail: String
): String? = when (availability) {
    ActivityViewModel.Availability.AVAILABLE -> availableDetail
    ActivityViewModel.Availability.UNAVAILABLE -> unavailableDetail
    ActivityViewModel.Availability.MEASURING, null -> stringResource(R.string.activity_measuring)
}
