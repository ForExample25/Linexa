package com.fem.linexa.ui.screens.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.settings.AppLanguage
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.domain.settings.ThemeMode
import com.fem.linexa.domain.version.AppVersionProvider
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspace.WorkspaceDeleter
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * The Home list's *enrichment* contract, after performance remediation 2026-11-23.
 *
 * This replaces `HomeViewModelPreviewTest`, whose subject — a declared preview
 * image reaching the card — no longer exists: PERF-005 found that
 * `Workspace.previewImage` had no consumer at all, and the field plus its
 * per-workspace canonicalization were removed. What is kept here is the half of
 * that file that still describes shipped behaviour: the size line's
 * measured-vs-absent distinction, and a new pin on PERF-011's single emission
 * per enrichment pass.
 *
 * **Real time, not virtual.** `workspacesUi` combines `debounce(150)` with
 * `withContext(Dispatchers.IO)`, and `Dispatchers.IO` is not substitutable — a
 * `runTest`/`StandardTestDispatcher` pair advances virtual time only while every
 * coroutine is on the test scheduler, so the debounce timer never fires and the
 * test scope hangs to its timeout. `runBlocking` with a real wait is both
 * correct and, at this size, faster than fighting that.
 *
 * The mapping also calls `android.graphics.Color.parseColor`, an unmocked stub
 * that throws here. It is *not* stubbed: `Mockito.mockStatic` is thread-confined
 * and the mapping runs on `Dispatchers.IO`, so a stub registered by the test
 * thread would not apply anyway. Instead these tests rely on — and therefore
 * pin — the mapping's fallback to the brand color, which is what makes an
 * unparseable stored hex a colored tile rather than an empty Home list.
 */
class HomeViewModelEnrichmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var store: WorkspaceRootFsMetadata
    private lateinit var repository: FakeRepository
    private lateinit var deleter: WorkspaceDeleter

    /**
     * Test-isolation fix (2026-09-03): every [viewModel] call creates a real
     * ViewModel whose `viewModelScope` (Main = Unconfined) keeps its two-stage
     * `workspacesUi` pipeline running AFTER the test returns. With the old
     * teardown the class-level `resetMain()` raced those leaked resumptions —
     * the missing-Main fallback then hit the unmocked `Looper.getMainLooper()`
     * and printed `CoroutinesInternalError: Fatal exception`, poisoning the
     * NEXT test class's `Dispatchers.setMain` check ("There were uncaught
     * exceptions before the test started") as an order-dependent flake.
     * Cancelling the leaked scopes BEFORE resetMain drains the pipeline
     * cleanly — the root cause, not a sleep.
     */
    private val createdScopes = mutableListOf<kotlinx.coroutines.Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(tmp.newFolder("files"))
        fileManager = WorkspaceFileManager(app)
        store = WorkspaceRootFsMetadata(fileManager, RootFsInspector(WorkspaceConfigParser()))
        repository = FakeRepository()
        deleter = Mockito.mock(WorkspaceDeleter::class.java)
    }

    @After
    fun tearDown() {
        runBlocking { createdScopes.forEach { it.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun seed(workspaceId: String) {
        fileManager.getRootfsPath(workspaceId).mkdirs()
        repository.emit(
            repository.current + metadata(workspaceId)
        )
    }

    /** Gives [workspaceId] bytes on disk so its size is genuinely measurable. */
    private fun writeBytes(workspaceId: String) {
        File(fileManager.getRootfsPath(workspaceId), "payload.bin")
            .writeBytes(ByteArray(8) { 0x41 })
    }

    private fun metadata(id: String) = WorkspaceMetadata(
        id = id,
        status = WorkspaceStatus.Ready,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = id,
        avatarLetter = id.take(1).uppercase(),
        avatarColorHex = "#112233",
        rootfsPath = fileManager.getRootfsPath(id).absolutePath,
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap()
    )

    private fun viewModel(): HomeViewModel {
        val vm = homeViewModel()
        createdScopes += vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
        return vm
    }

    private fun homeViewModel() = HomeViewModel(
        app,
        repository,
        fileManager,
        deleter,
        store,
        // What's New is not this test's subject, so both collaborators are pinned to
        // the one configuration that keeps the card out of the way: a fresh install
        // with the baseline already recorded, which `WhatsNewDecision` resolves to
        // `Hide`. Pinned rather than mocked so a future change to the decision cannot
        // make these assertions start depending on changelog state.
        NoWhatsNewSettings(),
        FirstInstallVersionProvider()
    )

    /**
     * The card list, awaited for real. Bounded so a wiring mistake fails as a
     * timeout with a useful name rather than hanging the suite.
     */
    private fun cards(): List<Workspace> = runBlocking {
        withTimeout(10_000) { viewModel().workspacesUi.first { it.isNotEmpty() } }
    }

    private fun card(workspaceId: String): Workspace = cards().single { it.id == workspaceId }

    /** The one card for [workspaceId] from the first emission where it satisfies [condition]. */
    private fun enrichedCard(workspaceId: String, condition: (Workspace) -> Boolean): Workspace =
        runBlocking {
            withTimeout(10_000) {
                viewModel().workspacesUi.first { list ->
                    list.any { it.id == workspaceId && condition(it) }
                }
            }
        }.single { it.id == workspaceId }

    // ── the size the card advertises ───────────────────────────────────
    // The card omits its size line entirely when the figure is absent, so the
    // mapping must distinguish "measured, and small" from "not measured". Both
    // sides are exercised over the real WorkspaceFileManager rather than a stub,
    // because the sentinel being translated here is that class's own return
    // value for a directory it could not walk.

    @Test
    fun aMeasuredWorkspaceReportsItsTotalSize() {
        seed("ws1")
        writeBytes("ws1")

        val card = enrichedCard("ws1") { it.storageUsedLabel != null }
        assertNotNull(
            "a workspace with bytes on disk must report a size",
            card.storageUsedLabel
        )
    }

    @Test
    fun anUnmeasurableWorkspaceReportsNoSizeRatherThanZero() {
        // A row whose directory holds nothing yet — the shape of a workspace
        // created before its rootfs, or one being deleted from under the list
        // (§1.117). "0 B" here would state a measurement that was never taken.
        seed("ws1")

        assertNull(
            "an unmeasurable workspace must report no size at all",
            card("ws1").storageUsedLabel
        )
    }

    // ── PERF-011: one emission per enrichment pass ─────────────────────

    @Test
    fun homeOpensExactlyOneRoomObserver() {
        // Phase 1 (2026-11-24): `workspacesUi` and `observeWorkspaces` each used
        // to call `repository.observeAll()`, so one HomeViewModel ran two Room
        // observers over the same table. The card pipeline now derives from the
        // single `workspaceRows` bridge owned by `observeWorkspaces`.
        viewModel()
        assertEquals(
            "Home must open exactly one Room observer",
            1,
            repository.observeAllCalls
        )
    }

    @Test
    fun theFirstNonEmptyListIsEmittedWithoutTheDebounceDelay() = runTest {
        // Deterministic virtual-time pin for the first-paint fix: the 150 ms
        // debounce exists to coalesce install-progress bursts, not to delay a
        // cold list. On the old chain this assertion fails at virtual time 0
        // because the first non-empty list is still sitting behind the timer.
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            seed("ws1")
            val vm = viewModel()
            val collector = launch { vm.workspacesUi.collect {} }
            runCurrent()
            assertEquals(
                "the first non-empty list must be painted without waiting for the burst debounce",
                listOf("ws1"),
                vm.workspacesUi.value.map { it.id }
            )
            assertEquals(0L, currentTime)
            collector.cancel()
        } finally {
            Dispatchers.setMain(Dispatchers.Unconfined)
        }
    }

    @Test
    fun theFirstEnrichedEmissionCarriesEveryWorkspace() {
        // PERF-011: Stage 2 used to emit the whole list after *each* workspace,
        // so N cards produced N full-list emissions and N re-runs of
        // `visibleWorkspaces` (search + filter + sort). It now emits once, when
        // the pass completes. The observable difference is the *first* emission
        // that carries any size: it must already carry them all.
        //
        // Caveat: `workspacesUi` is a StateFlow, so a slow collector can
        // conflate intermediate values and make this pass vacuously. The
        // collector here does no work, and the emissions are separated by real
        // filesystem walks, so in practice it observes the first one. A failure
        // is therefore meaningful; a pass is consistent with the fix but not a
        // proof of it — the device measurement in the remediation report is the
        // primary evidence for PERF-011.
        seed("ws1")
        seed("ws2")
        seed("ws3")
        writeBytes("ws1")
        writeBytes("ws2")
        writeBytes("ws3")

        val firstEnriched = runBlocking {
            withTimeout(15_000) {
                viewModel().workspacesUi.first { list ->
                    list.any { it.storageUsedLabel != null }
                }
            }
        }

        assertEquals(
            "the first emission with any size must have every size (PERF-011)",
            3,
            firstEnriched.count { it.storageUsedLabel != null }
        )
        // Give any emission the old code would still be producing a chance to
        // land before the scope is torn down; nothing is asserted on it, but a
        // pipeline that keeps re-emitting after the pass would at least not be
        // mistaken for idle here.
        runBlocking { delay(200) }
    }

    /**
     * Minimal [WorkspaceRepository] whose [observeAll] is a real, controllable
     * flow — the only method Home's card list reads.
     */
    private class FakeRepository : WorkspaceRepository {
        private val state = MutableStateFlow<List<WorkspaceMetadata>>(emptyList())
        val current: List<WorkspaceMetadata> get() = state.value

        /** Number of `observeAll()` calls — the duplicate-collector regression pin. */
        var observeAllCalls = 0
            private set

        fun emit(list: List<WorkspaceMetadata>) {
            state.value = list
        }

        override fun observeAll(): Flow<List<WorkspaceMetadata>> {
            observeAllCalls++
            return state
        }
        override fun observeById(id: String): Flow<WorkspaceMetadata?> = emptyFlow()
        override suspend fun getById(id: String): WorkspaceMetadata? = state.value.find { it.id == id }
        override suspend fun insert(metadata: WorkspaceMetadata) = Unit
        override suspend fun update(metadata: WorkspaceMetadata) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun updateStatus(id: String, status: WorkspaceStatus) = Unit
        override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) = Unit
        override suspend fun updateAttributes(id: String, attributes: Map<String, String>) = Unit
    }

    /**
     * A settings store that already knows about the running build, so the What's New
     * card never enters these assertions. The acknowledged version is deliberately
     * higher than any catalog entry rather than equal to one, so it stays `Hide` even
     * after a future `versionCode` bump.
     */
    private class NoWhatsNewSettings : SettingsRepository {
        override fun observeLastAcknowledgedWhatsNewVersion(): Flow<Int> =
            MutableStateFlow(Int.MAX_VALUE)

        override suspend fun setLastAcknowledgedWhatsNewVersion(versionCode: Int) = Unit

        // Untouched by Home's card list.
        override fun observeDebugEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun isDebugEnabled(): Boolean = false
        override suspend fun setDebugEnabled(enabled: Boolean) = Unit
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
        override fun currentThemeMode(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override fun observeColorTheme(): Flow<ColorTheme> = MutableStateFlow(ColorTheme.ALPINE_BLUE)
        override fun currentColorTheme(): ColorTheme = ColorTheme.ALPINE_BLUE
        override suspend fun setColorTheme(theme: ColorTheme) = Unit
        // The app language is not read by the What's New decision.
        override fun observeAppLanguage(): Flow<AppLanguage> = MutableStateFlow(AppLanguage.SYSTEM)
        override fun currentAppLanguage(): AppLanguage = AppLanguage.SYSTEM
        override suspend fun setAppLanguage(language: AppLanguage) = Unit
        // Terminal UI preferences are not read by the What's New decision.
        override fun observeRecentSessionsLayout(): Flow<RecentSessionsLayout> =
            MutableStateFlow(RecentSessionsLayout.DEFAULT)
        override suspend fun setRecentSessionsLayout(layout: RecentSessionsLayout) = Unit
        override fun observeTerminalToolbar(): Flow<TerminalToolbarSettings> =
            MutableStateFlow(TerminalToolbarSettings.DEFAULT)
        override suspend fun setTerminalToolbar(settings: TerminalToolbarSettings) = Unit
        // The terminal font size left the repository in the 2026-08-29 terminal
        // migration — the terminal owns its appearance (TerminalFontPreference).
    }

    /** The version seam, pinned. See [NoWhatsNewSettings] for why. */
    private class FirstInstallVersionProvider : AppVersionProvider {
        override val versionCode: Int = Int.MAX_VALUE
        override val versionName: String? = "1.0"
        override val isFirstInstall: Boolean = true
    }
}
