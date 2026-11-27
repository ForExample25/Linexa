package com.fem.linexa.ui.screens.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.data.Feature
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.commands.CustomCommand
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.ui.components.TerminalOverlay
import com.fem.linexa.ui.components.DistroIdentity
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HSegmented
import com.fem.linexa.ui.components.haven.HDot
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.swipeNavigation
import com.fem.linexa.ui.components.haven.workspaceStatusPresentation
import com.fem.linexa.ui.screens.terminal.TerminalSelectionSource
import com.fem.linexa.ui.screens.terminal.TerminalsViewModel
import com.fem.linexa.ui.screens.terminal.WorkspaceTerminalPanel
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The workspace surface — HAVEN edition (concept section 02).
 *
 * The workspace is a coherent parent context with contextual actions in its
 * header (Terminal + Desktop circles, per the concept frame `fr-ws`), the
 * distro glyph + name + status line, and the four-way pill segment
 * (Apps / Activity / Features / Settings). The Apps segment is the app
 * launcher; its tool pair — Packages / Files — pushes the SAME existing
 * destinations the old Settings-tab Tools card opened (2026-11 pass moved
 * the entry point, not the destinations or screens).
 *
 * 2026-11 motion pass: the four segment bodies are an [AnimatedContent] that
 * quarter-slides directionally with a quiet fade under the FIXED
 * header + selector, and the graph's sub-destination pushes (Packages /
 * Files / Desktop / Task Manager) carry the shared motion tokens of
 * [LocalHavenMotions] (see `Motion.kt` and WorkspaceNavGraph).
 *
 * The terminal stays an overlay (§1.133) rendered as a SIBLING of the
 * workspace content inside the Haven content region — never a window takeover.
 * The app bar and the segment body stay composed at a stable size while the
 * overlay slides over them (the bar fades out and stops accepting input), so
 * opening a terminal never re-measures the terminal surface or the workspace
 * beneath it. System back inside the workspace pops one overlay layer first
 * (sheet → overview → terminal), then one Commands drill-in level, then
 * reaches the graph's exit confirmation — never an unconditional jump Home.
 */
