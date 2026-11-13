package com.fem.linexa.ui.screens.install

import com.fem.linexa.service.installer.InstallFlowState
import com.fem.linexa.service.installer.InstallStep
import com.fem.linexa.service.installer.InstallSummary

/**
 * UI/UX Phase 1 — the single-screen install flow's presentation state.
 *
 * The installation *logic* still lives entirely in
 * [com.fem.linexa.service.installer.InstallCoordinator]; this is the pure
 * mapping from that state machine onto what the one screen renders. It exists
 * as a separate, Compose-free type for two reasons:
 *
 *  1. §1.88 split the flow across three nav destinations so each screen could
 *     read one state. Phase 1 collapses them into one screen, which therefore
 *     has to render *every* state — a mapping worth testing on the JVM rather
 *     than asserting by eye inside a composable.
 *  2. [InstallFlowState.Installing] deliberately carries no [InstallSummary]
 *     (the coordinator flips `Ready → Installing` synchronously at confirm,
 *     §1.88/§1.91). The facts the Root Filesystem card shows must survive that
 *     flip, so they come from a snapshot taken at confirm time. Making that
 *     an explicit parameter of a pure function is what makes "the card keeps
 *     its facts while installing" a testable property instead of a hope.
 */
enum class InstallFlowPhase {
    /** Nothing prepared (also the post-cancel state). */
    IDLE,

    /** Download/analysis running — facts not known yet. */
    ANALYZING,

    /** Analysis finished: facts + the confirm action are on screen. */
    CONFIRM,

    /** Committed: real extraction/fixup/finalize progress. */
    INSTALLING,

    /** Terminal success. */
    COMPLETED,

    /** Terminal failure. */
    FAILED
}

data class InstallFlowUiState(
    val phase: InstallFlowPhase,
    /**
     * The analyzed facts, when they are known. Null while [ANALYZING] (nothing
     * has been read yet) and — only after a process death mid-install — while
     * [INSTALLING], which is why the screen must render that card's absence
     * rather than assume it.
     */
    val summary: InstallSummary? = null,
    val step: InstallStep? = null,
    val progress: Float? = null,
    val currentEntryName: String? = null,
    val failureReason: String? = null,
    /**
     * Non-null when the failure happened *after* the workspace row was created:
     * a `Broken` record exists, so the screen hands off to the existing
     * workspace diagnostics route instead of showing the reason in place.
     */
    val failedWorkspaceId: String? = null,
    val completedWorkspaceId: String? = null
) {
    /** True while the flow can still be abandoned without losing committed work. */
    val cancellable: Boolean
        get() = phase == InstallFlowPhase.ANALYZING || phase == InstallFlowPhase.CONFIRM

    /** True when the failure has no workspace row and must be shown on this screen. */
    val showsFailureInPlace: Boolean
        get() = phase == InstallFlowPhase.FAILED && failedWorkspaceId == null

    companion object {
        fun from(state: InstallFlowState, snapshot: InstallSummary?): InstallFlowUiState =
            when (state) {
                is InstallFlowState.Idle -> InstallFlowUiState(InstallFlowPhase.IDLE)

                is InstallFlowState.Preparing -> InstallFlowUiState(
                    phase = InstallFlowPhase.ANALYZING,
                    step = state.step,
                    progress = state.progress,
                    currentEntryName = state.currentEntryName
                )

                is InstallFlowState.Ready -> InstallFlowUiState(
                    phase = InstallFlowPhase.CONFIRM,
                    // Straight from the live state, never the snapshot: this is
                    // the authoritative analysis result.
                    summary = state.summary
                )

                is InstallFlowState.Installing -> InstallFlowUiState(
                    phase = InstallFlowPhase.INSTALLING,
                    summary = snapshot,
                    step = state.step,
                    progress = state.progress,
                    currentEntryName = state.currentEntryName
                )

                is InstallFlowState.Completed -> InstallFlowUiState(
                    phase = InstallFlowPhase.COMPLETED,
                    summary = snapshot,
                    completedWorkspaceId = state.workspaceId
                )

                is InstallFlowState.Failed -> InstallFlowUiState(
                    phase = InstallFlowPhase.FAILED,
                    summary = snapshot,
                    failureReason = state.reason,
                    failedWorkspaceId = state.workspaceId
                )
            }
    }
}

/**
 * UI/UX Phase 1 — how the Root Filesystem card names its source *before*
 * analysis has produced any facts.
 *
 * Pure so it is JVM-testable, and deliberately returning null rather than a
 * hardcoded English fallback: the caller substitutes a string resource, so a
 * missing label is localized like everything else on the screen.
 */
object InstallSourceLabel {

    /** A catalog distro: display name plus its pinned version, when useful. */
    fun forDistro(displayName: String, version: String?): String? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        val v = version?.trim().orEmpty()
        return if (v.isEmpty() || name.contains(v, ignoreCase = true)) name else "$name $v"
    }

    /**
     * A user-picked archive. [derivedName] is what Home derived from the
     * document's display name (extension already stripped); blank/unknown
     * values yield null so the screen shows its own generic label.
     */
    fun forFile(derivedName: String?): String? {
        val name = derivedName?.trim().orEmpty()
        if (name.isEmpty() || name.equals("workspace", ignoreCase = true)) return null
        return name
    }
}
