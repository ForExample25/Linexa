package com.fem.linexa.ui.screens.device

import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostInfoCategory

/**
 * The Device/System Information screen's render state — a pure, Compose-free
 * type produced by [DeviceInfoMapper] and consumed by [DeviceInfoScreen].
 *
 * Same layering discipline as [com.fem.linexa.ui.components.WorkspaceCardContent]
 * and [com.fem.linexa.ui.screens.settings.GlobalSettingsContent]: everything
 * data-dependent is decided HERE (in JVM tests), while the composable only
 * resolves labels/colors from stable ids. Labels therefore live at the screen
 * layer as string resources ([InfoRowId] → res lookup); values arrive
 * pre-formatted by the mapper so no formatting logic can drift between
 * composition passes.
 *
 * Two contract rules inherited from the host-info subsystem, enforced here in
 * shape rather than prose:
 *
 * - **Known vs unknown is structural.** Every [InfoRow.value] paired with
 *   [InfoRow.known] == false renders as an explicit dash; the screen NEVER
 *   reparses a value to decide whether it is missing.
 * - **ANDROID HOST ≠ LINEXA LINUX ENVIRONMENT.** Host facts and the guest
 *   compatibility view are separate top-level fields
 *   ([hostKernelRows] vs [linuxEnvironmentNote]) so neither wording nor
 *   layout can merge them accidentally.
 */
data class DeviceInfoState(
    /** True until the first snapshot arrives; the screen shows its loader. */
    val loading: Boolean,
    /**
     * Epoch ms the current content was collected at, null never set. While a
     * refresh is IN FLIGHT this keeps the previous stamp — the screen renders
     * staleness from it instead of pretending old values are new (Phase 7).
     */
    val collectedAtEpochMs: Long?,
    val refreshing: Boolean,
    // ── Phase 6 summary header ──
    val summaryModel: String,
    val summaryAndroid: String,
    val summaryCpu: String,
    val summaryRam: String,
    val summaryStorage: String,
    val summaryBattery: String,
    val summaryUptime: String,
    // ── CPU ──
    val cpuRows: List<InfoRow>,
    val cpuUsagePercent: Float?,
    val cpuCores: List<CpuCoreRow>,
    // ── GPU ──

    /**
     * Graphics silicon rows (model/vendor/clocks/busy). The mapper emits NO
     * rows when the device withheld everything, so the screen's GPU card can
     * collapse entirely instead of showing a wall of dashes.
     */
    val gpuRows: List<InfoRow>,
    val gpuTemperatureRow: InfoRow?,
    // ── Memory ──
    val memoryLinuxRows: List<InfoRow>,
    val memoryAndroidRows: List<InfoRow>,
    val memoryPressure: MemoryPressure,
    // ── Swap / zRAM ──
    val swapRows: List<InfoRow>,
    // ── Storage ──
    val storagePrimary: StorageVolumeBar?,
    val storageSecondaryRows: List<InfoRow>,
    val storageOtherVolumes: List<StorageVolumeLine>,
    // ── Identity groups ──
    val deviceRows: List<InfoRow>,
    val androidRows: List<InfoRow>,
    val hostKernelRows: List<InfoRow>,
    val linuxEnvironmentNote: String?,
    val selfFootprintRows: List<InfoRow>,
    // ── Battery ──
    val batteryPercent: Int?,
    val batteryChargingLabel: ChargingView,
    val batteryRows: List<InfoRow>,
    // ── Thermal ──
    val thermalStatus: StatusView?,
    val thermalHeadroom: String?,

    /**
     * Battery temperature as seen by the THERMAL view (mirrored same-cycle
     * by the provider). Real zone sensors (CPU/GPU/skin) are HAL-withheld on
     * Android and are described by a static screen caption instead of rows.
     */
    val thermalBatteryTempRow: InfoRow?,
    // ── Network ──
    val networkType: String?,
    val networkMetered: MeteredView?,
    val networkDetailRows: List<InfoRow>,
    // ── Capability matrix (Phase 4 "Capabilities / Availability") ──
    val capabilityRows: List<CapabilityRow>
) {
    companion object {
        /** The explicit dash used app-wide for "the platform withheld this". */
        const val DASH = "—"

        /** Pre-first-collection placeholder: loading, nothing fabricated. */
        val LOADING = DeviceInfoState(
            loading = true,
            collectedAtEpochMs = null,
            refreshing = false,
            summaryModel = DASH,
            summaryAndroid = DASH,
            summaryCpu = DASH,
            summaryRam = DASH,
            summaryStorage = DASH,
            summaryBattery = DASH,
            summaryUptime = DASH,
            cpuRows = emptyList(),
            cpuUsagePercent = null,
            cpuCores = emptyList(),
            gpuRows = emptyList(),
            gpuTemperatureRow = null,
            memoryLinuxRows = emptyList(),
            memoryAndroidRows = emptyList(),
            memoryPressure = MemoryPressure.UNKNOWN,
            swapRows = emptyList(),
            storagePrimary = null,
            storageSecondaryRows = emptyList(),
            storageOtherVolumes = emptyList(),
            deviceRows = emptyList(),
            androidRows = emptyList(),
            hostKernelRows = emptyList(),
            linuxEnvironmentNote = null,
            selfFootprintRows = emptyList(),
            batteryPercent = null,
            batteryChargingLabel = ChargingView.UNDECIDED,
            batteryRows = emptyList(),
            thermalStatus = null,
            thermalHeadroom = null,
            thermalBatteryTempRow = null,
            networkType = null,
            networkMetered = null,
            networkDetailRows = emptyList(),
            capabilityRows = defaultCapabilities()
        )
    }

    enum class ChargingView { CHARGING, FULL, DISCHARGING, UNDECIDED }

    enum class MemoryPressure { LOW_MEMORY_OK, LOW_MEMORY_WARNING, UNKNOWN }

    enum class MeteredView { METERED, UNMETERED, UNKNOWN }

    /**
     * Thermal severity rendered structurally, so the screen picks its color
     * once here instead of re-parsing the lowercase severity label.
     */
    data class StatusView(val label: String, val severity: Severity) {
        enum class Severity { NONE, LIGHT, MODERATE, SEVERE_PLUS, UNKNOWN }
    }
}

