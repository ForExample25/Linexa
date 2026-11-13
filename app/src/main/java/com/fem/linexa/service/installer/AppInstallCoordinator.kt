package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.service.runtime.RuntimeBinder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ONE screen-independent install/uninstall orchestrator for the App
 * Launcher (App Launcher redesign).
 *
 * ## Why a Singleton, not the screen's ViewModel
 *
 * The Package Manager screen's `runOperation` lives in a back-stack-scoped
 * ViewModel — leaving the screen cancels the job. A launcher install must
 * survive navigation (the user taps Install and keeps browsing): this
 * coordinator owns its own [SupervisorJob] scope, so an install started on the
 * Launcher continues while the user opens the terminal, the file manager, or
 * another tab, and its progress is published to whoever is observing.
 *
 * ## Explicit lifecycle
 *
 * Every job moves through [JobState.Phase.QUEUED] → INSTALLING/REMOVING →
 * VERIFYING and ends in exactly one terminal [JobState.Result] —
 * INSTALLED, REMOVED, FAILED or CANCELLED. [AppInstallJobLifecycle] owns those
 * transitions, so a late exception cannot overwrite a verified result and a
 * terminal job can never write another progress line. The UI's progress bar
 * is gated on [JobState.isActive], which is false for every terminal result.
 *
 * ## What it does NOT do
 *
 *  - It never builds package commands itself: the catalog's own install
 *    command runs through the runtime's exec bridge, exactly as the Commands
 *    grid's tier-2 package lane runs it — which means the operation funnels
 *    through the runtime's [PackageOperationCoordinator] queue and cannot
 *    race a dpkg lock with any other surface.
 *  - It never fabricates progress: the published state carries the manager's
 *    own live output lines (a bounded tail), and the honest phase
 *    (queued → installing → verifying → done/failed). No numeric percentage
 *    exists to fake — apt does not report one, so the UI shows real streamed
 *    output instead.
 *  - It never claims success without verification: after the install command
 *    exits 0, [AppInstallProbe] confirms EVERY declared package is present
 *    (or all declared executables resolve) before publishing
 *    [JobState.Result.INSTALLED]. Removal requires every package/executable
 *    to measure absent.
 *
 * ## Concurrency rules
 *
 *  - One job per app: a second request while the app's job is active is
 *    ignored (the duplicate-install requirement). The check and insertion are
 *    atomic.
 *  - Jobs are keyed by `workspaceId::appId` and carry a unique [JobState.jobId],
 *    so two apps never overwrite each other and a finishing old job cannot
 *    publish into a newer job for the same app.
 *  - Cross-surface safety: actual mutual exclusion against every other
 *    package surface is the runtime exec bridge's job (the coordinator queue);
 *    this class adds the launcher's own per-app de-dup on top.
 *  - Binder swaps: a replaced RuntimeService binder is adopted via
 *    [attachBinder]'s identity check.
 *
 * Failure handling: network failures, guest errors and timeouts arrive as the
 * provider/exec failure path — the manager's own message is published, never
 * a generic "failed" alone.
 */
