package com.fem.linexa.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [HostsFileMerger] (§21): the merge must be idempotent,
 * own only its marked block, preserve every user line byte-for-byte (in
 * order), and sanitize both the entries and the hostname it manages.
 */
class HostsFileMergerTest {

    private val entries = listOf("127.0.0.1 localhost", "::1 localhost", "127.0.0.1 debian-12")

    @Test
    fun mergeIntoNullProducesExactlyTheManagedBlock() {
        val out = HostsFileMerger.merge(null, entries)
        assertEquals(
            "# BEGIN Linexa managed entries\n" +
                "127.0.0.1 localhost\n" +
                "::1 localhost\n" +
                "127.0.0.1 debian-12\n" +
                "# END Linexa managed entries",
            out
        )
    }

    @Test
    fun mergeIntoExistingAppendsBlockAfterBlankLine() {
        val out = HostsFileMerger.merge("127.0.0.1 old\n10.0.0.2 custom", entries)
        assertTrue(out.startsWith("127.0.0.1 old\n10.0.0.2 custom\n\n# BEGIN Linexa managed entries"))
        assertTrue(out.endsWith("# END Linexa managed entries"))
        // User lines preserved byte-for-byte and in order.
        val lines = out.lines()
        assertEquals("127.0.0.1 old", lines[0])
        assertEquals("10.0.0.2 custom", lines[1])
    }

    @Test
    fun mergeIsIdempotent() {
        val existing = "127.0.0.1 old\n10.0.0.2 custom"
        val once = HostsFileMerger.merge(existing, entries)
        val twice = HostsFileMerger.merge(once, entries)
        assertEquals(once, twice)
    }

    @Test
    fun previousManagedBlockIsReplacedNotDuplicated() {
        val stale = HostsFileMerger.merge("user line", listOf("127.0.0.1 stalehost"))
        val out = HostsFileMerger.merge(stale, entries)
        assertEquals(1, out.split("\n").count { it.trim() == HostsFileMerger.BEGIN_MARKER })
        assertFalse(out.contains("stalehost"))
        assertTrue(out.contains("debian-12"))
        assertTrue(out.contains("user line"))
    }

    @Test
    fun userLinesBeforeAndAfterTheOldBlockSurviveInOrder() {
        val existing = "first\n" +
            "# BEGIN Linexa managed entries\n" +
            "127.0.0.1 stale\n" +
            "# END Linexa managed entries\n" +
            "last"
        val out = HostsFileMerger.merge(existing, entries)
        val lines = out.lines()
        assertEquals("first", lines[0])
        // "last" survives byte-for-byte and in its original relative order;
        // the fresh block is always appended after it (merge = strip, keep,
        // append), so the old block's tail becomes the new block's head.
        assertEquals("last", lines[2])
        assertEquals("", lines[3])
        assertFalse(out.contains("127.0.0.1 stale"))
        assertTrue(lines.indexOf("first") < lines.indexOf(HostsFileMerger.BEGIN_MARKER))
        assertTrue(lines.lastIndexOf(HostsFileMerger.END_MARKER) < lines.lastIndexOf("last") || lines.indexOf("last") < lines.indexOf(HostsFileMerger.BEGIN_MARKER))
    }

    @Test
    fun unpairedBeginMarkerStripsToEndOfFile() {
        // A truncated previous write must not resurrect stale entries.
        val existing = "keep me\n# BEGIN Linexa managed entries\n127.0.0.1 stale\n"
        val out = HostsFileMerger.merge(existing, entries)
        assertFalse(out.contains("stale"))
        assertTrue(out.contains("keep me"))
    }

    @Test
    fun blankAndMultilineEntriesAreDropped() {
        val out = HostsFileMerger.merge(null, listOf("  ", "", "10.0.0.5 valid", "bad\nentry"))
        val lines = out.lines()
        // Exactly the two markers plus the one surviving entry.
        assertEquals(3, lines.size)
        assertTrue(out.contains("10.0.0.5 valid"))
        assertFalse(out.contains("bad"))
        assertFalse(out.contains("entry"))
    }

    @Test
    fun emptyEntryListAppendsNoBlock() {
        val out = HostsFileMerger.merge("keep me", emptyList())
        assertEquals("keep me", out)
        assertFalse(out.contains(HostsFileMerger.BEGIN_MARKER))
    }

    @Test
    fun managedEntriesForLowercasesAndSanitizesHostname() {
        assertEquals(
            listOf("127.0.0.1 localhost", "::1 localhost", "127.0.0.1 debian-12"),
            HostsFileMerger.managedEntriesFor("Debian-12")
        )
    }

    @Test
    fun managedEntriesForStripsWhitespaceAndInvalidChars() {
        assertEquals(
            listOf("127.0.0.1 localhost", "::1 localhost", "127.0.0.1 myhost"),
            HostsFileMerger.managedEntriesFor("My Host!")
        )
    }

    @Test
    fun managedEntriesForFullyInvalidHostnameOmitsTheHostnameLine() {
        assertEquals(
            listOf("127.0.0.1 localhost", "::1 localhost"),
            HostsFileMerger.managedEntriesFor("!@#%")
        )
        assertEquals(
            listOf("127.0.0.1 localhost", "::1 localhost"),
            HostsFileMerger.managedEntriesFor("")
        )
    }

    @Test
    fun managedEntriesForStripsShellMetacharacters() {
        // The hostname lands in a root-owned config file and travels through
        // command arguments downstream: `$`, backtick and `;` must never
        // survive sanitization. The character filter keeps only hostname-legal
        // letters — here "a;b$c`d" sanitizes to "abcd" (same rule that yields
        // "myhost" from "My Host!" above) with every metacharacter removed.
        assertEquals(
            listOf("127.0.0.1 localhost", "::1 localhost", "127.0.0.1 abcd"),
            HostsFileMerger.managedEntriesFor("a;b\$c`d")
        )
    }

    @Test
    fun managedEntriesKeepFqdnDotsAndInteriorHyphens() {
        val last = HostsFileMerger.managedEntriesFor("Web.Box-01").last()
        assertEquals("127.0.0.1 web.box-01", last)
    }

    @Test
    fun markerConstantsAreStable() {
        // Persisted /etc/hosts files must keep parsing across versions:
        // these strings are a storage format, not a display label.
        assertEquals("# BEGIN Linexa managed entries", HostsFileMerger.BEGIN_MARKER)
        assertEquals("# END Linexa managed entries", HostsFileMerger.END_MARKER)
    }
}
