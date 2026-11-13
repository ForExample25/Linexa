package com.fem.linexa.domain.resource

/**
 * One point-in-time reading of a **single workspace's** resource usage
 * (UI/UX Phase 5B).
 *
 * Every field is nullable, and that is the design rather than laziness: the
 * plan's constraint for this panel is "if a metric cannot reliably be calculated
 * at Workspace scope, report the limitation instead of showing misleading data".
 * A nullable field lets the sampler say *"unknown"*, which the UI renders as a
 * dash. The alternative — a non-null `Float` defaulting to `0f` — makes
 * "unknown" indistinguishable from "idle", and an idle-looking chart for a busy
 * workspace is exactly the misleading output the plan forbids.
 *
 * @property cpuPercentOfDevice CPU used by the workspace's whole process tree as
 *   a share of the device's total capacity, 0..100. Normalised by core count so
 *   the value fits a bar: a tree saturating two cores of eight reads 25, not 200.
 *   Null on the first sample of a pid — CPU is a delta between two readings and
 *   there is genuinely no answer yet, which is also why the previous
 *   implementation's `0f` first sample was wrong (it drew a dip to zero every
 *   time a session started).
 * @property cpuCores cores the normalisation used, so the UI can qualify the
 *   number instead of leaving the reader to guess its denominator.
 * @property memoryKb the tree's memory footprint in kB.
 * @property memoryTotalKb device RAM in kB, the denominator for the bar.
 * @property memoryIsProportional true when [memoryKb] came from `Pss`
 *   (`smaps_rollup`), which charges each shared page fractionally and therefore
 *   sums correctly across a process tree. False means it is a sum of `VmRSS`,
 *   which double-counts memory shared between `proot` and its children and is an
 *   **upper bound**. The UI must say so — a number that is silently 40% high is
 *   worse than one labelled approximate.
 * @property processCount processes in the tree, including the root. A real
 *   workspace-scope metric with no caveats, and the one that most directly shows
 *   the guest is alive.
 * @property sampledPids pids actually read, for diagnostics. Not rendered.
 */
data class WorkspaceResourceSample(
    val cpuPercentOfDevice: Float?,
    val cpuCores: Int,
    val memoryKb: Long?,
    val memoryTotalKb: Long?,
    val memoryIsProportional: Boolean,
    val processCount: Int,
    val sampledPids: Int
) {
    /** 0f..1f share of device RAM, or null when either side is unknown. */
    val memoryFraction: Float?
        get() {
            val used = memoryKb ?: return null
            val total = memoryTotalKb ?: return null
            if (total <= 0L) return null
            return (used.toFloat() / total).coerceIn(0f, 1f)
        }

    /** 0f..1f share of device CPU capacity, or null. */
    val cpuFraction: Float?
        get() = cpuPercentOfDevice?.let { (it / 100f).coerceIn(0f, 1f) }

    companion object {
        /**
         * The reading for a workspace whose process tree could not be read at
         * all — the pid is gone, or `/proc` refused it.
         *
         * Distinct from a stopped workspace, which the UI represents without
         * calling the sampler: this means "it should be running and we cannot
         * see it", and the panel shows dashes rather than zeros.
         */
        fun unavailable(cpuCores: Int) = WorkspaceResourceSample(
            cpuPercentOfDevice = null,
            cpuCores = cpuCores,
            memoryKb = null,
            memoryTotalKb = null,
            memoryIsProportional = false,
            processCount = 0,
            sampledPids = 0
        )
    }
}

/**
 * One consistent `/proc` snapshot for a workspace.
 *
 * [processes] and the aggregate derived from them come from the same read, so
 * a row can never contradict the card above it. [topology] supplies the
 * denominator used for every CPU value in that snapshot.
 */
data class WorkspaceSnapshot(
    val processes: List<WorkspaceProcessSample>,
    val topology: CpuTopology,
    val readable: Boolean,
    /** Device RAM denominator for the workspace memory bar, when `/proc/meminfo` is readable. */
    val deviceTotalKb: Long? = null
) {
    companion object {
        fun unavailable(topology: CpuTopology = CpuTopology.fallback()) =
            WorkspaceSnapshot(emptyList(), topology, readable = false)
    }
}

/**
 * Source of per-workspace resource samples.
 *
 * Screens depend on this interface, never on the concrete provider. There is
 * exactly one production implementation
 * ([com.fem.linexa.data.resource.ProcResourceMonitor], backed by `/proc`); Phase
 * 5B deleted the demo implementation that returned random walks, because a
 * fallback that fabricates plausible metrics is indistinguishable from a working
 * one on screen and the plan forbids fabricated workspace metrics outright.
 */
interface ResourceMonitor {
    /**
     * Sample the process tree rooted at [pid].
     *
     * Implementations own whatever delta state CPU requires, keyed on pid so a
     * restarted session (new pid) starts a fresh series instead of reporting a
     * spike computed against a dead process's counters.
     *
     * Never throws: an unreadable `/proc` yields
     * [WorkspaceResourceSample.unavailable].
     */
    suspend fun sample(pid: Int): WorkspaceResourceSample

    /**
     * Sample the workspace's process tree **per member** — the Sessions task
     * manager's expandable process list (App-grid redesign).
     *
     * The default returns an empty list so existing implementations (and test
     * fakes) keep compiling; the production [ProcResourceMonitor] walks the
     * same tree [sample] walks and answers per process. A process whose
     * `/proc` entry vanished between the listing and the read is simply absent
     * — racing process exit is normal, not exceptional.
     *
     * Never throws: an unreadable `/proc` yields an empty list, and the UI
     * shows "no process data" rather than rows of dashes for a tree it cannot
     * see.
     */
    suspend fun processes(pid: Int): List<WorkspaceProcessSample> = emptyList()

    /**
     * One workspace-wide snapshot for [rootPids].
     *
     * Production implementations attribute the whole workspace (descendants
     * plus the rootfs cmdline marker), not just the first root's tree. The
     * default is a compatibility fallback for fakes: merge [processes] for
     * each root and deduplicate.
     */
    suspend fun snapshot(rootPids: Collection<Int>): WorkspaceSnapshot {
        val roots = rootPids.filter { it > 0 }.distinct()
        if (roots.isEmpty()) return WorkspaceSnapshot.unavailable()
        val perRoot = roots.map { processes(it) }
        return WorkspaceSnapshot(
            processes = WorkspaceAggregator.mergeTrees(perRoot),
            topology = CpuTopology.fallback(),
            readable = perRoot.any { it.isNotEmpty() }
        )

    }

    /** Drop delta state for [pid] (session ended). */
    fun forget(pid: Int)
}
