package com.fem.linexa.core.filesystem

import com.fem.linexa.core.logging.Logger
import java.io.File

/**
 * Identity of one host process, captured from `/proc/<pid>/stat`.
 *
 * [startTimeTicks] is field 22 (kernel start time). A PID alone is never an
 * identity: Android recycles PIDs aggressively, so a signal is only safe when
 * the start time still matches the value captured at launch.
 */
data class ProcessIdentity(
    val pid: Int,
    val startTimeTicks: Long,
    val processGroupId: Int?
) {
    val isComplete: Boolean get() = pid > 0 && startTimeTicks > 0
}

/** The two signals containment uses: graceful first, then forceful. */
enum class ProcessSignal { TERM, KILL }

/**
 * Platform seam so the containment logic is JVM-testable. Production maps
 * [ProcessSignal] to `android.system.Os.kill`; tests record the calls.
 *
 * [target] may be negative: `kill(-pgid, sig)` addresses a process group.
 */
fun interface ProcessSignalSender {
    fun send(target: Int, signal: ProcessSignal): Boolean
}

/**
 * Kills a process tree by process group and confirms it is gone.
 *
 * ## Why this exists
 *
 * `Process.destroyForcibly()` signals only the direct child. PRoot is a
 * tracer: its tracees are separate processes that survive the host's death
 * (and `--kill-on-exit` is an exit handler, so it never runs after SIGKILL).
 * A timed-out extraction could therefore leave tar running while the caller
 * deleted the staging tree underneath it.
 *
 * ## Mechanism
 *
 * The child is launched through the bundled `setsid`, so it becomes a session
 * and process-group leader (`pgid == pid`). [killTree] sends SIGTERM to the
 * **group**, waits [graceMs], then sends SIGKILL and waits up to
 * [hardLimitMs] for every group member to disappear. When no separate group
 * is available it signals the single PID and reports whether that PID is
 * gone; it never signals a group it cannot prove belongs to the child, and
 * never `kill(0, ...)`.
 *
 * A member that calls `setsid` itself leaves the group. That is handled by the
 * runtime's cmdline sweep, not here; extraction runs only tar, which cannot
 * spawn a new session from archive data.
 *
 * All `/proc` access and time/sleep are injectable so the TERM→KILL sequence,
 * PID-reuse rejection and timeout path are asserted on the JVM.
 */
/**
 * Best-effort host PID of a JVM child process.
 *
 * Resolution order:
 *
 *  1. `Process.pid()` through the **public** [Process] class (never through
 *     `process.javaClass`, whose implementation type is package-private). It
 *     is a public API on the JVM, but Android's SDK (`android.jar`) does not
 *     declare it: the method exists only in libcore's implementation, so a
 *     normal app targeting a released SDK receives a hidden-API denial and
 *     every lookup returns null. That regression made every extraction and
 *     runtime launch on-device report "could not capture /proc identity".
 *  2. The runtime's process description. Android's `UNIXProcess.toString()`
 *     returns `Process[pid=<n>, hasExited=...]`; the JVM's returns
 *     `Process[pid=<n>, exitValue=...]`. `toString()` is public on every
 *     runtime, so it survives hidden-API enforcement. The parsed PID is
 *     still verified against `/proc/<pid>/stat` by [captureProcessIdentity]
 *     before anything is signalled, so a misleading description can only
 *     fail closed.
 *
 * Returns null when neither path yields a positive PID; callers then treat
 * the identity as unknown (fail closed) rather than guessing.
 */
internal fun processHostPid(process: Process): Int? {
    val direct = runCatching {
        val method = Process::class.java.getMethod("pid")
        when (val pid = method.invoke(process)) {
            is Int -> pid
            is Long -> pid.toInt()
            else -> null
        }
    }.getOrNull()
    if (direct != null && direct > 0) return direct

    // Android: `pid()` is not in the SDK, so fall back to the public
    // description. Never guess: an unparsable description returns null.
    return runCatching { parseHostPidFromDescription(process.toString()) }.getOrNull()
}

/**
 * Extracts the PID from the `toString()` shape shared by Android's
 * `UNIXProcess` (`Process[pid=8445, hasExited=false]`) and the JVM's
 * `ProcessImpl` (`Process[pid=8445, exitValue=0]`).
 */
internal fun parseHostPidFromDescription(description: String): Int? =
    PROCESS_PID_IN_DESCRIPTION.find(description)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

private val PROCESS_PID_IN_DESCRIPTION = Regex("""\bpid=(\d+)""")

