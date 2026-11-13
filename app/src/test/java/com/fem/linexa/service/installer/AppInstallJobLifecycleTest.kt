package com.fem.linexa.service.installer

import com.fem.linexa.domain.launcher.LauncherApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallJobLifecycleTest {

    private fun app() = LauncherApp(
        id = "builtin:python", name = "Python", description = null, iconKey = null,
        category = null, launchCommand = "python3", installCommand = "apt-get install -y python3",
        removeCommand = "apt-get remove -y python3",
        verificationPackages = listOf("python3"), verificationExecutables = emptyList(),
        launchAsRoot = false
    )

    @Test
    fun `job starts queued and active`() {
        val state = AppInstallJobLifecycle.started(
            "job-1",
            app(),
            AppInstallCoordinator.JobState.Operation.INSTALL
        )
        assertEquals("job-1", state.jobId)
        assertEquals(AppInstallCoordinator.JobState.Phase.QUEUED, state.phase)
        assertNull(state.result)
        assertTrue(state.isActive)
        assertFalse(state.isTerminal)
        assertTrue(AppInstallJobLifecycle.showsProgress(state))
    }

    @Test
    fun `active job moves through installing verifying then installed`() {
        val queued = AppInstallJobLifecycle.started(
            "job-1",
            app(),
            AppInstallCoordinator.JobState.Operation.INSTALL
        )
        val installing = AppInstallJobLifecycle.withPhase(
            queued,
            AppInstallCoordinator.JobState.Phase.INSTALLING
        )
        val withOutput = AppInstallJobLifecycle.withOutput(installing, "Reading package lists…")
        val verifying = AppInstallJobLifecycle.withPhase(
            withOutput,
            AppInstallCoordinator.JobState.Phase.VERIFYING
        )
        val done = AppInstallJobLifecycle.finished(
            verifying,
            AppInstallCoordinator.JobState.Result.INSTALLED
        )

        assertEquals(AppInstallCoordinator.JobState.Phase.INSTALLING, installing.phase)
        assertEquals("Reading package lists…", withOutput.output)
        assertEquals(AppInstallCoordinator.JobState.Phase.VERIFYING, verifying.phase)
        assertEquals(AppInstallCoordinator.JobState.Result.INSTALLED, done.result)
        assertFalse(done.isActive)
        assertTrue(done.isTerminal)
        assertFalse(AppInstallJobLifecycle.showsProgress(done))
    }

    @Test
    fun `failed terminal retains failure message and stops progress`() {
        val running = AppInstallJobLifecycle.started(
            "job-1",
            app(),
            AppInstallCoordinator.JobState.Operation.INSTALL
        )
        val failed = AppInstallJobLifecycle.finished(
            running,
            AppInstallCoordinator.JobState.Result.FAILED,
            "verification failed"
        )
        assertEquals(AppInstallCoordinator.JobState.Result.FAILED, failed.result)
        assertEquals("verification failed", failed.message)
        assertFalse(failed.isActive)
        assertFalse(AppInstallJobLifecycle.showsProgress(failed))
    }

    @Test
    fun `cancellation is an explicit terminal state`() {
        val running = AppInstallJobLifecycle.started(
            "job-1",
            app(),
            AppInstallCoordinator.JobState.Operation.REMOVE
        )
        val cancelled = AppInstallJobLifecycle.cancelled(running)
        assertEquals(AppInstallCoordinator.JobState.Result.CANCELLED, cancelled.result)
        assertFalse(cancelled.isActive)
        assertFalse(AppInstallJobLifecycle.showsProgress(cancelled))
    }

    @Test
    fun `the first terminal result wins`() {
        val running = AppInstallJobLifecycle.started(
            "job-1",
            app(),
            AppInstallCoordinator.JobState.Operation.INSTALL
        )
        val installed = AppInstallJobLifecycle.finished(
            running,
            AppInstallCoordinator.JobState.Result.INSTALLED
        )
        val lateFailure = AppInstallJobLifecycle.finished(
            installed,
            AppInstallCoordinator.JobState.Result.FAILED,
            "late exception"
        )
        assertEquals(AppInstallCoordinator.JobState.Result.INSTALLED, lateFailure.result)
        assertNull(lateFailure.message)
    }

    @Test
    fun `terminal job ignores later progress lines and phase changes`() {
        val terminal = AppInstallJobLifecycle.finished(
            AppInstallJobLifecycle.started(
                "job-1",
                app(),
                AppInstallCoordinator.JobState.Operation.INSTALL
            ),
            AppInstallCoordinator.JobState.Result.FAILED,
            "exit 1"
        )
        val afterOutput = AppInstallJobLifecycle.withOutput(terminal, "still running?")
        val afterPhase = AppInstallJobLifecycle.withPhase(
            afterOutput,
            AppInstallCoordinator.JobState.Phase.INSTALLING
        )
        assertEquals("", afterOutput.output)
        assertEquals(AppInstallCoordinator.JobState.Phase.QUEUED, afterPhase.phase)
        assertEquals(AppInstallCoordinator.JobState.Result.FAILED, afterPhase.result)
    }
}
