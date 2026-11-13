package com.fem.linexa.service.importservice

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
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceCapabilities
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.installer.InstallProgress
import com.fem.linexa.service.installer.InstallResult
import com.fem.linexa.service.installer.RootFsCapabilityProbe
import com.fem.linexa.service.installer.RootFsIdentityReader
import com.fem.linexa.service.installer.RootFsInstaller
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import com.fem.linexa.service.workspaceconfig.launchSnapshot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
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
import javax.inject.Inject

/**
 * Owns the Android Service lifecycle (foreground notification, Intent
 * actions, event broadcast) for importing a Workspace. The actual work —
 * streaming checksum, streaming validation, staged/atomic extraction — is
 * [RootFsInstaller] (§1.20); this class' job is the same as
 * [com.fem.linexa.service.runtime.RuntimeService]'s relationship
 * to `ContainerRuntime`: own the OS-level lifecycle, delegate the work.
 *
 * §1.20 fix: before this, `startImport` had a literal comment — "Note: In
 * real implementation, we'd copy URI to temp file first" — and did nothing
 * but flip `WorkspaceStatus` from `Importing(0f)` straight to `Ready` with
 * no extraction happening at all. Every workspace "imported" had an empty
 * rootfs directory.
 *
 * Workspace validation gate fix: `ACTION_IMPORT` can now target an
 * *existing* workspace id ([EXTRA_EXISTING_WORKSPACE_ID]) instead of always
 * minting a new one — this is what backs the Home/Workspace-detail screens'
 * "Retry" action for a [com.fem.linexa.domain.workspace.WorkspaceStatus.Broken]
 * workspace: same id, same DB row, just re-run the installer. The source
 * archive's `content://` URI is persisted on the workspace's `attributes`
 * ([ATTR_SOURCE_URI]) precisely so a later Retry doesn't need the user to
 * re-pick the file — see [tryTakePersistableUriPermission] for why that only
 * works if the permission grant is made persistable at import time.
 */
@AndroidEntryPoint
class ImportService : Service() {

