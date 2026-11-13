package com.fem.linexa.ui.theme

/**
 * UI/UX consistency pass — canonical alpha-tint scale.
 *
 * Composables apply tints by calling `MaterialTheme.colorScheme.*.copy(alpha = …)`.
 * Before this object existed, the *same* alpha literal (0.12f, 0.16f, 0.28f, …)
 * was restated in ~20 places across `ShortcutCard`, `FeatureItem`,
 * `WorkspacePerformancePanel`, `GlobalSettingsScreen`, `WhatsNewCard`,
 * `TerminalSessionOverview`,
 * `TerminalKilledNotice`, `VncViewer`, `SettingsTab`, `DesktopScreen`, and
 * `PackageManagerScreen`. Drift was inevitable — `0.12f` had already drifted to
 * `0.10f` in one place and `0.14f` in another for the same visual concept.
 *
 * The values below are the canonical scales. New code MUST source its alpha
 * from here rather than typing a literal; existing call sites are migrated
 * incrementally. Each name describes the *visual intent*, not the value —
 * so `chip` and `track` can both be `0.12f` today but evolve separately
 * tomorrow without a search-and-replace.
 *
 * The scale mirrors the M3 tonal-tier nomenclature:
 *  - `outline`     ~ 0.06  (subtle separation on a single surface)
 *  - `surfaceTint` ~ 0.12  (default chip/track/selected-row fill)
 *  - `hover`       ~ 0.16  (slightly stronger fill for hover/active emphasis)
 *  - `band`        ~ 0.22  (decorative colour band over an image)
 *  - `scrim`       ~ 0.28  (image overlay that still shows the image clearly)
 *  - `scrimStrong` ~ 0.45  (modal-style overlay that mostly hides the image)
 *  - `disabled`    ~ 0.38  (M3 disabled-container alpha)
 *  - `pressed`     ~ 0.5   (transient pressed-state emphasis)
 *  - `solid`       ~ 0.7   (near-opaque fill that still respects the underlying hue)
 *
 * Use via:
 *     MaterialTheme.colorScheme.primary.copy(alpha = Tints.surfaceTint)
 *
 * Or the convenience extension:
 *     MaterialTheme.colorScheme.primary.tinted(Tints.surfaceTint)
 */
object Tints {
    val outline: Float = 0.06f
    val surfaceTint: Float = 0.12f
    val hover: Float = 0.16f
    val band: Float = 0.22f
    val scrim: Float = 0.28f
    val disabled: Float = 0.38f
    val scrimStrong: Float = 0.45f
    val pressed: Float = 0.5f
    val solid: Float = 0.7f
}

/**
 * Convenience: `myColor.tinted(Tints.surfaceTint)` reads better at the call site
 * than `myColor.copy(alpha = Tints.surfaceTint)`, and signals the intent
 * ("tinted") rather than the mechanism ("copy the alpha component").
 */
fun androidx.compose.ui.graphics.Color.tinted(alpha: Float): androidx.compose.ui.graphics.Color =
    copy(alpha = alpha)
