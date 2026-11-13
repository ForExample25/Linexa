package com.fem.linexa.ui.screens.workspace

import androidx.compose.runtime.Composable
import com.fem.linexa.data.Workspace
import com.fem.linexa.domain.workspace.WorkspaceReadiness

/**
 * §1.53: Error/diagnostic report screen — delegates to DiagnosticViewerScreen.
 *
 * This is the routing layer that decides what to show based on readiness:
 * - Installing → progress bar (in DiagnosticViewerScreen)
 * - Busy → spinner (in DiagnosticViewerScreen)
 * - Blocked → full diagnostic viewer (in DiagnosticViewerScreen)
 * - Ready → unreachable (never rendered here)
 *
 * §1.52 diagnostics are fully preserved — DiagnosticViewerScreen only adds
 * presentation and export capabilities. Every existing diagnostic section
 * remains intact.
 */
@Composable
fun BlockedWorkspaceScreen(
    workspace: Workspace,
    readiness: WorkspaceReadiness,
    canRetry: Boolean,
    diagnostics: String,
    logPath: String? = null,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    DiagnosticViewerScreen(
        workspace = workspace,
        readiness = readiness,
        canRetry = canRetry,
        diagnostics = diagnostics,
        logPath = logPath,
        onRetry = onRetry,
        onDelete = onDelete,
        onBack = onBack
    )
}
