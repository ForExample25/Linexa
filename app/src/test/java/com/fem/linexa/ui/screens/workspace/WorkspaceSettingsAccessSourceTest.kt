package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 4 (§1.132) structural guard: the Access group is **gone** from the
 * Workspace Settings tab, and the §1.105 crash it once carried cannot come back with it.
 *
 * Two separate properties are pinned here, and both matter for a different reason.
 *
 * **(1) The rows are not here any more.** They were added on this tab in §1.104, which
 * conflated "a permission the workspace runtime needs" with "a permission scoped to a
 * workspace". All three grants — all-files access, the battery-optimization whitelist and
 * `POST_NOTIFICATIONS` — are held by the *application*: toggling one inside workspace A
 * changed it for workspace B, and a user with no workspaces at all had no screen on which
 * they existed, even though the FGS notification (the only way to stop a workspace,
 * §1.100) and the battery whitelist are both needed *before* a workspace is entered. The
 * plan's Phase 4 asks for a **move**, so a copy left behind would be a defect, not a
 * harmless duplicate: two rows over one platform grant is how they end up disagreeing.
 * Reachability of the new location is asserted by
 * [com.fem.linexa.ui.screens.settings.SettingsWiringSourceTest].
 *
 * **(2) The §1.105 crash guard travelled with the code.** Root cause (verified against
 * the resolved artifacts): the removed group used
 * `androidx.lifecycle.compose.LifecycleResumeEffect`, whose default
 * `lifecycleOwner = LocalLifecycleOwner.current` reads the
 * `androidx.lifecycle.compose` CompositionLocal — a *separate* local from compose-ui's
 * `androidx.compose.ui.platform.LocalLifecycleOwner`, and compose-ui 1.6.8
 * (BOM 2024.06.00) only ever provides the latter. Reading the un-provided local throws
 * `IllegalStateException: CompositionLocal LocalLifecycleOwner not present`, and because
 * this tab was the app's only `androidx.lifecycle.compose` user, only Settings crashed.
 * The moved code keeps the explicit `LifecycleEventObserver` binding; this test asserts
 * neither file can regress, because the *new* location has exactly the same trap
 * available to it.
 *
 * Like `LifecycleConformanceSourceTest`, these are structural source tests (JVM, reading
 * the module source) — the only test type this repo runs on the JVM (§1.124(a)).
 */
class WorkspaceSettingsAccessSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val start = File(System.getProperty("user.dir"))
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")
        var dir: File? = start
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        // Fall back to the canonical app-module working dir.
        moduleRoot = File(".")
    }

    private fun source(relative: String): String =
        File(moduleRoot, relative).readText()

    // HAVEN rebuild: the workspace's settings segment lives in
    // HavenWorkspaceSettingsTab.kt (the old SettingsTab.kt is deleted).
    private val settingsTab: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")

    private val accessGroup: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/settings/AccessSettingsGroup.kt")

    // ── (1) The app-global rows left this tab ──────────────────────────────

    @Test
    fun theWorkspaceTabNoLongerRendersAnAccessGroup() {
        assertFalse(
            "the Access group is app-global and now lives in Global Settings (Phase 4) — a copy here would let one platform grant be shown by two rows",
            settingsTab.contains("R.string.settings_access")
        )
        assertFalse(
            "the individual rows must be gone too, not just the group heading",
            settingsTab.contains("R.string.settings_file_access") ||
                settingsTab.contains("R.string.settings_battery_optimization") ||
                settingsTab.contains("R.string.settings_notifications")
        )
    }

    @Test
    fun theWorkspaceTabNoLongerQueriesOrRequestsAppGlobalPermissions() {
        // The rows going while the machinery stays is the worst outcome: dead code that
        // looks live and invites the next person to re-add the group.
        assertFalse(
            "no MANAGE_EXTERNAL_STORAGE query belongs on a per-workspace screen",
            settingsTab.contains("Environment.isExternalStorageManager()")
        )
        assertFalse(
            "no battery-optimization query belongs on a per-workspace screen",
            settingsTab.contains("isIgnoringBatteryOptimizations")
        )
        assertFalse(
            "no POST_NOTIFICATIONS request belongs on a per-workspace screen",
            settingsTab.contains("Manifest.permission.POST_NOTIFICATIONS")
        )
        assertFalse(
            "and the API-gating predicates must not be duplicated — they moved, they were not copied",
            settingsTab.contains("fun fileAccessSupported(") ||
                settingsTab.contains("fun notificationsPermissionRequired(")
        )
    }

    @Test
    fun theTabKeepsOnlyItsGenuinelyPerWorkspaceGroups() {
        // The move must not have taken anything else with it. These are real
        // per-workspace surfaces: the debug trace is this workspace's runtime
        // chain and the danger zone deletes this workspace. (2026-11 pass: the
        // old Tools group — the Files/Packages rows — moved to the Apps
        // segment; the same destinations are pushed from there, so a tools
        // copy here would be a duplicate entry point, not a setting.)
        assertTrue(settingsTab.contains("R.string.haven_ws_debug"))
        assertFalse(
            "the Tools group moved to the Apps segment (Packages/Files buttons above the launcher search) — a copy here would duplicate the entry point",
            settingsTab.contains("R.string.haven_ws_tools") ||
                settingsTab.contains("onOpenPackageManager") ||
                settingsTab.contains("onOpenFileManager")
        )
        assertTrue(settingsTab.contains("R.string.haven_ws_danger"))
    }

    // ── (2) The §1.105 crash cannot return, in either location ─────────────

    @Test
    fun neitherSettingsSurfaceUsesLifecycleCompose() {
        listOf("SettingsTab" to settingsTab, "AccessSettingsGroup" to accessGroup)
            .forEach { (name, src) ->
                assertFalse(
                    "$name must not import androidx.lifecycle.compose.* — its LocalLifecycleOwner is not provided by compose-ui 1.6.x and reading it crashes with 'CompositionLocal LocalLifecycleOwner not present' (§1.105)",
                    src.contains("import androidx.lifecycle.compose")
                )
                assertFalse(
                    "$name must not call LifecycleResumeEffect",
                    src.contains("LifecycleResumeEffect(")
                )
            }
    }

    @Test
    fun theMovedAccessGroupBindsToComposeUiLifecycleOwner() {
        assertTrue(
            "AccessSettingsGroup must read LocalLifecycleOwner from androidx.compose.ui.platform (the one compose-ui actually provides)",
            accessGroup.contains("import androidx.compose.ui.platform.LocalLifecycleOwner")
        )
        assertTrue(
            "AccessSettingsGroup must observe the lifecycle via DisposableEffect + LifecycleEventObserver",
            accessGroup.contains("DisposableEffect(lifecycleOwner)") &&
                accessGroup.contains("LifecycleEventObserver") &&
                accessGroup.contains("onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }")
        )
    }

    @Test
    fun theMovedAccessGroupStillGatesApiUseBySdkInt() {
        assertTrue(
            "File-access APIs (Environment.isExternalStorageManager) must be gated on API level",
            accessGroup.contains("if (fileAccessSupported(Build.VERSION.SDK_INT))")
        )
        assertTrue(
            "Notification permission must only be requested/checked on API 33+",
            accessGroup.contains("notificationsPermissionRequired(Build.VERSION.SDK_INT)")
        )
        val guardIndex = accessGroup.indexOf("if (fileAccessSupported(Build.VERSION.SDK_INT))")
        val callIndex = accessGroup.indexOf("Environment.isExternalStorageManager()")
        assertTrue(
            "Environment.isExternalStorageManager() must only be called after the fileAccessSupported guard",
            guardIndex != -1 && callIndex != -1 && callIndex > guardIndex
        )
    }

    @Test
    fun everySettingsIntentIsWrappedAgainstUnresolvableActivities() {
        // A device with no Activity for one of these system screens is unusual but real,
        // and startActivity throws rather than returning false.
        listOf("SettingsTab" to settingsTab, "AccessSettingsGroup" to accessGroup)
            .forEach { (name, src) ->
                val startActivityCount = src.split("context.startActivity").size - 1
                if (startActivityCount == 0) return@forEach
                val runCatchingCount = src.split("runCatching").size - 1
                assertTrue(
                    "Every startActivity in $name must be inside a runCatching guard so an unresolvable settings Activity can't crash the app",
                    runCatchingCount >= startActivityCount
                )
            }
    }
}
