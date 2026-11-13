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
 * The project compiles against an Android `java.lang.Process` surface where
 * the Java 9 `pid()` method is not visible to Kotlin, so this uses the same
 * reflective access as `GpuProcess` instead of a direct call. Returns null
 * when the runtime implementation does not expose a public `pid()`; callers
 * then treat the identity as unknown (fail closed) rather than guessing.
 */
internal fun processHostPid(process: Process): Int? =
    runCatching { (process.javaClass.getMethod("pid").invoke(process) as? Int) }.getOrNull()

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
