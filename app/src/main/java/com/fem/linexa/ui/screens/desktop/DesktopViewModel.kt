package com.fem.linexa.ui.screens.desktop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.R
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.service.rootfs.DeclaredDesktop
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.GuestEnvironmentResolver
import com.fem.linexa.service.runtime.desktop.TermuxX11
import com.fem.linexa.service.runtime.desktop.X11Environment
import com.fem.linexa.service.runtime.desktop.X11Provider
import com.fem.linexa.service.runtime.desktop.X11SessionManager
import com.fem.linexa.service.runtime.desktop.X11SocketReadiness
import com.fem.linexa.service.runtime.features.DesktopPreset
import com.fem.linexa.service.runtime.features.DesktopRunMode
import com.fem.linexa.service.runtime.features.DesktopTouchMode
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.GuiBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/**
 * ViewModel for Desktop screen — Path B: Embedded VNC client (§4).
 * Manages X server lifecycle inside PRoot and desktop state.
 *
 * §1.29: this ViewModel no longer binds RuntimeService itself. The
 * workspace's single binding lives in the graph-scoped
 * [com.fem.linexa.service.runtime.RuntimeViewModel]; Desktop receives that
 * shared [RuntimeBinder] via [attachBinder] (same session-boundary wiring
 * as Terminal/FileManager/PackageManager, §1.2). The runtime session is
 * started and stopped by the graph, never by a sub-screen — so
 * [startDesktop] assumes the session is already live and only drives the
 * X server, and [stopDesktop] stops the X server only, never the session.
 *
 * §1.114: starting the desktop now means **two** guest daemons, not one.
 * `Xvnc :1` is only an X *server*; with no client connected it serves the bare
 * root window, which is why every hop could succeed and still leave the user
 * looking at an empty grey screen. [startDesktop] therefore probes for a
 * desktop session ([DesktopSessionProvisioner]) *before* starting anything,
 * refuses with [DesktopUiState.NoSession] when the workspace has no X client at
 * all (Linexa deliberately installs no desktop environment), and starts the
 * session as its own supervised daemon with `DISPLAY=:1` once the X server is
 * listening. Re-entering the screen while both daemons are alive reconnects
 * instead of offering a misleading "Ready" placeholder.
 */