class ProcessContainment(
    private val expectedUid: Int?,
    private val sender: ProcessSignalSender,
    private val procRoot: File = File("/proc"),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    /** Reads the current identity for [pid], or null when the PID is gone. */
    fun identityOf(pid: Int): ProcessIdentity? {
        if (pid <= 0) return null
        val stat = readStat(pid) ?: return null
        return ProcessIdentity(
            pid = pid,
            startTimeTicks = stat.startTimeTicks,
            processGroupId = stat.processGroupId
        )
    }

    /**
     * Sends SIGTERM to the tree, then SIGKILL after [graceMs], and polls until
     * it is gone or [hardLimitMs] expires.
     *
     * Returns true only when every member of the child's process group is
     * confirmed gone (or, without a group, the child's PID is gone or has been
     * reused). Returns false when containment could not be confirmed, when the
     * identity is incomplete, or when the UID check fails — callers must treat
     * false as "do not delete the tree underneath it".
     */
    fun killTree(
        identity: ProcessIdentity,
        graceMs: Long = DEFAULT_GRACE_MS,
        hardLimitMs: Long = DEFAULT_HARD_LIMIT_MS
    ): Boolean {
        if (!identity.isComplete) {
            Logger.w(TAG, "containment refused: incomplete identity pid=${identity.pid} start=${identity.startTimeTicks}")
            return false
        }
        val current = readStat(identity.pid)
        if (current == null) {
            Logger.i(TAG, "containment: pid ${identity.pid} already gone")
            return true
        }
        if (current.startTimeTicks != identity.startTimeTicks) {
            Logger.i(
                TAG,
                "containment: pid ${identity.pid} was reused (start ${identity.startTimeTicks} -> " +
                    "${current.startTimeTicks}); not signalling"
            )
            return true
        }
        if (current.stateChar == 'Z') {
            Logger.i(TAG, "containment: pid ${identity.pid} is already a zombie")
            return true
        }
        if (!uidMatches(identity.pid)) {
            Logger.w(TAG, "containment refused: pid ${identity.pid} is not this app's uid")
            return false
        }

        val group = identity.processGroupId?.takeIf { it > 1 && it == identity.pid }
        val target = if (group != null) -group else identity.pid
        Logger.i(
            TAG,
            "containment: signalling ${if (group != null) "group $group" else "pid ${identity.pid}"} " +
                "SIGTERM (start ${identity.startTimeTicks})"
        )
        sender.send(target, ProcessSignal.TERM)

        val graceDeadline = clockMs() + graceMs.coerceAtLeast(0)
        while (true) {
            if (isTreeGone(identity, group)) {
                Logger.i(TAG, "containment: tree gone after SIGTERM (pid ${identity.pid})")
                return true
            }
            if (clockMs() >= graceDeadline) break
            sleep(POLL_INTERVAL_MS)
        }

        Logger.w(TAG, "containment: pid ${identity.pid} ignored SIGTERM; sending SIGKILL")
        sender.send(target, ProcessSignal.KILL)

        val hardDeadline = clockMs() + hardLimitMs.coerceAtLeast(0)
        while (true) {
            if (isTreeGone(identity, group)) {
                Logger.i(TAG, "containment: tree gone after SIGKILL (pid ${identity.pid})")
                return true
            }
            if (clockMs() >= hardDeadline) break
            sleep(POLL_INTERVAL_MS)
        }
        Logger.e(TAG, "containment FAILED: pid ${identity.pid} group=$group still alive after SIGKILL")
        return false
    }

    private fun isTreeGone(identity: ProcessIdentity, group: Int?): Boolean {
        if (group != null) return groupMembers(group).isEmpty()
        val stat = readStat(identity.pid) ?: return true
        if (stat.startTimeTicks != identity.startTimeTicks) return true
        return stat.stateChar == 'Z'
    }

    /** PIDs that still belong to [group] and to this app's UID. */
    private fun groupMembers(group: Int): List<Int> {
        val entries = procRoot.listFiles { file -> file.name.toIntOrNull() != null } ?: return emptyList()
        return entries.mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val stat = readStat(pid) ?: return@mapNotNull null
            if (stat.processGroupId != group) return@mapNotNull null
            if (stat.stateChar == 'Z') return@mapNotNull null
            if (!uidMatches(pid)) return@mapNotNull null
            pid
        }
    }

    private fun uidMatches(pid: Int): Boolean {
        val expected = expectedUid ?: return true
        val status = try {
            File(procRoot, "$pid/status").readText()
        } catch (_: Exception) {
            return false
        }
        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")
            ?.trim()
            ?.split(WHITESPACE)
            ?.firstOrNull()
            ?.toIntOrNull()
        return uid == expected
    }

    private fun readStat(pid: Int): ProcStat? {
        val line = try {
            File(procRoot, "$pid/stat").readText()
        } catch (_: Exception) {
            return null
        }
        return parseStat(line)
    }

    internal data class ProcStat(
        val stateChar: Char,
        val processGroupId: Int?,
        val startTimeTicks: Long
    )

    companion object {
        private const val TAG = "ProcessContainment"
        const val DEFAULT_GRACE_MS = 3_000L
        const val DEFAULT_HARD_LIMIT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 100L
        private val WHITESPACE = Regex("\\s+")

        /**
         * Parses `/proc/<pid>/stat` without assuming a single-token `comm`:
         * field 2 may contain spaces and parentheses, so every later field is
         * indexed from the **last** `)`.
         */
        internal fun parseStat(line: String): ProcStat? {
            val close = line.lastIndexOf(')')
            if (close < 0 || close + 1 >= line.length) return null
            val fields = line.substring(close + 1).trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
            // field 3 = state (index 0), field 5 = pgrp (index 2),
            // field 22 = starttime (index 19).
            val state = fields.getOrNull(0)?.firstOrNull() ?: return null
            val pgrp = fields.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
            val start = fields.getOrNull(19)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return ProcStat(stateChar = state, processGroupId = pgrp, startTimeTicks = start)
        }
    }
}
