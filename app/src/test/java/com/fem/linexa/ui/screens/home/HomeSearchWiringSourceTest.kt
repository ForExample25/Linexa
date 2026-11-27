package com.fem.linexa.ui.screens.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Source-pin tests for the Home search / sort / filter wiring (P3). The behaviour of the
 * ranking and the policy is covered by plain unit tests; these pin the *wiring* that no
 * JVM test can execute without a Compose host, in the style of the other
 * `*SourceTest`s in this module.
 */
class HomeSearchWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/home/WorkspacesScreen.kt")
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

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private fun resource(relative: String): String =
        File(moduleRoot, "src/main/res/$relative").readText()

    private val screen get() = source("com/fem/linexa/ui/screens/home/WorkspacesScreen.kt")
    private val viewModel get() = source("com/fem/linexa/ui/screens/home/HomeViewModel.kt")

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("'$from' not found", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("'$to' not found after '$from'", end > start)
        return text.substring(start, end)
    }

    // ── counts stay the total ──────────────────────────────────────────────

    @Test
    fun theHeaderCountAndNavBadgeStillReadTheUnfilteredTotal() {
        assertTrue(screen.contains("count = workspaces.size.toString()"))
        assertTrue(screen.contains("workspaceCount = workspaces.size.takeIf { it > 0 }"))
        assertTrue(
            "`workspaces` must remain the unfiltered workspacesUi",
            screen.contains("val workspaces by viewModel.workspacesUi.collectAsState()")
        )
    }

    @Test
    fun onlyTheListRendersTheFilteredWorkspaces() {
        assertTrue(screen.contains("val visibleWorkspaces by viewModel.visibleWorkspaces.collectAsState()"))
        assertTrue(
            Regex("""is HomeUiState\.HasWorkspaces -> HavenWorkspaceList\(\s*workspaces = visibleWorkspaces""")
                .containsMatchIn(screen)
        )
    }

    // ── empty states ───────────────────────────────────────────────────────

    @Test
    fun theWorkspaceLessEmptyBranchIsUntouched() {
        val emptyBranch = between(screen, "is HomeUiState.Empty ->", "is HomeUiState.HasWorkspaces ->")
        assertTrue(emptyBranch.contains("workspaces = emptyList()"))
        assertFalse(emptyBranch.contains("noResults"))
        assertFalse(emptyBranch.contains("toolbar"))
    }

    @Test
    fun theOrdinaryEmptyStateOnlyServesAListWithNoNoResultsSlot() {
        assertTrue(screen.contains("if (workspaces.isEmpty() && noResults == null) {"))
        val ordinary = between(screen, "if (workspaces.isEmpty() && noResults == null) {", "LazyColumn(")
        assertTrue(ordinary.contains("R.string.home_empty_title"))
        assertFalse(ordinary.contains("home_no_results"))
    }

    @Test
    fun noResultsIsADedicatedHEmptyGatedOnARealNarrowingSearch() {
        val gate = between(screen, "noResults = if (", "} else {")
        assertTrue(gate.contains("WorkspaceListPolicy.showsToolbar(workspaces.size)"))
        assertTrue(gate.contains("listOptions.narrowsList"))
        assertTrue(gate.contains("visibleWorkspaces.isEmpty()"))
        assertTrue(gate.contains("HEmpty("))
        assertTrue(gate.contains("R.string.home_no_results_title"))
        assertTrue(gate.contains("viewModel::clearSearchAndFilters"))
    }

    @Test
    fun theToolbarIsJudgedOnTheTotalNotTheFilteredList() {
        assertTrue(
            Regex("""toolbar = if \(WorkspaceListPolicy\.showsToolbar\(workspaces\.size\)\)""")
                .containsMatchIn(screen)
        )
        assertTrue(screen.contains("item(key = \"list-toolbar\") { toolbar() }"))
    }

    // ── shared components untouched ────────────────────────────────────────

    @Test
    fun theSearchFieldIsTheSharedHSearchFieldWithItsSignatureUnchanged() {
        assertTrue(screen.contains("HSearchField("))
        val controls = source("com/fem/linexa/ui/components/haven/HavenControls.kt")
        assertTrue(
            controls.contains(
                """fun HSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {"""
            )
        )
    }

    @Test
    fun theChipRowIsMadeScrollableAtTheCallSite() {
        assertTrue(screen.contains("HChipRow(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()))"))
    }

    // ── the enrichment pipeline is preserved ───────────────────────────────

    @Test
    fun theEnrichmentPipelineIsStillTheSameChain() {
        val pipeline = between(viewModel, "val workspacesUi: StateFlow<List<Workspace>> =", "// ── Search / sort / filter (P3)")
        // Phase 1 (2026-11-24): the pipeline derives from the ViewModel's single
        // Room upstream (`workspaceRows`) instead of opening a second
        // `repository.observeAll()` collector, and the first non-empty list
        // bypasses the install-burst debounce.
        assertTrue(Regex("""workspaceRows\s*\.debounce \{ list ->""").containsMatchIn(pipeline))
        assertTrue(pipeline.contains("firstNonEmptyEmission"))
        assertTrue(pipeline.contains("ROW_DEBOUNCE_MS"))
        assertTrue(Regex("""\.stateIn\(viewModelScope, SharingStarted\.WhileSubscribed\(5_000\), emptyList\(\)\)""").containsMatchIn(pipeline))
        assertTrue(pipeline.contains("enrichmentCache"))
    }

    @Test
    fun theEnrichmentPipelineKnowsNothingAboutSearchState() {
        val pipeline = between(viewModel, "val workspacesUi: StateFlow<List<Workspace>> =", "// ── Search / sort / filter (P3)")
        assertFalse(pipeline.contains("listOptions"))
        assertFalse(pipeline.contains("WorkspaceListPolicy"))
    }

    @Test
    fun theVisibleListIsDerivedDownstreamOfTheEnrichedList() {
        assertTrue(viewModel.contains("combine(workspacesUi, _listOptions)"))
        assertTrue(viewModel.contains("WorkspaceListPolicy.effective(options, list.size)"))
    }

    @Test
    fun theOptionsResetWhenTheListFallsBelowTheToolbarThreshold() {
        val collector = between(viewModel, "repository.observeAll().collect { workspaces ->", "} catch (e: Exception)")
        assertTrue(collector.contains("WorkspaceListPolicy.showsToolbar(workspaces.size)"))
        assertTrue(collector.contains("_listOptions.update { WorkspaceListOptions() }"))
    }

    // ── nothing is persisted ───────────────────────────────────────────────

    @Test
    fun searchSortAndFilterAreNotPersisted() {
        val block = between(viewModel, "// ── Search / sort / filter (P3)", "init {")
        assertFalse(block.contains("settingsRepository"))
        assertFalse(block.contains("SavedStateHandle"))
        assertFalse(viewModel.contains("SavedStateHandle"))
        val toolbar = between(screen, "private fun HomeListToolbar(", "private fun WorkspaceStatusFilter.labelRes()")
        assertFalse(toolbar.contains("rememberSaveable"))
    }

    // ── strings ────────────────────────────────────────────────────────────

    @Test
    fun everyNewStringExistsInEnglishAndPersian() {
        val keys = listOf(
            "home_search_hint", "home_filter_all", "home_filter_ready", "home_filter_in_progress",
            "home_filter_broken", "home_sort_label", "home_sort_newest", "home_sort_oldest",
            "home_sort_name", "home_no_results_title", "home_no_results_subtitle", "home_no_results_clear"
        )
        for (dir in listOf("values", "values-fa")) {
            val xml = resource("$dir/strings.xml")
            for (key in keys) {
                assertEquals(
                    "$dir/strings.xml must define $key exactly once",
                    1,
                    Regex("""<string name="$key">""").findAll(xml).count()
                )
            }
        }
        assertTrue(resource("values/strings.xml").contains("Sort: %1\$s"))
    }
}
