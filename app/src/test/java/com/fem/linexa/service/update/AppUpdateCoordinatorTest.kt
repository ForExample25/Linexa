package com.fem.linexa.service.update

import com.fem.linexa.domain.update.AppUpdateReleaseRecord
import com.fem.linexa.domain.update.GithubRelease
import com.fem.linexa.domain.update.GithubReleaseAsset
import com.fem.linexa.domain.version.AppVersionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The coordinator's complete state machine, against fakes for every
 * platform surface (HTTP, prefs, downloader, APK reader, installer) on an
 * [UnconfinedTestDispatcher] scope — every transition the update card can
 * make, asserted as state, including the restore-after-process-death rules
 * and the install-permission gate.
 *
 * `InstallStatusBus` is a process-global; each test runs its own coordinator
 * on a fresh scope (cancelled in [tearDown]) so no collector outlives its
 * test.
 */
class AppUpdateCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private val asset = GithubReleaseAsset(
        name = "app-release.apk",
        downloadUrl = "https://github.com/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
        sizeBytes = 4096L
    )

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        withApk: Boolean = true
    ): GithubRelease = GithubRelease(
        tagName = tag,
        displayName = "Linexa $tag",
        isPrerelease = prerelease,
        publishedAtMs = 1_700_000_000_000L,
        notes = "Notes for $tag",
        assets = if (withApk) listOf(asset) else emptyList()
    )

    private fun goodMetadata() = ApkMetadata(
        packageName = "com.fem.linexa",
        versionCode = 2L,
        versionName = "0.2.0",
        minSdk = 28,
        targetSdk = 34,
        signerSha256 = listOf(ReleaseSigningIdentity.SHA256)
    )

    private fun wrongPackageMetadata() = ApkMetadata(
        packageName = "com.other.app",
        versionCode = 2L,
        versionName = "0.2.0",
        minSdk = 28,
        targetSdk = 34,
        signerSha256 = listOf(ReleaseSigningIdentity.SHA256)
    )

    private class FakeApi : GithubReleaseApi {
        var releases: List<GithubRelease> = emptyList()
        var error: Exception? = null
        var calls = 0
        override suspend fun fetchReleases(): List<GithubRelease> {
            calls++
            error?.let { throw it }
            return releases
        }
    }

    private class FakePrefs : UpdatePreferences {
        var lastCheck: Long? = null
        var prereleases = false
        var record: AppUpdateReleaseRecord? = null
        override fun lastCheckAtMs(): Long? = lastCheck
        override fun setLastCheckAtMs(value: Long) { lastCheck = value }
        override fun includePrereleases(): Boolean = prereleases
        override fun setIncludePrereleases(enabled: Boolean) { prereleases = enabled }
        override fun lastReleaseRecord(): AppUpdateReleaseRecord? = record
        override fun setLastReleaseRecord(record: AppUpdateReleaseRecord?) { this.record = record }
    }

    /** Scriptable download: progress hook, hang mode, outcome factory. */
    private class FakeDownloader : ApkUpdateDownloader {
        // Writes the asset's DECLARED size by default — the coordinator's
        // validator checks file length against `asset.sizeBytes`, so a
        // undersized fake would (correctly) fail verification.
        var factory: (File) -> ApkUpdateDownloader.Outcome = { file ->
            file.writeBytes(ByteArray(4096))
            ApkUpdateDownloader.Outcome.Success(file, "a".repeat(64))
        }
        var hang = false
        var started = 0
        var progressHook: (() -> Unit)? = null

        override suspend fun download(
            asset: GithubReleaseAsset,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit
        ): ApkUpdateDownloader.Outcome {
            started++
            onProgress(32L, asset.sizeBytes)
            progressHook?.invoke()
            if (hang) {
                destination.writeBytes(ByteArray(10))
                awaitCancellation()
            }
            return factory(destination)
        }
    }

    private class FakeReader(private val metadata: ApkMetadata?) : ApkMetadataReader {
        override fun read(apkFile: File): ApkMetadata? = metadata
    }

    private class FakeInstaller(var canInstall: Boolean) : UpdateInstaller {
        var installCalls = 0
        var settingsCalls = 0
        var lastFile: File? = null
        var result = UpdateInstaller.LaunchResult.STARTED
        override fun canRequestPackageInstalls(): Boolean = canInstall
        override fun openInstallPermissionSettings() { settingsCalls++ }
        override fun installApk(apkFile: File): UpdateInstaller.LaunchResult {
            installCalls++
            lastFile = apkFile
            return result
        }
    }

    /** One wired coordinator plus the fakes a test wants to assert against. */
    private class Bundle(
        val coordinator: AppUpdateCoordinator,
        val api: FakeApi,
        val prefs: FakePrefs,
        val downloader: FakeDownloader,
        val installer: FakeInstaller,
        val updatesDir: File
    )

    private fun newBundle(
        releases: List<GithubRelease> = listOf(release("v0.2.0")),
        apiError: Exception? = null,
        reader: ApkMetadata? = goodMetadata(),
        canInstall: Boolean = true,
        versionName: String? = "0.1.0-beta",
        versionCode: Int = 1,
        prefs: FakePrefs = FakePrefs(),
        updatesDir: File? = null
    ): Bundle {
        // Unique per call: a test may build several bundles, and
        // TemporaryFolder.newFolder(name) refuses a repeated name.
        val dir = updatesDir ?: tmp.newFolder()
        val api = FakeApi().apply {
            this.releases = releases
            this.error = apiError
        }
        val downloader = FakeDownloader()
        val installer = FakeInstaller(canInstall)
        val coordinator = AppUpdateCoordinator(
            api = api,
            prefs = prefs,
            downloader = downloader,
            validator = ApkValidator(
                FakeReader(reader),
                "com.fem.linexa",
                28,
                expectedSignerSha256 = ReleaseSigningIdentity.ACCEPTED_SHA256
            ),
            installer = installer,
            appVersionProvider = object : AppVersionProvider {
                override val versionCode: Int = versionCode
                override val versionName: String? = versionName
                override val isFirstInstall: Boolean = false
            },
            updatesDir = dir,
            scope = scope
        )
        return Bundle(coordinator, api, prefs, downloader, installer, dir)
    }

    /** Check → assert the offer, so every later test starts from it. */
    private fun offerUpdate(bundle: Bundle) {
        bundle.coordinator.checkForUpdates()
        val state = bundle.coordinator.state.value
        assertTrue(
            "expected UpdateAvailable but was ${state::class.simpleName}",
            state is AppUpdateUiState.UpdateAvailable
        )
    }

    private fun download(bundle: Bundle) {
        bundle.coordinator.startDownload()
        assertTrue(
            "expected Downloaded but was ${bundle.coordinator.state.value::class.simpleName}",
            bundle.coordinator.state.value is AppUpdateUiState.Downloaded
        )
    }

    // ── Fresh start & check ────────────────────────────────────────────────

    @Test
    fun aFreshStartIsIdleWithTheInstalledVersionAndNoStamp() {
        val bundle = newBundle()
        val state = bundle.coordinator.state.value
        assertTrue(state is AppUpdateUiState.Idle)
        assertEquals("0.1.0-beta", state.currentVersionName)
        assertNull(state.lastCheckAtMs)
    }

    @Test
    fun aCompletedCheckAgainstANewerReleaseOffersTheUpdate() {
        val bundle = newBundle()
        offerUpdate(bundle)
        val offer = bundle.coordinator.state.value as AppUpdateUiState.UpdateAvailable
        assertEquals("v0.2.0", offer.release.tagName)
        assertEquals("app-release.apk", offer.asset.name)
        // A COMPLETED check stamps the time and persists the offer.
        assertNotNull(bundle.prefs.lastCheck)
        assertNotNull(bundle.prefs.record)
    }

    @Test
    fun theSameVersionSpelledDifferentlyReportsUpToDate() {
        val bundle = newBundle(releases = listOf(release("v0.1.0-beta")))
        bundle.coordinator.checkForUpdates()
        val state = bundle.coordinator.state.value
        assertTrue(state is AppUpdateUiState.UpToDate)
        assertFalse((state as AppUpdateUiState.UpToDate).installedIsNewer)
        assertNull(bundle.prefs.record)
    }

    @Test
    fun anInstalledBuildAheadOfTheChannelIsNeverOffered() {
        val bundle = newBundle(versionName = "1.0.0", versionCode = 10)
        bundle.coordinator.checkForUpdates()
        val state = bundle.coordinator.state.value
        assertTrue(state is AppUpdateUiState.UpToDate)
        assertTrue((state as AppUpdateUiState.UpToDate).installedIsNewer)
        assertNull(bundle.prefs.record)
    }

    @Test
    fun anEmptyReleaseListMeansNoRelease() {
        val bundle = newBundle(releases = emptyList())
        bundle.coordinator.checkForUpdates()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.NoRelease)
    }

    @Test
    fun aPrereleaseOnlyChannelMeansNoReleaseUntilTheUserOptsIn() {
        val bundle = newBundle(releases = listOf(release("v0.2.0-beta", prerelease = true)))
        bundle.coordinator.checkForUpdates()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.NoRelease)

        bundle.coordinator.setIncludePrereleases(true)
        // The toggle persists AND auto-rechecks.
        assertTrue(bundle.prefs.prereleases)
        val state = bundle.coordinator.state.value
        assertTrue(
            "expected UpdateAvailable after opting in but was ${state::class.simpleName}",
            state is AppUpdateUiState.UpdateAvailable
        )
        assertEquals("v0.2.0-beta", (state as AppUpdateUiState.UpdateAvailable).release.tagName)
    }

    @Test
    fun checkFailuresCarryTheirKindAndNeverStampTheCheck() {
        val rateLimited = newBundle(
            apiError = GithubReleaseApi.GithubApiException(
                GithubReleaseApi.GithubApiException.Kind.RATE_LIMITED, 403
            )
        )
        rateLimited.coordinator.checkForUpdates()
        val failed = rateLimited.coordinator.state.value as AppUpdateUiState.CheckFailed
        assertEquals(CheckFailureKind.RATE_LIMITED, failed.kind)
        assertNull(rateLimited.prefs.lastCheck)

        val offline = newBundle(apiError = IOException("offline"))
        offline.coordinator.checkForUpdates()
        assertEquals(CheckFailureKind.NETWORK, (offline.coordinator.state.value as AppUpdateUiState.CheckFailed).kind)
        assertNull(offline.prefs.lastCheck)
    }

    @Test
    fun aReleaseWithoutAnInstallableApkIsReportedAndStoresNoRecord() {
        val bundle = newBundle(releases = listOf(release("v0.2.0", withApk = false)))
        bundle.coordinator.checkForUpdates()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.ReleaseWithoutApk)
        assertNull(bundle.prefs.record)
    }

    // ── Download ───────────────────────────────────────────────────────────

    @Test
    fun downloadReportsProgressThenALocalVerifiedFile() {
        val bundle = newBundle()
        offerUpdate(bundle)
        var progressState: AppUpdateUiState? = null
        bundle.downloader.progressHook = { progressState = bundle.coordinator.state.value }

        bundle.coordinator.startDownload()

        assertTrue(progressState is AppUpdateUiState.Downloading)
        assertEquals(32L, (progressState as AppUpdateUiState.Downloading).downloadedBytes)

        val state = bundle.coordinator.state.value as AppUpdateUiState.Downloaded
        assertEquals("v0.2.0", state.release.tagName)
        assertTrue(state.file.isFile)
        assertEquals(4096L, state.file.length())
    }

    @Test
    fun aRunningDownloadBlocksDuplicatesChecksAndThenCancelsBackToTheOffer() {
        val bundle = newBundle()
        offerUpdate(bundle)
        bundle.downloader.hang = true
        bundle.coordinator.startDownload()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Downloading)

        // Duplicate download requests are ignored while one is active…
        bundle.coordinator.startDownload()
        assertEquals(1, bundle.downloader.started)
        // …and a check does not run mid-download either.
        val callsBefore = bundle.api.calls
        bundle.coordinator.checkForUpdates()
        assertEquals(callsBefore, bundle.api.calls)
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Downloading)

        // Cancel returns to the offer state and keeps the resumable partial.
        bundle.coordinator.cancelDownload()
        dispatcher.scheduler.advanceUntilIdle()
        val state = bundle.coordinator.state.value
        assertTrue(
            "expected UpdateAvailable after cancel but was ${state::class.simpleName}",
            state is AppUpdateUiState.UpdateAvailable
        )
        assertTrue(
            bundle.updatesDir.listFiles()!!.any { it.name.endsWith(".apk") && it.length() == 10L }
        )

        // And a fresh download can start.
        bundle.coordinator.startDownload()
        assertEquals(2, bundle.downloader.started)
    }

    @Test
    fun aNetworkFailureReportsARetryableDownloadFailed() {
        val bundle = newBundle()
        offerUpdate(bundle)
        bundle.downloader.factory = {
            ApkUpdateDownloader.Outcome.Failure(
                ApkUpdateDownloader.FailureKind.NETWORK,
                "connection died"
            )
        }
        bundle.coordinator.startDownload()
        val state = bundle.coordinator.state.value as AppUpdateUiState.DownloadFailed
        assertEquals(DownloadFailureKind.NETWORK, state.kind)
        assertEquals("connection died", state.detail)
        // Retry re-downloads.
        bundle.coordinator.startDownload()
        assertEquals(2, bundle.downloader.started)
    }

    @Test
    fun aFailedVerificationDeletesTheFileAndReportsVerify() {
        val bundle = newBundle(reader = wrongPackageMetadata())
        offerUpdate(bundle)
        bundle.coordinator.startDownload()
        val state = bundle.coordinator.state.value as AppUpdateUiState.DownloadFailed
        assertEquals(DownloadFailureKind.VERIFY, state.kind)
        // A rejected APK never stays on disk as an installable-looking file.
        assertTrue(bundle.updatesDir.listFiles()!!.none { it.name.endsWith(".apk") })
    }

    // ── Install & the permission gate ──────────────────────────────────────

    @Test
    fun installWithoutPermissionRoutesToSystemSettingsAndWaitsForTheGrant() {
        val bundle = newBundle(canInstall = false)
        offerUpdate(bundle)
        download(bundle)

        bundle.coordinator.installUpdate()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.InstallPermissionRequired)
        assertEquals(0, bundle.installer.installCalls)

        bundle.coordinator.openInstallPermissionSettings()
        assertEquals(1, bundle.installer.settingsCalls)

        // The user granted the permission in system settings and came back:
        // the gate re-opens and Install is offered again immediately.
        bundle.installer.canInstall = true
        bundle.coordinator.refreshPermissionGate()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Downloaded)

        bundle.coordinator.installUpdate()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Installing)
        assertEquals(1, bundle.installer.installCalls)
        assertEquals(4096L, bundle.installer.lastFile?.length())
    }

    @Test
    fun aMissingDownloadedFileBeforeInstallFailsWithVerify() {
        val bundle = newBundle()
        offerUpdate(bundle)
        download(bundle)
        (bundle.coordinator.state.value as AppUpdateUiState.Downloaded).file.delete()

        bundle.coordinator.installUpdate()
        val state = bundle.coordinator.state.value as AppUpdateUiState.DownloadFailed
        assertEquals(DownloadFailureKind.VERIFY, state.kind)
    }

    @Test
    fun thePlatformsVerdictAloneCompletesTheInstall() {
        val bundle = newBundle()
        offerUpdate(bundle)
        download(bundle)
        bundle.coordinator.installUpdate()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Installing)

        InstallStatusBus.publish(InstallStatus.Success)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.InstallComplete)
    }

    @Test
    fun aPlatformInstallFailureSurfacesAsInstallFailedAndRetries() {
        val bundle = newBundle()
        offerUpdate(bundle)
        download(bundle)
        bundle.coordinator.installUpdate()

        InstallStatusBus.publish(InstallStatus.Failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        dispatcher.scheduler.advanceUntilIdle()
        val state = bundle.coordinator.state.value as AppUpdateUiState.InstallFailed
        assertEquals(InstallFailureKind.GENERIC, state.kind)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", state.detail)

        // Retry re-launches the installer.
        bundle.coordinator.installUpdate()
        assertEquals(2, bundle.installer.installCalls)
    }

    @Test
    fun anInstallerLaunchFailureMapsToInstallFailedByKind() {
        val bundle = newBundle()
        offerUpdate(bundle)
        download(bundle)
        bundle.installer.result = UpdateInstaller.LaunchResult.FAILED_IO

        bundle.coordinator.installUpdate()
        val state = bundle.coordinator.state.value as AppUpdateUiState.InstallFailed
        assertEquals(InstallFailureKind.IO, state.kind)
        assertNotNull(state.file)
    }

    // ── Restore after process death ────────────────────────────────────────

    @Test
    fun aDownloadedUpdateSurvivesProcessRestartWithoutANetworkCall() {
        val sharedPrefs = FakePrefs()
        val sharedDir = tmp.newFolder("updates")
        val first = newBundle(prefs = sharedPrefs, updatesDir = sharedDir)
        offerUpdate(first)
        download(first)
        val originalFile = (first.coordinator.state.value as AppUpdateUiState.Downloaded).file

        // "Restart": a fresh coordinator over the same prefs + directory.
        val second = newBundle(prefs = sharedPrefs, updatesDir = sharedDir)
        val state = second.coordinator.state.value as AppUpdateUiState.Downloaded
        assertEquals("v0.2.0", state.release.tagName)
        assertEquals(originalFile.absolutePath, state.file.absolutePath)
        assertEquals(0, second.api.calls)
    }

    @Test
    fun anOfferedUpdateWithoutLocalApkRestoresAsAnOffer() {
        val sharedPrefs = FakePrefs().apply {
            record = AppUpdateReleaseRecord(release("v0.2.0"), asset)
        }
        val bundle = newBundle(prefs = sharedPrefs, updatesDir = tmp.newFolder("updates"))
        val state = bundle.coordinator.state.value
        assertTrue(state is AppUpdateUiState.UpdateAvailable)
        assertEquals("v0.2.0", (state as AppUpdateUiState.UpdateAvailable).release.tagName)
    }

    @Test
    fun anUntrackedApkIsRemovedOnRestoreAndTheCardStartsIdle() {
        val dir = tmp.newFolder("updates")
        File(dir, "stray-linexa.apk").writeBytes(ByteArray(16))

        val bundle = newBundle(prefs = FakePrefs(), updatesDir = dir)
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Idle)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun anUpdateAppliedWhileTheAppWasClosedCollapsesToIdleAndCleansUp() {
        val sharedPrefs = FakePrefs().apply {
            record = AppUpdateReleaseRecord(release("v0.2.0"), asset)
        }
        val dir = tmp.newFolder("updates")
        File(dir, "some-linexa.apk").writeBytes(ByteArray(16))

        val bundle = newBundle(prefs = sharedPrefs, updatesDir = dir, versionName = "0.2.0")
        assertTrue(bundle.coordinator.state.value is AppUpdateUiState.Idle)
        assertNull(sharedPrefs.record)
        assertEquals(0, dir.listFiles()!!.size)
    }

    // ── The state guards the buttons ride on ───────────────────────────────

    @Test
    fun stateGuardsDecideWhichControlsAreMeaningful() {
        val idle: AppUpdateUiState = AppUpdateUiState.Idle("0.1.0-beta", null)
        assertTrue(idle.canStartCheck)
        assertFalse(idle.canStartDownload)

        val checking: AppUpdateUiState = AppUpdateUiState.Checking("0.1.0-beta", null)
        assertFalse(checking.canStartCheck)
        assertFalse(checking.canStartDownload)

        val downloading: AppUpdateUiState = AppUpdateUiState.Downloading(
            "0.1.0-beta", null, release("v0.2.0"), asset, 32L, 4096L
        )
        assertFalse(downloading.canStartCheck)
        assertFalse(downloading.canStartDownload)

        val available: AppUpdateUiState = AppUpdateUiState.UpdateAvailable(
            "0.1.0-beta", null, release("v0.2.0"), asset
        )
        assertTrue(available.canStartDownload)
        assertTrue(available.canStartCheck)
    }
}
