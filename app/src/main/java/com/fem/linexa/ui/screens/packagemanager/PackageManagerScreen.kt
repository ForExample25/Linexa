package com.fem.linexa.ui.screens.packagemanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.swipeNavigation
import com.fem.linexa.ui.components.haven.motionItem
import com.fem.linexa.ui.components.haven.rememberMotionItems
import com.fem.linexa.R
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.packagemanager.AvailablePkg
import com.fem.linexa.domain.packagemanager.InstalledPkg
import com.fem.linexa.domain.packagemanager.PackageOperation
import com.fem.linexa.ui.components.haven.HBadge
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HChip
import com.fem.linexa.ui.components.haven.HChipRow
import com.fem.linexa.ui.components.haven.HEmpty
import com.fem.linexa.ui.components.haven.HError
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.components.haven.HHairline
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HIconGhost
import com.fem.linexa.ui.components.haven.HLoading
import com.fem.linexa.ui.components.haven.HMonoBlock
import com.fem.linexa.ui.components.haven.HProgress
import com.fem.linexa.ui.components.haven.HSearchField
import com.fem.linexa.ui.components.haven.HSegmented
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HSubHeader
import com.fem.linexa.ui.components.haven.HavenBadgeKind
import com.fem.linexa.ui.components.haven.HavenButtonSize
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

private const val STORE_LOAD_MORE_THRESHOLD = 4

/**
 * Package Manager — HAVEN (concept frame `fr-pkg`).
 *
 * Chrome is the sub-screen header (back circle + title + `<workspace> ·
 * <backend>` subtitle + refresh circle), the pill [HSegmented] for the
 * Installed/Store tabs, and one tight [HCard] per tab whose rows carry the
 * name / mono metadata / description plus the UP/DEL (and OPEN/INSTALL)
 * badges. A running operation docks a bottom sheet-like [HCard] with the
 * Haven spinner, indeterminate progress rail and a bounded mono block of the
 * live output. No Material scaffold, app bar, tabs, chips, fields or
 * indicators remain.
 *
 * Logic is untouched: same [PackageManagerViewModel] wiring, same paging
 * trigger, same operation stream.
 */
