package com.fem.linexa.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceAggregator
import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.ui.misc.ProcessPolling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the workspace performance panel (UI/UX Phase 5B).
 *
 * Sampling is **pull-based on a single timer**, and the timer only runs while a
 * workspace is actually running *and* the panel is on screen. The plan requires a
 * controlled, low refresh rate that avoids unnecessary CPU and battery use, and
 * for a `/proc` sampler that is not a detail: each tick enumerates `/proc` and
 * reads several files per process in the tree, so a loop left running behind a
 * closed screen is real, permanent work.
 *
 * The pre-Phase-5B version had exactly that defect. Its poll loop was started
 * from a `LaunchedEffect(pid)` and cancelled only by [onCleared] or by an explicit
 * `stop()` that no caller made — so opening the workspace, switching to the
 * Features tab and leaving the app kept a 2-second `/proc` walk running for the
 * lifetime of the ViewModel. [pause] exists so the composable can stop it from a
 * `DisposableEffect`, which is the only hook that fires when the panel leaves
 * composition.
 *
 * State is a [WorkspacePerformanceState] rather than pre-formatted strings: the
 * text and bar rules live in the pure [WorkspacePerformanceContent], which the
 * screen builds. That keeps every "is this value knowable" decision testable.
 */
@HiltViewModel
class WorkspacePerformanceViewModel @Inject constructor(
    private val resourceMonitor: ResourceMonitor,
    private val fileManager: WorkspaceFileManager
) : ViewModel() {

    /**
     * @property sample newest reading, or null before the first tick completes.
     * @property cpuHistory bounded rolling CPU history for the sparkline, oldest
     *   first. Only *known* CPU readings are appended — an unknown reading is
     *   skipped rather than pushed as zero, so the line never shows a dip the
     *   workspace did not have.
     * @property memoryHistory same, for memory fraction.
     */
    data class WorkspacePerformanceState(
        val sample: WorkspaceResourceSample? = null,
        val storage: WorkspaceStorage? = null,
        val cpuHistory: List<Float> = emptyList(),
        val memoryHistory: List<Float> = emptyList()
    )

    private val _state = MutableStateFlow(WorkspacePerformanceState())
    val state: StateFlow<WorkspacePerformanceState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var currentPid: Int? = null
    private var currentWorkspaceId: String? = null

    /**
     * Begin (or continue) sampling the workspace [workspaceId] whose process tree
     * is rooted at [pid].
     *
     * A null [pid] means no live session: the poll stops, the process metrics are
     * dropped, and only storage is refreshed. Dropping the sample is deliberate —
     * keeping the last reading would leave a plausible CPU figure on screen for a
     * workspace that is no longer running.
     *
     * Safe to call repeatedly. An already-running poll for the same pid is left
     * alone, so recomposition does not restart the series (and therefore does not
     * throw away the delta baseline, which would make the next reading unknown).
     */
    fun start(workspaceId: String, pid: Int?) {
        val workspaceChanged = currentWorkspaceId != workspaceId
        currentWorkspaceId = workspaceId
        if (workspaceChanged) {
            _state.value = WorkspacePerformanceState()
        }
        refreshStorage(workspaceId)

        if (pid == null) {
            currentPid?.let { resourceMonitor.forget(it) }
            currentPid = null
            pollJob?.cancel()
            pollJob = null
            _state.update { it.copy(sample = null, cpuHistory = emptyList(), memoryHistory = emptyList()) }
            return
        }

        if (currentPid == pid && pollJob?.isActive == true) return
        if (currentPid != pid) {
            // New session: the old pid's delta baseline is meaningless and its
            // history belongs to a process that no longer exists.
            currentPid?.let { resourceMonitor.forget(it) }
            _state.update { it.copy(cpuHistory = emptyList(), memoryHistory = emptyList()) }
        }
        currentPid = pid
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val snapshot = resourceMonitor.snapshot(listOf(pid))
                val sample = if (snapshot.readable) {
                    WorkspaceAggregator.workspaceSample(
                        processes = snapshot.processes,
                        cores = snapshot.topology.capacityCores,
                        deviceTotalKb = snapshot.deviceTotalKb
                    )
                } else {
                    WorkspaceResourceSample.unavailable(snapshot.topology.capacityCores)
                }
                _state.update { current ->
                    current.copy(
                        sample = sample,
                        cpuHistory = append(current.cpuHistory, sample.cpuFraction),
                        memoryHistory = append(current.memoryHistory, sample.memoryFraction)
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop sampling but keep the last reading.
     *
     * Called when the panel leaves composition. The reading is kept so returning
     * to the tab shows the last known values immediately instead of dashes, while
     * no work happens in between. The delta baseline is kept too, which is why
     * [start] can resume the same pid without losing the next CPU value.
     */
    fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Re-read this workspace's disk usage.
     *
     * Off the main thread: [WorkspaceFileManager.getWorkspaceStorage] can walk a
     * multi-GB rootfs on a cache miss. Storage is read once per [start] rather
     * than on the sampling tick — a rootfs walk every 2 seconds would be the most
     * expensive thing in the app, and workspace size does not change on that
     * timescale outside an install.
     */
    private fun refreshStorage(workspaceId: String) {
        viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) {
                runCatching { fileManager.getWorkspaceStorage(workspaceId) }.getOrNull()
            }
            if (storage != null && currentWorkspaceId == workspaceId) {
                _state.update { it.copy(storage = storage) }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        currentPid?.let { resourceMonitor.forget(it) }
        super.onCleared()
    }

    /**
     * Append [value] to a bounded history, skipping unknown readings.
     *
     * A null reading is *not* recorded as `0f`: the sparkline would then show a
     * drop to zero for a value that was simply unavailable — the same confusion
     * between "unknown" and "idle" that [WorkspacePerformanceContent] guards in
     * the text.
     */
    private fun append(history: List<Float>, value: Float?): List<Float> {
        if (value == null) return history
        return (history + value).takeLast(HISTORY_SIZE)
    }

    companion object {
        /**
         * 2 s. Fast enough to feel live, slow enough that the `/proc` walk is
         * negligible; also the interval the previous implementation used, so this
         * is not a behaviour change being smuggled in. Phase 08 (OPT-PERF-004):
         * the one shared [ProcessPolling] constant.
         */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS

        /** ~80 s of history at [POLL_INTERVAL_MS]. Bounded so the path never grows. */
        const val HISTORY_SIZE = 40
    }
}
