package com.fem.linexa.ui.screens.filemanager

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.R
import com.fem.linexa.core.i18n.linexaQuantityString
import com.fem.linexa.core.i18n.linexaString
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.filemanager.FileInfo
import com.fem.linexa.domain.filemanager.FileManager
import com.fem.linexa.domain.filemanager.FileOpResult
import com.fem.linexa.domain.filemanager.LsLongFormatParser
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.WorkspaceHomeLayout
import com.fem.linexa.service.runtime.container.WorkspaceUserHome
import com.fem.linexa.ui.screens.filemanager.FileTypes.FileTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the File Manager screen (§1.12, Phase 6).
 *
 * Uses the workspace's shared [RuntimeBinder] (owned by the graph-scoped
 * [com.fem.linexa.service.runtime.RuntimeViewModel]) via [attachBinder].
 * Delegates file operations to [FileManager] (WorkspaceFileManagerImpl).
 *
 * §1.115 changed three things:
 * - Every operation reports the guest's real failure reason instead of a
 *   generic "Failed to …" (or, for listing, an empty directory).
 * - `copyItem`/`moveItem`/`exportFile` were implemented but unreachable —
 *   §4's Phase 6 requirement ("copy/move/rename/delete/import-export/share")
 *   was therefore unmet. They are now driven by a real clipboard
 *   ([pendingTransfer]) and a real export path.
 * - [attachBinder] accepts a *replacement* binder for the same workspace
 *   (the §1.59 failure mode: a re-bound service left the screen holding a
 *   dead binder forever).
 */
