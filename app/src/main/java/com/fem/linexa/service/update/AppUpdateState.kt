package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubRelease
import com.fem.linexa.domain.update.GithubReleaseAsset
import java.io.File

/**
 * The update card's complete state machine — every row of the feature's
 * state table is one case here, and the transitions live only in
 * [AppUpdateCoordinator].
 *
 * Every state carries the installed version and the last completed check
 * time, because the card renders both on every state (they are the section's
 * identity), and carrying them avoids the "one state forgot its context"
 * recomposition bugs. `release`/`asset` presence IS the update-offer: a
 * state without them cannot render a download or install control.
 */
sealed interface AppUpdateUiState {

    /** The installed build's `versionName` (also the card's current-version row). */
    val currentVersionName: String?

    /** Epoch ms of the last COMPLETED check, or null when none ever completed. */
    val lastCheckAtMs: Long?

    /** Fresh state: current version + the Check button. */
    data class Idle(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?
    ) : AppUpdateUiState

    /** A check is in flight; the button is disabled, a spinner shows. */
    data class Checking(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?
    ) : AppUpdateUiState

    /** The check failed before a release could be evaluated. Retry = Check again. */
    data class CheckFailed(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val kind: CheckFailureKind,
        val detail: String? = null
    ) : AppUpdateUiState

    /** The newest published release matches (or predates) the installed build. */
    data class UpToDate(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val installedIsNewer: Boolean = false
    ) : AppUpdateUiState

    /** No usable release exists on the channel (e.g. only pre-releases and they are off). */
    data class NoRelease(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?
    ) : AppUpdateUiState

    /** A newer release exists, but no installable APK could be identified in it. */
    data class ReleaseWithoutApk(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease
    ) : AppUpdateUiState

    /** A newer release with an installable APK — the update-available card. */
    data class UpdateAvailable(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset
    ) : AppUpdateUiState

    /** Download in flight; progress in bytes (total may be unknown until headers arrive). */
    data class Downloading(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset,
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : AppUpdateUiState

    /** Download failed or failed verification; the file was removed. Retry re-downloads. */
    data class DownloadFailed(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset,
        val kind: DownloadFailureKind,
        val detail: String? = null
    ) : AppUpdateUiState

    /** Downloaded, size/digest/package verified — the Install Update state. */
    data class Downloaded(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset,
        val file: File
    ) : AppUpdateUiState

    /** Install tapped, but Android does not yet allow this app to request installs. */
    data class InstallPermissionRequired(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset,
        val file: File
    ) : AppUpdateUiState

    /** The APK is handed to Android's installer; the platform dialog is up. */
    data class Installing(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset
    ) : AppUpdateUiState

    /** The platform reported a failed install (e.g. signature mismatch). */
    data class InstallFailed(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease,
        val asset: GithubReleaseAsset,
        val file: File,
        val kind: InstallFailureKind,
        val detail: String? = null
    ) : AppUpdateUiState

    /** The platform reported SUCCESS. The app process is replaced moments later. */
    data class InstallComplete(
        override val currentVersionName: String?,
        override val lastCheckAtMs: Long?,
        val release: GithubRelease
    ) : AppUpdateUiState
}

/** Why a check did not complete — each kind has its own localized copy. */
enum class CheckFailureKind { NETWORK, RATE_LIMITED, SERVER, UNAVAILABLE, MALFORMED, UNKNOWN }

/** Why a download (or its verification) failed. */
enum class DownloadFailureKind { NETWORK, DISK, VERIFY, UNTRUSTED }

/** Why the install launch (or the platform install itself) failed. */
enum class InstallFailureKind { PERMISSION, IO, GENERIC }

/** States the Check button is meaningful in (never mid-check/download/install). */
internal val AppUpdateUiState.canStartCheck: Boolean
    get() = this !is AppUpdateUiState.Checking &&
        this !is AppUpdateUiState.Downloading &&
        this !is AppUpdateUiState.Installing

/** States whose Download / Retry Download control is meaningful. */
internal val AppUpdateUiState.canStartDownload: Boolean
    get() = this is AppUpdateUiState.UpdateAvailable ||
        this is AppUpdateUiState.DownloadFailed ||
        this is AppUpdateUiState.Downloaded
