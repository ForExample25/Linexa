package com.fem.linexa.ui.screens.device

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HChipRow
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HLoading
import com.fem.linexa.ui.components.haven.HPill
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HStorageBar
import com.fem.linexa.ui.components.haven.HTile
import com.fem.linexa.ui.components.haven.HVRow
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenColors
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlin.math.roundToInt

/**
 * Device & System Information — the polished validation surface for the
 * Android Host System Information subsystem — HAVEN (concept section 07,
 * `fr-device`).
 *
 * Where every value comes from (the ONLY data path):
 *
 *     Android APIs → HostSystemInfoProvider (typed snapshot)
 *                  → DeviceInfoViewModel (visible-lifetime sampling)
 *                  → DeviceInfoMapper (pure formatting)
 *                  → THIS SCREEN (labels/colors from resources)
 *
 * This composable contains zero measurement logic: no shell-outs, no /proc
 * reads, no Build.* access, no re-formatting of bytes that already arrived
 * formatted. Platform-withheld fields render as an explicit muted dash (never
 * zero, never a guess), and the Capabilities section states WHY each category
 * is unavailable — which is precisely what makes this screen the user-visible
 * proof that the provider tells the truth.
 *
 * Layout hierarchy, per the concept frame: the six summary tiles (Android /
 * CPU / RAM / Storage / Battery / Uptime) first, then the truth in rows —
 * Device / Android / Kernel (with the SELinux posture pill and the Linexa
 * runtime note) / CPU (with the live usage rail and the expandable per-core
 * detail) / Memory & Swap / Storage · Battery · Thermal, the Linexa process
 * footprint, and the capability matrix behind its legend chips. One
 * LazyColumn, no nested scrolling; every section is an [HCard].
 *
 * Header: the concept's `.hdr` (eyebrow "Insights" over the h1) flanked by
 * the pushed-screen back circle (this destination is pushed from Settings —
 * real stack behavior) and the manual-refresh circle. The concept's trailing
 * "jump to Performance" button is omitted: no such callback exists on this
 * screen, and inventing navigation is not a restyle.
 */
@Composable
fun DeviceInfoScreen(
    onBack: () -> Unit,
    viewModel: DeviceInfoViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState() // 2026-08-27 CRASH FIX — collectAsStateWithLifecycle() crashes under lifecycle-runtime-compose 2.8.x + compose-ui 1.6.x + navigation 2.7.x (LocalLifecycleOwner delegation); plain collectAsState is the app-wide pattern, see NavGraph.kt comment

    HavenScreen(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            InsightsHeader(
                stamp = state.collectedAtEpochMs?.let {
                    stringResource(R.string.device_info_updated_at, DeviceInfoMapper.clockLabel(it))
                } ?: stringResource(R.string.device_info_updated_never),
                onBack = onBack,
                onRefresh = viewModel::refresh,
                refreshEnabled = !state.loading && !state.refreshing
            )

            if (state.loading) {
                // Before the FIRST collection completes there is nothing honest
                // to show — blank dash cards would read as "everything
                // unavailable", which no capability evidence has established yet.
                HLoading(label = stringResource(R.string.state_loading))
            } else {
                ContentList(state)
            }
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────────────

/**
 * The concept `.hdr` for a PUSHED screen: back circle in the leading slot,
 * eyebrow + h1 + collection stamp in the middle, refresh circle trailing.
 */
@Composable
private fun InsightsHeader(
    stamp: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HIconCircle(
            iconRes = R.drawable.ic_haven_arrow_back,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack
        )
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = stringResource(R.string.insights_eyebrow),
                style = HavenType.eyebrow,
                color = h.muted,
                modifier = Modifier.padding(bottom = 9.dp)
            )
            Text(
                text = stringResource(R.string.device_info_title),
                style = HavenType.h1,
                color = h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stamp,
                style = HavenType.small,
                color = h.muted,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HIconCircle(
            iconRes = R.drawable.ic_haven_refresh,
            contentDescription = stringResource(R.string.device_info_refresh),
            onClick = onRefresh,
            enabled = refreshEnabled
        )
    }
}

// ── The scroll of section cards ──────────────────────────────────────────────

@Composable
private fun ContentList(state: DeviceInfoState) {
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
        item(key = "tiles") { SummaryTiles(state) }
        item(key = "device") { DeviceCard(state) }
        item(key = "android") { AndroidCard(state) }
        item(key = "kernel") { KernelCard(state) }
        item(key = "cpu") { CpuCard(state) }
        if (state.gpuRows.isNotEmpty() || state.gpuTemperatureRow != null) {
            item(key = "gpu") { GpuCard(state) }
        }
        item(key = "memory") { MemorySwapCard(state) }
        item(key = "storage-battery-thermal") { StorageBatteryThermalCard(state) }
        if (state.selfFootprintRows.any { it.known }) {
            item(key = "footprint") { FootprintCard(state) }
        }
        item(key = "capabilities") { CapabilitiesCard(state.capabilityRows) }
    }
}

