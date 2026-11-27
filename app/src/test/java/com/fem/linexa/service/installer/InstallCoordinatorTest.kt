package com.fem.linexa.service.installer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.ChecksumResolver
import com.fem.linexa.service.distro.DistroDownloader
import com.fem.linexa.service.distro.DistroSourceResolverRegistry
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.rootfs.profile.RootFsProfileResolver
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.rootfs.DeclaredPresentation
import com.fem.linexa.service.rootfs.RootFsArchiveFormat
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.86 unit tests for the [InstallCoordinator] success contract: a success
 * card ([InstallFlowState.Completed]) is emitted ONLY after the extracted
 * rootfs has been verified on disk AND its metadata persisted as `Ready`.
 * Any failure — extraction, verification, or persistence — must surface as
 * [InstallFlowState.Failed] with a `Broken` record, never as a completed
 * install that the workspace list cannot actually list/run.
 *
 * Uses a real [WorkspaceFileManager] (Mockito `Application` + TemporaryFolder)
 * and a fake installer that materializes a valid rootfs on success, so the
 * §1.86 verification step genuinely runs against disk.
 */
class InstallCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var coordinator: InstallCoordinator
    private lateinit var repository: FakeWorkspaceRepository
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var installer: FakeRootFsInstaller

    private val selection = InstallSelection.File(
        uri = Mockito.mock(Uri::class.java),
        distroName = "TestDistro",
        distroVersion = "1.0"
    )

    private fun setUpCoordinator(materializeRootfs: Boolean = true) {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.contentResolver).thenReturn(Mockito.mock(ContentResolver::class.java))

        fileManager = WorkspaceFileManager(app)
        repository = FakeWorkspaceRepository()
        installer = FakeRootFsInstaller(fileManager, materializeRootfs = materializeRootfs)
        coordinator = InstallCoordinator(
            application = app,
            repository = repository,
            fileManager = fileManager,
            installer = installer,
            downloader = Mockito.mock(DistroDownloader::class.java),
            checksumResolver = Mockito.mock(ChecksumResolver::class.java),
            resolverRegistry = DistroSourceResolverRegistry(emptySet()),
            configParser = WorkspaceConfigParser()
        )
    }

    /** Drives the flow to [InstallFlowState.Ready] and returns the workspace id. */
    private fun prepareToReady(): String = runBlocking {
        coordinator.prepare(selection)
        val ready = awaitState { it is InstallFlowState.Ready || it is InstallFlowState.Failed }
        assertTrue("preparation must reach Ready, was: $ready", ready is InstallFlowState.Ready)
        (ready as InstallFlowState.Ready).summary.workspaceId
    }

    private suspend fun awaitState(predicate: (InstallFlowState) -> Boolean): InstallFlowState {
        return withTimeout(10_000) {
            while (!predicate(coordinator.state.value)) {
                delay(10)
            }
            coordinator.state.value
        }
    }

    private suspend fun awaitTerminal(): InstallFlowState =
        awaitState { it is InstallFlowState.Completed || it is InstallFlowState.Failed }

    @Test
    fun successVerifiesAndPersistsReadyBeforeCompleted() {
        setUpCoordinator()
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        val terminal = runBlocking { awaitTerminal() }

        assertTrue("install must complete, was: $terminal", terminal is InstallFlowState.Completed)
        assertTrue("rootfs must be verified present on disk", fileManager.hasValidRootfs(workspaceId))
        val persisted = repository.getByIdSync(workspaceId)
        assertNotNull("workspace row must exist", persisted)
        assertEquals(
            "metadata must be persisted as Ready BEFORE Completed is emitted",
            WorkspaceStatus.Ready,
            persisted!!.status
        )
        assertEquals("sha256 must be recorded", "deadbeef", persisted.attributes["sha256"])
    }

    @Test
    fun installFailureNeverCompletes() {
        setUpCoordinator()
        installer.failInstall = "disk full"
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        val terminal = runBlocking { awaitTerminal() }

        assertTrue("failed install must be Failed, was: $terminal", terminal is InstallFlowState.Failed)
        assertTrue("Completed must never be emitted", terminal !is InstallFlowState.Completed)
        assertEquals(
            "failed install must be recorded as Broken",
            WorkspaceStatus.Broken("disk full"),
            repository.getByIdSync(workspaceId)?.status
        )
    }

    @Test
    fun unverifiedRootfsNeverCompletes() {
        setUpCoordinator(materializeRootfs = false)
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        val terminal = runBlocking { awaitTerminal() }

        assertTrue("an unverifiable rootfs must fail, was: $terminal", terminal is InstallFlowState.Failed)
        assertTrue("Completed must never be emitted for an unverified rootfs", terminal !is InstallFlowState.Completed)
        assertTrue(
            "status must be Broken (not Ready) when verification fails",
            repository.getByIdSync(workspaceId)?.status is WorkspaceStatus.Broken
        )
        assertFalse("rootfs must not be marked valid", fileManager.hasValidRootfs(workspaceId))
    }

    @Test
    fun persistenceFailureNeverShowsSuccess() {
        setUpCoordinator()
        repository.failOnReady = true
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        val terminal = runBlocking { awaitTerminal() }

        assertTrue("a persistence failure must fail, was: $terminal", terminal is InstallFlowState.Failed)
        assertTrue(
            "Completed must never be emitted when Ready cannot be persisted",
            terminal !is InstallFlowState.Completed
        )
        assertTrue(
            "workspace must not be left claiming Ready after a persistence failure",
            repository.getByIdSync(workspaceId)?.status !is WorkspaceStatus.Ready
        )
    }

    @Test
    fun analysisProgressCarriesRealPercentage() {
        setUpCoordinator()
        val analyzeGate = CompletableDeferred<Unit>()
        installer.analyzeGate = analyzeGate

        val observed = java.util.Collections.synchronizedList(mutableListOf<InstallFlowState>())
        val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val collector = collectorScope.launch {
            coordinator.state.collect { observed += it }
        }
        Thread.sleep(50) // let the collector attach to the StateFlow

        coordinator.prepare(selection)
        // The fake installer's analyze() emits its byte-count progress, then
        // blocks on the gate so the collector deterministically observes the
        // ANALYZING state before the flow races ahead to Ready.
        runBlocking {
            withTimeout(5_000) {
                while (observed.none { it is InstallFlowState.Preparing && it.step == InstallStep.ANALYZING }) {
                    delay(10)
                }
            }
        }
        val analyzing = observed.filterIsInstance<InstallFlowState.Preparing>()
            .filter { it.step == InstallStep.ANALYZING }
        assertTrue("analysis progress must carry a percentage", analyzing.any { it.progress != null })
        // §1.88: analysis is a fixed-height card, not a log — progress events
        // no longer stream entry names, so the state must not carry one.
        assertTrue(
            "analysis progress must not carry entry names (clean fixed-height card)",
            analyzing.all { it.currentEntryName == null }
        )
        analyzeGate.complete(Unit)
        runBlocking {
            withTimeout(5_000) { awaitState { it is InstallFlowState.Ready } }
        }
        coordinator.confirm("TestDistro")
        val terminal = runBlocking { awaitTerminal() }
        assertTrue("install must complete after the gate, was: $terminal", terminal is InstallFlowState.Completed)
        collector.cancel()
    }

    @Test
    fun extractionProgressStaysInsideRealBand() {
        setUpCoordinator()
        val workspaceId = prepareToReady()

        val observed = java.util.Collections.synchronizedList(mutableListOf<InstallFlowState>())
        val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val collector = collectorScope.launch {
            coordinator.state.collect { observed += it }
        }
        Thread.sleep(50)

        coordinator.confirm("TestDistro")
        runBlocking { awaitTerminal() }

        // Issue #5 (2026-09-03): extraction rides the shared weighted tracker.
        // This flow is a FILE selection (no download stage measures, so its
        // weight stays uncommitted) and the fake emits analyze fractions to
        // 1.0 (0.15 committed). Checkpoint extraction fractions 0.2/0.8/1.0
        // → 0.15 + 0.40*f = 0.23/0.47/0.55; fixup completes its own weight
        // (0.57), finalize (0.59). The bar is monotonic across the whole
        // flow — no stage restarts and no boundary drops.
        val extracting = observed.filterIsInstance<InstallFlowState.Installing>()
            .filter { it.step == InstallStep.EXTRACTING && it.progress != null }
        assertTrue("extraction progress must be observed", extracting.isNotEmpty())
        assertTrue(
            "extraction fraction must stay inside the 15%→55% band",
            extracting.all { it.progress!! in 0.15f..0.55f }
        )
        // Monotonicity: the emitted Installing progress never decreases.
        val installingProgress = observed.filterIsInstance<InstallFlowState.Installing>()
            .mapNotNull { it.progress }
        assertTrue("install progress must be monotonic", installingProgress == installingProgress.sorted())
        val fixing = observed.filterIsInstance<InstallFlowState.Installing>()
            .filter { it.step == InstallStep.FIXING && it.progress != null }
        assertTrue("fixup must be observed in the 55%→60% band", fixing.any { it.progress!! in 0.55f..0.60f })
        assertTrue("completed install, was: ${coordinator.state.value}", coordinator.state.value is InstallFlowState.Completed)
        collector.cancel()
        assertNotNull(repository.getByIdSync(workspaceId))
    }

    @Test
    fun confirmPersistsEditableContainerName() {
        setUpCoordinator()
        val workspaceId = prepareToReady()

        coordinator.confirm("MyDebian")
        runBlocking { awaitTerminal() }

        val persisted = repository.getByIdSync(workspaceId)
        assertNotNull("workspace row must exist", persisted)
        assertEquals("edited container name must be persisted as displayName", "MyDebian", persisted!!.displayName)
        // §1.88: the stored distro identity comes from the probe result
        // (Debian / 12 / arm64-v8a in the fake analysis), not a hardcoded guess.
        assertEquals("probe distro name must be persisted", "Debian", persisted.distro.name)
        assertEquals("probe version must be persisted", "12", persisted.distro.version)
        assertEquals("probe arch ABI must be persisted", "arm64-v8a", persisted.distro.arch)
    }

    // ── §1.119: a legacy `.tiny.yaml` contributes presentation only ──────

    /** Drives preparation and returns the summary the confirmation screen renders. */
    private fun summaryFor(descriptor: RootFsDescriptor): InstallSummary {
        setUpCoordinator()
        installer.descriptor = descriptor
        return runBlocking {
            coordinator.prepare(selection)
            val ready = awaitState { it is InstallFlowState.Ready || it is InstallFlowState.Failed }
            assertTrue("preparation must reach Ready, was: $ready", ready is InstallFlowState.Ready)
            (ready as InstallFlowState.Ready).summary
        }
    }

    @Test
    fun aLegacyImagesNameAndDescriptionReachTheConfirmationScreen() {
        // The regression this guards: reading `descriptor.metadata?.presentation`
        // instead of `descriptor.presentation` compiles, passes every native
        // test, and silently makes §1.119's only user-visible benefit
        // unreachable — a legacy image would fall back to the probe's
        // "Debian 12" label and show no description at all.
        val summary = summaryFor(
            RootFsDescriptor.legacy(
                presentation = DeclaredPresentation(
                    displayName = "Debian Trixie",
                    description = "Republished for Linexa."
                ),
                archiveFormat = RootFsArchiveFormat.TAR_ZST
            )
        )

        assertEquals("Debian Trixie", summary.distroLabel)
        assertEquals("Republished for Linexa.", summary.description)
        assertEquals("debian-trixie", summary.containerCode)
    }

    @Test
    fun aLegacyImageStillPersistsProbedFactsAndClaimsNoDeclaration() {
        val summary = summaryFor(
            RootFsDescriptor.legacy(
                presentation = DeclaredPresentation(displayName = "Debian Trixie"),
                archiveFormat = RootFsArchiveFormat.TAR_ZST
            )
        )

        // `declaredMetadata == null` is what keeps the confirmation screen from
        // claiming the distro/arch rows were read from the archive: for a legacy
        // image they were probed.
        assertEquals(null, summary.declaredMetadata)
        assertEquals("Debian", summary.distroName)
        assertEquals("12", summary.version)
        assertEquals("arm64-v8a", summary.archAbi)
        // …but the screen can still say *which* format was read.
        assertEquals(RootFsMetadataFile.LEGACY_TINY, summary.metadataSource)
    }

    @Test
    fun anUndeclaredArchiveNamesItselfFromTheProbeAsBefore() {
        val summary = summaryFor(RootFsDescriptor.UNDECLARED)
        assertEquals("Debian 12", summary.distroLabel)
        assertEquals(null, summary.description)
        assertEquals(null, summary.metadataSource)
    }

    @Test
    fun aLegacyImageWithNoNameFallsBackToTheProbeLabel() {
        val summary = summaryFor(
            RootFsDescriptor.legacy(
                presentation = DeclaredPresentation(description = "Only a description."),
                archiveFormat = null
            )
        )
        assertEquals("Debian 12", summary.distroLabel)
        assertEquals("Only a description.", summary.description)
    }

    @Test
    fun aLegacyNameIsOnlyADefaultAndTheUserCanOverrideIt() {
        // A declaration influences the pre-filled name; the person installing
        // still decides.
        setUpCoordinator()
        installer.descriptor = RootFsDescriptor.legacy(
            presentation = DeclaredPresentation(displayName = "Debian Trixie"),
            archiveFormat = null
        )
        val workspaceId = prepareToReady()

        coordinator.confirm("MyOwnName")
        runBlocking { awaitTerminal() }

        assertEquals("MyOwnName", repository.getByIdSync(workspaceId)!!.displayName)
    }

    // ── § distro identity persistence (installed entries keep the profile) ──

    @Test
    fun detectedDistroIdIsPersistedForFileImports() {
        // The regression this guards: the confirmation screen resolved the
        // archive's distro (probe → "debian") and showed its branded logo —
        // but the installed row carried no distro id, so the installed list
        // dropped the profile right after install and only the next process
        // start's backfill repaired it.
        setUpCoordinator()
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        runBlocking { awaitTerminal() }

        assertEquals(
            "the detected distro identity must be persisted on the installed row",
            "debian",
            repository.getByIdSync(workspaceId)!!.attributes[DownloadService.ATTR_DISTRO_ID]
        )
    }

    @Test
    fun theGoverningProfileIdIsThePersistedReference() {
        // §1.146: the resolved profile's own id is the stable identity — even
        // when it is not a download-catalog id ("arch" vs catalog
        // "archlinux"); the UI's icon registry resolves both to the same logo.
        setUpCoordinator()
        installer.profileId = "arch"
        installer.probedIdentity = installer.probedIdentity.copy(distroId = RootFsProbe.DistroId.ARCH)
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        runBlocking { awaitTerminal() }

        assertEquals(
            "arch",
            repository.getByIdSync(workspaceId)!!.attributes[DownloadService.ATTR_DISTRO_ID]
        )
    }

    @Test
    fun anUnidentifiedArchivePersistsNoDistroId() {
        // No fake branding: an archive neither the profile set nor the probe
        // could identify keeps the letter avatar, exactly as before.
        setUpCoordinator()
        installer.profileId = RootFsProfileResolver.GENERIC_PROFILE.id
        installer.probedIdentity = installer.probedIdentity.copy(distroId = RootFsProbe.DistroId.OTHER)
        val workspaceId = prepareToReady()

        coordinator.confirm("TestDistro")
        runBlocking { awaitTerminal() }

        assertFalse(
            "no distro id may be persisted for an unidentified archive",
            repository.getByIdSync(workspaceId)!!.attributes.containsKey(DownloadService.ATTR_DISTRO_ID)
        )
    }

    @Test
    fun cancellingDuringInstallingStopsTheJobAndDeletesTheUncommittedWorkspace() {
        setUpCoordinator()
        val workspaceId = prepareToReady()
        val gate = CompletableDeferred<Unit>()
        installer.installGate = gate

        coordinator.confirm("CancelDistro")
        runBlocking {
            awaitState { it is InstallFlowState.Installing }
            // The installer is now blocked inside install(); cancel must
            // reach it and the coordinator must leave the running state at
            // once (a spinner that keeps running after Cancel is the bug).
            coordinator.cancel()
            assertEquals(
                "the flow must return to Idle immediately after Cancel",
                InstallFlowState.Idle,
                coordinator.state.value
            )
            // RF-011: the workspace tree and row are removed only after the
            // cancelled job has actually stopped.
            withTimeout(10_000) {
                while (fileManager.getWorkspaceBasePath(workspaceId).exists() ||
                    repository.getByIdSync(workspaceId) != null
                ) {
                    delay(10)
                }
            }
            assertFalse(
                "the uncommitted workspace directory must be gone",
                fileManager.getWorkspaceBasePath(workspaceId).exists()
            )
            assertTrue(
                "the uncommitted workspace row must be gone",
                repository.getByIdSync(workspaceId) == null
            )
        }
    }
}

