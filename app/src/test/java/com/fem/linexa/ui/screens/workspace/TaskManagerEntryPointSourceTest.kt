package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LINEXA-PERF-014: the Task Manager route is registered in
 * `WorkspaceNavGraph` and wired through `WorkspaceScreen`, but before this
 * remediation no composable ever invoked the callback — the screen was dead
 * code in production. These pins are intentionally source-level: the subject
 * is *wiring* (a parameter reaching a click handler and a route staying
 * registered), which a Compose UI test would assert through the same string
 * tokens while needing the full Hilt graph.
 */
class TaskManagerEntryPointSourceTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("missing $path", file.isFile)
        return file.readText()
    }

    @Test
    fun sessionsHeaderReachesTheTaskManagerRoute() {
        val sessions = source("src/main/java/com/fem/linexa/ui/screens/workspace/SessionsSegment.kt")
        assertTrue(
            "the Sessions header must expose a Task Manager action",
            sessions.contains("onOpenTaskManager: () -> Unit")
        )
        assertTrue(
            "the action must use the existing localized description",
            sessions.contains("R.string.tm_open_task_manager")
        )
        assertTrue(
            "the action must invoke the hoisted callback",
            sessions.contains("onClick = onOpenTaskManager")
        )
    }

    @Test
    fun workspaceScreenForwardsTheCallbackAndGraphRegistersTheRoute() {
        val screen = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
        assertTrue(
            "WorkspaceScreen must pass its callback into the Sessions segment",
            screen.contains("onOpenTaskManager = onOpenTaskManager")
        )

        val graph = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")
        assertTrue(graph.contains("const val TASK_MANAGER"))
        assertTrue(
            "the route must be reached by a UI-wired navigate call",
            graph.contains("onOpenTaskManager = { navController.navigate(WorkspaceRoutes.TASK_MANAGER) }")
        )
        assertTrue(
            "the destination must stay registered",
            graph.contains("composable(WorkspaceRoutes.TASK_MANAGER)")
        )
        assertTrue("the screen must still be composed", graph.contains("TaskManagerScreen("))
    }
}
