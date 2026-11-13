package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcMetricParsersProcessTest {

    private fun statLine(
        pid: Int = 42,
        comm: String = "bash (deleted)",
        ppid: Int = 7,
        utime: Long = 500,
        stime: Long = 250,
        cutime: Long = 10,
        cstime: Long = 5,
        startTime: Long = 123456,
        processor: Int = 3
    ): String {
        val fields = MutableList(37) { "0" }
        fields[0] = "R"
        fields[1] = ppid.toString()
        fields[11] = utime.toString()
        fields[12] = stime.toString()
        fields[13] = cutime.toString()
        fields[14] = cstime.toString()
        fields[19] = startTime.toString()
        fields[36] = processor.toString()
        return "$pid ($comm) ${fields.joinToString(" ")}"
    }

    @Test
    fun `full stat parse keeps own ticks separate from child ticks`() {
        val parsed = requireNotNull(ProcMetricParsers.pidStatSample(statLine()))
        assertEquals(7, parsed.parentPid)
        assertEquals('R', parsed.stateChar)
        assertEquals(750L, parsed.ownTicks)
        assertEquals(765L, parsed.busyTicks)
        assertEquals(123456L, parsed.startTimeTicks)
        assertEquals(3, parsed.lastCore)
        assertEquals("bash (deleted)", parsed.name)
    }

    @Test
    fun `a malformed stat is null rather than zero`() {
        assertNull(ProcMetricParsers.pidStatSample(""))
        assertNull(ProcMetricParsers.pidStatSample("42 (bash) R 7 0"))
    }

    @Test
    fun `uid and threads come from status`() {
        val status = """
            Name:	bash
            Uid:	10123	10123	10123	10123
            Threads:	4
            VmRSS:	   1234 kB
        """.trimIndent()
        assertEquals(10123, ProcMetricParsers.statusUid(status))
        assertEquals(4, ProcMetricParsers.statusThreads(status))
        assertEquals(1234L, ProcMetricParsers.vmRssKb(status))
    }

    @Test
    fun `missing uid and threads stay unknown`() {
        assertNull(ProcMetricParsers.statusUid("Name:\tbash"))
        assertNull(ProcMetricParsers.statusThreads("Name:\tbash"))
    }

    @Test
    fun `command line joins argv and bounds output`() {
        assertEquals("proot -r /rootfs sh", ProcMetricParsers.commandLine("proot\u0000-r\u0000/rootfs\u0000sh"))
        assertEquals("a b c", ProcMetricParsers.commandLine("a\u0000b\u0000c"))
        assertEquals("ab", ProcMetricParsers.commandLine("abcdef", maxChars = 2))
        assertNull(ProcMetricParsers.commandLine("\u0000\u0000"))
        assertNull(ProcMetricParsers.commandLine(null))
    }

    @Test
    fun `cpu range syntax expands and rejects malformed input`() {
        assertEquals(listOf(0, 1, 2, 3, 6), ProcMetricParsers.cpuRangeList("0-3,6"))
        assertEquals(listOf(2), ProcMetricParsers.cpuRangeList("2"))
        assertNull(ProcMetricParsers.cpuRangeList(""))
        assertNull(ProcMetricParsers.cpuRangeList("1-x"))
        assertNull(ProcMetricParsers.cpuRangeList("3-1"))
    }
}
