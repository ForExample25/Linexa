package com.fem.linexa.audit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.domain.launcher.LauncherApps
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.installer.AppInstallProbe
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeService
import com.fem.linexa.ui.screens.workspace.AppInstallTracker
import com.fem.linexa.ui.screens.workspace.InstalledStates
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DRA-004 closure: device-verifies the install-state probe scheduler against
 * the REAL guest package manager, without UI automation.
 *
 * Asserts:
 *  - one real probe per corpus publication (not three),
 *  - non-forced refresh inside 5 s does not probe,
 *  - non-forced refresh while a probe is in flight does not probe,
 *  - after 5 s a tab-style refresh probes again,
 *  - a changed corpus forces a probe and the state map matches the guest's
 *    installed set for every package-verified app,
 *  - an unchanged corpus does not probe.
 *
 * Safety: marker-gated like `TerminalLatencyAuditTest`.
 */
@RunWith(AndroidJUnit4::class)
class InstallProbeThrottleAuditTest {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AuditEntryPoint {
        fun installCoordinator(): AppInstallCoordinator
        fun installProbe(): AppInstallProbe
        fun quickCommandsResolver(): QuickCommandsResolver
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probeThrottleAndCorrectness() = runBlocking {
        val fixture = fixtureWorkspace()
        assumeTrue(
            "skip: no workspace with .linexa-audit-fixture under files/workspaces",
            fixture != null
        )
        val workspaceId = fixture!!.name
        val ep = EntryPointAccessors.fromApplication(context, AuditEntryPoint::class.java)
        val apps = LauncherApps.fromCatalog(ep.quickCommandsResolver().resolve(null, null))
        assumeTrue("skip: portable catalog produced no apps", apps.isNotEmpty())

        val binder = bindRuntime()
        val initiallyRunning = binder.getState(workspaceId) is RuntimeState.Running
        if (!initiallyRunning) {
            context.startService(RuntimeService.startIntent(context, workspaceId))
            awaitWorkspaceRunning(binder, workspaceId)
        }

        val tracker = AppInstallTracker(ep.installCoordinator(), ep.installProbe())
        try {
            tracker.attach(workspaceId, binder)
            tracker.setApps(apps)
            awaitIdle(tracker, expected = 1)
            metric("initial_probe_count=${tracker.probeCountForTest()} apps=${apps.size} states=${tracker.states.value.size}")
            assertEquals("attach(empty corpus) + first setApps must be exactly one guest probe", 1L, tracker.probeCountForTest())

            // 1. Inside the 5 s window: no probe.
            tracker.refresh()
            tracker.refresh()
            delay(150)
            metric("after_rapid_refresh_count=${tracker.probeCountForTest()}")
            assertEquals("non-forced refresh inside 5 s must not probe", 1L, tracker.probeCountForTest())

            // 2. In-flight dedupe: force starts one, non-forced joins/skips.
            tracker.refresh(force = true)
            tracker.refresh()
            delay(50)
            metric("inflight_count=${tracker.probeCountForTest()}")
            assertEquals("non-forced refresh while in flight must not start another probe", 2L, tracker.probeCountForTest())
            awaitIdle(tracker, expected = 2)

            // 3. After the throttle window: a tab-style refresh probes again.
            delay(5_200)
            tracker.refresh()
            awaitIdle(tracker, expected = 3)
            metric("after_window_count=${tracker.probeCountForTest()}")
            assertEquals("after 5 s the tab refresh must probe", 3L, tracker.probeCountForTest())

            // 4. Changed corpus forces a probe; unchanged corpus does not.
            val half = apps.take(max(1, apps.size / 2))
            tracker.setApps(half)
            awaitIdle(tracker, expected = 4)
            assertEquals("changed corpus must force exactly one probe", 4L, tracker.probeCountForTest())
            tracker.setApps(half)
            delay(200)
            assertEquals("unchanged corpus must not probe", 4L, tracker.probeCountForTest())
            metric("corpus_change_count=${tracker.probeCountForTest()} states=${tracker.states.value.size}")

            // 5. Correctness: package-verified apps must match the guest's own
            //    installed set through the same documented merge rule.
            val installed = ep.installProbe().installedNames(binder, workspaceId)
            assertTrue("the guest package probe must return a set", installed != null)
            val expected = InstalledStates.mergeStates(half, installed, emptyMap())
            var checked = 0
            half.filter { it.verificationPackages.isNotEmpty() }.forEach { app ->
                assertEquals(
                    "state for ${app.name} (${app.verificationPackages})",
                    expected[app.id],
                    tracker.states.value[app.id]
                )
                checked++
            }
            metric("package_verified_apps_checked=$checked installed_packages=${installed!!.size}")

            val installedHit = half.firstOrNull { app ->
                app.verificationPackages.any { it in installed }
            }
            val installedMiss = half.firstOrNull { app ->
                app.verificationPackages.isNotEmpty() && app.verificationPackages.none { it in installed }
            }
            installedHit?.let {
                assertEquals(
                    AppInstallTracker.InstalledState.INSTALLED,
                    tracker.states.value[it.id]
                )
                metric("known_installed_app=${it.name} packages=${it.verificationPackages}")
            }
            installedMiss?.let {
                assertEquals(
                    AppInstallTracker.InstalledState.NOT_INSTALLED,
                    tracker.states.value[it.id]
                )
                metric("known_absent_app=${it.name} packages=${it.verificationPackages}")
            }
            metric("throttle_audit=PASS")
        } finally {
            tracker.detach()
            runCatching { context.unbindService(connection) }
            if (!initiallyRunning) {
                context.startService(RuntimeService.stopIntent(context, workspaceId))
            }
        }
    }

    private fun metric(line: String) = android.util.Log.i("AUDIT_METRIC", line)

    private suspend fun awaitIdle(tracker: AppInstallTracker, expected: Long) {
        withTimeout(60_000) {
            while (tracker.probeCountForTest() < expected || tracker.probing.value) {
                delay(50)
            }
            // Let the final state publication land after `probing=false`.
            delay(100)
        }
    }

    private fun fixtureWorkspace(): File? =
        File(context.filesDir, "workspaces").listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, ".linexa-audit-fixture").exists() }

    private var binder: RuntimeBinder? = null
    private var connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RuntimeBinder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }
    private val connected = CountDownLatch(1)

    private fun bindRuntime(): RuntimeBinder {
        val intent = Intent(context, RuntimeService::class.java)
        assertTrue(
            "RuntimeService must bind",
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        )
        assertTrue(
            "RuntimeService did not return its binder within 15 s",
            connected.await(15, TimeUnit.SECONDS)
        )
        return requireNotNull(binder)
    }

    private suspend fun awaitWorkspaceRunning(binder: RuntimeBinder, workspaceId: String) {
        withTimeout(90_000) {
            while (binder.getState(workspaceId) !is RuntimeState.Running) delay(100)
        }
    }
}
