package com.fem.linexa.ui.screens.workspace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.Feature
import com.fem.linexa.data.Workspace
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.resolveFeatureToggles
import com.fem.linexa.domain.commands.CustomCommand
import com.fem.linexa.domain.commands.CustomCommandsCodec
import com.fem.linexa.domain.commands.CustomCommandsStore
import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceReadiness
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.commands.QuickDistroKeys
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.importservice.ImportService
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.installer.RootFsCapabilityProbe
import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import com.fem.linexa.service.runtime.features.DesktopRunMode
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.workspace.WorkspaceDeleter
import com.fem.linexa.ui.theme.LinexaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the workspace detail screen (WorkspaceScreen / WorkspaceNavGraph).
 *
 * Replaces FakeData lookups with real repository data and extension-point-
 * sourced shortcuts/features (§1.19 audit fix).
 *
 * Workspace validation gate fix: this is the one place every route under
 * `workspace/{id}` passes through (Shortcuts/Features/Settings/Terminal/
 * Desktop all live inside the nested graph this drives — see
 * WorkspaceNavGraph), so it's the natural chokepoint to ask "is this
 * workspace actually usable?" before showing any of those tabs.
 * [WorkspaceLaunchValidator] answers that; a non-Ready result now produces
 * [WorkspaceDetailUiState.Blocked] instead of [WorkspaceDetailUiState.Success],
 * and this class exposes [retry]/[delete] for the UI that state renders.
 * Uses `observeById` (not a one-shot `getById`) so a successful Retry is
 * reflected here automatically, without re-navigating.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WorkspaceDetailViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val repository: WorkspaceRepository,
    private val fileManager: WorkspaceFileManager,
    private val launchValidator: WorkspaceLaunchValidator,
    private val bootstrapManager: BootstrapManager,
    private val diagnosticLogManager: DiagnosticLogManager,
    private val runtimeTrace: RuntimeTrace,
    private val settingsRepository: SettingsRepository,
    private val workspaceDeleter: WorkspaceDeleter,
    /**
     * §1.120: the installed image's own declaration, read from the workspace's
     * persisted `config.yaml`. Fail-open by contract (returns null for absent,
     * unreadable or invalid), which is exactly the input
     * [WorkspaceShortcuts.declaredShortcut] maps to cards.
     */
    private val rootFsMetadata: WorkspaceRootFsMetadata,
    /**
     * The built-in Quick Commands source: resolves the ONE project catalog
     * for this workspace's distribution/version. Every distribution/version
     * decision lives there — none of them in this ViewModel or the UI.
     */
    private val quickCommandsResolver: QuickCommandsResolver,
    /** Persistent storage for the workspace's own saved commands. */
    private val customCommandsStore: CustomCommandsStore
) : AndroidViewModel(application) {

    private val workspaceId: String = savedStateHandle["workspaceId"]
        ?: throw IllegalArgumentException("workspaceId is required")

    private val _uiState = MutableStateFlow<WorkspaceDetailUiState>(WorkspaceDetailUiState.Loading)
    val uiState: StateFlow<WorkspaceDetailUiState> = _uiState.asStateFlow()

    private val _runtimeTraceText = MutableStateFlow("")
    val runtimeTraceText: StateFlow<String> = _runtimeTraceText.asStateFlow()

    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    /**
     * §1.117: a refused delete's reason (teardown could not be confirmed, so
     * nothing was deleted). Null when there is nothing to report.
     */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeDebugEnabled().collect { enabled ->
                _debugEnabled.value = enabled
            }
        }
        // UI/UX Phase 7: the trace is rendered only while debug info is ON, and at
        // a controlled rate.
        //
        // Two defects are fixed here, and both were invisible. (i) This collector
        // ran unconditionally: `RuntimeTrace.revision` bumps on every traced hop,
        // and each bump re-joined the whole 400-line buffer into a StateFlow that
        // a hidden panel was the only reader of — so a user with debug off paid a
        // string build plus a state write per hop, forever. `flatMapLatest` on the
        // setting means there is no subscription at all while the toggle is off
        // (and the previous inner collector is cancelled the moment it flips), and
        // the state is reset to "" so nothing stale is retained.
        // (ii) Even with debug on, one render per hop is far more than a human can
        // read: startup bursts dozens of lines in a few hundred milliseconds, and
        // each one recomposed a 180dp scrolling text box. `sample` collapses a
        // burst into one render per [TRACE_REFRESH_INTERVAL_MS] — the plan's
        // "low/controlled refresh rate" — while still being live, because `sample`
        // always emits the *latest* value of the window rather than dropping the
        // tail like a debounce would.
        //
        // `onStart` emits once so opening the panel shows whatever was already
        // captured, instead of an empty box until the next hop happens to fire.
        viewModelScope.launch {
            settingsRepository.observeDebugEnabled()
                .flatMapLatest { enabled ->
                    if (!enabled) {
                        flowOf("")
                    } else {
                        runtimeTrace.revision
                            .sample(TRACE_REFRESH_INTERVAL_MS)
                            .onStart { emit(0L) }
                            .map { runtimeTrace.render(workspaceId) }
                    }
                }
                .distinctUntilChanged()
                .collect { text -> _runtimeTraceText.value = text }
        }
        viewModelScope.launch {
            // §1.89: run every expensive step — the disk-usage walk, the
            // launch validation (which itself walks the rootfs) and the
            // diagnostic collection/logging — on the IO dispatcher instead of
            // the main thread that this collector otherwise runs on.
            //
            // Tab-switch jank fix: the §1.89 debounce used to delay the FIRST
            // emission too, so every entry into this screen held the full-screen
            // Loading spinner for ≥120ms before the content replaced it — a
            // visible two-step jump and a delayed first interaction, on top of
            // the enter transition. The first arrival now paints immediately;
            // subsequent flips (the status-flip storms the debounce exists to
            // coalesce) still wait [STATE_DEBOUNCE_MS]. `mapLatest` also cancels
            // an in-flight build when a newer row lands, so a burst converges on
            // the latest row exactly like the debounce did.
            var firstEmission = true
            repository.observeById(workspaceId)
                .distinctUntilChanged()
                .mapLatest { metadata ->
                    if (firstEmission) {
                        firstEmission = false
                    } else {
                        delay(STATE_DEBOUNCE_MS)
                    }
                    val state = withContext(Dispatchers.IO) {
                        buildUiState(metadata)
                    }
                    _uiState.value = state
                }
                .collect {}
        }
    }

    /**
     * Builds the full [WorkspaceDetailUiState] for [metadata]. Pure (no
     * coroutines), but every filesystem and subprocess call it makes is
     * deliberately invoked from [Dispatchers.IO] by [init]'s collector. A
     * null [metadata] (row deleted while this screen was open) maps to the
     * user-facing "not found" error state.
     */
    private fun buildUiState(metadata: WorkspaceMetadata?): WorkspaceDetailUiState {
        if (metadata == null) {
            return WorkspaceDetailUiState.Error(getApplication<Application>().getString(R.string.workspace_not_found))
        }
        val (label, fraction) = getDiskUsage(workspaceId)
        val workspace = metadata.toUiWorkspace(label, fraction)
        val readiness = launchValidator.evaluate(metadata)

        return if (readiness is WorkspaceReadiness.Ready) {
            // §1.120/§1.122 + UI/UX Phase 5C: read the declaration once and use
            // it for both halves of the Commands list — which commands exist,
            // and which distribution to attribute them to. Two reads would risk
            // the two halves disagreeing after a reinstall between them.
            val descriptor = rootFsMetadata.descriptorOf(workspaceId)
            // 2026-09-02 (desktop prerequisites): the effective Desktop
            // condition. VNC mode needs an actual desktop session launcher
            // (intent AND capability); the WebView run mode has no such
            // requirement — its "desktop" is the guest web stack — so intent
            // alone governs there. Intent resolution is the pinned
            // desktopEnabled semantics; capability is probed LIVE from the
            // rootfs on every state build, so a desktop installed or removed
            // through the guest terminal converges at the next state
            // re-emission (the existing debounced Room lifecycle — no extra
            // polling) and no stale persisted flag can keep a fake-ready
            // button lit. The probe is a handful of file stats, far cheaper
            // than the disk-usage walk this builder already performs.
            val resolvedToggles = resolveFeatureToggles(
                defaults = WorkspaceContent.builtinFeatureDefaults,
                overrides = metadata.featureToggles
            )
            val resolvedSettings = FeatureSettings.from(metadata.attributes)
            val effectiveDesktopEnabled = if (resolvedSettings.desktopMode == DesktopRunMode.WEBVIEW) {
                WorkspaceCapabilities.desktopEnabled(resolvedToggles, metadata.attributes)
            } else {
                WorkspaceCapabilities.desktopUsable(
                    resolvedToggles,
                    metadata.attributes + (WorkspaceCapabilities.ATTR_DESKTOP to
                        RootFsCapabilityProbe.hasDesktopCapability(
                            fileManager.getRootfsPath(workspaceId)
                        ).toString())
                )
            }
            // The saved user commands, decoded once and used for both halves
            // of the Success state (the tree's My Commands section and the
            // edit/delete surface).
            val customCommands = CustomCommandsCodec.decode(
                metadata.attributes[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
            )
            WorkspaceDetailUiState.Success(
                workspace,
                // §1.120 → catalog era: the Commands tree is assembled from
                // its three sources — the image's declared shortcuts when the
                // image declares any (replacing the built-ins, the §1.120
                // contract), otherwise the project Quick Commands catalog
                // resolved for THIS workspace's distribution/version; plus
                // the user's own saved section, always last. Which commands
                // exist is decided here (data), never in the UI.
                //
                // UI/UX Phase 5C note kept: `descriptor` is read once and
                // used for both halves of the list — which commands exist,
                // and (for a declaring image) which distribution to
                // attribute them to. Two reads could straddle a reinstall.
                assembleCommands(metadata, descriptor, customCommands),
                customCommands,
                WorkspaceContent.builtinFeatures,
                // §1.81: effective toggle state = built-in defaults
                // overlaid by the persisted per-workspace toggles
                // (which already include config.yaml `linexa.features`
                // merged at import). A later overlay always wins.
                resolveFeatureToggles(
                    defaults = WorkspaceContent.builtinFeatureDefaults,
                    overrides = metadata.featureToggles
                ),
                // UI/UX Phase 6: the per-feature configuration, read from the
                // same record through the same total resolver the launch path
                // uses (FeatureSettings.from is fail-open, so a pre-Phase-6
                // workspace with no attributes resolves to the old defaults).
                resolvedSettings,
                // § desktop-capability: resolved from the workspace's real
                // persisted metadata (feature toggles / attributes), never
                // guessed from a name. Defaults off — no fake Desktop button.
                // 2026-09-02 (desktop prerequisites): the *button* condition
                // is intent AND actual rootfs capability (VNC mode) — a
                // persisted toggle alone no longer lights it. The Features
                // tab still resolves raw intent for its own toggle row.
                effectiveDesktopEnabled,
                // §26/§27: the workspace's network settings, resolved through
                // the same total, fail-open attribute read the runtime's own
                // WorkspaceNetworkProvisioner performs at start — the tab and
                // the launch path cannot disagree.
                NetworkSettings.from(metadata.attributes)
            )
        } else {
            // UI/UX Phase 7: this report is NOT gated on the debug toggle, and that
            // is deliberate — it is the Broken-workspace diagnosis surface (§1.117),
            // the only thing that explains why a workspace will not start, and it is
            // built once per *blocked* workspace rather than continuously. Gating it
            // would mean a user has to discover a debug setting before they can be
            // told why their install failed.
            //
            // What Phase 7 does fix here is the duplicated work: `diagnoseAll()`
            // stats and reads every bundled binary, symlink, loader and manifest to
            // build one large string, and it was called TWICE per emission — once
            // for the on-screen report and again for the persisted copy — so half of
            // it was pure waste on every debounced Room re-emission of a broken row.
            val bootstrapDiagnostics = try {
                bootstrapManager.diagnoseAll()
            } catch (e: Exception) {
                "Failed to collect diagnostics: ${e.message}"
            }
            val diagnostics = buildString {
                appendLine("=== Linexa Error Report ===")
                appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Workspace: ${metadata.displayName} (${metadata.id})")
                appendLine("Distro: ${metadata.distro.name} ${metadata.distro.version}")
                appendLine("Status: ${metadata.status}")
                appendLine()
                appendLine("--- Error ---")
                val reason = (readiness as? WorkspaceReadiness.Blocked)?.reason
                    ?: readiness.toString()
                appendLine(reason)
                appendLine()
                appendLine("--- Runtime Trace ---")
                // Empty unless the user had debug info on when the failure happened.
                // Honest either way: the rest of the report stands on its own.
                appendLine(runtimeTrace.render(workspaceId))
                appendLine()
                appendLine("--- Bootstrap Diagnostics ---")
                appendLine(bootstrapDiagnostics)
            }

            // §1.53: Persist the error report to workspace logs
            val logPath = diagnosticLogManager.getLogPathDisplay(workspaceId)
            try {
                diagnosticLogManager.logError(workspaceId, diagnostics)
                diagnosticLogManager.logBootstrapDiagnostics(workspaceId, bootstrapDiagnostics)
            } catch (_: Exception) {
                // Best effort
            }

            WorkspaceDetailUiState.Blocked(
                workspace = workspace,
                readiness = readiness,
                canRetry = metadata.attributes.containsKey(ImportService.ATTR_SOURCE_URI) ||
                    metadata.attributes.containsKey(DownloadService.ATTR_DISTRO_ID),
                diagnostics = diagnostics,
                logPath = logPath
            )
        }
    }

    /**
     * Builds the Commands screen's presentation tree from its three sources:
     *
     *  1. **Declared** — the installed image's own `linexa.shortcuts`. An
     *     image that declares commands replaces the built-in branch entirely
     *     (the author's statement wins; `shortcuts: []` means "deliberately
     *     none"). Attributed to the distribution THE IMAGE declared — a probed
     *     identity is not a declaration about the commands.
     *  2. **Built-in** — otherwise the project Quick Commands catalog,
     *     resolved for this workspace's distribution/version. The identity
     *     inputs reuse what the app already knows: the image's DECLARED distro
     *     id/version first, then the persisted catalog id attribute, then the
     *     probed `DistroInfo` — no second detection pass, and an unknown
     *     distribution gets the catalog's portable branch only, never another
     *     distribution's commands (that decision lives in
     *     [QuickCommandsResolver]; nothing here inspects distro keys).
     *  3. **Custom** — the user's own saved commands (their own section,
     *     last), decoded fail-open from the workspace attributes.
     */
    private fun assembleCommands(
        metadata: WorkspaceMetadata,
        descriptor: com.fem.linexa.service.rootfs.RootFsDescriptor,
        custom: List<CustomCommand>
    ): List<CommandNode> {
        val declared = descriptor.declaredShortcuts
        val declaredCards = declared?.map { declaredShortcut ->
            WorkspaceShortcuts.declaredShortcut(
                declaredShortcut,
                descriptor.metadata?.distro?.displayLabel
            )
        }
        val builtinNodes = if (declared != null) {
            emptyList()
        } else {
            val distroKey = QuickDistroKeys.distroKey(
                declaredDistroId = descriptor.metadata?.distro?.id,
                catalogId = metadata.attributes[DownloadService.ATTR_DISTRO_ID],
                probedName = metadata.distro.name
            )
            val versionKey = QuickDistroKeys.versionKey(
                declaredVersion = descriptor.metadata?.distro?.version,
                persistedVersion = metadata.distro.version
            )
            quickCommandsResolver.resolve(distroKey, versionKey)
        }
        return WorkspaceCommandsAssembler.assemble(
            builtinNodes = builtinNodes,
            declaredShortcuts = declaredCards,
            custom = custom,
            customSectionTitle = getApplication<Application>().getString(R.string.workspace_commands_mine),
            customIconRes = com.fem.linexa.R.drawable.ic_haven_terminal
        )
    }

    /** Adds a user command; the Room re-emission refreshes the screen. */
    fun addCustomCommand(name: String, command: String) {
        viewModelScope.launch {
            customCommandsStore.save(workspaceId, currentCustomCommands(), name, command)
        }
    }

    /** Updates the saved command with [id] IN PLACE — no duplicate record. */
    fun updateCustomCommand(id: String, name: String, command: String) {
        viewModelScope.launch {
            customCommandsStore.save(workspaceId, currentCustomCommands(), name, command, editId = id)
        }
    }

    /** Deletes the saved command with [id]; built-ins and other data are untouched. */
    fun deleteCustomCommand(id: String) {
        viewModelScope.launch {
            customCommandsStore.delete(workspaceId, currentCustomCommands(), id)
        }
    }

    private suspend fun currentCustomCommands(): List<CustomCommand> {
        val metadata = repository.getById(workspaceId) ?: return emptyList()
        return CustomCommandsCodec.decode(
            metadata.attributes[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
    }

    /**
     * Re-runs whichever pipeline originally installed this workspace,
     * using whatever it persisted to retry with: a picked file's URI
     * ([ImportService.ATTR_SOURCE_URI]) or a catalog distro id
     * ([DownloadService.ATTR_DISTRO_ID]). If neither attribute is present
     * (workspace predates both, or the URI grant couldn't be made
     * persistable), there's nothing safe to retry with —
     * [WorkspaceDetailUiState.Blocked.canRetry] should already be false in
     * that case so the UI won't offer this action, but this guards the
     * call directly too.
     */
    fun retry() {
        viewModelScope.launch {
            val metadata = repository.getById(workspaceId) ?: return@launch
            val context = getApplication<Application>()
            val sourceUriString = metadata.attributes[ImportService.ATTR_SOURCE_URI]
            val distroId = metadata.attributes[DownloadService.ATTR_DISTRO_ID]

            when {
                sourceUriString != null -> context.startService(
                    ImportService.retryIntent(
                        context = context,
                        workspaceId = workspaceId,
                        uri = Uri.parse(sourceUriString),
                        distroName = metadata.distro.name,
                        distroVersion = metadata.distro.version,
                        expectedSha256 = metadata.attributes["sha256"]
                    )
                )
                distroId != null -> context.startService(
                    DownloadService.retryIntent(context, workspaceId, distroId)
                )
                else -> Logger.w(
                    "WorkspaceDetailViewModel",
                    "Retry requested for $workspaceId but no source URI or distro id was persisted"
                )
            }
        }
    }

    /**
     * Deletes this workspace's DB record and on-disk contents, then notifies
     * the caller.
     *
     * §1.117: goes through [WorkspaceDeleter], which **stops the runtime
     * before touching disk**. This path is reachable only from inside a live
     * workspace (Settings → Danger Zone, and the Blocked screen), and entering
     * a workspace always starts its session — so deleting directly used to
     * unlink `{workspace}/` while PRoot was still executing inside it, leaking
     * anything the guest recreated behind `deleteRecursively`'s walk.
     *
     * [onDeleted] fires only on a confirmed delete. A refused delete (teardown
     * could not be confirmed) leaves the workspace untouched and surfaces the
     * reason through [deleteError] instead of navigating away.
     */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            when (val outcome = workspaceDeleter.delete(workspaceId)) {
                is WorkspaceDeleter.Outcome.Deleted -> onDeleted()
                is WorkspaceDeleter.Outcome.StopFailed -> {
                    Logger.w("WorkspaceDetailViewModel", "delete refused: ${outcome.reason}")
                    _deleteError.value = outcome.reason
                }
                is WorkspaceDeleter.Outcome.DeleteFailed -> {
                    Logger.w("WorkspaceDetailViewModel", "delete failed: ${outcome.reason}")
                    _deleteError.value = outcome.reason
                }
            }
        }
    }

    /** Clears a surfaced [deleteError] once the UI has shown it. */
    fun clearDeleteError() {
        _deleteError.value = null
    }

    /**
     * §1.81: persists a feature-toggle change for this workspace. The
     * `observeById` collector in [init] re-emits on the Room update, so the
     * Features tab converges on the new state without a manual refresh.
     * Toggles are intentionally stored as a full map (not a delta) — the
     * workspace record is the single source of truth.
     *
     * 2026-09-02 (desktop prerequisites, Phase 2 §4.5): enabling **desktop**
     * first re-checks the rootfs LIVE — cached capability data is never
     * trusted at the moment of intent. When no desktop session exists (VNC
     * mode), the enable is refused: Desktop Mode stays off, the cached
     * capability attribute is refreshed from the probe, and
     * [desktopToggleBlocked] explains what is missing while the Features
     * tab's Desktop panel offers the guided installation flow. The WebView
     * run mode needs no desktop environment, so it enables as before.
     */
    fun setFeatureToggle(featureId: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getById(workspaceId) ?: return@launch
            if (featureId == FEATURE_DESKTOP_ID) {
                val needsDesktop =
                    FeatureSettings.from(current.attributes).desktopMode != DesktopRunMode.WEBVIEW
                if (enabled && needsDesktop) {
                    val capable = withContext(Dispatchers.IO) {
                        RootFsCapabilityProbe.hasDesktopCapability(fileManager.getRootfsPath(workspaceId))
                    }
                    // Refresh the persisted capability from the live probe —
                    // the cache is never the sole source of truth (§4.6).
                    repository.updateAttributes(
                        workspaceId,
                        mapOf(WorkspaceCapabilities.ATTR_DESKTOP to capable.toString())
                    )
                    if (!capable) {
                        _desktopToggleBlocked.value = true
                        Logger.i(
                            TAG,
                            "Desktop enable refused for $workspaceId — no desktop session in rootfs; offering the install flow"
                        )
                        return@launch
                    }
                }
                _desktopToggleBlocked.value = false
            }
            // Phase 05: atomic single-toggle write — two rapid toggles both
            // persist. The old read here + full-map write in
            // updateFeatureToggles meant the second toggle overwrote the
            // first one's key (the row it read was already stale).
            repository.setFeatureToggle(workspaceId, featureId, enabled)
        }
    }

    /**
     * 2026-09-02: true while the last desktop-enable attempt was refused
     * for a missing desktop environment. Transient UI state (a successful
     * later enable, a disable, or the guided install completing clears it
     * through [setFeatureToggle]/[clearDesktopToggleBlocked]); the Features
     * tab renders it as the Desktop row's explanation.
     */
    private val _desktopToggleBlocked = MutableStateFlow(false)
    val desktopToggleBlocked: StateFlow<Boolean> = _desktopToggleBlocked.asStateFlow()

    /** Clears the desktop-enable refusal notice (e.g. when the panel is read). */
    fun clearDesktopToggleBlocked() {
        _desktopToggleBlocked.value = false
    }

    /**
     * UI/UX Phase 6: persists this workspace's per-feature configuration.
     *
     * Symmetric with [setFeatureToggle] — same single source of truth, same
     * convergence through the `observeById` collector — but writes into
     * `attributes` via [WorkspaceRepository.updateAttributes], which **merges**
     * so the install-source/distro-id/desktop-capability entries sharing that map
     * survive (see that method's KDoc).
     *
     * Takes the whole [FeatureSettings] rather than one field: the value is
     * already resolved and held by the state, and a per-field API would have to
     * re-read and re-merge, which is where two concurrent edits lose one.
     */
    fun setFeatureSettings(settings: FeatureSettings) {
        viewModelScope.launch {
            repository.updateAttributes(workspaceId, settings.toAttributes())
        }
    }

    /**
     * §26/§27: persists this workspace's network settings (DNS mode/servers,
     * guest package mirror, RootFS download mirror).
     *
     * Symmetric with [setFeatureSettings] — the same merge-write into
     * `attributes` (so the install-source/desktop-capability entries survive),
     * the same convergence through the `observeById` collector. The consumer
     * is the workspace **start** path (`WorkspaceNetworkProvisioner` reads
     * `NetworkSettings.from(attributes)`), so the write never touches a
     * running runtime; the panel states when the change applies.
     *
     * Takes the whole [NetworkSettings] rather than one field, for the same
     * reason [setFeatureSettings] does: the value is already resolved and held
     * by the state, and per-field writes are where concurrent edits lose one.
     */
    fun setNetworkSettings(settings: NetworkSettings) {
        viewModelScope.launch {
            repository.updateAttributes(workspaceId, settings.toAttributes())
        }
    }

    /**
     * §28: persists the terminal session-persistence opt-in.
     *
     * It lives on [FeatureSettings] (one attribute key beside the others), but
     * the row that edits it sits on the Settings tab, where the rest of the
     * FeatureSettings state is not otherwise needed — so this reads the
     * current record, overlays the one field, and writes the full encoding
     * back through the same **merge** path as [setFeatureSettings]. Re-reading
     * instead of reusing the UI state's copy keeps this correct even if the
     * collector's emission is still in flight when the toggle is hit.
     */
    fun setTerminalPersistence(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getById(workspaceId) ?: return@launch
            repository.updateAttributes(
                workspaceId,
                FeatureSettings.from(current.attributes)
                    .copy(terminalPersistence = enabled)
                    .toAttributes()
            )
        }
    }

    /** Toggles the global Debug setting (Settings → Debug). */
    fun setDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDebugEnabled(enabled)
        }
    }

    private fun getDiskUsage(workspaceId: String): Pair<String, Float> {
        val size = fileManager.getRootfsSize(workspaceId)
        val sizeLabel = FileSizeFormatter.format(size)
        val fraction = (size.toFloat() / (10 * 1024 * 1024 * 1024)).coerceIn(0f, 1f)
        return Pair(sizeLabel, fraction)
    }

    companion object {
        private const val TAG = "WorkspaceDetailViewModel"

        /** The feature id [setFeatureToggle] gates with a live capability probe. */
        private const val FEATURE_DESKTOP_ID = FEATURE_DESKTOP

        /**
         * UI/UX Phase 7: the trace panel's refresh window, in milliseconds.
         *
         * 500 ms is chosen against what the panel is for rather than for
         * smoothness: it is a log being read by a person, so a sub-second lag is
         * imperceptible, while startup emits dozens of hops in a few hundred
         * milliseconds and each render re-joins up to 400 lines and recomposes a
         * scrolling text box. Sampling collapses that burst into one render.
         *
         * Not a `delay`-driven poll: `sample` only emits while lines are actually
         * arriving, so an idle workspace with the panel open does no work at all.
         */
        const val TRACE_REFRESH_INTERVAL_MS: Long = 500L

        /**
         * The §1.89 debounce window for Room re-emissions (status flips during
         * runtime startup burst several writes in quick succession). Applied to
         * *subsequent* rows only — the first arrival renders immediately so
         * entering the screen is never delayed by the window itself.
         */
        const val STATE_DEBOUNCE_MS: Long = 120L
    }
}

