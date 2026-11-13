package com.fem.linexa.domain.resource

/**
 * Pure roll-up of per-process readings into the numbers the Activity tab and the
 * workspace Sessions tab show.
 *
 * Both screens used to carry their own copy of this arithmetic (the Sessions
 * ViewModel inline, the Activity tab not at all). One implementation means the
 * two screens can never disagree about what "workspace CPU" is, and it is JVM-
 * testable without a device.
 *
 * ## Scope rule
 * Input is *only* processes that belong to the workspace (a tree rooted at the
 * workspace's `proot`, or at one of its terminal sessions). Nothing here ever
 * sees the device or the Linexa app process — those are measured by
 * [com.fem.linexa.data.resource.HostResourceSampler] and are structurally
 * unable to leak into a workspace number.
 *
 * ## Honesty rules
 *  - No readable CPU value at all → CPU is `null` (unknown), never 0.
 *  - A process with no CPU baseline yet (first sample of its pid) contributes
 *    nothing, so the sum is a lower bound while a burst of new processes is
 *    appearing. That is the accurate statement; inventing a baseline would not be.
 *  - Memory is "proportional" only if *every* contributing reading was `Pss`.
 *    A single `VmRSS` fallback makes the whole sum an upper bound and the UI must
 *    say so.
 */
object WorkspaceAggregator {

    /** Totals over some set of processes (a session's tree, or the whole workspace). */
    data class Rollup(
        val cpuPercentOfDevice: Float?,
        val memoryKb: Long?,
        val memoryIsProportional: Boolean,
        val processCount: Int
    ) {
        companion object {
            val EMPTY = Rollup(
                cpuPercentOfDevice = null,
                memoryKb = null,
                memoryIsProportional = false,
                processCount = 0
            )
        }
    }

    fun rollup(processes: List<WorkspaceProcessSample>): Rollup {
        if (processes.isEmpty()) return Rollup.EMPTY
        val cpu = processes.mapNotNull { it.cpuPercentOfDevice }
        val mem = processes.mapNotNull { it.memKb }
        val flags = processes.mapNotNull { it.memoryIsProportional }
        return Rollup(
            cpuPercentOfDevice = cpu.takeIf { it.isNotEmpty() }?.sum()?.coerceIn(0f, 100f),
            memoryKb = mem.takeIf { it.isNotEmpty() }?.sum(),
            memoryIsProportional = flags.isNotEmpty() && flags.all { it },
            processCount = processes.size
        )
    }

    /**
     * Union of several process trees, deduplicated by pid.
     *
     * A pid reachable from two roots (a terminal session spawned under the
     * workspace root, say) must be counted once or the workspace would report
     * double its real usage. The first tree to claim a pid wins; the result is
     * ordered root-first, then by pid, so the list is stable between polls and a
     * refresh can never reorder rows or duplicate them.
     */
    fun mergeTrees(perRoot: List<List<WorkspaceProcessSample>>): List<WorkspaceProcessSample> {
        val merged = LinkedHashMap<Int, WorkspaceProcessSample>()
        for (tree in perRoot) {
            for (process in tree) merged.putIfAbsent(process.pid, process)
        }
        return merged.values.sortedWith(
            compareByDescending<WorkspaceProcessSample> { it.isRoot }.thenBy { it.pid }
        )
    }

    /**
     * The workspace-scope sample for [processes] (already merged/deduplicated).
     *
     * @param deviceTotalKb device RAM, the denominator of the memory bar. This is
     *   the *only* device figure that enters a workspace sample, and only as a
     *   denominator; it never contributes to a numerator.
     */
    fun workspaceSample(
        processes: List<WorkspaceProcessSample>,
        cores: Int,
        deviceTotalKb: Long?
    ): WorkspaceResourceSample {
        val total = rollup(processes)
        return WorkspaceResourceSample(
            cpuPercentOfDevice = total.cpuPercentOfDevice,
            cpuCores = cores,
            memoryKb = total.memoryKb,
            memoryTotalKb = deviceTotalKb,
            memoryIsProportional = total.memoryIsProportional,
            processCount = total.processCount,
            sampledPids = processes.size
        )
    }
}
