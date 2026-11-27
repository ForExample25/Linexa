package com.fem.linexa.ui.theme.haven

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.domain.settings.ColorTheme

/**
 * HAVEN design tokens — the single source of truth for the redesigned Linexa
 * UI (concept-2-haven.html).
 *
 * Haven is "minimal, spacious, premium": tone-step surfaces instead of
 * borders (bg → surface → surface2), one soft-green primary per screen,
 * monospaced data at readable sizes, hairline separators, and radii of
 * 20 (cards) / 14 (buttons & fields) / 24 (sheets & dialogs) / 999 (pills).
 *
 * The token chain is deliberately data-shaped:
 *
 *   Theme definition (this file today, an external `theme.json` later)
 *     → [HavenColors] / [HavenDim] / [HavenType] tokens
 *       → Haven components (ui/components/haven)
 *         → Screens
 *
 * Nothing below the token layer may hard-code a color, radius, or text
 * style — that is what makes a future external theme format a drop-in:
 * a parser only has to produce a [HavenColors] instance.
 *
 * ## Dark palette (the shipped default)
 * bg #101113 · surface #17181B · surface2 #1E2024 · border 6% white
 * text #F2F3F5 · text2 #A8ADB4 · muted #6C7178
 * primary #2AD178 · tint 12% primary · red #F87171 · amber #FBBF24 · blue #5AB8F0
 * terminal #0A0E0F (always dark, in both themes — see the concept's `.light`
 * block which deliberately does not override `--term`)
 *
 * ## Light palette (a first-class theme, not an inversion)
 * bg #F7F7F5 · surface #FFFFFF · surface2 #F0EFEC · border 7% black
 * text #1A1C1E · text2 #5C6167 · muted #9AA0A6
 * primary #0E9F5D (deepened for daylight contrast)
 */
@Immutable
data class HavenColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val muted: Color,
    val primary: Color,
    val tint: Color,
    val onPrimary: Color,
    val red: Color,
    val redTint: Color,
    val amber: Color,
    val amberTint: Color,
    val blue: Color,
    val blueTint: Color,
    val hover: Color,
    /** Terminal surface — always the near-black #0A0E0F in both themes. */
    val term: Color,
    val termText: Color,
    val termDim: Color,
    val termPrompt: Color,
    /** Translucent terminal top bar + extra-keys surfaces. */
    val termBar: Color,
    val keyBg: Color,
    val keyFg: Color,
    val toast: Color,
    val onToast: Color,
    val isDark: Boolean
) {
    companion object {
        /** The concept's default dark accent (Alpine Blue key): green. */
        val defaultDarkAccent = Color(0xFF2AD178)
        /** The concept's default light accent: deepened green. */
        val defaultLightAccent = Color(0xFF0E9F5D)

        /**
         * Accent swap for the persisted [ColorTheme]. Keys are the historical
         * SharedPreferences values; the accents follow the concept's
         * Settings → Appearance swatch rows (Alpine Blue default green,
         * Lavender #B9A7F5/#8B7BD8, Forest green, Sunset ember).
         */
        fun accentFor(theme: ColorTheme, dark: Boolean): Color = when (theme) {
            ColorTheme.ALPINE_BLUE -> if (dark) defaultDarkAccent else defaultLightAccent
            ColorTheme.LAVENDER -> if (dark) Color(0xFFB9A7F5) else Color(0xFF8B7BD8)
            ColorTheme.FOREST -> if (dark) Color(0xFF34A853) else Color(0xFF0E9F5D)
            ColorTheme.SUNSET -> if (dark) Color(0xFFF28B54) else Color(0xFFE5533C)
        }

        fun darkPalette(primary: Color = defaultDarkAccent): HavenColors = HavenColors(
            bg = Color(0xFF101113),
            surface = Color(0xFF17181B),
            surface2 = Color(0xFF1E2024),
            border = Color.White.copy(alpha = 0.06f),
            text = Color(0xFFF2F3F5),
            text2 = Color(0xFFA8ADB4),
            muted = Color(0xFF6C7178),
            primary = primary,
            tint = primary.copy(alpha = 0.12f),
            onPrimary = Color(0xFF07130C),
            red = Color(0xFFF87171),
            redTint = Color(0xFFF87171).copy(alpha = 0.12f),
            amber = Color(0xFFFBBF24),
            amberTint = Color(0xFFFBBF24).copy(alpha = 0.12f),
            blue = Color(0xFF5AB8F0),
            blueTint = Color(0xFF5AB8F0).copy(alpha = 0.12f),
            hover = Color.White.copy(alpha = 0.03f),
            term = Color(0xFF0A0E0F),
            termText = Color(0xFFC9CED4),
            termDim = Color(0xFF697077),
            termPrompt = Color(0xFF7EE0A8),
            termBar = Color(0xFF0A0E0F).copy(alpha = 0.86f),
            // 2026-08-29 (appearance fix, Issue 1): the shortcut bar reads as
            // part of the terminal — near-black surface + light keys in the
            // dark theme. These two tokens are used ONLY by the shortcut bar.
            keyBg = Color(0xFF0A0E0F),
            keyFg = Color(0xFFD6DAE0),
            toast = Color(0xFF26282D),
            onToast = Color(0xFFF2F3F5),
            isDark = true
        )

        fun lightPalette(primary: Color = defaultLightAccent): HavenColors = HavenColors(
            bg = Color(0xFFF7F7F5),
            surface = Color.White,
            surface2 = Color(0xFFF0EFEC),
            border = Color.Black.copy(alpha = 0.07f),
            text = Color(0xFF1A1C1E),
            text2 = Color(0xFF5C6167),
            muted = Color(0xFF9AA0A6),
            primary = primary,
            tint = primary.copy(alpha = 0.10f),
            onPrimary = Color.White,
            red = Color(0xFFD93025),
            redTint = Color(0xFFD93025).copy(alpha = 0.09f),
            amber = Color(0xFFB45309),
            amberTint = Color(0xFFB45309).copy(alpha = 0.10f),
            blue = Color(0xFF3873B3),
            blueTint = Color(0xFF3873B3).copy(alpha = 0.10f),
            hover = Color.Black.copy(alpha = 0.03f),
            term = Color(0xFF0A0E0F),
            termText = Color(0xFFC9CED4),
            termDim = Color(0xFF697077),
            termPrompt = Color(0xFF7EE0A8),
            termBar = Color(0xFF0A0E0F).copy(alpha = 0.86f),
            // Light theme: the shortcut surface goes LIGHT with dark keys
            // (appearance fix, Issue 1 — was a dark bar in both themes).
            keyBg = Color(0xFFE7E9EC),
            keyFg = Color(0xFF2B3036),
            toast = Color(0xFF26282D),
            onToast = Color(0xFFF2F3F5),
            isDark = false
        )
    }

    /** Secondary convenience used by sparkline/status colors. */
    val success: Color get() = primary
}

