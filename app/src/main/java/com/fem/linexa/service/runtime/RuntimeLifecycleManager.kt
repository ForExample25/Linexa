package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.perf.PerfCounters
import com.fem.linexa.core.perf.PerfTrace
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.service.runtime.container.RecoverySweepReport
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.97/W4 — service-side single-active-workspace enforcement.
 *
 * Today the "at most ONE Workspace running at any time" invariant is only a
 * navigation property (pop A before push B, `NavGraph.kt`) plus
 * `RuntimeViewModel.onCleared` → `ACTION_STOP` — nothing in the service
 * refuses a second concurrent workspace (audit §8). This manager makes it a
 * service contract: `ACTION_START` for workspace B while A is active first
 * runs A's full stop sequence (all terminals + runtime) and only then opens
 * B's first terminal. START/STOP/RESTART are serialized on a single mutex, so
 * a `START(B)` racing an in-flight `STOP(A)` can never let B open while A is
 * still being torn down (audit §8.3: "the manager's mutex + a Stopping
 * transition").
 *
 * The manager is deliberately pure (no Android dependencies) so the
 * serialization / single-active behavior is JVM-testable against the real
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] through
 * the fake-PRoot harness. It does NOT own the workspace session coroutine
 * (readiness gate, `awaitExit`, error surfacing stay in [RuntimeService]) — it
 * owns the *transitions*: [requestStart] / [requestStop],
 * the [activeWorkspaceId] gate, and the ONE shared teardown path
 * ([stopWorkspaceLocked]) every stop trigger (ACTION_STOP, notification Stop,
 * workspace leave, restart, gate eviction) funnels through, so the "Stop"
 * notification action can never diverge from a programmatic stop.
 *
 * State discipline (§1.16 / audit §9.2): the workspace [RuntimeState] stays
 * in [SessionManager] and the per-terminal [com.fem.linexa.domain.runtime.TerminalState]
 * in [WorkspaceTerminalManager]. This class adds only the single-active
 * *reference* — which workspace owns the runtime right now — not a second
 * state machine.
 *
 * §1.165 (re-entrancy): [transitionMutex] serializes transitions but does not
 * make a *duplicate* one idempotent. Two starts for the SAME workspace used to
 * run the full start body twice in sequence — a second terminal for a workspace
 * that already had one (the reported phantom terminal), and a second launch
 * coroutine that could then run the destructive unwind against the first one's
 * live session (the reported SIGKILL). [requestStart] now short-circuits to
 * [StartOutcome.AlreadyStarted] when a live session already owns the workspace,
 * and every outcome carries the generation it belongs to so the caller never
 * re-reads it across a suspension boundary. Per-workspace keying comes for free:
 * the guard is a per-workspace predicate, so unrelated workspaces are unaffected
 * beyond the brief serialization the mutex already imposed.
 */
