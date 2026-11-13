package com.fem.linexa.ui.screens.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.domain.launcher.LauncherApps
import com.fem.linexa.domain.terminal.TerminalWindowIdentity
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.installer.AppInstallCoordinator
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
 * The App Launcher's ViewModel (App Launcher redesign): the app corpus from the
 * resolved catalog, measured install states, one-tap install/uninstall through
 * the screen-independent [AppInstallCoordinator], and app launching.
 *
 * ## Tap semantics (the launcher's contract)
 *
 *  - **Installed app with a launch command** → opens a NEW terminal session in
 *    the workspace and runs the launch command there (the same tier the
 *    Commands grid uses: package commands stay inline, interactive tools get a
 *    real pty). The one-shot `launchedTerminal` carries the id for the overlay
 *    to focus — published only after the command was written, so the overlay
 *    never opens onto a session that received nothing.
 *  - **Installed app with NO launch command** → opens a terminal at a shell in
 *    the workspace (the honest "this app has no launcher entry" fallback).
 *  - **Not-installed app** → the UI confirms, then [install] runs in the
 *    background; the user stays on the launcher.
 *  - **Unknown state** → tapping opens the app's action sheet (the grid's
 *    context menu), which offers Install with its measured state, never a
 *    guessed one.
 *
 * ## Separation of duties
 *
 * Install-state MEASUREMENT lives in [AppInstallTracker] (a peer ViewModel the
 * screen wires); this class only reads its flow so the launcher's list state
 * stays single-sourced. Nothing here fabricates states: `apps` carries exactly
 * what [LauncherApps.fromCatalog] derived.
 */
