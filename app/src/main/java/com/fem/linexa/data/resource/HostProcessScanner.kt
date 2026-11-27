package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.HostProcessSample
import com.fem.linexa.domain.resource.HostProcessSnapshot
import com.fem.linexa.domain.resource.ProcMetricParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How often the inaccessible-PID cache is discarded and every `/proc` entry is
 * probed again (PERF-006). Short enough that a recycled pid becomes visible
 * again quickly; long enough that the per-tick cost is dominated by the few
 * readable processes.
 */
private const val FULL_PROBE_INTERVAL_MS = 30_000L

/**
 * Reads every process Android lets this app see in `/proc`.
 *
 * This is not "all device processes". On modern Android most `/proc/<pid>`
 * entries are hidden or SELinux-denied to an ordinary app, so the scanner
 * counts those PIDs and reports [HostProcessSnapshot.scopeLimited]. The list
 * may still contain Linexa's own processes and PRoot guests because they share
 * its UID; that is the honest host view, not a failure to filter.
 *
 * CPU is a per-pid delta of `utime + stime`, normalised by online device
 * capacity. The baseline carries the kernel start time, so PID reuse starts
 * pending rather than inheriting a dead process's counters.
 */
@Singleton
class HostProcessScanner @Inject constructor(
    private val procFs: ProcFsReader,
    private val topologyProvider: CpuTopologyProvider,
    private val processSignals: ProcessSignals,
    private val processCpuTracker: ProcessCpuTracker = ProcessCpuTracker()
) {

    /**
     * Serializes scans. One screen polls today, but the cache below is plain
     * mutable state; a second caller must not interleave with a scan in flight.
     */
    private val scanMutex = Mutex()

    /**
     * PIDs whose `stat` was unreadable at the last probe.
     *
     * PERF-006: on the test device `/proc` lists ~1 069 pid directories and the
     * overwhelming majority are denied to this UID, so a naive scan opened
     * ~1 000 files that could only ever fail, every 2 s. A denied PID stays
     * denied for its lifetime, so its verdict is cached. Entries for pids that
     * have left `/proc` are dropped every tick, so a recycled pid is re-read
     * immediately; [FULL_PROBE_INTERVAL_MS] is the safety valve for the one case
     * that leaves — a live pid whose first read failed transiently.
     */
    private val inaccessiblePids = HashSet<Int>()

    /** When [inaccessiblePids] was last cleared, on the [System.nanoTime] clock. */
    private var lastFullProbeMs = 0L

    /**
     * @param includePss read `smaps_rollup` (a page-table walk) for readable
     *   processes. False while the process section is collapsed and nothing
     *   renders the figures; the status-derived RSS is still reported, labelled
     *   as non-proportional via [HostProcessSample.memoryIsProportional].
     */
    suspend fun snapshot(includePss: Boolean = true): HostProcessSnapshot = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val topology = topologyProvider.topology()
            val clockTicks = topologyProvider.clockTicksPerSecond
            val selfPid = procFs.selfPid()
            val selfUid = procFs.selfUid()
            val pids = procFs.listPids()
                ?: return@withContext HostProcessSnapshot(
                    processes = emptyList(),
                    readableProcesses = 0,
                    inaccessibleProcesses = 0,
                    procListReadable = false,
                    selfUid = selfUid,
                    topology = topology
                )

            val now = System.nanoTime() / 1_000_000L
            val fullProbe = now - lastFullProbeMs >= FULL_PROBE_INTERVAL_MS
            if (fullProbe) {
                inaccessiblePids.clear()
                lastFullProbeMs = now
            }
            // Drop verdicts for pids that no longer exist, so a recycled pid is
            // re-read instead of inheriting a dead process's "inaccessible".
            val pidSet = pids.toHashSet()
            inaccessiblePids.retainAll(pidSet)

            val rows = ArrayList<HostProcessSample>(pids.size)
            var readable = 0
            var inaccessible = 0

            for (pid in pids.sorted()) {
                if (!fullProbe && pid in inaccessiblePids) {
                    inaccessible++
                    continue
                }
                val statLine = procFs.readText(pid, "stat")
                val stat = statLine?.let(ProcMetricParsers::pidStatSample)
                if (stat == null) {
                    inaccessible++
                    inaccessiblePids.add(pid)
                    continue
                }
                inaccessiblePids.remove(pid)
                readable++

                val status = procFs.readText(pid, "status")
                val uid = status?.let(ProcMetricParsers::statusUid)
                val threads = status?.let(ProcMetricParsers::statusThreads)
                val rawCmdline = procFs.readText(pid, "cmdline")
                val pss = if (includePss) {
                    procFs.readText(pid, "smaps_rollup")
                        ?.let(ProcMetricParsers::pssKb)
                } else {
                    null
                }
                val memKb = pss
                    ?: status?.let(ProcMetricParsers::vmRssKb)

                // Shared with every other /proc consumer; see ProcessCpuTracker.
                // The global Task Manager and the workspace panels therefore read
                // the same percentage for the same pid instead of stealing each
                // other's baseline.
                val cpu = processCpuTracker.observe(
                    pid = pid,
                    startTimeTicks = stat.startTimeTicks,
                    ownTicks = stat.ownTicks,
                    atMs = now,
                    clockTicksPerSecond = clockTicks,
                    capacityCores = topology.capacityCores
                )

                val isSelf = pid == selfPid
                rows.add(
                    HostProcessSample(
                        pid = pid,
                        startTimeTicks = stat.startTimeTicks,
                        parentPid = stat.parentPid?.takeIf { it > 0 },
                        uid = uid,
                        name = stat.name.ifBlank { "pid $pid" },
                        command = ProcMetricParsers.commandLine(rawCmdline),
                        stateChar = stat.stateChar,
                        threads = threads,
                        cpuPercentOfDevice = cpu.percent,
                        cpuAvailability = cpu.availability,
                        memKb = memKb,
                        memoryIsProportional = when {
                            memKb == null -> null
                            pss != null -> true
                            else -> false
                        },
                        lastCore = stat.lastCore,
                        isSelf = isSelf,
                        canSignal = !isSelf && uid != null && uid == selfUid
                    )
                )
            }

            HostProcessSnapshot(
                processes = rows,
                readableProcesses = readable,
                inaccessibleProcesses = inaccessible,
                procListReadable = true,
                selfUid = selfUid,
                topology = topology
            )
        }
    }

    /**
     * SIGTERM only when the pid still belongs to Linexa's UID, is not the
     * app's own main process, and (when supplied) still has the same kernel
     * start time as the row the user acted on.
     *
     * [HostProcessSample.canSignal] is a presentation hint from the scan; this
     * re-reads `/proc` immediately before signalling so a recycled pid or a
     * stale row cannot terminate another process. Workspace/session roots are
     * shielded by the screen through [HostProcessSample.isProtected].
     */
    fun terminate(pid: Int, expectedStartTimeTicks: Long? = null): Boolean {
        if (pid <= 0 || pid == procFs.selfPid()) return false
        val selfUid = procFs.selfUid() ?: return false
        val stat = procFs.readText(pid, "stat")
            ?.let(ProcMetricParsers::pidStatSample)
            ?: return false
        if (expectedStartTimeTicks != null && stat.startTimeTicks != expectedStartTimeTicks) {
            return false
        }
        val currentUid = procFs.readText(pid, "status")
            ?.let(ProcMetricParsers::statusUid)
            ?: return false
        if (currentUid != selfUid) return false
        return processSignals.terminate(pid)
    }

    fun forget(pid: Int) {
        processCpuTracker.forget(pid)
    }
}
