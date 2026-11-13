package com.fem.linexa.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuPercentFormatterTest {

    @Test
    fun `unknown is not zero`() {
        assertEquals(CpuPercentFormatter.UNKNOWN, CpuPercentFormatter.format(null))
    }

    @Test
    fun `a measured zero is exactly zero`() {
        assertEquals("0%", CpuPercentFormatter.format(0f))
    }

    @Test
    fun `sub-one values keep one decimal`() {
        assertEquals("<0.1%", CpuPercentFormatter.format(0.04f))
        assertEquals("0.4%", CpuPercentFormatter.format(0.4f))
        assertEquals("0.9%", CpuPercentFormatter.format(0.9f))
    }

    @Test
    fun `one percent and above keep the existing whole-percent form`() {
        assertEquals("1%", CpuPercentFormatter.format(1f))
        assertEquals("12%", CpuPercentFormatter.format(12.4f))
        assertEquals("13%", CpuPercentFormatter.format(12.5f))
        assertEquals("100%", CpuPercentFormatter.format(100f))
    }
}
