package com.fem.linexa.domain.update

/**
 * Picks the release to evaluate from GitHub's releases list, newest first.
 *
 * GitHub's own `/releases/latest` endpoint would do most of this, but it
 * answers 404 when a repository has NO stable release (exactly this project's
 * state today — the published v0.1.0-beta is a pre-release), so the checker
 * reads the full list and applies the same rules itself, visibly and
 * testably:
 *
 *   * drafts are ALWAYS ignored (they are unpublished and anonymous callers
 *     do not receive them anyway);
 *   * pre-releases are skipped on a stable channel. A beta build defaults to
 *     including them (it is already on that channel); the user's persisted
 *     toggle is the final word either way.
 *   * the first surviving entry is the release evaluated — GitHub's list is
 *     newest-first, which is the only ordering claim this relies on.
 */
object ReleaseSelector {

    fun select(releases: List<GithubRelease>, includePrereleases: Boolean): GithubRelease? =
        releases
            .asSequence()
            .filter { !it.isDraft }
            .firstOrNull { includePrereleases || !it.isPrerelease }
            ?.takeIf { it.tagName.isNotBlank() }
}
