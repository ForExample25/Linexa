package com.fem.linexa.domain.update

/**
 * The update channel a build belongs to.
 *
 * Linexa publishes betas as GitHub pre-releases. A stable build must not offer those
 * unless the user explicitly opts in; a beta build is already on that channel, so
 * hiding its own successor behind an off-by-default toggle would strand beta testers.
 *
 * The build flag exists because a beta's `versionName` need not contain `-beta` (0.2.0
 * is exactly `0.2.0`). The name check is retained for older/dev builds that do carry
 * the suffix.
 *
 * The default is only a default: once the user touches the toggle, the persisted
 * boolean wins. This is a pure function so the rule is testable without
 * `SharedPreferences` or `BuildConfig`.
 */
object UpdateChannel {

    fun isBeta(versionName: String?, declaredBeta: Boolean = false): Boolean =
        declaredBeta || versionName?.contains("beta", ignoreCase = true) == true

    /** Whether pre-releases should be offered by default for this build. */
    fun defaultIncludePrereleases(versionName: String?, declaredBeta: Boolean = false): Boolean =
        isBeta(versionName, declaredBeta)
}