@Composable
fun WorkspaceScreen(
    workspace: Workspace,
    /**
     * The assembled Commands tree (built-in catalog / declared image
     * shortcuts + the user's own section), built by the ViewModel — the UI
     * renders it, it never assembles or selects commands itself.
     */
    commands: List<CommandNode>,
    /** The workspace's saved user commands, in storage order (edit/delete surface). */
    customCommands: List<CustomCommand>,
    features: List<Feature>,
    featureToggles: Map<String, Boolean>,
    featureSettings: FeatureSettings,
    runtimeState: RuntimeState,
    runtimeTraceText: String,
    desktopEnabled: Boolean,
    /** 2026-09-02: the desktop-enable refusal notice, rendered on the Desktop row. */
    desktopToggleBlocked: Boolean = false,
    debugEnabled: Boolean,
    binder: RuntimeBinder?,
    terminalOpen: Boolean,
    onTerminalOpenChange: (Boolean) -> Unit,
    activeTerminalId: String,
    onActiveTerminalIdChange: (String) -> Unit,
    // Monotonic from WorkspaceNavGraph: increments only when an external
    // intent (deep link/bus) asks for a terminal. The panel never writes this,
    // so the hoisted id can no longer feed a stale focus request back into the
    // desktop.
    terminalRequestVersion: Long = 0,
    // 2026-08-31 (Phase 5/7): the session-list visibility is hoisted to
    // WorkspaceNavGraph so the notification deep-link/bus retarget can close
    // the list deterministically — a tap that arrives while the overview is
    // up must surface the retargeted terminal, not leave it hidden under the
    // list.
    showOverview: Boolean,
    onShowOverviewChange: (Boolean) -> Unit,
    onSetDebugEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
    // The header's back circle must go through the SAME exit confirmation the
    // system back hits — never a bare pop that silently stops the runtime.
    onRequestExit: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenPackageManager: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenTaskManager: () -> Unit,
    onFeatureToggle: (featureId: String, enabled: Boolean) -> Unit,
    onFeatureSettingsChange: (FeatureSettings) -> Unit,
    networkSettings: NetworkSettings,
    onNetworkSettingsChange: (NetworkSettings) -> Unit,
    onSetTerminalPersistence: (Boolean) -> Unit,
    onWorkspaceDeleted: () -> Unit,
    // Custom (user) command mutations — the ViewModel persists them through
    // CustomCommandsStore; the screen only forwards the intent.
    onAddCustomCommand: (name: String, command: String) -> Unit,
    onUpdateCustomCommand: (id: String, name: String, command: String) -> Unit,
    onDeleteCustomCommand: (id: String) -> Unit,
    // Hoisted for the crash-repro harness (Robolectric has no Hilt graph);
    // production resolves them exactly as before through the defaults.
    shortcutsViewModel: ShortcutsViewModel = hiltViewModel(),
    pinsViewModel: ShortcutPinsViewModel = hiltViewModel(),
    featuresViewModel: FeaturesViewModel = hiltViewModel(),
    // App Launcher redesign: the Apps tab's VMs (install tracking + launching).
    launcherViewModel: AppLauncherViewModel = hiltViewModel(),
    installTracker: AppInstallTracker = hiltViewModel(),
    // Sessions segment: real sessions + the task-manager resource/process view.
    sessionsViewModel: SessionsViewModel = hiltViewModel(),
    // Pre-attached here (same nav-entry scope as the panel's instance) so the
    // terminal desktop is populated before the overlay first composes.
    terminalsViewModel: TerminalsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    // 2026-08-30 (Issue 1 — terminal inside the app chrome): the terminal is
    // APP-layer content that lives in the Haven content region. It no longer
    // takes over the window: the status bar strip is owned by the Haven screen
    // (theme background, theme-controlled icon appearance from LinexaTheme).
    // 2026-11 (Root Terminal stability): the overlay is a SIBLING of the
    // workspace content, and the app bar stays composed at its final size —
    // it fades out and disables its circles while the overlay covers it — so
    // opening or closing the terminal never re-measures the workspace beneath
    // it or the terminal surface itself. The previous per-screen
    // status-bar appearance patch existed only because the terminal
    // painted behind the status bar; with the terminal inset below the app
    // chrome the global theme value is always correct, and the patch also had
    // a stale-closure restore bug (its onDispose read the OLD terminalOpen, so
    // the light-theme appearance was never restored on close). No insets are
    // hidden anywhere on this screen — no immersive flags exist for the
    // terminal.
    //
    // The four-way segment (Apps / Sessions / Features / Settings) — the
    // concept's `.segwrap`. Saved per workspace so re-entry lands on Apps.
    var selectedSegment by rememberSaveable(workspace.id) { mutableStateOf(0) }

    // Back precedence inside the workspace (later-registered wins):
    //   terminal sheet (internal ModalBottomSheet) → session overview →
    //   terminal overlay → graph exit confirmation.
    val dismissOneLayer: () -> Unit = {
        if (showOverview) onShowOverviewChange(false) else onTerminalOpenChange(false)
    }
    BackHandler(enabled = terminalOpen) { dismissOneLayer() }
    // The header back circle: dismisses the terminal surface when it is the
    // topmost layer; with everything closed it requests the exit
    // confirmation (same dialog system back shows).
    val onHeaderBack: () -> Unit = {
        when {
            showOverview -> onShowOverviewChange(false)
            terminalOpen -> onTerminalOpenChange(false)
            else -> onRequestExit()
        }
    }

    // App Launcher redesign: attach the launcher's VMs to the same binder the
    // rest of the workspace surfaces use, resolve the catalog branch for THIS
    // workspace's distro identity (the same QuickDistroKeys the Commands
    // assembler uses), and publish the app corpus to the install tracker so
    // it can batch-probe install states.
    LaunchedEffect(workspace.id, binder) {
        if (binder != null) {
            launcherViewModel.attach(workspace.id, binder)
            installTracker.attach(workspace.id, binder)
            installTracker.observeJobs()
            val distroKey = com.fem.linexa.service.commands.QuickDistroKeys.distroKey(
                declaredDistroId = workspace.distroCode,
                catalogId = workspace.distroId,
                probedName = workspace.distroName
            )
            val versionKey = com.fem.linexa.service.commands.QuickDistroKeys.versionKey(
                declaredVersion = workspace.distroVersion,
                persistedVersion = workspace.distroVersion
            )
            launcherViewModel.setDistro(distroKey, versionKey)
        }
    }
    val launcherApps by launcherViewModel.state.collectAsState()
    LaunchedEffect(launcherApps.apps) {
        installTracker.setApps(launcherApps.apps.map { it.app })
    }

    // Root Terminal stability: the panel is no longer the first caller of
    // attachBinder(), and selection is no longer restored from the hoisted id
    // on every change. Attaching the SAME nav-scoped VM here (attachBinder is
    // idempotent) populates the desktop before the overlay composes; external
    // requests below go through the ViewModel's single authority.
    LaunchedEffect(workspace.id, binder) {
        if (binder != null) terminalsViewModel.attachBinder(workspace.id, binder)
    }
    // External intent version: only NavGraph deep links/bus requests bump it.
    // Applying it once per value keeps a configuration change from replaying a
    // stale focus request over a pending Root Shell selection.
    var appliedTerminalRequestVersion by rememberSaveable(workspace.id) {
        mutableLongStateOf(0L)
    }
    LaunchedEffect(terminalRequestVersion, workspace.id) {
        if (terminalRequestVersion <= appliedTerminalRequestVersion) return@LaunchedEffect
        appliedTerminalRequestVersion = terminalRequestVersion
        terminalsViewModel.requestActiveTerminal(
            activeTerminalId,
            TerminalSelectionSource.ExternalOpenRequest
        )
    }
    // Process-death restore: the saved id is applied once when the binder is
    // available. It cannot replace a pending Root Shell request.
    var restoreRequested by rememberSaveable(workspace.id) { mutableStateOf(false) }
    LaunchedEffect(workspace.id, binder, terminalOpen) {
        if (binder == null || !terminalOpen || restoreRequested) return@LaunchedEffect
        restoreRequested = true
        terminalsViewModel.requestActiveTerminal(
            activeTerminalId,
            TerminalSelectionSource.WindowRestore,
            canReplacePending = false
        )
    }
    // 2026-11 motion pass: the segment body is an AnimatedContent now, so
    // every segment's scroll/grid holder is hoisted OUTSIDE it — a segment
    // switch never resets the user's position, because the holders stay
    // composed while only the rendered branch swaps. The terminal overlay is
    // a sibling of the whole body, so opening it does not reset anything
    // either. The Apps tab keeps a lazy-grid state (a lazy grid must never
    // sit inside a vertically scrollable Column); the other three keep
    // plain scroll states (both remember* helpers are saveable across
    // config changes).
    val appsGridState = rememberLazyGridState()
    // The Apps grid's bounded reveal plays once per workspace. Hoisted here
    // (saveable) so a segment switch, filter change or terminal overlay does
    // not replay it; `AppLauncherSegment` consumes it after the first frame.
    var appsRevealPlayed by rememberSaveable(workspace.id) { mutableStateOf(false) }
    val sessionsScroll = rememberScrollState()
    val featuresScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    // The one motion set every animated surface of this screen binds
    // (segment quarter-slide + selection tweens); reduced-motion collapses
    // it to instant swaps (Motion.kt).
    val motions = LocalHavenMotions.current

    // 2026-08-30 (Issue 1): the Haven screen owns the status-bar strip (the
    // default `statusBarInsets = true` paints the theme background behind it
    // and insets the content below it). The workspace header stays composed
    // at its final size for the whole screen's life: opening the terminal
    // fades it out and disables its actions instead of removing it, so the
    // layout beneath the overlay never changes size and the terminal surface
    // is measured once at its final height.
    HavenScreen {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── App bar: back circle · spacer · Terminal + Desktop circles ──
                // Workspace chrome for the CLOSED-terminal state; while the
                // terminal overlay is open it is transparent (the overlay's
                // own HavenTerminalBar is the terminal's chrome) and its
                // circles no longer accept input. System back (the BackHandler
                // above) dismisses the terminal.
                val appBarAlpha by animateFloatAsState(
                    targetValue = if (terminalOpen) 0f else 1f,
                    animationSpec = motions.fade,
                    label = "workspace-app-bar"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = appBarAlpha }
                        .padding(start = HavenDim.hdrH, end = HavenDim.hdrH, top = HavenDim.hdrV, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HIconCircle(
                        iconRes = R.drawable.ic_haven_arrow_back,
                        contentDescription = stringResource(R.string.workspace_back_to_home),
                        onClick = onHeaderBack,
                        enabled = !terminalOpen
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Same gap as every Haven page header (HavenDim.headerActionGap);
                    // this Row used to place the two circles edge to edge.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(HavenDim.headerActionGap)
                    ) {
                        HIconCircle(
                            iconRes = R.drawable.ic_haven_terminal,
                            contentDescription = stringResource(R.string.haven_open_terminal),
                            onClick = { onTerminalOpenChange(!terminalOpen) },
                            enabled = !terminalOpen
                        )
                        if (desktopEnabled) {
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_monitor,
                                contentDescription = stringResource(R.string.haven_open_desktop),
                                onClick = onOpenDesktop,
                                enabled = !terminalOpen
                            )
                        }
                    }
                }
                // The workspace body stays composed under the terminal overlay:
                // its size never changes when the overlay opens, so the segment
                // AnimatedContent and the launcher grid keep their state and
                // measure passes.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = HavenDim.screenH)
                ) {
                    WorkspaceIdentityHeader(
                        workspace = workspace,
                        runtimeState = runtimeState,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    // The four-way selector is FIXED chrome: it never
                    // slides with its content, and its bottom spacing is
                    // constant so the slide's landing spot does not jump
                    // as segments trade places.
                    HSegmented(
                        options = listOf(
                            stringResource(R.string.launcher_segment_apps),
                            stringResource(R.string.haven_segment_sessions),
                            stringResource(R.string.haven_segment_features),
                            stringResource(R.string.haven_segment_settings)
                        ),
                        selected = selectedSegment,
                        onSelect = { selectedSegment = it },
                        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                    )

                    // ── The four-way segment body (2026-11 motion pass) ──
                    // A directional quarter-slide: a forward tap carries
                    // the incoming page in from the trailing edge while
                    // the outgoing is pushed ahead; a backward tap
                    // reverses it. The fade tokens keep the swap quiet
                    // where the pages overlap, the slide tokens carry the
                    // direction, and the reduced-motion path (animator
                    // scale 0) collapses the whole spec to an instant
                    // swap. The container fills the region below the
                    // selector, so no segment ever changes the sheet's
                    // own height mid-transition.
                    AnimatedContent(
                        targetState = selectedSegment,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (fadeIn(animationSpec = motions.fade) +
                                slideInHorizontally(animationSpec = motions.segment) {
                                    if (forward) it / 4 else -it / 4
                                }) togetherWith
                                (fadeOut(animationSpec = motions.fade) +
                                    slideOutHorizontally(animationSpec = motions.segment) {
                                        if (forward) -it / 4 else it / 4
                                    })
                        },
                        label = "workspace-segment",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // Sibling tabs share the app-wide horizontal
                            // swipe contract: vertical scrolling and any
                            // child horizontal surface keep their gesture.
                            .swipeNavigation(
                                onSwipeLeft = {
                                    if (selectedSegment < 3) selectedSegment += 1
                                },
                                onSwipeRight = {
                                    if (selectedSegment > 0) selectedSegment -= 1
                                }
                            )
                    ) { segment ->
                        when (segment) {
                            // App Launcher redesign: the Apps tab — the
                            // icon-grid launcher with one-tap install. A
                            // lazy grid must never sit inside a vertically
                            // scrollable Column; its own container IS the
                            // region (the grid scrolls itself, with the
                            // hoisted grid state above).
                            0 -> AppLauncherSegment(
                                workspaceId = workspace.id,
                                launcherViewModel = launcherViewModel,
                                installTracker = installTracker,
                                pinsViewModel = pinsViewModel,
                                onOpenTerminal = { terminalId, source ->
                                    if (terminalsViewModel.requestActiveTerminal(terminalId, source)) {
                                        onActiveTerminalIdChange(terminalId)
                                        onTerminalOpenChange(true)
                                    }
                                },
                                onAddCustomCommand = onAddCustomCommand,
                                onOpenPackageManager = onOpenPackageManager,
                                onOpenFileManager = onOpenFileManager,
                                gridState = appsGridState,
                                staggerReveal = !appsRevealPlayed,
                                onStaggerConsumed = { appsRevealPlayed = true },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Every other segment is a plain scrolling
                            // page — the same shape the shared container
                            // used to give them, per-segment now so the
                            // launcher grid stays scroller-free.
                            1 -> SegmentScrollPage(scrollState = sessionsScroll) {
                                SessionsSegment(
                                    workspaceId = workspace.id,
                                    binder = binder,
                                    runtimeState = runtimeState,
                                    onOpenTerminal = { terminalId ->
                                        if (terminalsViewModel.requestActiveTerminal(
                                                terminalId,
                                                TerminalSelectionSource.WindowRestore
                                            )
                                        ) {
                                            onActiveTerminalIdChange(terminalId)
                                            onTerminalOpenChange(true)
                                        }
                                    },
                                    viewModel = sessionsViewModel,
                                    onOpenTaskManager = onOpenTaskManager
                                )
                            }
                            2 -> SegmentScrollPage(scrollState = featuresScroll) {
                                FeaturesTab(
                                    features = features,
                                    toggles = featureToggles,
                                    onToggleChange = onFeatureToggle,
                                    settings = featureSettings,
                                    onSettingsChange = onFeatureSettingsChange,
                                    workspaceId = workspace.id,
                                    binder = binder,
                                    desktopToggleBlocked = desktopToggleBlocked,
                                    viewModel = featuresViewModel
                                )
                            }
                            else -> SegmentScrollPage(scrollState = settingsScroll) {
                                HavenWorkspaceSettingsTab(
                                    workspace = workspace,
                                    featureSettings = featureSettings,
                                    runtimeTraceText = runtimeTraceText,
                                    debugEnabled = debugEnabled,
                                    onSetDebugEnabled = onSetDebugEnabled,
                                    networkSettings = networkSettings,
                                    onNetworkSettingsChange = onNetworkSettingsChange,
                                    onSetTerminalPersistence = onSetTerminalPersistence,
                                    onDeleteWorkspace = onWorkspaceDeleted
                                )
                            }
                        }
                    }
                }
            }
            TerminalOverlay(
                visible = terminalOpen,
                modifier = Modifier.fillMaxSize()
            ) {
                // The terminal desktop owns its own chrome: a thin title bar
                // (workspace identity + Recent Sessions + New Terminal) and
                // one title bar per window. The panel draws its own always-dark
                // terminal surface and applies IME padding to the terminal
                // column only — the Sessions overview is a sibling and
                // therefore never resizes with the keyboard.
                WorkspaceTerminalPanel(
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                    onActiveTerminalIdChange = onActiveTerminalIdChange,
                    showOverview = showOverview,
                    onShowOverviewChange = onShowOverviewChange,
                    binder = binder,
                    terminalsViewModel = terminalsViewModel,
                    onDismiss = dismissOneLayer,
                    // Minimize / closing the last terminal: straight back to
                    // the workspace home, whatever layer is currently open.
                    onGoHome = {
                        onShowOverviewChange(false)
                        onTerminalOpenChange(false)
                    },
                    modifier = Modifier.fillMaxSize(),
                    // Finding 011: the terminal error card's Diagnostics
                    // action shows the workspace trace, scoped by the
                    // terminal id in the report header.
                    runtimeTraceText = runtimeTraceText
                )
            }
        }
    }
}

