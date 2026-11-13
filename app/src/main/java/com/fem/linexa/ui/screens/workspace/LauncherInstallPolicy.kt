package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.service.installer.AppInstallCoordinator

/**
 * Pure presentation/action policy for one launcher app (JVM-tested).
 *
 * The tile and the install sheet must agree on what a job state means. Putting
 * the rules here — instead of duplicating `if` chains in Compose — keeps the
 * important guarantees testable:
 *
 *  - an INSTALLED job result is treated as installed even before the tracker's
 *    next probe lands, so neither the sheet nor a repeated tap can start a
 *    second install;
 *  - a FAILED/CANCELLED install never renders as installed;
 *  - progress is shown only for an active job;
 *  - a terminal job keeps its success/failure identity, while the underlying
 *    measured state still wins for the tile when it says INSTALLED.
 */
object LauncherInstallPolicy {

    enum class TileStatus {
        INSTALLED,
        NOT_INSTALLED,
        UNKNOWN,
        INSTALLING,
        REMOVING,
        VERIFYING,
        FAILED,
        CANCELLED
    }

    enum class SheetAction { OPEN, INSTALL, CHECKING, PROGRESS }

    /** The status line the tile renders for [installed] plus an optional job. */
    fun tileStatus(
        installed: AppInstallTracker.InstalledState,
        job: AppInstallCoordinator.JobState?
    ): TileStatus {
        if (job != null) {
            if (job.isActive) {
                if (job.operation == AppInstallCoordinator.JobState.Operation.REMOVE) {
                    return TileStatus.REMOVING
                }
                return if (job.phase == AppInstallCoordinator.JobState.Phase.VERIFYING) {
                    TileStatus.VERIFYING
                } else {
                    TileStatus.INSTALLING
                }
            }
            when (job.result) {
                AppInstallCoordinator.JobState.Result.INSTALLED -> return TileStatus.INSTALLED
                AppInstallCoordinator.JobState.Result.REMOVED -> return TileStatus.NOT_INSTALLED
                AppInstallCoordinator.JobState.Result.FAILED ->
                    if (installed != AppInstallTracker.InstalledState.INSTALLED) return TileStatus.FAILED
                AppInstallCoordinator.JobState.Result.CANCELLED ->
                    if (installed != AppInstallTracker.InstalledState.INSTALLED) return TileStatus.CANCELLED
                null -> Unit
            }
        }
        return when (installed) {
            AppInstallTracker.InstalledState.INSTALLED -> TileStatus.INSTALLED
            AppInstallTracker.InstalledState.NOT_INSTALLED -> TileStatus.NOT_INSTALLED
            AppInstallTracker.InstalledState.UNKNOWN -> TileStatus.UNKNOWN
        }
    }

    /** True when the app is installed by the measured state OR a verified job. */
    fun isInstalled(
        installed: AppInstallTracker.InstalledState,
        job: AppInstallCoordinator.JobState?
    ): Boolean =
        installed == AppInstallTracker.InstalledState.INSTALLED ||
            job?.result == AppInstallCoordinator.JobState.Result.INSTALLED

    /**
     * The install sheet's one primary action. A running job always wins (the
     * sheet shows its progress; no second Install button), a verified install
     * flips to Open, and an unmeasured app waits for the probe instead of
     * offering a possibly redundant install.
     */
    fun sheetAction(
        installed: AppInstallTracker.InstalledState,
        job: AppInstallCoordinator.JobState?,
        probing: Boolean
    ): SheetAction {
        if (job?.isActive == true) return SheetAction.PROGRESS
        if (isInstalled(installed, job)) return SheetAction.OPEN
        if (installed == AppInstallTracker.InstalledState.UNKNOWN && probing) {
            return SheetAction.CHECKING
        }
        return SheetAction.INSTALL
    }

    /**
     * Whether a new install may start. This is the repeated-tap guard: an
     * installed app, a verified terminal install, or an active job all reject
     * the request.
     */
    fun canStartInstall(
        installed: AppInstallTracker.InstalledState,
        job: AppInstallCoordinator.JobState?
    ): Boolean =
        !isInstalled(installed, job) &&
            job?.isActive != true

    /** Progress animations are allowed only while the job can still change state. */
    fun showsProgress(job: AppInstallCoordinator.JobState?): Boolean =
        job?.isActive == true
}
