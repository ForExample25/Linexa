package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.100 / §P0 — structural source-invariant test pinning the clarified
 * Workspace lifecycle contract:
 *
 *   "Workspace lifecycle is controlled only by entering/leaving the Workspace
 *    and by the FGS notification's Stop action. There is NO Start/Stop/
 *    Restart control inside the Workspace UI."
 *
 * The §1.32 `SettingsTab` "Session" group (Start/Stop/Restart) was removed in
 * §P0. This test reads the *source* of the UI + runtime wiring and asserts the
 * invariant structurally, so a future reintroduction fails the build on the
 * JVM even if no UI test ever renders the tab.
 *
 * Why a source test (not a compose test): the codebase runs no Compose UI
 * test framework on the JVM, and a framework render test would drag in an
 * entire emulator/main-thread harness for one boolean assertion. Reading the
 * actual source is the smallest, most direct guard: the removed controls were
 * literally a `SettingsGroup("Session")` + three buttons wired to
 * `RuntimeViewModel.startWorkspace/stopWorkspace/restartWorkspace`, and the
 * removed intent was `ACTION_RESTART`. Each is assertable as text.
 *
 * These assertions are intentionally *negative* (absence of the controls) plus
 * a small set of *positive* assertions that the enter/leave path (the allowed
 * control) still exists — so this test can't silently pass because the whole
 * lifecycle was deleted.
 *
 * Source location: JVM unit tests run with the module directory (`app/`) as
 * the working directory (verified on this repo's CI/sandbox), so paths are
 * resolved relative to `src/main/...`. A small upward walk is included so the
 * test also works if the runner sets `user.dir` to the repo root instead.
 */
class LifecycleConformanceSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val start = File(System.getProperty("user.dir"))
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")
        var dir = start
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

    // ── Negative: the removed Settings "Session" group ──────────────────

    @Test
    fun settingsTabHasNoSessionGroup() {
        val src = source("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")
        // The §1.32 group was `SettingsGroup(title = stringResource(R.string.settings_session))`.
        assertFalse(
            "HavenWorkspaceSettingsTab must not reference the settings_session group title",
            src.contains("settings_session")
        )
        // The §1.32 group title was `stringResource(R.string.settings_session)`.
        // A *literal* `SettingsGroup(title = "Session")` is just as forbidden —
        // match the composable call shape, not prose comments.
        assertFalse(
            "SettingsTab must not compose a literal 'Session' SettingsGroup",
            src.contains("SettingsGroup(title = \"Session\"")
        )
        // The removed group rendered three action buttons wired to the runtime.
        assertFalse(
            "SettingsTab must not wire a session start button",
            src.contains("onStartWorkspace") || src.contains("onStartSession")
        )
        assertFalse(
            "SettingsTab must not wire a session stop button",
            src.contains("onStopWorkspace") || src.contains("onStopSession")
        )
        assertFalse(
            "SettingsTab must not wire a session restart button",
            src.contains("onRestartWorkspace") || src.contains("onRestartSession")
        )
        // The removed group also surfaced the live RuntimeState. SettingsTab no
        // longer takes a runtimeState parameter.
        assertFalse(
            "SettingsTab must not accept a runtimeState parameter (removed Session group)",
            src.contains("runtimeState")
        )
    }

    @Test
    fun stringsXmlHasNoSessionControlStrings() {
        val src = source("src/main/res/values/strings.xml")
        for (removed in listOf(
            "settings_session",
            "settings_session_start",
            "settings_session_stop",
            "settings_session_restart"
        )) {
            assertFalse(
                "strings.xml must not contain removed string resource: $removed",
                src.contains(removed)
            )
        }
    }

    // ── Negative: the removed ACTION_RESTART intent path ────────────────

    @Test
    fun runtimeServiceHasNoRestartAction() {
        val src = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")
        assertFalse(
            "RuntimeService must not define ACTION_RESTART",
            src.contains("ACTION_RESTART")
        )
        assertFalse(
            "RuntimeService must not define a restart intent builder",
            src.contains("restartIntent")
        )
        assertFalse(
            "RuntimeService must not handle an ACTION_RESTART branch",
            src.contains("ACTION_RESTART")
        )
    }

    @Test
    fun runtimeViewModelHasNoRestartApi() {
        val src = source("src/main/java/com/fem/linexa/service/runtime/RuntimeViewModel.kt")
        assertFalse(
            "RuntimeViewModel must not expose restartWorkspace",
            src.contains("restartWorkspace")
        )
        assertFalse(
            "RuntimeViewModel must not expose requestRestart",
            src.contains("requestRestart")
        )
    }

    // ── Positive: the allowed enter/leave + FGS-stop lifecycle still exists ──

    @Test
    fun enterLeaveLifecycleStillIntact() {
        // Entering the workspace must still start the runtime (§1.2).
        val vm = source("src/main/java/com/fem/linexa/service/runtime/RuntimeViewModel.kt")
        assertTrue(
            "RuntimeViewModel must still expose startWorkspace (enter path)",
            vm.contains("fun startWorkspace")
        )
        assertTrue(
            "RuntimeViewModel must still expose stopWorkspace (leave/FGS-stop path)",
            vm.contains("fun stopWorkspace")
        )
        // Exiting the workspace graph must still start the runtime on entry.
        val navGraph = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")
        assertTrue(
            "WorkspaceNavGraph must still start the runtime on entry",
            navGraph.contains("startWorkspace")
        )
        // The FGS Stop action must still exist as the explicit stop path.
        val service = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")
        assertTrue(
            "RuntimeService must still define ACTION_START (enter path)",
            service.contains("ACTION_START")
        )
        assertTrue(
            "RuntimeService must still define ACTION_STOP (FGS stop path)",
            service.contains("ACTION_STOP")
        )
        // SettingsTab must be wired with the non-session callbacks it still has
        // (delete, package manager, file manager, debug toggle) — i.e. it is
        // still composed, not silently removed from the screen.
        val screen = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
        assertTrue(
            "WorkspaceScreen must still compose SettingsTab",
            screen.contains("SettingsTab(")
        )
    }

    // ── Transformation spec §9/§10: UI destruction ≠ runtime termination ──

    @Test
    fun leavingTheGraphMustNotStopTheRuntime() {
        // §9: the runtime continues when the user backs out of the workspace;
        // explicit stop lives in the leave-dialog's "Stop workspace" choice,
        // the FGS notification Stop action, and workspace deletion.
        val vm = source("src/main/java/com/fem/linexa/service/runtime/RuntimeViewModel.kt")
        assertTrue(
            "onCleared must NOT stop the workspace (§9 keep-running policy)",
            !Regex("""onCleared\(\)[\s\S]*?stopWorkspace\(""").containsMatchIn(vm.substringAfter("override fun onCleared"))
        )
        val navGraph = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")
        assertTrue(
            "the leave dialog must offer the explicit Stop choice",
            navGraph.contains("dialog_exit_workspace_confirm")
        )
        assertTrue(
            "the leave dialog must offer the keep-running choice (§9)",
            navGraph.contains("dialog_exit_workspace_background")
        )
        assertTrue(
            "the exit dialog must only stop a RUNNING workspace (Starting races are avoided by leaving)",
            navGraph.contains("runtimeState is RuntimeState.Running")
        )
    }
}
