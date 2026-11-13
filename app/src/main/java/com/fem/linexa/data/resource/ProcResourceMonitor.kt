package com.fem.linexa.data.resource

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.resource.CpuTopology
import com.fem.linexa.domain.resource.ProcMetricParsers
import com.fem.linexa.domain.resource.ProcMetricParsers.PidStatSample
import com.fem.linexa.domain.resource.ProcessAttribution
import com.fem.linexa.domain.resource.ProcessTree
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceAggregator
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.domain.resource.WorkspaceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real per-workspace [ResourceMonitor] backed by `/proc`.
 *
 * The scope rules are deliberately asymmetric:
 *
 * - [sample] and [processes] describe the tree under one root pid. That is the
 *   per-session contract the terminal screens already depend on.
 * - [snapshot] describes the **whole workspace**: every root passed by the
 *   caller, their descendants, and processes whose host argv carries the
 *   workspace rootfs path. The last signal is the same one
 *   `PRootContainerRuntime.killRootfsProcesses()` uses, and it is what includes
 *   a daemon started outside the original process tree, a reparented orphan,
 *   or a `setsid`-detached process without merging another workspace.
 *
 * All CPU values are shares of total device capacity, normalised by the
 * **online** core count. Process rows use `utime + stime` only; reaped-child
 * columns are excluded so summing rows cannot double-count live children. A
 * first reading is null/pending, never a fabricated zero, and a PID whose
 * start time changed is a new identity with a fresh baseline.
 */
