package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.hostinfo.HostNetInterface
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest-visible network telemetry synthesis (Phase 8): /proc/net/dev
 * rows with REAL counters, kernel-shaped route lines in hex network order,
 * if_inet6 scope nibbles — plus the "absence is header-only, never fake"
 * guarantees that keep parsers fed without inventing data.
 */
class ProcSnapshotContentFactoryNetworkTest {

    private fun iface(
        name: String = "wlan0",
        active: Boolean = true,
        gw4: String? = "192.168.1.1",
        v4: List<String> = listOf("192.168.1.7/24"),
        v6: List<String> = listOf("fe80::abcd/64")
    ) = HostNetInterface(
        name = name, transport = "wifi", isActiveNetwork = active, mtu = 1500,
        ipv4WithPrefix = v4, ipv6WithPrefix = v6, gatewayV4 = gw4,
        rxBytes = 100_000_000L + name.hashCode(), txBytes = 1_000L,
        rxPackets = 555L, txPackets = 66L
    )

    // ── net/dev ────────────────────────────────────────────────────────

    @Test
    fun `dev file always carries loopback then interfaces`() {
        val body = ProcSnapshotContentFactory.netDevContent(
            HostNetworkInfo.offline().copy(interfaces = listOf(iface("rmnet0", active = false)))
        )
        val rows = body.lineSequence().drop(2).filter { it.isNotBlank() }.toList()
        assertTrue(rows.first().trim().startsWith("lo:"))
        assertTrue(body.contains("rmnet0"))
        assertEquals("two interface rows exactly", 2, rows.size)
    }

    @Test
    fun `dev counters render real byte and packet values`() {
        val body = ProcSnapshotContentFactory.netDevContent(
            HostNetworkInfo.offline().copy(interfaces = listOf(iface()))
        )
        assertTrue(body.contains("1000"))     // rx bytes tail matches
        assertFalse(body.endsWith("\n\n"))
    }

    @Test
    fun `header lines match the kernel two-line prologue`() {
        val body = ProcSnapshotContentFactory.netDevContent(HostNetworkInfo.offline())
        val lines = body.lineSequence().toList()
        assertTrue(lines[0].startsWith("Inter-|"))
        assertTrue(lines[1].contains("face |bytes"))
    }

    // ── route ──────────────────────────────────────────────────────────

    @Test
    fun `default route toward real gateway with flags UG`() {
        val body = ProcSnapshotContentFactory.netRouteContent(
            HostNetworkInfo.offline().copy(interfaces = listOf(iface()))
        )
        val row = body.lineSequence().first { it.startsWith("wlan0") }
        val cols = row.trim().split(Regex("\\s+|\\t+"))
        assertEquals(listOf("wlan0", "00000000", "C0A80101", "0003"), cols.take(4))
    }

    @Test
    fun `prefix routes are UP-only with correct masks`() {
        val body = ProcSnapshotContentFactory.netRouteContent(
            HostNetworkInfo.offline().copy(
                interfaces = listOf(iface(v4 = listOf("10.0.0.9/8")))
            )
        )
        val row = body.lineSequence()
            .filter { it.isNotBlank() && it.startsWith("wlan0") }
            .last { !it.contains("C0A80101") }
        // 10.0.0.0 → 0A000000; mask /8 → FF000000; flags U = 0001.
        assertTrue(row.contains("0A000000"))
        assertTrue(row.contains("FF000000"))
        assertTrue(row.contains("0001"))
        assertFalse("host bits must be masked into network address",
            row.contains("09000000"))   // 10.0.0.9's low octet must not leak
    }

