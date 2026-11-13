package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.pty.PtySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.97/W2 — runtime-owned per-terminal bookkeeping for a Workspace, layered
 * above [ContainerRuntime] (which keeps all proot/pty execution — this class
 * never launches a process itself).
 *
 * Owns `Map<workspaceId, Map<terminalId, TerminalSession>>` (audit §5.2/§5.3):
 * one [TerminalSession] per open terminal, a [TerminalState] per terminal
 * (`Starting`/`Running`/`Stopping`/`Stopped`/`Error`) observable through
 * [terminalStates], per-terminal lifecycle isolation ([closeTerminal] tears
 * down exactly one terminal, leaving siblings running) and a workspace-wide
 * teardown primitive ([closeWorkspace]) that closes every terminal and then
 * delegates the workspace-level teardown (tmux hygiene + guest daemons) to
 * [ContainerRuntime.closeWorkspaceSessions].
 *
 * Terminal ids follow audit §9.1: charset `[A-Za-z0-9_-]`, per-workspace
 * unique, allocated monotonically via [nextTerminalId] (first = [DEFAULT_TERMINAL_ID]),
 * never reused after close within a workspace session (the per-workspace
 * counter is reset only when the workspace closes). W2 keeps the current
 * single-terminal path: [RuntimeService] opens [DEFAULT_TERMINAL_ID] through
 * this manager, so behavior is identical to pre-W2.
 *
 * Exit observation: the manager launches one observer per terminal that awaits
 * [PtySession.awaitExit] and transitions the terminal to [TerminalState.Error]
 * (unexpected death) / [TerminalState.Stopped] (clean exit) — the per-terminal
 * counterpart of RuntimeService's workspace-level exit handling. An intentional
 * close cancels the observer *before* destroying the pty, so a requested close
 * can never be misread as an unexpected death. Observers only ever update
 * terminal state — the workspace-level [com.fem.linexa.domain.runtime.RuntimeState]
 * stays with [SessionManager] (§1.16 / audit §9.2).
 */
