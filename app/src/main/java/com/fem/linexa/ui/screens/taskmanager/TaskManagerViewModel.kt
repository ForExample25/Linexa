package com.fem.linexa.ui.screens.taskmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.i18n.linexaString
import com.fem.linexa.data.resource.AppThreadCpuSnapshot
import com.fem.linexa.data.resource.HostResourceSampler
import com.fem.linexa.data.resource.ProcessSignals
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceAggregator
import com.fem.linexa.domain.resource.WorkspaceProcessSample
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The dedicated Task Manager screen's ViewModel (Task Manager redesign).
 *
 * ## The three scopes the screen must keep apart
 *
 *  1. **Device** — total/available RAM (ActivityManager.MemoryInfo) and
 *     overall CPU (shared /proc/stat deltas). These describe the PHONE. A
 *     5 MB workspace reading is never presented against them.
 *  2. **Linexa app** — this process's own PSS (Debug.MemoryInfo) and CPU
 *     share (the app's pid delta from the same /proc walk the workspace
 *     sampler uses).
 *  3. **Active Linux workspace** — the proot tree's CPU/RAM and every member,
 *     including independent terminals and detached daemons, all from one
 *     [ResourceMonitor.snapshot].
 *
 * All three are MEASURED values; every field is nullable and a null is
 * rendered as unknown, never as zero.
 *
 * ## Lifecycle
 *
 * Polling runs ONLY while [start] has been called and stops at [stop] — the
 * screen calls them from LaunchedEffect/DisposableEffect, so a closed screen
 * does no /proc work (the same discipline WorkspacePerformanceViewModel
 * established). 2 s interval, matching every other sampler in the app.
 */
@HiltViewModel
class TaskManagerViewModel @Inject constructor(
    application: Application,
    private val resourceMonitor: ResourceMonitor,
    private val processSignals: ProcessSignals,
    private val hostSampler: HostResourceSampler,
    private val workspaceFileManager: WorkspaceFileManager
) : AndroidViewModel(application) {

    data class State(
        val deviceMemory: HostResourceSampler.DeviceMemory? = null,
        val appMemory: HostResourceSampler.AppMemory? = null,
        val deviceCpu: HostResourceSampler.DeviceCpu? = null,
        val workspaceSample: WorkspaceResourceSample? = null,
        val appCpuPercent: Float? = null,
        /** Thread detail for this app process; null until the first tick. */
        val appThreads: AppThreadCpuSnapshot? = null,
        val storage: WorkspaceStorage? = null,
        val processes: List<WorkspaceProcessSample> = emptyList(),
        val loading: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var currentPid: Int? = null
    private var currentWorkspaceId: String? = null

    fun start(workspaceId: String, rootPid: Int?) {
        if (currentWorkspaceId != workspaceId) {
            _state.value = State()
            currentWorkspaceId = workspaceId
        }
        refreshStorage(workspaceId)
        if (rootPid == null || rootPid <= 0) {
            currentPid = null
            pollJob?.cancel()
            pollJob = null
            _state.value = _state.value.copy(processes = emptyList(), workspaceSample = null)
            return
        }
        if (currentPid == rootPid && pollJob?.isActive == true) return
        currentPid = rootPid
        pollJob?.cancel()
        _state.value = _state.value.copy(loading = true)
        pollJob = viewModelScope.launch {
            while (true) {
                tick(rootPid)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun tick(rootPid: Int) {
        val snapshot = resourceMonitor.snapshot(listOf(rootPid))
        val deviceMemory = withContext(Dispatchers.IO) { hostSampler.deviceMemory() }
        val appMemory = withContext(Dispatchers.IO) { hostSampler.appMemory() }
        val deviceCpu = withContext(Dispatchers.IO) { hostSampler.deviceCpu() }
        val appCpu = withContext(Dispatchers.IO) { hostSampler.appCpuPercent() }
        val appThreads = withContext(Dispatchers.IO) { hostSampler.appThreads() }
        val workspaceSample = if (snapshot.readable) {
            WorkspaceAggregator.workspaceSample(
                processes = snapshot.processes,
                cores = snapshot.topology.capacityCores,
                deviceTotalKb = snapshot.deviceTotalKb
                    ?: deviceMemory?.totalBytes?.div(1024L)
            )
        } else {
            WorkspaceResourceSample.unavailable(snapshot.topology.capacityCores)
        }
        _state.value = _state.value.copy(
            deviceMemory = deviceMemory,
            appMemory = appMemory,
            deviceCpu = deviceCpu,
            appCpuPercent = appCpu,
            appThreads = appThreads,
            workspaceSample = workspaceSample,
            processes = snapshot.processes,
            loading = false
        )
    }

    private fun refreshStorage(workspaceId: String) {
        viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) {
                runCatching { workspaceFileManager.getWorkspaceStorage(workspaceId) }.getOrNull()
            }
            if (currentWorkspaceId == workspaceId) {
                _state.value = _state.value.copy(storage = storage)
            }
        }
    }

    /**
     * Ends [sample]'s process: SIGTERM, then a bounded verification re-read —
     * the row is only reported as ended once the pid is really gone (or has
     * become a zombie). The workspace root is refused (its lifecycle belongs
     * to the session controls); the app's own pid can never appear in the
     * workspace tree by construction.
     */
    fun terminate(sample: WorkspaceProcessSample, onResult: (Boolean, String?) -> Unit) {
        if (sample.isRoot) {
            onResult(false, getApplication<Application>().linexaString(com.fem.linexa.R.string.tm_root_protected))
            return
        }
        viewModelScope.launch {
            if (!sameProcess(sample.pid, sample.startTimeTicks)) {
                onResult(false, "process changed before the signal was sent")
                return@launch
            }
            val signaled = processSignals.terminate(sample.pid)
            // Bounded verification: re-read /proc up to 3×500ms.
            var gone = false
            repeat(3) {
                delay(500)
                gone = !java.io.File("/proc/${sample.pid}").exists() || isZombie(sample.pid)
                if (gone) return@repeat
            }
            if (gone) {
                _state.value = _state.value.copy(
                    processes = _state.value.processes.filterNot { it.pid == sample.pid }
                )
                onResult(true, null)
            } else {
                val reason = if (!signaled) "signal was not delivered" else "process is still running"
                onResult(false, reason)
            }
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

    private fun isZombie(pid: Int): Boolean = runCatching {
        val stat = java.io.File("/proc/$pid/stat").readText()
        val idx = stat.lastIndexOf(')')
        idx in 0 until stat.length - 2 && stat[idx + 2] == 'Z'
    }.getOrDefault(false)

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** 2 s — the interval every other sampler in this app uses. Phase 08 (OPT-PERF-004): the one shared [ProcessPolling] constant. */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS
    }
}
