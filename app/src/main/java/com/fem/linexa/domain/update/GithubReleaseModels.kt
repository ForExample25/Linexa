package com.fem.linexa.domain.update

import kotlinx.serialization.Serializable

/**
 * The app-facing model of a GitHub release (the in-app update checker's
 * vocabulary).
 *
 * These are DOMAIN models, deliberately free of GitHub's wire shapes: the
 * HTTP layer parses GitHub's JSON into its own DTOs and maps them here, so
 * the version decision, asset selection and persistence
 * ([AppUpdateReleaseRecord]) never learn what GitHub renamed a field.
 *
 * `assets` is unfiltered — draft/prerelease filtering and APK picking are
 * separate, individually tested rules ([ReleaseSelector],
 * [ApkAssetSelector]).
 */
@Serializable
data class GithubRelease(
    val tagName: String,
    /** GitHub's human release title ("Linexa 0.1.0-beta"); may be absent. */
    val displayName: String? = null,
    val isPrerelease: Boolean = false,
    /** GitHub does not serve drafts to anonymous API callers, but filter them anyway. */
    val isDraft: Boolean = false,
    /** Publication time in epoch milliseconds; null when GitHub omitted it. */
    val publishedAtMs: Long? = null,
    /**
     * The Android `versionCode` the publisher declared for this release, when the
     * release body carries a `linexa-version-code` marker. GitHub's API has no
     * versionCode field, so this is the only way to compare the same monotonic
     * number Android uses; null on releases that predate the marker.
     */
    val declaredVersionCode: Int? = null,
    /** The release notes body, as markdown. */
    val notes: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList()
)

/** One downloadable asset attached to a [GithubRelease]. */
@Serializable
data class GithubReleaseAsset(
    val name: String,
    /** GitHub's own download URL (`browser_download_url`); always https. */
    val downloadUrl: String,
    /** Declared size in bytes; null when GitHub omitted it. */
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    /**
     * The GitHub-provided SHA-256 of the asset content, lowercase hex with no
     * `sha256:` prefix; null when GitHub did not publish one (the digest field
     * is newer than some historic assets).
     */
    val sha256Hex: String? = null
)

/**
 * The persisted shape of "the release the checker last selected" — written to
 * the update preferences as JSON so a completed download survives process
 * death and the update card can be rebuilt without a network call.
 */
@Serializable
data class AppUpdateReleaseRecord(
    val release: GithubRelease,
    val selectedAsset: GithubReleaseAsset
)
