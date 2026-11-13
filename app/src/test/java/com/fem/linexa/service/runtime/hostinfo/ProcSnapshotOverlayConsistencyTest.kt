package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #8 / #9 (2026-09-03) — the two /proc overlay consistency fixes:
 *
 *  1. **Core count consistency:** the synthetic /proc/stat and /proc/cpuinfo
 *     report the kernel's ONLINE core count, not the scheduler affinity
 *     mask — the affinity-trimmed view made one workspace's htop show 3
 *     cores while a sibling rootfs with unrefreshed overlays showed the
 *     real 8. A host-provided REAL grid is emitted verbatim.
 *  2. **Workspace-relative uptime:** a running session's /proc/uptime is
 *     the workspace's own uptime; no session keeps the host passthrough.
 */
class ProcSnapshotOverlayConsistencyTest {

    private val device = HostDeviceInfo(
        androidRelease = "15", sdkInt = 35, codename = "REL", incremental = null,
        securityPatch = null, manufacturer = null, brand = null, model = null,
        product = null, device = null, board = null, hardware = null, bootloader = null,
        fingerprint = null, kernelRelease = "6.6.89-android15", kernelVersion = null,
        machineArch = "aarch64", selinuxEnforcing = true,
        uptimeMillis = 123_456_789L, deepSleepMillis = 100_000_000L,
        bootTimeEpochMillis = 1_700_000_000_000L
    )

    /** The 3-vs-8 scenario: 8 configured/online cores, affinity grants 3. */
    private fun cpu(
        logical: Int = 8,
        online: Int? = 8,
        affinity: List<Int>? = listOf(0, 1, 2),
        perCoreTicks: Map<Int, Long>? = null,
        totalTicks: Long? = null
    ): HostCpuInfo = HostCpuInfo(
        logicalCores = logical, onlineCores = online, architecture = "aarch64",
        supportedAbis = listOf("arm64-v8a"), cpuModelName = "test",
        cpuImplementation = null, coresPerCoreInfo = emptyList(),
        minFrequencyKhz = null, maxFrequencyKhz = null, currentFrequencyKhz = null,
        overallUsagePercent = null, perCoreUsagePercent = emptyList(),
        loadAvg1m = null, loadAvg5m = null, loadAvg15m = null,
        totalStatTicks = totalTicks, idleInclIowaitStatTicks = null,
        totalPerCoreStatTicks = perCoreTicks,
        affinityCoreIndices = affinity
    )

    private fun perCoreLines(stat: String) = stat.lineSequence()
        .filter { it.startsWith("cpu") && !it.startsWith("cpu ") }
        .toList()

    @Test
    fun `stat row count follows ONLINE cores, not the affinity mask`() {
        // 8 online cores; the app is only ALLOWED 3. The overlay must still
        // show 8 — that is what the kernel's own /proc/stat shows.
        val stat = ProcSnapshotContentFactory.statContent(cpu(), device)
        assertEquals(8, perCoreLines(stat).size)
    }

    @Test
    fun `stat rows match the kernel grid verbatim when readable`() {
        val grid = (0 until 8).associateWith { (it + 1) * 1_000L }
        val stat = ProcSnapshotContentFactory.statContent(
            cpu(perCoreTicks = grid, totalTicks = grid.values.sum()), device
        )
        val rows = perCoreLines(stat)
        assertEquals(8, rows.size)
        // Kernel order; the bucketed columns must sum back to the kernel's
        // recorded per-core total (the bucketing is coarse but conserving).
        rows.forEachIndexed { slot, line ->
            assertEquals("cpu$slot", line.substringBefore(' '))
            val cols = line.substringAfter(' ').split(' ').map { it.toLong() }
            assertEquals("column sum conserves the kernel total", grid[slot]!!, cols.sum())
        }
    }

    @Test
    fun `cpuinfo processor rows follow online cores too`() {
        val body = ProcSnapshotContentFactory.cpuinfoContent(cpu())
        assertEquals(8, body.split("processor\t: ").size - 1)
        // And the real kernel index stays auditable per row.
        assertTrue(body.contains("# kernel cpu index"))
    }

    @Test
    fun `floor keeps parsers fed when every count is unknown`() {
        val stat = ProcSnapshotContentFactory.statContent(
            cpu(logical = 0, online = null, affinity = null), device
        )
        assertTrue(perCoreLines(stat).size >= 1)
    }

    // ── workspace-relative uptime ────────────────────────────────────────

    @Test
    fun `uptime reports the workspace value when a session runs`() {
        val body = ProcSnapshotContentFactory.uptimeContent(
            device,
            workspaceUptimeSeconds = 42L
        ).trim()
        assertTrue("workspace uptime, not host uptime", body.startsWith("42.00 "))
    }

    @Test
    fun `uptime falls back to the host value without a session`() {
        assertEquals(
            "123456.79 23456.79",
            ProcSnapshotContentFactory.uptimeContent(device, null).trim()
        )
    }

    @Test
    fun `workspace idle column mirrors uptime when unmeasured`() {
        val parts = ProcSnapshotContentFactory.uptimeContent(device, 10L).trim().split(' ')
        assertEquals(parts[0], parts[1])
    }

    @Test
    fun `negative workspace uptime is rejected, never rendered`() {
        assertEquals(
            "123456.79 23456.79",
            ProcSnapshotContentFactory.uptimeContent(device, -5L).trim()
        )
    }
}
