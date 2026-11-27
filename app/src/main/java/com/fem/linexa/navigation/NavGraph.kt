package com.fem.linexa.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.fem.linexa.R
import com.fem.linexa.core.i18n.findActivity
import com.fem.linexa.service.runtime.RuntimeNotificationContent
import com.fem.linexa.ui.components.haven.HavenDestination
import com.fem.linexa.ui.components.haven.HavenSidebar
import com.fem.linexa.ui.screens.about.AboutScreen
import com.fem.linexa.ui.screens.activity.ActivityScreen
import com.fem.linexa.ui.screens.home.WorkspacesScreen
import com.fem.linexa.ui.screens.install.InstallFlowScreen
import com.fem.linexa.ui.screens.install.InstallFlowViewModel
import com.fem.linexa.ui.screens.settings.SettingsScreen
import com.fem.linexa.ui.screens.settings.TerminalLayoutSettingsScreen
import com.fem.linexa.ui.screens.settings.TerminalToolbarSettingsScreen
import com.fem.linexa.ui.screens.workspace.WorkspaceNavGraph

/**
 * HAVEN routes (concept-2-haven.html section 01).
 *
 * The information architecture is THREE permanent destinations — Workspaces,
 * Activity, Settings — plus contextual screens pushed onto the same stack:
 *
 *   workspaces  activity  settings          ← top-level tabs (bottom bar /
 *       │                                    tablet sidebar, saveState'd)
 *       └─ workspace/{id}                    ← parent context for the tools
 *            ├─ desktop / files / packages   ← children, popped back in order
 *            └─ terminal                     ← overlay, not a destination
 *
 * Every screen below the tabs is a REAL back-stack entry: Settings →
 * Performance → back lands on Settings, Workspace → Files → back lands on
 * Workspace. There are no "jump home" shortcuts anywhere.
 */
object Routes {
    const val WORKSPACES = "workspaces"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    const val WORKSPACE = "workspace/{workspaceId}?terminalId={terminalId}"
    const val INSTALL = "install?distroId={distroId}&uri={uri}&name={name}&version={version}"
    const val ABOUT = "about"
    // UI/UX Phase 3: the standalone Performance Dashboard route was removed —
    // Devices & System Information already carries the same live host metrics.
    // No dead navigation path remains.
    const val DEVICE_INFO = "device-info"
    // 2026-11 Settings pass: two app-scoped terminal UI preferences. Real
    // pushed destinations, same as About / Device info, so system back returns
    // to Settings with its scroll position intact.
    const val TERMINAL_LAYOUT = "settings/terminal/recent-sessions"
    const val TERMINAL_TOOLBAR = "settings/terminal/toolbar"

    fun workspace(id: String, terminalId: String? = null): String {
        val base = "workspace/${encodeArgument(id)}"
        return if (terminalId == null) base else "$base?terminalId=${encodeArgument(terminalId)}"
    }

    fun installDistro(distroId: String) = "install?distroId=${encodeArgument(distroId)}"
    fun installFile(uri: String, name: String, version: String) =
        "install?uri=${encodeArgument(uri)}&name=${encodeArgument(name)}&version=${encodeArgument(version)}"

