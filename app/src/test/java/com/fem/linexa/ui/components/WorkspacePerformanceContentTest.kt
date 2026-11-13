package com.fem.linexa.ui.components

import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.domain.runtime.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * UI/UX Phase 5B — what the performance panel says, in the three states that must
 * stay distinguishable.
 *
 * The plan's requirement for this panel is that a metric which cannot be
 * calculated at workspace scope reports its limitation rather than misleading
 * data. That makes **stopped**, **unknown** and **measured zero** three different
 * things, and the failure mode being guarded is that they collapse into a
 * confident `0%` — which is exactly what a screenshot cannot distinguish, and this
 * repo has no Compose UI test harness (§1.124(a)) to catch it either.
 */
class WorkspacePerformanceContentTest {

    private val running = RuntimeState.Running(pid = 1234, since = Instant.EPOCH)

    private fun sample(
        cpuPercent: Float? = 12f,
        memoryKb: Long? = 512L * 1024,
        totalKb: Long? = 4L * 1024 * 1024,
        proportional: Boolean = true,
        processCount: Int = 4
    ) = WorkspaceResourceSample(
        cpuPercentOfDevice = cpuPercent,
        cpuCores = 8,
        memoryKb = memoryKb,
        memoryTotalKb = totalKb,
        memoryIsProportional = proportional,
        processCount = processCount,
        sampledPids = processCount
    )

    private val storage = WorkspaceStorage(
        usedBytes = 2L * 1024 * 1024 * 1024,
        deviceFreeBytes = 6L * 1024 * 1024 * 1024
    )

    // ── stopped ───────────────────────────────────────────────────────────────

    /**
     * A stopped workspace has no process to measure. Showing the last reading
     * would look current, and showing `0%` would look idle — both claim knowledge
     * the panel does not have.
     */
    @Test
    fun `a stopped workspace shows no process metrics`() {
        val content = WorkspacePerformanceContent.from(RuntimeState.Stopped, sample(), storage)
        assertFalse(content.active)
        assertEquals(WorkspacePerformanceContent.UNKNOWN, content.cpu.value)
        assertEquals(WorkspacePerformanceContent.UNKNOWN, content.memory.value)
        assertFalse(content.cpu.known)
        assertFalse(content.memory.known)
        assertNull(content.cpu.fraction)
        assertNull(content.memory.fraction)
        assertNull(content.processLabel)
    }

    /**
     * A stale sample from the previous session must not leak into the stopped
     * state — the sample argument is deliberately ignored when not running.
     */
    @Test
    fun `every non-running state ignores a leftover sample`() {
        val states = listOf(
            RuntimeState.Stopped,
            RuntimeState.Starting,
            RuntimeState.Stopping,
            RuntimeState.Error("boom")
        )
        for (state in states) {
            val content = WorkspacePerformanceContent.from(state, sample(cpuPercent = 90f), storage)
            assertFalse("$state must not report active", content.active)
            assertEquals("$state must not show a CPU value", WorkspacePerformanceContent.UNKNOWN, content.cpu.value)
        }
    }

    /**
     * Storage is a property of the installed workspace, not of a session, so it
     * remains a real reading while the workspace is stopped.
     */
    @Test
    fun `storage is still reported for a stopped workspace`() {
        val content = WorkspacePerformanceContent.from(RuntimeState.Stopped, null, storage)
        assertTrue(content.storage.known)
        assertEquals("2.0 GB", content.storage.value)
        assertEquals(0.25f, content.storage.fraction!!, 0.0001f)
    }

    // ── running but unknown ───────────────────────────────────────────────────

    /**
     * The first tick after launch has no CPU delta yet. The pre-Phase-5B code
     * reported `0f` here, which drew a dip to zero into the chart on every start —
     * a value the workspace never had.
     */
    @Test
    fun `a running workspace with no cpu delta yet reads unknown not zero`() {
        val content = WorkspacePerformanceContent.from(running, sample(cpuPercent = null), storage)
        assertTrue(content.active)
        assertEquals(WorkspacePerformanceContent.UNKNOWN, content.cpu.value)
        assertFalse(content.cpu.known)
        assertNull(content.cpu.fraction)
    }

    @Test
    fun `a running workspace with no sample at all reads unknown`() {
        val content = WorkspacePerformanceContent.from(running, null, storage)
        assertTrue(content.active)
        assertFalse(content.cpu.known)
        assertFalse(content.memory.known)
        assertNull(content.processLabel)
    }

    @Test
    fun `an unreadable proc tree reads unknown rather than empty`() {
        val content = WorkspacePerformanceContent.from(
            running,
            WorkspaceResourceSample.unavailable(cpuCores = 8),
            storage
        )
        assertTrue(content.active)
        assertFalse(content.cpu.known)
        assertFalse(content.memory.known)
        // processCount 0 is not "0 processes" — a workspace that is running always
        // has at least its proot, so zero means the read failed.
        assertNull(content.processLabel)
    }

