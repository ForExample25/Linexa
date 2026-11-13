package com.fem.linexa.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fem.linexa.R

/**
 * LinexaRuntimeStatus — the unified runtime state model for the entire app.
 *
 * Every screen renders the same runtime state in the same color + icon +
 * label — Home, Terminal, Desktop, Environment, Performance, Diagnostics.
 * No screen invents its own status meaning. This is the contract; every
 * status-aware screen MUST resolve its display through [presentation].
 *
 * The enum is pure Kotlin (no Compose dependency) so it can be produced by
 * ViewModels and tested on a plain JVM classpath. The (color, icon, label)
 * triple lives in [LinexaRuntimeStatusPresentation] inside the UI layer.
 *
 * Phase 17 of the Linexa UI/UX 2026 redesign.
 */
enum class LinexaRuntimeStatus {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
    DEGRADED,
    DISCONNECTED;

    /**
     * Whether this status represents an active (transition) state — the
     * UI shows a spinner / shimmer for these rather than a static pill.
     */
    val isTransient: Boolean
        get() = this == INSTALLING || this == STARTING || this == STOPPING

    /**
     * Whether this status is a steady state that the UI can show as a
     * stable pill (no spinner).
     */
    val isSteady: Boolean get() = !isTransient

    /**
     * Whether this status represents a problem the user should care about.
     */
    val isProblem: Boolean
        get() = this == ERROR || this == DEGRADED || this == DISCONNECTED

    /**
     * Whether the runtime is operational — the user can launch a terminal
     * or a desktop session.
     */
    val isOperational: Boolean
        get() = this == RUNNING || this == DEGRADED
}

/**
 * The visual triple for a [LinexaRuntimeStatus] — color, icon (vector
 * drawable resource), and label. Pulls colors from the [StatusXxx] palette
 * in Color.kt so the mapping is deterministic across screens.
 *
 * Use [LinexaRuntimeStatus.presentation] from inside a Composable; outside
 * composition, the [color] / [label] / [iconRes] values are static and
 * safe to read.
 */
data class LinexaRuntimeStatusPresentation(
    val status: LinexaRuntimeStatus,
    val color: Color,
    val containerColor: Color,
    val iconRes: Int,
    val label: String
)

/**
 * Resolve a [LinexaRuntimeStatus] to its visual presentation.
 *
 * Composable-only because the icon is loaded via [vectorResource]. Outside
 * composition (e.g. inside a ViewModel producing UI state), use the static
 * [colorFor] / [containerColorFor] / [iconResFor] / [labelFor] functions —
 * they return the same values without requiring Composable context.
 */
@Composable
fun LinexaRuntimeStatus.presentation(): LinexaRuntimeStatusPresentation {
    return LinexaRuntimeStatusPresentation(
        status = this,
        color = colorFor(),
        containerColor = containerColorFor(),
        iconRes = iconResFor(),
        label = labelFor(LocalContext.current)
    )
}

/**
 * Static color for a runtime status — no Composable context required.
 * Used by ViewModels producing UI state and by static defaults.
 */
fun LinexaRuntimeStatus.colorFor(): Color = when (this) {
    LinexaRuntimeStatus.NOT_INSTALLED -> StatusNotInstalled
    LinexaRuntimeStatus.INSTALLING -> StatusInstalling
    LinexaRuntimeStatus.INSTALLED -> StatusSuccess
    LinexaRuntimeStatus.STARTING -> StatusStarting
    LinexaRuntimeStatus.RUNNING -> StatusRunning
    LinexaRuntimeStatus.STOPPING -> StatusStopping
    LinexaRuntimeStatus.STOPPED -> StatusStopped
    LinexaRuntimeStatus.ERROR -> StatusError
    LinexaRuntimeStatus.DEGRADED -> StatusDegraded
    LinexaRuntimeStatus.DISCONNECTED -> StatusDisconnected
}

/**
 * Static container tint for a runtime status — paired with [colorFor].
 */
fun LinexaRuntimeStatus.containerColorFor(): Color = when (this) {
    LinexaRuntimeStatus.NOT_INSTALLED -> NotInstalledContainer
    LinexaRuntimeStatus.INSTALLING -> InstallingContainer
    LinexaRuntimeStatus.INSTALLED -> RunningContainer
    LinexaRuntimeStatus.STARTING -> StartingContainer
    LinexaRuntimeStatus.RUNNING -> RunningContainer
    LinexaRuntimeStatus.STOPPING -> StoppedContainer
    LinexaRuntimeStatus.STOPPED -> StoppedContainer
    LinexaRuntimeStatus.ERROR -> ErrorContainer
    LinexaRuntimeStatus.DEGRADED -> DegradedContainer
    LinexaRuntimeStatus.DISCONNECTED -> DisconnectedContainer
}

/**
 * Static icon resource for a runtime status.
 */
fun LinexaRuntimeStatus.iconResFor(): Int = when (this) {
    LinexaRuntimeStatus.NOT_INSTALLED -> R.drawable.ic_linexa_status_stopped
    LinexaRuntimeStatus.INSTALLING -> R.drawable.ic_linexa_status_warning
    LinexaRuntimeStatus.INSTALLED -> R.drawable.ic_linexa_status_running
    LinexaRuntimeStatus.STARTING -> R.drawable.ic_linexa_status_running
    LinexaRuntimeStatus.RUNNING -> R.drawable.ic_linexa_status_running
    LinexaRuntimeStatus.STOPPING -> R.drawable.ic_linexa_status_stopped
    LinexaRuntimeStatus.STOPPED -> R.drawable.ic_linexa_status_stopped
    LinexaRuntimeStatus.ERROR -> R.drawable.ic_linexa_status_error
    LinexaRuntimeStatus.DEGRADED -> R.drawable.ic_linexa_status_warning
    LinexaRuntimeStatus.DISCONNECTED -> R.drawable.ic_linexa_status_disconnected
}

/**
 * Localized label for a runtime status. Resolves through the Android
 * string resource table so labels localize correctly.
 */
fun LinexaRuntimeStatus.labelFor(context: android.content.Context): String {
    val res = when (this) {
        LinexaRuntimeStatus.NOT_INSTALLED -> R.string.linexa_status_not_installed
        LinexaRuntimeStatus.INSTALLING -> R.string.linexa_status_installing
        LinexaRuntimeStatus.INSTALLED -> R.string.linexa_status_installed
        LinexaRuntimeStatus.STARTING -> R.string.linexa_status_starting
        LinexaRuntimeStatus.RUNNING -> R.string.linexa_status_running
        LinexaRuntimeStatus.STOPPING -> R.string.linexa_status_stopping
        LinexaRuntimeStatus.STOPPED -> R.string.linexa_status_stopped
        LinexaRuntimeStatus.ERROR -> R.string.linexa_status_error
        LinexaRuntimeStatus.DEGRADED -> R.string.linexa_status_degraded
        LinexaRuntimeStatus.DISCONNECTED -> R.string.linexa_status_disconnected
    }
    return context.getString(res)
}