    /**
     * F-030: one encoder for every route argument. It used to be `Uri.encode`
     * in some builders and raw interpolation in others (`workspace/$id`), so
     * an id containing `/`, `?`, `&` or a space could change the route shape.
     *
     * `URLEncoder` is JVM-testable (the route builders are pure functions and
     * their round-trip is pinned by `RoutesTest`); its only quirk is encoding
     * a space as `+`, which is not what a URI path/query expects, so it is
     * normalised to `%20`.
     */
    private fun encodeArgument(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Navigates to one of the three top-level tabs with the standard
 * save/restore-state pattern: the previous tab's stack is SAVED (so returning
 * to Workspaces restores scroll/tab state) and the target's state RESTORED.
 * `launchSingleTop` avoids stacking duplicate tab entries; the popUpTo target
 * is the start destination, so each tab keeps its own history below it.
 */
fun NavHostController.navigateHavenTab(destination: HavenDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private val topLevelRoutes = setOf(
    Routes.WORKSPACES, Routes.ACTIVITY, Routes.SETTINGS
)

/**
 * Top-level tab transition — a short, simultaneous crossfade.
 *
 * Why the transition itself was part of the tab-switch jank (evidence from the
 * library defaults the three tab destinations were falling back to):
 *
 *  1. Navigation-Compose's default spec fades the outgoing destination OUT
 *     over 90ms while the incoming one waits at alpha 0 with a **90ms enter
 *     delay** before its own 220ms fade-in — so every tab switch showed a
 *     ~90ms gap where the content area is only background. A blink between
 *     tabs, on every switch, in both directions.
 *  2. The whole default window (~310ms) keeps BOTH destination trees composed,
 *     measured and drawn per frame — two Scaffolds, two bottom bars, two full
 *     card lists — while each tab's composition is being built from scratch
 *     (Navigation recreates the incoming destination). The destination's
 *     most expensive frames (first composition + inset measure) land while
 *     the animation is also running, which is what read as stutter/freeze on
 *     mid-range hardware.
 *  3. Each tab hosts its own [com.fem.linexa.ui.components.haven.HBottomNav]
 *     by design (so the terminal overlay can cover it), so the most stable
 *     element on screen pulsed for the whole window on every switch.
 *
 * A 90ms SIMULTANEOUS fade fixes all three: no enter delay (no blank gap),
 * the double-composition window shrinks ~3x, and a hint of motion is kept —
 * nothing is disabled. Scoped strictly to the three top-level destinations
 * via [TabTransition]; pushed screens (workspace detail, install, …) keep
 * the library default so the stack feel is unchanged.
 */
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(animationSpec = TabTransition.spec()) }

private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(animationSpec = TabTransition.spec()) }

/**
 * Main navigation graph for Linexa — HAVEN edition.
 *
 * The shell renders the adaptive navigation chrome:
 *  - phone: each top-level screen hosts [com.fem.linexa.ui.components.haven.HBottomNav]
 *    through its own scaffold (so the terminal overlay can cover it);
 *  - tablet (≥840dp width): a persistent 238dp [HavenSidebar] with the same
 *    three destinations plus the workspace list; selecting a workspace opens
 *    it in the detail pane BESIDE the list (two-pane, same nav model).
 */
@Composable
fun AppNavGraph(
    navController: NavHostController
) {
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= 840

    // The root composition swaps in a locale-configured ContextWrapper, which is
    // not itself an Activity; unwrap it instead of casting the CompositionLocal
    // directly (that cast used to be safe only while LocalContext was the raw
    // activity).
    val activity = requireNotNull(LocalContext.current.findActivity() as? ComponentActivity) {
        "AppNavGraph requires a ComponentActivity host"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isTablet) {
            // PERF-010 (2026-11-24 continuation): the selected route exists
            // only to drive the tablet sidebar. Collecting it at the root made
            // every phone back-stack change recompose this shell and the
            // NavHost for a value no phone UI renders; the collection now
            // lives in the one branch that consumes it.
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val selectedDestination =
                HavenDestination.entries.firstOrNull { it.route == currentRoute }

            // One shell-scoped HomeViewModel for the tablet sidebar's workspace
            // list (activity scope so it survives tab switches and pushed
            // destinations).
            //
            // PERF-002 (performance audit 2026-11-23): created ONLY on tablets.
            // It used to be created and collected unconditionally, so on a
            // phone — where the sidebar does not exist and nothing read it —
            // every activity ran a second Room collector, a second enrichment
            // pipeline and a second `declaredPreview` filesystem pass for its
            // whole lifetime. Its `collectAsState` also made the ROOT
            // composition read workspace state, so every workspace emission
            // recomposed the shell and the NavHost. Neither had a consumer on
            // phones; both are gone now.
            val shellHomeViewModel: com.fem.linexa.ui.screens.home.HomeViewModel =
                hiltViewModel(activity)
            val workspaces by shellHomeViewModel.workspacesUi.collectAsState()

            Row(modifier = Modifier.fillMaxSize()) {
                HavenSidebar(
                    selected = selectedDestination,
                    onSelect = { navController.navigateHavenTab(it) },
                    workspaces = workspaces,
                    onOpenWorkspace = {
                        // Pre-release audit fix (2026-09-02, finding L-5):
                        // repeated sidebar taps used to stack duplicate
                        // workspace entries on the back stack.
                        navController.navigate(Routes.workspace(it)) { launchSingleTop = true }
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                )
                Box(modifier = Modifier.weight(1f)) {
                    HavenNavHost(navController = navController)
                }
            }
        } else {
            HavenNavHost(navController = navController)
        }
    }
}