/**
 * The three non-launcher segments render here: one plain vertical-scroll
 * page with the trailing breath of space — exactly the shape the shared
 * scroll container used to give them before the motion pass split each
 * segment into its own page inside the AnimatedContent. The Apps launcher
 * deliberately does NOT use this (a lazy grid must never be nested inside
 * a vertically scrollable Column); [WorkspaceScreen] documents the split.
 */
@Composable
private fun SegmentScrollPage(
    scrollState: ScrollState,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        content()
        Spacer(modifier = Modifier.height(40.dp))
    }
}

/** The glyph + name + status · distro line (concept `.glyph.g52` header). */
@Composable
private fun WorkspaceIdentityHeader(
    workspace: Workspace,
    runtimeState: RuntimeState,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val status = workspaceStatusPresentation(workspace.status, workspace.isRunning, runtimeState)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DistroIdentity(
            distroId = workspace.distroId,
            letter = workspace.avatarLetter,
            color = workspace.avatarColor,
            size = HavenDim.glyphLg
        )
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = workspace.name,
                style = HavenType.h3,
                color = h.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // The dot is the state; its accessible name carries the word.
                // Printing "Running" beside every dot was redundant chrome.
                HDot(
                    color = status.color,
                    size = 6f,
                    pulsing = status.pulsing,
                    modifier = Modifier.semantics {
                        contentDescription = status.label
                    }
                )
                val distroLine = workspace.distroName ?: workspace.description
                if (distroLine.isNotBlank()) {
                    Text(
                        text = distroLine,
                        style = HavenType.desc,
                        color = h.text2,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
