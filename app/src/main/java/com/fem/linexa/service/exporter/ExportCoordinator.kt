package com.fem.linexa.service.exporter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.RuntimeLifecycleManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § export: the user-facing RootFS export flow, orchestrated as ONE singleton
 * state machine — the exact shape of [com.fem.linexa.service.installer.InstallCoordinator],
 * scaled down to a single long operation (there is no analyze/confirm split:
 * the user has already chosen the destination file by the time this runs).
 *
 * Flow: [start] (validate the workspace → open the SAF output → stream the
 * live rootfs through [RootFsExporter] with real progress → verify the
 * written archive) → [ExportFlowState.Completed] / [ExportFlowState.Failed].
 * [cancel] aborts the in-flight export, removes the partial output file and
 * returns the machine to [ExportFlowState.Idle].
 *
 * Everything here is orchestration: the packing itself lives in
 * [TarRootFsExporter], the UI observes [state] through
 * [com.fem.linexa.ui.screens.home.HomeViewModel] and forwards
 * [start]/[cancel].
 *
 * Single-flight: one export at a time, app-wide. [start] flips the state
 * synchronously (the same discipline [com.fem.linexa.service.installer.InstallCoordinator.confirm]
 * uses), so a second tap can never open a second output stream.
 *
 * Cleanup contract: the destination file is counted as a backup ONLY after
 * [RootFsExporter.export] returns Success — meaning the archive was written,
 * closed, re-opened and byte-verified. Any other exit (failure, cancellation)
 * attempts to delete the partial SAF document; when the provider refuses,
 * [ExportFlowState.Failed.partialFileCleanupFailed] says so explicitly
 * instead of pretending the file is gone.
 */
