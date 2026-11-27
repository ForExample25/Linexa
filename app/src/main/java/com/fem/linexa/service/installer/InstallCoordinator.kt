package com.fem.linexa.service.installer

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.core.filesystem.deleteRecursivelyAllowingReadOnly
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.resolveFeatureToggles
import com.fem.linexa.domain.distro.ChecksumFormat
import com.fem.linexa.domain.distro.DistroManifest
import com.fem.linexa.domain.distro.DistroSource
import com.fem.linexa.domain.distro.ResolvedDownload
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.WorkspaceDeletionResult
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.ChecksumResolver
import com.fem.linexa.service.distro.DistroDownloader
import com.fem.linexa.service.distro.DistroSourceResolverRegistry
import com.fem.linexa.service.distro.DownloadResult
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.importservice.ImportService
import com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.RootFsMetadata
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import com.fem.linexa.service.rootfs.profile.RootFsProfileResolver
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import com.fem.linexa.service.workspaceconfig.launchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.84: the user-facing install flow, orchestrated as ONE singleton state
 * machine so the screen is a thin observer and the installation logic has a
 * single owner (no two installs thrashing the same directories).
 *
 * Flow: [prepare] (download-if-needed + analyze/validate the archive, with
 * real progress events) → [InstallFlowState.Ready] (a summary the UI shows
 * on a confirmation card) → [confirm] (create the workspace record + run the
 * destructive extraction/fixup/activation, with real phase events) →
 * [InstallFlowState.Completed]/[Failed]. [cancel] aborts a not-yet-confirmed
 * flow and removes its temp data.
 *
 * Everything here is installation logic: UI logic lives in
 * [com.fem.linexa.ui.screens.install.InstallFlowViewModel]/`InstallFlowScreen`,
 * which only collect [state] and forward [prepare]/[confirm]/[cancel].
 *
 * The workspace record is deliberately NOT created until [confirm] — a
 * cancelled preparation leaves no half-`Importing` row behind.
 */
