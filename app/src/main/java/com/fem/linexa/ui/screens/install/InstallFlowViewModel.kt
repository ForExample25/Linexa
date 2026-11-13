package com.fem.linexa.ui.screens.install

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.distro.DistroCatalog
import com.fem.linexa.service.installer.InstallCoordinator
import com.fem.linexa.service.installer.InstallFlowState
import com.fem.linexa.service.installer.InstallSelection
import com.fem.linexa.service.installer.InstallSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI/UX Phase 1 — the ONE install-flow ViewModel, replacing §1.88's
 * `AnalyzeViewModel` + `InstallConfirmationViewModel` + `InstallViewModel`.
 *
 * The three-stage split existed so each *screen* could read one state; Phase 1
 * puts the whole flow on one screen, so three ViewModels observing the same
 * singleton coordinator became three copies of the same observation with three
 * different, easy-to-desync cancellation policies. This is still a thin
 * observer — no installation logic moved here, [InstallCoordinator] remains the
 * single owner.
 *
 * Cancellation policy (now stated once instead of three times): leaving the
 * screen aborts a flow that is still `Preparing` **or** `Ready` — i.e. anything
 * the user has not committed. A committed flow (`Installing` onwards) is left
 * to finish, exactly as before.
 *
 * The `summarySnapshot` exists because `confirm()` flips `Ready → Installing`
 * synchronously (§1.88's guard against `onCleared` cancelling a just-committed
 * flow) and `Installing` carries no summary. On a single screen the Root
 * Filesystem card must keep showing its facts across that flip, so the summary
 * is pinned here rather than in composition — a screen-local `remember` would
 * be lost on rotation or on any recomposition-scope change, which is precisely
 * the class of bug §1.91 fixed once already.
 */
@HiltViewModel
class InstallFlowViewModel @Inject constructor(
    private val coordinator: InstallCoordinator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * What the user picked, in words, for the Root Filesystem card's header —
     * available immediately, before analysis has produced anything.
     */
    val sourceLabel: String?
    /** True when the source is a local archive the user picked (vs. a catalog distro). */
    val isLocalFile: Boolean
    /** The picked document's name, when the source is a local archive. */
    val sourceFileName: String?

    private val _summarySnapshot = MutableStateFlow<InstallSummary?>(null)

    val uiState: StateFlow<InstallFlowUiState> =
        combine(coordinator.state, _summarySnapshot) { state, snapshot ->
            InstallFlowUiState.from(state, snapshot)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = InstallFlowUiState.from(coordinator.state.value, _summarySnapshot.value)
        )

    init {
        val distroId = savedStateHandle.get<String>(ARG_DISTRO_ID)
        if (distroId != null) {
            val manifest = DistroCatalog.findById(distroId)
            isLocalFile = false
            sourceFileName = null
            sourceLabel = manifest?.let { InstallSourceLabel.forDistro(it.displayName, it.version) }
            if (manifest != null) {
                // 2026-09-01 gate: locked distros (every entry outside
                // Debian/Ubuntu for now) never reach the coordinator — the
                // UI hides them, and this guard covers direct navigation
                // (deep link / back-stack restore) landing on a locked id.
                if (manifest.enabled) {
                    coordinator.prepare(InstallSelection.Distro(manifest))
                } else {
                    Logger.w(TAG, "Distro '$distroId' is locked (not installable) — flow aborted")
                    _summarySnapshot.value = null
                }
            }
        } else {
            val uri = savedStateHandle.get<String>(ARG_URI)
            val name = savedStateHandle.get<String>(ARG_NAME)
            isLocalFile = true
            sourceFileName = name?.takeIf { it.isNotBlank() }
            sourceLabel = InstallSourceLabel.forFile(name)
            if (uri != null) {
                coordinator.prepare(
                    InstallSelection.File(
                        uri = Uri.parse(uri),
                        distroName = name ?: "workspace",
                        distroVersion = savedStateHandle.get<String>(ARG_VERSION) ?: "unknown"
                    )
                )
            }
        }
    }

    /**
     * Commits the install with the (possibly edited) workspace name, pinning the
     * analyzed summary first so the screen keeps its facts across the
     * synchronous `Ready → Installing` flip.
     */
    fun confirm(workspaceName: String) {
        (coordinator.state.value as? InstallFlowState.Ready)?.let { ready ->
            _summarySnapshot.value = ready.summary
        }
        coordinator.confirm(workspaceName)
    }

    fun cancel() = coordinator.cancel()

    override fun onCleared() {
        // Only an uncommitted flow is safe to abort — Installing onwards owns
        // real work (and, past the row creation, a real workspace record).
        when (coordinator.state.value) {
            is InstallFlowState.Preparing, is InstallFlowState.Ready -> coordinator.cancel()
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "InstallFlowViewModel"
        const val ARG_DISTRO_ID = "distroId"
        const val ARG_URI = "uri"
        const val ARG_NAME = "name"
        const val ARG_VERSION = "version"
    }
}