@Singleton
class WorkspaceTerminalManager @Inject constructor(
    private val containerRuntime: ContainerRuntime,
    private val runtimeTrace: RuntimeTrace
) {

    private val mutex = Mutex()

    /** workspaceId → terminalId → live session record. Only mutated under [mutex]. */
    private val _terminalSessions = MutableStateFlow<Map<String, Map<String, TerminalSession>>>(emptyMap())

    /** workspaceId → terminalId → current TerminalState. Only mutated under [mutex]. */
    private val _terminalStates = MutableStateFlow<Map<String, Map<String, TerminalState>>>(emptyMap())

    /**
     * §1.103 — workspaceId → terminalId of the most recently *viewed*
     * terminal, kept so the runtime notification's Open action can restore
     * the last-active session. Set by [openTerminal] (a freshly opened
     * terminal is the active one) and [markTerminalActive] (the Terminal
     * screen records which terminal it renders). A StateFlow (not a plain
     * map) so it is safe to read from any thread — the notification is built
     * on the service's Main dispatcher while this is mutated under [mutex].
     * Only mutated under [mutex].
     */
    private val _lastActiveTerminals = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Per-terminal exit observers, cancelled on intentional close. Under [mutex]. */
    private val observers = mutableMapOf<String, MutableMap<String, Job>>()

    /** Per-workspace monotonic id counter (§9.1). Under [mutex]. Reset on workspace close. */
    private val nextTerminalCounters = mutableMapOf<String, Int>()

    /**
     * §9.1 — every terminal id that has been opened during the workspace's
     * current session, including already-closed ones. [nextTerminalId] skips
     * these, which is what makes "never reused after close" hold even for
     * terminals opened with an explicit id (which leave the counter untouched —
     * the launch path and every test that opens a specific id). Without it,
     * closing a workspace's only terminal freed its id again and the §1.106
     * replacement was handed back the id it had just closed. Cleared with the
     * rest of the session state on [closeWorkspace]. Under [mutex].
     */
    private val usedTerminalIds = mutableMapOf<String, MutableSet<String>>()

    /**
     * §1.106 — the rootfs path each workspace's terminals were opened with,
     * remembered so [ensureTerminalPresence] can open a replacement terminal
     * without the caller having to supply it again (the "a Running Workspace
     * never has zero terminal sessions" invariant is enforced here, where
     * every close funnels through, not at a UI call site). Recorded when a
     * terminal is registered, dropped on [closeWorkspace]. Under [mutex].
     *
     * The shell is not remembered because it is not this layer's to know:
     * [ContainerRuntime.openTerminal] resolves the launch shell itself from the
     * rootfs + `config.yaml` (`PRootContainerRuntime.resolveLaunchShell`), so
     * the replacement resolves exactly like the terminal it replaces. §17.1
     * removed the vestigial `shellPath` parameter that made this read as a
     * deliberate omission rather than the only possibility.
     */
    private val rootfsPaths = mutableMapOf<String, String>()

    /**
     * §1.106 — workspaces whose session is currently live (at least one
     * terminal was successfully opened and no [closeWorkspace] has run since).
     * This is NOT a third state machine: it is the "is this workspace session
     * still ours to keep terminals in" predicate that gates the
     * never-zero-terminals replacement, so a close that races a teardown can
     * never resurrect a terminal in a workspace that is going away. Under
     * [mutex].
     */
    private val liveWorkspaces = mutableSetOf<String>()

    /**
     * Terminals closed while their open was still in flight (record-less, still
     * [TerminalState.Starting]). A late-arriving open consults this and destroys
     * the pty it just created instead of registering it in a closed workspace
     * (close-race hygiene — audit §11 matrix). Persists until that id is next
     * opened, so an in-flight open can never leak a terminal into a closed
     * workspace.
     */
    private val closedDuringStart = mutableSetOf<Pair<String, String>>()

    /**
     * §1.97/PT0 — the workspace-level "session over" signal, decoupled from any
     * one terminal's pty. Completes with `null` when the workspace is closed
     * intentionally ([closeWorkspace] / teardown — stop/close/leave/restart/
     * gate eviction) — that is its ONLY completion. NO terminal exit completes
     * it — not the anchor terminal (T0) exiting on its own, not a sibling
     * exiting, not [closeTerminal] — so the workspace runtime and every sibling
     * terminal outlive any individual terminal (terminal lifecycle ≠ workspace
     * lifecycle). Under [mutex]; ONE deferred per session. It is never removed
     * on completion — a completed signal is kept so any (even late)
     * [awaitSessionEnd] waiter observes the ended session instead of minting a
     * fresh never-completing one — and it is replaced with a fresh uncompleted
     * deferred when the NEXT session's T0 opens ([openTerminal]).
     */
    private val workspaceEnd = mutableMapOf<String, CompletableDeferred<Int?>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Per-terminal state, keyed workspace then terminal id. */
    val terminalStates: StateFlow<Map<String, Map<String, TerminalState>>> = _terminalStates.asStateFlow()

    /**
     * The live terminal records, keyed workspace then terminal id.
     *
     * The Sessions segment reads this to enumerate real sessions (id, pty pid,
     * state, creation time) instead of inferring them from terminal state.
     * Emitted under [mutex] like every other mutation of the session map.
     */
    val terminalSessions: StateFlow<Map<String, Map<String, TerminalSession>>> =
        _terminalSessions.asStateFlow()

    /**
     * 2026-08-30 (Issues 4+7 — the notification's Open target must never go
     * stale): the same map [lastActiveTerminalId] reads, as a flow. The
     * foreground service collects this so the notification's Open deep link
     * is rebuilt whenever the user switches the terminal they are viewing —
     * previously the Open action kept the terminal id it was built with
     * (usually T0) until some unrelated content change happened to repost,
     * so tapping the notification while already viewing T1 retargeted the
     * overlay back to T0: a session switch (detach/attach flash) on what
     * must be a same-destination no-op. StateFlow conflation keeps this
     * honest: re-recording the SAME terminal emits nothing, so repeated
     * attaches cannot churn the notification.
     */
    val lastActiveTerminals: StateFlow<Map<String, String>> = _lastActiveTerminals.asStateFlow()

    /**
     * §1.97/W5 — the startup recovery sweep entry (audit §13): called once per
     * fresh process, on first bind, BEFORE any workspace opens. Scans for
     * stale per-workspace runtime scratch (PROOT_TMP_DIR scopes, tmux sockets)
     * left by a process that died abnormally and releases it — the runtime
     * analog of [com.fem.linexa.service.installer.InstallRecoveryManager]'s
     * interrupted-install sweep. A fresh process has no live sessions, so
     * nothing the sweep deletes can belong to a running workspace; the actual
     * reclamation is delegated to [ContainerRuntime.startupRecoverySweep]
     * (this layer never launches processes or owns the tmp/socket primitives).
     *
     * Serialization is the lifecycle manager's job ([RuntimeLifecycleManager]
     * runs this under its transition mutex, once per process, before the first
     * START/RESTART). Best-effort: partial failures are reported, never thrown.
     */
    suspend fun initialize(): RecoverySweepReport {
        val report = containerRuntime.startupRecoverySweep()
        Logger.i(TAG, "initialize (startup recovery sweep): $report")
        return report
    }

    /**
     * §9.1: next terminal id for [workspaceId], allocated monotonically within
     * the workspace session (first = `"T0"`), never reused after close. Resets
     * when the workspace closes, so a fresh workspace session starts at `"T0"`.
     *
     * The launch path ([RuntimeLifecycleManager.requestStart])
     * allocates the workspace's first terminal ("T0") through this counter, so
     * "T0" is a real allocator allocation. As defense-in-depth, ids already in
     * use for the workspace are skipped and the counter advanced past them:
     * a terminal opened out-of-band (an explicit id, not via this allocator —
     * the W6 device bug opened the live launch "T0" again and the
     * replace-on-open path destroyed it, SIGKILL -9 → Error → whole workspace
     * torn down) can never be handed out again. A used id stays out of the
     * allocator for the rest of the workspace session (§9.1
     * never-reuse-after-close) — [usedTerminalIds] remembers already-closed
     * ids too, so a close (including the §1.106 replacement path) can never
     * hand back the id it just closed.
     */
    suspend fun nextTerminalId(workspaceId: String): String = mutex.withLock {
        val inUse = _terminalSessions.value[workspaceId].orEmpty().keys +
            _terminalStates.value[workspaceId].orEmpty().keys +
            usedTerminalIds[workspaceId].orEmpty()
        var n = nextTerminalCounters[workspaceId] ?: 0
        while ("T$n" in inUse) {
            n++
        }
        nextTerminalCounters[workspaceId] = n + 1
        "T$n"
    }

    /**
     * Open a terminal inside [rootfsPath]. Delegates the actual proot/pty
     * launch to [ContainerRuntime.openTerminal]; returns the live [PtySession]
     * so the caller (RuntimeService's existing single-terminal path) keeps
     * working unchanged. State sequence: [TerminalState.Starting] (before
     * launch) → [TerminalState.Running] (pty alive). Reopening the same
     * (workspaceId, terminalId) replaces the previous session (W1
     * replace-on-open semantics).
     *
     * §17.1: there is no `shellPath` parameter. This function used to declare
     * one, defaulting to `/bin/bash`, and forward it to a runtime that ignored
     * it — see [ContainerRuntime.openTerminal] for why resolution belongs
     * there and why the parameter was removed rather than honoured. Every
     * caller already relied on that resolution, including
     * [ensureTerminalPresence]'s replacement open (see [rootfsPaths]).
     */
    suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String = DEFAULT_TERMINAL_ID,
        asRoot: Boolean = false
    ): PtySession {
        require(isValidTerminalId(terminalId)) {
            "invalid terminal id '$terminalId' (§9.1: [A-Za-z0-9_-])"
        }
        mutex.withLock {
            // A fresh open supersedes a prior close of this id.
            closedDuringStart.remove(workspaceId to terminalId)
            // Replacing an existing terminal: drop its record + cancel its
            // observer so the old pty's exit can't transition state after the
            // replacement. ContainerRuntime.openTerminal destroys the old pty.
            cancelObserverLocked(workspaceId, terminalId)
            removeSessionLocked(workspaceId, terminalId)
            setStateLocked(workspaceId, terminalId, TerminalState.Starting)
            Logger.i(TAG, "openTerminal $workspaceId/$terminalId → Starting")
            runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.openTerminal ENTER terminalId=$terminalId")
        }

        val pty = try {
            containerRuntime.openTerminal(workspaceId, rootfsPath, terminalId, asRoot = asRoot)
        } catch (e: Exception) {
            mutex.withLock {
                if (!closedDuringStart.contains(workspaceId to terminalId)) {
                    setStateLocked(workspaceId, terminalId, TerminalState.Error(e.message ?: "Failed to open terminal"))
                }
            }
            throw e
        }

        var abort = false
        mutex.withLock {
            if (closedDuringStart.contains(workspaceId to terminalId)) {
                // The terminal was closed while this open was in flight: destroy
                // the pty we just created and leave the terminal closed rather
                // than registering it in a closed workspace.
                closedDuringStart.remove(workspaceId to terminalId)
                abort = true
            } else {
                val session = TerminalSession(
                    workspaceId = workspaceId,
                    terminalId = terminalId,
                    ptySession = pty,
                    state = TerminalState.Running,
                    createdAt = Instant.now()
                )
                _terminalSessions.value = _terminalSessions.value + (
                    workspaceId to (_terminalSessions.value[workspaceId].orEmpty() + (terminalId to session))
                )
                setStateLocked(workspaceId, terminalId, TerminalState.Running)
                // §1.106: remember what a replacement terminal for this
                // workspace must be opened with, and mark the workspace session
                // live so the never-zero-terminals rule applies to it.
                rootfsPaths[workspaceId] = rootfsPath
                liveWorkspaces += workspaceId
                // §9.1: this id is spent for the rest of the session, even
                // after it closes.
                usedTerminalIds.getOrPut(workspaceId) { mutableSetOf() } += terminalId
                // §1.103: a freshly opened terminal is the one the user is
                // looking at — it becomes the workspace's last-active terminal
                // (the notification Open action restores it).
                _lastActiveTerminals.value = _lastActiveTerminals.value + (workspaceId to terminalId)
                if (terminalId == DEFAULT_TERMINAL_ID) {
                    // §1.97/PT0: the workspace-end signal for THIS session. Make it
                    // exist BEFORE the exit observer can complete it (a very fast T0
                    // exit must never race a later awaitSessionEnd into a
                    // never-completing deferred), and never re-create it while a
                    // session is live: a waiter that subscribed before T0 exited
                    // holds the same deferred, so its completion must reach it. Only
                    // a completed signal (a previous session ended — teardown) or no
                    // signal yet is replaced with a fresh one.
                    val existing = workspaceEnd[workspaceId]
                    if (existing == null || existing.isCompleted) {
                        workspaceEnd[workspaceId] = CompletableDeferred()
                    }
                }
                observers.getOrPut(workspaceId) { mutableMapOf() }[terminalId] = scope.launch {
                    observeExit(workspaceId, terminalId, pty)
                }
                Logger.i(TAG, "openTerminal $workspaceId/$terminalId → Running pid=${pty.pid}")
                runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.openTerminal EXIT terminalId=$terminalId pid=${pty.pid}")
            }
        }

        if (abort) {
            // Destroy only the pty THIS open created; a concurrent reopen may
            // already have replaced it in the container (W1 replace-on-open).
            val current = containerRuntime.getTerminal(workspaceId, terminalId)
            if (current?.id == pty.id) {
                containerRuntime.closeTerminal(workspaceId, terminalId)
            }
            throw IllegalStateException("Terminal $workspaceId/$terminalId was closed while starting")
        }
        return pty
    }

    /**
     * §1.97/PT0: suspend until the workspace session is over, then return how
     * it ended — always `null`, because the signal completes ONLY on an
     * explicit [closeWorkspace] / teardown. NO terminal exit completes it —
     * not [closeTerminal], not T0 (or any terminal) exiting on its own — so
     * the workspace runtime outlives its terminals, and this is the signal
     * RuntimeService's session watcher awaits instead of any terminal pty's
     * `awaitExit`. Safe to call before anything is open (the signal is created
     * on demand) and idempotent for concurrent waiters of the same workspace.
     */
    suspend fun awaitSessionEnd(workspaceId: String): Int? {
        val deferred = mutex.withLock {
            workspaceEnd.getOrPut(workspaceId) { CompletableDeferred() }
        }
        return deferred.await()
    }

    /**
     * Tear down exactly one terminal of a workspace. Cancels its exit observer
     * first (so the intentional close can't be misread as an unexpected death),
     * marks [TerminalState.Stopping], destroys the pty via
     * [ContainerRuntime.closeTerminal], then forgets the terminal (its id is
     * not reused within the workspace session — §9.1). Leaves sibling terminals
     * and the workspace runtime untouched. Idempotent.
     *
     * §1.106 — **never-zero-terminals (Termux-like semantics):** closing the
     * LAST remaining terminal of a live workspace session immediately opens a
     * replacement, and its id is returned so the caller (the Terminal screen,
     * through [RuntimeBinder.closeTerminal]) can switch to it instead of
     * stranding on the closed id's "unavailable" placeholder. Returns null when
     * no replacement was needed (siblings remain) or when the workspace session
     * is not live (it was never opened, or a [closeWorkspace] teardown is
     * already under way — a close must never resurrect a terminal in a
     * workspace that is going away). Enforced here rather than at a UI call
     * site because every terminal close funnels through this method.
     */
    suspend fun closeTerminal(workspaceId: String, terminalId: String): String? {
        val ptyToDestroy: PtySession?
        mutex.withLock {
            cancelObserverLocked(workspaceId, terminalId)
            val hadRecord = _terminalSessions.value[workspaceId]?.containsKey(terminalId) == true
            ptyToDestroy = _terminalSessions.value[workspaceId]?.get(terminalId)?.ptySession
            removeSessionLocked(workspaceId, terminalId)
            // §1.103: if the closed terminal was the last-active one, the
            // notification target falls back to the most recently opened
            // remaining terminal (or is dropped when none remain).
            recomputeLastActiveLocked(workspaceId)
            if (!hadRecord) {
                // Still Starting (open in flight): tombstone it so the late
                // open destroys its pty instead of registering.
                closedDuringStart += (workspaceId to terminalId)
            }
            setStateLocked(workspaceId, terminalId, TerminalState.Stopping)
            Logger.i(TAG, "closeTerminal $workspaceId/$terminalId → Stopping")
            runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.closeTerminal terminalId=$terminalId → Stopping")
        }
        if (ptyToDestroy != null) {
            containerRuntime.closeTerminal(workspaceId, terminalId)
        }
        mutex.withLock {
            removeStateLocked(workspaceId, terminalId)
            Logger.i(TAG, "closeTerminal $workspaceId/$terminalId closed")
            runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.closeTerminal terminalId=$terminalId closed")
        }
        return ensureTerminalPresence(workspaceId)
    }

    /**
     * §1.106 — the never-zero-terminals rule: if [workspaceId]'s session is
     * live but has no terminal left, open a replacement and return its id;
     * otherwise return null. Called after every [closeTerminal].
     *
     * The replacement is opened through the normal [openTerminal] path (same
     * allocator, same [ContainerRuntime] launch, same shell resolution), so it
     * is indistinguishable from a user-created terminal. A failure to open the
     * replacement is logged and swallowed: a close must always complete, and a
     * workspace that momentarily has zero terminals is recoverable from the UI
     * ("new session"), whereas a throwing close would leave the closed terminal
     * half-destroyed from the caller's point of view.
     */
    private suspend fun ensureTerminalPresence(workspaceId: String): String? {
        val rootfsPath = mutex.withLock {
            val live = workspaceId in liveWorkspaces
            val remaining = _terminalSessions.value[workspaceId].orEmpty()
            if (!live || remaining.isNotEmpty()) return@withLock null
            rootfsPaths[workspaceId]
        } ?: return null

        val replacementId = nextTerminalId(workspaceId)
        Logger.i(TAG, "closeTerminal left $workspaceId with zero terminals → opening replacement $replacementId")
        runtimeTrace.record(workspaceId, "never-zero-terminals: opening replacement terminal $replacementId")
        return try {
            openTerminal(workspaceId, rootfsPath, replacementId)
            replacementId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Replacement terminal $workspaceId/$replacementId failed to open: ${e.message}")
            runtimeTrace.record(workspaceId, "never-zero-terminals: replacement $replacementId FAILED: ${e.message}")
            null
        }
    }

    /**
     * Close every terminal of a workspace and tear the workspace down — the
     * workspace-wide teardown primitive (stop/restart/leave all terminate the
     * whole workspace, not one terminal). Cancels each observer, marks each
     * [TerminalState.Stopping], destroys every pty, then delegates the
     * workspace-level teardown (tmux hygiene + guest daemons) to
     * [ContainerRuntime.closeWorkspaceSessions]. Forgets the whole workspace
     * (records, states, id counter), so a fresh session starts clean at `"T0"`.
     * Idempotent and safe when nothing is open.
     */
    suspend fun closeWorkspace(workspaceId: String) {
        mutex.withLock {
            val sessions = _terminalSessions.value[workspaceId].orEmpty()
            sessions.keys.forEach { terminalId ->
                cancelObserverLocked(workspaceId, terminalId)
                setStateLocked(workspaceId, terminalId, TerminalState.Stopping)
            }
            // Terminals still Starting (record-less) must not let a late-arriving
            // open register into the closed workspace.
            (_terminalStates.value[workspaceId].orEmpty().keys - sessions.keys).forEach { terminalId ->
                closedDuringStart += (workspaceId to terminalId)
            }
            // §P0: an explicit teardown ends the workspace session — the ONLY
            // completion of the end signal (always null; a terminal exit never
            // completes it), so any awaitSessionEnd waiter unblocks as
            // "Stopped", never as a crash. The signal is created on demand so
            // a close is observable even when nothing was ever opened (or the
            // waiter subscribes after the close); the completed signal stays
            // in the map — a late awaitSessionEnd must observe the ended
            // session, not mint a fresh never-completing one; the next
            // session's openTerminal replaces it.
            workspaceEnd.getOrPut(workspaceId) { CompletableDeferred() }.complete(null)
            // §1.106: the session is over — drop it from the live set BEFORE
            // the suspending container teardown below, so a closeTerminal that
            // races this teardown sees a non-live workspace and does NOT open a
            // replacement terminal into a workspace that is going away.
            liveWorkspaces -= workspaceId
            Logger.i(TAG, "closeWorkspace $workspaceId ENTER terminals=${sessions.keys.sorted()}")
            runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.closeWorkspace ENTER terminals=${sessions.keys.sorted()}")
        }
        // Destroy every pty through the container, then the workspace-wide
        // teardown (tmux + daemons). Outside the manager mutex: this suspends
        // into ContainerRuntime and must not hold the manager lock across it.
        containerRuntime.closeWorkspaceSessions(workspaceId)
        mutex.withLock {
            _terminalSessions.value = _terminalSessions.value - workspaceId
            _terminalStates.value = _terminalStates.value - workspaceId
            _lastActiveTerminals.value = _lastActiveTerminals.value - workspaceId
            observers.remove(workspaceId)
            nextTerminalCounters.remove(workspaceId)
            usedTerminalIds.remove(workspaceId)
            // §1.106: forget the workspace's launch rootfs with the rest of its
            // session state — a fresh session records its own on open.
            rootfsPaths.remove(workspaceId)
            liveWorkspaces -= workspaceId
            Logger.i(TAG, "closeWorkspace $workspaceId complete")
            runtimeTrace.record(workspaceId, "WorkspaceTerminalManager.closeWorkspace complete")
        }
    }

    /**
     * The live pty for one terminal of a workspace, or null if the terminal is
     * not open (unknown, closed, stopped or errored). The provisioning seam the
     * Terminal UI (and [RuntimeBinder]) uses to resolve a terminal's session.
     */
    fun getPtySession(workspaceId: String, terminalId: String): PtySession? {
        val session = _terminalSessions.value[workspaceId]?.get(terminalId) ?: return null
        return when (session.state) {
            is TerminalState.Starting, is TerminalState.Running -> session.ptySession
            else -> null
        }
    }

    /**
     * §1.103 — the terminal the runtime notification's Open action should
     * restore for [workspaceId]: the most recently *viewed* open terminal
     * (recorded by [openTerminal] / [markTerminalActive]); if that one was
     * closed, the most recently *opened* open terminal ([TerminalSession.createdAt],
     * the W3 activity ordering); if no terminal is open (e.g. the workspace
     * is still Starting), the workspace default [DEFAULT_TERMINAL_ID]. Never
     * returns a closed/unknown terminal id. Thread-safe (reads immutable
     * StateFlow snapshots).
     */
    fun lastActiveTerminalId(workspaceId: String): String {
        val sessions = _terminalSessions.value[workspaceId].orEmpty()
        val recorded = _lastActiveTerminals.value[workspaceId]
        if (recorded != null && sessions.containsKey(recorded)) return recorded
        return sessions.maxByOrNull { it.value.createdAt }?.key ?: DEFAULT_TERMINAL_ID
    }

    /**
     * §1.103 — record the terminal the user is currently viewing in
     * [workspaceId] (called by the Terminal screen when it attaches/renders a
     * terminal). This is what makes the notification's Open action restore
     * the "last active session" instead of always the default T0. Best-effort
     * and idempotent: a terminal id that is not currently open is ignored
     * (never a fallback to another terminal).
     */
    suspend fun markTerminalActive(workspaceId: String, terminalId: String) {
        mutex.withLock {
            if (_terminalSessions.value[workspaceId]?.containsKey(terminalId) == true) {
                _lastActiveTerminals.value = _lastActiveTerminals.value + (workspaceId to terminalId)
                Logger.i(TAG, "markTerminalActive $workspaceId/$terminalId")
            }
        }
    }

    /**
     * §1.103 — after a terminal was removed from [workspaceId], keep the
     * last-active record pointing at a real open terminal: if the recorded
     * last-active was the closed one, fall back to the most recently opened
     * remaining terminal; with nothing left, drop the record. Caller must
     * hold [mutex].
     */
    private fun recomputeLastActiveLocked(workspaceId: String) {
        val sessions = _terminalSessions.value[workspaceId].orEmpty()
        if (sessions.isEmpty()) {
            _lastActiveTerminals.value = _lastActiveTerminals.value - workspaceId
            return
        }
        val recorded = _lastActiveTerminals.value[workspaceId]
        if (recorded != null && sessions.containsKey(recorded)) return
        val fallback = sessions.maxByOrNull { it.value.createdAt }?.key ?: return
        _lastActiveTerminals.value = _lastActiveTerminals.value + (workspaceId to fallback)
    }

    /**
     * Per-terminal exit observer: suspends on [PtySession.awaitExit] and
     * transitions the terminal to [TerminalState.Error] (unexpected death) or
     * [TerminalState.Stopped] (clean exit). Only acts when [pty] is still the
     * manager's live session for the id — a reopen/close cancels this observer
     * and must never let a stale pty transition the current session.
     *
     * Phase 6b bug-fix — **post-exit bookkeeping mirrors [closeTerminal]'s,
     * but ONLY for a clean exit (`exitCode == 0`):** when the pty exits with
     * status 0 (user typed `exit`), the dead terminal is removed from BOTH
     * [_terminalStates] and [_terminalSessions] (so `TerminalsViewModel.items`
     * reflects its absence — the screen's existing sibling-fallback at
     * `WorkspaceTerminalPanel.LaunchedEffect(terminalItems, activeTerminalId)`
     * then switches to a live sibling in place), [_lastActiveTerminals] is
     * recomputed so the next notification-tap restores a *live* terminal
     * rather than the dead one, and — because `_terminalSessions` is now
     * genuinely empty when this was the workspace's last terminal —
     * [ensureTerminalPresence] correctly opens a replacement (the
     * never-zero-terminals rule, §1.106). The new id surfaces through
     * `_terminalStates` → `_items` → the same sibling-fallback effect, so the
     * user lands on the replacement instead of on a "loading + Stopped"
     * placeholder for a dead id.
     *
     * **An unexpected exit (`exitCode != 0` — a crash or SIGKILL) does NONE
     * of the above.** It stays in both `_terminalStates` and
     * `_terminalSessions`, exactly like the pre-Phase-6b behavior. This is
     * deliberate, not an oversight: §1.191 built [TerminalState.Error]'s
     * `killedBySigkill` flag and the [com.fem.linexa.ui.screens.terminal.TerminalKilledNotice]
     * footer specifically so a SIGKILLed terminal's placeholder is *seen* by
     * the user, with actionable content, rather than silently disappearing.
     * An earlier version of this fix pruned and auto-switched away from
     * *every* exit, including `Error` — which meant a SIGKILLed terminal was
     * replaced by a sibling (or a fresh replacement) before the user ever
     * saw the §1.191 notice, undoing that entire feature. Auto-fallback is a
     * correct UX for "nothing to show" (`Stopped`); it is the wrong UX for
     * "something failed and you should know why" (`Error`). A crashed
     * terminal stays visible — in the panel if it was active, and in the
     * session overview either way — until the user acts on it explicitly
     * (switches away, or closes it via the overview's X, which goes through
     * [closeTerminal] and prunes it the same way an explicit close always
     * has).
     *
     * **Why the clean-exit case was a bug before this fix:** the original
     * version only transitioned state to `Stopped`/`Error` and stopped
     * there. The dead terminal stayed in `_terminalSessions` (so
     * `terminalItems` still contained it), the sibling-fallback effect did
     * NOT fire (its predicate `terminalItems.none { it.terminalId ==
     * activeTerminalId }` was false because the dead terminal was still in
     * the list), the never-zero-terminals replacement did NOT fire (it was
     * only wired into [closeTerminal], and even if called here would have
     * seen a non-empty `_terminalSessions` because of the same stale entry),
     * and the panel sat on the dead id painting `LinexaBlobProgress` +
     * "Stopped" until the user manually picked another session. The user's
     * complaint — "exiting a terminal shows a loading page with progress,
     * whereas it should go back to the previous terminal if one exists, or
     * open a new terminal if not" — is exactly this gap, and is fixed for
     * `exitCode == 0` only.
     */
    private suspend fun observeExit(workspaceId: String, terminalId: String, pty: PtySession) {
        val exitCode = try {
            pty.awaitExit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
        var wasCleanExit = false
        mutex.withLock {
            val current = _terminalSessions.value[workspaceId]?.get(terminalId)
            if (current?.ptySession !== pty) return@withLock
            if (exitCode == 0) {
                wasCleanExit = true
                setStateLocked(workspaceId, terminalId, TerminalState.Stopped)
                setSessionStateLocked(workspaceId, terminalId, TerminalState.Stopped)
                Logger.i(TAG, "terminal $workspaceId/$terminalId exited cleanly (code 0) → Stopped")
                runtimeTrace.record(workspaceId, "Terminal $terminalId exited code=0 → Stopped")

                // Phase 6b bug-fix (corrected) — a clean exit is "nothing left
                // to show", so drop the dead terminal from BOTH the public
                // state map (_terminalStates, which the UI's sibling-fallback
                // keys on) AND the dispatch map (_terminalSessions, which
                // ensureTerminalPresence's emptiness check reads) and
                // recompute last-active. Mirroring closeTerminal's
                // removeSessionLocked + removeStateLocked +
                // recomputeLastActiveLocked exactly — including
                // removeSessionLocked, which the first version of this fix
                // omitted — is what makes ensureTerminalPresence below
                // actually see an empty `_terminalSessions[workspaceId]` and
                // open a replacement when this was the last terminal, instead
                // of silently no-op'ing against its own now-stale entry.
                removeSessionLocked(workspaceId, terminalId)
                removeStateLocked(workspaceId, terminalId)
                recomputeLastActiveLocked(workspaceId)
                Logger.i(TAG, "terminal $workspaceId/$terminalId pruned after clean exit — UI sibling-fallback will take over")
                runtimeTrace.record(workspaceId, "Terminal $terminalId pruned after clean exit — sibling-fallback takes over")
            } else {
                val reason = if (exitCode < 0) {
                    "Terminal $terminalId exited unexpectedly"
                } else {
                    "Terminal $terminalId exited unexpectedly (code $exitCode)"
                }
                // §1.191: carry the raw exit status alongside the reason so the
                // UI can tell a SIGKILL (-9, almost always the OS reclaiming
                // memory) apart from an ordinary crash. This is additive
                // *diagnosis* only — the transition itself is unchanged, and
                // still per-terminal: a killed terminal never drives the
                // workspace or its siblings anywhere (see the §P0 note below).
                //
                // Deliberately NOT pruned and NOT auto-switched away from —
                // see this function's doc comment. An Error terminal (crash
                // or SIGKILL) stays in both _terminalStates and
                // _terminalSessions exactly as before Phase 6b, so its
                // message (and, for SIGKILL, the §1.191 TerminalKilledNotice
                // footer) stays visible until the user explicitly moves away
                // from or closes it.
                val error = TerminalState.Error(reason, exitCode)
                setStateLocked(workspaceId, terminalId, error)
                setSessionStateLocked(workspaceId, terminalId, error)
                Logger.w(TAG, "terminal $workspaceId/$terminalId exited code=$exitCode → Error (kept visible, not pruned)")
                runtimeTrace.record(workspaceId, "Terminal $terminalId exited code=$exitCode → Error (kept visible, not pruned)")
            }
            // §P0: terminal lifecycle ≠ workspace lifecycle. A terminal's
            // natural exit transitions ONLY that terminal's state — it never
            // completes the workspace session signal, so the workspace and its
            // sibling terminals stay alive. The workspace session ends only on
            // an explicit closeWorkspace (stop/close/leave). Genuine failures
            // stay observable here — the Error state above is surfaced through
            // terminalStates and logged/traced — just without destroying the
            // workspace.
            Logger.i(TAG, "workspace $workspaceId terminal $terminalId exited code=$exitCode → workspace session continues (terminal/workspace independence)")
            runtimeTrace.record(workspaceId, "Terminal $terminalId exited code=$exitCode; workspace session continues")
        }
        if (wasCleanExit) {
            // Newly found while fixing the above: a clean exit reaching this
            // point had never called containerRuntime.closeTerminal, unlike
            // the explicit-X-button path (closeTerminal() above), which
            // means the terminal's PROOT_TMP_DIR scope
            // (bootstrapManager.releaseProotTmpDir, allocated per-terminal —
            // see PRootContainerRuntime.closeTerminal's own §1.48/§1.97
            // comment) was never released on a natural exit, only on an
            // explicit close. destroy()-ing an already-exited pty is safe —
            // TermuxPtySession.destroy() calls finishIfRunning(), which is a
            // no-op on a session that has already finished — so calling the
            // same cleanup closeTerminal() uses is safe here too. Outside the
            // mutex, matching closeTerminal()'s own ordering (containerRuntime
            // has its own internal lock).
            containerRuntime.closeTerminal(workspaceId, terminalId)
            // Phase 6b bug-fix — never-zero-terminals replacement, in case the
            // exited terminal was the workspace's LAST. Only for a clean exit —
            // an Error terminal is intentionally left in place (see above), so
            // there is nothing to replace. The id of any opened replacement is
            // intentionally discarded here — it surfaces through
            // _terminalStates → _items → the screen's sibling-fallback effect,
            // so the panel switches to it without the manager having to know
            // about UI navigation.
            ensureTerminalPresence(workspaceId)
        }
    }

    private fun cancelObserverLocked(workspaceId: String, terminalId: String) {
        observers[workspaceId]?.remove(terminalId)?.cancel()
    }

    private fun setStateLocked(workspaceId: String, terminalId: String, state: TerminalState) {
        _terminalStates.value = _terminalStates.value + (
            workspaceId to (_terminalStates.value[workspaceId].orEmpty() + (terminalId to state))
        )
    }

    /**
     * Keeps the [TerminalSession] record's state in sync with [_terminalStates]
     * — without this the record can outlive the pty it references with a stale
     * `Running` and [getPtySession] would keep resolving a dead pty (§9.2: a
     * stopped/errored terminal must not resolve a session).
     */
    private fun setSessionStateLocked(workspaceId: String, terminalId: String, state: TerminalState) {
        val session = _terminalSessions.value[workspaceId]?.get(terminalId) ?: return
        val updated = session.copy(state = state)
        _terminalSessions.value = _terminalSessions.value + (
            workspaceId to (_terminalSessions.value[workspaceId].orEmpty() + (terminalId to updated))
        )
    }

    private fun removeStateLocked(workspaceId: String, terminalId: String) {
        val ws = _terminalStates.value[workspaceId] ?: return
        val next = ws - terminalId
        _terminalStates.value = if (next.isEmpty()) {
            _terminalStates.value - workspaceId
        } else {
            _terminalStates.value + (workspaceId to next)
        }
    }

    private fun removeSessionLocked(workspaceId: String, terminalId: String) {
        val ws = _terminalSessions.value[workspaceId] ?: return
        val next = ws - terminalId
        _terminalSessions.value = if (next.isEmpty()) {
            _terminalSessions.value - workspaceId
        } else {
            _terminalSessions.value + (workspaceId to next)
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"

        /** §9.1: terminal ids must stay charset-safe for paths/logs/tmux names. */
        val VALID_TERMINAL_ID = Regex("^[A-Za-z0-9_-]+$")

        fun isValidTerminalId(id: String): Boolean = VALID_TERMINAL_ID.matches(id)
    }
}
