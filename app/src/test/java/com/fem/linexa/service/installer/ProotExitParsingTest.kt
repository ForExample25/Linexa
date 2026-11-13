package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.67: tracee-exit parsing for the extraction failure label.
 *
 * The 2026-08-05 on-device report was labeled "Native extraction failed
 * (tar exit 70)", but the capture shows tar itself exited 2 ("Cannot
 * open: Operation not permitted" on the source archive, "Error is not
 * recoverable") and only the forked zstd decompressor child exited 70
 * (broken pipe after tar died). proot (5.3.0 src/tracee/event.c:424)
 * overwrites `last_exit_status` on every tracee exit and exits with the
 * LAST one's status, so the ProcessBuilder exit code must be reported as
 * proot's, with the parsed per-tracee breakdown.
 */
class ProotExitParsingTest {

    @Test
    fun reportedDeviceCaseParsesTarTwoThenZstdSeventy() {
        // Real tail from the 2026-08-05 report.
        val raw = """
            proot info: vpid 1: translate("/" + "/*stdin*\")
            proot info: vpid 1:          -> "/*stdin*\"
            tar: Error is not recoverable: exiting now
            proot info: vpid 1: exited with status 2
            zstd: error 70 : Write error : cannot write block : Broken pipe
            proot info: vpid 4: exited with status 70
            proot info: vpid 3: exited with status 70
            proot info: vpid 2: exited with status 70
        """.trimIndent()
        val exits = parseProotTraceeExits(raw)
        assertEquals(listOf(1 to 2, 4 to 70, 3 to 70, 2 to 70), exits)
        // Summary sorts by vpid for readability.
        assertEquals("vpid 1=2, vpid 2=70, vpid 3=70, vpid 4=70", prootExitSummary(exits))
    }

    @Test
    fun interleavedFusedLinesDoNotBreakParsing() {
        // tar's stderr and proot's verbose trace share the same fd 2, so a
        // line can be fused mid-message (…: Cannot openvpid 2: translate(…)…).
        // Standalone exit lines must still be found.
        val raw = """
            proot info: tar: /path/source-archive: Cannot openvpid 2: translate("/" + "/path/source-archive")
            : Operation not permitted
            tar: Error is not recoverable: exiting now
            proot info: vpid 1: exited with status 2
        """.trimIndent()
        assertEquals(listOf(1 to 2), parseProotTraceeExits(raw))
    }

    @Test
    fun tailWithoutTrailingNewlineStillParses() {
        // BoundedOutputTail can hand us a stream cut mid-line: the very last
        // exit line - exactly what the label needs - must still match.
        val raw = "…proot info: vpid 1: exited with status 2"
        assertEquals(listOf(1 to 2), parseProotTraceeExits(raw))
    }

    @Test
    fun emptyOrTraceOnlyCaptureParsesToEmpty() {
        assertTrue(parseProotTraceeExits("").isEmpty())
        assertTrue(parseProotTraceeExits("proot info: binding = /\nproot info: verbose level = 2\n").isEmpty())
        assertTrue(parseProotTraceeExits("proot info: vpid 1: terminated with signal 11").isEmpty())
        assertTrue(parseProotTraceeExits("proot info: vpid 1: exited with statusX 2").isEmpty())
    }

    @Test
    fun successfulRunParsesZeroExits() {
        val raw = """
            proot info: vpid 2: exited with status 0
            proot info: vpid 3: exited with status 0
        """.trimIndent()
        assertEquals(listOf(2 to 0, 3 to 0), parseProotTraceeExits(raw))
    }

    @Test
    fun summaryOnEmptyExitsIsEmptyString() {
        assertEquals("", prootExitSummary(emptyList()))
    }

    @Test
    fun overflowingVpidIsSkippedNotThrown() {
        // Roadmap Item 4: the regex freely matches digit runs; a corrupted /
        // oversized vpid must be skipped, not turned into NumberFormatException
        // that would mislabel the real failure as "Native extraction threw".
        val raw = "proot info: vpid 9999999999999999999999: exited with status 1\n" +
            "proot info: vpid 1: exited with status 2"
        assertEquals(listOf(1 to 2), parseProotTraceeExits(raw))
    }

    @Test
    fun overflowingStatusIsSkippedNotThrown() {
        val raw = "proot info: vpid 1: exited with status 9999999999999999999999\n" +
            "proot info: vpid 2: exited with status 70"
        assertEquals(listOf(2 to 70), parseProotTraceeExits(raw))
    }

    @Test
    fun adversarialJunkyTraceNeverThrows() {
        // Any junk a trace or a fused line can contain: out of place tokens,
        // signs, letters, empty groups. Whatever matches parses; the rest is
        // ignored — never an exception, never an aborted label.
        val raw = "vpid : exited with status\n" +
            "vpid 1: exited with status -3\n" +
            "vpid 2: exited with status +4\n" +
            "vpid a1: exited with status 5\n" +
            "proot info: vpid 03: exited with status 007"
        assertEquals("leading-zero numbers parse normally", listOf(3 to 7), parseProotTraceeExits(raw))
    }

    @Test
    fun overflowingInputFallsBackToPlainProotExitLabel() {
        // The full label path: an overflowing (unparsable) vpid must leave the
        // summary empty, so the failure label reads "(proot exit 70)" with no
        // tracee breakdown — never a throw.
        val exits = parseProotTraceeExits("proot info: vpid 9999999999999999999999: exited with status 70")
        assertTrue(exits.isEmpty())
        assertEquals("", prootExitSummary(exits))
    }
}