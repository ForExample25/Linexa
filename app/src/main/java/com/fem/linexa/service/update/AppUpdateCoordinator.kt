package com.fem.linexa.service.update

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.update.ApkAssetSelector
import com.fem.linexa.domain.update.AppUpdateReleaseRecord
import com.fem.linexa.domain.update.AppVersionComparator
import com.fem.linexa.domain.update.GithubRelease
import com.fem.linexa.domain.update.GithubReleaseAsset
import com.fem.linexa.domain.update.ReleaseSelector
import com.fem.linexa.domain.update.UpdateCheckDecision
import com.fem.linexa.domain.version.AppVersionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app update system's orchestrator — the ONLY owner of the update
 * card's state and of every transition in it.
 *
 * Responsibilities, in the order the user meets them:
 *
 *   * **Check** ([checkForUpdates]) — user-initiated only. GitHub's release
 *     list → [ReleaseSelector] (drafts never, pre-releases only when the
 *     user opted in) → [UpdateCheckDecision] (version compare, never string
 *     equality) → [ApkAssetSelector]. Nothing downloads or installs here.
 *   * **Download** ([startDownload]) — user-initiated only, one at a time
 *     (guarded by state and by the live job). Runs on the coordinator's IO
 *     scope, so it survives screen recreation and navigation; progress lands
 *     in [state] as it arrives. A cancelled download leaves the resumable
 *     partial in place and returns to the update-available state.
 *   * **Install** ([installUpdate]) — user-initiated only. The APK is
 *     re-validated first (exists, parses, right package, not a downgrade),
 *     the Android install-permission gate is checked (and reported
 *     separately when it needs the user's trip to system settings), then the
 *     APK is handed to the platform installer. Success is claimed ONLY when
 *     Android reports it ([InstallStatus.Success]).
 *   * **Restore** (init) — the last selected release is persisted as JSON;
 *     on process start the coordinator rebuilds the exact pre-death state
 *     (downloaded → Downloaded, offered → UpdateAvailable) from disk alone,
 *     and cleans up the APK when the installed version already matches the
 *     release (the update completed while the app was closed).
 *
 * State is a [StateFlow] on a `@Singleton`: one owner, no duplicate
 * observers, no re-checks on recomposition. All network and file work runs
 * on the provided IO scope — never the UI thread.
 */
@Singleton
class AppUpdateCoordinator @Inject constructor(
    private val api: GithubReleaseApi,
    private val prefs: UpdatePreferences,
    private val downloader: ApkUpdateDownloader,
    private val validator: ApkValidator,
    private val installer: UpdateInstaller,
    private val appVersionProvider: AppVersionProvider,
    /** The private directory APKs download into; `filesDir/updates` in production. */
    updatesDir: File,
    private val scope: CoroutineScope
) {

    private val updatesDir: File = updatesDir

    private val _state = MutableStateFlow<AppUpdateUiState>(
        AppUpdateUiState.Idle(appVersionProvider.versionName, null)
    )

    /** The update card's state. One flow, screen-independent lifetime. */
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    /** The user's pre-release channel preference (persisted). */
    private val _includePrereleases = MutableStateFlow(prefs.includePrereleases())
    val includePrereleases: StateFlow<Boolean> = _includePrereleases.asStateFlow()

    private var downloadJob: Job? = null

    private val installedVersionName: String?
        get() = appVersionProvider.versionName

    init {
        scope.launch { restorePersistedState() }
        scope.launch {
            InstallStatusBus.status.collect { status -> onInstallStatus(status) }
        }
    }

    // ── Check ──────────────────────────────────────────────────────────────

    /**
     * Ask GitHub for the newest release. Never downloads, never installs —
     * the card's states carry the offer and the buttons carry the actions.
     */
    fun checkForUpdates() {
        if (!_state.value.canStartCheck || downloadJob?.isActive == true) return
        val previous = _state.value
        _state.value = AppUpdateUiState.Checking(previous.currentVersionName, previous.lastCheckAtMs)
        scope.launch {
            try {
                val releases = api.fetchReleases()
                val release = ReleaseSelector.select(releases, _includePrereleases.value)
                // Only a COMPLETED evaluation updates the "last checked" stamp.
                val checkedAt = nowMs().also { prefs.setLastCheckAtMs(it) }
                if (release == null) {
                    prefs.setLastReleaseRecord(null)
                    _state.value = AppUpdateUiState.NoRelease(installedVersionName, checkedAt)
                    return@launch
                }
                when (val decision = UpdateCheckDecision.decide(
                    installedVersionName,
                    release,
                    appVersionProvider.versionCode
                )) {
                    is UpdateCheckDecision.Outcome.UpdateAvailable -> offerRelease(release, checkedAt)
                    is UpdateCheckDecision.Outcome.UpToDate -> {
                        prefs.setLastReleaseRecord(null)
                        _state.value = AppUpdateUiState.UpToDate(
                            installedVersionName, checkedAt, release, installedIsNewer = false
                        )
                    }
                    is UpdateCheckDecision.Outcome.InstalledVersionNewer -> {
                        prefs.setLastReleaseRecord(null)
                        _state.value = AppUpdateUiState.UpToDate(
                            installedVersionName, checkedAt, release, installedIsNewer = true
                        )
                    }
                }
            } catch (e: GithubReleaseApi.GithubApiException) {
                _state.value = AppUpdateUiState.CheckFailed(
                    previous.currentVersionName, previous.lastCheckAtMs,
                    kind = when (e.kind) {
                        GithubReleaseApi.GithubApiException.Kind.RATE_LIMITED -> CheckFailureKind.RATE_LIMITED
                        GithubReleaseApi.GithubApiException.Kind.SERVER -> CheckFailureKind.SERVER
                        GithubReleaseApi.GithubApiException.Kind.UNAVAILABLE -> CheckFailureKind.UNAVAILABLE
                    },
                    detail = e.message
                )
            } catch (e: IOException) {
                _state.value = AppUpdateUiState.CheckFailed(
                    previous.currentVersionName, previous.lastCheckAtMs, CheckFailureKind.NETWORK, e.message
                )
            } catch (e: kotlinx.serialization.SerializationException) {
                _state.value = AppUpdateUiState.CheckFailed(
                    previous.currentVersionName, previous.lastCheckAtMs, CheckFailureKind.MALFORMED, e.message
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AppUpdateUiState.CheckFailed(
                    previous.currentVersionName, previous.lastCheckAtMs, CheckFailureKind.UNKNOWN, e.message
                )
            }
        }
    }

    /** Set the pre-release channel preference; re-checks when a check is meaningful. */
    fun setIncludePrereleases(enabled: Boolean) {
        if (_includePrereleases.value == enabled) return
        _includePrereleases.value = enabled
        prefs.setIncludePrereleases(enabled)
        val current = _state.value
        if (current.canStartCheck && downloadJob?.isActive != true) checkForUpdates()
    }

    /** Evaluate the newest release and move to the update-offer (or failure) states. */
    private suspend fun offerRelease(release: GithubRelease, checkedAt: Long?) {
        val asset = ApkAssetSelector.select(release)
        if (asset == null) {
            prefs.setLastReleaseRecord(null)
            _state.value = AppUpdateUiState.ReleaseWithoutApk(installedVersionName, checkedAt, release)
            return
        }
        val record = AppUpdateReleaseRecord(release, asset)
        prefs.setLastReleaseRecord(record)

        // An APK for THIS release may already be on disk (downloaded before a
        // process death) — verify before skipping the download step.
        val existing = downloadedApkFor(record)
        if (existing != null) {
            when (val validation = validator.validate(existing, asset, installedVersionCode)) {
                is ApkValidator.ApkValidationResult.Valid -> {
                    _state.value = AppUpdateUiState.Downloaded(
                        installedVersionName, checkedAt, release, asset, existing
                    )
                    return
                }
                is ApkValidator.ApkValidationResult.Invalid -> {
                    Logger.w(TAG, "restored APK rejected (${validation.kind}) — deleting")
                    existing.delete()
                }
            }
        }
        _state.value = AppUpdateUiState.UpdateAvailable(installedVersionName, checkedAt, release, asset)
    }

    // ── Download ───────────────────────────────────────────────────────────

    /** Start (or resume) the APK download for the release currently offered. */
    fun startDownload() {
        val current = _state.value
        if (!current.canStartDownload || downloadJob?.isActive == true) return
        val (release, asset) = when (current) {
            is AppUpdateUiState.UpdateAvailable -> current.release to current.asset
            is AppUpdateUiState.DownloadFailed -> current.release to current.asset
            is AppUpdateUiState.Downloaded -> current.release to current.asset
            else -> return
        }
        val destination = destinationFileFor(release, asset)
        val total = asset.sizeBytes
        _state.value = AppUpdateUiState.Downloading(
            current.currentVersionName, current.lastCheckAtMs, release, asset,
            downloadedBytes = 0, totalBytes = total
        )
        downloadJob = scope.launch {
            try {
                val outcome = downloader.download(asset, destination) { bytes, total ->
                    onDownloadProgress(bytes, total)
                }
                when (outcome) {
                    is ApkUpdateDownloader.Outcome.Success ->
                        finishDownload(outcome.file, release, asset, current.lastCheckAtMs)
                    is ApkUpdateDownloader.Outcome.Failure -> _state.value = AppUpdateUiState.DownloadFailed(
                        current.currentVersionName, current.lastCheckAtMs,
                        release, asset, when (outcome.kind) {
                            ApkUpdateDownloader.FailureKind.NETWORK -> DownloadFailureKind.NETWORK
                            ApkUpdateDownloader.FailureKind.DISK -> DownloadFailureKind.DISK
                            ApkUpdateDownloader.FailureKind.VERIFY -> DownloadFailureKind.VERIFY
                            ApkUpdateDownloader.FailureKind.UNTRUSTED -> DownloadFailureKind.UNTRUSTED
                        }, outcome.detail
                    )
                }
            } catch (e: CancellationException) {
                // User cancel (or teardown): the resumable partial stays, the
                // card returns to the offer state. Rethrow for proper scope
                // bookkeeping.
                _state.value = AppUpdateUiState.UpdateAvailable(
                    current.currentVersionName, current.lastCheckAtMs, release, asset
                )
                throw e
            }
        }
    }

    /** Cancel the running download; the partial file stays resumable. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    private suspend fun onDownloadProgress(bytesDownloaded: Long, totalBytes: Long?) {
        val current = _state.value
        if (current is AppUpdateUiState.Downloading) {
            _state.value = current.copy(
                downloadedBytes = bytesDownloaded,
                totalBytes = current.totalBytes ?: totalBytes
            )
        }
    }

    /** The download's byte stream is complete; validate before offering install. */
    private fun finishDownload(file: File, release: GithubRelease, asset: GithubReleaseAsset, checkedAt: Long?) {
        when (val validation = validator.validate(file, asset, installedVersionCode)) {
            is ApkValidator.ApkValidationResult.Valid -> _state.value = AppUpdateUiState.Downloaded(
                installedVersionName, checkedAt, release, asset, file
            )
            is ApkValidator.ApkValidationResult.Invalid -> {
                file.delete()
                _state.value = AppUpdateUiState.DownloadFailed(
                    installedVersionName, checkedAt, release, asset,
                    kind = DownloadFailureKind.VERIFY,
                    detail = validation.kind.name
                )
            }
        }
    }

    // ── Install ────────────────────────────────────────────────────────────

    /**
     * Hand the verified APK to Android's official installer. Re-validates the
     * file first (it may have vanished or been truncated since the
     * download), then checks the install-permission gate: when Android needs
     * the user's explicit "allow installs from this app" switch, the state
     * says so and NOTHING is launched.
     */
    fun installUpdate() {
        val current = _state.value
        val (release, asset, file) = when (current) {
            is AppUpdateUiState.Downloaded ->
                Triple(current.release, current.asset, current.file)
            is AppUpdateUiState.InstallPermissionRequired ->
                Triple(current.release, current.asset, current.file)
            is AppUpdateUiState.InstallFailed ->
                Triple(current.release, current.asset, current.file)
            else -> return
        }
        if (!file.isFile) {
            _state.value = AppUpdateUiState.DownloadFailed(
                current.currentVersionName, current.lastCheckAtMs,
                release, asset, DownloadFailureKind.VERIFY, "the downloaded file is missing"
            )
            return
        }
        when (val validation = validator.validate(file, asset, installedVersionCode)) {
            is ApkValidator.ApkValidationResult.Invalid -> {
                file.delete()
                _state.value = AppUpdateUiState.DownloadFailed(
                    current.currentVersionName, current.lastCheckAtMs,
                    release, asset, DownloadFailureKind.VERIFY, validation.kind.name
                )
                return
            }
            is ApkValidator.ApkValidationResult.Valid -> Unit
        }
        if (!installer.canRequestPackageInstalls()) {
            _state.value = AppUpdateUiState.InstallPermissionRequired(
                current.currentVersionName, current.lastCheckAtMs,
                release, asset, file
            )
            return
        }
        launchInstall(release, asset, file, current.lastCheckAtMs)
    }

    /** Open Android's per-app "install unknown apps" page (explicit user action). */
    fun openInstallPermissionSettings() {
        installer.openInstallPermissionSettings()
    }

    /**
     * Called when the screen resumes: if the user granted the install
     * permission in system settings and returned, the install action is
     * offered again immediately (Downloaded state) instead of leaving the
     * permission card stuck.
     */
    fun refreshPermissionGate() {
        val current = _state.value
        val blocked = current as? AppUpdateUiState.InstallPermissionRequired ?: return
        if (installer.canRequestPackageInstalls()) {
            _state.value = AppUpdateUiState.Downloaded(
                current.currentVersionName, current.lastCheckAtMs,
                blocked.release, blocked.asset, blocked.file
            )
        }
    }

    private fun launchInstall(release: GithubRelease, asset: GithubReleaseAsset, file: File, checkedAt: Long?) {
        _state.value = AppUpdateUiState.Installing(installedVersionName, checkedAt, release, asset)
        scope.launch {
            when (installer.installApk(file)) {
                UpdateInstaller.LaunchResult.STARTED -> Unit // status arrives on the bus
                UpdateInstaller.LaunchResult.FAILED_PERMISSION ->
                    _state.value = AppUpdateUiState.InstallFailed(
                        installedVersionName, checkedAt, release, asset, file,
                        InstallFailureKind.PERMISSION, "Android refused the install session"
                    )
                UpdateInstaller.LaunchResult.FAILED_IO ->
                    _state.value = AppUpdateUiState.InstallFailed(
                        installedVersionName, checkedAt, release, asset, file,
                        InstallFailureKind.IO, "writing the APK to the install session failed"
                    )
                UpdateInstaller.LaunchResult.FAILED_GENERIC ->
                    _state.value = AppUpdateUiState.InstallFailed(
                        installedVersionName, checkedAt, release, asset, file,
                        InstallFailureKind.GENERIC, "the install session could not start"
                    )
            }
        }
    }

    /** The platform's install-session status, delivered by the manifest receiver. */
    private fun onInstallStatus(status: InstallStatus) {
        val current = _state.value as? AppUpdateUiState.Installing ?: return
        when (status) {
            is InstallStatus.PendingUserAction -> Unit // the confirmation UI is up; still installing
            is InstallStatus.Success -> _state.value = AppUpdateUiState.InstallComplete(
                current.currentVersionName, current.lastCheckAtMs, current.release
            )
            is InstallStatus.Failure -> _state.value = AppUpdateUiState.InstallFailed(
                current.currentVersionName, current.lastCheckAtMs,
                current.release, current.asset,
                file = destinationFileFor(current.release, current.asset),
                kind = InstallFailureKind.GENERIC,
                detail = status.message
            )
        }
    }

    // ── Restore & housekeeping ─────────────────────────────────────────────

    /**
     * Rebuild the card from disk after process death — no network:
     *
     *   * the persisted release record is re-decided against the INSTALLED
     *     version (so a completed update collapses to Idle);
     *   * a downloaded APK that still validates restores the Downloaded
     *     state;
     *   * stray APKs (no record — e.g. interrupted between delete and
     *     write, or from an older build of the feature) are removed: an
     *     untracked file is never offered for install.
     */
    private suspend fun restorePersistedState() {
        val checkedAt = prefs.lastCheckAtMs()
        val record = prefs.lastReleaseRecord()

        if (record == null) {
            deleteStrayApks()
            _state.value = AppUpdateUiState.Idle(installedVersionName, checkedAt)
            return
        }

        val installed = installedVersionName
        if (installed != null && AppVersionComparator.isSame(record.release.tagName, installed)) {
            // The update was applied while the app was closed.
            deleteStrayApks()
            prefs.setLastReleaseRecord(null)
            _state.value = AppUpdateUiState.Idle(installedVersionName, checkedAt)
            return
        }

        when (UpdateCheckDecision.decide(installed, record.release, appVersionProvider.versionCode)) {
            is UpdateCheckDecision.Outcome.UpdateAvailable -> {
                val apk = downloadedApkFor(record)
                if (apk != null) {
                    when (val validation = validator.validate(
                        apk, record.selectedAsset, installedVersionCode
                    )) {
                        is ApkValidator.ApkValidationResult.Valid -> _state.value = AppUpdateUiState.Downloaded(
                            installedVersionName, checkedAt, record.release, record.selectedAsset, apk
                        )
                        is ApkValidator.ApkValidationResult.Invalid -> {
                            apk.delete()
                            _state.value = AppUpdateUiState.UpdateAvailable(
                                installedVersionName, checkedAt, record.release, record.selectedAsset
                            )
                        }
                    }
                } else {
                    deleteStrayApks()
                    _state.value = AppUpdateUiState.UpdateAvailable(
                        installedVersionName, checkedAt, record.release, record.selectedAsset
                    )
                }
            }
            else -> {
                // The release no longer outranks the installed build.
                deleteStrayApks()
                prefs.setLastReleaseRecord(null)
                _state.value = AppUpdateUiState.Idle(installedVersionName, checkedAt)
            }
        }
    }

    /**
     * APKs with no corresponding persisted record can never be offered for
     * install (nothing re-validates them against a release) — they are
     * removed rather than trusted.
     */
    private fun deleteStrayApks() {
        updatesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(APK_SUFFIX) }
            ?.forEach { it.delete() }
    }

    private fun destinationFileFor(release: GithubRelease, asset: GithubReleaseAsset): File {
        updatesDir.mkdirs()
        return File(updatesDir, fileNameFor(release, asset))
    }

    private fun downloadedApkFor(record: AppUpdateReleaseRecord): File? {
        updatesDir.mkdirs()
        val file = File(updatesDir, fileNameFor(record.release, record.selectedAsset))
        return file.takeIf { it.isFile }
    }

    /** `linexa-v0.1.0-beta.apk` — tag-derived, sanitized, collision-free per release. */
    private fun fileNameFor(release: GithubRelease, asset: GithubReleaseAsset): String {
        val tag = release.tagName.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')
        val base = "linexa-${tag.ifEmpty { "release" }}-${asset.name.hashCode().toUInt()}"
        return "$base$APK_SUFFIX"
    }

    private val installedVersionCode: Long?
        get() = appVersionProvider.versionCode.takeIf { it > 0 }?.toLong()

    private fun nowMs(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "AppUpdateCoordinator"
        const val APK_SUFFIX = ".apk"
    }
}