/** In-memory [WorkspaceRepository] for coordinator tests. */
private class FakeWorkspaceRepository : WorkspaceRepository {
    private val store = mutableMapOf<String, WorkspaceMetadata>()
    private val all = MutableStateFlow<List<WorkspaceMetadata>>(emptyList())
    var failOnReady: Boolean = false

    override fun observeAll(): Flow<List<WorkspaceMetadata>> = all

    override fun observeById(id: String): Flow<WorkspaceMetadata?> =
        MutableStateFlow(store[id])

    override suspend fun getById(id: String): WorkspaceMetadata? = store[id]

    fun getByIdSync(id: String): WorkspaceMetadata? = store[id]

    override suspend fun insert(metadata: WorkspaceMetadata) {
        store[metadata.id] = metadata
        all.value = store.values.toList()
    }

    override suspend fun update(metadata: WorkspaceMetadata) {
        store[metadata.id] = metadata
        all.value = store.values.toList()
    }

    override suspend fun deleteById(id: String) {
        store.remove(id)
        all.value = store.values.toList()
    }

    override suspend fun updateStatus(id: String, status: WorkspaceStatus) {
        if (failOnReady && status is WorkspaceStatus.Ready) {
            throw IllegalStateException("simulated persistence failure writing Ready")
        }
        val current = store[id] ?: return
        store[id] = current.copy(status = status)
        all.value = store.values.toList()
    }

