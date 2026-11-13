package com.fem.linexa.service.runtime.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatParserTest {

    @Test
    fun readsTheForegroundProcessGroupFromARealStatLine() {
        val stat = "1234 (python3) S 1200 1234 1234 34816 1234 4194304 100 0 0 0"

        assertEquals(1234, ProcStatParser.foregroundProcessGroupId(stat))
    }

    @Test
    fun handlesExecutableNamesWithSpacesAndParentheses() {
        // The executable name may contain both; parsing must use the LAST ')'.
        val stat = "9001 (node (dev) server) S 8000 9001 9001 34816 4242 4194304 1 2 3"

        assertEquals(4242, ProcStatParser.foregroundProcessGroupId(stat))
    }

    @Test
    fun noForegroundGroupMeansNull() {
        assertNull(ProcStatParser.foregroundProcessGroupId("42 (bash) S 1 42 42 0 -1 0 0 0 0"))
        assertNull(ProcStatParser.foregroundProcessGroupId("42 (bash) S 1 42 42 0 0 0 0 0 0"))
    }

    @Test
    fun malformedStatIsNull() {
        assertNull(ProcStatParser.foregroundProcessGroupId(""))
        assertNull(ProcStatParser.foregroundProcessGroupId("not a stat line"))
        assertNull(ProcStatParser.foregroundProcessGroupId("42 (bash)"))
        assertNull(ProcStatParser.foregroundProcessGroupId("42 (bash) S 1"))
    }

    @Test
    fun processNameIsTrimmedAndBounded() {
        assertEquals("python3", ProcStatParser.processName("python3\n"))
        assertEquals("node", ProcStatParser.processName("  node  "))
        assertNull(ProcStatParser.processName(null))
        assertNull(ProcStatParser.processName("\n"))
        assertEquals(64, ProcStatParser.processName("x".repeat(200))!!.length)
    }
}
