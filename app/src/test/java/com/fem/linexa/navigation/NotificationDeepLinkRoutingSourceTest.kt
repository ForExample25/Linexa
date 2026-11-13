package com.fem.linexa.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 (Issues 4–7) — the notification → terminal navigation contract.
 *
 * Why a source test: the path spans Activity lifecycle (onCreate/onNewIntent),
 * PendingIntent flags, service-side notification rebuilds and compose
 * navigation — none of which a JVM-only harness (§1.124(a)) can execute. The
 * pins below capture the exact mechanisms the fix relies on, so a future edit
 * cannot silently reintroduce the flash:
 *
 *  1. **Same-destination pre-emption** — when the deep link targets the
 *     workspace the user is already on, `onNewIntent` delivers through
 *     [TerminalDeepLinkBus] and RETURNS, so `NavController.handleDeepLink`
 *     (the pop/replace/recreate path) is never reached.
 *  2. **No activity-owned background override** — the removed
 *     `setBackgroundDrawable` patch must stay removed: the window background
 *     is the theme's job, and a hardcoded terminal-black pre-frame is the
 *     light-theme flash root cause.
 *  3. **Launch flags** — the Open PendingIntent targets MainActivity with
 *     `NEW_TASK | SINGLE_TOP | CLEAR_TOP` (reuse + onNewIntent, never a second
 *     activity instance) and `UPDATE_CURRENT | IMMUTABLE` (targetSdk 34).
 *  4. **Fresh Open target** — the service rebuilds the notification when the
 *     last-active terminal changes, so the embedded `terminalId` is the
 *     terminal the user is actually viewing (a stale target turned a
 *     same-destination tap into a session switch).
 *  5. **In-place retarget** — the workspace graph applies bus requests without
 *     navigation when already on target, and surfaces them with exactly one
 *     deterministic pop when the user is inside a sub-destination.
 */
class NotificationDeepLinkRoutingSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
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

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val mainActivity: String
        get() = source("com/fem/linexa/MainActivity.kt")

    private val runtimeService: String
        get() = source("com/fem/linexa/service/runtime/RuntimeService.kt")

    private val terminalManager: String
        get() = source("com/fem/linexa/service/runtime/WorkspaceTerminalManager.kt")

    private val workspaceNavGraph: String
        get() = source("com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")

    private val notificationContent: String
        get() = source("com/fem/linexa/service/runtime/RuntimeNotificationContent.kt")

    @Test
    fun sameWorkspaceDeepLinksArePreEmptedToTheBusBeforeHandleDeepLink() {
        val onNewIntent = mainActivity.substringAfter("override fun onNewIntent(")
            .substringBefore("\n    /**")
        val busEmit = onNewIntent.indexOf("TerminalDeepLinkBus.emit(")
        val handleDeepLink = onNewIntent.indexOf("handleDeepLink(")
        assertTrue("the bus delivery must exist in onNewIntent", busEmit >= 0)
        assertTrue("the fallback deep-link delivery must exist", handleDeepLink >= 0)
        assertTrue(
            "the same-workspace check must pre-empt nav-compose: the bus emit " +
                "must precede handleDeepLink in onNewIntent",
            busEmit < handleDeepLink
        )
        assertTrue(
            "the pre-empted path must RETURN — handleDeepLink must not also run " +
                "for the same-workspace case",
            onNewIntent.contains("TerminalDeepLinkBus.emit(deepWorkspaceId, deepTerminalId)\n                return")
        )
    }

    @Test
    fun noActivityOwnedWindowBackgroundOverrideOnLaunch() {
        assertFalse(
            "MainActivity must not paint a hardcoded terminal-black window " +
                "background for deep-link launches — the theme's own " +
                "windowBackground is the pre-frame surface (the old patch was " +
                "the light-theme black flash)",
            mainActivity.contains("setBackgroundDrawable")
        )
        assertFalse(
            "and it must not import Haven colors to do it",
            mainActivity.contains("ui.theme.haven") || mainActivity.contains("HavenColors")
        )
    }

    @Test
    fun theOpenPendingIntentReusesTheExistingActivity() {
        val openIntentBlock = runtimeService.substringAfter("val openIntent = Intent(this, MainActivity::class.java)")
            .substringBefore("val openPending")
        assertTrue(
            "NEW_TASK | SINGLE_TOP | CLEAR_TOP — the existing MainActivity is " +
                "reused and a warm tap becomes onNewIntent, never a recreation",
            openIntentBlock.contains("Intent.FLAG_ACTIVITY_NEW_TASK") &&
                openIntentBlock.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP") &&
                openIntentBlock.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP")
        )
        assertTrue(
            "UPDATE_CURRENT + IMMUTABLE — the same request code always carries " +
                "the latest deep link, and the PendingIntent cannot be mutated",
            runtimeService.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE")
        )
        assertFalse(
            "no full-screen intent (that would steal the screen instead of " +
                "navigating the existing one)",
            runtimeService.contains("setFullScreenIntent")
        )
    }

    @Test
    fun theOpenDeepLinkAlwaysCarriesTheActiveTerminal() {
        assertTrue(
            "the notification snapshot reads lastActiveTerminalId — the Open " +
                "target is the terminal the user is viewing",
            runtimeService.contains(
                "openTerminalId = workspaceTerminalManager.lastActiveTerminalId(workspaceId)"
            )
        )
        assertTrue(
            "the service must rebuild the notification when the last-active " +
                "terminal changes (the stale-target fix)",
            runtimeService.contains("observeLastActiveTerminalForNotification") &&
                runtimeService.contains("lastActiveTerminals.collect")
        )
        assertTrue(
            "the manager must expose the last-active record as a StateFlow",
            terminalManager.contains("val lastActiveTerminals: StateFlow<Map<String, String>>")
        )
    }

    @Test
    fun theWorkspaceGraphAppliesBusRequestsWithoutBackStackChurn() {
        val busCollector = workspaceNavGraph.substringAfter("TerminalDeepLinkBus.request.collect")
            .substringBefore("\n            NavHost(")
        assertTrue(
            "the retarget writes are guarded — an already-on-target request " +
                "writes nothing (the pure no-op contract)",
            busCollector.contains("if (target != activeTerminalId)") &&
                busCollector.contains("if (!terminalOpen)")
        )
        assertTrue(
            "the request is consumed after applying, so a re-subscribe cannot " +
                "replay it",
            busCollector.contains("TerminalDeepLinkBus.consume()")
        )
        assertTrue(
            "a request arriving inside a sub-destination surfaces the retargeted " +
                "overlay with one deterministic popBackStack",
            busCollector.contains("navController.popBackStack(WorkspaceRoutes.SHORTCUTS")
        )
    }

    @Test
    fun theDeepLinkTargetsTheWorkspaceRouteWithTheTerminalId() {
        assertTrue(
            "the Open deep link carries ?terminalId= so the tap lands on the " +
                "terminal, not the workspace main screen",
            notificationContent.contains("openDeepLink(workspaceId: String, terminalId: String)") &&
                notificationContent.contains("?terminalId=")
        )
    }
}
