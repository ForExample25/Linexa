package com.fem.linexa.ui.screens.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.i18n.linexaString
import com.fem.linexa.R
import com.fem.linexa.data.resource.HostResourceSampler
import com.fem.linexa.data.resource.ProcessSignals
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.RootTreePartition
import com.fem.linexa.domain.resource.WorkspaceAggregator
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.domain.runtime.TerminalNumbering
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.TerminalSession
import com.fem.linexa.ui.misc.ProcessPolling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the workspace **Sessions** segment: the real terminal sessions of one
 * workspace, their measured resource use, and the task-manager controls that
 * used to live on a separate screen. The segment is about *this workspace*; the
 * full device / Linexa-app dashboard lives on the global Activity tab, and
 * storage lives in the File Manager — neither is sampled here.
 *
 * ## Honesty rules (same contract as the performance panel)
 *
 *  - Sessions come from [RuntimeBinder.terminalSessions] — actual runtime
 *    records, never a synthesized list.
 *  - CPU/RAM per session come from the same `/proc` sampler the rest of the app
 *    uses ([ResourceMonitor.processes]); unknown values stay null and render as
 *    a dash.
 *  - The workspace total is the union of every *live* session's process tree plus
 *    the workspace root tree, deduplicated by pid ([WorkspaceAggregator] — the
 *    same arithmetic the Activity tab uses). CPU shares are additive because each
 *    is normalised by core count; memory is additive only when every contributing
 *    reading is `Pss`, and the aggregate is marked approximate otherwise.
 *  - Ended sessions are never sampled: their pty pid is dead and could be reused.
 *  - Device and Linexa-app scopes are measured separately ([HostResourceSampler])
 *    and are never presented as workspace numbers.
 *
 * ## Lifecycle
 *
 * [start] is called from the segment's LaunchedEffect and [pause] from its
 * DisposableEffect. A segment that is not visible does no `/proc` work. The
 * session record collector is stopped with the poll loop for the same reason.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    application: Application,
    private val resourceMonitor: ResourceMonitor,
    private val processSignals: ProcessSignals,
    private val hostSampler: HostResourceSampler
) : AndroidViewModel(application) {

    enum class SessionKind(val keyword: String) {
        TERMINAL("terminal"),
        SHELL("shell"),
        CLAUDE("claude"),
        PYTHON("python"),
        NODE("node"),
        GIT("git"),
        PACKAGE("package")
    }

    enum class SessionFilter { ALL, RUNNING, ENDED }

    /** [CPU] and [RAM] order by the *measured* value, unknown last. */
    enum class SessionSort { NEWEST, OLDEST, CPU, RAM }

    data class SessionUi(
        val terminalId: String,
        /** Visible compact label (T1..TN, gap-free); [terminalId] is the internal identity. */
        val label: String,
        val kind: SessionKind,
        val state: TerminalState,
        val createdAt: Instant,
        /** When this UI first observed the session not running; freezes its duration. */
        val endedAtMillis: Long?,
        val pid: Int?,
        val cpuPercentOfDevice: Float?,
        val memKb: Long?,
        val memoryIsProportional: Boolean,
        val processCount: Int,
        val processes: List<WorkspaceProcessSample>
    ) {
        val isActive: Boolean
            get() = state is TerminalState.Running || state is TerminalState.Starting

        /** Running time; stops counting at the moment the session ended. */
        fun elapsedMillis(nowMillis: Long): Long =
            ((endedAtMillis ?: nowMillis) - createdAt.toEpochMilli()).coerceAtLeast(0L)

        fun withoutProcess(pid: Int): SessionUi = recompute(processes.filterNot { it.pid == pid })

        fun recompute(next: List<WorkspaceProcessSample> = processes): SessionUi {
            val total = WorkspaceAggregator.rollup(next)
            return copy(
                kind = inferKind(next),
                cpuPercentOfDevice = total.cpuPercentOfDevice,
                memKb = total.memoryKb,
                memoryIsProportional = total.memoryIsProportional,
                processCount = total.processCount,
                processes = next
            )
        }
    }

    data class State(
        val sessions: List<SessionUi> = emptyList(),
        val processes: List<WorkspaceProcessSample> = emptyList(),
        val protectedPids: Set<Int> = emptySet(),
        val workspaceSample: WorkspaceResourceSample? = null,
        val deviceMemory: HostResourceSampler.DeviceMemory? = null,
        val appMemory: HostResourceSampler.AppMemory? = null,
        val deviceCpu: HostResourceSampler.DeviceCpu? = null,
        val appCpuPercent: Float? = null,
        val nowMillis: Long = System.currentTimeMillis(),
        val loading: Boolean = false,
        val query: String = "",
        val filter: SessionFilter = SessionFilter.ALL,
        val sort: SessionSort = SessionSort.NEWEST,
        val ending: Set<String> = emptySet(),
        val restarting: Set<String> = emptySet(),
        val message: String? = null,
        val workspaceRunning: Boolean = false
    ) {
        val runningCount: Int get() = sessions.count { it.isActive }
        val endedCount: Int get() = sessions.count { !it.isActive }

        /** A sort by CPU/RAM is only offered when at least one session has that reading. */
        val canSortByCpu: Boolean get() = sessions.any { it.cpuPercentOfDevice != null }
        val canSortByRam: Boolean get() = sessions.any { it.memKb != null }

        /** Search / filter / sort applied to the real session records. */
        val visibleSessions: List<SessionUi>
            get() {
                val needle = query.trim()
                val filtered = sessions.filter { session ->
                    val matchesFilter = when (filter) {
                        SessionFilter.ALL -> true
                        SessionFilter.RUNNING -> session.isActive
                        SessionFilter.ENDED -> !session.isActive
                    }
                    matchesFilter && (needle.isEmpty() || session.matches(needle))
                }
                return when (sort) {
                    SessionSort.NEWEST -> filtered.sortedByDescending { it.createdAt }
                    SessionSort.OLDEST -> filtered.sortedBy { it.createdAt }
                    SessionSort.CPU -> filtered.sortedWith(
                        compareByDescending<SessionUi> { it.cpuPercentOfDevice ?: -1f }
                            .thenByDescending { it.createdAt }
                    )
                    SessionSort.RAM -> filtered.sortedWith(
                        compareByDescending<SessionUi> { it.memKb ?: -1L }
                            .thenByDescending { it.createdAt }
                    )
                }
            }

        private fun SessionUi.matches(needle: String): Boolean =
            label.contains(needle, ignoreCase = true) ||
                terminalId.contains(needle, ignoreCase = true) ||
                kind.keyword.contains(needle, ignoreCase = true) ||
                (pid?.toString()?.contains(needle) == true) ||
                processes.any { it.name.contains(needle, ignoreCase = true) }
    }

    sealed interface Event {
        data class OpenTerminal(val terminalId: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var collectJob: Job? = null
    private var currentWorkspaceId: String? = null
    private var currentRootPid: Int? = null
    private var attachedBinder: RuntimeBinder? = null
    private var terminalSnapshot: Map<String, TerminalSession> = emptyMap()

    /** terminalId → first moment this UI saw it not running. Frozen duration source. */
    private val endedAt = HashMap<String, Long>()

    /** Serialises ticks: an overlapping manual refresh must not shrink a CPU delta window. */
    private val tickMutex = Mutex()
    private var lastTickAtMs = 0L

    /** Root pids sampled last tick, so delta state for vanished trees can be dropped. */
    private var sampledRoots: Set<Int> = emptySet()

    fun start(
        workspaceId: String,
        binder: RuntimeBinder?,
        rootPid: Int?,
        workspaceRunning: Boolean
    ) {
        val workspaceChanged = currentWorkspaceId != workspaceId
        currentWorkspaceId = workspaceId
        currentRootPid = rootPid
        _state.update {
            it.copy(
                workspaceRunning = workspaceRunning,
                loading = workspaceRunning && it.workspaceSample == null
            )
        }
        if (workspaceChanged) {
            terminalSnapshot = emptyMap()
            endedAt.clear()
            forgetSampledRoots()
            _state.value = State(
                workspaceRunning = workspaceRunning,
                loading = workspaceRunning
            )
        }

        if (binder == null) {
            collectJob?.cancel()
            collectJob = null
            attachedBinder = null
            terminalSnapshot = emptyMap()
            _state.update { it.copy(sessions = emptyList(), processes = emptyList(), protectedPids = emptySet()) }
            stopPolling()
            forgetSampledRoots()
            return
        }

        if (binder !== attachedBinder || collectJob?.isActive != true) {
            attachedBinder = binder
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                binder.terminalSessions.collect { all ->
                    terminalSnapshot = all[workspaceId].orEmpty()
                    _state.update { current ->
                        current.copy(sessions = buildFromRecords(terminalSnapshot, current.sessions))
                    }
                }
            }
        }

        if (!workspaceRunning && terminalSnapshot.isEmpty()) {
            stopPolling()
            _state.update {
                it.copy(
                    sessions = emptyList(),
                    processes = emptyList(),
                    protectedPids = emptySet(),
                    workspaceSample = null,
                    loading = false
                )
            }
            return
        }

        if (pollJob?.isActive == true) return
        _state.update { it.copy(loading = true) }
        pollJob = viewModelScope.launch {
            while (true) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun pause() {
        stopPolling()
        forgetSampledRoots()
        collectJob?.cancel()
        collectJob = null
        attachedBinder = null
    }

    fun refreshNow() {
        if (pollJob?.isActive != true) {
            if (currentWorkspaceId != null) start(
                workspaceId = currentWorkspaceId!!,
                binder = attachedBinder,
                rootPid = currentRootPid,
                workspaceRunning = _state.value.workspaceRunning
            )
            return
        }
        viewModelScope.launch { tick() }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setFilter(value: SessionFilter) = _state.update { it.copy(filter = value) }

    fun setSort(value: SessionSort) = _state.update { it.copy(sort = value) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun openSession(session: SessionUi) {
        viewModelScope.launch { _events.emit(Event.OpenTerminal(session.terminalId)) }
    }

    fun startTerminal() {
        val workspaceId = currentWorkspaceId ?: return
        val binder = attachedBinder ?: return
        viewModelScope.launch {
            runCatching { binder.openTerminal(workspaceId) }
                .onSuccess { id ->
                    _events.emit(Event.OpenTerminal(id))
                    refreshNow()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(message = app().linexaString(R.string.sessions_open_failed, e.message ?: ""))
                    }
                }
        }
    }

    fun endSession(session: SessionUi) {
        val workspaceId = currentWorkspaceId ?: return
        val binder = attachedBinder ?: return
        if (session.terminalId in _state.value.ending) return
        _state.update {
            it.copy(ending = it.ending + session.terminalId, message = null)
        }
        viewModelScope.launch {
            val replacement = runCatching { binder.closeTerminal(workspaceId, session.terminalId) }
                .onFailure { e ->
                    _state.update {
                        it.copy(message = app().linexaString(R.string.sessions_end_failed, e.message ?: ""))
                    }
                }
                .getOrNull()
            val gone = awaitPtyGone(binder, workspaceId, session.terminalId)
            _state.update { current ->
                current.copy(
                    ending = current.ending - session.terminalId,
                    message = when {
                        !gone -> app().linexaString(R.string.sessions_end_not_verified)
                        replacement != null -> app().linexaString(R.string.sessions_end_replaced, replacement)
                        else -> app().linexaString(R.string.sessions_end_ok, session.terminalId)
                    }
                )
            }
            refreshNow()
        }
    }

    fun restartSession(session: SessionUi) {
        val workspaceId = currentWorkspaceId ?: return
        val binder = attachedBinder ?: return
        if (session.terminalId in _state.value.restarting) return
        _state.update {
            it.copy(restarting = it.restarting + session.terminalId, message = null)
        }
        viewModelScope.launch {
            val newId = runCatching { binder.openTerminal(workspaceId) }
                .onFailure { e ->
                    _state.update {
                        it.copy(message = app().linexaString(R.string.sessions_restart_failed, e.message ?: ""))
                    }
                }
                .getOrNull()
            if (newId != null) {
                runCatching { binder.closeTerminal(workspaceId, session.terminalId) }
                val newPty = binder.getPtySession(workspaceId, newId)
                _state.update { current ->
                    current.copy(
                        restarting = current.restarting - session.terminalId,
                        message = if (newPty != null && newPty.isAlive) {
                            app().linexaString(R.string.sessions_restart_ok, session.terminalId, newId)
                        } else {
                            app().linexaString(R.string.sessions_restart_not_verified)
                        }
                    )
                }
                if (newPty != null && newPty.isAlive) {
                    _events.emit(Event.OpenTerminal(newId))
                }
                refreshNow()
            } else {
                _state.update { it.copy(restarting = it.restarting - session.terminalId) }
            }
        }
    }

    fun terminateProcess(process: WorkspaceProcessSample) {
        val pid = process.pid
        if (process.isRoot || pid in _state.value.protectedPids) {
            _state.update { it.copy(message = app().linexaString(R.string.sessions_process_protected)) }
            return
        }
        viewModelScope.launch {
            val signaled = processSignals.terminate(pid)
            val gone = awaitProcessGone(pid)
            if (gone) {
                _state.update { current ->
                    current.copy(
                        sessions = current.sessions.map { it.withoutProcess(pid) },
                        processes = current.processes.filterNot { it.pid == pid },
                        message = app().linexaString(R.string.sessions_process_ended, process.name)
                    )
                }
            } else {
                val reason = if (!signaled) {
                    app().linexaString(R.string.sessions_process_signal_failed)
                } else {
                    app().linexaString(R.string.sessions_process_still_running)
                }
                _state.update {
                    it.copy(message = app().linexaString(R.string.sessions_process_end_failed, process.name, reason))
                }
            }
            refreshNow()
        }
    }

    private data class HostReadings(
        val memory: HostResourceSampler.DeviceMemory?,
        val cpu: HostResourceSampler.DeviceCpu?,
        val appMemory: HostResourceSampler.AppMemory?,
        val appCpu: Float?
    )

    private suspend fun tick() {
        tickMutex.withLock {
            // CPU is a delta. Two samples a few milliseconds apart (the poll loop
            // plus a refresh tap) would quantise to 0 or spike; coalesce them.
            val nowMs = System.nanoTime() / 1_000_000L
            if (lastTickAtMs != 0L && nowMs - lastTickAtMs < MIN_TICK_GAP_MS) return
            lastTickAtMs = nowMs
            sampleOnce()
        }
    }

    private suspend fun sampleOnce() {
        val sessions = terminalSnapshot
        val nowMs = System.currentTimeMillis()

        // Live sessions only: an ended session's pty pid is dead and may be
        // reused, so it is never sampled.
        val activePids = sessions.values
            .filter { it.state is TerminalState.Running || it.state is TerminalState.Starting }
            .mapNotNull { s -> s.ptySession.pid.takeIf { it > 0 } }
        val rootPid = currentRootPid?.takeIf { it > 0 }
        val roots = (activePids + listOfNotNull(rootPid)).distinct()

        // LINEXA-PERF-002: the map mutation stays on the caller (Main); the
        // per-tick price — /proc scan, tree correlation, rollups and the host
        // readings — moves to Default so the 2 s loop cannot deliver
        // main-thread jitter while a terminal is rendering.
        val endedTimes = sessions.values.associate { record ->
            record.terminalId to endedAtFor(record, nowMs)
        }
        val labels = TerminalNumbering.compactLabels(sessions.keys)

        val computed = withContext(Dispatchers.Default) {
            forgetSampledRoots(except = roots.toSet())
            sampledRoots = roots.toSet()

            val snapshot = resourceMonitor.snapshot(roots)
            val workspaceProcesses = snapshot.processes
            val perRoot = RootTreePartition.byRoot(roots, workspaceProcesses)

            val sessionUis = sessions.values.map { record ->
                val active = record.state is TerminalState.Running || record.state is TerminalState.Starting
                val pid = record.ptySession.pid.takeIf { it > 0 }
                val processes = if (active) pid?.let { perRoot[it] }.orEmpty() else emptyList()
                val total = WorkspaceAggregator.rollup(processes)
                SessionUi(
                    terminalId = record.terminalId,
                    label = labels[record.terminalId] ?: record.terminalId,
                    kind = inferKind(processes),
                    state = record.state,
                    createdAt = record.createdAt,
                    endedAtMillis = endedTimes[record.terminalId],
                    pid = pid,
                    cpuPercentOfDevice = total.cpuPercentOfDevice,
                    memKb = total.memoryKb,
                    memoryIsProportional = total.memoryIsProportional,
                    processCount = total.processCount,
                    processes = processes
                )
            }.sortedBy { it.createdAt }

            val host = withContext(Dispatchers.IO) {
                HostReadings(
                    memory = hostSampler.deviceMemory(),
                    cpu = hostSampler.deviceCpu(),
                    appMemory = hostSampler.appMemory(),
                    appCpu = hostSampler.appCpuPercent()
                )
            }

            val workspaceSample = WorkspaceAggregator.workspaceSample(
                processes = workspaceProcesses,
                cores = snapshot.topology.capacityCores,
                deviceTotalKb = snapshot.deviceTotalKb
                    ?: host.memory?.totalBytes?.div(1024L)
            )

            val protected = buildSet {
                rootPid?.let { add(it) }
                activePids.forEach { add(it) }
            }

            SampleResult(
                sessions = sessionUis,
                processes = workspaceProcesses,
                protected = protected,
                workspaceSample = workspaceSample,
                host = host
            )
        }

        _state.update { current ->
            current.copy(
                sessions = computed.sessions,
                processes = computed.processes,
                protectedPids = computed.protected,
                workspaceSample = computed.workspaceSample,
                deviceMemory = computed.host.memory,
                appMemory = computed.host.appMemory,
                deviceCpu = computed.host.cpu,
                appCpuPercent = computed.host.appCpu,
                nowMillis = nowMs,
                loading = false
            )
        }
    }

    private class SampleResult(
        val sessions: List<SessionUi>,
        val processes: List<WorkspaceProcessSample>,
        val protected: Set<Int>,
        val workspaceSample: WorkspaceResourceSample?,
        val host: HostReadings
    )

    /** Frozen end time for a session that is not running; null while it runs. */
    private fun endedAtFor(record: TerminalSession, nowMs: Long): Long? {
        val active = record.state is TerminalState.Running || record.state is TerminalState.Starting
        return if (active) {
            endedAt.remove(record.terminalId)
            null
        } else {
            endedAt.getOrPut(record.terminalId) { nowMs }
        }
    }

    /** Drop `/proc` delta baselines for trees no longer being sampled. */
    private fun forgetSampledRoots(except: Set<Int> = emptySet()) {
        sampledRoots.filterNot { it in except }.forEach { resourceMonitor.forget(it) }
        if (except.isEmpty()) sampledRoots = emptySet()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun awaitPtyGone(
        binder: RuntimeBinder,
        workspaceId: String,
        terminalId: String
    ): Boolean {
        repeat(3) {
            if (binder.getPtySession(workspaceId, terminalId) == null) return true
            delay(300)
        }
        return binder.getPtySession(workspaceId, terminalId) == null
    }

    private suspend fun awaitProcessGone(pid: Int): Boolean {
        repeat(3) {
            if (!File("/proc/$pid").exists() || isZombie(pid)) return true
            delay(500)
        }
        return !File("/proc/$pid").exists() || isZombie(pid)
    }

    private fun isZombie(pid: Int): Boolean = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val idx = stat.lastIndexOf(')')
        idx in 0 until stat.length - 2 && stat[idx + 2] == 'Z'
    }.getOrDefault(false)

    private fun app(): Application = getApplication<Application>()

    override fun onCleared() {
        stopPolling()
        forgetSampledRoots()
        collectJob?.cancel()
        super.onCleared()
    }

    /**
     * Rebuilds the list from the runtime's records, carrying over the last
     * measured values for a session whose pid did not change. Built from the
     * records every time (never appended to), so a re-emission can neither
     * duplicate a session nor resurrect one the runtime dropped.
     */
    private fun buildFromRecords(
        records: Map<String, TerminalSession>,
        previousList: List<SessionUi>
    ): List<SessionUi> {
        val previous = previousList.associateBy { it.terminalId }
        val labels = TerminalNumbering.compactLabels(records.keys)
        val nowMs = System.currentTimeMillis()
        // Forget end-times of terminals the runtime no longer lists.
        endedAt.keys.retainAll(records.keys)
        return records.values.map { record ->
            val old = previous[record.terminalId]
            val pid = record.ptySession.pid.takeIf { it > 0 }
            val active = record.state is TerminalState.Running || record.state is TerminalState.Starting
            val reused = old != null && old.pid == pid && active
            val processes = if (reused) old!!.processes else emptyList()
            SessionUi(
                terminalId = record.terminalId,
                label = labels[record.terminalId] ?: record.terminalId,
                kind = if (reused) old!!.kind else inferKind(emptyList()),
                state = record.state,
                createdAt = record.createdAt,
                endedAtMillis = endedAtFor(record, nowMs),
                pid = pid,
                cpuPercentOfDevice = if (reused) old!!.cpuPercentOfDevice else null,
                memKb = if (reused) old!!.memKb else null,
                memoryIsProportional = if (reused) old!!.memoryIsProportional else false,
                processCount = processes.size,
                processes = processes
            )
        }.sortedBy { it.createdAt }
    }

    companion object {
        /** Phase 08 (OPT-PERF-004): the one shared [ProcessPolling] constant. */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS

        /** Ticks closer together than this are coalesced (see [tick]). */
        const val MIN_TICK_GAP_MS = 750L

        fun inferKind(processes: List<WorkspaceProcessSample>): SessionKind {
            val names = processes.map { it.name.lowercase() }
            fun any(vararg needles: String): Boolean =
                names.any { name -> needles.any { name == it || name.contains(it) } }

            return when {
                any("claude") -> SessionKind.CLAUDE
                any("python", "python3") -> SessionKind.PYTHON
                any("node", "npm", "yarn", "pnpm") -> SessionKind.NODE
                any("git") -> SessionKind.GIT
                any("apt", "dpkg", "apt-get") -> SessionKind.PACKAGE
                names.isEmpty() -> SessionKind.TERMINAL
                else -> SessionKind.SHELL
            }
        }
    }
}
