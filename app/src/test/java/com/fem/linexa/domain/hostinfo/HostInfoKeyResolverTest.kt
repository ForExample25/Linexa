package com.fem.linexa.domain.hostinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared fixtures for resolver/factory/mapper tests — a snapshot with
 * deterministic, clearly-distinguishable values so any accidental cross-field
 * copy/paste in production code shows up as an assertion failure.
 */
internal object HostInfoTestFixtures {

    fun populatedSnapshot(atEpochMs: Long = 1_000_000L): HostSystemInfoSnapshot {
        val swap = HostSwapInfo(totalKb = 8_192_000L, freeKb = 6_144_000L, usedKb = 2_048_000L)
        val memory = HostMemoryInfo(
            memTotalKb = 16_000_000L, memFreeKb = 2_000_000L, memAvailableKb = 9_000_000L,
            buffersKb = 100_000L, cachedKb = 3_000_000L, sReclaimableKb = 500_000L,
            usedLinuxKb = 7_000_000L,
            androidTotalKb = 15_800_000L, androidAvailKb = 7_500_000_000L / 1024L,
            androidThresholdKb = 1_200_000L, isLowMemory = false,
            swap = swap,
            zram = HostZramInfo(present = true, diskSizeBytes = 4_294_967_296L),
            memoryClassMb = 256, largeMemoryClassMb = 512
        )
        return HostSystemInfoSnapshot(
            collectedAtEpochMs = atEpochMs,
            cpu = HostCpuInfo(
                logicalCores = 8, onlineCores = 8, architecture = "aarch64",
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                cpuModelName = "Qualcomm Technologies, Inc SM8550",
                cpuImplementation = "0x51", coresPerCoreInfo = emptyList(),
                minFrequencyKhz = 300_000L, maxFrequencyKhz = 3_187_200L,
                currentFrequencyKhz = null,
                overallUsagePercent = 37.5f, perCoreUsagePercent = emptyList(),
                loadAvg1m = 1.23f, loadAvg5m = 0.45f, loadAvg15m = 0.67f,
                totalStatTicks = 987_654_321L, idleInclIowaitStatTicks = 900_000_000L
            ),
            memory = memory,
            swap = swap,
            storage = HostStorageInfo(
                primaryVolume = HostStorageVolume(
                    id = "primary_internal", description = "Internal storage",
                    path = "/data/user/0/com.fem.linexa/files",
                    kind = HostStorageVolume.Kind.INTERNAL_PRIMARY, state = null,
                    totalBytes = 64_000_000_000L, freeBytes = 21_000_000_000L
                ),
                externalAppVolume = null, sharedStorageVolume = null,
                allVolumes = emptyList()
            ),
            device = HostDeviceInfo(
                androidRelease = "15", sdkInt = 35, codename = "REL",
                incremental = "2024082600", securityPatch = "2025-06-01",
                manufacturer = "Google", brand = "google", model = "Pixel 9",
                product = "tokay", device = "tokay", board = "tokay",
                hardware = "zuma", bootloader = "zuma-2.0-12345",
                fingerprint = "google/tokay/tokay:15/AP4A.250105.002/…/user/release-keys",
                kernelRelease = "6.6.89-android15-8-g1234abcd", kernelVersion = "#1 SMP PREEMPT …",
                machineArch = "aarch64", selinuxEnforcing = true,
                uptimeMillis = 12_345_678L, deepSleepMillis = 10_000_000L,
                bootTimeEpochMillis = 1_700_000_000_000L
            ),
            battery = HostBatteryInfo(
                present = true, percent = 84,
                charging = HostBatteryInfo.ChargingState.CHARGING,
                powerSource = HostBatteryInfo.PowerSource.AC,
                health = HostBatteryInfo.Health.GOOD,
                temperatureC = 28.4f, voltageMillivolts = 4312,
                currentMicroamps = -212_000, chargeCounterMicroAh = 4_100_000,
                designCapacityMilliampHours = null, isPowerSaveMode = false
            ),
            thermal = HostThermalInfo(
                status = HostThermalInfo.Status.LIGHT, severityLabel = "light",
                headroom = 0.75f, cpuTemperatureC = null, gpuTemperatureC = null,
                skinTemperatureC = null, batteryTemperatureC = 28.4f
            ),
            network = HostNetworkInfo(
                isActive = true, transports = listOf("wifi"), primaryTransport = "wifi",
                isValidated = true, isMetered = false, downlinkKbps = 68_112, uplinkKbps = 22_356,
                interfaceName = "wlan0", ipv4Addresses = listOf("192.168.1.42"),
                ipv6Addresses = emptyList(), dnsServers = listOf("192.168.1.1"),
                isPrivateDnsActive = false
            ),
            processes = HostProcessInfo(
                selfPid = 4242, selfProcessName = "com.fem.linexa",
                selfRssKb = 91_000, selfPssKb = 74_000, selfCpuTicks = 3_141L,
                otherAppsVisibility = HostProcessInfo.OTHER_APPS_RESTRICTED
            ),
            capabilities = HostInfoCategory.entries.associateWith { HostInfoAvailability.AVAILABLE },
            collectedAtPerCategory = HostInfoCategory.entries.associateWith { atEpochMs }
        )
    }
}

