package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.166 — structural source-invariant test for the notification re-check.
 *
 * The defect being guarded against is *the absence of a second trigger*: the
 * §W6 recovery for a late POST_NOTIFICATIONS grant lived only in the
 * `rememberLauncherForActivityResult` callback, so a grant that arrived any
 * other way (Settings → Access, or a callback lost to activity/process
 * recreation) left the foreground notification hidden for the whole session.
 *
 * "A trigger exists" is not observable from a pure JVM test — the wiring is a
 * Compose lifecycle observer inside `WorkspaceNavGraph`, and this repo runs no
 * Compose UI test framework on the JVM (see [LifecycleConformanceSourceTest] for
 * the same reasoning). So the invariant is asserted against the source, the same
 * way the §1.100 lifecycle contract is: a future refactor that deletes the
 * resume observer, or "simplifies" it to the CompositionLocal that throws
 * (§1.105), fails the build.
 *
 * The idempotence half of the fix is a behavioural test, not a source one — see
 * [com.fem.linexa.service.runtime.RuntimeNotificationPresenceTest].
 */
class WorkspaceNotificationRecheckSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val navGraph: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")

    private val service: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")

    @Test
    fun workspaceGraphRechecksTheNotificationOnResume() {
        val src = navGraph
        assertTrue(
            "WorkspaceNavGraph must observe the lifecycle for the notification re-check",
            src.contains("LifecycleEventObserver")
        )
        assertTrue(
            "the re-check must be tied to ON_RESUME (the first moment the app is back in front of the user)",
            src.contains("Lifecycle.Event.ON_RESUME")
        )
        assertTrue(
            "the re-check must send ACTION_REFRESH_NOTIFICATION",
            src.contains("refreshNotificationIntent")
        )
    }

    @Test
    fun theRefreshIsNotOnlyReachableFromThePermissionCallback() {
        // The regression: exactly one `refreshNotificationIntent` call site, inside
        // the permission launcher's callback. There must now be at least two — the
        // fast path (dialog resolved) and the lifecycle recovery.
        val callSites = Regex("refreshNotificationIntent\\(").findAll(navGraph).count()
        assertTrue(
            "expected the permission-callback trigger AND a lifecycle trigger, found $callSites call site(s)",
            callSites >= 2
        )
    }

    @Test
    fun resumeObserverIsRegisteredAndUnregistered() {
        val src = navGraph
        assertTrue(
            "the observer must be registered on the lifecycle",
            src.contains("lifecycle.addObserver(observer)")
        )
        assertTrue(
            "the observer must be removed on dispose — leaving the workspace must not keep refreshing",
            src.contains("lifecycle.removeObserver(observer)")
        )
    }

    @Test
    fun resumeObserverUsesTheComposeUiLifecycleOwner() {
        // §1.105: androidx.lifecycle.compose's identically-named LocalLifecycleOwner
        // is not provided by compose-ui 1.6.x and throws at read time, taking the
        // screen down. AccessSettingsGroup already learned this.
        assertTrue(
            "must import compose-ui's LocalLifecycleOwner (§1.105)",
            navGraph.contains("import androidx.compose.ui.platform.LocalLifecycleOwner")
        )
        assertTrue(
            "must not import androidx.lifecycle.compose's LocalLifecycleOwner (§1.105 crash)",
            !navGraph.contains("import androidx.lifecycle.compose.LocalLifecycleOwner")
        )
    }

    @Test
    fun serviceRefreshIsIdempotentAndStillActiveWorkspaceGated() {
        val src = service
        assertTrue(
            "the refresh must be gated on the workspace still owning the runtime",
            src.contains("lifecycleManager.activeWorkspaceId.value == workspaceId")
        )
        assertTrue(
            "the refresh must consult RuntimeNotificationPresence rather than posting unconditionally",
            src.contains("notificationPresence.evaluate(")
        )
        assertTrue(
            "the posted snapshot must be recorded so the next check can be a no-op",
            src.contains("notificationPresence.recordPosted(")
        )
        assertTrue(
            "tearing the foreground down must forget the posted snapshot",
            src.contains("notificationPresence.clear()")
        )
    }

    @Test
    fun permissionStateIsReadWithAreNotificationsEnabled() {
        // A granted POST_NOTIFICATIONS with a blocked channel still shows nothing,
        // and pre-33 has no permission to check — so the service's predicate must
        // be areNotificationsEnabled(), not checkSelfPermission.
        assertTrue(
            "RuntimeService must use NotificationManagerCompat.areNotificationsEnabled()",
            service.contains("areNotificationsEnabled()")
        )
    }

    @Test
    fun notificationChannelSettingsAreUnchanged() {
        // Acceptance criterion: existing content/channel/importance are untouched.
        val src = service
        assertTrue(
            "the channel id must remain runtime_service",
            src.contains("const val CHANNEL_ID = \"runtime_service\"")
        )
        assertTrue(
            "the channel importance must remain IMPORTANCE_LOW",
            src.contains("NotificationManager.IMPORTANCE_LOW")
        )
        assertEquals(
            "there must remain exactly one runtime notification id",
            1,
            Regex("const val NOTIFICATION_ID").findAll(src).count()
        )
        assertTrue(
            "content must still come from RuntimeNotificationContent",
            src.contains("RuntimeNotificationContent.contentText(")
        )
    }
}
