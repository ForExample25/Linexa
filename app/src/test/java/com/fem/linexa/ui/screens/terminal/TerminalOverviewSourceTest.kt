package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural pins for the Recent Sessions overview.
 *
 * The behavioural half is `TerminalWindowManagerTest` + `TerminalsViewModelTest`
 * + `TerminalOverviewMetricsTest`; this one pins the Compose topology that only
 * exists in source: the two genuinely different arrangements, width-only card
 * geometry, the trailing New Session action, the honest preview fallback and
 * the stable identity keys that make layout switches lossless.
 */
class TerminalOverviewSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/terminal/TerminalSessionOverview.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val overview: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/terminal/TerminalSessionOverview.kt"
        ).readText()

    private val panel: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt"
        ).readText()

    private val grid: String
        get() = overview.substringAfter("LazyVerticalGrid(").substringBefore("gridItems(")

    private val pager: String
        get() = overview.substringAfter("HorizontalPager(").substringBefore("PagerDots(")

    private val card: String
        get() = overview.substringAfter("private fun TerminalWindowCard(")
            .substringBefore("private fun NewSessionCard(")

    @Test
    fun theOverviewOffersExactlyTwoLayouts() {
        assertTrue(
            "the overview receives the selected layout instead of owning one",
            overview.contains("layout: RecentSessionsLayout")
        )
        assertTrue(
            "both arrangements are branches on the same screen",
            overview.contains("RecentSessionsLayout.VERTICAL -> VerticalSessions(") &&
                overview.contains("RecentSessionsLayout.HORIZONTAL -> HorizontalSessions(")
        )
        assertTrue(
            "vertically is a fixed-column grid",
            overview.contains("GridCells.Fixed(columns)") &&
                overview.contains("LazyVerticalGrid(")
        )
        assertTrue(
            "horizontally is a real pager, one page per session",
            overview.contains("HorizontalPager(")
        )
        assertFalse(
            "the old snapping carousel must never return",
            overview.contains("rememberSnapFlingBehavior") ||
                overview.contains("SnapFlingBehavior")
        )
    }

    @Test
    fun bothLayoutsShareOnePortraitCardShape() {
        assertTrue(
            "every card derives its height from the shared aspect ratio",
            overview.contains("aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)")
        )
        assertTrue(
            "the grid column count is chosen from available width",
            overview.contains("TerminalOverviewMetrics.columnsForWidth(maxWidth.value)")
        )
        assertTrue(
            "horizontal cards are capped from width only",
            overview.contains("TerminalOverviewMetrics.HORIZONTAL_MAX_CARD_WIDTH_DP")
        )
        assertTrue("cards fill the page or cell width", overview.contains(".fillMaxWidth()"))
    }

    @Test
    fun cardSizingNeverConsultsHeightOrTheIme() {
        val constraints = overview
            .substringAfter("BoxWithConstraints(")
            .substringBefore("LazyVerticalGrid(")
        assertFalse(
            "card columns must not depend on the IME-reduced height",
            constraints.contains("maxHeight")
        )
        assertFalse(
            "the overview itself must not apply imePadding",
            overview.contains("imePadding")
        )
        assertFalse(
            "the overview must not observe IME insets",
            overview.contains("WindowInsets.ime")
        )
        // The panel isolates IME padding to the terminal column; the overview
        // is its sibling, so the keyboard cannot resize a card.
        assertTrue(
            "IME padding is applied to the terminal column, not the layer",
            panel.contains(".imePadding()")
        )
        val layerCall = panel.substringAfter("TerminalOverviewLayer(")
            .substringBefore("val pendingClose")
        assertFalse(
            "the overview layer is outside every IME-padded wrapper",
            layerCall.contains("imePadding")
        )
    }

    @Test
    fun previewsAreTheSessionsOwnStateAndNeverASpinner() {
        assertTrue(
            "a session with output shows its own snapshot",
            card.contains("text = window.preview")
        )
        assertTrue(
            "a blank session falls back to a terminal prompt",
            card.contains("if (window.preview.isBlank())") &&
                overview.contains("R.string.terminal_overview_prompt")
        )
        assertFalse(
            "a session card must never show an indefinite progress indicator",
            card.contains("HSpinner") ||
                card.contains("HProgress") ||
                card.contains("CircularProgressIndicator") ||
                card.contains("LinearProgressIndicator")
        )
    }

    @Test
    fun cardsCloseAndSelectThroughTheCallerCallbacks() {
        assertTrue(
            "the card exposes the window's close action",
            overview.contains("onClose = { onCloseWindow(window.terminalId) }")
        )
        assertTrue(
            "the card opens that exact window",
            overview.contains("onClick = { onSelectWindow(window.terminalId) }")
        )
        assertTrue(
            "the selected window is distinguishable",
            overview.contains("selected = window.terminalId == activeTerminalId")
        )
        assertTrue(
            "close is labelled for accessibility",
            card.contains("R.string.terminal_window_close")
        )
    }

    @Test
    fun theNewActionIsTrailingInBothLayouts() {
        val header = overview.substringAfter("private fun OverviewHeader(")
            .substringBefore("private fun VerticalSessions(")
        assertTrue(
            "the header keeps a New Terminal shortcut",
            header.contains("R.string.terminals_new")
        )
        val vertical = overview.substringAfter("private fun VerticalSessions(")
            .substringBefore("private fun HorizontalSessions(")
        assertTrue(
            "the vertical grid ends with a New Session tile",
            vertical.contains("item(key = NEW_SESSION_KEY)") &&
                vertical.contains("NewSessionCard(")
        )
        assertTrue(
            "the horizontal pager reserves one trailing New Session page",
            pager.contains("NewSessionCard(")
        )
        assertTrue(
            "the pager indicator counts the New Session page",
            overview.contains("pageCount = windows.size + 1")
        )
        assertTrue(
            "the New Session action gates rapid taps",
            overview.substringAfter("private fun NewSessionCard(")
                .contains("enabled = !isCreating")
        )
        assertTrue(
            "the empty state offers the same primary action",
            overview.substringAfter("private fun ColumnScope.EmptySessions(")
                .contains("R.string.terminals_new")
        )
    }

    @Test
    fun layoutSwitchAndReentryKeepStableIdentity() {
        assertTrue(
            "grid scroll is hoisted so re-entering keeps the position",
            overview.contains("gridState: LazyGridState") &&
                grid.contains("state = gridState")
        )
        assertTrue(
            "pager position is hoisted alongside the grid",
            overview.contains("pagerState: PagerState") &&
                pager.contains("state = pagerState")
        )
        assertTrue(
            "grid cards are keyed by terminal identity, not preview text",
            overview.contains("rememberMotionItems(windows) { it.terminalId }") &&
                overview.contains("key = { it.key }")
        )
        assertTrue(
            "pager pages are keyed by terminal identity",
            overview.contains("windows.getOrNull(page)?.terminalId ?: NEW_SESSION_KEY")
        )
        assertTrue(
            "the panel remembers one pager across layout switches",
            panel.contains("rememberPagerState(")
        )
    }

    @Test
    fun selectionChangesChromeNotGeometry() {
        assertFalse("no card scale animation", card.contains(".scale("))
        assertFalse("no graphics-layer transform", card.contains("graphicsLayer"))
        assertTrue(
            "selection is a colour/border change only",
            card.contains("animateColorAsState(")
        )
    }

    @Test
    fun chromeUsesOnDarkTerminalTokens() {
        // The overview paints on h.term in BOTH themes. Light-theme
        // `surface`/`text` tokens would vanish on it, so its chrome must use
        // the terminal's own on-dark tokens.
        assertTrue(
            "card base and preview are the terminal surface",
            card.contains(".background(h.term)")
        )
        assertTrue(
            "card text uses the on-dark token",
            card.contains("color = h.termText")
        )
        assertFalse(
            "no light surface may sit behind on-dark card text",
            card.contains(".background(h.surface)") ||
                card.contains(".background(h.surface2)")
        )
    }

    @Test
    fun redundantFooterTextIsGone() {
        // The card title is already the session identity; the footer is only
        // a live foreground process or an honest process state.
        assertFalse(
            "the footer must not repeat the window label",
            card.contains("footer = window.displayLabel") ||
                card.contains("text = window.title,\n                    style = HavenType.desc")
        )
        assertTrue(
            "the footer falls back to process state only when no process is live",
            card.contains("!window.hasLiveProcess -> statusText")
        )
    }

    @Test
    fun theOverviewIsNotTheOldTaskManager() {
        assertFalse(
            "the overview no longer embeds a CPU/task-manager card",
            overview.contains("TaskManager") || overview.contains("processCpuTracker")
        )
        assertFalse(
            "no square icon tile remains as the window representation",
            card.contains(".size(64.dp)")
        )
    }
}