    // ── measured ──────────────────────────────────────────────────────────────

    /**
     * A measured zero is a real reading and reads as `0%`, distinct from the dash
     * used for unknown.
     */
    @Test
    fun `a measured zero is reported as zero`() {
        val content = WorkspacePerformanceContent.from(running, sample(cpuPercent = 0f), storage)
        assertTrue(content.cpu.known)
        assertEquals("0%", content.cpu.value)
        assertEquals(0f, content.cpu.fraction!!, 0.0001f)
    }

    @Test
    fun `cpu is rounded to a whole percent`() {
        assertEquals("12%", WorkspacePerformanceContent.from(running, sample(cpuPercent = 12.4f), storage).cpu.value)
        assertEquals("13%", WorkspacePerformanceContent.from(running, sample(cpuPercent = 12.5f), storage).cpu.value)
        assertEquals("100%", WorkspacePerformanceContent.from(running, sample(cpuPercent = 100f), storage).cpu.value)
    }

    @Test
    fun `sub-one cpu keeps one decimal instead of collapsing to zero or less-than-one`() {
        assertEquals("0.4%", WorkspacePerformanceContent.from(running, sample(cpuPercent = 0.4f), storage).cpu.value)
        assertEquals("<0.1%", WorkspacePerformanceContent.from(running, sample(cpuPercent = 0.04f), storage).cpu.value)
    }

    @Test
    fun `memory is formatted and fractioned`() {
        val content = WorkspacePerformanceContent.from(running, sample(memoryKb = 512L * 1024), storage)
        assertEquals("512 MB", content.memory.value)
        assertEquals(0.125f, content.memory.fraction!!, 0.0001f)
        assertFalse(content.memoryApproximate)
    }

    /**
     * A `VmRSS`-derived total double-counts pages shared between `proot` and its
     * children, so it is an upper bound and the panel must mark it. A silently
     * inflated number is worse than one labelled approximate.
     */
    @Test
    fun `an rss-derived total is marked approximate`() {
        val content = WorkspacePerformanceContent.from(
            running,
            sample(memoryKb = 700L * 1024, proportional = false),
            storage
        )
        assertTrue(content.memoryApproximate)
        assertTrue("value must carry the marker", content.memory.value.startsWith("~"))
        assertEquals("~700 MB", content.memory.value)
    }

    /**
     * The approximate flag describes a *value*, so it must not be set when there is
     * no value to qualify.
     */
    @Test
    fun `approximate is not claimed when memory is unknown`() {
        val content = WorkspacePerformanceContent.from(
            running,
            sample(memoryKb = null, proportional = false),
            storage
        )
        assertFalse(content.memoryApproximate)
    }

    @Test
    fun `process count is singular for one process`() {
        assertEquals(
            "1 process",
            WorkspacePerformanceContent.from(running, sample(processCount = 1), storage).processLabel
        )
        assertEquals(
            "4 processes",
            WorkspacePerformanceContent.from(running, sample(processCount = 4), storage).processLabel
        )
    }

    // ── storage ───────────────────────────────────────────────────────────────

    @Test
    fun `unknown storage reads as a dash`() {
        val content = WorkspacePerformanceContent.from(running, sample(), storage = null)
        assertEquals(WorkspacePerformanceContent.UNKNOWN, content.storage.value)
        assertFalse(content.storage.known)
        assertNull(content.storage.fraction)
    }

    /**
     * Unknown free space still shows the measured size; only the bar is withheld.
     */
    @Test
    fun `storage without free space shows the size but no bar`() {
        val content = WorkspacePerformanceContent.from(
            running,
            sample(),
            WorkspaceStorage(usedBytes = 1024L * 1024, deviceFreeBytes = null)
        )
        assertTrue(content.storage.known)
        assertEquals("1 MB", content.storage.value)
        assertNull(content.storage.fraction)
    }

    // ── formatting ────────────────────────────────────────────────────────────

    @Test
    fun `kb formatting crosses its unit boundaries`() {
        assertEquals("512 kB", WorkspacePerformanceContent.formatKb(512))
        assertEquals("1 MB", WorkspacePerformanceContent.formatKb(1024))
        assertEquals("512 MB", WorkspacePerformanceContent.formatKb(512 * 1024))
        assertEquals("1.0 GB", WorkspacePerformanceContent.formatKb(1024 * 1024))
        assertEquals("2.5 GB", WorkspacePerformanceContent.formatKb((2.5 * 1024 * 1024).toLong()))
        assertEquals("0 kB", WorkspacePerformanceContent.formatKb(0))
        // Never a negative size on screen.
        assertEquals("0 kB", WorkspacePerformanceContent.formatKb(-5))
    }
}
