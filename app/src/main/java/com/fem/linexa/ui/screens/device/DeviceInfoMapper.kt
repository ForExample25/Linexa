package com.fem.linexa.ui.screens.device

import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuCore
import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot
import java.util.Locale

/**
 * Pure mapper: [HostSystemInfoSnapshot] → [DeviceInfoState].
 *
 * This file is the Device-info screen's side of the §18 contract — the ONLY
 * input is the typed provider snapshot; there is no Process, no shell, no
 * `/proc` read, no Linux-command parsing anywhere in this package, and the
 * wiring source test pins that.
 *
 * Data-accuracy chain this mapper implements (Phase 8):
 *
 *     Android API → AndroidHostInfoSources (sole platform touchpoint)
 *                 → HostSystemInfoSnapshot (immutable, capability-stamped)
 *                 → THIS MAPPER (formatting only — no re-measurement,
 *                    no independent calculation, no /proc reads)
 *                 → DeviceInfoState → composable labels/colors
 *
 * Formatting rules follow the repository's "unknown ≠ zero" contract: every
 * field the platform withheld renders as an explicit dash and carries
 * known=false so the screen can mute it. Derived quantities are computed ONLY
 * from fields the model itself defines as derivable (volume.usedBytes,
 * swap.usedKb), never re-derived here from raw bytes twice.
 *
 * English literals inside pure mappers are the established precedent
 * ([com.fem.linexa.ui.screens.performance.PerformanceDashboardMapper] —
 * string resources live at the composable layer so mappers stay JVM-runnable).
 */
object DeviceInfoMapper {

    // ── Entry point ─────────────────────────────────────────────────────────

    fun from(
        snapshot: HostSystemInfoSnapshot?,
        refreshing: Boolean = false,
        labels: DeviceInfoLabels = DeviceInfoLabels.English
    ): DeviceInfoState {
        if (snapshot == null) return DeviceInfoState.LOADING

        val device = snapshot.device
        val cpu = snapshot.cpu

        return DeviceInfoState(
            loading = false,
            collectedAtEpochMs = snapshot.collectedAtEpochMs,
            refreshing = refreshing,
            summaryModel = modelLabel(device.manufacturer, device.model),
            summaryAndroid = androidVersionLabel(device.androidRelease, device.sdkInt),
            summaryCpu = summaryCpuLabel(cpu.logicalCores, cpu.architecture, labels),
            summaryRam = formatKb(snapshot.memory.memTotalKb ?: snapshot.memory.androidTotalKb),
            summaryStorage = storageSummary(snapshot, labels),
            summaryBattery = batterySummary(snapshot.battery.percent, chargingView(snapshot.battery.charging)),
            summaryUptime = uptimeLabel(device.uptimeMillis, labels),

            cpuRows = buildCpuRows(snapshot, labels),
            cpuUsagePercent = cpu.overallUsagePercent?.coerceIn(0f, 100f),
            cpuCores = run {
                val perCore = cpu.perCoreUsagePercent
                cpu.coresPerCoreInfo.map { core ->
                    // The usage list is indexed by KERNEL core number, not by
                    // position in coresPerCoreInfo. An offline cpu1 therefore
                    // leaves a null slot instead of shifting cpu2's value into
                    // cpu1's row.
                    cpuCoreRow(core, perCore.getOrNull(core.index), labels)
                }
            },

            gpuRows = buildGpuRows(snapshot.gpu),
            gpuTemperatureRow = gpuTemperatureRow(snapshot),

            memoryLinuxRows = buildMemoryLinuxRows(snapshot.memory, labels),
            memoryAndroidRows = buildMemoryAndroidRows(snapshot.memory),
            memoryPressure = memoryPressureOf(snapshot.memory.isLowMemory),

            swapRows = buildSwapRows(snapshot.swap, snapshot.memory.zram, labels),

            storagePrimary = snapshot.storage.primaryVolume?.let { primary ->
                StorageVolumeBar(
                    name = primary.description ?: shortPath(primary.path),
                    totalBytes = primary.totalBytes,
                    freeBytes = primary.freeBytes
                )
            },
            storageSecondaryRows = buildStorageSecondaryRows(snapshot),
            storageOtherVolumes = otherVolumeLines(snapshot, labels),

            deviceRows = buildDeviceRows(device),
            androidRows = buildAndroidRows(device, cpu.supportedAbis),
            hostKernelRows = buildHostKernelRows(device, labels),
            linuxEnvironmentNote = linuxEnvironmentNote(),
            selfFootprintRows = buildSelfFootprintRows(snapshot),

            batteryPercent = snapshot.battery.percent,
            batteryChargingLabel = chargingView(snapshot.battery.charging),
            batteryRows = buildBatteryRows(snapshot.battery, labels),

            thermalStatus = thermalStatusView(snapshot.thermal.status, snapshot.thermal.severityLabel, labels),
            thermalHeadroom = snapshot.thermal.headroom?.let { oneDecimal(it) },
            // Battery temperature is the ONE real temperature Android exposes;
            // it was collected WITH this cycle's battery sample and mirrored
            // into thermal by the provider. Absent → NO row at all (the
            // thermal card then shows only its honesty caption), never a dash
            // masquerading as a reading.
            thermalBatteryTempRow = snapshot.thermal.batteryTemperatureC?.let { temp ->
                InfoRow(
                    id = InfoRowId.BATTERY_TEMPERATURE,
                    value = "${oneDecimal(temp)} °C",
                    known = true
                )
            },

            networkType = snapshot.network.primaryTransport,
            networkMetered = meteredView(snapshot.network.isMetered),
            networkDetailRows = buildNetworkDetailRows(snapshot.network, labels),

            capabilityRows = snapshot.capabilities.map { (category, availability) ->
                CapabilityRow(
                    category = category,
                    availability = availability,
                    lastCollectedLabel = snapshot.collectedAtPerCategory[category]?.let(::clockLabel)
                )
            }.sortedBy { availabilitySortKey(it.availability) }
        )
    }

