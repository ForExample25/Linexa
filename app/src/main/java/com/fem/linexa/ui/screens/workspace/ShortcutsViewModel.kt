package com.fem.linexa.ui.screens.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.Shortcut
import com.fem.linexa.domain.packagemanager.PackageOperationClassifier
import com.fem.linexa.domain.terminal.TerminalWindowIdentity
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.pty.PtySession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-command state, keyed by [Shortcut.id].
 *
 * Two groups, because UI/UX Phase 5C's correction split the two ways a command
 * can run:
 *
 *  - [Opening] / [Launched] / [Failed] — a **leaf command tapped in the
 *    Commands tree**. It runs in a *new terminal session*, so the states this
 *    layer can report are only "opening the session", "handed to session Tn" and
 *    "could not get that far". Everything after the hand-off (output, exit code,
 *    prompts) belongs to the terminal, and deliberately never comes back here.
 *  - [Running] / [Done] — the header's `+`, which still executes inline through
 *    [RuntimeBinder.execCommandStreaming] and still reports into the dismissible
 *    row. That affordance is a one-off command the user typed, with no card and
 *    no session of its own to look at.
 */
sealed interface ShortcutRunState {
    data object Idle : ShortcutRunState

    /** Leaf: the new terminal session is being opened. */
    data object Opening : ShortcutRunState

    /**
     * Leaf: the command was written to [terminalId]'s shell.
     *
     * Terminal, in both senses — this layer never learns how the command ended,
     * because the user is watching the session it was sent to.
     */
    data class Launched(val terminalId: String) : ShortcutRunState

    /**
     * Leaf: the session could not be opened, or the command could not be sent.
     *
     * A distinct state rather than a `Done(success = false)`: nothing ran, so
     * there is no exit code and no output to show, and saying "failed — exit
     * null" for a session that never opened would misreport it.
     */
    data class Failed(val reason: String) : ShortcutRunState

    /** `+` only: streaming output of an inline run. */
    data class Running(val output: String) : ShortcutRunState

    /** `+` only: an inline run's result. */
    data class Done(val success: Boolean, val exitCode: Int?, val output: String) : ShortcutRunState
}

/**
 * The Commands section's actions: launching a declared command, and running the
 * header's one-off command.
 *
 * ### Leaf commands run in a new terminal session (UI/UX Phase 5C, corrected)
 *
 * Tapping a leaf **opens a new terminal session and executes the command
 * there** — it does not execute inside the card. The card was the wrong place
 * for it: these commands are long, interactive package operations (the reference
 * image's leaves are `apt install` invocations that ask questions), and an
 * `execCommandStreaming` run into a 3-line card tail can neither answer a prompt
 * nor show a pager. A real pty can.
 *
 * The session comes from the **existing** terminal infrastructure — the same
 * [RuntimeBinder.openTerminal] hop that the session overview's "new terminal"
 * card uses, which allocates through [com.fem.linexa.service.runtime.WorkspaceTerminalManager]'s
 * §9.1 id allocator and therefore never re-opens a live session (in particular
 * never T0). Nothing here starts a pty by itself.
 *
 * Why this lives here rather than delegating to
 * [com.fem.linexa.ui.screens.terminal.TerminalsViewModel.createTerminal], which
 * also opens a session:
 *
 *  - that call publishes the new id into a one-shot channel consumed by the
 *    terminal panel, and the panel is **not composed while the overlay is
 *    closed** (`AnimatedVisibility` does not compose invisible content) — which
 *    is exactly the state the Commands tab is in when a card is tapped, so the
 *    id would sit unconsumed and no command would ever be sent;
 *  - the created id has to be **paired with the command to send**. Splitting the
 *    open and the write across two owners means a second tap can interleave and
 *    send one command into the other command's session.
 *
 * So the open, the write and the "focus this session" signal are one
 * non-overlapping operation ([launchJob]), and the id is published for the UI to
 * *focus* rather than to complete the work.
 */
