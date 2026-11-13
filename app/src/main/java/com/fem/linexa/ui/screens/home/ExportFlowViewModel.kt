package com.fem.linexa.ui.screens.home

import androidx.lifecycle.ViewModel
import com.fem.linexa.data.Workspace
import com.fem.linexa.service.exporter.ExportCoordinator
import com.fem.linexa.service.exporter.ExportFlowState
import com.fem.linexa.service.exporter.ExportNames
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * § export — the Home screen's RootFS-export flow ViewModel.
 *
 * Deliberately its own [ViewModel] rather than a growth of [HomeViewModel]:
 * the Workspaces tab already hosts more than one (the management terminal's),
 * and the export flow is a self-contained state machine the screen only
 * forwards — the same thin-observer shape the install screen keeps towards
 * [com.fem.linexa.service.installer.InstallCoordinator]. Everything it
 * exposes forwards to [ExportCoordinator]; no export logic lives here.
 */
@HiltViewModel
class ExportFlowViewModel @Inject constructor(
    private val coordinator: ExportCoordinator
) : ViewModel() {

    /** The coordinator's state machine, observed by the export dialogs. */
    val state: StateFlow<ExportFlowState> = coordinator.state

    /**
     * The synchronous pre-gate for the card menu's Export entry: why this
     * workspace must not be exported right now, or null when the file picker
     * may open. Checks the coordinator's single-flight rule, the workspace
     * status, whether it is the one workspace the runtime is currently
     * serving, and that a rootfs actually exists — [ExportCoordinator.start]
     * re-validates everything authoritatively, this only spares the user a
     * destination pick that could never succeed.
     */
    fun blockReason(workspace: Workspace): String? =
        coordinator.quickBlockReason(workspace.id, workspace.status)

    /**
     * The suggested backup file name for [workspace] — distro, version and
     * architecture where the workspace knows them, plus a timestamp and id
     * suffix so successive exports never collide (see [ExportNames]).
     */
    fun suggestedFileName(workspace: Workspace): String =
        ExportNames.suggestedFileName(
            distroName = workspace.distroName ?: workspace.name,
            distroVersion = workspace.distroVersion,
            distroArch = workspace.distroArch,
            workspaceId = workspace.id
        )

    /** Starts the export into the SAF document the user just picked. */
    fun start(workspace: Workspace, uri: Uri, suggestedFileName: String) {
        coordinator.start(
            workspaceId = workspace.id,
            workspaceName = workspace.name,
            targetUri = uri,
            suggestedFileName = suggestedFileName,
        )
    }

    /** Cancels the running export; the partial output is removed by the coordinator. */
    fun cancel() {
        coordinator.cancel()
    }

    /** Clears a surfaced export completion/failure once the dialog has shown it. */
    fun consumeResult() {
        coordinator.consumeResult()
    }
}
