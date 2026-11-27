package com.fem.linexa.ui.screens.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.terminal.TerminalDesktopState
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.domain.terminal.TerminalWindowManager
import com.fem.linexa.domain.terminal.TerminalWindowSession
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.pty.PtyEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The workspace terminal selector (audit §18 W3): shows every open terminal
 * of a workspace (keyed by the authoritative runtime terminal id), exposes
 * the per-terminal process state, and offers a single "new terminal" action.
 *
 * It consumes the graph-scoped [RuntimeBinder] (the workspace's one service
 * bind, §1.2) via [attachBinder]; it never binds the service itself.
 *
 * **Duplicate-create protection (§11 matrix):** [createTerminal] is guarded
 * by an in-flight [createJob] + [isCreating] flag, so a repeated tap on
 * "New Terminal" cannot launch more than one pty for one logical user
 * action. The created terminal's id is surfaced once through
 * [createdTerminal] (consumed by the screen via [consumeCreatedTerminal]),
 * never re-emitted.
 *
 * **Never zero terminals (§1.106):** the runtime guarantees a live workspace
 * always has at least one terminal, so [closeTerminal] can return the id of a
 * replacement the runtime opened. That id is published through the same
 * [createdTerminal] channel, which is why the channel is named for "the
 * terminal the screen should switch to" rather than strictly "created by the
 * user".
 */