@Singleton
class ExportCoordinator @Inject constructor(
    private val application: Application,
    private val repository: WorkspaceRepository,
    private val fileManager: WorkspaceFileManager,
    private val lifecycleManager: RuntimeLifecycleManager,
    private val exporter: RootFsExporter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ExportFlowState>(ExportFlowState.Idle)
    val state: StateFlow<ExportFlowState> = _state.asStateFlow()

    private var activeJob: Job? = null

    // ── user-facing API ─────────────────────────────────────────────────────

    /**
     * Starts exporting [workspaceId]'s rootfs into the SAF document [targetUri]
     * the user created with the file picker. [suggestedFileName] is the name
     * the picker was launched with — the completion state falls back to it when
     * the provider's own display name cannot be queried. [credentialPolicy] is
     * the user's answer to the pre-export credential warning (finding 022);
     * it is required so no UI path can start an export without deciding.
     * Validation failures
     * (workspace gone, not installed, still running, rootfs unusable) surface
     * as [ExportFlowState.Failed] with a user-presentable reason — the same
     * channel a mid-export I/O failure uses, so the UI needs exactly one
     * error presentation.
     */
    fun start(
        workspaceId: String,
        workspaceName: String,
        targetUri: Uri,
        credentialPolicy: ExportCredentialPolicy,
        suggestedFileName: String? = null,
    ) {
        val current = _state.value
        if (current is ExportFlowState.Active) {
            Logger.w(TAG, "start($workspaceId) refused: an export is already running")
            return
        }
        activeJob?.cancel()

        // Synchronous flip before launching — same single-flight discipline as
        // InstallCoordinator.confirm().
        _state.value = ExportFlowState.Active(workspaceId, workspaceName, ExportStep.PREPARING, null, null)

        activeJob = scope.launch {
            try {
                runExport(workspaceId, workspaceName, targetUri, credentialPolicy, suggestedFileName)
            } catch (e: CancellationException) {
                Logger.i(TAG, "export($workspaceId): cancelled — cleaning up the partial output")
                deletePartialOutput(targetUri)
                _state.value = ExportFlowState.Idle
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "export($workspaceId) failed", e)
                val cleanedUp = deletePartialOutput(targetUri)
                _state.value = ExportFlowState.Failed(
                    reason = e.message ?: "Export failed",
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    partialFileCleanupFailed = !cleanedUp,
                )
            }
        }
    }

    /**
     * Cancels the running export. The job's cancellation path removes the
     * partial output and returns the machine to Idle — cancelling is not an
     * error, so the UI shows nothing beyond the progress dialog going away.
     */
    fun cancel() {
        if (_state.value is ExportFlowState.Active) {
            activeJob?.cancel()
        }
    }

    /** Clears a surfaced [ExportFlowState.Failed]/[ExportFlowState.Completed] once shown. */
    fun consumeResult() {
        if (_state.value !is ExportFlowState.Active) {
            _state.value = ExportFlowState.Idle
        }
    }

    // ── the work ────────────────────────────────────────────────────────────

    private suspend fun runExport(
        workspaceId: String,
        workspaceName: String,
        targetUri: Uri,
        credentialPolicy: ExportCredentialPolicy,
        suggestedFileName: String?,
    ) {
        val failure: (String) -> Nothing = { reason ->
            throw ExportFailure(reason)
        }

        val workspace = repository.getById(workspaceId)
            ?: failure("The workspace no longer exists.")

        val statusReason = statusBlockReason(workspace.status)
        if (statusReason != null) failure(statusReason)

        if (lifecycleManager.activeWorkspaceId.value == workspaceId) {
            failure(REASON_WORKSPACE_RUNNING)
        }

        if (!fileManager.hasValidRootfs(workspaceId)) {
            failure("This workspace has no installed rootfs to export.")
        }
        val rootfs = fileManager.getRootfsPath(workspaceId)
        val rootfsBytes = fileManager.getRootfsSize(workspaceId)
        if (rootfsBytes <= 0L) {
            failure("This workspace's rootfs is empty; there is nothing to export.")
        }
        // The re-import scan refuses archives over the validator's bomb cap —
        // spending an hour writing a backup the installer would reject is not
        // an honest option, so the cap is checked here, before any packing.
        if (rootfsBytes > ArchiveValidator.DEFAULT_MAX_UNCOMPRESSED_SIZE) {
            failure(
                "This rootfs is ${FileSizeFormatter.format(rootfsBytes)}, beyond the " +
                    "${FileSizeFormatter.format(ArchiveValidator.DEFAULT_MAX_UNCOMPRESSED_SIZE)} " +
                    "re-import limit; an export of it could not be installed again."
            )
        }

        // The workspace's captured native declaration (`{workspace}/config.yaml`,
        // written verbatim by the installer at install time). Embedded as the
        // archive's `.linexa.yaml` only when the tree does not already carry a
        // root-level declaration of its own (see TarRootFsExporter). Size-bounded
        // by the same cap the scan applies to metadata candidates, so the
        // re-import will never silently skip it.
        val embeddedConfig = readEmbeddedConfig(workspaceId)

        // Best effort: CreateDocument grants are persistable, and taking one
        // keeps the write alive across a configuration change mid-export.
        tryTakePersistableUriPermission(targetUri)

        val output = try {
            application.contentResolver.openOutputStream(targetUri, "w")
        } catch (e: Exception) {
            Logger.w(TAG, "export($workspaceId): could not open $targetUri for writing: ${e.message}")
            failure("Could not open the selected file for writing: ${e.message ?: "access refused"}")
        } ?: failure("Could not open the selected file for writing: the storage provider returned no stream.")

        Logger.i(TAG, "export($workspaceId): packing ${rootfs.absolutePath} → $targetUri")

        val result = exporter.export(
            ExportRequest(
                rootfs = rootfs,
                output = output,
                verifyInput = {
                    try {
                        application.contentResolver.openInputStream(targetUri)
                    } catch (e: Exception) {
                        Logger.w(TAG, "export($workspaceId): verify re-open failed: ${e.message}")
                        null
                    }
                },
                embeddedConfig = embeddedConfig,
                expectedBytes = rootfsBytes,
                credentialPolicy = credentialPolicy,
            )
        ) { progress ->
            val active = _state.value
            if (active is ExportFlowState.Active) {
                _state.value = active.copy(
                    step = stepOf(progress.phase),
                    progress = uiFraction(progress),
                    currentEntryName = progress.currentEntryName,
                )
            }
        }

        when (result) {
            is ExportResult.Success -> {
                val fileName = queryDisplayName(targetUri)
                    ?: suggestedFileName
                    ?: ExportNames.suggestedFileName(workspace.distro.name, workspace.distro.version, workspace.distro.arch, workspaceId)
                _state.value = ExportFlowState.Completed(
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    fileName = fileName,
                    outputBytes = result.outputBytes,
                    skippedEntries = result.skippedEntries,
                    removedCredentials = result.removedCredentials,
                )
                Logger.i(
                    TAG,
                    "export($workspaceId): completed — ${result.entryCount} entries, " +
                        "${result.outputBytes} bytes, sha256=${result.sha256.take(12)}…, " +
                        "${result.removedCredentials.size} credential entries removed"
                )
            }

            is ExportResult.Failure -> {
                val cleanedUp = deletePartialOutput(targetUri)
                _state.value = ExportFlowState.Failed(
                    reason = result.reason,
                    workspaceId = workspaceId,
                    workspaceName = workspaceName,
                    partialFileCleanupFailed = !cleanedUp,
                )
            }
        }
    }

    // ── validation ──────────────────────────────────────────────────────────

    /**
     * The export-specific status gate. [WorkspaceStatus.Ready] and
     * [WorkspaceStatus.Installed] are the two statuses that guarantee a
     * complete, quiet rootfs: Created has no rootfs yet, Importing is racing
     * an extraction into staging, Repairing is mutating it, Broken may be a
     * partial tree, Deleting is being removed under us.
     */
    private fun statusBlockReason(status: WorkspaceStatus): String? = when (status) {
        is WorkspaceStatus.Ready, is WorkspaceStatus.Installed -> null
        is WorkspaceStatus.Importing ->
            "This workspace is still installing; wait for it to finish before exporting."
        is WorkspaceStatus.Deleting ->
            "This workspace is being deleted and cannot be exported."
        is WorkspaceStatus.Repairing ->
            "This workspace is being repaired; export is unavailable until it finishes."
        is WorkspaceStatus.Broken ->
            "This workspace is broken (${status.reason}); its rootfs cannot be exported safely."
        is WorkspaceStatus.Created ->
            "This workspace has no installed rootfs to export."
    }

    /**
     * The synchronous pre-gate the Home screen runs *before* opening the file
     * picker, so a workspace that cannot be exported never asks the user to
     * choose a destination first. Checks the same conditions [start] enforces
     * (which stays authoritative); the reason constants are shared.
     */
    fun quickBlockReason(workspaceId: String, status: WorkspaceStatus): String? {
        if (_state.value is ExportFlowState.Active) return REASON_EXPORT_ALREADY_RUNNING
        statusBlockReason(status)?.let { return it }
        if (lifecycleManager.activeWorkspaceId.value == workspaceId) return REASON_WORKSPACE_RUNNING
        if (!fileManager.hasValidRootfs(workspaceId)) {
            return "This workspace has no installed rootfs to export."
        }
        return null
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readEmbeddedConfig(workspaceId: String): ByteArray? {
        val config = File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml")
        if (!config.isFile) return null
        val bytes = try {
            config.readBytes()
        } catch (e: Exception) {
            Logger.w(TAG, "export($workspaceId): config.yaml unreadable, exporting without it: ${e.message}")
            return null
        }
        if (bytes.isEmpty() || bytes.size > WorkspaceConfigParser.MAX_CONFIG_BYTES) {
            Logger.w(TAG, "export($workspaceId): config.yaml is ${bytes.size} bytes — outside the embeddable range")
            return null
        }
        return bytes
    }

    private fun tryTakePersistableUriPermission(uri: Uri) {
        try {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // The grant still covers this process's lifetime, which is all a
            // single export needs — same reasoning as the installer's read-side
            // take.
            Logger.w(TAG, "URI does not support a persistable write grant: $uri")
        }
    }

    /**
     * Removes a partial/failed output document. Best effort across the two
     * delete routes a document Uri can have; returns whether the file is
     * *believed* gone, which the failure state surfaces honestly when false.
     */
    private fun deletePartialOutput(uri: Uri): Boolean {
        val deleted = try {
            DocumentsContract.deleteDocument(application.contentResolver, uri)
        } catch (e: Exception) {
            Logger.w(TAG, "DocumentsContract.deleteDocument($uri) failed: ${e.message}")
            try {
                application.contentResolver.delete(uri, null, null) > 0
            } catch (e2: Exception) {
                Logger.w(TAG, "contentResolver.delete($uri) failed: ${e2.message}")
                false
            }
        }
        if (deleted) Logger.i(TAG, "Partial export output removed: $uri")
        return deleted
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun stepOf(phase: ExportPhase): ExportStep = when (phase) {
        ExportPhase.PREPARING -> ExportStep.PREPARING
        ExportPhase.PACKING -> ExportStep.PACKING
        ExportPhase.FINALIZING -> ExportStep.FINALIZING
        ExportPhase.VERIFYING -> ExportStep.VERIFYING
    }

    /**
     * Maps a real phase progress onto the UI bar. The installer moved to a
     * shared weighted monotonic tracker (StagedProgressTracker, issue #5);
     * the export path stays on its fixed bands — packing owns 5%→90%,
     * verification owns 93%→99%, and the fixed stages pin the points
     * between, each phase emitted once so no band can repeat. `null` stays
     * null (indeterminate rail).
     */
    private fun uiFraction(progress: ExportProgress): Float? = when (progress.phase) {
        ExportPhase.PREPARING -> 0.02f
        ExportPhase.PACKING -> progress.fraction?.let { 0.05f + 0.85f * it.coerceIn(0f, 1f) }
        ExportPhase.FINALIZING -> 0.92f
        ExportPhase.VERIFYING -> progress.fraction?.let { 0.93f + 0.06f * it.coerceIn(0f, 1f) }
    }

    /** Internal control-flow exception: a validated refusal, not a crash. */
    private class ExportFailure(val userReason: String) : Exception(userReason)

    companion object {
        private const val TAG = "ExportCoordinator"

        /** Shared with the Home screen's pre-gate so both gates say the same words. */
        const val REASON_WORKSPACE_RUNNING =
            "This workspace is running. Stop it before exporting its RootFS."
        const val REASON_EXPORT_ALREADY_RUNNING =
            "An export is already in progress. Wait for it to finish or cancel it."
    }
}

/** The export flow state machine, driven by [ExportCoordinator]. */
sealed interface ExportFlowState {
    data object Idle : ExportFlowState

    /** One export in flight — packing/verifying [workspaceId]'s rootfs. */
    data class Active(
        val workspaceId: String,
        val workspaceName: String,
        val step: ExportStep,
        /**
         * Real 0f..1f bar position when measurable, null = indeterminate
         * (the installer's progress convention).
         */
        val progress: Float?,
        val currentEntryName: String? = null,
    ) : ExportFlowState

    /**
     * The archive was written AND verified. [fileName] is the provider's
     * display name for the file the user picked; [skippedEntries] is non-empty
     * when some guest files could not be represented in the backup.
     */
    data class Completed(
        val workspaceId: String,
        val workspaceName: String,
        val fileName: String,
        val outputBytes: Long,
        val skippedEntries: List<String> = emptyList(),
        /** Credential paths intentionally left out of the archive (finding 022). */
        val removedCredentials: List<String> = emptyList(),
    ) : ExportFlowState

    /**
     * The export did not produce a verified archive. [reason] is
     * user-presentable; [partialFileCleanupFailed] is true when the partial
     * output document could not be removed and the user should delete it.
     */
    data class Failed(
        val reason: String,
        val workspaceId: String? = null,
        val workspaceName: String? = null,
        val partialFileCleanupFailed: Boolean = false,
    ) : ExportFlowState
}

/** Pipeline stage the UI renders a status text for. */
enum class ExportStep { PREPARING, PACKING, VERIFYING, FINALIZING }
