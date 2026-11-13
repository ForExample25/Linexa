package com.fem.linexa.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1.87 — regression tests for [FileSizeFormatter]. The home screen crashed
 * with `IllegalFormatConversionException: f != java.lang.Long` because a
 * Long (integer division) was passed to a `%f` specifier for sizes ≥ 1 GiB.
 * These tests pin the binary-unit output for every tier, including very
 * large Long values that previously crashed.
 */
class FileSizeFormatterTest {

    @Test
    fun `zero bytes`() {
        assertEquals("0 B", FileSizeFormatter.format(0L))
    }

    @Test
    fun `negative clamps to zero`() {
        assertEquals("0 B", FileSizeFormatter.format(-1L))
        assertEquals("0 B", FileSizeFormatter.format(Long.MIN_VALUE))
    }

    @Test
    fun `plain bytes`() {
        assertEquals("1 B", FileSizeFormatter.format(1L))
        assertEquals("511 B", FileSizeFormatter.format(511L))
        assertEquals("1023 B", FileSizeFormatter.format(1023L))
    }

    @Test
    fun `kilobytes`() {
        assertEquals("1 KB", FileSizeFormatter.format(1024L))
        assertEquals("512 KB", FileSizeFormatter.format(512L * 1024L))
        assertEquals("1023 KB", FileSizeFormatter.format(1024L * 1024L - 1L))
    }

    @Test
    fun `megabytes`() {
        assertEquals("1 MB", FileSizeFormatter.format(1024L * 1024L))
        assertEquals("512 MB", FileSizeFormatter.format(512L * 1024L * 1024L))
        assertEquals("1023 MB", FileSizeFormatter.format(1024L * 1024L * 1024L - 1L))
    }

    @Test
    fun `gigabytes with one decimal`() {
        assertEquals("1.0 GB", FileSizeFormatter.format(1024L * 1024L * 1024L))
        assertEquals("1.5 GB", FileSizeFormatter.format(1_610_612_736L))
        assertEquals("2.5 GB", FileSizeFormatter.format(2_684_354_560L))
        assertEquals("1024.0 GB", FileSizeFormatter.format(1024L * 1024L * 1024L * 1024L - 1L))
    }

    @Test
    fun `terabytes`() {
        assertEquals("1.0 TB", FileSizeFormatter.format(1024L * 1024L * 1024L * 1024L))
        assertEquals("3.5 TB", FileSizeFormatter.format(3_848_290_697_216L))
    }

    @Test
    fun `large long values never overflow`() {
        assertEquals("8388608.0 TB", FileSizeFormatter.format(Long.MAX_VALUE))
    }

    @Test
    fun `uses locale independent decimal separator`() {
        val label = FileSizeFormatter.format(2_684_354_560L)
        assert(label.contains(".")) { "expected dot separator, got: $label" }
    }
}
