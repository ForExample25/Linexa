package com.fem.linexa.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.terminal.TerminalChromePolicy
import com.fem.linexa.domain.terminal.TerminalWindow
import com.fem.linexa.domain.terminal.TerminalWindowUiState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.ui.components.LinexaAlertDialog
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HDot
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HavenExtraKeys
import com.fem.linexa.ui.components.haven.HavenTerminalModifiers
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The workspace terminal as a small desktop window manager.
 *
 * Every open terminal is one window: its own title bar (identity icon, title,
 * live-process dot, close/minimize/recents), its own pty-backed
 * [RealTerminalView], and its own entry in the Recent Sessions overview. The
 * desktop itself owns only presentation ([com.fem.linexa.domain.terminal.TerminalWindowManager]);
 * the pty/process lifetime stays with the runtime, so minimizing or switching
 * windows never recreates or closes a session.
 *
 * 2026-11 Settings pass — IME independence:
 * `imePadding()` applies to the TERMINAL column only. The Recent Sessions
 * layer is a sibling of that column, so it always measures against the full
 * window and a card's geometry can never change when the keyboard opens or
 * closes. The panel dismisses the keyboard on entry anyway, but the layout no
 * longer depends on that timing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceTerminalPanel(
    workspaceId: String,
    workspaceName: String,
    activeTerminalId: String,
    onActiveTerminalIdChange: (String) -> Unit,
    showOverview: Boolean,
    onShowOverviewChange: (Boolean) -> Unit,
    binder: RuntimeBinder?,
    onDismiss: () -> Unit,
    onGoHome: () -> Unit = onDismiss,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
    terminalsViewModel: TerminalsViewModel = hiltViewModel(),
    uiSettingsViewModel: TerminalUiSettingsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val ptySession by viewModel.ptySession.collectAsState()
    val terminalState by viewModel.terminalState.collectAsState()
    val terminalItems by terminalsViewModel.items.collectAsState()
    val desktop by terminalsViewModel.desktop.collectAsState()
    val isCreating by terminalsViewModel.isCreating.collectAsState()
    val createdTerminalId by terminalsViewModel.createdTerminal.collectAsState()
    val recentSessionsLayout by uiSettingsViewModel.recentSessionsLayout.collectAsState()
    val toolbarSettings by uiSettingsViewModel.toolbarSettings.collectAsState()

    // Both overview positions survive leaving/re-entering the layer while the
    // terminal desktop stays mounted — and the pager is hoisted here so
    // switching the layout mode can never recreate the sessions it presents.
    val overviewGridState = rememberLazyGridState()
    val overviewPagerState = rememberPagerState(pageCount = { desktop.windows.size + 1 })
    var pendingCloseTerminalId by remember { mutableStateOf<String?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val activeWindow = desktop.activeWindow
    val terminalModifiers = remember { HavenTerminalModifiers() }
    val terminalHandleRef = remember { mutableStateOf<TerminalHandle?>(null) }
    val motions = LocalHavenMotions.current

    // The terminal window and Recent Terminals are two states of ONE surface.
    // The live terminal (its AndroidView, pty and scrollback untouched) shrinks
    // and fades toward the top while the overview scales up over it. Selecting
    // a card runs the same transform backwards, so the chosen session appears
    // to expand into the terminal instead of being swapped in. This is a
    // layer transform, NOT an AnimatedContent: the terminal view is never
    // removed from composition, re-measured into a new hierarchy or recreated.
    val overviewProgress by animateFloatAsState(
        targetValue = if (showOverview) 1f else 0f,
        animationSpec = motions.containerSpec(),
        label = "terminal-container"
    )

    // Attach the session list to the shared binder once live.
    LaunchedEffect(workspaceId, binder) {
        if (binder != null) {
            Logger.d("TerminalChain", "Terminal session list attaching binder for $workspaceId")
            terminalsViewModel.attachBinder(workspaceId, binder)
        }
    }

    // A create/replacement publishes its id: focus that window immediately.
    LaunchedEffect(createdTerminalId) {
        val createdId = createdTerminalId
        if (createdId != null) {
            terminalsViewModel.consumeCreatedTerminal()
            terminalsViewModel.activateTerminal(createdId)
            onActiveTerminalIdChange(createdId)
            onShowOverviewChange(false)
        }
    }

    // External open intents (app launch, notification deep link, hoisted state)
    // focus the matching window. If the window list has not caught up yet the
    // ViewModel remembers the id and applies it on its next sync.
    LaunchedEffect(workspaceId, binder, activeTerminalId) {
        if (binder != null) terminalsViewModel.restoreTerminal(activeTerminalId)
    }

    // The desktop's focused window is the source of truth for what is rendered;
    // mirror it back to the hoisted id so deep links and process-death restore
    // keep pointing at the window the user actually sees.
    LaunchedEffect(activeWindow?.terminalId) {
        val focused = activeWindow?.terminalId
        if (focused != null && focused != activeTerminalId) {
            onActiveTerminalIdChange(focused)
        }
    }

    // The previously rendered terminal disappeared -> focus a live sibling.
    LaunchedEffect(terminalItems, activeTerminalId) {
        if (terminalItems.isNotEmpty() && terminalItems.none { it.terminalId == activeTerminalId }) {
            val fallback = desktop.activeWindow?.terminalId ?: terminalItems.first().terminalId
            Logger.i("TerminalChain", "Rendered terminal $activeTerminalId is gone — switching to $fallback")
            onActiveTerminalIdChange(fallback)
        }
    }

    // Attach the one rendered TerminalView to exactly the active window.
    LaunchedEffect(workspaceId, activeWindow?.terminalId, binder) {
        val terminalId = activeWindow?.terminalId
        if (binder == null) {
            Logger.d("TerminalChain", "Terminal waiting for binder (workspaceId=$workspaceId)")
            viewModel.detachBinder()
            return@LaunchedEffect
        }
        if (terminalId == null) {
            // The desktop is idle (every window minimized) or the last window
            // closed. Detach the view only — the runtime's ptys keep running.
            viewModel.detachBinder()
            return@LaunchedEffect
        }
        Logger.d("TerminalChain", "Terminal attaching binder for $workspaceId/$terminalId")
        viewModel.attachBinder(workspaceId, terminalId, binder)
        binder.markTerminalActive(workspaceId, terminalId)
    }

    // Preview polling is overview-only: the live window already paints itself.
    LaunchedEffect(showOverview, workspaceId, binder) {
        terminalsViewModel.setOverviewVisible(showOverview && binder != null)
    }
    // Leaving the terminal input context for Sessions must not leave the IME
    // over the grid. Hide it immediately (no artificial wait before the
    // transition) and clear the terminal's own focus so the overview opens at
    // its final size. Returning to a terminal deliberately does NOT raise the
    // keyboard again: the user taps the terminal when they want to type.
    LaunchedEffect(showOverview) {
        if (showOverview) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    DisposableEffect(terminalsViewModel) {
        onDispose { terminalsViewModel.setOverviewVisible(false) }
    }

    // Closing ends that terminal only. If it was the last window the user goes
    // back to the workspace home (the runtime still opens its never-zero
    // replacement shell in the background, so reopening shows a fresh one).
    val performClose: (String) -> Unit = { terminalId ->
        val goHome = TerminalChromePolicy.returnsHomeAfterClose(
            openWindowCount = desktop.windows.count { !it.isClosing },
            closingIsOpen = desktop.windows.any { it.terminalId == terminalId && !it.isClosing }
        )
        terminalsViewModel.closeTerminal(terminalId)
        if (goHome) onGoHome()
    }
    val requestClose: (String) -> Unit = { terminalId ->
        if (terminalsViewModel.requiresCloseConfirmation(terminalId)) {
            pendingCloseTerminalId = terminalId
        } else {
            performClose(terminalId)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // IME padding belongs to the TERMINAL column only — never to
                // the overview sibling below. The keyboard re-flows the
                // terminal and its shortcut bar; it cannot change the size of
                // a Recent Sessions card.
                .imePadding()
                .graphicsLayer {
                    val progress = overviewProgress
                    val scale = 1f - 0.14f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - progress
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    // Read the transform in the draw phase only: the terminal
                    // view keeps its own measure/layout pass while the layer
                    // shrinks, so a running shell never sees a reflow.
                }
        ) {
            // ONE merged bar: window identity + live state on the left,
            // Minimize / Recents / Close on the right.
            TerminalWindowTopBar(
                window = activeWindow,
                workspaceName = workspaceName,
                windowCount = desktop.windows.size,
                // Minimize = go home. The window is deliberately left ACTIVE in
                // the desktop state, so reopening the terminal restores exactly
                // this window; the pty is never touched.
                onMinimize = onGoHome,
                onOpenOverview = {
                    // Hide the IME in the same frame the overview is requested;
                    // the transform runs immediately, not after a keyboard
                    // dismiss animation.
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    onShowOverviewChange(true)
                },
                onClose = { activeWindow?.let { requestClose(it.terminalId) } }
            )

            Box(modifier = Modifier.weight(1f)) {
                if (activeWindow != null) {
                    TerminalWindowSurface(
                        ptySession = ptySession,
                        terminalState = terminalState,
                        terminalModifiers = terminalModifiers,
                        onViewReady = { terminalHandleRef.value = it }
                    )
                } else {
                    TerminalDesktopIdle(
                        minimizedWindows = desktop.minimizedWindows,
                        isCreating = isCreating,
                        onCreateTerminal = terminalsViewModel::createTerminal,
                        onOpenOverview = { onShowOverviewChange(true) }
                    )
                }
            }

            if (activeWindow != null) {
                HavenExtraKeys(
                    modifiers = terminalModifiers,
                    config = toolbarSettings.config,
                    onModifierTap = { key ->
                        key.modifier?.let { terminalModifiers.toggle(it) }
                    },
                    onKey = { key ->
                        val code = key.keyCode ?: return@HavenExtraKeys
                        // Keys dispatch only — the Keyboard control owns the IME.
                        terminalHandleRef.value?.dispatchTerminalKeyCode(
                            code,
                            key.dispatchMetaState()
                        )
                    }
                )
            }
        }

        // Recent Sessions rises over the desktop as one continuous layer. It
        // is deliberately OUTSIDE the IME-padded column above.
        TerminalOverviewLayer(
            visible = showOverview,
            windows = desktop.windows,
            activeTerminalId = activeWindow?.terminalId,
            isCreating = isCreating,
            layout = recentSessionsLayout,
            gridState = overviewGridState,
            pagerState = overviewPagerState,
            onSelectWindow = { terminalId ->
                terminalsViewModel.restoreTerminal(terminalId)
                onActiveTerminalIdChange(terminalId)
                onShowOverviewChange(false)
            },
            onCreateTerminal = terminalsViewModel::createTerminal,
            onCloseWindow = requestClose,
            onDismiss = { onShowOverviewChange(false) }
        )
    }

    val pendingClose = pendingCloseTerminalId?.let { desktop.window(it) }
    if (pendingCloseTerminalId != null && pendingClose != null) {
        LinexaAlertDialog(
            title = stringResource(R.string.terminal_window_close_title),
            message = stringResource(R.string.terminal_window_close_message, pendingClose.title),
            confirmLabel = stringResource(R.string.terminal_window_close),
            destructive = true,
            onConfirm = {
                val terminalId = pendingCloseTerminalId
                pendingCloseTerminalId = null
                if (terminalId != null) performClose(terminalId)
            },
            onDismiss = { pendingCloseTerminalId = null }
        )
    }
}

/**
 * The overview layer as its own composable so it is NOT resolved as the
 * `ColumnScope.AnimatedVisibility` overload: the layer is painted as a
 * sibling over the whole terminal panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalOverviewLayer(
    visible: Boolean,
    windows: List<TerminalWindow>,
    activeTerminalId: String?,
    isCreating: Boolean,
    layout: RecentSessionsLayout,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    pagerState: PagerState,
    onSelectWindow: (String) -> Unit,
    onCreateTerminal: () -> Unit,
    onCloseWindow: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val motions = LocalHavenMotions.current
    // The overview is the terminal's "container destination": it scales up
    // from a slightly smaller frame while the terminal layer shrinks toward
    // it. One transform plus one opacity per layer — no slide, no stacked
    // third effect. The exit grows a touch as it fades so a selected card
    // reads as opening into the terminal underneath.
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            animationSpec = motions.containerSpec(),
            initialScale = 0.96f,
            transformOrigin = TransformOrigin(0.5f, 0.35f)
        ) + fadeIn(animationSpec = motions.containerSpec()),
        exit = scaleOut(
            animationSpec = motions.containerSpec(),
            targetScale = 1.02f,
            transformOrigin = TransformOrigin(0.5f, 0.35f)
        ) + fadeOut(animationSpec = motions.containerSpec())
    ) {
        TerminalSessionOverview(
            windows = windows,
            activeTerminalId = activeTerminalId,
            isCreating = isCreating,
            gridState = gridState,
            pagerState = pagerState,
            layout = layout,
            onSelectWindow = onSelectWindow,
            onCreateTerminal = onCreateTerminal,
            onCloseWindow = onCloseWindow,
            onDismiss = onDismiss
        )
    }
}

/**
 * The desktop's thin top bar: workspace identity on the left, Recent Terminals
 * (with the live window count) and New Terminal on the right. No overflow menu,
 * no session-chip strip and no keyboard icon — those belonged to the old
 * single-session chrome.
 */
@Composable
private fun TerminalWindowTopBar(
    window: TerminalWindow?,
    workspaceName: String,
    windowCount: Int,
    onMinimize: () -> Unit,
    onOpenOverview: () -> Unit,
    onClose: () -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(h.surface)
            .height(HavenDim.tbarH)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (window != null) {
            TerminalWindowIdentityIcon(window = window, size = 32.dp, iconSize = 18.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = window?.title ?: workspaceName,
                style = HavenType.small.copy(fontWeight = FontWeight.SemiBold),
                color = h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (window != null) {
                val windowStatus = terminalWindowStatusText(window)
                // The window title above is the identity; repeating the
                // workspace name here was pure chrome. State stays the dot
                // (its accessible name carries the word), and the only second
                // line is a real foreground process when there is one.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    HDot(
                        color = terminalWindowStatusColor(window),
                        size = 6f,
                        modifier = Modifier.semantics {
                            contentDescription = windowStatus
                        }
                    )
                    val detail = window.foregroundProcess?.takeIf { it.isNotBlank() }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = HavenType.desc.copy(fontFamily = HavenType.mono),
                            color = h.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        // Minimize = back to the workspace home; the shell keeps running.
        TerminalWindowControl(
            iconRes = R.drawable.ic_haven_minus,
            contentDescription = stringResource(R.string.terminal_window_minimize),
            onClick = onMinimize
        )
        // The square opens Recent Terminals (it is not maximize/restore); the
        // badge is the open-window count.
        TerminalWindowControl(
            iconRes = R.drawable.ic_haven_maximize,
            contentDescription = stringResource(R.string.terminals_recent_title),
            onClick = onOpenOverview,
            badge = windowCount
        )
        if (window != null) {
            TerminalWindowControl(
                iconRes = R.drawable.ic_haven_close,
                contentDescription = stringResource(R.string.terminal_window_close),
                onClick = onClose,
                danger = true
            )
        }
    }
}

/**
 * The window body: the real terminal surface, full-bleed under the merged top
 * bar. The pty itself is untouched by any chrome change.
 */
@Composable
private fun TerminalWindowSurface(
    ptySession: PtySession?,
    terminalState: TerminalState?,
    terminalModifiers: HavenTerminalModifiers,
    onViewReady: (TerminalHandle?) -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(h.term)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(h.term)
        ) {
            // Deliberately NOT keyed on window.terminalId: the AndroidView
            // factory must run once for this panel, and RealTerminalView's
            // update block re-attaches the new pty session to the SAME view.
            // Keying here used to destroy/recreate the terminal view on every
            // session switch — the exact expensive recreation the overview
            // transform is designed to avoid.
            val liveSession = ptySession
            if (liveSession != null) {
                var unavailable by remember(liveSession.id) { mutableStateOf(false) }
                if (!unavailable) {
                    RealTerminalView(
                        ptySession = liveSession,
                        modifiers = terminalModifiers,
                        onViewReady = onViewReady,
                        modifier = Modifier.fillMaxSize(),
                        onUnavailable = { unavailable = true }
                    )
                } else {
                    TerminalStatusPlaceholder(
                        message = stringResource(R.string.terminal_unavailable),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                TerminalStatusPlaceholder(
                    message = placeholderMessage(terminalState),
                    modifier = Modifier.fillMaxSize(),
                    footer = if ((terminalState as? TerminalState.Error)?.killedBySigkill == true) {
                        { TerminalKilledNotice() }
                    } else {
                        null
                    },
                    showProgress = terminalState is TerminalState.Starting ||
                        terminalState is TerminalState.Running
                )
            }
        }
    }
}

/**
 * One title-bar window control. Hover (mouse / stylus) and pressed (touch)
 * both get an animated background; Close turns red so the destructive action
 * is unmistakable. The 40dp target keeps it touch-friendly on a phone.
 */
@Composable
private fun TerminalWindowControl(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    badge: Int? = null
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val highlighted = pressed || hovered
    val background by animateColorAsState(
        targetValue = when {
            danger && highlighted -> h.red
            highlighted -> h.text2.copy(alpha = if (pressed) 0.22f else 0.12f)
            else -> Color.Transparent
        },
        animationSpec = motions.quickSpec(),
        label = "window-control-background"
    )
    val tint by animateColorAsState(
        targetValue = if (danger && highlighted) Color.White else h.text2,
        animationSpec = motions.quickSpec(),
        label = "window-control-tint"
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        if (badge != null && badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                    .clip(CircleShape)
                    .background(h.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge.toString(),
                    style = HavenType.desc.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
        }
    }
}

/**
 * The idle desktop, shown when every window is minimized (or before the first
 * terminal sync). It offers the two honest exits: restore through Recent
 * Terminals, or open a fresh window.
 */
@Composable
private fun TerminalDesktopIdle(
    minimizedWindows: List<TerminalWindow>,
    isCreating: Boolean,
    onCreateTerminal: () -> Unit,
    onOpenOverview: () -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(h.term)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(h.surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_haven_terminal),
                contentDescription = null,
                tint = h.text2,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = if (minimizedWindows.isEmpty()) {
                stringResource(R.string.terminal_desktop_idle_empty)
            } else {
                stringResource(R.string.terminal_desktop_idle_minimized, minimizedWindows.size)
            },
            style = HavenType.h2.copy(fontSize = 20.sp),
            color = h.text,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = stringResource(R.string.terminal_desktop_idle_body),
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(
            modifier = Modifier.padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (minimizedWindows.isNotEmpty()) {
                HButton(
                    label = stringResource(R.string.terminals_recent_title),
                    onClick = onOpenOverview,
                    variant = HavenButtonVariant.SECONDARY
                )
            }
            HButton(
                label = stringResource(R.string.terminals_new),
                onClick = onCreateTerminal,
                enabled = !isCreating,
                variant = HavenButtonVariant.PRIMARY,
                icon = {
                    if (isCreating) {
                        HSpinner(small = true, color = h.onPrimary)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_haven_add),
                            contentDescription = null,
                            tint = h.onPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            )
        }
    }
}

/** App identity tile or the generic terminal glyph. */
@Composable
internal fun TerminalWindowIdentityIcon(
    window: TerminalWindow,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp
) {
    val h = HavenTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(h.tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(window.iconRes ?: R.drawable.ic_haven_terminal),
            contentDescription = null,
            tint = h.primary,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Honest one-line UI/process status for a window title bar or overview card. */
@Composable
internal fun terminalWindowStatusText(window: TerminalWindow): String = when {
    window.uiState == TerminalWindowUiState.MINIMIZED ->
        stringResource(R.string.terminal_window_minimized)
    window.uiState == TerminalWindowUiState.CLOSING ->
        stringResource(R.string.terminal_window_closing)
    window.isMaximized ->
        stringResource(R.string.terminal_window_maximized)
    else -> processStateText(window.processState)
}

@Composable
private fun processStateText(state: TerminalState): String = when (state) {
    is TerminalState.Running -> stringResource(R.string.terminal_state_running)
    is TerminalState.Starting -> stringResource(R.string.terminal_state_starting)
    is TerminalState.Stopping -> stringResource(R.string.terminal_state_stopping)
    is TerminalState.Stopped -> stringResource(R.string.terminal_state_stopped)
    is TerminalState.Error -> stringResource(R.string.terminal_state_error)
}

@Composable
internal fun terminalWindowStatusColor(window: TerminalWindow): Color {
    val h = HavenTheme.colors
    return when (window.processState) {
        is TerminalState.Running -> h.primary
        is TerminalState.Starting -> h.blue
        is TerminalState.Stopping -> h.amber
        is TerminalState.Stopped -> h.muted
        is TerminalState.Error -> h.red
    }
}

@Composable
private fun placeholderMessage(terminalState: TerminalState?): String = when (terminalState) {
    is TerminalState.Error -> stringResource(R.string.terminal_session_error, terminalState.reason)
    is TerminalState.Stopped -> stringResource(R.string.terminal_state_stopped)
    is TerminalState.Stopping -> stringResource(R.string.terminal_state_stopping)
    is TerminalState.Starting -> stringResource(R.string.terminal_state_starting)
    is TerminalState.Running -> stringResource(R.string.terminal_state_running)
    null -> stringResource(R.string.terminal_unavailable)
}

/**
 * The per-terminal state placeholder in Haven colors — honest state text,
 * a spinner only while the state is genuinely still resolving, and the
 * SIGKILL footer where it applies.
 */
@Composable
private fun TerminalStatusPlaceholder(
    message: String,
    modifier: Modifier,
    footer: (@Composable () -> Unit)? = null,
    showProgress: Boolean = true
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showProgress) {
            HSpinner(
                modifier = Modifier.padding(bottom = 16.dp),
                color = h.primary
            )
        } else {
            Spacer(modifier = Modifier.size(40.dp))
        }
        Text(
            text = message,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = HavenType.mono,
                fontSize = 13.sp
            ),
            color = h.termDim
        )
        if (footer != null) {
            Spacer(modifier = Modifier.size(TerminalKilledNoticeSpacing))
            footer()
        }
    }
}
