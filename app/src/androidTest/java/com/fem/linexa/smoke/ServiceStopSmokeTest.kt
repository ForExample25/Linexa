package com.fem.linexa.smoke

import android.app.ActivityManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fem.linexa.service.runtime.RuntimeService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 10B (OPT-TEST-002) smoke row 5: `RuntimeService` started with
 * [RuntimeService.ACTION_STOP] and an EXTRA_WORKSPACE_ID that names no real
 * workspace must survive the no-op stop (an explicit `return
 * START_NOT_STICKY` / safe `stopWorkspace` miss at RuntimeService.kt:250-251)
 * and then be destroyed cleanly by [android.content.Context.stopService] —
 * exercising the real `onDestroy` without any fake runtime.
 *
 * Destruction is observed through `ActivityManager.getRunningServices`, which
 * is deprecated but still functional for the CALLING app's own services
 * (exactly what an instrumentation of com.fem.linexa needs). A service that
 * self-stops during the start phase makes the interim "was running" state
 * best-effort; the contract this test pins is the FINAL state (not running)
 * plus "no exception anywhere along the way".
 */
@RunWith(AndroidJUnit4::class)
class ServiceStopSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runtimeServiceRunning(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.getRunningServices(200)
            .any { it.service.className == RuntimeService::class.java.name }
    }

    private fun pollUntil(timeoutMs: Long, want: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runtimeServiceRunning() == want) return true
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(200)
        }
        return runtimeServiceRunning() == want
    }

    @Test
    fun aStopForANonexistentWorkspaceDestroysTheServiceCleanly() {
        val intent = Intent(context, RuntimeService::class.java).apply {
            action = RuntimeService.ACTION_STOP
            putExtra(RuntimeService.EXTRA_WORKSPACE_ID, "smoke-nonexistent")
        }

        context.startService(intent)
        // Best-effort: the service is expected to appear; a fast self-stop is
        // tolerated (see class KDoc) because the start itself must not throw.
        pollUntil(5_000, want = true)

        context.stopService(intent)
        assertTrue(
            "RuntimeService must be destroyed after stopService (ACTION_STOP, " +
                "no active workspace)",
            pollUntil(10_000, want = false)
        )
        assertFalse(
            "no runtime service instance may survive the smoke stop",
            runtimeServiceRunning()
        )
    }
}
