package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.service.runtime.container.PRootLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the guest-visible /proc overlay contract: real core counts, monotonic
 * counters, legacy-fallback values where the host is silent, spoof-version
 * consistency, trailing-newline discipline — the invariants downstream tools
 * (nproc, top, btop, uptime, ps) silently rely on.
 */
class ProcSnapshotContentFactoryTest {

    private val cpu = HostCpuInfo(
        logicalCores = 6, onlineCores = 6, architecture = "aarch64",
        supportedAbis = listOf("arm64-v8a"), cpuModelName = null,
        cpuImplementation = null, coresPerCoreInfo = emptyList(),
        minFrequencyKhz = null, maxFrequencyKhz = null, currentFrequencyKhz = null,
        overallUsagePercent = 40f, perCoreUsagePercent = emptyList(),
        loadAvg1m = 2.5f, loadAvg5m = 1.5f, loadAvg15m = 0.5f,
        totalStatTicks = 10_000L, idleInclIowaitStatTicks = 6_000L
    )

    private val device = HostDeviceInfo(
        androidRelease = "15", sdkInt = 35, codename = "REL", incremental = null,
        securityPatch = null, manufacturer = null, brand = null, model = null,
        product = null, device = null, board = null, hardware = null, bootloader = null,
        fingerprint = null, kernelRelease = "6.6.89-android15", kernelVersion = null,
        machineArch = "aarch64", selinuxEnforcing = true,
        uptimeMillis = 123_456_789L, deepSleepMillis = 100_000_000L,
        bootTimeEpochMillis = 1_700_000_000_000L
    )

    private fun bareCpu(cores: Int): HostCpuInfo = cpu.copy(logicalCores = cores)
    private fun bareDevice(): com.fem.linexa.domain.hostinfo.HostDeviceInfo {
        return HostDeviceInfo(
            androidRelease = null, sdkInt = 0, codename = null, incremental = null,
            securityPatch = null, manufacturer = null, brand = null, model = null,
            product = null, device = null, board = null, hardware = null, bootloader = null,
            fingerprint = null, kernelRelease = null, kernelVersion = null, machineArch = null,
            selinuxEnforcing = null, uptimeMillis = null, deepSleepMillis = null,
            bootTimeEpochMillis = null
        )
    }

    @Test
    fun `stat exposes exactly logicalCores per-core lines`() {
        val stat = ProcSnapshotContentFactory.statContent(cpu, device)
        val perCore = stat.lineSequence()
            .filter { it.startsWith("cpu") && !it.startsWith("cpu ") }
            .toList()
        assertEquals(6, perCore.size)
        assertTrue(stat.startsWith("cpu "))
    }

    @Test
    fun `stat falls back to legacy zero line when host counters are unavailable`() {
        val restrictedCpu = cpu.copy(totalStatTicks = null, idleInclIowaitStatTicks = null)
        val stat = ProcSnapshotContentFactory.statContent(restrictedCpu, bareDevice())

        assertEquals(
            "host-restricted counters keep the R-6 zeroed aggregate, never a fake live value",
            "cpu  0 0 0 0 0 0 0 0 0 0",
            stat.lineSequence().first()
        )
    }

    @Test
    fun `stat btime uses derivable boot epoch and legacy constant otherwise`() {
        val withReal = ProcSnapshotContentFactory.statContent(cpu, device)
        assertEquals(
            (1_700_000_000_000L / 1000L).toString(),
            withReal.lineSequence().first { it.startsWith("btime ") }.substringAfter(' ')
        )

        val withoutBoot = ProcSnapshotContentFactory.statContent(cpu, bareDevice())
        assertEquals(
            ProcSnapshotContentFactory.LEGACY_BTIME_EPOCH_SECONDS.toString(),
            withoutBoot.lineSequence().first { it.startsWith("btime ") }.substringAfter(' ')
        )
    }

    @Test
    fun `stat respects core-count floor even when cores unknown`() {
        val weird = ProcSnapshotContentFactory.statContent(bareCpu(0), bareDevice())
        // Fallback floor (== HostCpuInfo.FALLBACK_CORES) keeps nproc≥1 semantics.
        val perCoreCount = weird.lineSequence().filter { it.startsWith("cpu") && !it.startsWith("cpu ") }.count()
        assertTrue(perCoreCount >= 1)
    }

    @Test
    fun `uptime derives from host millis and never goes negative on sleep accounting`() {
        // Float formatting lands on the nearest two decimals of each second value.
        assertEquals(
            "123456.79 23456.79",
            ProcSnapshotContentFactory.uptimeContent(device).trim()
        )
        // deepSleep > uptime (clock skew) clamps to zero idle, no negatives.
        val skewed = device.copy(deepSleepMillis = Long.MAX_VALUE / 4)
        val parts = ProcSnapshotContentFactory.uptimeContent(skewed).trim().split(' ')
        assertTrue(parts[1].toFloat() >= 0f)
    }

    @Test
    fun `uptime falls back to legacy zero when host uptime unknown`() {
        assertEquals("0.00 0.00", ProcSnapshotContentFactory.uptimeContent(bareDevice()).trim())
    }

    @Test
    fun `loadavg mirrors host values with dot decimals`() {
        assertEquals(
            "2.50 1.50 0.50 1/1 1",
            ProcSnapshotContentFactory.loadavgContent(cpu).trim()
        )
        assertEquals(
            "legacy zeros when the host will not report load",
            "0.00 0.00 0.00 1/1 1",
            ProcSnapshotContentFactory.loadavgContent(bareCpu(6).copy(loadAvg1m = null)).trim()
        )
    }

    @Test
    fun `version stays the SPOOFED release matching PRootLauncher - never host kernel`() {
        assertEquals(
            PRootLauncher.SPOOFED_KERNEL_RELEASE,
            ProcSnapshotContentFactory.SPOOF_RELEASE
        )
        assertTrue(ProcSnapshotContentFactory.versionContent().endsWith("\n"))
        assertFalse(
            "the guest version must NOT leak the real Android kernel string",
            ProcSnapshotContentFactory.versionContent().contains(device.kernelRelease ?: "?")
        )
    }

    @Test
    fun `every generated body ends with a newline`() {
        for (body in listOf(
            ProcSnapshotContentFactory.statContent(cpu, device),
            ProcSnapshotContentFactory.uptimeContent(device),
            ProcSnapshotContentFactory.loadavgContent(cpu),
            ProcSnapshotContentFactory.versionContent(),
            ProcSnapshotContentFactory.vmstatContent()
        )) {
            assertTrue("proc file bodies must end with \\n", body.endsWith("\n"))
        }
    }
}
