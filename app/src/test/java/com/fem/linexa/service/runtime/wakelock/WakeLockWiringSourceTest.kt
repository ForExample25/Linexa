package com.fem.linexa.service.runtime.wakelock

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.142 structural guard: the wake lock is actually reachable, actually
 * permitted, and released from the one path every stop funnels through.
 *
 * This is the §1.19 defect class applied to a feature where the failure is
 * invisible in the worst direction. [WorkspaceWakeLockManager] and
 * [AndroidWakeLock] can both be perfectly correct while:
 *
 *  - the manifest has no `WAKE_LOCK` permission, so every acquire silently
 *    returns false and the feature just never works;
 *  - nothing adds the notification action, so there is no way to take a lock at
 *    all (the manager compiles, its tests pass, the user has no affordance);
 *  - the release is wired somewhere *other* than `stopWorkspaceLocked`, so one
 *    stop trigger leaves the CPU awake — which no screenshot and no unit test of
 *    the manager alone can reveal;
 *  - the manager is provided per-injection instead of as a singleton, so two
 *    instances track separate holders against one platform lock.
 *
 * Asserted against module source for the same reason as
 * `DebugInfoWiringSourceTest`/`AboutWiringSourceTest`: the pieces that must be
 * connected are a manifest, a Hilt module, a `Service` and a `PowerManager` —
 * none of which this repo can instantiate in a JVM test (no Robolectric,
 * §1.124(a)).
 */
class WakeLockWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/service/runtime/wakelock/WakeLockPolicy.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val manifest: String
        get() = source("src/main/AndroidManifest.xml")
    private val appModule: String
        get() = source("src/main/java/com/fem/linexa/di/AppModule.kt")
    private val service: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")
    private val lifecycleManager: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/RuntimeLifecycleManager.kt")
    private val androidWakeLock: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/wakelock/AndroidWakeLock.kt")

    // ── the permission ──────────────────────────────────────────────────────

    @Test
    fun theManifestDeclaresTheWakeLockPermission() {
        assertTrue(
            "AndroidManifest must declare WAKE_LOCK — without it every acquire " +
                "silently fails and the feature never works, with no error anywhere",
            manifest.contains("android.permission.WAKE_LOCK")
        )
    }

    // ── the lock itself ─────────────────────────────────────────────────────

    @Test
    fun theLockIsPartialNotAScreenLock() {
        assertTrue(
            "the lock must be PARTIAL_WAKE_LOCK: it keeps the CPU alive with the " +
                "screen off, which is the whole point (a screen lock would burn the display)",
            androidWakeLock.contains("PARTIAL_WAKE_LOCK")
        )
    }

    @Test
    fun theLockIsNotReferenceCounted() {
        assertTrue(
            "setReferenceCounted(false) is required for HostWakeLock's idempotent " +
                "release: with counting on, one missed release among the several " +
                "release paths keeps the CPU awake",
            androidWakeLock.contains("setReferenceCounted(false)")
        )
    }

    @Test
    fun theLockIsTaggedSoALeakIsAttributable() {
        assertTrue(
            "the wake lock tag must name Linexa so a hold is identifiable in dumpsys power",
            androidWakeLock.contains("linexa:")
        )
    }

    @Test
    fun thePlatformAcquireCarriesATimeoutBackstop() {
        assertTrue(
            "the platform acquire must take a timeout — if this process never runs " +
                "its own expiry coroutine (frozen, scope cancelled), it is the only " +
                "thing left that can release the lock",
            Regex("""wakeLock\.acquire\(timeoutMs\)""").containsMatchIn(androidWakeLock)
        )
    }

    // ── one instance, one holder ─────────────────────────────────────────────

    @Test
    fun theManagerIsProvidedAsASingleton() {
        val provider = appModule.substringAfter("fun provideWorkspaceWakeLockManager")
        assertTrue(
            "WorkspaceWakeLockManager must be @Singleton: two instances would track " +
                "separate holders against ONE platform lock, so a release by either " +
                "drops the other's hold while its bookkeeping still claims it",
            appModule.substringBefore("fun provideWorkspaceWakeLockManager")
                .takeLast(200).contains("@Singleton") && provider.isNotEmpty()
        )
    }

    @Test
    fun theHostLockIsProvidedAsASingleton() {
        assertTrue(
            "HostWakeLock must be a singleton too — a second PowerManager.WakeLock " +
                "object is a second lock the manager does not know about",
            appModule.substringBefore("fun provideHostWakeLock").takeLast(200).contains("@Singleton")
        )
    }

    @Test
    fun theManagerDoesNotUseTheServiceScope() {
        assertFalse(
            "the expiry timer must not live on RuntimeService's scope: onDestroy " +
                "cancels it, which would cancel the timer of a hold the platform still holds",
            appModule.contains("scope = serviceScope")
        )
        assertTrue(
            "the manager needs its own long-lived scope",
            appModule.substringAfter("fun provideWorkspaceWakeLockManager").contains("CoroutineScope(")
        )
    }

    // ── the user can actually take one ───────────────────────────────────────

    @Test
    fun theNotificationOffersTheWakeLockAction() {
        assertTrue(
            "RuntimeService must add the wake-lock action to the FGS notification — " +
                "without it there is no affordance and the feature is unreachable",
            service.contains("wakeLockActionLabel")
        )
        assertTrue(
            "the action needs a PendingIntent built from toggleWakeLockIntent",
            service.contains("toggleWakeLockIntent(this, workspaceId)")
        )
    }

    @Test
    fun theServiceHandlesTheToggleAction() {
        assertTrue(
            "onStartCommand must handle ACTION_TOGGLE_WAKE_LOCK, or the action is " +
                "a button that does nothing",
            service.contains("ACTION_TOGGLE_WAKE_LOCK ->")
        )
        assertTrue(
            "the toggle must reach WorkspaceWakeLockManager.toggle",
            service.contains("wakeLockManager.toggle(workspaceId)")
        )
    }

    @Test
    fun theToggleIsRefusedForAnInactiveWorkspace() {
        val branch = service.substringAfter("ACTION_TOGGLE_WAKE_LOCK ->").substringBefore("updateNotification")
        assertTrue(
            "a stale notification (an already-stopped workspace still on screen) " +
                "must not be able to take a lock for a workspace with no guest to keep awake",
            branch.contains("activeWorkspaceId.value == workspaceId")
        )
    }

    @Test
    fun theNotificationIsRefreshedAfterAToggle() {
        val branch = service.substringAfter("ACTION_TOGGLE_WAKE_LOCK ->").substringBefore("\n            }\n        }")
        assertTrue(
            "the action's own label reports the hold, so it must be re-rendered " +
                "after the toggle or it shows the previous state",
            branch.contains("updateNotification(workspaceId)")
        )
    }

    // ── every stop lets go ──────────────────────────────────────────────────

    @Test
    fun theReleaseIsWiredIntoTheOneTeardownPath() {
        val teardown = lifecycleManager.substringAfter("private suspend fun stopWorkspaceLocked")
        assertTrue(
            "the release must be in stopWorkspaceLocked — it is the ONE path every " +
                "stop trigger funnels through (ACTION_STOP, notification Stop, " +
                "workspace leave, gate eviction, delete). Releasing anywhere else " +
                "means some trigger leaves a hold behind",
            teardown.contains("wakeLockManager.releaseFor(workspaceId)")
        )
    }

    @Test
    fun theTeardownReleaseIsInAFinallyBlock() {
        val teardown = lifecycleManager.substringAfter("private suspend fun stopWorkspaceLocked")
            .substringBefore("\n    companion object")
        val finallyBlock = teardown.substringAfter("} finally {", "")
        assertTrue(
            "the release must be in a `finally`: the early-return no-op and the " +
                "timed-out failure are exit paths too, and a wedged teardown that " +
                "kept the CPU awake is this feature's worst case",
            finallyBlock.contains("wakeLockManager.releaseFor(workspaceId)")
        )
    }

    @Test
    fun shutdownReleasesUnconditionally() {
        val shutdown = lifecycleManager.substringAfter("suspend fun shutdown()").substringBefore("\n    }")
        assertTrue(
            "shutdown() must call releaseAll(): a hold whose workspace has no " +
                "session entry left is iterated past by the per-session loop, and " +
                "the process is going away so nothing else would release it",
            shutdown.contains("wakeLockManager.releaseAll()")
        )
    }

    @Test
    fun theServiceOwnsNoReleaseLogicOfItsOwn() {
        assertFalse(
            "RuntimeService must not release directly — a stop trigger that never " +
                "reaches this service (a leave, an eviction) must still let go, so " +
                "the release belongs to RuntimeLifecycleManager alone",
            service.contains("wakeLockManager.releaseFor") || service.contains("wakeLockManager.releaseAll")
        )
    }

    @Test
    fun nothingAcquiresALockOutsideTheUsersToggle() {
        // Opt-in per hold: a Running workspace is not a reason to keep the CPU
        // awake. The only acquire call site is the manager's own internal one.
        assertFalse(
            "RuntimeService must not acquire a lock on its own (e.g. on start)",
            service.contains("wakeLockManager.acquire")
        )
        assertFalse(
            "the lifecycle manager must never take a lock — starting a workspace " +
                "is not a request to keep the phone awake",
            lifecycleManager.contains("wakeLockManager.toggle")
        )
    }
}
