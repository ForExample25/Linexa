package com.fem.linexa.ui.screens.taskmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HDot
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HSearchField
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HStorageBar
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.ResourceText
import com.fem.linexa.ui.components.rememberIsForeground
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The dedicated Task Manager screen (Task Manager redesign) — a professional
 * system monitor for the active Linux workspace, in HAVEN surfaces.
 *
 * Layout: back-header; the DEVICE card (overall CPU + per-core + RAM);
 * the LINEXA APP card (the app's own PSS/CPU — explicitly separated from the
 * workspace's); the WORKSPACE card (tree CPU/RAM/storage, scope-labelled);
 * then the live process list with search/sort/end-task.
 *
 * Every value is measured (see [TaskManagerViewModel]); unknowns render as
 * an em dash, never a zero. Polling runs only while the screen is composed.
 */
@Composable
fun TaskManagerScreen(
    viewModel: TaskManagerViewModel,
    workspaceId: String,
    workspaceName: String,
    runningPid: Int?,
    onBack: () -> Unit
) {
    val h = HavenTheme.colors
    val state by viewModel.state.collectAsState()

    // This screen owns three pollers (device, app, workspace tree). Stop them
    // when the lifecycle leaves STARTED, not only when the composable is
    // disposed: a backgrounded Task Manager would otherwise keep walking
    // /proc and /sys for a screen nobody can see.
    val foreground by rememberIsForeground()
    LaunchedEffect(workspaceId, runningPid, foreground) {
        if (foreground) viewModel.start(workspaceId, runningPid) else viewModel.stop()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var endTarget by remember { mutableStateOf<WorkspaceProcessSample?>(null) }
    var endFailure by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = HavenDim.screenH)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HIconCircle(
                iconRes = R.drawable.ic_haven_arrow_back,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.tm_screen_title),
                    style = HavenType.h2,
                    color = h.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = workspaceName,
                    style = HavenType.desc,
                    color = h.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            HIconGhost(
                iconRes = R.drawable.ic_haven_refresh,
                contentDescription = stringResource(R.string.tm_refresh),
                onClick = { runningPid?.let { viewModel.start(workspaceId, it) } }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Device card ──
            item {
                val devMem = state.deviceMemory
                val devTotal = devMem?.totalBytes?.takeIf { it > 0 }
                val devUsed = devTotal?.let { total -> (total - (devMem.availableBytes ?: total)).coerceAtLeast(0L) }
                ScopeCard(title = stringResource(R.string.tm_device_section), accent = h.primary) {
                    MetricRow(
                        label = stringResource(R.string.tm_overall_cpu),
                        value = ResourceText.percent(state.deviceCpu?.overallPercent),
                        fraction = state.deviceCpu?.overallPercent?.let { it / 100f },
                        accent = h.primary
                    )
                    state.deviceCpu?.let { cpu ->
                        val caption = when {
                            !cpu.readable -> stringResource(R.string.activity_cpu_device_unavailable)
                            cpu.overallPercent == null -> stringResource(R.string.activity_measuring)
                            else -> null
                        }
                        caption?.let {
                            Text(
                                text = it,
                                style = HavenType.small,
                                color = h.muted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    val cores = state.deviceCpu?.perCorePercent.orEmpty()
                    if (cores.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        PerCoreBars(perCore = cores, accent = h.primary)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    MetricRow(
                        label = stringResource(R.string.tm_memory_label),
                        value = if (devUsed != null && devTotal != null) {
                            "${FileSizeFormatter.format(devUsed)} / ${FileSizeFormatter.format(devTotal)}"
                        } else "—",
                        fraction = if (devUsed != null && devTotal != null) {
                            (devUsed.toFloat() / devTotal).coerceIn(0f, 1f)
                        } else null,
                        accent = h.primary
                    )
                }
            }

            // ── Linexa app card ──
            item {
                ScopeCard(title = stringResource(R.string.tm_app_section), accent = h.blue) {
                    MetricRow(
                        label = stringResource(R.string.tm_app_cpu),
                        value = ResourceText.percent(state.appCpuPercent),
                        fraction = state.appCpuPercent?.let { it / 100f },
                        accent = h.blue
                    )
                    if (state.appCpuPercent == null) {
                        Text(
                            text = stringResource(R.string.activity_measuring),
                            style = HavenType.small,
                            color = h.muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    MetricRow(
                        label = stringResource(R.string.tm_app_memory),
                        value = state.appMemory?.pssBytes?.let { FileSizeFormatter.format(it) } ?: "—",
                        fraction = state.appMemory?.pssBytes?.let { pss ->
                            state.deviceMemory?.totalBytes?.takeIf { it > 0 }?.let { total ->
                                (pss.toFloat() / total).coerceIn(0f, 1f)
                            }
                        },
                        accent = h.blue
                    )
                    val threads = state.appThreads
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        // Thread visibility is an OS-permission surface: say so
                        // explicitly when /proc/<pid>/task is denied rather
                        // than rendering an empty "0 threads" list.
                        text = when {
                            threads == null -> stringResource(R.string.activity_measuring)
                            !threads.readable -> stringResource(R.string.tm_app_threads_unavailable)
                            else -> stringResource(R.string.tm_app_threads, threads.threadCount)
                        },
                        style = HavenType.small,
                        color = h.muted
                    )
                    val busiestThread = threads?.takeIf { it.readable }?.busiest
                        ?.takeIf { it.cpuPercentOfDevice != null }
                    if (busiestThread != null) {
                        Text(
                            text = stringResource(
                                R.string.tm_app_busiest_thread,
                                busiestThread.name,
                                ResourceText.percent(busiestThread.cpuPercentOfDevice)
                            ),
                            style = HavenType.small,
                            color = h.text2,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // ── Workspace card ──
            item {
                ScopeCard(title = stringResource(R.string.tm_workspace_section), accent = h.amber) {
                    MetricRow(
                        label = stringResource(R.string.tm_workspace_cpu),
                        value = ResourceText.percent(state.workspaceSample?.cpuPercentOfDevice),
                        fraction = state.workspaceSample?.cpuFraction,
                        accent = h.amber
                    )
                    val workspaceCpu = state.workspaceSample
                    if (workspaceCpu != null && workspaceCpu.cpuPercentOfDevice == null) {
                        Text(
                            text = stringResource(
                                if (workspaceCpu.processCount == 0) {
                                    R.string.activity_cpu_unavailable
                                } else {
                                    R.string.activity_measuring
                                }
                            ),
                            style = HavenType.small,
                            color = h.muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    MetricRow(
                        label = stringResource(R.string.tm_workspace_memory),
                        value = state.workspaceSample?.memoryKb?.let { FileSizeFormatter.format(it * 1024L) } ?: "—",
                        fraction = state.workspaceSample?.memoryFraction,
                        accent = h.amber
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    MetricRow(
                        label = stringResource(R.string.tm_workspace_storage_used),
                        value = state.storage?.usedLabel ?: "—",
                        fraction = state.storage?.fraction,
                        accent = h.amber
                    )
                }
            }

            // ── Process list ──
            item {
                HSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.tm_search_processes),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = stringResource(R.string.tm_processes_section).uppercase() +
                        if (state.loading) "" else " · ${state.processes.size}",
                    style = HavenType.section,
                    color = h.muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (state.loading) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HSpinner(small = true)
                        Text(stringResource(R.string.tm_no_processes), style = HavenType.small, color = h.muted)
                    }
                }
            } else {
                val filtered = state.processes.filter {
                    searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) ||
                        it.pid.toString().contains(searchQuery)
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.tm_no_match),
                            style = HavenType.desc,
                            color = h.muted,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else {
                    items(filtered, key = { it.pid }) { process ->
                        ProcessCard(
                            process = process,
                            onEnd = { endTarget = process }
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    // ── The End-task confirmation ──
    endTarget?.let { target ->
        com.fem.linexa.ui.components.haven.HDialog(
            title = stringResource(R.string.tm_end_confirm_title, target.name),
            body = stringResource(R.string.tm_end_confirm_message, target.name, target.pid),
            onDismiss = { endTarget = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { endTarget = null },
                    variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.SECONDARY,
                    size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.tm_end_task),
                    onClick = {
                        val targetName = target.name
                        viewModel.terminate(target) { ok, reason ->
                            endFailure = if (ok) null else targetName to (reason ?: "")
                        }
                        endTarget = null
                    },
                    variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.DANGER,
                    size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL
                )
            }
        )
    }
    endFailure?.let { failure ->
        LaunchedEffect(failure) {
            kotlinx.coroutines.delay(3000)
            endFailure = null
        }
        Box(
            modifier = Modifier
                .padding(bottom = 30.dp)
                .clip(RoundedCornerShape(50))
                .background(h.toast)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.tm_end_failed, failure.first, failure.second),
                style = HavenType.small,
                color = h.onToast
            )
        }
    }
}

/** One scope-labelled card: a small accent dot tells device / app / workspace apart. */
@Composable
private fun ScopeCard(title: String, accent: Color, content: @Composable () -> Unit) {
    val h = HavenTheme.colors
    HCard(tight = true) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HDot(color = accent)
                Text(
                    text = title.uppercase(),
                    style = HavenType.section,
                    color = h.muted,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * label · value · optional bar. A metric that could not be measured shows an
 * em dash and NO bar — an empty or indeterminate track would imply data.
 */
@Composable
private fun MetricRow(label: String, value: String, fraction: Float?, accent: Color) {
    val h = HavenTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = HavenType.body,
                color = h.text2,
                modifier = Modifier.weight(1f)
            )
            Text(text = value, style = HavenType.num14, color = h.text)
        }
        if (fraction != null) {
            HProgress(fraction = fraction, color = accent, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Per-core utilization as a compact strip of vertical bars (eight per row)
 * instead of one full-width bar per core. An unmeasured core renders an empty
 * track with an em dash label.
 */
@Composable
private fun PerCoreBars(perCore: Map<Int, Float?>, accent: Color) {
    val h = HavenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        perCore.toSortedMap().entries.toList().chunked(8).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { (core, value) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(h.surface2),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            val fraction = ((value ?: 0f) / 100f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction)
                                    .background(accent)
                            )
                        }
                        Text(
                            text = ResourceText.percent(value),
                            style = HavenType.mono12,
                            color = h.muted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Text(
                            text = "CPU $core",
                            style = HavenType.mono12,
                            color = h.muted
                        )
                    }
                }
                // Keep the last row's bars the same width as a full row's.
                repeat(8 - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** One process card: state dot, name, mono metrics line, expandable details, end-task. */
@Composable
private fun ProcessCard(process: WorkspaceProcessSample, onEnd: () -> Unit) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    var expanded by rememberSaveable(process.pid) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rCard))
            .background(h.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (process.stateChar) {
                'R' -> h.primary
                'S', 'I' -> h.text2
                'D', 'Z', 'T', 't' -> h.amber
                else -> h.red
            }
            HDot(color = dotColor)
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
                        append(" · ${process.memKb?.let { FileSizeFormatter.format(it * 1024L) } ?: "—"}")
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
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
            exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
        ) {
            Column(modifier = Modifier.padding(top = 8.dp, start = 17.dp)) {
                process.command?.let { command ->
                    Text(
                        text = command,
                        style = HavenType.mono12,
                        color = h.text2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                process.parentPid?.let { ppid ->
                    Text(
                        text = stringResource(R.string.tm_process_parent, ppid),
                        style = HavenType.mono12,
                        color = h.muted
                    )
                }
                val details = buildList {
                    process.uid?.let { add(stringResource(R.string.activity_host_uid, it)) }
                    process.threads?.let { add(stringResource(R.string.activity_host_threads, it)) }
                    process.lastCore?.let { add(stringResource(R.string.activity_host_core, it)) }
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = HavenType.mono12,
                        color = h.muted
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!process.isRoot) {
                        HButton(
                            label = stringResource(R.string.tm_end_task),
                            onClick = onEnd,
                            variant = com.fem.linexa.ui.components.haven.HavenButtonVariant.DANGER,
                            size = com.fem.linexa.ui.components.haven.HavenButtonSize.SMALL
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.tm_root_protected),
                            style = HavenType.small,
                            color = h.muted
                        )
                    }
                }
            }
        }
    }
}
