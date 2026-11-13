package com.fem.linexa.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.version.AppVersionProvider
import com.fem.linexa.domain.whatsnew.ReleaseNotes
import com.fem.linexa.domain.whatsnew.ReleaseNotesCatalog
import com.fem.linexa.domain.whatsnew.WhatsNewDecision
import com.fem.linexa.domain.workspace.WorkspaceListEntry
import com.fem.linexa.domain.workspace.WorkspaceListOptions
import com.fem.linexa.domain.workspace.WorkspaceListPolicy
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatusFilter
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.workspace.WorkspaceDeleter
import com.fem.linexa.ui.theme.LinexaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: WorkspaceRepository,
    private val fileManager: WorkspaceFileManager,
    private val workspaceDeleter: WorkspaceDeleter,
    private val rootFsMetadata: WorkspaceRootFsMetadata,
    private val settingsRepository: SettingsRepository,
    private val appVersionProvider: AppVersionProvider
) : AndroidViewModel(application) {

    private val _workspaces = MutableStateFlow<List<WorkspaceMetadata>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceMetadata>> = _workspaces.asStateFlow()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * The last enriched rendering per workspace id, paired with the exact
     * [WorkspaceMetadata] row it was derived from. Touched only inside
     * [workspacesUi]'s `transformLatest` block — one sequential collector at a
     * time (`transformLatest` cancels the previous one before starting a new
     * one) — so a plain map is safe. Survives `WhileSubscribed` pipeline
     * restarts (it is a field, not flow state), which is the whole point: a
     * re-subscribed Stage 1 can repaint the previously enriched cards instead
     * of regressing to the stripped fast shape.
     */
    private val enrichmentCache = mutableMapOf<String, Pair<WorkspaceMetadata, Workspace>>()

    /**
     * Presentation-ready workspace cards (metadata + cached disk usage +
     * declared preview).
     *
     * Room re-emits the whole list on *every* DB write — including each
     * Importing progress tick and status flip during an install — so this
     * flow debounces bursts and only re-derives disk usage when the set of
     * workspaces actually changes ([distinctUntilChanged]). The per-item
     * size lookups hit [WorkspaceFileManager]'s cache after the first read,
     * and the whole mapping runs off the main thread.
     *
     * §1.124: the declared preview is resolved here, on the same IO dispatcher,
     * because [WorkspaceRootFsMetadata.previewImage] canonicalizes a path
     * against the live filesystem (§1.121(b) — it is intentionally not
     * memoized, so it must never run on the main thread). Re-resolving it on
     * every emission is the point of that decision, not a cost to avoid: a
     * preview that has since been replaced with a symlink out of the rootfs
     * stops resolving, and the card falls back to the glyph.
     *
     * **Staged emission (Phase 6b bug-fix):** the cold-cache cost of
     * [WorkspaceFileManager.getWorkspaceSize] — a `Files.walkFileTree` over
     * the entire rootfs — is seconds, not milliseconds, for a multi-GB
     * Debian/Ubuntu image. Running it serially across N workspaces inside
     * `mapLatest` blocked the *first* paint of the list behind every
     * workspace's full disk walk, so the screen read as "shows late and
     * hangs a bit" the moment the user had even one big image installed.
     * `transformLatest` lets the flow emit twice:
     *
     * 1. **Fast emit.** A list of [Workspace] objects with `storageUsedLabel`
     *    = null, `storageUsedFraction` = 0f, `previewImage` = null and
     *    `declaration` = [RootFsDescriptor.UNDECLARED] — i.e. the Room row's
     *    own distro facts only. `HavenWorkspaceCard` already handles every one of
     *    these nulls (omits the size line, falls back to the letter avatar,
     *    derives distro facts from the Room `distro` field) so the list paints
     *    immediately.
     *
     * 2. **Per-item enrichment.** Each workspace's `getDiskUsage`,
     *    `declaredPreview` and `declaration` is then computed in list order,
     *    re-emitting the list after each completion so the corresponding card
     *    snaps from the fast-fallback to its real fields. `LazyColumn` keys
     *    by `Workspace.id`, so only the one card whose `Workspace` changed
     *    recomposes; the rest stay still.
     *
     * Re-entrancy-safe: `transformLatest` cancels the previous collector on
     * every upstream emission, so a Room tick that lands while enrichment is
     * in flight drops the slow work in favour of a fresh fast-emit. No
     * enrichment ever lands on a stale `WorkspaceMetadata` list.
     *
     * **Enrichment reuse (tab-switch jank fix):** `WhileSubscribed(5_000)`
     * stops this pipeline whenever Home has been off-screen for a while, and a
     * restart used to re-run Stage 1 from scratch — re-emitting the *stripped*
     * shape and stripping the size lines and preview images off cards the user
     * had fully painted a moment earlier, then re-enriching them one by one.
     * That regression was exactly the visible flash/jump on every return to
     * Home from a workspace (or another tab), and it also re-ran the per-card
     * preview/declaration file IO. [enrichmentCache] now remembers each row's
     * last enriched rendering keyed by the source `WorkspaceMetadata`'s
     * structural equality: Stage 1 re-emits the *cached enriched* card for an
     * unchanged row (identical content — no visual change at all) and Stage 2
     * skips re-reading the filesystem for it. A changed row (status flip,
     * install progress tick) misses the cache and re-enriches, unchanged.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val workspacesUi: StateFlow<List<Workspace>> = repository.observeAll()
        .distinctUntilChanged()
        .debounce(150)
        .transformLatest { list ->
            // Stage 1 — fast emit: paint every card from the Room row alone.
            // toUiWorkspace() with its default args produces the "no enrichment"
            // shape (null storage / null preview / UNDECLARED declaration) that
            // HavenWorkspaceCard already renders without a size line and without a
            // branded glyph — but a row whose enrichment is already cached
            // re-emits that cached rendering instead, so a pipeline restart
            // (returning to Home after WhileSubscribed stopped it) repaints
            // exactly what was on screen rather than regressing to the
            // stripped shape.
            val fast = list.map { metadata ->
                val cached = enrichmentCache[metadata.id]
                if (cached != null && cached.first == metadata) {
                    cached.second
                } else {
                    metadata.toUiWorkspace()
                }
            }
            emit(fast)

            // Drop cache entries for workspaces that no longer exist, so the
            // cache can never outlive its rows (delete/reinstall cannot grow
            // it unboundedly or resurrect stale fields).
            val currentIds = list.mapTo(HashSet()) { it.id }
            enrichmentCache.keys.retainAll(currentIds)

            // Stage 2 — per-item enrichment, re-emitting after each completion
            // so a card snaps from the fast-fallback to its real fields the
            // moment its data is ready. Serial on purpose: parallelising with
            // `async` would still cap the parallelism at the IO dispatcher's
            // default of 64, but would emit in completion order rather than list
            // order — visually noisier, and the dominant cost (the rootfs walk
            // for the workspace size) is bound by the same IO subsystem either
            // way. Re-emitting the entire list per completion is cheap:
            // LazyColumn diffs by `Workspace.id`, so only the one changed card
            // recomposes. Rows whose cache entry is still fresh (source row
            // unchanged) skip the IO entirely.
            val enriched = fast.toMutableList()
            for (i in list.indices) {
                val metadata = list[i]
                if (enrichmentCache[metadata.id]?.first == metadata) continue
                val (storageLabel, storageFraction) = withContext(Dispatchers.IO) {
                    getDiskUsage(metadata.id)
                }
                val preview = withContext(Dispatchers.IO) { declaredPreview(metadata.id) }
                val declaration = withContext(Dispatchers.IO) { declaration(metadata.id) }
                val enrichedWorkspace = metadata.toUiWorkspace(
                    storageUsedLabel = storageLabel,
                    storageUsedFraction = storageFraction,
                    previewImage = preview,
                    declaration = declaration
                )
                enrichmentCache[metadata.id] = metadata to enrichedWorkspace
                enriched[i] = enrichedWorkspace
                emit(enriched.toList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Search / sort / filter (P3) ────────────────────────────────────────

    private val _listOptions = MutableStateFlow(WorkspaceListOptions())

    /**
     * The user's current search, status filter and sort for the Home list. In-memory only:
     * it survives rotation (this ViewModel does) but deliberately not a process restart —
     * a search left in a box days ago should not greet the user with a half-empty list.
     */
    val listOptions: StateFlow<WorkspaceListOptions> = _listOptions.asStateFlow()

    /**
     * [workspacesUi] after the search, filter and sort — what the list renders.
     *
     * Derived *downstream* of [workspacesUi] and never feeding back into it, so the staged
     * enrichment pipeline above, its debounce and its [enrichmentCache] are exactly as they
     * were: typing a query re-ranks the already-enriched cards and touches no disk.
     * [workspacesUi] itself stays the unfiltered total — the header count and the bottom
     * navigation badge read that, and must not change as the user narrows the list.
     */
    val visibleWorkspaces: StateFlow<List<Workspace>> =
        combine(workspacesUi, _listOptions) { list, options -> visibleOf(list, options) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                visibleOf(workspacesUi.value, _listOptions.value)
            )

    private fun visibleOf(list: List<Workspace>, options: WorkspaceListOptions): List<Workspace> =
        WorkspaceListPolicy.apply(
            items = list,
            // Below the toolbar threshold the options can neither be seen nor cleared, so
            // they must not hide anything.
            options = WorkspaceListPolicy.effective(options, list.size),
            entryOf = Workspace::toListEntry
        )

    fun onQueryChange(query: String) {
        _listOptions.update { it.copy(query = query.take(WorkspaceListPolicy.MAX_QUERY_LENGTH)) }
    }

    fun onFilterChange(filter: WorkspaceStatusFilter) {
        _listOptions.update { it.copy(filter = filter) }
    }

    /** Advances the single sort chip: Newest → Oldest → Name → Newest. */
    fun onSortCycle() {
        _listOptions.update { it.copy(sort = it.sort.next()) }
    }

    /** Clears the search and the status filter; the chosen sort is kept. */
    fun clearSearchAndFilters() {
        _listOptions.update { it.copy(query = "", filter = WorkspaceStatusFilter.ALL) }
    }

    init {
        observeWorkspaces()
    }

    /**
     * (Re)starts observing the persisted workspace list. Called from [init] and
     * from the Home error state's Retry action. Room-backed flows don't emit
     * exceptions in practice, but the collector is guarded so a failure
     * surfaces as [HomeUiState.Error] instead of silently freezing on Loading.
     */
    private fun observeWorkspaces() {
        // Pre-release audit fix (2026-09-02, finding L-1): cancel the previous
        // collector before starting a new one. refresh() used to stack a
        // duplicate Room collector on every Retry tap — each duplicate ran
        // the full enrichment pipeline per emission, unbounded across
        // repeated retries.
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            try {
                repository.observeAll().collect { workspaces ->
                    _workspaces.value = workspaces
                    // P3: the toolbar disappears below the threshold, so a leftover query
                    // or filter would be invisible and un-clearable — and would reappear
                    // unannounced when the list grows back. Reset it with the list.
                    if (!WorkspaceListPolicy.showsToolbar(workspaces.size)) {
                        _listOptions.update { WorkspaceListOptions() }
                    }
                    _uiState.value = if (workspaces.isEmpty()) {
                        HomeUiState.Empty
                    } else {
                        HomeUiState.HasWorkspaces
                    }
                }
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message)
            }
        }
    }

    fun refresh() = observeWorkspaces()

    /** The single Room collector for this ViewModel (L-1 fix: never stacked). */
    private var collectJob: kotlinx.coroutines.Job? = null

    // ── What's New ─────────────────────────────────────────────────────────

    /** Whether the user has tapped to see a long changelog in full. Not persisted. */
    private val _whatsNewExpanded = MutableStateFlow(false)

    /**
     * The release notes to show on Home, or null for "show nothing".
     *
     * Derived rather than assigned: it combines the persisted high-water mark with the
     * running build's identity through [WhatsNewDecision], so acknowledging the card
     * removes it in the same emission that writes the preference — there is no separate
     * "hide it now" signal that could disagree with what was stored.
     *
     * [WhatsNewDecision.Outcome.EstablishBaseline] is handled here, as a side effect on
     * a fresh install: nothing is shown, and the current version is recorded so the
     * *next* update has something to compare against. Writing from inside a flow
     * mapping is deliberate and safe — the write is idempotent (it stores the same
     * value the decision was made from), and it re-emits through
     * `observeLastAcknowledgedWhatsNewVersion`, at which point the decision returns
     * `Hide` and settles. The alternative, a `LaunchedEffect` in the composable, would
     * put the one piece of business logic this feature has into the UI layer.
     */
    val whatsNew: StateFlow<WhatsNewUiState?> = combine(
        settingsRepository.observeLastAcknowledgedWhatsNewVersion(),
        _whatsNewExpanded
    ) { acknowledged, expanded ->
        val outcome = WhatsNewDecision.decide(
            lastAcknowledgedVersionCode = acknowledged,
            currentVersionCode = appVersionProvider.versionCode,
            notes = ReleaseNotesCatalog.forVersion(appVersionProvider.versionCode),
            isFirstInstall = appVersionProvider.isFirstInstall
        )
        when (outcome) {
            is WhatsNewDecision.Outcome.Show ->
                WhatsNewUiState(notes = outcome.notes, expanded = expanded)

            is WhatsNewDecision.Outcome.EstablishBaseline -> {
                settingsRepository.setLastAcknowledgedWhatsNewVersion(outcome.versionCode)
                null
            }

            is WhatsNewDecision.Outcome.Hide -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Acknowledge the currently shown changelog.
     *
     * Records the version the card was showing rather than the running build's, so the
     * write can only ever mark as seen what was actually on screen. The two are the
     * same value today — the decision only ever shows the running build's notes — but
     * reading it from the card keeps that an observation rather than an assumption.
     *
     * Not disabling anything permanently: the next release's `versionCode` is higher
     * than what this stores, so its card appears normally.
     */
    fun acknowledgeWhatsNew() {
        val shown = whatsNew.value?.notes ?: return
        viewModelScope.launch {
            settingsRepository.setLastAcknowledgedWhatsNewVersion(shown.versionCode)
            // So a subsequent card starts collapsed rather than inheriting this one's
            // expansion.
            _whatsNewExpanded.value = false
        }
    }

    /** Toggle a long changelog between the collapsed excerpt and the full list. */
    fun toggleWhatsNewExpanded() {
        _whatsNewExpanded.value = !_whatsNewExpanded.value
    }

    private val _deleteError = MutableStateFlow<String?>(null)

    /**
     * §1.117: a refused delete's reason (teardown could not be confirmed, so
     * nothing was deleted). Null when there is nothing to report.
     */
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    /**
     * Deletes a workspace (DB row + disk).
     *
     * §1.117: goes through [WorkspaceDeleter], which stops the runtime before
     * touching disk. Home can delete a workspace that is *currently running*
     * (the user backgrounds it via the FGS notification's Open, returns Home,
     * and deletes the card), so this needs the same ordering guarantee as the
     * Danger Zone path — deleting the rootfs from under a live PRoot leaks
     * whatever the guest recreates behind `deleteRecursively`'s walk.
     */
    fun deleteWorkspace(id: String) {
        viewModelScope.launch {
            when (val outcome = workspaceDeleter.delete(id)) {
                is WorkspaceDeleter.Outcome.Deleted -> Unit
                is WorkspaceDeleter.Outcome.StopFailed -> {
                    _deleteError.value = outcome.reason
                }
                is WorkspaceDeleter.Outcome.DeleteFailed -> {
                    _deleteError.value = outcome.reason
                }
            }
        }
    }

    /** Clears a surfaced [deleteError] once the UI has shown it. */
    fun clearDeleteError() {
        _deleteError.value = null
    }

    fun renameWorkspace(id: String, newName: String) {
        viewModelScope.launch {
            // Phase 05: atomic read-modify-write — the old getById+update pair
            // could revert a status/attribute change that landed between the
            // read and the write.
            repository.mutate(id) { current -> current.copy(displayName = newName) }
        }
    }

    /**
     * The card's size figure: the **total** on-disk size of the workspace, not
     * just its rootfs — or null when there is nothing to report.
     *
     * UI Phase 2 changed which number this is. The card advertises the total
     * installed size, and `getRootfsSize` excludes the persisted `config.yaml`,
     * the per-workspace `logs/` every install writes, and any resumable-download
     * cache — all of which are bytes that deleting the workspace would reclaim,
     * and all of which the user can see nowhere else. See
     * [WorkspaceFileManager.getWorkspaceSize].
     *
     * **Zero means "not measured", not "empty".** `getWorkspaceSize` returns `0`
     * for a workspace whose directory does not exist — a row persisted before
     * its rootfs, or one being deleted from under the Home list (§1.117) — and an
     * installed workspace is never genuinely zero bytes. Returning null for that
     * case is what lets the card omit the size line instead of stating `0 B`,
     * which would read as a measurement rather than the absence of one.
     *
     * The fraction is unchanged and still cosmetic: a share of a notional 10 GB
     * budget, which is what the hairline rail on the card renders. It is
     * deliberately not a share of real free space — that would make the same
     * workspace draw differently depending on unrelated storage.
     */
    fun getDiskUsage(workspaceId: String): Pair<String?, Float> {
        val size = fileManager.getWorkspaceSize(workspaceId)
        val sizeLabel = if (size > 0L) FileSizeFormatter.format(size) else null
        val fraction = (size.toFloat() / (10 * 1024 * 1024 * 1024)).coerceIn(0f, 1f)
        return Pair(sizeLabel, fraction)
    }

    /**
     * §1.124 — this workspace's declared preview image, or null.
     *
     * Fail-open around the store as well as inside it: `previewImage` is already
     * contract-bound to return null rather than throw, but it touches the
     * filesystem, and a workspace whose directory is being deleted concurrently
     * (Home can delete a running workspace, §1.117) must not turn an avatar
     * lookup into a broken list.
     *
     * Per-workspace by construction — the id is threaded through to
     * [WorkspaceFileManager.getRootfsPath] inside the store, so a preview can
     * only ever resolve inside the rootfs of the workspace it belongs to. There
     * is no shared or app-wide preview path anywhere on this route.
     */
    private fun declaredPreview(workspaceId: String): java.io.File? =
        try {
            rootFsMetadata.previewImage(workspaceId)
        } catch (e: Exception) {
            null
        }

    /**
     * UI Phase 2 — this workspace's declaration, in whichever format the image
     * used, or [RootFsDescriptor.UNDECLARED].
     *
     * `descriptorOf`, not `of`: the card needs the *presentation* half (the
     * image's description) and the distro facts, and `of()` is native-only by
     * definition (§1.123(d)), so a legacy `.tiny.yaml` workspace would lose its
     * description — which is precisely the text §1.119 went to the trouble of
     * salvaging.
     *
     * Fail-open around the store as well as inside it, for the same reason as
     * [declaredPreview]: this runs for every card on every Room emission, and
     * Home can delete a running workspace (§1.117), so a directory disappearing
     * mid-read must degrade one card's subtitle rather than break the list.
     */
    private fun declaration(workspaceId: String): RootFsDescriptor =
        try {
            rootFsMetadata.descriptorOf(workspaceId)
        } catch (e: Exception) {
            RootFsDescriptor.UNDECLARED
        }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data object HasWorkspaces : HomeUiState
    data class Error(val message: String? = null) : HomeUiState
}

/**
 * The What's New card's state: which release to describe, and whether the user has
 * expanded a long list.
 *
 * A card-specific type rather than fields on [HomeUiState] because the two are
 * orthogonal — the changelog appears above the workspace list whether that list is
 * populated, empty, or still loading, and folding it into that sealed interface would
 * multiply its cases for no gain. `null` on the flow means "no card", so absence is
 * not a fourth state here either.
 */
data class WhatsNewUiState(
    val notes: ReleaseNotes,
    val expanded: Boolean
)

private fun WorkspaceMetadata.toUiWorkspace(
    // Defaults produce the "no enrichment" fast-emit shape the Stage-1 call
    // site relies on (no size line, no branded glyph) — documented at the
    // call site since the two-stage pipeline landed, but the defaults
    // themselves were missing, which broke compilation.
    storageUsedLabel: String? = null,
    storageUsedFraction: Float = 0f,
    previewImage: java.io.File? = null,
    declaration: RootFsDescriptor = RootFsDescriptor.UNDECLARED
): Workspace {
    val color = try {
        Color(android.graphics.Color.parseColor(avatarColorHex))
    } catch (e: RuntimeException) {
        // Any failure to parse a *cosmetic* hex string falls back to the brand
        // color. Broader than the IllegalArgumentException parseColor documents
        // on purpose: this mapping is the only thing standing between Room and
        // the entire Home list, so a stored value that upsets the platform
        // parser must degrade to a colored tile, never to an empty screen.
        LinexaColors.Primary
    }
    // UI Phase 2: the distribution facts, declaration first, Room row second.
    //
    // The declaration outranks `DistroInfo` where it exists because it is the
    // image's own statement of what it is, and it carries fields the row has no
    // column for (the codename). The row is never *replaced* though — a legacy
    // workspace, or a native image that declared no version, keeps exactly what
    // was persisted at install, which is why every fallback below is `?:`
    // rather than a branch on `isDeclared`.
    val declared = declaration.metadata
    val catalogId = attributes[DownloadService.ATTR_DISTRO_ID]
    return Workspace(
        id = id,
        name = displayName,
        // Unchanged meaning: the distro summary two other screens already read
        // as one (Home's share intent, DiagnosticViewerScreen's metadata bar).
        description = "${distro.name} ${distro.version}".trim(),
        storageUsedLabel = storageUsedLabel,
        storageUsedFraction = storageUsedFraction,
        avatarLetter = avatarLetter,
        avatarColor = color,
        // Home doesn't bind to RuntimeService today, so live running state
        // isn't available here yet - known follow-up, see roadmap §1.19.
        isRunning = false,
        status = status,
        // Distro icon: catalog installs persist their DistroCatalog id as a
        // workspace attribute ("distroId"); imported workspaces don't, so
        // they keep the letter-avatar fallback in HavenWorkspaceCard.
        //
        // UI Phase 2: a *declared* `distro.id` now also selects the glyph, via
        // the same fixed table. That is what gives an imported image its brand
        // mark — it was never that the icon could not be known, only that the
        // catalog attribute was the sole source. Unknown ids still map to null
        // (no generic "Linux" glyph — that would be fake branding).
        distroId = catalogId ?: declared?.distro?.id,
        // §1.124: the image's own artwork, when it declared some and it still
        // resolves inside this workspace's rootfs. Outranks the glyph above.
        previewImage = previewImage,
        distroName = declared?.distro?.name?.takeIf { it.isNotBlank() }
            ?: distro.name.takeIf { it.isNotBlank() },
        // The short code is an identifier, so a catalog id is as good a source
        // as a declared one; neither is user-entered.
        distroCode = declared?.distro?.id?.takeIf { it.isNotBlank() } ?: catalogId,
        distroVersion = declared?.distro?.version?.takeIf { it.isNotBlank() }
            ?: distro.version.takeIf { it.isNotBlank() },
        // § export: the ABI the install probe or declaration recorded. Not
        // rendered — the suggested export file name consumes it.
        distroArch = declared?.architecture?.abi
            ?: distro.arch.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) },
        // Declaration-only by construction: there is no column and no probe for
        // it, so an undeclared workspace shows no codename rather than a guess.
        distroCodename = declared?.distro?.codename?.takeIf { it.isNotBlank() },
        // §1.119: from `presentation`, not `metadata.presentation`, so a legacy
        // .tiny.yaml image's description reaches the card too.
        imageDescription = declaration.presentation?.description?.takeIf { it.isNotBlank() },
        createdAt = createdAt
    )
}

/**
 * What the Home search matches on for one card: the user's name for it first, then the
 * distro summary, version, codename, id and the image's own description — the same facts
 * the card shows, so a query finds what the user can see.
 */
private fun Workspace.toListEntry(): WorkspaceListEntry = WorkspaceListEntry(
    name = name,
    extras = listOfNotNull(
        description.takeIf { it.isNotBlank() },
        distroName,
        distroVersion,
        distroCodename,
        distroCode,
        imageDescription
    ),
    status = status,
    createdAt = createdAt
)
