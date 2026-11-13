package com.fem.linexa.ui.screens.settings

/**
 * UI/UX Phase 4 (3) — the version line under the app icon at the bottom of Global
 * Settings.
 *
 * A pure type for the same reason as [GlobalSettingsContent] (§1.129(d)): the
 * interesting cases are *data* cases that a screenshot of one build cannot show. Here
 * they are specifically the ones a release process produces:
 *
 *  * **A release with no version name.** `versionName` is nullable in `BuildConfig` in
 *    principle and empty in practice if it is ever driven from CI (`-PversionName=`
 *    with nothing after it). "Version " followed by nothing reads as a broken build;
 *    [UNKNOWN_VERSION] states the fact instead.
 *  * **The build number.** `versionName` alone cannot distinguish two builds of "1.0",
 *    which is exactly the situation a bug report from a CI artifact arrives in — every
 *    APK so far is `1.0`. So the code is shown too, in parentheses, the same convention
 *    Play Console and every bug-report template use.
 *  * **A version name that already contains its build number.** Some release scripts set
 *    `versionName = "1.0 (12)"`. Appending the code again would render `1.0 (12) (12)`.
 *
 * The values themselves come from `BuildConfig` at the call site rather than being read
 * here, so this stays JVM-testable — `BuildConfig` is generated per variant and is not
 * on the unit-test classpath in a form worth depending on.
 */
object AppVersionLabel {

    /** Shown in place of a missing version name, rather than an empty string. */
    const val UNKNOWN_VERSION = "unknown"

    /**
     * The version line: the name, plus the build code in parentheses.
     *
     * @param versionName `BuildConfig.VERSION_NAME`; nullable and blank-tolerant because
     *   a build script can produce either.
     * @param versionCode `BuildConfig.VERSION_CODE`. Omitted from the output when it is
     *   not a real build number (`<= 0`) or when [versionName] already ends with it.
     */
    fun format(versionName: String?, versionCode: Int): String {
        val name = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_VERSION
        if (versionCode <= 0) return name
        // "1.0 (12)" must not become "1.0 (12) (12)". Matched on the exact rendered
        // suffix rather than on "contains the number", so a version name of "1.12" is
        // still allowed to carry code 12.
        val suffix = "($versionCode)"
        if (name.endsWith(suffix)) return name
        return "$name $suffix"
    }
}