@HiltViewModel
class DesktopViewModel @Inject constructor(
    application: Application,
    private val workspaceRootFsMetadata: WorkspaceRootFsMetadata,
    /**
     * §1.166 — the existing workspace debug trace, injected for **diagnostics only**.
     *
     * Why this ViewModel needs it: §16.5 of the forensic audit asks what geometry
     * Xvnc is actually started with, and the answer was already computed here and
     * already logged — but only through [Logger], whose two sinks (logcat, restricted
     * by Android to the calling UID, and the app-private log file) are both
     * unreachable on an unrooted device without adb. That is the same gap §1.165
     * closed for the render counters, and this is the same fix applied to the one
     * remaining unreadable value, using the mechanism the app already has rather than
     * a second one.
     *
     * [RuntimeTrace] is the right vehicle because the Workspace → Debug panel already
     * renders and copies it, it is already `@Singleton` and injectable, and it is
     * already gated on `DebugCapture` **inside** `record` — so with debug info off
     * this costs one string interpolation and one function call per desktop start,
     * on a path that then spawns a PRoot and polls a TCP port for up to five seconds.
     * Every terminal and runtime hop in the app is traced this way already
     * (`TerminalViewModel`, `PRootContainerRuntime`); the desktop path was simply
     * never wired in.
     */
    private val runtimeTrace: RuntimeTrace,
    /**
     * Desktop Modes: the process-lifetime record of which loopback port each
     * workspace's desktop runs on. Needed because the ViewModel is scoped to
     * the Desktop NavBackStackEntry (§1.29) while the daemons it starts are
     * scoped to the workspace — see [DesktopPortRegistry]'s KDoc for why the
     * per-workspace dynamic port needs a process-lifetime home.
     */
    private val portRegistry: DesktopPortRegistry,
    /**
     * Transformation spec §13: the runtime-owned X11 lifecycle — per-workspace
     * display allocation, host-side socket readiness and the observable
     * X11 state machine. The Desktop screen and the diagnostics surface read
     * the same states through this manager.
     */
    private val x11SessionManager: X11SessionManager,
    /** Workspace path layout (§7): resolves the rootfs path for host-side
     *  X-socket readiness checks. */
    private val fileManager: WorkspaceFileManager
) : AndroidViewModel(application) {

    private var binder: RuntimeBinder? = null
    private var currentWorkspaceId: String? = null
    private var observeJob: Job? = null

    /**
     * Desktop Modes: the run-mode configuration the screen was given
     * ([configure]), applied at start time. Defaults to the pre-Desktop-Modes
     * behaviour (VNC, auto geometry, no password) so a screen entered before
     * its settings arrive behaves exactly as it always did.
     */
    private var desktopConfig: FeatureSettings = FeatureSettings()

    private val _desktopState = MutableStateFlow<DesktopUiState>(DesktopUiState.Unbound)
    val desktopState: StateFlow<DesktopUiState> = _desktopState.asStateFlow()

    /**
     * Desktop Backends: the X11 readiness lifecycle (§13), observable by the
     * screen so the X11 path can show Starting/Ready distinctly from the
     * ordinary desktop states. Reset to IDLE whenever the desktop stops.
     */
    private val _x11State = MutableStateFlow(X11SessionState.IDLE)
    val x11State: StateFlow<X11SessionState> = _x11State.asStateFlow()

    /**
     * Attaches the workspace's shared binder (the graph-scoped
     * [com.fem.linexa.service.runtime.RuntimeViewModel]'s [RuntimeBinder]).
     * Safe to call repeatedly — the same binder re-attaches idempotently;
     * a binder arriving after first composition (late connect) swaps in a
     * new observation of the live session states.
     */
    fun attachBinder(workspaceId: String, binder: RuntimeBinder) {
        if (this.currentWorkspaceId == workspaceId && this.binder === binder) {
            Logger.d(TAG, "Re-attach of same binder")
            observeRuntimeState()
            resumeIfAlreadyRunning(workspaceId, binder)
            return
        }
        Logger.d(TAG, "Attaching workspaceId=$workspaceId binder=${binder::class.simpleName}")
        this.currentWorkspaceId = workspaceId
        this.binder = binder
        observeJob?.cancel()
        observeRuntimeState()
        // Immediate sync: StateFlow always exposes the current value, so
        // a session already running before the Desktop opens converges
        // without waiting for the next emission.
        desktopStateFrom(binder.sessionStates.value[workspaceId])
        resumeIfAlreadyRunning(workspaceId, binder)
    }

    /**
     * §1.114: reconnect to a desktop that is *already running*.
     *
     * This ViewModel is scoped to the Desktop `NavBackStackEntry`, so leaving
     * and re-entering the screen constructs a fresh instance starting at
     * [DesktopUiState.Unbound]. Before §1.114 that meant a live desktop was
     * presented as the "Ready — Start Desktop" placeholder: the user's only way
     * back to their session was a button that killed it (`pkill Xvnc`) and
     * started a new one, losing every open window. The daemon host already
     * knows whether both daemons are alive ([RuntimeBinder.isDaemonAlive]),
     * which the desktop path simply never consulted.
     *
     * Both daemons must be alive to call it running: an Xvnc with no session
     * left is the grey-screen state §1.114 exists to remove, so it is treated
     * as not-running and a fresh Start relaunches the pair.
     */
    private fun resumeIfAlreadyRunning(workspaceId: String, binder: RuntimeBinder) {
        val state = _desktopState.value
        if (state is DesktopUiState.Running || state is DesktopUiState.Starting ||
            state is DesktopUiState.WebRunning || state is DesktopUiState.InstallingDependencies
        ) {
            return
        }
        // Desktop Modes: the resume path is mode-aware. A live web daemon
        // reattaches to its URL; a live Xvnc pair reattaches to the VNC
        // stream through the port the registry recorded at start time.
        if (binder.isDaemonAlive(workspaceId, DAEMON_WEB)) {
            val webPort = portRegistry.webPortFor(workspaceId, desktopConfig.desktopWebPort)
            Logger.i(TAG, "Desktop web mode already running for $workspaceId — reattaching to port $webPort")
            _desktopState.value = DesktopUiState.WebRunning(workspaceId, DesktopWebMode.buildUrl(webPort))
            return
        }
        val xvncAlive = binder.isDaemonAlive(workspaceId, DAEMON_XVNC)
        val sessionAlive = binder.isDaemonAlive(workspaceId, DesktopSessionProvisioner.DAEMON_NAME)
        // §13: a termux-x11 helper + session pair is a running X11 desktop too —
        // and a live pair IS an X11-ready stack regardless of which backend
        // setting started it (the X server and its socket are up).
        val helperAlive = binder.isDaemonAlive(workspaceId, TermuxX11.DAEMON_NAME)
        val xServerAlive = xvncAlive || helperAlive
        if (xServerAlive && sessionAlive) {
            Logger.i(TAG, "§1.114 desktop already running for $workspaceId — reattaching without restart")
            if (desktopConfig.desktopBackend == GuiBackend.X11) {
                _x11State.value = X11SessionState.X11_READY
                x11SessionManager.setState(workspaceId, X11SessionState.X11_READY)
            }
            if (helperAlive) {
                // §13: an external-transport desktop reattaches to the status
                // panel, not to an RFB viewer that has nothing to connect to.
                _desktopState.value = DesktopUiState.RunningExternal(workspaceId, X11Provider.TERMUX_X11.label)
            } else {
                _desktopState.value = DesktopUiState.Running(workspaceId)
            }
        }
    }

    private fun observeRuntimeState() {
        val workspaceId = currentWorkspaceId ?: return
        val runtimeBinder = binder ?: return
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            runtimeBinder.sessionStates.collect { states ->
                desktopStateFrom(states[workspaceId])
            }
        }
    }

    /**
     * Reflects the underlying runtime state in the desktop UI state, but
     * never clobbers an in-flight [Starting] launch or an X server failure
     * with the mere presence of a live session: the X server reaching
     * [Running] is decided by the port probe in [startDesktop], not by the
     * runtime session state (the session can stay up while the desktop is
     * stopped, §1.2 — the graph owns the session, the sub-screen owns the
     * X server).
     */
    private fun desktopStateFrom(runtimeState: RuntimeState?) {
        val state = _desktopState.value
        when (runtimeState) {
            is RuntimeState.Stopped -> {
                if (state is DesktopUiState.Running ||
                    state is DesktopUiState.RunningExternal ||
                    state is DesktopUiState.Starting ||
                    state is DesktopUiState.WebRunning ||
                    state is DesktopUiState.Connected ||
                    state is DesktopUiState.X11Failed
                ) {
                    _desktopState.value = DesktopUiState.Stopped
                    _x11State.value = X11SessionState.IDLE
                    currentWorkspaceId?.let { ws ->
                        x11SessionManager.clearState(ws)
                        x11SessionManager.releaseDisplay(ws)
                        // 2026-09-05 (restart-state fix): parity with stopDesktop.
                        // A workspace-level stop used to release the display and
                        // clear the X11 state but keep the desktop's recorded VNC
                        // port, so the next start skipped its preferred port
                        // (allocating 5902 for no reason) and the resume path
                        // kept answering a port nothing was listening on.
                        portRegistry.clear(ws)
                    }
                }
            }
            is RuntimeState.Error -> {
                _desktopState.value = DesktopUiState.Error(runtimeState.reason)
            }
            else -> {
                // Desktop failure-surfacing (2026-08): while the user is looking
                // at a live desktop, an unexpected death of one of the desktop's
                // own daemons (Xvnc, the session, the termux-x11 helper, the web
                // server) is surfaced as an honest desktop Error with the
                // runtime's detail — instead of a black viewer whose reconnect
                // budget silently expired. Narrowly scoped: other daemons'
                // failures degrade the workspace, not the desktop, and must not
                // interrupt a live session. A restart clears the degradation
                // (startDaemon reports healthy), so Start recovers cleanly.
                DesktopHealthGate.failureDetail(runtimeState, WATCHED_DESKTOP_DAEMONS, state)
                    ?.let { reason ->
                        Logger.w(TAG, "Desktop daemon failure surfaced: $reason")
                        _desktopState.value = DesktopUiState.Error(reason)
                        return
                    }
                // 2026-09-05 (state-machine correctness): the same death during
                // an in-flight START must not leave the X11 machine in
                // X11_STARTING until the port poll's full budget expires — the
                // X server is already dead and never will listen. Flip the X11
                // state to X11_FAILED immediately (the start flow's own
                // liveness-aware poll reports the same fact with the daemon log
                // tail a moment later; both writes are idempotent).
                if (_x11State.value == X11SessionState.X11_STARTING) {
                    DesktopHealthGate.startingFailureDetail(runtimeState, WATCHED_XSERVER_DAEMONS)
                        ?.let { reason ->
                            Logger.w(TAG, "X11 server daemon died during start: $reason")
                            _x11State.value = X11SessionState.X11_FAILED
                            currentWorkspaceId?.let { ws ->
                                x11SessionManager.setState(ws, X11SessionState.X11_FAILED)
                            }
                        }
                }
                if (state is DesktopUiState.Unbound) {
                    _desktopState.value = DesktopUiState.Connected
                }
            }
        }
    }

    fun detachBinder() {
        binder = null
        currentWorkspaceId = null
        observeJob?.cancel()
        observeJob = null
        _desktopState.value = DesktopUiState.Unbound
    }

    /**
     * Desktop Modes: receive the workspace's resolved feature settings from
     * the navigation graph (the same [FeatureSettings] the Features tab edits).
     *
     * Kept out of the constructor on purpose: the settings live in Room
     * attributes and arrive through the workspace graph's observed state, so
     * re-injecting the repository here would open a second observation of the
     * same row that could disagree with the one the user is looking at. The
     * screen calls this on every settings change, BEFORE any start/connect
     * effect can read it — placement is part of the contract, see
     * [com.fem.linexa.ui.screens.desktop.DesktopScreen].
     */
    fun configure(settings: FeatureSettings) {
        desktopConfig = settings
    }

    /**
     * Desktop Touch work (2026-08): how the viewer maps touches to the remote
     * pointer. Read from the same settings the start path consumes, and read
     * *live* by the screen on every recomposition — unlike the server-side
     * settings, a touch-mode change takes effect on the next touch without
     * restarting the desktop, because it changes nothing on the guest side.
     */
    val touchMode: DesktopTouchMode
        get() = desktopConfig.desktopTouchMode

    fun startDesktop(workspaceId: String, backendOverride: GuiBackend? = null) {
        // Phase 2C instrumentation: T0 = desktop start requested. One line, no behavior change.
        Logger.i(TAG, "DesktopTiming T0 startDesktop ws=$workspaceId wallMs=${System.currentTimeMillis()}")
        val workspaceIdState = _desktopState.value
        if (workspaceIdState is DesktopUiState.Running || workspaceIdState is DesktopUiState.Starting ||
            workspaceIdState is DesktopUiState.WebRunning
        ) {
            Logger.w("DesktopViewModel", "Desktop already active for $workspaceId")
            return
        }

        currentWorkspaceId = workspaceId
        val runtimeBinder = binder
        if (runtimeBinder == null) {
            _desktopState.value = DesktopUiState.Error(getApplication<Application>().getString(R.string.desktop_not_connected))
            Logger.e("DesktopViewModel", "startDesktop called without a RuntimeBinder for $workspaceId")
            return
        }

        _desktopState.value = DesktopUiState.Starting

        viewModelScope.launch {
            if (!runtimeBinder.hasActiveSession(workspaceId)) {
                _desktopState.value = DesktopUiState.Error(getApplication<Application>().getString(R.string.desktop_session_not_running))
                Logger.e("DesktopViewModel", "Session not running for $workspaceId — start it from the workspace screen")
                return@launch
            }

            // Desktop Modes: the run mode decides what "desktop" even means
            // here — an X server plus session (VNC), or a guest web server
            // rendered in the platform WebView. Both are supervised guest
            // daemons on the same session; neither can be live while the
            // other starts, because the state machine above gates on the
            // shared Starting state.
            //
            // Desktop Backends: within the VNC run mode, the GUI backend
            // picks the X11 lifecycle (preferred — X-socket readiness + X11
            // environment) or the compatibility VNC path. The override is
            // the §28 fallback: an X11 failure offers the VNC path explicitly
            // instead of silently switching backends under the user.
            when (desktopConfig.desktopMode) {
                DesktopRunMode.WEBVIEW -> startWebDesktop(workspaceId, runtimeBinder)
                DesktopRunMode.VNC -> when (backendOverride ?: desktopConfig.desktopBackend) {
                    GuiBackend.X11 -> startX11Desktop(workspaceId, runtimeBinder)
                    GuiBackend.VNC -> startVncDesktop(workspaceId, runtimeBinder)
                }
            }
        }
    }

    /**
     * Desktop Modes: the VNC run mode — the pre-Desktop-Modes startup sequence
     * (probe → install if needed → session check → Xvnc → session), with three
     * per-workspace upgrades:
     *
     * - **dynamic loopback port** ([DesktopPortRegistry.allocateVnc]) instead
     *   of the shared constant 5901;
     * - **optional VNC authentication** ([DesktopVncAuthProvisioner]) instead
     *   of an unconditional `-SecurityTypes None`;
     * - **resolution preset** ([FeatureSettings.desktopResolution]) instead of
     *   the display-derived geometry alone.
     */
    private suspend fun startVncDesktop(workspaceId: String, runtimeBinder: RuntimeBinder) {
        try {
                // Read once, before the probe, because the declared session
                // binary is part of the probe command (§1.118). This is a
                // host-side descriptor read, not a guest exec.
                val declared = workspaceRootFsMetadata.of(workspaceId)?.desktop
                // 2026-09-05 (/home/tiny → /home/linexa migration): the VNC
                // passwd file lives under the workspace's RESOLVED guest home —
                // the same value the runtime passes to ensureGuestUser — so a
                // declared non-standard home can never grow a second .vnc tree.
                val guestHome = GuestEnvironmentResolver
                    .resolve(workspaceRootFsMetadata.of(workspaceId)).home
                if (declared != null && !declared.enabled) {
                    // An explicit `enabled: false` is the author stating the
                    // image has no desktop. Probing anyway is still correct (the
                    // user may have installed one since), so this only logs —
                    // but it explains a subsequent NoSession without the user
                    // having to guess.
                    Logger.i(TAG, "§1.118 $workspaceId declares desktop.enabled=false — probing anyway")
                }

                val probe = runtimeBinder.execCommand(
                    workspaceId,
                    DesktopStartupProbe.command(declared?.session)
                )
                val discovery = DesktopStartupProbe.parse(probe.output)
                if (!discovery.wellFormed) {
                    // Not a failure by itself — `xvncFound` is already false, so
                    // the auto-install path below runs exactly as it would for a
                    // genuinely missing Xvnc. Logged because "the guest returned
                    // something unexpected" and "Xvnc is absent" lead to the same
                    // place and should not be indistinguishable afterwards.
                    Logger.w(
                        TAG,
                        "§16.3.1 combined probe output was malformed for $workspaceId " +
                            "(exit=${probe.exitCode}) — treating Xvnc as missing"
                    )
                }

                // The session probe's stdout, as captured by the combined exec.
                // Replaced below if the auto-install path runs, so that a
                // session manager pulled in as an install dependency is still
                // seen — preserving the pre-§16.3.1 ordering, where the session
                // was always probed after the install completed.
                var sessionProbeOutput = discovery.sessionProbeOutput

                if (!discovery.xvncFound) {
                    val installed = autoInstallXvnc(runtimeBinder, workspaceId)
                    if (!installed) return
                    // Re-probe the session after the install, exactly as the
                    // pre-§16.3.1 order did: the install may have brought in an
                    // X client of its own, and the earlier reading predates it.
                    sessionProbeOutput = runtimeBinder.execCommand(
                        workspaceId,
                        DesktopSessionProvisioner.detectSessionCommand(declared?.session)
                    ).output
                }

                // §1.114: resolve the desktop session BEFORE starting the X
                // server. Xvnc with no client is the grey-screen state this
                // decision exists to remove, so a workspace that has no X
                // client at all must be told so instead of being handed a
                // running-but-empty desktop. Probing first also means nothing
                // is started that would then have to be torn down.
                var sessionPath = sessionPathFrom(declared, sessionProbeOutput)
                if (sessionPath == null) {
                    // Desktop Provisioning (§16): a chosen preset is installed
                    // through the guest's own package manager before giving
                    // up — the old behaviour (NoSession with no way forward)
                    // stays only when no preset is selected or the install
                    // could not produce a session.
                    val presetSession = installDesktopPreset(declared?.session, runtimeBinder, workspaceId)
                    if (presetSession == null) {
                        _desktopState.value = DesktopUiState.NoSession
                        Logger.w(
                            TAG,
                            "§1.114 no desktop session found in $workspaceId — refusing to start an empty X server"
                        )
                        return
                    }
                    sessionPath = presetSession
                }

                // §13.5 (transformation spec): per-workspace cleanup of any
                // stale server of THIS workspace only — the old
                // `pkill Xvnc || true` was host-global (PRoot does not
                // isolate pids), so starting workspace B's desktop killed
                // workspace A's Xvnc. Daemon keys are (workspace, name).
                runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }

                // Desktop Modes: per-workspace loopback port and optional
                // authentication. The port is allocated before the daemon so
                // a fallback port is recorded even if the start then fails —
                // the record is the resume path's only memory of where the
                // server ended up.
                val port = portRegistry.allocateVnc(workspaceId)
                // §7: the display is workspace-scoped too — one workspace can
                // no longer address another's X server.
                val display = x11SessionManager.allocateDisplay(
                    workspaceId,
                    fileManager.getRootfsPath(workspaceId).absolutePath
                )
                // P11: a saved password that cannot be decrypted is not "no password" —
                // fail closed instead of starting an unauthenticated server.
                if (DesktopVncAuthProvisioner.authRequirement(
                        desktopConfig.desktopVncPassword,
                        desktopConfig.desktopVncPasswordUnreadable
                    ) == VncAuthRequirement.BlockedUnreadable
                ) {
                    _desktopState.value = DesktopUiState.Error(
                        getApplication<Application>().getString(R.string.desktop_vnc_password_unreadable)
                    )
                    Logger.e(TAG, "Xvnc not started for $workspaceId: saved VNC password unreadable (fail-closed)")
                    return
                }
                val requestedPassword = desktopConfig.desktopVncPassword.takeIf { it.isNotEmpty() }
                val provisioned = requestedPassword != null && run {
                    val result = runtimeBinder.execCommand(
                        workspaceId,
                        DesktopVncAuthProvisioner.provisionCommand(requestedPassword, guestHome)
                    )
                    if (result.exitCode != 0) {
                        // Pre-release audit fix (2026-09-02): the old behaviour
                        // degraded to an unauthenticated start here. That is a
                        // security regression the user never asked for: with
                        // Android's lack of per-app localhost isolation, any
                        // local app could attach to the desktop with no
                        // credential. Fail CLOSED instead — surface the error
                        // and start nothing. The user can install vncpasswd in
                        // the guest or clear the configured password.
                        Logger.w(
                            TAG,
                            "VNC password provisioning failed for $workspaceId " +
                                "(exit=${result.exitCode}) — refusing to start unauthenticated"
                        )
                    }
                    result.exitCode == 0
                }
                if (requestedPassword != null && !provisioned) {
                    _desktopState.value = DesktopUiState.Error(
                        getApplication<Application>().getString(R.string.desktop_vnc_auth_provision_failed)
                    )
                    Logger.e(TAG, "Xvnc not started for $workspaceId: VNC auth provisioning failed (fail-closed)")
                    return
                }
                val securityArgs = DesktopVncAuthProvisioner.securityArgs(provisioned, guestHome)
                // Phase 07 (OPT-SEC-004, Step B2): fail closed BEFORE any
                // process exists when the server could be neither restricted
                // to loopback (-localhost unsupported on this guest's Xvnc)
                // nor authenticated (no password provisioned). With Android's
                // lack of per-app localhost isolation that combination is
                // reachable by every local app with no credential.
                if (DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                        discovery.localhostSupported,
                        securityArgs
                    )
                ) {
                    _desktopState.value = DesktopUiState.Error(
                        getApplication<Application>().getString(R.string.desktop_vnc_loopback_unsupported)
                    )
                    Logger.e(
                        TAG,
                        "Xvnc not started for $workspaceId: cannot restrict to loopback and no VNC password (fail-closed)"
                    )
                    return
                }

                val started = runtimeBinder.startDaemon(
                    workspaceId,
                    DAEMON_XVNC,
                    xServerCommand(workspaceId, port, securityArgs, display, discovery.localhostSupported)
                )
                if (!started) {
                    _desktopState.value = DesktopUiState.Error(
                        getApplication<Application>().getString(R.string.desktop_xvnc_start_failed)
                    )
                    Logger.e("DesktopViewModel", "Xvnc daemon failed to start for $workspaceId")
                    return
                }

                val portReady = waitForVncPort(
                    host = "127.0.0.1", port = port, timeoutMs = 5000,
                    // 2026-09-05: a dead Xvnc must fail the wait NOW, not after
                    // the full budget — the poll stays the TCP readiness gate;
                    // this only short-circuits it to the honest failure.
                    isServerAlive = { runtimeBinder.isDaemonAlive(workspaceId, DAEMON_XVNC) }
                )
                // Phase 2C instrumentation: T2 = VNC port answered (X server accepting TCP). One line, no behavior change.
                if (portReady) Logger.i(
                    TAG,
                    "DesktopTiming T2 vncPortReady ws=$workspaceId port=$port wallMs=${System.currentTimeMillis()}"
                )
                if (!portReady) {
                    // Reason FIRST (it consults liveness), then the cleanup.
                    val reason = xvncStartFailureReason(workspaceId, port, runtimeBinder)
                    runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                    _desktopState.value = DesktopUiState.Error(reason)
                    Logger.e("DesktopViewModel", "Timed out waiting for VNC port $port")
                    return
                }

                // Phase 07 (OPT-SEC-004, Step B3): the port answering is not
                // the same fact as "loopback-only" — verify the server's
                // actual bind address now that the listener exists. A
                // non-loopback listener stops the server here, before any
                // session attaches.
                vncBindViolationMessage(workspaceId, port, runtimeBinder)?.let { violationMessage ->
                    runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                    _desktopState.value = DesktopUiState.Error(violationMessage)
                    return
                }

                // §1.114: the session is started only once the X server is
                // actually listening — an X client that starts first finds no
                // display, exits, and takes its own daemon host down with it.
                // §14: the daemon command carries the full X11 environment
                // (DISPLAY + TMPDIR + XKB_CONFIG_ROOT) explicitly.
                val sessionStarted = runtimeBinder.startDaemon(
                    workspaceId,
                    DesktopSessionProvisioner.DAEMON_NAME,
                    DesktopSessionProvisioner.daemonCommand(
                        sessionPath,
                        display,
                        extraEnv = mapOf(
                            "TMPDIR" to "/tmp",
                            "XKB_CONFIG_ROOT" to "/usr/share/X11/xkb"
                        )
                    )
                )
                if (!sessionStarted) {
                    // The X server is up but nothing will draw into it. Stop it
                    // rather than leave a grey screen behind a "Running" state.
                    runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                    _desktopState.value = DesktopUiState.Error(
                        getApplication<Application>().getString(
                            R.string.desktop_session_start_failed,
                            DesktopSessionProvisioner.sessionDisplayName(sessionPath)
                        )
                    )
                    Logger.e(TAG, "§1.114 desktop session '$sessionPath' failed to start for $workspaceId")
                    return
                }

                _desktopState.value = DesktopUiState.Running(workspaceId)
                Logger.i(
                    TAG,
                    "§1.114 desktop up for $workspaceId (Xvnc + ${DesktopSessionProvisioner.sessionDisplayName(sessionPath)}, " +
                        "port=$port, auth=${if (provisioned) "VncAuth" else "None"})"
                )
            } catch (e: Exception) {
                _desktopState.value = DesktopUiState.Error(e.message ?: getApplication<Application>().getString(R.string.desktop_unknown_error))
                Logger.e("DesktopViewModel", "X server start exception", e)
            }
    }

    /**
     * Desktop Backends → transformation spec §11/§13: the X11 lifecycle with
     * a REAL provider selection. Two providers implement the X11 backend:
     *
     * - **[X11Provider.TERMUX_X11]** — the preferred local transport. The
     *   guest runs the `termux-x11` helper as its own supervised daemon; the
     *   helper connects to the termux-x11 Android app over binder and
     *   creates the display's unix socket inside the workspace rootfs, which
     *   Linexa watches HOST-side (one stat per poll — the same integration
     *   shape Tiny Container uses with its embedded server). The app renders
     *   the desktop natively. Requires the app installed AND the helper
     *   binary in the guest — both detected here, never assumed.
     * - **[X11Provider.XVNC]** — the always-available compatibility path:
     *   the guest's own Xvnc (a complete X11 server) serves the workspace's
     *   display and Linexa's embedded RFB viewer renders it.
     *
     * Shared upgrades over the pre-transformation flow, per §7/§13/§14:
     *
     * 1. **per-workspace display** ([X11SessionManager.allocateDisplay]) —
     *    the shared `":1"` constant is gone; two workspaces can no longer
     *    address the wrong server;
     * 2. **host-side socket readiness** — the X unix socket lives at
     *    `{rootfs}/tmp/.X11-unix/X<n>`, so readiness costs one stat per
     *    poll, not one PRoot launch per poll;
     * 3. **guest env** (§14) — the session daemon carries DISPLAY/TMPDIR/
     *    XKB_CONFIG_ROOT explicitly, plus the idempotent profile script for
     *    interactive shells;
     * 4. **explicit failure state** — an X11 failure lands in
     *    [DesktopUiState.X11Failed] (never a silent backend switch) and the
     *    screen offers the VNC fallback (§28);
     * 5. **no host-global pkill** — the old `pkill Xvnc` killed EVERY
     *    workspace's server (PRoot does not isolate pids); daemons are
     *    stopped by their own (workspace, name) key only.
     */
    private suspend fun startX11Desktop(workspaceId: String, runtimeBinder: RuntimeBinder) {
        fun fail(reason: String) {
            _desktopState.value = DesktopUiState.X11Failed(reason)
            _x11State.value = X11SessionState.X11_FAILED
            x11SessionManager.setState(workspaceId, X11SessionState.X11_FAILED)
        }
        _x11State.value = X11SessionState.X11_STARTING
        x11SessionManager.setState(workspaceId, X11SessionState.X11_STARTING)
        try {
            val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
            // 2026-09-05 (/home/tiny → /home/linexa migration): the VNC passwd
            // file lives under the workspace's RESOLVED guest home — the same
            // value the runtime passes to ensureGuestUser.
            val guestHome = GuestEnvironmentResolver
                .resolve(workspaceRootFsMetadata.of(workspaceId)).home
            // Pre-release audit fix (2026-09-02, finding B4): stop any stale
            // Xvnc daemon BEFORE allocating the display — the VNC-mode path
            // already had this order; here the allocation used to run first,
            // so removeStaleSocket could delete the socket of a still-live
            // Xvnc from a previous VNC-mode session.
            runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
            val display = x11SessionManager.allocateDisplay(workspaceId, rootfsPath)

            // Provider selection: deterministic and traced (§28 — the user
            // must be able to see which transport the desktop is using).
            val appInstalled = isTermuxX11AppInstalled()
            val helperFound = appInstalled && runtimeBinder.execCommand(
                workspaceId,
                TermuxX11.HELPER_PROBE_COMMAND
            ).output.lineSequence().any { it.isNotBlank() }
            val provider = if (appInstalled && helperFound) X11Provider.TERMUX_X11 else X11Provider.XVNC
            runtimeTrace.record(
                workspaceId,
                "X11 provider=${provider.label} (app=$appInstalled, helper=$helperFound, display=$display)"
            )

            // §14: the guest environment — the idempotent profile script is
            // written by the SAME exec as the combined discovery probe
            // (2026-09-05 startup-parity fix): both are idempotent, neither
            // reads anything the other writes, and merging them removes one
            // full PRoot launch (~⅓ of a second) from every X11-path start —
            // the same class of cold-start redundancy §16.3.1 removed when it
            // merged the two `which` probes. Fail-open: the desktop can still
            // start without the profile script (only the terminal-launched X
            // clients lose their DISPLAY default).
            val declared = workspaceRootFsMetadata.of(workspaceId)?.desktop
            val probe = runtimeBinder.execCommand(
                workspaceId,
                "${X11Environment.profileScriptCommand(display)}; " +
                    DesktopStartupProbe.command(declared?.session)
            )
            val discovery = DesktopStartupProbe.parse(probe.output)
            var sessionProbeOutput = discovery.sessionProbeOutput

            if (provider == X11Provider.XVNC && !discovery.xvncFound) {
                val installed = autoInstallXvnc(runtimeBinder, workspaceId)
                if (!installed) {
                    fail(getApplication<Application>().getString(R.string.desktop_xvnc_missing))
                    return
                }
                sessionProbeOutput = runtimeBinder.execCommand(
                    workspaceId,
                    DesktopSessionProvisioner.detectSessionCommand(declared?.session)
                ).output
            }

            var sessionPath = sessionPathFrom(declared, sessionProbeOutput)
            if (sessionPath == null) {
                val presetSession = installDesktopPreset(declared?.session, runtimeBinder, workspaceId)
                if (presetSession == null) {
                    _desktopState.value = DesktopUiState.NoSession
                    _x11State.value = X11SessionState.X11_FAILED
                    x11SessionManager.setState(workspaceId, X11SessionState.X11_FAILED)
                    return
                }
                sessionPath = presetSession
            }

            when (provider) {
                X11Provider.TERMUX_X11 -> {
                    // The helper IS the X server (bridging to the app). Own
                    // daemon key — no pkill, no cross-workspace effects.
                    val helperStarted = runtimeBinder.startDaemon(
                        workspaceId,
                        TermuxX11.DAEMON_NAME,
                        TermuxX11.helperDaemonCommand(display)
                    )
                    if (!helperStarted) {
                        fail(getApplication<Application>().getString(R.string.desktop_xvnc_start_failed))
                        return
                    }
                    val socketReady = x11SessionManager.awaitSocketReady(rootfsPath, display)
                    if (!socketReady) {
                        runCatching { runtimeBinder.stopDaemon(workspaceId, TermuxX11.DAEMON_NAME) }
                        fail(
                            getApplication<Application>().getString(
                                R.string.desktop_x11_socket_timeout,
                                X11SocketReadiness.guestSocketPath(display)
                            )
                        )
                        Logger.e(TAG, "termux-x11 socket never appeared for $workspaceId ($display)")
                        return
                    }
                    launchTermuxX11App()
                }
                X11Provider.XVNC -> {
                    // 2026-09-05: the stale-server stop ran once at the top of
                    // this function (before the display allocation); the second
                    // call here was a duplicate — nothing between the two can
                    // start a daemon for this workspace.

                    val port = portRegistry.allocateVnc(workspaceId)
                    // P11: same fail-closed gate as the VNC run mode above.
                    if (DesktopVncAuthProvisioner.authRequirement(
                            desktopConfig.desktopVncPassword,
                            desktopConfig.desktopVncPasswordUnreadable
                        ) == VncAuthRequirement.BlockedUnreadable
                    ) {
                        fail(getApplication<Application>().getString(R.string.desktop_vnc_password_unreadable))
                        return
                    }
                    val requestedPassword = desktopConfig.desktopVncPassword.takeIf { it.isNotEmpty() }
                    val provisioned = requestedPassword != null && run {
                        val result = runtimeBinder.execCommand(
                            workspaceId,
                            DesktopVncAuthProvisioner.provisionCommand(requestedPassword, guestHome)
                        )
                        if (result.exitCode != 0) {
                            // Pre-release audit fix (2026-09-02): log the
                            // degradation in the X11-provider path too (it was
                            // silent here), and fail CLOSED below like the VNC
                            // path — a configured password must never yield an
                            // unauthenticated server.
                            Logger.w(
                                TAG,
                                "VNC password provisioning failed for $workspaceId " +
                                    "(exit=${result.exitCode}) — refusing to start unauthenticated"
                            )
                        }
                        result.exitCode == 0
                    }
                    if (requestedPassword != null && !provisioned) {
                        fail(getApplication<Application>().getString(R.string.desktop_vnc_auth_provision_failed))
                        return
                    }
                    val securityArgs = DesktopVncAuthProvisioner.securityArgs(provisioned, guestHome)
                    // Phase 07 (OPT-SEC-004, Step B2): same fail-closed gate
                    // as the VNC run mode — an Xvnc that can be neither
                    // restricted to loopback nor authenticated starts nothing.
                    if (DesktopVncAuthProvisioner.unauthenticatedStartBlocked(
                            discovery.localhostSupported,
                            securityArgs
                        )
                    ) {
                        fail(getApplication<Application>().getString(R.string.desktop_vnc_loopback_unsupported))
                        Logger.e(
                            TAG,
                            "Xvnc not started for $workspaceId: cannot restrict to loopback and no VNC password (fail-closed)"
                        )
                        return
                    }

                    val started = runtimeBinder.startDaemon(
                        workspaceId,
                        DAEMON_XVNC,
                        xServerCommand(workspaceId, port, securityArgs, display, discovery.localhostSupported)
                    )
                    if (!started) {
                        fail(getApplication<Application>().getString(R.string.desktop_xvnc_start_failed))
                        return
                    }

                    val portReady = waitForVncPort(
                        host = "127.0.0.1", port = port, timeoutMs = 5000,
                        // 2026-09-05: a dead Xvnc must fail the wait NOW, not
                        // after the full budget. The poll stays the TCP
                        // readiness gate; this only short-circuits to failure.
                        isServerAlive = { runtimeBinder.isDaemonAlive(workspaceId, DAEMON_XVNC) }
                    )
                    // Phase 2D live-run correction: the Phase 2C T2 marker only existed in
                    // startVncDesktop, so X11-backend workspaces (this one) emitted no T2.
                    // Mirror of the VNC-path marker; logging-only, no behavior change.
                    if (portReady) Logger.i(
                        TAG,
                        "DesktopTiming T2 vncPortReady ws=$workspaceId port=$port wallMs=${System.currentTimeMillis()}"
                    )
                    if (!portReady) {
                        // Reason FIRST (it consults liveness), then the cleanup.
                        val reason = xvncStartFailureReason(workspaceId, port, runtimeBinder)
                        runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                        // 2026-09-05 (Xvnc exit-1 forensics): when the server
                        // died mid-start, the timeout is the wrong message —
                        // the X11 fatal error is in the daemon log the runtime
                        // already keeps host-side. Surface it.
                        fail(reason)
                        return
                    }

                    // Phase 07 (OPT-SEC-004, Step B3): verify the actual bind
                    // address (listener now exists); a non-loopback listener
                    // stops the server before any session attaches.
                    vncBindViolationMessage(workspaceId, port, runtimeBinder)?.let { violationMessage ->
                        runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                        fail(violationMessage)
                        return
                    }

                    // §13: X-socket readiness — the fact that distinguishes
                    // "an X server is accepting clients" from "a TCP port is
                    // open", now one host-side stat per poll.
                    val socketReady = x11SessionManager.awaitSocketReady(rootfsPath, display)
                    if (!socketReady) {
                        runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                        fail(
                            getApplication<Application>().getString(
                                R.string.desktop_x11_socket_timeout,
                                X11SocketReadiness.guestSocketPath(display)
                            )
                        )
                        Logger.e(TAG, "X11 socket never appeared for $workspaceId ($display)")
                        return
                    }
                }
            }

            // §1.114: the session starts only once the X server is actually
            // serving its display. §14: the daemon command carries the full
            // X11 environment explicitly (DISPLAY + TMPDIR + XKB_CONFIG_ROOT).
            val sessionStarted = runtimeBinder.startDaemon(
                workspaceId,
                DesktopSessionProvisioner.DAEMON_NAME,
                DesktopSessionProvisioner.daemonCommand(
                    sessionPath,
                    display,
                    extraEnv = mapOf(
                        "TMPDIR" to "/tmp",
                        "XKB_CONFIG_ROOT" to "/usr/share/X11/xkb"
                    )
                )
            )
            if (!sessionStarted) {
                if (provider == X11Provider.TERMUX_X11) {
                    runCatching { runtimeBinder.stopDaemon(workspaceId, TermuxX11.DAEMON_NAME) }
                } else {
                    runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }
                }
                fail(
                    getApplication<Application>().getString(
                        R.string.desktop_session_start_failed,
                        DesktopSessionProvisioner.sessionDisplayName(sessionPath)
                    )
                )
                return
            }

            _x11State.value = X11SessionState.X11_READY
            x11SessionManager.setState(workspaceId, X11SessionState.X11_READY)
            if (provider == X11Provider.TERMUX_X11) {
                // §13: external transport — the screen must NOT open its RFB
                // viewer (there is no RFB endpoint); it shows the external-
                // desktop status panel instead.
                _desktopState.value = DesktopUiState.RunningExternal(workspaceId, provider.label)
            } else {
                _desktopState.value = DesktopUiState.Running(workspaceId)
            }
            runtimeTrace.record(
                workspaceId,
                "X11 backend READY via ${provider.label} (display=$display, socket=${X11SocketReadiness.guestSocketPath(display)}, " +
                    "session ${DesktopSessionProvisioner.sessionDisplayName(sessionPath)})"
            )
        } catch (e: Exception) {
            _x11State.value = X11SessionState.X11_FAILED
            x11SessionManager.setState(workspaceId, X11SessionState.X11_FAILED)
            _desktopState.value = DesktopUiState.X11Failed(
                e.message ?: getApplication<Application>().getString(R.string.desktop_unknown_error)
            )
            Logger.e(TAG, "X11 start exception", e)
        }
    }

    /** Whether the termux-x11 Android app is installed (feature-detect, never assume). */
    private fun isTermuxX11AppInstalled(): Boolean =
        runCatching {
            getApplication<Application>().packageManager.getLaunchIntentForPackage(TermuxX11.PACKAGE) != null
        }.getOrDefault(false)

    /**
     * Bring the termux-x11 app's display activity to the foreground. The
     * helper daemon only BRIDGES to the app; the pixels render in the app's
     * own window, so launching it is part of "expose desktop" (§13), and a
     * failure to launch is logged, not fatal (the session is still up — the
     * user can open the app from its icon).
     */
    private fun launchTermuxX11App() {
        runCatching {
            val context = getApplication<Application>()
            val intent = context.packageManager.getLaunchIntentForPackage(TermuxX11.PACKAGE)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
        }.onFailure { Logger.w(TAG, "could not launch the termux-x11 app activity: ${it.message}") }
    }

    /**
     * Desktop Provisioning (§16): when the session probe found no X client
     * and a preset is selected, install it through the guest's own package
     * manager and re-probe. Idempotent (probe-before-install), observable
     * ([DesktopUiState.InstallingDependencies] during the install, the
     * installer's output tail in any error), cancellable (a cancellation
     * unwinds the start coroutine the same way it does for Xvnc), and
     * recoverable (failure is an [DesktopUiState.Error] — the terminal and
     * workspace are untouched).
     *
     * @return the resolved session path after the install, or null when the
     *   preset is [DesktopPreset.NONE], no supported package manager exists,
     *   the install failed, or the re-probe still finds no session — in
     *   every null case the UI state already explains why.
     */
    private suspend fun installDesktopPreset(
        declaredSession: String?,
        runtimeBinder: RuntimeBinder,
        workspaceId: String
    ): String? {
        val preset = desktopConfig.desktopPreset
        if (preset == DesktopPreset.NONE) return null
        Logger.i(TAG, "No session in $workspaceId — installing desktop preset '${preset.id}' (§16)")

        val present = runtimeBinder.execCommand(
            workspaceId,
            DesktopPreset.presentCheckCommand(preset) ?: return null
        )
        if (present.exitCode != 0) {
            _desktopState.value = DesktopUiState.InstallingDependencies
            val detection = runtimeBinder.execCommand(workspaceId, XvncProvisioner.DETECT_PACKAGE_MANAGER_COMMAND)
            val pmKey = XvncProvisioner.packageManagerKey(detection.output)
            if (pmKey == null) {
                _desktopState.value = DesktopUiState.Error(
                    getApplication<Application>().getString(R.string.desktop_preset_no_package_manager)
                )
                return null
            }
            val installCommand = DesktopPreset.installCommand(preset, pmKey)
            if (installCommand == null) {
                _desktopState.value = DesktopUiState.Error(
                    getApplication<Application>().getString(R.string.desktop_preset_no_package_manager)
                )
                return null
            }
            runtimeTrace.record(workspaceId, "Desktop preset install: ${preset.id} via $pmKey")
            // Pre-release audit fix (2026-09-02, finding U4/M-1): the preset
            // install (a full XFCE/LXQt transaction) used to run through the
            // non-streaming exec with its hard 120 s COMMAND_TIMEOUT — large
            // apt/pacman transactions deterministically failed with exit −1
            // and a half-installed dpkg state behind them. The streaming exec
            // carries the 1800 s operation timeout (the same budget the
            // guided installer and Package Manager use).
            val result = runtimeBinder.execCommandStreaming(workspaceId, installCommand) { }
            if (result.exitCode != 0) {
                _desktopState.value = DesktopUiState.Error(
                    getApplication<Application>().getString(
                        R.string.desktop_preset_install_failed,
                        preset.displayName,
                        result.error.ifEmpty { result.output }.take(300)
                    )
                )
                return null
            }
        } else {
            Logger.i(TAG, "Preset '${preset.id}' already present in $workspaceId — skipping install")
        }

        val reprobe = runtimeBinder.execCommand(
            workspaceId,
            DesktopSessionProvisioner.detectSessionCommand(declaredSession)
        )
        return DesktopSessionProvisioner.parseSessionPath(
            probeOutput = reprobe.output,
            preferred = DesktopSessionProvisioner.preferredCandidates(null, declaredSession)
        )
    }

    /**
     * Desktop Modes: the WebView run mode — start the user-configured guest
     * web server as a supervised daemon, wait for its loopback port, then
     * hand the URL to the screen's WebView panel.
     *
     * Deliberately lighter than the VNC path: no package-manager detection,
     * no session probe, no pkill. The guest web stack is whatever the user
     * configured; if the command is missing, the error says where to set it
     * (Features → Desktop) rather than guessing a default the user's rootfs
     * may not be able to run.
     */
    private suspend fun startWebDesktop(workspaceId: String, runtimeBinder: RuntimeBinder) {
        val command = desktopConfig.desktopWebCommand.trim()
        if (command.isEmpty()) {
            _desktopState.value = DesktopUiState.Error(
                getApplication<Application>().getString(R.string.desktop_web_command_missing)
            )
            Logger.w(TAG, "WebView run mode for $workspaceId has no guest command configured")
            return
        }

        val port = desktopConfig.desktopWebPort
        val started = runtimeBinder.startDaemon(workspaceId, DAEMON_WEB, command)
        if (!started) {
            _desktopState.value = DesktopUiState.Error(
                getApplication<Application>().getString(R.string.desktop_web_start_failed)
            )
            Logger.e(TAG, "Web server daemon failed to start for $workspaceId")
            return
        }

        // Same probe the VNC path uses — the server is a guest process on the
        // shared network stack either way. A longer budget: web stacks (python
        // http.server, a webtop's nginx) vary far more in cold-start time than
        // Xvnc does.
        val portReady = waitForVncPort(host = "127.0.0.1", port = port, timeoutMs = WEB_PORT_TIMEOUT_MS)
        if (!portReady) {
            runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_WEB) }
            _desktopState.value = DesktopUiState.Error(
                getApplication<Application>().getString(R.string.desktop_web_port_timeout, port)
            )
            Logger.e(TAG, "Timed out waiting for guest web port $port in $workspaceId")
            return
        }

        portRegistry.recordWeb(workspaceId, port)
        val url = DesktopWebMode.buildUrl(port)
        _desktopState.value = DesktopUiState.WebRunning(workspaceId, url)
        Logger.i(TAG, "Desktop web mode up for $workspaceId at $url")
    }

    /**
     * §1.114: pick the guest's preferred desktop session out of a session-probe
     * stdout, or null when the workspace has no X client at all.
     *
     * Deliberately does **not** install one. Xvnc is auto-installed (§1.85)
     * because it is this feature's own dependency with no substitute; a desktop
     * environment is a large, opinionated choice that belongs to the user, so a
     * missing one is reported ([DesktopUiState.NoSession]) rather than decided
     * for them — the same rule §1.113 applied to PulseAudio.
     *
     * §1.118: the rootfs's declared `linexa.desktop` block, when present,
     * reorders the candidates and can add a session binary the fixed candidate
     * list doesn't know. It never *replaces* the probe: the guest's actual
     * filesystem still decides what exists, so a declaration that has drifted
     * from the image degrades to the §1.114 behaviour instead of failing.
     *
     * §16.3.1: this used to run the session `which` itself. It no longer does —
     * the probe output now arrives from [DesktopStartupProbe]'s combined exec (or
     * from the post-install re-probe), and this function is the pure selection
     * half. Splitting it that way is what lets one exec serve both lookups while
     * keeping the §1.114/§1.118 selection rules in exactly one place; it also
     * means nothing here can accidentally spend another PRoot launch.
     */
    private fun sessionPathFrom(declared: DeclaredDesktop?, probeOutput: String): String? =
        DesktopSessionProvisioner.parseSessionPath(
            probeOutput = probeOutput,
            preferred = DesktopSessionProvisioner.preferredCandidates(declared?.type, declared?.session)
        )

    /**
     * §1.85: attempt to install Xvnc into the guest via its package
     * manager (audit §4.5). Shows [DesktopUiState.InstallingDependencies]
     * while the one-shot install runs, then re-checks `which Xvnc`.
     *
     * @return true when Xvnc is present afterward; false when install
     *   failed, the guest has no supported package manager, or the guest
     *   package manager probe timed out — in each case the UI has been set
     *   to a user-facing [DesktopUiState.Error] already.
     */
    private suspend fun autoInstallXvnc(runtimeBinder: RuntimeBinder, workspaceId: String): Boolean {
        _desktopState.value = DesktopUiState.InstallingDependencies
        Logger.i("DesktopViewModel", "Xvnc not found in workspace $workspaceId — attempting auto-install (§1.85)")

        val detection = runtimeBinder.execCommand(workspaceId, XvncProvisioner.DETECT_PACKAGE_MANAGER_COMMAND)
        val pmKey = XvncProvisioner.packageManagerKey(detection.output)
        if (pmKey == null) {
            val reason = if (detection.exitCode == -1) {
                getApplication<Application>().getString(R.string.desktop_xvnc_probe_timeout)
            } else {
                getApplication<Application>().getString(R.string.desktop_xvnc_missing)
            }
            _desktopState.value = DesktopUiState.Error(reason)
            Logger.e("DesktopViewModel", "No supported package manager found for $workspaceId (probe='${detection.output.take(120)}')")
            return false
        }

        val installCommand = XvncProvisioner.installCommand(pmKey)
        if (installCommand == null) {
            _desktopState.value = DesktopUiState.Error(getApplication<Application>().getString(R.string.desktop_xvnc_missing))
            Logger.e("DesktopViewModel", "No install command for package manager '$pmKey'")
            return false
        }

        Logger.i("DesktopViewModel", "Installing Xvnc via '$pmKey' in $workspaceId: $installCommand")
        // Pre-release audit fix (2026-09-02, finding U4/M-1): stream with the
        // 1800 s operation budget — see the preset-install comment above.
        val installResult = runtimeBinder.execCommandStreaming(workspaceId, installCommand) { }
        if (installResult.exitCode != 0) {
            val reason = if (installResult.exitCode == -1) {
                getApplication<Application>().getString(R.string.desktop_xvnc_install_timeout)
            } else {
                getApplication<Application>().getString(R.string.desktop_xvnc_install_failed, installResult.error.ifEmpty { installResult.output })
            }
            _desktopState.value = DesktopUiState.Error(reason)
            Logger.e("DesktopViewModel", "Xvnc install via '$pmKey' failed for $workspaceId: ${installResult.error.ifEmpty { installResult.output }.take(300)}")
            return false
        }

        val recheck = runtimeBinder.execCommand(workspaceId, "which Xvnc")
        if (recheck.exitCode != 0) {
            _desktopState.value = DesktopUiState.Error(getApplication<Application>().getString(R.string.desktop_xvnc_missing))
            Logger.e("DesktopViewModel", "Xvnc still missing after install for $workspaceId (install exited 0 but which fails)")
            return false
        }

        Logger.i("DesktopViewModel", "Xvnc auto-installed via '$pmKey' in $workspaceId")
        return true
    }

    /**
     * §1.125: the Xvnc command for **this device's** screen — and, since
     * Desktop Modes, for this workspace's **settings**.
     *
     * The pre-§1.125 constant served 1280×720 to every device. [VncViewer]
     * contain-scales, so that was not broken — it was permanently letterboxed:
     * a 20:9 phone got black bars and a desktop scaled down to the narrow axis,
     * and a 1080p-or-better screen never used the resolution it had. Nothing
     * failed and nothing logged, which is the same silent-wrongness class §1.114
     * fixed in the decode path.
     *
     * The geometry decision itself is pure and tested in [DesktopGeometry]
     * (landscape-forced, clamped to 1080p, rounded to even pixels, falling back to
     * the old constant when the display reports nothing usable). This function only
     * reads the display metrics, which needs a real [Application].
     *
     * Desktop Modes: the geometry decision now starts from the user's
     * resolution preset ([FeatureSettings.desktopResolution] — [DesktopRunMode]'s
     * VNC mode settings), and the command is pinned to this workspace's
     * [port] with the [securityArgs] the auth provisioning decided — both
     * threaded through unchanged, so this function stays the metrics-reader
     * and the pure decisions stay in [DesktopGeometry].
     *
     * §1.166: the values it already computed are now also recorded to
     * [RuntimeTrace], so the Workspace → Debug panel can show them. **Nothing about
     * the geometry changed** — same metrics source, same [DesktopGeometry] call, same
     * command, same `Logger` line, in the same order. The audit's §16.5 question ("is
     * the framebuffer shaped for a window that had not entered fullscreen yet?") needs
     * exactly these three numbers and could not be asked, because they went only to
     * logcat and an app-private file. `density` is included because it is what
     * distinguishes a full-display metric from an inset-reduced one when comparing
     * against a `dp`-denominated window size; `heightPixels` alone cannot.
     */
    private fun xServerCommand(
        workspaceId: String,
        port: Int,
        securityArgs: String,
        display: String,
        localhostSupported: Boolean
    ): String {
        val metrics = getApplication<Application>().resources.displayMetrics
        // §16.5 landed (Desktop Zoom work, 2026-08): the framebuffer geometry is
        // now derived from the display's REAL bounds, not the application
        // window's `displayMetrics`. The forensic audit's §4.2 found the old
        // source measured while the window was still portrait with system bars
        // shown — before `DesktopWindowMode` had requested landscape and hidden
        // the bars — so any inset/orientation difference between that moment and
        // the fullscreen window reappeared as a permanent letterbox through the
        // viewer's contain-scale. The real display bounds are a property of the
        // panel, not of this window's transient configuration, so they match the
        // fullscreen landscape window by construction and the measurement order
        // stops mattering.
        val realBounds = realDisplayBounds()
        val realWidth = maxOf(realBounds.first, realBounds.second)
        val realHeight = minOf(realBounds.first, realBounds.second)
        // One geometry decision from one metrics pair: the real display when it
        // is readable, else the historical window metrics (logged as such).
        val metricsUsed = if (realWidth > 0 && realHeight > 0) {
            realWidth to realHeight
        } else {
            Logger.w(TAG, "§16.5 real display bounds unavailable; falling back to displayMetrics")
            metrics.widthPixels to metrics.heightPixels
        }
        val geometry = DesktopGeometry.geometryFor(
            metricsUsed.first,
            metricsUsed.second,
            desktopConfig.desktopResolution
        )
        // 2026-09-05 (TinyContainer parity, DPI half): the framebuffer's DPI is
        // derived, not tuned — the device's own density scaled by whatever
        // ratio the framebuffer bears to the panel (1:1 for AUTO on any phone
        // or tablet). Without it Xvnc reports a built-in default that has no
        // relationship to the panel and every Xft/GTK font renders at the
        // wrong physical size.
        val framebufferWidth = geometry.substringBefore('x').toIntOrNull() ?: 0
        val framebufferHeight = geometry.substringAfter('x').substringBefore(' ').toIntOrNull() ?: 0
        val dpi = DesktopGeometry.dpiFor(
            framebufferWidth,
            framebufferHeight,
            metricsUsed.first,
            metricsUsed.second,
            metrics.densityDpi
        )
        // Both readings are logged: the audit's §16.5 falsification test is
        // "compare what Xvnc was asked for with the fullscreen window" — keeping
        // the old source's numbers next to the new one's makes that comparison
        // possible from the trace alone.
        Logger.i(
            TAG,
            "§1.125 Xvnc geometry $geometry " +
                "(real display ${realWidth}x${realHeight}, display ${metrics.widthPixels}x${metrics.heightPixels})"
        )
        // §1.166: the same facts, on the path a user can actually read. Recorded
        // after the decision so the trace cannot disagree with what Xvnc is told.
        runtimeTrace.record(
            workspaceId,
            "§16.5 Xvnc geometry=$geometry port=$port auth=${securityArgs.substringAfter("-SecurityTypes ")} " +
                "display=$display dpi=$dpi localhost=${if (localhostSupported) "yes" else "unsupported"} " +
                "realDisplay=${realWidth}x${realHeight} " +
                "displayMetrics=${metrics.widthPixels}x${metrics.heightPixels} " +
                "density=${metrics.density} densityDpi=${metrics.densityDpi} " +
                "measuredInState=${_desktopState.value::class.simpleName}"
        )
        return DesktopGeometry.xvncCommand(
            geometry,
            display = display,
            rfbPort = port,
            securityArgs = securityArgs,
            dpi = dpi,
            localhostSupported = localhostSupported
        )
    }

    /**
     * The display's real, physical bounds in pixels — the fullscreen surface
     * the desktop will draw into once [DesktopWindowMode] has hidden the bars
     * and requested landscape.
     *
     * On API 30+ this is `maximumWindowMetrics` (documented as covering the
     * entire display, ignoring system bars and the current window
     * configuration); on 28–29 it is the deprecated-but-correct
     * `Display.getRealMetrics`. Both read the panel, not this window, which is
     * what makes the geometry independent of the measurement moment (§4.2).
     * Returns (0, 0) on a failure the caller falls back from.
     */
    private fun realDisplayBounds(): Pair<Int, Int> = runCatching {
        val app = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null) bounds.width() to bounds.height() else 0 to 0
        } else {
            @Suppress("DEPRECATION")
            val displayManager = app.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.hardware.display.DisplayManager
            @Suppress("DEPRECATION")
            val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }.getOrDefault(0 to 0)

    fun stopDesktop(workspaceId: String) {
        viewModelScope.launch {
            Logger.i("DesktopViewModel", "Stopping desktop for workspace: $workspaceId")
            if (_x11State.value == X11SessionState.X11_STARTING || _x11State.value == X11SessionState.X11_READY) {
                _x11State.value = X11SessionState.X11_STOPPING
                x11SessionManager.setState(workspaceId, X11SessionState.X11_STOPPING)
            }
            _desktopState.value = DesktopUiState.Stopped

            // Desktop Modes: whichever daemons this workspace owns are stopped.
            // Each stop is guarded and independent — stopping a web-mode desktop
            // must not fail because there is no Xvnc, and vice versa. The
            // termux-x11 helper daemon joins the family (§13: stop cleanly).
            runCatching { binder?.stopDaemon(workspaceId, DesktopSessionProvisioner.DAEMON_NAME) }
                .onFailure { Logger.w(TAG, "Error stopping desktop session: ${it.message}") }
            runCatching { binder?.stopDaemon(workspaceId, DAEMON_XVNC) }
                .onFailure { Logger.w(TAG, "Error stopping X server: ${it.message}") }
            runCatching { binder?.stopDaemon(workspaceId, TermuxX11.DAEMON_NAME) }
                .onFailure { Logger.w(TAG, "Error stopping termux-x11 helper: ${it.message}") }
            runCatching { binder?.stopDaemon(workspaceId, DAEMON_WEB) }
                .onFailure { Logger.w(TAG, "Error stopping web server: ${it.message}") }
            portRegistry.clear(workspaceId)
            x11SessionManager.releaseDisplay(workspaceId)
            x11SessionManager.clearState(workspaceId)
            _x11State.value = X11SessionState.IDLE
        }
    }

    /**
     * Phase 07 (OPT-SEC-004, Step B3): verify a STARTED Xvnc's actual bind
     * address from inside the guest, and return the user-facing failure
     * message when the evidence shows a non-loopback listener — `null` when
     * the bind is loopback-only.
     *
     * Runs AFTER the port is ready (the caller's `waitForVncPort` succeeded),
     * so the listener exists to be observed; probing earlier would read a
     * socket-less table and prove nothing. A blank listing (none of `ss` /
     * `netstat` / BusyBox netstat available, or the exec failed) is logged as
     * a warning and returns `null`: per the brief, loopback-only is never
     * CLAIMED without evidence — the password requirement stays, and the
     * start proceeds only under whatever auth provisioning already decided.
     *
     * Same-device note (Step B4): even a verified `127.0.0.1` bind does not
     * isolate the desktop from other apps — Android grants no per-app
     * loopback isolation, so same-device apps can always reach a loopback
     * VNC port. A VNC password is the control for that threat model; this
     * check only catches the server binding on a routable interface.
     */
    private suspend fun vncBindViolationMessage(
        workspaceId: String,
        port: Int,
        runtimeBinder: RuntimeBinder
    ): String? {
        val listing = runCatching {
            runtimeBinder.execCommand(workspaceId, DesktopVncAuthProvisioner.BIND_PROBE_COMMAND)
        }.getOrNull()?.output.orEmpty()
        if (listing.isBlank()) {
            Logger.w(
                TAG,
                "VNC bind probe produced no listing for $workspaceId (probe tools unavailable?) — " +
                    "loopback-only cannot be claimed; keeping the password requirement"
            )
            return null
        }
        val violation = DesktopVncAuthProvisioner.loopbackBindViolation(listing, port) ?: return null
        Logger.e(TAG, "Xvnc bind violation for $workspaceId on port $port: $violation — stopping the daemon")
        return getApplication<Application>()
            .getString(R.string.desktop_vnc_loopback_bind_violation, violation)
    }

    /**
     * 2026-09-05 (Xvnc exit-1 forensics): the failure reason for a port-wait
     * timeout that knows the difference between "the server is slow" and "the
     * server is dead". When the Xvnc daemon already exited, the port can never
     * open and the useful message is the X11 fatal error in the daemon log the
     * runtime keeps host-side (`{workspace}/logs/daemon-xvnc.log`) — the file
     * that answers WHY the process exited 1 (stale display lock, bind
     * failure, missing fonts, bad option…). The tail is also mirrored to the
     * workspace trace so it survives the screen.
     */
    private fun xvncStartFailureReason(
        workspaceId: String,
        port: Int,
        runtimeBinder: RuntimeBinder
    ): String {
        val daemonAlive = runCatching { runtimeBinder.isDaemonAlive(workspaceId, DAEMON_XVNC) }
            .getOrDefault(false)
        if (daemonAlive) {
            return getApplication<Application>().getString(R.string.desktop_xvnc_port_timeout, port, 5000)
        }
        val tail = daemonLogTail(workspaceId, DAEMON_XVNC)
        runtimeTrace.record(
            workspaceId,
            "Xvnc exited during startup; daemon log tail: ${tail.take(400).replace("\n", " | ")}"
        )
        return getApplication<Application>().getString(R.string.desktop_xvnc_died_during_start, tail.take(600))
    }

    /**
     * The last ~8 KB of a daemon's log file (the runtime's own per-daemon
     * sink — see `PRootContainerRuntime.launchDaemonProcess`). Host-side and
     * bounded; a missing or unreadable file yields an honest placeholder, not
     * an exception on a path that is already handling one.
     */
    private fun daemonLogTail(workspaceId: String, daemonName: String): String {
        val logFile = java.io.File(
            fileManager.getWorkspaceBasePath(workspaceId),
            "logs/daemon-$daemonName.log"
        )
        return runCatching {
            if (!logFile.isFile) return "(no daemon log written)"
            val length = logFile.length()
            val start = (length - 8_192L).coerceAtLeast(0L)
            java.io.RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }.trim()
        }.getOrElse { "(daemon log unreadable: ${it.message})" }
            .ifEmpty { "(daemon log empty)" }
    }

    /**
     * Polls a TCP port until it accepts a connection or the timeout elapses.
     *
     * §16.3.4: the retry interval is [DesktopPortPoll]'s, not a literal — dense
     * while Xvnc is plausibly still binding, relaxed afterwards, and clamped so a
     * sleep can never outlive [timeoutMs]. See that object for why a dense window
     * was chosen over the audit's exponential backoff.
     *
     * **On the `CancellationException` rethrow.** This is *defensive*, not a bug
     * fix: the pre-§16.3.4 loop was already cancellation-safe and measurement
     * confirmed it (`DesktopPortPollWiringSourceTest`). `CancellationException`
     * is an `IllegalStateException`, so `catch (_: Exception)` does match it —
     * but the only suspending call is `delay`, which sits in the **catch** block,
     * not the `try`. `Socket.connect` is blocking, so nothing inside the `try`
     * can ever raise cancellation, and a cancelled `delay` propagates out of the
     * loop untouched.
     *
     * That safety is an accident of where the suspension point happens to sit.
     * Moving any suspending call into the `try` — which is exactly what a future
     * change to this loop would do — would silently convert cancellation into
     * another poll iteration, and the loop would then spin without pacing until
     * its deadline (measured at ~90k iterations over a 2 s budget in that shape).
     * The rethrow makes the invariant explicit instead of structural.
     *
     * 2026-09-05: the poll gained an OPTIONAL liveness short-circuit
     * ([isServerAlive]). Readiness remains "accepts TCP" — the pin that
     * matters — but a server that already DIED during its start window can
     * never open the port, so polling its full budget is pure latency on a
     * path whose honest answer is already known. The callback is consulted
     * only after a failed connect, never instead of one.
     */
    private suspend fun waitForVncPort(
        host: String,
        port: Int,
        timeoutMs: Long,
        isServerAlive: (() -> Boolean)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val deadline = start + timeoutMs
            var listening = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(host, port),
                            DesktopPortPoll.CONNECT_TIMEOUT_MS
                        )
                    }
                    listening = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The server exited without ever opening the port: no
                    // amount of further polling changes the answer.
                    if (isServerAlive?.invoke() == false) break
                    val after = System.currentTimeMillis()
                    val wait = DesktopPortPoll.delayAfterFailure(
                        elapsedMs = after - start,
                        remainingMs = deadline - after
                    )
                    if (wait <= 0L) break
                    delay(wait)
                }
            }
            listening
        }
    }

    fun getCurrentWorkspaceId(): String? = currentWorkspaceId

    /**
     * Transformation spec §13: re-open the termux-x11 app's display window
     * (the RunningExternal status panel's button). Public because the
     * screen cannot start another app's activity itself without the
     * NEW_TASK flag plumbing this helper owns.
     */
    fun openTermuxX11App() = launchTermuxX11App()

    /**
     * §1.166 — records the framebuffer geometry the *server* announced, for §16.5.
     *
     * The other half of the geometry question. [xServerCommand] traces what Xvnc was
     * *asked* for; this traces what the RFB `ServerInit` actually reported, which is
     * the number the viewer contain-scales against. They can differ — a server may
     * refuse or adjust a requested geometry — and the audit's letterbox mechanism is
     * about the relationship between the two, so one without the other cannot answer
     * it.
     *
     * **Why this lives here rather than in [VncClient].** `VncClient` is constructed
     * by `DesktopScreen` with `remember { VncClient() }`: it is not a Hilt-managed
     * object, has no injected dependencies at all, and deliberately owns nothing but
     * the socket and the frame state. Giving it a [RuntimeTrace] would mean either
     * threading a singleton through a `remember` block or making the client
     * injectable — an architectural change to the render path, which §16.5 does not
     * justify and which the task's constraints rule out. It is unnecessary anyway:
     * the client **already publishes** `frameWidth`/`frameHeight` as public
     * `StateFlow`s (set from `ServerInit` via `resizeFramebuffer`), so the value is
     * observable from the screen, and the screen already has this ViewModel. Nothing
     * inside `VncClient` had to change.
     *
     * Idempotent per geometry by construction: the caller collects distinct values
     * only, and a `StateFlow` of the same size does not re-emit. A resize mid-session
     * (§4.3) legitimately records a second line, which is information, not noise.
     */
    fun recordFramebufferGeometry(workspaceId: String, width: Int, height: Int) {
        runtimeTrace.record(
            workspaceId,
            "§16.5 VNC framebuffer (ServerInit) ${width}x$height"
        )
    }

    /**
     * §1.114: the connect target for the embedded VNC client. Exposed so
     * [DesktopScreen] and this ViewModel cannot disagree about the port (they
     * previously each hardcoded 5901 with only a comment tying them together).
     *
     * Desktop Modes: the port is now the one recorded for THIS workspace at
     * start time — [DesktopPortRegistry.vncPortFor] — falling back to the
     * historical constant for a workspace whose desktop was started before
     * the registry existed. The [vncPassword]/[preferZrle] getters complete
     * the endpoint: the screen passes them straight into
     * [com.fem.linexa.ui.screens.desktop.VncClient.connect], so client and
     * server credentials can never drift apart across a code change.
     */
    val vncHost: String get() = VNC_HOST
    val vncPort: Int
        get() = currentWorkspaceId?.let { portRegistry.vncPortFor(it, default = VNC_PORT) } ?: VNC_PORT

    /** The configured VNC-Auth password, or null — passed straight to the client. */
    val vncPassword: String?
        get() = desktopConfig.desktopVncPassword.takeIf { it.isNotEmpty() }

    /** Whether the client may offer ZRLE (the run-mode setting). */
    val preferZrle: Boolean get() = desktopConfig.desktopVncPreferZrle

    override fun onCleared() {
        super.onCleared()
        detachBinder()
    }

    companion object {
        private const val TAG = "DesktopViewModel"
        // §1.83: the X server now runs as a named guest daemon so it survives
        // the launching call (see PRootContainerRuntime's daemon-host block).
        private const val DAEMON_XVNC = "xvnc"

        // Desktop Modes: the WebView run mode's supervised guest web server,
        // named apart from the X daemons so stop/resume are mode-aware.
        private const val DAEMON_WEB = "webserver"

        /**
         * Desktop failure-surfacing (2026-08): the daemon names whose
         * unexpected death means "this desktop is broken" — exactly the
         * daemons the desktop start path owns. Supplied to the pure
         * [DesktopHealthGate] so the gate does not need the runtime's
         * internals. A companion val (not an instance property beside
         * [desktopStateFrom]) because the source-pinning tests slice the
         * ViewModel from `fun startDesktop` and read the *first* occurrence
         * of the daemon-key patterns; a use site there would silently
         * re-order what they pin.
         */
        private val WATCHED_DESKTOP_DAEMONS = setOf(
            DAEMON_XVNC,
            DesktopSessionProvisioner.DAEMON_NAME,
            TermuxX11.DAEMON_NAME,
            DAEMON_WEB
        )

        /**
         * 2026-09-05 (state-machine correctness): the subset of watched
         * daemons whose death means the X11 lifecycle can never reach READY —
         * the X server itself. Used to fail an in-flight X11_STARTING state
         * immediately instead of leaving it hanging on the port poll.
         */
        private val WATCHED_XSERVER_DAEMONS = setOf(
            DAEMON_XVNC,
            TermuxX11.DAEMON_NAME
        )

        // Desktop Modes: the web-mode port probe's budget. Web stacks vary
        // far more in cold-start time than Xvnc — a python http.server binds
        // instantly, a webtop's full nginx+supervisor chain does not.
        private const val WEB_PORT_TIMEOUT_MS = 15_000L

        // §1.125: the geometry is per-device now (see [xServerCommand] and
        // [DesktopGeometry]); the old `X_SERVER_COMMAND` constant hardcoded
        // 1280x720 for every screen.
        // §1.114: single source of the VNC endpoint (was duplicated in
        // DesktopScreen).
        private const val VNC_HOST = "127.0.0.1"
        private const val VNC_PORT = 5901
    }
}