val LocalHavenColors = staticCompositionLocalOf { HavenColors.darkPalette() }

/**
 * Haven spacing & sizing. The concept runs a strict rhythm: 20px screen
 * padding, 24px header padding, 12px card gaps, hairline 1px separators.
 */
object HavenDim {
    // ── Radii ──
    val rCard = 20.dp
    val rTile = 20.dp
    val rBtn = 14.dp
    val rField = 14.dp
    val rSheet = 24.dp
    val rDialog = 24.dp
    val rMenu = 16.dp
    val rRic = 12.dp
    val rRicSm = 10.dp
    val rBadge = 9.dp
    val rGlyph = 13.dp
    val rGlyphLg = 15.dp
    val rGlyphSm = 10.dp
    val rGlyphXs = 8.dp
    val rEmptyIcon = 28.dp
    val rMonoBlock = 14.dp
    val rPreview = 12.dp
    val rNav = 14.dp
    val rSidebarRow = 12.dp

    // ── Screen rhythm ──
    val screenH = 20.dp
    val hdrH = 24.dp
    val hdrV = 8.dp
    val cardPad = 20.dp
    val cardGap = 12.dp
    val sectionTop = 26.dp
    val hairline = 1.dp
    /** Gap between adjacent header action circles (Home, Workspace, every page header). */
    val headerActionGap = 10.dp
    /** Inner horizontal padding of a row inside a `tight` card (equals [cardPad]). */
    val cardRowH = 20.dp
    /** Vertical padding of a single-line status row inside a `tight` card. */
    val cardRowV = 14.dp
    /** Vertical padding of a compact settings row inside a `tight` card. */
    val cardRowCompactV = 12.dp
    /** Vertical padding of a block that carries actions (progress, buttons). */
    val cardBlockV = 16.dp

    // ── Controls ──
    val hbtn = 40.dp
    val hbtnIcon = 20.dp
    val ibtn = 34.dp
    val ibtnSm = 28.dp
    // Touch-target floor for the regular `.ibtn` (visual stays 34dp; only the
    // tappable area grows to the project's 40dp compact minimum).
    val ibtnHit = 40.dp
    val ric = 38.dp
    val ricSm = 32.dp
    val ricIcon = 19.dp
    val rowMin = 62.dp
    val rowMinSm = 52.dp
    val pillH = 26.dp
    val chipH = 32.dp
    val chipCatH = 22.dp
    val segH = 42.dp
    val segMiniH = 34.dp
    val toggleW = 46.dp
    val toggleH = 28.dp
    val fieldH = 50.dp
    val searchH = 46.dp
    val stepBtn = 36.dp
    val btnH = 48.dp
    val btnSmH = 40.dp

