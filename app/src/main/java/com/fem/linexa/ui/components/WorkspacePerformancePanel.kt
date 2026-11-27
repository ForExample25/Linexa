package com.fem.linexa.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.ui.theme.LinexaColors
import com.fem.linexa.ui.theme.Spacing
import com.fem.linexa.ui.theme.asMono

/**
 * The workspace performance panel (UI/UX Phase 5B) — a compact, htop-flavoured
 * rectangle at the top of the workspace surface showing CPU, memory and storage
 * for **this workspace only**.
 *
 * Scope is the point of the whole component: the numbers come from the process
 * tree rooted at the pid in [RuntimeState.Running]
 * ([com.fem.linexa.data.resource.ProcResourceMonitor]), so they describe the
 * workspace's `proot` supervisor and its guest children — not the device, and not
 * the Linexa app. The panel says so in its own subtitle, because a resource
 * readout with an unstated denominator is the easiest kind of number to
 * misread.
 *
 * Every value can be *unknown*, and unknown renders as a dash with an empty
 * track. That distinction is the panel's main correctness property, decided by
 * the pure [WorkspacePerformanceContent] rather than here: a stopped workspace,
 * a running workspace whose `/proc` tree could not be read, and a genuinely idle
 * one must not all display `0%`.
 *
 * @param runtimeState the workspace's live state; supplies the pid and decides
 *   whether the process metrics mean anything at all.
 * @param workspaceId which workspace's storage to measure.
 */
@Composable
fun WorkspacePerformancePanel(
    runtimeState: RuntimeState,
    workspaceId: String,
    modifier: Modifier = Modifier,
    viewModel: WorkspacePerformanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pid = (runtimeState as? RuntimeState.Running)?.pid

    // P0-5 (2026-11-23): composition alone is not visibility — a workspace
    // destination stays composed behind another screen or while the app is
    // backgrounded, and this poller reads /proc every 2 s. Key start/pause on
    // the lifecycle foreground flag; DisposableEffect remains the final
    // "left composition" stop. start() is idempotent, pause() cancels safely.
    val foreground by rememberIsForeground()
    LaunchedEffect(workspaceId, pid, foreground) {
        if (foreground) viewModel.start(workspaceId, pid) else viewModel.pause()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.pause() }
    }

    val content = remember(runtimeState, state.sample, state.storage) {
        WorkspacePerformanceContent.from(
            state = runtimeState,
            sample = state.sample,
            storage = state.storage
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PanelHeader(processCount = content.processCount, processLabel = content.processLabel)

            Spacer(modifier = Modifier.height(Spacing.md))

            MetricGauge(
                label = stringResource(R.string.resource_cpu),
                metric = content.cpu,
                history = state.cpuHistory,
                color = LinexaColors.Cpu,
                iconRes = R.drawable.ic_custom_cpu,
                qualifier = if (content.active && content.cpu.known) {
                    stringResource(R.string.resource_cpu_cores, state.sample?.cpuCores ?: 0)
                } else {
                    null
                }
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            MetricGauge(
                label = stringResource(R.string.resource_ram),
                metric = content.memory,
                history = state.memoryHistory,
                color = LinexaColors.Ram,
                iconRes = R.drawable.ic_custom_memory,
                qualifier = if (content.memoryApproximate) {
                    stringResource(R.string.resource_ram_approximate)
                } else {
                    null
                }
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            MetricGauge(
                label = stringResource(R.string.resource_storage),
                metric = content.storage,
                // Storage is a level line by nature — a sparkline of a value that
                // changes only on install would be a flat line pretending to be a
                // chart, so this row deliberately has none.
                history = emptyList(),
                color = LinexaColors.Disk,
                iconRes = R.drawable.ic_custom_storage,
                qualifier = state.storage?.freeLabel?.let {
                    stringResource(R.string.resource_storage_free, it)
                }
            )
        }
    }
}

/**
 * Title row: what the panel measures, and how many processes it found.
 *
 * The subtitle is not decoration. "This workspace" is the one fact a reader needs
 * to interpret every number below it, and the plan's critical scope requirement
 * for this panel is precisely that the metrics not be mistaken for the phone's or
 * the app's.
 */
@Composable
private fun PanelHeader(processCount: Int?, processLabel: String?) {
    val label = processCount?.let {
        pluralStringResource(R.plurals.workspace_process_count, it, it)
    } ?: processLabel
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.resource_monitor_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.resource_monitor_scope),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.asMono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One metric: icon, label, optional qualifier, value, bar, and (when it has one)
 * a sparkline of recent history.
 *
 * The bar and the sparkline are separate on purpose — the bar answers "how much
 * of the available capacity right now", the sparkline "what has it been doing" —
 * and a metric with an unknown value gets an empty track rather than a zero-width
 * bar, which would read as a confident "none".
 */
@Composable
private fun MetricGauge(
    label: String,
    metric: WorkspacePerformanceContent.Metric,
    history: List<Float>,
    color: Color,
    iconRes: Int,
    qualifier: String?
) {
    val target = metric.fraction ?: 0f
    val animated by animateFloatAsState(targetValue = target, label = "metric-$label")

    // One announcement for the whole row: read out piecemeal, "CPU", "of 8
    // cores", "12%" and a bar arrive as four unrelated fragments.
    val description = buildString {
        append(label)
        append(": ")
        append(metric.value)
        if (qualifier != null) {
            append(", ")
            append(qualifier)
        }
    }

    Column(modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (qualifier != null) {
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = qualifier,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = metric.value,
                style = MaterialTheme.typography.labelLarge.asMono(),
                fontWeight = FontWeight.Medium,
                color = if (metric.known) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            MetricBar(
                fraction = if (metric.fraction == null) null else animated,
                color = color,
                modifier = Modifier.weight(1f)
            )
            if (history.size >= 2) {
                MiniSparkline(
                    values = history,
                    color = color,
                    modifier = Modifier
                        .width(56.dp)
                        .height(14.dp)
                )
            }
        }
    }
}

/**
 * A thin capacity bar. A null [fraction] draws the track only — the visual form
 * of "unknown", as distinct from a bar of length zero.
 */
@Composable
private fun MetricBar(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(6.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(3.dp)
            )
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(color = color, shape = RoundedCornerShape(3.dp))
            )
        }
    }
}
