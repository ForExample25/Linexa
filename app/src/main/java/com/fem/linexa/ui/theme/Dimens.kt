package com.fem.linexa.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Linexa spacing scale — the single source of truth for layout rhythm.
 * Built on a 4dp base grid (M3-friendly) so every padding/margin value
 * reads as a multiple of 4 (or a deliberate one-off).
 *
 * Shared components and screens MUST pull spacing from here instead of
 * scattering `.dp` literals, so gutters/padding stay consistent across the
 * app. Values off this grid (e.g. 14dp, 18dp) are intentional one-offs
 * and may stay inline.
 *
 * The scale is intentionally compact — Linexa is a technical application
 * with high data density, so the rhythm is tighter than a content app.
 * Most screens pack 2–4× the information per row of a Material 3 default.
 */
object Spacing {
    /** 2dp — tight inner padding inside dense rows. */
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

/**
 * Card / container content padding — the inner rhythm of every Haven card.
 * Pulled out of [Spacing] because it's referenced by every card component
 * and the value pairs the outer card padding with the inner section rhythm
 * (card padding = lg, section gap = md) rather than being an independent
 * number on a 4dp grid.
 */
object ContentPadding {
    /** Card outer padding — the breathing room inside a card surface. */
    val card = Spacing.lg

    /** Gap between sections inside a card. */
    val sectionGap = Spacing.md

    /** Gap between rows inside a section. */
    val rowGap = Spacing.sm

    /** Section header → body gap. */
    val headerToBody = Spacing.md
}

/**
 * Corner radii — the Linexa shape language.
 *
 * The app uses a tighter corner scale than the previous M3 default (which
 * went up to 36dp). A technical application reads better with smaller
 * corners — the surface feels like a console panel, not a chat bubble.
 */
object Corners {
    val none = 0.dp
    val xs = 6.dp          // chips, pills
    val sm = 10.dp         // buttons, inputs
    val md = 14.dp         // cards (default)
    val lg = 18.dp         // sheets, dialogs
    val xl = 22.dp         // hero cards
    val xxl = 28.dp        // full-bleed sheets
}

/**
 * Elevation — the Linexa surface stack.
 *
 * Linexa's design language prefers surface tint over shadow for elevation
 * (true to the dark-first, terminal-style aesthetic). When a shadow is
 * needed (e.g. modals over a transparent surface), pull from this scale.
 */
object Elevation {
    val none = 0.dp
    val raised = 1.dp
    val card = 2.dp
    val floating = 4.dp
    val modal = 8.dp
}

/**
 * Icon sizes — the Linexa icon system.
 *
 * Glyph Monoline icons come in three sizes:
 *   - small  : inside rows, inline labels (e.g. status pill icon)
 *   - medium  : default for app bars, navigation, card headers
 *   - large   : empty states, hero card icons, illustrations
 */
object IconSize {
    val tiny = 12.dp
    val small = 16.dp
    val medium = 20.dp
    val large = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val huge = 64.dp
}

/**
 * Touch targets — minimum hit sizes for accessibility.
 *
 * Linexa is a power-user app with dense rows, but every tap target still
 * meets the 40dp minimum (M3 / WCAG AA). Compact rows use 40dp; default
 * targets use 48dp.
 */
object TouchTarget {
    val compact = 40.dp
    val default = 48.dp
    val large = 56.dp
    val fab = 56.dp
}