    @Inject lateinit var repository: WorkspaceRepository
    @Inject lateinit var fileManager: WorkspaceFileManager
    @Inject lateinit var installer: RootFsInstaller
    @Inject lateinit var configParser: WorkspaceConfigParser

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<ImportEvent>()
    val events: SharedFlow<ImportEvent> = _events.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Logger.i("ImportService", "ImportService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_IMPORT -> {
                val uriString = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val distroName = intent.getStringExtra(EXTRA_DISTRO_NAME) ?: "Unknown"
                val distroVersion = intent.getStringExtra(EXTRA_DISTRO_VERSION) ?: "latest"
                val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)
                val existingWorkspaceId = intent.getStringExtra(EXTRA_EXISTING_WORKSPACE_ID)
                startForegroundNotification()
                startImport(Uri.parse(uriString), distroName, distroVersion, expectedSha256, existingWorkspaceId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startImport(
        uri: Uri,
        distroName: String,
        distroVersion: String,
        expectedSha256: String?,
        existingWorkspaceId: String? = null
    ) {
        serviceScope.launch {
            val workspaceId = existingWorkspaceId ?: UUID.randomUUID().toString()
            val isRetry = existingWorkspaceId != null
            Logger.i("ImportService", "${if (isRetry) "Retrying" else "Starting"} import for workspace: $workspaceId")

            tryTakePersistableUriPermission(uri)

            try {
                if (isRetry) {
                    val existing = repository.getById(workspaceId)
                    if (existing == null) {
                        Logger.w("ImportService", "Retry requested for unknown workspace: $workspaceId")
                        return@launch
                    }
                    // Phase 05: atomic attribute merge (was getById+update, a
                    // stale-snapshot overwrite that could drop a concurrent
                    // writer's keys).
                    repository.mutate(workspaceId) { current ->
                        current.copy(attributes = current.attributes + (ATTR_SOURCE_URI to uri.toString()))
                    }
                    repository.updateStatus(workspaceId, WorkspaceStatus.Importing(0f))
                } else {
                    val metadata = WorkspaceMetadata(
                        id = workspaceId,
                        status = WorkspaceStatus.Importing(0f),
                        distro = DistroInfo(distroName, distroVersion, "arm64-v8a"),
                        displayName = "$distroName $distroVersion",
                        avatarLetter = distroName.first().toString(),
                        avatarColorHex = "#E8734A",
                        rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath,
                        createdAt = Instant.now().toEpochMilli(),
                        runtimeConfig = RuntimeConfig(),
                        terminalSettings = TerminalSettings(),
                        featureToggles = emptyMap(),
                        attributes = mapOf(ATTR_SOURCE_URI to uri.toString())
                    )
                    repository.insert(metadata)
                }
                _events.emit(ImportEvent.Started(workspaceId))

                lastImportFraction = 0f // fresh import, fresh high-water mark
                val result = installer.install(workspaceId, uri, expectedSha256) { progress ->
                    // Issue #5 (2026-09-03): monotonic clamp — the analysis scan's
                    // byte fraction is monotonic by nature, but an unknown content
                    // length falls back to the ASSUMED size below, which can
                    // over/under-estimate and wiggle the bar. The high-water mark
                    // keeps the reported value honest (never above the real cap
                    // of 0.99) and never backwards.
                    val fraction = progressFraction(progress)
                    lastImportFraction = maxOf(lastImportFraction, fraction)
                    repository.updateStatus(workspaceId, WorkspaceStatus.Importing(lastImportFraction))
                    _events.emit(ImportEvent.Progress(workspaceId, lastImportFraction))
                }

                when (result) {
                    is InstallResult.Success -> {
                        // Phase 05: file-I/O probes (config.yaml reads, desktop
                        // probe, distro-id probe) outside the row mutex; only
                        // their results go inside the atomic transform.
                        val pre = repository.getById(workspaceId)
                        if (pre != null) {
                            val mirrored = mirrorLaunchConfig(workspaceId, pre.runtimeConfig)
                            val initialToggles = resolveInitialFeatureToggles(workspaceId)
                            val desktopCapable = RootFsCapabilityProbe
                                .hasDesktopCapability(fileManager.getRootfsPath(workspaceId))
                            val distroAttrs = importDistroIdAttributes(workspaceId)
                            repository.mutate(workspaceId) { current ->
                                current.copy(
                                    runtimeConfig = mirrored,
                                    featureToggles = initialToggles,
                                    attributes = current.attributes +
                                        ("sha256" to result.sha256) +
                                        (WorkspaceCapabilities.ATTR_DESKTOP to desktopCapable.toString()) +
                                        distroAttrs
                                )
                            }
                        }
                        // Phase 05: ONE status write (was Installed then Ready —
                        // a crash window between two writes for states every
                        // consumer treats identically).
                        repository.updateStatus(workspaceId, WorkspaceStatus.Ready)
                        _events.emit(ImportEvent.Completed(workspaceId))
                        Logger.i("ImportService", "Import completed for workspace: $workspaceId (sha256=${result.sha256})")
                    }
                    is InstallResult.Failure -> {
                        Logger.w("ImportService", "Import failed for $workspaceId: ${result.reason}")
                        repository.updateStatus(workspaceId, WorkspaceStatus.Broken(result.reason))
                        _events.emit(ImportEvent.Failed(workspaceId, result.reason))
                    }
                }
            } catch (e: Exception) {
                Logger.e("ImportService", "Import failed", e)
                repository.updateStatus(workspaceId, WorkspaceStatus.Broken(e.message ?: "Unknown error"))
                _events.emit(ImportEvent.Failed(workspaceId, e.message ?: "Unknown error"))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Best-effort: makes the read grant on [uri] survive past this process,
     * so a later Retry (possibly a new app process, hours later) can reopen
     * the same archive without asking the user to pick it again. Some
     * providers/URIs don't support this (e.g. some FileProvider-backed
     * ones) — that's fine, it just means Retry for that particular
     * workspace will need a fresh pick when the time comes; it does not
     * affect the *current* import, which already holds a normal (non-
     * persistable) grant good for this call regardless.
     */
    private fun tryTakePersistableUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Logger.w("ImportService", "URI does not support a persistable grant, Retry will need a fresh pick: $uri")
        }
    }

    /**
     * § desktop-capability: attribute map recording this imported rootfs's
     * catalog distro id (from [RootFsIdentityReader]) so the workspace gets
     * its branded icon immediately. Empty when the rootfs can't be identified
     * — callers keep their letter-avatar fallback, never a fake brand.
     */
    private fun importDistroIdAttributes(workspaceId: String): Map<String, String> {
        val catalogId = RootFsIdentityReader.catalogDistroId(fileManager.getRootfsPath(workspaceId))
        return if (catalogId != null) mapOf(DownloadService.ATTR_DISTRO_ID to catalogId) else emptyMap()
    }

    /** [InstallProgress.totalBytes] is sometimes unknown (a content provider that doesn't report length) — fall back to a fixed nominal size so progress still moves instead of staying at a frozen 0%. */
    private fun progressFraction(progress: InstallProgress): Float {
        val total = progress.totalBytes ?: ASSUMED_ARCHIVE_SIZE_WHEN_UNKNOWN
        return (progress.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
    }

    /** Issue #5: high-water mark for the import progress bar (monotonic). */
    private var lastImportFraction = 0f

    /**
     * §1.73: mirrors the persisted config.yaml's `linexa.launch` overrides
     * into [RuntimeConfig] on the workspace record. Fail-open: missing or
     * unparseable config keeps the current runtime config untouched (the
     * file was hard-validated at scan time, so this only fires on later
     * tampering). Never throws — a snapshot failure must not fail an
     * otherwise-successful import.
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
            Logger.w("ImportService", "Could not mirror launch config for $workspaceId: ${e.message}")
            base
        }
    }

    /**
     * §1.81: resolves the workspace's initial feature-toggle defaults from the
     * archive's `config.yaml` `linexa.features` block (built-in defaults
     * overlaid by config overrides — [resolveFeatureToggles]). Fail-open: a
     * missing or unparseable config yields the plain built-in defaults; a
     * merge failure must not fail an otherwise-successful import.
     */
    private fun resolveInitialFeatureToggles(workspaceId: String): Map<String, Boolean> {
        return try {
            val config = configParser.load(File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml"))
            resolveFeatureToggles(
                defaults = WorkspaceContent.builtinFeatureDefaults,
                overrides = config?.features.orEmpty()
            )
        } catch (e: Exception) {
            Logger.w("ImportService", "Could not resolve feature defaults for $workspaceId: ${e.message}")
            resolveFeatureToggles(defaults = WorkspaceContent.builtinFeatureDefaults, overrides = emptyMap())
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Linexa Import")
            .setContentText("Importing workspace...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Import Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when importing a workspace"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Logger.i("ImportService", "ImportService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "import_service"
        const val NOTIFICATION_ID = 2
        const val ACTION_IMPORT = "com.fem.linexa.ACTION_IMPORT"
        const val EXTRA_URI = "uri"
        const val EXTRA_DISTRO_NAME = "distro_name"
        const val EXTRA_DISTRO_VERSION = "distro_version"
        const val EXTRA_EXPECTED_SHA256 = "expected_sha256"
        const val EXTRA_EXISTING_WORKSPACE_ID = "existing_workspace_id"

        /** Workspace-attribute key for the persisted source archive URI (see class doc). */
        const val ATTR_SOURCE_URI = "sourceUri"

        // §1.20: used only to keep progress moving when a content provider
        // doesn't report a length (see progressFraction) — not a real
        // assumption about any specific distro's size.
        private const val ASSUMED_ARCHIVE_SIZE_WHEN_UNKNOWN = 500L * 1024 * 1024

        fun importIntent(
            context: Context,
            uri: Uri,
            distroName: String,
            distroVersion: String,
            expectedSha256: String? = null
        ): Intent {
            return Intent(context, ImportService::class.java).apply {
                action = ACTION_IMPORT
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISTRO_NAME, distroName)
                putExtra(EXTRA_DISTRO_VERSION, distroVersion)
                if (expectedSha256 != null) putExtra(EXTRA_EXPECTED_SHA256, expectedSha256)
            }
        }

        /**
         * Re-runs the installer against an *existing* workspace id, using a
         * URI the caller already has (typically read back from
         * [ATTR_SOURCE_URI] on that workspace's persisted attributes).
         */
        fun retryIntent(
            context: Context,
            workspaceId: String,
            uri: Uri,
            distroName: String,
            distroVersion: String,
            expectedSha256: String? = null
        ): Intent {
            return importIntent(context, uri, distroName, distroVersion, expectedSha256).apply {
                putExtra(EXTRA_EXISTING_WORKSPACE_ID, workspaceId)
            }
        }
    }
}

sealed interface ImportEvent {
    data class Started(val workspaceId: String) : ImportEvent
    data class Progress(val workspaceId: String, val progress: Float) : ImportEvent
    data class Completed(val workspaceId: String) : ImportEvent
    data class Failed(val workspaceId: String, val reason: String) : ImportEvent
}
