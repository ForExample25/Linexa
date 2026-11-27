package com.fem.linexa.service.runtime.hostinfo

import android.app.Application
import com.fem.linexa.data.hostinfo.HostInfoSources
import com.fem.linexa.data.hostinfo.AndroidHostSystemInfoProvider
import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostGpuInfo
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetInterface
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostThermalInfo
import com.fem.linexa.domain.hostinfo.HostZramInfo
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * The runtime bridge's file/env contracts on a JVM: overlays land at the exact
 * dot-names PRoot binds over, carry host-derived values, honor the legacy
 * fallback when the "host" is silent, synthesize the RESTRICTED-only files
 * under injected readability verdicts, and the LINEXA_HOST_* env layer never
 * emits variables for facts the provider cannot know.
 *
 * 2026-08-27 expansion adds coverage for: network telemetry files, the
 * `/run/linexa/hostinfo.conf` feed, read-only overlay modes (Phase 12) and
 * environment-tick-driven LIVE stats (Phase 5).
 */
class HostInfoRuntimeBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File

    private val device = HostDeviceInfo(
        androidRelease = "15", sdkInt = 35, codename = "REL", incremental = null,
        securityPatch = null, manufacturer = null, brand = null, model = null,
        product = null, device = null, board = null, hardware = null, bootloader = null,
        fingerprint = null, kernelRelease = "6.6.89-android15", kernelVersion = null,
        machineArch = "aarch64", selinuxEnforcing = true,
        uptimeMillis = 90_000L, deepSleepMillis = 40_000L, bootTimeEpochMillis = null
    )

    private val cpu = HostCpuInfo(
        logicalCores = 5, onlineCores = null, architecture = null,
        supportedAbis = emptyList(), cpuModelName = null, cpuImplementation = null,
        coresPerCoreInfo = emptyList(), minFrequencyKhz = null, maxFrequencyKhz = null,
        currentFrequencyKhz = null, overallUsagePercent = null,
        perCoreUsagePercent = emptyList(),
        loadAvg1m = null, loadAvg5m = null, loadAvg15m = null,
        totalStatTicks = null, idleInclIowaitStatTicks = null
    )

    private val networking = HostNetworkInfo(
        isActive = true,
        transports = listOf("wifi"),
        primaryTransport = "wifi",
        isValidated = true,
        isMetered = false,
        downlinkKbps = null,
        uplinkKbps = null,
        interfaceName = "wlan0",
        ipv4Addresses = listOf("192.168.1.7"),
        ipv6Addresses = listOf("fe80::1234"),
        dnsServers = listOf("192.168.1.1"),
        isPrivateDnsActive = false,
        interfaces = listOf(
            HostNetInterface(
                name = "wlan0", transport = "wifi", isActiveNetwork = true, mtu = 1500,
                ipv4WithPrefix = listOf("192.168.1.7/24"),
                ipv6WithPrefix = listOf("fe80::1234/64"),
                gatewayV4 = "192.168.1.1",
                rxBytes = 456_789L, txBytes = 12_345L, rxPackets = 700L, txPackets = 90L
            )
        )
    )

    private fun provider(sources: HostInfoSources): AndroidHostSystemInfoProvider =
        AndroidHostSystemInfoProvider(
            sources = sources,
            ioDispatcher = Dispatchers.Unconfined,
            wallClockMs = { 0L }
        )

    private fun bridgeOf(
        sources: HostInfoSources,
        sampler: EnvTickSampler? = null,
        nativeReadable: Map<String, Boolean> = emptyMap()
    ): HostInfoRuntimeBridge {
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(tmp.newFolder("files-${System.nanoTime()}"))
        return HostInfoRuntimeBridge(
            hostSystemInfoProvider = provider(sources),
            fileManager = WorkspaceFileManager(app),
            envTickSampler = sampler,
            nativeReadableProbe = { path -> nativeReadable[path] ?: false }
        )
    }

    @Before
    fun setUp() {
        rootfs = tmp.newFolder("rootfs")
        File(rootfs, "proc").mkdirs()
    }

    @Test
    fun `refreshProcOverlays writes host values under the launcher's bind names`() = runBlocking {
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws"))

        val procDir = File(rootfs, "proc")

        // uptime: host 90s up, 40s deep sleep → idle column mirrors the delta.
        assertEquals("90.00 50.00\n", File(procDir, ".uptime").readText())

        // stat: exactly logicalCores(5) per-core lines so guest nproc == 5;
        // counters take the legacy zero line because this stub host is silent.
        val stat = File(procDir, ".stat").readText()
        val perCore = stat.lineSequence()
            .filter { it.startsWith("cpu") && !it.startsWith("cpu ") }
            .toList()
        assertEquals(5, perCore.size)
        assertTrue(stat.contains("cpu  0 0 0 0 0 0 0 0 0 0"))

        // every ALWAYS-written name got an overlay file (conditional members
        // meminfo/swaps/cpuinfo exist only when the probe denies native reads;
        // see the dedicated synthesis test below).
        for (name in listOf("stat", "uptime", "loadavg", "version", "vmstat")) {
            assertTrue("missing overlay .$name", File(procDir, ".$name").isFile)
        }

        // loadavg falls back to legacy zeros for a host that won't report it.
        assertEquals(
            "0.00 0.00 0.00 1/1 1",
            File(procDir, ".loadavg").readText().trim()
        )

        // Phase 12: every overlay lands OWNER-READ-ONLY (no write-through).
        for (name in listOf("stat", "uptime", "loadavg", "version", "vmstat")) {
            val perms = Files.getPosixFilePermissions(File(procDir, ".$name").toPath())
            assertFalse(".$name must not be writable",
                perms.contains(PosixFilePermission.OWNER_WRITE))
        }
    }

    @Test
    fun `refreshProcOverlays widens and restores a read-only proc and nested net`() = runBlocking {
        // Arch/Fedora shape: both `/proc` and `/proc/.net` can arrive 0555.
        // The bridge must write the hidden bind sources and restore the
        // archive's exact modes afterwards.
        val procDir = File(rootfs, "proc")
        val netDir = File(procDir, ".net").apply { mkdirs() }
        val procOriginal = Files.getPosixFilePermissions(procDir.toPath())
        val netOriginal = Files.getPosixFilePermissions(netDir.toPath())
        val procReadOnly = procOriginal - PosixFilePermission.OWNER_WRITE
        val netReadOnly = netOriginal - PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(procDir.toPath(), procReadOnly)
        Files.setPosixFilePermissions(netDir.toPath(), netReadOnly)

        try {
            val bridge = bridgeOf(
                sources = StubHostInfoSources(device, cpu).withNetwork(networking),
                nativeReadable = mapOf("/proc/net/dev" to false)
            )

            assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws-readonly-proc"))
            assertTrue("flat overlay missing", File(procDir, ".stat").isFile)
            assertTrue("nested overlay missing", File(netDir, "dev").isFile)
            assertEquals(
                "the archive's /proc mode must be restored exactly",
                procReadOnly,
                Files.getPosixFilePermissions(procDir.toPath())
            )
            assertEquals(
                "the archive's /proc/.net mode must be restored exactly",
                netReadOnly,
                Files.getPosixFilePermissions(netDir.toPath())
            )
        } finally {
            Files.setPosixFilePermissions(procDir.toPath(), procOriginal)
            Files.setPosixFilePermissions(netDir.toPath(), netOriginal)
        }
    }

    @Test
    fun `restricted-native probes synthesize meminfo swaps cpuinfo and net telemetry`() = runBlocking {
        val sources = StubHostInfoSources(device, cpu).withNetwork(networking)
        val bridge = bridgeOf(
            sources = sources,
            nativeReadable = mapOf(
                "/proc/meminfo" to false,
                "/proc/cpuinfo" to false,
                "/proc/net/dev" to false
            )
        )
        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws"))

        val procDir = File(rootfs, "proc")

        // meminfo: present rows only, real stub values.
        val meminfo = File(procDir, ".meminfo").readText()
        assertTrue(meminfo.contains("MemTotal:"))
        assertTrue(meminfo.contains("16000000"))
        assertTrue(meminfo.contains("MemAvailable:"))

        // swaps: header + one zRAM row derived from real swap info.
        val swaps = File(procDir, ".swaps").readText()
        assertTrue(swaps.contains("Filename"))
        assertTrue(swaps.contains("zram0"))
        assertTrue(swaps.contains("8192000"))

        // net/dev: loopback row plus wlan0 with REAL TrafficStats counters.
        val dev = File(procDir, ".net/dev").readText()
        assertTrue(dev.contains("wlan0"))
        assertTrue(dev.contains("456789"))
        assertTrue(dev.contains("12345"))

        // net/route: default route toward the real gateway.
        val route = File(procDir, ".net/route").readText()
        assertTrue(route.contains("wlan0"))
        assertTrue(route.contains("C0A80101"))   // 192.168.1.1 hex network order
        assertTrue(route.contains("00000000"))   // default destination

        // if_inet6: link-local scope nibble 20.
        val inet6 = File(procDir, ".net/if_inet6").readText()
        assertTrue(inet6.contains("FE800000000000000000000000001234"))
        assertTrue(inet6.contains(" 40 20 "))    // prefix 0x40=64, scope 20

        // Richer passthrough wins: when the probe says NATIVE IS readable the
        // conditionals must NOT be bound (never shadow a richer truth).
        val readableBridge = bridgeOf(
            sources = sources,
            nativeReadable = mapOf("/proc/meminfo" to true, "/proc/cpuinfo" to true, "/proc/net/dev" to true)
        )
        val otherRoot = tmp.newFolder("rootfs-readable")
        File(otherRoot, "proc").mkdirs()
        assertNull(readableBridge.refreshProcOverlays(otherRoot, workspaceId = "ws"))
        assertFalse(File(otherRoot, "proc/.meminfo").exists())
        assertFalse(File(otherRoot, "proc/.swaps").exists())
        assertFalse(File(otherRoot, "proc/.cpuinfo").exists())
        assertFalse(File(otherRoot, "proc/.net/dev").exists())
    }

    @Test
    fun `hostinfo feed lands in run linexa with resolver-canonical body`() = runBlocking {
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws"))

        val feed = File(File(rootfs, "run"), "linexa/hostinfo.conf")
        assertTrue(feed.isFile)
        val perms = Files.getPosixFilePermissions(feed.toPath())
        assertFalse(perms.contains(PosixFilePermission.OWNER_WRITE))

        val body = feed.readText()
        assertTrue(body.contains("CPU_CORES=5\n"))
        assertTrue(body.startsWith("#"))
        // no whitespace-carrying values possible; format stays KEY=value
        body.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach {
            assertTrue("line must be KEY=value: $it", it.substringBefore('=') != it && !it.substringAfter('=').contains(' '))
        }
    }

    @Test
    fun `environment tick sampler makes restricted-host stats move`() = runBlocking {
        val fakeProc = tmp.newFolder("fake-proc")
        writeFakePidStat(fakeProc, pid = 700, comm = "bash", state = 'R',
            utime = 1000L, stime = 500L, cutime = 300L, cstime = 200L, lastCore = 4)
        var now = 10_000L
        val sampler = EnvTickSampler(
            procDir = fakeProc,
            clockTicksPerSecond = 100L,
            nowMs = { now }
        )
        val mutableBridge = bridgeOf(StubHostInfoSources(device, cpu), sampler = sampler)

        // Baseline pass: restricted host ⇒ legacy zeros (sampler still seeding).
        assertNull(mutableBridge.refreshProcOverlays(rootfs, workspaceId = "ws"))
        assertTrue(File(rootfs, "proc/.stat").readText().contains("cpu  0 0 0 0 0 0 0 0 0 0"))

        // Grow CPU usage between passes: +30 ticks attributed to core 4.
        now += 1_000
        writeFakePidStat(fakeProc, pid = 700, comm = "bash", state = 'R',
            utime = 1030L, stime = 500L, cutime = 300L, cstime = 200L, lastCore = 4)
        assertNull(mutableBridge.refreshProcOverlays(rootfs, workspaceId = "ws"))

        val stat = File(rootfs, "proc/.stat").readText()
        // Environment accounting: busy 30 attributed to core 4; idle capacity
        // over the window = 5 granted cores × 100 ticks − 30 busy = 470.
        val aggregate = stat.lineSequence().first { it.startsWith("cpu ") }
        val cols = aggregate.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        assertEquals(30L, cols[0] + cols[1] + cols[2] + cols[5] + cols[6] + cols[7])
        assertEquals(470L, cols[3] + cols[4])

        // Real observed process counts land in the header lines.
        assertTrue(stat.contains("processes "))
        assertTrue(stat.contains("procs_running 1\n"))
    }

    private fun writeFakePidStat(dir: File, pid: Int, comm: String, state: Char,
                                 utime: Long, stime: Long, cutime: Long, cstime: Long, lastCore: Int) {
        val d = File(dir, pid.toString())
        d.mkdirs()
        val sb = StringBuilder()
        sb.append("$pid ($comm) $state 1 1 1 -1 -1 0 0 0 0 50 ")   // fields 1..13
        sb.append("$utime $stime $cutime $cstime ")                  // fields 14..17
        repeat(21) { sb.append("${it + 7} ") }                      // fields 18..38
        sb.append(lastCore)                                          // field 39: last CPU
        repeat(13) { sb.append(" 0") }                              // fields 40..52
        sb.append("\n")
        File(d, "stat").writeText(sb.toString())
    }

    @Test
    fun `overlay-only refresh skips the feed and still restores a deleted overlay`() = runBlocking {
        // LINEXA-PERF-001: only feed ticks pay for the full host collection and
        // the /run/linexa/hostinfo.conf rewrite.
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        val feed = File(File(rootfs, "run"), "linexa/hostinfo.conf")

        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws", includeFeed = false))
        assertFalse("overlay-only tick must not create the feed", feed.exists())
        assertTrue(File(rootfs, "proc/.stat").isFile)

        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws", includeFeed = true))
        assertTrue(feed.isFile)

        // The content memo must never mask an overlay deleted behind our back.
        File(rootfs, "proc/.version").delete()
        assertNull(bridge.refreshProcOverlays(rootfs, workspaceId = "ws", includeFeed = false))
        assertTrue("deleted overlay must be rewritten", File(rootfs, "proc/.version").isFile)
    }

    @Test
    fun `no proc directory is a silent success - launcher will bind nothing`() = runBlocking {
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        val withoutProc = tmp.newFolder("no-proc-rootfs")
        assertNull(bridge.refreshProcOverlays(withoutProc, workspaceId = "ws"))
    }

    @Test
    fun `default env keys render only known facts under the LINEXA_HOST_ prefix`() = runBlocking {
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        val env = bridge.buildGuestEnv(HostInfoRuntimeBridge.DEFAULT_GUEST_KEYS)

        assertTrue(env.keys.all { it.startsWith(HostInfoRuntimeBridge.ENV_PREFIX) })
        assertEquals("5", env["LINEXA_HOST_CPU_CORES"])
        assertEquals("15", env["LINEXA_HOST_ANDROID_VERSION"])
        assertEquals("35", env["LINEXA_HOST_ANDROID_SDK_INT"])
        assertEquals("90", env["LINEXA_HOST_UPTIME_SECONDS"])
        // battery/thermal are deliberately NOT in the minimal default set…
        assertNull(env["LINEXA_HOST_BATTERY_PERCENT"])
    }

    @Test
    fun `enable=false semantics live in the config layer but unknown facts never emit`() = runBlocking {
        val bridge = bridgeOf(StubHostInfoSources(device, cpu))
        // Request keys whose facts are absent from this stub host entirely:
        // battery percent, thermal status, affinity count are all unknown here.
        val env = bridge.buildGuestEnv(setOf(
            com.fem.linexa.domain.hostinfo.HostInfoKey.BATTERY_PERCENT,
            com.fem.linexa.domain.hostinfo.HostInfoKey.THERMAL_STATUS,
            com.fem.linexa.domain.hostinfo.HostInfoKey.NETWORK_TYPE,
            com.fem.linexa.domain.hostinfo.HostInfoKey.CPU_AFFINITY_COUNT
        ))
        assertTrue("unknown facts must not emit variables", env.isEmpty())
    }
}