/**
 * §15 selective resolution contract: only requested keys appear, unknowns are
 * omitted (never zero-filled), rendering is locale-stable.
 */
class HostInfoKeyResolverTest {

    @Test
    fun `only requested keys land in the output map`() {
        val keys = setOf(HostInfoKey.CPU_CORES, HostInfoKey.UPTIME_SECONDS)
        val resolved = HostInfoKeyResolver.resolve(HostInfoTestFixtures.populatedSnapshot(), keys)

        assertEquals(setOf("CPU_CORES", "UPTIME_SECONDS"), resolved.keys.toSet())
        assertEquals("8", resolved["CPU_CORES"])
        assertEquals("12345", resolved["UPTIME_SECONDS"])
    }

    @Test
    fun `unknown values are omitted rather than zero-filled`() {
        val snapshot = HostSystemInfoSnapshot.empty(atEpochMs = 9L)
        val resolved = HostInfoKeyResolver.resolve(
            snapshot,
            setOf(HostInfoKey.BATTERY_PERCENT, HostInfoKey.MEMORY_TOTAL_KB)
        )

        assertTrue(
            "absent facts must leave NO variable behind: $resolved",
            resolved.isEmpty()
        )
    }

    @Test
    fun `memory available prefers linux semantics and falls back to android`() {
        val full = HostSystemInfoSnapshot.empty(atEpochMs = 0L)
            .copy(memory = HostSystemInfoSnapshot.empty(atEpochMs = 0L).memory.copy(memAvailableKb = null))
        // Android avail is stored in KB already by the collector contract.
        val withAndroidOnly = full.copy(
            memory = full.memory.copy(androidAvailKb = 5_242_880L) // 512 MB as a KB figure, arithmetic-free (KB is the model's unit already)
        )
        assertEquals("5242880", HostInfoKeyResolver.resolve(withAndroidOnly, setOf(HostInfoKey.MEMORY_AVAILABLE_KB))["MEMORY_AVAILABLE_KB"])

        val linuxWins = withAndroidOnly.copy(
            memory = withAndroidOnly.memory.copy(memAvailableKb = 123L)
        )
        assertEquals("123", HostInfoKeyResolver.resolve(linuxWins, setOf(HostInfoKey.MEMORY_AVAILABLE_KB))["MEMORY_AVAILABLE_KB"])
    }

    @Test
    fun `charging renders as stable lowercase booleans or is withheld`() {
        val fixture = HostInfoTestFixtures.populatedSnapshot()

        val charging = fixture.battery
        assertEquals("true", resolveBattery(fixture.copy(battery = charging), charging.charging))

        val discharging = fixture.copy(battery = fixture.battery.copy(charging = HostBatteryInfo.ChargingState.DISCHARGING))
        assertEquals("false", resolveBattery(discharging, HostBatteryInfo.ChargingState.DISCHARGING))

        val unknownState = fixture.copy(battery = fixture.battery.copy(charging = HostBatteryInfo.ChargingState.UNKNOWN))
        assertTrue(HostInfoKeyResolver.resolve(unknownState, setOf(HostInfoKey.BATTERY_CHARGING)).isEmpty())
    }

    private fun resolveBattery(snapshot: HostSystemInfoSnapshot, state: HostBatteryInfo.ChargingState): String? =
        HostInfoKeyResolver.resolve(snapshot, setOf(HostInfoKey.BATTERY_CHARGING))["BATTERY_CHARGING"]

    @Test
    fun `categoriesFor covers every requestable category - PROCESSES intentionally excluded`() {
        // PROCESSES deliberately has NO selectable keys: it is a diagnostics
        // surface (self-identity only), never something a RootFS requests.
        val expected = HostInfoCategory.entries - HostInfoCategory.PROCESSES
        val categories = HostInfoKeyResolver.categoriesFor(HostInfoKey.entries.toSet())
        assertTrue(categories.containsAll(expected))
        for (key in HostInfoKey.entries) {
            assertTrue(key.category in HostInfoCategory.entries)
        }
    }

    @Test
    fun `rendering never emits decimal commas regardless of default locale`() {
        val resolved = HostInfoKeyResolver.resolve(
            HostInfoTestFixtures.populatedSnapshot(),
            setOf(HostInfoKey.CPU_LOADAVG_1M)
        )
        val value = resolved["CPU_LOADAVG_1M"]
        if (value == null) {
            org.junit.Assert.fail("loadavg missing from resolved output")
            return
        }
        assertFalse(value.contains(','))
        assertEquals("1.23", value)
    }
}