// ── Phase 6: the six summary tiles (concept `tgrid3`, two rows) ─────────────

@Composable
private fun SummaryTiles(state: DeviceInfoState) {
    val tiles = listOf(
        stringResource(R.string.device_info_summary_android) to state.summaryAndroid,
        stringResource(R.string.device_info_summary_cpu) to state.summaryCpu,
        stringResource(R.string.device_info_summary_ram) to state.summaryRam,
        stringResource(R.string.device_info_summary_storage) to state.summaryStorage,
        stringResource(R.string.device_info_summary_battery) to state.summaryBattery,
        stringResource(R.string.device_info_summary_uptime) to state.summaryUptime
    )
    Column(verticalArrangement = Arrangement.spacedBy(HavenDim.cardGap)) {
        tiles.chunked(3).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(HavenDim.cardGap)) {
                rowTiles.forEach { (label, value) ->
                    HTile(label = label, value = value, sub = "", modifier = Modifier.weight(1f))
                }
                repeat(3 - rowTiles.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Generic key/value groups ─────────────────────────────────────────────────

/**
 * One reusable card of key/value rows (Device, Android, footprint…). The
 * structural dash renders muted — unknown is quiet, never loud.
 */
@Composable
private fun ValueCard(title: String, rows: List<Pair<String, String>>) {
    HCard {
        Text(
            text = title,
            style = HavenType.rowTitle,
            color = HavenTheme.colors.text
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                DashVRow(label = label, value = value, divider = index < rows.lastIndex)
            }
        }
    }
}

@Composable
private fun DeviceCard(state: DeviceInfoState) {
    ValueCard(
        title = stringResource(R.string.device_section_device),
        rows = listOf(
            stringResource(R.string.device_row_manufacturer) to state.deviceRows.value(InfoRowId.DEVICE_MANUFACTURER),
            stringResource(R.string.device_row_model) to state.deviceRows.value(InfoRowId.DEVICE_MODEL),
            stringResource(R.string.device_row_board) to state.deviceRows.value(InfoRowId.DEVICE_BOARD)
        )
    )
}

@Composable
private fun AndroidCard(state: DeviceInfoState) {
    ValueCard(
        title = stringResource(R.string.device_section_android),
        rows = listOf(
            stringResource(R.string.device_row_sdk) to state.androidRows.value(InfoRowId.ANDROID_SDK),
            stringResource(R.string.device_row_security_patch) to state.androidRows.value(InfoRowId.ANDROID_SECURITY_PATCH),
            stringResource(R.string.device_row_build_id) to state.androidRows.value(InfoRowId.ANDROID_BUILD_ID)
        )
    )
}

@Composable
private fun FootprintCard(state: DeviceInfoState) {
    ValueCard(
        title = stringResource(R.string.device_section_self_footprint),
        rows = state.selfFootprintRows.map { row -> rowLabel(row.id) to row.value }
    )
}

/**
 * The HOST kernel card, deliberately split from everything guests observe:
 * the rows are uname/SystemClock truth about Android's own kernel, the SELinux
 * pill states the security posture (when the platform exposed it at all), and
 * the quiet mono note at the bottom is the Linexa runtime compatibility view —
 * ANDROID HOST and LINEXA LINUX ENVIRONMENT can never be confused.
 */
@Composable
private fun KernelCard(state: DeviceInfoState) {
    val h = HavenTheme.colors
    HCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.device_section_kernel_host),
                style = HavenType.rowTitle,
                color = h.text,
                modifier = Modifier.weight(1f, fill = false)
            )
            val selinux = state.hostKernelRows.firstOrNull { it.id == InfoRowId.SELINUX_STATE }
            if (selinux != null && selinux.known) {
                val enforcing = selinux.value.equals("enforcing", ignoreCase = true)
                HPill(
                    text = stringResource(R.string.device_row_selinux) + " " + selinux.value,
                    color = if (enforcing) h.primary else h.amber,
                    container = if (enforcing) h.tint else h.amberTint
                )
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            DashVRow(
                label = stringResource(R.string.device_row_kernel_release),
                value = state.hostKernelRows.value(InfoRowId.KERNEL_RELEASE_HOST)
            )
            DashVRow(
                label = stringResource(R.string.device_row_deep_sleep),
                value = state.hostKernelRows.value(InfoRowId.DEEP_SLEEP),
                divider = false
            )
        }
        state.linuxEnvironmentNote?.let { note ->
            Column(modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    text = stringResource(R.string.device_section_runtime_linexa),
                    style = HavenType.tileLabel,
                    color = h.primary
                )
                Text(
                    text = note,
                    style = HavenType.descMono,
                    color = h.text2,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

// ── CPU ──────────────────────────────────────────────────────────────────────

@Composable
private fun CpuCard(state: DeviceInfoState) {
    val h = HavenTheme.colors
    var coresExpanded by rememberSaveable { mutableStateOf(false) }

    HCard {
        Text(
            text = stringResource(R.string.device_section_cpu),
            style = HavenType.rowTitle,
            color = h.text
        )

        // Overall usage renders ONLY when /proc/stat was readable this cycle.
        // When RESTRICTED the rail simply does not appear here — and the truth
        // about why lives one section below in the capability matrix. Never 0%.
        state.cpuUsagePercent?.let { usage ->
            HProgress(
                fraction = usage / 100f,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Column(modifier = Modifier.padding(top = 12.dp)) {
            DashVRow(
                label = stringResource(R.string.device_row_cpu_usage),
                value = state.cpuUsagePercent?.let { "${it.roundToInt()}%" } ?: DeviceInfoState.DASH
            )
            DashVRow(
                label = stringResource(R.string.device_row_freq_min),
                value = state.cpuRows.value(InfoRowId.CPU_FREQ_MIN)
            )
            DashVRow(
                label = stringResource(R.string.device_row_freq_max),
                value = state.cpuRows.value(InfoRowId.CPU_FREQ_MAX),
                divider = false
            )
        }

        // Compact expandable per-core detail (Phase 4). Starts collapsed so the
        // default view stays compact on any display size; expansion is saved
        // across configuration changes.
        if (state.cpuCores.isNotEmpty()) {
            ExpandToggle(
                expanded = coresExpanded,
                count = state.cpuCores.size,
                onToggle = { coresExpanded = !coresExpanded }
            )
            if (coresExpanded) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    state.cpuCores.forEach { core -> PerCoreRow(core) }
                }
            }
        }
    }
}

@Composable
private fun PerCoreRow(core: CpuCoreRow) {
    val h = HavenTheme.colors
    val offline = core.online == false
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.device_row_core_label, core.index),
            style = HavenType.mono12,
            color = if (offline) h.muted else h.text2
        )
        val offlineTag = if (offline) stringResource(R.string.device_row_core_offline) else null
        Text(
            text = listOfNotNull(core.usageLabel, core.freqLabel, offlineTag)
                .joinToString(" · ").ifBlank { DeviceInfoState.DASH },
            style = HavenType.mono12,
            color = if (offline) h.muted else h.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/**
 * The GPU card (hardware-info plugin family completion). Rendered only when
 * the device produced at least ONE honest GPU fact — an empty card would read
 * as "the screen forgot the GPU", while an omitted card correctly says the
 * platform withheld all of it (the capability matrix carries that story).
 */
@Composable
private fun GpuCard(state: DeviceInfoState) {
    val h = HavenTheme.colors
    HCard {
        Text(
            text = stringResource(R.string.device_section_gpu),
            style = HavenType.rowTitle,
            color = h.text
        )
        Column(modifier = Modifier.padding(top = 12.dp)) {
            state.gpuRows.forEachIndexed { index, row ->
                DashVRow(
                    label = rowLabel(row.id),
                    value = row.value,
                    divider = index < state.gpuRows.lastIndex || state.gpuTemperatureRow != null
                )
            }
            state.gpuTemperatureRow?.let { tempRow ->
                DashVRow(
                    label = rowLabel(tempRow.id),
                    value = tempRow.value,
                    divider = false
                )
            }
        }
    }
}

@Composable
private fun ExpandToggle(expanded: Boolean, count: Int, onToggle: () -> Unit) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.device_info_collapse_details)
            } else {
                stringResource(R.string.device_info_expand_details)
            },
            style = HavenType.desc.copy(fontWeight = FontWeight.SemiBold),
            color = h.primary
        )
        Text(
            text = "$count",
            style = HavenType.mono12,
            color = h.muted
        )
        Spacer(modifier = Modifier.weight(1f))
        HChevron(expanded = expanded)
    }
}