    override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) {
        val current = store[id] ?: return
        store[id] = current.copy(featureToggles = toggles)
        all.value = store.values.toList()
    }

    override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
        val current = store[id] ?: return
        // Merges, like the real implementation — this map also holds the install
        // source and the catalog distro id that the coordinator itself writes.
        store[id] = current.copy(attributes = current.attributes + attributes)
        all.value = store.values.toList()
    }
}

/**
 * Fake installer: reports real phases/entry names, and on success materializes
 * a valid rootfs (bin/etc/lib/usr/var + a file) so the coordinator's §1.86
 * verification step genuinely checks disk.
 */
private class FakeRootFsInstaller(
    private val fileManager: WorkspaceFileManager,
    private val materializeRootfs: Boolean = true
) : RootFsInstaller {

    var failInstall: String? = null
    var analyzeGate: CompletableDeferred<Unit>? = null

    /**
     * RF-010/RF-011: suspends [install] so a test can cancel while the job is
     * genuinely in flight. `await()` is a cancellation point, which is the
     * same cooperation point the real extractor now has.
     */
    var installGate: CompletableDeferred<Unit>? = null

    /**
     * §1.118/§1.119: what the archive declared about itself. Defaults to
     * `UNDECLARED`, the shape of every third-party rootfs tarball, so the
     * pre-existing tests keep exercising the probe path.
     */
    var descriptor: RootFsDescriptor = RootFsDescriptor.UNDECLARED

    /** §1.146: the governing profile id the real installer reports. */
    var profileId: String = RootFsProfileResolver.GENERIC_PROFILE.id

    /** The probed identity, overridable when a test needs another distro. */
    var probedIdentity: RootFsProbe.Identity = RootFsProbe.Identity(
        distroId = RootFsProbe.DistroId.DEBIAN,
        distroName = "Debian",
        version = "12",
        arch = RootFsProbe.RootFsArch.ARM64_V8A,
        detected = true
    )

    override suspend fun analyze(
        workspaceId: String,
        sourceUri: Uri,
        expectedSha256: String?,
        bundledMetadata: ByteArray?,
        onProgress: suspend (InstallProgress) -> Unit
    ): AnalyzeResult {
        // §1.88: analysis progress is a clean percentage — no entry names.
        onProgress(InstallProgress(bytesRead = 100, totalBytes = 1000, currentEntryName = null, phase = InstallPhase.ANALYZING))
        onProgress(InstallProgress(bytesRead = 400, totalBytes = 1000, currentEntryName = null, phase = InstallPhase.ANALYZING))
        onProgress(InstallProgress(bytesRead = 1000, totalBytes = 1000, currentEntryName = null, phase = InstallPhase.ANALYZING))
        analyzeGate?.await()
        return AnalyzeResult.Success(
            sha256 = "deadbeef",
            requiredStorageBytes = 4_000_000L,
            archiveBytes = 1_000_000L,
            totalEntries = 1_000L,
            identity = probedIdentity,
            requiredFreeBytes = 5_000_000L,
            capturedConfig = null,
            descriptor = descriptor,
            profileId = profileId
        )
    }

    override suspend fun install(
        workspaceId: String,
        analysis: AnalyzeResult.Success,
        onProgress: suspend (InstallProgress) -> Unit
    ): InstallResult {
        installGate?.await()
        // §1.88: checkpoint-driven extraction fractions (0→1), then the
        // byte-less phase transitions for the fixed final bands. Small delays
        // so a StateFlow collector observes each distinct value.
        onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING)); delay(20)
        onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING, fraction = 0.2f)); delay(20)
        onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING, fraction = 0.8f)); delay(20)
        onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING, fraction = 1f)); delay(20)
        onProgress(InstallProgress(0, null, null, InstallPhase.FIXING)); delay(20)
        onProgress(InstallProgress(0, null, null, InstallPhase.FINALIZING)); delay(20)
        if (failInstall != null) return InstallResult.Failure(failInstall!!)
        if (materializeRootfs) {
            val rootfs = fileManager.getRootfsPath(workspaceId)
            for (dir in listOf("bin", "etc", "lib", "usr", "var")) {
                File(rootfs, dir).mkdirs()
            }
            File(rootfs, "bin/sh").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
        }
        return InstallResult.Success(analysis.sha256)
    }
}
