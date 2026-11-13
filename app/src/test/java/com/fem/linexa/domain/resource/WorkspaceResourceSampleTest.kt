package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UI/UX Phase 5B — the derived fractions on a sample, and the storage reading.
 *
 * These are the arithmetic the bars' lengths come from, and every case here is
 * one where the honest answer is "unknown" and the tempting answer is a number.
 */
class WorkspaceResourceSampleTest {

    private fun sample(
        cpuPercent: Float? = 25f,
        memoryKb: Long? = 512 * 1024,
        totalKb: Long? = 4 * 1024 * 1024,
        proportional: Boolean = true
    ) = WorkspaceResourceSample(
        cpuPercentOfDevice = cpuPercent,
        cpuCores = 8,
        memoryKb = memoryKb,
        memoryTotalKb = totalKb,
        memoryIsProportional = proportional,
        processCount = 3,
        sampledPids = 3
    )

    @Test
    fun `memory fraction is used over device total`() {
        val fraction = sample(memoryKb = 1024, totalKb = 4096).memoryFraction
        assertEquals(0.25f, fraction!!, 0.0001f)
    }

    /**
     * A missing denominator has no honest bar length. Returning `0f` would draw an
     * empty bar for a workspace using real memory.
     */
    @Test
    fun `memory fraction is unknown when either side is unknown`() {
        assertNull(sample(memoryKb = null).memoryFraction)
        assertNull(sample(totalKb = null).memoryFraction)
        assertNull(sample(totalKb = 0).memoryFraction)
    }

    /**
     * `Pss` summed across a tree can exceed the reported total on some kernels
     * (rounding, and pages counted against a total that excludes some reserved
     * memory), so the fraction is clamped rather than allowed to overflow the bar.
     */
    @Test
    fun `memory fraction is clamped to the bar`() {
        assertEquals(1f, sample(memoryKb = 9999, totalKb = 4096).memoryFraction!!, 0.0001f)
    }

    @Test
    fun `cpu fraction is the percentage over one hundred`() {
        assertEquals(0.25f, sample(cpuPercent = 25f).cpuFraction!!, 0.0001f)
        assertEquals(0f, sample(cpuPercent = 0f).cpuFraction!!, 0.0001f)
    }

    /**
     * The first sample of a pid genuinely has no CPU value — it is a delta — and
     * that must stay distinct from a measured zero.
     */
    @Test
    fun `cpu fraction is unknown when the percentage is unknown`() {
        assertNull(sample(cpuPercent = null).cpuFraction)
    }

    @Test
    fun `an unavailable sample knows nothing except the core count`() {
        val unavailable = WorkspaceResourceSample.unavailable(cpuCores = 8)
        assertNull(unavailable.cpuPercentOfDevice)
        assertNull(unavailable.memoryKb)
        assertNull(unavailable.memoryFraction)
        assertNull(unavailable.cpuFraction)
        assertEquals(0, unavailable.processCount)
        assertEquals(8, unavailable.cpuCores)
    }
}

/**
 * The storage reading. Its denominator is `used + free`, i.e. the space this
 * workspace could occupy — not total device capacity, which would render every
 * workspace as a sliver against the OS and other apps and tell the user nothing.
 */
class WorkspaceStorageTest {

    @Test
    fun `fraction is the workspace share of the space available to it`() {
        val storage = WorkspaceStorage(usedBytes = 1_000L, deviceFreeBytes = 3_000L)
        assertEquals(0.25f, storage.fraction!!, 0.0001f)
    }

    /**
     * Unknown free space means the bar has no honest length, but the *measured*
     * size is still real and must still be shown — so the fraction is null while
     * the label is not.
     */
    @Test
    fun `unknown free space withholds the bar but not the size`() {
        val storage = WorkspaceStorage(usedBytes = 2_000_000L, deviceFreeBytes = null)
        assertNull(storage.fraction)
        assertNull(storage.freeLabel)
        assertEquals("1 MB", storage.usedLabel)
    }

    @Test
    fun `a full volume reads as full rather than dividing by zero`() {
        assertEquals(1f, WorkspaceStorage(1_000L, 0L).fraction!!, 0.0001f)
        assertNull(WorkspaceStorage(0L, 0L).fraction)
    }

    @Test
    fun `labels use the app-wide binary units`() {
        val storage = WorkspaceStorage(usedBytes = 3L * 1024 * 1024 * 1024, deviceFreeBytes = 5L * 1024 * 1024 * 1024)
        assertEquals("3.0 GB", storage.usedLabel)
        assertEquals("5.0 GB", storage.freeLabel)
    }
}