@HiltViewModel
class AppLauncherViewModel @Inject constructor(
    private val quickCommandsResolver: QuickCommandsResolver,
    private val coordinator: AppInstallCoordinator,
    private val windowIdentities: TerminalWindowIdentityRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "AppLauncherViewModel"

        /** Run-state key prefix for app-launch operations (namespaced away from shortcut ids). */
        const val LAUNCH_KEY_PREFIX = "applaunch:"

        /** Synthetic window identity for the Apps toolbar's Root shell action. */
        const val ROOT_SHELL_APP_ID = "linexa:root-shell"

        private const val PTY_ATTEMPTS = 20
        private const val PTY_RETRY_MS = 100L
    }

    /** One app row: the app plus everything the tile renders measured at runtime. */
    data class AppRow(
        val app: LauncherApp,
        val installed: AppInstallTracker.InstalledState
    )

    data class LauncherState(
        /** The app corpus in catalog order (empty before the catalog resolves). */
        val apps: List<AppRow> = emptyList(),
        /** Live install/uninstall jobs for THIS workspace, for the indicator area. */
        val jobs: List<AppInstallCoordinator.JobState> = emptyList()
    )

    private val _state = MutableStateFlow(LauncherState())
    val state: StateFlow<LauncherState> = _state.asStateFlow()

    /** Run states for app launches (Opening/Launched/Failed), keyed by app id. */
    private val _launchStates = MutableStateFlow<Map<String, AppLaunchState>>(emptyMap())
    val launchStates: StateFlow<Map<String, AppLaunchState>> = _launchStates.asStateFlow()

    /** One-shot: the terminal session an app launch opened, for the overlay to focus. */
    private val _launchedTerminal = MutableStateFlow<String?>(null)
    val launchedTerminal: StateFlow<String?> = _launchedTerminal.asStateFlow()

    /**
     * 2026-11-13: the explicit Apps → Root shell action's state. Separate
     * from the per-app map because it has no catalog tile; the UI shows its
     * failure reason inline instead of silently logging it.
     */
    private val _rootShellState = MutableStateFlow<AppLaunchState>(AppLaunchState.Idle)
    val rootShellState: StateFlow<AppLaunchState> = _rootShellState.asStateFlow()

    private var rootShellJob: Job? = null

    private var binder: RuntimeBinder? = null
    private var workspaceId: String? = null
    private var distroKey: String? = null
    private var distroVersion: String? = null

    /**
     * One in-flight launch PER app id. A rapid double-tap on the same app is
     * ignored (it cannot open two windows or write the command twice), while
     * different apps may open their own independent windows concurrently.
     */
    private val launchJobs = mutableMapOf<String, Job>()

    /** The most recent install-state map measured by the tracker (kept in sync by [adoptStates]). */
    private var installStates: Map<String, AppInstallTracker.InstalledState> = emptyMap()

    fun attach(wsId: String, runtimeBinder: RuntimeBinder) {
        val same = workspaceId == wsId && runtimeBinder === binder
        workspaceId = wsId
        binder = runtimeBinder
        coordinator.attachBinder(runtimeBinder)
        if (!same) {
            _state.value = LauncherState()
            installStates = emptyMap()
        }
        observeJobs()
    }

    /** The workspace's distro identity, for resolving the catalog branch. */
    fun setDistro(key: String?, version: String?) {
        if (key == distroKey && version == distroVersion) return
        distroKey = key
        distroVersion = version
        reloadApps()
    }

    /** Resolves the app corpus from the catalog (pure) and publishes it. */
    fun reloadApps() {
        val apps = LauncherApps.fromCatalog(
            quickCommandsResolver.resolve(distroKey, distroVersion)
        )
        _state.update { it.copy(apps = apps.map { app -> AppRow(app, stateOf(app)) }) }
    }

    /** Adopts a re-measured install-state map from the tracker. */
    fun adoptStates(states: Map<String, AppInstallTracker.InstalledState>) {
        installStates = states
        _state.update { current ->
            current.copy(apps = current.apps.map { it.copy(installed = states[it.app.id] ?: AppInstallTracker.InstalledState.UNKNOWN) })
        }
    }

    /**
     * Adopts live coordinator jobs for this workspace (the indicator area's
     * data). Re-subscribes when the workspace changes — the old guard returned
     * early forever, so a new workspace kept showing nothing.
     */
    private fun observeJobs() {
        val ws = workspaceId ?: return
        if (jobsWorkspace == ws && jobsObserver?.isActive == true) return
        jobsObserver?.cancel()
        jobsWorkspace = ws
        jobsObserver = viewModelScope.launch {
            coordinator.jobs.collect { jobs ->
                val mine = jobs.filterKeys { it.startsWith("$ws::") }
                    .map { it.value }
                    .sortedWith(
                        compareByDescending<AppInstallCoordinator.JobState> { it.isActive }
                            .thenBy { it.app.name }
                    )
                _state.update { it.copy(jobs = mine) }
            }
        }
    }

    private var jobsObserver: Job? = null
    private var jobsWorkspace: String? = null

    fun detach() {
        binder = null
        workspaceId = null
        jobsObserver?.cancel()
        jobsObserver = null
        jobsWorkspace = null
        launchJobs.values.forEach { it.cancel() }
        launchJobs.clear()
        rootShellJob?.cancel()
        rootShellJob = null
        _rootShellState.value = AppLaunchState.Idle
        _state.value = LauncherState()
        _launchStates.value = emptyMap()
        _launchedTerminal.value = null
    }

    // ── one-tap install / uninstall ──────────────────────────────────────────

    /**
     * Starts the background install of [app] (post-confirmation). The
     * coordinator owns the job; the tracker re-measures when it finishes.
     *
     * Guarded by [LauncherInstallPolicy.canStartInstall]: an installed app, a
     * verified terminal install, or an active job never starts another
     * package operation.
     */
    fun install(app: LauncherApp) {
        val ws = workspaceId ?: return
        val job = jobOf(app.id)
        val installed = installStates[app.id] ?: AppInstallTracker.InstalledState.UNKNOWN
        if (!LauncherInstallPolicy.canStartInstall(installed, job)) return
        if (coordinator.isJobActive(ws, app.id)) return
        coordinator.install(ws, app)
    }

    /** Starts the background uninstall of [app] (post-confirmation). */
    fun uninstall(app: LauncherApp) {
        val ws = workspaceId ?: return
        if (coordinator.isJobActive(ws, app.id)) return
        coordinator.remove(ws, app)
    }

    /** The current workspace's job for [appId], if any. */
    private fun jobOf(appId: String): AppInstallCoordinator.JobState? =
        _state.value.jobs.firstOrNull { it.app.id == appId }

    /** Dismisses a finished job's indicator row. */
    fun dismissJob(app: LauncherApp) {
        val ws = workspaceId ?: return
        coordinator.dismiss(ws, app.id)
    }

    /** Cancels [app]'s job where technically safe (see [AppInstallCoordinator.cancelIfSafe]). */
    fun cancelJob(app: LauncherApp) {
        val ws = workspaceId ?: return
        coordinator.cancelIfSafe(ws, app.id)
    }

    // ── app launching ───────────────────────────────────────────────────────

    /**
     * Launches [app] in a NEW terminal session of the workspace.
     *
     * The session comes from the existing terminal infrastructure
     * ([RuntimeBinder.openTerminal] / [RuntimeBinder.openRootTerminal]) — the
     * same hop the Commands grid's leaf launch uses, so session ids are
     * allocated by the one manager that never re-opens a live session. A tap is
     * serialized per app id: a double tap cannot open two sessions, while two
     * different apps open two independent windows.
     *
     * The app's own catalog identity (name/icon/launch command) is recorded
     * against the allocated terminal id BEFORE the pty is awaited, so the
     * desktop title and overview card carry the app identity from their first
     * frame. A launch that never reaches a usable pty closes the session it
     * opened and reports failure rather than leaking an orphan shell.
     */
    fun launch(app: LauncherApp) {
        val b = binder ?: run {
            Logger.w(TAG, "launch('${app.id}') ignored: no binder attached")
            return
        }
        val ws = workspaceId ?: return
        if (launchJobs[app.id]?.isActive == true) {
            Logger.d(TAG, "launch('${app.id}') ignored: this app is already launching")
            return
        }
        val key = app.id
        val command = app.launchCommand
        val identity = TerminalWindowIdentity(
            title = app.name,
            appId = app.id,
            iconKey = app.iconKey,
            iconRes = WorkspaceShortcuts.iconFor(app.iconKey),
            command = command
        )
        launchJobs[key] = viewModelScope.launch {
            try {
                _launchStates.update { it + (key to AppLaunchState.Opening) }
                openTerminalForLaunch(
                    binder = b,
                    ws = ws,
                    asRoot = app.launchAsRoot,
                    identity = identity,
                    command = command,
                    label = "App '${app.name}'"
                ) { state -> _launchStates.update { it + (key to state) } }
            } finally {
                launchJobs.remove(key)
            }
        }
    }

    /**
     * 2026-11-13: opens an explicit root-emulated terminal from the Apps
     * toolbar. Root Shell is the interactive counterpart of the guest `sudo`
     * bridge: both use the runtime's `-0` launch, never a fake uid change in
     * the caller's session.
     *
     * [title] is supplied by the composable so the window title is localized.
     */
    fun launchRootShell(title: String) {
        val b = binder ?: run {
            Logger.w(TAG, "Root shell ignored: no binder attached")
            _rootShellState.value = AppLaunchState.Failed("The runtime is not connected yet")
            return
        }
        val ws = workspaceId ?: run {
            _rootShellState.value = AppLaunchState.Failed("No workspace is attached")
            return
        }
        if (rootShellJob?.isActive == true) {
            Logger.d(TAG, "Root shell ignored: a launch is already in flight")
            return
        }
        val identity = TerminalWindowIdentity(
            title = title,
            appId = ROOT_SHELL_APP_ID,
            iconKey = "terminal",
            iconRes = WorkspaceShortcuts.iconFor("terminal"),
            command = null
        )
        _rootShellState.value = AppLaunchState.Opening
        rootShellJob = viewModelScope.launch {
            openTerminalForLaunch(
                binder = b,
                ws = ws,
                asRoot = true,
                identity = identity,
                command = null,
                label = "Root shell"
            ) { state -> _rootShellState.value = state }
        }
    }

    /**
     * Shared terminal-launch choreography for a catalog app and Root Shell.
     * Publishes [AppLaunchState] through [onState]; [label] only shapes the
     * diagnostic log.
     */
    private suspend fun openTerminalForLaunch(
        binder: RuntimeBinder,
        ws: String,
        asRoot: Boolean,
        identity: TerminalWindowIdentity,
        command: String?,
        label: String,
        onState: (AppLaunchState) -> Unit
    ) {
        var openedTerminalId: String? = null
        var handedOff = false
        try {
            val terminalId = if (asRoot) {
                binder.openRootTerminal(ws)
            } else {
                binder.openTerminal(ws)
            }
            openedTerminalId = terminalId
            windowIdentities.record(ws, terminalId, identity)
            val pty = awaitPty(ws, terminalId)
            if (pty == null) {
                Logger.e(TAG, "Opened $ws/$terminalId but its pty never resolved")
                windowIdentities.remove(ws, terminalId)
                runCatching { binder.closeTerminal(ws, terminalId) }
                onState(AppLaunchState.Failed("Terminal session did not start"))
                return
            }
            // No launch command → a plain shell: the terminal is ready for
            // interaction and the user runs the app however they like.
            command?.let { pty.write(it + "\n") }
            handedOff = true
            Logger.i(TAG, "$label sent to terminal $ws/$terminalId")
            onState(AppLaunchState.Launched(terminalId))
            _launchedTerminal.value = terminalId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to launch $label", e)
            val failedTerminalId = openedTerminalId
            if (!handedOff && failedTerminalId != null) {
                windowIdentities.remove(ws, failedTerminalId)
                runCatching { binder.closeTerminal(ws, failedTerminalId) }
            }
            onState(AppLaunchState.Failed(e.message ?: "Could not open a terminal"))
        }
    }

    /** Consumes the one-shot launched-terminal id once the UI focused it. */
    fun consumeLaunchedTerminal() {
        _launchedTerminal.value = null
    }

    /**
     * Requests the app's launch command edit — the context menu's "Edit
     * command" action.
     *
     * Editing a CATALOG app's launch command would mutate project data on the
     * fly, which nothing in this app does (the catalog is the app's own
     * declaration). The honest equivalent the launcher offers: saving the
     * edited command as the workspace's own custom command — the same store
     * the Commands grid's Edit writes to.
     */
    fun editCommandFor(app: LauncherApp): LauncherEditRequest =
        LauncherEditRequest(
            appId = app.id,
            appName = app.name,
            initialCommand = app.launchCommand ?: ""
        )

    /** One "edit command" request for the screen's dialog. */
    data class LauncherEditRequest(
        val appId: String,
        val appName: String,
        val initialCommand: String
    )

    /**
     * Requests an Android home-screen shortcut for [app] — the context menu's
     * "Add to home screen" action. The screen layer owns the platform
     * [android.content.pm.ShortcutManager] call (needs a Context + the
     * workspace's deep-link intent); this layer only emits the request
     * object so it stays testable.
     */
    fun requestHomeShortcut(app: LauncherApp): HomeShortcutRequest =
        HomeShortcutRequest(
            appId = app.id,
            appName = app.name,
            launchCommand = app.launchCommand
        )

    /** One "add to home screen" request for the screen's platform call. */
    data class HomeShortcutRequest(
        val appId: String,
        val appName: String,
        val launchCommand: String?
    )

    /** Clears a terminal launch's settled state (the tile's dismiss). */
    fun clearLaunchState(appId: String) {
        _launchStates.update { it - appId }
    }

    private suspend fun awaitPty(workspaceId: String, terminalId: String): PtySession? {
        var attempts = 0
        var pty = binder?.getPtySession(workspaceId, terminalId)
        while (pty == null && attempts < PTY_ATTEMPTS) {
            delay(PTY_RETRY_MS)
            attempts++
            pty = binder?.getPtySession(workspaceId, terminalId)
        }
        return pty
    }

    private fun stateOf(app: LauncherApp): AppInstallTracker.InstalledState =
        installStates[app.id] ?: AppInstallTracker.InstalledState.UNKNOWN

    override fun onCleared() {
        launchJobs.values.forEach { it.cancel() }
        launchJobs.clear()
        jobsObserver?.cancel()
        super.onCleared()
    }
}

/** Terminal-launch states for one app, mirroring the Commands grid's contract. */
sealed interface AppLaunchState {
    data object Idle : AppLaunchState
    data object Opening : AppLaunchState
    data class Launched(val terminalId: String) : AppLaunchState
    data class Failed(val reason: String) : AppLaunchState
}
