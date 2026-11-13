package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roll-up both the Activity tab and the Sessions tab use for workspace
 * numbers. The cases are the ones where a plausible wrong number is easy: no
 * readable CPU (must be unknown, not 0), a pid reachable from two trees (must be
 * counted once), and a single RSS fallback poisoning an otherwise-PSS sum.
 */
class WorkspaceAggregatorTest {

    private fun p(
        pid: Int,
        cpu: Float? = null,
        mem: Long? = null,
        proportional: Boolean? = mem?.let { true },
        root: Boolean = false
    ) = WorkspaceProcessSample(
        pid = pid,
        name = "p$pid",
        stateChar = 'S',
        cpuPercentOfDevice = cpu,
        memKb = mem,
        isRoot = root,
        memoryIsProportional = proportional
    )

    @Test
    fun `empty tree is unknown not zero`() {
        val r = WorkspaceAggregator.rollup(emptyList())
        assertNull(r.cpuPercentOfDevice)
        assertNull(r.memoryKb)
        assertEquals(0, r.processCount)
    }

    @Test
    fun `no readable cpu stays null even with processes present`() {
        val r = WorkspaceAggregator.rollup(listOf(p(1, cpu = null, mem = 100), p(2, cpu = null, mem = 50)))
        assertNull(r.cpuPercentOfDevice)
        assertEquals(150L, r.memoryKb)
        assertEquals(2, r.processCount)
    }

    @Test
    fun `cpu and memory are summed and cpu is clamped to 100`() {
        val r = WorkspaceAggregator.rollup(listOf(p(1, cpu = 60f, mem = 10), p(2, cpu = 70f, mem = 20)))
        assertEquals(100f, r.cpuPercentOfDevice!!, 0.001f)
        assertEquals(30L, r.memoryKb)
        assertTrue(r.memoryIsProportional)
    }

    @Test
    fun `a single rss fallback makes the whole memory sum approximate`() {
        val r = WorkspaceAggregator.rollup(
            listOf(p(1, mem = 10, proportional = true), p(2, mem = 20, proportional = false))
        )
        assertFalse(r.memoryIsProportional)
    }

    @Test
    fun `process without a cpu baseline contributes nothing to the sum`() {
        val r = WorkspaceAggregator.rollup(listOf(p(1, cpu = 5f), p(2, cpu = null)))
        assertEquals(5f, r.cpuPercentOfDevice!!, 0.001f)
    }

    @Test
    fun `merge counts a pid reachable from two roots once and orders root first`() {
        val shared = p(30, cpu = 4f, mem = 40)
        val treeA = listOf(p(10, root = true), shared)
        val treeB = listOf(p(20, root = true), shared.copy(cpuPercentOfDevice = 99f))
        val merged = WorkspaceAggregator.mergeTrees(listOf(treeA, treeB))
        assertEquals(listOf(10, 20, 30), merged.map { it.pid })
        // First tree to claim the pid wins — the duplicate's reading is ignored.
        assertEquals(4f, merged.single { it.pid == 30 }.cpuPercentOfDevice!!, 0.001f)
    }

    @Test
    fun `merge is stable regardless of input order within a tree`() {
        val a = WorkspaceAggregator.mergeTrees(listOf(listOf(p(3), p(1), p(2))))
        val b = WorkspaceAggregator.mergeTrees(listOf(listOf(p(2), p(3), p(1))))
        assertEquals(a.map { it.pid }, b.map { it.pid })
    }

    @Test
    fun `workspace sample uses device total only as the denominator`() {
        val s = WorkspaceAggregator.workspaceSample(
            processes = listOf(p(1, cpu = 10f, mem = 1024)),
            cores = 8,
            deviceTotalKb = 4096
        )
        assertEquals(0.25f, s.memoryFraction!!, 0.001f)
        assertEquals(10f, s.cpuPercentOfDevice!!, 0.001f)
        assertEquals(8, s.cpuCores)
    }

    @Test
    fun `workspace sample without device total has no memory fraction`() {
        val s = WorkspaceAggregator.workspaceSample(listOf(p(1, mem = 10)), cores = 4, deviceTotalKb = null)
        assertNull(s.memoryFraction)
    }
}