    // ── Summary header ──────────────────────────────────────────────────────

    private fun summaryCpuLabel(cores: Int?, arch: String?, labels: DeviceInfoLabels): String =
        listOfNotNull(
            cores?.let { labels.text(R.string.device_summary_cores, it) },
            arch
        ).joinToString(" · ")
            .ifBlank { DeviceInfoState.DASH }

    private fun storageSummary(s: HostSystemInfoSnapshot, labels: DeviceInfoLabels): String =
        s.storage.primaryVolume?.freeBytes?.let { free ->
            val usedPart = s.storage.primaryVolume?.usedBytes?.let {
                "${FileSizeFormatter.format(it)} / "
            } ?: ""
            "$usedPart${labels.text(R.string.device_storage_free, FileSizeFormatter.format(free))}"
        } ?: DeviceInfoState.DASH

    private fun batterySummary(percent: Int?, charging: DeviceInfoState.ChargingView): String =
        when (percent) {
            null -> DeviceInfoState.DASH
            else -> when (charging) {
                DeviceInfoState.ChargingView.CHARGING -> "$percent% ⚡"
                else -> "$percent%"
            }
        }

    // ── CPU ─────────────────────────────────────────────────────────────────

    private fun buildCpuRows(s: HostSystemInfoSnapshot, labels: DeviceInfoLabels): List<InfoRow> {
        val cpu = s.cpu
        return listOf(
            // Online/configured pairing keeps meaning even when sysfs refused:
            // "— / 8" says "count known, online-set withheld" honestly.
            InfoRow(
                id = InfoRowId.CPU_CORES_ONLINE,
                value = listOfNotNull(
                    cpu.onlineCores?.toString() ?: DeviceInfoState.DASH,
                    "/", cpu.logicalCores.toString()
                ).joinToString(""),
                known = true
            ),
            row(InfoRowId.CPU_ARCHITECTURE) { cpu.architecture ?: s.device.machineArch },
            row(InfoRowId.CPU_ABIS) { cpu.supportedAbis.joinToString(", ") },
            row(InfoRowId.CPU_MODEL) { cpu.cpuModelName },
            row(InfoRowId.CPU_IMPLEMENTATION) { cpu.cpuImplementation },
            row(InfoRowId.CPU_FREQ_MIN) { cpu.minFrequencyKhz?.let(::frequencyLabel) },
            row(InfoRowId.CPU_FREQ_MAX) { cpu.maxFrequencyKhz?.let(::frequencyLabel) },
            row(InfoRowId.CPU_FREQ_CURRENT) { cpu.currentFrequencyKhz?.let(::frequencyLabel) },
            row(InfoRowId.CPU_LOAD_AVG) { loadAvgLabel(cpu.loadAvg1m, cpu.loadAvg5m, cpu.loadAvg15m) },
            row(InfoRowId.CPU_TEMP) { s.thermal.cpuTemperatureC?.let { "${oneDecimal(it)} °C" } }
        )
    }

