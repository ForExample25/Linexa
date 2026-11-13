package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-09-02 ("Now processing" fix) — JVM tests for [parseTarVerboseEntry],
 * the parser that turns GNU tar's `-v` stdout lines into the extracted
 * member name reported as `InstallProgress.currentEntryName`.
 *
 * Mirrors [CheckpointProgressTest]'s rules: every input must either parse to
 * a name or yield null — a parser throw would take down the extraction drain
 * thread, so the defensive contract is pinned here line by line.
 */
class TarVerboseEntryParsingTest {

    // ── valid tar -v output ─────────────────────────────────────────────

    @Test
    fun plainMemberNameParses() {
        assertEquals("./usr/bin/ls", parseTarVerboseEntry("./usr/bin/ls"))
    }

    @Test
    fun memberWithoutDotSlashParses() {
        assertEquals("etc/passwd", parseTarVerboseEntry("etc/passwd"))
    }

    @Test
    fun rootDirectoryEntryParses() {
        assertEquals("./", parseTarVerboseEntry("./"))
    }

    @Test
    fun trailingCarriageReturnIsStripped() {
        assertEquals("./bin/sh", parseTarVerboseEntry("./bin/sh\r"))
    }

    @Test
    fun trailingWhitespaceIsStripped() {
        assertEquals("./usr/share/doc", parseTarVerboseEntry("./usr/share/doc  "))
    }

    // ── unusual (but genuine) file names ────────────────────────────────

    @Test
    fun memberNameWithSpacesParses() {
        // GNU tar echoes the name verbatim; a space is part of the name.
        assertEquals("./usr/share/my file.txt", parseTarVerboseEntry("./usr/share/my file.txt"))
    }

    @Test
    fun memberNameWithUnicodeParses() {
        assertEquals("./home/üñíçødé", parseTarVerboseEntry("./home/üñíçødé"))
    }

    @Test
    fun memberNameWithQuotesParses() {
        // A file literally named with a quote character is a valid member;
        // the parser is an echo of tar's stdout, not a quote detector.
        assertEquals("./weird\"name", parseTarVerboseEntry("./weird\"name"))
    }

    // ── irrelevant proot / merged-stream artifacts ──────────────────────

    @Test
    fun prootTranslateLinesAreIgnored() {
        assertNull(parseTarVerboseEntry("translate(\"/\" + \"bin/usr\")"))
        assertNull(parseTarVerboseEntry("translate(\"/data\" + \"x\") = \"/data/x\""))
    }

    @Test
    fun prootTraceeExitLinesAreIgnored() {
        assertNull(parseTarVerboseEntry("vpid 1: exited with status 0"))
    }

    @Test
    fun checkpointLinesAreIgnoredEvenIfMerged() {
        assertNull(parseTarVerboseEntry("Read checkpoint 100"))
        assertNull(parseTarVerboseEntry("tar: Write checkpoint 5000"))
    }

    @Test
    fun tarDiagnosticsAreIgnored() {
        assertNull(parseTarVerboseEntry("tar: Removing leading `/' from member names"))
        assertNull(parseTarVerboseEntry("tar: etc/passwd: Cannot open: No such file or directory"))
    }

    @Test
    fun tarSummaryArtifactsAreIgnored() {
        assertNull(parseTarVerboseEntry("1234/5678 files"))
        assertNull(parseTarVerboseEntry("total size 10240"))
        assertNull(parseTarVerboseEntry("block 5"))
    }

    // ── empty / blank / malformed ────────────────────────────────────────

    @Test
    fun emptyLineIsIgnored() {
        assertNull(parseTarVerboseEntry(""))
    }

    @Test
    fun blankLineIsIgnored() {
        assertNull(parseTarVerboseEntry("   "))
        assertNull(parseTarVerboseEntry("\t"))
    }

    @Test
    fun controlCharactersAreRejected() {
        assertNull(parseTarVerboseEntry("./bin\u0000/sh"))
        assertNull(parseTarVerboseEntry("./bin\tsh"))
    }

    @Test
    fun arbitraryNonBlankLinesAreEchoedVerbatim() {
        // stdout is dedicated to tar -v, so any non-blank, control-free line
        // IS a member name — even one that reads like prose (a legal, if
        // absurd, file name). The parser is an echo, not a path validator;
        // only the documented artifact shapes above are refused.
        assertEquals(
            "this is not a path: it has a colon prefix",
            parseTarVerboseEntry("this is not a path: it has a colon prefix")
        )
    }

    // ── mixed output (stream merge resilience) ───────────────────────────

    @Test
    fun mixedLinesParseIndependently() {
        val lines = listOf(
            "Read checkpoint 100" to null,
            "./usr/bin" to "./usr/bin",
            "translate(\"/\" + \"x\")" to null,
            "./etc/motd" to "./etc/motd",
            "" to null
        )
        lines.forEach { (line, expected) ->
            assertEquals("line: '$line'", expected, parseTarVerboseEntry(line))
        }
    }
}
