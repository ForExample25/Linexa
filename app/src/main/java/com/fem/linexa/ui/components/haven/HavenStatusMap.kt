package com.fem.linexa.ui.components.haven

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.fem.linexa.R
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.ui.components.DistroIcons
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * Maps a workspace's persisted + runtime state onto the Haven status language
 * (dot color + one quiet word, concept `.st` classes: run/ready/err/warn/stop).
 */
data class HavenStatusPresentation(
    val color: Color,
    val label: String,
    val pulsing: Boolean = false
)

@Composable
fun workspaceStatusPresentation(
    status: WorkspaceStatus,
    isRunning: Boolean,
    runtimeState: RuntimeState? = null
): HavenStatusPresentation {
    val h = HavenTheme.colors
    return when {
        runtimeState is RuntimeState.Running ->
            HavenStatusPresentation(h.primary, stringResource(R.string.haven_status_running), pulsing = false)
        runtimeState is RuntimeState.Starting ->
            HavenStatusPresentation(h.blue, stringResource(R.string.haven_status_starting))
        runtimeState is RuntimeState.Error ->
            HavenStatusPresentation(h.red, stringResource(R.string.haven_status_error))
        isRunning ->
            HavenStatusPresentation(h.primary, stringResource(R.string.haven_status_running))
        status is WorkspaceStatus.Importing ->
            HavenStatusPresentation(h.amber, stringResource(R.string.haven_status_installing))
        status is WorkspaceStatus.Repairing ->
            HavenStatusPresentation(h.amber, stringResource(R.string.haven_status_repairing))
        status is WorkspaceStatus.Deleting ->
            HavenStatusPresentation(h.amber, stringResource(R.string.haven_status_stopping))
        status is WorkspaceStatus.Broken ->
            HavenStatusPresentation(h.red, stringResource(R.string.haven_status_error))
        status is WorkspaceStatus.Created ->
            HavenStatusPresentation(h.muted, stringResource(R.string.haven_status_created))
        // Ready / Installed
        else -> HavenStatusPresentation(h.blue, stringResource(R.string.haven_status_ready))
    }
}

/**
 * Distro brand colors for the letter-tile fallback — resolved through the
 * central [com.fem.linexa.ui.components.DistroIcons] registry (with its
 * aliases: "arch"/"archlinuxarm"/"kali"/… all resolve), so a distro's tile
 * color always matches the brand color behind its official logo. Unknown
 * codes keep the fedora-blue fallback the palette has always had.
 */
fun havenDistroColor(distroCode: String?): Color =
    DistroIcons.visualFor(distroCode)?.brandColor
        ?: Color(0xFF3C6EB4)
