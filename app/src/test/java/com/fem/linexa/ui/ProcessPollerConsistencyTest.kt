package com.fem.linexa.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the consolidated File Manager state and the shared
 * `/proc` poller cadence.
 *
 * The behavioural tests pin the consolidated state mapping
 * (`FileManagerScreenStateMappingTest`) and the poller loop
 * (`DeviceInfoPollerLifecycleTest`, `SessionTaskManagerPollerLifecycleTest`).
 * This test pins the WIRING that makes them hold:
 *
 *  1. the File Manager screen collects only its consolidated `screenState` and
 *     the workspace `homes` layout — no per-flow subscription may quietly grow
 *     back, and `collectAsStateWithLifecycle` must not appear on the pinned
 *     toolchain;
 *  2. the ViewModel exposes the consolidated state built from all 19 of its
 *     hot flows;
 *  3. the six `/proc` pollers delegate their 2 s cadence to the ONE shared
 *     [ProcessPolling] constant instead of declaring six private copies.
 */
class ProcessPollerConsistencyTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/misc/ProcessPolling.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    /** Strips comment lines so assertions judge code, not prose (Phase 07 lesson). */
    private fun code(source: String): String =
        source.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `the file manager screen collects only the consolidated state and homes`() {
        val screen = code(source("ui/screens/filemanager/FileManagerScreen.kt"))
        val calls = Regex("""collectAsState\(\s*\)""").findAll(screen).count()
        assertEquals(
            "FileManagerScreen must collect only the consolidated screenState and the " +
                "workspace home layout — found $calls collectAsState() calls",
            2,
            calls
        )
        assertTrue(
            "one collection must be the consolidated screenState",
            screen.contains("val state by viewModel.screenState.collectAsState()")
        )
        assertTrue(
            "the other collection must be the workspace home layout",
            screen.contains("val homes by viewModel.homes.collectAsState()")
        )
        assertFalse(
            "collectAsStateWithLifecycle is not used on the pinned toolchain",
            screen.contains("collectAsStateWithLifecycle")
        )
        // The two overview-branch collections are gone: no second subscription
        // slot for storage may exist anywhere in the screen.
        assertFalse(
            "storage must not be collected in the screen (it rides the consolidated state)",
            screen.contains("viewModel.storage.collectAsState")
        )
        assertFalse(
            "storageLoading must not be collected in the screen",
            screen.contains("viewModel.storageLoading.collectAsState")
        )
    }

    @Test
    fun `the viewmodel builds the consolidated state from all nineteen flows`() {
        val viewModel = code(source("ui/screens/filemanager/FileManagerViewModel.kt"))
        val blockStart = viewModel.indexOf("combineFileManagerScreenState(")
        val blockEnd = viewModel.indexOf(".stateIn(", blockStart)
        assertTrue(
            "the ViewModel must wire screenState through combineFileManagerScreenState + stateIn",
            blockStart >= 0 && blockEnd > blockStart
        )
        val wiring = viewModel.substring(blockStart, blockEnd)
        for (flow in listOf(
            "_uiState", "_currentPath", "_mode", "_androidStorage", "_showOverview",
            "_files", "_visibleFiles", "_searchQuery", "_sort", "_breadcrumbs",
            "_operationMessage", "_viewingFile", "_fileContent", "_pendingTransfer",
            "_selectedPaths", "_editorState", "_inspectedFile", "_storage", "_storageLoading"
        )) {
            assertTrue(
                "the consolidated state must consume $flow",
                wiring.contains(flow)
            )
        }
    }

    @Test
    fun `the six proc pollers share one interval constant`() {
        val pollers = listOf(
            "ui/screens/activity/ActivityViewModel.kt",
            "ui/screens/workspace/SessionsViewModel.kt",
            "ui/screens/taskmanager/TaskManagerViewModel.kt",
            "ui/screens/device/DeviceInfoViewModel.kt",
            "ui/screens/terminal/SessionTaskManagerViewModel.kt",
            "ui/components/WorkspacePerformanceViewModel.kt"
        )
        for (path in pollers) {
            val viewModel = code(source(path))
            assertTrue(
                "$path must delegate POLL_INTERVAL_MS to the shared ProcessPolling constant",
                viewModel.contains("const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS")
            )
            assertFalse(
                "$path must not declare its own literal interval any more",
                Regex("""POLL_INTERVAL_MS\s*=\s*[0-9]""").containsMatchIn(viewModel)
            )
        }
        val shared = code(source("ui/misc/ProcessPolling.kt"))
        assertTrue(
            "ProcessPolling.INTERVAL_MS must be the one 2 s literal",
            shared.contains("const val INTERVAL_MS = 2_000L")
        )
    }

    @Test
    fun `every poller keeps its idempotent start and cancels on stop`() {
        // The visible-lifetime law stays in force per poller.
        // Structural pins only — the loop semantics are behaviourally tested.
        val expectations = mapOf(
            "ui/screens/activity/ActivityViewModel.kt" to listOf("fun start()", "fun pause()"),
            "ui/screens/workspace/SessionsViewModel.kt" to listOf("fun start(", "fun pause()"),
            "ui/screens/taskmanager/TaskManagerViewModel.kt" to listOf("fun start(", "fun stop()"),
            "ui/screens/device/DeviceInfoViewModel.kt" to listOf("fun start()", "fun pause()"),
            "ui/screens/terminal/SessionTaskManagerViewModel.kt" to listOf("fun start(", "fun stop()"),
            "ui/components/WorkspacePerformanceViewModel.kt" to listOf("fun start(", "fun pause()")
        )
        for ((path, apis) in expectations) {
            val viewModel = code(source(path))
            for (api in apis) {
                assertTrue("$path must keep its $api API (UI callers depend on it)", viewModel.contains(api))
            }
            assertTrue(
                "$path must cancel its polling job in onCleared (the last-resort stop)",
                Regex("""override fun onCleared\(\)\s*\{[^}]*?(pollJob\?\.cancel\(\)|stopPolling\(\))""", RegexOption.DOT_MATCHES_ALL)
                    .containsMatchIn(viewModel)
            )
        }
    }
}
