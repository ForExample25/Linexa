package com.fem.linexa.ui.screens.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.domain.commands.ShortcutPins
import com.fem.linexa.domain.commands.ShortcutPinsOrdering
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HavenMotion
import com.fem.linexa.ui.components.haven.ItemPhase
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.motionItem
import com.fem.linexa.ui.components.haven.rememberMotionItems
import com.fem.linexa.ui.components.haven.havenPressScale
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HMenu
import com.fem.linexa.ui.components.haven.HMenuItem
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HSheet
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * The App Launcher grid — the Apps tab's presentation (App Launcher redesign).
 *
 * ## What this screen is
 *
 * An icon grid of the workspace's installable/launchable APPLICATIONS — the
 * catalog's `application` nodes — each with its blob icon, name, category and
 * MEASURED install state. Tapping an installed app launches it (CLI tools open
 * the integrated terminal and run there; apps without a launch command get a
 * shell); tapping a not-installed app confirms and installs it IN THE
 * BACKGROUND through the [AppInstallCoordinator] — the user stays here, a
 * compact indicator shows live progress, and the tile's state updates when the
 * guest verifies it. Long-press opens the management menu (Pin / Favorite /
 * Home screen / Edit command / Uninstall), adapting to the app's measured
 * state.
 *
 * ## What is deliberately reused
 *
 *  - [CommandAppTile]/[AppBlobIcon] — the same blob icon language the Commands
 *    grid uses, so one design system, not two.
 *  - [ShortcutPinsViewModel] — the same per-workspace pins store the Commands
 *    grid persists (an app id is `builtin:`-namespaced exactly like a command
 *    leaf's, so both surfaces can pin the same entity without collision).
 *  - [ShortcutPinsOrdering] — the same "pinned lifts to the top" rule.
 *
 * Pure arrangement logic lives in [LauncherGridModel] (JVM-testable).
 */
@Composable
fun AppLauncherSegment(
    workspaceId: String,
    launcherViewModel: AppLauncherViewModel,
    installTracker: AppInstallTracker,
    pinsViewModel: ShortcutPinsViewModel,
    onOpenTerminal: (String) -> Unit,
    onAddCustomCommand: (name: String, command: String) -> Unit,
    // 2026-11 UI pass: the two workspace-tool entries (Packages / Files) were
    // moved from the Settings tab's Tools card to right above this segment's
    // search field. They push the SAME destinations the old rows navigated to
    // (WorkspaceRoutes.PACKAGE_MANAGER / FILE_MANAGER), so no screen, route or
    // ViewModel is duplicated — only the launcher's entry point changed.
    onOpenPackageManager: () -> Unit,
    onOpenFileManager: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted by WorkspaceScreen so the grid's scroll position survives
    // segment switches (and the terminal overlay blinking over the page).
    gridState: LazyGridState = rememberLazyGridState(),
    // MOT-4: bounded first-composition reveal. WorkspaceScreen owns the
    // saveable "already played" flag, so re-entering the segment never
    // replays it; this segment consumes the flag after the first frame.
    staggerReveal: Boolean = false,
    onStaggerConsumed: () -> Unit = {}
) {
    val h = HavenTheme.colors
    val state by launcherViewModel.state.collectAsState()
    val launchStates by launcherViewModel.launchStates.collectAsState()
    val launchedTerminal by launcherViewModel.launchedTerminal.collectAsState()
    val rootShellState by launcherViewModel.rootShellState.collectAsState()
    val rootShellTitle = stringResource(R.string.haven_root_shell)
    val installStates by installTracker.states.collectAsState()
    val probing by installTracker.probing.collectAsState()
    val pins by pinsViewModel.pins.collectAsState()

    // The confirmation dialogs (install/uninstall) and the context menu's
    // target — rememberSaveable so a rotation does not lose an open dialog.
    var installTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTargetId by remember { mutableStateOf<String?>(null) }
    // The context menu's platform-owning requests (dialog state, not saveable —
    // a rotation mid-dialog re-asks rather than resurrecting stale text).
    var homeShortcutRequest by remember { mutableStateOf<AppLauncherViewModel.HomeShortcutRequest?>(null) }
    var editRequest by remember { mutableStateOf<AppLauncherViewModel.LauncherEditRequest?>(null) }

    // The first frame composes the visible tiles with their reveal state; only
    // then is the flag consumed, so tiles that scroll in later (including
    // during the reveal window) compose at their final value and never replay
    // the stagger. Reduced motion makes the whole window zero-length.
    LaunchedEffect(workspaceId, staggerReveal) {
        if (staggerReveal) {
            withFrameNanos { }
            onStaggerConsumed()
        }
    }

    // The home-screen shortcut deep-link target (set per workspace attach).
    LaunchedEffect(workspaceId) { currentWorkspaceIdForShortcuts = workspaceId }

    // Adopt re-measured install states into the launcher's rows.
    LaunchedEffect(installStates) { launcherViewModel.adoptStates(installStates) }

    // The Apps tab is the moment a package may have changed outside this app
    // (another terminal, Package Manager, an apt run). Re-measure on every
    // entry so an externally installed app stops showing "Unknown state".
    LaunchedEffect(workspaceId) { installTracker.refresh() }

    // Open + focus the terminal an app launch opened.
    LaunchedEffect(launchedTerminal) {
        val terminalId = launchedTerminal
        if (terminalId != null) {
            launcherViewModel.consumeLaunchedTerminal()
            onOpenTerminal(terminalId)
        }
    }

    Column(modifier = modifier) {
        // ── Compact install indicators (near the top of the app, as required) ──
        if (state.jobs.isNotEmpty()) {
            val active = state.jobs.filter { it.isActive }
            val finished = state.jobs.filter { !it.isActive }
            if (active.isNotEmpty()) {
                AppInstallIndicatorList(jobs = active)
                Spacer(modifier = Modifier.height(10.dp))
            } else if (finished.isNotEmpty()) {
                AppInstallIndicatorList(
                    jobs = finished.take(1),
                    onDismiss = { job -> launcherViewModel.dismissJob(job.app) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // ── Toolbar: Packages · Files, with the search ICON docked at its end ──
        // Tapping the icon morphs it into the search field across the toolbar
        // (the pair fades out underneath); closing restores the toolbar. The
        // query is cleared on close — the launcher's "no query = full list".
        var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var searchActive by rememberSaveable { mutableStateOf(false) }
        com.fem.linexa.ui.components.haven.HSearchMorphBar(
            active = searchActive,
            onActiveChange = { searchActive = it },
            query = searchQuery,
            onQueryChange = {
                searchQuery = it
                if (it.isNotBlank()) categoryFilter = null
            },
            placeholder = stringResource(R.string.launcher_search_hint, state.apps.size),
            barHeight = HavenDim.btnH
        ) {
            LauncherToolButtons(
                onOpenPackageManager = onOpenPackageManager,
                onOpenFileManager = onOpenFileManager,
                bottomPadding = 0.dp
            )
        }
        // 2026-11-13: explicit Root Shell entry, directly beneath the
        // Packages/Files toolbar. The metadata `root-shell` card is no longer
        // rendered by the Apps grid, so this is the discoverable way to open
        // the uid-0 terminal (interactive sudo, package operations, system
        // repair). The guest `sudo` bridge is the non-interactive counterpart.
        HButton(
            label = rootShellTitle,
            onClick = { launcherViewModel.launchRootShell(title = rootShellTitle) },
            variant = HavenButtonVariant.SECONDARY,
            enabled = rootShellState != AppLaunchState.Opening,
            block = true,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_terminal),
                    contentDescription = null,
                    tint = h.amber,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
        if (rootShellState is AppLaunchState.Failed) {
            Text(
                text = (rootShellState as AppLaunchState.Failed).reason,
                style = HavenType.small,
                color = h.red,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))

        val rows = remember(state.apps, searchQuery, categoryFilter, pins) {
            LauncherGridModel.arrange(state.apps, searchQuery, categoryFilter, pins)
        }
        val categories = remember(state.apps) { LauncherGridModel.categoriesOf(state.apps) }

        LauncherSearchAndChips(
            apps = state.apps,
            categories = categories,
            searchQuery = searchQuery,
            // Chips step aside while the search field owns the toolbar.
            searchActive = searchActive,
            onSearchQueryChange = {
                searchQuery = it
                if (it.isNotBlank()) categoryFilter = null
            },
            categoryFilter = categoryFilter,
            onCategorySelect = {
                searchQuery = ""
                categoryFilter = if (categoryFilter == it) null else it
            },
        )

        // ── The grid ──
        if (state.apps.isEmpty()) {
            EmptyAppsHint(text = stringResource(R.string.launcher_empty))
        } else {
            ColumnScopeGrid(
                rows = rows,
                launchStates = launchStates,
                jobs = state.jobs.associateBy { it.app.id },
                pins = pins,
                onLaunch = { app ->
                    val row = state.apps.firstOrNull { it.app.id == app.id }
                    val job = state.jobs.firstOrNull { it.app.id == app.id }
                    if (job?.isActive != true && row != null &&
                        LauncherInstallPolicy.isInstalled(row.installed, job)
                    ) {
                        launcherViewModel.launch(app)
                    } else {
                        // Not installed, or a job is genuinely running: the
                        // sheet is the honest surface (progress, no second
                        // install).
                        installTarget = app.id
                    }
                },
                onMenuToggle = { appId ->
                    val row = state.apps.firstOrNull { it.app.id == appId }
                    val job = state.jobs.firstOrNull { it.app.id == appId }
                    if (job?.isActive != true && row != null &&
                        LauncherInstallPolicy.isInstalled(row.installed, job)
                    ) {
                        menuTargetId = if (menuTargetId == appId) null else appId
                    } else {
                        menuTargetId = null
                        installTarget = appId
                    }
                },
                menuTargetId = menuTargetId,
                onUninstall = { uninstallTarget = it.id },
                onTogglePinned = { pinsViewModel.togglePinned(it.id) },
                onToggleFavorite = { pinsViewModel.toggleFavorite(it.id) },
                onAddToHomeScreen = { app ->
                    homeShortcutRequest = launcherViewModel.requestHomeShortcut(app)
                },
                onEditCommand = { app ->
                    editRequest = launcherViewModel.editCommandFor(app)
                },
                gridState = gridState,
                staggerReveal = staggerReveal,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    // ── The install sheet (not-installed / unverified apps only) ──
    val installRow = state.apps.firstOrNull { it.app.id == installTarget }
    if (installRow != null) {
        val job = state.jobs.firstOrNull { it.app.id == installRow.app.id }
        AppInstallSheet(
            row = installRow,
            job = job,
            probing = probing,
            onInstall = { launcherViewModel.install(installRow.app) },
            onOpen = {
                launcherViewModel.launch(installRow.app)
                installTarget = null
            },
            onDismiss = { installTarget = null }
        )
    }

    // ── The uninstall confirmation dialog (always required) ──
    val uninstallApp = state.apps.firstOrNull { it.app.id == uninstallTarget }?.app
    if (uninstallApp != null) {
        AppConfirmDialog(
            title = stringResource(R.string.launcher_uninstall_title, uninstallApp.name),
            message = stringResource(R.string.launcher_uninstall_message, uninstallApp.name),
            confirmLabel = stringResource(R.string.launcher_uninstall_action),
            danger = true,
            onConfirm = {
                launcherViewModel.uninstall(uninstallApp)
                uninstallTarget = null
            },
            onDismiss = { uninstallTarget = null }
        )
    }

    // ── The context menu's "Add to home screen" platform request ──
    homeShortcutRequest?.let { request ->
        // The ShortcutManager call is screen-owned (needs a Context); the
        // request object keeps this composable's logic testable.
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(request) {
            requestHomeScreenShortcut(context, request)
            homeShortcutRequest = null
        }
    }

    // ── The context menu's "Edit command" dialog ──
    editRequest?.let { request ->
        var command by remember(request.appId) { mutableStateOf(request.initialCommand) }
        com.fem.linexa.ui.components.haven.HDialog(
            title = stringResource(R.string.launcher_edit_command_title, request.appName),
            onDismiss = { editRequest = null },
            actions = {
                HButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = { editRequest = null },
                    variant = HavenButtonVariant.SECONDARY,
                    size = HavenButtonSize.SMALL
                )
                HButton(
                    label = stringResource(R.string.common_save),
                    onClick = {
                        onAddCustomCommand("${request.appName} (edited)", command)
                        editRequest = null
                    },
                    variant = HavenButtonVariant.PRIMARY,
                    size = HavenButtonSize.SMALL,
                    enabled = command.isNotBlank()
                )
            },
            content = {
                com.fem.linexa.ui.components.haven.HField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = stringResource(R.string.launcher_edit_command_hint)
                )
            }
        )
    }
}

/**
 * Pure arrangement for the launcher grid (JVM-tested).
 *
 * Rules:
 *  - Search matches name, description and category (case-insensitive).
 *  - A category filter and a search query never combine — a chip tap clears
 *    the query and a keystroke clears the chip, so "what am I looking at" is
 *    always one honest answer.
 *  - Pinned apps lift to the TOP band regardless of category (the same rule
 *    the Commands grid uses); everything else stays in catalog order.
 *  - Rows are chunked 3-across for the grid composable.
 */
object LauncherGridModel {

    fun matches(app: LauncherApp, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return app.name.contains(q, ignoreCase = true) ||
            (app.description?.contains(q, ignoreCase = true) == true) ||
            (app.category?.contains(q, ignoreCase = true) == true)
    }

    fun arrange(
        rows: List<AppLauncherViewModel.AppRow>,
        query: String,
        category: String?,
        pins: ShortcutPins
    ): List<AppLauncherViewModel.AppRow> {
        val filtered = rows.filter { row ->
            (category == null || row.app.category == category) && matches(row.app, query)
        }
        return ShortcutPinsOrdering.orderedBy(filtered, { it.app.id }, pins)
    }

    /** The distinct categories present in [rows], in catalog order. */
    fun categoriesOf(rows: List<AppLauncherViewModel.AppRow>): List<String> =
        rows.mapNotNull { it.app.category }.distinct()
}

/**
 * The launcher's two workspace-tool entries — Packages and Files, side by side
 * above the search field. They are the entry points the Settings tab used to
 * carry (its Tools card was retired when this pair shipped): equal 48dp
 * buttons, one icon + label each, pushing the SAME destinations the old rows
 * opened, so which section you are in is always named by the destination's own
 * header. Icon accents follow the colors the rest of the app already speaks —
 * amber for package boxes, blue for folder surfaces.
 */
@Composable
private fun LauncherToolButtons(
    onOpenPackageManager: () -> Unit,
    onOpenFileManager: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 10.dp
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(HavenDim.cardGap)
    ) {
        HButton(
            label = stringResource(R.string.haven_packages),
            onClick = onOpenPackageManager,
            variant = HavenButtonVariant.SECONDARY,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_box),
                    contentDescription = null,
                    tint = h.amber,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.weight(1f)
        )
        HButton(
            label = stringResource(R.string.haven_files),
            onClick = onOpenFileManager,
            variant = HavenButtonVariant.SECONDARY,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_folder),
                    contentDescription = null,
                    tint = h.blue,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** The launcher's category chips (the search field lives in the morph bar above). */
@Composable
private fun LauncherSearchAndChips(
    apps: List<AppLauncherViewModel.AppRow>,
    categories: List<String>,
    searchQuery: String,
    searchActive: Boolean,
    onSearchQueryChange: (String) -> Unit,
    categoryFilter: String?,
    onCategorySelect: (String) -> Unit
) {
    val h = HavenTheme.colors
    val chipMotions = LocalHavenMotions.current
    AnimatedVisibility(
        visible = categories.isNotEmpty() && !searchActive,
        enter = fadeIn(chipMotions.enter) + expandVertically(chipMotions.enterSpec()),
        exit = fadeOut(chipMotions.exit) + shrinkVertically(chipMotions.exitSpec())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HChip(
                label = stringResource(R.string.grid_chip_all),
                selected = searchQuery.isBlank() && categoryFilter == null,
                onClick = {
                    onSearchQueryChange("")
                    onCategorySelect("")
                }
            )
            categories.forEach { category ->
                HChip(
                    label = category,
                    selected = searchQuery.isBlank() && categoryFilter == category,
                    onClick = { onCategorySelect(category) }
                )
            }
        }
    }
}

/**
 * The launcher grid. A [LazyVerticalGrid] that fills the remaining height of
 * the Apps tab (the tool pair, search field and chips above it stay put),
 * adapting its column count to the screen width; its state comes from the
 * caller so the scroll position survives leaving and re-entering the tab.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColumnScopeGrid(
    rows: List<AppLauncherViewModel.AppRow>,
    launchStates: Map<String, AppLaunchState>,
    jobs: Map<String, AppInstallCoordinator.JobState>,
    pins: ShortcutPins,
    onLaunch: (LauncherApp) -> Unit,
    onMenuToggle: (String) -> Unit,
    menuTargetId: String?,
    onUninstall: (LauncherApp) -> Unit,
    onTogglePinned: (LauncherApp) -> Unit,
    onToggleFavorite: (LauncherApp) -> Unit,
    onAddToHomeScreen: (LauncherApp) -> Unit,
    onEditCommand: (LauncherApp) -> Unit,
    gridState: LazyGridState,
    staggerReveal: Boolean,
    modifier: Modifier = Modifier
) {
    val gridMotions = LocalHavenMotions.current
    val motionRows = rememberMotionItems(rows) { it.app.id }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(motionRows, key = { _, mi -> mi.key }) { index, mi ->
            val row = mi.value
            val firstPageReveal = index.takeIf {
                staggerReveal && it < HavenMotion.STAGGER_MAX_ITEMS
            }
            LauncherAppTile(
                row = row,
                // Stable keys (app.id) + placement animation: surviving tiles
                // glide to their new cells. Entering tiles fade/scale/lift in,
                // leaving tiles fade out before their cell closes (see
                // HavenCollectionMotion.kt for why this is retained, not
                // animateItem()). The very first page keeps its own bounded
                // stagger reveal below, so it is excluded from ENTERING here.
                modifier = Modifier
                    .animateItemPlacement(animationSpec = gridMotions.enterSpec())
                    .motionItem(
                        phase = if (firstPageReveal != null && mi.phase == ItemPhase.ENTERING) {
                            ItemPhase.PRESENT
                        } else {
                            mi.phase
                        },
                        enterOrdinal = mi.enterOrdinal
                    ),
                // Only the first bounded page animates
                revealIndex = firstPageReveal,
                launchState = launchStates[row.app.id],
                installed = row.installed,
                job = jobs[row.app.id],
                pinned = pins.isPinned(row.app.id),
                favorite = pins.isFavorite(row.app.id),
                menuOpen = menuTargetId == row.app.id,
                onClick = { onLaunch(row.app) },
                onLongPress = { onMenuToggle(row.app.id) },
                onUninstall = { onUninstall(row.app) },
                onTogglePinned = { onTogglePinned(row.app) },
                onToggleFavorite = { onToggleFavorite(row.app) },
                onAddToHomeScreen = { onAddToHomeScreen(row.app) },
                onEditCommand = { onEditCommand(row.app) }
            )
        }
    }
}

/**
 * One tile: the blob icon, name, measured status subtitle, and — anchored in
 * the tile — the long-press context menu. The menu's items ADAPT to the
 * measured install state: Uninstall only for a measured-installed app, Install
 * only for a measured-not-installed one; Pin/Favorite/Home screen/Edit
 * command are always applicable (they are launcher-side facts).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LauncherAppTile(
    row: AppLauncherViewModel.AppRow,
    modifier: Modifier = Modifier,
    revealIndex: Int?,
    launchState: AppLaunchState?,
    installed: AppInstallTracker.InstalledState,
    job: AppInstallCoordinator.JobState?,
    pinned: Boolean,
    favorite: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onUninstall: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onEditCommand: () -> Unit
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    // Capture the reveal decision ONCE. When the parent consumes the stagger
    // flag this tile may recompose with revealIndex = null; the remembered
    // Animatable must keep running, and a tile that first appears later must
    // start settled (no reveal on scroll).
    val animateIn = remember { revealIndex != null }
    val reveal = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(reveal) {
        if (animateIn) {
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = motions.staggerSpec(revealIndex ?: 0)
            )
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val app = row.app
    val status = LauncherInstallPolicy.tileStatus(installed, job)
    val subtitle = when (status) {
        LauncherInstallPolicy.TileStatus.INSTALLED ->
            stringResource(R.string.launcher_status_installed)
        LauncherInstallPolicy.TileStatus.NOT_INSTALLED ->
            stringResource(R.string.launcher_status_not_installed)
        LauncherInstallPolicy.TileStatus.UNKNOWN ->
            stringResource(R.string.launcher_status_unknown)
        LauncherInstallPolicy.TileStatus.INSTALLING ->
            stringResource(R.string.launcher_status_installing)
        LauncherInstallPolicy.TileStatus.REMOVING ->
            stringResource(R.string.launcher_status_removing)
        LauncherInstallPolicy.TileStatus.VERIFYING ->
            stringResource(R.string.launcher_status_verifying)
        LauncherInstallPolicy.TileStatus.FAILED ->
            stringResource(R.string.launcher_status_failed)
        LauncherInstallPolicy.TileStatus.CANCELLED ->
            stringResource(R.string.launcher_status_cancelled)
    }
    val isInstalled = LauncherInstallPolicy.isInstalled(installed, job)
    val jobRunning = job?.isActive == true
    // The tile that owns an open context menu gets a quiet raised surface
    // (reference video: the pressed tile is highlighted while its menu is up).
    val menuShown = menuOpen && isInstalled && !jobRunning
    val highlight by androidx.compose.animation.animateColorAsState(
        targetValue = if (menuShown) h.surface2 else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = motions.selection,
        label = "tile-menu-highlight"
    )
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (animateIn) {
                        Modifier.graphicsLayer {
                            alpha = reveal.value
                            translationY = (1f - reveal.value) * 12.dp.toPx()
                        }
                    } else {
                        Modifier
                    }
                )
                .havenPressScale(interaction)
                .clip(RoundedCornerShape(20.dp))
                .background(highlight)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongPress
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Box {
            CommandAppTileIcon(
                iconRes = WorkspaceShortcuts.iconFor(app.iconKey),
                shape = CommandGridModel.blobVariant(app.id),
                palette = CommandGridModel.blobPalette(app.id)
            )
            if (pinned || favorite) {
                Icon(
                    painter = painterResource(
                        if (pinned) R.drawable.ic_haven_pin else R.drawable.ic_haven_star
                    ),
                    contentDescription = if (pinned) {
                        stringResource(R.string.grid_badge_pinned)
                    } else {
                        stringResource(R.string.grid_badge_favorite)
                    },
                    tint = h.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(13.dp)
                )
            }
            // The installing overlay: the honest "work is happening" spinner.
            if (launchState is AppLaunchState.Opening || jobRunning) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(h.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    HSpinner(small = true)
                }
            }
        }
        Text(
            text = app.name,
            style = HavenType.rowTitle.copy(fontSize = 13.sp),
            color = if (!isInstalled && !jobRunning) {
                h.text2
            } else {
                h.text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 9.dp)
        )
        Text(
            text = subtitle,
            style = HavenType.small,
            color = h.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
        }

        // The tile's own context menu — anchored here so it opens at the tile.
        // Items adapt to the MEASURED install state (never a guessed one).
        // Installed apps ONLY, and never while a package operation runs.
        HMenu(
            expanded = menuOpen && isInstalled && !jobRunning,
            onDismiss = onLongPress,
            items = if (!isInstalled || jobRunning) emptyList() else listOf(
                HMenuItem(
                    label = stringResource(R.string.launcher_menu_launch),
                    iconRes = R.drawable.ic_haven_play,
                    onClick = onClick
                ),
                HMenuItem(
                    label = if (pinned) stringResource(R.string.grid_menu_unpin)
                    else stringResource(R.string.grid_menu_pin),
                    iconRes = R.drawable.ic_haven_pin,
                    onClick = onTogglePinned
                ),
                HMenuItem(
                    label = if (favorite) stringResource(R.string.grid_menu_unfavorite)
                    else stringResource(R.string.grid_menu_favorite),
                    iconRes = R.drawable.ic_haven_star,
                    onClick = onToggleFavorite
                ),
                HMenuItem(
                    label = stringResource(R.string.launcher_menu_home_screen),
                    iconRes = R.drawable.ic_haven_home,
                    onClick = onAddToHomeScreen
                ),
                HMenuItem(
                    label = stringResource(R.string.launcher_menu_edit_command),
                    iconRes = R.drawable.ic_haven_edit,
                    onClick = onEditCommand
                ),
                HMenuItem(
                    label = stringResource(R.string.launcher_menu_uninstall),
                    iconRes = R.drawable.ic_haven_trash,
                    danger = true,
                    onClick = onUninstall
                )
            ),
            alignEnd = true
        )
    }
}

/** [CommandAppTile]'s icon, isolated so the tile file reuses the exact same blob language. */
@Composable
private fun CommandAppTileIcon(iconRes: Int, shape: Int, palette: Int) {
    AppBlobIcon(
        iconRes = iconRes,
        shape = shape,
        palette = palette,
        contentDescription = null
    )
}

/** The compact per-job install indicator row (top of the app). */
@Composable
private fun AppInstallIndicatorList(
    jobs: List<AppInstallCoordinator.JobState>,
    onDismiss: (AppInstallCoordinator.JobState) -> Unit = {}
) {
    val h = HavenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        jobs.forEach { job ->
            HCard(tight = true) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val title = if (job.isActive) {
                            when (job.phase) {
                                AppInstallCoordinator.JobState.Phase.QUEUED ->
                                    stringResource(R.string.launcher_phase_queued, job.app.name)
                                AppInstallCoordinator.JobState.Phase.INSTALLING ->
                                    stringResource(R.string.launcher_phase_installing, job.app.name)
                                AppInstallCoordinator.JobState.Phase.VERIFYING ->
                                    stringResource(R.string.launcher_phase_verifying, job.app.name)
                                AppInstallCoordinator.JobState.Phase.REMOVING ->
                                    stringResource(R.string.launcher_phase_removing, job.app.name)
                            }
                        } else {
                            when (job.result) {
                                AppInstallCoordinator.JobState.Result.INSTALLED ->
                                    stringResource(R.string.launcher_phase_installed, job.app.name)
                                AppInstallCoordinator.JobState.Result.REMOVED ->
                                    stringResource(R.string.launcher_phase_removed, job.app.name)
                                AppInstallCoordinator.JobState.Result.CANCELLED ->
                                    stringResource(R.string.launcher_phase_cancelled, job.app.name)
                                else ->
                                    stringResource(R.string.launcher_phase_failed, job.app.name)
                            }
                        }
                        Text(
                            text = title,
                            style = HavenType.body,
                            color = h.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (job.isActive) {
                            HSpinner(small = true)
                        } else {
                            val resultIcon = when (job.result) {
                                AppInstallCoordinator.JobState.Result.INSTALLED,
                                AppInstallCoordinator.JobState.Result.REMOVED ->
                                    R.drawable.ic_haven_checkc
                                else -> R.drawable.ic_haven_warning
                            }
                            Icon(
                                painter = painterResource(resultIcon),
                                contentDescription = null,
                                tint = when (job.result) {
                                    AppInstallCoordinator.JobState.Result.FAILED -> h.red
                                    AppInstallCoordinator.JobState.Result.CANCELLED -> h.muted
                                    else -> h.primary
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            HIconGhost(
                                iconRes = R.drawable.ic_haven_close,
                                contentDescription = stringResource(R.string.launcher_dismiss_indicator),
                                onClick = { onDismiss(job) },
                                small = true
                            )
                        }
                    }
                    val detail = if (job.isActive) {
                        job.output.lineSequence().lastOrNull { it.isNotBlank() }
                    } else {
                        job.message?.takeIf { it.isNotBlank() }
                            ?: job.output.lineSequence().lastOrNull { it.isNotBlank() }
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = HavenType.mono12,
                            color = if (job.result == AppInstallCoordinator.JobState.Result.FAILED) h.red else h.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // ONLY a live job gets the animated bar. A terminal job's
                    // output must never keep a progress animation running —
                    // that was the "indicator never stops" defect.
                    if (job.isActive) {
                        HProgress(fraction = null, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/** The launcher's empty state. */
@Composable
private fun EmptyAppsHint(text: String) {
    HCard(tight = true) {
        Text(
            text = text,
            style = HavenType.desc,
            color = HavenTheme.colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
        )
    }
}

/**
 * Publishes the app's home-screen shortcut through Android's
 * [android.content.pm.ShortcutManager].
 *
 * The shortcut deep-links into the workspace (the app's existing
 * `linexa://workspace/{id}` link — the same one the runtime notification
 * uses), so a home-screen tap opens the workspace the app belongs to. The
 * label carries the app name; an icon-less request is honest (the system
 * renders the app's icon). Failures (OEM limits, API gates) log quietly — the
 * action is a convenience, never a workflow the user depends on.
 */
private fun requestHomeScreenShortcut(
    context: android.content.Context,
    request: AppLauncherViewModel.HomeShortcutRequest
) {
    val wsId = currentWorkspaceIdForShortcuts ?: return
    try {
        val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            com.fem.linexa.core.logging.Logger.w(
                "AppLauncher",
                "Home-screen shortcuts not supported on this launcher"
            )
            return
        }
        val deepLink = android.net.Uri.parse("linexa://workspace/$wsId")
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            deepLink
        ).setPackage(context.packageName)
        val shortcut = android.content.pm.ShortcutInfo.Builder(
            context,
            "launcher-${request.appId.substringAfter(':')}"
        )
            .setShortLabel(request.appName.take(10))
            .setLongLabel(request.appName)
            .setIntent(intent)
            .build()
        manager.requestPinShortcut(shortcut, null)
    } catch (e: Exception) {
        com.fem.linexa.core.logging.Logger.w(
            "AppLauncher",
            "Home-screen shortcut request failed: ${e.message}"
        )
    }
}

/** The workspace id the home-screen shortcut deep-links into (set by the screen on attach). */
internal var currentWorkspaceIdForShortcuts: String? = null

/** One launcher confirmation dialog (install / uninstall). */
@Composable
private fun AppConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    com.fem.linexa.ui.components.haven.HDialog(
        title = title,
        body = message,
        onDismiss = onDismiss,
        actions = {
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = HavenButtonVariant.SECONDARY,
                size = HavenButtonSize.SMALL
            )
            HButton(
                label = confirmLabel,
                onClick = onConfirm,
                variant = if (danger) HavenButtonVariant.DANGER else HavenButtonVariant.PRIMARY,
                size = HavenButtonSize.SMALL
            )
        }
    )
}

/**
 * The install sheet — the ONLY surface a not-installed (or not-yet-verified)
 * app opens. It shows the app, its measured status, an explicit Install
 * button, live job progress, and flips to "Open" when the guest verifies the
 * install. Nothing here is simulated: status comes from [AppInstallTracker]
 * (via [row]) and progress from the coordinator's [job].
 */
@Composable
private fun AppInstallSheet(
    row: AppLauncherViewModel.AppRow,
    job: AppInstallCoordinator.JobState?,
    probing: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val app = row.app
    val action = LauncherInstallPolicy.sheetAction(row.installed, job, probing)
    val running = action == LauncherInstallPolicy.SheetAction.PROGRESS
    val installed = LauncherInstallPolicy.isInstalled(row.installed, job)
    val failed = job?.result == AppInstallCoordinator.JobState.Result.FAILED
    val cancelled = job?.result == AppInstallCoordinator.JobState.Result.CANCELLED
    val unknown = row.installed == AppInstallTracker.InstalledState.UNKNOWN
    val statusText = when {
        installed -> stringResource(R.string.launcher_status_installed)
        running -> when (job?.phase) {
            AppInstallCoordinator.JobState.Phase.QUEUED ->
                stringResource(R.string.launcher_phase_queued, app.name)
            AppInstallCoordinator.JobState.Phase.VERIFYING ->
                stringResource(R.string.launcher_phase_verifying, app.name)
            AppInstallCoordinator.JobState.Phase.REMOVING ->
                stringResource(R.string.launcher_phase_removing, app.name)
            else -> stringResource(R.string.launcher_phase_installing, app.name)
        }
        failed -> stringResource(R.string.launcher_status_failed)
        cancelled -> stringResource(R.string.launcher_status_cancelled)
        unknown && probing -> stringResource(R.string.launcher_sheet_checking)
        unknown -> stringResource(R.string.launcher_status_unknown)
        else -> stringResource(R.string.launcher_status_not_installed)
    }
    HSheet(onDismiss = onDismiss, skipPartiallyExpanded = false) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBlobIcon(
                    iconRes = WorkspaceShortcuts.iconFor(app.iconKey),
                    shape = CommandGridModel.blobVariant(app.id),
                    palette = CommandGridModel.blobPalette(app.id),
                    contentDescription = null,
                    blobSize = 56,
                    glyphSize = 26
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = HavenType.rowTitle.copy(fontSize = 20.sp),
                        color = h.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        style = HavenType.small,
                        color = when {
                            installed -> h.primary
                            failed -> h.red
                            else -> h.muted
                        },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            app.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            // Live progress: the coordinator's phase + the manager's own last
            // line. The bar exists ONLY while the job is active.
            AnimatedVisibility(
                visible = running,
                enter = fadeIn(motions.enter) + expandVertically(motions.enterSpec()),
                exit = fadeOut(motions.exit) + shrinkVertically(motions.exitSpec())
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    val phaseLabel = when (job?.phase) {
                        AppInstallCoordinator.JobState.Phase.VERIFYING ->
                            stringResource(R.string.launcher_phase_verifying, app.name)
                        AppInstallCoordinator.JobState.Phase.REMOVING ->
                            stringResource(R.string.launcher_phase_removing, app.name)
                        AppInstallCoordinator.JobState.Phase.QUEUED ->
                            stringResource(R.string.launcher_phase_queued, app.name)
                        else -> stringResource(R.string.launcher_phase_installing, app.name)
                    }
                    Text(text = phaseLabel, style = HavenType.body, color = h.text)
                    val tail = job?.output?.lineSequence()?.lastOrNull { it.isNotBlank() }
                    if (tail != null) {
                        Text(
                            text = tail,
                            style = HavenType.mono12,
                            color = h.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // The coordinator reports phases and output, not a byte
                    // fraction — so this bar is honestly indeterminate.
                    HProgress(fraction = null, modifier = Modifier.padding(top = 10.dp))
                }
            }
            if (failed && !running) {
                Text(
                    text = job?.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.launcher_sheet_failed),
                    style = HavenType.desc,
                    color = h.red,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            if (cancelled && !running) {
                Text(
                    text = job?.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.launcher_sheet_cancelled),
                    style = HavenType.desc,
                    color = h.muted,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            when (action) {
                LauncherInstallPolicy.SheetAction.OPEN -> {
                    HButton(
                        label = stringResource(R.string.launcher_sheet_open_button, app.name),
                        onClick = onOpen,
                        variant = HavenButtonVariant.PRIMARY,
                        block = true
                    )
                }
                LauncherInstallPolicy.SheetAction.INSTALL,
                LauncherInstallPolicy.SheetAction.CHECKING -> {
                    HButton(
                        label = stringResource(R.string.launcher_sheet_install_button, app.name),
                        onClick = onInstall,
                        variant = HavenButtonVariant.PRIMARY,
                        enabled = action == LauncherInstallPolicy.SheetAction.INSTALL,
                        block = true,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_haven_download),
                                contentDescription = null,
                                tint = h.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Text(
                        text = stringResource(R.string.launcher_sheet_hint),
                        style = HavenType.small,
                        color = h.muted,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                LauncherInstallPolicy.SheetAction.PROGRESS -> {
                    // No Install CTA while a job is genuinely running.
                    Text(
                        text = stringResource(R.string.launcher_sheet_hint),
                        style = HavenType.small,
                        color = h.muted
                    )
                }
            }
            HButton(
                label = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = HavenButtonVariant.GHOST,
                block = true,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