@HiltViewModel
class FileManagerViewModel @Inject constructor(
    application: Application,
    private val fileManager: FileManager,
    /** App-grid redesign: the storage hero card's source of truth (same
     *  measured reading the performance pane uses). */
    private val workspaceFileManager: WorkspaceFileManager,
    /**
     * 2026-11-13 unified home architecture: resolves the Workspace identity's
     * REAL home from the installed image's `/etc/passwd`. The File Manager
     * must never derive "Home" from `/root`, its own Android process HOME, or
     * the uid the internal root-exec bridge runs under.
     */
    private val workspaceUserHome: WorkspaceUserHome
) : AndroidViewModel(application) {

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null

    private val _uiState = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Unbound)
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<FileInfo>>(emptyList())
    val files: StateFlow<List<FileInfo>> = _files.asStateFlow()

    /**
     * FM-1: what the browse list renders. [files] stays the RAW listing — the
     * source of truth for selection and file operations — while this flow is
     * the same entries filtered by [searchQuery] and ordered by [sort]. A
     * filtered-out entry that is selected still operates, so search never
     * silently changes what an action targets.
     */
    private val _visibleFiles = MutableStateFlow<List<FileInfo>>(emptyList())
    val visibleFiles: StateFlow<List<FileInfo>> = _visibleFiles.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Native by default: the guest's `LC_ALL=C ls -lA` order (Linux) or the
     * Android lister's directories-first order is preserved until the user
     * explicitly chooses another criterion.
     */
    private val _sort = MutableStateFlow(FileListSort())
    val sort: StateFlow<FileListSort> = _sort.asStateFlow()

    /** The debounced/off-thread search pass; cancelled by newer input or navigation. */
    private var filterJob: Job? = null

    private val _breadcrumbs = MutableStateFlow<List<String>>(emptyList())
    val breadcrumbs: StateFlow<List<String>> = _breadcrumbs.asStateFlow()

    /**
     * File Manager redesign: WHICH filesystem the screen is browsing.
     *
     * [BrowserMode.LINUX] is the historical behavior (guest paths through the
     * binder). [BrowserMode.ANDROID] browses the device's shared storage
     * DIRECTLY on the Android side (java.io.File + StatFs) — never through
     * PRoot, never pretending /sdcard and the guest tree are one filesystem.
     */
    enum class BrowserMode { LINUX, ANDROID }

    private val _mode = MutableStateFlow(BrowserMode.LINUX)
    val mode: StateFlow<BrowserMode> = _mode.asStateFlow()

    /**
     * Whether the screen shows the storage OVERVIEW (Android storage + Linux
     * workspace places) rather than a directory listing.
     *
     * Root cause of the "Filesystem just refreshes" bug: the overview used to
     * be rendered whenever the Linux path was "/" — so the Filesystem entry
     * (target "/") had nowhere to go: it was already "on" "/". The overview is
     * now its own explicit state, independent of the path, and every place /
     * folder tap leaves it and opens a real listing.
     */
    private val _showOverview = MutableStateFlow(true)
    val showOverview: StateFlow<Boolean> = _showOverview.asStateFlow()

    /** Returns to the overview (the file manager's home). */
    fun openOverview() {
        _showOverview.value = true
        resetSearchOnNavigation()
        _visibleFiles.value = emptyList()
        selectedPathsClearIfAny()
        refreshAndroidStorage()
    }

    /**
     * 2026-11-13: the identity-aware Home places for the workspace overview.
     *
     * [WorkspaceHomeLayout.WorkspaceHomes.default] is the normal-user
     * `/home/linexa` placeholder, so the overview never paints `/root` while
     * the passwd read is in flight. [attachBinder] replaces it with the
     * actual resolved account; [detachBinder] resets it to the same safe
     * default — the tile can never retain the previous workspace's home.
     */
    private val _homes = MutableStateFlow(WorkspaceHomeLayout.WorkspaceHomes.default())
    val homes: StateFlow<WorkspaceHomeLayout.WorkspaceHomes> = _homes.asStateFlow()

    private var homesJob: Job? = null

    /** Re-resolves the workspace user's home (also used by the header refresh). */
    fun refreshHomes(wsId: String? = null) {
        val target = wsId ?: workspaceId ?: return
        homesJob?.cancel()
        homesJob = viewModelScope.launch {
            val resolved = runCatching { workspaceUserHome.resolve(target) }
                .getOrElse {
                    Logger.w(TAG, "Could not resolve the workspace user home for $target — using default: ${it.message}")
                    WorkspaceHomeLayout.WorkspaceHomes.default()
                }
            if (workspaceId == target) _homes.value = resolved
        }
    }

    private fun selectedPathsClearIfAny() {
        if (selectionActive) clearSelection()
    }

    // ── FM-1: browse search + sort ──────────────────────────────────────────

    /**
     * Filters the CURRENT directory. Debounced and computed on
     * [Dispatchers.Default]: a 10k-entry listing must not be filtered on the
     * composition thread.
     */
    fun onSearchQueryChange(query: String) {
        if (_searchQuery.value == query) return
        _searchQuery.value = query
        scheduleVisibleUpdate(debounce = true)
    }

    fun clearSearch() {
        if (_searchQuery.value.isEmpty()) return
        _searchQuery.value = ""
        scheduleVisibleUpdate(debounce = false)
    }

    fun onSortChange(sort: FileListSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        scheduleVisibleUpdate(debounce = false)
    }

    fun cycleSort() = onSortChange(_sort.value.nextCriterion())

    fun toggleSortDirection() = onSortChange(_sort.value.toggledDirection())

    private fun scheduleVisibleUpdate(debounce: Boolean) {
        filterJob?.cancel()
        val files = _files.value
        val query = _searchQuery.value
        val sort = _sort.value
        val linux = _mode.value == BrowserMode.LINUX
        filterJob = viewModelScope.launch {
            if (debounce) delay(FileListPresentation.SEARCH_DEBOUNCE_MS)
            val visible = withContext(Dispatchers.Default) {
                FileListPresentation.filterAndSort(files, query, sort, linux)
            }
            // Drop a pass whose inputs were superseded while it computed; the
            // newer input has already scheduled its own pass.
            if (_searchQuery.value != query || _sort.value != sort || _files.value !== files) {
                return@launch
            }
            _visibleFiles.value = visible
        }
    }

    /**
     * A directory change is a new scope: keeping the old query would make the
     * new folder look empty for a reason the user cannot see. A same-folder
     * refresh keeps the query and sort.
     */
    private fun resetSearchOnNavigation() {
        if (_searchQuery.value.isEmpty()) return
        _searchQuery.value = ""
        filterJob?.cancel()
    }

    /** One Android quick-folder (Downloads/Music/…) with its real path. */
    data class AndroidPlace(val label: String, val path: String)

    /** Android shared-storage overview, measured with StatFs. */
    data class AndroidStorage(
        val totalBytes: Long?,
        val availableBytes: Long?,
        val accessGranted: Boolean
    ) {
        val usedBytes: Long? get() = if (totalBytes != null && availableBytes != null) totalBytes - availableBytes else null
        val fraction: Float?
            get() {
                val total = totalBytes ?: return null
                val used = usedBytes ?: return null
                if (total <= 0L) return null
                return (used.toFloat() / total).coerceIn(0f, 1f)
            }
    }

    private val _androidStorage = MutableStateFlow<AndroidStorage?>(null)
    val androidStorage: StateFlow<AndroidStorage?> = _androidStorage.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _fileContent = MutableStateFlow<String?>(null)
    val fileContent: StateFlow<String?> = _fileContent.asStateFlow()

    private val _viewingFile = MutableStateFlow<String?>(null)
    val viewingFile: StateFlow<String?> = _viewingFile.asStateFlow()

    /** §1.115: the cut/copied entry awaiting a paste, if any. */
    private val _pendingTransfer = MutableStateFlow<PendingTransfer?>(null)
    val pendingTransfer: StateFlow<PendingTransfer?> = _pendingTransfer.asStateFlow()

    /**
     * App-grid redesign: the workspace's storage reading for the file
     * manager's root-level hero card — the SAME [WorkspaceStorage] the
     * performance pane uses (measured rootfs size + the volume's real free
     * space), never a notional budget. Null until the first walk completes;
     * [storageLoading] covers the walk (a multi-GB rootfs can take a moment
     * on a cache miss), so the card shows a spinner rather than a dash that
     * reads as "empty".
     */
    private val _storage = MutableStateFlow<WorkspaceStorage?>(null)
    val storage: StateFlow<WorkspaceStorage?> = _storage.asStateFlow()

    private val _storageLoading = MutableStateFlow(false)
    val storageLoading: StateFlow<Boolean> = _storageLoading.asStateFlow()

    private var storageJob: Job? = null

    /** U8 fix (2026-09-02): the in-flight directory listing, cancelled on navigation. */
    private var listJob: Job? = null

    fun attachBinder(wsId: String, runtimeBinder: RuntimeBinder) {
        val sameBinder = binder === runtimeBinder && workspaceId == wsId
        workspaceId = wsId
        binder = runtimeBinder
        if (sameBinder && _uiState.value !is FileManagerUiState.Unbound) return
        // A different runtime/workspace has a different filesystem in scope.
        _searchQuery.value = ""
        filterJob?.cancel()
        _uiState.value = FileManagerUiState.Ready
        if (mode.value == BrowserMode.ANDROID) {
            refreshAndroidStorage()
            navigateAndroid(_currentPath.value)
        } else {
            navigateTo(if (sameBinder) _currentPath.value else "/", force = true)
        }
        refreshStorage(wsId)
        refreshHomes(wsId)
    }

    fun detachBinder() {
        binder = null
        workspaceId = null
        _uiState.value = FileManagerUiState.Unbound
        filterJob?.cancel()
        _searchQuery.value = ""
        _visibleFiles.value = emptyList()
        storageJob?.cancel()
        _storage.value = null
        _storageLoading.value = false
        homesJob?.cancel()
        _homes.value = WorkspaceHomeLayout.WorkspaceHomes.default()
    }

    /**
     * Re-reads the workspace storage card (App-grid redesign) — on attach and
     * on the header's explicit refresh. Off the main thread: the size walk
     * can traverse the whole rootfs on a cache miss.
     */
    fun refreshStorage(wsId: String? = null) {
        val target = wsId ?: workspaceId ?: return
        if (target.isBlank()) return
        storageJob?.cancel()
        _storageLoading.value = true
        storageJob = viewModelScope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { workspaceFileManager.getWorkspaceStorage(target) }.getOrNull()
            }
            if (workspaceId == target) {
                _storage.value = reading
                _storageLoading.value = false
            }
        }
    }

    /**
     * Navigates to [path] in the CURRENT mode's filesystem.
     *
     * File Manager redesign — the refresh-without-navigation bug fix: a
     * navigateTo for the path the screen is ALREADY on is a no-op unless
     * [force] is set (the header's refresh button passes it). The old
     * unconditional reload made every Places tap at root — including the
     * "Filesystem" entry whose target IS root — flash a full Loading state
     * over the same content, which read as "refreshed but went nowhere".
     */
    fun navigateTo(path: String, force: Boolean = false) {
        if (!force && mode.value == BrowserMode.LINUX && path == _currentPath.value && _uiState.value is FileManagerUiState.Ready) {
            return
        }
        if (mode.value == BrowserMode.ANDROID) {
            navigateAndroid(path)
            return
        }
        val b = binder ?: return
        val ws = workspaceId ?: return

        val normalizedPath = if (path.isBlank()) "/" else path
        if (normalizedPath != _currentPath.value) resetSearchOnNavigation()
        _currentPath.value = normalizedPath
        _breadcrumbs.value = buildBreadcrumbs(normalizedPath)
        _fileContent.value = null
        _viewingFile.value = null

        // Pre-release audit fix (2026-09-02, finding U8/M-5): cancel the
        // in-flight listing before launching a new one. Two rapid navigations
        // used to complete out of order — the visible path showed B while
        // _files still held A's listing — and a subsequent delete/paste could
        // then operate against a listing that didn't match the screen. The
        // stale result is also dropped on arrival (belt-and-braces against a
        // listing that races its own cancellation).
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _uiState.value = FileManagerUiState.Loading
            try {
                when (val result = fileManager.listFiles(b, ws, normalizedPath)) {
                    is FileOpResult.Ok -> {
                        if (_currentPath.value != normalizedPath) return@launch
                        // Drop the previous directory's projection before the
                        // Ready state can compose: the off-thread pass below
                        // publishes one main-loop turn later, and showing A's
                        // rows under B's breadcrumb for that frame is wrong.
                        _visibleFiles.value = emptyList()
                        _files.value = result.value
                        _uiState.value = FileManagerUiState.Ready
                        scheduleVisibleUpdate(debounce = false)
                    }
                    is FileOpResult.Failure -> {
                        // Failing to list is not the same as an empty
                        // directory: show the guest's reason, and keep the
                        // previous listing rather than blanking the screen.
                        if (_currentPath.value != normalizedPath) return@launch
                        _uiState.value = FileManagerUiState.Error(result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to list files at $normalizedPath", e)
                if (_currentPath.value == normalizedPath) {
                    _uiState.value = FileManagerUiState.Error(e.message ?: str(R.string.file_manager_list_failed))
                }
            }
        }
    }

    // ── Android shared-storage browsing (File Manager redesign) ──────────

    /**
     * Switches to Android shared storage at [path] (default: the storage
     * root). Only directories that actually exist and are readable are
     * offered — nothing is fabricated.
     */
    fun openAndroidStorage(path: String? = null) {
        if (_mode.value != BrowserMode.ANDROID) {
            resetSearchOnNavigation()
            _files.value = emptyList()
            _visibleFiles.value = emptyList()
        }
        _mode.value = BrowserMode.ANDROID
        _showOverview.value = false
        refreshAndroidStorage()
        navigateAndroid(path ?: androidStorageRoot()?.absolutePath ?: "/sdcard")
    }

    /** Returns to Linux workspace browsing (at "/" — a mode switch is a navigation). */
    fun openLinuxFilesystem() = openLinuxPlace("/")

    /**
     * Opens a workspace location (Filesystem "/", the identity-aware Home,
     * Root Home, Temporary "/tmp", …) as a real listing: leaves the overview,
     * switches to Linux mode, and ALWAYS loads [path] from the guest (force) —
     * a place tap is a navigation, never a same-screen refresh. Paths are
     * guest paths resolved through the runtime binder (PRoot's view of the
     * rootfs), so the PRoot boundary is respected.
     */
    fun openLinuxPlace(path: String) {
        if (_mode.value != BrowserMode.LINUX) {
            resetSearchOnNavigation()
            _files.value = emptyList()
            _visibleFiles.value = emptyList()
        }
        _mode.value = BrowserMode.LINUX
        _showOverview.value = false
        navigateTo(path, force = true)
    }

    /**
     * "All files access" (MANAGE_EXTERNAL_STORAGE) exists only on API 30+.
     *
     * [Environment.isExternalStorageManager] is a method added in R; calling it
     * on API 28/29 throws `NoSuchMethodError` and would crash Android-storage
     * browsing on devices the app still supports. The version gate therefore
     * has to live in this method, beside the call: lint's NewApi check cannot
     * see through a helper function, and runtime safety must not depend on
     * lint.
     *
     * On pre-R the answer is "not granted": this app declares no legacy
     * broad-storage permission (SAF is the supported path there), so claiming
     * access would only move the failure to `getExternalStorageDirectory`.
     */
    private fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return Environment.isExternalStorageManager()
    }

    private fun androidStorageRoot(): java.io.File? {
        if (!hasAllFilesAccess()) return null
        return runCatching {
            Environment.getExternalStorageDirectory()
        }.getOrNull()?.takeIf { it.canRead() }
    }

    /** Lists an Android directory directly (host side — never through PRoot). */
    private fun navigateAndroid(path: String) {
        val normalizedPath = if (path.isBlank()) "/sdcard" else path
        if (normalizedPath != _currentPath.value) resetSearchOnNavigation()
        _currentPath.value = normalizedPath
        _breadcrumbs.value = buildBreadcrumbs(normalizedPath)
        _fileContent.value = null
        _viewingFile.value = null
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _uiState.value = FileManagerUiState.Loading
            val listing = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(normalizedPath)
                    if (!dir.exists() || !dir.isDirectory) {
                        null
                    } else {
                        dir.listFiles()
                            ?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
                            ?.map { file ->
                                FileInfo(
                                    name = file.name,
                                    path = file.absolutePath,
                                    isDirectory = file.isDirectory,
                                    size = if (file.isFile) file.length() else 0L,
                                    permissions = if (file.canRead() && file.canWrite()) "rw" else if (file.canRead()) "r" else "-",
                                    lastModified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                        .format(java.util.Date(file.lastModified())),
                                    isSymlink = false,
                                    linkTarget = null,
                                    hasByteSize = file.isFile
                                )
                            }
                    }
                }.getOrNull()
            }
            if (_currentPath.value != normalizedPath) return@launch
            if (listing == null) {
                _uiState.value = FileManagerUiState.Error(str(R.string.file_manager_cannot_read, normalizedPath))
            } else {
                // Same one-frame guarantee as the Linux branch above.
                _visibleFiles.value = emptyList()
                _files.value = listing
                _uiState.value = FileManagerUiState.Ready
                scheduleVisibleUpdate(debounce = false)
            }
        }
    }

    /** Re-measures the Android shared-storage overview (StatFs, real values). */
    fun refreshAndroidStorage() {
        viewModelScope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching {
                    if (!hasAllFilesAccess()) return@withContext AndroidStorage(null, null, false)
                    val root = Environment.getExternalStorageDirectory()
                    if (!root.canRead()) return@withContext AndroidStorage(null, null, false)
                    val stat = StatFs(root.absolutePath)
                    AndroidStorage(
                        totalBytes = stat.totalBytes,
                        availableBytes = stat.availableBytes,
                        accessGranted = true
                    )
                }.getOrNull()
            }
            if (reading != null) _androidStorage.value = reading
        }
    }

    /**
     * The Android quick folders that ACTUALLY EXIST and are readable —
     * Downloads/Movies/Music/Pictures/Documents/DCIM plus any other
     * top-level directories present. Nothing fabricated; hidden entries
     * (dot-prefixed) are skipped as file managers do.
     */
    suspend fun androidPlaces(): List<AndroidPlace> = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext emptyList()
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return@withContext emptyList()
        if (!root.canRead()) return@withContext emptyList()
        val standard = listOf(
            R.string.fm_dir_downloads to "Download",
            R.string.fm_dir_movies to "Movies",
            R.string.fm_dir_music to "Music",
            R.string.fm_dir_pictures to "Pictures",
            R.string.fm_dir_documents to "Documents",
            R.string.fm_dir_dcim to "DCIM"
        )
        val real = root.listFiles()?.filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }?.map { it.name }?.toSet() ?: emptySet()
        val out = ArrayList<AndroidPlace>()
        // Standard folders first in canonical order (only when present).
        for ((labelRes, dir) in standard) {
            if (dir in real) out += AndroidPlace(str(labelRes), java.io.File(root, dir).absolutePath)
        }
        // Then any other real top-level directory, alphabetical.
        real.filterNot { it in standard.map { s -> s.second } }.sorted().forEach { dir ->
            out += AndroidPlace(dir, java.io.File(root, dir).absolutePath)
        }
        out
    }

    fun navigateUp() {
        if (_showOverview.value) return
        if (mode.value == BrowserMode.ANDROID) {
            val root = androidStorageRoot()?.absolutePath
            // At (or above) the storage root, "up" is the overview.
            if (root == null || _currentPath.value == root || !isInside(_currentPath.value, root)) {
                openOverview()
                return
            }
            navigateAndroid(parentOf(_currentPath.value))
            return
        }
        // Linux: "/" is the top of the guest tree; up from there is the overview.
        if (_currentPath.value == "/") {
            openOverview()
        } else {
            navigateTo(parentOf(_currentPath.value))
        }
    }

    fun createFolder(name: String) {
        val path = LsLongFormatParser.joinPath(_currentPath.value, name)
        run(str(R.string.file_manager_created_folder, name)) { b, ws -> fileManager.createDirectory(b, ws, path) }
    }

    /**
     * Issue #9 (2026-09-03): creates a new file, optionally seeded with the
     * chosen template's starter content. Two guest operations when a template
     * applies (touch + write) and one otherwise — both idempotent-checked.
     */
    fun createFile(name: String, template: FileTemplate?) {
        val path = LsLongFormatParser.joinPath(_currentPath.value, name)
        val b = binder ?: return
        val ws = workspaceId ?: return
        viewModelScope.launch {
            try {
                when (val result = fileManager.createFile(b, ws, path)) {
                    is FileOpResult.Failure -> {
                        _operationMessage.value = result.message
                        return@launch
                    }
                    is FileOpResult.Ok -> Unit
                }
                template?.let { chosen ->
                    when (val write = fileManager.writeFile(b, ws, path, chosen.content ?: "")) {
                        is FileOpResult.Failure -> {
                            _operationMessage.value = str(R.string.file_manager_created_template_failed, name, write.message)
                            navigateTo(_currentPath.value)
                            return@launch
                        }
                        is FileOpResult.Ok -> Unit
                    }
                }
                _operationMessage.value = str(R.string.file_manager_created_file, name)
                navigateTo(_currentPath.value)
            } catch (e: Exception) {
                Logger.e(TAG, "Create file failed", e)
                _operationMessage.value = str(R.string.file_manager_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ── Issue #9: selection mode (multi-select) ─────────────────────────

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    /** Long-press: enter selection mode (or toggle the row when already selecting). */
    fun toggleSelection(fileInfo: FileInfo) {
        val current = _selectedPaths.value
        _selectedPaths.value = if (fileInfo.path in current) current - fileInfo.path else current + fileInfo.path
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    /** Whether the screen is in selection mode (one or more rows ticked). */
    val selectionActive: Boolean get() = _selectedPaths.value.isNotEmpty()

    /** Batch delete of everything ticked; reports per-item failures honestly. */
    fun deleteSelected() {
        val targets = _selectedPaths.value.toList()
        if (targets.isEmpty()) return
        val b = binder ?: return
        val ws = workspaceId ?: return
        viewModelScope.launch {
            var deleted = 0
            val failures = mutableListOf<String>()
            for (path in targets) {
                try {
                    when (val result = fileManager.delete(b, ws, path)) {
                        is FileOpResult.Ok -> deleted++
                        is FileOpResult.Failure -> failures += result.message
                    }
                } catch (e: Exception) {
                    failures += e.message ?: path
                }
            }
            _selectedPaths.value = emptySet()
            _operationMessage.value = when {
                failures.isEmpty() -> qty(R.plurals.file_manager_deleted_count, deleted, deleted)
                deleted == 0 -> failures.first()
                else -> str(R.string.file_manager_delete_partial, deleted, failures.size, failures.first())
            }
            navigateTo(_currentPath.value)
        }
    }

    /**
     * Batch clipboard: everything ticked is cut/copied together; paste
     * iterates the list (single-item transfers stay a one-element list).
     */
    fun copySelectedToClipboard() {
        val files = _files.value.filter { it.path in _selectedPaths.value }
        if (files.isEmpty()) return
        _pendingTransfer.value = PendingTransfer(files, move = false)
        _operationMessage.value = qty(R.plurals.file_manager_copied_count_hint, files.size, files.size)
        _selectedPaths.value = emptySet()
    }

    fun cutSelectedToClipboard() {
        val files = _files.value.filter { it.path in _selectedPaths.value }
        if (files.isEmpty()) return
        _pendingTransfer.value = PendingTransfer(files, move = true)
        _operationMessage.value = qty(R.plurals.file_manager_cut_count_hint, files.size, files.size)
        _selectedPaths.value = emptySet()
    }

    // ── Issue #9: file inspection ─────────────────────────────────────

    private val _inspectedFile = MutableStateFlow<FileInfo?>(null)
    val inspectedFile: StateFlow<FileInfo?> = _inspectedFile.asStateFlow()

    /** Loads one entry's `ls -ld` metadata for the info dialog. */
    fun inspectFile(fileInfo: FileInfo) {
        val b = binder ?: return
        val ws = workspaceId ?: return
        viewModelScope.launch {
            try {
                when (val result = fileManager.statFile(b, ws, fileInfo.path)) {
                    is FileOpResult.Ok -> _inspectedFile.value = result.value
                    is FileOpResult.Failure -> _operationMessage.value = result.message
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Inspect failed", e)
                _operationMessage.value = str(R.string.file_manager_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeInspector() {
        _inspectedFile.value = null
    }

    // ── Issue #9: the text editor (read → edit → save) ──────────────────

    private val _editorState = MutableStateFlow<FileEditorState?>(null)
    val editorState: StateFlow<FileEditorState?> = _editorState.asStateFlow()

    /**
     * Phase 08 (audit OPT-PERF-001): ONE immutable snapshot of everything the
     * File Manager screen renders, collected once instead of subscribing to
     * each of the 19 flows separately (17 at the screen's root — one of them,
     * `mode`, was collected but never read — plus 2 inside the overview
     * branch).
     *
     * Declared after all 19 backing flows: the initializer consumes them, and
     * Kotlin forbids a property initializer from reading a sibling declared
     * later in the class.
     *
     * The per-flow getters above stay: they are the mutation sites' source of
     * truth, and every internal `suspend`/`launch` body still reads them
     * directly. This property only changes WHAT THE SCREEN SUBSCRIBES TO.
     *
     * `WhileSubscribed(5_000)` keeps the upstream combine running for 5 s
     * after the last collector leaves (recomposition churn) and stops it
     * after that; the private flows are plain `StateFlow`s, so a returning
     * collector always receives the current snapshot immediately — no state
     * is ever dropped while the screen is backgrounded.
     */
    val screenState: StateFlow<FileManagerScreenState> =
        combineFileManagerScreenState(
            _uiState, _currentPath, _mode, _androidStorage, _showOverview,
            _files, _visibleFiles, _searchQuery, _sort, _breadcrumbs,
            _operationMessage, _viewingFile, _fileContent, _pendingTransfer,
            _selectedPaths, _editorState, _inspectedFile, _storage, _storageLoading
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FileManagerScreenState()
        )

    /**
     * Whether the editor is still open LOADING [path]. The async read's
     * completion is only allowed to act while this holds: the user can back
     * out or re-target while the read is in flight, and a guard-free
     * completion would (a) reopen an editor the user just closed, or (b) load
     * file A's content into the editor the user meanwhile opened for file B —
     * the stale-content race this layer must not have.
     */
    private fun editorIsLoading(path: String): Boolean {
        val current = _editorState.value ?: return false
        return current.loading && current.path == path
    }

    /** Opens the editor for a text file (binary or oversized files are refused with a reason). */
    fun editFile(fileInfo: FileInfo) {
        val b = binder ?: return
        val ws = workspaceId ?: return
        if (!FileTypes.isEditable(fileInfo.name)) {
            _operationMessage.value = str(R.string.file_manager_no_editor)
            viewFile(fileInfo)
            return
        }
        viewModelScope.launch {
            _editorState.value = FileEditorState(path = fileInfo.path, name = fileInfo.name, loading = true)
            try {
                when (val result = fileManager.readFile(b, ws, fileInfo.path)) {
                    is FileOpResult.Ok -> {
                        if (!editorIsLoading(fileInfo.path)) return@launch
                        if (result.value.length > MAX_EDITOR_CHARS) {
                            _editorState.value = null
                            _operationMessage.value = str(R.string.file_manager_file_too_large_edit, MAX_EDITOR_CHARS / 1024)
                            viewFile(fileInfo)
                        } else {
                            _editorState.value = FileEditorState(
                                path = fileInfo.path,
                                name = fileInfo.name,
                                original = result.value,
                                text = result.value
                            )
                        }
                    }
                    is FileOpResult.Failure -> {
                        if (!editorIsLoading(fileInfo.path)) return@launch
                        _editorState.value = null
                        _operationMessage.value = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Edit open failed", e)
                if (!editorIsLoading(fileInfo.path)) return@launch
                _editorState.value = null
                _operationMessage.value = str(R.string.file_manager_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Live text change from the editor field. */
    fun updateEditorText(text: String) {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(text = text)
    }

    /**
     * Persists the buffer via the same heredoc write the import path uses.
     *
     * Correctness of the pipeline rests on three invariants here:
     *
     * 1. **The exact bytes written are [FileEditorState.text] as of this
     *    call** — captured on the main thread where [updateEditorText] just
     *    delivered the latest keystroke; the coroutine never re-reads a
     *    different snapshot mid-write.
     * 2. **[FileEditorState.saving] is set true synchronously BEFORE the
     *    write starts** — the save action disables itself, the buffer turns
     *    read-only, and a second tap cannot start a concurrent write to the
     *    same file.
     * 3. **Completion updates the CURRENT state, never the pre-save
     *    snapshot.** Assigning the captured `state.copy(...)` wholesale used
     *    to clobber any newer buffer state and mis-mark it saved; only
     *    `original` (the dirty baseline, set to exactly the written text) and
     *    `saving` ever change, and only while the editor is still open for
     *    the same path. Edits the user made during the in-flight write stay
     *    in `text` and correctly read as Modified (unsaved).
     */
    fun saveEditor() {
        val state = _editorState.value ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        if (state.loading || state.saving) return
        val textToWrite = state.text
        _editorState.value = state.copy(saving = true)
        viewModelScope.launch {
            try {
                when (val result = fileManager.writeFile(b, ws, state.path, textToWrite)) {
                    is FileOpResult.Ok -> {
                        _editorState.update { current ->
                            if (current?.path == state.path) {
                                current.copy(original = textToWrite, saving = false)
                            } else {
                                current
                            }
                        }
                        _operationMessage.value = str(R.string.file_manager_saved_named, state.name)
                    }
                    is FileOpResult.Failure -> {
                        _editorState.update { current ->
                            if (current?.path == state.path) current.copy(saving = false) else current
                        }
                        _operationMessage.value = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Save failed", e)
                _editorState.update { current ->
                    if (current?.path == state.path) current.copy(saving = false) else current
                }
                _operationMessage.value = str(R.string.file_manager_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setEditorSaving(saving: Boolean) {
        val state = _editorState.value ?: return
        _editorState.value = state.copy(saving = saving)
    }

    fun closeEditor() {
        _editorState.value = null
    }

    fun deleteItem(fileInfo: FileInfo) {
        run(str(R.string.file_manager_deleted_named, fileInfo.name)) { b, ws -> fileManager.delete(b, ws, fileInfo.path) }
    }

    fun renameItem(fileInfo: String, newName: String) {
        val oldPath = LsLongFormatParser.joinPath(_currentPath.value, fileInfo)
        val newPath = LsLongFormatParser.joinPath(_currentPath.value, newName)
        run(str(R.string.file_manager_renamed_to, newName)) { b, ws -> fileManager.rename(b, ws, oldPath, newPath) }
    }

    /** §1.115: mark an entry to be copied on the next [pasteInto]. */
    fun copyToClipboard(fileInfo: FileInfo) {
        _pendingTransfer.value = PendingTransfer(fileInfo, move = false)
        _operationMessage.value = str(R.string.file_manager_copied_named_hint, fileInfo.name)
    }

    /** §1.115: mark an entry to be moved on the next [pasteInto]. */
    fun cutToClipboard(fileInfo: FileInfo) {
        _pendingTransfer.value = PendingTransfer(fileInfo, move = true)
        _operationMessage.value = str(R.string.file_manager_cut_named_hint, fileInfo.name)
    }

    fun clearPendingTransfer() {
        _pendingTransfer.value = null
    }

    /**
     * Paste the pending entries into the current directory.
     *
     * A move is `mv` (which is atomic within a filesystem and keeps
     * permissions), a copy is `cp -r`. Pasting into the source's own parent is
     * refused rather than silently producing `mv a a` — `mv -n` would report
     * success while doing nothing, which is exactly the class of lie §1.115
     * removed elsewhere. Issue #9: multi-item transfers iterate; a mid-list
     * failure is reported but does not abort the rest.
     */
    fun pasteInto(directory: String) {
        val transfer = _pendingTransfer.value ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        viewModelScope.launch {
            var moved = 0
            val failures = mutableListOf<String>()
            for (item in transfer.items) {
                val source = item.path
                val destination = LsLongFormatParser.joinPath(directory, item.name)
                if (source == destination) {
                    continue
                }
                if (item.isDirectory && isInside(destination, source)) {
                    failures += getApplication<Application>().linexaString(
                        R.string.file_manager_paste_into_itself,
                        item.name
                    )
                    continue
                }
                try {
                    val result = if (transfer.move) {
                        fileManager.rename(b, ws, source, destination)
                    } else {
                        fileManager.copy(b, ws, source, destination)
                    }
                    when (result) {
                        is FileOpResult.Ok -> moved++
                        is FileOpResult.Failure -> failures += result.message
                    }
                } catch (e: Exception) {
                    failures += e.message ?: item.name
                }
            }
            _pendingTransfer.value = null
            _operationMessage.value = when {
                failures.isEmpty() -> if (transfer.move) qty(R.plurals.file_manager_moved_count, moved, moved) else qty(R.plurals.file_manager_copied_count, moved, moved)
                moved == 0 -> failures.first()
                else -> str(
                    R.string.file_manager_transfer_partial,
                    if (transfer.move) qty(R.plurals.file_manager_moved_count, moved, moved) else qty(R.plurals.file_manager_copied_count, moved, moved),
                    failures.size,
                    failures.first()
                )
            }
            navigateTo(_currentPath.value)
        }
    }

    fun viewFile(fileInfo: FileInfo) {
        val b = binder ?: return
        val ws = workspaceId ?: return

        viewModelScope.launch {
            _viewingFile.value = fileInfo.path
            _fileContent.value = null
            try {
                when (val result = fileManager.readFile(b, ws, fileInfo.path)) {
                    is FileOpResult.Ok -> {
                        // Same stale-content discipline as [editFile]: a read
                        // that finishes after the viewer moved to another
                        // entry (or closed) must not install its content under
                        // the wrong viewer.
                        if (_viewingFile.value == fileInfo.path) _fileContent.value = result.value
                    }
                    is FileOpResult.Failure -> {
                        if (_viewingFile.value == fileInfo.path) {
                            _viewingFile.value = null
                            _operationMessage.value = result.message
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to read file", e)
                if (_viewingFile.value == fileInfo.path) {
                    _viewingFile.value = null
                    _operationMessage.value = str(R.string.file_manager_read_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun closeFileViewer() {
        _viewingFile.value = null
        _fileContent.value = null
    }

    fun importFile(uri: Uri, fileName: String, contentResolver: android.content.ContentResolver) {
        val b = binder ?: return
        val ws = workspaceId ?: return

        viewModelScope.launch {
            _operationMessage.value = str(R.string.file_manager_importing, fileName)
            try {
                val content = contentResolver.openInputStream(uri)?.use { stream ->
                    // Import is text-only by construction (the guest write is a
                    // heredoc). Detect binary instead of writing a corrupted
                    // file and reporting success.
                    val bytes = stream.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) {
                        _operationMessage.value = str(R.string.file_manager_import_too_large, MAX_IMPORT_BYTES / 1024)
                        return@launch
                    }
                    if (bytes.contains(0)) {
                        _operationMessage.value = str(R.string.file_manager_import_binary)
                        return@launch
                    }
                    bytes.decodeToString()
                } ?: run {
                    _operationMessage.value = str(R.string.file_manager_import_open_failed)
                    return@launch
                }

                val destPath = LsLongFormatParser.joinPath(_currentPath.value, fileName)
                when (val result = fileManager.writeFile(b, ws, destPath, content)) {
                    is FileOpResult.Ok -> {
                        _operationMessage.value = str(R.string.file_manager_imported, fileName)
                        navigateTo(_currentPath.value)
                    }
                    is FileOpResult.Failure -> _operationMessage.value = result.message
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Import failed", e)
                _operationMessage.value = str(R.string.file_manager_import_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun exportFile(fileInfo: FileInfo, uri: Uri, contentResolver: android.content.ContentResolver) {
        val b = binder ?: return
        val ws = workspaceId ?: return

        viewModelScope.launch {
            _operationMessage.value = str(R.string.file_manager_exporting, fileInfo.name)
            try {
                when (val result = fileManager.readFile(b, ws, fileInfo.path)) {
                    is FileOpResult.Failure -> _operationMessage.value = result.message
                    is FileOpResult.Ok -> {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(result.value.toByteArray())
                        } ?: run {
                            _operationMessage.value = str(R.string.file_manager_export_open_failed)
                            return@launch
                        }
                        _operationMessage.value = str(R.string.file_manager_exported, fileInfo.name)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Export failed", e)
                _operationMessage.value = str(R.string.file_manager_export_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearMessage() {
        _operationMessage.value = null
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Run a mutating guest operation, reporting [successMessage] on success and
     * the guest's own reason on failure, then refresh the listing.
     */
    private fun run(
        successMessage: String,
        onSuccess: () -> Unit = {},
        operation: suspend (RuntimeBinder, String) -> FileOpResult<Unit>
    ) {
        val b = binder ?: return
        val ws = workspaceId ?: return
        viewModelScope.launch {
            try {
                when (val result = operation(b, ws)) {
                    is FileOpResult.Ok -> {
                        onSuccess()
                        _operationMessage.value = successMessage
                        navigateTo(_currentPath.value)
                    }
                    is FileOpResult.Failure -> _operationMessage.value = result.message
                }
            } catch (e: Exception) {
                Logger.e(TAG, "File operation failed", e)
                _operationMessage.value = str(R.string.file_manager_operation_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Resolve a user-facing message in the app-selected language. */
    private fun str(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().linexaString(resId, *args)

    /** Quantity-aware message for counts. */
    private fun qty(@PluralsRes resId: Int, quantity: Int, vararg args: Any): String =
        getApplication<Application>().linexaQuantityString(resId, quantity, *args)

    private fun buildBreadcrumbs(path: String): List<String> {
        if (path == "/") return listOf("/")
        val parts = path.trimStart('/').split('/').filter { it.isNotBlank() }
        val crumbs = mutableListOf("/")
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            crumbs.add(current)
        }
        return crumbs
    }

    internal companion object {
        private const val TAG = "FileManagerViewModel"

        /** Import ships the whole file through a shell heredoc; keep it small. */
        const val MAX_IMPORT_BYTES = 512 * 1024

        /**
         * Issue #9: editor buffer cap (chars). A phone keyboard editing a
         * multi-megabyte file is misery and the heredoc write is sized for
         * small text anyway; larger files stay on the read-only viewer.
         */
        const val MAX_EDITOR_CHARS = 512 * 1024

        fun parentOf(path: String): String {
            if (path == "/" || path.isBlank()) return "/"
            val trimmed = path.trimEnd('/')
            val lastSlash = trimmed.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else trimmed.substring(0, lastSlash)
        }

        /** Whether [candidate] is [ancestor] itself or below it. */
        fun isInside(candidate: String, ancestor: String): Boolean {
            val base = ancestor.trimEnd('/')
            if (base.isEmpty()) return true
            return candidate == base || candidate.startsWith("$base/")
        }
    }
}

/**
 * Phase 08 (audit OPT-PERF-001): the File Manager screen's one consolidated
 * render state — the 19 flows it used to subscribe to individually, as fields
 * of a single immutable snapshot collected once at the screen's root.
 *
 * Defaults mirror the ViewModel's own initial flow values exactly, so the
 * empty constructor IS the pre-first-emission state (the same thing 17
 * separate `collectAsState()` calls rendered before their first updates).
 * Deliberately NOT annotated `@Immutable`: the list/set fields are plain
 * (replaced, never mutated in place) collections Compose treats as unstable,
 * and the annotation would claim more than Compose's compiler can verify —
 * children receive individual fields, so skipping still works per child.
 */
data class FileManagerScreenState(
    val uiState: FileManagerUiState = FileManagerUiState.Unbound,
    val currentPath: String = "/",
    val mode: FileManagerViewModel.BrowserMode = FileManagerViewModel.BrowserMode.LINUX,
    val androidStorage: FileManagerViewModel.AndroidStorage? = null,
    val showOverview: Boolean = true,
    val files: List<FileInfo> = emptyList(),
    val visibleFiles: List<FileInfo> = emptyList(),
    val searchQuery: String = "",
    val sort: FileListSort = FileListSort(),
    val breadcrumbs: List<String> = emptyList(),
    val operationMessage: String? = null,
    val viewingFile: String? = null,
    val fileContent: String? = null,
    val pendingTransfer: PendingTransfer? = null,
    val selectedPaths: Set<String> = emptySet(),
    val editorState: FileEditorState? = null,
    val inspectedFile: FileInfo? = null,
    val storage: WorkspaceStorage? = null,
    val storageLoading: Boolean = false
)

/**
 * Builds [FileManagerScreenState] from the ViewModel's 19 hot flows.
 *
 * The four inner combines are the TYPED arity overloads, so every flow is
 * passed in its declared slot — swapping two flows is a compile error, not a
 * silent field swap. Each group is captured as an array at its own transform
 * and unwrapped into the state class at the single outer transform; `internal`
 * so the mapping itself can be unit-tested with representative values.
 */
@Suppress("UNCHECKED_CAST")
internal fun combineFileManagerScreenState(
    uiState: Flow<FileManagerUiState>,
    currentPath: Flow<String>,
    mode: Flow<FileManagerViewModel.BrowserMode>,
    androidStorage: Flow<FileManagerViewModel.AndroidStorage?>,
    showOverview: Flow<Boolean>,
    files: Flow<List<FileInfo>>,
    visibleFiles: Flow<List<FileInfo>>,
    searchQuery: Flow<String>,
    sort: Flow<FileListSort>,
    breadcrumbs: Flow<List<String>>,
    operationMessage: Flow<String?>,
    viewingFile: Flow<String?>,
    fileContent: Flow<String?>,
    pendingTransfer: Flow<PendingTransfer?>,
    selectedPaths: Flow<Set<String>>,
    editorState: Flow<FileEditorState?>,
    inspectedFile: Flow<FileInfo?>,
    storage: Flow<WorkspaceStorage?>,
    storageLoading: Flow<Boolean>
): Flow<FileManagerScreenState> =
    combine(
        combine(uiState, currentPath, mode, androidStorage, showOverview) { a, b, c, d, e ->
            arrayOf<Any?>(a, b, c, d, e)
        },
        combine(files, visibleFiles, searchQuery, sort, breadcrumbs) { a, b, c, d, e ->
            arrayOf<Any?>(a, b, c, d, e)
        },
        combine(operationMessage, viewingFile, fileContent, pendingTransfer, selectedPaths) { a, b, c, d, e ->
            arrayOf<Any?>(a, b, c, d, e)
        },
        combine(editorState, inspectedFile, storage, storageLoading) { a, b, c, d ->
            arrayOf<Any?>(a, b, c, d)
        }
    ) { core, list, clipboard, extras ->
        FileManagerScreenState(
            uiState = core[0] as FileManagerUiState,
            currentPath = core[1] as String,
            mode = core[2] as FileManagerViewModel.BrowserMode,
            androidStorage = core[3] as FileManagerViewModel.AndroidStorage?,
            showOverview = core[4] as Boolean,
            files = list[0] as List<FileInfo>,
            visibleFiles = list[1] as List<FileInfo>,
            searchQuery = list[2] as String,
            sort = list[3] as FileListSort,
            breadcrumbs = list[4] as List<String>,
            operationMessage = clipboard[0] as String?,
            viewingFile = clipboard[1] as String?,
            fileContent = clipboard[2] as String?,
            pendingTransfer = clipboard[3] as PendingTransfer?,
            selectedPaths = clipboard[4] as Set<String>,
            editorState = extras[0] as FileEditorState?,
            inspectedFile = extras[1] as FileInfo?,
            storage = extras[2] as WorkspaceStorage?,
            storageLoading = extras[3] as Boolean
        )
    }

/**
 * §1.115: a cut/copied entry awaiting a paste. Issue #9: one OR MANY items —
 * the single-item clipboard becomes the multi-select batch case unchanged.
 */
data class PendingTransfer(
    val items: List<FileInfo>,
    val move: Boolean
) {
    constructor(item: FileInfo, move: Boolean) : this(listOf(item), move)

    /** The single-item shape, for callers that display the pending entry. */
    val first: FileInfo? get() = items.firstOrNull()
}

/**
 * Issue #9 (2026-09-03): the text editor's state — the loaded buffer, its
 * saved baseline ([original]) for the dirty indicator, and busy flags.
 */
data class FileEditorState(
    val path: String,
    val name: String,
    val original: String = "",
    val text: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false
) {
    val isDirty: Boolean get() = text != original
}

sealed interface FileManagerUiState {
    data object Unbound : FileManagerUiState
    data object Loading : FileManagerUiState
    data object Ready : FileManagerUiState

    /** §1.115: a listing that failed, with the guest's reason. */
    data class Error(val message: String) : FileManagerUiState
}