sealed interface WorkspaceDetailUiState {
    data object Loading : WorkspaceDetailUiState
    data class Error(val message: String) : WorkspaceDetailUiState

    /** Workspace exists but isn't launchable right now — see [WorkspaceLaunchValidator]. */
    data class Blocked(
        val workspace: Workspace,
        val readiness: WorkspaceReadiness,
        val canRetry: Boolean,
        val diagnostics: String,
        val logPath: String? = null
    ) : WorkspaceDetailUiState

    data class Success(
        val workspace: Workspace,
        /** The assembled Commands tree: built-in/declared sections + My Commands. */
        val commands: List<CommandNode>,
        /** The workspace's saved user commands (storage order) — the edit/delete surface. */
        val customCommands: List<CustomCommand>,
        val features: List<Feature>,
        val featureToggles: Map<String, Boolean>,
        /** UI/UX Phase 6 — the effective per-feature configuration. */
        val featureSettings: FeatureSettings = FeatureSettings(),
        val desktopEnabled: Boolean = false,
        /** §26/§27 — the effective network settings (fail-open defaults when nothing is persisted). */
        val networkSettings: NetworkSettings = NetworkSettings()
    ) : WorkspaceDetailUiState
}

private fun WorkspaceMetadata.toUiWorkspace(
    storageUsedLabel: String,
    storageUsedFraction: Float
): Workspace {
    val color = try {
        androidx.compose.ui.graphics.Color(
            android.graphics.Color.parseColor(avatarColorHex)
        )
    } catch (_: IllegalArgumentException) {
        LinexaColors.Primary
    }
    return Workspace(
        id = id,
        name = displayName,
        description = "${distro.name} ${distro.version}".trim(),
        storageUsedLabel = storageUsedLabel,
        storageUsedFraction = storageUsedFraction,
        avatarLetter = avatarLetter,
        avatarColor = color,
        isRunning = false,
        status = status,
        // Distro icon: catalog installs persist their DistroCatalog id as a
        // workspace attribute ("distroId"); imported workspaces don't, so
        // they keep the letter-avatar fallback in the Home workspace card.
        distroId = attributes[DownloadService.ATTR_DISTRO_ID]
    )
}
