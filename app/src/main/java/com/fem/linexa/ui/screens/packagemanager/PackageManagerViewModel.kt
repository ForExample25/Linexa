package com.fem.linexa.ui.screens.packagemanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.packagemanager.AvailablePkg
import com.fem.linexa.domain.packagemanager.UpdateCommandResolver
import com.fem.linexa.domain.packagemanager.InstalledPkg
import com.fem.linexa.domain.packagemanager.OperationEvent
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.packagemanager.PackageManagerRegistry
import com.fem.linexa.domain.packagemanager.PackageLockDiagnostics
import com.fem.linexa.domain.packagemanager.PackageOperation
import com.fem.linexa.domain.packagemanager.PackageOperationCoordinator
import com.fem.linexa.domain.packagemanager.PackageProvider
import com.fem.linexa.domain.packagemanager.PkgManagerInfo
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §1.92 — ViewModel for the Package Manager screen.
 *
 * Detection runs once per workspace (cached in [providerCache]); the
 * resolved [PackageProvider] is the only backend the UI talks to. Installed
 * metadata is cached so recomposition never re-queries the workspace; Store
 * results are paged manually (offset/limit) into the [StoreState.Loaded]
 * list. Operations stream [OperationEvent]s into [PackageManagerUiState.Ready.operation]
 * so the screen can render a live progress panel.
 */
