package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1.88 JVM tests for the GNU tar `--checkpoint` parser that drives the
 * extraction progress bar. GNU tar's default checkpoint action echoes
 * `Read checkpoint N` (some builds prefix `tar: `) to stderr every 100
 * records of ~10240 bytes; [parseCheckpointRecords] extracts N.
 *
 * Strictly defensive, like the §1.67 proot-tracee parser: any line that
 * doesn't match yields null (indeterminate progress), never a throw.
 */
class CheckpointProgressTest {

    @Test
    fun plainReadCheckpointParses() {
        assertEquals(100L, parseCheckpointRecords("Read checkpoint 100"))
    }

    @Test
    fun tarPrefixedReadCheckpointParses() {
        assertEquals(5000L, parseCheckpointRecords("tar: Read checkpoint 5000"))
    }

    @Test
    fun writeCheckpointParses() {
        assertEquals(123L, parseCheckpointRecords("tar: Write checkpoint 123"))
    }

    @Test
    fun caseInsensitiveParses() {
        assertEquals(50L, parseCheckpointRecords("TAR: read CHECKPOINT 50"))
    }

    @Test
    fun traceLinesAreIgnored() {
        assertNull(parseCheckpointRecords("translate(\"/\" + \"bin/usr\")"))
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(parseCheckpointRecords("tar: Removing leading `/' from member names"))
    }

    @Test
    fun nearMissWordBoundaryIsIgnored() {
        // "checkpointing" must not match "checkpoint" — the regex requires
        // whitespace before the number.
        assertNull(parseCheckpointRecords("tar: Read checkpointing 100"))
    }

    @Test
    fun nonNumericCountIsIgnored() {
        assertNull(parseCheckpointRecords("Read checkpoint N"))
    }
}
