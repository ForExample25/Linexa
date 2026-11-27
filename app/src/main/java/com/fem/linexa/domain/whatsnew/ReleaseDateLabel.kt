package com.fem.linexa.domain.whatsnew

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formats a catalog release date for display.
 *
 * The catalog stores dates as ISO-8601 (`2026-11-27`) because that is unambiguous in
 * source review and sorts lexicographically. Users should see their locale's medium
 * date instead, so the conversion lives here as a pure function: the composable passes
 * the locale it is actually rendering in, and the JVM tests can cover malformed and
 * missing values without a Compose harness.
 *
 * A null, blank, or malformed value returns null — the caller omits the row rather than
 * rendering "Released null" or crashing on a bad catalog edit.
 */
object ReleaseDateLabel {

    fun format(isoDate: String?, locale: Locale = Locale.getDefault()): String? {
        val value = isoDate?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            LocalDate.parse(value)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
