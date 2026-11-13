package com.fem.linexa.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalNumberingTest {

    @Test
    fun `labels are gap free after a close`() {
        // T1 was closed; internal ids keep growing but the visible numbers do not skip.
        val labels = TerminalNumbering.compactLabels(listOf("T0", "T2", "T3"))
        assertEquals(mapOf("T0" to "T1", "T2" to "T2", "T3" to "T3"), labels)
    }

    @Test
    fun `ordering is numeric not lexicographic`() {
        val labels = TerminalNumbering.compactLabels(listOf("T10", "T2", "T1"))
        assertEquals("T1", labels["T1"])
        assertEquals("T2", labels["T2"])
        assertEquals("T3", labels["T10"])
    }

    @Test
    fun `non numeric ids sort last and duplicates collapse`() {
        val labels = TerminalNumbering.compactLabels(listOf("root", "T0", "T0"))
        assertEquals(mapOf("T0" to "T1", "root" to "T2"), labels)
    }
}
