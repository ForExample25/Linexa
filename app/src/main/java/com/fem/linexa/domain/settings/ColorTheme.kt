package com.fem.linexa.domain.settings

/**
 * UI/UX Phase 7 — the app's color-theme dimension, separate from [ThemeMode].
 *
 * [ThemeMode] answers "light or dark?", and the answer is resolved against the live
 * device value when the user picks `SYSTEM`. [ColorTheme] answers "which palette?",
 * and the answer is independent of the light/dark decision: choosing `LAVENDER` does
 * not change whether the app is light or dark, and switching Light→Dark does not
 * reset the chosen color theme. The two compose — every (ThemeMode, ColorTheme)
 * pair is a distinct visual.
 *
 * Persisted by its [key], not by [Enum.ordinal] or [Enum.name], for the same reason
 * as [ThemeMode]: an ordinal is a position in a list (reordering entries would
 * silently repoint every existing user's stored preference), and `name` would tie
 * the on-disk format to Kotlin identifiers. An unknown or absent stored value
 * resolves to [ALPINE_BLUE]: this wraps the whole app's color scheme, so it must
 * not be a startup failure, and falling back to the project's reference alpine
 * palette is the same thing the app did before this setting existed.
 *
 * **Pure Kotlin** — no `androidx.compose` dependency. The swatch-color preview and
 * the per-theme `ColorScheme` mapping live in the UI layer
 * (`com.fem.linexa.ui.theme.ColorThemes`), so the domain layer stays free of Compose
 * types and the enum stays JVM-testable on a plain `kotlinc` classpath.
 */
enum class ColorTheme(val key: String) {
    /** The project's reference alpine-blue palette — the historical default. */
    ALPINE_BLUE("alpine_blue"),

    /** A lavender-tinted variant — replaces the blue primary with ReferenceLavender. */
    LAVENDER("lavender"),

    /** A forest-green variant — replaces the blue primary with StatusSuccess green. */
    FOREST("forest"),

    /** A sunset-orange variant — replaces the blue primary with DistroOrange. */
    SUNSET("sunset");

    companion object {
        /** The theme used when nothing has been chosen or the stored value is unreadable. */
        val DEFAULT: ColorTheme = ALPINE_BLUE

        /**
         * Parse a persisted [key] back into a theme, falling back to [DEFAULT].
         *
         * Deliberately total: it accepts null (nothing stored yet), an empty
         * string, and any value written by a future or older build. Nothing here
         * throws, because the caller is the theme wrapper around the entire app.
         */
        fun fromKey(key: String?): ColorTheme =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