// ── Memory & Swap ────────────────────────────────────────────────────────────

/**
 * Two explicitly labeled memory views — Linux semantics (what guest tools see
 * via /proc/meminfo overlays) and Android semantics (what ActivityManager will
 * let apps claim) — plus the swap/zRAM composite. A low-memory warning appears
 * only on a REAL sample.
 */
@Composable
private fun MemorySwapCard(state: DeviceInfoState) {
    val h = HavenTheme.colors
    val swapTotal = state.swapRows.value(InfoRowId.SWAP_TOTAL)
    val swapUsed = state.swapRows.value(InfoRowId.SWAP_USED)
    val swapValue = when {
        swapUsed != DeviceInfoState.DASH && swapTotal != DeviceInfoState.DASH -> "$swapUsed / $swapTotal"
        swapTotal != DeviceInfoState.DASH -> swapTotal
        else -> DeviceInfoState.DASH
    }

    HCard {
        Text(
            text = stringResource(R.string.device_section_memory_swap),
            style = HavenType.rowTitle,
            color = h.text
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            DashVRow(
                label = stringResource(R.string.device_row_mem_view_linux),
                value = state.memoryLinuxRows.value(InfoRowId.MEM_USED)
            )
            DashVRow(
                label = stringResource(R.string.device_row_mem_view_android),
                value = state.memoryAndroidRows.value(InfoRowId.AM_AVAILABLE)
            )
            DashVRow(
                label = stringResource(R.string.device_section_swap_zram),
                value = swapValue,
                divider = false
            )
        }
        if (state.memoryPressure == DeviceInfoState.MemoryPressure.LOW_MEMORY_WARNING) {
            HPill(
                text = stringResource(R.string.device_memory_pressure_low),
                color = h.red,
                container = h.redTint,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ── Storage · Battery · Thermal · Network ───────────────────────────────────

/**
 * The posture card: primary volume with its fill bar, battery reading, thermal
 * severity pill and the network transport — the four facts that describe "how
 * is the device doing right now", each honest about being withheld.
 */
@Composable
private fun StorageBatteryThermalCard(state: DeviceInfoState) {
    val h = HavenTheme.colors

    val batteryValue = listOfNotNull(
        state.batteryRows.value(InfoRowId.BATTERY_HEALTH).takeUnless { it == DeviceInfoState.DASH },
        state.batteryRows.value(InfoRowId.BATTERY_TEMPERATURE).takeUnless { it == DeviceInfoState.DASH }
    ).joinToString(" · ").ifBlank { DeviceInfoState.DASH }

    val meteredLabel = when (state.networkMetered) {
        DeviceInfoState.MeteredView.METERED -> stringResource(R.string.device_network_metered)
        DeviceInfoState.MeteredView.UNMETERED -> stringResource(R.string.device_network_unmetered)
        null, DeviceInfoState.MeteredView.UNKNOWN -> null
    }
    val networkValue = listOfNotNull(state.networkType, meteredLabel)
        .joinToString(" · ")
        .ifBlank { DeviceInfoState.DASH }

    HCard {
        Text(
            text = stringResource(R.string.device_section_storage_battery_thermal),
            style = HavenType.rowTitle,
            color = h.text
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            DashVRow(
                label = stringResource(R.string.device_row_storage_internal),
                value = state.summaryStorage,
                divider = false
            )
        }
        state.storagePrimary?.usedFraction?.let { fraction ->
            HStorageBar(
                fraction = fraction,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
        }
        DashVRow(
            label = stringResource(R.string.device_section_battery),
            value = batteryValue
        )
        ThermalRow(state)
        DashVRow(
            label = stringResource(R.string.device_section_network),
            value = networkValue,
            divider = false
        )
    }
}

/**
 * Thermal renders its severity as the concept's pill INSIDE the value slot of
 * the row; with no reading this cycle it degrades to the quiet dash.
 */
@Composable
private fun ThermalRow(state: DeviceInfoState) {
    val h = HavenTheme.colors
    val label = stringResource(R.string.device_section_thermal)
    val thermalView = state.thermalStatus
    if (thermalView == null) {
        DashVRow(label = label, value = DeviceInfoState.DASH)
        return
    }
    val (pillContainer, pillColor) = thermalPresentation(thermalView.severity, h)
    HVRow(
        label = label,
        value = "",
        labelContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = HavenType.desc.copy(fontSize = 13.5.sp),
                    color = h.text2,
                    modifier = Modifier.weight(1f)
                )
                HPill(text = thermalView.label, color = pillColor, container = pillContainer)
            }
        }
    )
}

/** Thermal severity onto the Haven tone pairs (container to content). */
private fun thermalPresentation(
    severity: DeviceInfoState.StatusView.Severity,
    h: HavenColors
): Pair<Color, Color> = when (severity) {
    DeviceInfoState.StatusView.Severity.NONE -> h.tint to h.primary
    DeviceInfoState.StatusView.Severity.LIGHT, DeviceInfoState.StatusView.Severity.MODERATE -> h.amberTint to h.amber
    DeviceInfoState.StatusView.Severity.SEVERE_PLUS -> h.redTint to h.red
    // Constructed mappers never emit UNKNOWN views; a future addition still
    // renders (neutral) rather than crashing the screen.
    DeviceInfoState.StatusView.Severity.UNKNOWN -> h.surface2 to h.muted
}

// ── Capability matrix ────────────────────────────────────────────────────────

/**
 * The availability matrix: per provider category, WHY this refresh did or did
 * not produce trustworthy values, plus each category's own last-collected
 * stamp. Sorted worst-first by the mapper so problems read at the top. The
 * legend chips set expectations before the rows; the note explains the
 * Restricted class.
 */
@Composable
private fun CapabilitiesCard(rows: List<CapabilityRow>) {
    val h = HavenTheme.colors
    HCard {
        Text(
            text = stringResource(R.string.device_section_capabilities),
            style = HavenType.rowTitle,
            color = h.text
        )
        HChipRow(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            HChip(
                label = stringResource(R.string.availability_available),
                selected = true,
                onClick = { }
            )
            HChip(
                label = stringResource(R.string.availability_unavailable),
                selected = false,
                onClick = { }
            )
            LegendChip(
                label = stringResource(R.string.availability_restricted),
                container = h.amberTint,
                content = h.amber
            )
        }
        Text(
            text = stringResource(R.string.device_capability_note),
            style = HavenType.desc,
            color = h.text2
        )
        Column(modifier = Modifier.padding(top = 6.dp)) {
            rows.forEach { capability -> CapabilityEntry(capability) }
        }
    }
}

@Composable
private fun CapabilityEntry(capability: CapabilityRow) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = categoryTitle(capability.category),
                style = HavenType.body,
                color = h.text
            )
            capability.lastCollectedLabel?.let { stamp ->
                Text(
                    text = stringResource(
                        R.string.availability_last_collected,
                        availabilityLabel(capability.availability),
                        stamp
                    ),
                    style = HavenType.descMono,
                    color = h.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        val (pillContainer, pillColor) = availabilityPresentation(capability.availability, h)
        HPill(text = availabilityLabel(capability.availability), color = pillColor, container = pillContainer)
    }
}

/** The static amber legend chip — HChip's geometry in the Restricted tone. */
@Composable
private fun LegendChip(label: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .height(HavenDim.chipH)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = HavenType.desc.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun categoryTitle(category: HostInfoCategory): String = when (category) {
    HostInfoCategory.SYSTEM -> stringResource(R.string.device_section_device)
    HostInfoCategory.CPU -> stringResource(R.string.device_section_cpu)
    HostInfoCategory.GPU -> stringResource(R.string.device_section_gpu)
    HostInfoCategory.MEMORY -> stringResource(R.string.availability_category_memory)
    HostInfoCategory.SWAP -> stringResource(R.string.device_section_swap_zram)
    HostInfoCategory.STORAGE -> stringResource(R.string.device_section_storage)
    HostInfoCategory.BATTERY -> stringResource(R.string.device_section_battery)
    HostInfoCategory.THERMAL -> stringResource(R.string.device_section_thermal)
    HostInfoCategory.NETWORK -> stringResource(R.string.device_section_network)
    HostInfoCategory.PROCESSES -> stringResource(R.string.device_section_self_footprint)
}

@Composable
private fun availabilityLabel(a: HostInfoAvailability): String = when (a) {
    HostInfoAvailability.AVAILABLE -> stringResource(R.string.availability_available)
    HostInfoAvailability.UNAVAILABLE -> stringResource(R.string.availability_unavailable)
    HostInfoAvailability.PERMISSION_DENIED -> stringResource(R.string.availability_permission_denied)
    HostInfoAvailability.UNSUPPORTED -> stringResource(R.string.availability_unsupported)
    HostInfoAvailability.RESTRICTED -> stringResource(R.string.availability_restricted)
    HostInfoAvailability.ERROR -> stringResource(R.string.availability_error)
}

/** Availability onto the Haven tone pairs (container to content). */
private fun availabilityPresentation(a: HostInfoAvailability, h: HavenColors): Pair<Color, Color> = when (a) {
    HostInfoAvailability.AVAILABLE -> h.tint to h.primary
    HostInfoAvailability.RESTRICTED -> h.amberTint to h.amber
    HostInfoAvailability.PERMISSION_DENIED, HostInfoAvailability.ERROR -> h.redTint to h.red
    else -> h.surface2 to h.muted // UNAVAILABLE / UNSUPPORTED — neutral, honest gray
}

// ── Row renderer + shared atoms ──────────────────────────────────────────────

/**
 * ONE label/value renderer for the whole screen — hairline rows with the
 * structural dash rendered muted and announced to accessibility services
 * through the mapper-provided value text.
 */
@Composable
private fun DashVRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true
) {
    val h = HavenTheme.colors
    HVRow(
        label = label,
        value = value,
        modifier = modifier,
        valueColor = if (value == DeviceInfoState.DASH) h.muted else h.text,
        divider = divider
    )
}

/** The pre-formatted value of a mapper row by its stable id, dash when absent. */
private fun List<InfoRow>.value(id: InfoRowId): String =
    firstOrNull { it.id == id }?.value ?: DeviceInfoState.DASH

@Composable
private fun rowLabel(id: InfoRowId): String = when (id) {
    InfoRowId.DEVICE_MANUFACTURER -> stringResource(R.string.device_row_manufacturer)
    InfoRowId.DEVICE_BRAND -> stringResource(R.string.device_row_brand)
    InfoRowId.DEVICE_MODEL -> stringResource(R.string.device_row_model)
    InfoRowId.DEVICE_NAME -> stringResource(R.string.device_row_device_name)
    InfoRowId.DEVICE_CODENAME -> stringResource(R.string.device_row_codename)
    InfoRowId.DEVICE_PRODUCT -> stringResource(R.string.device_row_product)
    InfoRowId.DEVICE_BOARD -> stringResource(R.string.device_row_board)
    InfoRowId.DEVICE_HARDWARE -> stringResource(R.string.device_row_hardware)
    InfoRowId.DEVICE_BOOTLOADER -> stringResource(R.string.device_row_bootloader)
    InfoRowId.ANDROID_VERSION -> stringResource(R.string.device_row_android_version)
    InfoRowId.ANDROID_SDK -> stringResource(R.string.device_row_sdk)
    InfoRowId.ANDROID_SECURITY_PATCH -> stringResource(R.string.device_row_security_patch)
    InfoRowId.ANDROID_BUILD_ID -> stringResource(R.string.device_row_build_id)
    InfoRowId.ANDROID_INCREMENTAL -> stringResource(R.string.device_row_incremental)
    InfoRowId.ANDROID_ABI_LIST -> stringResource(R.string.device_row_abi_list)
    InfoRowId.ANDROID_FINGERPRINT -> stringResource(R.string.device_row_fingerprint)
    InfoRowId.KERNEL_RELEASE_HOST -> stringResource(R.string.device_row_kernel_release)
    InfoRowId.KERNEL_VERSION -> stringResource(R.string.device_row_kernel_version)
    InfoRowId.KERNEL_ARCH -> stringResource(R.string.device_row_kernel_arch)
    InfoRowId.SELINUX_STATE -> stringResource(R.string.device_row_selinux)
    InfoRowId.HOST_UPTIME -> stringResource(R.string.device_row_uptime)
    InfoRowId.DEEP_SLEEP -> stringResource(R.string.device_row_deep_sleep)
    InfoRowId.BOOT_TIME -> stringResource(R.string.device_row_boot_time)
    InfoRowId.CPU_CORES_ONLINE -> stringResource(R.string.device_row_cores_online)
    InfoRowId.CPU_ARCHITECTURE -> stringResource(R.string.device_row_cpu_architecture)
    InfoRowId.CPU_ABIS -> stringResource(R.string.device_row_cpu_abis)
    InfoRowId.CPU_MODEL -> stringResource(R.string.device_row_cpu_model)
    InfoRowId.CPU_IMPLEMENTATION -> stringResource(R.string.device_row_cpu_implementation)
    InfoRowId.CPU_FREQ_MIN -> stringResource(R.string.device_row_freq_min)
    InfoRowId.CPU_FREQ_MAX -> stringResource(R.string.device_row_freq_max)
    InfoRowId.CPU_FREQ_CURRENT -> stringResource(R.string.device_row_freq_current)
    InfoRowId.CPU_LOAD_AVG -> stringResource(R.string.device_row_load_avg)
    InfoRowId.MEM_TOTAL -> stringResource(R.string.device_row_mem_total)
    InfoRowId.MEM_USED -> stringResource(R.string.device_row_mem_used)
    InfoRowId.MEM_AVAILABLE -> stringResource(R.string.device_row_mem_available)
    InfoRowId.MEM_FREE -> stringResource(R.string.device_row_mem_free)
    InfoRowId.MEM_BUFFERS_CACHED -> stringResource(R.string.device_row_mem_buffers_cached)
    InfoRowId.AM_TOTAL -> stringResource(R.string.device_row_am_total)
    InfoRowId.AM_AVAILABLE -> stringResource(R.string.device_row_am_available)
    InfoRowId.AM_THRESHOLD -> stringResource(R.string.device_row_am_threshold)
    InfoRowId.MEMORY_CLASS -> stringResource(R.string.device_row_memory_class)
    InfoRowId.SWAP_TOTAL -> stringResource(R.string.device_row_swap_total)
    InfoRowId.SWAP_USED -> stringResource(R.string.device_row_swap_used)
    InfoRowId.SWAP_FREE -> stringResource(R.string.device_row_swap_free)
    InfoRowId.ZRAM_PRESENT -> stringResource(R.string.device_row_zram)
    InfoRowId.ZRAM_DISKSIZE -> stringResource(R.string.device_row_zram)
    InfoRowId.STORAGE_PRIMARY_PATH -> stringResource(R.string.device_row_primary_path)
    InfoRowId.BATTERY_SOURCE -> stringResource(R.string.device_row_battery_source)
    InfoRowId.BATTERY_HEALTH -> stringResource(R.string.device_row_battery_health)
    InfoRowId.BATTERY_TEMPERATURE -> stringResource(R.string.device_row_battery_temperature)
    InfoRowId.BATTERY_VOLTAGE -> stringResource(R.string.device_row_battery_voltage)
    InfoRowId.BATTERY_CURRENT -> stringResource(R.string.device_row_battery_current)
    InfoRowId.BATTERY_CHARGE_COUNTER -> stringResource(R.string.device_row_charge_counter)
    InfoRowId.BATTERY_CAPACITY_DESIGN -> stringResource(R.string.device_row_capacity_design)
    InfoRowId.BATTERY_POWER_SAVE -> stringResource(R.string.device_row_power_save)
    InfoRowId.NETWORK_VALIDATED -> stringResource(R.string.device_row_net_validated)
    InfoRowId.NETWORK_INTERFACE -> stringResource(R.string.device_row_net_interface)
    InfoRowId.NETWORK_DOWNLINK -> stringResource(R.string.device_row_net_downlink)
    InfoRowId.NETWORK_PRIVATE_DNS -> stringResource(R.string.device_row_private_dns)
    InfoRowId.PROCESS_SELF_PID -> stringResource(R.string.device_row_process_pid)
    InfoRowId.PROCESS_SELF_RSS -> stringResource(R.string.device_row_process_rss)
    InfoRowId.PROCESS_SELF_PSS -> stringResource(R.string.device_row_process_pss)
    InfoRowId.PROCESS_OTHER_APPS -> stringResource(R.string.device_note_no_value)

    InfoRowId.CPU_TEMP -> stringResource(R.string.device_row_cpu_temp)

    InfoRowId.GPU_MODEL -> stringResource(R.string.device_row_gpu_model)
    InfoRowId.GPU_VENDOR -> stringResource(R.string.device_row_gpu_vendor)
    InfoRowId.GPU_FREQ_CURRENT -> stringResource(R.string.device_row_gpu_freq_current)
    InfoRowId.GPU_FREQ_MAX -> stringResource(R.string.device_row_gpu_freq_max)
    InfoRowId.GPU_BUSY -> stringResource(R.string.device_row_gpu_busy)
    InfoRowId.GPU_TEMP -> stringResource(R.string.device_row_gpu_temp)
    InfoRowId.GPU_SOURCE -> stringResource(R.string.device_row_gpu_source)
}
