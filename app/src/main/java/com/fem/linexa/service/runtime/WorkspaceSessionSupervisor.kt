package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceReadiness
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.service.runtime.desktop.X11SessionManager
import com.fem.linexa.service.runtime.desktop.X11SessionState
import com.fem.linexa.service.runtime.features.WorkspaceFeatureReconciler
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The workspace-session launch choreography that used to live inline in
 * `RuntimeService.launchSession`.
 *
 * §P0: extracted so the stale-coroutine race fix is JVM-testable — the race
 * regression test drives THIS class (the real choreography) against the real
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] through the
 * fake-PRoot harness, instead of RuntimeService (an Android Service that a JVM
 * unit test cannot instantiate).
 *
 * The lifecycle *transition* (readiness gate → single-active eviction →
 * first-terminal open) is delegated to [RuntimeLifecycleManager]; the long-lived
 * workspace-session end wait ([WorkspaceTerminalManager.awaitSessionEnd], which
 * completes only on an explicit workspace close) and the §1.76 launch-error
 * surfacing stay here. §P0: the wait is followed by a generation-guarded unwind
 * — if a newer session opened for the same workspace while this coroutine was
 * waiting (stop-then-immediate-start, double-start), this coroutine is stale
 * and must not close the replacement's terminals, remove its session, clobber
 * its [RuntimeState], or tear the service down.
 *
 * Android-owned side effects (notification, display-name cache, foreground
 * teardown) are supplied through [Host] so this class stays pure Kotlin.
 *
 * §1.110: the workspace's persisted *feature toggles* are applied here too —
 * [WorkspaceFeatureReconciler.onSessionStarted] runs once the session is
 * Running (a guest must exist before a daemon can start in it) and
 * [WorkspaceFeatureReconciler.onSessionStopping] runs in the guarded unwind.
 * This is the launch-path counterpart of §1.82's Shared Storage gate: without
 * it, a daemon-backed toggle was only honored while the Features tab was open.
 * Both calls are fail-open — a feature can never keep a workspace from running
 * or block its teardown.
 *
 * Phase 2E-1: the application is two-phase. Network provisioning stays
 * awaited inline (the desktop start path consumes `/etc/resolv.conf`); the
 * four daemon-backed features (ssh → notifications → clipboard → audio) run
 * on a CHILD coroutine of the session coroutine — it dies with the session,
 * and its execs no longer compete with a desktop start for the cgroup's CPU
 * (Phase 2D measured the T0→T4 window degrade with every provisioning exec
 * inside it). The child waits for the desktop start to settle — observed
 * through the service-side [X11SessionManager] state machine, which
 * `DesktopViewModel.startX11Desktop` flips STARTING → READY/FAILED for both
 * backends — or for a bounded fallback, whichever comes first; a start that
 * never begins therefore still converges. The final [SessionManager.refreshHealth]
 * after the deferred phase leaves the registry aggregate identical to the
 * pre-split single-pass reconcile.
 *
 * §1.165: the generation this coroutine owns is taken from
 * [StartOutcome.generation] rather than re-read from the manager after
 * [RuntimeLifecycleManager.requestStart] returns, and a
 * [StartOutcome.AlreadyStarted] (a re-entrant duplicate start) returns
 * immediately without entering the try/finally — so a duplicate launch can never
 * reach the teardown path of the session it duplicated.
 */
