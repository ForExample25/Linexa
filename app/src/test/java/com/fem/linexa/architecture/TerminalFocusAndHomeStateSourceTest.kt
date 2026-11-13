package com.fem.linexa.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-09-05 debug round — source pins for fixes whose behavior only exists
 * on a real Android device (focus/IME, layout centering, coroutine
 * interleaving). They pin the exact seams the fixes hang off so a future
 * refactor cannot silently drop them:
 *
 *  1. **Terminal focus** (reported: returning to Terminal A requires tapping
 *     the surface before typing works) — the vendored view's ONLY automatic
 *     focus path is its touch handler, so programmatic session binds must
 *     focus + raise the IME at the one choke point every path shares: the
 *     composable observing a bound view, keyed on the session id.
 *  2. **Empty-workspace indicator** (reported: "not workspace yet" sits too
 *     high) — the empty state must be centered through layout weights in a
 *     full-height container, never a LazyColumn item (which stacks from the
 *     top), and never hardcoded coordinates.
 *  3. **Shortcuts per-key runs** (reported: commands "do nothing") — an
 *     in-flight run of one command key must never be cancelled by starting
 *     another; cancellation must rethrow instead of writing a bogus state.
 */
class TerminalFocusAndHomeStateSourceTest {

    private val moduleRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "src/main/java/com/fem/linexa/MainActivity.kt").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("module root not found")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/$path").readText()

    private val realTerminalView: String
        get() = source("com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")

    private val workspacesScreen: String
        get() = source("com/fem/linexa/ui/screens/home/WorkspacesScreen.kt")

    private val shortcutsViewModel: String
        get() = source("com/fem/linexa/ui/screens/workspace/ShortcutsViewModel.kt")

    // ── 1. terminal focus ───────────────────────────────────────────────────

    @Test
    fun terminalViewIsFocusedWithoutRaisingTheImeOnEverySessionBind() {
        // The LaunchedEffect must be keyed on the session id — that is what
        // makes fresh views, session switches, placeholder-gap rebuilds and
        // overlay reopens all land on the same deterministic hook.
        assertTrue(
            "focus must be requested in an effect keyed on ptySession.id",
            realTerminalView.contains("LaunchedEffect(ptySession.id)")
        )
        assertTrue(
            "the bind hook must route through the shared focus helper",
            realTerminalView.contains("focusTerminalView(context, terminalViewState.value)")
        )
        assertTrue(
            "a programmatic bind must NOT raise the keyboard (Recent Sessions " +
                "returns to a terminal without forcing the IME)",
            realTerminalView.contains(
                "internal fun focusTerminalView(\n" +
                    "    context: Context,\n" +
                    "    view: TerminalView?,\n" +
                    "    showKeyboard: Boolean = false\n" +
                    ")"
            )
        )
    }

    @Test
    fun focusHelperRequestsFocusAndOnlyShowsSoftInputWhenAsked() {
        // The helper is the single place the two IME paths (programmatic bind,
        // ⌨ button) share; focus is always requested, the IME is opt-in.
        val helper = realTerminalView.substringAfter("internal fun focusTerminalView")
        assertTrue(
            "the helper must requestFocus on the vendored view",
            helper.contains("view.requestFocus()")
        )
        assertTrue(
            "a missing view must be a safe no-op",
            helper.contains("if (view == null) return")
        )
        assertTrue(
            "the IME is gated on the explicit flag",
            helper.contains("if (!showKeyboard) return")
        )
        assertTrue(
            "the explicit path raises the soft keyboard anchored to the view",
            helper.contains("showSoftInput(view")
        )
    }

    @Test
    fun handleKeyboardButtonUsesTheSharedFocusHelperWithKeyboard() {
        // The ⌨ button and the programmatic bind share the helper; only the
        // explicit button opts into raising the IME.
        assertTrue(
            "TerminalHandle.focusAndShowKeyboard must delegate to the shared helper",
            realTerminalView.contains(
                "focusTerminalView(context, terminalViewState.value, showKeyboard = true)"
            )
        )
    }

    @Test
    fun openingRecentSessionsHidesTheImeAndClearsTerminalFocus() {
        val panel = source("com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")
        assertTrue(
            "the overview transition must clear the terminal's focus",
            panel.contains("focusManager.clearFocus(force = true)")
        )
        assertTrue(
            "the overview transition must hide the keyboard immediately",
            panel.contains("keyboard?.hide()")
        )
        assertTrue(
            "the same work must also run when the overview is already visible",
            panel.contains("LaunchedEffect(showOverview)")
        )
    }

    @Test
    fun focusRequestIsBoundedNotContinuous() {
        // No continuous focus stealing: the wait for the view bind is a
        // bounded attempt count, not a polling loop that never ends.
        val effect = realTerminalView.substringAfter("LaunchedEffect(ptySession.id)")
            .substringBefore("AndroidView(")
        assertTrue(
            "the bind wait must be bounded (VIEW_BIND_ATTEMPTS), not an unbounded loop",
            effect.contains("VIEW_BIND_ATTEMPTS")
        )
        assertTrue(
            "no focus work may run in the AndroidView update block (that fires on " +
                "every recomposition — it would steal focus from dialogs/menus)",
            !realTerminalView.substringAfter("update = { terminalView ->")
                .substringBefore("onRelease = {").contains("showSoftInput")
        )
    }

    // ── 2. empty-workspace indicator centering ──────────────────────────────

    @Test
    fun emptyWorkspaceStateIsCenteredByLayoutWeightsNotLazyColumnItems() {
        // The empty branch must return BEFORE the LazyColumn with a
        // full-height weighted Box — layout-driven centering.
        val body = workspacesScreen.substringAfter("private fun HavenWorkspaceList")
        val emptyBranch = body.substringBefore("LazyColumn(")
        assertTrue(
            "the empty state must take an early-return branch before the LazyColumn " +
                "(a search with a noResults slot is not the install-less empty state)",
            body.contains("if (workspaces.isEmpty() && noResults == null)") &&
                emptyBranch.contains("return")
        )
        assertTrue(
            "centering must be a weight(1f) Box with Center alignment (adapts to " +
                "insets, bars and screen size; no hardcoded offsets)",
            emptyBranch.contains("Modifier.weight(1f)") &&
                emptyBranch.contains("contentAlignment = Alignment.Center")
        )
        // The old broken shape: an "empty" item INSIDE the LazyColumn.
        val lazyBody = body.substringAfter("LazyColumn(")
        assertTrue(
            "the LazyColumn must not carry the empty-state item any more",
            !lazyBody.contains("item(key = \"empty\")")
        )
    }

    @Test
    fun loadedWorkspaceListRenderingIsUnchanged() {
        // The loaded state keeps its card pipeline (per-id keys are
        // load-bearing for the enrichment diffing).
        val lazyBody = workspacesScreen.substringAfter("LazyColumn(")
        assertTrue(
            "workspace cards must keep their per-id LazyColumn keys",
            workspacesScreen.contains("rememberMotionItems(workspaces) { it.id }") &&
                lazyBody.contains("items(motionWorkspaces, key = { it.key })")
        )
        assertTrue(
            "the What's New banner must still render in the loaded list",
            lazyBody.contains("whats-new-")
        )
    }

    // ── 3. shortcuts per-key run isolation ─────────────────────────────────

    @Test
    fun inlineRunsAreKeyedSoOneCommandCannotCancelAnother() {
        assertTrue(
            "run state jobs must be per-key (the shared runJob cancelled an " +
                "in-flight package operation when another command started)",
            shortcutsViewModel.contains("private val runJobs = mutableMapOf<String, Job>()")
        )
        val execute = shortcutsViewModel.substringAfter("private fun execute(")
        assertTrue(
            "the same-key guard must check the per-key job, not the run state",
            execute.contains("runJobs[key]?.isActive == true")
        )
        assertTrue(
            "the guard map must free the key when the job ends (finally)",
            execute.contains("runJobs.remove(key)")
        )
        assertTrue(
            "the shared-runJob cancel must be gone",
            !shortcutsViewModel.contains("runJob?.cancel()")
        )
    }

    @Test
    fun terminalTierCancellationsRethrowInsteadOfWritingAFailedState() {
        // U5/M-2 parity: both terminal tiers must rethrow CancellationException
        // like execute() already did.
        val rootTier = shortcutsViewModel.substringAfter("if (shortcut.asRoot)")
            .substringBefore("if (PackageOperationClassifier")
        val userTier = shortcutsViewModel.substringAfter("launchJob = viewModelScope.launch {")
            .substringAfter("pty.write(command")
        assertTrue(
            "the root-terminal tier must rethrow CancellationException",
            Regex("catch \\(e: CancellationException\\)[\\s\\S]*?throw e")
                .containsMatchIn(rootTier)
        )
        assertTrue(
            "the user-terminal tier must rethrow CancellationException",
            Regex("catch \\(e: CancellationException\\)[\\s\\S]*?throw e")
                .containsMatchIn(userTier)
        )
    }
}
