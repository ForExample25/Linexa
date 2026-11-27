package com.fem.linexa.core.filesystem

import com.fem.linexa.core.logging.Logger
import java.io.File

/**
 * One row of the `/proc` process table.
 *
 * [startTimeTicks] is field 22 of `/proc/<pid>/stat` (kernel start time). A
 * PID is never an identity on its own: Android recycles PIDs aggressively, so
 * a signal is only safe while the start time still matches the value captured
 * when the process was observed.
 */
data class ProcessTreeEntry(
    val pid: Int,
    val parentPid: Int,
    val processGroupId: Int,
    val startTimeTicks: Long,
    val state: Char
) {
    /** A zombie has already exited; signalling it would be a no-op on a recycled PID. */
    val isLive: Boolean get() = state != 'Z'
}

/**
 * An immutable snapshot of the own-UID-visible `/proc` table.
 *
 * ## Why a snapshot
 *
 * Component ownership must be decided in one pass and **before** anything is
 * signalled. Once a PRoot host is killed its tracees are reparented to init;
 * a later PPID walk can no longer find them. The snapshot is therefore the
 * authoritative membership list for one termination operation.
 */
class ProcessTable internal constructor(
    private val entries: Map<Int, ProcessTreeEntry>
) {

    fun entry(pid: Int): ProcessTreeEntry? = entries[pid]

    /** Every live descendant of [rootPid], breadth-first, never including the root. */
    fun descendantsOf(rootPid: Int): List<ProcessTreeEntry> {
        val childrenByParent = entries.values.filter { it.isLive }.groupBy { it.parentPid }
        val result = ArrayList<ProcessTreeEntry>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current].orEmpty().forEach { child ->
                if (seen.add(child.pid)) {
                    result += child
                    queue.add(child.pid)
                }
            }
        }
        return result
    }

    /**
     * Every live member of [processGroupId].
     *
     * Used as a hardening layer when the root is a session/group leader
     * (`pgid == pid`, e.g. a PTY child): a member that double-forks and is
     * reparented stays in the group, so the group is still addressable after
     * the PPID chain is broken.
     */
    fun membersOfGroup(processGroupId: Int): List<ProcessTreeEntry> =
        entries.values.filter { it.isLive && it.processGroupId == processGroupId }
}

/**
 * Reads and parses `/proc` without depending on `android.os.Process`.
 *
 * Kept as a pure object so the whole ownership model is testable against a
 * fake `/proc` root on the JVM.
 */
object ProcessTableReader {

    fun read(procRoot: File = File("/proc")): ProcessTable {
        val entries = HashMap<Int, ProcessTreeEntry>()
        procRoot.listFiles { file -> file.name.isNotEmpty() && file.name.all(Char::isDigit) }
            ?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
                parse(pid, stat)?.let { entries[pid] = it }
            }
        return ProcessTable(entries)
    }

    fun readEntry(pid: Int, procRoot: File = File("/proc")): ProcessTreeEntry? {
        if (pid <= 0) return null
        val stat = runCatching { File(procRoot, "$pid/stat").readText() }.getOrNull() ?: return null
        return parse(pid, stat)
    }

    /**
     * Parses `/proc/<pid>/stat`. `comm` (field 2) may contain spaces and
     * parentheses, so every later field is indexed from the **last** `)`:
     * index 0 = state (field 3), index 1 = ppid (field 4),
     * index 2 = pgrp (field 5), index 19 = starttime (field 22).
     */
    internal fun parse(pid: Int, stat: String): ProcessTreeEntry? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 1 >= stat.length) return null
        val fields = stat.substring(close + 1).trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
        val state = fields.getOrNull(0)?.firstOrNull() ?: return null
        val parentPid = fields.getOrNull(1)?.toIntOrNull() ?: return null
        val processGroupId = fields.getOrNull(2)?.toIntOrNull() ?: return null
        val startTimeTicks = fields.getOrNull(19)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcessTreeEntry(pid, parentPid, processGroupId, startTimeTicks, state)
    }

    private val WHITESPACE = Regex("\\s+")
}

