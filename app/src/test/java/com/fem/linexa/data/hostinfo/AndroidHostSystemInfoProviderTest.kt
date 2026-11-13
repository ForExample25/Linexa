package com.fem.linexa.data.hostinfo

import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostGpuInfo
import com.fem.linexa.domain.hostinfo.HostInfoAvailability
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.domain.hostinfo.HostInfoRefreshPolicy
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostThermalInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §13 behavioral core, proven on a JVM fake:
 * TTL-driven refresh, selective snapshotOf (unnamed categories collect NOTHING),
 * forced-refresh rate guard, thermal's same-cycle battery mirror, and
 * last-known-good survival across collector failures. No Android class is
 * touched anywhere below.
 */
class AndroidHostSystemInfoProviderTest {

    /** Deterministic sources with per-category failure switches + call counts. */
    private class FakeSources : HostInfoSources {
        var systemValue: HostDeviceInfo? = null
        var cpuValue: HostCpuInfo? = null
        val throwOn = mutableSetOf<String>()

        val calls = mutableMapOf<String, Int>()
        var lastThermalBatteryTempSeen: Float? = null

        private fun record(name: String) {
            calls.merge(name, 1, Int::plus)
            if (name in throwOn) throw IllegalStateException("boom $name")
        }

        override fun collectSystem(): HostDeviceInfo {
            record("SYSTEM")
            return systemValue ?: emptyDevice()
        }

        override fun collectCpu(): HostCpuInfo {
            record("CPU")
            return cpuValue ?: HostCpuInfo(
                logicalCores = 8, onlineCores = null, architecture = null,
                supportedAbis = emptyList(), cpuModelName = null,
                cpuImplementation = null, coresPerCoreInfo = emptyList(),
                minFrequencyKhz = null, maxFrequencyKhz = null, currentFrequencyKhz = null,
                overallUsagePercent = null, perCoreUsagePercent = emptyList(),
                loadAvg1m = null, loadAvg5m = null, loadAvg15m = null
            )
        }

        override fun collectGpu(): HostGpuInfo {
            record("GPU")
            return HostGpuInfo.unknown()
        }

        override fun collectMemory(): HostMemoryInfo {
            record("MEMORY")
            return defaultMemory()
        }

        override fun collectStorage(): HostStorageInfo {
            record("STORAGE")
            return HostStorageInfo(null, null, null, emptyList())
        }

        override fun collectBattery(): HostBatteryInfo {
            record("BATTERY")
            return HostBatteryInfo(
                present = null, percent = 50,
                charging = HostBatteryInfo.ChargingState.DISCHARGING,
                powerSource = HostBatteryInfo.PowerSource.NONE,
                health = HostBatteryInfo.Health.GOOD,
                temperatureC = 30f, voltageMillivolts = null, currentMicroamps = null,
                chargeCounterMicroAh = null, designCapacityMilliampHours = null,
                isPowerSaveMode = false
            )
        }

        override fun collectThermal(batteryTemperatureC: Float?): HostThermalInfo {
            lastThermalBatteryTempSeen = batteryTemperatureC
            record("THERMAL")
            return HostThermalInfo(
                status = null, severityLabel = null, headroom = null,
                cpuTemperatureC = null, gpuTemperatureC = null, skinTemperatureC = null,
                batteryTemperatureC = batteryTemperatureC
            )
        }

        override fun collectNetwork(): HostNetworkInfo {
            record("NETWORK")
            return HostNetworkInfo.offline()
        }

        override fun collectProcesses(): HostProcessInfo {
            record("PROCESSES")
            return HostProcessInfo.unknown()
        }

        private fun emptyDevice(): HostDeviceInfo = HostDeviceInfo(
            androidRelease = "test", sdkInt = 34, codename = "REL", incremental = null,
            securityPatch = null, manufacturer = null, brand = null, model = null,
            product = null, device = null, board = null, hardware = null, bootloader = null,
            fingerprint = null, kernelRelease = null, kernelVersion = null, machineArch = null,
            selinuxEnforcing = null, uptimeMillis = null, deepSleepMillis = null,
            bootTimeEpochMillis = null
        )

        companion object {
            fun defaultMemory(): HostMemoryInfo = HostMemoryInfo(
                memTotalKb = 64_000L, memFreeKb = null, memAvailableKb = 32_000L,
                buffersKb = null, cachedKb = null, sReclaimableKb = null, usedLinuxKb = null,
                androidTotalKb = null, androidAvailKb = null, androidThresholdKb = null,
                isLowMemory = null,
                // Non-null on purpose: the SWAP entry prefers the fresh memory
                // slice; keeping it non-null keeps ONE meminfo-style read per
                // pass so the call-count assertions below stay exact.
                swap = HostSwapInfo(totalKb = 1_000L, freeKb = 500L, usedKb = 500L),
                zram = null, memoryClassMb = null, largeMemoryClassMb = null
            )
        }
    }

