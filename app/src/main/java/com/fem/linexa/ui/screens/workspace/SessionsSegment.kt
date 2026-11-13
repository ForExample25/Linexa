package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.ui.components.WorkspacePerformanceContent
import com.fem.linexa.ui.components.rememberIsForeground
import com.fem.linexa.ui.components.haven.HMetricPair
import com.fem.linexa.ui.components.haven.HMiniStat
import com.fem.linexa.ui.components.haven.HResourceCard
import com.fem.linexa.ui.components.haven.HResourceMetric
import com.fem.linexa.ui.components.haven.ResourceText
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HChipRow
import com.fem.linexa.ui.components.haven.HDialog
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HMenu
import com.fem.linexa.ui.components.haven.HMenuItem
import com.fem.linexa.ui.components.haven.HPill
import com.fem.linexa.ui.components.haven.HSearchField
import com.fem.linexa.ui.components.haven.HSheet
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The workspace's **Activity** segment — a task manager for *this workspace*.
 *
 * Top: one prominent resource card for the workspace itself (CPU, RAM,
 * processes, execution status), measured only from the workspace's own process
 * tree. A compact context strip shows the device's RAM and the Linexa app's
 * memory for orientation; the full device/app dashboard lives on the global
 * Activity tab and storage lives in the File Manager, so neither is repeated here.
 *
 * Below: the real terminal sessions from [RuntimeBinder.terminalSessions] as
 * task-manager rows (status, duration, CPU, RAM, overflow menu), with search,
 * filters and sorting. Process-level drill-downs deliberately live on the
 * dedicated Task Manager screen — the cards surface session-level facts only.
 * Every action shown is wired to the runtime; nothing is decorative.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsSegment(
    workspaceId: String,
    binder: RuntimeBinder?,
    runtimeState: RuntimeState,
    onOpenTerminal: (String) -> Unit,
    viewModel: SessionsViewModel,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val state by viewModel.state.collectAsState()
    val runningPid = (runtimeState as? RuntimeState.Running)?.pid
    val workspaceRunning = runtimeState is RuntimeState.Running
    val foreground by rememberIsForeground()

    // Polling runs only while the segment is on display AND the app is in the
    // foreground; either leaving the tab (dispose) or backgrounding pauses it.
    LaunchedEffect(workspaceId, binder, runningPid, workspaceRunning, foreground) {
        if (foreground) {
            viewModel.start(workspaceId, binder, runningPid, workspaceRunning)
        } else {
            viewModel.pause()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.pause() } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionsViewModel.Event.OpenTerminal -> onOpenTerminal(event.terminalId)
            }
        }
    }

    var menuSessionId by remember { mutableStateOf<String?>(null) }
    var detailSessionId by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    var endTarget by remember { mutableStateOf<SessionsViewModel.SessionUi?>(null) }
    var restartTarget by remember { mutableStateOf<SessionsViewModel.SessionUi?>(null) }

    val detailSession = detailSessionId?.let { id -> state.sessions.firstOrNull { it.terminalId == id } }
    val canOpenTerminal = binder != null && workspaceRunning

    Column(modifier = modifier.fillMaxWidth()) {
        WorkspaceResourceCard(
            state = state,
            runtimeState = runtimeState,
            connected = binder != null
        )
        SystemContextRow(state = state)

        state.message?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(HavenDim.rCard))
                    .background(h.surface2)
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    style = HavenType.small,
                    color = h.text2,
                    modifier = Modifier.weight(1f)
                )
                HIconGhost(
                    iconRes = R.drawable.ic_haven_close,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = viewModel::clearMessage,
                    small = true
                )
            }
        }

        // ── Sessions header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.activity_section_sessions),
                style = HavenType.section,
                color = h.muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.sessions_count, state.runningCount),
                style = HavenType.small,
                color = h.muted
            )
            if (canOpenTerminal) {
                HIconGhost(
                    iconRes = R.drawable.ic_haven_add,
                    contentDescription = stringResource(R.string.sessions_new_terminal),
                    onClick = viewModel::startTerminal
                )
            }
            HIconGhost(
                iconRes = R.drawable.ic_haven_refresh,
                contentDescription = stringResource(R.string.tm_refresh),
                onClick = viewModel::refreshNow
            )
        }

        HSearchField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = stringResource(R.string.sessions_search),
            // Explicit width: HSearchField wraps content by default, so without
            // this the search box would collapse to its hint text.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )

        // Filters with live counts.
        HChipRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            HChip(
                label = "${stringResource(R.string.sessions_filter_all)} ${state.sessions.size}",
                selected = state.filter == SessionsViewModel.SessionFilter.ALL,
                onClick = { viewModel.setFilter(SessionsViewModel.SessionFilter.ALL) }
            )
            HChip(
                label = "${stringResource(R.string.sessions_filter_running)} ${state.runningCount}",
                selected = state.filter == SessionsViewModel.SessionFilter.RUNNING,
                onClick = { viewModel.setFilter(SessionsViewModel.SessionFilter.RUNNING) }
            )
            HChip(
                label = "${stringResource(R.string.sessions_filter_ended)} ${state.endedCount}",
                selected = state.filter == SessionsViewModel.SessionFilter.ENDED,
                onClick = { viewModel.setFilter(SessionsViewModel.SessionFilter.ENDED) }
            )
        }

        // Sorting. CPU / RAM only appear when some session has that reading —
        // sorting by a value that does not exist would be a no-op dressed as a feature.
        HChipRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.sessions_sort_label),
                style = HavenType.small,
                color = h.muted,
                modifier = Modifier.padding(end = 4.dp)
            )
            HChip(
                label = stringResource(R.string.sessions_sort_newest),
                selected = state.sort == SessionsViewModel.SessionSort.NEWEST,
                onClick = { viewModel.setSort(SessionsViewModel.SessionSort.NEWEST) }
            )
            HChip(
                label = stringResource(R.string.sessions_sort_oldest),
                selected = state.sort == SessionsViewModel.SessionSort.OLDEST,
                onClick = { viewModel.setSort(SessionsViewModel.SessionSort.OLDEST) }
            )
            if (state.canSortByCpu || state.sort == SessionsViewModel.SessionSort.CPU) {
                HChip(
                    label = stringResource(R.string.sessions_sort_cpu),
                    selected = state.sort == SessionsViewModel.SessionSort.CPU,
                    onClick = { viewModel.setSort(SessionsViewModel.SessionSort.CPU) }
                )
            }
            if (state.canSortByRam || state.sort == SessionsViewModel.SessionSort.RAM) {
                HChip(
                    label = stringResource(R.string.sessions_sort_ram),
                    selected = state.sort == SessionsViewModel.SessionSort.RAM,
                    onClick = { viewModel.setSort(SessionsViewModel.SessionSort.RAM) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val visible = state.visibleSessions
        when {
            state.sessions.isEmpty() -> EmptySessions(
                workspaceRunning = workspaceRunning,
                canOpen = canOpenTerminal,
                onNewTerminal = viewModel::startTerminal
            )
            visible.isEmpty() -> Text(
                text = stringResource(R.string.sessions_no_match),
                style = HavenType.desc,
                color = h.muted,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
            )
            else -> visible.forEach { session ->
                // Stable key: the row keeps its menu/animation state across polls.
                key(session.terminalId) {
                    TaskRow(
                        session = session,
                        nowMillis = state.nowMillis,
                        ending = session.terminalId in state.ending,
                        restarting = session.terminalId in state.restarting,
                        menuOpen = menuSessionId == session.terminalId,
                        onOpen = { viewModel.openSession(session) },
                        onMenuOpen = { menuSessionId = session.terminalId },
                        onMenuDismiss = { menuSessionId = null },
                        onEndRequest = { endTarget = session },
                        onRestartRequest = { restartTarget = session },
                        onRemove = { viewModel.endSession(session) },
                        onViewDetails = {
                            menuSessionId = null
                            detailSessionId = session.terminalId
                        },
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    detailSession?.let { session ->
        SessionDetailsSheet(
            session = session,
            nowMillis = state.nowMillis,
            onDismiss = { detailSessionId = null },
            onOpen = {
                detailSessionId = null
                viewModel.openSession(session)
            },
            onRestart = {
                detailSessionId = null
                restartTarget = session
            },
            onEnd = {
                detailSessionId = null
                if (session.isActive) endTarget = session else viewModel.endSession(session)
            }
        )
    }

    endTarget?.let { session ->
        HDialog(
            title = stringResource(R.string.sessions_end_confirm_title, session.label),
            body = stringResource(R.string.sessions_end_confirm_body),
            onDismiss = { endTarget = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { endTarget = null },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.sessions_end),
                    onClick = {
                        endTarget = null
                        viewModel.endSession(session)
                    },
                    variant = HavenButtonVariant.DANGER,
                    size = HavenButtonSize.SMALL
                )
            }
        )
    }

    restartTarget?.let { session ->
        HDialog(
            title = stringResource(R.string.sessions_restart_confirm_title, session.label),
            body = stringResource(
                if (session.isActive) R.string.sessions_restart_confirm_body
                else R.string.sessions_restart_ended_body
            ),
            onDismiss = { restartTarget = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { restartTarget = null },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.sessions_restart),
                    onClick = {
                        restartTarget = null
                        viewModel.restartSession(session)
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL
                )
            }
        )
    }
}

// ── Workspace resource card ───────────────────────────────────────────────

/**
 * The primary readout of the Sessions tab: this workspace's CPU, RAM, process
 * count and execution status. Every figure comes from
 * [SessionsViewModel.State.workspaceSample], i.e. the workspace's own process
 * tree — never the device and never the Linexa app process.
 */
@Composable
private fun WorkspaceResourceCard(
    state: SessionsViewModel.State,
    runtimeState: RuntimeState,
    connected: Boolean
) {
    val h = HavenTheme.colors
    val running = runtimeState is RuntimeState.Running
    // A workspace that is not running has no process tree: show unknown, not the
    // last reading and certainly not zero.
    val sample = state.workspaceSample.takeIf { running }

    val (statusText, statusColor) = when {
        runtimeState is RuntimeState.Running && !connected ->
            stringResource(R.string.sessions_status_disconnected) to h.amber
        runtimeState is RuntimeState.Running ->
            stringResource(R.string.activity_status_running) to h.primary
        runtimeState is RuntimeState.Starting ->
            stringResource(R.string.activity_status_starting) to h.blue
        runtimeState is RuntimeState.Stopping ->
            stringResource(R.string.sessions_status_stopping) to h.amber
        runtimeState is RuntimeState.Error ->
            stringResource(R.string.sessions_status_error) to h.red
        else -> stringResource(R.string.sessions_status_stopped) to h.muted
    }
    val pending = when {
        !running -> stringResource(
            if (runtimeState is RuntimeState.Starting) R.string.activity_status_starting
            else R.string.sessions_not_running
        )
        !connected -> stringResource(R.string.sessions_status_disconnected)
        else -> stringResource(R.string.activity_measuring)
    }

    HResourceCard(
        title = stringResource(R.string.sessions_workspace_title),
        caption = stringResource(R.string.sessions_workspace_caption),
        iconRes = R.drawable.ic_linexa_container,
        accent = h.amber,
        status = statusText,
        statusColor = statusColor
    ) {
        HMetricPair(
            start = {
                HResourceMetric(
                    label = stringResource(R.string.activity_cpu),
                    value = ResourceText.percent(sample?.cpuPercentOfDevice),
                    fraction = sample?.cpuFraction,
                    accent = h.amber,
                    detail = if (sample?.cpuPercentOfDevice != null) {
                        stringResource(R.string.activity_of_device_cpu)
                    } else {
                        pending
                    },
                    prominent = true
                )
            },
            end = {
                HResourceMetric(
                    label = stringResource(R.string.activity_ram),
                    value = ResourceText.kb(
                        sample?.memoryKb,
                        approximate = sample?.memoryIsProportional == false
                    ),
                    fraction = sample?.memoryFraction,
                    accent = h.amber,
                    detail = when {
                        sample?.memoryKb == null -> pending
                        !sample.memoryIsProportional ->
                            stringResource(R.string.activity_ram_upper_bound)
                        else -> sample.memoryFraction?.let {
                            stringResource(
                                R.string.activity_of_device_ram,
                                ResourceText.percent(it * 100f)
                            )
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (sample != null) {
                    stringResource(R.string.sessions_processes_count, sample.processCount) +
                        " · " + stringResource(R.string.sessions_count, state.runningCount)
                } else {
                    stringResource(R.string.sessions_processes_count, 0)
                },
                style = HavenType.mono12,
                color = h.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.loading && running) HSpinner(small = true, color = h.amber)
        }
    }
}

/**
 * One quiet line of orientation under the workspace card: how full the device's
 * RAM is and what the Linexa app itself holds. Deliberately small — the full
 * dashboard is the Activity tab. Device CPU is shown only when the OS lets us
 * read it; on most current Android versions it cannot be measured, and a
 * permanent dash would be noise.
 */
@Composable
private fun SystemContextRow(state: SessionsViewModel.State) {
    val h = HavenTheme.colors
    val memory = state.deviceMemory
    val app = state.appMemory
    val deviceCpu = state.deviceCpu?.overallPercent
    val deviceRam = memory?.usedBytes?.let { used ->
        memory?.totalBytes?.let { total ->
            "${ResourceText.bytes(used)} / ${ResourceText.bytes(total)}"
        }
    }
    val appRam = (app?.pssBytes ?: app?.rssBytes)?.let {
        ResourceText.bytes(it, approximate = app?.pssBytes == null)
    }
    if (deviceRam == null && appRam == null && deviceCpu == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (deviceCpu != null) {
            HMiniStat(
                label = stringResource(R.string.sessions_context_device_cpu),
                value = ResourceText.percent(deviceCpu),
                modifier = Modifier.weight(0.7f)
            )
        }
        HMiniStat(
            label = stringResource(R.string.sessions_context_device_ram),
            value = deviceRam ?: ResourceText.UNKNOWN,
            modifier = Modifier.weight(1.3f)
        )
        HMiniStat(
            label = stringResource(R.string.tm_app_section),
            value = appRam ?: ResourceText.UNKNOWN,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Task-manager row ──────────────────────────────────────────────────────

/**
 * One session as a task-manager row.
 *
 * Active sessions show live CPU / RAM / process count and can be opened,
 * restarted or ended. Ended sessions show why they ended and can be inspected,
 * restarted (a fresh terminal replaces the dead one) or removed — they offer no
 * "Open", because there is nothing to attach to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    session: SessionsViewModel.SessionUi,
    nowMillis: Long,
    ending: Boolean,
    restarting: Boolean,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEndRequest: () -> Unit,
    onRestartRequest: () -> Unit,
    onRemove: () -> Unit,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val busy = ending || restarting
    val active = session.isActive
    val duration = formatDuration(session.elapsedMillis(nowMillis))

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HavenDim.rCard))
                .background(h.surface)
                .combinedClickable(
                    enabled = !busy,
                    onClick = if (active) onOpen else onViewDetails,
                    onLongClick = onMenuOpen,
                    onClickLabel = stringResource(
                        if (active) R.string.sessions_open else R.string.sessions_view_details
                    )
                )
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(HavenDim.rRic))
                        .background(if (active) h.tint else h.surface2),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(kindIcon(session.kind)),
                        contentDescription = null,
                        tint = if (active) kindAccent(session.kind) else h.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                ) {
                    Text(
                        text = kindLabel(session.kind),
                        style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = if (active) h.text else h.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${session.label} · $duration",
                        style = HavenType.mono12,
                        color = h.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (busy) {
                    HSpinner(small = true, color = h.primary)
                    Text(
                        text = stringResource(
                            if (ending) R.string.sessions_ending else R.string.sessions_restarting
                        ),
                        style = HavenType.small,
                        color = h.muted,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                } else {
                    HPill(
                        text = statusLabel(session.state),
                        color = statusColor(session.state),
                        container = h.surface2,
                        withDot = true
                    )
                }
                HIconGhost(
                    iconRes = R.drawable.ic_haven_more_vert,
                    contentDescription = stringResource(R.string.sessions_menu),
                    onClick = onMenuOpen,
                    enabled = !busy
                )
            }

            if (active) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HMiniStat(
                        label = stringResource(R.string.sessions_details_cpu),
                        value = ResourceText.percentPrecise(session.cpuPercentOfDevice),
                        modifier = Modifier.weight(1f)
                    )
                    HMiniStat(
                        label = stringResource(R.string.sessions_details_ram),
                        value = ResourceText.kb(
                            session.memKb,
                            approximate = !session.memoryIsProportional
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    HMiniStat(
                        label = stringResource(R.string.sessions_details_processes),
                        value = session.processCount.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                val reason = endReason(session.state)
                if (reason != null) {
                    Text(
                        text = reason,
                        style = HavenType.small,
                        color = h.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp, end = 10.dp)
                    )
                }
            }
        }

        HMenu(
            expanded = menuOpen,
            onDismiss = onMenuDismiss,
            items = if (active) {
                listOf(
                    HMenuItem(
                        label = stringResource(R.string.sessions_open),
                        iconRes = R.drawable.ic_haven_terminal,
                        onClick = onOpen
                    ),
                    HMenuItem(
                        label = stringResource(R.string.sessions_restart),
                        iconRes = R.drawable.ic_haven_refresh,
                        onClick = onRestartRequest
                    ),
                    HMenuItem(
                        label = stringResource(R.string.sessions_view_details),
                        iconRes = R.drawable.ic_haven_info,
                        onClick = onViewDetails
                    ),
                    HMenuItem(
                        label = stringResource(R.string.sessions_end),
                        iconRes = R.drawable.ic_haven_power,
                        onClick = onEndRequest,
                        danger = true
                    )
                )
            } else {
                listOf(
                    HMenuItem(
                        label = stringResource(R.string.sessions_view_details),
                        iconRes = R.drawable.ic_haven_info,
                        onClick = onViewDetails
                    ),
                    HMenuItem(
                        label = stringResource(R.string.sessions_restart),
                        iconRes = R.drawable.ic_haven_refresh,
                        onClick = onRestartRequest
                    ),
                    HMenuItem(
                        label = stringResource(R.string.sessions_remove),
                        iconRes = R.drawable.ic_haven_trash,
                        onClick = onRemove,
                        danger = true
                    )
                )
            }
        )
    }
}

/** Why a session is not running, in words — or null when there is nothing useful to say. */
@Composable
private fun endReason(state: TerminalState): String? = when (state) {
    is TerminalState.Error -> when {
        state.killedBySigkill -> stringResource(R.string.sessions_ended_killed)
        state.reason.isNotBlank() -> state.reason
        else -> stringResource(R.string.sessions_ended_error)
    }
    is TerminalState.Stopped -> stringResource(R.string.sessions_ended_clean)
    else -> null
}

@Composable
private fun EmptySessions(
    workspaceRunning: Boolean,
    canOpen: Boolean,
    onNewTerminal: () -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_haven_terminal),
            contentDescription = null,
            tint = h.muted,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = stringResource(R.string.sessions_empty_title),
            style = HavenType.body.copy(fontWeight = FontWeight.SemiBold),
            color = h.text,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = if (workspaceRunning) {
                stringResource(R.string.sessions_empty_body)
            } else {
                stringResource(R.string.sessions_empty_stopped)
            },
            style = HavenType.desc,
            color = h.muted,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (canOpen) {
            HButton(
                label = stringResource(R.string.sessions_new_terminal),
                onClick = onNewTerminal,
                variant = HavenButtonVariant.PRIMARY,
                size = HavenButtonSize.SMALL,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SessionDetailsSheet(
    session: SessionsViewModel.SessionUi,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRestart: () -> Unit,
    onEnd: () -> Unit
) {
    val h = HavenTheme.colors
    HSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.sessions_details_title, session.label),
        subtitle = "${kindLabel(session.kind)} · ${statusLabel(session.state)}"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            DetailRow(
                label = stringResource(R.string.sessions_details_status),
                value = statusLabel(session.state)
            )
            DetailRow(
                label = stringResource(R.string.sessions_details_pid),
                value = session.pid?.toString() ?: UNKNOWN
            )
            DetailRow(
                label = stringResource(R.string.sessions_details_started),
                value = TIME_FORMAT.format(session.createdAt)
            )
            DetailRow(
                label = stringResource(R.string.sessions_details_uptime),
                value = formatDuration(session.elapsedMillis(nowMillis))
            )
            DetailRow(
                label = stringResource(R.string.sessions_details_cpu),
                value = ResourceText.percent(session.cpuPercentOfDevice)
            )
            DetailRow(
                label = stringResource(R.string.sessions_details_ram),
                value = session.memKb?.let { kb ->
                    val label = WorkspacePerformanceContent.formatKb(kb)
                    if (session.memoryIsProportional) label else "~$label"
                } ?: UNKNOWN
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (session.isActive) {
                    HButton(
                        label = stringResource(R.string.sessions_open),
                        onClick = onOpen,
                        variant = HavenButtonVariant.PRIMARY,
                        size = HavenButtonSize.SMALL,
                        modifier = Modifier.weight(1f)
                    )
                }
                HButton(
                    label = stringResource(R.string.sessions_restart),
                    onClick = onRestart,
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL,
                    modifier = Modifier.weight(1f)
                )
                HButton(
                    label = stringResource(
                        if (session.isActive) R.string.sessions_end else R.string.sessions_remove
                    ),
                    onClick = onEnd,
                    variant = HavenButtonVariant.DANGER,
                    size = HavenButtonSize.SMALL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = HavenType.small,
            color = h.text2,
            modifier = Modifier.weight(1f)
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

@Composable
private fun kindLabel(kind: SessionsViewModel.SessionKind): String = stringResource(
    when (kind) {
        SessionsViewModel.SessionKind.TERMINAL -> R.string.sessions_kind_terminal
        SessionsViewModel.SessionKind.SHELL -> R.string.sessions_kind_shell
        SessionsViewModel.SessionKind.CLAUDE -> R.string.sessions_kind_claude
        SessionsViewModel.SessionKind.PYTHON -> R.string.sessions_kind_python
        SessionsViewModel.SessionKind.NODE -> R.string.sessions_kind_node
        SessionsViewModel.SessionKind.GIT -> R.string.sessions_kind_git
        SessionsViewModel.SessionKind.PACKAGE -> R.string.sessions_kind_package
    }
)

private fun kindIcon(kind: SessionsViewModel.SessionKind): Int = when (kind) {
    SessionsViewModel.SessionKind.CLAUDE -> R.drawable.ic_haven_activity
    SessionsViewModel.SessionKind.PYTHON -> R.drawable.ic_haven_terminal
    SessionsViewModel.SessionKind.NODE -> R.drawable.ic_haven_globe
    SessionsViewModel.SessionKind.GIT -> R.drawable.ic_haven_db
    SessionsViewModel.SessionKind.PACKAGE -> R.drawable.ic_haven_box
    SessionsViewModel.SessionKind.TERMINAL,
    SessionsViewModel.SessionKind.SHELL -> R.drawable.ic_haven_terminal
}

@Composable
private fun kindAccent(kind: SessionsViewModel.SessionKind): androidx.compose.ui.graphics.Color {
    val h = HavenTheme.colors
    return when (kind) {
        SessionsViewModel.SessionKind.CLAUDE -> h.primary
        SessionsViewModel.SessionKind.PYTHON -> h.blue
        SessionsViewModel.SessionKind.NODE -> h.blue
        SessionsViewModel.SessionKind.GIT -> h.amber
        SessionsViewModel.SessionKind.PACKAGE -> h.amber
        SessionsViewModel.SessionKind.TERMINAL,
        SessionsViewModel.SessionKind.SHELL -> h.text2
    }
}

@Composable
private fun statusLabel(state: TerminalState): String = stringResource(
    when (state) {
        is TerminalState.Starting -> R.string.terminal_state_starting
        is TerminalState.Running -> R.string.terminal_state_running
        is TerminalState.Stopping -> R.string.terminal_state_stopping
        is TerminalState.Stopped -> R.string.terminal_state_stopped
        is TerminalState.Error -> R.string.terminal_state_error
    }
)

@Composable
private fun statusColor(state: TerminalState): androidx.compose.ui.graphics.Color {
    val h = HavenTheme.colors
    return when (state) {
        is TerminalState.Starting -> h.blue
        is TerminalState.Running -> h.primary
        is TerminalState.Stopping -> h.amber
        is TerminalState.Stopped -> h.muted
        is TerminalState.Error -> h.red
    }
}

private fun formatDuration(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h"
    }
}

private const val UNKNOWN = "—"
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
