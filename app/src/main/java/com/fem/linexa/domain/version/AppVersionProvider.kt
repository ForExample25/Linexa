package com.fem.linexa.domain.version

/**
 * The running build's identity, and how it came to be installed.
 *
 * An interface rather than direct `BuildConfig` reads at the call site because both
 * values it carries are *untestable in place*: `BuildConfig` is generated per variant
 * and is not meaningfully on the unit-test classpath (the same reason
 * `AppVersionLabel` takes its values as parameters instead of reading them), and
 * [isFirstInstall] comes from `PackageManager`, which cannot be exercised on the JVM
 * at all. Behind this seam, the update-detection rule is testable against plain
 * values; without it, the one behaviour the What's New feature exists to get right
 * could only be verified by repeatedly reinstalling the APK by hand.
 *
 * Note this is the *first* such seam in the app. Before it, the only version read
 * anywhere was `BuildConfig.VERSION_CODE`/`VERSION_NAME` inline in the Global Settings
 * footer. That call site is deliberately left alone — it renders the version rather
 * than deciding anything with it, so it has nothing to gain from injection, and
 * changing it would be an unrelated edit.
 */
interface AppVersionProvider {

    /**
     * `versionCode` of the running build — the authoritative update identifier.
     *
     * `versionCode`, not `versionName`, for the same reason Android uses it to decide
     * whether one APK may replace another: it is monotonic and unique per build, while
     * `versionName` is a display string that several builds can share (every Linexa
     * build so far is named `1.0`).
     */
    val versionCode: Int

    /** `versionName` of the running build, or null if the build declared none. */
    val versionName: String?

    /**
     * Whether this package has never been updated — i.e. the install currently on the
     * device is the first one.
     *
     * This is asked of the *operating system* rather than inferred from stored app
     * state, because those two answers differ in a case that matters: a user upgrading
     * from a build that shipped before the What's New feature existed has no stored
     * baseline, yet is unambiguously not a first install. Treating "nothing stored" as
     * "fresh install" would silently swallow the first changelog the feature was built
     * to show. See `WhatsNewDecision.decide`.
     */
    val isFirstInstall: Boolean
}