@Singleton
class RuntimeLifecycleManager @Inject constructor(
    private val sessionManager: SessionManager,
    private val workspaceTerminalManager: WorkspaceTerminalManager,
    private val fileManager: WorkspaceFileManager,
    private val runtimeTrace: RuntimeTrace,
    /**
     * §6: the component-health registry. Cleared on the ONE teardown path so
     * a stopped workspace never reports degradations from its last run.
     */
    private val healthRegistry: WorkspaceHealthRegistry,
    /**
     * §1.142: the Workspace-runtime wake lock. Injected here, and released from
     * [stopWorkspaceLocked], because that method is already documented as the ONE
     * teardown path every stop trigger funnels through (ACTION_STOP, notification
     * Stop, workspace leave, gate eviction, delete). Releasing anywhere else would
     * mean one of those triggers could leave a hold behind, which is the single
     * failure mode this feature must not have.
     */
    private val wakeLockManager: WorkspaceWakeLockManager,
) {

    /**
     * Test-facing constructor: injects a custom teardown bound. The Hilt
     * graph can't provide `Long`, so the timeout is a plain class property
     * defaulted to [DEFAULT_TEARDOWN_TIMEOUT_MS]; production keeps that
     * default, tests shorten it to exercise the deterministic block path.
     */
    constructor(
        sessionManager: SessionManager,
        workspaceTerminalManager: WorkspaceTerminalManager,
        fileManager: WorkspaceFileManager,
        runtimeTrace: RuntimeTrace,
        healthRegistry: WorkspaceHealthRegistry,
        wakeLockManager: WorkspaceWakeLockManager,
        teardownTimeoutMs: Long,
    ) : this(sessionManager, workspaceTerminalManager, fileManager, runtimeTrace, healthRegistry, wakeLockManager) {
        this.teardownTimeoutMs = teardownTimeoutMs
    }

    private var teardownTimeoutMs: Long = DEFAULT_TEARDOWN_TIMEOUT_MS

    private val transitionMutex = Mutex()

    /**
     * Phase M0-A: the single wrapper around every [transitionMutex] acquisition.
     * The mutex itself is unchanged — same instance, same acquisition order,
     * same cancellation behavior — this only records wait time before the lock
     * and hold time inside it when capture is enabled.
     */
    private suspend fun <T> withTransitionLock(block: suspend () -> T): T {
        val waitStart = PerfCounters.mutexWaitBegin()
        var acquired = false
        try {
            return transitionMutex.withLock {
                acquired = true
                PerfCounters.mutexWaitEnd(waitStart)
                val holdStart = PerfCounters.mutexHoldBegin()
                try {
                    block()
                } finally {
                    PerfCounters.mutexHoldEnd(holdStart)
                }
            }
        } finally {
            if (!acquired) PerfCounters.mutexWaitAbandoned(waitStart)
        }
    }

    /**
     * §P0: per-workspace session generation, bumped at the START of every
     * [requestStart] (inside the mutex, before any suspension). Guards the
     * stale-launchSession-coroutine race: a coroutine that was waiting on
     * [WorkspaceTerminalManager.awaitSessionEnd] when a stop/leave teardown
     * completed it may resume AFTER a replacement session has already opened
     * for the same workspace. Comparing the generation the coroutine captured
     * when its session started against [currentGeneration] tells it it is
     * stale, so it does not close the replacement's terminals, remove its
     * session, clobber its [RuntimeState], or tear the service down.
     */
    private val sessionGenerations = mutableMapOf<String, Long>()

    /**
     * §1.97/W5: whether the once-per-process startup recovery sweep has been
     * attempted. Guarded by [transitionMutex]; never reset — the singleton
     * outlives service recreation, so only a genuinely fresh process re-sweeps.
     */
    private var startupSweepDone = false

    private val _activeWorkspaceId = MutableStateFlow<String?>(null)

    /** The workspace whose runtime is currently active, or null. */
    val activeWorkspaceId: StateFlow<String?> = _activeWorkspaceId.asStateFlow()

    /**
     * Start [workspaceId]'s runtime. Serialized. If another workspace is
     * currently active it is stopped *fully* first (all terminals + runtime,
     * [stopWorkspaceLocked]); only when that teardown verifiably completes
     * does [workspaceId] become active and its first terminal open — the
     * "enter B while A runs ⇒ A fully stopped first" service backstop.
     *
     * On a failed/timed-out eviction teardown the call returns
     * [StartOutcome.Blocked] WITHOUT opening B and WITHOUT changing the gate:
     * the still-active workspace stays recorded so a later start/stop retries
     * its teardown (a deterministic block — never a hidden overlap).
     *
     * Only the first terminal (T0) is opened here; the caller ([RuntimeService])
     * promotes the session to [RuntimeState.Running] after its own child-alive
     * check (the §1.76 "never report Running on pid>0 alone" discipline stays
     * in the service).
     *
     * §1.165 (re-entrancy): a second start for a workspace that already has a
     * live session returns [StartOutcome.AlreadyStarted] carrying the incumbent's
     * pty and generation — it does NOT allocate a terminal id, open a terminal,
     * re-create the session, reset the state to [RuntimeState.Starting] or bump
     * the generation. [transitionMutex] already made two starts *sequential*;
     * what it could not do is make the second one a *no-op*, and a start that
     * runs its whole body a second time is what produced the reported phantom
     * second terminal (the allocator correctly hands out a fresh id, so the
     * redundant start opens T1 next to a perfectly healthy T0). See
     * [liveStartLocked] for the predicate and the §1.165 entry for the race.
     */
    suspend fun requestStart(workspaceId: String, rootfsPath: String): StartOutcome {
        // Phase M0-A (E0): the request is accepted here. A duplicate start for
        // a workspace that already has a live trace is ignored by the engine,
        // so the incumbent's timestamps are never reset.
        PerfTrace.beginWorkspaceStart(workspaceId)
        return withTransitionLock {
            // §1.165: the re-entrancy guard runs BEFORE the generation bump, so a
            // redundant start leaves the incumbent's generation — and therefore
            // the incumbent launch coroutine's ownership of the teardown path —
            // completely untouched. Bumping first would invalidate a live,
            // correct session's own unwind on behalf of a call that then goes on
            // to do nothing.
            val incumbent = liveStartLocked(workspaceId)
            if (incumbent != null) {
                val generation = currentGeneration(workspaceId)
                Logger.w(
                    TAG,
                    "requestStart($workspaceId) IGNORED (re-entrant): a live session already owns this " +
                        "workspace (generation $generation, pty ${incumbent.id} pid=${incumbent.pid}); " +
                        "not opening a second terminal"
                )
                runtimeTrace.record(
                    workspaceId,
                    "requestStart re-entrancy guard: returning the live session (generation $generation)"
                )
                return@withTransitionLock StartOutcome.AlreadyStarted(incumbent, generation)
            }
            // §P0: bump the session generation FIRST — before any suspension
            // that could let a previous launchSession coroutine for THIS
            // workspace resume (its awaitSessionEnd was completed by an earlier
            // stop/leave teardown). The stale coroutine's unwind then sees
            // generation != currentGeneration and leaves the replacement alone.
            // Incrementing at the top also means a start that later fails still
            // invalidates any older coroutine that was waiting — correct, since
            // that coroutine's own teardown already ran and the workspace is
            // being re-owned.
            //
            // §1.165: the value is captured and handed back in the outcome. The
            // caller used to read it back with a separate currentGeneration()
            // call *after* requestStart returned — a suspension boundary, so two
            // launch coroutines for the same workspace could both read the same
            // (newer) generation and both believe they owned the teardown path.
            val generation = (sessionGenerations[workspaceId] ?: 0L) + 1L
            sessionGenerations[workspaceId] = generation
            runStartupSweepLocked()
            val active = _activeWorkspaceId.value
            if (active != null && active != workspaceId) {
                runtimeTrace.record(
                    workspaceId,
                    "RuntimeLifecycleManager gate: stopping active $active before starting $workspaceId"
                )
                val stopped = stopWorkspaceLocked(active)
                if (!stopped) {
                    val reason = "Could not stop active workspace $active (teardown failed/timed out); $workspaceId not started"
                    Logger.w(TAG, "requestStart($workspaceId) BLOCKED: $reason")
                    sessionManager.updateState(workspaceId, RuntimeState.Error(reason))
                    PerfTrace.abandonWorkspace(workspaceId, "blocked-by-active-teardown")
                    return@withTransitionLock StartOutcome.Blocked(active, generation)
                }
            }
            _activeWorkspaceId.value = workspaceId
            sessionManager.createSession(workspaceId, rootfsPath)
            sessionManager.updateState(workspaceId, RuntimeState.Starting)
            try {
                val terminalId = workspaceTerminalManager.nextTerminalId(workspaceId)
                val pty = workspaceTerminalManager.openTerminal(workspaceId, rootfsPath, terminalId)
                StartOutcome.Started(pty, generation)
            } catch (e: CancellationException) {
                PerfTrace.abandonWorkspace(workspaceId, "start-cancelled")
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "Failed to open session"
                Logger.w(TAG, "requestStart($workspaceId) FAILED: $reason")
                sessionManager.updateState(workspaceId, RuntimeState.Error(reason))
                _activeWorkspaceId.value = null
                PerfTrace.abandonWorkspace(workspaceId, "start-failed")
                // §1.165: this workspace-wide close is safe *because* of the
                // guard above — it is only reachable when the workspace had no
                // live session to begin with, so it can only be tearing down
                // this attempt's own residue. Before the guard, a redundant
                // start whose extra terminal failed to open reached exactly
                // here and killed the healthy session's process group.
                runCatching { workspaceTerminalManager.closeWorkspace(workspaceId) }
                StartOutcome.Failed(reason, generation)
            }
        }
    }

    /**
     * §1.165 — the re-entrancy predicate: the live pty of [workspaceId]'s
     * current session, or null when there is no session for a second start to
     * be redundant *with*. Caller must hold [transitionMutex].
     *
     * All four conditions are load-bearing:
     *
     * - **the gate** — a workspace that does not own the runtime is not started,
     *   whatever residue it has;
     * - **[RuntimeState.Starting] or [RuntimeState.Running]** — `Stopping`,
     *   `Stopped` and `Error` are exactly the states a start must be allowed to
     *   recover from;
     * - **a session record** — the workspace-level bookkeeping must agree that a
     *   session exists;
     * - **a live pty** — a registered terminal whose child is dead is not a
     *   session worth protecting, and refusing to restart it would turn one
     *   dead guest into a permanently unstartable workspace.
     *
     * The terminal it resolves is the workspace's last-active one rather than
     * `T0`, so a workspace whose T0 was closed and replaced (§1.106) is still
     * recognised as started.
     */
    private fun liveStartLocked(workspaceId: String): PtySession? {
        if (_activeWorkspaceId.value != workspaceId) return null
        val state = sessionManager.getState(workspaceId)
        if (state !is RuntimeState.Starting && state !is RuntimeState.Running) return null
        if (sessionManager.getSession(workspaceId) == null) return null
        val terminalId = workspaceTerminalManager.lastActiveTerminalId(workspaceId)
        return workspaceTerminalManager.getPtySession(workspaceId, terminalId)?.takeIf { it.isAlive }
    }

    /**
     * Stop [workspaceId]'s runtime. Serialized, idempotent. Stops the workspace
     * whether it is the active one (clearing the gate) or merely holds residual
     * terminals/session (post-eviction cleanup); a fully-stopped workspace is a
     * no-op returning true. Notification Stop, ACTION_STOP and
     * RuntimeViewModel.onCleared all reach this same teardown path.
     *
     * §1.142: the already-stopped no-op is decided *inside*
     * [stopWorkspaceLocked] (which has always carried the identical check)
     * rather than short-circuited here. It used to be tested twice, and the copy
     * in this method returned before `stopWorkspaceLocked`'s `finally` could run
     * — so a stop of an already-stopped workspace was the one stop path that
     * could leave a wake lock held. Keeping a single gate keeps a single release.
     */
    suspend fun requestStop(workspaceId: String): Boolean {
        return withTransitionLock {
            val active = _activeWorkspaceId.value
            if (active == null || active != workspaceId) {
                val stopped = stopWorkspaceLocked(workspaceId)
                if (stopped) PerfTrace.abandonWorkspace(workspaceId, "stopped")
                return@withTransitionLock stopped
            }
            val stopped = stopWorkspaceLocked(workspaceId)
            if (stopped) {
                _activeWorkspaceId.value = null
                PerfTrace.abandonWorkspace(workspaceId, "stopped")
            }
            stopped
        }
    }

    /**
     * §P0: how many times a session has been opened for [workspaceId]
     * (0 = none). Bumped at the start of every [requestStart]. The
     * launchSession coroutine captures this right after a successful start and
     * uses [isCurrentSession] to detect that a newer session replaced it while
     * it was waiting on [WorkspaceTerminalManager.awaitSessionEnd].
     *
     * §1.165: a launch path must NOT use this to learn its own generation —
     * reading it after [requestStart] returns is a read after the transition
     * mutex was released, which is the race this note exists to prevent. Take
     * the value from [StartOutcome.generation] instead. This accessor remains
     * for *comparison* and diagnostics (what the current generation is now).
     */
    fun currentGeneration(workspaceId: String): Long = sessionGenerations[workspaceId] ?: 0L

    /**
     * §P0: whether the launchSession coroutine that captured [generation]
     * still owns [workspaceId]'s runtime. False when a newer session was
     * opened for the same workspace (stop-then-immediate-start, double-start)
     * — the stale coroutine must NOT close the replacement's terminals, remove
     * its session, or tear the service down.
     */
    fun isCurrentSession(workspaceId: String, generation: Long): Boolean =
        generation == currentGeneration(workspaceId)

    /**
     * Called by the service when a session coroutine unwinds (its `finally`).
     * Clears the gate ONLY if this workspace is genuinely over ([sessionTrulyOver]).
     * This protects a replacement session opened for the same workspace: the
     * OLD session's `finally` runs AFTER the new session already opened, so it
     * must not clear the gate out from under the replacement (which is still
     * Starting).
     */
    suspend fun sessionEnded(workspaceId: String) {
        withTransitionLock {
            if (_activeWorkspaceId.value == workspaceId && sessionTrulyOver(workspaceId)) {
                _activeWorkspaceId.value = null
            }
        }
    }

    /** Whether [workspaceId]'s runtime is genuinely over (stopped/errored/no session). */
    fun sessionTrulyOver(workspaceId: String): Boolean {
        return when (val state = sessionManager.getState(workspaceId)) {
            is RuntimeState.Stopped, is RuntimeState.Error -> true
            else -> sessionManager.getSession(workspaceId) == null
        }
    }

    /**
     * Tear down every active workspace and clear the gate — the bounded
     * service-teardown entry point used by [RuntimeService.onDestroy]
     * (P0-2 / ADR-0006).
     *
     * §1.142: ends with an unconditional [WorkspaceWakeLockManager.releaseAll].
     * `stopWorkspaceLocked` already releases per workspace, but a hold whose
     * workspace has no session entry left (a state this loop iterates *past*)
     * would survive — and the process is going away, so there would be nothing
     * left to release it.
     */
    suspend fun shutdown() {
        withTransitionLock {
            sessionManager.getActiveSessions().forEach { session ->
                runCatching { stopWorkspaceLocked(session.workspaceId) }
                PerfTrace.abandonWorkspace(session.workspaceId, "service-shutdown")
            }
            _activeWorkspaceId.value = null
            wakeLockManager.releaseAll()
        }
    }

    /**
     * Phase 07 (OPT-REL-005): the durable record that a service teardown was
     * PENDING when the process went away.
     *
     * [RuntimeService.onDestroy] calls this SYNCHRONOUSLY, before it launches
     * the (now off-Main) teardown: Android may kill the process the moment
     * `onDestroy` returns, and an in-memory-only "what was running" list dies
     * with it. One marker file per still-active workspace —
     * `{workspaceBase}/.shutdown-pending` ([SHUTDOWN_MARKER], content: id,
     * state, timestamp) — survives that kill, and the next process's startup
     * sweep ([runStartupSweepLocked] → [consumeShutdownMarkers]) tears the
     * workspace down properly and removes it.
     *
     * A marker is therefore a *reservation*, not a claim that processes are
     * alive: the normal case is that the launched teardown completes moments
     * later and [stopWorkspaceLocked] clears the marker it is reserving. Only
     * a death (or teardown failure) between write and clear leaves it behind
     * for the sweep.
     *
     * Synchronous by contract (called from a lifecycle callback that must not
     * return before the record exists) and best-effort per workspace: a
     * single unwritable marker is logged, never thrown, so one bad directory
     * cannot prevent the other reservations — or the service teardown — from
     * proceeding.
     */
    fun writeShutdownMarkers() {
        sessionManager.getActiveSessions().forEach { session ->
            runCatching {
                val base = fileManager.getWorkspaceBasePath(session.workspaceId)
                base.mkdirs()
                File(base, SHUTDOWN_MARKER).writeText(
                    "${session.workspaceId} ${session.state::class.simpleName ?: "Unknown"} " +
                        java.time.Instant.now() + "\n"
                )
            }.onFailure {
                Logger.w(TAG, "shutdown marker for ${session.workspaceId} could not be written: ${it.message}")
            }
        }
    }

    /**
     * Clear [workspaceId]'s shutdown marker — called from
     * [stopWorkspaceLocked]'s success path, i.e. exactly when the teardown the
     * marker reserved has actually run. Absent marker → no-op (the normal
     * stop of a workspace that never needed a reservation).
     */
    private fun clearShutdownMarker(workspaceId: String) {
        runCatching { File(fileManager.getWorkspaceBasePath(workspaceId), SHUTDOWN_MARKER).delete() }
            .onFailure { Logger.w(TAG, "shutdown marker for $workspaceId could not be cleared: ${it.message}") }
    }

    /**
     * §1.97/W5 — the startup recovery sweep entry point (audit §13),
     * serialized on the SAME mutex as every lifecycle transition. Runs once
     * per process (this is a singleton, so the flag survives service
     * recreation within a process — exactly the guard that keeps a later
     * service instance from re-sweeping a live workspace). The sweep is
     * ensured at the head of every start path ([runStartupSweepLocked]) — the
     * service no longer runs it eagerly in `onCreate` (PERF-003: that ran on
     * Main during Activity entry, for sessions that did not exist) — so a
     * START/STOP/RESTART can never race it and a workspace can never open
     * before the sweep finishes. Best-effort and bounded: a partial failure is
     * returned in the report, never thrown, and never blocks a start.
     */
    suspend fun startupSweep(): RecoverySweepReport = withTransitionLock {
        runStartupSweepLocked()
    }

    /**
     * Run the once-per-process recovery sweep, assuming the caller already
     * holds [transitionMutex] (callers: [startupSweep], [requestStart]). Bounded by [teardownTimeoutMs] so a wedged delete can
     * never hang a start transition; the flag is set before the attempt so a
     * failed sweep is never retried on every later start (it is best-effort,
     * and the next fresh process sweeps again).
     */
    private suspend fun runStartupSweepLocked(): RecoverySweepReport {
        if (startupSweepDone) return RecoverySweepReport(0, 0, 0)
        startupSweepDone = true
        val report = withTimeoutOrNull(teardownTimeoutMs) {
            // PERF-003: this is filesystem work (stale PROOT_TMP_DIR scopes,
            // tmux sockets). `requestStart` can run on a Main-dispatched
            // caller, so the sweep must not do its IO there.
            withContext(Dispatchers.IO) {
                runCatching { workspaceTerminalManager.initialize() }.getOrElse {
                    Logger.w(TAG, "startup recovery sweep threw: ${it.message}")
                    RecoverySweepReport(0, 0, 1)
                }
            }
        } ?: RecoverySweepReport(0, 0, 1)
        Logger.i(TAG, "startup recovery sweep: $report")
        consumeShutdownMarkers()
        return report
    }

    /**
     * Phase 07 (OPT-REL-005): consume the shutdown markers a previous process
     * left behind ([writeShutdownMarkers] wrote them in `onDestroy`; the
     * process died — or its teardown failed — before [stopWorkspaceLocked]
     * could clear them).
     *
     * Runs inside [runStartupSweepLocked], i.e. under the SAME transition
     * mutex as every lifecycle transition and BEFORE any start can proceed
     * (the sweep is ensured at the head of every start path). For each marked
     * workspace the teardown the previous process never completed is run now
     * — [WorkspaceTerminalManager.closeWorkspace], the same call the stop
     * path makes, which is idempotent and safe for workspaces whose session
     * record died with the old process — and only a SUCCESSFUL consumption
     * deletes the marker: a failed/retained one is retried by the next fresh
     * process rather than silently dropped (fail-safe in the containment
     * direction).
     *
     * Bounded by [teardownTimeoutMs] like the rest of the sweep: a wedge
     * cannot hang a start; whatever was not consumed stays marked for next
     * launch. Best-effort: never throws, never blocks a start.
     */
    private suspend fun consumeShutdownMarkers() = withContext(Dispatchers.IO) {
        val base = fileManager.getWorkspaceBasePath("")
        val marked = runCatching {
            base.listFiles()?.filter { it.isDirectory && File(it, SHUTDOWN_MARKER).exists() }
        }.getOrNull().orEmpty()
        if (marked.isEmpty()) return@withContext
        Logger.i(TAG, "shutdown markers left by a previous process: ${marked.joinToString { it.name }}")
        val consumed = withTimeoutOrNull(teardownTimeoutMs) {
            marked.forEach { dir ->
                val workspaceId = dir.name
                try {
                    workspaceTerminalManager.closeWorkspace(workspaceId)
                    File(dir, SHUTDOWN_MARKER).delete()
                    Logger.i(TAG, "shutdown marker consumed for $workspaceId — pending teardown completed")
                } catch (e: CancellationException) {
                    // A sweep timeout (or a cancelled caller) must surface as
                    // cancellation — never swallowed into a per-workspace
                    // "retained" log line (same discipline as
                    // [stopWorkspaceLocked]'s bounded teardown).
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "shutdown marker RETAINED for $workspaceId: closeWorkspace failed (${e.message})")
                }
            }
            true
        }
        if (consumed != true) {
            Logger.w(TAG, "shutdown-marker consumption timed out — retained markers will be consumed on the next launch")
        }
    }

    /**
     * The ONE teardown path every stop trigger funnels through. Bounded by
     * [teardownTimeoutMs]: a wedged close must deterministically fail (return
     * false) rather than hang the transition — and a failed teardown propagates
     * up to block any start that depends on it.
     *
     * §1.142: the workspace's wake lock is released here, in a `finally`, so
     * **every** exit path lets go — including the already-stopped no-op and the
     * timed-out failure. A wedged teardown that kept the CPU awake would be the
     * worst case for this feature: the workspace is unusable *and* the phone
     * never sleeps. The release is after the teardown attempt rather than before
     * it so the lock still covers the (bounded) kill sequence itself.
     */
    private suspend fun stopWorkspaceLocked(workspaceId: String): Boolean {
        try {
            val state = sessionManager.getState(workspaceId)
            if ((state is RuntimeState.Stopped || state is RuntimeState.Error) &&
                _activeWorkspaceId.value != workspaceId
            ) {
                return true
            }
            Logger.i(TAG, "stopWorkspaceLocked($workspaceId) ENTER")
            runtimeTrace.record(workspaceId, "RuntimeLifecycleManager.stopWorkspaceLocked ENTER")
            sessionManager.updateState(workspaceId, RuntimeState.Stopping)
            val stopped: Boolean = withTimeoutOrNull(teardownTimeoutMs) {
                try {
                    workspaceTerminalManager.closeWorkspace(workspaceId)
                    // DEEP-AUDIT (2026-11-26): stopping the runtime does not
                    // change the workspace's size and is not a read request.
                    // Mark the in-memory generation stale only; the persisted
                    // value stays readable and the next screen revalidates it
                    // in the background. A full invalidate here forced the
                    // next entry's detail build into a blocking multi-GB tree
                    // walk (measured 3 927 ms: the re-entry slowdown).
                    fileManager.markRootfsSizeStale(workspaceId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "teardown of $workspaceId threw: ${e.message}")
                    false
                }
            } ?: false
            if (!stopped) {
                val reason = "Teardown of $workspaceId failed or timed out"
                Logger.w(TAG, "stopWorkspaceLocked($workspaceId) FAILED")
                sessionManager.updateState(workspaceId, RuntimeState.Error(reason))
                runtimeTrace.record(workspaceId, "stopWorkspaceLocked FAILED: $reason")
                return false
            }
            sessionManager.updateState(workspaceId, RuntimeState.Stopped)
            sessionManager.removeSession(workspaceId)
            // §6: a stopped workspace has no components left to degrade —
            // forget its health so the next session starts clean.
            healthRegistry.clear(workspaceId)
            // Phase 07 (OPT-REL-005): the teardown the marker reserved has now
            // actually run — release the reservation. A marker written by a
            // previous crashed process is consumed here too: an explicit stop
            // of a crash-recovered workspace completes the same teardown.
            clearShutdownMarker(workspaceId)
            runtimeTrace.record(workspaceId, "stopWorkspaceLocked complete")
            return true
        } finally {
            // §1.142: scoped to this workspace (a no-op when another workspace
            // holds the lock) and idempotent, so repeated stops are harmless.
            wakeLockManager.releaseFor(workspaceId)
        }
    }

    companion object {
        private const val TAG = "RuntimeLifecycleManager"

        /** Matches [RuntimeService.TEARDOWN_TIMEOUT_MS] (asserted by test). */
        const val DEFAULT_TEARDOWN_TIMEOUT_MS = 3_000L

        /**
         * Phase 07 (OPT-REL-005): the per-workspace file
         * ([WorkspaceFileManager.getWorkspaceBasePath] basis) that marks
         * "service teardown was pending when the previous process ended".
         * Written synchronously by [writeShutdownMarkers], cleared by
         * [stopWorkspaceLocked]'s success path, consumed by the startup sweep.
         */
        const val SHUTDOWN_MARKER = ".shutdown-pending"
    }
}