@Singleton
class AppInstallCoordinator @Inject constructor(
    private val probe: AppInstallProbe
) {

    /**
     * The live state of one app's install/uninstall, keyed by
     * `workspaceId + app.id`.
     *
     * [output] is a bounded tail of the package manager's real output — what
     * the compact progress indicator shows while the job is active. [phase]
     * orders the operation's honest stages; [result] is null until the one
     * terminal transition, and [message] carries a human-readable terminal
     * explanation (verification failure, cancellation, thrown error).
     */
    data class JobState(
        val jobId: String,
        val app: LauncherApp,
        val operation: Operation,
        val phase: Phase,
        /** Bounded tail of live output lines (the manager's own text). */
        val output: String = "",
        /** Terminal result of the whole job — null while it runs. */
        val result: Result? = null,
        /** Terminal explanation, when one exists. */
        val message: String? = null
    ) {
        enum class Operation { INSTALL, REMOVE }

        enum class Phase { QUEUED, INSTALLING, VERIFYING, REMOVING }

        enum class Result { INSTALLED, REMOVED, FAILED, CANCELLED }

        val isActive: Boolean get() = result == null

        val isTerminal: Boolean get() = result != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val jobs: StateFlow<Map<String, JobState>> = _jobs.asStateFlow()

    /** In-flight jobs for [workspaceId], for the screen's compact indicator area. */
    fun activeJobs(workspaceId: String): List<JobState> =
        _jobs.value.filterKeys { it.startsWith("$workspaceId::") }
            .map { it.value }
            .filter { it.isActive }

    private var binder: RuntimeBinder? = null

    /** The active coroutine per key, with the job id it belongs to. */
    private val jobsByKey = HashMap<String, ActiveJob>()
    private val lock = Any()
    private val nextJobNumber = AtomicLong(0)

    private data class ActiveJob(val jobId: String, val job: Job)

    /** Adopts the graph's runtime binder (identity-checked, replacement-safe). */
    fun attachBinder(runtimeBinder: RuntimeBinder) {
        if (runtimeBinder === binder) return
        binder = runtimeBinder
    }

    /**
     * Starts installing [app] into [workspaceId] in the background.
     *
     * Runs the app's own catalog install command through the exec bridge (the
     * package queue serializes it against every other surface). When the
     * command succeeds, the result is re-verified by probing the app's
     * declared packages/executables before publishing
     * [JobState.Result.INSTALLED].
     */
    fun install(workspaceId: String, app: LauncherApp) {
        startJob(workspaceId, app, JobState.Operation.INSTALL) { b, ws, onLine, onPhase ->
            val command = app.installCommand
                ?: return@startJob JobState.Result.FAILED to
                    "The catalog declares no install command for this app"
            onPhase(JobState.Phase.INSTALLING)
            val exit = runPackageCommand(b, ws, command, onLine)
            if (exit != 0) {
                JobState.Result.FAILED to null
            } else {
                onPhase(JobState.Phase.VERIFYING)
                when (probe.measure(b, ws, app)) {
                    AppInstallProbe.State.INSTALLED -> JobState.Result.INSTALLED to null
                    AppInstallProbe.State.NOT_INSTALLED -> JobState.Result.FAILED to
                        "The install command finished, but the app is incomplete — one or more declared packages are missing. Check the output and try again."
                    AppInstallProbe.State.UNKNOWN -> JobState.Result.FAILED to
                        "The install command finished, but the app could not be verified in this workspace."
                }
            }
        }
    }

    /**
     * Uninstalls [app] from [workspaceId] using the catalog's own remove
     * command. Verification mirrors [install]: every declared package (or
     * executable) must measure absent before [JobState.Result.REMOVED] is
     * published.
     */
    fun remove(workspaceId: String, app: LauncherApp) {
        startJob(workspaceId, app, JobState.Operation.REMOVE) { b, ws, onLine, onPhase ->
            val command = app.removeCommand
                ?: return@startJob JobState.Result.FAILED to
                    "The catalog declares no uninstall command for this app"
            onPhase(JobState.Phase.REMOVING)
            val exit = runPackageCommand(b, ws, command, onLine)
            if (exit != 0) {
                JobState.Result.FAILED to null
            } else {
                onPhase(JobState.Phase.VERIFYING)
                when (probe.measure(b, ws, app)) {
                    AppInstallProbe.State.NOT_INSTALLED -> JobState.Result.REMOVED to null
                    AppInstallProbe.State.INSTALLED -> JobState.Result.FAILED to
                        "The uninstall command finished, but the app still measures as present."
                    AppInstallProbe.State.UNKNOWN -> JobState.Result.FAILED to
                        "The uninstall command finished, but the removal could not be verified in this workspace."
                }
            }
        }
    }

    /** Whether an install/uninstall job is currently active for this app. */
    fun isJobActive(workspaceId: String, appId: String): Boolean =
        _jobs.value[keyOf(workspaceId, appId)]?.isActive == true

    /** Drops a finished job's state (the indicator's dismiss action). Active jobs are never dropped. */
    fun dismiss(workspaceId: String, appId: String) {
        val key = keyOf(workspaceId, appId)
        _jobs.update { current ->
            val state = current[key] ?: return@update current
            if (state.isActive) current else current - key
        }
    }

    /**
     * Cancels the active job for this app where technically safe.
     *
     * A package operation mid-flight cannot be safely torn down from outside
     * the guest (a cancelled coroutine leaves dpkg half-configured; the
     * runtime's own `dpkg --configure -a` recovery preflight exists precisely
     * for that). So cancellation is only offered BEFORE the guest command has
     * produced output — once real work started, the job must run to its
     * verified end. A successful cancel publishes the explicit terminal
     * [JobState.Result.CANCELLED] state (never silently deletes the job), so
     * the UI stops immediately and can dismiss the card.
     *
     * Returns whether the job was actually cancelled.
     */
    fun cancelIfSafe(workspaceId: String, appId: String): Boolean {
        val key = keyOf(workspaceId, appId)
        val active: ActiveJob
        val state: JobState
        synchronized(lock) {
            active = jobsByKey[key] ?: return false
            state = _jobs.value[key] ?: return false
        }
        if (!state.isActive) return false
        if (state.output.isNotBlank()) return false
        publish(key, active.jobId) { AppInstallJobLifecycle.cancelled(it) }
        active.job.cancel()
        return true
    }

    // ── implementation ──────────────────────────────────────────────────────

    private fun startJob(
        workspaceId: String,
        app: LauncherApp,
        operation: JobState.Operation,
        run: suspend (
            binder: RuntimeBinder,
            workspaceId: String,
            onLine: (String) -> Unit,
            onPhase: (JobState.Phase) -> Unit
        ) -> Pair<JobState.Result, String?>
    ) {
        val b = binder ?: run {
            Logger.w(TAG, "install(${app.id}) ignored: no binder attached")
            return
        }
        val key = keyOf(workspaceId, app.id)
        val jobId = "job-${nextJobNumber.incrementAndGet()}"
        val startedJob: Job
        synchronized(lock) {
            if (_jobs.value[key]?.isActive == true) {
                Logger.d(TAG, "job for ${app.id} already active — request ignored")
                return
            }
            _jobs.update {
                it + (key to AppInstallJobLifecycle.started(jobId, app, operation))
            }
            startedJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val (result, message) = run(
                        b,
                        workspaceId,
                        { line -> publish(key, jobId) { AppInstallJobLifecycle.withOutput(it, line) } },
                        { phase -> publish(key, jobId) { AppInstallJobLifecycle.withPhase(it, phase) } }
                    )
                    publish(key, jobId) { AppInstallJobLifecycle.finished(it, result, message) }
                } catch (e: CancellationException) {
                    publish(key, jobId) { AppInstallJobLifecycle.cancelled(it) }
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "job for ${app.id} failed", e)
                    publish(key, jobId) {
                        AppInstallJobLifecycle.finished(
                            it,
                            JobState.Result.FAILED,
                            e.message ?: "Operation failed"
                        )
                    }
                } finally {
                    synchronized(lock) {
                        if (jobsByKey[key]?.jobId == jobId) jobsByKey.remove(key)
                    }
                }
            }
            jobsByKey[key] = ActiveJob(jobId, startedJob)
        }
        startedJob.start()
    }

    /**
     * Runs one catalog package command, streaming each output line into the
     * job's state. Returns the exit code (0 = success). Cancellation is never
     * converted into an exit code.
     */
    private suspend fun runPackageCommand(
        b: RuntimeBinder,
        workspaceId: String,
        command: String,
        onLine: (String) -> Unit
    ): Int = try {
        b.execCommandStreaming(workspaceId, command, onLine = onLine).exitCode
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onLine(e.message ?: "Operation failed")
        -1
    }

    /**
     * Applies [transform] only to the job identified by [jobId]. This is the
     * guard that keeps a finishing old job from writing into a newer job for
     * the same app.
     */
    private fun publish(
        key: String,
        jobId: String,
        transform: (JobState) -> JobState
    ) {
        _jobs.update { current ->
            val existing = current[key] ?: return@update current
            if (existing.jobId != jobId) return@update current
            val next = transform(existing)
            if (next == existing) current else current + (key to next)
        }
    }

    private fun keyOf(workspaceId: String, appId: String) = "$workspaceId::$appId"

    private companion object {
        const val TAG = "AppInstallCoordinator"
    }
}
