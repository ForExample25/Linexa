package com.fem.linexa.core.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.fem.linexa.domain.settings.AppLanguage
import java.util.Locale

/**
 * Central English/Persian locale resolver.
 *
 * This is deliberately a small, dependency-free object rather than an
 * `AppCompatDelegate` wrapper:
 *
 *  * The app is a Compose-only `ComponentActivity` app and does not depend on
 *    AppCompat, so `AppCompatDelegate.setApplicationLocales` is unavailable.
 *  * Per-app language storage on Android 13+ is opt-in and would not cover the
 *    API 28–32 range this app supports. A persisted preference that both the
 *    composition and non-composition callers can read is uniform on all of them.
 *
 * The root composable provides the localized context (and a fixed LTR layout
 * direction) to the whole tree, so a language switch is immediate and does not
 * recreate the activity, interrupt a terminal, or touch any workspace state.
 * Non-composition callers — ViewModels producing messages, notifications, the
 * document provider — call [linexaString] / [localizedContext], which resolve
 * the same persisted preference at the point of use.
 *
 * Language and layout direction are deliberately separate: the locale decides
 * which resources are read, while [localizedContext] always clears the RTL
 * layout bit. The app's interface is left-to-right in every language; Persian
 * text keeps RTL bidi rendering through the text pipeline (see
 * `docs/design/ACCESSIBILITY_AND_LOCALIZATION.md` §4.2.1).
 *
 * Technical identifiers are intentionally not translated: language tags are
 * `en`/`fa`, and the Persian system locale is matched by its ISO tag rather than
 * by a display name.
 */
object AppLocale {

    const val PERSIAN_TAG = "fa"
    const val ENGLISH_TAG = "en"

    /** The settings file/key shared with `SettingsRepositoryImpl`. */
    const val PREFS_FILE = "linexa_settings"
    const val KEY_APP_LANGUAGE = "app_language"

    /**
     * Read the persisted preference without constructing the repository.
     *
     * Needed by notification/service code and by the localized-context helper,
     * which can run before (or outside) any Hilt component is available. It is
     * the same key the repository writes, so there is exactly one source of
     * truth on disk.
     */
    fun readPreference(context: Context): AppLanguage {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        return AppLanguage.fromKey(prefs.getString(KEY_APP_LANGUAGE, null))
    }

    /**
     * Resolve [preference] to one of the two supported languages.
     *
     * SYSTEM walks the device's locale list in priority order and takes the
     * first supported language. If none is supported the contract is English,
     * never the device's unsupported language (which would leave most strings
     * untranslated and the layout direction ambiguous).
     */
    fun effectiveLanguage(preference: AppLanguage, systemLocales: LocaleList): AppLanguage {
        if (preference.isExplicit) return preference
        for (index in 0 until systemLocales.size()) {
            when (systemLocales[index].language.lowercase(Locale.ROOT)) {
                PERSIAN_TAG -> return AppLanguage.PERSIAN
                ENGLISH_TAG -> return AppLanguage.ENGLISH
            }
        }
        return AppLanguage.ENGLISH
    }

    /** Convenience overload using the context's current configuration. */
    fun effectiveLanguage(context: Context, preference: AppLanguage): AppLanguage =
        effectiveLanguage(preference, context.resources.configuration.locales)

    fun localeFor(language: AppLanguage): Locale = when (language) {
        AppLanguage.PERSIAN -> Locale(PERSIAN_TAG)
        AppLanguage.ENGLISH, AppLanguage.SYSTEM -> Locale(ENGLISH_TAG)
    }

    /**
     * A context whose resources resolve strings in [preference].
     *
     * The returned context **wraps [context]** and overrides only resource/asset
     * resolution from a `createConfigurationContext` result. That distinction is
     * load-bearing: `createConfigurationContext` returns a bare `ContextImpl`
     * that is not a `ContextWrapper`, so callers such as `findActivity()` cannot
     * recover the Activity from it — the root composition would lose the host
     * and crash. Wrapping the original context keeps its services, content
     * resolver, theme and activity token intact while still resolving strings
     * (and `LocalConfiguration`) in [preference]'s locale.
     *
     * The layout-direction bit is forced to LTR even for Persian. This is the
     * single place where a locale is turned into a `Configuration`, so every
     * consumer of the result — `LocalConfiguration`, platform views created
     * from this context, and non-composition callers — sees the same LTR
     * policy. Text direction is not touched here: it is a property of each
     * string and is resolved by the text renderer's bidi algorithm.
     */
    fun localizedContext(context: Context, preference: AppLanguage): Context {
        val language = effectiveLanguage(context, preference)
        val locale = localeFor(language)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            // Global LTR layout policy: set the locale above, then clear the
            // RTL layout bit. `Locale.ROOT` is direction-neutral, so this is a
            // layout-only change; `locales` still resolves to `locale`.
            setLayoutDirection(Locale.ROOT)
        }
        val localized = context.createConfigurationContext(configuration)
        return LocalizedResourcesContext(context, localized.resources)
    }

    /** Localize with the preference currently on disk. */
    fun localizedContext(context: Context): Context =
        localizedContext(context, readPreference(context))

    /**
     * Resolve a string in the app's selected language from any context.
     *
     * Use this in ViewModels, services, providers and other non-composition
     * callers. Composition code should use `stringResource`, which follows the
     * root-provided `LocalContext` and updates immediately.
     */
    fun string(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String =
        localizedContext(context).getString(resId, *formatArgs)

    /**
     * Quantity-aware counterpart to [string].
     *
     * Uses `getQuantityString`, not `getString`, so Persian's plural rules are
     * the resource system's job rather than a hardcoded `(s)` suffix.
     */
    fun quantityString(
        context: Context,
        @PluralsRes resId: Int,
        quantity: Int,
        vararg formatArgs: Any
    ): String = localizedContext(context).resources
        .getQuantityString(resId, quantity, *formatArgs)
}

/**
 * A context that keeps [base]'s identity (services, theme, content resolver,
 * activity token) and swaps only resource/asset resolution.
 *
 * This is deliberately a `ContextWrapper`: a bare `createConfigurationContext`
 * result is not part of the wrapper chain, so [findActivity] could not reach
 * the host Activity from it.
 */
private class LocalizedResourcesContext(
    base: Context,
    private val localized: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
    override fun getAssets(): AssetManager = localized.assets
}

/** The non-composition counterpart to Compose's `stringResource`. */
fun Context.linexaString(@StringRes resId: Int, vararg formatArgs: Any): String =
    AppLocale.string(this, resId, *formatArgs)

/** Quantity-aware counterpart to [linexaString]. */
fun Context.linexaQuantityString(
    @PluralsRes resId: Int,
    quantity: Int,
    vararg formatArgs: Any
): String = AppLocale.quantityString(this, resId, quantity, *formatArgs)

/**
 * Unwrap a `ContextWrapper` chain to its Activity, if any.
 *
 * The root composition provides a configuration-wrapped context as
 * `LocalContext`; a `ContextWrapper` around an Activity is not itself an
 * Activity, so callers that need the activity owner must unwrap explicitly.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