    @Test
    fun `no known routes keeps a parseable header-only table`() {
        val body = ProcSnapshotContentFactory.netRouteContent(HostNetworkInfo.offline())
        assertEquals("header-only table", 1,
            body.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(body.trimStart().startsWith("Iface"))
        assertTrue(body.endsWith("\n"))
    }

    // ── if_inet6 ───────────────────────────────────────────────────────

    @Test
    fun `ipv6 rows expand to 32 hex chars with scope nibble`() {
        val body = ProcSnapshotContentFactory.ifInet6Content(
            HostNetworkInfo.offline().copy(interfaces = listOf(iface()))
        )
        val line = body.trim()
        assertTrue(line.startsWith("FE80000000000000000000000000ABCD"))
        assertTrue(line.contains(" 1 40 20 80 "))   // idx=1, /64=0x40, link-scope, permanent flag
    }

    @Test
    fun `global unicast addresses carry global scope`() {
        val body = ProcSnapshotContentFactory.ifInet6Content(
            HostNetworkInfo.offline().copy(
                interfaces = listOf(iface(v6 = listOf("2001:db8:0:f101::7/64")))
            )
        )
        val line = body.trim()
        assertTrue(line.startsWith("20010DB80000F1010000000000000007"))
        assertTrue(line.contains(" 00 "))
    }

    @Test
    fun `compact-notated full groups expand deterministically`() {
        val body = ProcSnapshotContentFactory.ifInet6Content(
            HostNetworkInfo.offline().copy(
                interfaces = listOf(iface(v6 = listOf("::1/128")))
            )
        )
        assertTrue(body.trim().startsWith("00000000000000000000000000000001"))
    }

    @Test
    fun `offline network still yields well-formed empty telemetry`() {
        // No interfaces ⇒ no if_inet6 rows at all (absence, never a fake row).
        assertTrue(ProcSnapshotContentFactory.ifInet6Content(HostNetworkInfo.offline()).isEmpty())
    }

    // ── conditional meminfo/swaps/cpuinfo ─────────────────────────────

    @Test
    fun `swaps omits entirely when swap unknown`() {
        val swap = com.fem.linexa.domain.hostinfo.HostSwapInfo(null, null, null)
        assertEquals("", ProcSnapshotContentFactory.swapsContent(swap, null))
    }

    @Test
    fun `meminfo renders only measured rows`() {
        val memory = com.fem.linexa.domain.hostinfo.HostMemoryInfo(
            memTotalKb = 8000000L, memFreeKb = null, memAvailableKb = 4000000L,
            buffersKb = null, cachedKb = null, sReclaimableKb = null, usedLinuxKb = null,
            androidTotalKb = null, androidAvailKb = null, androidThresholdKb = null,
            isLowMemory = null,
            swap = com.fem.linexa.domain.hostinfo.HostSwapInfo(2000000L, 1500000L, 500000L),
            zram = null, memoryClassMb = null, largeMemoryClassMb = null
        )
        val body = ProcSnapshotContentFactory.meminfoContent(memory, memory.swap)
        assertTrue(body.contains("MemTotal:"))
        assertFalse(body.contains("MemFree:"))          // never measured → never faked
        assertTrue(body.contains("SwapTotal:"))
        assertTrue(body.endsWith("\n"))
    }

    @Test
    fun `cpuinfo rows follow ONLINE cores with granted kernel indices first`() {
        // Issue #8 (2026-09-03): the row count follows the kernel's ONLINE
        // count (8) — an affinity-trimmed list made sibling rootfs workspaces
        // disagree about the machine. The GRANTED kernel indices still lead
        // the rows (auditable via the `# kernel cpu index` comment).
        val cpu = com.fem.linexa.domain.hostinfo.HostCpuInfo(
            logicalCores = 8, onlineCores = 8, architecture = "aarch64",
            supportedAbis = listOf("arm64-v8a"), cpuModelName = "Test SoC",
            cpuImplementation = "0x41",
            coresPerCoreInfo = emptyList(), minFrequencyKhz = null,
            maxFrequencyKhz = null, currentFrequencyKhz = null,
            overallUsagePercent = null, perCoreUsagePercent = emptyList(),
            loadAvg1m = null, loadAvg5m = null, loadAvg15m = null,
            affinityCoreIndices = listOf(4, 5, 6)
        )
        val body = ProcSnapshotContentFactory.cpuinfoContent(cpu)
        assertTrue(body.contains("processor\t: 0"))
        assertTrue(body.contains("processor\t: 2"))
        assertTrue(body.contains("processor\t: 7"))
        assertFalse(body.contains("processor\t: 8"))
        assertTrue(body.contains("# kernel cpu index\t: 4"))
        assertTrue(body.contains("model name\t: Test SoC"))
    }
}
