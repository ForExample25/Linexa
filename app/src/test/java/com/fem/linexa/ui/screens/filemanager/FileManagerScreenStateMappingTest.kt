package com.fem.linexa.ui.screens.filemanager

import android.app.Application
import com.fem.linexa.domain.filemanager.FileInfo
import com.fem.linexa.domain.filemanager.FileManager
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.WorkspaceHomeLayout
import com.fem.linexa.service.runtime.container.WorkspaceUserHome
import com.fem.linexa.ui.screens.filemanager.FileManagerViewModel.AndroidStorage
import com.fem.linexa.ui.screens.filemanager.FileManagerViewModel.BrowserMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

/**
 * Phase 08 (audit OPT-PERF-001): the File Manager screen now collects ONE
 * [FileManagerScreenState] instead of 19 flows (17 at the screen root — one of
 * them never read — plus 2 inside the overview branch).
 *
 * Two things must hold for that to be a pure re-wiring and not a behavior
 * change:
 *
 *  1. [combineFileManagerScreenState] maps every flow into its NAMED field —
 *     verified with deliberately distinct values, so a wrong slot produces a
 *     visible field mismatch (or a cast crash) here rather than a subtly wrong
 *     screen. The representative combination the brief names is covered too:
 *     an empty listing while the storage walk is still loading.
 *  2. The real ViewModel's consolidated state starts at the documented
 *     initial value — the same thing 17 separate `collectAsState()` calls
 *     rendered before their first updates (Unbound, "/", overview shown,
 *     nothing loaded).
 *
 * Source-flow locals are prefixed `f` on purpose: a `with(state) { }` block's
 * receiver fields share names with the flows, and a bare name would silently
 * resolve to the enclosing local (a real shadowing bug this test once had).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerScreenStateMappingTest {

    private fun fileInfo(path: String) = FileInfo(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        size = 42L,
        permissions = "-rw-r--r--",
        lastModified = "2026-11-13 00:00"
    )

    // ── 1. the combine maps every flow to its named field ──────────────────

    @Test
    fun theCombineMapsEveryFlowIntoItsNamedField() = runTest {
        val file = fileInfo("/tmp/report.txt")
        val fUiState = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Ready)
        val fCurrentPath = MutableStateFlow("/tmp")
        val fMode = MutableStateFlow(BrowserMode.ANDROID)
        val fAndroidStorage = MutableStateFlow(
            AndroidStorage(totalBytes = 64_000_000_000L, availableBytes = 16_000_000_000L, accessGranted = true)
        )
        val fShowOverview = MutableStateFlow(false)
        val fFiles = MutableStateFlow(listOf(file))
        val fVisibleFiles = MutableStateFlow(listOf(file))
        val fSearchQuery = MutableStateFlow("report")
        val fSort = MutableStateFlow(FileListSort())

        val fBreadcrumbs = MutableStateFlow(listOf("/", "tmp"))
        val fOperationMessage = MutableStateFlow<String?>("Copied 1 item(s)")
        val fViewingFile = MutableStateFlow<String?>("/tmp/report.txt")
        val fFileContent = MutableStateFlow<String?>("hello")
        val fPendingTransfer = MutableStateFlow<PendingTransfer?>(PendingTransfer(listOf(file), move = true))
        val fSelectedPaths = MutableStateFlow(setOf("/tmp/report.txt"))
        val fEditorState = MutableStateFlow<FileEditorState?>(
            FileEditorState(path = "/tmp/report.txt", name = "report.txt", text = "hello")
        )
        val fInspectedFile = MutableStateFlow<FileInfo?>(file)
        val fStorage = MutableStateFlow<WorkspaceStorage?>(null)
        val fStorageLoading = MutableStateFlow(true)

        val mapped = combineFileManagerScreenState(
            fUiState, fCurrentPath, fMode, fAndroidStorage, fShowOverview,
            fFiles, fVisibleFiles, fSearchQuery, fSort, fBreadcrumbs,
            fOperationMessage, fViewingFile, fFileContent, fPendingTransfer,
            fSelectedPaths, fEditorState, fInspectedFile, fStorage, fStorageLoading
        )
        val collected = mutableListOf<FileManagerScreenState>()
        val job = launch { mapped.collect { collected += it } }
        runCurrent()

        // One consistent initial snapshot, field for field.
        assertEquals(1, collected.size)
        with(collected.single()) {
            assertEquals(FileManagerUiState.Ready, uiState)
            assertEquals("/tmp", currentPath)
            assertEquals(BrowserMode.ANDROID, mode)
            assertEquals(fAndroidStorage.value, androidStorage)
            assertEquals(false, showOverview)
            assertEquals(listOf(file), files)
            assertEquals(listOf(file), visibleFiles)
            assertEquals("report", searchQuery)
            assertEquals(FileListSort(), sort)
            assertEquals(listOf("/", "tmp"), breadcrumbs)
            assertEquals("Copied 1 item(s)", operationMessage)
            assertEquals("/tmp/report.txt", viewingFile)
            assertEquals("hello", fileContent)
            assertEquals(PendingTransfer(listOf(file), move = true), pendingTransfer)
            assertEquals(setOf("/tmp/report.txt"), selectedPaths)
            assertEquals(FileEditorState(path = "/tmp/report.txt", name = "report.txt", text = "hello"), editorState)
            assertEquals(file, inspectedFile)
            assertEquals(null, storage)
            assertEquals(true, storageLoading)
        }

        // The representative delta (Part D): the storage walk completes while
        // the directory listing and the search state ride along unchanged.
        // ONE flow per step, so each emission is deterministic (two changes in
        // one step could conflate into one or two emissions).
        val reading = WorkspaceStorage(usedBytes = 5_000_000_000L, deviceFreeBytes = 11_000_000_000L)
        fStorage.value = reading
        runCurrent()

        assertEquals(2, collected.size)
        with(collected.last()) {
            assertEquals(reading, storage)
            assertEquals(true, storageLoading)
        }

        fStorageLoading.value = false
        runCurrent()

        assertEquals(3, collected.size)
        with(collected.last()) {
            assertEquals(reading, storage)
            assertEquals(false, storageLoading)
            // Untouched fields keep their values in the same snapshot.
            assertEquals("report", searchQuery)
            assertEquals(listOf(file), visibleFiles)
            assertEquals(BrowserMode.ANDROID, mode)
        }
        job.cancel()
    }

    // ── 2. the real ViewModel starts at the documented initial state ───────

    @Test
    fun theRealViewModelStartsAtTheDocumentedInitialState() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = FileManagerViewModel(
                Mockito.mock(Application::class.java),
                Mockito.mock(FileManager::class.java),
                Mockito.mock(WorkspaceFileManager::class.java),
                Mockito.mock(WorkspaceUserHome::class.java)
            )
            // The stateIn initialValue IS the pre-first-emission state: the
            // same snapshot 17 separate collectAsState() calls rendered before
            // their first updates. WhileSubscribed has no collector here, so
            // this is also what a re-subscription would immediately re-receive.
            assertEquals(
                FileManagerScreenState(),
                viewModel.screenState.value
            )
            // The Home place renders before the passwd read completes. It must
            // be the normal-user placeholder, never `/root`: the tile cannot
            // momentarily display root's home while the workspace is unknown.
            assertEquals(
                WorkspaceHomeLayout.WorkspaceHomes.default(),
                viewModel.homes.value
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}