private class StubHostInfoSources(
    internal val device: HostDeviceInfo,
    internal val cpu: HostCpuInfo,
    internal val network: HostNetworkInfo = HostNetworkInfo.offline()
) : HostInfoSources {
    override fun collectSystem(): HostDeviceInfo = device

    override fun collectCpu(): HostCpuInfo = cpu

    override fun collectGpu(): HostGpuInfo = HostGpuInfo.unknown()

    override fun collectMemory(): HostMemoryInfo = HostMemoryInfo(
        memTotalKb = 16_000_000L, memFreeKb = null, memAvailableKb = 9_000_000L,
        buffersKb = null, cachedKb = null, sReclaimableKb = null, usedLinuxKb = null,
        androidTotalKb = null, androidAvailKb = null, androidThresholdKb = null,
        isLowMemory = null,
        swap = HostSwapInfo(totalKb = 8_192_000L, freeKb = 6_144_000L, usedKb = 2_048_000L),
        zram = HostZramInfo(present = true, diskSizeBytes = 4_294_967_296L),
        memoryClassMb = null, largeMemoryClassMb = null
    )

    override fun collectStorage(): HostStorageInfo =
        HostStorageInfo(null, null, null, emptyList())

    override fun collectBattery(): HostBatteryInfo = HostBatteryInfo(
        present = null, percent = null,
        charging = HostBatteryInfo.ChargingState.UNKNOWN,
        powerSource = HostBatteryInfo.PowerSource.UNKNOWN,
        health = HostBatteryInfo.Health.UNKNOWN,
        temperatureC = null, voltageMillivolts = null, currentMicroamps = null,
        chargeCounterMicroAh = null, designCapacityMilliampHours = null,
        isPowerSaveMode = null
    )

    override fun collectThermal(batteryTemperatureC: Float?): HostThermalInfo =
        HostThermalInfo(null, null, null, null, null, null, batteryTemperatureC)

    override fun collectNetwork(): HostNetworkInfo = network

    override fun collectProcesses(): HostProcessInfo = HostProcessInfo.unknown()
}

/** Convenience copy so individual tests can vary the network answer. */
private fun StubHostInfoSources.withNetwork(net: HostNetworkInfo): StubHostInfoSources =
    StubHostInfoSources(device, cpu, net)