/**
 * Outcome of a [RuntimeLifecycleManager.requestStart].
 *
 * §1.165: every variant carries the [generation] the call is associated with, so
 * the caller never has to re-read it with a second, separately-locked
 * [RuntimeLifecycleManager.currentGeneration] call across a suspension boundary.
 * For [Started]/[Failed]/[Blocked] it is the generation this call minted; for
 * [AlreadyStarted] it is the *incumbent's* generation, which this call did not
 * mint and does not own.
 */
sealed interface StartOutcome {

    /** The session generation this outcome belongs to (§1.165). */
    val generation: Long

    /** The workspace's first terminal (T0) is open; the caller promotes it to Running. */
    data class Started(val pty: PtySession, override val generation: Long) : StartOutcome

    /**
     * §1.165 — a live session already owns this workspace and this start was a
     * re-entrant duplicate (double-tap, a lifecycle callback firing twice, two
     * racing callers). Nothing was opened, nothing was torn down, no state was
     * touched, and [generation] belongs to the *incumbent* launch — so a caller
     * receiving this must NOT run the stop unwind: it does not own the session
     * whose end signal it would otherwise wait for and act on.
     */
    data class AlreadyStarted(val pty: PtySession, override val generation: Long) : StartOutcome

    /** The start failed (the session open threw). */
    data class Failed(val reason: String, override val generation: Long) : StartOutcome

    /** Blocked because an eviction teardown failed/timed out — nothing started. */
    data class Blocked(val reason: String, override val generation: Long) : StartOutcome
}
