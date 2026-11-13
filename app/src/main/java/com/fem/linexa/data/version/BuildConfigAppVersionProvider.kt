package com.fem.linexa.data.version

import android.app.Application
import android.content.pm.PackageManager
import com.fem.linexa.BuildConfig
import com.fem.linexa.domain.version.AppVersionProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [AppVersionProvider]: `BuildConfig` for the version, `PackageManager` for
 * how the app got here.
 *
 * `@Singleton` because all three values are fixed for the lifetime of the process —
 * `firstInstallTime`/`lastUpdateTime` cannot change while the app that is reading them
 * is running, since an update replaces the process. So the `PackageManager` query
 * happens once rather than on every Home composition.
 */
@Singleton
class BuildConfigAppVersionProvider @Inject constructor(
    private val application: Application
) : AppVersionProvider {

    override val versionCode: Int = BuildConfig.VERSION_CODE

    override val versionName: String? = BuildConfig.VERSION_NAME

    /**
     * True when the OS reports that this package has never been updated.
     *
     * Uses the `firstInstallTime == lastUpdateTime` comparison rather than any
     * install-source or flag check, because that equality is exactly the OS's own
     * record of "installed once, never replaced". Both are set to the same value on a
     * fresh install and `lastUpdateTime` advances on every subsequent install of a new
     * APK over it.
     *
     * Fail-**open**, deliberately: any failure resolves to `false`, i.e. "not a fresh
     * install", which routes the caller down the update path. The asymmetry is chosen
     * rather than incidental. Guessing `true` would mean silently writing an
     * acknowledgement baseline for a version the user never saw notes for, permanently
     * swallowing one changelog; guessing `false` at worst shows a card once to someone
     * who just installed the app. A `NameNotFoundException` for the app's own package
     * should be impossible, but this runs on the Home path and must not be able to
     * throw there.
     */
    override val isFirstInstall: Boolean = try {
        @Suppress("DEPRECATION")
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
