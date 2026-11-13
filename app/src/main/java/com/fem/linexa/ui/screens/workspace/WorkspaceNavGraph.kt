package com.fem.linexa.ui.screens.workspace

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fem.linexa.R
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.navigation.TerminalDeepLinkBus
import com.fem.linexa.service.runtime.RuntimeService
import com.fem.linexa.service.runtime.RuntimeServiceState
import com.fem.linexa.service.runtime.RuntimeViewModel
import com.fem.linexa.ui.components.LinexaAlertDialog
import com.fem.linexa.ui.components.LinexaMessageDialog
import com.fem.linexa.ui.components.LinexaLoadingState
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.screens.desktop.DesktopScreen
import com.fem.linexa.ui.screens.filemanager.FileManagerScreen
import com.fem.linexa.ui.screens.filemanager.FileManagerViewModel
import com.fem.linexa.ui.screens.packagemanager.PackageManagerScreen
import com.fem.linexa.ui.screens.packagemanager.PackageManagerViewModel
import com.fem.linexa.ui.screens.taskmanager.TaskManagerScreen
import com.fem.linexa.ui.screens.taskmanager.TaskManagerViewModel

object WorkspaceRoutes {
    const val SHORTCUTS = "shortcuts"
    // §1.133: there is no `terminal/{terminalId}` route any more. The workspace
    // terminal is an overlay on the SHORTCUTS destination (WorkspaceScreen), not
    // a destination of its own — see WorkspaceChromeState. Which session it shows
    // is `activeTerminalId` state below, not a nav argument.
    //
    // Phase 7 cleanup: FEATURES and SETTINGS routes were removed (dead code).
    // These two workspace screens are tabs inside WorkspaceScreen's selectedTab
    // state, not nav destinations — declaring them as routes led to dead
    // constants that were never referenced anywhere.
    const val DESKTOP = "desktop"
    const val PACKAGE_MANAGER = "package_manager"
    const val FILE_MANAGER = "file_manager"

    /** Task Manager redesign: the dedicated resource monitor screen. */
    const val TASK_MANAGER = "task_manager"
}

/**
 * Single nested navigation graph for a workspace's whole session (§1.2).
 * Desktop/Package Manager/File Manager are sub-screens of the *same* running
 * workspace — navigating between them must not start/stop the runtime.
 *
 * §1.133: the terminal is deliberately **not** among them. It is an overlay on
 * [WorkspaceScreen], so it has no route and no back-stack entry; which session it
 * shows is `activeTerminalId` state owned here (see below for why it is hoisted
 * this far).
 *
 * [WorkspaceDetailViewModel] proves the workspace is usable; [RuntimeViewModel]
 * is scoped to this back-stack entry (created with `hiltViewModel()` here,
 * which it inherits for every sub-destination of the graph) and owns the
 * single RuntimeService bind for the graph's lifetime: it starts the
 * workspace session once when the graph opens (Success), the terminal overlay
 * consumes its binder rather than binding the service itself, and it stops the
 * workspace when the whole graph is popped back to Home (Phase 2 §1.2
 * session-boundary implementation, §1.29).
 *
 * 2026-11 motion pass: the NavHost below overrides all four transition
 * parameters at graph level (`enterTransition`/`exitTransition`/
 * `popEnterTransition`/`popExitTransition`) so every push inside the
 * workspace is one shared motion (Motion.kt tokens, reduced-motion aware);
 * the destinations themselves stay default-free.
 */
