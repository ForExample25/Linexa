package com.fem.linexa.domain.resource

/**
 * One process of the workspace's process tree, as the Sessions task manager
 * renders it (App-grid redesign — the expandable per-process list under the
 * task-manager card).
 *
 * Every metric follows the panel's honesty contract ([WorkspaceResourceSample],
 * [ResourceMonitor]): a value the kernel did not answer is **null**, and the UI
 * renders a dash — never a fabricated zero.
 *
 * @property pid the kernel pid. The workspace tree lives in the host pid
 *   namespace (PRoot does not virtualize pids), so this is the same number a
 *   guest-side `kill` addresses — which is what makes the row's kill action a
 *   real operation rather than decoration.
 * @property name the process's `comm` (`/proc/<pid>/stat` field 2, parentheses
 *   stripped). A command name, not a full command line — `/proc/<pid>/cmdline`
 *   is readable but unbounded, and the row is one line tall.
 * @property stateChar the `/proc/<pid>/stat` state character (R, S, D, Z, T, I…).
 *   The UI maps it to a label; an unmapped character renders as-is rather than
 *   collapsing into a wrong state.
 * @property cpuPercentOfDevice this process's CPU share of the device's total
 *   capacity (0..100, normalized by core count — the same denominator the
 *   tree-level sample uses so a row can never contradict its card). Null on
 *   the first sample of the pid: CPU is a delta and has no answer yet.
 * @property memKb the process's memory footprint in kB — `Pss` from
 *   `smaps_rollup` when the kernel offers it (sums correctly across the tree),
 *   else `VmRSS` (an upper bound). Null when neither is readable.
 * @property isRoot true for the workspace's own root process (the `proot`
 *   supervisor): the row shows it as the tree's anchor and the kill action
 *   refuses it — ending the workspace is [closeTerminal]/runtime territory,
 *   not a per-process tap.
 */
data class WorkspaceProcessSample(
    val pid: Int,
    /** Kernel start time for PID-reuse checks; 0 when it was not read. */
    val startTimeTicks: Long = 0L,
    val name: String,
    val stateChar: Char,
    val cpuPercentOfDevice: Float?,
    val memKb: Long?,
    val isRoot: Boolean = false,
    /** Parent pid, when the process tree resolved it (Task Manager redesign). */
    val parentPid: Int? = null,
    /**
     * True when [memKb] is `Pss` (sums correctly across a tree); false when it
     * is a `VmRSS` fallback and therefore an upper bound. Null when memory was
     * unreadable. The Sessions overview aggregates per-process readings, and
     * carries this through so its workspace total can be labelled approximate
     * instead of silently overstating.
     */
    val memoryIsProportional: Boolean? = null,
    /** Host UID from `/proc/<pid>/status`; all workspace processes are the app UID. */
    val uid: Int? = null,
    /** Bounded `/proc/<pid>/cmdline` for the expanded row. */
    val command: String? = null,
    /** `/proc/<pid>/status` Threads count, when readable. */
    val threads: Int? = null,
    /** Kernel core this process last ran on (field 39), when readable. */
    val lastCore: Int? = null,
    /** Why [cpuPercentOfDevice] is null: first sample, or unreadable. */
    val cpuAvailability: MetricAvailability =
        if (cpuPercentOfDevice == null) MetricAvailability.PENDING else MetricAvailability.AVAILABLE
) {
    companion object {
        /**
         * State character → the label the process row shows, following
         * `ps(1)`'s vocabulary. Unknown characters render as themselves — a
         * kernel this code has never met must stay visible, not become
         * "unknown".
         */
        fun stateLabel(stateChar: Char): String = when (stateChar) {
            'R' -> "running"
            'S' -> "sleeping"
            'D' -> "disk sleep"
            'Z' -> "zombie"
            'T', 't' -> "stopped"
            'I' -> "idle"
            'X', 'x' -> "dead"
            'K' -> "wakekill"
            'W' -> "waking"
            'P' -> "parked"
            else -> stateChar.toString()
        }
    }
}