    // ── GPU ─────────────────────────────────────────────────────────────

    /**
     * Graphics rows. Every field the device withheld is OMITTED (not a dash
     * row): a GPU the platform cannot describe renders as an empty card in
     * the screen (gated on [DeviceInfoState.gpuRows].isEmpty()), not as a
     * column of unknowns implying a collector that never ran.
     */
    private fun buildGpuRows(g: com.fem.linexa.domain.hostinfo.HostGpuInfo): List<InfoRow> {
        val rows = mutableListOf<InfoRow>()
        val model = g.model
        if (!model.isNullOrBlank()) {
            rows += InfoRow(id = InfoRowId.GPU_MODEL, value = model, known = true)
            g.vendorFamily?.let { vendor ->
                rows += InfoRow(id = InfoRowId.GPU_VENDOR, value = vendor, known = true)
            }
        }
        g.clockKhz?.let { rows += row(InfoRowId.GPU_FREQ_CURRENT) { frequencyLabel(it) } }
        g.maxClockKhz?.let { rows += row(InfoRowId.GPU_FREQ_MAX) { frequencyLabel(it) } }
        g.busyPercent?.let { rows += InfoRow(id = InfoRowId.GPU_BUSY, value = "$it%", known = true) }
        g.sourceLabel?.takeIf { g.model != null }?.let { source ->
            rows += InfoRow(id = InfoRowId.GPU_SOURCE, value = source, known = true)
        }
        return rows
    }

    /**
     * GPU zone temperature lives in the THERMAL card (same home as the
     * battery temperature row) — it is a temperature, not an identity. null
     * when the kernel denied every gpu-named zone.
     */
    private fun gpuTemperatureRow(s: HostSystemInfoSnapshot): InfoRow? =
        s.thermal.gpuTemperatureC?.let { temp ->
            InfoRow(
                id = InfoRowId.GPU_TEMP,
                value = "${oneDecimal(temp)} °C",
                known = true
            )
        }

    /**
     * Per-core expandable rows. A core readable in sysfs gets its current/max
     * clock; unreadable-but-enumerated cores render their online state alone;
     * nothing is ever invented.
     */
    internal fun cpuCoreRow(
        core: HostCpuCore,
        usagePercent: Float? = null,
        labels: DeviceInfoLabels = DeviceInfoLabels.English
    ): CpuCoreRow = CpuCoreRow(
        index = core.index,
        online = core.online,
        freqLabel = when {
            core.currentKhz == null && core.maxKhz == null -> null
            else -> listOfNotNull(
                core.currentKhz?.let(::frequencyLabel),
                core.maxKhz?.let(::frequencyLabel)
                    ?.let { labels.text(R.string.device_core_max, it) }
            ).joinToString(" · ")
        },
        usageLabel = usagePercent?.let { String.format(Locale.US, "%.0f%%", it) }
    )

    // ── Memory ──────────────────────────────────────────────────────────────