@Composable
fun WorkspaceNavGraph(
    workspaceId: String,
    navController: NavHostController,
    onBack: () -> Unit,
    // §1.103: terminal to restore when this graph was opened by the runtime
    // notification's Open action (?terminalId=…). Non-null opens the workspace
    // with that session's terminal overlay already showing (§1.133 — the terminal
    // is no longer a destination, so this is initial *state*, not a start route);
    // null = a normal Home → workspace entry with the overlay closed.
    requestedTerminalId: String? = null,
    viewModel: WorkspaceDetailViewModel = hiltViewModel(),
    runtimeViewModel: RuntimeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // The RuntimeService bind completes asynchronously; `serviceState` reaching
    // Connected recomposes the graph with a live binder for Terminal to use.
    val serviceState by runtimeViewModel.serviceState.collectAsState()
    val binder = runtimeViewModel.currentBinder.takeIf { serviceState is RuntimeServiceState.Connected }
    val workspaceStates by runtimeViewModel.workspaceStates.collectAsState()
    val runtimeState = workspaceStates[workspaceId] ?: RuntimeState.Stopped
    // §1.68: the startup chain trace (persisted + copied from Settings → Debug).
    val runtimeTraceText by viewModel.runtimeTraceText.collectAsState()
    // § Debug: global debug-info toggle, gating the runtime trace section.
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    // 2026-09-02 (desktop prerequisites): the desktop-enable refusal notice —
    // transient ViewModel state, deliberately NOT part of the debounced Room
    // state so a re-emission can never clobber it.
    val desktopToggleBlocked by viewModel.desktopToggleBlocked.collectAsState()
    // §1.117: set when a delete was refused because the runtime couldn't be
    // confirmed stopped — nothing was deleted, so we stay put and say so
    // instead of navigating back to Home as if it had worked.
    val deleteError by viewModel.deleteError.collectAsState()
    // §1.97/W4: the runtime's foreground notification only shows its Open/Stop
    // actions once POST_NOTIFICATIONS is granted on API 33+. Request it at
    // workspace entry (when the runtime actually starts), and only when it's
    // genuinely missing — no onboarding redesign.
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // §W6 bug fix: the foreground notification is posted at ACTION_START —
        // before this dialog resolves — so a first-run grant leaves it hidden
        // forever. Re-issue it now that the permission is granted; the service
        // reposts the notification (only if the workspace is still active).
        //
        // §1.166: kept as the *fast* path (the notification appears the moment
        // the dialog closes, without waiting for the resume), but it is no longer
        // the only one — the ON_RESUME observer below is the recovery that makes
        // a missed callback survivable.
        if (granted) {
            context.startService(RuntimeService.refreshNotificationIntent(context, workspaceId))
        }
    }

    when (val state = uiState) {
        is WorkspaceDetailUiState.Loading -> {
            LinexaLoadingState()
        }
        is WorkspaceDetailUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        is WorkspaceDetailUiState.Blocked -> {
            BlockedWorkspaceScreen(
                workspace = state.workspace,
                readiness = state.readiness,
                canRetry = state.canRetry,
                diagnostics = state.diagnostics,
                logPath = state.logPath,
                onRetry = viewModel::retry,
                onDelete = { viewModel.delete(onDeleted = onBack) },
                onBack = onBack
            )
        }
        is WorkspaceDetailUiState.Success -> {
            val workspace = state.workspace
            // §1.104: leaving the workspace (the system back that would pop the
            // outer workspace route and clear the graph-scoped RuntimeViewModel)
            // requires confirmation. This BackHandler is an always-on FALLBACK:
            // every within-workspace back handler is registered AFTER this one and
            // the back dispatcher consults callbacks in reverse registration
            // order, so they all get first refusal — the inner NavHost's (pops
            // Desktop/Package Manager/File Manager, and disables itself exactly
            // when its own stack is at the root) and, §1.133, WorkspaceScreen's
            // overlay handler (enabled only while the terminal overlay is open,
            // where back dismisses one layer). Only the back press that would
            // otherwise pop the outer workspace route (i.e. the workspace exit)
            // reaches this handler and shows the dialog.
            // Backgrounding (Home/app-switch/lock) never fires a back press, so
            // it can never reach this dialog or stop the runtime.
            var showExitConfirmation by remember(workspaceId) { mutableStateOf(false) }
            // Pre-release audit fix (2026-09-02, finding U1/H-1): the Settings
            // tab's Danger-Zone "Delete workspace" row used to call
            // viewModel.delete() DIRECTLY — one accidental tap inside a
            // workspace irreversibly destroyed the DB row and the entire
            // rootfs on disk. Every other delete path in the app confirms
            // first (Home card, Blocked screen); this one now does too.
            var showDeleteConfirm by remember(workspaceId) { mutableStateOf(false) }
            BackHandler {
                showExitConfirmation = true
            }

            if (showExitConfirmation) {
                LinexaAlertDialog(
                    title = stringResource(R.string.dialog_exit_workspace_title),
                    message = stringResource(R.string.dialog_exit_workspace_message, workspace.name),
                    confirmLabel = stringResource(R.string.dialog_exit_workspace_confirm),
                    dismissLabel = stringResource(R.string.dialog_exit_workspace_background),
                    onConfirm = {
                        showExitConfirmation = false
                        // Transformation spec §9/§10: "Stop workspace" is the
                        // EXPLICIT stop verb — the same ACTION_STOP the FGS
                        // notification's Stop action uses. It is chosen, not
                        // implied: leaving the screen no longer stops anything.
                        //
                        // Phase 7 fix retained: skip the explicit stop when the
                        // runtime is still Starting — the start chain is in
                        // flight, and sending ACTION_STOP right now races with
                        // it (the runtime may or may not have reached
                        // RuntimeState.Running by the time STOP arrives, which
                        // is a flaky-teardown shape on some PRoot builds).
                        // Backing out alone never stops, so for a Starting
                        // runtime the safe action is the background path.
                        if (runtimeState is RuntimeState.Running) {
                            runtimeViewModel.stopWorkspace(workspaceId)
                        }
                        onBack()
                    },
                    onDismiss = {
                        // §9: "Keep running" — just leave. The runtime
                        // continues under its foreground service; the user
                        // returns via the notification or by re-entering.
                        showExitConfirmation = false
                        onBack()
                    }
                )
            }
            if (showDeleteConfirm) {
                LinexaAlertDialog(
                    title = stringResource(R.string.workspace_delete_title),
                    message = stringResource(R.string.workspace_delete_message, workspace.name),
                    confirmLabel = stringResource(R.string.common_delete),
                    dismissLabel = stringResource(R.string.common_cancel),
                    onConfirm = {
                        showDeleteConfirm = false
                        viewModel.delete(onDeleted = onBack)
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }
            // §1.2: entering the workspace from Home starts its Linux system,
            // exactly once per graph (RuntimeViewModel.startWorkspace is
            // gated on runtime state, and the graph binds the service once).
            LaunchedEffect(workspace.id) {
                runtimeViewModel.bindService()
                runtimeViewModel.startWorkspace(workspace.id)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // §1.166: re-verify the runtime notification on every resume of the
            // workspace graph, not only when the permission dialog happens to
            // resolve `granted`.
            //
            // The §W6 fix had exactly one trigger — the launcher callback above —
            // so the foreground notification was lost for the whole session
            // whenever that trigger did not fire: the grant was made from
            // Settings → Access (which has no callback into this graph at all),
            // the activity or process was recreated while the system dialog was up
            // so the callback never arrived, or the notification was posted
            // correctly and then cleared by the system / an OEM notification
            // manager while the workspace kept running. Nothing re-checked
            // afterwards, so the only recovery was restarting the workspace.
            //
            // ON_RESUME is the chosen hook because it is the first moment the app
            // is back in front of the user after any of those — returning from the
            // permission dialog, from the Settings app, or from a cleared shade all
            // resume this graph — and it is precisely when the notification is
            // expected to be visible: the user can act on the workspace again, so
            // the way to stop it has to be on screen. It is scoped to the Success
            // branch so it only runs for a workspace that actually has a runtime.
            //
            // Cost per resume is one no-op service delivery: RuntimeService ignores
            // it unless this workspace still owns the runtime, and
            // RuntimeNotificationPresence posts nothing when what is on screen is
            // already correct — so repeated resumes cannot flicker or re-animate
            // the notification.
            //
            // §1.105: the observer binds to compose-ui's LocalLifecycleOwner
            // (androidx.compose.ui.platform), exactly like AccessSettingsGroup —
            // the identically-named androidx.lifecycle.compose CompositionLocal is
            // not provided by compose-ui 1.6.x and throws. Do not "simplify" this
            // to LifecycleResumeEffect.
            val notificationLifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(notificationLifecycleOwner, workspace.id) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        context.startService(
                            RuntimeService.refreshNotificationIntent(context, workspace.id)
                        )
                    }
                }
                notificationLifecycleOwner.lifecycle.addObserver(observer)
                onDispose { notificationLifecycleOwner.lifecycle.removeObserver(observer) }
            }
            // §1.133: the workspace terminal is an overlay on the workspace
            // screen, so "which terminal, and is it showing" is state here rather
            // than a route + nav argument. It is hoisted to the graph, not kept
            // inside the terminal panel, for two reasons: the §1.103 notification
            // deep link must be able to *set* both (open the workspace, open the
            // overlay, focus that session), and the active session's identity has
            // to survive the overlay being dismissed and reopened — panel-local
            // state would reset on dismissal, which is exactly the identity loss
            // the deleted route's argument was protecting against.
            //
            // rememberSaveable, so a configuration change or process death
            // restores the same session rather than silently falling back to T0.
            var terminalOpen by rememberSaveable(workspaceId) {
                mutableStateOf(requestedTerminalId != null)
            }
            var activeTerminalId by rememberSaveable(workspaceId) {
                mutableStateOf(requestedTerminalId ?: DEFAULT_TERMINAL_ID)
            }
            // 2026-08-31 (Phase 5/7): whether the terminal session list
            // (overview) is open, hoisted here next to terminalOpen/
            // activeTerminalId so the notification retarget path below can
            // close it deterministically — a tap that arrives while the list
            // is up must surface the retargeted terminal directly, never
            // leave it hidden under the session list.
            var showOverview by rememberSaveable(workspaceId) {
                mutableStateOf(false)
            }

            // §1.103: re-delivery of the Open deep link while this workspace graph
            // is already composed (the notification tapped while the app is
            // foreground/background on this workspace) re-targets the overlay at
            // the requested terminal. A fresh entry is already there via the
            // initial values above; this handles the re-delivery case, and it is
            // idempotent because both writes are to the value the link asked for.
            LaunchedEffect(workspaceId, requestedTerminalId) {
                val target = requestedTerminalId ?: return@LaunchedEffect
                activeTerminalId = target
                terminalOpen = true
            }

            // Phase 6b bug-fix — in-app re-delivery via the bus. The previous
            // effect keys on `requestedTerminalId`, which only changes when the
            // back-stack entry is replaced — and replacing the entry is exactly
            // what `MainActivity.onNewIntent` now avoids, because it destroyed
            // the WorkspaceNavGraph (firing RuntimeViewModel.onCleared →
            // ACTION_STOP → closeWorkspace → every terminal closed) on every
            // notification tap. The bus carries the same `(workspaceId,
            // terminalId)` pair the deep link would have carried, but is
            // collected here without touching the back stack. `consume()` is
            // called after applying so a re-subscribe (e.g. config change)
            // does not replay the request against the already-applied state.
            //
            // 2026-08-30 (Issues 6+7 — deterministic, no-op when on target):
            // the same-destination contract is enforced here in terminal
            // terms: when the requested terminal is already the active one AND
            // the overlay is already showing it, applying the request writes
            // nothing (both guarded writes are skipped) — the tap is a pure
            // no-op with no recomposition, no navigation and no session
            // restart. When the user is inside a sub-destination (Desktop /
            // Package Manager / File Manager), the retargeted overlay would
            // otherwise be set invisibly under them — one deterministic
            // popBackStack to the SHORTCUTS destination surfaces it, instead
            // of nav-compose deep-link machinery replacing the whole entry.
            LaunchedEffect(workspaceId) {
                TerminalDeepLinkBus.request.collect { req ->
                    if (req == null || req.workspaceId != workspaceId) return@collect
                    val target = req.terminalId ?: DEFAULT_TERMINAL_ID
                    if (target != activeTerminalId) {
                        activeTerminalId = target
                    }
                    if (!terminalOpen) {
                        terminalOpen = true
                    }
                    // Phase 5/7: the retargeted terminal must actually be the
                    // thing on screen — close the session list if it was up.
                    if (showOverview) {
                        showOverview = false
                    }
                    if (navController.currentDestination?.route != WorkspaceRoutes.SHORTCUTS) {
                        navController.popBackStack(WorkspaceRoutes.SHORTCUTS, inclusive = false)
                    }
                    TerminalDeepLinkBus.consume()
                }
            }

            // 2026-11 motion pass: the graph's sub-destination pushes carry
            // the one shared motion set (Motion.kt) instead of the nav
            // library's default crossfade:
            //  - forward push: the new page slides the full width in over a
            //    page that stays put (no fade on the moving page — the exact
            //    lesson of the terminal overlay spec: opacity during a slide
            //    reads as a glitch);
            //  - pop: the leaving page slides out the way it came, while the
            //    page being revealed fades in quietly underneath.
            // Reduced motion (animator scale 0) collapses every tween to an
            // instant swap.
            val motions = LocalHavenMotions.current
            NavHost(
                navController = navController,
                startDestination = WorkspaceRoutes.SHORTCUTS,
                enterTransition = {
                    slideInHorizontally(animationSpec = motions.push) { it }
                },
                exitTransition = { ExitTransition.None },
                popEnterTransition = {
                    fadeIn(animationSpec = motions.fade)
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = motions.push) { it }
                }
            ) {
                composable(WorkspaceRoutes.SHORTCUTS) {
                    WorkspaceScreen(
                        workspace = workspace,
                        commands = state.commands,
                        customCommands = state.customCommands,
                        features = state.features,
                        featureToggles = state.featureToggles,
                        featureSettings = state.featureSettings,
                        runtimeState = runtimeState,
                        runtimeTraceText = runtimeTraceText,
                        desktopEnabled = state.desktopEnabled,
                        // 2026-09-02: the desktop-enable refusal notice —
                        // transient ViewModel state, collected beside the
                        // debounced Room state so it is never clobbered by a
                        // re-emission.
                        desktopToggleBlocked = desktopToggleBlocked,
                        debugEnabled = debugEnabled,
                        binder = binder,
                        terminalOpen = terminalOpen,
                        onTerminalOpenChange = { terminalOpen = it },
                        activeTerminalId = activeTerminalId,
                        onActiveTerminalIdChange = { activeTerminalId = it },
                        showOverview = showOverview,
                        onShowOverviewChange = { showOverview = it },
                        onSetDebugEnabled = viewModel::setDebugEnabled,
                        onBack = onBack,
                        onRequestExit = { showExitConfirmation = true },
                        onOpenDesktop = { navController.navigate(WorkspaceRoutes.DESKTOP) },
                        onOpenPackageManager = { navController.navigate(WorkspaceRoutes.PACKAGE_MANAGER) },
                        onOpenFileManager = { navController.navigate(WorkspaceRoutes.FILE_MANAGER) },
                        onOpenTaskManager = { navController.navigate(WorkspaceRoutes.TASK_MANAGER) },
                        onFeatureToggle = viewModel::setFeatureToggle,
                        onFeatureSettingsChange = viewModel::setFeatureSettings,
                        // §26/§27: the Network surface on the Settings tab —
                        // same resolved record, same merge-write path.
                        networkSettings = state.networkSettings,
                        onNetworkSettingsChange = viewModel::setNetworkSettings,
                        onSetTerminalPersistence = viewModel::setTerminalPersistence,
                        onWorkspaceDeleted = { showDeleteConfirm = true },
                        // Custom (user) commands — persisted by the workspace
                        // detail ViewModel through CustomCommandsStore; the
                        // screen only forwards the intent.
                        onAddCustomCommand = viewModel::addCustomCommand,
                        onUpdateCustomCommand = viewModel::updateCustomCommand,
                        onDeleteCustomCommand = viewModel::deleteCustomCommand
                    )
                }

                composable(WorkspaceRoutes.DESKTOP) {
                    DesktopScreen(
                        workspaceId = workspaceId,
                        workspaceName = workspace.name,
                        desktopEnabled = state.desktopEnabled,
                        binder = binder,
                        // Desktop Modes: the same resolved settings the Features
                        // tab edits decide how this desktop runs (run mode and
                        // its parameters). Passed rather than re-read, so the
                        // screen can never disagree with the tab the user just
                        // changed.
                        settings = state.featureSettings,
                        onBack = { navController.popBackStack() },
                        // §1.133: Desktop's Terminal action returns to the
                        // workspace screen and opens the overlay there — the
                        // terminal is no longer somewhere to navigate *to*, so
                        // this pops rather than pushing a third surface onto the
                        // stack. It targets the session the user was last on,
                        // never unconditionally T0.
                        onOpenTerminal = {
                            terminalOpen = true
                            navController.popBackStack()
                        }
                    )
                }

                composable(WorkspaceRoutes.PACKAGE_MANAGER) {
                    val pkgVm: PackageManagerViewModel = hiltViewModel()
                    LaunchedEffect(workspaceId, binder) {
                        if (binder != null) pkgVm.attachBinder(workspaceId, binder)
                    }
                    PackageManagerScreen(
                        viewModel = pkgVm,
                        workspaceName = workspace.name,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(WorkspaceRoutes.TASK_MANAGER) {
                    val tmVm: TaskManagerViewModel = hiltViewModel()
                    TaskManagerScreen(
                        viewModel = tmVm,
                        workspaceId = workspaceId,
                        workspaceName = workspace.name,
                        runningPid = (runtimeState as? RuntimeState.Running)?.pid,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(WorkspaceRoutes.FILE_MANAGER) {
                    val fileVm: FileManagerViewModel = hiltViewModel()
                    LaunchedEffect(workspaceId, binder) {
                        if (binder != null) fileVm.attachBinder(workspaceId, binder)
                    }
                    FileManagerScreen(
                        viewModel = fileVm,
                        workspaceName = workspace.name,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    // §1.117: shown for BOTH delete entry points (Danger Zone on Success, and
    // the Blocked screen) — hence outside the `when`. A refused delete deleted
    // nothing and did not call onBack, so the user is still here and needs the
    // reason.
    deleteError?.let { reason ->
        LinexaMessageDialog(
            title = stringResource(R.string.dialog_delete_workspace_failed_title),
            message = reason,
            onDismiss = viewModel::clearDeleteError
        )
    }
}
