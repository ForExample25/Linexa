package com.fem.linexa.ui.screens.device

import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuCore
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostStorageVolume
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot
import com.fem.linexa.domain.hostinfo.HostThermalInfo
import com.fem.linexa.domain.hostinfo.HostZramInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Device-info screen's pure mapper — the layer where the
 * §18 "typed data, never command parsing" contract becomes renderable strings.
 *
 * These tests assert the Phase 8 accuracy chain end-to-end from the only legal
 * input (a typed [HostSystemInfoSnapshot]): formatting, known/unknown honesty,
 * the capability matrix ordering, and the ANDROID-HOST vs LINEXA-ENVIRONMENT
 * separation. The mapper must never need Android on the classpath.
 */
class DeviceInfoMapperTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun fullDevice() = HostDeviceInfo(
        androidRelease = "15", sdkInt = 35, codename = "REL", incremental = "2025051501",
        securityPatch = "2025-05-05", manufacturer = "Google", brand = "google",
        model = "Pixel 8", product = "husky", device = "husky", board = "shiba",
        hardware = "zuma", bootloader = "hap-1.0-1",
        fingerprint = "google/husky/husky:15/AP4A/123:user/release-keys",
        kernelRelease = "6.6.89-android15", kernelVersion = "#1 SMP PREEMPT",
        machineArch = "aarch64", selinuxEnforcing = true,
        uptimeMillis = 3_789_000L, deepSleepMillis = 900_000L, bootTimeEpochMillis = null,
        deviceName = "My Pixel", buildId = "AP4A.250105.002"
    )

    private fun fullCpu(cores: List<HostCpuCore> = emptyList()) = HostCpuInfo(
        logicalCores = 8, onlineCores = 8, architecture = "aarch64",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        cpuModelName = "Cortex-X4", cpuImplementation = "0x41",
        coresPerCoreInfo = cores,
        minFrequencyKhz = 720_000L, maxFrequencyKhz = 2_988_000L, currentFrequencyKhz = 1_800_000L,
        overallUsagePercent = 37.5f, perCoreUsagePercent = emptyList(),
        loadAvg1m = 1.25f, loadAvg5m = 2.5f, loadAvg15m = null,
        totalStatTicks = null, idleInclIowaitStatTicks = null
    )

    private fun fullMemory() = HostMemoryInfo(
        memTotalKb = 64_000_000L, memFreeKb = 10_000_000L, memAvailableKb = 32_000_000L,
        buffersKb = 500_000L, cachedKb = 20_000_000L, sReclaimableKb = 5_000_000L,
        usedLinuxKb = 32_000_000L,
        androidTotalKb = 63_800_000L, androidAvailKb = 18_000_000L, androidThresholdKb = 1_500_000L,
        isLowMemory = false,
        swap = HostSwapInfo(totalKb = 4_000_000L, freeKb = 1_000_000L, usedKb = 3_000_000L),
        zram = HostZramInfo(present = true, diskSizeBytes = 4_294_967_296L),
        memoryClassMb = 256, largeMemoryClassMb = 512
    )

    private fun primaryVolume() = HostStorageVolume(
        id = "primary", description = "Internal storage",
        path = "/data/user/0/com.fem.linexa/files", kind = HostStorageVolume.Kind.INTERNAL_PRIMARY,
        state = "mounted", totalBytes = 128_000_000_000L, freeBytes = 60_000_000_000L
    )

    private fun fullSnapshot(): HostSystemInfoSnapshot {
        val capabilities = mapOf(
            HostInfoCategory.CPU to HostInfoAvailability.RESTRICTED,
            HostInfoCategory.GPU to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.SYSTEM to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.MEMORY to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.SWAP to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.STORAGE to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.BATTERY to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.THERMAL to HostInfoAvailability.AVAILABLE,
            HostInfoCategory.NETWORK to HostInfoAvailability.UNAVAILABLE,
            HostInfoCategory.PROCESSES to HostInfoAvailability.ERROR
        )
        return HostSystemInfoSnapshot(
            collectedAtEpochMs = 1_700_000_000_123L,
            cpu = fullCpu(),
            memory = fullMemory(),
            swap = HostSwapInfo(totalKb = 4_000_000L, freeKb = 1_000_000L, usedKb = 3_000_000L),
            storage = HostStorageInfo(
                primaryVolume = primaryVolume(),
                externalAppVolume = null, sharedStorageVolume = null,
                allVolumes = listOf(
                    primaryVolume(),
                    HostStorageVolume(
                        id = "sdcard", description = "SD card",
                        path = "/storage/0000-1111", kind = HostStorageVolume.Kind.OTHER,
                        state = "mounted", totalBytes = 32_000_000_000L, freeBytes = 31_000_000_000L
                    )
                )
            ),
            device = fullDevice(),
            battery = HostBatteryInfo(
                present = true, percent = 84,
                charging = HostBatteryInfo.ChargingState.CHARGING,
                powerSource = HostBatteryInfo.PowerSource.AC,
                health = HostBatteryInfo.Health.GOOD,
                temperatureC = 28.4f, voltageMillivolts = 4350, currentMicroamps = -320_000,
                chargeCounterMicroAh = 4_200_000, designCapacityMilliampHours = null,
                isPowerSaveMode = false
            ),
            thermal = HostThermalInfo(
                status = HostThermalInfo.Status.LIGHT, severityLabel = "light", headroom = 0.85f,
                cpuTemperatureC = null, gpuTemperatureC = null, skinTemperatureC = null,
                batteryTemperatureC = 28.4f
            ),
            network = HostNetworkInfo(
                isActive = true, transports = listOf("vpn", "wifi"), primaryTransport = "wifi",
                isValidated = true, isMetered = false, downlinkKbps = 120_000, uplinkKbps = null,
                interfaceName = "wlan0", ipv4Addresses = listOf("192.168.1.7"), ipv6Addresses = emptyList(),
                dnsServers = listOf("192.168.1.1"), isPrivateDnsActive = false
            ),
            processes = HostProcessInfo(
                selfPid = 4242, selfProcessName = "com.fem.linexa",
                selfRssKb = 180_000L, selfPssKb = 140_000L, selfCpuTicks = 12345L,
                otherAppsVisibility = HostInfoAvailability.RESTRICTED
            ),
            capabilities = capabilities,
            collectedAtPerCategory = mapOf(HostInfoCategory.SYSTEM to 1_700_000_000_100L)
        )
    }

    private fun findRow(rows: List<InfoRow>, id: InfoRowId): InfoRow? = rows.firstOrNull { it.id == id }

    // ── Null / loading ───────────────────────────────────────────────────────

    @Test
    fun `null snapshot maps to the loading placeholder with zero fabricated values`() {
        val state = DeviceInfoMapper.from(null)
        assertEquals(DeviceInfoState.LOADING, state)
        assertTrue(state.loading)
        assertNull(state.collectedAtEpochMs)
        assertEquals(DeviceInfoState.DASH, state.summaryRam)
    }

    // ── Summary header (Phase 6) ─────────────────────────────────────────────

    @Test
    fun `summary merges manufacturer and model`() {
        val state = DeviceInfoMapper.from(fullSnapshot())
        assertEquals("Google Pixel 8", state.summaryModel)
    }

    @Test
    fun `summary keeps model when it already starts with manufacturer`() {
        val device = fullDevice().copy(manufacturer = "pixel")
        assertEquals("Pixel 8", DeviceInfoMapper.from(fullSnapshot().copy(device = device)).summaryModel)
    }

    @Test
    fun `summary android pairs release with api level and summary cpu pairs cores with arch`() {
        val state = DeviceInfoMapper.from(fullSnapshot())
        assertEquals("15 (API 35)", state.summaryAndroid)
        assertEquals("8 cores · aarch64", state.summaryCpu)
    }

    @Test
    fun `charging batteries show the charging mark in the summary`() {
        assertEquals("84% ⚡", DeviceInfoMapper.from(fullSnapshot()).summaryBattery)
    }

    @Test
    fun `unknown everything still renders a complete dash-shaped summary`() {
        val state = DeviceInfoMapper.from(HostSystemInfoSnapshot.empty(atEpochMs = 42L))
        assertFalse(state.loading)
        assertEquals(DeviceInfoState.DASH, state.summaryModel)
        assertEquals(DeviceInfoState.DASH, state.summaryRam)
        assertEquals(DeviceInfoState.DASH, state.summaryBattery)
        assertEquals(DeviceInfoState.DASH, state.summaryUptime)
    }

    // ── Formatting helpers (the Phase 8 UI columns) ──────────────────────────

    @Test
    fun `kB formatting routes through the shared byte formatter`() {
        // 64,000,000 kB * 1024 = exactly 61.0 GiB
        assertEquals("61.0 GB", DeviceInfoMapper.formatKb(64_000_000L))
        assertEquals(DeviceInfoState.DASH, DeviceInfoMapper.formatKb(null))
    }

    @Test
    fun `frequency labels choose unit by magnitude`() {
        assertEquals("720 MHz", DeviceInfoMapper.frequencyLabel(720_000L))
        assertEquals("2.99 GHz", DeviceInfoMapper.frequencyLabel(2_988_000L))
        assertEquals("500 kHz", DeviceInfoMapper.frequencyLabel(500L))
    }

    @Test
    fun `uptime scales through seconds minutes hours days`() {
        assertEquals("45s", DeviceInfoMapper.uptimeLabel(45_000L))
        assertEquals("5m 30s", DeviceInfoMapper.uptimeLabel(330_000L))
        assertEquals("3h 41m", DeviceInfoMapper.uptimeLabel(13_300_000L))
        assertEquals("1d 2h 30m", DeviceInfoMapper.uptimeLabel(95_400_000L))
        assertEquals(DeviceInfoState.DASH, DeviceInfoMapper.uptimeLabel(null))
    }

    @Test
    fun `load average renders partial sets without inventing values`() {
        assertEquals("1.25 2.50 —", DeviceInfoMapper.loadAvgLabel(1.25f, 2.5f, null))
        assertNull(DeviceInfoMapper.loadAvgLabel(null, 2.5f, 3f))
    }

    // ── CPU section ──────────────────────────────────────────────────────────

    @Test
    fun `cores-online row keeps meaning when sysfs withheld online count`() {
        val row = findRow(DeviceInfoMapper.from(fullSnapshot()).cpuRows, InfoRowId.CPU_CORES_ONLINE)!!
        assertEquals("8/8", row.value)

        val snapshot = fullSnapshot().copy(cpu = fullCpu().copy(onlineCores = null))
        val withheldRow = findRow(DeviceInfoMapper.from(snapshot).cpuRows, InfoRowId.CPU_CORES_ONLINE)!!
        assertEquals("${DeviceInfoState.DASH}/8", withheldRow.value)
        assertTrue(withheldRow.known)
    }

    @Test
    fun `cpu architecture falls back to uname machine before going unknown`() {
        val snapshot = fullSnapshot().copy(cpu = fullCpu().copy(architecture = null))
        assertEquals(
            "aarch64",
            findRow(DeviceInfoMapper.from(snapshot).cpuRows, InfoRowId.CPU_ARCHITECTURE)?.value
        )
    }

    @Test
    fun `usage value clamps into percent range instead of overflowing bars`() {
        assertEquals(37.5f, DeviceInfoMapper.from(fullSnapshot()).cpuUsagePercent!!)
        val overflowing = fullSnapshot().copy(cpu = fullCpu().copy(overallUsagePercent = 143f))
        assertEquals(100f, DeviceInfoMapper.from(overflowing).cpuUsagePercent!!)
    }

    @Test
    fun `per-core usage is paired by kernel core index so an offline core leaves a gap`() {
        val cores = listOf(
            HostCpuCore(index = 0, online = true, minKhz = null, maxKhz = null, currentKhz = null),
            HostCpuCore(index = 1, online = false, minKhz = null, maxKhz = null, currentKhz = null),
            HostCpuCore(index = 2, online = true, minKhz = null, maxKhz = null, currentKhz = null)
        )
        // cpu0=10%, cpu1=unavailable, cpu2=30% — list index IS the core number.
        val snapshot = fullSnapshot().copy(
            cpu = fullCpu(cores).copy(perCoreUsagePercent = listOf(10f, null, 30f))
        )

        val rows = DeviceInfoMapper.from(snapshot).cpuCores
        assertEquals("10%", rows.first { it.index == 0 }.usageLabel)
        assertNull(rows.first { it.index == 1 }.usageLabel)
        assertEquals("30%", rows.first { it.index == 2 }.usageLabel)
    }

    @Test
    fun `per-core rows pair current with max frequency and never invent one`() {
        val coreFull = DeviceInfoMapper.cpuCoreRow(
            HostCpuCore(index = 3, online = true, minKhz = null, maxKhz = 2_400_000L, currentKhz = 1_200_000L)
        )
        assertEquals("1.20 GHz · max 2.40 GHz", coreFull.freqLabel)

        val coreBlind = DeviceInfoMapper.cpuCoreRow(
            HostCpuCore(index = 4, online = null, minKhz = null, maxKhz = null, currentKhz = null)
        )
        assertNull(coreBlind.freqLabel)
    }

    // ── Memory / swap / zRAM ─────────────────────────────────────────────────

    @Test
    fun `linux and android memory views stay separated as two row groups`() {
        val state = DeviceInfoMapper.from(fullSnapshot())
        assertEquals(5, state.memoryLinuxRows.size)
        assertEquals(4, state.memoryAndroidRows.size)
        // 61.0 GiB physical (meminfo) vs Android's own accounting below it.
        assertEquals("61.0 GB", findRow(state.memoryLinuxRows, InfoRowId.MEM_TOTAL)?.value)
        assertTrue(findRow(state.memoryAndroidRows, InfoRowId.AM_TOTAL)?.value != null)
    }

    @Test
    fun `zero swap renders dashes while existing swap shows real numbers`() {
        val noSwap = fullSnapshot().copy(
            swap = HostSwapInfo(totalKb = 0L, freeKb = 0L, usedKb = 0L),
            memory = fullMemory().copy(swap = HostSwapInfo(totalKb = 0L, freeKb = 0L, usedKb = 0L))
        )
        val dashTotal = findRow(DeviceInfoMapper.from(noSwap).swapRows, InfoRowId.SWAP_TOTAL)!!
        assertFalse(dashTotal.known)
        assertEquals(DeviceInfoState.DASH, dashTotal.value)

        val liveTotal = findRow(DeviceInfoMapper.from(fullSnapshot()).swapRows, InfoRowId.SWAP_TOTAL)!!
        assertEquals("3.8 GB", liveTotal.value)
    }

    @Test
    fun `zram presence without readable disksize says present instead of a size lie`() {
        val snapshot = fullSnapshot().copy(
            memory = fullMemory().copy(zram = HostZramInfo(present = true, diskSizeBytes = null))
        )
        val zramRow = findRow(DeviceInfoMapper.from(snapshot).swapRows, InfoRowId.ZRAM_PRESENT)!!
        assertEquals("present", zramRow.value)
        assertTrue(zramRow.known)
    }

    @Test
    fun `no zram signal at all renders an explicitly unknown row`() {
        val snapshot = fullSnapshot().copy(
            memory = fullMemory().copy(zram = HostZramInfo(present = null, diskSizeBytes = null))
        )
        val zramRow = findRow(DeviceInfoMapper.from(snapshot).swapRows, InfoRowId.ZRAM_PRESENT)!!
        assertFalse(zramRow.known)
        assertEquals(DeviceInfoState.DASH, zramRow.value)
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    @Test
    fun `storage summary pairs used over free for the primary volume`() {
        val summary = DeviceInfoMapper.from(fullSnapshot()).summaryStorage
        assertTrue(summary.contains("/"))
        assertTrue(summary.contains("free"))
    }

    @Test
    fun `other-volume lines exclude the primary path but keep genuinely distinct mounts`() {
        val state = DeviceInfoMapper.from(fullSnapshot())
        assertEquals(1, state.storageOtherVolumes.size)
        assertEquals("SD card", state.storageOtherVolumes[0].name)
        assertTrue(state.storageOtherVolumes[0].capacityLabel.contains("free"))
    }

    @Test
    fun `volume bar fraction derives from model-defined derivables only`() {
        val bar = StorageVolumeBar(name = "p", totalBytes = 128_000_000_000L, freeBytes = 60_000_000_000L)
        assertEquals(0.53125f, bar.usedFraction!!, 0.001f)
        assertNull(StorageVolumeBar(name = "x", totalBytes = null, freeBytes = null).usedFraction)
    }

    // ── Battery / thermal ────────────────────────────────────────────────────

    @Test
    fun `battery design capacity stays the explicit known-unavailable row`() {
        val row = findRow(
            DeviceInfoMapper.from(fullSnapshot()).batteryRows,
            InfoRowId.BATTERY_CAPACITY_DESIGN
        )!!
        assertFalse(row.known)
        assertEquals(DeviceInfoState.DASH, row.value)
    }

    @Test
    fun `charge counter renders at mAh granularity`() {
        val row = findRow(
            DeviceInfoMapper.from(fullSnapshot()).batteryRows,
            InfoRowId.BATTERY_CHARGE_COUNTER
        )!!
        assertEquals("4200 mAh", row.value)
    }

    @Test
    fun `signed current keeps its sign so discharge reads honestly`() {
        val row = findRow(DeviceInfoMapper.from(fullSnapshot()).batteryRows, InfoRowId.BATTERY_CURRENT)!!
        assertEquals("-320000 µA", row.value)
    }

    @Test
    fun `thermal severity escalates past moderate into the severe bucket`() {
        assertEquals(
            DeviceInfoState.StatusView.Severity.NONE,
            DeviceInfoMapper.thermalStatusView(HostThermalInfo.Status.NONE, "none")?.severity
        )
        assertEquals(
            DeviceInfoState.StatusView.Severity.MODERATE,
            DeviceInfoMapper.thermalStatusView(HostThermalInfo.Status.MODERATE, "moderate")?.severity
        )
        assertEquals(
            DeviceInfoState.StatusView.Severity.SEVERE_PLUS,
            DeviceInfoMapper.thermalStatusView(HostThermalInfo.Status.SHUTDOWN, "shutdown")?.severity
        )
        assertNull(DeviceInfoMapper.thermalStatusView(null, null))
    }

    @Test
    fun `thermal battery temperature is mirrored same-cycle or absent together`() {
        assertEquals("28.4 °C", DeviceInfoMapper.from(fullSnapshot()).thermalBatteryTempRow?.value)

        val cold = fullSnapshot().copy(thermal = fullSnapshot().thermal.copy(batteryTemperatureC = null))
        assertNull(DeviceInfoMapper.from(cold).thermalBatteryTempRow)
    }

    // ── Android host vs Linexa Linux environment ─────────────────────────────

    @Test
    fun `linux environment note names the guest compatibility release constant`() {
        // Same literal ProcSnapshotContentFactory writes into guest /proc/version overlays.
        assertEquals("6.1.0-linexa", DeviceInfoMapper.linuxEnvironmentNote())
    }

    @Test
    fun `host kernel release is distinct from any spoofed guest line`() {
        val state = DeviceInfoMapper.from(fullSnapshot())
        val hostRelease = findRow(state.hostKernelRows, InfoRowId.KERNEL_RELEASE_HOST)?.value
        assertEquals("6.6.89-android15", hostRelease)
        if (state.linuxEnvironmentNote != null) {
            assertFalse(hostRelease!!.contains(state.linuxEnvironmentNote!!))
        }
    }

    // ── Network privacy defaults ─────────────────────────────────────────────

    @Test
    fun `network detail carries posture but never raw addresses or dns lists`() {
        val ids = DeviceInfoMapper.from(fullSnapshot()).networkDetailRows.map { it.id }
        assertTrue(InfoRowId.NETWORK_INTERFACE in ids)
        assertTrue(InfoRowId.NETWORK_VALIDATED in ids)
        ids.forEach { id ->
            assertFalse(id.name.contains("IPV"))
            assertFalse(id.name.contains("DNS_SERVER"))
        }
    }

    // ── Capability matrix ────────────────────────────────────────────────────

    @Test
    fun `capability matrix mirrors provider capabilities verbatim per category`() {
        val rowsByCat = DeviceInfoMapper.from(fullSnapshot()).capabilityRows.associateBy { it.category }
        assertEquals(HostInfoCategory.entries.size, rowsByCat.size)
        assertEquals(HostInfoAvailability.RESTRICTED, rowsByCat[HostInfoCategory.CPU]!!.availability)
        assertEquals(HostInfoAvailability.ERROR, rowsByCat[HostInfoCategory.PROCESSES]!!.availability)
    }

    @Test
    fun `capability matrix sorts problem states above healthy ones`() {
        val statuses = DeviceInfoMapper.from(fullSnapshot()).capabilityRows.map { it.availability }
        val firstHealthyIndex = statuses.indexOfFirst { it == HostInfoAvailability.AVAILABLE }
        assertTrue(firstHealthyIndex > 0)
        assertEquals(HostInfoAvailability.ERROR, statuses.first())
        assertTrue(statuses.indexOf(HostInfoAvailability.RESTRICTED) < firstHealthyIndex)
    }

    @Test
    fun `availability sort keys stay distinct across every enum entry`() {
        val keys = HostInfoAvailability.entries.map { DeviceInfoMapper.availabilitySortKey(it) }
        assertEquals(HostInfoAvailability.entries.size, keys.toSet().size)
    }

    // ── Unknown-field honesty ────────────────────────────────────────────────

    @Test
    fun `withheld identity fields render as structural unknowns not blanks`() {
        val sparseDevice = fullDevice().copy(board = null, hardware = "", buildId = null, deviceName = null)
        val state = DeviceInfoMapper.from(fullSnapshot().copy(device = sparseDevice))

        val board = findRow(state.deviceRows, InfoRowId.DEVICE_BOARD)!!
        assertFalse(board.known)
        assertEquals(DeviceInfoState.DASH, board.value)

        assertFalse(findRow(state.androidRows, InfoRowId.ANDROID_BUILD_ID)!!.known)
        assertFalse(findRow(state.deviceRows, InfoRowId.DEVICE_NAME)!!.known)
    }

    // ── Refresh behavior flags (Phase 7) ─────────────────────────────────────

    @Test
    fun `refresh pass keeps the previous collected stamp visible`() {
        assertEquals(1_700_000_000_123L, DeviceInfoMapper.from(fullSnapshot()).collectedAtEpochMs)
        val refreshingState = DeviceInfoMapper.from(fullSnapshot(), refreshing = true)
        assertTrue(refreshingState.refreshing)
        assertEquals(1_700_000_000_123L, refreshingState.collectedAtEpochMs)
    }
}
