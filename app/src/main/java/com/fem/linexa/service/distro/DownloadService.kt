package com.fem.linexa.service.distro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.resolveFeatureToggles
import com.fem.linexa.domain.distro.DistroCatalog
import com.fem.linexa.domain.distro.DistroManifest
import com.fem.linexa.domain.distro.DistroSource
import com.fem.linexa.domain.distro.ChecksumFormat
import com.fem.linexa.domain.distro.ResolvedDownload
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.distro.RootfsMirrorResolver
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.installer.AnalyzeResult
import com.fem.linexa.service.installer.InstallPhase
import com.fem.linexa.service.installer.InstallProgress
import com.fem.linexa.service.installer.InstallResult
import com.fem.linexa.service.installer.StagedProgressTracker
import com.fem.linexa.service.installer.RootFsCapabilityProbe
import com.fem.linexa.service.installer.RootFsInstaller
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import com.fem.linexa.service.workspaceconfig.launchSnapshot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * RootFS-management "Download" workflow (the audit's item 4): downloads a
 * cataloged distro's archive, resolves its checksum where the source
 * offers one, then hands the local file to the exact same
 * [RootFsInstaller] the Import workflow uses — extraction, staged/atomic
 * activation, and validation are one implementation either way, not
 * duplicated between "imported from a picked file" and "downloaded from a
 * catalog." Mirrors [com.fem.linexa.service.importservice.ImportService]'s
 * shape deliberately (foreground notification, Intent actions, event
 * broadcast, same Retry-by-existing-workspace-id pattern) — same relationship
 * to [DistroDownloader]/[RootFsInstaller] that ImportService has to
 * [RootFsInstaller] alone.
 *
 * Progress is reported across two phases so it doesn't sit at a
 * meaningless 0% during a multi-hundred-MB download and then jump straight
 * to 100%: download owns the 0–50% range, extraction owns 50–100%.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var repository: WorkspaceRepository
    @Inject lateinit var fileManager: WorkspaceFileManager
    @Inject lateinit var installer: RootFsInstaller
    @Inject lateinit var downloader: DistroDownloader
    @Inject lateinit var checksumResolver: ChecksumResolver
    @Inject lateinit var resolverRegistry: DistroSourceResolverRegistry
    @Inject lateinit var configParser: WorkspaceConfigParser
    /** 2026-09-01: bundled linexa.yaml install profiles (Debian/Ubuntu). */
    @Inject lateinit var bundledRootfsMetadata: com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<DownloadEvent>()
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** F5 (2026-09-02): workspace ids (or distro ids for fresh installs) with a download currently running. */
    private val inFlightDownloads = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Logger.i(TAG, "DownloadService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val distroId = intent.getStringExtra(EXTRA_DISTRO_ID) ?: return START_NOT_STICKY
            val existingWorkspaceId = intent.getStringExtra(EXTRA_EXISTING_WORKSPACE_ID)
            startForegroundNotification(distroId)
            startDownload(distroId, existingWorkspaceId)
        }
        return START_NOT_STICKY
    }

    private fun startDownload(distroId: String, existingWorkspaceId: String?) {
        // Pre-release audit fix (2026-09-02, finding F5): deduplicate per
        // workspace. Two quick retry intents used to run two
        // downloader.download() calls to the SAME destination file in append
        // mode — interleaved writes, corrupt archive. The in-flight set makes
        // a duplicate start a no-op while the first one runs.
        val guardKey = existingWorkspaceId ?: distroId
        if (!inFlightDownloads.add(guardKey)) {
            Logger.i(TAG, "Download already in flight for $guardKey — duplicate start ignored")
            return
        }
        serviceScope.launch {
            val manifest = DistroCatalog.findById(distroId)
            if (manifest == null) {
                Logger.w(TAG, "Unknown distro id: $distroId")
                inFlightDownloads.remove(guardKey)
                existingWorkspaceId?.let {
                    repository.updateStatus(it, WorkspaceStatus.Broken("Unknown distro: $distroId"))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            // 2026-09-01 gate: only Debian/Ubuntu are installable for now.
            // This service is reachable via retry intents carrying a persisted
            // distro id, so the manifest flag is re-checked here — the UI gate
            // alone cannot cover a stale PendingIntent.
            if (!manifest.enabled) {
                Logger.w(TAG, "Distro '$distroId' is locked (not installable) — download refused")
                inFlightDownloads.remove(guardKey)
                existingWorkspaceId?.let {
                    repository.updateStatus(it, WorkspaceStatus.Broken("Distro locked: $distroId"))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            // 2026-09-01: the bundled linexa.yaml for this distro (Debian/
            // Ubuntu ship one) — applied by the installer only when the
            // archive itself declares no metadata.
            val bundledMetadataBytes = bundledRootfsMetadata.forDistro(manifest.id)

            val workspaceId = existingWorkspaceId ?: UUID.randomUUID().toString()
            val isRetry = existingWorkspaceId != null
            Logger.i(TAG, "${if (isRetry) "Retrying" else "Starting"} download for workspace: $workspaceId (${manifest.id})")

            try {
                if (isRetry) {
                    repository.updateStatus(workspaceId, WorkspaceStatus.Importing(0f))
                } else {
                    repository.insert(newWorkspaceMetadata(workspaceId, manifest))
                }
                _events.emit(DownloadEvent.Started(workspaceId))

                // Pre-release audit fix (2026-09-02, finding F3): the pair
                // carries (resolved source, OFFICIAL archive filename for the
                // checksum sidecar lookup) — the official name survives the
                // mirror override so MULTI_HASH_LIST lookups still match.
                val resolvedSource = resolveSource(manifest, workspaceId)
                if (resolvedSource == null) {
                    val reason = "Could not resolve a download URL for ${manifest.displayName}"
                    Logger.w(TAG, reason)
                    repository.updateStatus(workspaceId, WorkspaceStatus.Broken(reason))
                    _events.emit(DownloadEvent.Failed(workspaceId, reason))
                    return@launch
                }
                val (resolved, resolvedChecksumFileName) = resolvedSource

                val archiveFileName = resolved.downloadUrl.substringAfterLast('/')
                // Pre-release audit fix (2026-09-02, finding F3): the SHA256SUMS
                // sidecar lives on the OFFICIAL server and lists the OFFICIAL
                // archive filename. The checksum lookup must therefore use the
                // pre-override (official) filename — a mirror whose last path
                // segment differs used to make the MULTI_HASH_LIST lookup miss,
                // silently degrading a mirrored download to "unverified".
                // resolveSource now carries the official name alongside.
                val checksumLookupName = resolvedChecksumFileName ?: archiveFileName
                val destination = fileManager.getDownloadCachePath(workspaceId, archiveFileName)

                // Issue #5 (2026-09-03): the two-phase API with a shared weighted
                // monotonic tracker for the WHOLE pipeline. The old flow mapped
                // download onto its own 0..50% bar and then used the one-shot
                // install overload whose callback never carries extraction
                // fractions — every byte-less event fell back to the fixed 50%
                // boundary, producing the reported 60%→50%→70%→30% chaos.
                // Weights: download 50, analyze+checksum 10, extract 35, fix 3,
                // finalize 2. The tracker is monotonic, so a downloader
                // range-restart (HTTP 200 to a Range request) freezes the bar
                // instead of snapping it to zero.
                val tracker = StagedProgressTracker(
                    listOf(
                        StagedProgressTracker.Stage(STAGE_DOWNLOAD, 0.50f),
                        StagedProgressTracker.Stage(STAGE_ANALYZE, 0.10f),
                        StagedProgressTracker.Stage(STAGE_EXTRACT, 0.35f),
                        StagedProgressTracker.Stage(STAGE_FIX, 0.03f),
                        StagedProgressTracker.Stage(STAGE_FINALIZE, 0.02f)
                    )
                )

                suspend fun emitProgress(fraction: Float?) {
                    // Importing carries a non-null fraction by type; while the
                    // tracker is still indeterminate (no measurable stage yet)
                    // it stays at 0 rather than inventing a number.
                    val value = (fraction ?: 0f).coerceIn(0f, 0.99f)
                    repository.updateStatus(workspaceId, WorkspaceStatus.Importing(value))
                }

                val downloadResult = downloader.download(resolved.downloadUrl, destination) { downloaded, total ->
                    val fraction = tracker.update(
                        STAGE_DOWNLOAD,
                        total?.takeIf { it > 0 }?.let { downloaded.toFloat() / it.toFloat() }
                    )
                    emitProgress(fraction)
                    fraction?.let { _events.emit(DownloadEvent.Progress(workspaceId, it)) }
                }

                when (downloadResult) {
                    is DownloadResult.Failure -> {
                        Logger.w(TAG, "Download failed for $workspaceId: ${downloadResult.reason}")
                        repository.updateStatus(workspaceId, WorkspaceStatus.Broken(downloadResult.reason))
                        _events.emit(DownloadEvent.Failed(workspaceId, downloadResult.reason))
                    }
                    is DownloadResult.Success -> {
                        val expectedSha256 = checksumResolver.resolve(
                            resolved.checksumUrl,
                            resolved.checksumFormat,
                            checksumLookupName
                        )
                        if (expectedSha256 == null) {
                            // Pre-release audit fix (2026-09-02, finding F1):
                            // fail closed when the source declares a checksum
                            // but the sidecar could not be fetched/parsed —
                            // same policy as InstallCoordinator. Only an
                            // honest catalog declaration of NO checksum may
                            // proceed unverified.
                            val declaresChecksum = resolved.checksumUrl != null &&
                                resolved.checksumFormat != ChecksumFormat.NONE
                            if (declaresChecksum) {
                                val reason = "Could not fetch or parse the SHA-256 checksum for ${manifest.id} " +
                                    "(${resolved.checksumUrl}) — refusing to install an unverified download"
                                Logger.w(TAG, reason)
                                repository.updateStatus(workspaceId, WorkspaceStatus.Broken(reason))
                                _events.emit(DownloadEvent.Failed(workspaceId, reason))
                                return@launch
                            }
                            Logger.w(TAG, "No checksum published for ${manifest.id} — proceeding unverified (catalog declares none)")
                        }

                        // Issue #5: analyze() tees the archive into the import
                        // cache and verifies the checksum; its byte fractions
                        // ride the tracker's analyze stage (see the weights
                        // comment above the tracker).
                        val analysis = installer.analyze(
                            workspaceId = workspaceId,
                            sourceUri = Uri.fromFile(downloadResult.file),
                            expectedSha256 = expectedSha256,
                            bundledMetadata = bundledMetadataBytes,
                            onProgress = { progress ->
                                val fraction = tracker.update(
                                    STAGE_ANALYZE,
                                    progress.totalBytes?.takeIf { it > 0 }
                                        ?.let { progress.bytesRead.toFloat() / it.toFloat() }
                                )
                                emitProgress(fraction)
                            }
                        )
                        when (analysis) {
                            is AnalyzeResult.Failure -> {
                                Logger.w(TAG, "Analysis failed for $workspaceId: ${analysis.reason}")
                                // Phase 06 (OPT-SEC-005): an analyze failure is
                                // checksum mismatch, archive-format-detection failure
                                // or archive-validation failure — none of them can
                                // succeed against the SAME partial bytes on Retry,
                                // so the poisoned cache must not survive it. The
                                // failure kind is not typed (AnalyzeResult.Failure
                                // carries only a reason string) and RootFsInstaller
                                // is outside this phase's write set, so the brief's
                                // fallback applies: delete the cache on EVERY
                                // analyze failure — re-downloading is always safer
                                // than retrying a poisoned partial. The cases that
                                // must keep the cache are not routed through this
                                // branch: the downloader retries transient network
                                // failures itself, and user cancellation is caught
                                // as CancellationException below (never marked
                                // Failed), while the confirmed-UI flow (which is how
                                // most installs actually run) wipes the whole
                                // workspace base dir — cache included — via
                                // InstallCoordinator.cleanupWorkspace.
                                fileManager.deleteDownloadCache(workspaceId)
                                repository.updateStatus(workspaceId, WorkspaceStatus.Broken(analysis.reason))
                                _events.emit(DownloadEvent.Failed(workspaceId, analysis.reason))
                            }
                            is AnalyzeResult.Success -> {
                                val installResult = installer.install(workspaceId, analysis) { progress ->
                                    val fraction = when (progress.phase) {
                                        InstallPhase.EXTRACTING -> tracker.update(STAGE_EXTRACT, progress.fraction)
                                        InstallPhase.FIXING -> tracker.complete(STAGE_FIX)
                                        InstallPhase.FINALIZING -> tracker.complete(STAGE_FINALIZE)
                                        InstallPhase.ANALYZING -> tracker.overallNullable
                                    }
                                    emitProgress(fraction)
                                }
                                when (installResult) {
                            is InstallResult.Success -> {
                                fileManager.deleteDownloadCache(workspaceId)
                                // Phase 05: the config.yaml reads and the desktop probe are
                                // file I/O — done OUTSIDE the row mutex (the mutate
                                // transform must stay pure); only their results are applied
                                // inside the atomic transform. The row merge itself is now
                                // atomic, so a concurrent status/attribute writer cannot
                                // be reverted by this whole-row write (the old getById+
                                // update pair was a stale-snapshot overwrite).
                                val pre = repository.getById(workspaceId)
                                if (pre != null) {
                                    val mirrored = mirrorLaunchConfig(workspaceId, pre.runtimeConfig)
                                    val initialToggles = resolveInitialFeatureToggles(workspaceId)
                                    val desktopCapable = RootFsCapabilityProbe
                                        .hasDesktopCapability(fileManager.getRootfsPath(workspaceId))
                                    repository.mutate(workspaceId) { current ->
                                        current.copy(
                                            runtimeConfig = mirrored,
                                            featureToggles = initialToggles,
                                            attributes = current.attributes +
                                                ("sha256" to installResult.sha256) +
                                                ("sha256Verified" to (expectedSha256 != null).toString()) +
                                                (WorkspaceCapabilities.ATTR_DESKTOP to desktopCapable.toString())
                                        )
                                    }
                                }
                                // Phase 05: ONE status write. The old Installed→Ready pair
                                // left a crash window between two writes; every consumer
                                // (WorkspaceListPolicy, launch validation, the status map)
                                // treats the two identically, so the intermediate state
                                // bought nothing.
                                repository.updateStatus(workspaceId, WorkspaceStatus.Ready)
                                _events.emit(DownloadEvent.Completed(workspaceId))
                                Logger.i(TAG, "Download+install completed for $workspaceId (sha256=${installResult.sha256}, verified=${expectedSha256 != null})")
                            }
                            is InstallResult.Failure -> {
                                Logger.w(TAG, "Extraction failed for $workspaceId: ${installResult.reason}")
                                repository.updateStatus(workspaceId, WorkspaceStatus.Broken(installResult.reason))
                                _events.emit(DownloadEvent.Failed(workspaceId, installResult.reason))
                            }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Pre-release audit fix (2026-09-02, finding F6): normal
                // service teardown (onDestroy → serviceScope.cancel, task
                // removed) used to land in the generic catch below and mark
                // the workspace Broken with the cancellation message — a
                // spurious failure for a merely-cancelled download. Cancellation
                // is not a download error; rethrow (the workspace row stays in
                // its Importing state and the startup recovery pass / a retry
                // handles it from there).
                Logger.i(TAG, "Download pipeline cancelled for $workspaceId")
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Download pipeline failed for $workspaceId", e)
                repository.updateStatus(workspaceId, WorkspaceStatus.Broken(e.message ?: "Unknown error"))
                _events.emit(DownloadEvent.Failed(workspaceId, e.message ?: "Unknown error"))
            } finally {
                inFlightDownloads.remove(guardKey)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * §1.73: mirrors the persisted config.yaml's `linexa.launch` overrides
     * into [RuntimeConfig] on the workspace record. Fail-open: missing or
     * unparseable config keeps the current runtime config untouched. Never
     * throws — a snapshot failure must not fail an otherwise-successful
     * install.
     */
    private fun mirrorLaunchConfig(workspaceId: String, base: RuntimeConfig): RuntimeConfig {
        return try {
            val config = configParser.load(File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml"))
            if (config?.launch?.hasOverrides == true) {
                config.launchSnapshot()
            } else {
                base
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not mirror launch config for $workspaceId: ${e.message}")
            base
        }
    }

    /**
     * §1.81: resolves the workspace's initial feature-toggle defaults from the
     * archive's `config.yaml` `linexa.features` block (built-in defaults
     * overlaid by config overrides — [resolveFeatureToggles]). Fail-open: a
     * missing or unparseable config yields the plain built-in defaults; a
     * merge failure must not fail an otherwise-successful install.
     */
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

    private fun newWorkspaceMetadata(workspaceId: String, manifest: DistroManifest): WorkspaceMetadata {
        return WorkspaceMetadata(
            id = workspaceId,
            status = WorkspaceStatus.Importing(0f),
            distro = DistroInfo(manifest.displayName, manifest.version, "arm64-v8a"),
            displayName = "${manifest.displayName} ${manifest.version}",
            avatarLetter = manifest.avatarLetter,
            avatarColorHex = manifest.avatarColorHex,
            rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath,
            createdAt = Instant.now().toEpochMilli(),
            runtimeConfig = RuntimeConfig(),
            terminalSettings = TerminalSettings(),
            featureToggles = emptyMap(),
            attributes = mapOf(ATTR_DISTRO_ID to manifest.id)
        )
    }

    /**
     * Mirror Configuration (§22/§25): resolves the distro source, applying
     * the workspace's persisted RootFS download mirror override
     * (`network.mirror.rootfs.url`) when present. The checksum URL and
     * format ALWAYS come from the catalog — a mirror serves bytes, never
     * trust — so integrity validation is unchanged: an override mirror that
     * serves different content than the official checksum still fails the
     * install. An invalid/absent override falls back to the catalog URL.
     */
    private suspend fun resolveSource(
        manifest: DistroManifest,
        workspaceId: String
    ): Pair<ResolvedDownload, String?>? {
        // The checksum sidecar is keyed by the OFFICIAL archive filename; the
        // mirror override must never change the lookup key (F3, 2026-09-02).
        var officialFileName: String? = when (val source = manifest.source) {
            is DistroSource.Direct -> source.downloadUrl.substringAfterLast('/')
            is DistroSource.Resolved -> null // filled in below, from the resolver's official URL
        }
        val base = when (val source = manifest.source) {
            is DistroSource.Direct -> ResolvedDownload(source.downloadUrl, source.checksumUrl, source.checksumFormat)
            is DistroSource.Resolved -> {
                val resolver = resolverRegistry.resolverFor(source.resolverId)
                if (resolver == null) {
                    Logger.w(TAG, "No resolver registered for id: ${source.resolverId}")
                    return null
                }
                try {
                    val r = resolver.resolve(source)
                    // The resolver output is the OFFICIAL url (resolvers never
                    // know about user mirrors) — its last segment is the name
                    // the official sidecar lists.
                    if (officialFileName == null) {
                        officialFileName = r.downloadUrl.substringAfterLast('/')
                    }
                    r
                } catch (e: Exception) {
                    Logger.w(TAG, "Resolver '${source.resolverId}' failed for ${manifest.id}: ${e.message}")
                    return null
                }
            }
        }
        val override = repository.getById(workspaceId)?.attributes
            ?.get(NetworkSettings.KEY_ROOTFS_MIRROR_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (override != null) {
            Logger.i(TAG, "RootFS mirror override for $workspaceId: $override")
        }
        val resolved = RootfsMirrorResolver.resolve(manifest, base, override)
        return resolved to officialFileName
    }

    private fun startForegroundNotification(distroId: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Linexa Download")
            .setContentText("Downloading $distroId\u2026")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Distro Download",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when downloading a distribution"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Logger.i(TAG, "DownloadService destroyed")
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "download_service"
        const val NOTIFICATION_ID = 3
        const val ACTION_DOWNLOAD = "com.fem.linexa.ACTION_DOWNLOAD"
        const val EXTRA_DISTRO_ID = "distro_id"
        const val EXTRA_EXISTING_WORKSPACE_ID = "existing_workspace_id"

        /** Workspace-attribute key recording which [DistroManifest.id] this workspace was downloaded from — how Retry knows to re-download instead of re-import. */
        const val ATTR_DISTRO_ID = "distroId"

        // Issue #5: stage keys for the shared weighted progress tracker.
        private const val STAGE_DOWNLOAD = "download"
        private const val STAGE_ANALYZE = "analyze"
        private const val STAGE_EXTRACT = "extract"
        private const val STAGE_FIX = "fix"
        private const val STAGE_FINALIZE = "finalize"

        fun downloadIntent(context: Context, distroId: String): Intent {
            return Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_DISTRO_ID, distroId)
            }
        }

        /** Re-runs the whole download+install pipeline for an existing workspace id (Retry). */
        fun retryIntent(context: Context, workspaceId: String, distroId: String): Intent {
            return downloadIntent(context, distroId).apply {
                putExtra(EXTRA_EXISTING_WORKSPACE_ID, workspaceId)
            }
        }
    }
}

sealed interface DownloadEvent {
    data class Started(val workspaceId: String) : DownloadEvent
    data class Progress(val workspaceId: String, val progress: Float) : DownloadEvent
    data class Completed(val workspaceId: String) : DownloadEvent
    data class Failed(val workspaceId: String, val reason: String) : DownloadEvent
}
