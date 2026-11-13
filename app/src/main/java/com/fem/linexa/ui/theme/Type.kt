package com.fem.linexa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Linexa typography — a strong typographic hierarchy anchored on the system
 * sans-serif family for the chrome and `FontFamily.Monospace` for every
 * technical surface (terminal, diagnostics, logs, PID/CPU%/RAM values,
 * package output, command trees).
 *
 * The spec calls for "Google Sans or Inter" typography. Neither ships with the
 * Android platform, so this project uses the system default font family to keep
 * it build-ready with zero setup. To switch to Inter, drop Inter's .ttf files
 * into res/font/ and swap [AppFontFamily] for the commented FontFamily(...) block.
 *
 * The Linexa type system adds four styles outside Material 3's standard slots —
 * the four `Stat*` styles at the bottom of this file. They exist because
 * performance metrics (CPU%, RAM GB, Disk GB, Net Mbps) are the app's most
 * re-read content, and rendering them in body text loses the visual weight
 * that makes a glance-readable command center. The four Stat styles give
 * numeric content its own rhythm: large display digits for the headline
 * metric on Home, medium digits for resource tiles, monospace digits for
 * aligned tabular output, and a small variant for inline values.
 */
val AppFontFamily = FontFamily.Default

/**
 * Monospace family for technical surfaces (terminal, diagnostics, logs,
 * package/outputs). Use it via [asMono] so code text stays anchored to the
 * standard type scale instead of ad-hoc `fontFamily = FontFamily.Monospace` +
 * manual font sizes.
 */
val CodeFontFamily = FontFamily.Monospace

/**
 * Rebase a scale style (e.g. `MaterialTheme.typography.bodySmall`) onto the
 * monospace code family. Code text should go through this so it keeps the
 * app's type rhythm instead of defining its own sizes.
 */
fun TextStyle.asMono(): TextStyle = copy(fontFamily = CodeFontFamily)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp
    )
)

/**
 * Stat / numeric typography — Linexa's four non-M3 type styles for the most
 * re-read content in the app: performance metrics.
 *
 * Why these are not M3 slots: M3's standard slots are sized for prose, not
 * for the dense, tabular numeric output of a system monitor. Rendering
 * `23%` CPU in `headlineLarge` looks like a chapter title. Rendering it in
 * a `Stat` style says "this is a metric" — fixed-width digits, a hairline
 * weight, and a tabular figure posture.
 *
 * Usage:
 *   - `StatHero`  : the headline metric on Home (e.g. the big CPU% number)
 *   - `StatTile`  : the metric value on a resource tile (CPU/RAM/Disk/Net)
 *   - `StatMono`  : aligned tabular output (PID list, process table)
 *   - `StatInline`: a small numeric inline value (uptime, "1.4 MB/s")
 */
object StatTypography {
    val StatHero = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    )

    val StatTile = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.25).sp
    )

    val StatMono = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    val StatInline = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
}
