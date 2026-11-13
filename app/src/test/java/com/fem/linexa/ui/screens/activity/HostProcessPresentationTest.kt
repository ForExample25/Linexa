package com.fem.linexa.ui.screens.activity

import com.fem.linexa.domain.resource.HostProcessSample
import com.fem.linexa.domain.resource.MetricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACT-2 — host-process presentation rules.
 *
 * The section is a monitor of the Android process table, so "collapsed" must
 * only stop composing rows: ordering, identity and the full sample set stay
 * exactly as the scanner produced them.
 */
class HostProcessPresentationTest {

    private fun process(
        pid: Int,
        name: String,
        cpu: Float? = null
    ): HostProcessSample = HostProcessSample(
        pid = pid,
        startTimeTicks = pid.toLong(),
        parentPid = null,
        uid = null,
        name = name,
        command = null,
        stateChar = 'S',
        threads = null,
        cpuPercentOfDevice = cpu,
        cpuAvailability = if (cpu == null) MetricAvailability.PENDING else MetricAvailability.AVAILABLE,
        memKb = null,
        memoryIsProportional = null,
        lastCore = null,
        isSelf = false,
        canSignal = false
    )

    @Test
    fun ordersByCpuThenNameThenPid() {
        val rows = HostProcessPresentation.ordered(
            listOf(
                process(1, "zeta", 5f),
                process(2, "alpha", 10f),
                process(3, "alpha", 10f),
                process(4, "beta", 10f),
                process(5, "gamma", 1f)
            )
        )
        assertEquals(listOf(2, 3, 4, 1, 5), rows.map { it.pid })
    }

    @Test
    fun missingCpuSortsAfterMeasuredProcesses() {
        val rows = HostProcessPresentation.ordered(
            listOf(
                process(1, "unknown"),
                process(2, "measured", 0f)
            )
        )
        assertEquals(listOf(2, 1), rows.map { it.pid })
    }

    @Test
    fun orderingIsCaseInsensitiveForNames() {
        val rows = HostProcessPresentation.ordered(
            listOf(
                process(1, "Beta", 1f),
                process(2, "alpha", 1f)
            )
        )
        assertEquals(listOf(2, 1), rows.map { it.pid })
    }

    @Test
    fun keysAreStableAndScopedToTheHostSection() {
        val first = process(4242, "worker", 1f)
        val second = first.copy(cpuPercentOfDevice = 9f)
        assertEquals("host-4242", HostProcessPresentation.key(first))
        assertEquals(
            "a sample update must re-use the row instead of creating a new identity",
            HostProcessPresentation.key(first),
            HostProcessPresentation.key(second)
        )
        assertFalse(HostProcessPresentation.key(first) == "session-4242")
    }

    @Test
    fun collapsedStateComposesNoProcessRows() {
        val rows = HostProcessPresentation.ordered((1..200).map { process(it, "p$it") })
        assertTrue(HostProcessPresentation.visible(rows, expanded = false).isEmpty())
    }

    @Test
    fun expandedStateKeepsEveryProcessInOrder() {
        val input = (1..200).map { process(it, "p$it") }
        val ordered = HostProcessPresentation.ordered(input)
        val visible = HostProcessPresentation.visible(ordered, expanded = true)
        assertEquals(200, visible.size)
        assertEquals(ordered, visible)
        assertEquals(input.map { it.pid }.sorted().toSet(), visible.map { it.pid }.toSet())
    }

    @Test
    fun collapsingDoesNotMutateTheBackendSampleList() {
        val input = listOf(process(1, "a", 3f), process(2, "b", 1f))
        HostProcessPresentation.visible(HostProcessPresentation.ordered(input), expanded = false)
        assertEquals(listOf(1, 2), input.map { it.pid })
    }

    @Test
    fun lowLevelHostNamesArePreservedNotFiltered() {
        // This section is the host monitor, not a workspace session card. A
        // process that burns CPU (including ssh or a PRoot helper) stays
        // visible; filtering here would hide a real device consumer.
        val names = listOf("libproot.so", "ssh", "com.fem.linexa")
        val rows = HostProcessPresentation.ordered(
            names.mapIndexed { index, name -> process(index + 1, name, 1f) }
        )
        assertEquals(names.toSet(), rows.map { it.name }.toSet())
    }

    @Test
    fun keyIsDerivedFromPidNotListPosition() {
        // Sorting must not remap identities: the key travels with the process.
        val rows = HostProcessPresentation.ordered(
            listOf(process(7, "a", 1f), process(8, "b", 2f))
        )
        assertEquals(
            listOf("host-8", "host-7"),
            rows.map { HostProcessPresentation.key(it) }
        )
    }
}
