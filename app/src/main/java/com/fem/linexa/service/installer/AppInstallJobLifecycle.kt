package com.fem.linexa.service.installer

import com.fem.linexa.domain.launcher.LauncherApp

/**
 * Pure state transitions of one launcher install/uninstall job (JVM-tested).
 *
 * The coordinator owns coroutines and the runtime binder; every mutation of a
 * [AppInstallCoordinator.JobState] goes through this object so the lifecycle
 * is one explicit rule instead of ad-hoc copies:
 *
 *  - a job starts QUEUED and is active;
 *  - queue → running (INSTALLING/REMOVING) → verifying transitions never
 *    revive a terminal job;
 *  - exactly one terminal result (INSTALLED/REMOVED/FAILED/CANCELLED) is
 *    written, and the first terminal result wins (a late exception cannot
 *    overwrite a verified cancellation);
 *  - progress lines are appended only while the job is active;
 *  - terminal states expose `isActive == false`, which is the single switch
 *    the UI uses to stop every progress animation.
 */
object AppInstallJobLifecycle {

    fun started(
        jobId: String,
        app: LauncherApp,
        operation: AppInstallCoordinator.JobState.Operation
    ): AppInstallCoordinator.JobState = AppInstallCoordinator.JobState(
        jobId = jobId,
        app = app,
        operation = operation,
        phase = AppInstallCoordinator.JobState.Phase.QUEUED
    )

    fun withPhase(
        state: AppInstallCoordinator.JobState,
        phase: AppInstallCoordinator.JobState.Phase
    ): AppInstallCoordinator.JobState =
        if (state.isActive) state.copy(phase = phase) else state

    fun withOutput(
        state: AppInstallCoordinator.JobState,
        line: String
    ): AppInstallCoordinator.JobState {
        if (!state.isActive || line.isBlank()) return state
        val combined = if (state.output.isEmpty()) line else "${state.output}\n$line"
        return state.copy(output = combined.takeLast(MAX_OUTPUT_CHARS))
    }

    /** Writes the terminal result; a second terminal write is ignored. */
    fun finished(
        state: AppInstallCoordinator.JobState,
        result: AppInstallCoordinator.JobState.Result,
        message: String? = null
    ): AppInstallCoordinator.JobState =
        if (!state.isActive) state else state.copy(result = result, message = message)

    fun cancelled(state: AppInstallCoordinator.JobState): AppInstallCoordinator.JobState =
        finished(
            state,
            AppInstallCoordinator.JobState.Result.CANCELLED,
            message = "Cancelled before the package operation produced output"
        )

    /** True only while the job may still change package state. */
    fun showsProgress(state: AppInstallCoordinator.JobState): Boolean = state.isActive

    const val MAX_OUTPUT_CHARS = 2_000
}