/** Outcome of one component termination. */
data class ProcessTreeTerminationReport(
    /** Processes in the target set (root(s) + descendants + group members). */
    val targets: Int,
    /** Successful SIGTERM sends. */
    val signalled: Int,
    /** Successful SIGKILL sends during escalation. */
    val escalated: Int,
    /** PIDs still alive and start-time-identical after the hard limit. */
    val survivors: List<Int>
) {
    /** True only when the whole target set is confirmed gone or reused. */
    val confirmed: Boolean get() = survivors.isEmpty()
}

/**
 * Terminates exactly one component's process tree — never a workspace.
 *
 * ## Contract
 *
 * 1. **Snapshot before signalling.** The tree is computed while the root is
 *    still alive; PRoot's tracees are ordinary children of the PRoot host, so
 *    the PPID chain identifies them. After the host dies the chain is broken.
 * 2. **Identity, not PID.** Every member carries its start time; a member
 *    whose `/proc/<pid>/stat` start time changed (PID reuse) is never
 *    signalled, and counts as already gone.
 * 3. **UID guard.** Only own-UID processes are signalled.
 * 4. **Group hardening.** When the root's process group equals its PID (a
 *    session leader — PTY children, or a `setsid`-wrapped launch), every live
 *    group member is included, covering members that reparented away.
 * 5. **Verified termination.** SIGTERM, bounded grace, SIGKILL, bounded hard
 *    limit, then a final identity check. Success is only reported when the
 *    target set is confirmed gone.
 *
 * All platform interaction is injected so the model is asserted on the JVM
 * with a fake `/proc` and a recording sender.
 */
