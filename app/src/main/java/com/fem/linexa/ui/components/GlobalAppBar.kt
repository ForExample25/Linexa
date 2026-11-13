package com.fem.linexa.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.fem.linexa.R

/**
 * § global-bar pass: ONE app bar structure for the whole app.
 *
 * Every screen renders through [AppScaffold] with a [GlobalAppBarState] that
 * describes what the bar shows in *that context* — title, back button,
 * trailing actions (Terminal/Desktop are always placed on the RIGHT by the
 * caller, never in the leading slot), and [visible] (the fullscreen Desktop
 * session hides the bar centrally through this flag instead of each screen
 * scattering `if (isDesktop)` around its own Scaffold).
 */
data class GlobalAppBarState(
    val title: String,
    val visible: Boolean = true,
    val subtitle: String? = null,
    @DrawableRes val navigationIconRes: Int = R.drawable.ic_custom_arrow_back,
    val navigationContentDescription: String? = null,
    val onNavigationClick: (() -> Unit)? = null,
    /**
     * §1.133: an arbitrary composable for the leading slot, used *instead of*
     * [navigationIconRes] when non-null.
     *
     * It exists because the workspace bar's leading slot now holds the
     * distribution glyph (a colored tile with either a branded vector or the
     * workspace's letter) when no overlay is open, and a `@DrawableRes` cannot
     * express that — the glyph needs the workspace's avatar color and letter.
     *
     * Deliberately narrow: it replaces the leading slot only, so a caller cannot
     * use it to smuggle a second title or extra actions past the shared bar. The
     * back arrow stays the [navigationIconRes] path, which means "shows a glyph"
     * and "shows a back arrow" remain mutually exclusive by construction rather
     * than by convention.
     */
    val navigationContent: (@Composable () -> Unit)? = null,
    val actions: List<TopBarAction> = emptyList(),
    // Null = the app default; resolved to the theme colors inside the
    // composable (a default here would need composable context).
    val containerColor: Color? = null,
    val titleColor: Color? = null,
    // Null = the app default typography; resolved to titleMedium inside the
    // composable. Lets a screen (e.g. the Workspace bar) request a larger,
    // bolder title without touching the shared bar component.
    val titleStyle: TextStyle? = null
)

/**
 * The single global app bar. Renders [LinexaTopBar] from [state]; when
 * [GlobalAppBarState.visible] is false (fullscreen Desktop session) nothing is
 * emitted and the surrounding Scaffold leaves no space for it.
 */
@Composable
fun GlobalAppBar(
    state: GlobalAppBarState,
    modifier: Modifier = Modifier
) {
    if (!state.visible) return
    LinexaTopBar(
        modifier = modifier,
        title = state.title,
        subtitle = state.subtitle,
        navigationIconRes = state.navigationIconRes,
        navigationContentDescription = state.navigationContentDescription,
        onNavigationClick = state.onNavigationClick,
        navigationContent = state.navigationContent,
        actions = state.actions,
        containerColor = state.containerColor ?: MaterialTheme.colorScheme.background,
        titleColor = state.titleColor ?: MaterialTheme.colorScheme.onSurface,
        titleStyle = state.titleStyle
    )
}

/**
 * The one Scaffold used by every screen. The app bar comes from [appBarState];
 * screens keep only their genuinely screen-specific chrome (bottom bar, FAB,
 * snackbar host) here. [containerColor] defaults to the standard background so
 * the window, bar, and content all agree.
 */
@Composable
fun AppScaffold(
    appBarState: GlobalAppBarState,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = { GlobalAppBar(appBarState) },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor ?: MaterialTheme.colorScheme.background,
        content = content
    )
}

/**
 * The shared Workspace-context bar. Used identically by [WorkspaceScreen] and
 * `DesktopScreen` so every workspace surface presents the SAME global bar:
 * workspace name on the left, and Terminal + Desktop (desktop only when the
 * workspace actually supports it) on the RIGHT.
 * [terminalSelected]/[desktopSelected] mark which of those the current surface
 * is, so the active one reads with the primary accent.
 *
 * §1.133 — this now takes a [WorkspaceChromeState]-shaped set of parameters
 * rather than a handful of booleans each caller assembles, and the two additions
 * are what make the terminal overlay possible:
 *
 *  * [leadingContent] — the workspace's distribution glyph, shown *instead of*
 *    the back arrow while no overlay is open. Passing it sets
 *    `navigationIconRes = 0`, which is how "glyph or arrow, never both" is
 *    enforced by construction (see [GlobalAppBarState.navigationContent]).
 *  * [onOpenSessionOverview] — non-null replaces the Desktop action with the
 *    terminal session-overview action. It is a *replacement*, not an addition:
 *    the overview is only reachable while the terminal is on top, where the
 *    Desktop action would be a second navigation out of the surface the user
 *    just opened.
 *
 * The bar deliberately keeps [subtitle] as a plain string: the section label is
 * resolved by the caller from [com.fem.linexa.ui.screens.workspace.WorkspaceSection],
 * so this component never has to know what a "section" is.
 */
@Composable
fun workspaceAppBarState(
    workspaceName: String,
    desktopEnabled: Boolean,
    subtitle: String = stringResource(R.string.workspace_title_label),
    terminalSelected: Boolean = false,
    desktopSelected: Boolean = false,
    sessionOverviewSelected: Boolean = false,
    // §1.104: the Workspace main screen drops the leading back arrow — leaving
    // the workspace is now gated by the system-back exit confirmation
    // (WorkspaceNavGraph), so a one-tap top-bar back button is removed there.
    // §1.133: while an overlay is open the arrow returns, and it dismisses
    // exactly ONE layer (overview → terminal → workspace) rather than exiting.
    showBackIcon: Boolean = true,
    // §1.133: what the leading arrow *does*, for a screen reader. It defaults to
    // "Back to Home" for the surfaces where that is true (Desktop pops out of the
    // workspace's inner graph), but an overlay's arrow dismisses one layer and
    // stays in the workspace, so it must be able to say so.
    backContentDescription: String = stringResource(R.string.workspace_back_to_home),
    leadingContent: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenSessionOverview: (() -> Unit)? = null
): GlobalAppBarState = GlobalAppBarState(
    title = workspaceName,
    subtitle = subtitle,
    // §1.104: the Workspace bar presents the session as the top-level surface —
    // title is larger and bold. The default titleMedium is used everywhere else.
    titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    navigationIconRes = if (showBackIcon && leadingContent == null) {
        R.drawable.ic_custom_arrow_back
    } else {
        0
    },
    onNavigationClick = onBack,
    navigationContentDescription = backContentDescription,
    navigationContent = leadingContent,
    actions = buildList {
        add(
            TopBarAction(
                iconRes = R.drawable.ic_custom_terminal,
                contentDescription = stringResource(R.string.workspace_tab_terminal),
                onClick = onOpenTerminal,
                selected = terminalSelected
            )
        )
        if (onOpenSessionOverview != null) {
            // §1.133: takes the Desktop action's slot while a terminal surface
            // is on top — replace, don't add.
            add(
                TopBarAction(
                    iconRes = R.drawable.ic_custom_terminal_sessions,
                    contentDescription = stringResource(R.string.workspace_section_sessions),
                    onClick = onOpenSessionOverview,
                    selected = sessionOverviewSelected
                )
            )
        } else if (desktopEnabled) {
            add(
                TopBarAction(
                    iconRes = R.drawable.ic_custom_desktop,
                    contentDescription = stringResource(R.string.workspace_tab_desktop),
                    onClick = onOpenDesktop,
                    selected = desktopSelected
                )
            )
        }
    }
)