    /**
     * LINUX SEMANTICS block — /proc/meminfo values, i.e. what guest tools
     * (`free`) report. Kept separate from Android semantics per HostMemoryInfo:
     * mixing the two IS the plausible-wrong-number bug class.
     */
    private fun buildMemoryLinuxRows(
        m: com.fem.linexa.domain.hostinfo.HostMemoryInfo,
        labels: DeviceInfoLabels
    ): List<InfoRow> =
        listOf(
            row(InfoRowId.MEM_TOTAL) { m.memTotalKb?.let(::formatKb) },
            row(InfoRowId.MEM_USED) { m.usedLinuxKb?.let(::formatKb) },
            row(InfoRowId.MEM_AVAILABLE) { m.memAvailableKb?.let(::formatKb) },
            row(InfoRowId.MEM_FREE) { m.memFreeKb?.let(::formatKb) },
            row(InfoRowId.MEM_BUFFERS_CACHED) {
                cacheGroupLabel(m.buffersKb, m.cachedKb, m.sReclaimableKb, labels)
            }
        )

    /** ANDROID SEMANTICS block — ActivityManager.MemoryInfo values. */
    private fun buildMemoryAndroidRows(m: com.fem.linexa.domain.hostinfo.HostMemoryInfo): List<InfoRow> =
        listOf(
            row(InfoRowId.AM_TOTAL) { m.androidTotalKb?.let(::formatKb) },
            row(InfoRowId.AM_AVAILABLE) { m.androidAvailKb?.let(::formatKb) },
            row(InfoRowId.AM_THRESHOLD) { m.androidThresholdKb?.let(::formatKb) },
            row(InfoRowId.MEMORY_CLASS) { m.memoryClassMb?.takeIf { it > 0 }?.let { "$it MB" } }
        )