@Composable
fun PackageManagerScreen(
    viewModel: PackageManagerViewModel,
    workspaceName: String,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val installedSort by viewModel.installedSort.collectAsState()
    // PKG-1: a read-only projection of PackageOperationCoordinator.snapshotOf.
    // Plain collectAsState is the app-wide pattern (see DeviceInfoScreen's
    // 2026-08-27 crash note); the ViewModel's own collector is cancelled on
    // detach, so nothing observes the queue after the screen goes away.
    val queue by viewModel.queue.collectAsState()
    val listState = rememberLazyListState()
    // Separate scroll state per tab so switching never resets the other tab's
    // position (the lists are lazy and hoisted above the AnimatedContent).
    val installedListState = rememberLazyListState()

    // LX-4 (2026-09-01): the Installed/Store views read the SAME rootfs the
    // terminal writes to — dpkg's database and apt's lists are one shared
    // state, there is no second copy to "sync". What went stale before was
    // THIS screen's cached snapshot: apt changes made in a terminal session
    // (or another manager session) only appeared after a manual refresh.
    // Re-querying the guest on every ON_RESUME closes that gap — the lists
    // are fast `dpkg-query`/`apt-cache` reads and the screen is on the
    // foreground path only while visible, so this adds no background work.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HavenScreen {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                val ready = uiState as? PackageManagerUiState.Ready
                HSubHeader(
                    title = stringResource(R.string.pkg_manager_title),
                    // `<workspace> · <backend>` once the probe resolved a
                    // manager; the workspace name alone while detecting.
                    subtitle = ready?.let { "$workspaceName · ${it.manager.name}" } ?: workspaceName,
                    onBack = onBack,
                    backContentDescription = stringResource(R.string.common_back),
                    trailing = {
                        if (ready != null) {
                            HIconCircle(
                                iconRes = R.drawable.ic_haven_refresh,
                                contentDescription = stringResource(R.string.pkg_manager_refresh),
                                onClick = viewModel::refreshSources
                            )
                        }
                    }
                )
                when (val state = uiState) {
                    is PackageManagerUiState.Unbound, PackageManagerUiState.Detecting -> HLoading(fill = true)
                    // `no_pm` is the honest outcome: none of the five managers
                    // was detected. Any `unsupported_pm_<key>` reason is
                    // unreachable for the five keys the detector can produce,
                    // so it falls back to the generic wording rather than
                    // claiming Linexa supports no manager at all.
                    is PackageManagerUiState.Unsupported -> HEmpty(
                        title = when (state.reason) {
                            "no_pm" -> stringResource(R.string.pkg_manager_unsupported_none)
                            else -> stringResource(R.string.pkg_manager_unsupported)
                        },
                        fill = true
                    )
                    is PackageManagerUiState.Error -> HError(title = state.message, fill = true)
                    is PackageManagerUiState.Ready -> {
                        var tab by remember { mutableIntStateOf(0) }
                        Column(modifier = Modifier.fillMaxSize()) {
                            // HAVEN: the pill segment replaces the Material TabRow
                            // (concept frame fr-pkg — Installed / Store segwrap).
                            HSegmented(
                                options = listOf(
                                    stringResource(R.string.pkg_manager_tab_installed),
                                    stringResource(R.string.pkg_manager_tab_store)
                                ),
                                selected = tab,
                                onSelect = { tab = it },
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)
                            )
                            // PKG-1: operations started by OTHER surfaces
                            // (feature provisioners, shortcuts, the app
                            // installer) hold the same workspace queue. When
                            // this screen has no operation of its own, a
                            // compact strip makes that wait visible; when it
                            // does, the count lives in OperationPanel instead
                            // so the same queue is never shown twice.
                            if (queue.hasOtherActivity &&
                                state.operation?.status != OperationStatus.RUNNING
                            ) {
                                PackageQueueStrip(
                                    activeCommand = queue.activeCommand,
                                    waiting = queue.waiting,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                                )
                            }
                            val tabsMotions = LocalHavenMotions.current
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = {
                                    val forward = targetState > initialState
                                    (fadeIn(animationSpec = tabsMotions.fade) +
                                        slideInHorizontally(animationSpec = tabsMotions.segment) {
                                            if (forward) it / 4 else -it / 4
                                        }) togetherWith
                                        (fadeOut(animationSpec = tabsMotions.fade) +
                                            slideOutHorizontally(animationSpec = tabsMotions.segment) {
                                                if (forward) -it / 4 else it / 4
                                            })
                                },
                                label = "pkg-tab",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .swipeNavigation(
                                        onSwipeLeft = { if (tab < 1) tab = 1 },
                                        onSwipeRight = { if (tab > 0) tab = 0 }
                                    )
                            ) { which ->
                                when (which) {
                                    0 -> InstalledTab(
                                        state = state.installed,
                                        sort = installedSort,
                                        listState = installedListState,
                                        onSortChange = viewModel::onInstalledSortChange,
                                        onRemove = viewModel::removePackage,
                                        onUpdate = viewModel::updatePackage,
                                        onUpgradeAll = viewModel::upgradeAllPackages
                                    )
                                    else -> StoreTab(
                                        state = state.store,
                                        searchQuery = searchQuery,
                                        listState = listState,
                                        onSearchQueryChange = viewModel::onSearchQueryChange,
                                        onSearch = viewModel::search,
                                        onLoadMore = viewModel::loadMore,
                                        onInstall = viewModel::installPackage
                                    )
                                }
                            }
                        }
                    }
                }
            }

            (uiState as? PackageManagerUiState.Ready)?.operation?.let { op ->
                OperationPanel(
                    op = op,
                    // Only a RUNNING own operation can be waiting; a settled
                    // panel must not keep showing a stale count.
                    queued = if (op.status == OperationStatus.RUNNING) queue.waiting else 0,
                    onDismiss = viewModel::dismissOperation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

// ── Installed tab ───────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InstalledTab(
    state: InstalledState,
    sort: InstalledSort,
    listState: LazyListState,
    onSortChange: (InstalledSort) -> Unit,
    onRemove: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onUpgradeAll: () -> Unit
) {
    // Hoisted above the `when` so the diff survives Loading/refresh flips.
    val pkgMotions = LocalHavenMotions.current
    val installedMotion = rememberMotionItems(
        (state as? InstalledState.Loaded)?.packages ?: emptyList()
    ) { it.name }
    when (state) {
        is InstalledState.Loading -> HLoading(fill = true)
        is InstalledState.Failed -> HError(title = state.message, fill = true)
        is InstalledState.Loaded -> {
            if (state.packages.isEmpty()) {
                HEmpty(
                    title = stringResource(R.string.pkg_manager_empty_installed),
                    iconRes = R.drawable.ic_haven_box,
                    fill = true
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    InstalledToolbar(
                        sort = sort,
                        onSortChange = onSortChange,
                        onUpgradeAll = onUpgradeAll,
                        upgradeEnabled = !state.refreshing
                    )
                    // Concept `.list`: one tight card, hairline-separated rows.
                    // The card owns the remaining height and the LazyColumn
                    // scrolls inside it, so the 20dp radius clips the rows on
                    // both ends while items stay lazy.
                    HCard(
                        tight = true,
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Clears the docked operation panel when one shows.
                            contentPadding = PaddingValues(bottom = 150.dp)
                        ) {
                            itemsIndexed(installedMotion, key = { _, mi -> mi.key }) { index, mi ->
                                Column(
                                    modifier = Modifier
                                        .animateItemPlacement(animationSpec = pkgMotions.enterSpec())
                                        .motionItem(mi.phase, mi.enterOrdinal, scaleFrom = 1f, liftFrom = 0.dp)
                                ) {
                                    InstalledPackageRow(
                                        pkg = mi.value,
                                        onRemove = { onRemove(mi.value.name) },
                                        onUpdate = { onUpdate(mi.value.name) }
                                    )
                                    if (index != installedMotion.lastIndex) {
                                        HHairline()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * §1.147 — Sort selector + "Upgrade all" above the Installed list.
 *
 * The three sorts are Haven [HChip]s rather than a dropdown: there are only
 * three, all fit one row on a phone, and the selected one stays visible
 * without a second tap. The toolbar is pinned above the list card so the
 * controls never scroll away mid-sort; the list itself still fills the rest
 * of the screen.
 */
@Composable
private fun InstalledToolbar(
    sort: InstalledSort,
    onSortChange: (InstalledSort) -> Unit,
    onUpgradeAll: () -> Unit,
    upgradeEnabled: Boolean
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.pkg_manager_sort),
                style = HavenType.desc,
                color = h.muted,
                maxLines = 1
            )
            HChipRow {
                InstalledSort.entries.forEach { option ->
                    HChip(
                        label = stringResource(option.labelRes()),
                        selected = sort == option,
                        onClick = { onSortChange(option) }
                    )
                }
            }
        }
        HButton(
            label = stringResource(R.string.pkg_manager_upgrade),
            onClick = onUpgradeAll,
            variant = HavenButtonVariant.SECONDARY,
            size = HavenButtonSize.SMALL,
            enabled = upgradeEnabled,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_refresh),
                    contentDescription = null,
                    tint = h.text2,
                    modifier = Modifier.size(15.dp)
                )
            },
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

private fun InstalledSort.labelRes(): Int = when (this) {
    InstalledSort.NAME -> R.string.pkg_manager_sort_name
    InstalledSort.UPDATES -> R.string.pkg_manager_sort_updates
    InstalledSort.SIZE -> R.string.pkg_manager_sort_size
}

/**
 * §1.147 — One installed package as a concept `.row`.
 *
 * Name / mono metadata / description occupy the weighted text column (every
 * line ellipsised — package names like `fonts-noto-cjk-extra` exceed a
 * phone's width), and the UP / DEL badges are the flex-zero trailing content.
 *
 * - DEL is always rendered. When the manager reported the package as
 *   protected ([InstalledPkg.removable] false) it is greyed and not clickable,
 *   so the user can see the action exists and why it is unavailable.
 * - UP is rendered **only** when the manager reported an available update
 *   ([InstalledPkg.hasUpdate]); there is no disabled UP state. The candidate
 *   version is folded into the mono metadata line (`2.6.1 → 2.7`), so the
 *   reason the action is offered stays visible without a third pill.
 */
@Composable
private fun InstalledPackageRow(
    pkg: InstalledPkg,
    onRemove: () -> Unit,
    onUpdate: () -> Unit
) {
    val updateCd = stringResource(R.string.pkg_manager_update)
    val removeCd = if (pkg.removable) {
        stringResource(R.string.pkg_manager_remove)
    } else {
        stringResource(R.string.pkg_manager_protected)
    }
    PackageRow(
        name = pkg.name,
        metadata = packageMetadata(
            name = pkg.name,
            version = pkg.version +
                (if (pkg.hasUpdate && !pkg.updateVersion.isNullOrBlank()) " → ${pkg.updateVersion}" else ""),
            sizeBytes = pkg.installedSizeBytes,
            category = pkg.categoryLabel
        ),
        description = pkg.description,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pkg.hasUpdate) {
                    HBadge(
                        label = stringResource(R.string.pkg_manager_action_up),
                        kind = HavenBadgeKind.PRIMARY,
                        onClick = onUpdate,
                        modifier = Modifier.semantics { contentDescription = updateCd }
                    )
                }
                HBadge(
                    label = stringResource(R.string.pkg_manager_action_del),
                    kind = HavenBadgeKind.DANGER,
                    enabled = pkg.removable,
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = removeCd
                        if (!pkg.removable) disabled()
                    }
                )
            }
        }
    )
}

// ── Store tab ───────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StoreTab(
    state: StoreState,
    searchQuery: String,
    listState: LazyListState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onInstall: (String) -> Unit
) {
    val h = HavenTheme.colors
    // Search results REPLACE the list (no retained leaving rows); paging and a
    // fresh result set enter with the shared bounded stagger.
    val pkgMotions = LocalHavenMotions.current
    val storeMotion = rememberMotionItems(
        (state as? StoreState.Loaded)?.packages ?: emptyList(),
        retainLeaving = false
    ) { it.name }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HSearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.pkg_manager_search_hint)
            )
            // HSearchField carries no IME action hook, so the explicit circle
            // button is the submit affordance for the existing onSearch call.
            HIconCircle(
                iconRes = R.drawable.ic_haven_search,
                contentDescription = stringResource(R.string.pkg_manager_search),
                onClick = onSearch,
                enabled = searchQuery.isNotBlank(),
                accent = searchQuery.isNotBlank()
            )
        }

        when (state) {
            // §1.147: the pre-search state shows the search affordance and
            // nothing else — no catalog, no package count.
            is StoreState.Idle -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.pkg_manager_store_hint),
                    style = HavenType.desc,
                    color = h.text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
            is StoreState.Loading -> HLoading(fill = true)
            is StoreState.Failed -> HError(title = state.message, fill = true)
            is StoreState.Loaded -> {
                if (state.packages.isEmpty() && !state.refreshing) {
                    // A completed search that matched nothing: an empty result,
                    // not a fault. The query and its clear affordance stay in
                    // the toolbar above, so no action is duplicated here.
                    HEmpty(
                        title = stringResource(R.string.pkg_manager_no_results),
                        fill = true
                    )
                } else {
                    HCard(
                        tight = true,
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 150.dp)
                        ) {
                            itemsIndexed(storeMotion, key = { _, mi -> mi.key }) { index, mi ->
                                Column(
                                    modifier = Modifier
                                        .animateItemPlacement(animationSpec = pkgMotions.enterSpec())
                                        .motionItem(mi.phase, mi.enterOrdinal, scaleFrom = 1f, liftFrom = 0.dp)
                                ) {
                                    StorePackageRow(pkg = mi.value, onInstall = { onInstall(mi.value.name) })
                                    if (index != storeMotion.lastIndex) {
                                        HHairline()
                                    }
                                }
                            }
                            item {
                                LaunchedEffect(listState, state.packages.size) {
                                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                                    if (state.hasMore && lastVisible >= state.packages.size - STORE_LOAD_MORE_THRESHOLD) {
                                        onLoadMore()
                                    }
                                }
                                if (state.loadingMore) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        HSpinner(small = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * §1.147 — One Store result. Same row geometry as the Installed card so the
 * two tabs read as one design. OPEN is only rendered when the manager's
 * metadata carried a real `http(s)` project URL ([AvailablePkg.openableUrl]);
 * INSTALL is always offered. Both render as concept `.badge` verb pills.
 */
@Composable
private fun StorePackageRow(
    pkg: AvailablePkg,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    val url = pkg.openableUrl
    val installCd = stringResource(R.string.pkg_manager_install)
    val openCd = stringResource(R.string.pkg_manager_open_repository)
    PackageRow(
        name = pkg.name,
        metadata = packageMetadata(
            name = pkg.name,
            version = pkg.latestVersion,
            sizeBytes = pkg.downloadSizeBytes ?: pkg.installedSizeBytes,
            category = pkg.categoryLabel
        ),
        description = pkg.description,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (url != null) {
                    HBadge(
                        label = stringResource(R.string.pkg_manager_action_open),
                        kind = HavenBadgeKind.PRIMARY,
                        onClick = { openUrl(context, url) },
                        modifier = Modifier.semantics { contentDescription = openCd }
                    )
                }
                HBadge(
                    label = stringResource(R.string.pkg_manager_action_install),
                    kind = HavenBadgeKind.PRIMARY,
                    onClick = onInstall,
                    modifier = Modifier.semantics { contentDescription = installCd }
                )
            }
        }
    )
}

// ── Shared row pieces ───────────────────────────────────────────────────────

/**
 * The concept `.row` for packages: a weighted text column (name 15sp
 * semibold, mono 12sp muted metadata, 12.5sp description capped at two lines)
 * plus flex-zero trailing actions. HRow itself carries a single description
 * line, so the package's three text lines are composed here from the same
 * tokens it uses.
 */
@Composable
private fun PackageRow(
    name: String,
    metadata: String,
    description: String?,
    trailing: @Composable () -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = HavenType.rowTitle,
                color = h.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = HavenType.mono12,
                    color = h.muted,
                    modifier = Modifier.padding(top = 3.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = HavenType.desc.copy(fontSize = 12.5.sp),
                    color = h.text2,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

/**
 * §1.147 — The compact `name • version • size • category` metadata line, e.g.
 * `apt • 2.6.1 • 4 MB • admin`.
 *
 * Fields the provider did not report are omitted rather than rendered as a
 * placeholder, so the separator count reflects real data — a package whose
 * manager exposes no size shows `nano • 7.2-1 • editors`, not
 * `nano • 7.2-1 • — • editors`.
 */
private fun packageMetadata(
    name: String,
    version: String,
    sizeBytes: Long?,
    category: String?
): String = buildList {
    add(name)
    version.takeIf { it.isNotBlank() }?.let { add(it) }
    sizeBytes?.takeIf { it > 0 }?.let { add(FileSizeFormatter.format(it)) }
    category?.takeIf { it.isNotBlank() }?.let { add(it) }
}.joinToString(" • ")

// ── Operation panel ─────────────────────────────────────────────────────────

/**
 * PKG-1 — a compact read-only row for queue activity this screen did not
 * start. The active command label and the waiter count come straight from
 * [com.fem.linexa.domain.packagemanager.PackageOperationCoordinator.Snapshot];
 * nothing here fabricates progress.
 */
@Composable
private fun PackageQueueStrip(
    activeCommand: String?,
    waiting: Int,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    HCard(tight = true, color = h.surface2, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HSpinner(small = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pkg_manager_queue_title),
                    style = HavenType.desc.copy(fontWeight = FontWeight.SemiBold),
                    color = h.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = activeCommand?.let {
                        stringResource(R.string.pkg_manager_queue_active, it)
                    } ?: stringResource(R.string.pkg_manager_queue_running),
                    style = HavenType.mono12,
                    color = h.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (waiting > 0) {
                Text(
                    text = pluralStringResource(R.plurals.pkg_manager_queue_waiting, waiting, waiting),
                    style = HavenType.mono12.copy(fontWeight = FontWeight.SemiBold),
                    color = h.primary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The docked operation panel (concept bottom-sheet card): spinner/check/warn
 * glyph + status line, the Haven progress rail while running, and a bounded,
 * scrollable mono block of the live output. [OperationUi] carries the output
 * tail but no numeric fraction, so the rail renders the indeterminate sweep.
 *
 * PKG-1 adds [queued]: the coordinator's waiting count while THIS operation
 * runs. It is shown inside the existing panel rather than as a second strip.
 */
@Composable
private fun OperationPanel(
    op: OperationUi,
    queued: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    HCard(tight = true, color = h.surface2, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (op.status) {
                    OperationStatus.RUNNING -> HSpinner(small = true)
                    OperationStatus.SUCCESS -> Icon(
                        painter = painterResource(R.drawable.ic_haven_checkc),
                        contentDescription = null,
                        tint = h.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    OperationStatus.FAILED -> Icon(
                        painter = painterResource(R.drawable.ic_haven_warning),
                        contentDescription = null,
                        tint = h.red,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = operationLabel(op.operation, op.packageName),
                    style = HavenType.rowTitle,
                    color = when (op.status) {
                        OperationStatus.FAILED -> h.red
                        OperationStatus.SUCCESS -> h.primary
                        OperationStatus.RUNNING -> h.text
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Dismissable only once the operation settled (the ViewModel
                // guards this too, matching the pre-Haven behaviour).
                if (op.status != OperationStatus.RUNNING) {
                    HIconGhost(
                        iconRes = R.drawable.ic_haven_close,
                        contentDescription = stringResource(R.string.pkg_manager_dismiss),
                        onClick = onDismiss,
                        small = true
                    )
                }
            }
            if (queued > 0) {
                Text(
                    text = pluralStringResource(R.plurals.pkg_manager_queue_waiting, queued, queued),
                    style = HavenType.mono12.copy(fontWeight = FontWeight.SemiBold),
                    color = h.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (op.status == OperationStatus.RUNNING) {
                Spacer(modifier = Modifier.height(12.dp))
                HProgress(fraction = null)
            }
            if (op.output.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                HMonoBlock(
                    text = op.output,
                    color = h.bg,
                    modifier = Modifier
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun operationLabel(operation: PackageOperation, packageName: String?): String = when (operation) {
    PackageOperation.INSTALL -> stringResource(R.string.pkg_manager_op_install)
    PackageOperation.REMOVE -> stringResource(R.string.pkg_manager_op_remove)
    PackageOperation.UPDATE -> stringResource(R.string.pkg_manager_op_update)
    PackageOperation.UPGRADE -> stringResource(R.string.pkg_manager_op_upgrade)
    PackageOperation.REFRESH -> stringResource(R.string.pkg_manager_op_refresh)
} + (packageName?.let { ": $it" } ?: "")

/**
 * Opens a package's project page in the browser.
 *
 * Wrapped for the same reason as the About screen's opener: a device with no
 * activity handling `http(s)` is unusual but real, and `startActivity` throws
 * rather than returning.
 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser available; the OPEN action simply does nothing.
    }
}