@HiltViewModel
class PackageManagerViewModel @Inject constructor(
    application: Application,
    private val registry: PackageManagerRegistry,
    private val workspaceRootFsMetadata: WorkspaceRootFsMetadata,
    /**
     * Update System (§24): the workspace repository, so the PM key this
     * detection resolves is persisted into the workspace attributes — the
     * Commands screen's "Update System" card reads the same key to run the
     * exact per-PM update command instead of the generic dispatcher.
     */
    private val workspaceRepository: com.fem.linexa.domain.workspace.WorkspaceRepository,
    /**
     * PKG-1: the existing singleton queue. The screen only READS its
     * `snapshotOf(workspaceId)` StateFlow — scheduling, locking and execution
     * stay entirely in the domain.
     */
    private val packageOperationCoordinator: PackageOperationCoordinator
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PackageManagerViewModel"
        private const val PAGE_SIZE = 50
        /** Bounded tail of live operation output kept for the progress panel. */
        private const val MAX_OUTPUT_CHARS = 12_000
    }

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null
    private var provider: PackageProvider? = null

    /** Provider cache per workspace — detection is a one-shot probe per workspace. */
    private val providerCache = mutableMapOf<String, PackageProvider>()

    private var installedJob: Job? = null
    private var storeJob: Job? = null
    private var operationJob: Job? = null
    private var queueJob: Job? = null

    private val _uiState = MutableStateFlow<PackageManagerUiState>(PackageManagerUiState.Unbound)
    val uiState: StateFlow<PackageManagerUiState> = _uiState.asStateFlow()

    /**
     * PKG-1: the workspace's package-operation queue, projected from the
     * coordinator's existing snapshot. Empty until [attachBinder] installs the
     * workspace and then updated reactively — no polling and no second queue.
     */
    private val _queue = MutableStateFlow(PackageQueueUi())
    val queue: StateFlow<PackageQueueUi> = _queue.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * §1.147 — Installed-tab ordering. Held on the ViewModel (not the
     * composable) so it survives recomposition and tab switches, and applied
     * by [sortInstalled] at the point the list is written into state, so the
     * rendered order and the state are never out of step.
     */
    private val _installedSort = MutableStateFlow(InstalledSort.NAME)
    val installedSort: StateFlow<InstalledSort> = _installedSort.asStateFlow()

    fun onInstalledSortChange(sort: InstalledSort) {
        if (_installedSort.value == sort) return
        _installedSort.value = sort
        // Re-sort what is already loaded rather than re-querying the guest:
        // ordering is a pure view concern over the same package list.
        updateInstalledState { state ->
            if (state is InstalledState.Loaded) state.copy(packages = sortInstalled(state.packages, sort)) else state
        }
    }

    fun attachBinder(wsId: String, runtimeBinder: RuntimeBinder) {
        // Pre-release audit fix (2026-09-02, finding U3/H-3): the old guard
        // refused a REPLACEMENT binder — after RuntimeService is destroyed
        // and re-created, every other screen VM (FileManager, Terminals,
        // Features) adopts the new binder via its identity check while this
        // one kept the dead instance, so package operations silently targeted
        // a destroyed service. Match the identity-checked contract.
        val sameBinder = runtimeBinder === binder && workspaceId == wsId
        if (workspaceId == wsId && sameBinder) return
        workspaceId = wsId
        binder = runtimeBinder
        startQueueObservation(wsId)
        detectAndLoad()
    }

    fun detachBinder() {
        binder = null
        workspaceId = null
        provider = null
        installedJob?.cancel()
        storeJob?.cancel()
        operationJob?.cancel()
        queueJob?.cancel()
        _queue.value = PackageQueueUi()
        _uiState.value = PackageManagerUiState.Unbound
        _searchQuery.value = ""
    }

    /**
     * PKG-1 — collect the coordinator's StateFlow for as long as this screen's
     * ViewModel is attached. `snapshotOf` is cold-start-safe and already
     * shared; the job is cancelled on [detachBinder] rather than replaced by
     * polling.
     */
    private fun startQueueObservation(wsId: String) {
        queueJob?.cancel()
        _queue.value = PackageQueueUi()
        queueJob = viewModelScope.launch {
            packageOperationCoordinator.snapshotOf(wsId).collect { snapshot ->
                _queue.value = PackageQueuePresentation.from(snapshot, ownOperationRunning())
            }
        }
    }

    private fun ownOperationRunning(): Boolean =
        (_uiState.value as? PackageManagerUiState.Ready)?.operation?.status == OperationStatus.RUNNING

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    // ── detection ───────────────────────────────────────────────────────────

    private fun detectAndLoad() {
        val b = binder ?: return
        val ws = workspaceId ?: return
        _uiState.value = PackageManagerUiState.Detecting
        viewModelScope.launch {
            try {
                providerCache[ws]?.let {
                    provider = it
                    loadInstalled()
                    return@launch
                }
                val key = PackageManagerDetector.detect(
                    runtimeBinder = b,
                    workspaceId = ws,
                    // §1.118: when the rootfs declared its package manager,
                    // hand it to the detector so it can confirm that one
                    // manager instead of running the full five-way `which` +
                    // os-release tie-break. A wrong declaration falls back to
                    // the normal probe inside detect(), so this is a speedup,
                    // not a new source of truth.
                    declaredType = workspaceRootFsMetadata.of(ws)?.packageManager?.type
                )
                val resolved = key?.let { registry.providerFor(it) }
                if (resolved == null) {
                    _uiState.value = PackageManagerUiState.Unsupported(
                        reason = key?.let { "unsupported_pm_$it" } ?: "no_pm"
                    )
                    return@launch
                }
                providerCache[ws] = resolved
                provider = resolved
                // §24: remember the resolved key for the Update System
                // shortcut. Fail-open persistence — a failed write changes
                // nothing about package-manager operation itself.
                if (key != null) {
                    runCatching {
                        workspaceRepository.updateAttributes(ws, mapOf(UpdateCommandResolver.ATTR_DETECTED_PM to key))
                    }.onFailure { Logger.w(TAG, "Could not persist detected PM key: ${it.message}") }
                }
                val info = resolved.metadata(b, ws)
                _uiState.value = PackageManagerUiState.Ready(
                    manager = info,
                    installed = InstalledState.Loading,
                    store = StoreState.Idle(_searchQuery.value),
                    operation = null
                )
                loadInstalled()
                // §1.147: the Store deliberately does NOT preload a catalog.
                // It stays Idle until the user searches, so no package list and
                // no "N packages available" total is shown before then.
            } catch (e: CancellationException) {
                // Issue #1 fix (2026-09-03): detection cancellation is not a
                // detection failure. This catch sat below the U5 rethrow
                // pattern used by the store/operation lanes, so a cancelled
                // detect could land as "Detection failed: StandaloneCoroutine
                // was cancelled". Cancellation propagates; real failures are
                // still mapped below.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Detection failed", e)
                _uiState.value = PackageManagerUiState.Error("Detection failed: ${e.message}")
            }
        }
    }

    // ── installed tab ───────────────────────────────────────────────────────

    private fun loadInstalled(force: Boolean = false) {
        val p = provider ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        // Issue #1 fix (2026-09-03): coalesce, don't clobber. The nav
        // lifecycle fires ON_RESUME right after the first composition, so the
        // resume-refresh used to cancel the initial fetch mid-flight — and
        // the cancelled coroutine then wrote
        // Failed("Failed to load installed packages: StandaloneCoroutine was
        // cancelled") into the state the new fetch was about to replace.
        // That is the exact flash the user saw on every open. Two guards:
        //
        //  1. **Coalesce** (below): a refresh that arrives while a load is
        //     already running does NOT cancel it — the running fetch is
        //     already delivering fresh data, and skipping removes both the
        //     duplicate `dpkg-query` process and the cancellation itself.
        //     Operation-completion refreshes pass [force]=true: their
        //     predecessor's data predates the mutation, so a restart is
        //     genuinely required.
        //  2. **Rethrow + ensureActive** (in the job): any OTHER cancellation
        //     source (screen close, binder swap) must not write a Failed row
        //     from a dead coroutine — the U5 contract the store/operation
        //     lanes already follow, now applied to the installed lane too.
        if (!force && shouldCoalesceInstalledLoad(installedJob?.isActive == true)) return
        installedJob?.cancel()
        installedJob = viewModelScope.launch {
            // Only an already-loaded list is re-marked as refreshing; the
            // initial Loading state already renders the spinner (§1.93).
            updateInstalledState { if (it is InstalledState.Loaded) it.copy(refreshing = true) else it }
            val result = runCatching { p.listInstalled(b, ws) }
            // A cancelled fetch must not write a Failed row: runCatching
            // captured the CancellationException, so re-check the job before
            // treating the result as terminal. A cancelled coroutine ends
            // cancelled — the U5 contract the store/operation lanes follow.
            coroutineContext.ensureActive()
            result.exceptionOrNull()?.let { Logger.e(TAG, "listInstalled failed", it) }
            updateInstalledState { installedAfterFetch(result, _installedSort.value) }
        }
    }

    fun refreshInstalled() = loadInstalled(force = false)

    private fun updateInstalledState(transform: (InstalledState) -> InstalledState) {
        _uiState.value = applyInstalledUpdate(_uiState.value, transform)
    }

    // ── store tab ───────────────────────────────────────────────────────────

    fun search() {
        storeJob?.cancel()
        _searchQuery.value = _searchQuery.value.trim()
        loadStoreFirstPage()
    }

    private fun loadStoreFirstPage() {
        val p = provider ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) {
            updateStoreState { StoreState.Idle("") }
            return
        }
        storeJob?.cancel()
        storeJob = viewModelScope.launch {
            updateStoreState { StoreState.Loading(query) }
            try {
                val page = p.search(b, ws, query, PAGE_SIZE, 0)
                updateStoreState {
                    StoreState.Loaded(
                        query = query,
                        packages = page.packages,
                        total = page.total,
                        hasMore = page.packages.isNotEmpty() && page.packages.size == PAGE_SIZE,
                        loadingMore = false,
                        refreshing = false
                    )
                }
            } catch (e: CancellationException) {
                // U5 fix: a cancelled search must not overwrite the next
                // search's Loading/Loaded state with a Failed row.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Store search failed", e)
                updateStoreState { StoreState.Failed(query, "Search failed: ${e.message}") }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state !is PackageManagerUiState.Ready) return
        val store = state.store as? StoreState.Loaded ?: return
        if (store.loadingMore || store.refreshing || !store.hasMore) return
        val p = provider ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        storeJob?.cancel()
        storeJob = viewModelScope.launch {
            _uiState.value = state.copy(store = store.copy(loadingMore = true))
            try {
                val page = p.search(b, ws, store.query, PAGE_SIZE, store.packages.size)
                val merged = store.packages + page.packages
                updateStoreState {
                    store.copy(
                        packages = merged,
                        total = page.total,
                        hasMore = page.packages.isNotEmpty() && page.packages.size == PAGE_SIZE,
                        loadingMore = false
                    )
                }
            } catch (e: CancellationException) {
                // U5 fix: same contract as the search path.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Store loadMore failed", e)
                updateStoreState { store.copy(loadingMore = false) }
            }
        }
    }

    // §1.147: there is no separate `refreshStore()`. The top bar's refresh
    // runs `refreshSources` (the manager's real `update`/`-Sy`/`makecache`) and
    // then re-runs the active query, so re-searching against stale metadata is
    // one action rather than two entry points that could disagree.

    private fun updateStoreState(transform: (StoreState) -> StoreState) {
        val current = _uiState.value
        if (current is PackageManagerUiState.Ready) {
            _uiState.value = current.copy(store = transform(current.store))
        }
    }

    // ── operations ──────────────────────────────────────────────────────────

    fun installPackage(packageName: String) = runOperation(PackageOperation.INSTALL, packageName) { p, b, ws ->
        p.install(b, ws, packageName)
    }

    fun removePackage(packageName: String) = runOperation(PackageOperation.REMOVE, packageName) { p, b, ws ->
        p.remove(b, ws, packageName)
    }

    fun updatePackage(packageName: String) {
        // Unified update: re-installing an installed package upgrades it on
        // every supported manager (apt-get install, pacman -S, apk add, ...).
        installPackage(packageName)
    }

    fun refreshSources() = runOperation(PackageOperation.REFRESH, null) { p, b, ws ->
        p.refreshSources(b, ws)
    }

    fun upgradeAllPackages() = runOperation(PackageOperation.UPGRADE, null) { p, b, ws ->
        p.upgrade(b, ws)
    }

    fun dismissOperation() {
        val state = _uiState.value
        if (state is PackageManagerUiState.Ready && state.operation != null && state.operation.status != OperationStatus.RUNNING) {
            _uiState.value = state.copy(operation = null)
        }
    }

    private fun runOperation(
        operation: PackageOperation,
        packageName: String?,
        launch: suspend (PackageProvider, RuntimeBinder, String) -> kotlinx.coroutines.flow.Flow<OperationEvent>
    ) {
        val p = provider ?: return
        val b = binder ?: return
        val ws = workspaceId ?: return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            setOperation(OperationUi(operation, packageName, "", OperationStatus.RUNNING))
            try {
                launch(p, b, ws).collect { event ->
                    when (event) {
                        is OperationEvent.Progress -> appendOperationOutput(event.outputTail)
                        is OperationEvent.Success -> {
                            setOperation(
                                OperationUi(
                                    operation = operation,
                                    packageName = packageName,
                                    output = event.output.takeLast(MAX_OUTPUT_CHARS),
                                    status = OperationStatus.SUCCESS
                                )
                            )
                            // Issue #1 (2026-09-03): forced — the running state
                            // predates this mutation, so an in-flight load must be
                            // replaced, not coalesced (coalescing would show the
                            // pre-operation package list).
                            loadInstalled(force = true)
                            // §1.147: only re-run the Store query if the user
                            // already searched. Refreshing sources must not
                            // materialise a catalog the user never asked for.
                            if (operation == PackageOperation.REFRESH && _searchQuery.value.isNotBlank()) {
                                loadStoreFirstPage()
                            }

                        }
                        is OperationEvent.Failure -> setOperation(
                            OperationUi(
                                operation = operation,
                                packageName = packageName,
                                output = event.output.takeLast(MAX_OUTPUT_CHARS),
                                status = OperationStatus.FAILED,
                                // Finding 021: the app cannot queue a package
                                // command the user typed into a terminal. Name
                                // the holder when apt/dpkg did; never kill it.
                                lockContention = PackageLockDiagnostics.detect(event.output)
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Pre-release audit fix (2026-09-02, finding U5/M-2): the old
                // generic catch turned a cancelled operation into a FAILED
                // operation row — and because runOperation cancels the
                // previous job right before starting a new one, the OLD
                // coroutine's catch fired AFTER the new operation had set
                // RUNNING, clobbering the fresh state with the old failure.
                // Cancellation is not an operation result: rethrow.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Operation $operation failed", e)
                setOperation(
                    OperationUi(
                        operation = operation,
                        packageName = packageName,
                        output = e.message.orEmpty(),
                        status = OperationStatus.FAILED,
                        lockContention = PackageLockDiagnostics.detect(e.message.orEmpty())
                    )
                )
            }
        }
    }

    private fun setOperation(op: OperationUi) {
        val state = _uiState.value
        if (state is PackageManagerUiState.Ready) {
            _uiState.value = state.copy(operation = op)
        }
    }

    private fun appendOperationOutput(tail: String) {
        val state = _uiState.value
        if (state is PackageManagerUiState.Ready && state.operation != null && state.operation.status == OperationStatus.RUNNING) {
            val current = state.operation.output
            val combined = if (current.isEmpty()) tail else "$current\n$tail"
            _uiState.value = state.copy(operation = state.operation.copy(output = combined.takeLast(MAX_OUTPUT_CHARS)))
        }
    }
}

// ── UI state ────────────────────────────────────────────────────────────────

sealed interface PackageManagerUiState {
    data object Unbound : PackageManagerUiState
    data object Detecting : PackageManagerUiState

    /** [reason] is a stable code (`no_pm`, `unsupported_pm_<key>`) for string lookup. */
    data class Unsupported(val reason: String) : PackageManagerUiState
    data class Error(val message: String) : PackageManagerUiState
    data class Ready(
        val manager: PkgManagerInfo,
        val installed: InstalledState,
        val store: StoreState,
        val operation: OperationUi?
    ) : PackageManagerUiState
}

sealed interface InstalledState {
    data object Loading : InstalledState
    data class Loaded(
        val packages: List<InstalledPkg>,
        val refreshing: Boolean
    ) : InstalledState
    data class Failed(val message: String) : InstalledState
}

sealed interface StoreState {
    data class Idle(val query: String) : StoreState
    data class Loading(val query: String) : StoreState
    data class Loaded(
        val query: String,
        val packages: List<AvailablePkg>,
        val total: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean,
        val refreshing: Boolean
    ) : StoreState
    data class Failed(val query: String, val message: String) : StoreState
}

enum class OperationStatus { RUNNING, SUCCESS, FAILED }

data class OperationUi(
    val operation: PackageOperation,
    val packageName: String?,
    val output: String,
    val status: OperationStatus,
    /**
     * Finding 021: set only when a failed operation's output names a package
     * lock held by another process. The UI explains it; nothing is killed or
     * queued after the fact.
     */
    val lockContention: PackageLockDiagnostics.Holder? = null
)

// ── pure installed-tab transitions (JVM-tested) ─────────────────────────────

/**
 * §1.147 — Installed-tab ordering the user can choose. Only the three orders
 * the list can actually be sorted by from data the providers really return:
 * name, whether an update is available, and installed size.
 */
enum class InstalledSort { NAME, UPDATES, SIZE }

/**
 * §1.147 — Pure ordering for the Installed list.
 *
 * - [InstalledSort.NAME] is case-insensitive alphabetical.
 * - [InstalledSort.UPDATES] floats the packages with an available update to
 *   the top (the reason to open this sort at all), alphabetical within each
 *   group, so it stays a total order rather than an unstable partition.
 * - [InstalledSort.SIZE] is largest first, and packages whose manager reported
 *   no size sink to the bottom instead of being treated as 0 bytes — an
 *   unknown size is not a small size.
 */
internal fun sortInstalled(packages: List<InstalledPkg>, sort: InstalledSort): List<InstalledPkg> = when (sort) {
    InstalledSort.NAME -> packages.sortedBy { it.name.lowercase() }
    InstalledSort.UPDATES -> packages.sortedWith(
        compareByDescending<InstalledPkg> { it.hasUpdate }.thenBy { it.name.lowercase() }
    )
    InstalledSort.SIZE -> packages.sortedWith(
        compareByDescending<InstalledPkg> { it.installedSizeBytes ?: -1L }.thenBy { it.name.lowercase() }
    )
}

/**
 * Issue #1 (2026-09-03) — pure decision for the installed-tab load coalescing:
 * a refresh that arrives while a load is ALREADY running is absorbed by the
 * running fetch instead of cancelling and restarting it. The nav lifecycle's
 * ON_RESUME fires right after the first composition — i.e. while the initial
 * fetch is in flight — and the old cancel-then-restart turned that innocent
 * refresh into the "Failed to load installed packages: StandaloneCoroutine
 * was cancelled" flash plus a duplicate `dpkg-query` process.
 */
internal fun shouldCoalesceInstalledLoad(loadInFlight: Boolean): Boolean = loadInFlight

/**
 * §1.93 — Terminal [InstalledState] after a `listInstalled` fetch completes,
 * REPLACING whatever the current state was (including the initial
 * [InstalledState.Loading]). A fetch result must never be gated on the prior
 * state being [InstalledState.Loaded] — that gate (the pre-§1.93
 * `updateInstalledState` guard) kept the Installed tab on
 * [InstalledState.Loading] forever and silently swallowed the Failed path too,
 * so neither packages nor an error could ever render.
 *
 * §1.147: the successful list is ordered by [sort] here, so the state always
 * holds the list in the order the UI renders it.
 */
internal fun installedAfterFetch(
    fetch: Result<List<InstalledPkg>>,
    sort: InstalledSort = InstalledSort.NAME
): InstalledState = fetch.fold(
    onSuccess = { InstalledState.Loaded(packages = sortInstalled(it, sort), refreshing = false) },
    onFailure = { InstalledState.Failed("Failed to load installed packages: ${it.message}") }
)

/**
 * §1.93 — Pure guard for writing a new [InstalledState]: applies [transform]
 * once the parent state is [PackageManagerUiState.Ready] (before that the
 * screen isn't even showing the tab). Any [InstalledState] may be transformed
 * — Loading → Loaded/Failed, Loaded → refreshed Loaded, Failed → Loaded
 * recovery — so a terminal repository result can never be dropped and
 * [InstalledState.Loading] can never outlive its fetch.
 */
internal fun applyInstalledUpdate(
    uiState: PackageManagerUiState,
    transform: (InstalledState) -> InstalledState
): PackageManagerUiState =
    if (uiState is PackageManagerUiState.Ready) {
        uiState.copy(installed = transform(uiState.installed))
    } else {
        uiState
    }
