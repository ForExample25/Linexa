package com.fem.linexa.domain.settings

/**
 * UI/UX Phase 4 — how the app chooses between its light and dark color schemes.
 *
 * Both schemes already existed and were already complete (`Theme.kt`'s
 * `DarkColors`/`LightColors`, §1.90); the only thing missing was a way to say
 * which one to use, because `LinexaTheme`'s `darkTheme` parameter defaulted to
 * `isSystemInDarkTheme()` and its single call site passed no argument. So this is
 * a preference over machinery that is already there, not a new theming system.
 *
 * [SYSTEM] is the default and is **not** the same as picking whichever scheme the
 * system happens to be using right now: it is a live subscription, so a device
 * that flips to dark at sunset flips the app with it. That is why the resolution
 * step takes the current system value as a parameter ([resolveDarkTheme]) rather
 * than being a plain `isDark` property.
 *
 * Persisted by its [key], not by [Enum.ordinal] or [Enum.name]. An ordinal is a
 * position in a list — reordering the entries would silently repoint every
 * existing user's stored preference at a different mode — and `name` would tie
 * the on-disk format to Kotlin identifiers. An unknown or absent stored value
 * resolves to [SYSTEM]: a preference this cosmetic must never be a startup
 * failure, and falling back to "follow the device" is the same thing the app did
 * before this setting existed.
 */
enum class ThemeMode(val key: String) {
    /** Follow the device's light/dark setting (the pre-Phase-4 behaviour). */
    SYSTEM("system"),

    /** Always use the light color scheme, regardless of the device. */
    LIGHT("light"),

    /** Always use the dark color scheme, regardless of the device. */
    DARK("dark");

    /**
     * Whether the dark scheme should be used, given what the system currently
     * reports.
     *
     * [systemDark] is only consulted by [SYSTEM]; the two explicit modes ignore
     * it entirely, which is the whole point of choosing one.
     */
    fun resolveDarkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** The mode used when nothing has been chosen or the stored value is unreadable. */
        val DEFAULT: ThemeMode = SYSTEM

        /**
         * Parse a persisted [key] back into a mode, falling back to [DEFAULT].
         *
         * Deliberately total: it accepts null (nothing stored yet), an empty
         * string, and any value written by a future or older build. Nothing here
         * throws, because the caller is the theme wrapper around the entire app.
         */
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
