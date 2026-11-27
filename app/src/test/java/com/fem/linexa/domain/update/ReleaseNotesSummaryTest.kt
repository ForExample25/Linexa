package com.fem.linexa.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The summary-line extraction against real-shaped GitHub markdown: headings,
 * horizontal rules, bilingual navigation lines, bold/link markup.
 */
class ReleaseNotesSummaryTest {

    @Test
    fun nullAndBlankNotesProduceNoSummary() {
        assertNull(ReleaseNotesSummary.short(null))
        assertNull(ReleaseNotesSummary.short(""))
        assertNull(ReleaseNotesSummary.short("   \n  \n"))
    }

    @Test
    fun theFirstProseLineWinsOverHeadingsAndRules() {
        val notes = """
            ## What's new
            ---
            The first public preview build of Linexa, packaged as a single universal APK.
        """.trimIndent()
        assertEquals(
            "The first public preview build of Linexa, packaged as a single universal APK.",
            ReleaseNotesSummary.short(notes)
        )
    }

    @Test
    fun boldAndInlineLinkMarkupIsStripped() {
        val notes = "**First public preview build** of [Linexa](https://github.com/ForExample25/Linexa), a terminal-first Linux environment for Android."
        assertEquals(
            "First public preview build of Linexa, a terminal-first Linux environment for Android.",
            ReleaseNotesSummary.short(notes)
        )
    }

    @Test
    fun shortNavigationLinesAreSkipped() {
        val notes = """
            **English** · [فارسی](#فارسی)
            ## APK
            The universal APK installs on arm64-v8a and armeabi-v7a devices alike.
        """.trimIndent()
        assertEquals(
            "The universal APK installs on arm64-v8a and armeabi-v7a devices alike.",
            ReleaseNotesSummary.short(notes)
        )
    }

    @Test
    fun notesWithNoProseLineProduceNoSummary() {
        val notes = """
            ## Changelog
            ---
            **APK**
        """.trimIndent()
        assertNull(ReleaseNotesSummary.short(notes))
    }
}
