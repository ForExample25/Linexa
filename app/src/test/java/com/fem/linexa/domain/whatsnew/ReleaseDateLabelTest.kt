package com.fem.linexa.domain.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The catalog's ISO date → the user's locale. Missing and malformed values are
 * omitted, never rendered as a placeholder or allowed to crash the card.
 */
class ReleaseDateLabelTest {

    @Test
    fun anIsoDateIsFormattedForTheGivenLocale() {
        val us = ReleaseDateLabel.format("2026-11-27", Locale.US)
        assertNotNull(us)
        assertEquals("Nov 27, 2026", us)
    }

    @Test
    fun theSameInstantUsesTheLocalesOwnOrder() {
        val uk = ReleaseDateLabel.format("2026-11-27", Locale.UK)
        assertNotNull(uk)
        // Medium style in en-GB is day-month-year, unlike en-US.
        assertEquals("27 Nov 2026", uk)
    }

    @Test
    fun aMissingDateIsAbsent() {
        assertNull(ReleaseDateLabel.format(null))
        assertNull(ReleaseDateLabel.format(""))
        assertNull(ReleaseDateLabel.format("   "))
    }

    @Test
    fun aMalformedDateIsAbsentRatherThanACrash() {
        assertNull(ReleaseDateLabel.format("27/11/2026"))
        assertNull(ReleaseDateLabel.format("2026-13-40"))
        assertNull(ReleaseDateLabel.format("not-a-date"))
    }
}
