package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the 2026-08-27 host-bridge expansion parsers: affinity mask
 * expansion (Cpus_allowed_list), per-core /proc/stat grid totals, and the
 * sampler-grade pid-stat parse. The comm-with-spaces trap rules from the
 * original suite apply to every one of these too.
 */
class ProcMetricParsersExpansionTest {

    // ── cpuAffinityAllowedList ─────────────────────────────────────────

    @Test
    fun `affinity list expands ranges`() {
        val body = """
            Name:   com.fem.linexa
            Umask:  0022
            Cpus_allowed:	 ff
            Cpus_allowed_list:	0-3,6
        """.trimIndent()
        assertEquals(listOf(0, 1, 2, 3, 6), ProcMetricParsers.cpuAffinityAllowedList(body))
    }

    @Test
    fun `single-core affinity parses without dash`() {
        val body = "Threads: 12\nCpus_allowed_list:\t4\n"
        assertEquals(listOf(4), ProcMetricParsers.cpuAffinityAllowedList(body))
    }

    @Test
    fun `missing or malformed line yields null not empty`() {
        assertNull(ProcMetricParsers.cpuAffinityAllowedList("Name: x\nVmRSS:\t100 kB\n"))
        assertNull(ProcMetricParsers.cpuAffinityAllowedList("Cpus_allowed_list:\tz \n"))
        assertNull(ProcMetricParsers.cpuAffinityAllowedList("Cpus_allowed_list:\t9-2\n"))
    }

    @Test
    fun `absurd span rejected as malformed`() {
        assertNull(ProcMetricParsers.cpuAffinityAllowedList("Cpus_allowed_list:\t0-999999\n"))
    }

    // ── procStatPerCoreTotals ──────────────────────────────────────────

    @Test
    fun `per-core totals sum all ten columns per cpuN line`() {
        val stat = """
            cpu  100 0 50 400 40 0 10 0 0 0
            cpu0 8 1 6 70 5 0 2 0 0 0
            cpu4 9 1 7 80 5 0 1 0 0 0
        """.trimIndent()
        val grid = ProcMetricParsers.procStatPerCoreTotals(stat)
        assertEquals(92L, grid!![0])               // 8+1+6+70+5+2 = 92
        assertEquals(103L, grid[4])                // 9+1+7+80+5+1 = 103
        assertEquals(setOf(0, 4), grid.keys)
    }

    @Test
    fun `aggregate line never pollutes grid keys`() {
        val grid = ProcMetricParsers.procStatPerCoreTotals("cpu  1 1 1 1 1 1 1 1 1 1\ncpu7 0 0 0 5 0 0 0 0 0 0\n")
        assertEquals(setOf(7), grid!!.keys)
        assertEquals(5L, grid[7])
    }

    @Test
    fun `malformed row voids whole grid rather than lying partially`() {
        val stat = "cpu0 1 1 1 1 1 1 1 1 1 1\ncpu1 2 2 2 oops 2 2 2 2 2 2\n"
        assertNull(ProcMetricParsers.procStatPerCoreTotals(stat))
    }

    @Test
    fun `empty input has no grid`() {
        assertNull(ProcMetricParsers.procStatPerCoreTotals(""))
        assertNull(ProcMetricParsers.procStatPerCoreTotals("intr 12345\nctxt 99\n"))
    }

    // ── pidTickSample ───────────────────────────────────────────────────

    private fun pidStat(
        comm: String,
        state: Char,
        utime: Long, stime: Long, cutime: Long, cstime: Long,
        lastCore: Int
    ): String {
        val sb = StringBuilder()
        sb.append("91 ($comm) $state 1 1 1 -1 -1 0 0 0 0 50 ")
        sb.append("$utime $stime $cutime $cstime ")
        repeat(21) { sb.append("${it + 3} ") }
        sb.append(lastCore)
        repeat(13) { sb.append(" 0") }
        return sb.toString()
    }

    @Test
    fun `busy includes reaped children columns`() {
        val parsed = ProcMetricParsers.pidTickSample(
            pidStat("(worker pro) (ces)s", 'R', utime = 11, stime = 22, cutime = 33, cstime = 44, lastCore = 5)
        )!!
        assertEquals(110L, parsed.busyTicks)
        assertEquals('R', parsed.stateChar)
        assertEquals(5, parsed.lastCore)
    }

    @Test
    fun `malformed numerics yield null sentinel`() {
        val bad = "91 (x) S 1 1 1 -1 -1 0 0 0 0 50 xx 2 0 0 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1"
        assertNull(ProcMetricParsers.pidTickSample(bad))
    }
}