    private class Harness {
        val sources = FakeSources()
        var nowMs = 10_000L
        val provider = AndroidHostSystemInfoProvider(
            sources = sources,
            ioDispatcher = Dispatchers.Unconfined,
            wallClockMs = { nowMs }
        )

        fun callsOf(category: String): Int = sources.calls[category] ?: 0
    }

    @Test
    fun `first snapshot collects every category and marks them available`() = runBlocking {
        val harness = Harness()

        val snapshot = harness.provider.snapshot()

        for (category in HostInfoCategory.entries) {
            assertEquals(HostInfoAvailability.AVAILABLE, snapshot.availabilityOf(category))
        }
        assertTrue(harness.callsOf("SYSTEM") >= 1)
        assertTrue(harness.callsOf("PROCESSES") >= 1)
    }

    @Test
    fun `within TTL subsequent snapshots collect nothing`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot()
        val before = HashMap(harness.sources.calls)

        harness.nowMs += 500 // below every policy TTL (the minimum floor is CPU's 600ms)
        harness.provider.snapshot()

        assertEquals(before, harness.sources.calls)
    }

    @Test
    fun `after its own TTL a category re-collects while others ride cache`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot()
        val cpuBefore = harness.callsOf("CPU")
        val memoryBefore = harness.callsOf("MEMORY")

        harness.nowMs += HostInfoRefreshPolicy.CPU.ttlMillis + 50 // past CPU only

        harness.provider.snapshot()

        assertTrue("cpu must re-collect past its ttl", harness.callsOf("CPU") > cpuBefore)
        assertEquals("memory is on the 1s tier — must still be fresh", memoryBefore, harness.callsOf("MEMORY"))
    }

    @Test
    fun `snapshotOf collects ONLY the named categories even when everything expired`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot() // prime caches
        val before = HashMap(harness.sources.calls)

        harness.nowMs += 60 * 60 * 1000L // everything long expired
        val selective = harness.provider.snapshotOf(setOf(HostInfoCategory.CPU))

        for (name in listOf("SYSTEM", "MEMORY", "SWAP", "STORAGE", "BATTERY", "NETWORK", "PROCESSES")) {
            assertEquals("category $name must NOT be collected by a selective pass",
                before[name] ?: 0, harness.callsOf(name))
        }
        assertTrue("cpu WAS named - must have re-collected", harness.callsOf("CPU") > (before["CPU"] ?: 0))
        assertEquals(HostInfoAvailability.AVAILABLE, selective.availabilityOf(HostInfoCategory.CPU))
    }

    @Test
    fun `failed collection keeps last-known-good values and its stamp stops advancing`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot()
        val firstStamp = harness.provider.snapshot().collectedAtPerCategory[HostInfoCategory.MEMORY]

        harness.sources.throwOn.add("MEMORY")
        harness.nowMs += 5_000 // far past the memory TTL
        val afterFailure = harness.provider.snapshot()

        assertEquals(HostInfoAvailability.AVAILABLE, afterFailure.availabilityOf(HostInfoCategory.MEMORY))
        assertEquals(FakeSources.defaultMemory().memTotalKb, afterFailure.memory.memTotalKb)
        assertEquals("stamp must not move forward through failures",
            firstStamp, afterFailure.collectedAtPerCategory[HostInfoCategory.MEMORY])
    }

    @Test
    fun `never-succeeded category reports ERROR rather than fabricating values`() = runBlocking {
        val harness = Harness()
        harness.sources.throwOn.add("STORAGE")

        val snapshot = harness.provider.snapshot()

        assertEquals(HostInfoAvailability.ERROR, snapshot.availabilityOf(HostInfoCategory.STORAGE))
        assertNull(snapshot.storage.primaryVolume)
    }

    @Test
    fun `forced refresh honors the MIN_FORCED_REFRESH_MS guard`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot()
        val before = HashMap(harness.sources.calls)

        harness.nowMs += 10 // << guard
        harness.provider.refreshSnapshot()
        assertEquals(before, harness.sources.calls)

        harness.nowMs += HostInfoRefreshPolicy.MIN_FORCED_REFRESH_MS + 11
        harness.provider.refreshSnapshot()
        assertTrue(harness.callsOf("BATTERY") > (before["BATTERY"] ?: 0))
    }

    @Test
    fun `thermal mirrors the SAME-cycle battery temperature`() = runBlocking {
        val harness = Harness()

        val snapshot = harness.provider.snapshot()

        // Battery collector above always reports temperatureC=30f when it runs.
        assertEquals(30f, snapshot.thermal.batteryTemperatureC)
        assertEquals(30f, harness.sources.lastThermalBatteryTempSeen)
    }

    @Test
    fun `invalidateAll forces the next standard pass to re-collect everything`() = runBlocking {
        val harness = Harness()
        harness.provider.snapshot()
        val before = HashMap(harness.sources.calls)

        harness.provider.invalidateAll()
        harness.nowMs += 1
        harness.provider.snapshot()

        for ((name, count) in before) {
            assertTrue("category $name should have re-collected", harness.callsOf(name) > count)
        }
    }
}
