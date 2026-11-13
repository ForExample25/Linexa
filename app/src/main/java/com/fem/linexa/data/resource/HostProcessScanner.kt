package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.HostProcessSample
import com.fem.linexa.domain.resource.HostProcessSnapshot
import com.fem.linexa.domain.resource.ProcMetricParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun snapshot(): HostProcessSnapshot = withContext(Dispatchers.IO) {
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
        val rows = ArrayList<HostProcessSample>(pids.size)
        var readable = 0
        var inaccessible = 0

        for (pid in pids.sorted()) {
            val statLine = procFs.readText(pid, "stat")
            val stat = statLine?.let(ProcMetricParsers::pidStatSample)
            if (stat == null) {
                inaccessible++
                continue
            }
            readable++

            val status = procFs.readText(pid, "status")
            val uid = status?.let(ProcMetricParsers::statusUid)
            val threads = status?.let(ProcMetricParsers::statusThreads)
            val rawCmdline = procFs.readText(pid, "cmdline")
            val pss = procFs.readText(pid, "smaps_rollup")
                ?.let(ProcMetricParsers::pssKb)
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
