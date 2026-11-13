package com.fem.linexa.service.runtime.container

import java.io.File

/**
 * Pure decision logic for the `/proc` sweep that kills one workspace's guest
 * process tree (`killRootfsProcesses` in [PRootContainerRuntime]).
 *
 * The sweep used to be `cmdline.contains(rootfsPath)` plus nothing else, which
 * failed in both directions:
 *
 * - **Overmatching:** marker `/…/rootfs` also matched `/…/rootfs2`, any file
 *   named after it, and another app's process that merely quoted the path.
 * - **Undermatching / PID reuse:** a PIDs are recycled; a recycled PID that
 *   happens to carry the path in its argv got killed for a dead process's
 *   crimes, and a live decoy with a suffix path was killed too.
 *
 * [shouldSignal] is the whole contract: the cmdline must reference the rootfs
 * as a path boundary, the process must belong to the app's UID, and — when a
 * launch start time is known — it must have started at or after that launch.
 * Keeping it object-free lets the matcher be asserted on the JVM without
 * `android.os.Process` (whose JVM stub throws).
 */
internal object RootfsProcessMatcher {

    /**
     * True when [cmdline] names [marker] as a path, not as a substring.
     *
     * A token equals the marker exactly (PRoot's `-r <path>`, a working
     * directory, a script name) or extends it (`<path>/…` — a path *inside*
     * the rootfs). Suffix paths (`<marker>extra`), quoted-but-equal tokens and
     * unrelated arguments never match. Quotes are trimmed because some
     * producers wrap paths in argv display forms.
     */
    fun referencesRootfs(cmdline: String, marker: String): Boolean {
        val normalized = marker.trimEnd('/')
        if (normalized.isEmpty()) return false
        return tokens(cmdline).any { token ->
            token == normalized || token.startsWith("$normalized/")
        }
    }

    /** First `Uid:` value of a `/proc/<pid>/status` body, or null. */
    fun uid(status: String?): Int? {
        val line = status?.lineSequence()?.firstOrNull { it.startsWith("Uid:") } ?: return null
        val uid = line.removePrefix("Uid:").trim()
            .split(WHITESPACE)
            .firstOrNull()
            ?.toIntOrNull()
        return uid?.takeIf { it >= 0 }
    }

    /**
     * `/proc/<pid>/stat` field 22 (start time in clock ticks) — the PID-reuse
     * guard. Null when the line is unreadable/malformed.
     */
    fun startTimeTicks(stat: String?): Long? {
        val fields = fieldsAfterComm(stat ?: return null) ?: return null
        return fields.getOrNull(STARTTIME_INDEX)?.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** The launch's own start time, or null when unreadable. */
    fun startTimeTicksOf(pid: Int, procRoot: File = File("/proc")): Long? =
        startTimeTicks(runCatching { File(procRoot, "$pid/stat").readText() }.getOrNull())

    /**
     * The whole sweep decision for one `/proc/<pid>` row.
     *
     * - empty or unreadable cmdline → false (a dead row cannot hold files);
     * - [marker] must be referenced as a path;
     * - [selfUid] (when known) must equal the row's UID — `/proc`'s hidepid
     *   already limits visibility, but the explicit check is what a JVM test
     *   can assert;
     * - [notBeforeTicks] (when known) excludes PIDs started before the
     *   workspace's launch — the PID-reuse guard;
     * - unreadable/malformed start time → false (fail closed: a sweep that
     *   cannot identify a process must not signal it).
     */
    fun shouldSignal(
        cmdline: String,
        status: String?,
        stat: String?,
        marker: String,
        selfUid: Int?,
        notBeforeTicks: Long?
    ): Boolean {
        if (cmdline.isBlank()) return false
        if (!referencesRootfs(cmdline, marker)) return false
        if (selfUid != null && uid(status) != selfUid) return false
        if (notBeforeTicks != null) {
            val start = startTimeTicks(stat) ?: return false
            if (start < notBeforeTicks) return false
        }
        return true
    }

    private fun tokens(cmdline: String): List<String> =
        cmdline.split('\u0000', ' ', '\t', '\n', '\r')
            .map { it.trim('"', '\'') }
            .filter { it.isNotEmpty() }

    private fun fieldsAfterComm(statLine: String): List<String>? {
        val close = statLine.lastIndexOf(')')
        if (close < 0 || close + 1 >= statLine.length) return null
        return statLine.substring(close + 1)
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    private const val STARTTIME_INDEX = 19 // field 22
    private val WHITESPACE = Regex("\\s+")
}
