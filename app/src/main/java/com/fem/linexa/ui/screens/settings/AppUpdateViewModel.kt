package com.fem.linexa.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.fem.linexa.domain.version.AppVersionProvider
import com.fem.linexa.service.update.AppUpdateCoordinator
import com.fem.linexa.service.update.AppUpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The Application Updates section's ViewModel — a thin, lifecycle-aware
 * window over the process-wide [AppUpdateCoordinator].
 *
 * The coordinator is a `@Singleton` and owns ALL state and transitions, so
 * this class deliberately adds nothing: no state of its own, no scheduling,
 * no caching. That is what keeps the update flow consistent across screen
 * recreation and navigation (the download continues while the user is
 * elsewhere) while the ViewModel remains the compose-world's lifecycle-aware
 * access point.
 *
 * `versionLabel` is seeded synchronously from [AppVersionProvider]
 * (`BuildConfig`-backed) because the card's current-version row must be
 * correct on the first frame, not one observation later.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val coordinator: AppUpdateCoordinator,
    appVersionProvider: AppVersionProvider
) : ViewModel() {

    /** The update card's complete state machine (collected with collectAsState). */
    val state: StateFlow<AppUpdateUiState> = coordinator.state

    /** The persisted pre-release channel preference. */
    val includePrereleases: StateFlow<Boolean> = coordinator.includePrereleases

    /** "0.1.0-beta (1)" — the installed build, the section's identity row. */
    val versionLabel: String = AppVersionLabel.format(
        versionName = appVersionProvider.versionName,
        versionCode = appVersionProvider.versionCode
    )

    fun checkForUpdates() = coordinator.checkForUpdates()

    fun startDownload() = coordinator.startDownload()

    fun cancelDownload() = coordinator.cancelDownload()

    fun installUpdate() = coordinator.installUpdate()

    /** Open Android's per-app "install unknown apps" page. */
    fun openInstallPermissionSettings() = coordinator.openInstallPermissionSettings()

    /** Re-evaluate the install-permission gate when the screen resumes. */
    fun refreshOnResume() = coordinator.refreshPermissionGate()

    /** Toggle the pre-release channel; persisted, with an automatic re-check. */
    fun setIncludePrereleases(enabled: Boolean) = coordinator.setIncludePrereleases(enabled)
}
