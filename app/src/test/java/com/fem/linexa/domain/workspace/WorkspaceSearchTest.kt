package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchTest {

    private fun rank(name: String, query: String, vararg extras: String) =
        WorkspaceSearch.rank(WorkspaceSearchEntry(name, extras.toList()), WorkspaceSearch.tokens(query))

    // ── normalisation ──────────────────────────────────────────────────────

    @Test
    fun normalizeIgnoresCaseAccentsAndWidth() {
        assertEquals("cafe", WorkspaceSearch.normalize("Café"))
        assertEquals("deb", WorkspaceSearch.normalize("ＤＥＢ"))
    }

    @Test
    fun arabicYehAndAlefMaksuraFoldToPersianYeh() {
        val persian = "علی" // ی U+06CC
        assertEquals(persian, WorkspaceSearch.normalize("علي")) // ي U+064A
        assertEquals(persian, WorkspaceSearch.normalize("على")) // ى U+0649
    }

    @Test
    fun arabicKafFoldsToPersianKeheh() {
        assertEquals("کتاب", WorkspaceSearch.normalize("كتاب")) // ك U+0643 -> ک U+06A9
    }

    @Test
    fun zeroWidthNonJoinerAndJoinerAreIgnored() {
        assertEquals("میخواهم", WorkspaceSearch.normalize("می\u200Cخواهم"))
        assertEquals(WorkspaceSearch.normalize("میخواهم"), WorkspaceSearch.normalize("می\u200Cخواهم"))
        assertEquals("ab", WorkspaceSearch.normalize("a\u200Db"))
    }

    @Test
    fun arabicIndicAndPersianDigitsBecomeAscii() {
        assertEquals("0123456789", WorkspaceSearch.normalize("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", WorkspaceSearch.normalize("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("dev 12", WorkspaceSearch.normalize("Dev ۱۲"))
    }

    @Test
    fun harakatTatweelAndHamzaMarksAreStripped() {
        assertEquals("محمد", WorkspaceSearch.normalize("مُحَمَّد"))
        assertEquals("سلام", WorkspaceSearch.normalize("ســلام"))
        assertEquals("ان", WorkspaceSearch.normalize("أن"))
    }

    @Test
    fun whitespaceIsCollapsedAndTrimmedIncludingNbsp() {
        assertEquals("my dev box", WorkspaceSearch.normalize("  My \u00A0 Dev\t\nBox  "))
        assertEquals("", WorkspaceSearch.normalize(" \u00A0 "))
    }

    // ── tokens ─────────────────────────────────────────────────────────────

    @Test
    fun aBlankQueryHasNoTokens() {
        assertEquals(emptyList<String>(), WorkspaceSearch.tokens(""))
        assertEquals(emptyList<String>(), WorkspaceSearch.tokens("  \u200C "))
    }

    @Test
    fun aQuerySplitsIntoNormalisedWords() {
        assertEquals(listOf("debian", "12"), WorkspaceSearch.tokens("  Debian   ۱۲ "))
    }

    // ── tiers ──────────────────────────────────────────────────────────────

    @Test
    fun theFourTiersRankInTheDocumentedOrder() {
        val prefix = rank("Devbox", "dev")!!
        val wordPrefix = rank("My dev box", "dev")!!
        val substring = rank("Prodevil", "dev")!!
        val extras = rank("Workspace", "dev", "Debian dev tools")!!
        assertEquals(WorkspaceSearch.TIER_NAME_PREFIX, prefix.worst)
        assertEquals(WorkspaceSearch.TIER_NAME_WORD_PREFIX, wordPrefix.worst)
        assertEquals(WorkspaceSearch.TIER_NAME_SUBSTRING, substring.worst)
        assertEquals(WorkspaceSearch.TIER_EXTRAS, extras.worst)
        assertTrue(prefix < wordPrefix && wordPrefix < substring && substring < extras)
    }

    @Test
    fun anEntryThatMatchesNowhereIsExcluded() {
        assertNull(rank("Alpha", "zzz", "Debian 13"))
    }

    @Test
    fun theNameOutranksTheExtrasEvenWhenBothContainTheWord() {
        assertEquals(WorkspaceSearch.TIER_NAME_PREFIX, rank("Debian lab", "debian", "Debian 13")!!.worst)
    }

    @Test
    fun aBlankQueryMatchesEverythingEqually() {
        assertEquals(WorkspaceSearchRank(0, 0), rank("Alpha", ""))
        assertEquals(WorkspaceSearchRank(0, 0), rank("Beta", "   ", "x"))
    }

    @Test
    fun everyQueryWordMustMatchAndTheWeakestWordSetsTheTier() {
        // "debian" lands in the extras (tier 3), "12" is a word prefix of the name (tier 1).
        val both = rank("Lab 12", "debian 12", "Debian 13")!!
        assertEquals(3, both.worst)
        assertEquals(4, both.total)
        assertNull(rank("Lab 12", "debian 99", "Debian 13"))
    }

    @Test
    fun entriesTyingOnTheWeakestTierAreOrderedByTheirTotal() {
        val tight = rank("Debian 12", "debian 12")!!      // tiers 0 + 1
        val loose = rank("Box", "debian 12", "Debian 12")!! // tiers 3 + 3
        assertTrue(tight < loose)
    }

    // ── Persian ────────────────────────────────────────────────────────────

    @Test
    fun anArabicKafQueryFindsAPersianKehehName() {
        assertEquals(WorkspaceSearch.TIER_NAME_PREFIX, rank("کتابخانه", "كتاب")!!.worst)
    }

    @Test
    fun aPersianKehehQueryFindsAnArabicKafName() {
        assertEquals(WorkspaceSearch.TIER_NAME_PREFIX, rank("كتابخانه", "کتاب")!!.worst)
    }

    @Test
    fun anArabicYehQueryFindsAPersianYehName() {
        assertNotNull(rank("علی دسکتاپ", "علي"))
        assertNotNull(rank("علي دسکتاپ", "علی"))
    }

    @Test
    fun aQueryWithoutZwnjFindsANameWithIt() {
        assertNotNull(rank("می\u200Cخواهم لینوکس", "میخواهم"))
        assertNotNull(rank("میخواهم لینوکس", "می\u200Cخواهم"))
    }

    @Test
    fun persianDigitsMatchAsciiDigitsInNamesAndExtras() {
        assertNotNull(rank("Dev ۱۲", "12"))
        assertNotNull(rank("Dev 12", "۱۲"))
        assertNotNull(rank("Lab", "13", "Debian ۱۳"))
    }

    @Test
    fun aSecondWordInANameMatchesAsAWordPrefix() {
        assertEquals(WorkspaceSearch.TIER_NAME_WORD_PREFIX, rank("دسکتاپ کاری", "کار")!!.worst)
    }
}