@Singleton
class ProcResourceMonitor @Inject constructor(
    private val topologyProvider: CpuTopologyProvider,
    private val processCpuTracker: ProcessCpuTracker
) : ResourceMonitor {

    /**
     * Last whole-workspace snapshot and the roots it was built for.
     *
     * Several screens legitimately observe the same workspace at the same
     * moment (Workspace Performance, Sessions, the task-manager card and the
     * global Task Manager). Rebuilding the identical `/proc` walk for each one
     * is wasted I/O and can make their membership lists differ by a process
     * exit. A short cache keyed by the exact root set coalesces those calls.
     * It is intentionally shorter than any poll interval; it is not a stale-
     * data cache.
     */
    private val snapshotCacheLock = Any()
    private var cachedRoots: Set<Int> = emptySet()
    private var cachedAtMs = 0L
    private var cachedSnapshot: WorkspaceSnapshot? = null

    override suspend fun sample(pid: Int): WorkspaceResourceSample =
        withContext(Dispatchers.IO) {
            val topology = topologyProvider.topology()
            if (pid <= 0) return@withContext WorkspaceResourceSample.unavailable(topology.capacityCores)

            val scan = scanVisible(includeCmdlines = false)
            val tree = ProcessTree.descendantsOf(pid, scan.parents)
            if (tree.isEmpty() || pid !in scan.stats) {
                processCpuTracker.forget(pid)
                return@withContext WorkspaceResourceSample.unavailable(topology.capacityCores)
            }

            val rows = buildRows(
                members = tree,
                roots = setOf(pid),
                scan = scan,
                topology = topology,
                nowMs = nowMs()
            )
            if (rows.isEmpty()) {
                return@withContext WorkspaceResourceSample.unavailable(topology.capacityCores)
            }
            val totalKb = readText("/proc/meminfo")?.let { ProcMetricParsers.memTotalKb(it) }
            WorkspaceAggregator.workspaceSample(
                processes = rows,
                cores = topology.capacityCores,
                deviceTotalKb = totalKb
            )
        }

    override suspend fun processes(pid: Int): List<WorkspaceProcessSample> =
        withContext(Dispatchers.IO) {
            if (pid <= 0) return@withContext emptyList()
            val topology = topologyProvider.topology()
            val scan = scanVisible(includeCmdlines = false)
            val tree = ProcessTree.descendantsOf(pid, scan.parents)
            if (tree.isEmpty() || pid !in scan.stats) {
                processCpuTracker.forget(pid)
                return@withContext emptyList()
            }
            buildRows(
                members = tree,
                roots = setOf(pid),
                scan = scan,
                topology = topology,
                nowMs = nowMs()
            )
        }

    /**
     * Whole-workspace snapshot used by the Activity, Sessions, and Workspace
     * Performance screens.
     *
     * One directory scan supplies both the parent map and the raw command
     * lines; one status/smaps read per member supplies identity and memory.
     * The rows returned here are also the aggregate's input, so the card and
     * its process list cannot disagree within a poll.
     */
    override suspend fun snapshot(rootPids: Collection<Int>): WorkspaceSnapshot =
        withContext(Dispatchers.IO) {
            val topology = topologyProvider.topology()
            val roots = rootPids.filter { it > 0 }.toSet()
            if (roots.isEmpty()) return@withContext WorkspaceSnapshot.unavailable(topology)

            val now = nowMs()
            synchronized(snapshotCacheLock) {
                val cached = cachedSnapshot
                if (cached != null && roots == cachedRoots && now - cachedAtMs < SNAPSHOT_CACHE_MS) {
                    return@withContext cached
                }
            }

            val scan = scanVisible(includeCmdlines = true)
            if (scan.stats.isEmpty()) return@withContext WorkspaceSnapshot.unavailable(topology)

            val directMarkers = roots.mapNotNull { root ->
                scan.cmdlines[root]?.let(ProcessAttribution::rootfsMarkerFromCmdline)
            }.toSet()
            // Normally the workspace/terminal proot argv names the rootfs. If
            // that direct read was denied, recover the marker from the tree we
            // can already see before giving up: otherwise a daemon outside the
            // original root's descendants silently drops out of the workspace
            // total while another session is busy.
            val markers = if (directMarkers.isNotEmpty()) {
                directMarkers
            } else {
                roots.asSequence()
                    .flatMap { ProcessTree.descendantsOf(it, scan.parents).asSequence() }
                    .mapNotNull { member ->
                        scan.cmdlines[member]?.let(ProcessAttribution::rootfsMarkerFromCmdline)
                    }
                    .toSet()
            }
            val members = ProcessAttribution.workspacePids(
                roots = roots,
                parents = scan.parents,
                cmdlines = scan.cmdlines,
                rootfsMarkers = markers
            )
            if (members.isEmpty()) return@withContext WorkspaceSnapshot.unavailable(topology)

            val rows = buildRows(
                members = members,
                roots = roots,
                rootMarkers = markers,
                scan = scan,
                topology = topology,
                nowMs = now
            )
            val result = WorkspaceSnapshot(
                processes = rows,
                topology = topology,
                readable = rows.isNotEmpty(),
                deviceTotalKb = readText("/proc/meminfo")
                    ?.let(ProcMetricParsers::memTotalKb)
            )
            synchronized(snapshotCacheLock) {
                cachedRoots = roots
                cachedAtMs = now
                cachedSnapshot = result
            }
            result
        }

    override fun forget(pid: Int) {
        processCpuTracker.forget(pid)
    }

    /**
     * Build rows from an existing scan. CPU baselines are updated exactly once
     * per row, and only for a process whose stat was actually readable.
     */
    private fun buildRows(
        members: Set<Int>,
        roots: Set<Int>,
        scan: ProcScan,
        topology: CpuTopology,
        nowMs: Long,
        rootMarkers: Set<String> = emptySet()
    ): List<WorkspaceProcessSample> {
        val capacityCores = topology.capacityCores
        val clockTicks = topologyProvider.clockTicksPerSecond
        val rows = ArrayList<WorkspaceProcessSample>(members.size)

        for (pid in members.sorted()) {
            val stat = scan.stats[pid] ?: continue
            val status = readText("/proc/$pid/status")
            val uid = status?.let(ProcMetricParsers::statusUid)
            val threads = status?.let(ProcMetricParsers::statusThreads)
            val rawCmdline = scan.cmdlines[pid]
                ?: readText("/proc/$pid/cmdline")

            val pss = readText("/proc/$pid/smaps_rollup")
                ?.let(ProcMetricParsers::pssKb)
            val memKb = pss
                ?: status?.let(ProcMetricParsers::vmRssKb)

            // One shared table for every screen; see ProcessCpuTracker. The
            // tracker keeps the previous ticks and applies the sampling floor,
            // so a second consumer polling the same pid in the same window
            // cannot shrink this row's delta.
            val cpu = processCpuTracker.observe(
                pid = pid,
                startTimeTicks = stat.startTimeTicks,
                ownTicks = stat.ownTicks,
                atMs = nowMs,
                clockTicksPerSecond = clockTicks,
                capacityCores = capacityCores
            )

            rows.add(
                WorkspaceProcessSample(
                    pid = pid,
                    startTimeTicks = stat.startTimeTicks,
                    name = stat.name.ifBlank { "pid $pid" },
                    stateChar = stat.stateChar,
                    cpuPercentOfDevice = cpu.percent,
                    memKb = memKb,
                    isRoot = pid in roots || rootMarkers.any { marker ->
                        ProcessAttribution.isRootfsSupervisor(rawCmdline, marker)
                    },
                    parentPid = stat.parentPid?.takeIf { it in members },
                    memoryIsProportional = when {
                        memKb == null -> null
                        pss != null -> true
                        else -> false
                    },
                    uid = uid,
                    command = ProcMetricParsers.commandLine(rawCmdline),
                    threads = threads,
                    lastCore = stat.lastCore,
                    cpuAvailability = cpu.availability
                )
            )
        }
        return rows
    }

    /**
     * One `/proc` directory walk.
     *
     * [includeCmdlines] is true only for workspace-wide attribution; the
     * per-session paths never pay for command-line reads they do not use. The
     * stats map is reused for both membership and rows, so a member is not
     * read twice in the same snapshot.
     */
    private fun scanVisible(includeCmdlines: Boolean): ProcScan {
        val entries = runCatching { File("/proc").list() }.getOrNull()
        if (entries == null) {
            Logger.w(TAG, "Cannot list /proc; workspace metrics unavailable")
            return ProcScan.EMPTY
        }
        val stats = HashMap<Int, PidStatSample>(entries.size)
        val cmdlines = HashMap<Int, String>(if (includeCmdlines) entries.size else 0)
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val statLine = readText("/proc/$pid/stat") ?: continue
            val stat = ProcMetricParsers.pidStatSample(statLine) ?: continue
            stats[pid] = stat
            if (includeCmdlines) {
                readText("/proc/$pid/cmdline")?.let { cmdlines[pid] = it }
            }
        }
        if (stats.isEmpty()) {
            Logger.w(TAG, "No readable /proc entries; workspace metrics unavailable")
        }
        return ProcScan(stats, cmdlines)
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    private data class ProcScan(
        val stats: Map<Int, PidStatSample>,
        val cmdlines: Map<Int, String>
    ) {
        val parents: Map<Int, Int>
            get() = stats.mapNotNull { (pid, stat) -> stat.parentPid?.let { pid to it } }.toMap()

        companion object {
            val EMPTY = ProcScan(emptyMap(), emptyMap())
        }
    }

    private companion object {
        const val TAG = "ResourceMonitor"

        /**
         * Coalescing window for [snapshot]. Below every screen poll interval
         * (the shortest workspace poll is ~1 s), so it never hides a genuinely
         * new sample; it only merges simultaneous consumers of one workspace.
         */
        const val SNAPSHOT_CACHE_MS = 250L
    }
}
