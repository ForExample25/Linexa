package com.fem.linexa.ui.screens.activity

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.data.resource.HostProcessScanner
import com.fem.linexa.data.resource.HostResourceSampler
import com.fem.linexa.domain.resource.HostProcessSample
import com.fem.linexa.domain.resource.HostProcessSnapshot
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceAggregator
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalNumbering
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.distro.DownloadService
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeService
import com.fem.linexa.service.runtime.RuntimeServiceState
import com.fem.linexa.service.runtime.TerminalSession
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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Producer for the global Activity tab.
 *
 * The tab answers one question — "what is this phone, this app and the running
 * Linux workspace costing right now?" — in three **separate** scopes. Each scope
 * has exactly one source, and none can feed another:
 *
 *  | scope     | CPU                                  | RAM                                   |
 *  |-----------|--------------------------------------|---------------------------------------|
 *  | Device    | `/proc/stat` delta (if the OS allows)| `ActivityManager.MemoryInfo`          |
 *  | Linexa app| this process's `/proc/<pid>/stat`    | `Debug.getMemoryInfo` PSS (this pid)  |
 *  | Workspace | sum over the workspace's process tree| `Pss` summed over that same tree      |
 *
 * The app process and the workspace's PRoot processes are different pids, so the
 * app figure never contains the workspace and the workspace figure never
 * contains the app.
 *
 * ## Sources
 *  1. a **read-only** bind of [RuntimeService] — which workspace is running, its
 *     root pid, and every workspace's terminal records. This observer NEVER
 *     starts or stops a workspace; `onCleared` only unbinds.
 *  2. [HostResourceSampler] — device and app scopes.
 *  3. [ResourceMonitor] + [WorkspaceAggregator] — workspace scope.
 *  4. [WorkspaceRepository] — identities and the recent-activity feed.
 *
 * ## Polling discipline
 * One loop, alive only while the screen is in the foreground ([start]/[pause]).
 * The workspace tree is sampled only for the single monitored workspace, and not
 * at all when nothing is running. A tick is idempotent: it overwrites readings,
 * it never appends sessions or processes, so refreshing cannot duplicate rows.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    application: Application,
    private val hostSampler: HostResourceSampler,
    private val hostScanner: HostProcessScanner,
    private val resourceMonitor: ResourceMonitor,
    private val workspaceRepository: WorkspaceRepository
) : AndroidViewModel(application) {

    /** Whether a figure is present, still being measured, or cannot be measured here. */
    enum class Availability { AVAILABLE, MEASURING, UNAVAILABLE }

    data class DeviceMetrics(
        val cpuPercent: Float?,
        val cpuAvailability: Availability,
        val cores: Int,
        val ramUsedBytes: Long?,
        val ramTotalBytes: Long?,
        val ramFraction: Float?
    )

    data class AppMetrics(
        val cpuPercent: Float?,
        val cpuAvailability: Availability,
        val ramBytes: Long?,
        val ramIsProportional: Boolean,
        /** Share of device RAM, for the bar. Null when either side is unknown. */
        val ramFraction: Float?
    )

    data class WorkspaceMetrics(
        val workspaceId: String,
        val name: String,
        val avatarLetter: String,
        val distroId: String?,
        val isStarting: Boolean,
        val uptimeLabel: String?,
        val terminalCount: Int,
        val cpuPercent: Float?,
        val cpuAvailability: Availability,
        val ramKb: Long?,
        val ramIsProportional: Boolean,
        val ramFraction: Float?,
        val processCount: Int
    )

    /** A running workspace the user can point the third card at. */
    data class MonitorChoice(val workspaceId: String, val name: String)

    data class SessionRow(
        val workspaceId: String,
        val workspaceName: String,
        val avatarLetter: String,
        val terminalId: String,
        /** Visible compact label (T1..TN, no gaps); [terminalId] stays the navigation identity. */
        val displayId: String,
        val isRunning: Boolean
    )

    data class EventRow(
        val kind: EventKind,
        val label: String,
        val relativeTime: String
    )

    data class HostProcessState(
        val processes: List<HostProcessSample>,
        val readableProcesses: Int,
        val inaccessibleProcesses: Int,
        val scopeLimited: Boolean
    )

    enum class EventKind { PRIMARY, BLUE, AMBER, RED, MUTED }

    data class ActivityUiState(
        val device: DeviceMetrics? = null,
        val app: AppMetrics? = null,
        /** Null when no workspace is running — the UI shows an empty state, not zeros. */
        val workspace: WorkspaceMetrics? = null,
        val choices: List<MonitorChoice> = emptyList(),
        val sessions: List<SessionRow> = emptyList(),
        val events: List<EventRow> = emptyList(),
        val hostProcesses: HostProcessState? = null,
        val anyRunning: Boolean = false
    )

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private var binder: RuntimeBinder? = null
    private var isBound = false
    private val _serviceState = MutableStateFlow<RuntimeServiceState>(RuntimeServiceState.Unbound)
    val serviceState: StateFlow<RuntimeServiceState> = _serviceState.asStateFlow()

    private var workspaceStates: Map<String, RuntimeState> = emptyMap()
    private var terminalRecords: Map<String, Map<String, TerminalSession>> = emptyMap()
    private var workspaces: List<WorkspaceMetadata> = emptyList()

    private var deviceMetrics: DeviceMetrics? = null
    private var appMetrics: AppMetrics? = null
    private var workspaceReading: WorkspaceReading? = null
    private var hostProcesses: HostProcessState? = null

    /** The workspace the user explicitly chose to monitor; ignored once it stops running. */
    private var selectedWorkspaceId: String? = null

    /** Root pids sampled last tick, so a stopped workspace's delta state can be dropped. */
    private var sampledRoots: Set<Int> = emptySet()

    private var pollJob: Job? = null

    private data class WorkspaceReading(
        val workspaceId: String,
        val cpuPercent: Float?,
        val ramKb: Long?,
        val ramIsProportional: Boolean,
        val ramFraction: Float?,
        val processCount: Int,
        val pids: Set<Int>
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val runtimeBinder = service as? RuntimeBinder ?: return
            binder = runtimeBinder
            isBound = true
            _serviceState.value = RuntimeServiceState.Connected
            viewModelScope.launch {
                runtimeBinder.sessionStates.collect { states ->
                    workspaceStates = states
                    render()
                }
            }
            viewModelScope.launch {
                runtimeBinder.terminalSessions.collect { records ->
                    terminalRecords = records
                    render()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The runtime died or was unbound under us. Everything derived from
            // it is now unknown: drop it instead of leaving a stale "Running".
            binder = null
            isBound = false
            workspaceStates = emptyMap()
            terminalRecords = emptyMap()
            workspaceReading = null
            _serviceState.value = RuntimeServiceState.Disconnected
            render()
        }
    }

    init {
        viewModelScope.launch {
            workspaceRepository.observeAll().collect { rows ->
                workspaces = rows
                render()
            }
        }
    }

    /** Begin the read-only bind + sampling; safe to call repeatedly. */
    fun start() {
        if (!isBound && binder == null) {
            val context = getApplication<Application>()
            val intent = Intent(context, RuntimeService::class.java)
            try {
                context.startService(intent)
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {
                // A failed read-only bind leaves the screen on Room-derived
                // state; it must never crash the Activity tab.
            }
        }
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (true) {
                    try {
                        tick()
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // stale-but-honest state; never crash a UI tick
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /** Stop sampling (screen left the foreground). The bind stays; it costs nothing. */
    fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Point the Workspace card at [workspaceId] (only meaningful while several run). */
    fun selectWorkspace(workspaceId: String) {
        if (selectedWorkspaceId == workspaceId) return
        selectedWorkspaceId = workspaceId
        // The previous workspace's reading must not flash under the new name.
        workspaceReading = null
        render()
        if (pollJob?.isActive == true) viewModelScope.launch { runCatching { tick() } }
    }

    override fun onCleared() {
        // READ-ONLY: unlike the workspace graph's RuntimeViewModel, this
        // observer never sends ACTION_STOP — it only unbinds. Workspaces the
        // user started elsewhere keep running.
        pollJob?.cancel()
        if (isBound) {
            try {
                getApplication<Application>().unbindService(connection)
            } catch (_: Exception) {
            }
            isBound = false
        }
        sampledRoots.forEach { resourceMonitor.forget(it) }
        sampledRoots = emptySet()
        super.onCleared()
    }

    // ── sampling ──────────────────────────────────────────────────────

    private suspend fun tick() {
        val (memory, cpu, appCpu, appMem) = withContext(Dispatchers.IO) {
            HostReadings(
                hostSampler.deviceMemory(),
                hostSampler.deviceCpu(),
                hostSampler.appCpuPercent(),
                hostSampler.appMemory()
            )
        }

        val total = memory?.totalBytes
        deviceMetrics = DeviceMetrics(
            cpuPercent = cpu?.overallPercent,
            cpuAvailability = when {
                cpu?.overallPercent != null -> Availability.AVAILABLE
                cpu?.readable == false -> Availability.UNAVAILABLE
                else -> Availability.MEASURING
            },
            cores = hostSampler.cores,
            ramUsedBytes = memory?.usedBytes,
            ramTotalBytes = total,
            ramFraction = memory?.usedFraction
        )

        val appPss = appMem?.pssBytes
        appMetrics = AppMetrics(
            cpuPercent = appCpu,
            cpuAvailability = if (appCpu != null) Availability.AVAILABLE else Availability.MEASURING,
            ramBytes = appPss ?: appMem?.rssBytes,
            ramIsProportional = appPss != null,
            ramFraction = (appPss ?: appMem?.rssBytes)?.let { bytes ->
                total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
            }
        )

        sampleWorkspace(total)
        sampleHostProcesses()
        render()
    }

    /**
     * Host scope: every `/proc` entry Android lets this app read. Workspace
     * roots and live terminal pids are marked protected in the same tick that
     * discovers them, so the row's kill action can never target a session
     * supervisor.
     */
    private suspend fun sampleHostProcesses() {
        val snapshot = withContext(Dispatchers.IO) { hostScanner.snapshot() }
        // Protect every root and every member of the workspace card we just
        // sampled: PRoot guests share Linexa's UID, so UID alone is not enough
        // to tell a host daemon from a guest task.
        val protected = allLiveRootPids() + workspaceReading?.pids.orEmpty()
        hostProcesses = hostProcessState(snapshot, protected)
    }

    private fun hostProcessState(
        snapshot: HostProcessSnapshot,
        protectedPids: Set<Int>
    ): HostProcessState = HostProcessState(
        processes = snapshot.processes.map { process ->
            val belongsToWorkspace = process.pid in protectedPids ||
                process.command?.contains("/rootfs") == true
            if (belongsToWorkspace) process.copy(isProtected = true) else process
        },
        readableProcesses = snapshot.readableProcesses,
        inaccessibleProcesses = snapshot.inaccessibleProcesses,
        scopeLimited = snapshot.scopeLimited
    )

    private fun allLiveRootPids(): Set<Int> = buildSet {
        liveWorkspaces().forEach { (row, _) -> addAll(rootPidsOf(row.id)) }
    }

    fun terminateHost(process: HostProcessSample) {
        if (!process.canSignal || process.isProtected || process.isSelf) return
        viewModelScope.launch {
            val ended = withContext(Dispatchers.IO) {
                hostScanner.terminate(process.pid, process.startTimeTicks)
            }
            if (ended) {
                val current = hostProcesses ?: return@launch
                hostProcesses = current.copy(
                    processes = current.processes.filterNot { it.pid == process.pid }
                )
                render()
            }
        }
    }

    private data class HostReadings(
        val memory: HostResourceSampler.DeviceMemory?,
        val cpu: HostResourceSampler.DeviceCpu?,
        val appCpu: Float?,
        val appMemory: HostResourceSampler.AppMemory?
    )

    private suspend fun sampleWorkspace(deviceTotalBytes: Long?) {
        val target = monitoredWorkspaceId()
        val roots = target?.let { rootPidsOf(it) }.orEmpty()

        // Drop delta baselines for anything we are no longer watching.
        (sampledRoots - roots.toSet()).forEach { resourceMonitor.forget(it) }
        sampledRoots = roots.toSet()

        if (target == null || roots.isEmpty()) {
            workspaceReading = null
            return
        }
        val snapshot = resourceMonitor.snapshot(roots)
        val sample = WorkspaceAggregator.workspaceSample(
            processes = snapshot.processes,
            cores = snapshot.topology.capacityCores,
            deviceTotalKb = snapshot.deviceTotalKb ?: deviceTotalBytes?.div(1024L)
        )
        workspaceReading = WorkspaceReading(
            workspaceId = target,
            cpuPercent = sample.cpuPercentOfDevice,
            ramKb = sample.memoryKb,
            ramIsProportional = sample.memoryIsProportional,
            ramFraction = sample.memoryFraction,
            processCount = sample.processCount,
            pids = snapshot.processes.mapTo(HashSet()) { it.pid }
        )
    }

    /** Workspaces that are actually up (running or coming up). */
    private fun liveWorkspaces(): List<Pair<WorkspaceMetadata, RuntimeState>> =
        workspaces.mapNotNull { row ->
            val state = workspaceStates[row.id] ?: return@mapNotNull null
            if (state is RuntimeState.Running || state is RuntimeState.Starting) row to state else null
        }

    /**
     * The workspace the third card describes: the user's pick if it is still
     * live, else the most recently launched live workspace.
     */
    private fun monitoredWorkspaceId(): String? {
        val live = liveWorkspaces()
        selectedWorkspaceId?.let { picked -> if (live.any { it.first.id == picked }) return picked }
        return live.maxByOrNull { it.first.lastLaunchedAt ?: 0L }?.first?.id
    }

    /**
     * Distinct host pids whose trees make up [workspaceId]: the workspace's own
     * PRoot root plus each live terminal's pty child. Trees can overlap; the
     * aggregator dedupes by pid.
     */
    private fun rootPidsOf(workspaceId: String): List<Int> {
        val workspacePid = (workspaceStates[workspaceId] as? RuntimeState.Running)?.pid
        val terminalPids = terminalRecords[workspaceId].orEmpty().values
            .filter { it.state is TerminalState.Running || it.state is TerminalState.Starting }
            .mapNotNull { runCatching { it.ptySession.pid }.getOrNull() }
        return (listOfNotNull(workspacePid) + terminalPids).filter { it > 0 }.distinct()
    }

    // ── rendering ─────────────────────────────────────────────────────

    private fun render() {
        val now = Instant.now()
        val live = liveWorkspaces()
        val target = monitoredWorkspaceId()
        val targetRow = live.firstOrNull { it.first.id == target }

        val workspaceMetrics = targetRow?.let { (row, state) ->
            val reading = workspaceReading?.takeIf { it.workspaceId == row.id }
            val openTerminals = terminalRecords[row.id].orEmpty().values
                .count { it.state is TerminalState.Running || it.state is TerminalState.Starting }
            WorkspaceMetrics(
                workspaceId = row.id,
                name = row.displayName,
                avatarLetter = row.avatarLetter,
                distroId = row.attributes[DownloadService.ATTR_DISTRO_ID],
                isStarting = state is RuntimeState.Starting,
                uptimeLabel = (state as? RuntimeState.Running)
                    ?.let { formatDuration(Duration.between(it.since, now)) },
                terminalCount = openTerminals,
                cpuPercent = reading?.cpuPercent,
                cpuAvailability = when {
                    reading?.cpuPercent != null -> Availability.AVAILABLE
                    // Sampled, but no process of the tree was readable: the
                    // workspace is up and we cannot see it. Not "measuring" —
                    // a per-pid CPU delta only lacks a value on a pid's FIRST
                    // sample, so a populated tree resolves on the next tick.
                    reading != null && reading.processCount == 0 -> Availability.UNAVAILABLE
                    else -> Availability.MEASURING
                },
                ramKb = reading?.ramKb,
                ramIsProportional = reading?.ramIsProportional ?: false,
                ramFraction = reading?.ramFraction,
                processCount = reading?.processCount ?: 0
            )
        }

        val sessions = buildList {
            live.forEach { (row, _) ->
                val records = terminalRecords[row.id].orEmpty()
                val labels = TerminalNumbering.compactLabels(records.keys)
                records.values
                    .sortedBy { labels[it.terminalId]?.drop(1)?.toIntOrNull() ?: Int.MAX_VALUE }
                    .forEach { record ->
                        add(
                            SessionRow(
                                workspaceId = row.id,
                                workspaceName = row.displayName,
                                avatarLetter = row.avatarLetter,
                                terminalId = record.terminalId,
                                displayId = labels[record.terminalId] ?: record.terminalId,
                                isRunning = record.state is TerminalState.Running ||
                                    record.state is TerminalState.Starting
                            )
                        )
                    }
            }
        }

        _uiState.value = ActivityUiState(
            device = deviceMetrics,
            app = appMetrics,
            workspace = workspaceMetrics,
            choices = if (live.size > 1) {
                live.map { MonitorChoice(it.first.id, it.first.displayName) }
            } else {
                emptyList()
            },
            sessions = sessions,
            events = buildEvents(now),
            hostProcesses = hostProcesses,
            anyRunning = live.isNotEmpty()
        )
    }

    /** Honest event feed, derived ONLY from persisted workspace facts. */
    private fun buildEvents(now: Instant): List<EventRow> {
        val nowMs = now.toEpochMilli()
        val events = mutableListOf<Triple<Long, EventKind, String>>()
        workspaces.forEach { row ->
            row.lastLaunchedAt?.let {
                events.add(Triple(it, EventKind.PRIMARY, "Started ${row.displayName}"))
            }
            events.add(Triple(row.createdAt, EventKind.BLUE, "Installed ${row.displayName}"))
            if (row.status is WorkspaceStatus.Broken) {
                events.add(
                    Triple(
                        row.lastLaunchedAt ?: row.createdAt,
                        EventKind.RED,
                        "Diagnostics captured for ${row.displayName}"
                    )
                )
            }
        }
        return events
            .sortedByDescending { it.first }
            .take(6)
            .map { (time, kind, label) -> EventRow(kind, label, formatRelative(time, nowMs)) }
    }

    companion object {
        /** 2 s — Phase 08 (OPT-PERF-004): the one shared [ProcessPolling] constant. */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS

        fun formatDuration(duration: Duration): String {
            val days = duration.toDays()
            val hours = duration.toHours() % 24
            val minutes = duration.toMinutes() % 60
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${minutes}m"
                else -> "${minutes}m"
            }
        }

        fun formatRelative(thenMs: Long, nowMs: Long): String {
            val minutes = (nowMs - thenMs) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "${minutes}m ago"
                minutes < 60 * 24 -> "${minutes / 60}h ago"
                else -> "${minutes / (60 * 24)}d ago"
            }
        }
    }
}
