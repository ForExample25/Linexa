package com.fem.linexa.ui.screens.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.terminal.TerminalOverviewMetrics
import com.fem.linexa.domain.terminal.TerminalWindow
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HDot
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HavenButtonVariant
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.components.haven.motionItem
import com.fem.linexa.ui.components.haven.rememberMotionItems
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * Recent Sessions — the terminal desktop's task switcher.
 *
 * Two genuinely different arrangements, both selected in
 * `Settings → Terminal → Recent Sessions Layout`:
 *
 *  - [RecentSessionsLayout.VERTICAL] — the launcher-style two-column grid on
 *    phones. More rows are reached by scrolling down.
 *  - [RecentSessionsLayout.HORIZONTAL] — one large session card per page with
 *    natural horizontal paging. The next/previous session is one swipe away.
 *
 * Both variants share the same card, the same stable terminal-id keys and the
 * same trailing New Session action. Card geometry is derived from the
 * available WIDTH (plus the shared aspect ratio), never from the IME-reduced
 * height: the overview is deliberately not IME-padded, and it dismisses the
 * keyboard on entry, so opening/closing the keyboard cannot resize a card.
 *
 * The overview does not create, restart or duplicate a pty. Previews are the
 * caller's bounded, off-main-thread snapshots; selecting or closing a card
 * only calls the caller's existing desktop actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalSessionOverview(
    windows: List<TerminalWindow>,
    activeTerminalId: String?,
    isCreating: Boolean,
    gridState: LazyGridState,
    pagerState: PagerState,
    layout: RecentSessionsLayout,
    onSelectWindow: (String) -> Unit,
    onCreateTerminal: () -> Unit,
    onCloseWindow: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    BackHandler(onBack = onDismiss)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(h.term)
    ) {
        OverviewHeader(
            windowCount = windows.size,
            isCreating = isCreating,
            onCreateTerminal = onCreateTerminal,
            onDismiss = onDismiss
        )

        if (windows.isEmpty()) {
            EmptySessions(
                isCreating = isCreating,
                onCreateTerminal = onCreateTerminal
            )
            return@Column
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (layout) {
                RecentSessionsLayout.VERTICAL -> VerticalSessions(
                    windows = windows,
                    activeTerminalId = activeTerminalId,
                    isCreating = isCreating,
                    gridState = gridState,
                    columns = TerminalOverviewMetrics.columnsForWidth(maxWidth.value),
                    onSelectWindow = onSelectWindow,
                    onCreateTerminal = onCreateTerminal,
                    onCloseWindow = onCloseWindow
                )

                RecentSessionsLayout.HORIZONTAL -> HorizontalSessions(
                    windows = windows,
                    activeTerminalId = activeTerminalId,
                    isCreating = isCreating,
                    pagerState = pagerState,
                    onSelectWindow = onSelectWindow,
                    onCreateTerminal = onCreateTerminal,
                    onCloseWindow = onCloseWindow
                )
            }
        }
    }
}

@Composable
private fun OverviewHeader(
    windowCount: Int,
    isCreating: Boolean,
    onCreateTerminal: () -> Unit,
    onDismiss: () -> Unit
) {
    val h = HavenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OverviewIconButton(
            iconRes = R.drawable.ic_haven_arrow_back,
            contentDescription = stringResource(R.string.terminal_overview_back),
            onClick = onDismiss
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.terminals_overview_title),
                style = HavenType.h2.copy(fontSize = 22.sp),
                // This screen paints on the terminal surface in BOTH themes,
                // so its chrome uses the terminal's on-dark tokens.
                color = h.termText
            )
            if (windowCount > 0) {
                Text(
                    text = stringResource(R.string.terminals_overview_count, windowCount),
                    style = HavenType.desc,
                    color = h.muted,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        OverviewIconButton(
            iconRes = R.drawable.ic_haven_add,
            contentDescription = stringResource(R.string.terminals_new),
            onClick = onCreateTerminal,
            accent = true,
            enabled = !isCreating
        )
    }
}

