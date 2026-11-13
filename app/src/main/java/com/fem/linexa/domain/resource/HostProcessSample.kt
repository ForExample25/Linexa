package com.fem.linexa.domain.resource

/**
 * One process readable through unprivileged `/proc` access.
 *
 * This is the Android-host scope, not the workspace tree. It may contain the
 * Linexa app, its helper processes, and PRoot/guest processes, because Android
 * commonly exposes same-UID processes. It never claims to be every process on
 * the device: unreadable PIDs are counted in [HostProcessSnapshot] and the UI
 * says so.
 */
data class HostProcessSample(
    val pid: Int,
    /** Kernel start time, paired with [pid] to reject PID reuse at signal time. */
    val startTimeTicks: Long,
    val parentPid: Int?,
    val uid: Int?,
    val name: String,
    /** Sanitized `/proc/<pid>/cmdline`, bounded for display. */
    val command: String?,
    val stateChar: Char,
    val threads: Int?,
    val cpuPercentOfDevice: Float?,
    val cpuAvailability: MetricAvailability,
    val memKb: Long?,
    val memoryIsProportional: Boolean?,
    val lastCore: Int?,
    val isSelf: Boolean,
    /** True only when the process belongs to Linexa's UID and is not Linexa itself. */
    val canSignal: Boolean,
    /** Set by a screen when the pid is a workspace/session root it must protect. */
    val isProtected: Boolean = false
)

/**
 * Result of one host scan.
 *
 * [readableProcesses] and [inaccessibleProcesses] are the honesty contract:
 * Android may deny `/proc/<pid>` for most of the device, so the UI can say
 * "showing N readable processes; M could not be read" instead of implying a
 * complete process table.
 */
data class HostProcessSnapshot(
    val processes: List<HostProcessSample>,
    val readableProcesses: Int,
    val inaccessibleProcesses: Int,
    val procListReadable: Boolean,
    val selfUid: Int?,
    val topology: CpuTopology
) {
    val scopeLimited: Boolean
        get() = !procListReadable || inaccessibleProcesses > 0
}
