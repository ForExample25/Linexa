package com.fem.linexa.ui.screens.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.domain.packagemanager.PackageShell
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.installer.AppInstallMeasurements
import com.fem.linexa.service.installer.AppInstallProbe
import com.fem.linexa.service.runtime.RuntimeBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Measures and holds the launcher's apps' INSTALLED states for one workspace
 * (App Launcher redesign).
 *
 * ## The measurement — the workspace's own package manager, never dpkg-only
 *
 * [AppInstallProbe] resolves the workspace's actual package manager and asks
 * it for the COMPLETE installed set once per refresh. Every declared package
 * is then compared against that set. This fixes the two defects of the old
 * `dpkg-query -W <names>` probe:
 *
 *  - non-apt workspaces (pacman/apk/dnf/zypper) measure instead of staying
 *    UNKNOWN, and
 *  - absence is measurable: a name missing from the complete set is
 *    positively NOT installed, whereas `dpkg-query -W` printed nothing for an
 *    unknown package and left the tile on "Unknown state".
 *
 * An app is INSTALLED only when EVERY declared verification package is
 * present. A partial install is NOT_INSTALLED, never "installed enough".
 * Apps without package names fall back to their launch executable; an app
 * with neither source stays [InstalledState.UNKNOWN] — no guessed state.
 *
 * ## Refresh rules
 *
 *  - [attach] and [setApps] measure immediately (works after app restart);
 *  - the Apps screen calls [refresh] on tab entry, so a package installed by
 *    another surface is picked up without restarting Linexa;
 *  - [observeJobs] watches the coordinator's per-job terminal state: a
 *    verified INSTALLED/REMOVED result is applied immediately, then the
 *    authoritative probe runs once more. The old active-count heuristic is
 *    gone, so two jobs overlapping cannot hide the completion of one.
 *
 * Pure decision logic lives in [InstalledStates] (JVM-tested); this class is
 * the binder-facing shell.
 */