/**
 * Desktop UI state machine for Path B: Embedded VNC client (§4).
 */
sealed interface DesktopUiState {
    /** Service not yet bound. */
    data object Unbound : DesktopUiState

    /** Service bound, waiting for start command. */
    data object Connected : DesktopUiState

    /** X server is starting inside PRoot. */
    data object Starting : DesktopUiState

    /** §1.85: Xvnc is missing and is being auto-installed into the guest. */
    data object InstallingDependencies : DesktopUiState

    /**
     * §1.114: the workspace has an X server but no X client (no desktop
     * environment, window manager, or even `xterm`), so there is nothing to
     * display. Distinct from [Error] on purpose: nothing failed, the workspace
     * simply hasn't got a desktop installed — and Linexa deliberately doesn't
     * install one, so this is a state the user resolves, not a fault to retry.
     */
    data object NoSession : DesktopUiState

    /** X server is running and VNC stream is active. */
    data class Running(val workspaceId: String) : DesktopUiState

    /**
     * Transformation spec §13: the desktop is running through the **external
     * termux-x11 app** — the real local-X11 transport. There is no RFB
     * endpoint for the embedded viewer; the pixels render in the termux-x11
     * app's own window, so the screen shows a status panel whose button
     * re-opens the app. [provider] names the transport for the status line.
     */
    data class RunningExternal(val workspaceId: String, val provider: String) : DesktopUiState

    /**
     * Desktop Modes: the WebView run mode is live — the guest web server is
     * listening and [url] is ready for the screen's WebView panel. Kept a
     * *separate* state from [Running] rather than a field on it, because the
     * screen renders a completely different surface (and window mode) for it:
     * a VNC session is fullscreen landscape, a web page is an ordinary,
     * system-bar-respecting column.
     */
    data class WebRunning(val workspaceId: String, val url: String) : DesktopUiState

    /**
     * Desktop Backends: the X11 start failed (§28) — distinct from [Error]
     * so the screen can offer the VNC fallback explicitly instead of the
     * user having to discover the backend setting.
     */
    data class X11Failed(val reason: String) : DesktopUiState

    /** X server or runtime encountered an error. */
    data class Error(val reason: String) : DesktopUiState

    /** Desktop session stopped. */
    data object Stopped : DesktopUiState
}