    private fun cacheGroupLabel(
        buffersKb: Long?,
        cachedKb: Long?,
        sReclaimableKb: Long?,
        labels: DeviceInfoLabels
    ): String? {
        val parts = listOfNotNull(
            buffersKb?.let { labels.text(R.string.device_memory_buffers, formatKb(it)) },
            cachedKb?.let { labels.text(R.string.device_memory_cached, formatKb(it)) },
            sReclaimableKb?.let {
                labels.text(R.string.device_memory_reclaimable, formatKb(it))
            }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun memoryPressureOf(isLowMemory: Boolean?): DeviceInfoState.MemoryPressure = when (isLowMemory) {
        true -> DeviceInfoState.MemoryPressure.LOW_MEMORY_WARNING
        false -> DeviceInfoState.MemoryPressure.LOW_MEMORY_OK
        null -> DeviceInfoState.MemoryPressure.UNKNOWN
    }

    // ── Swap / zRAM ─────────────────────────────────────────────────────────

    private fun buildSwapRows(
        s: com.fem.linexa.domain.hostinfo.HostSwapInfo,
        zram: com.fem.linexa.domain.hostinfo.HostZramInfo?,
        labels: DeviceInfoLabels
    ): List<InfoRow> = listOf(
        row(InfoRowId.SWAP_TOTAL) { s.totalKb?.takeIf { it > 0 }?.let(::formatKb) },
        row(InfoRowId.SWAP_USED) { s.usedKb?.takeIf { (s.totalKb ?: 0L) > 0 }?.let(::formatKb) },
        row(InfoRowId.SWAP_FREE) { s.freeKb?.takeIf { (s.totalKb ?: 0L) > 0 }?.let(::formatKb) },
        InfoRow(
            id = InfoRowId.ZRAM_PRESENT,
            // present=true without a readable disksize → "present", not a size lie.
            value = when {
                zram == null -> DeviceInfoState.DASH
                zram.diskSizeBytes != null -> FileSizeFormatter.format(zram.diskSizeBytes!!)
                zram.present == true -> labels.text(R.string.device_value_present)
                else -> DeviceInfoState.DASH
            },
            known = zram != null && (zram.diskSizeBytes != null || zram.present == true)
        )
    )

    // ── Storage ─────────────────────────────────────────────────────────────

    private fun buildStorageSecondaryRows(s: HostSystemInfoSnapshot): List<InfoRow> =
        s.storage.primaryVolume?.let { p ->
            listOf(row(InfoRowId.STORAGE_PRIMARY_PATH) { p.path })
        } ?: emptyList()

    /** Additional volumes beyond the primary, deduped by mount path. */
    private fun otherVolumeLines(
        s: HostSystemInfoSnapshot,
        labels: DeviceInfoLabels
    ): List<StorageVolumeLine> {
        val primaryPath = s.storage.primaryVolume?.path
        return s.storage.allVolumes
            .filter { it.totalBytes != null && it.path != primaryPath }
            .distinctBy { it.path }
            .map { v ->
                StorageVolumeLine(
                    name = v.description ?: shortPath(v.path),
                    capacityLabel = listOfNotNull(
                        v.totalBytes?.let(FileSizeFormatter::format),
                        v.freeBytes?.let {
                            labels.text(R.string.device_storage_free, FileSizeFormatter.format(it))
                        }
                    ).joinToString("  ·  "),
                    stateLabel = v.state
                )
            }
    }

    // ── Identity groups ─────────────────────────────────────────────────────

    private fun buildDeviceRows(d: com.fem.linexa.domain.hostinfo.HostDeviceInfo): List<InfoRow> = listOf(
        row(InfoRowId.DEVICE_MANUFACTURER) { d.manufacturer },
        row(InfoRowId.DEVICE_BRAND) { d.brand },
        row(InfoRowId.DEVICE_MODEL) { d.model },
        row(InfoRowId.DEVICE_NAME) { d.deviceName },
        row(InfoRowId.DEVICE_CODENAME) { d.device },
        row(InfoRowId.DEVICE_PRODUCT) { d.product },
        row(InfoRowId.DEVICE_BOARD) { d.board },
        row(InfoRowId.DEVICE_HARDWARE) { d.hardware },
        row(InfoRowId.DEVICE_BOOTLOADER) { d.bootloader }
    )

    private fun buildAndroidRows(
        d: com.fem.linexa.domain.hostinfo.HostDeviceInfo,
        abis: List<String>
    ): List<InfoRow> = listOf(
        InfoRow(
            id = InfoRowId.ANDROID_VERSION,
            value = androidVersionLabel(d.androidRelease, d.sdkInt),
            known = !d.androidRelease.isNullOrBlank()
        ),
        row(InfoRowId.ANDROID_SDK) { d.sdkInt.takeIf { it > 0 }?.toString() },
        row(InfoRowId.ANDROID_SECURITY_PATCH) { d.securityPatch },
        row(InfoRowId.ANDROID_BUILD_ID) { d.buildId },
        row(InfoRowId.ANDROID_INCREMENTAL) { d.incremental },
        row(InfoRowId.ANDROID_ABI_LIST) { abis.joinToString(", ") },
        row(InfoRowId.ANDROID_FINGERPRINT) { d.fingerprint }
    )

    private fun buildHostKernelRows(
        d: com.fem.linexa.domain.hostinfo.HostDeviceInfo,
        labels: DeviceInfoLabels
    ): List<InfoRow> = listOf(
        row(InfoRowId.KERNEL_RELEASE_HOST) { d.kernelRelease },
        row(InfoRowId.KERNEL_VERSION) { d.kernelVersion },
        row(InfoRowId.KERNEL_ARCH) { d.machineArch },
        row(InfoRowId.SELINUX_STATE) {
            d.selinuxEnforcing?.let { enforcing ->
                labels.text(
                    if (enforcing) R.string.device_selinux_enforcing
                    else R.string.device_selinux_permissive
                )
            }
        },
        row(InfoRowId.HOST_UPTIME) { uptimeLabel(d.uptimeMillis, labels) },
        row(InfoRowId.DEEP_SLEEP) { d.deepSleepMillis?.let { uptimeLabel(it, labels) } },
        row(InfoRowId.BOOT_TIME) { d.bootTimeEpochMillis?.let(::epochLabel) }
    )

    /**
     * The one honest line about what guests see vs what the host runs,
     * sourced from the SAME constant the bridge writes into the guest
     * /proc/version overlays — displayed as context beneath the host kernel
     * block, never merged into it (ANDROID HOST ≠ LINEXA LINUX ENVIRONMENT).
     *
     * Reflective read keeps this mapper JVM-runnable without dragging the
     * runtime-bridge package into the UI layer's dependency set (the ui↮
     * runtime boundary is pinned by ArchUnit); a failed lookup simply omits
     * the note instead of printing a wrong release string.
     */
    internal fun linuxEnvironmentNote(): String? = try {
        Class.forName("com.fem.linexa.service.runtime.hostinfo.ProcSnapshotContentFactory")
            .getField("SPOOF_RELEASE").get(null) as String
    } catch (_: Throwable) {
        null
    }

    private fun buildSelfFootprintRows(s: HostSystemInfoSnapshot): List<InfoRow> {
        val processes = s.processes
        return listOf(
            row(InfoRowId.PROCESS_SELF_PID) { processes.selfPid.takeIf { it > 0 }?.toString() },
            row(InfoRowId.PROCESS_SELF_RSS) { processes.selfRssKb?.let(::formatKb) },
            row(InfoRowId.PROCESS_SELF_PSS) { processes.selfPssKb?.let(::formatKb) }
        )
    }

    // ── Battery ─────────────────────────────────────────────────────────────

    private fun powerSourceLabelRes(source: HostBatteryInfo.PowerSource): Int = when (source) {
        HostBatteryInfo.PowerSource.AC -> R.string.device_power_source_ac
        HostBatteryInfo.PowerSource.USB -> R.string.device_power_source_usb
        HostBatteryInfo.PowerSource.WIRELESS -> R.string.device_power_source_wireless
        HostBatteryInfo.PowerSource.DOCK -> R.string.device_power_source_dock
        HostBatteryInfo.PowerSource.NONE -> R.string.device_power_source_none
        HostBatteryInfo.PowerSource.UNKNOWN -> R.string.device_power_source_unknown
    }

    private fun batteryHealthLabelRes(health: HostBatteryInfo.Health): Int = when (health) {
        HostBatteryInfo.Health.GOOD -> R.string.device_health_good
        HostBatteryInfo.Health.OVERHEAT -> R.string.device_health_overheat
        HostBatteryInfo.Health.DEAD -> R.string.device_health_dead
        HostBatteryInfo.Health.OVERVOLTAGE -> R.string.device_health_overvoltage
        HostBatteryInfo.Health.COLD -> R.string.device_health_cold
        HostBatteryInfo.Health.UNSPECIFIED_FAILURE -> R.string.device_health_unspecified_failure
        HostBatteryInfo.Health.UNKNOWN -> R.string.device_health_unknown
    }

    private fun buildBatteryRows(b: HostBatteryInfo, labels: DeviceInfoLabels): List<InfoRow> = listOf(
        InfoRow(
            id = InfoRowId.BATTERY_SOURCE,
            value = labels.text(powerSourceLabelRes(b.powerSource)),
            known = b.powerSource != HostBatteryInfo.PowerSource.UNKNOWN
        ),
        InfoRow(
            id = InfoRowId.BATTERY_HEALTH,
            value = labels.text(batteryHealthLabelRes(b.health)),
            known = b.health != HostBatteryInfo.Health.UNKNOWN
        ),
        row(InfoRowId.BATTERY_TEMPERATURE) { b.temperatureC?.let { "${oneDecimal(it)} °C" } },
        row(InfoRowId.BATTERY_VOLTAGE) { b.voltageMillivolts?.takeIf { it > 0 }?.let { "$it mV" } },
        row(InfoRowId.BATTERY_CURRENT) {
            b.currentMicroamps?.let { microamps ->
                val signed = if (microamps > 0) "+$microamps" else "$microamps"
                "$signed µA"
            }
        },
        row(InfoRowId.BATTERY_CHARGE_COUNTER) { b.chargeCounterMicroAh?.let(::milliAhLabel) },
        // No public Android API reports design capacity — kept as an explicit
        // known-unavailable row so users see a labeled gap, not a mystery.
        InfoRow(id = InfoRowId.BATTERY_CAPACITY_DESIGN, value = DeviceInfoState.DASH, known = false),
        InfoRow(
            id = InfoRowId.BATTERY_POWER_SAVE,
            value = b.isPowerSaveMode?.let {
                labels.text(if (it) R.string.device_value_on else R.string.device_value_off)
            } ?: DeviceInfoState.DASH,
            known = b.isPowerSaveMode != null
        )
    )

    internal fun chargingView(state: HostBatteryInfo.ChargingState): DeviceInfoState.ChargingView =
        when (state) {
            HostBatteryInfo.ChargingState.CHARGING -> DeviceInfoState.ChargingView.CHARGING
            HostBatteryInfo.ChargingState.FULL -> DeviceInfoState.ChargingView.FULL
            HostBatteryInfo.ChargingState.DISCHARGING -> DeviceInfoState.ChargingView.DISCHARGING
            HostBatteryInfo.ChargingState.UNKNOWN -> DeviceInfoState.ChargingView.UNDECIDED
        }

    private fun milliAhLabel(microAh: Int): String {
        // Charge counter arrives in µAh; render at mAh granularity to match how
        // battery specs are written ("6000 mAh", never "6_000_000 µAh").
        return "${microAh / 1000L} mAh"
    }

    // ── Thermal ─────────────────────────────────────────────────────────────

    internal fun thermalStatusView(
        status: com.fem.linexa.domain.hostinfo.HostThermalInfo.Status?,
        severityLabel: String?,
        labels: DeviceInfoLabels = DeviceInfoLabels.English
    ): DeviceInfoState.StatusView? {
        status ?: return null
        val severity = when (status) {
            com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.NONE ->
                DeviceInfoState.StatusView.Severity.NONE
            com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.LIGHT ->
                DeviceInfoState.StatusView.Severity.LIGHT
            com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.MODERATE ->
                DeviceInfoState.StatusView.Severity.MODERATE
            // SEVERE/CRITICAL/EMERGENCY/SHUTDOWN share one urgent treatment;
            // every step past MODERATE means the user should care NOW.
            else -> DeviceInfoState.StatusView.Severity.SEVERE_PLUS
        }
        val localized = labels.text(thermalLabelRes(status))
        return DeviceInfoState.StatusView(
            label = localized.ifBlank { severityLabel ?: status.name.lowercase(Locale.US) },
            severity = severity
        )
    }

    private fun thermalLabelRes(
        status: com.fem.linexa.domain.hostinfo.HostThermalInfo.Status
    ): Int = when (status) {
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.NONE -> R.string.device_thermal_none
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.LIGHT -> R.string.device_thermal_light
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.MODERATE -> R.string.device_thermal_moderate
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.SEVERE -> R.string.device_thermal_severe
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.CRITICAL -> R.string.device_thermal_critical
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.EMERGENCY -> R.string.device_thermal_emergency
        com.fem.linexa.domain.hostinfo.HostThermalInfo.Status.SHUTDOWN -> R.string.device_thermal_shutdown
    }

    // ── Network ─────────────────────────────────────────────────────────────

    private fun meteredView(isMetered: Boolean?): DeviceInfoState.MeteredView? = when (isMetered) {
        true -> DeviceInfoState.MeteredView.METERED
        false -> DeviceInfoState.MeteredView.UNMETERED
        null -> null
    }

    private fun buildNetworkDetailRows(
        n: com.fem.linexa.domain.hostinfo.HostNetworkInfo,
        labels: DeviceInfoLabels
    ): List<InfoRow> =
        listOf(
            row(InfoRowId.NETWORK_VALIDATED) {
                n.isValidated?.let {
                    labels.text(
                        if (it) R.string.device_network_internet
                        else R.string.device_network_connected_only
                    )
                }
            },
            row(InfoRowId.NETWORK_INTERFACE) { n.interfaceName },
            row(InfoRowId.NETWORK_DOWNLINK) {
                n.downlinkKbps?.takeIf { it > 0 }?.let { "$it Kbps*" }
            },
            row(InfoRowId.NETWORK_PRIVATE_DNS) {
                n.isPrivateDnsActive?.let {
                    labels.text(if (it) R.string.device_value_on else R.string.device_value_off)
                }
            }
        )

    // ── Capability matrix ───────────────────────────────────────────────────

    /** RESTRICTED/ERROR surface first in the matrix; healthy categories sink. */
    internal fun availabilitySortKey(a: HostInfoAvailability): Int = when (a) {
        HostInfoAvailability.ERROR -> 0
        HostInfoAvailability.RESTRICTED -> 1
        HostInfoAvailability.PERMISSION_DENIED -> 2
        HostInfoAvailability.UNAVAILABLE -> 3
        HostInfoAvailability.UNSUPPORTED -> 4
        HostInfoAvailability.AVAILABLE -> 5
    }

    // ── Shared formatting helpers ────────────────────────────────────────────

    /** kB → human label through the shared formatter (never raw bytes shown). */
    internal fun formatKb(kb: Long?): String =
        kb?.let { FileSizeFormatter.format(it * 1024L) } ?: DeviceInfoState.DASH

    internal fun frequencyLabel(khz: Long): String = when {
        khz >= 1_000_000L -> "%.2f GHz".format(Locale.US, khz / 1_000_000.0)
        khz >= 1000L -> "%d MHz".format(Locale.US, khz / 1000L)
        else -> "$khz kHz"
    }

    internal fun loadAvgLabel(l1: Float?, l5: Float?, l15: Float?): String? {
        // Load average cannot be measured at all until the first average exists;
        // partial later columns render as dashes inside a live prefix.
        l1 ?: return null
        return String.format(
            Locale.US, "%.2f %s %s",
            l1, l5?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            l15?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        )
    }

    internal fun uptimeLabel(
        millis: Long?,
        labels: DeviceInfoLabels = DeviceInfoLabels.English
    ): String {
        if (millis == null || millis < 0L) return DeviceInfoState.DASH
        val totalMinutes = millis / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        val seconds = (millis / 1000L) % 60
        return when {
            days > 0 -> labels.text(
                R.string.device_uptime_days_hours_minutes, days, hours, minutes
            )
            hours > 0 -> labels.text(R.string.device_uptime_hours_minutes, hours, minutes)
            minutes > 0 -> labels.text(R.string.device_uptime_minutes_seconds, minutes, seconds)
            else -> labels.text(R.string.device_uptime_seconds, seconds)
        }
    }

    /** ISO-style numeric stamp; locale-safe digits ordering, no month names. */
    internal fun epochLabel(epochMs: Long): String {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%04d-%02d-%02d %02d:%02d".format(
            Locale.US,
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )
    }

    internal fun clockLabel(epochMs: Long): String {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%02d:%02d:%02d".format(
            Locale.US,
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
    }

    internal fun modelLabel(manufacturer: String?, model: String?): String = when {
        manufacturer.isNullOrBlank() && model.isNullOrBlank() -> DeviceInfoState.DASH
        manufacturer.isNullOrBlank() -> model!!
        model.isNullOrBlank() -> manufacturer
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }

    internal fun androidVersionLabel(release: String?, sdk: Int): String =
        listOfNotNull(release, sdk.takeIf { it > 0 }?.let { "(API $it)" })
            .joinToString(" ").ifBlank { DeviceInfoState.DASH }

    private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun shortPath(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    /**
     * Builder keeping the `null/blank value → dash + known=false` invariant in
     * ONE place; every row constructor routes through it so no call site can
     * accidentally pass an unknown forward as blank text.
     */
    private inline fun row(id: InfoRowId, value: () -> String?): InfoRow {
        val resolved = value()
        return if (resolved.isNullOrBlank()) {
            InfoRow(id = id, value = DeviceInfoState.DASH, known = false)
        } else {
            InfoRow(id = id, value = resolved, known = true)
        }
    }
}