@Composable
private fun HavenNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.WORKSPACES) {

        composable(
            route = Routes.WORKSPACES,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit
        ) {
            WorkspacesScreen(
                onOpenWorkspace = { workspace ->
                    navController.navigate(Routes.workspace(workspace.id))
                },
                onInstallDistro = { distroId ->
                    navController.navigate(Routes.installDistro(distroId))
                },
                onInstallFile = { uri, name, version ->
                    navController.navigate(Routes.installFile(uri, name, version))
                },
                onSelectTab = { navController.navigateHavenTab(it) }
            )
        }

        composable(
            route = Routes.ACTIVITY,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit
        ) {
            ActivityScreen(
                onOpenWorkspace = { workspaceId, terminalId ->
                    navController.navigate(Routes.workspace(workspaceId, terminalId))
                },
                onSelectTab = { navController.navigateHavenTab(it) }
            )
        }

        composable(
            route = Routes.SETTINGS,
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit
        ) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                onOpenTerminalLayout = { navController.navigate(Routes.TERMINAL_LAYOUT) },
                onOpenTerminalToolbar = { navController.navigate(Routes.TERMINAL_TOOLBAR) },
                onSelectTab = { navController.navigateHavenTab(it) }
            )
        }

        composable(Routes.TERMINAL_LAYOUT) {
            TerminalLayoutSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TERMINAL_TOOLBAR) {
            TerminalToolbarSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        // UI/UX Phase 3: the PERFORMANCE destination was removed (redundant
        // with Devices & System Information); no dead navigation path remains.

        // 2026-08-27 (device-info UI track): visible-lifetime sampling
        // starts/stops with the destination; back returns to Settings.
        // P0-5 (2026-11-23): "visible" now means lifecycle-visible, not merely
        // composed — nav destinations stay composed while the app is
        // backgrounded, so the /proc poller used to keep running off-screen.
        composable(Routes.DEVICE_INFO) {
            val viewModel: com.fem.linexa.ui.screens.device.DeviceInfoViewModel = hiltViewModel()
            val foreground by com.fem.linexa.ui.components.rememberIsForeground()
            LaunchedEffect(viewModel, foreground) {
                if (foreground) viewModel.start() else viewModel.pause()
            }
            DisposableEffect(viewModel) {
                onDispose { viewModel.pause() }
            }
            com.fem.linexa.ui.screens.device.DeviceInfoScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }

        composable(
            route = Routes.INSTALL,
            arguments = listOf(
                navArgument(InstallFlowViewModel.ARG_DISTRO_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(InstallFlowViewModel.ARG_URI) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(InstallFlowViewModel.ARG_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(InstallFlowViewModel.ARG_VERSION) { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) {
            InstallFlowScreen(
                onBack = { navController.popBackStack(Routes.WORKSPACES, inclusive = false) },
                onInstalled = {
                    // Return to the workspace LIST after a completed install —
                    // the freshly installed workspace appears there (Room
                    // observeAll() auto-refreshes). Do not auto-launch.
                    navController.popBackStack(Routes.WORKSPACES, inclusive = false)
                },
                onViewDiagnostics = { workspaceId ->
                    navController.navigate(Routes.workspace(workspaceId)) {
                        popUpTo(Routes.WORKSPACES)
                    }
                }
            )
        }

        composable(
            route = Routes.WORKSPACE,
            arguments = listOf(
                navArgument("workspaceId") { type = NavType.StringType },
                navArgument("terminalId") { type = NavType.StringType; nullable = true; defaultValue = null }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "${RuntimeNotificationContent.DEEP_LINK_SCHEME}://workspace/{workspaceId}" },
                navDeepLink { uriPattern = "${RuntimeNotificationContent.DEEP_LINK_SCHEME}://workspace/{workspaceId}?terminalId={terminalId}" }
            )
        ) { backStackEntry ->
            val workspaceId = backStackEntry.arguments?.getString("workspaceId") ?: return@composable
            val requestedTerminalId = backStackEntry.arguments?.getString("terminalId")
            val workspaceNavController = androidx.navigation.compose.rememberNavController()

            WorkspaceNavGraph(
                workspaceId = workspaceId,
                requestedTerminalId = requestedTerminalId,
                navController = workspaceNavController,
                onBack = { navController.popBackStack(Routes.WORKSPACES, inclusive = false) }
            )
        }
    }
}
