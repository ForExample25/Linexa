package com.fem.linexa.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 4 — source pins for the three surface wirings that the pure
 * helpers cannot prove: the Activity section is actually collapsed/lazy, the
 * File Manager renders the filtered flow without changing the operation
 * source, and the Package Manager observes the existing coordinator queue.
 *
 * These are deliberately narrow string pins in the style of
 * `UiRefinementSourcePinsTest`, not behaviour re-implementations.
 */
class Phase4SurfaceWiringTest {

    private lateinit var root: File

    @Before
    fun locate() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/activity/ActivityScreen.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) { root = dir; return }
            dir = dir.parentFile
        }
        root = File(".")
    }

    private fun src(p: String) = File(root, "src/main/java/com/fem/linexa/$p").readText()

    @Test
    fun activityHostListIsCollapsedByDefaultAndSaveable() {
        val screen = src("ui/screens/activity/ActivityScreen.kt")
        assertTrue(screen.contains("var hostExpanded by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(screen.contains("HostProcessPresentation.visible(hostRows, hostExpanded)"))
        assertTrue(screen.contains("HostProcessPresentation.key(hostVisible[it])"))
        // The per-process rows are emitted only through the lazy items branch,
        // never through a Column-based expandable panel.
        assertTrue(screen.contains("} else if (hostVisible.isNotEmpty()) {"))
        assertFalse(screen.contains("HExpandablePanel("))
        // PERF-001 (phase 8): a collapsed section composes no rows, so it must
        // not order the process table either. The sort sits behind the expand
        // gate; the header count and empty state use the raw sample count so a
        // collapsed section still reports the true number of processes.
        assertTrue(screen.contains("val hostProcessCount = state.hostProcesses?.processes?.size ?: 0"))
        assertTrue(
            screen.contains(
                "if (hostExpanded) {\n            HostProcessPresentation.ordered("
            )
        )
        assertTrue(screen.contains("count = hostProcessCount"))
        assertFalse(screen.contains("count = hostRows.size"))
        assertFalse(screen.contains("if (hostRows.isEmpty())"))
    }

    @Test
    fun activityScreenNeverStartsTheRuntimeService() {
        // PERF-003: visiting the Activity tab must not create RuntimeService.
        // The screen only attaches to a service the workspace start path owns,
        // and only while a session exists.
        val vm = src("ui/screens/activity/ActivityViewModel.kt")
        assertFalse(
            "the Activity tab must never call startService — that leak is what created the service",
            vm.contains("startService(intent)")
        )
        assertTrue(
            "the read-only bind must be gated on the session state",
            vm.contains("sessionManager.sessionStates")
        )
        assertTrue(
            "the bind is session-gated, so its collectors must die with the connection",
            vm.contains("serviceCollectorJobs.forEach { it.cancel() }")
        )
    }

    @Test
    fun activityKeepsTheMonitoringEngineEvenWhenCollapsed() {
        val vm = src("ui/screens/activity/ActivityViewModel.kt")
        assertTrue(vm.contains("private val hostScanner: HostProcessScanner"))
        assertTrue(vm.contains("fun start()"))
        assertTrue(vm.contains("fun pause()"))
        // PERF-006: the engine is still wired; only the PSS walk is conditional
        // on the section being expanded.
        assertTrue(vm.contains("hostScanner.snapshot(includePss = hostProcessesExpanded)"))
        // No low-level name filter was added to the monitor.
        assertFalse(vm.contains("libproot.so"))
        assertFalse(vm.contains("\"ssh\""))
    }

    @Test
    fun fileManagerBrowsesTheFilteredFlowButOperatesOnTheRawListing() {
        val vm = src("ui/screens/filemanager/FileManagerViewModel.kt")
        val screen = src("ui/screens/filemanager/FileManagerScreen.kt")
        assertTrue(vm.contains("val visibleFiles: StateFlow<List<FileInfo>>"))
        assertTrue(vm.contains("withContext(Dispatchers.Default) {"))
        assertTrue(vm.contains("FileListPresentation.SEARCH_DEBOUNCE_MS"))
        assertTrue(vm.contains("resetSearchOnNavigation()"))
        // The row list is the visible projection...
        assertTrue(screen.contains("itemsIndexed(visibleFiles"))
        assertFalse(screen.contains("itemsIndexed(files"))
        // ...while selection/paste/delete still read the ViewModel's raw list.
        assertTrue(vm.contains("val files: StateFlow<List<FileInfo>>"))
        assertTrue(vm.contains("_files.value.filter { it.path in _selectedPaths.value }"))
    }

    @Test
    fun aNewListingNeverProjectsThePreviousDirectoriesRows() {
        val vm = src("ui/screens/filemanager/FileManagerViewModel.kt")
        // Both listing success branches (Linux and Android) drop the old
        // projection before Ready can compose, then publish off-thread.
        val readySnippets = listOf("_files.value = result.value", "_files.value = listing")
        for (snippet in readySnippets) {
            val index = vm.indexOf(snippet)
            assertTrue("missing listing assignment: $snippet", index >= 0)
            val before = vm.substring((index - 200).coerceAtLeast(0), index)
            assertTrue(
                "the previous directory's rows must be dropped before Ready: $snippet",
                before.contains("_visibleFiles.value = emptyList()")
            )
        }
    }

    @Test
    fun fileManagerSearchHasAnExplicitNoResultRecovery() {
        val screen = src("ui/screens/filemanager/FileManagerScreen.kt")
        assertTrue(screen.contains("R.string.fm_no_results_title"))
        assertTrue(screen.contains("onAction = viewModel::clearSearch"))
        // Motion pass 2: the permanently visible HSearchField became the
        // icon-to-field HSearchMorphBar (same shared Haven family, same
        // query/onQueryChange wiring); the sort chips still live in the toolbar.
        assertTrue(screen.contains("HSearchMorphBar("))
        assertTrue(screen.contains("onQueryChange = onQueryChange"))
        assertTrue(screen.contains("HChip("))
    }

    @Test
    fun packageManagerReadsTheExistingCoordinatorQueue() {
        val vm = src("ui/screens/packagemanager/PackageManagerViewModel.kt")
        val screen = src("ui/screens/packagemanager/PackageManagerScreen.kt")
        assertTrue(vm.contains("private val packageOperationCoordinator: PackageOperationCoordinator"))
        assertTrue(vm.contains("packageOperationCoordinator.snapshotOf(wsId).collect"))
        assertTrue(vm.contains("queueJob?.cancel()"))
        assertTrue(screen.contains("val queue by viewModel.queue.collectAsState()"))
        assertTrue(screen.contains("queue.hasOtherActivity"))
        assertTrue(screen.contains("queued = if (op.status == OperationStatus.RUNNING) queue.waiting else 0"))
    }

    @Test
    fun packageQueueIsNotASecondSourceOfTruth() {
        val coordinator = src("domain/packagemanager/PackageOperationCoordinator.kt")
        val vm = src("ui/screens/packagemanager/PackageManagerViewModel.kt")
        // The domain still owns the one mutex and the one snapshot flow.
        assertTrue(coordinator.contains("suspend fun <T> runExclusive("))
        assertTrue(coordinator.contains("fun snapshotOf(workspaceId: String)"))
        // The ViewModel must not create its own queue or scheduling primitive.
        assertFalse(vm.contains("Mutex("))
        assertFalse(vm.contains("runExclusive("))
        assertFalse(vm.contains("while (true)"))
    }
}