@HiltViewModel
class AppInstallTracker @Inject constructor(
    private val coordinator: AppInstallCoordinator,
    private val probe: AppInstallProbe
) : ViewModel() {

    /** One app's measured install state. */
    enum class InstalledState { INSTALLED, NOT_INSTALLED, UNKNOWN }

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null

    private val _states = MutableStateFlow<Map<String, InstalledState>>(emptyMap())
    val states: StateFlow<Map<String, InstalledState>> = _states.asStateFlow()

    /** True while the initial/refresh probe is in flight (the grid's quiet loader). */
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private var probeJob: Job? = null
    private var probeGeneration = 0

    private var jobsObserver: Job? = null
    private var observedJobsWorkspace: String? = null
    private val handledTerminalJobs = HashSet<String>()

    fun attach(wsId: String, runtimeBinder: RuntimeBinder) {
        val same = workspaceId == wsId && runtimeBinder === binder
        workspaceId = wsId
        binder = runtimeBinder
        coordinator.attachBinder(runtimeBinder)
        if (!same || _states.value.isEmpty()) refresh()
        observeJobs()
    }

    fun detach() {
        binder = null
        workspaceId = null
        probeGeneration++
        probeJob?.cancel()
        probeJob = null
        jobsObserver?.cancel()
        jobsObserver = null
        observedJobsWorkspace = null
        handledTerminalJobs.clear()
        _probing.value = false
        _states.value = emptyMap()
    }

    /** Re-runs the authoritative probe (screen entry, explicit refresh, after a job). */
    fun refresh() {
        val b = binder ?: return
        val ws = workspaceId ?: return
        val generation = ++probeGeneration
        probeJob?.cancel()
        _probing.value = true
        probeJob = viewModelScope.launch {
            try {
                val measured = measureAll(b, ws)
                if (generation == probeGeneration) _states.value = measured
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed probe keeps the previous measurements rather than
                // blanking the grid into "unknown everywhere".
                Logger.w(TAG, "install-state probe failed: ${e.message}")
            } finally {
                if (generation == probeGeneration) _probing.value = false
            }
        }
    }

    /**
     * Observes coordinator jobs for this workspace. A job's ONE terminal
     * transition applies its verified result immediately (INSTALLED /
     * REMOVED) and triggers a re-probe; FAILED/CANCELLED never mark anything
     * installed. Re-subscribes when the workspace changes.
     */
    fun observeJobs() {
        val ws = workspaceId ?: return
        if (observedJobsWorkspace == ws && jobsObserver?.isActive == true) return
        jobsObserver?.cancel()
        observedJobsWorkspace = ws
        handledTerminalJobs.clear()
        jobsObserver = viewModelScope.launch {
            coordinator.jobs.collect { jobs ->
                val mine = jobs.filterKeys { it.startsWith("$ws::") }.values
                var terminalSeen = false
                for (job in mine) {
                    if (!job.isTerminal || !handledTerminalJobs.add(job.jobId)) continue
                    terminalSeen = true
                    when (job.result) {
                        AppInstallCoordinator.JobState.Result.INSTALLED ->
                            _states.update { it + (job.app.id to InstalledState.INSTALLED) }
                        AppInstallCoordinator.JobState.Result.REMOVED ->
                            _states.update { it + (job.app.id to InstalledState.NOT_INSTALLED) }
                        else -> Unit
                    }
                }
                if (terminalSeen) refresh()
            }
        }
    }

    /** The app's current measured state, or UNKNOWN when never probed. */
    fun stateOf(app: LauncherApp): InstalledState =
        _states.value[app.id] ?: InstalledState.UNKNOWN

    // ── measurement ────────────────────────────────────────────────────────

    /**
     * One measurement pass: a single complete installed-set query through the
     * resolved provider, plus one `command -v` sweep per app that has no
     * package verdict (no declared packages, or no provider answer).
     */
    private suspend fun measureAll(b: RuntimeBinder, ws: String): Map<String, InstalledState> {
        val apps = currentApps
        if (apps.isEmpty()) return emptyMap()

        val installed = probe.installedNames(b, ws)
        val executableVerdicts = HashMap<String, Boolean>()
        for (app in apps) {
            val packages = app.verificationPackages.filter(PackageShell::isSafePackageName)
            val presence = AppInstallMeasurements.presenceOf(installed, packages)
            if (presence != AppInstallMeasurements.Presence.UNKNOWN) continue
            if (app.verificationExecutables.isEmpty()) continue
            probe.executablesPresent(b, ws, app.verificationExecutables)?.let { present ->
                executableVerdicts[app.id] = present
            }
        }

        return InstalledStates.mergeStates(apps, installed, executableVerdicts)
    }

    /** The apps to measure — supplied by the launcher screen on attach/refresh. */
    private var currentApps: List<LauncherApp> = emptyList()

    /** Sets the app corpus and immediately re-measures (the launcher's tree load). */
    fun setApps(apps: List<LauncherApp>) {
        currentApps = apps
        refresh()
    }

    private companion object {
        const val TAG = "AppInstallTracker"
    }
}

/**
 * Pure merge decisions of the install-state probe (JVM-tested).
 *
 * Kept top-level so the tests exercise exactly the production logic without
 * a ViewModel.
 */
object InstalledStates {

    /**
     * The final per-app state.
     *
     * Package source first, with strict "all declared packages present"
     * semantics: a complete installed set is authoritative for both presence
     * and absence. The executable verdict is only consulted when no package
     * verdict exists. UNKNOWN when neither source measured anything.
     */
    fun mergeStates(
        apps: List<LauncherApp>,
        installedNames: Set<String>?,
        executableVerdicts: Map<String, Boolean>
    ): Map<String, AppInstallTracker.InstalledState> {
        val out = HashMap<String, AppInstallTracker.InstalledState>()
        for (app in apps) {
            out[app.id] = stateOf(app, installedNames, executableVerdicts)
        }
        return out
    }

    /** [mergeStates] for one app — the single rule, kept apart for tests. */
    fun stateOf(
        app: LauncherApp,
        installedNames: Set<String>?,
        executableVerdicts: Map<String, Boolean>
    ): AppInstallTracker.InstalledState {
        val packages = app.verificationPackages.filter(PackageShell::isSafePackageName)
        when (AppInstallMeasurements.presenceOf(installedNames, packages)) {
            AppInstallMeasurements.Presence.PRESENT ->
                return AppInstallTracker.InstalledState.INSTALLED
            AppInstallMeasurements.Presence.ABSENT ->
                return AppInstallTracker.InstalledState.NOT_INSTALLED
            AppInstallMeasurements.Presence.UNKNOWN -> Unit
        }
        executableVerdicts[app.id]?.let {
            return if (it) {
                AppInstallTracker.InstalledState.INSTALLED
            } else {
                AppInstallTracker.InstalledState.NOT_INSTALLED
            }
        }
        return AppInstallTracker.InstalledState.UNKNOWN
    }
}
