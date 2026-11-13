package com.fem.linexa.ui.screens.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.data.resource.ProcessSignals
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.ui.misc.ProcessPolling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Sessions overview's task-manager card (App-grid redesign) — the
 * per-process half of the workspace's resource picture.
 *
 * The tree-level CPU/RAM numbers keep coming from the existing
 * [com.fem.linexa.ui.components.WorkspacePerformanceViewModel]; this VM owns
 * only what that one deliberately does not model: every workspace member,
 * including independently launched terminals and detached daemons
 * ([ResourceMonitor.snapshot]), and the per-row terminate action.
 *
 * Polling discipline is inherited from the performance VM's contract: the
 * sampler walks `/proc` and reads several files per process, so the loop runs
 * ONLY while the overview is on screen — [start] from the composable's
 * LaunchedEffect, [stop] from its DisposableEffect. A kill does not need the
 * loop; it patches the state directly (the next tick overwrites it anyway).
 */
@HiltViewModel
class SessionTaskManagerViewModel @Inject constructor(
    private val resourceMonitor: ResourceMonitor,
    private val processSignals: ProcessSignals
) : ViewModel() {

    data class State(
        val processes: List<WorkspaceProcessSample> = emptyList(),
        /** True while the first sample is still in flight. */
        val loading: Boolean = false,
        /** Set when the last read answered nothing at all (unreadable /proc). */
        val unavailable: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var currentPid: Int? = null

    /**
     * Begin polling the tree rooted at [rootPid]. Safe to call repeatedly with
     * the same pid (recomposition does not restart the series — the CPU
     * baselines survive); a new pid resets the list.
     */
    fun start(rootPid: Int) {
        if (rootPid <= 0) {
            stop()
            _state.value = State()
            return
        }
        if (currentPid == rootPid && pollJob?.isActive == true) return
        if (currentPid != rootPid) {
            _state.value = State(loading = true)
        }
        currentPid = rootPid
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val processes = resourceMonitor.snapshot(listOf(rootPid)).processes
                _state.update {
                    State(
                        processes = processes,
                        loading = false,
                        unavailable = processes.isEmpty()
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop polling (overview dismissed). The last reading stays for the return. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Sends SIGTERM to [pid] and reflects the intent in the list immediately —
     * the row is marked terminating rather than left looking alive until the
     * next tick proves otherwise. The workspace root is refused (its lifecycle
     * belongs to the session controls, not a process row).
     */
    fun terminate(sample: WorkspaceProcessSample) {
        if (sample.isRoot || currentPid == null) return
        if (!sameProcess(sample.pid, sample.startTimeTicks)) return
        if (!processSignals.terminate(sample.pid)) return
        _state.update { current ->
            current.copy(
                processes = current.processes.filterNot { it.pid == sample.pid }
            )
        }
    }

    /** Reject a recycled pid before delivering SIGTERM. */
    private fun sameProcess(pid: Int, startTimeTicks: Long): Boolean {
        if (startTimeTicks <= 0L) return true
        return runCatching {
            com.fem.linexa.domain.resource.ProcMetricParsers
                .pidStatSample(java.io.File("/proc/$pid/stat").readText())
                ?.startTimeTicks == startTimeTicks
        }.getOrDefault(false)
    }

    override fun onCleared() {
        pollJob?.cancel()
        Logger.d(TAG, "cleared")
        super.onCleared()
    }

    companion object {
        private const val TAG = "SessionTaskManagerVm"

        /**
         * 2 s — the performance VM's interval. The per-process read is one
         * `stat` + one memory read per member; at task-manager scale (a
         * workspace tree, not the whole device) that stays negligible.
         * Phase 08 (OPT-PERF-004): the one shared [ProcessPolling] constant.
         */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS
    }
}