class ProcessTreeTerminator(
    private val expectedUid: Int?,
    private val sender: ProcessSignalSender,
    private val procRoot: File = File("/proc"),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val selfPid: Int? = null
) {

    fun terminate(
        roots: List<ProcessIdentity>,
        graceMs: Long = DEFAULT_GRACE_MS,
        hardLimitMs: Long = DEFAULT_HARD_LIMIT_MS
    ): ProcessTreeTerminationReport {
        // One snapshot decides membership for the whole operation. Root
        // identity is checked inside that snapshot, so there is no window
        // between an "is it alive?" probe and the tree read.
        val table = ProcessTableReader.read(procRoot)
        val liveRoots = roots.filter { root ->
            if (!root.isComplete) return@filter false
            val entry = table.entry(root.pid) ?: return@filter false
            entry.startTimeTicks == root.startTimeTicks && entry.isLive
        }
        if (liveRoots.isEmpty()) {
            return ProcessTreeTerminationReport(0, 0, 0, emptyList())
        }

        val targets = linkedMapOf<Int, ProcessIdentity>()
        liveRoots.forEach { root ->
            table.entry(root.pid)?.takeIf { it.startTimeTicks == root.startTimeTicks && it.isLive }?.let {
                addTarget(targets, it)
            }
            table.descendantsOf(root.pid).forEach { addTarget(targets, it) }
            // Group hardening: only when the recorded group really is this
            // root's own group (never kill the app's inherited group).
            root.processGroupId
                ?.takeIf { it > 1 && it == root.pid }
                ?.let { group -> table.membersOfGroup(group).forEach { addTarget(targets, it) } }
        }
        if (targets.isEmpty()) {
            return ProcessTreeTerminationReport(0, 0, 0, emptyList())
        }

        Logger.i(
            TAG,
            "containment: terminating ${targets.size} process(es) " +
                "roots=${liveRoots.joinToString { "${it.pid}@${it.startTimeTicks}" }}"
        )

        var signalled = 0
        targets.values.forEach { identity ->
            if (!uidMatches(identity.pid)) return@forEach
            if (isGone(identity)) return@forEach
            if (sender.send(identity.pid, ProcessSignal.TERM)) signalled++
        }

        if (awaitAllGone(targets.values, graceMs)) {
            Logger.i(TAG, "containment: component tree gone after SIGTERM (targets=${targets.size})")
            return ProcessTreeTerminationReport(targets.size, signalled, 0, emptyList())
        }

        // Escalation: include anything the survivors forked during the grace
        // window, then SIGKILL every still-live, still-identical member.
        val escalate = linkedMapOf<Int, ProcessIdentity>()
        targets.values.forEach { identity ->
            if (isGone(identity)) return@forEach
            escalate[identity.pid] = identity
            ProcessTableReader.read(procRoot).descendantsOf(identity.pid).forEach { addTarget(escalate, it) }
        }
        var escalated = 0
        escalate.values.forEach { identity ->
            if (!uidMatches(identity.pid)) return@forEach
            if (isGone(identity)) return@forEach
            if (sender.send(identity.pid, ProcessSignal.KILL)) escalated++
        }

        val allGone = awaitAllGone(escalate.values.ifEmpty { targets.values }, hardLimitMs)
        val survivors = escalate.values
            .filter { !isGone(it) }
            .map { it.pid }
        if (allGone) {
            Logger.i(TAG, "containment: component tree gone after SIGKILL (targets=${escalate.size})")
        } else {
            Logger.e(TAG, "containment FAILED: still alive after SIGKILL: $survivors")
        }
        return ProcessTreeTerminationReport(
            targets = targets.size,
            signalled = signalled,
            escalated = escalated,
            survivors = survivors
        )
    }

    private fun addTarget(targets: MutableMap<Int, ProcessIdentity>, entry: ProcessTreeEntry) {
        if (!entry.isLive) return
        if (entry.pid <= 0) return
        if (selfPid != null && entry.pid == selfPid) return
        targets.putIfAbsent(
            entry.pid,
            ProcessIdentity(entry.pid, entry.startTimeTicks, entry.processGroupId)
        )
    }

    private fun awaitAllGone(identities: Collection<ProcessIdentity>, timeoutMs: Long): Boolean {
        val deadline = clockMs() + timeoutMs.coerceAtLeast(0)
        while (true) {
            if (identities.all { isGone(it) }) return true
            if (clockMs() >= deadline) return identities.all { isGone(it) }
            sleep(pollIntervalMs)
        }
    }

    /**
     * A PID is gone when its row vanished, its start time changed (reuse), or
     * it is a zombie. All three mean "signalling it is unnecessary and
     * unsafe".
     */
    private fun isGone(identity: ProcessIdentity): Boolean {
        val entry = ProcessTableReader.readEntry(identity.pid, procRoot) ?: return true
        if (entry.startTimeTicks != identity.startTimeTicks) return true
        return !entry.isLive
    }

    private fun uidMatches(pid: Int): Boolean {
        val expected = expectedUid ?: return true
        val status = runCatching { File(procRoot, "$pid/status").readText() }.getOrNull() ?: return false
        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")
            ?.trim()
            ?.split(WHITESPACE)
            ?.firstOrNull()
            ?.toIntOrNull()
        return uid == expected
    }

    companion object {
        private const val TAG = "ProcessContainment"
        const val DEFAULT_GRACE_MS = 3_000L
        const val DEFAULT_HARD_LIMIT_MS = 10_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 100L
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Captures the identity of a freshly started [Process] for later containment.
 *
 * Returns null when the host PID cannot be read or `/proc/<pid>/stat` is
 * unavailable; callers must then stop the direct child only and log that
 * guest-tree containment was unavailable — never fall back to a workspace
 * sweep.
 */
internal fun captureProcessIdentity(
    process: Process,
    procRoot: File = File("/proc")
): ProcessIdentity? {
    val pid = processHostPid(process) ?: return null
    val entry = ProcessTableReader.readEntry(pid, procRoot) ?: return null
    return ProcessIdentity(
        pid = pid,
        startTimeTicks = entry.startTimeTicks,
        processGroupId = entry.processGroupId
    )
}
