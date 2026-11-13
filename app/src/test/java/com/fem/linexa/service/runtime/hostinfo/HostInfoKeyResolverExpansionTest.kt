package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.hostinfo.HostInfoKey
import com.fem.linexa.domain.hostinfo.HostInfoKeyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2/15 additions to the key contract: the configured/granted/online
 * triad renders as DISTINCT keys (never merged), compact-range formatting
 * matches taskset conventions, and sanitized network exposure stays at
 * name-and-primary-address grain.
 */
class HostInfoKeyResolverExpansionTest {

    private fun snapshotWith(
        logical: Int = 8,
        online: Int? = 8,
        affinity: List<Int>? = listOf(4, 5, 6)
    ): com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot {
        val base = com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot.empty(atEpochMs = 0L)
        return base.copy(
            cpu = base.cpu.copy(
                logicalCores = logical,
                onlineCores = online,
                affinityCoreIndices = affinity
            )
        )
    }

    @Test
    fun `triad keys stay distinct`() {
        val out = HostInfoKeyResolver.resolve(
            snapshotWith(),
            setOf(HostInfoKey.CPU_CORES, HostInfoKey.CPU_AFFINITY_COUNT,
                HostInfoKey.CPU_AFFINITY_CORES, HostInfoKey.CPU_ONLINE_COUNT)
        )
        assertEquals("8", out[HostInfoKey.CPU_CORES.name])
        assertEquals("3", out[HostInfoKey.CPU_AFFINITY_COUNT.name])
        assertEquals("4-6", out[HostInfoKey.CPU_AFFINITY_CORES.name])
        assertEquals("8", out[HostInfoKey.CPU_ONLINE_COUNT.name])
    }

    @Test
    fun `unknown affinity omits both granted keys instead of faking full-set`() {
        val out = HostInfoKeyResolver.resolve(
            snapshotWith(affinity = null),
            setOf(HostInfoKey.CPU_AFFINITY_COUNT, HostInfoKey.CPU_AFFINITY_CORES)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `compact ranges merge runs and split gaps taskset-style`() {
        assertEquals(listOf("0-3", "6"), HostInfoKeyResolver.compactRanges(listOf(0, 1, 2, 3, 6)))
        assertEquals(listOf("7"), HostInfoKeyResolver.compactRanges(listOf(7)))
        assertEquals(listOf("1-3", "5", "9"), HostInfoKeyResolver.compactRanges(listOf(9, 5, 1, 3, 2)))
        assertEquals(listOf("4-7", "9"), HostInfoKeyResolver.compactRanges(listOf(6, 4, 9, 7, 5)))
        assertTrue(HostInfoKeyResolver.compactRanges(emptyList()).isEmpty())
    }

    @Test
    fun `selinux enforcing renders lowercase booleans or omits when unknown`() {
        val withValue = snapshotWith().copy(
            device = snapshotWith().device.copy(selinuxEnforcing = true)
        )
        val out = HostInfoKeyResolver.resolve(withValue, setOf(HostInfoKey.SELINUX_ENFORCING))
        assertEquals("true", out[HostInfoKey.SELINUX_ENFORCING.name])
        assertTrue(HostInfoKeyResolver.resolve(snapshotWith(), setOf(HostInfoKey.SELINUX_ENFORCING)).isEmpty())
    }
}
