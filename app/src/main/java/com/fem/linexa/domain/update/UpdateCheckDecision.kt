package com.fem.linexa.domain.update

/**
 * Decides whether a GitHub release constitutes an update for the installed
 * build — the rule layer between the fetched release and the UI state.
 *
 * The primary comparison is by Android `versionCode` when the release body
 * carries a `linexa-version-code` marker: that is the same monotonic number
 * Android itself uses, so it cannot be fooled by a re-spelled or prefixed
 * version name. Releases published before the marker existed (and releases
 * where the marker is malformed) fall back to [AppVersionComparator] on the
 * tag/`versionName` pair.
 *
 * The outcomes are deliberately closed: the caller either offers the update,
 * reports up-to-date, or reports that the INSTALLED build is newer (the
 * release is stale relative to this device) — in every case the release is
 * NEVER offered as an update. An unreadable release tag fails toward
 * up-to-date rather than toward an update.
 */
object UpdateCheckDecision {

    sealed interface Outcome {
        /** A newer release — safe to offer, with its APK already selected. */
        data class UpdateAvailable(val release: GithubRelease) : Outcome

        /** The installed build matches the newest published release. */
        data object UpToDate : Outcome

        /**
         * The installed build is NEWER than the newest published release (a
         * dev build ahead of the channel, or a stale release). Never offered.
         */
        data object InstalledVersionNewer : Outcome
    }

    fun decide(
        installedVersionName: String?,
        release: GithubRelease,
        installedVersionCode: Int? = null
    ): Outcome {
        val declaredCode = release.declaredVersionCode
        if (declaredCode != null && declaredCode > 0 && installedVersionCode != null && installedVersionCode > 0) {
            return when {
                declaredCode > installedVersionCode -> Outcome.UpdateAvailable(release)
                declaredCode == installedVersionCode -> Outcome.UpToDate
                else -> Outcome.InstalledVersionNewer
            }
        }

        val candidate = release.tagName
        // Blank AND unparseable tags both fail toward up-to-date: an
        // unreadable tag is not evidence the installed build is ahead of the
        // channel, and offering it as an update is out of the question.
        if (candidate.isBlank() || !AppVersionComparator.isParseable(candidate)) {
            return Outcome.UpToDate
        }
        val current = installedVersionName?.trim().orEmpty().ifEmpty { "0" }
        return when {
            AppVersionComparator.isNewer(candidate, current) -> Outcome.UpdateAvailable(release)
            AppVersionComparator.isSame(candidate, current) -> Outcome.UpToDate
            else -> Outcome.InstalledVersionNewer
        }
    }
}
