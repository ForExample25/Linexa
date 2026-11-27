package com.fem.linexa.service.update

import android.content.Context
import com.fem.linexa.BuildConfig
import com.fem.linexa.domain.update.AppUpdateReleaseRecord
import com.fem.linexa.domain.update.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The update checker's own persisted state — deliberately separate from the
 * main [com.fem.linexa.domain.settings.SettingsRepository]: these are
 * feature-local facts about a background-ish flow (when was GitHub last
 * asked, which release was last selected, does the user want pre-releases),
 * not presentation settings, and keeping them apart leaves the settings
 * repository's interface untouched.
 *
 * `last_release_json` is the one piece that makes the update card survive
 * process death: it is the record of the release the checker last offered,
 * so a completed download can be re-validated and re-shown without a network
 * call when the app is reopened mid-update.
 */
interface UpdatePreferences {
    /** Epoch ms of the last COMPLETED check; null when none ever completed. */
    fun lastCheckAtMs(): Long?
    fun setLastCheckAtMs(value: Long)

    /** Whether pre-release builds are offered. Defaults to the build's own channel. */
    fun includePrereleases(): Boolean
    fun setIncludePrereleases(enabled: Boolean)

    /** The release record the checker last selected, or null. */
    fun lastReleaseRecord(): AppUpdateReleaseRecord?
    fun setLastReleaseRecord(record: AppUpdateReleaseRecord?)
}

@Singleton
class PrefsUpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : UpdatePreferences {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    override fun lastCheckAtMs(): Long? =
        prefs.getLong(KEY_LAST_CHECK, -1L).takeIf { it > 0 }

    override fun setLastCheckAtMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
    }

    override fun includePrereleases(): Boolean =
        prefs.getBoolean(KEY_INCLUDE_PRERELEASES, defaultIncludePrereleases)

    override fun setIncludePrereleases(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PRERELEASES, enabled).apply()
    }

    override fun lastReleaseRecord(): AppUpdateReleaseRecord? = runCatching {
        prefs.getString(KEY_LAST_RELEASE, null)
            ?.let { json.decodeFromString<AppUpdateReleaseRecord>(it) }
    }.getOrNull()

    override fun setLastReleaseRecord(record: AppUpdateReleaseRecord?) {
        val editor = prefs.edit()
        if (record == null) {
            editor.remove(KEY_LAST_RELEASE)
        } else {
            editor.putString(KEY_LAST_RELEASE, runCatching { json.encodeToString(record) }.getOrNull())
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_FILE = "linexa_update_prefs"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_INCLUDE_PRERELEASES = "include_prereleases"
        const val KEY_LAST_RELEASE = "last_release_json"
        val json = Json { ignoreUnknownKeys = true }
        val defaultIncludePrereleases: Boolean =
            UpdateChannel.defaultIncludePrereleases(BuildConfig.VERSION_NAME, BuildConfig.IS_BETA)
    }
}