@HiltViewModel
class TerminalsViewModel @Inject constructor(
    private val windowIdentities: TerminalWindowIdentityRegistry
) : ViewModel() {

    /**
     * One open terminal of the workspace, for the list UI.
     *
     * 2026-08-31 (Phase 6, compact session numbering): [terminalId] is the
     * STABLE internal runtime identity — a never-reuse allocator id (§9.1)
     * used as map key, TerminalKey, PROOT_TMP_DIR path scope, tmux session
     * name and notification deep-link arg — and is NEVER displayed as the
     * session number. [displayLabel] is the derived compact position
     * (T1..TN) over the sorted live list, re-derived on every emission, so
     * closing a first/middle/last session never leaves numbering gaps.
     */
    data class TerminalItem(
        val terminalId: String,
        val state: TerminalState,
        val displayLabel: String = ""
    )

    private val _items = MutableStateFlow<List<TerminalItem>>(emptyList())
    val items: StateFlow<List<TerminalItem>> = _items.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    /**
     * UX-002: a restart the manager refused (not Error / no launch rootfs) or
     * an open that failed must be visible. The terminal's own state carries
     * Error reasons, but a refusal leaves the state untouched, so the user saw
     * "nothing happens". The panel renders this one-shot reason in a dialog.
     */
    private val _restartFailure = MutableStateFlow<String?>(null)
    val restartFailure: StateFlow<String?> = _restartFailure.asStateFlow()

    fun clearRestartFailure() {
        _restartFailure.value = null
    }

    /**
     * One-shot: the id of the terminal the screen should switch to. Set by
     * [createTerminal] (a user-created terminal) and, per §1.106, by
     * [closeTerminal] when closing the last terminal caused the runtime to open
     * a replacement.
     */
    private val _createdTerminal = MutableStateFlow<String?>(null)
    val createdTerminal: StateFlow<String?> = _createdTerminal.asStateFlow()

    /**
     * Desktop presentation state: one window per live terminal, the focused
     * window, its maximize/minimize state and each window's real preview.
     * Separate from [items]' [TerminalState] because window UI state and Linux
     * process state must never be conflated.
     */
    private var windowManager = TerminalWindowManager()
    private val _desktop = MutableStateFlow(TerminalDesktopState())
    val desktop: StateFlow<TerminalDesktopState> = _desktop.asStateFlow()

    private var workspaceId: String? = null
    private var binder: RuntimeBinder? = null
    private var observeJob: Job? = null
    private var identityJob: Job? = null
    private var createJob: Job? = null
    private var closeAllJob: Job? = null
    private var previewJob: Job? = null

    /** Finding 011: one in-flight restart per terminal; a double tap is a no-op. */
    private val restartJobs = mutableMapOf<String, Job>()

    /** Per-terminal title observers; cancelled when a terminal disappears. */
    private val titleJobs = mutableMapOf<String, Job>()

    /**
     * Active id requested before the window list caught up (a create/open can
     * publish its id before the manager's state emission arrives). Applied by
     * [syncWindows] once that id appears — a create tap must not strand the
     * desktop on an empty/idle surface.
     *
     * This is the **pending-selection protection** for the first Root Shell
     * launch: while it is set, a lower-priority restore (a stale hoisted
     * desktop id or a process-death restore) cannot erase it. The request is
     * cleared only when its window syncs or the runtime explicitly reports
     * terminals without it.
     */
    private var pendingActiveTerminalId: String? = null

    /** Where [pendingActiveTerminalId] came from, for the TerminalChain trace. */
    private var pendingSelectionSource: TerminalSelectionSource? = null

    /**
     * Terminal ids that have actually appeared in a published window list.
     * Identity pruning is driven by this set, never by the current live ids:
     * a launcher records an app/Root identity immediately after the runtime
     * allocates an id, which can be one emission before that id reaches
     * [items]. Pruning against [items] deleted that identity (losing the
     * "Root shell" title) and then the late window sat on its fallback title.
     */
    private var knownTerminalIds: Set<String> = emptySet()

    private val _pendingActiveTerminal = MutableStateFlow<String?>(null)

    /**
     * The terminal id a selection is waiting for, or null. Exposed for the
     * regression tests and diagnostics; the desktop itself keeps rendering
     * the last consistent window until the pending id syncs.
     */
    val pendingActiveTerminal: StateFlow<String?> = _pendingActiveTerminal.asStateFlow()

    private var overviewVisible = false

    fun attachBinder(workspaceId: String, binder: RuntimeBinder) {
        if (this.workspaceId == workspaceId && this.binder === binder) return
        Logger.d(TAG, "TerminalsViewModel attaching workspaceId=$workspaceId")
        if (this.workspaceId != workspaceId) {
            val previousWorkspace = this.workspaceId
            previewJob?.cancel()
            previewJob = null
            titleJobs.values.forEach { it.cancel() }
            titleJobs.clear()
            restartJobs.values.forEach { it.cancel() }
            restartJobs.clear()
            // A first attach (previousWorkspace == null) may carry a focus
            // request made before the binder existed; keep it. Switching to a
            // different workspace must not leak the other workspace's request.
            if (previousWorkspace != null) clearPendingSelection()
            knownTerminalIds = emptySet()
            windowManager = TerminalWindowManager()
            _desktop.value = TerminalDesktopState()
        }
        this.workspaceId = workspaceId
        this.binder = binder
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            binder.terminalStates.collect { states ->
                val terminals = states[workspaceId].orEmpty()
                // 2026-08-31 (Phase 6): display labels re-derived here and on
                // the immediate sync below, so both paths emit identical items.
                publishItems(terminals)
                Logger.d(TAG, "terminal list for $workspaceId: ${_items.value.map { it.terminalId }}")
            }
        }
        // App/shortcut identities are recorded right AFTER the runtime allocates
        // a terminal id, which can be later than the state emission that created
        // the window. Reconcile on every identity change so a new window never
        // sits on its fallback title until an unrelated terminal event.
        identityJob?.cancel()
        identityJob = viewModelScope.launch {
            // Collect the CURRENT revision too: an identity may have been
            // recorded between `publishItems` and this collector starting, and
            // dropping the first emission would strand that window on its
            // fallback title. Re-syncing identical state is a harmless no-op.
            windowIdentities.revision.collect {
                if (this@TerminalsViewModel.workspaceId != null) {
                    syncWindows(_items.value)
                }
            }
        }
        // Immediate sync from the current value (StateFlow always holds it).
        val terminals = binder.terminalStates.value[workspaceId].orEmpty()
        publishItems(terminals)
        if (overviewVisible) startPreviewPolling()
    }

    /**
     * Publishes one terminal-state emission to both the session list and the
     * desktop. The desktop is reconciled from the same ordered items, so the
     * overview can never show a session the list does not have (or vice
     * versa).
     */
    private fun publishItems(terminals: Map<String, TerminalState>) {
        val items = toItems(terminals)
        _items.value = items
        syncWindows(items)
    }

    private fun syncWindows(items: List<TerminalItem>) {
        val wsId = workspaceId ?: return
        val b = binder
        val liveIds = items.map { it.terminalId }.toSet()

        // Prune identities only for terminals that actually appeared in a
        // previous window list and are now gone. A launcher records the
        // app/Root identity immediately after allocation, which can be one
        // emission before the terminal reaches `items`; pruning against
        // `liveIds` deleted that fresh identity and lost the window title.
        val removedIds = knownTerminalIds - liveIds
        knownTerminalIds = liveIds
        removedIds.forEach { deadId ->
            windowIdentities.remove(wsId, deadId)
            Logger.i(
                CHAIN_TAG,
                "CHAIN: terminal removed ws=$wsId tid=$deadId " +
                    "source=${TerminalSelectionSource.TerminalRemoval}"
            )
        }

        // Title observers follow the live list; a closed terminal's collector
        // must not keep a dead pty's shared flow alive.
        titleJobs.keys.filter { it !in liveIds }.forEach { deadId ->
            titleJobs.remove(deadId)?.cancel()
        }
        val sessions = items.map { item ->
            val pty = b?.getPtySession(wsId, item.terminalId)
            TerminalWindowSession(
                terminalId = item.terminalId,
                displayLabel = item.displayLabel,
                processState = item.state,
                runtimeTitle = pty?.title,
                // Foreground /proc probes are intentionally NOT read here: this
                // runs on the main thread for every terminal-state emission.
                // The overview's preview poll (Dispatchers.IO) refreshes it.
                foregroundProcess = null
            )
        }
        val activeBefore = windowManager.state.activeTerminalId
        windowManager.syncSessions(sessions, windowIdentities.snapshot(wsId))
        val pendingApplied = resolvePendingSelection(wsId)
        if (!pendingApplied) {
            val activeAfter = windowManager.state.activeTerminalId
            if (activeAfter != null && activeAfter != activeBefore) {
                Logger.i(
                    CHAIN_TAG,
                    "CHAIN: fallback selected ws=$wsId tid=$activeAfter " +
                        "previous=${activeBefore ?: "none"} " +
                        "source=${TerminalSelectionSource.FallbackSelection}"
                )
            }
        }
        _desktop.value = windowManager.state
        items.forEach { item ->
            if (titleJobs[item.terminalId]?.isActive == true) return@forEach
            val pty = b?.getPtySession(wsId, item.terminalId) ?: return@forEach
            titleJobs[item.terminalId] = viewModelScope.launch {
                pty.events.collect { event ->
                    if (event is PtyEvent.TitleChanged) {
                        windowManager.updateRuntimeTitle(item.terminalId, event.title)
                        _desktop.value = windowManager.state
                    }
                }
            }
        }
    }

    /**
     * Applies a pending focus request once its window has synced, or drops it
     * when the runtime explicitly reports that the requested terminal does not
     * exist. Kept pending while the runtime has no terminal list yet: a Root
     * Shell request can legitimately arrive before the first state emission.
     *
     * @return true when a pending request was applied.
     */
    private fun resolvePendingSelection(wsId: String): Boolean {
        val pending = pendingActiveTerminalId ?: return false
        val pendingWindow = windowManager.state.window(pending)
        if (pendingWindow != null && !pendingWindow.isClosing) {
            val requestedBy = pendingSelectionSource
            windowManager.restore(pending)
            clearPendingSelection()
            Logger.i(
                CHAIN_TAG,
                "CHAIN: pending resolved ws=$wsId tid=$pending " +
                    "requestedBy=$requestedBy " +
                    "source=${TerminalSelectionSource.PendingTerminalResolution}"
            )
            return true
        }
        val runtimeIds = binder?.terminalStates?.value?.get(wsId)?.keys.orEmpty()
        if (runtimeIds.isNotEmpty() && pending !in runtimeIds) {
            Logger.w(
                CHAIN_TAG,
                "CHAIN: pending invalid ws=$wsId tid=$pending " +
                    "requestedBy=$pendingSelectionSource runtime=${runtimeIds.sorted()} " +
                    "source=${TerminalSelectionSource.FallbackSelection}"
            )
            clearPendingSelection()
        } else {
            Logger.d(
                CHAIN_TAG,
                "CHAIN: pending waiting ws=$wsId tid=$pending runtime=${runtimeIds.sorted()}"
            )
        }
        return false
    }

    // ── Desktop window actions (presentation only; ptys stay runtime-owned) ──

    /**
     * The single authority for every focus intent. The panel no longer calls
     * `restoreTerminal` from a mirror effect, so two Compose effects cannot
     * feed stale active ids back into the desktop and oscillate.
     *
     * A request for a window that is not in the desktop yet is remembered and
     * applied by [resolvePendingSelection] when its runtime emission arrives.
     * A lower-priority restore (process-death/hoisted restore) cannot erase a
     * pending Root Shell selection.
     *
     * @return true when applied or remembered; false when it cannot resolve.
     */
    fun requestActiveTerminal(
        terminalId: String,
        source: TerminalSelectionSource,
        canReplacePending: Boolean = true
    ): Boolean {
        val wsId = workspaceId
        val pending = pendingActiveTerminalId
        val staleRestore = source == TerminalSelectionSource.DesktopActiveWindowUpdate
        if (pending != null && pending != terminalId && (!canReplacePending || staleRestore)) {
            Logger.i(
                CHAIN_TAG,
                "CHAIN: ignored stale selection ws=$wsId tid=$terminalId " +
                    "pending=$pending requestedBy=$pendingSelectionSource source=$source"
            )
            return false
        }
        val target = windowManager.state.window(terminalId)
        if (target != null && !target.isClosing) {
            val previous = _desktop.value.activeTerminalId
            clearPendingSelection()
            windowManager.restore(terminalId)
            _desktop.value = windowManager.state
            Logger.i(
                CHAIN_TAG,
                "CHAIN: selection applied ws=$wsId tid=$terminalId source=$source " +
                    "previous=${previous ?: "none"}"
            )
            return true
        }
        if (target?.isClosing == true) {
            Logger.i(CHAIN_TAG, "CHAIN: selection ignored ws=$wsId tid=$terminalId closing source=$source")
            return false
        }
        pendingActiveTerminalId = terminalId
        pendingSelectionSource = source
        _pendingActiveTerminal.value = terminalId
        Logger.i(
            CHAIN_TAG,
            "CHAIN: selection pending ws=$wsId tid=$terminalId source=$source " +
                "desktop=${_desktop.value.activeTerminalId ?: "none"}"
        )
        val runtimeIds = binder?.terminalStates?.value?.get(wsId)?.keys.orEmpty()
        if (runtimeIds.isNotEmpty() && terminalId !in runtimeIds) {
            Logger.w(
                CHAIN_TAG,
                "CHAIN: selection invalid ws=$wsId tid=$terminalId requestSource=$source " +
                    "runtime=${runtimeIds.sorted()} " +
                    "fallback=${_desktop.value.activeTerminalId ?: "none"} " +
                    "source=${TerminalSelectionSource.FallbackSelection}"
            )
            clearPendingSelection()
            return false
        }
        return true
    }

    private fun clearPendingSelection() {
        pendingActiveTerminalId = null
        pendingSelectionSource = null
        _pendingActiveTerminal.value = null
    }

    /**
     * Explicit user restore/focus (overview card tap, dock tap). Kept as the
     * public presentation verb; all focus routes go through
     * [requestActiveTerminal]'s single pending/authority path.
     */
    fun restoreTerminal(terminalId: String, canReplacePending: Boolean = true): Boolean =
        requestActiveTerminal(
            terminalId,
            TerminalSelectionSource.WindowRestore,
            canReplacePending = canReplacePending
        )

    /** Focus [terminalId] if it exists; same authoritative path as [restoreTerminal]. */
    fun activateTerminal(terminalId: String): Boolean =
        requestActiveTerminal(terminalId, TerminalSelectionSource.WindowRestore)

    /** Hide a window; the pty keeps running and output keeps accumulating. */
    fun minimizeTerminal(terminalId: String) {
        windowManager.minimize(terminalId)
        _desktop.value = windowManager.state
        // The overview is the minimized window's home. If it is already open,
        // keep it open; the caller may also choose to open it.
        if (overviewVisible) startPreviewPolling()
    }

    fun toggleMaximizeTerminal(terminalId: String) {
        windowManager.toggleMaximize(terminalId)
        _desktop.value = windowManager.state
    }

    fun requiresCloseConfirmation(terminalId: String): Boolean =
        windowManager.requiresCloseConfirmation(terminalId)

    /**
     * Starts/cancels preview polling. The overview is the only surface that
     * needs every session's preview, so polling is tied to its visibility
     * rather than running for the workspace screen's whole lifetime.
     */
    fun setOverviewVisible(visible: Boolean) {
        if (overviewVisible == visible) return
        overviewVisible = visible
        if (visible) {
            startPreviewPolling()
        } else {
            previewJob?.cancel()
            previewJob = null
        }
    }

    private fun startPreviewPolling() {
        if (previewJob?.isActive == true) return
        if (workspaceId == null || binder == null) return
        previewJob = viewModelScope.launch {
            while (isActive) {
                refreshPreviews()
                delay(PREVIEW_POLL_MS)
            }
        }
    }

    private suspend fun refreshPreviews() {
        val wsId = workspaceId ?: return
        val b = binder ?: return
        val items = _items.value
        if (items.isEmpty()) return
        val ptys = withContext(Dispatchers.IO) {
            items.mapNotNull { item ->
                b.getPtySession(wsId, item.terminalId)?.let { item.terminalId to it }
            }
        }
        // TS-002/CC-003: emulator buffers are unsynchronised and their writer
        // is the main-looper Handler; reading them from IO raced resize/append.
        // Snapshot the screen and title on Main (the owning thread); only the
        // /proc foreground probe stays on IO.
        val snapshots = withContext(Dispatchers.Main.immediate) {
            ptys.map { (terminalId, pty) ->
                PreviewRead(
                    terminalId = terminalId,
                    preview = runCatching {
                        pty.previewText(
                            maxLines = PREVIEW_MAX_LINES,
                            maxChars = PREVIEW_MAX_CHARS
                        )
                    }.getOrDefault(""),
                    foregroundProcess = null,
                    runtimeTitle = runCatching { pty.title }.getOrNull()
                )
            }
        }
        val foreground = withContext(Dispatchers.IO) {
            ptys.associate { (terminalId, pty) ->
                terminalId to runCatching { pty.foregroundProcessName() }.getOrNull()
            }
        }
        snapshots.forEach { read ->
            windowManager.updatePreview(read.terminalId, read.preview, foreground[read.terminalId])
            if (read.runtimeTitle != null) {
                windowManager.updateRuntimeTitle(read.terminalId, read.runtimeTitle)
            }
        }
        if (snapshots.isNotEmpty()) _desktop.value = windowManager.state
    }

    private data class PreviewRead(
        val terminalId: String,
        val preview: String,
        val foregroundProcess: String?,
        val runtimeTitle: String?
    )

    /**
     * Opens a fresh terminal in the workspace. Idempotent per user action:
     * a second call while one create is in flight is ignored (no duplicate
     * pty). On success the new terminal id is published once to
     * [createdTerminal] so the screen can navigate straight into it.
     */
    fun createTerminal() {
        val wsId = workspaceId ?: return
        if (_isCreating.value) return
        if (createJob?.isActive == true) return
        Logger.i(TAG, "Creating terminal for $wsId")
        _isCreating.value = true
        createJob = viewModelScope.launch {
            try {
                val createdId = binder?.openTerminal(wsId) ?: return@launch
                Logger.i(TAG, "Terminal $wsId/$createdId created")
                requestActiveTerminal(createdId, TerminalSelectionSource.ExternalOpenRequest)
                _createdTerminal.value = createdId
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create terminal for $wsId: ${e.message}", e)
            } finally {
                _isCreating.value = false
            }
        }
    }

    /** Consumes the one-shot [createdTerminal] id after the screen navigated. */
    fun consumeCreatedTerminal() {
        _createdTerminal.value = null
    }

    /**
     * Finding 011/019 — restart the terminal the user is looking at.
     *
     * The id does not change, so the window keeps its position, title and
     * focus: only the pty behind it is replaced. The manager enforces "Error
     * only" by default; [allowRunning] is the no-output banner's explicit
     * opt-in for replacing a live-but-silent pty. This method adds the per-id
     * in-flight guard the audit's "restart invokes the ViewModel once" row asks
     * for, so a double tap (or a recomposition-driven repeat) cannot open two
     * shells.
     *
     * No automatic retry: the only callers are the error card's and the
     * no-output banner's explicit Restart buttons.
     */
    fun restartTerminal(terminalId: String, allowRunning: Boolean = false) {
        val wsId = workspaceId ?: return
        val b = binder ?: return
        if (restartJobs[terminalId]?.isActive == true) return
        Logger.i(TAG, "Restarting terminal $wsId/$terminalId allowRunning=$allowRunning")
        restartJobs[terminalId] = viewModelScope.launch {
            try {
                b.restartTerminal(wsId, terminalId, allowRunning = allowRunning)
                Logger.i(TAG, "Terminal $wsId/$terminalId restarted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The manager refused (not Error / no launch rootfs) or the
                // open failed. The terminal's own state carries the failure;
                // a restart must not replace that with a UI-side error.
                Logger.e(TAG, "Failed to restart terminal $wsId/$terminalId: ${e.message}", e)
                // UX-002: …but it must be visible. The reason is shown by the
                // panel; a refusal would otherwise look like a no-op tap.
                _restartFailure.value = e.message ?: e::class.java.simpleName
            } finally {
                restartJobs.remove(terminalId)
            }
        }
    }

    /**
     * Closes exactly one terminal of the workspace. Leaves siblings running.
     *
     * §1.106: if this was the workspace's LAST terminal, the runtime opens a
     * replacement (a Running Workspace never has zero terminal sessions) and
     * returns its id; it is published through [createdTerminal] — the same
     * one-shot channel a user-initiated "new terminal" uses — so the screen
     * switches to the live replacement instead of stranding on the closed id.
     */
    fun closeTerminal(terminalId: String) {
        val wsId = workspaceId ?: return
        Logger.i(TAG, "Closing terminal $wsId/$terminalId")
        windowManager.beginClose(terminalId)
        _desktop.value = windowManager.state
        viewModelScope.launch {
            try {
                val replacementId = binder?.closeTerminal(wsId, terminalId)
                if (replacementId != null) {
                    Logger.i(TAG, "Terminal $wsId/$terminalId was the last one; switching to replacement $replacementId")
                    requestActiveTerminal(replacementId, TerminalSelectionSource.ExternalOpenRequest)
                    _createdTerminal.value = replacementId
                }
            } finally {
                // The backend close is complete; drop the card immediately so a
                // missing state emission can never leave a CLOSING tombstone.
                windowManager.finishClose(terminalId)
                _desktop.value = windowManager.state
            }
        }
    }

    /**
     * Closes EVERY open terminal (App-grid redesign: the Sessions overview's
     * task-manager card's "Close all").
     *
     * Each close goes through the same [binder.closeTerminal] hop the single
     * close uses, so the manager's own invariants stay authoritative — in
     * particular §1.106's never-zero rule: closing the final session makes the
     * runtime open a fresh replacement, whose id lands in [createdTerminal]
     * exactly as a single close's would, and the screen's existing effect
     * switches into it and dismisses the overview. The user's "close all"
     * therefore ends every running command and lands on one clean shell —
     * which is what the button promises, given the runtime's invariant.
     *
     * Serialized into one job (a second tap while draining is ignored), and
     * the item list is snapshotted up front: terminals that exit on their own
     * mid-drain are skipped by the manager's idempotent close anyway.
     */
    fun closeAll() {
        val wsId = workspaceId ?: return
        if (closeAllJob?.isActive == true) return
        val targets = _items.value.map { it.terminalId }
        if (targets.isEmpty()) return
        Logger.i(TAG, "Closing ALL ${targets.size} terminals of $wsId")
        targets.forEach { terminalId ->
            windowManager.beginClose(terminalId)
        }
        _desktop.value = windowManager.state
        closeAllJob = viewModelScope.launch {
            targets.forEach { terminalId ->
                try {
                    binder?.closeTerminal(wsId, terminalId)
                } catch (e: Exception) {
                    Logger.w(TAG, "closeAll: closing $wsId/$terminalId failed: ${e.message}")
                } finally {
                    windowManager.finishClose(terminalId)
                    _desktop.value = windowManager.state
                }
            }
        }
    }

    /** Drops the binder observation; the terminals themselves stay alive. */
    fun detachBinder() {
        workspaceId = null
        binder = null
        observeJob?.cancel()
        observeJob = null
        identityJob?.cancel()
        identityJob = null
        previewJob?.cancel()
        previewJob = null
        overviewVisible = false
        titleJobs.values.forEach { it.cancel() }
        titleJobs.clear()
        clearPendingSelection()
        knownTerminalIds = emptySet()
        windowManager = TerminalWindowManager()
        _desktop.value = TerminalDesktopState()
        _items.value = emptyList()
        _isCreating.value = false
    }

    override fun onCleared() {
        super.onCleared()
        detachBinder()
    }

    private companion object {
        const val TAG = "TerminalsViewModel"

        /** Targeted selection/identity trace; one line per state change, not per frame. */
        const val CHAIN_TAG = "TerminalChain"

        /** Overview preview cadence; fast enough to feel live, slow enough for /proc. */
        const val PREVIEW_POLL_MS = 900L
        const val PREVIEW_MAX_LINES = 14
        const val PREVIEW_MAX_CHARS = 2_400

        /** §9.1 ids: "T0" < "T1" < … < "T10" (numeric suffix, then lexicographic). */
        private val TERMINAL_ORDER: Comparator<TerminalItem> =
            compareBy<TerminalItem> { terminalNumber(it.terminalId) }.thenBy { it.terminalId }

        private fun terminalNumber(id: String): Int {
            val suffix = id.trimStart('T')
            val n = suffix.toIntOrNull()
            return n ?: Int.MAX_VALUE
        }

        // 2026-08-31 (Phase 6, compact session numbering): one shared build
        // path — sort by TERMINAL_ORDER, then derive the compact visible
        // label from the list position, so labels are always 1..N with no
        // gaps regardless of which internal ids are still alive.
        private fun toItems(terminals: Map<String, TerminalState>): List<TerminalItem> =
            terminals.map { (id, state) -> TerminalItem(id, state) }
                .sortedWith(TERMINAL_ORDER)
                .mapIndexed { index, item -> item.copy(displayLabel = compactLabel(index)) }

        private fun compactLabel(index: Int): String = "T${index + 1}"
    }
}
