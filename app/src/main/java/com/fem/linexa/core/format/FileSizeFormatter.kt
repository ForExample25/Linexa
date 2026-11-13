package com.fem.linexa.core.format

import java.util.Locale

/**
 * Human-readable binary-unit byte formatter shared by every size label in
 * the app (home, workspace detail, file manager, diagnostics).
 *
 * The units are binary (KiB/MiB/GiB/TiB) to match the existing UI: integer
 * values for bytes/KB/MB, one decimal place for GB/TB. Formatting always goes
 * through [Locale.US] so the decimal separator never changes with the device
 * locale, and every floating-point argument is converted from [Long] to
 * [Double] before formatting to avoid `IllegalFormatConversionException`
 * (passing a `Long` to a `%f` specifier).
 */
object FileSizeFormatter {

    private val LOCALE = Locale.US

    private const val KIB = 1024L
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024
    private const val TIB = GIB * 1024

    fun format(bytes: Long): String {
        val value = if (bytes < 0) 0L else bytes
        return when {
            value < KIB -> "$value B"
            value < MIB -> "${value / KIB} KB"
            value < GIB -> "${value / MIB} MB"
            value < TIB -> "%.1f GB".format(LOCALE, value / GIB.toDouble())
            else -> "%.1f TB".format(LOCALE, value / TIB.toDouble())
        }
    }
}
