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

    /** Per-terminal title observers; cancelled when a terminal disappears. */
    private val titleJobs = mutableMapOf<String, Job>()

    /**
     * Active id requested before the window list caught up (a create/open can
     * publish its id before the manager's state emission arrives). Applied by
     * [syncWindows] once that id appears — a create tap must not strand the
     * desktop on an empty/idle surface.
     */
    private var pendingActiveTerminalId: String? = null

    private var overviewVisible = false

    fun attachBinder(workspaceId: String, binder: RuntimeBinder) {
        if (this.workspaceId == workspaceId && this.binder === binder) return
        Logger.d(TAG, "TerminalsViewModel attaching workspaceId=$workspaceId")
        if (this.workspaceId != workspaceId) {
            previewJob?.cancel()
            previewJob = null
            titleJobs.values.forEach { it.cancel() }
            titleJobs.clear()
            pendingActiveTerminalId = null
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
        windowIdentities.prune(wsId, liveIds)
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
        windowManager.syncSessions(sessions, windowIdentities.snapshot(wsId))
        val pending = pendingActiveTerminalId
        if (pending != null && windowManager.state.window(pending) != null) {
            windowManager.activate(pending)
            pendingActiveTerminalId = null
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

    // ── Desktop window actions (presentation only; ptys stay runtime-owned) ──

    /**
     * Focus [terminalId] if it exists. A not-yet-synced id is remembered and
     * applied when the manager's state emission arrives.
     */
    fun activateTerminal(terminalId: String) {
        pendingActiveTerminalId = terminalId
        if (windowManager.state.window(terminalId) == null) return
        pendingActiveTerminalId = null
        windowManager.activate(terminalId)
        _desktop.value = windowManager.state
    }

    /** Explicit user restore/focus (overview card tap, dock tap). */
    fun restoreTerminal(terminalId: String) {
        pendingActiveTerminalId = terminalId
        if (windowManager.state.window(terminalId) == null) return
        pendingActiveTerminalId = null
        windowManager.restore(terminalId)
        _desktop.value = windowManager.state
    }

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
        // /proc foreground probes and emulator screen reads are off the main
        // thread; the manager state update itself is a cheap value swap.
        val reads = withContext(Dispatchers.IO) {
            items.mapNotNull { item ->
                val pty = b.getPtySession(wsId, item.terminalId) ?: return@mapNotNull null
                PreviewRead(
                    terminalId = item.terminalId,
                    preview = runCatching {
                        pty.previewText(
                            maxLines = PREVIEW_MAX_LINES,
                            maxChars = PREVIEW_MAX_CHARS
                        )
                    }.getOrDefault(""),
                    foregroundProcess = runCatching { pty.foregroundProcessName() }.getOrNull(),
                    runtimeTitle = runCatching { pty.title }.getOrNull()
                )
            }
        }
        reads.forEach { read ->
            windowManager.updatePreview(read.terminalId, read.preview, read.foregroundProcess)
            if (read.runtimeTitle != null) {
                windowManager.updateRuntimeTitle(read.terminalId, read.runtimeTitle)
            }
        }
        if (reads.isNotEmpty()) _desktop.value = windowManager.state
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
        pendingActiveTerminalId = null
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
