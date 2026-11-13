package com.fem.linexa.domain.hostinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the snapshot model: immutability semantics, capability
 * totality, honest-unknown defaults, and uptime derivation — the properties
 * every consumer (UI, bridge, diagnostics) silently relies on.
 */
class HostSystemInfoSnapshotTest {

    @Test
    fun `empty snapshot exposes every category capability as unknown-classed`() {
        val snapshot = HostSystemInfoSnapshot.empty(atEpochMs = 1000L)

        assertEquals(HostInfoCategory.entries.size, snapshot.capabilities.size)
        for (category in HostInfoCategory.entries) {
            assertEquals(
                "capability for $category must be recorded even when never collected",
                HostInfoAvailability.UNAVAILABLE,
                snapshot.availabilityOf(category)
            )
            assertNull(
                "no per-category stamp may exist before the first successful collection",
                snapshot.collectedAtPerCategory[category]
            )
        }
    }

    @Test
    fun `empty snapshot fabricates nothing`() {
        val snapshot = HostSystemInfoSnapshot.empty(atEpochMs = 5L)

        assertNull(snapshot.memory.memTotalKb)
        assertNull(snapshot.memory.androidAvailKb)
        assertNull(snapshot.swap.totalKb)
        assertNull(snapshot.device.uptimeMillis)
        assertNull(snapshot.battery.percent)
        assertNull(snapshot.thermal.status)
        assertNull(snapshot.storage.primaryVolume)
        assertTrue(snapshot.storage.allVolumes.isEmpty())
        assertTrue(snapshot.network.transports.isEmpty())
        assertFalse(snapshot.network.isActive)
        // CPU cores come from the JVM (never fabricated beyond a floor)…
        assertNotNull(snapshot.cpu.logicalCores)
        // …but every measured aspect stays absent.
        assertNull(snapshot.cpu.architecture)
        assertNull(snapshot.cpu.cpuModelName)
        assertNull(snapshot.cpu.overallUsagePercent)
    }

    @Test
    fun `uptime seconds truncate from millis like proc files report`() {
        val base = HostSystemInfoSnapshot.empty(atEpochMs = 0L)
        val withUptime = base.copy(device = base.device.copy(uptimeMillis = 90_299L))

        assertEquals(90L, withUptime.uptimeSeconds)

        val none = withUptime.copy(device = withUptime.device.copy(uptimeMillis = null))
        assertNull(none.uptimeSeconds)
    }

    @Test
    fun `availabilityOf falls back to UNAVAILABLE rather than throwing`() {
        val snapshot = HostSystemInfoSnapshot.empty(atEpochMs = 1L).copy(
            capabilities = emptyMap()
        )
        assertEquals(HostInfoAvailability.UNAVAILABLE, snapshot.availabilityOf(HostInfoCategory.CPU))
    }

    @Test
    fun `snapshot is a value object - copies compare equal and are not aliased`() {
        val original = HostSystemInfoSnapshot.empty(atEpochMs = 42L)
        val copy = original.copy()

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
        // Holding one instance and "refreshing" via copy must not mutate the other.
        val updated = original.copy(collectedAtEpochMs = 43L)
        assertEquals(42L, original.collectedAtEpochMs)
        assertEquals(43L, updated.collectedAtEpochMs)
    }

    @Test
    fun `swap mirror in memory and top-level swap can differ only by absence`() {
        // Top-level swap is the BRIDGE-facing convenience; both stay null-safe.
        val snapshot = HostSystemInfoSnapshot.empty(atEpochMs = 3L)
        assertNull(snapshot.swap.totalKb)
        assertNull(snapshot.memory.swap?.totalKb ?: null)
    }

    @Test
    fun `availability transitions expose usable and transient flags honestly`() {
        assertTrue(HostInfoAvailability.AVAILABLE.isUsable)
        assertFalse(HostInfoAvailability.RESTRICTED.isUsable)
        assertFalse(HostInfoAvailability.RESTRICTED.isTransient)
        assertTrue(HostInfoAvailability.ERROR.isTransient)
        assertTrue(HostInfoAvailability.PERMISSION_DENIED.isTransient)
    }
}
