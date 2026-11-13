package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.installer.AppInstallJobLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherInstallPolicyTest {

    private fun app() = LauncherApp(
        id = "builtin:python", name = "Python", description = null, iconKey = null,
        category = null, launchCommand = "python3", installCommand = "apt-get install -y python3",
        removeCommand = "apt-get remove -y python3",
        verificationPackages = listOf("python3"), verificationExecutables = emptyList(),
        launchAsRoot = false
    )

    private fun job(
        operation: AppInstallCoordinator.JobState.Operation =
            AppInstallCoordinator.JobState.Operation.INSTALL,
        phase: AppInstallCoordinator.JobState.Phase =
            AppInstallCoordinator.JobState.Phase.INSTALLING,
        result: AppInstallCoordinator.JobState.Result? = null,
        message: String? = null
    ): AppInstallCoordinator.JobState {
        val started = AppInstallJobLifecycle.started("job-1", app(), operation)
        val phased = AppInstallJobLifecycle.withPhase(started, phase)
        return if (result == null) {
            phased
        } else {
            AppInstallJobLifecycle.finished(phased, result, message)
        }
    }

    @Test
    fun `installed app offers open and never install`() {
        val installed = AppInstallTracker.InstalledState.INSTALLED
        assertEquals(
            LauncherInstallPolicy.SheetAction.OPEN,
            LauncherInstallPolicy.sheetAction(installed, null, probing = false)
        )
        assertFalse(LauncherInstallPolicy.canStartInstall(installed, null))
    }

    @Test
    fun `active job shows progress and rejects a duplicate install`() {
        val running = job()
        assertEquals(
            LauncherInstallPolicy.SheetAction.PROGRESS,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                running,
                probing = false
            )
        )
        assertFalse(
            LauncherInstallPolicy.canStartInstall(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                running
            )
        )
        assertTrue(LauncherInstallPolicy.showsProgress(running))
        assertEquals(
            LauncherInstallPolicy.TileStatus.INSTALLING,
            LauncherInstallPolicy.tileStatus(AppInstallTracker.InstalledState.NOT_INSTALLED, running)
        )
    }

    @Test
    fun `verified terminal install wins over a stale not-installed state`() {
        val installedJob = job(result = AppInstallCoordinator.JobState.Result.INSTALLED)
        assertEquals(
            LauncherInstallPolicy.SheetAction.OPEN,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                installedJob,
                probing = false
            )
        )
        assertFalse(
            LauncherInstallPolicy.canStartInstall(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                installedJob
            )
        )
        assertEquals(
            LauncherInstallPolicy.TileStatus.INSTALLED,
            LauncherInstallPolicy.tileStatus(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                installedJob
            )
        )
        assertFalse(LauncherInstallPolicy.showsProgress(installedJob))
    }

    @Test
    fun `failed install is retryable and never renders as installed`() {
        val failed = job(
            result = AppInstallCoordinator.JobState.Result.FAILED,
            message = "partial install"
        )
        assertEquals(
            LauncherInstallPolicy.TileStatus.FAILED,
            LauncherInstallPolicy.tileStatus(AppInstallTracker.InstalledState.NOT_INSTALLED, failed)
        )
        assertEquals(
            LauncherInstallPolicy.SheetAction.INSTALL,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                failed,
                probing = false
            )
        )
        assertTrue(
            LauncherInstallPolicy.canStartInstall(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                failed
            )
        )
        assertFalse(LauncherInstallPolicy.showsProgress(failed))
    }

    @Test
    fun `cancelled install is explicit and retryable`() {
        val cancelled = job(result = AppInstallCoordinator.JobState.Result.CANCELLED)
        assertEquals(
            LauncherInstallPolicy.TileStatus.CANCELLED,
            LauncherInstallPolicy.tileStatus(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                cancelled
            )
        )
        assertTrue(
            LauncherInstallPolicy.canStartInstall(
                AppInstallTracker.InstalledState.NOT_INSTALLED,
                cancelled
            )
        )
    }

    @Test
    fun `failed uninstall keeps the app installed`() {
        val failedRemove = job(
            operation = AppInstallCoordinator.JobState.Operation.REMOVE,
            phase = AppInstallCoordinator.JobState.Phase.VERIFYING,
            result = AppInstallCoordinator.JobState.Result.FAILED
        )
        assertEquals(
            LauncherInstallPolicy.TileStatus.INSTALLED,
            LauncherInstallPolicy.tileStatus(AppInstallTracker.InstalledState.INSTALLED, failedRemove)
        )
        assertEquals(
            LauncherInstallPolicy.SheetAction.OPEN,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.INSTALLED,
                failedRemove,
                probing = false
            )
        )
    }

    @Test
    fun `active removal and verification have distinct statuses`() {
        val removing = job(operation = AppInstallCoordinator.JobState.Operation.REMOVE)
        assertEquals(
            LauncherInstallPolicy.TileStatus.REMOVING,
            LauncherInstallPolicy.tileStatus(AppInstallTracker.InstalledState.INSTALLED, removing)
        )
        val verifying = job(phase = AppInstallCoordinator.JobState.Phase.VERIFYING)
        assertEquals(
            LauncherInstallPolicy.TileStatus.VERIFYING,
            LauncherInstallPolicy.tileStatus(AppInstallTracker.InstalledState.NOT_INSTALLED, verifying)
        )
    }

    @Test
    fun `unknown state waits for the probe before offering install`() {
        assertEquals(
            LauncherInstallPolicy.SheetAction.CHECKING,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.UNKNOWN,
                null,
                probing = true
            )
        )
        assertEquals(
            LauncherInstallPolicy.SheetAction.INSTALL,
            LauncherInstallPolicy.sheetAction(
                AppInstallTracker.InstalledState.UNKNOWN,
                null,
                probing = false
            )
        )
    }
}
