package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LINEXA-PERF-002: [RootTreePartition] must be a drop-in for the previous
 * `ProcessTree.descendantsOf` + filter pair. These tests pin that equivalence
 * on the cases the process table actually produces (several terminals sharing
 * one workspace root, an orphan reparented to init, a corrupted cycle, a root
 * with no children, duplicate roots).
 */
class RootTreePartitionTest {

    private fun process(pid: Int, parentPid: Int? = null) = WorkspaceProcessSample(
        pid = pid,
        name = "p$pid",
        stateChar = 'S',
        cpuPercentOfDevice = null,
        memKb = null,
        isRoot = parentPid == null,
        parentPid = parentPid
    )

    /** The exact semantics the SESSION path relied on before the rewrite. */
    private fun reference(
        roots: Collection<Int>,
        processes: List<WorkspaceProcessSample>
    ): Map<Int, Set<Int>> {
        val parents = processes.mapNotNull { p -> p.parentPid?.let { p.pid to it } }.toMap()
        return roots.associateWith { root ->
            val members = ProcessTree.descendantsOf(root, parents)
            processes.filter { it.pid in members }.map { it.pid }.toSet()
        }
    }

    private fun assertMatchesReference(roots: List<Int>, processes: List<WorkspaceProcessSample>) {
        val actual = RootTreePartition.byRoot(roots, processes).mapValues { it.value.map { p -> p.pid }.toSet() }
        assertEquals(reference(roots, processes), actual)
    }

    @Test
    fun `matches the reference semantics for a shared workspace tree`() {
        val processes = listOf(
            process(100),            // workspace root
            process(101, 100),       // terminal 1
            process(102, 101),
            process(103, 101),
            process(104, 100),       // terminal 2
            process(105, 104)
        )
        assertMatchesReference(listOf(101, 104, 100), processes)
    }

    @Test
    fun `a cycle terminates and matches the reference verdict`() {
        val processes = listOf(
            process(10, 11),
            process(11, 10),
            process(12, 10),
            process(20)
        )
        assertMatchesReference(listOf(10, 12, 20), processes)
    }

    @Test
    fun `a root outside the visible table yields an empty tree`() {
        val processes = listOf(process(1), process(2, 1))
        assertMatchesReference(listOf(99), processes)
    }

    @Test
    fun `duplicate roots are evaluated once with identical content`() {
        val processes = listOf(process(1), process(2, 1))
        val result = RootTreePartition.byRoot(listOf(1, 1), processes)
        assertEquals(setOf(1, 2), result.getValue(1).map { it.pid }.toSet())
        assertEquals(1, result.keys.size)
    }

    @Test
    fun `no roots means no work`() {
        assertEquals(emptyMap<Int, List<WorkspaceProcessSample>>(), RootTreePartition.byRoot(emptyList(), emptyList()))
    }
}
