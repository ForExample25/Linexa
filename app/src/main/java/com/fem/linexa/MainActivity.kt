package com.fem.linexa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.fem.linexa.core.i18n.AppLocale
import com.fem.linexa.navigation.AppNavGraph
import com.fem.linexa.navigation.TerminalDeepLinkBus
import com.fem.linexa.service.runtime.RuntimeNotificationContent
import com.fem.linexa.ui.i18n.AppLocaleViewModel
import com.fem.linexa.ui.theme.AppThemeViewModel
import com.fem.linexa.ui.theme.LinexaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * §1.97/W4: hoisted NavHostController so the foreground notification's
     * Open action (a deep link to `workspace/{id}`) can navigate even when the
     * activity is already on screen (onNewIntent) or is being cold-started by
     * the notification (onCreate → LaunchedEffect).
     */
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 2026-08-29 (UX optimization, Issues 2+3): the launch deep link is now
        // delivered by nav-compose's own graph-setup handling (the workspace
        // destination declares `navDeepLink` patterns, and NavController
        // consumes the launch intent when the graph is set — DURING
        // composition, before the first frame). The previous manual
        // `handleDeepLink` in onControllerReady ran in a LaunchedEffect —
        // AFTER the first frame — so cold-start delivery happened TWICE: the
        // built-in pass navigated to the workspace, then the manual pass
        // popped and recreated the workspace entry (the Phase-6b destroy
        // path), flashing the placeholder and reloading the terminal. The
        // manual call is gone; onNewIntent still pre-empts nav-compose for
        // the warm same-workspace path via TerminalDeepLinkBus.
        //
        // 2026-08-30 (Issue 4 — no theme/background flash on a notification
        // cold start): the previous "paint the window background dark for
        // this launch" patch is REMOVED. It hardcoded a terminal-ish black
        // (#0A0E0F) over the theme's real window background, so a light-Haven
        // device cold-started by the notification drew a full black frame
        // before the first Compose frame — precisely the black flash the
        // brief forbids, and it also leaked the terminal's visual language
        // into app-owned chrome. The theme's own `android:windowBackground`
        // (Haven bg, light or dark per values-night) is the correct, theme-
        // owned pre-frame surface for every launch, deep link or not; the
        // terminal screen itself sits below the app chrome and paints its own
        // terminal-owned background inside its content region only.
        setContent {
            LinexaApp(
                navController = rememberNavController(),
                onControllerReady = { controller ->
                    navController = controller
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Phase 6b bug-fix: if the deep link targets the workspace the user
        // is already on, deliver the terminal-id retarget through the bus
        // instead of through NavController.handleDeepLink. nav-compose 2.7.7
        // would otherwise pop the existing workspace back-stack entry (whose
        // args lack `?terminalId=…`) in favour of a new one with the arg,
        // destroying and recreating the WorkspaceNavGraph composable — a
        // full terminal reload (placeholder → re-attach) plus back-stack
        // churn for what must be an in-place retarget. (At the time of the
        // original fix it also fired RuntimeViewModel.onCleared → ACTION_STOP,
        // closing every terminal; onCleared no longer stops the runtime, but
        // the recreate/flash half of the defect is what the bus still owns.)
        // The bus lets the existing WorkspaceNavGraph retarget its overlay in
        // place, and its consumer turns an already-on-target request into a
        // pure no-op (Issues 6+7, 2026-08-30).
        val controller = navController
        val data = intent.data
        if (controller != null && data != null && isLinexaWorkspaceDeepLink(data)) {
            val deepWorkspaceId = parseWorkspaceId(data)
            val deepTerminalId = data.getQueryParameter("terminalId")
            val currentDestination = controller.currentDestination
            val currentRoute = currentDestination?.route
            // Routes.WORKSPACE == "workspace/{workspaceId}?terminalId={terminalId}"
            val isOnWorkspace = currentRoute != null &&
                currentRoute.startsWith("workspace/") &&
                currentRoute.contains("{workspaceId}")
            // Argument VALUES live on the back-stack entry (a Bundle);
            // NavDestination.arguments is only the route's argument SPECS
            // (Map<String, NavArgument>) — reading .getString off it never
            // compiled.
            val currentWorkspaceId = controller.currentBackStackEntry
                ?.arguments?.getString("workspaceId")
            if (isOnWorkspace && deepWorkspaceId != null && currentWorkspaceId == deepWorkspaceId) {
                TerminalDeepLinkBus.emit(deepWorkspaceId, deepTerminalId)
                return
            }
        }
        if (intent.data != null) {
            navController?.handleDeepLink(intent)
        }
    }

    /**
     * `linexa://workspace/{id}[?terminalId={tid}]` — the deep-link shape
     * `RuntimeNotificationContent.openDeepLink` produces. Anything else is
     * not the notification's Open action and goes through the default path.
     */
    private fun isLinexaWorkspaceDeepLink(data: Uri): Boolean {
        if (data.scheme != RuntimeNotificationContent.DEEP_LINK_SCHEME) return false
        val host = data.host ?: return false
        if (host != "workspace") return false
        // Path segments: ["workspace", "{id}"] for `linexa://workspace/abc`.
        val segments = data.pathSegments
        return segments.size >= 2 && segments[0] == "workspace"
    }

    /** Extracts the `{workspaceId}` segment from `linexa://workspace/{id}`. */
    private fun parseWorkspaceId(data: Uri): String? {
        val segments = data.pathSegments
        return if (segments.size >= 2) segments[1] else null
    }
}

@Composable
fun LinexaApp(
    navController: NavHostController,
    onControllerReady: (NavHostController) -> Unit = {},
    themeViewModel: AppThemeViewModel = hiltViewModel(),
    appLocaleViewModel: AppLocaleViewModel = hiltViewModel()
) {
    // Register the hoisted controller once the graph is composed, so deep links
    // (notification Open) can reach it.
    LaunchedEffect(navController) {
        onControllerReady(navController)
    }

    // UI/UX Phase 4: the light/dark decision is made here, not inside LinexaTheme.
    // The persisted mode comes from the ViewModel (seeded synchronously, so the
    // first frame is already correct), and SYSTEM is resolved against
    // isSystemInDarkTheme() *in composition* so a device that flips to dark at
    // sunset flips the app with it rather than being frozen at whatever the system
    // reported when the ViewModel was constructed.
    //
    // UI/UX Phase 7: same shape for the color-theme dimension — also seeded
    // synchronously, also collected here, also passed *explicitly* into
    // LinexaTheme. LinexaTheme's `colorTheme` parameter defaults to
    // ColorTheme.DEFAULT, so calling `LinexaTheme(darkTheme = darkTheme) { ... }`
    // would silently ignore the persisted selection. That is the §1.19 defect
    // class again; SettingsWiringSourceTest pins it for both axes.
    val themeMode by themeViewModel.themeMode.collectAsState()
    val darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
    val colorTheme by themeViewModel.colorTheme.collectAsState()

    // Localization: the whole tree — stringResource, dialogs, locale-aware
    // formatting — is re-provided from this one place. Recreating the activity
    // for a language change (the platform's default) would tear down the nav
    // graph and the workspace's terminal surface, so instead the localized
    // context is swapped in place.
    //
    // Layout-direction policy: the application chrome is ALWAYS left-to-right,
    // in English and in Persian. Language selects resources and text content,
    // never the arrangement of rows, navigation, padding or icons. The direction
    // is pinned here, at the composition root, so no screen or nested component
    // can inherit RTL from the Persian locale. Persian text itself still renders
    // RTL through Compose's first-strong bidi heuristic (TextDirection.Unspecified);
    // only the surrounding layout is LTR. See
    // docs/design/ACCESSIBILITY_AND_LOCALIZATION.md §4.2.1.
    val baseContext = LocalContext.current
    val appLanguage by appLocaleViewModel.appLanguage.collectAsState()
    val localizedContext = remember(baseContext, appLanguage) {
        AppLocale.localizedContext(baseContext, appLanguage)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalLayoutDirection provides LayoutDirection.Ltr
    ) {
        LinexaTheme(darkTheme = darkTheme, colorTheme = colorTheme) {
            // §1.89: paint the root Surface with the same color every Scaffold
            // uses (MaterialTheme.colorScheme.background), so the window
            // background, the NavHost crossfades, and the empty areas between
            // screens all match — the confirm -> install transition no longer
            // flashes the default `surface` color underneath.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavGraph(navController = navController)
            }
        }
    }
}