    // ── Terminal ──
    val tbarH = 46.dp
    val xkMinW = 38.dp
    val xkH = 34.dp

    /**
     * 2026-08-29 (UX optimization, Issues 4-6): the shortcut-bar key height —
     * larger than the old 34dp pill keys so the row is a comfortable
     * one-handed touch surface.
     */
    val xkBarH = 42.dp

    // ── Glyphs (distro identity tiles) ──
    val glyphXs = 28.dp
    val glyphSm = 36.dp
    val glyph = 44.dp
    val glyphLg = 52.dp

    // ── Sidebar (tablet) ──
    val sidebarW = 238.dp

    // ── Bars ──
    val storageBarH = 3.dp
    val progressH = 6.dp
    val progressBigH = 8.dp
    val coreBarH = 4.dp

    // ── Bottom nav ──
    val bottomNavH = 64.dp
}

internal fun em(value: Float): TextUnit = TextUnit(value, TextUnitType.Em)

/**
 * Haven typography. Hierarchy is carried by size + weight, not color:
 * 28/26/22 semibold heads, 15px base, 13px descriptions, 10-11px uppercase
 * eyebrows, and monospaced numerals for data. Colors are applied by callers
 * via `TextStyle.copy(color = …)` so styles stay theme-free data.
 */
object HavenType {
    val mono = FontFamily.Monospace
    val sans = FontFamily.Default

    // ── Prose styles ──
    // Declared once, in Latin metrics, in [HavenLatinType] below; each getter
    // hands out the script-calibrated style (see [HavenScript]). Call sites
    // are unchanged: `HavenType.body` still reads like a constant, and reading
    // it inside a composition subscribes to the script flag, so a language
    // switch recalibrates every prose style without per-screen edits.
    /** `.h1` — 28sp semibold, tight tracking. */
    val h1: TextStyle get() = HavenLatinType.h1.forScript()
    /** `.h2` — 26sp semibold. */
    val h2: TextStyle get() = HavenLatinType.h2.forScript()
    /** Workspace / screen titles — 22sp semibold. */
    val h3: TextStyle get() = HavenLatinType.h3.forScript()
    /** Card/section titles — 17sp semibold. */
    val title: TextStyle get() = HavenLatinType.title.forScript()
    /** Hero value inside the Activity card — 18sp semibold. */
    val titleLg: TextStyle get() = HavenLatinType.titleLg.forScript()
    /** List row titles — 15sp semibold. */
    val rowTitle: TextStyle get() = HavenLatinType.rowTitle.forScript()
    /** Row body — 15sp regular. */
    val body: TextStyle get() = HavenLatinType.body.forScript()
    /** Row descriptions — 13sp. */
    val desc: TextStyle get() = HavenLatinType.desc.forScript()
    /** Small labels — 12sp. */
    val small: TextStyle get() = HavenLatinType.small.forScript()
    /** Section headers — 12.5sp semibold, uppercase, letter-spaced. */
    val section: TextStyle get() = HavenLatinType.section.forScript()
    /** Eyebrow — 10sp bold uppercase, wide tracking. */
    val eyebrow: TextStyle get() = HavenLatinType.eyebrow.forScript()
    /** Sidebar brand — 13sp bold uppercase. */
    val brand: TextStyle get() = HavenLatinType.brand.forScript()
    /** Bottom-nav labels — 10sp semibold. */
    val navLabel: TextStyle get() = HavenLatinType.navLabel.forScript()
    /** Tile label — 10.5sp bold uppercase. */
    val tileLabel: TextStyle get() = HavenLatinType.tileLabel.forScript()

    /**
     * Letter-spacing for a tracked label (`em` of the font size). Latin/uppercase
     * labels keep the designed tracking; Arabic-script text (Persian) gets none,
     * because added tracking pulls apart letters that must stay joined.
     */
    fun tracking(value: Float): TextUnit =
        if (HavenScript.isArabicScript) 0.sp else em(value)


    /** Mono description (paths, metadata) — 12sp. */
    val descMono = TextStyle(
        fontFamily = mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        // Paths, commands and IDs are technical content and stay LTR in every
        // language, including Persian. The surrounding app chrome is also LTR
        // (a global policy), so Start is simply the left edge; pinning the
        // direction keeps mixed-script identifiers in logical order.
        textDirection = TextDirection.Ltr,
        textAlign = TextAlign.Start
    )

