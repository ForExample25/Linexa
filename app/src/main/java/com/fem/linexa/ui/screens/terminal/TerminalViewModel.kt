package com.fem.linexa.ui.screens.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for one terminal screen (§1.4, §7.2 Phase 2, §1.97/W3).
 *
 * §1.97/W3: this ViewModel is **terminal-specific** — it operates on
 * `(workspaceId, terminalId)`, not just the workspace. It resolves exactly
 * the requested terminal's [PtySession] and never silently falls back to
 * another terminal (in particular never to "T0"). The workspace's single
 * [RuntimeBinder] (owned by the graph-scoped
 * [com.fem.linexa.service.runtime.RuntimeViewModel], §1.2) is passed in via
 * [attachBinder]; this ViewModel never binds the service itself.
 *
 * **Lifecycle invariant (§11 matrix): a terminal SCREEN does not own the
 * terminal PROCESS.** [detachBinder] (and [onCleared]) only drop the UI-side
 * reference — they never call `closeTerminal`, so the pty/session stays
 * alive in [com.fem.linexa.service.runtime.WorkspaceTerminalManager] and a
 * later re-entry re-attaches to the same live session.
 *
 * State: [terminalState] mirrors the per-terminal process state
 * ([TerminalState], audit §9.2 — Starting/Running/Stopping/Stopped/Error);
 * `null` means the requested terminal id does not currently exist in the
 * workspace (deterministic "unavailable" state, §11 matrix — never a crash,
 * never a silent fallback). [ptySession] is the live pty for that exact
 * terminal while it is open.
 *
 * §1.59 hardening carried over: the observation job is restarted whenever a
 * *different* binder instance is attached (service reconnect yields a new
 * `RuntimeBinder`), and while the terminal is [TerminalState.Starting]/[Running]
 * but the pty lookup returns null (service-side ordering hiccup), a bounded
 * retry loop keeps re-resolving instead of giving up silently. Every hop
 * logs under the `TerminalChain` tag for on-device diagnosis.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val runtimeTrace: RuntimeTrace
) : ViewModel() {

    private val _ptySession = MutableStateFlow<PtySession?>(null)
    val ptySession: StateFlow<PtySession?> = _ptySession.asStateFlow()

    private val _terminalState = MutableStateFlow<TerminalState?>(null)
    val terminalState: StateFlow<TerminalState?> = _terminalState.asStateFlow()

    private var workspaceId: String? = null
    private var terminalId: String? = null
    private var source: PtySessionSource? = null
    private var observeJob: Job? = null
    private var retryJob: Job? = null

    /**
     * Attaches the workspace's shared binder (the graph-scoped
     * [RuntimeBinder], which implements [PtySessionSource]) and starts
     * observing the per-terminal state of [terminalId]. Safe to call
     * repeatedly — the same (workspace, terminal, source) re-attaches
     * idempotently (re-resolving the current value), a different source or
     * terminal replaces the observation.
     */
    fun attachBinder(workspaceId: String, terminalId: String, source: PtySessionSource) {
        attach(workspaceId, terminalId, source)
    }

    private fun attach(workspaceId: String, terminalId: String, source: PtySessionSource) {
        if (this.workspaceId == workspaceId && this.terminalId == terminalId && this.source === source) {
            Logger.d(TAG, "Re-attach of same workspace/terminal/source — re-resolving")
            resolvePty()
            return
        }
        Logger.d(TAG, "Attaching workspaceId=$workspaceId terminalId=$terminalId binder=${source::class.simpleName}")
        val switchedTerminal = this.workspaceId != workspaceId || this.terminalId != terminalId
        this.workspaceId = workspaceId
        this.terminalId = terminalId
        this.source = source
        if (switchedTerminal) {
            // Never let the previously focused window's pty survive into a
            // different window while the new terminal is still resolving. When
            // it resolves synchronously the very next line replaces this null
            // in the same call, so no frame ever loses a live terminal.
            _ptySession.value = null
        }
        observeJob?.cancel()
        retryJob?.cancel()
        observeJob = viewModelScope.launch {
            source.terminalStates.collect { states ->
                val state = states[workspaceId]?.get(terminalId)
                if (state != _terminalState.value) {
                    Logger.d(TAG, "Terminal state for $workspaceId/$terminalId: $state")
                }
                _terminalState.value = state
                Logger.i(TAG, "CHAIN: terminalStates emission ws=$workspaceId tid=$terminalId state=" + stateLabel(state))
                resolvePty()
            }
        }
        // Immediate sync: StateFlow always exposes the current value, so this
        // covers "terminal already open before the screen attaches" without
        // waiting for an emission.
        _terminalState.value = source.terminalStates.value[workspaceId]?.get(terminalId)
        resolvePty()
    }

    /**
     * Drops the UI-side reference to the terminal. **Detach only** — the pty
     * and its [TerminalSession] stay alive in the manager; re-entry via
     * [attachBinder] re-resolves the same live session. Never calls
     * `closeTerminal` (§11 matrix: screen lifecycle != process lifecycle).
     */
    fun detachBinder() {
        workspaceId = null
        terminalId = null
        source = null
        observeJob?.cancel()
        observeJob = null
        retryJob?.cancel()
        retryJob = null
        _terminalState.value = null
        _ptySession.value = null
    }

    override fun onCleared() {
        super.onCleared()
        detachBinder()
    }

    /**
     * The single resolution point: expose the live [PtySession] of the exact
     * requested terminal iff its [TerminalState] is [TerminalState.Starting]
     * or [TerminalState.Running] AND the lookup succeeds; anything else
     * (Stopping/Stopped/Error, or a terminal id that doesn't exist — null)
     * clears it. While Starting/Running-but-unresolvable, a bounded retry
     * loop keeps trying so a temporary service-side ordering gap can't strand
     * the terminal on the placeholder.
     */
    private fun resolvePty() {
        val wsId = workspaceId ?: return
        val tid = terminalId ?: return
        val src = source ?: return
        val state = _terminalState.value
        when (state) {
            is TerminalState.Starting, is TerminalState.Running -> {
                val pty = src.getPtySession(wsId, tid)
                if (pty != null) {
                    retryJob?.cancel()
                    retryJob = null
                    if (_ptySession.value?.id != pty.id) {
                        Logger.i(TAG, "TERMINAL_CHAIN: resolved live pty workspaceId=$wsId terminalId=$tid sessionId=${pty.id} pid=${pty.pid}")
                    }
                    Logger.i(TAG, "CHAIN: resolvePty SUCCESS pid=${pty.pid} ws=$wsId tid=$tid")
                    runtimeTrace.record(wsId, "TerminalViewModel.resolvePty SUCCESS terminalId=$tid pid=${pty.pid}")
                    _ptySession.value = pty
                } else {
                    Logger.d(TAG, "$tid not resolvable yet (state=$state) for $wsId — scheduling retry")
                    Logger.i(TAG, "CHAIN: resolvePty no-pty state=$state ws=$wsId tid=$tid — scheduling retry")
                    runtimeTrace.record(wsId, "resolvePty no-pty state=$state terminalId=$tid — scheduling retry")
                    scheduleRetry(wsId, tid, src)
                    _ptySession.value = null
                }
            }
            else -> {
                // Stopping/Stopped/Error, or the terminal id does not exist
                // (null) → no live pty; deterministic unavailable/error state.
                retryJob?.cancel()
                retryJob = null
                if (state is TerminalState.Error) {
                    Logger.e(TAG, "TERMINAL_CHAIN: terminal $wsId/$tid is in error state — no pty: ${state.reason}")
                }
                Logger.i(TAG, "CHAIN: resolvePty clearing pty ws=$wsId tid=$tid state=${stateLabel(state)}")
                runtimeTrace.record(wsId, "resolvePty clearing pty ws=$wsId terminalId=$tid state=${stateLabel(state)}")
                _ptySession.value = null
            }
        }
    }

    private fun stateLabel(state: TerminalState?): String = when (state) {
        is TerminalState.Starting -> "Starting"
        is TerminalState.Running -> "Running"
        is TerminalState.Stopping -> "Stopping"
        is TerminalState.Stopped -> "Stopped"
        is TerminalState.Error -> "Error(reason=${state.reason})"
        null -> "null"
    }

    private fun scheduleRetry(wsId: String, tid: String, src: PtySessionSource) {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_RETRY_ATTEMPTS) {
                // A state change (exited, errored, or the id disappeared)
                // ends the retry — resolvePty already handles the clearing.
                val state = _terminalState.value
                if (state !is TerminalState.Starting && state !is TerminalState.Running) {
                    retryJob = null
                    return@launch
                }
                delay(RETRY_DELAY_MS)
                attempts++
                val pty = src.getPtySession(wsId, tid)
                if (pty != null) {
                    Logger.i(TAG, "TERMINAL_CHAIN: retry $attempts resolved pty pid=${pty.pid} for $wsId/$tid")
                    _ptySession.value = pty
                    retryJob = null
                    return@launch
                }
                Logger.d(TAG, "Retry $attempts/$MAX_RETRY_ATTEMPTS: no pty for $wsId/$tid while ${stateLabel(_terminalState.value)}")
            }
            Logger.w(TAG, "Gave up waiting for pty of $wsId/$tid after $MAX_RETRY_ATTEMPTS attempts")
            retryJob = null
        }
    }

    private companion object {
        const val TAG = "TerminalChain"
        const val RETRY_DELAY_MS = 250L
        const val MAX_RETRY_ATTEMPTS = 40
    }
}