@HiltViewModel
class ShortcutsViewModel @Inject constructor(
    private val windowIdentities: TerminalWindowIdentityRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "ShortcutsViewModel"

        /**
         * Run-state key for commands entered through the Commands header's `+`
         * (UI/UX Phase 5C). Not a [Shortcut.id] from any catalog — matching
         * `WorkspaceShortcuts.CUSTOM_ID`, which declared ids are namespaced away
         * from, so an image cannot collide with this key and hijack the row.
         */
        const val CUSTOM_KEY = "custom"

        /** Bounded tail of command output kept per inline (`+`) run. */
        private const val MAX_OUTPUT_CHARS = 4_000

        /**
         * Bounded wait for the new session's pty to become resolvable.
         *
         * [RuntimeBinder.openTerminal] returns once the manager has registered
         * the session, so the first lookup normally succeeds; this mirrors
         * [com.fem.linexa.ui.screens.terminal.TerminalViewModel]'s bounded retry
         * for the service-side ordering gap it documents, rather than assuming
         * the happy path and dropping the command on the floor.
         */
        private const val PTY_ATTEMPTS = 20
        private const val PTY_RETRY_MS = 100L
    }

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null

    /**
     * One in-flight inline run PER command key (2026-09-05 fix): the single
     * shared `runJob` this replaced was cancelled by the NEXT run of any
     * other key, which killed an already-queued package operation's
     * collector mid-flight and left its card stuck at [ShortcutRunState.Running]
     * forever — and the stuck `Running` state then blocked that command's
     * per-key re-launch guard, so the command "did nothing" on every later
     * tap. Distinct commands are distinct operations: they run concurrently
     * (package operations still serialize inside the runtime's
     * [com.fem.linexa.domain.packagemanager.PackageOperationCoordinator]).
     * Same-key double taps are still ignored by the guard below.
     */
    private val runJobs = mutableMapOf<String, Job>()
    private var launchJob: Job? = null

    private val _runStates = MutableStateFlow<Map<String, ShortcutRunState>>(emptyMap())
    val runStates: StateFlow<Map<String, ShortcutRunState>> = _runStates.asStateFlow()

    /**
     * One-shot: the terminal session the UI should open and focus, set once a
     * leaf command has actually been written to it.
     *
     * Published *after* the write, so the overlay never opens onto a session
     * that turned out not to receive the command. Consumed via
     * [consumeLaunchedTerminal].
     */
    private val _launchedTerminal = MutableStateFlow<String?>(null)
    val launchedTerminal: StateFlow<String?> = _launchedTerminal.asStateFlow()

    fun attachBinder(wsId: String, runtimeBinder: RuntimeBinder) {
        // Pre-release audit fix (2026-09-02, finding U3/H-3): identity check —
        // a replacement binder (RuntimeService recreated) must be adopted,
        // not refused; the old guard kept the dead instance forever.
        if (workspaceId == wsId && runtimeBinder === binder) return
        workspaceId = wsId
        binder = runtimeBinder
    }

    fun detachBinder() {
        binder = null
        workspaceId = null
        runJobs.values.forEach { it.cancel() }
        runJobs.clear()
        launchJob?.cancel()
        _runStates.value = emptyMap()
        _launchedTerminal.value = null
    }

    override fun onCleared() {
        runJobs.values.forEach { it.cancel() }
        runJobs.clear()
        launchJob?.cancel()
        super.onCleared()
    }

    /**
     * Opens a session for [shortcut] and runs its command there.
     *
     * Issue #2 (2026-09-03) — THREE routing tiers, so every Linexa-triggered
     * package operation funnels through the ONE package queue:
     *
     *  1. `asRoot` entries (issue #1's elevation surface) open a **root**
     *     terminal and run their command there — a specific operation that
     *     explicitly requires root privileges gets a root session.
     *  2. Package operations (classified by the ONE
     *     [PackageOperationClassifier] the runtime queues with) execute
     *     through [RuntimeBinder.execCommandStreaming] — the same bridge as
     *     the Package Manager UI and every provisioner — so concurrent
     *     package requests QUEUE behind each other instead of racing the
     *     dpkg locks. Output and exit code report in the card, exactly like
     *     a `+` custom run. Non-interactive is intentional: every metadata
     *     package command is written `-y`/noninteractive, and the exec
     *     bridge answers prompts with documented defaults instead of
     *     stalling on an unanswerable pty question.
     *  3. Everything else (info commands, file helpers, clear-screen…) runs
     *     in a new interactive terminal as before — those are user-session
     *     commands and a pty is the honest place for them.
     *
     * Never reuses the session the user was last looking at: a command sent to
     * the active terminal would land in whatever that shell is currently doing
     * — mid-command, inside `less`, inside a half-typed line — and would
     * interleave with the user's own input.
     *
     * Serialized by [launchJob]: a second tap while one launch is in flight is
     * ignored, so a double tap cannot open two sessions or send one command into
     * the other's shell.
     */
    fun launch(shortcut: Shortcut) {
        val command = shortcut.command
        if (command.isNullOrBlank() && !shortcut.asRoot) {
            // 2026-09-05: visible no-op. Unreachable through validated
            // catalogs (the validator rejects commandless non-root leaves),
            // so firing means upstream data drifted — worth the trace line.
            Logger.w(TAG, "launch('${shortcut.id}') ignored: no command and not asRoot")
            return
        }
        val key = shortcut.id
        val b = binder
        if (b == null) {
            Logger.w(TAG, "launch('$key') ignored: no binder attached")
            return
        }
        val ws = workspaceId
        if (ws == null) {
            Logger.w(TAG, "launch('$key') ignored: no workspace bound")
            return
        }
        if (launchJob?.isActive == true) {
            // Deliberate same-shape dedup (double tap), now with a trace line
            // so a stuck launchJob can never hide behind silence.
            Logger.d(TAG, "launch('$key') ignored: a leaf launch is already in flight")
            return
        }

        if (command != null && command.any { it == '\n' || it.isISOControl() }) {
            // Defence in depth. Both metadata paths already guarantee a single-line,
            // control-character-free command (the native validator rejects the entry,
            // §1.120(c); the legacy flattener skips the leaf, §1.122) precisely so a
            // card can show the user the whole thing before they consent to it. That
            // guarantee is what makes writing this straight to a shell safe — an
            // embedded newline would be a second command the card never displayed —
            // so it is re-checked at the point of use rather than trusted across a
            // layer boundary. The queue path re-checks again inside [execute].
            Logger.w(TAG, "Refusing to launch multi-line command for '$key'")
            _runStates.update { it + (key to ShortcutRunState.Failed("Command is not a single line")) }
            return
        }

        val identity = TerminalWindowIdentity(
            title = shortcut.title,
            appId = shortcut.id,
            iconKey = null,
            iconRes = shortcut.iconRes.takeIf { it != 0 },
            command = command
        )

        if (shortcut.asRoot) {
            launchJob = viewModelScope.launch {
                _runStates.update { it + (key to ShortcutRunState.Opening) }
                var openedTerminalId: String? = null
                try {
                    val terminalId = b.openRootTerminal(ws)
                    openedTerminalId = terminalId
                    windowIdentities.record(ws, terminalId, identity)
                    val pty = awaitPty(ws, terminalId)
                    if (pty == null) {
                        Logger.e(TAG, "Opened $ws/$terminalId (root) but its pty never resolved")
                        // A session that never reached a usable pty is closed and
                        // its provisional identity dropped: no orphan shell and no
                        // window titled after a command that never ran.
                        windowIdentities.remove(ws, terminalId)
                        runCatching { b.closeTerminal(ws, terminalId) }
                        _runStates.update {
                            it + (key to ShortcutRunState.Failed("Terminal session did not start"))
                        }
                        return@launch
                    }
                    command?.let { pty.write(it + "\n") }
                    Logger.i(TAG, "Command '$key' sent to new ROOT terminal $ws/$terminalId")
                    _runStates.update { it + (key to ShortcutRunState.Launched(terminalId)) }
                    _launchedTerminal.value = terminalId
                } catch (e: CancellationException) {
                    // 2026-09-05 (U5/M-2 parity with execute()): a cancelled
                    // launch — workspace exit, binder swap, onCleared — is not
                    // a result; rethrow instead of writing a bogus Failed row
                    // over whatever state the UI is now showing.
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to launch '$key' in a root terminal", e)
                    openedTerminalId?.let { terminalId ->
                        windowIdentities.remove(ws, terminalId)
                        runCatching { b.closeTerminal(ws, terminalId) }
                    }
                    _runStates.update {
                        it + (key to ShortcutRunState.Failed(e.message ?: "Could not open a terminal"))
                    }
                }
            }
            return
        }

        if (PackageOperationClassifier.isPackageOperation(command!!)) {
            // Tier 2: the ONE queue. The card shows streamed output + exit code;
            // [PackageOperationCoordinator] publishes who owns the queue while
            // this waits.
            execute(key, command)
            return
        }

        launchJob = viewModelScope.launch {
            _runStates.update { it + (key to ShortcutRunState.Opening) }
            var openedTerminalId: String? = null
            try {
                // The existing allocator + open path (§9.1, §1.97/W2) — the same
                // one the session overview's "+" card uses.
                val terminalId = b.openTerminal(ws)
                openedTerminalId = terminalId
                windowIdentities.record(ws, terminalId, identity)
                val pty = awaitPty(ws, terminalId)
                if (pty == null) {
                    Logger.e(TAG, "Opened $ws/$terminalId but its pty never resolved")
                    windowIdentities.remove(ws, terminalId)
                    runCatching { b.closeTerminal(ws, terminalId) }
                    _runStates.update {
                        it + (key to ShortcutRunState.Failed("Terminal session did not start"))
                    }
                    return@launch
                }
                // The newline is the Enter key: the shell reads its input when it
                // is ready, so writing straight after the open is safe even
                // before the emulator view attaches — the pty buffers it, and the
                // output is in the session's transcript by the time the overlay
                // shows it.
                pty.write(command + "\n")
                Logger.i(TAG, "Command '$key' sent to new terminal $ws/$terminalId")
                _runStates.update { it + (key to ShortcutRunState.Launched(terminalId)) }
                _launchedTerminal.value = terminalId
            } catch (e: CancellationException) {
                // 2026-09-05 (U5/M-2 parity with execute()): same contract as
                // the root-terminal tier above.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to launch '$key' in a new terminal", e)
                openedTerminalId?.let { terminalId ->
                    windowIdentities.remove(ws, terminalId)
                    runCatching { b.closeTerminal(ws, terminalId) }
                }
                _runStates.update {
                    it + (key to ShortcutRunState.Failed(e.message ?: "Could not open a terminal"))
                }
            }
        }
    }

    /** Consumes the one-shot [launchedTerminal] id once the UI has focused it. */
    fun consumeLaunchedTerminal() {
        _launchedTerminal.value = null
    }

    /**
     * Runs a one-off command typed into the header's `+` dialog, inline.
     *
     * Deliberately *not* routed into a new terminal session. It has no card and
     * no place in the tree; its whole purpose is a quick answer (`uname -a`),
     * which the dismissible report row gives without a session to close
     * afterwards. Requirement: the `+` stays separate from the command tree.
     */
    fun runCustomCommand(command: String) {
        if (command.isBlank()) return
        execute(CUSTOM_KEY, command)
    }

    /**
     * Clears the custom run's result.
     *
     * Needed because that run has no card of its own to re-run and thereby
     * replace its state (UI/UX Phase 5C): without this its report row would
     * persist for the lifetime of the ViewModel. A run in progress is left alone
     * — dropping its state would orphan the streaming job's updates.
     */
    fun clearCustomRun() {
        if (_runStates.value[CUSTOM_KEY] is ShortcutRunState.Running) return
        _runStates.update { it - CUSTOM_KEY }
    }

    /** The new session's pty, or null if it never became resolvable. */
    private suspend fun awaitPty(
        workspaceId: String,
        terminalId: String
    ): PtySession? {
        var attempts = 0
        var pty = binder?.getPtySession(workspaceId, terminalId)
        while (pty == null && attempts < PTY_ATTEMPTS) {
            delay(PTY_RETRY_MS)
            attempts++
            pty = binder?.getPtySession(workspaceId, terminalId)
        }
        return pty
    }

    private fun execute(key: String, command: String) {
        val b = binder
        if (b == null) {
            // 2026-09-05: a tap that cannot run must be visible in the trace,
            // not silent — the binder is normally attached before any tap
            // (the workspace screen attaches it in a LaunchedEffect), so this
            // firing means a lifecycle ordering bug worth the log line.
            Logger.w(TAG, "execute('$key') ignored: no binder attached")
            return
        }
        val ws = workspaceId
        if (ws == null) {
            Logger.w(TAG, "execute('$key') ignored: no workspace bound")
            return
        }
        // Per-key guard (2026-09-05): ignore a second tap of the SAME command
        // while it runs; never touch other keys' in-flight jobs — cancelling
        // those stranded a running package operation's card (see runJobs).
        if (runJobs[key]?.isActive == true) {
            Logger.d(TAG, "execute('$key') ignored: already running")
            return
        }
        runJobs[key] = viewModelScope.launch {
            _runStates.update { it + (key to ShortcutRunState.Running("")) }
            val tail = StringBuilder()
            try {
                val result = b.execCommandStreaming(ws, command, onLine = { line ->
                    appendBounded(tail, line)
                    _runStates.update { it + (key to ShortcutRunState.Running(tail.toString())) }
                })
                _runStates.update {
                    it + (key to ShortcutRunState.Done(result.exitCode == 0, result.exitCode, result.output.trim()))
                }
            } catch (e: CancellationException) {
                // Pre-release audit fix (2026-09-02, finding U5/M-2): a
                // cancelled run is not a result; rethrow instead of writing a
                // fake Done(false) row over a newer run's state.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Custom command run failed", e)
                _runStates.update { it + (key to ShortcutRunState.Done(false, null, e.message ?: "Run failed")) }
            } finally {
                // The job slot must not outlive the job: the map exists only
                // to make the same-key guard honest, and by the time this
                // finally runs the operation is over (Done/Failed written or
                // cancellation unwound), so the key frees for the next tap.
                runJobs.remove(key)
            }
        }
    }

    /** Appends [line] to [out], dropping the head once [MAX_OUTPUT_CHARS] is exceeded. */
    private fun appendBounded(out: StringBuilder, line: String) {
        if (out.isNotEmpty()) out.append('\n')
        out.append(line)
        if (out.length > MAX_OUTPUT_CHARS) {
            out.delete(0, out.length - MAX_OUTPUT_CHARS)
        }
    }
}