    // ── Numeric / data ──
    // Every numeric/mono style below pins LTR: measurements, IDs and log
    // lines are technical content and read in logical order in every
    // language. Body/heading styles intentionally do NOT pin a direction, so
    // Persian prose keeps its native RTL bidi rendering inside the LTR chrome.
    /** `.pn .pv` — 26sp mono semibold (pane numbers). */
    val num26 = TextStyle(fontFamily = mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.02f), textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** `.tile .tv` — 22sp mono semibold. */
    val num22 = TextStyle(fontFamily = mono, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.01f), textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** Performance hero — 44sp mono. */
    val num44 = TextStyle(fontFamily = mono, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.03f), textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** Medium mono value — 14sp. */
    val num14 = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Medium, textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** Inline mono — 12.5sp. */
    val mono12 = TextStyle(fontFamily = mono, fontSize = 12.5.sp, fontWeight = FontWeight.Normal, textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** Terminal body. */
    val term = TextStyle(fontFamily = mono, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
    /** Mono block (logs, traces). */
    val monoBlock = TextStyle(fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp, textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)
}

/**
 * Latin-metric prose styles — the design values of [HavenType]'s prose scale.
 * Never read directly by UI code; go through [HavenType], which applies the
 * Arabic-script calibration. Prose styles deliberately leave `textDirection`
 * unspecified so Persian keeps native bidi rendering inside the LTR chrome.
 */
private object HavenLatinType {
    /** `.h1` — 28sp semibold, tight tracking. */
    val h1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.02f))
    /** `.h2` — 26sp semibold. */
    val h2 = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.02f))
    /** Workspace / screen titles — 22sp semibold. */
    val h3 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(-0.01f))
    /** Card/section titles — 17sp semibold. */
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    /** Hero value inside the Activity card — 18sp semibold. */
    val titleLg = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    /** List row titles — 15sp semibold. */
    val rowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    /** Row body — 15sp regular. */
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    /** Row descriptions — 13sp. */
    val desc = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    /** Small labels — 12sp. */
    val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    /** Section headers — 12.5sp semibold, uppercase, letter-spaced. */
    val section = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(0.09f))
    /** Eyebrow — 10sp bold uppercase, wide tracking. */
    val eyebrow = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = em(0.24f))
    /** Sidebar brand — 13sp bold uppercase. */
    val brand = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = em(0.14f))
    /** Bottom-nav labels — 10sp semibold. */
    val navLabel = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = em(0.05f))
    /** Tile label — 10.5sp bold uppercase. */
    val tileLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = em(0.1f))
}

/**
 * Script profile of the active UI language.
 *
 * Persian (Arabic script) differs from Latin in two ways that matter to a
 * fixed-rhythm design system:
 *
 *  1. Letter-spacing. Tracking is applied after every glyph; in a joining
 *     script that opens gaps inside words and breaks the cursive connection.
 *     Haven's tracked styles (eyebrow, section, brand, nav, tile labels and
 *     the negative tracking on headings) are therefore set to zero.
 *  2. Line box. Without an explicit line height Compose takes the line box
 *     from the font's own ascent/descent. Persian falls back to a system
 *     Arabic-script face whose metrics are much taller than the Latin face, so
 *     every label, button and row grew. Styles without an explicit line height
 *     get a fixed proportional one so the same style makes the same box in both
 *     scripts. Wrapped prose keeps its explicit line heights (`desc`, `term`,
 *     `monoBlock`).
 *
 * Font SIZES are intentionally not scaled here; any size calibration must be
 * measured on a device before it is introduced.
 *
 * The flag is Compose snapshot state published once by `LinexaTheme` from the
 * localized configuration. Reading it inside a composition subscribes the
 * reader, so a language switch recomposes the affected text.
 */
object HavenScript {
    private val arabicScript = androidx.compose.runtime.mutableStateOf(false)

    /** True while the active UI language is written in Arabic script. */
    val isArabicScript: Boolean get() = arabicScript.value

    /** Publishes the active script. Called by `LinexaTheme` only. */
    internal fun publish(arabic: Boolean) {
        if (arabicScript.value != arabic) arabicScript.value = arabic
    }

    /** Proportional line height for styles that do not declare one (Persian only). */
    const val ARABIC_LINE_HEIGHT_EM = 1.3f
}

/** Applies the Arabic-script calibration of [HavenScript] to a prose style. */
private fun TextStyle.forScript(): TextStyle =
    if (!HavenScript.isArabicScript) {
        this
    } else {
        copy(
            letterSpacing = 0.sp,
            lineHeight = if (lineHeight == TextUnit.Unspecified) em(HavenScript.ARABIC_LINE_HEIGHT_EM) else lineHeight
        )
    }

/**
 * Resolves the Haven palette for the current composition.
 */
@Composable
fun rememberHavenColors(darkTheme: Boolean, accent: ColorTheme): HavenColors =
    if (darkTheme) {
        HavenColors.darkPalette(HavenColors.accentFor(accent, dark = true))
    } else {
        HavenColors.lightPalette(HavenColors.accentFor(accent, dark = false))
    }
