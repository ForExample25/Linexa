package com.fem.linexa.ui.screens.desktop

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fem.linexa.ui.icons.LinexaIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.features.DesktopRunMode
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.GuiBackend
import kotlinx.coroutines.delay
import com.fem.linexa.ui.components.GlobalAppBar
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.StatusChip
import com.fem.linexa.ui.components.workspaceAppBarState


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    workspaceId: String,
    workspaceName: String,
    desktopEnabled: Boolean,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    binder: RuntimeBinder? = null,
    /**
     * Desktop Modes: the workspace's resolved feature settings, handed over by
     * [com.fem.linexa.ui.screens.workspace.WorkspaceNavGraph] from the same
     * state the Features tab edits. They decide the run mode and its
     * parameters; see [DesktopViewModel.configure] for why they arrive as a
     * parameter and not a second repository read.
     */
    settings: FeatureSettings = FeatureSettings(),
    /**
     * Direct launch: asks the workspace layer to persist a generated VNC
     * password when the (auto-detected) desktop has none, so the secure
     * fail-closed start policy does not refuse a desktop that never went
     * through the enable toggle. Default no-op keeps existing call sites valid.
     */
    onEnsureVncPassword: () -> Unit = {},
    viewModel: DesktopViewModel = hiltViewModel()
) {
    val desktopState by viewModel.desktopState.collectAsState()

    // §1.153: the client owns its own Dispatchers.IO scope and is no longer handed
    // the composition's. `rememberCoroutineScope()` is Main-dispatched
    // (AndroidUiDispatcher.Main), so every RFB message the receive loop read and
    // every pointer/key event it wrote had to wait for a Main-queue turn — behind the
    // frame collector, the buffer swap, recomposition and the traversal that the
    // previous frame caused. Frame delivery was bounded by the availability of the
    // thread that draws the frames. See the VncClient kdoc.
    //
    // The lifecycle consequence: the client's coroutines no longer stop when this
    // composition goes away, so `DisposableEffect(vncClient)` below is now the only
    // thing that stops them. It already existed (§1.140 added it because
    // `disconnect()` was already the only thing that closed the socket), which is
    // why this is a scope change and not a lifecycle change.
    val vncClient = remember { VncClient() }

    // §1.29: consume the graph's shared RuntimeService binder (owned by the
    // graph-scoped RuntimeViewModel, §1.2) instead of binding the service
    // from this screen. The binder can arrive after first composition, so
    // this depends on it — on a late arrival the screen recomposes with the
    // live binder and attaches then.
    LaunchedEffect(workspaceId, binder) {
        if (binder == null) {
            Logger.d("DesktopChain", "Desktop waiting for binder (workspaceId=$workspaceId)")
        } else {
            Logger.d("DesktopChain", "Desktop attaching binder for $workspaceId")
            viewModel.attachBinder(workspaceId, binder)
        }
    }

    // Desktop Modes: deliver the run-mode settings BEFORE any effect below can
    // start or reconnect a desktop. Placement is load-bearing — Compose runs
    // LaunchedEffects in composition order, and the resume path in the
    // ViewModel reads the settings to decide which mode to reattach to.
    LaunchedEffect(settings) {
        viewModel.configure(settings)
    }

    // Direct launch: choosing Desktop starts the session — there is no
    // intermediate "Start Desktop" placeholder to tap through. The effect is
    // one-shot per visit (rememberSaveable survives rotation/recreation, and is
    // dropped when the destination leaves the back stack), so a user's explicit
    // Stop or a failure does not loop back into another start; those states keep
    // their manual Start/Retry affordances. It only fires from the idle
    // Connected state — an already-running desktop is reattached by the
    // ViewModel's resume path, never restarted — and only while the workspace
    // session is genuinely up, so a not-yet-running runtime cannot turn into a
    // spurious "session not running" error.
    var autoStartConsumed by rememberSaveable(workspaceId) { mutableStateOf(false) }
    LaunchedEffect(desktopState, desktopEnabled, binder, settings) {
        if (autoStartConsumed || !desktopEnabled || binder == null) return@LaunchedEffect
        if (desktopState !is DesktopUiState.Connected) return@LaunchedEffect
        if (!binder.hasActiveSession(workspaceId)) return@LaunchedEffect
        if (settings.desktopMode != DesktopRunMode.WEBVIEW &&
            DesktopVncAuthProvisioner.needsGeneratedPassword(
                password = settings.desktopVncPassword,
                unreadable = settings.desktopVncPasswordUnreadable,
                allowUnauthenticated = settings.desktopVncAllowUnauthenticated
            )
        ) {
            // Persist the secure default first; the effect re-runs when the
            // resolved settings arrive with the password.
            onEnsureVncPassword()
            return@LaunchedEffect
        }
        autoStartConsumed = true
        viewModel.startDesktop(workspaceId)
    }

    LaunchedEffect(desktopState) {
        when (desktopState) {
            is DesktopUiState.Running -> {
                // §1.114: the endpoint comes from the ViewModel, which is what
                // starts Xvnc — the screen previously hardcoded its own copy of
                // 5901, so the two could drift apart silently.
                //
                // Desktop Modes: the endpoint is now the workspace's own port,
                // and the credential/encoding preference travel with it — the
                // same settings the ViewModel started Xvnc from, so client and
                // server agree by construction.
                vncClient.connect(
                    host = viewModel.vncHost,
                    port = viewModel.vncPort,
                    password = viewModel.vncPassword,
                    preferZrleEncodings = viewModel.preferZrle
                )
            }
            is DesktopUiState.RunningExternal -> {
                // §13: external transport — there is no RFB endpoint; make
                // sure no viewer is connected and nothing reconnects.
                vncClient.disconnect()
            }
            else -> {
                vncClient.disconnect()
            }
        }
    }

    // Transformation spec §12/§C — bounded auto-reconnect for the RFB
    // transport. A mid-session socket drop used to strand the user inside a
    // half-dead viewer with only an error line: the daemons (Xvnc + session)
    // were still alive and serving, but the client never came back. Now a
    // previously-connected session that drops retries with a bounded backoff
    // (1s/2s/4s, three attempts) while the desktop state still says Running —
    // deterministically, never forever (a server that is genuinely gone
    // surfaces as a daemon-death degradation in the runtime's health
    // registry, and the user always keeps the manual restart affordance).
    var vncReconnectAttempts by remember { mutableStateOf(0) }
    val vncError by vncClient.error.collectAsState()
    val vncConnected by vncClient.isConnected.collectAsState()
    LaunchedEffect(vncConnected) {
        if (vncConnected) {
            // A fresh successful (re)connection resets the attempt budget.
            vncReconnectAttempts = 0
        }
    }
    LaunchedEffect(vncError, desktopState) {
        val state = desktopState
        if (vncError != null && state is DesktopUiState.Running && vncReconnectAttempts < 3) {
            vncReconnectAttempts++
            delay(1000L * (1L shl (vncReconnectAttempts - 1)))
            // Re-read the state after the backoff: a stop during the wait
            // must not resurrect a connection the user asked to tear down.
            if (viewModel.desktopState.value is DesktopUiState.Running) {
                vncClient.connect(
                    host = viewModel.vncHost,
                    port = viewModel.vncPort,
                    password = viewModel.vncPassword,
                    preferZrleEncodings = viewModel.preferZrle
                )
            }
        }
    }

    // §1.166 (§16.5 diagnostics): mirror the framebuffer geometry the server
    // announced into the workspace debug trace, so it can be read next to the
    // `§16.5 Xvnc geometry` line the ViewModel records when it starts Xvnc. Answering
    // the audit's letterbox question needs BOTH numbers — what Xvnc was asked for and
    // what ServerInit came back with — and until now neither was reachable without
    // adb.
    //
    // Diagnostics only. It observes the two StateFlows the client already publishes
    // and changes nothing about the connection, the framebuffer or the render path;
    // `VncClient` is untouched. Keyed on the pair, so it runs once per distinct
    // geometry rather than per recomposition, and the zero guard skips the initial
    // pre-ServerInit value (0×0 is "not connected yet", not a measurement). The
    // append itself is gated inside `RuntimeTrace.record` on "Enable debug info".
    val frameWidth by vncClient.frameWidth.collectAsState()
    val frameHeight by vncClient.frameHeight.collectAsState()
    LaunchedEffect(workspaceId, frameWidth, frameHeight) {
        if (frameWidth > 0 && frameHeight > 0) {
            viewModel.recordFramebufferGeometry(workspaceId, frameWidth, frameHeight)
        }
    }

    // §1.140: the connection is also released when this screen leaves
    // composition, not only when the state transitions away from Running.
    //
    // The effect above is keyed on the state, so its `else` branch runs only on
    // a *transition* — and leaving a running desktop (the back arrow, which is
    // the normal way out) is not one. Cancelling `scope` ends the receive
    // coroutine, but [VncClient.disconnect] is the only thing that closes the
    // socket, so every visit to a running desktop leaked an open socket, its two
    // buffered streams and the server-side client connection, and the next visit
    // opened another one on top (`resumeIfAlreadyRunning` → Running → connect).
    // Same defect class as §1.125's fullscreen effect: that pass converted the
    // window-mode effect below to a DisposableEffect and left this one keyed on
    // a transition that may never happen.
    //
    // This closes the *client* only. Xvnc and the X session are guest daemons
    // (§1.83) owned by the workspace, so they keep running and re-entry
    // reattaches through `resumeIfAlreadyRunning` (§1.114) — disconnecting here
    // must not, and does not, stop the desktop.
    DisposableEffect(vncClient) {
        onDispose { vncClient.disconnect() }
    }

    // § fullscreen: immersive mode while the desktop session is actually
    // running, so no Android status/navigation bar overlays the remote screen.
    //
    // §1.125: this is a DisposableEffect, which the pre-§1.125 comment claimed
    // and the code did not do. A LaunchedEffect keyed on `isRunning` only ever
    // ran its `else` branch when the state *changed* while this screen was still
    // composed — so leaving the screen while the desktop was Running (system
    // back, which is the only way out once the app bar is hidden) left the status
    // and navigation bars hidden on every screen the user went to next, until
    // something else happened to show them. The bars must be restored on dispose,
    // not on a state transition that may never come.
    //
    // §1.138 (UI/UX Phase 8): the window mode is now a value
    // ([DesktopWindowMode.forState]) covering orientation as well as the bars, and
    // both are restored on dispose. The desktop is landscape while a session runs
    // — the framebuffer has been landscape since §1.125, so a portrait window drew
    // it as a letterboxed strip using about a fifth of the screen. The activity's
    // *previous* requested orientation is captured and put back rather than being
    // cleared to UNSPECIFIED, because this activity is shared by every screen in
    // the app.
    val view = LocalView.current
    val windowMode = DesktopWindowMode.forState(desktopState)
    DisposableEffect(windowMode) {
        val activity = view.context as? android.app.Activity
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, view) }
        val previousOrientation = activity?.requestedOrientation
            ?: DesktopWindowMode.ORIENTATION_UNSPECIFIED

        if (windowMode.hideSystemBars) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            // Transient, not sticky-hidden: on a gesture-navigation device the
            // bars are the only way back, and a swipe must be able to bring them
            // over the desktop without ending the session.
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        if (windowMode.landscape) {
            // The framework constant itself (rather than the pure-JVM mirror)
            // lets lint verify the value; DesktopWindowModeTest keeps the
            // mirror honest for the unit-testable model.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (windowMode.landscape) {
                activity?.requestedOrientation = previousOrientation
            }
        }
    }

    // HAVEN: the Desktop surface keeps the SAME workspace bar (workspace name
    // left, Terminal + Desktop right) but hosted directly on the Haven screen
    // root — no M3 Scaffold. The fullscreen exception stays data-driven: the
    // bar is simply not composed while the session actually runs, and the
    // content then takes the whole window.
    val barState = workspaceAppBarState(
        workspaceName = workspaceName,
        desktopEnabled = desktopEnabled,
        // §1.133: Desktop is still a real destination (it is a different
        // surface, not an overlay on the workspace content), so its bar keeps
        // the back arrow — that back is a within-workspace pop, not a
        // workspace exit, and needs no confirmation. Its subtitle names the
        // surface for the same reason the overlay sections name theirs.
        subtitle = stringResource(R.string.workspace_section_desktop),
        desktopSelected = true,
        onBack = onBack,
        onOpenTerminal = onOpenTerminal,
        onOpenDesktop = {}
    )
    val barVisible = desktopState !is DesktopUiState.Running

    HavenScreen {
        Column(modifier = Modifier.fillMaxSize()) {
            if (barVisible) {
                GlobalAppBar(state = barState)
            }
            // The header is content, not an inset-providing Scaffold slot; the
            // placeholder states below take no extra top spacing (the header is
            // already above them) and the Running state still takes the whole
            // window (the bar is not composed then).
            val padding = PaddingValues(0.dp)
            when (desktopState) {
            is DesktopUiState.Running -> {
                // §1.138: no `padding(padding)` here, deliberately. The bar is
                // hidden and the bars are hidden, so the desktop takes the whole
                // window — insetting it would reintroduce the letterbox this phase
                // removes, in the one state that is supposed to be fullscreen.
                //
                // Desktop Touch work (2026-08): the workspace's touch mode rides
                // along — the viewer re-reads it on every recomposition, so the
                // setting takes effect on the next touch (no desktop restart).
                VncViewer(
                    vncClient = vncClient,
                    touchMode = viewModel.touchMode,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Desktop Modes: the WebView run mode. Its surface is NOT the VNC
            // viewer: an ordinary, system-bar-respecting column with a URL
            // header over the platform WebView. The app bar stays visible
            // (the visible condition above hides it only for the VNC session),
            // because a web page has its own chrome and the back arrow is how
            // the user leaves the mode.
            is DesktopUiState.WebRunning -> {
                val web = desktopState as DesktopUiState.WebRunning
                DesktopWebViewPanel(
                    url = web.url,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            // Transformation spec §13: an external-transport desktop renders
            // its pixels in the termux-x11 app — the screen shows a status
            // panel with an explicit "Open display" affordance instead of a
            // viewer that has nothing to connect to.
            is DesktopUiState.RunningExternal -> {
                val external = desktopState as DesktopUiState.RunningExternal
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_external_running, external.provider),
                    isStarting = false,
                    onStart = { viewModel.openTermuxX11App() },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            is DesktopUiState.Starting -> {
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_starting),
                    isStarting = true,
                    onStart = {},
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            // §1.85: Xvnc is missing and the guest package manager is
            // installing it — a distinct progress state (install can take a
            // while) so the user isn't shown a dead "Starting…" forever.
            is DesktopUiState.InstallingDependencies -> {
                // § shared live-progress (2026-09-02): the Xvnc auto-install
                // now shows the SAME visual language as the RootFS install
                // flow (spinner + phase + big indeterminate rail), not a
                // bare spinner. The rail stays indeterminate deliberately:
                // the guest's package manager emits no percentage this app
                // can parse without fabricating one.
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_xvnc_installing),
                    isStarting = true,
                    onStart = {},
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                    progressRail = true
                )
            }

            is DesktopUiState.Error -> {
                val errorMsg = (desktopState as DesktopUiState.Error).reason
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_error, errorMsg),
                    isStarting = false,
                    onStart = { viewModel.startDesktop(workspaceId) },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            // Desktop Backends (§28): an X11 failure is reported explicitly
            // and offers the VNC fallback as a visible, deliberate choice —
            // never a silent backend switch underneath the user.
            is DesktopUiState.X11Failed -> {
                val errorMsg = (desktopState as DesktopUiState.X11Failed).reason
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_x11_failed, errorMsg),
                    isStarting = false,
                    onStart = { viewModel.startDesktop(workspaceId) },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    fallbackLabel = stringResource(R.string.desktop_x11_fallback_vnc),
                    onFallback = { viewModel.startDesktop(workspaceId, GuiBackend.VNC) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            // §1.114: the workspace has an X server but no X client. Not an
            // Error — nothing failed, and Linexa deliberately does not choose a
            // desktop environment for the user, so this states what to install
            // rather than offering a retry that would fail identically.
            is DesktopUiState.NoSession -> {
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_no_session),
                    isStarting = false,
                    onStart = { viewModel.startDesktop(workspaceId) },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            is DesktopUiState.Stopped -> {
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = stringResource(R.string.desktop_stopped),
                    isStarting = false,
                    onStart = { viewModel.startDesktop(workspaceId) },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            else -> {
                DesktopPlaceholder(
                    workspaceId = workspaceId,
                    stateLabel = when (desktopState) {
                        is DesktopUiState.Unbound -> stringResource(R.string.common_not_connected)
                        is DesktopUiState.Connected -> stringResource(R.string.common_ready)
                        else -> stringResource(R.string.common_unknown)
                    },
                    isStarting = false,
                    onStart = { viewModel.startDesktop(workspaceId) },
                    onStop = { viewModel.stopDesktop(workspaceId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
            }
        }
    }
}

@Composable
private fun DesktopPlaceholder(
    workspaceId: String,
    stateLabel: String,
    isStarting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackLabel: String? = null,
    onFallback: (() -> Unit)? = null,
    /** § shared live-progress (2026-09-02): the big indeterminate rail. */
    progressRail: Boolean = false
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .size(220.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LinexaIcons.DesktopWindows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.desktop_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.desktop_start_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (isStarting) {
            // § progress unification (2026-08-28): the concept's desktop-start
            // state shows the ring spinner inline ("Starting desktop… +
            // .spin.sm") — the Haven spinner IS the canonical indicator now.
            com.fem.linexa.ui.components.haven.HSpinner()
            Spacer(modifier = Modifier.height(12.dp))
        }

        StatusChip(text = stateLabel)

        // § shared live-progress (2026-09-02): when real per-package or
        // percentage data is unavailable (the Xvnc auto-install's apt/dpkg
        // output carries no parseable progress), the shared big
        // indeterminate rail communicates liveness without fabricating a
        // number. Determinate values are never faked here.
        if (progressRail && isStarting) {
            Spacer(modifier = Modifier.height(16.dp))
            com.fem.linexa.ui.components.haven.HProgress(
                fraction = null,
                big = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = !isStarting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.desktop_start))
            }
            OutlinedButton(
                onClick = onStop,
                enabled = !isStarting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.desktop_stop))
            }
        }

        // Desktop Backends (§28): the X11-failure fallback — an explicit,
        // user-chosen switch to the VNC compatibility path.
        if (fallbackLabel != null && onFallback != null) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onFallback,
                enabled = !isStarting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(fallbackLabel)
            }
        }
    }
}