/**
 * Vertically — the two-column (phone) grid. Column count comes from width;
 * every card is the shared portrait ratio. The New Session tile is the last
 * grid item, so it participates in the same scroll and uses the same size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VerticalSessions(
    windows: List<TerminalWindow>,
    activeTerminalId: String?,
    isCreating: Boolean,
    gridState: LazyGridState,
    columns: Int,
    onSelectWindow: (String) -> Unit,
    onCreateTerminal: () -> Unit,
    onCloseWindow: (String) -> Unit
) {
    val motions = LocalHavenMotions.current
    // Stable terminal-id keys: creating/closing slides a card in or out,
    // surviving cards glide to their new cell. Identity is never tied to the
    // preview text.
    val motionWindows = rememberMotionItems(windows) { it.terminalId }

    // Position the current session once per panel session, but keep a
    // previously chosen overview scroll position on later re-entries.
    LaunchedEffect(Unit) {
        val atTop = gridState.firstVisibleItemIndex == 0 &&
            gridState.firstVisibleItemScrollOffset == 0
        val activeIndex = windows.indexOfFirst { it.terminalId == activeTerminalId }
        if (atTop && activeIndex > 0) gridState.scrollToItem(activeIndex)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TerminalOverviewMetrics.SCREEN_PADDING_DP.dp,
            end = TerminalOverviewMetrics.SCREEN_PADDING_DP.dp,
            top = 4.dp,
            bottom = 28.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(
            TerminalOverviewMetrics.COLUMN_GAP_DP.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        gridItems(motionWindows, key = { it.key }) { mi ->
            val window = mi.value
            TerminalWindowCard(
                window = window,
                selected = window.terminalId == activeTerminalId,
                onClick = { onSelectWindow(window.terminalId) },
                onClose = { onCloseWindow(window.terminalId) },
                modifier = Modifier
                    .animateItemPlacement(animationSpec = motions.enterSpec())
                    .motionItem(mi.phase, mi.enterOrdinal)
                    .fillMaxWidth()
            )
        }
        item(key = NEW_SESSION_KEY) {
            NewSessionCard(
                isCreating = isCreating,
                onClick = onCreateTerminal,
                modifier = Modifier
                    .animateItemPlacement(animationSpec = motions.enterSpec())
                    .fillMaxWidth()
            )
        }
    }
}

/**
 * Horizontally — one large card per page. The pager owns the swipe and the
 * page keys are terminal ids, so closing a page before the current one keeps
 * the visible session selected rather than silently showing a neighbour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HorizontalSessions(
    windows: List<TerminalWindow>,
    activeTerminalId: String?,
    isCreating: Boolean,
    pagerState: PagerState,
    onSelectWindow: (String) -> Unit,
    onCreateTerminal: () -> Unit,
    onCloseWindow: (String) -> Unit
) {
    LaunchedEffect(Unit) {
        if (pagerState.currentPage == 0) {
            val activeIndex = windows.indexOfFirst { it.terminalId == activeTerminalId }
            if (activeIndex > 0) pagerState.scrollToPage(activeIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = TerminalOverviewMetrics.SCREEN_PADDING_DP.dp
            ),
            pageSpacing = TerminalOverviewMetrics.COLUMN_GAP_DP.dp,
            key = { page ->
                windows.getOrNull(page)?.terminalId ?: NEW_SESSION_KEY
            }
        ) { page ->
            val window = windows.getOrNull(page)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (window != null) {
                    TerminalWindowCard(
                        window = window,
                        selected = window.terminalId == activeTerminalId,
                        onClick = { onSelectWindow(window.terminalId) },
                        onClose = { onCloseWindow(window.terminalId) },
                        modifier = Modifier
                            .widthIn(max = TerminalOverviewMetrics.HORIZONTAL_MAX_CARD_WIDTH_DP.dp)
                            .fillMaxWidth()
                    )
                } else {
                    NewSessionCard(
                        isCreating = isCreating,
                        onClick = onCreateTerminal,
                        modifier = Modifier
                            .widthIn(max = TerminalOverviewMetrics.HORIZONTAL_MAX_CARD_WIDTH_DP.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
        PagerDots(
            pageCount = windows.size + 1,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(bottom = 6.dp)
        )
    }
}

/** Page indicator for the horizontal arrangement. */
@Composable
private fun PagerDots(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount.coerceAtMost(9)) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == currentPage) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) h.primary else h.termText.copy(alpha = 0.25f)
                    )
            )
        }
    }
}

