package com.fem.linexa.core.format

import java.util.Locale

/**
 * The one CPU-percentage text rule for every scope.
 *
 * Percentages are shares of total device capacity (0..100). Values at or above
 * one percent keep the app's existing whole-percent presentation; values below
 * one percent keep one decimal so a real 0.4% is not displayed as either `0%`
 * or `<1%`. A genuine measured zero is `0%` and must remain distinguishable
 * from the em dash used for unknown/pending (`—`).
 */
object CpuPercentFormatter {

    const val UNKNOWN = "—"

    fun format(value: Float?): String {
        if (value == null) return UNKNOWN
        val percent = value.coerceIn(0f, 100f)
        if (percent == 0f) return "0%"
        if (percent < 1f) {
            val decimal = String.format(Locale.US, "%.1f", percent)
            return if (decimal == "0.0") "<0.1%" else "$decimal%"
        }
        val whole = (percent + 0.5f).toInt().coerceIn(1, 100)
        return "$whole%"
    }
}