class WorkspaceSessionSupervisor(
    private val workspaceRepository: WorkspaceRepository,
    private val launchValidator: WorkspaceLaunchValidator,
    private val sessionManager: SessionManager,
    private val workspaceTerminalManager: WorkspaceTerminalManager,
    private val lifecycleManager: RuntimeLifecycleManager,
    private val runtimeTrace: RuntimeTrace,
    private val diagnosticLogManager: DiagnosticLogManager,
    private val events: MutableSharedFlow<RuntimeEvent>,
    private val featureReconciler: WorkspaceFeatureReconciler,
    /**
     * Phase 2E-1: the service-side X11 state machine the deferred reconcile
     * phase observes. A desktop start flips the workspace's entry
     * STARTING → READY/FAILED (`DesktopViewModel.startX11Desktop`, both
     * backends); a workspace whose desktop never starts keeps IDLE. Nullable
     * so the JVM tests (which pass no X11 manager) exercise the fallback.
     */
    private val x11SessionManager: X11SessionManager?,
    private val host: Host,
    /**
     * Phase 2E-1: the bounded fallback for the deferred phase — how long it
     * may keep waiting for a desktop start to settle before running anyway.
     * Defaults to the production bound; JVM tests shrink it to keep the
     * never-settles branch deterministic without real-time waits.
     */
    private val deferredReconcileFallbackMillis: Long = DEFERRED_FALLBACK_TIMEOUT_MS,
) {

    /**
     * Android-owned side effects of the session choreography, implemented by
     * `RuntimeService`. Kept behind callbacks so the supervisor (and its §P0
     * race regression tests) stays JVM-testable.
     */
    interface Host {
        /** Remember the workspace display name for the foreground notification. */
        fun onDisplayName(workspaceId: String, displayName: String)

        /** The workspace display name is no longer needed (session truly over). */
        fun onDisplayNameForgotten(workspaceId: String)

        /** The session is Running — refresh the foreground notification. */
        fun onSessionRunning(workspaceId: String)

        /** No workspace is active anymore — tear the foreground service down. */
        fun onForegroundIdle()
    }

    /**
     * Run the launch choreography for [workspaceId] on [scope]. Returns the
     * [Job] (which completes when the workspace session ends and this
     * coroutine unwinds) so tests can `join()` it after driving a stop/start.
     */
    fun launch(scope: CoroutineScope, workspaceId: String, rootfsPath: String): Job {
        return scope.launch { run(this, workspaceId, rootfsPath) }
    }

    /**
     * Phase 2E-1 — when the deferred feature-reconcile phase may run.
     *
     * Desktop-start-observing (c): if the workspace's X11 lifecycle is in
     * flight (STARTING), wait until it settles (READY or FAILED — both mean
     * the desktop path's exec storm is over); if none is in flight (IDLE,
     * including a disabled desktop feature), the phase may run immediately.
     * A start requested AFTER that first observation races only until the
     * [DEFERRED_FALLBACK_TIMEOUT_MS] budget expires, which is also the
     * never-starts converging branch (b) — so the phase always runs, at most
     * one fallback window late. Cancellation (session stop) propagates
     * through the checks and [delay]; nothing here throws anything else.
     */
    private suspend fun awaitDeferredPhaseStart(workspaceId: String) {
        val x11 = x11SessionManager ?: return
        val deadline = System.currentTimeMillis() + deferredReconcileFallbackMillis
        // Phase 2E-1 review fix: IDLE must hold too, not only STARTING. At session
        // start the desktop has not been requested yet (state IDLE), and the common
        // flow requests it seconds later — an immediate-IDLE run would put the
        // deferred execs right back into the T0→T4 contention window (the exact
        // effect this phase removes). Hold while the desktop has NOT settled:
        // IDLE (may still start) or STARTING (in flight), until READY/FAILED
        // (start settled — its exec storm is over) or the bounded fallback
        // deadline (desktop never starts / feature disabled).
        while ((x11.stateFor(workspaceId) == X11SessionState.X11_STARTING ||
                x11.stateFor(workspaceId) == X11SessionState.IDLE) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(DEFERRED_POLL_INTERVAL_MS)
        }
    }

    /**
     * Launch the deferred reconcile phase on a child of the session
     * coroutine's Job: it dies with the session (no leaks), never crashes the
     * session scope (the reconciler's own per-feature catches are fail-open;
     * anything else is caught here and logged — a thrown CancellationException
     * is normal teardown and is ignored), and stops before
     * [WorkspaceFeatureReconciler.onSessionStopping] releases host state.
     */
    private fun launchDeferredReconcile(
        sessionScope: CoroutineScope,
        workspaceId: String,
        t0: Long
    ): Job {
        return sessionScope.launch {
            runtimeTrace.record(workspaceId, "Feature reconcile DEFERRED phase scheduled (Phase 2E-1)")
            awaitDeferredPhaseStart(workspaceId)
            if (!isActive) return@launch
            runtimeTrace.record(workspaceId, "Applying deferred feature toggles (Phase 2E-1)")
            try {
                featureReconciler.onSessionStartedDeferred(workspaceId)
                // Same aggregate as the pre-split single-pass reconcile: the
                // deferred phase re-reads the registry after every component
                // outcome has been reported.
                sessionManager.refreshHealth(workspaceId)
                Logger.i(
                    "TerminalChain",
                    "CHAIN: DEFERRED feature reconcile done elapsed=${elapsedMs(t0)}"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(
                    "RuntimeService",
                    "Deferred feature reconcile failed for $workspaceId (workspace keeps running): ${e.message}",
                    e
                )
            }
        }
    }

    private suspend fun run(
        sessionScope: CoroutineScope,
        workspaceId: String,
        rootfsPath: String
    ) {
        val t0 = System.currentTimeMillis()
        Logger.i("TerminalChain", "CHAIN: ENTER launchSession workspaceId=$workspaceId rootfs=$rootfsPath")
        runtimeTrace.record(workspaceId, "RuntimeService.launchSession ENTER")
        // Phase 2E-1: the deferred feature-reconcile phase, launched (in the
        // Started branch) on [sessionScope] — the launch coroutine's own scope,
        // whose Job is this session coroutine — so it dies with the session.
        // Cancelled in the finally below before teardown releases the feature
        // halves, so a stopping session never races a starting one.
        var deferredReconcile: Job? = null

        val metadata = workspaceRepository.getById(workspaceId)
        // §1.89: the validator re-walks the rootfs (hasValidRootfs +
        // getRootfsSize); this coroutine otherwise runs on the main
        // dispatcher (the service scope is Dispatchers.Main), so run the
        // filesystem checks off the main thread. The size lookup is cached in
        // WorkspaceFileManager, so repeated starts are cheap.
        val readiness = metadata?.let { meta ->
            withContext(Dispatchers.IO) { launchValidator.evaluate(meta) }
        }
        if (metadata == null || readiness !is WorkspaceReadiness.Ready) {
            val reason = describeRefusal(workspaceId, readiness)
            Logger.w("RuntimeService", "Refusing to start workspace $workspaceId: $reason")
            runtimeTrace.record(workspaceId, "RuntimeService.launchSession REFUSED: $reason")
            sessionManager.updateState(workspaceId, RuntimeState.Error(reason))
            events.emit(RuntimeEvent.Crashed(workspaceId, reason))
            host.onForegroundIdle()
            Logger.i("TerminalChain", "CHAIN: launchSession REFUSED reason=$reason elapsed=${elapsedMs(t0)}")
            runtimeTrace.persist(workspaceId, diagnosticLogManager)
            return
        }
        Logger.i("TerminalChain", "CHAIN: readiness gate PASSED elapsed=${elapsedMs(t0)}")
        runtimeTrace.record(workspaceId, "Readiness gate PASSED")
        host.onDisplayName(workspaceId, metadata.displayName)

        val outcome = lifecycleManager.requestStart(workspaceId, rootfsPath)
        // §P0: the session generation for THIS start. A later start for the same
        // workspace bumps it; if it moves while we wait on awaitSessionEnd, we
        // are the stale coroutine and must not unwind the replacement session.
        //
        // §1.165: taken from the outcome rather than re-read with a separate
        // currentGeneration() call. The old read happened after requestStart had
        // released the transition mutex, so a second start that slipped in
        // between handed BOTH coroutines the same, newer generation — and both
        // then passed isCurrentSession and ran the destructive unwind. The value
        // is now minted under the same lock that performed the start.
        val generation = outcome.generation

        // §1.165: a re-entrant duplicate start owns nothing. It opened no
        // terminal, changed no state, and its `generation` is the *incumbent's*.
        // Returning before the try/finally is what keeps it out of the teardown
        // path entirely: if it fell through, it would await the incumbent's end
        // signal and then — passing the generation guard, because the generation
        // legitimately is the current one — run closeWorkspace and removeSession
        // against a session it never started. That is the SIGKILL: the incumbent
        // is still mid-flight (or running perfectly well) when a duplicate's
        // unwind kills its process group.
        if (outcome is StartOutcome.AlreadyStarted) {
            Logger.w(
                "RuntimeService",
                "Duplicate launchSession for $workspaceId ignored: generation $generation already owns the " +
                    "runtime (pty ${outcome.pty.id} pid=${outcome.pty.pid}); this coroutine performs no teardown"
            )
            runtimeTrace.record(workspaceId, "launchSession DUPLICATE (incumbent generation $generation owns the runtime)")
            return
        }

        try {
            when (outcome) {
                is StartOutcome.AlreadyStarted -> {
                    // §1.165: unreachable — the duplicate returned above, before
                    // this try/finally was entered. Present only because Kotlin
                    // requires an exhaustive `when` over a sealed type; a real
                    // arrival here would mean the early return was deleted, and
                    // falling into the teardown path is precisely the bug.
                    Logger.e("RuntimeService", "AlreadyStarted reached the launch body for $workspaceId — early return lost")
                    return
                }
                is StartOutcome.Blocked -> {
                    val reason = outcome.reason
                    Logger.w("RuntimeService", "Workspace $workspaceId start BLOCKED: $reason")
                    runtimeTrace.record(workspaceId, "launchSession BLOCKED: $reason")
                    events.emit(RuntimeEvent.Crashed(workspaceId, reason))
                    return
                }
                is StartOutcome.Failed -> {
                    val reason = outcome.reason
                    Logger.w("RuntimeService", "Workspace $workspaceId start FAILED: $reason")
                    runtimeTrace.record(workspaceId, "launchSession FAILED: $reason")
                    events.emit(RuntimeEvent.Crashed(workspaceId, reason))
                    return
                }
                is StartOutcome.Started -> {
                    val session = outcome.pty
                    Logger.i(
                        "TerminalChain",
                        "CHAIN: openTerminal RETURNED pid=${session.pid} isAlive=${session.isAlive} id=${session.id} elapsed=${elapsedMs(t0)}"
                    )
                    runtimeTrace.record(workspaceId, "openTerminal RETURNED pid=${session.pid} isAlive=${session.isAlive} id=${session.id}")
                    // §1.76: never report Running on pid>0 alone — the pty child
                    // (proot, which runs the guest shell) can die before we get
                    // here (exec failure, bad rootfs, host-side launch error).
                    // The child writes its failure to the pty, so surface the
                    // transcript as the Error reason instead of flashing a
                    // "Running" state that is already dead.
                    if (!session.isAlive) {
                        val childOutput = runCatching { session.currentTranscript() }
                            .getOrElse { "" }
                            .replace(SANITIZE_CONTROL_RE, "")
                            .trim()
                        val outputTail = childOutput.takeLast(1200)
                        Logger.w(
                            "RuntimeService",
                            "Workspace $workspaceId PTY child dead at open (pid=${session.pid}); " +
                                "child output (${childOutput.length} chars):\n${outputTail.take(2000)}"
                        )
                        runtimeTrace.record(workspaceId, "PTY child dead at open, isAlive=${session.isAlive}")
                        val reason = if (outputTail.isNotEmpty()) {
                            "Workspace session exited immediately (code 0)\n\n--- child output (tail) ---\n$outputTail"
                        } else {
                            "Workspace session exited immediately (code 0)"
                        }
                        sessionManager.updateState(workspaceId, RuntimeState.Error(reason))
                        events.emit(RuntimeEvent.Crashed(workspaceId, reason))
                        return
                    }
                    sessionManager.updateState(workspaceId, RuntimeState.Running(session.pid, Instant.now()))
                    host.onSessionRunning(workspaceId)
                    Logger.i("RuntimeService", "TERMINAL_CHAIN: workspace $workspaceId state=Running pid=${session.pid} ptyId=${session.id}")
                    events.emit(RuntimeEvent.Started(workspaceId, session.pid))

                    // §1.110: apply the workspace's persisted network settings
                    // now that a guest exists — the ONLY reconcile work still
                    // awaited inline (Phase 2E-1: the desktop start path's Xvnc
                    // install consumes /etc/resolv.conf). Fail-open by
                    // construction (the reconciler swallows and logs every
                    // failure), so a feature that can't come up never costs the
                    // user their session. Sequenced BEFORE awaitSessionEnd
                    // deliberately: the wait only completes on an explicit
                    // teardown, so anything after it would never run for a live
                    // workspace.
                    runtimeTrace.record(workspaceId, "Applying persisted network provisioning (§1.110)")
                    featureReconciler.onSessionStarted(workspaceId)
                    // Transformation spec §6: the reconciler just reported the
                    // network component outcome to the health registry —
                    // aggregate it into the Running state so a fail-open
                    // feature failure is observable as Degraded health instead
                    // of only a log line. (Refreshed again after the deferred
                    // phase, which reports the other four components — the
                    // final aggregate is identical to the pre-split reconcile.)
                    sessionManager.refreshHealth(workspaceId)

                    // Phase 2E-1: the four daemon-backed features (ssh →
                    // notifications → clipboard → audio) leave the launch
                    // path. They are fail-open, consumed by nothing on the
                    // desktop path, and re-runnable at will — deferring their
                    // execs keeps them out of the desktop start's CPU window
                    // (Phase 2D: 0–11 provisioning execs measured inside
                    // T0→T4). The child waits for the desktop start to settle
                    // (X11 READY/FAILED) or the bounded fallback; a session
                    // stop cancels it before teardown releases host state.
                    launchDeferredReconcile(sessionScope, workspaceId, t0)
                        .also { deferredReconcile = it }

                    Logger.i("TerminalChain", "CHAIN: ENTER awaitSessionEnd elapsed=${elapsedMs(t0)}")
                    runtimeTrace.record(workspaceId, "awaitSessionEnd start")
                    // §P0: await the WORKSPACE-level end signal, not any terminal
                    // pty's awaitExit. It completes ONLY on an explicit workspace
                    // teardown ([WorkspaceTerminalManager.closeWorkspace] —
                    // stop/close/leave/restart/gate eviction), always with null.
                    // No terminal exit completes it — not closing T0
                    // (closeTerminal), not T0 exiting on its own, not a sibling
                    // exiting — so the workspace runtime and its sibling
                    // terminals stay alive regardless of what any individual
                    // terminal does (terminal lifecycle ≠ workspace lifecycle).
                    workspaceTerminalManager.awaitSessionEnd(workspaceId)
                    Logger.i(
                        "TerminalChain",
                        "CHAIN: awaitSessionEnd RETURNED (workspace closed) totalElapsed=${elapsedMs(t0)}"
                    )
                    runtimeTrace.record(workspaceId, "awaitSessionEnd end (workspace closed)")
                    // §P0 (race fix): if a NEWER session opened for this same
                    // workspace while we waited (stop-then-immediate-start,
                    // double-start), we are the stale coroutine — the replacement
                    // session owns the runtime. Do NOT clobber its state or emit a
                    // spurious Stopped; the guarded finally below then also skips
                    // closeWorkspace/removeSession.
                    if (!lifecycleManager.isCurrentSession(workspaceId, generation)) {
                        Logger.w(
                            "RuntimeService",
                            "Stale launchSession coroutine for $workspaceId (gen $generation, current " +
                                "${lifecycleManager.currentGeneration(workspaceId)}); replacement session owns the runtime — skipping stop unwind"
                        )
                        runtimeTrace.record(workspaceId, "launchSession STALE (replacement owns runtime)")
                        return
                    }
                    Logger.i("RuntimeService", "Workspace $workspaceId session ended (workspace closed)")
                    // §P0: this unwind is always an explicit workspace stop/close
                    // — never a terminal-exit crash (no terminal exit can complete
                    // the signal). Genuine runtime failures stay observable
                    // without conflating them with terminal lifecycle: launch
                    // failures surface as RuntimeState.Error + Crashed above, and
                    // a terminal dying on its own surfaces as TerminalState.Error
                    // on that terminal while the workspace keeps running.
                    sessionManager.updateState(workspaceId, RuntimeState.Stopped)
                    events.emit(RuntimeEvent.Stopped(workspaceId))
                }
            }
        } catch (e: CancellationException) {
            // Pre-release audit fix (2026-09-02, finding RT-8): a cancelled
            // session coroutine (service-scope cancel, shutdown timeout) is
            // NOT a workspace crash. The old generic catch recorded
            // RuntimeState.Error + a Crashed event for a normal cancellation
            // AND completed normally instead of propagating the cancellation.
            Logger.i("RuntimeService", "TERMINAL_CHAIN: workspace $workspaceId session cancelled (state stays as teardown left it)")
            throw e
        } catch (e: Exception) {
            Logger.e("RuntimeService", "TERMINAL_CHAIN: workspace $workspaceId crashed (state=Error): ${e.message}", e)
            Logger.i("TerminalChain", "CHAIN: launchSession FAILURE ${e::class.simpleName}: ${e.message} elapsed=${elapsedMs(t0)}")
            sessionManager.updateState(workspaceId, RuntimeState.Error(e.message ?: "Unknown error"))
            events.emit(RuntimeEvent.Crashed(workspaceId, e.message ?: "Unknown error"))
        } finally {
            Logger.i("TerminalChain", "CHAIN: EXIT launchSession (finally) elapsed=${elapsedMs(t0)}")
            runtimeTrace.record(workspaceId, "RuntimeService.launchSession EXIT (finally)")
            // Phase 2E-1: the deferred reconcile phase must not run against a
            // session being torn down — cancel it before the stop unwind
            // releases the feature halves (releaseHostState / stopDaemon).
            // cancel() returns immediately; the child dies at its next
            // suspension (the readiness poll, delay, or a guest exec).
            deferredReconcile?.cancel()
            // §P0 (race fix): only the CURRENT generation performs the destructive
            // teardown. A stale coroutine must not close the replacement's
            // terminals or remove its session. This also preserves the §1.68
            // Error-state retention (removeSession skipped while Error) and the
            // refusal/failure safety-net close (no newer start has replaced the
            // workspace, so isCurrentSession is still true).
            //
            // §1.165: this guard is only sound because `generation` now comes from
            // the [StartOutcome] — minted under the transition mutex by the very
            // call that performed the start — and because a re-entrant duplicate
            // never reaches this block at all. Previously `generation` was re-read
            // after requestStart returned, so a duplicate start could observe the
            // incumbent's own generation, pass this check, and run closeWorkspace
            // against a live session: the reported SIGKILL (-9), because
            // closeWorkspaceSessions kills the workspace's PRoot process group.
            if (lifecycleManager.isCurrentSession(workspaceId, generation)) {
                // §1.110: release the guest-side feature halves before the PRoot
                // hosts are torn down. NonCancellable because this is cleanup in
                // a `finally` — a cancelled launch coroutine must still not skip
                // the teardown below (the reconciler rethrows CancellationException
                // by design, per structured concurrency).
                withContext(NonCancellable) {
                    featureReconciler.onSessionStopping(workspaceId)
                }
                workspaceTerminalManager.closeWorkspace(workspaceId)
                // §1.68 fix: when the session ends with Error, preserve the
                // state in sessionStates so the UI (the TerminalScreen error
                // reason) can display it. removeSession deletes the entry
                // entirely → null → the failure reason flashes for one frame
                // and vanishes (the reported bug). The Error state is cleaned
                // up when the user navigates back (onCleared → stopWorkspace →
                // removeSession) or retries.
                val isError = sessionManager.getState(workspaceId) is RuntimeState.Error
                if (!isError) {
                    sessionManager.removeSession(workspaceId)
                }
            }
            runtimeTrace.persist(workspaceId, diagnosticLogManager)
            // §1.97/W4: tell the gate this session unwound — but only clear the
            // active reference if THIS workspace is genuinely over, so a
            // replacement's session can't have the gate cleared out from under it.
            lifecycleManager.sessionEnded(workspaceId)
            if (lifecycleManager.sessionTrulyOver(workspaceId)) {
                host.onDisplayNameForgotten(workspaceId)
            }
            // §1.97/W4: only stop the service when no workspace is active
            // anymore. A session evicted by START(B) must not stopSelf out
            // from under B; a stop/refusal leaves the gate empty, so the
            // service tears its foreground notification down here.
            if (lifecycleManager.activeWorkspaceId.value == null) {
                host.onForegroundIdle()
            }
        }
    }

    /** Turns a failed/absent [WorkspaceReadiness] into a message suitable for [RuntimeEvent.Crashed]. */
    private fun describeRefusal(workspaceId: String, readiness: WorkspaceReadiness?): String {
        return when (readiness) {
            null -> "No workspace record found for $workspaceId"
            is WorkspaceReadiness.Blocked -> readiness.reason
            is WorkspaceReadiness.Installing -> "Installation is still in progress (${(readiness.progress * 100).toInt()}%)"
            is WorkspaceReadiness.Busy -> "Workspace is currently ${readiness.operation.lowercase()}"
            is WorkspaceReadiness.Ready -> "Workspace is ready" // unreachable here, satisfies exhaustiveness
        }
    }

    private companion object {
        /** §1.68: strip C0 control frames from a child-output snapshot (keep \n \r \t). */
        val SANITIZE_CONTROL_RE = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

        /** §1.68 chain diagnostic: ms since [t0]. */
        fun elapsedMs(t0: Long): String = "${System.currentTimeMillis() - t0}ms"

        /**
         * Phase 2E-1: how long the deferred reconcile phase will keep waiting
         * for a desktop start (X11 STARTING) to settle before running anyway.
         * The bound is the "features present within a bounded window" reading
         * of the §1.110 contract — a workspace whose desktop never starts
         * (feature disabled, user on the terminal) converges at most this
         * late. Same scale as X11SessionManager's own socket timeout.
         */
        const val DEFERRED_FALLBACK_TIMEOUT_MS = 15_000L

        /** Phase 2E-1: poll cadence for the X11 STARTING observation (one cheap in-process read; no guest exec). */
        const val DEFERRED_POLL_INTERVAL_MS = 250L
    }
}