@Singleton
class InstallCoordinator @Inject constructor(
    private val application: Application,
    private val repository: WorkspaceRepository,
    private val fileManager: WorkspaceFileManager,
    private val installer: RootFsInstaller,
    private val downloader: DistroDownloader,
    private val checksumResolver: ChecksumResolver,
    private val resolverRegistry: DistroSourceResolverRegistry,
    private val configParser: WorkspaceConfigParser,
    /**
     * 2026-09-01: the Linexa-bundled `linexa.yaml` install profiles (Debian/
     * Ubuntu). Nullable with a default so plain constructor call sites (JVM
     * tests) that do not exercise the download flow stay valid; Hilt injects
     * the real provider in production.
     */
    private val bundledRootfsMetadata: BundledRootfsMetadataProvider? = null,
    /**
     * 2026-09-03 (issue #2 post-install validation): the container runtime,
     * used ONCE per install to run the declared `linexa.health_check.command`
     * inside the freshly activated rootfs — the "can this rootfs actually
     * start" gate. Nullable with the same default-for-tests rationale as
     * [bundledRootfsMetadata]; null (or an undeclared check) simply keeps the
     * pre-existing static verification. No cycle: the runtime never depends
     * on this coordinator.
     */
    private val containerRuntime: ContainerRuntime? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<InstallFlowState>(InstallFlowState.Idle)
    val state: StateFlow<InstallFlowState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeWorkspaceId: String? = null
    private var pending: Pending? = null

    /**
     * RF-011: every flow gets a generation. Cancelling (or starting a new
     * flow) bumps it, so a cancelled job that is still winding down — or a
     * progress callback already queued on the dispatcher — cannot overwrite
     * the newer flow's state or the post-cancel [InstallFlowState.Idle].
     */
    private var flowGeneration: Long = 0L

    /**
     * Publishes [state] only while [generation] is still the active flow.
     * All job-internal state writes go through this; terminal teardown
     * ([abortActive]) does not, because it is the one that invalidates the
     * generation deliberately.
     */
    private fun publishState(generation: Long, state: InstallFlowState) {
        if (generation == flowGeneration) _state.value = state
    }

    /**
     * Issue #5 (2026-09-03): the shared weighted monotonic progress tracker
     * for the active flow. Recreated by [prepare]; read by the download,
     * analyze, extract, fix, finalize and verify event mappers so every
     * stage reports on ONE scale and the bar never moves backwards.
     */
    private var progressTracker: StagedProgressTracker? = null

    /** Starts (or restarts, for a different selection) the flow. */
    fun prepare(selection: InstallSelection) {
        val current = _state.value
        val alreadyRunningThis =
            (current is InstallFlowState.Preparing || current is InstallFlowState.Ready || current is InstallFlowState.Installing) &&
                pending?.selection?.sameAs(selection) == true
        if (alreadyRunningThis) return
        // Pre-release audit fix (2026-09-02, finding U6/M-3): a different
        // selection while an install is RUNNING no longer aborts it. The old
        // abortActive() path cancelled the in-flight job AND deleted the
        // workspace row + staging the user had already confirmed — silently
        // destroying a committed install, contradicting [cancel]'s own
        // documented policy ("once Installing, an in-flight extraction is
        // left to finish and its workspace survives"). The flow now refuses
        // until the running install completes; the caller surfaces the
        // existing state card.
        if (current is InstallFlowState.Installing) {
            Logger.w(TAG, "prepare() ignored: an install is already running for $activeWorkspaceId")
            return
        }
        if (current is InstallFlowState.Preparing || current is InstallFlowState.Ready) {
            abortActive()
        }

        pending = null
        val workspaceId = UUID.randomUUID().toString()
        activeWorkspaceId = workspaceId
        // Issue #5 (2026-09-03): one weighted monotonic tracker for the WHOLE
        // flow (download → analyze → extract → fix → finalize → verify), so
        // stage transitions no longer restart the bar at 0. Fresh per flow.
        progressTracker = newProgressTracker()
        val generation = ++flowGeneration
        _state.value = InstallFlowState.Preparing(initialStep(selection), 0f)

        activeJob = scope.launch {
            fun publish(state: InstallFlowState) = publishState(generation, state)
            try {
                val source = when (selection) {
                    is InstallSelection.Distro -> {
                        publish(InstallFlowState.Preparing(InstallStep.DOWNLOADING, 0f))
                        prepareDistroSource(workspaceId, selection.manifest)
                    }
                    is InstallSelection.File -> {
                        tryTakePersistableUriPermission(selection.uri)
                        PreparedSource(selection.uri, null)
                    }
                }

                publish(InstallFlowState.Preparing(InstallStep.ANALYZING, progressTracker?.update(STAGE_ANALYZING, null) ?: 0f))
                val analysis = installer.analyze(
                    workspaceId = workspaceId,
                    sourceUri = source.uri,
                    expectedSha256 = source.expectedSha256,
                    onProgress = { progress ->
                        // Issue #5: the analyzer's fraction rides the shared
                        // weighted scale; a null (unknown content length) leaves
                        // the bar where it is instead of inventing a value.
                        publish(
                            InstallFlowState.Preparing(
                                InstallStep.ANALYZING,
                                progressTracker?.update(STAGE_ANALYZING, analyzeFraction(progress)),
                                progress.currentEntryName
                            )
                        )
                    },
                    // 2026-09-01: hand the installer the distro's bundled
                    // linexa.yaml (Debian/Ubuntu) — applied only when the
                    // archive carries no NATIVE Linexa metadata of its own
                    // (a legacy .tiny.yaml stays a lower-priority compat
                    // source). 2026-09-03: version-aware selection — a
                    // `<distro>-<version>.linexa.yaml` asset is preferred
                    // over the generic `<distro>.linexa.yaml` when the
                    // catalog's version matches (issue #10/#11 precedence).
                    bundledMetadata = when (selection) {
                        is InstallSelection.Distro ->
                            bundledRootfsMetadata?.forDistro(
                                selection.manifest.id,
                                selection.manifest.version
                            )
                        is InstallSelection.File -> null
                    }
                )

                when (analysis) {
                    is AnalyzeResult.Failure -> {
                        cleanupWorkspace(workspaceId)
                        publish(InstallFlowState.Failed(analysis.reason))
                    }
                    is AnalyzeResult.Success -> {
                        val summary = buildSummary(workspaceId, selection, analysis)
                        pending = Pending(selection, workspaceId, summary, analysis, source.expectedSha256 != null)
                        publish(InstallFlowState.Ready(summary))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Install preparation failed", e)
                cleanupWorkspace(workspaceId)
                publish(InstallFlowState.Failed(e.message ?: "Preparation failed"))
            }
        }
    }

    /**
     * User confirmed the [InstallFlowState.Ready] summary, optionally with an
     * edited [containerName] (the display name of the new workspace).
     * Commits the install.
     */
    fun confirm(containerName: String) {
        val current = _state.value
        if (current !is InstallFlowState.Ready) return
        val base = pending ?: return
        val name = containerName.trim().ifBlank { current.summary.containerCode }
        val p = base.copy(containerName = name)
        pending = p

        // Transition out of Ready SYNCHRONOUSLY: the confirmation screen pops
        // itself immediately after calling this, and its `onCleared` must not
        // cancel a flow the user just committed (cancel() only aborts
        // non-terminal flows now that Installing is cancellable too).
        _state.value = InstallFlowState.Installing(InstallStep.EXTRACTING, null)
        val generation = ++flowGeneration

        activeJob = scope.launch {
            fun publish(state: InstallFlowState) = publishState(generation, state)
            try {
                // §1.88: re-verify free space at the moment of commit — time
                // has passed since the analysis estimate and other installs may
                // have consumed space since. Fails BEFORE the workspace row is
                // created (no half-Importing row for a storage-rejected install).
                val availableBytes = application.filesDir.usableSpace
                val requiredFreeBytes = p.summary.requiredFreeBytes
                if (requiredFreeBytes > 0 && availableBytes < requiredFreeBytes) {
                    Logger.w(TAG, "Disk space re-check failed at confirm: need ${FileSizeFormatter.format(requiredFreeBytes)}, have ${FileSizeFormatter.format(availableBytes)}")
                    cleanupWorkspace(p.workspaceId)
                    publish(
                        InstallFlowState.Failed(
                            "Insufficient disk space to install: need ~${FileSizeFormatter.format(requiredFreeBytes)}, have ${FileSizeFormatter.format(availableBytes)}"
                        )
                    )
                    return@launch
                }

                createWorkspaceRow(p)

                // 2026-09-02 ("Now processing" fix): the extractor emits two
                // interleaved event kinds on the EXTRACTING phase — checkpoint
                // fraction events (entry = null) and tar `-v` entry events
                // (fraction = null). Neither may clobber the other's last value
                // in the UI state, so this mapper keeps the entry channel's
                // latest value and feeds BOTH through the shared weighted
                // tracker (issue #5): the fraction channel advances the bar;
                // entry-only events leave the bar exactly where it was instead
                // of dropping it to a stage boundary.
                var lastEntry: String? = null
                when (val result = installer.install(p.workspaceId, p.analysis) { progress ->
                    when (progress.phase) {
                        InstallPhase.EXTRACTING -> {
                            progressTracker?.update(STAGE_EXTRACTING, progress.fraction)
                            progress.currentEntryName?.let { lastEntry = it }
                        }
                        InstallPhase.FIXING -> progressTracker?.complete(STAGE_FIXING)
                        InstallPhase.FINALIZING -> progressTracker?.complete(STAGE_FINALIZING)
                        InstallPhase.ANALYZING -> Unit // analyze owns its own stage; install never re-runs it
                    }
                    publish(
                        InstallFlowState.Installing(
                            stepOf(progress.phase),
                            progressTracker?.overallNullable,
                            progress.currentEntryName ?: lastEntry
                        )
                    )
                }) {
                    is InstallResult.Success -> {
                        // §1.86: never show a success card before the rootfs has been
                        // verified on disk AND its metadata persisted as Ready. A crash
                        // or a dead DB row here must surface as failure + a Broken
                        // record, never as a completed install that the workspace list
                        // cannot actually list/run.
                        publish(
                            InstallFlowState.Installing(
                                InstallStep.VERIFYING,
                                progressTracker?.update(STAGE_VERIFYING, 0f) ?: 0.98f,
                                null
                            )
                        )
                        val verifyFailure = verifyRootfs(p)
                        if (verifyFailure != null) {
                            Logger.w(TAG, "Rootfs verification failed for ${p.workspaceId}: $verifyFailure")
                            repository.updateStatus(p.workspaceId, WorkspaceStatus.Broken(verifyFailure))
                            publish(InstallFlowState.Failed(verifyFailure, workspaceId = p.workspaceId))
                        } else if (!updateWorkspaceAfterSuccess(p, result.sha256)) {
                            Logger.e(TAG, "Metadata persistence failed for ${p.workspaceId} — not showing success")
                            repository.updateStatus(p.workspaceId, WorkspaceStatus.Broken("Workspace record could not be persisted"))
                            publish(InstallFlowState.Failed("Workspace record could not be persisted", workspaceId = p.workspaceId))
                        } else {
                            fileManager.deleteDownloadCache(p.workspaceId)
                            publish(InstallFlowState.Completed(p.workspaceId))
                            Logger.i(TAG, "Install completed for ${p.workspaceId} (sha256=${result.sha256}, verified=${p.sha256Verified})")
                        }
                    }
                    is InstallResult.Failure -> {
                        Logger.w(TAG, "Install failed for ${p.workspaceId}: ${result.reason}")
                        repository.updateStatus(p.workspaceId, WorkspaceStatus.Broken(result.reason))
                        publish(InstallFlowState.Failed(result.reason, workspaceId = p.workspaceId))
                    }
                }
            } catch (e: CancellationException) {
                // RF-010: a user cancel during Installing. abortActive() owns
                // the teardown (it joins this job, then deletes the workspace
                // row + directory) — the job itself must not write a Broken
                // status or a Failed state on the way out.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Install failed for ${p.workspaceId}", e)
                repository.updateStatus(p.workspaceId, WorkspaceStatus.Broken(e.message ?: "Installation failed"))
                publish(InstallFlowState.Failed(e.message ?: "Installation failed", workspaceId = p.workspaceId))
            }
        }
    }

    /**
     * Aborts a flow the user has not committed, or one they explicitly cancel
     * while it is installing (RF-010/UX-001). The cancelled job keeps running
     * until its next suspension/cancellation check, so the workspace is
     * deleted only after the job has finished (RF-011): deleting the
     * directory underneath a still-running writer can orphan paths and
     * resurrect files inside a tree being removed.
     */
    fun cancel() {
        when (_state.value) {
            is InstallFlowState.Preparing,
            is InstallFlowState.Ready,
            is InstallFlowState.Installing -> abortActive()
            else -> Unit
        }
    }

    // ── preparation helpers ─────────────────────────────────────────────

    private suspend fun prepareDistroSource(workspaceId: String, manifest: DistroManifest): PreparedSource {
        val resolved = resolveSource(manifest)
            ?: throw IllegalStateException("Could not resolve a download URL for ${manifest.displayName}")
        val archiveFileName = resolved.downloadUrl.substringAfterLast('/')
        val destination = fileManager.getDownloadCachePath(workspaceId, archiveFileName)

        val result = downloader.download(resolved.downloadUrl, destination) { downloaded, total ->
            // Issue #5: the download's fraction rides the shared weighted
            // scale (base 0, weight 0.40) instead of being its own 0..1 bar.
            val fraction = progressTracker?.update(
                STAGE_DOWNLOADING,
                total?.takeIf { it > 0 }?.let { downloaded.toFloat() / it.toFloat() }
            )
            _state.value = InstallFlowState.Preparing(InstallStep.DOWNLOADING, fraction)
        }
        return when (result) {
            is DownloadResult.Failure -> throw IllegalStateException(result.reason)
            is DownloadResult.Success -> {
                val expected = checksumResolver.resolve(resolved.checksumUrl, resolved.checksumFormat, archiveFileName)
                if (expected == null) {
                    // Pre-release audit fix (2026-09-02, finding F1): a
                    // source that DECLARES a checksum (url + format present)
                    // must not silently proceed unverified when the sidecar
                    // could not be fetched/parsed — a transient network error
                    // would otherwise convert a verified pipeline into an
                    // untrusted one whose bytes are later executed as this
                    // app's uid. Fail closed; only a catalog entry that
                    // honestly declares no checksum (ChecksumFormat.NONE or
                    // null url, e.g. Arch ARM) may proceed unverified.
                    val declaresChecksum = resolved.checksumUrl != null &&
                        resolved.checksumFormat != ChecksumFormat.NONE
                    if (declaresChecksum) {
                        throw IllegalStateException(
                            "Could not fetch or parse the SHA-256 checksum for ${manifest.id} " +
                                "(${resolved.checksumUrl}) — refusing to install an unverified download"
                        )
                    }
                    Logger.w(TAG, "No checksum published for ${manifest.id} — proceeding unverified (catalog declares none)")
                }
                PreparedSource(Uri.fromFile(result.file), expected)
            }
        }
    }

    private suspend fun resolveSource(manifest: DistroManifest): ResolvedDownload? {
        return when (val source = manifest.source) {
            is DistroSource.Direct -> ResolvedDownload(source.downloadUrl, source.checksumUrl, source.checksumFormat)
            is DistroSource.Resolved -> {
                val resolver = resolverRegistry.resolverFor(source.resolverId)
                if (resolver == null) {
                    Logger.w(TAG, "No resolver registered for id: ${source.resolverId}")
                    return null
                }
                try {
                    resolver.resolve(source)
                } catch (e: Exception) {
                    Logger.w(TAG, "Resolver '${source.resolverId}' failed for ${manifest.id}: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * §1.88: resolves the display identity from the probe result (falling
     * back to the user's selection when detection failed) and collects every
     * number the confirmation card needs — detected distro/arch, required
     * storage, required free space (extracted + archive + slack), archive
     * size, entry count, and the current free space.
     *
     * §1.118: when the archive declared its own metadata, that declaration is
     * already what `analysis.identity` carries (the installer prefers it over
     * the probe), and the author's suggested presentation
     * ([DeclaredPresentation]) becomes the pre-filled container name — the
     * user can still edit it, so a declaration influences the default and
     * never overrides the person installing.
     *
     * §1.119: presentation is read from [RootFsDescriptor.presentation], not
     * from `descriptor.metadata`, so a legacy `.tiny.yaml` image gets its name
     * and description too. That is the *only* thing a legacy file contributes:
     * [declaredMetadata] below stays null for it, so the identity row, the
     * "read from the archive" note and every fact-consuming stage still come
     * from the probe. Reading `metadata?.presentation` here would have left
     * §1.119's one user-visible benefit unreachable.
     */
    private suspend fun buildSummary(
        workspaceId: String,
        selection: InstallSelection,
        analysis: AnalyzeResult.Success
    ): InstallSummary {
        val identity = analysis.identity
        val (fallbackName, fallbackVersion) = when (selection) {
            is InstallSelection.Distro -> selection.manifest.displayName to selection.manifest.version
            is InstallSelection.File -> selection.distroName to selection.distroVersion
        }
        val detected = identity.detected
        val distroName = if (detected) identity.distroName else fallbackName
        val version = if (detected && identity.version != "unknown") identity.version else fallbackVersion
        val archAbi = if (detected) identity.arch.abi else "arm64-v8a"
        val versionForLabel = version.takeIf { !it.isBlank() && !it.equals("unknown", ignoreCase = true) }.orEmpty()
        val declared = analysis.descriptor.metadata
        // §1.119: whichever format supplied it (native `linexa.metadata` or a
        // legacy `.tiny.yaml`) — see RootFsDescriptor.presentation.
        val presentation = analysis.descriptor.presentation
        val label = presentation?.displayName?.takeIf { it.isNotBlank() }
            ?: listOf(distroName, versionForLabel).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "workspace" }
        val containerCode = slugify(label)
        return InstallSummary(
            workspaceId = workspaceId,
            containerCode = containerCode,
            distroLabel = label,
            distroName = distroName,
            version = version,
            archAbi = archAbi,
            identity = identity,
            detected = detected,
            requiredStorageBytes = analysis.requiredStorageBytes,
            requiredFreeBytes = analysis.requiredFreeBytes,
            archiveBytes = analysis.archiveBytes,
            totalEntries = analysis.totalEntries,
            availableBytes = application.filesDir.usableSpace,
            overwritesExisting = containerNameInUse(containerCode),
            declaredMetadata = declared,
            declarationWarnings = analysis.descriptor.warnings,
            description = presentation?.description,
            metadataSource = analysis.descriptor.source
        )
    }

    /** True if any existing workspace's display name slugifies to [containerCode]. */
    private suspend fun containerNameInUse(containerCode: String): Boolean {
        val code = containerCode.lowercase(Locale.ROOT)
        return repository.observeAll().first().any { ws -> slugify(ws.displayName).lowercase(Locale.ROOT) == code }
    }

    // ── confirm helpers ─────────────────────────────────────────────────

    private suspend fun createWorkspaceRow(p: Pending) {
        val summary = p.summary
        val attributes = when (val selection = p.selection) {
            is InstallSelection.Distro -> mapOf(DownloadService.ATTR_DISTRO_ID to selection.manifest.id)
            is InstallSelection.File -> mapOf(ImportService.ATTR_SOURCE_URI to selection.uri.toString()) +
                detectedDistroIdAttributes(p.analysis)
        }
        val avatarLetter = when (val selection = p.selection) {
            is InstallSelection.Distro -> selection.manifest.avatarLetter
            is InstallSelection.File -> summary.distroName.firstOrNull()?.toString()?.uppercase() ?: "W"
        }
        val avatarColorHex = when (val selection = p.selection) {
            is InstallSelection.Distro -> selection.manifest.avatarColorHex
            is InstallSelection.File -> "#E8734A"
        }
        val metadata = WorkspaceMetadata(
            id = p.workspaceId,
            status = WorkspaceStatus.Importing(0f),
            // §1.88: persist the PROBE-RESOLVED identity, not a hardcoded
            // "arm64-v8a" guess — the confirmation card showed it, so the
            // stored record must match.
            distro = DistroInfo(summary.distroName, summary.version, summary.archAbi),
            displayName = p.containerName,
            avatarLetter = avatarLetter,
            avatarColorHex = avatarColorHex,
            rootfsPath = fileManager.getRootfsPath(p.workspaceId).absolutePath,
            createdAt = Instant.now().toEpochMilli(),
            runtimeConfig = RuntimeConfig(),
            terminalSettings = TerminalSettings(),
            featureToggles = emptyMap(),
            attributes = attributes
        )
        repository.insert(metadata)
    }

    /**
     * The distro identity the confirmation card showed, persisted as a stable
     * catalog/profile reference under the same
     * [DownloadService.ATTR_DISTRO_ID] key every consumer already reads.
     *
     * The analysis resolved the archive's distro (embedded profile › built-in
     * profile › probe — §1.146's precedence) and the confirmation screen
     * rendered its branded logo from exactly that identity. The installed row
     * must carry the same reference, or the installed list drops the profile
     * the user just saw and only the next process start's backfill would
     * repair it ([WorkspaceMetadataBackfillManager] runs at startup, not
     * after an in-session install).
     *
     * The governing profile's own id is the stable reference — it is what the
     * profile system resolved, and what the UI's icon registry re-resolves at
     * render time (with its alias handling, e.g. profile "arch" and catalog
     * "archlinux" select the same logo). The probe enum's existing catalog
     * mapping ([RootFsIdentityReader.catalogIdFor]) is the fallback. Empty
     * when the archive could not be identified — the row keeps its letter
     * avatar, never a fake brand.
     */
    private fun detectedDistroIdAttributes(analysis: AnalyzeResult.Success): Map<String, String> {
        val profileId = analysis.profileId
            .takeIf { it.isNotBlank() && it != RootFsProfileResolver.GENERIC_PROFILE.id }
        val catalogId = profileId
            ?: RootFsIdentityReader.catalogIdFor(analysis.identity.distroId)
            ?: return emptyMap()
        return mapOf(DownloadService.ATTR_DISTRO_ID to catalogId)
    }

    /**
     * Post-install metadata mirror — same shape as ImportService/DownloadService
     * (§1.73 launch-config snapshot, §1.81 feature-toggle defaults, sha256
     * attributes), so workspaces created through the confirmed UI flow carry
     * the same records as background/retry installs.
     *
     * Returns false (and does NOT persist `Ready`) when the workspace row is
     * missing — callers must then surface failure, never success.
     */
    private suspend fun updateWorkspaceAfterSuccess(p: Pending, sha256: String): Boolean {
        val current = repository.getById(p.workspaceId) ?: return false
        val verifiedAttr = if (p.sha256Verified) mapOf("sha256Verified" to "true") else emptyMap()
        // Phase 05: the config.yaml reads and the desktop probe are file I/O —
        // done OUTSIDE the row mutex; only their results go inside the atomic
        // transform. The merge itself is now atomic, so a concurrent writer
        // cannot be reverted by this whole-row write.
        val mirrored = mirrorLaunchConfig(p.workspaceId, current.runtimeConfig)
        val initialToggles = resolveInitialFeatureToggles(p.workspaceId)
        val desktopCapable = RootFsCapabilityProbe
            .hasDesktopCapability(fileManager.getRootfsPath(p.workspaceId))
        val persisted = repository.mutate(p.workspaceId) { row ->
            row.copy(
                runtimeConfig = mirrored,
                featureToggles = initialToggles,
                attributes = row.attributes +
                    ("sha256" to sha256) +
                    verifiedAttr +
                    (WorkspaceCapabilities.ATTR_DESKTOP to desktopCapable.toString())
            )
        }
        // A row that vanished between the read and the write is the same
        // failure the old `getById ?: return false` covered.
        if (persisted == null) return false
        // Phase 05: ONE status write (was Installed then Ready — a crash
        // window between two writes for states every consumer treats
        // identically: WorkspaceListPolicy, launch validation, the status map).
        repository.updateStatus(p.workspaceId, WorkspaceStatus.Ready)
        return true
    }

    /**
     * §1.86: sanity-checks that the extracted rootfs actually exists and is
     * non-empty before the flow reports success. Returns a human-readable
     * failure reason, or null when the rootfs is present and usable.
     *
     * 2026-09-03 (issue #2): two extensions, both diagnostics-forward:
     *
     *  1. **Declared health check.** When the winning metadata declares
     *     `linexa.health_check.command`, it runs INSIDE the activated rootfs
     *     (one-shot exec) before success is reported. A non-zero exit — or a
     *     runtime that cannot even start the guest — marks the workspace
     *     Broken with the command, its exit code and its output tail as the
     *     diagnostic, instead of advertising a workspace that can never
     *     start. "Installation appeared to succeed but the terminal fails"
     *     becomes impossible for anything the declaration covers.
     *  2. **Failure context.** Every reason string carries the distro /
     *     version / arch / profile context (issue #12), so a log or a UI
     *     message says WHAT failed to boot, not just that something did.
     *
     * @param p the pending install (carries the analysis descriptor whose
     *   metadata declares the health check, and the identity for context).
     */
    private suspend fun verifyRootfs(p: Pending): String? {
        val context = "distro=${p.summary.distroName} version=${p.summary.version} " +
            "arch=${p.summary.archAbi} profile=${p.analysis.profileId}"
        // §1.147: the check is analysis-driven, not a fixed Debian-shaped set.
        // The analyzer resolved which directories and shell the governing
        // profile requires; a Fedora or Alpine activation must be verified
        // against ITS layout, not against `bin/bash` and `usr/lib`.
        val rootfs = fileManager.getRootfsPath(p.workspaceId)
        val missing = p.analysis.requiredDirectories.filter { !File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            return "Extracted rootfs is missing essential dirs: " +
                "${missing.joinToString(", ")} ($context)"
        }
        val shellCandidates = p.analysis.shellCandidates
        if (shellCandidates.isNotEmpty() &&
            shellCandidates.none { File(rootfs, it.removePrefix("/")).exists() }
        ) {
            return "Extracted rootfs has no declared shell " +
                "(looked for ${shellCandidates.joinToString(", ") { "/${it.removePrefix("/")}" }}) ($context)"
        }
        if (!fileManager.hasAnyRegularFile(p.workspaceId)) {
            return "Extracted rootfs is empty ($context)"
        }
        val healthCheck = p.analysis.descriptor.metadata?.healthCheck ?: return null
        val runtime = containerRuntime ?: return null.also {
            Logger.i(TAG, "health check declared but no container runtime injected — skipping ($context)")
        }
        val rootfsPath = fileManager.getRootfsPath(p.workspaceId).absolutePath
        Logger.i(TAG, "running declared health check for ${p.workspaceId}: '${healthCheck.command}' ($context)")
        val result = try {
            runtime.exec(p.workspaceId, rootfsPath, healthCheck.command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "health check exec threw for ${p.workspaceId}", e)
            return "Post-install health check could not run '${healthCheck.command}': ${e.message} ($context)"
        }
        if (result.exitCode != 0) {
            val tail = result.output.trim().takeLast(400).ifBlank { "<no output>" }
            Logger.w(
                TAG,
                "health check FAILED for ${p.workspaceId}: cmd='${healthCheck.command}' exit=${result.exitCode} " +
                    "output=$tail ($context)"
            )
            return "Post-install health check failed (exit ${result.exitCode}): '${healthCheck.command}' — " +
                "$tail ($context)"
        }
        Logger.i(TAG, "health check passed for ${p.workspaceId} ($context)")
        return null
    }

    private fun mirrorLaunchConfig(workspaceId: String, base: RuntimeConfig): RuntimeConfig {
        return try {
            val config = configParser.load(File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml"))
            if (config?.launch?.hasOverrides == true) config.launchSnapshot() else base
        } catch (e: Exception) {
            Logger.w(TAG, "Could not mirror launch config for $workspaceId: ${e.message}")
            base
        }
    }

    private fun resolveInitialFeatureToggles(workspaceId: String): Map<String, Boolean> {
        return try {
            val config = configParser.load(File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml"))
            resolveFeatureToggles(
                defaults = WorkspaceContent.builtinFeatureDefaults,
                overrides = config?.features.orEmpty()
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Could not resolve feature defaults for $workspaceId: ${e.message}")
            resolveFeatureToggles(defaults = WorkspaceContent.builtinFeatureDefaults, overrides = emptyMap())
        }
    }

    // ── teardown helpers ────────────────────────────────────────────────

    private fun abortActive() {
        val job = activeJob
        val workspaceId = activeWorkspaceId
        // RF-011: invalidate every state write the cancelled job (or a
        // progress callback it already queued) might still perform.
        flowGeneration++
        activeJob = null
        pending = null
        progressTracker = null
        activeWorkspaceId = null
        _state.value = InstallFlowState.Idle
        job?.cancel()
        if (job == null && workspaceId == null) return
        scope.launch {
            // The job's blocking loops observe cancellation only at their next
            // suspension/ensureActive point; join before touching the tree it
            // is still writing into.
            job?.join()
            if (workspaceId != null) {
                // deleteWorkspace removes the DB row (if any) and the workspace
                // dir (download cache / import cache / staging), tolerating
                // read-only directories left by a partial extraction.
                val result = repository.deleteWorkspace(workspaceId)
                if (result is WorkspaceDeletionResult.DiskFailure) {
                    Logger.w(TAG, "abortActive: cleanup of $workspaceId failed: ${result.reason}")
                }
            }
        }
    }

    /** Removes a prepared-but-not-confirmed workspace's temp data (no DB row yet). */
    private fun cleanupWorkspace(workspaceId: String) {
        val base = fileManager.getWorkspaceBasePath(workspaceId)
        val removed = base.deleteRecursivelyAllowingReadOnly()
        if (!removed && base.exists()) {
            // A partial extraction can contain 0555 directories; if even the
            // widening delete failed, leave a durable marker instead of
            // pretending the staging tree is gone. The marker is diagnostic
            // and is swept by InstallRecoveryManager in a later phase.
            Logger.w(
                TAG,
                "cleanupWorkspace($workspaceId): could not remove ${base.absolutePath}; " +
                    "leaving .cleanup-pending marker"
            )
            runCatching {
                File(base, ".cleanup-pending").apply {
                    parentFile?.mkdirs()
                    writeText("workspaceId=$workspaceId\n")
                }
            }.onFailure {
                Logger.w(TAG, "cleanupWorkspace($workspaceId): marker write failed: ${it.message}")
            }
        }
    }

    private fun tryTakePersistableUriPermission(uri: Uri) {
        try {
            application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Logger.w(TAG, "URI does not support a persistable grant, Retry will need a fresh pick: $uri")
        }
    }

    // ── mapping / formatting ────────────────────────────────────────────

    private fun analyzeFraction(progress: InstallProgress): Float? {
        val total = progress.totalBytes ?: return null
        return (progress.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    private fun stepOf(phase: InstallPhase): InstallStep = when (phase) {
        InstallPhase.ANALYZING -> InstallStep.ANALYZING
        InstallPhase.EXTRACTING -> InstallStep.EXTRACTING
        InstallPhase.FIXING -> InstallStep.FIXING
        InstallPhase.FINALIZING -> InstallStep.FINALIZING
    }

    /**
     * Issue #5: the flow's stage weights. Download 40% (network-bound, the
     * longest measurable stretch), analyze 15% (a full read of the archive),
     * extract 40% (the native tar pass, checkpoint-measured), fix 2%,
     * finalize 2%, verify 1% (bounded host-side checks + the declared health
     * check). The sum must be 1 — pinned by StagedProgressTracker's require.
     */
    private fun newProgressTracker(): StagedProgressTracker = StagedProgressTracker(
        listOf(
            StagedProgressTracker.Stage(STAGE_DOWNLOADING, 0.40f),
            StagedProgressTracker.Stage(STAGE_ANALYZING, 0.15f),
            StagedProgressTracker.Stage(STAGE_EXTRACTING, 0.40f),
            StagedProgressTracker.Stage(STAGE_FIXING, 0.02f),
            StagedProgressTracker.Stage(STAGE_FINALIZING, 0.02f),
            StagedProgressTracker.Stage(STAGE_VERIFYING, 0.01f)
        )
    )

    private fun initialStep(selection: InstallSelection): InstallStep = when (selection) {
        is InstallSelection.Distro -> InstallStep.DOWNLOADING
        is InstallSelection.File -> InstallStep.ANALYZING
    }

    private fun slugify(input: String): String =
        input.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "workspace" }

    private data class PreparedSource(val uri: Uri, val expectedSha256: String?)

    private data class Pending(
        val selection: InstallSelection,
        val workspaceId: String,
        val summary: InstallSummary,
        val analysis: AnalyzeResult.Success,
        val sha256Verified: Boolean,
        /** User-editable display name; defaults to [InstallSummary.containerCode]. */
        val containerName: String = ""
    )

    companion object {
        private const val TAG = "InstallCoordinator"

        // Issue #5: stage keys shared by every event mapper in this class.
        private const val STAGE_DOWNLOADING = "downloading"
        private const val STAGE_ANALYZING = "analyzing"
        private const val STAGE_EXTRACTING = "extracting"
        private const val STAGE_FIXING = "fixing"
        private const val STAGE_FINALIZING = "finalizing"
        private const val STAGE_VERIFYING = "verifying"
    }
}

/** What the user asked to install — a catalog distro or a local archive. */
sealed interface InstallSelection {
    data class Distro(val manifest: DistroManifest) : InstallSelection
    data class File(val uri: Uri, val distroName: String, val distroVersion: String) : InstallSelection

    fun sameAs(other: InstallSelection): Boolean = when (this) {
        is Distro -> other is Distro && other.manifest.id == manifest.id
        is File -> other is File && other.uri == uri
    }
}

/** Pipeline stage the UI renders a status text for. */
enum class InstallStep {
    DOWNLOADING, ANALYZING, EXTRACTING, FIXING, FINALIZING, VERIFYING
}

/** What the confirmation card shows once preparation finished. */
data class InstallSummary(
    val workspaceId: String,
    /** Short, stable identifier for the container (e.g. `debian-12`). */
    val containerCode: String,
    val distroLabel: String,
    /** Resolved distro identity for persistence (probe result or selection fallback). */
    val distroName: String,
    val version: String,
    /** Arch ABI to persist (probe result or `arm64-v8a` fallback). */
    val archAbi: String,
    /** Raw probe result — `detected=false` means the UI should note the fallback. */
    val identity: RootFsProbe.Identity,
    val detected: Boolean,
    /** Sum of entry data sizes — the extracted-size estimate. */
    val requiredStorageBytes: Long,
    /** True requirement: extracted + compressed archive + slack, checked at confirm. */
    val requiredFreeBytes: Long,
    /** Compressed archive size (teed in the import cache). */
    val archiveBytes: Long,
    /** Non-directory entry count. */
    val totalEntries: Long,
    /** Free space on the install partition at summary time. */
    val availableBytes: Long,
    /** True when a workspace with this container name already exists. */
    val overwritesExisting: Boolean,
    /**
     * §1.118: the archive's own validated declaration, or null when it
     * carried none (the normal case for third-party rootfs tarballs).
     */
    val declaredMetadata: RootFsMetadata? = null,
    /**
     * §1.118: declaration-vs-reality disagreements worth showing the user
     * (wrong `compressed_format`, an architecture this device can't run).
     * Never fatal — see [RootFsDescriptor].
     */
    val declarationWarnings: List<String> = emptyList(),
    /**
     * §1.118: the author's `linexa.metadata.description`, or §1.119 a legacy
     * `.tiny.yaml` `description:`. Presentation is format-independent text, so
     * this is populated from either — unlike [declaredMetadata].
     */
    val description: String? = null,
    /**
     * §1.119: which root-level file described this image, or null when none
     * did. Lets the confirmation screen say *which* format was read rather
     * than only that something was, and is what distinguishes a legacy image
     * (non-null [metadataSource], null [declaredMetadata]) from an undeclared
     * one (both null).
     */
    val metadataSource: RootFsMetadataFile? = null
)

/** The install flow state machine, driven by [InstallCoordinator]. */
sealed interface InstallFlowState {
    data object Idle : InstallFlowState
    data class Preparing(val step: InstallStep, val progress: Float?, val currentEntryName: String? = null) : InstallFlowState
    data class Ready(val summary: InstallSummary) : InstallFlowState
    data class Installing(val step: InstallStep, val progress: Float?, val currentEntryName: String? = null) : InstallFlowState
    data class Completed(val workspaceId: String) : InstallFlowState
    data class Failed(val reason: String, val workspaceId: String? = null) : InstallFlowState
}