@Composable
private fun OverviewIconButton(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    accent: Boolean = false,
    enabled: Boolean = true
) {
    val h = HavenTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            // On-dark chrome: HIconCircle's regular surface is a light theme
            // surface, which would put a white disc on the black overview.
            .background(if (accent) h.tint else h.termText.copy(alpha = 0.08f))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = when {
                !enabled -> h.termDim
                accent -> h.primary
                else -> h.termText
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ColumnScope.EmptySessions(
    isCreating: Boolean,
    onCreateTerminal: () -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                // The empty state also sits on the always-dark terminal
                // surface, so it uses on-dark tokens, not light surfaces.
                .background(h.termText.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_haven_terminal),
                contentDescription = null,
                tint = h.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.terminal_overview_empty_title),
            style = HavenType.h2.copy(fontSize = 20.sp),
            color = h.termText,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = stringResource(R.string.terminal_overview_empty_body),
            style = HavenType.desc,
            color = h.muted,
            modifier = Modifier.padding(top = 6.dp)
        )
        HButton(
            label = stringResource(R.string.terminals_new),
            onClick = onCreateTerminal,
            enabled = !isCreating,
            variant = HavenButtonVariant.PRIMARY,
            modifier = Modifier.padding(top = 20.dp),
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

/**
 * One miniature terminal window: identity bar (title, live-state dot, close),
 * a preview of that session's screen, and — only when there is a real fact to
 * report — a quiet footer. Selected state is an outline + surface shift; no
 * scale, so grid geometry never changes during selection.
 *
 * The footer deliberately no longer repeats the session label. The title is
 * already at the top of the same card; the useful second line is the foreground
 * process, or the honest process state when there is no live process. Blank
 * footers are omitted entirely.
 */
@Composable
private fun TerminalWindowCard(
    window: TerminalWindow,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val shape = RoundedCornerShape(HavenDim.rCard)
    val outline by animateColorAsState(
        targetValue = if (selected) h.primary else h.termText.copy(alpha = 0.16f),
        animationSpec = motions.emphasisSpec(),
        label = "session-card-outline"
    )
    // The overview is an always-dark terminal surface in both themes, so the
    // card chrome uses on-dark tokens. The wash is the only surface change on
    // selection: no scale, no size change, so grid geometry stays fixed.
    val wash by animateColorAsState(
        targetValue = if (selected) {
            h.primary.copy(alpha = 0.10f)
        } else {
            h.termText.copy(alpha = 0.04f)
        },
        animationSpec = motions.emphasisSpec(),
        label = "session-card-surface"
    )
    val statusText = terminalWindowStatusText(window)
    val activeDescription = stringResource(R.string.terminal_overview_active)
    val statusColor = terminalWindowStatusColor(window)
    val process = window.foregroundProcess?.takeIf { it.isNotBlank() }
    val footer = when {
        !window.hasLiveProcess -> statusText
        process != null -> process
        else -> null
    }

    Column(
        modifier = modifier
            .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            .clip(shape)
            .background(h.term)
            .background(wash)
            .border(width = 1.5.dp, color = outline, shape = shape)
            .clickable(
                onClickLabel = stringResource(R.string.terminal_overview_open),
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                stateDescription = if (selected) activeDescription else statusText
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(start = 10.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TerminalWindowIdentityIcon(window = window, size = 21.dp, iconSize = 12.dp)
            Text(
                text = window.title,
                style = HavenType.small.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp
                ),
                color = h.termText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            HDot(
                color = statusColor,
                size = 5.5f,
                modifier = Modifier.semantics { contentDescription = statusText }
            )
            // 36dp visual target inside the identity bar; the card itself is
            // the large tap surface, so the close control stays deliberate.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(23.dp)
                        .clip(CircleShape)
                        .background(h.termText.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_haven_close),
                        contentDescription = stringResource(R.string.terminal_window_close),
                        tint = h.termText,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(HavenDim.rPreview))
                .background(h.term)
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            if (window.preview.isBlank()) {
                PromptPreview(window = window, statusText = statusText)
            } else {
                Text(
                    text = window.preview,
                    style = TextStyle(
                        fontFamily = HavenType.mono,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp
                    ),
                    color = h.termText,
                    maxLines = if (footer == null) 22 else 18,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (footer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = footer,
                    style = HavenType.desc.copy(
                        fontFamily = HavenType.mono,
                        fontSize = 9.5.sp
                    ),
                    color = h.termDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The trailing New Session action. It uses the exact card geometry of a
 * session card in both layouts, has a large tap surface, and gates itself on
 * [isCreating] so a rapid double-tap cannot present as two actions (the
 * ViewModel's in-flight guard is the authoritative duplicate protection).
 */
@Composable
private fun NewSessionCard(
    isCreating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val shape = RoundedCornerShape(HavenDim.rCard)
    val label = stringResource(R.string.terminal_overview_new_session)
    Column(
        modifier = modifier
            .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            .clip(shape)
            .background(h.term)
            .background(h.termText.copy(alpha = 0.03f))
            .border(
                width = 1.5.dp,
                color = h.termText.copy(alpha = 0.18f),
                shape = shape
            )
            .clickable(
                enabled = !isCreating,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick
            )
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(h.termText.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            if (isCreating) {
                HSpinner(small = true, color = h.primary)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_add),
                    contentDescription = null,
                    tint = h.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = label,
            style = HavenType.small.copy(fontWeight = FontWeight.SemiBold),
            color = h.termText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp)
        )
    }
}

/**
 * The honest no-snapshot state: a prompt, not a spinner. It is stable across
 * every poll until the first real output arrives.
 */
@Composable
private fun PromptPreview(window: TerminalWindow, statusText: String) {
    val h = HavenTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.terminal_overview_prompt),
            style = TextStyle(
                fontFamily = HavenType.mono,
                fontSize = 9.5.sp,
                lineHeight = 12.sp
            ),
            color = h.termPrompt,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = if (window.hasLiveProcess) {
                stringResource(R.string.terminal_overview_waiting)
            } else {
                statusText
            },
            style = TextStyle(
                fontFamily = HavenType.mono,
                fontSize = 8.5.sp,
                lineHeight = 11.sp
            ),
            color = h.termDim
        )
    }
}

/** Stable pager/grid key for the trailing action (terminal ids are never this). */
private const val NEW_SESSION_KEY = "__linexa_new_session__"
