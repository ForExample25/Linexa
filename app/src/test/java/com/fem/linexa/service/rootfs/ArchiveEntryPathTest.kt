package com.fem.linexa.service.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.146 — the packing-convention bug, pinned.
 *
 * Every case below is drawn from a real archive: Debian/Ubuntu pack bare,
 * Alpine and Arch Linux ARM pack with `./`. Detection compared raw names
 * against bare literals, so the second group matched nothing and reported
 * "could not be detected automatically" — Debian working was luck, not design.
 */
class ArchiveEntryPathTest {

    @Test
    fun bothPackingConventionsNormalizeToTheSamePath() {
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("etc/os-release"))
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("./etc/os-release"))
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("/etc/os-release"))
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("././etc/os-release"))
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("etc//os-release"))
        assertEquals("etc/os-release", ArchiveEntryPath.normalize("./etc/./os-release"))
    }

    @Test
    fun directoryAndFileEntriesForOnePathCompareEqual() {
        assertEquals(ArchiveEntryPath.normalize("./etc/"), ArchiveEntryPath.normalize("etc"))
        assertEquals("etc", ArchiveEntryPath.normalize("./etc/"))
    }

    @Test
    fun theArchiveRootNormalizesToEmptyAndMatchesNothing() {
        assertEquals("", ArchiveEntryPath.normalize("."))
        assertEquals("", ArchiveEntryPath.normalize("./"))
        assertEquals("", ArchiveEntryPath.normalize("/"))
        assertEquals("", ArchiveEntryPath.normalize(""))
    }

    @Test
    fun traversalSegmentsArePreservedSoTheValidatorStillSeesThem() {
        // This is the security-relevant assertion: collapsing `..` here would
        // make `./../etc/passwd` look tamer than it is. ArchiveValidator runs on
        // the RAW name and is the only authority; this normalizer must never
        // launder anything for it.
        assertEquals("../etc/passwd", ArchiveEntryPath.normalize("./../etc/passwd"))
        assertEquals("a/../../b", ArchiveEntryPath.normalize("./a/../../b"))
        assertTrue(ArchiveEntryPath.normalize("../../root").startsWith(".."))
    }

    @Test
    fun matchNormalizationFoldsCaseWithoutALocale() {
        assertEquals("etc/os-release", ArchiveEntryPath.normalizeForMatch("./ETC/OS-Release"))
        // The Turkish dotless-i: a locale-sensitive fold would make detection
        // depend on the user's phone language.
        assertEquals("bin/i", ArchiveEntryPath.normalizeForMatch("./BIN/I"))
    }

    @Test
    fun normalizePreservesCaseSoLogsAndMetadataMatchingSeeTheRealName() {
        assertEquals("etc/OS-Release", ArchiveEntryPath.normalize("./etc/OS-Release"))
    }

    @Test
    fun rootLevelDetectionWorksForBothConventions() {
        assertTrue(ArchiveEntryPath.isRootLevel(".linexa.yaml"))
        assertTrue(ArchiveEntryPath.isRootLevel("./.linexa.yaml"))
        assertTrue(ArchiveEntryPath.isRootLevel("/config.yaml"))
        assertFalse(ArchiveEntryPath.isRootLevel("etc/.linexa.yaml"))
        assertFalse(ArchiveEntryPath.isRootLevel("./etc/config.yaml"))
        assertFalse(ArchiveEntryPath.isRootLevel("./"))
    }
}
