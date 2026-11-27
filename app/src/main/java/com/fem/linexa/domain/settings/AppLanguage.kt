package com.fem.linexa.domain.settings

/**
 * The user's app-language preference.
 *
 * The default is [SYSTEM], not [ENGLISH], because the product contract is:
 *
 *  * a fresh install follows the device language when it is English or Persian;
 *  * an unsupported device language falls back to English;
 *  * only an explicit choice is persisted as an override, and once it is, later
 *    system-language changes must not silently replace it.
 *
 * Keeping the third state is what makes that contract expressible. If the
 * default were a concrete language the app could not tell "English because the
 * device is English" from "English because the user chose it", and it would
 * either ignore a device language change that should apply or override one that
 * should not.
 */
enum class AppLanguage(val key: String) {

    /** Follow the device language, clamped to a supported language. */
    SYSTEM("system"),

    ENGLISH("en"),

    PERSIAN("fa");

    /** True when this value is one of the two concrete languages. */
    val isExplicit: Boolean get() = this != SYSTEM

    companion object {

        val DEFAULT: AppLanguage = SYSTEM

        /** Parse a persisted key; unknown or absent values degrade to [DEFAULT]. */
        fun fromKey(key: String?): AppLanguage =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
