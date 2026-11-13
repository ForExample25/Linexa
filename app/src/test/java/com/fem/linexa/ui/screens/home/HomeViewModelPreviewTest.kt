package com.fem.linexa.ui.screens.home

import android.app.Application
import com.fem.linexa.data.Workspace
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
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspace.WorkspaceDeleter
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.124 — the integration §1.121 was missing: a declared preview reaching the
 * Home card.
 *
 * The backend halves were already covered (`RootFsPreviewImageTest` for the path
 * rule, `WorkspaceRootFsMetadataTest` for the store), and both passed while the
 * feature was invisible — because nothing between the store and the UI carried
 * the value. These tests pin the *route*: real [WorkspaceRootFsMetadata] over a
 * real [WorkspaceFileManager] on a real temp filesystem, through the real
 * [HomeViewModel], asserting on the [Workspace] the card actually receives.
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
 *
 * What deliberately is **not** asserted here: the decoded pixels. `BitmapFactory`
 * is a throwing stub on the JVM and this repo runs no instrumented tests, so the
 * testable contract ends at "the card is handed the right `File`". The decode
 * planning is covered by `RootFsPreviewScalingTest`, and that the composable
 * prefers the preview over the glyph is pinned structurally by
 * `DistroAvatarPreviewSourceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPreviewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var store: WorkspaceRootFsMetadata
    private lateinit var repository: FakeRepository
    private lateinit var deleter: WorkspaceDeleter

    /** A native declaration with a preview — `.linexa.yaml`'s schema. */
    private val nativeWithPreview = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
          architecture: arm64
          metadata:
            display_name: Debian (native)
            preview: /opt/linexa/native.png
    """.trimIndent()

    private val nativeWithoutPreview = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
          architecture: arm64
          metadata:
            display_name: Debian (native)
    """.trimIndent()

    /** A legacy Tiny Container descriptor with its container-absolute preview. */
    private val legacyWithPreview = """
        name: Debian Trixie
        description: Republished for Linexa.
        preview: /opt/tiny/preview.png
        boot_command: /bin/bash
    """.trimIndent()

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

    private fun writeNative(workspaceId: String, yaml: String) {
        val base = fileManager.getWorkspaceBasePath(workspaceId)
        base.mkdirs()
        File(base, "config.yaml").writeText(yaml)
    }

    private fun writeLegacy(workspaceId: String, yaml: String) {
        val rootfs = fileManager.getRootfsPath(workspaceId)
        rootfs.mkdirs()
        File(rootfs, RootFsMetadataFile.LEGACY_TINY.fileName).writeText(yaml)
    }

    /** A real (tiny, but non-empty) file where the declaration says it is. */
    private fun writePreviewFile(workspaceId: String, relativePath: String): File {
        val target = File(fileManager.getRootfsPath(workspaceId), relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        return target
    }

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
        // make these preview assertions start depending on changelog state.
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

    /**
     * The card list, awaited until [predicate] holds — i.e. until the Stage-2
     * *enrichment* emission the two-stage pipeline (`transformLatest` in
     * [HomeViewModel]) re-emits per item, not the fast Stage-1 emit whose
     * preview/size fields are null by design. Waiting on
     * `first { it.isNotEmpty() }` would race the fast emit and pin nothing.
     */
    private fun cardsAwaiting(predicate: (List<Workspace>) -> Boolean): List<Workspace> = runBlocking {
        withTimeout(10_000) { viewModel().workspacesUi.first(predicate) }
    }

    /** The one card for [workspaceId] from the first emission where it satisfies [condition]. */
    private fun enrichedCard(workspaceId: String, condition: (Workspace) -> Boolean): Workspace =
        cardsAwaiting { list -> list.any { it.id == workspaceId && condition(it) } }
            .single { it.id == workspaceId }

    // ── the route, per format ───────────────────────────────────────────

    @Test
    fun aNativeDeclaredPreviewReachesTheCard() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        val expected = writePreviewFile("ws1", "opt/linexa/native.png")

        val preview = enrichedCard("ws1") { it.previewImage != null }.previewImage
        assertNotNull("the .linexa.yaml preview must reach the Home card", preview)
        assertEquals(expected.canonicalFile, preview!!.canonicalFile)
    }

    @Test
    fun aLegacyDeclaredPreviewReachesTheCard() {
        seed("ws1")
        writeLegacy("ws1", legacyWithPreview)
        val expected = writePreviewFile("ws1", "opt/tiny/preview.png")

        val preview = enrichedCard("ws1") { it.previewImage != null }.previewImage
        assertNotNull("the .tiny.yaml preview must reach the Home card", preview)
        assertEquals(expected.canonicalFile, preview!!.canonicalFile)
    }

    @Test
    fun whenBothFilesDeclareAPreviewTheNativeOneWins() {
        // The §1.119/§1.123 priority, asserted at the UI boundary: an image
        // republished for Linexa ships both files, and the card must show the
        // artwork the *native* declaration names.
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        writeLegacy("ws1", legacyWithPreview)
        val native = writePreviewFile("ws1", "opt/linexa/native.png")
        val legacy = writePreviewFile("ws1", "opt/tiny/preview.png")

        val preview = enrichedCard("ws1") { it.previewImage != null }.previewImage
        assertEquals(native.canonicalFile, preview?.canonicalFile)
        assertTrue(
            "the legacy preview exists on disk, so this must be a priority decision rather than a fallback",
            legacy.isFile
        )
    }

    @Test
    fun aNativeConfigWithNoMetadataDoesNotShadowALegacyPreview() {
        // A config-only file (features/env) is not a declaration, so it must not
        // suppress the legacy sibling's artwork.
        seed("ws1")
        writeNative("ws1", "linexa:\n  features:\n    audio: true\n")
        writeLegacy("ws1", legacyWithPreview)
        val expected = writePreviewFile("ws1", "opt/tiny/preview.png")

        assertEquals(
            expected.canonicalFile,
            enrichedCard("ws1") { it.previewImage != null }.previewImage?.canonicalFile
        )
    }

    // ── every fallback keeps the pre-§1.121 rendering ───────────────────

    @Test
    fun aWorkspaceThatDeclaresNothingHasNoPreview() {
        // The overwhelmingly common case, and the one that must be unchanged:
        // every workspace installed before §1.121 keeps its glyph/letter avatar.
        seed("ws1")
        assertNull(card("ws1").previewImage)
    }

    @Test
    fun aDeclarationWithNoPreviewKeyHasNoPreview() {
        seed("ws1")
        writeNative("ws1", nativeWithoutPreview)
        assertNull(card("ws1").previewImage)
    }

    @Test
    fun aDeclaredPreviewWhoseFileIsMissingHasNoPreview() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        // No writePreviewFile: the declaration is valid, the file is not there.
        assertNull(card("ws1").previewImage)
    }

    @Test
    fun anEmptyPreviewFileHasNoPreview() {
        // A zero-length file is an unreadable image, not a preview — and it is
        // what a truncated or failed extraction leaves behind.
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        val target = File(fileManager.getRootfsPath("ws1"), "opt/linexa/native.png")
        target.parentFile?.mkdirs()
        target.createNewFile()

        assertNull(card("ws1").previewImage)
    }

    @Test
    fun aPreviewDeclaredAsADirectoryHasNoPreview() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        File(fileManager.getRootfsPath("ws1"), "opt/linexa/native.png").mkdirs()

        assertNull(card("ws1").previewImage)
    }

    @Test
    fun aBrokenLegacyDescriptorHasNoPreviewAndIsNotAnError() {
        seed("ws1")
        writeLegacy("ws1", "name: [unclosed")
        writePreviewFile("ws1", "opt/tiny/preview.png")

        // Fail-open all the way to the card: an unparseable declaration must
        // render as the letter avatar, not break the Home list.
        assertNull(card("ws1").previewImage)
    }

    // ── containment, at the UI boundary ────────────────────────────────

    @Test
    fun aPreviewSymlinkedOutOfTheRootfsNeverReachesTheCard() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        val declared = writePreviewFile("ws1", "opt/linexa/native.png")
        // storageUsedLabel != null marks the Stage-2 *enriched* emission for
        // this card — the fast emit carries null there by design, so this wait
        // cannot be satisfied by it.
        assertNotNull(
            enrichedCard("ws1") { it.storageUsedLabel != null }.previewImage
        )

        // The escape §1.121(b) exists for: the rootfs is guest-writable, so the
        // file that was legitimate a moment ago becomes a link to host bytes.
        val outside = tmp.newFile("host-secret.png").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        declared.delete()
        try {
            java.nio.file.Files.createSymbolicLink(declared.toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return // no symlink support here; covered in RootFsPreviewImageTest
        }

        assertNull(
            "a preview resolving outside the rootfs must never be handed to the UI",
            // Await the *enriched* emission (size measured, declaration consumed) —
            // the fast emit's null would pass vacuously.
            enrichedCard("ws1") { it.storageUsedLabel != null }.previewImage
        )
    }

    @Test
    fun aTraversingPreviewInALegacyFileNeverReachesTheCard() {
        // The legacy path is fail-open, so this is silently no preview rather
        // than an error — but it must not resolve.
        seed("ws1")
        writeLegacy("ws1", "name: Evil\npreview: ../../../../etc/passwd.png\n")
        assertNull(card("ws1").previewImage)
    }

    // ── per-workspace isolation ────────────────────────────────────────

    @Test
    fun eachWorkspaceResolvesItsOwnPreviewAndNeverAnothersFile() {
        // Same declaration, same relative path, two workspaces — the shape that
        // would expose a shared or app-wide preview lookup.
        seed("ws1")
        seed("ws2")
        writeNative("ws1", nativeWithPreview)
        writeNative("ws2", nativeWithPreview)
        val first = writePreviewFile("ws1", "opt/linexa/native.png")
        val second = writePreviewFile("ws2", "opt/linexa/native.png")

        val cards = cardsAwaiting { list ->
            list.isNotEmpty() && list.all { it.previewImage != null }
        }.associateBy { it.id }
        assertEquals(first.canonicalFile, cards.getValue("ws1").previewImage?.canonicalFile)
        assertEquals(second.canonicalFile, cards.getValue("ws2").previewImage?.canonicalFile)
        assertTrue(
            "ws1's preview must be inside ws1's rootfs",
            cards.getValue("ws1").previewImage!!.canonicalPath
                .startsWith(fileManager.getRootfsPath("ws1").canonicalPath + File.separator)
        )
        assertTrue(
            "ws2's preview must be inside ws2's rootfs",
            cards.getValue("ws2").previewImage!!.canonicalPath
                .startsWith(fileManager.getRootfsPath("ws2").canonicalPath + File.separator)
        )
    }

    @Test
    fun aWorkspaceWithNoPreviewIsUnaffectedByASiblingThatHasOne() {
        seed("with")
        seed("without")
        writeNative("with", nativeWithPreview)
        writePreviewFile("with", "opt/linexa/native.png")

        val cards = cardsAwaiting { list ->
            list.any { it.id == "with" && it.previewImage != null }
        }.associateBy { it.id }
        assertNotNull(cards.getValue("with").previewImage)
        assertNull(
            "a declared preview must not leak onto an undeclared workspace's card",
            cards.getValue("without").previewImage
        )
    }

    @Test
    fun oneWorkspacePreviewCannotPointIntoAnotherWorkspacesRootfs() {
        seed("ws1")
        seed("ws2")
        writeNative("ws1", nativeWithPreview)
        val neighbour = writePreviewFile("ws2", "opt/linexa/native.png")

        val declared = File(fileManager.getRootfsPath("ws1"), "opt/linexa/native.png")
        declared.parentFile?.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(declared.toPath(), neighbour.toPath())
        } catch (e: UnsupportedOperationException) {
            return
        }

        assertNull(
            "another workspace's rootfs is outside this one — containment must reject it",
            card("ws1").previewImage
        )
    }

    // ── the rest of the card is untouched ──────────────────────────────

    @Test
    fun addingThePreviewDoesNotDisturbTheOtherCardFields() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        writePreviewFile("ws1", "opt/linexa/native.png")

        val card = card("ws1")
        // The declared display_name is deliberately NOT adopted as the card's
        // name (§1.119: it is only the pre-filled, editable install name), so
        // this must still be the user's own workspace name.
        assertEquals("ws1", card.name)
        assertEquals("Debian 13", card.description)
        assertEquals("W", card.avatarLetter)
        assertEquals(WorkspaceStatus.Ready, card.status)
    }

    // ── the size the card advertises ───────────────────────────────────
    // The card omits its size line entirely when the figure is absent, so the
    // mapping must distinguish "measured, and small" from "not measured". Both
    // sides are exercised over the real WorkspaceFileManager rather than a stub,
    // because the sentinel being translated here is that class's own return
    // value for a directory it could not walk.

    @Test
    fun aMeasuredWorkspaceReportsItsTotalSize() {
        seed("ws1")
        writeNative("ws1", nativeWithPreview)
        // 4 bytes of PNG magic — small, but genuinely measured.
        writePreviewFile("ws1", "opt/linexa/native.png")

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

    /**
     * Minimal [WorkspaceRepository] whose [observeAll] is a real, controllable
     * flow — the only method Home's card list reads.
     */
    private class FakeRepository : WorkspaceRepository {
        private val state = MutableStateFlow<List<WorkspaceMetadata>>(emptyList())
        val current: List<WorkspaceMetadata> get() = state.value

        fun emit(list: List<WorkspaceMetadata>) {
            state.value = list
        }

        override fun observeAll(): Flow<List<WorkspaceMetadata>> = state
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
        // ColorTheme members added to the interface after this fake was
        // written — inert defaults keep the preview tests meaningful.
        override fun observeColorTheme(): Flow<ColorTheme> = MutableStateFlow(ColorTheme.ALPINE_BLUE)
        override fun currentColorTheme(): ColorTheme = ColorTheme.ALPINE_BLUE
        override suspend fun setColorTheme(theme: ColorTheme) = Unit
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