/** One key/value display row. [known]=false renders the explicit dash. */
data class InfoRow(val id: InfoRowId, val value: String, val known: Boolean = true)

/**
 * Stable identifiers for every key/value row the Device info screen can show.
 * The screen maps each id to its label string resource; the mapper decides
 * WHICH rows exist and whether their value is known. Ids are exhaustive by
 * construction — adding a row means adding an id, which fails loudly at the
 * screen's label lookup if left unresolved.
 */
enum class InfoRowId {
    DEVICE_MANUFACTURER, DEVICE_BRAND, DEVICE_MODEL, DEVICE_NAME, DEVICE_CODENAME,
    DEVICE_PRODUCT, DEVICE_BOARD, DEVICE_HARDWARE, DEVICE_BOOTLOADER,

    ANDROID_VERSION, ANDROID_SDK, ANDROID_SECURITY_PATCH, ANDROID_BUILD_ID,
    ANDROID_INCREMENTAL, ANDROID_ABI_LIST, ANDROID_FINGERPRINT,

    KERNEL_RELEASE_HOST, KERNEL_VERSION, KERNEL_ARCH, SELINUX_STATE,
    HOST_UPTIME, DEEP_SLEEP, BOOT_TIME,

    CPU_CORES_ONLINE, CPU_ARCHITECTURE, CPU_ABIS, CPU_MODEL, CPU_IMPLEMENTATION,
    CPU_FREQ_MIN, CPU_FREQ_MAX, CPU_FREQ_CURRENT, CPU_LOAD_AVG, CPU_TEMP,

    GPU_MODEL, GPU_VENDOR, GPU_SOURCE, GPU_FREQ_CURRENT, GPU_FREQ_MAX, GPU_BUSY, GPU_TEMP,

    MEM_TOTAL, MEM_USED, MEM_AVAILABLE, MEM_FREE, MEM_BUFFERS_CACHED,
    AM_TOTAL, AM_AVAILABLE, AM_THRESHOLD, MEMORY_CLASS,

    SWAP_TOTAL, SWAP_USED, SWAP_FREE, ZRAM_PRESENT, ZRAM_DISKSIZE,

    STORAGE_PRIMARY_PATH,
    BATTERY_SOURCE, BATTERY_HEALTH, BATTERY_TEMPERATURE,
    BATTERY_VOLTAGE, BATTERY_CURRENT, BATTERY_CHARGE_COUNTER, BATTERY_CAPACITY_DESIGN,
    BATTERY_POWER_SAVE,

    NETWORK_VALIDATED, NETWORK_INTERFACE, NETWORK_PRIVATE_DNS, NETWORK_DOWNLINK,

    PROCESS_SELF_PID, PROCESS_SELF_RSS, PROCESS_SELF_PSS, PROCESS_OTHER_APPS
}

/**
 * One per-core row of the expandable CPU detail. Frequency values arrive
 * pre-formatted; online stays structural so the screen can dim offline cores.
 */
data class CpuCoreRow(
    val index: Int,
    val online: Boolean?,
    val freqLabel: String?,
    /** Pre-formatted busy percent 0..100 for this core; null when unmeasured. */
    val usageLabel: String? = null
)

/** A volume with both bytes and its rendering; drives the storage bar. */
data class StorageVolumeBar(val name: String, val totalBytes: Long?, val freeBytes: Long?) {
    /**
     * Bar fill fraction (used share) when computable. Clamped defensively;
     * a StatFs quirk reporting free > total renders as empty, never negative.
     */
    val usedFraction: Float?
        get() {
            val t = totalBytes ?: return null
            val f = freeBytes ?: return null
            if (t <= 0L) return null
            return (t - f).coerceAtLeast(0L).toFloat() / t.toFloat()
        }
}

/** One mounted-volume line in the storage section. */
data class StorageVolumeLine(val name: String, val capacityLabel: String, val stateLabel: String?)

/**
 * One capability-matrix entry: the WHY-behind-the-value state of a whole
 * host-info category. Carries the DOMAIN availability directly — no
 * translation layer, no chance the matrix disagrees with the provider.
 */
data class CapabilityRow(
    val category: HostInfoCategory,
    val availability: HostInfoAvailability,
    val lastCollectedLabel: String?
)

private fun defaultCapabilities(): List<CapabilityRow> =
    HostInfoCategory.entries.map { CapabilityRow(it, HostInfoAvailability.UNAVAILABLE, null) }
