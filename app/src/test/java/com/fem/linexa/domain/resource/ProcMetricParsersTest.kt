package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UI/UX Phase 5B — the `/proc` text formats the performance panel depends on.
 *
 * Every case here is a format trap that produces a *plausible wrong number*
 * rather than an error, which is why they are asserted rather than eyeballed on a
 * chart. The `comm`-with-spaces cases in particular are the exact defect in the
 * pre-Phase-5B sampler, which split the whole `stat` line on whitespace and read
 * `utime`/`stime` at fixed indices.
 */
class ProcMetricParsersTest {

    /**
     * A real-shaped `/proc/<pid>/stat` line.
     *
     * Fields after `comm` are numbered so a mis-indexed read is obvious: state is
     * field 3, ppid field 4, and utime/stime are fields 14/15 — here 500 and 250.
     */
    private fun statLine(
        pid: Int = 1234,
        comm: String = "proot",
        ppid: Int = 1000,
        utime: Long = 500,
        stime: Long = 250
    ): String {
        val fields = mutableListOf<String>()
        fields += "R"                 // 3 state
        fields += ppid.toString()     // 4 ppid
        fields += "1000"              // 5 pgrp
        fields += "1000"              // 6 session
        fields += "0"                 // 7 tty_nr
        fields += "-1"                // 8 tpgid
        fields += "4194304"           // 9 flags
        fields += "100"               // 10 minflt
        fields += "0"                 // 11 cminflt
        fields += "0"                 // 12 majflt
        fields += "0"                 // 13 cmajflt
        fields += utime.toString()    // 14 utime
        fields += stime.toString()    // 15 stime
        fields += "10"                // 16 cutime
        fields += "5"                 // 17 cstime
        return "$pid ($comm) ${fields.joinToString(" ")}"
    }

    @Test
    fun `cpu ticks are the sum of utime and stime`() {
        assertEquals(750L, ProcMetricParsers.cpuTicks(statLine(utime = 500, stime = 250)))
    }

    /**
     * The defect this parser exists for.
     *
     * `comm` is field 2 and may contain spaces. Splitting the raw line on
     * whitespace shifts every later field, so a process named `sh (deleted)` or
     * `Web Content` yielded whatever happened to sit at index 13/14 — a number,
     * so nothing failed; the CPU reading was simply wrong.
     */
    @Test
    fun `a process name containing spaces does not shift the cpu fields`() {
        assertEquals(
            750L,
            ProcMetricParsers.cpuTicks(statLine(comm = "my long name", utime = 500, stime = 250))
        )
    }

    /**
     * `comm` may also contain parentheses, so the closing one must be found from
     * the *end* of the field, not the first `)` in the line.
     */
    @Test
    fun `a process name containing parentheses does not shift the cpu fields`() {
        assertEquals(
            750L,
            ProcMetricParsers.cpuTicks(statLine(comm = "bash (deleted)", utime = 500, stime = 250))
        )
        assertEquals(
            1000,
            ProcMetricParsers.parentPid(statLine(comm = "sh (deleted) (x)", ppid = 1000))
        )
    }

    @Test
    fun `process name is extracted without its parentheses`() {
        assertEquals("proot", ProcMetricParsers.processName(statLine(comm = "proot")))
        assertEquals("bash (deleted)", ProcMetricParsers.processName(statLine(comm = "bash (deleted)")))
    }

    @Test
    fun `parent pid is field four`() {
        assertEquals(1000, ProcMetricParsers.parentPid(statLine(ppid = 1000)))
        assertEquals(1, ProcMetricParsers.parentPid(statLine(ppid = 1)))
    }

    /**
     * Unknown must stay distinguishable from zero all the way down: a malformed
     * line contributing `0L` to a tree total would read as an idle process.
     */
    @Test
    fun `a malformed stat line is unknown rather than zero`() {
        assertNull(ProcMetricParsers.cpuTicks(""))
        assertNull(ProcMetricParsers.cpuTicks("1234 proot R 1000"))       // no parens
        assertNull(ProcMetricParsers.cpuTicks("1234 (proot)"))            // nothing after comm
        assertNull(ProcMetricParsers.cpuTicks("1234 (proot) R 1000 1"))   // too few fields
        assertNull(ProcMetricParsers.parentPid(""))
        assertNull(ProcMetricParsers.parentPid("garbage"))
        assertNull(ProcMetricParsers.processName("1234 proot R"))
    }

    @Test
    fun `a non-numeric cpu field is unknown rather than zero`() {
        val corrupted = statLine().replace(" 500 250 ", " x 250 ")
        assertNull(ProcMetricParsers.cpuTicks(corrupted))
    }

    @Test
    fun `pss is read from smaps rollup`() {
        val rollup = """
            Rss:               12345 kB
            Pss:                6789 kB
            Pss_Dirty:          1000 kB
            Shared_Clean:       2000 kB
        """.trimIndent()
        assertEquals(6789L, ProcMetricParsers.pssKb(rollup))
    }

    /**
     * `Pss_Dirty:` also starts with "Pss" — matching on a prefix shorter than the
     * whole label including its colon would pick whichever came first.
     */
    @Test
    fun `pss is not confused with pss dirty`() {
        val dirtyFirst = """
            Pss_Dirty:          1000 kB
            Pss:                6789 kB
        """.trimIndent()
        assertEquals(6789L, ProcMetricParsers.pssKb(dirtyFirst))
    }

    @Test
    fun `a rollup without pss is unknown`() {
        assertNull(ProcMetricParsers.pssKb("Rss: 12345 kB"))
        assertNull(ProcMetricParsers.pssKb(""))
    }

    @Test
    fun `vm rss is read from status`() {
        val status = """
            Name:	proot
            State:	S (sleeping)
            VmPeak:	  123456 kB
            VmRSS:	   45678 kB
            VmData:	   10000 kB
        """.trimIndent()
        assertEquals(45678L, ProcMetricParsers.vmRssKb(status))
    }

    @Test
    fun `mem total is read from meminfo`() {
        val meminfo = """
            MemTotal:        7654321 kB
            MemFree:         1234567 kB
            MemAvailable:    2345678 kB
        """.trimIndent()
        assertEquals(7654321L, ProcMetricParsers.memTotalKb(meminfo))
    }

    @Test
    fun `a missing meminfo field is unknown`() {
        assertNull(ProcMetricParsers.memTotalKb("MemFree: 100 kB"))
    }
}
