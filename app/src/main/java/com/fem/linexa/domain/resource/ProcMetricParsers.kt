package com.fem.linexa.domain.resource

/**
 * Pure parsers for the `/proc` files the workspace performance panel reads
 * (UI/UX Phase 5B).
 *
 * These are separated from the sampler that reads the filesystem for one
 * reason: every one of them is a **text format with a trap in it**, and a trap
 * in a parser is invisible on screen — it produces a plausible number. Keeping
 * them pure means the traps are asserted in `ProcMetricParsersTest` instead of
 * being judged by looking at a chart.
 *
 * The traps, concretely:
 *
 * - `/proc/<pid>/stat` puts the executable name in field 2, wrapped in
 *   parentheses, and that name **may contain spaces and parentheses**. Splitting
 *   the whole line on whitespace therefore shifts every subsequent field by the
 *   number of spaces in the process name. The pre-Phase-5B implementation did
 *   exactly that and read `utime`/`stime` at fixed indices of the raw split; it
 *   happened to work only because `proot` and `bash` have single-token names.
 *   Every function here locates the **last** `)` first and indexes from there.
 * - The scheduler fields are in *clock ticks*, not milliseconds, and the tick
 *   rate is a runtime constant ([ProcMetricParsers] does not assume 100).
 * - `VmRSS` double-counts pages shared between processes, so a process tree's
 *   RSS sum overstates its footprint; `smaps_rollup`'s `Pss` divides each shared
 *   page by its number of sharers and is the value that sums correctly across a
 *   tree. Both parsers exist so the sampler can prefer Pss and fall back.
 */
object ProcMetricParsers {

    /**
     * One parsed `/proc/<pid>/stat` sample shaped for the environment tick
     * sampler: scheduler-charged busy ticks INCLUDING reaped children, the
     * process state character, and the core the process last ran on.
     */
    data class PidTickSample(
        val busyTicks: Long,
        val stateChar: Char,
        /** Kernel core number (field 39) the PID last executed on; null when withheld. */
        val lastCore: Int?
    )

    /**
     * A full process identity for the monitors that need more than the
     * environment tick sampler: own (non-child) ticks for row arithmetic,
     * the child-inclusive total for compatibility, the kernel start time for
     * PID-reuse rejection, and the parent link for tree discovery.
     */
    data class PidStatSample(
        val parentPid: Int?,
        val stateChar: Char,
        /** `utime + stime`; what a process row may sum without double counting. */
        val ownTicks: Long,
        /** `utime + stime + cutime + cstime`; retained for callers that need it. */
        val busyTicks: Long,
        /** Field 22: kernel start time in clock ticks. Identity, not CPU. */
        val startTimeTicks: Long,
        val lastCore: Int?,
        val name: String
    )

    /**
     * Parse everything a process monitor needs from one `/proc/<pid>/stat`
     * line. Null when any required field is malformed; callers skip the row
     * rather than inventing zeroes.
     */
    fun pidStatSample(statLine: String): PidStatSample? {
        val fields = fieldsAfterComm(statLine) ?: return null
        val state = fields.getOrNull(STATE_INDEX)?.trim()?.firstOrNull() ?: return null
        val ppid = fields.getOrNull(PPID_INDEX)?.toIntOrNull() ?: return null
        val u = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
        val s = fields.getOrNull(STIME_INDEX)?.toLongOrNull() ?: return null
        val cu = fields.getOrNull(CUTIME_INDEX)?.toLongOrNull() ?: return null
        val cs = fields.getOrNull(CSTIME_INDEX)?.toLongOrNull() ?: return null
        val start = fields.getOrNull(STARTTIME_INDEX)?.toLongOrNull() ?: return null
        if (u < 0 || s < 0 || cu < 0 || cs < 0 || start < 0 || ppid < 0) return null
        val core = fields.getOrNull(PROCESSOR_INDEX)?.toIntOrNull()
        return PidStatSample(
            parentPid = ppid,
            stateChar = state,
            ownTicks = u + s,
            busyTicks = u + s + cu + cs,
            startTimeTicks = start,
            lastCore = core?.takeIf { it >= 0 },
            name = processName(statLine).orEmpty()
        )
    }

    /**
     * Full sampler-grade parse of a `/proc/<pid>/stat` line.
     *
     * busyTicks = utime + stime + cutime + cstime (fields 14–17): including
     * the children columns is what lets a pid-stable sampler observe work
     * done by short-lived guest children that die between passes without
     * double counting live ones (children folds into the parent when reaped).
     * Null (the unknown sentinel, never 0) when any required numeric field
     * is missing/negative.
     */
    fun pidTickSample(statLine: String): PidTickSample? {
        val fields = fieldsAfterComm(statLine) ?: return null
        val state = fields.getOrNull(STATE_INDEX)?.trim()?.firstOrNull() ?: return null
        val u = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
        val s = fields.getOrNull(STIME_INDEX)?.toLongOrNull() ?: return null
        val cu = fields.getOrNull(CUTIME_INDEX)?.toLongOrNull() ?: return null
        val cs = fields.getOrNull(CSTIME_INDEX)?.toLongOrNull() ?: return null
        val core = fields.getOrNull(PROCESSOR_INDEX)?.toIntOrNull()
        if (u < 0 || s < 0 || cu < 0 || cs < 0) return null
        return PidTickSample(
            busyTicks = u + s + cu + cs,
            stateChar = state,
            lastCore = core?.takeIf { it >= 0 }
        )
    }

    /**
     * The CPU affinity mask (`sched_getaffinity`) of a `/proc/<pid>/status`
     * body, expanded from the `Cpus_allowed_list:` range syntax ("0-3,6").
     *
     * THIS is the number `nproc` reports — the scheduler-enforced subset —
     * not `_SC_NPROCESSORS_CONF`. On cpuset-restricted Android devices the
     * app (and therefore every PRoot child) is granted fewer cores than are
     * configured; surfacing that fact separately is the Phase 2 decision.
     * Null when the line is missing or malformed (unknown ≠ empty-set:
     * callers fall back to "all configured cores" rather than assuming a
     * restriction nobody measured).
     */
    fun cpuAffinityAllowedList(statusBody: String): List<Int>? {
        val line = statusBody.lineSequence()
            .firstOrNull { it.startsWith("Cpus_allowed_list:") } ?: return null
        return cpuRangeList(line.removePrefix("Cpus_allowed_list:").trim())
    }

    /**
     * Expand Linux range syntax ("0-3,6") to sorted core numbers. Used for
     * both `/sys/devices/system/cpu/online` and `Cpus_allowed_list`; null when
     * the text is absent or malformed, never an empty-list-as-unknown.
     */
    fun cpuRangeList(value: String?): List<Int>? {
        if (value.isNullOrBlank()) return null
        val out = sortedSetOf<Int>()
        for (part in value.trim().split(',')) {
            if (part.isBlank()) continue
            val range = part.trim().split('-')
            val start = range.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val end = if (range.size > 1) range[1].trim().toIntOrNull() ?: return null else start
            if (start < 0 || end < start || end - start > MAX_AFFINITY_SPAN) return null
            for (core in start..end) out.add(core)
        }
        return out.takeIf { it.isNotEmpty() }?.toList()
    }

    /**
     * Cumulative per-core tick totals summed across all ten columns of every
     * `cpuN` line in a `/proc/stat` body, indexed by KERNEL CORE NUMBER.
     *
     * These absolutes are what make the guest stat overlay's per-core rows
     * genuinely moving counters (guest-side `top` diffs successive overlay
     * writes): the previous implementation emitted constant-zero per-core
     * lines EVEN WHEN THE HOST ANSWERED, so multi-core htop renderings never
     * left 0.00%. Requires a well-formed line per core; any malformed line
     * voids the grid (null) rather than admitting a partial silent lie.
     */
    fun procStatPerCoreTotals(procStatBody: String): Map<Int, Long>? {
        val out = LinkedHashMap<Int, Long>()
        for (raw in procStatBody.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("cpu") || line.length < 4) continue
            if (!line[3].isDigit()) continue
            val digits = line.drop(3).takeWhile { it.isDigit() }
            if (digits.isEmpty()) continue
            val core = digits.toIntOrNull() ?: continue
            // STRICT parse: a non-numeric token voids the ENTIRE grid rather
            // than shrinking silently into plausible-looking sums — a partial
            // grid presented as fact would be a fabricated truth hole.
            val rawCols = line.drop(3 + digits.length).trim().split(WHITESPACE)
            if (rawCols.size < MIN_STAT_COLUMNS) return null
            val cols = ArrayList<Long>(rawCols.size)
            for (token in rawCols) {
                val value = token.toLongOrNull() ?: return null
                if (value < 0) return null
                cols.add(value)
            }
            // Column 10 (sum index) = guest_nice double-counts user/system;
            // the kernel itself excludes guest from its own denominators, and
            // so does the aggregate delta math elsewhere — stay consistent.
            out[core] = cols.sum()
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Sum of `utime` + `stime` (fields 14 and 15) from a `/proc/<pid>/stat`
     * line, in clock ticks, or null when the line is not parseable.
     *
     * Null is distinct from `0L` on purpose: zero is a real reading for an idle
     * process, whereas null means "this process's CPU time is unknown" and must
     * not be added into a total as if it were idle.
     */
    fun cpuTicks(statLine: String): Long? {
        val fields = fieldsAfterComm(statLine) ?: return null
        // Field 3 (state) is index 0 here, so field N is index N - 3.
        val utime = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(STIME_INDEX)?.toLongOrNull() ?: return null
        if (utime < 0 || stime < 0) return null
        return utime + stime
    }

    /**
     * Parent pid (field 4) from a `/proc/<pid>/stat` line, or null.
     *
     * This is how the process tree is discovered. The obvious alternative,
     * `/proc/<pid>/task/<tid>/children`, requires `CONFIG_PROC_CHILDREN` and is
     * **absent on the Android kernels this app targets** — verified missing on a
     * 6.6 android15 kernel during Phase 5B. The previous implementation read it
     * unconditionally inside a `catch (_: Exception) { null }`, so on every such
     * device the walk silently found no children and the panel reported only the
     * `proot` supervisor's own CPU time while the guest did the work.
     */
    fun parentPid(statLine: String): Int? {
        val fields = fieldsAfterComm(statLine) ?: return null
        val ppid = fields.getOrNull(PPID_INDEX)?.toIntOrNull() ?: return null
        return if (ppid < 0) null else ppid
    }

    /**
     * The process name (field 2, `comm`) with its parentheses removed, or null.
     *
     * Used only for diagnostics, but it is the same field whose contents break
     * naive parsing, so it is extracted with the same rule.
     */
    fun processName(statLine: String): String? {
        val open = statLine.indexOf('(')
        val close = statLine.lastIndexOf(')')
        if (open < 0 || close < open + 1) return null
        return statLine.substring(open + 1, close)
    }

    /**
     * `Pss` in kB from a `/proc/<pid>/smaps_rollup` body, or null when the file
     * has no such line (or is unreadable, in which case the caller passes null
     * content and never reaches here).
     *
     * Proportional set size: each page is charged to this process divided by the
     * number of processes mapping it. That is precisely what makes it summable
     * across a process tree — a workspace's `proot` and its guest children share
     * a great deal, and summing `VmRSS` over them counts that shared memory once
     * per process.
     */
    fun pssKb(smapsRollup: String): Long? = firstFieldValue(smapsRollup, "Pss:")

    /**
     * `VmRSS` in kB from a `/proc/<pid>/status` body, or null.
     *
     * The fallback when `smaps_rollup` is unavailable (it requires kernel
     * support and is not readable for every process). Callers must treat a sum
     * of these as an **upper bound**, not a measurement.
     */
    fun vmRssKb(status: String): Long? = firstFieldValue(status, "VmRSS:")

    /**
     * First UID from `/proc/<pid>/status`'s `Uid:` row (real, effective,
     * saved, filesystem). Null when missing/negative.
     */
    fun statusUid(status: String): Int? {
        val line = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        val uid = line.removePrefix("Uid:").trim().split(WHITESPACE)
            .firstOrNull()
            ?.toIntOrNull()
            ?: return null
        return uid.takeIf { it >= 0 }
    }

    /** `/proc/<pid>/status`'s `Threads:` count, or null. */
    fun statusThreads(status: String): Int? =
        firstFieldValue(status, "Threads:")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    /**
     * `/proc/<pid>/cmdline` → one bounded display line.
     *
     * NUL-separated argv is joined with spaces; repeated whitespace is
     * collapsed. This is deliberately a display projection, not the raw form
     * [ProcessAttribution] matches on, because paths with meaningful spaces
     * must remain matchable there.
     */
    fun commandLine(raw: String?, maxChars: Int = 512): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace('\u0000', ' ')
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (text.isEmpty()) return null
        return if (text.length > maxChars) text.take(maxChars) else text
    }

    /** `MemTotal` in kB from a `/proc/meminfo` body, or null. */
    fun memTotalKb(meminfo: String): Long? = firstFieldValue(meminfo, "MemTotal:")

    /**
     * Any `key: value kB` line's value from a `/proc/meminfo` body, or null.
     *
     * Generalization of [memTotalKb] for the host-memory collector
     * (`MemFree`, `MemAvailable`, `Buffers`, `Cached`, `SReclaimable`,
     * `SwapTotal`, `SwapFree` — all share meminfo's exact shape). Same trap
     * rules apply: null for absent/invalid, never 0-as-unknown, negative
     * rejected rather than propagated.
     */
    fun meminfoValueKb(meminfo: String, label: String): Long? =
        firstFieldValue(meminfo, "$label:")

    /**
     * Fields of a `/proc/<pid>/stat` line from field 3 (`state`) onwards, or
     * null if the line has no `comm` parentheses at all.
     *
     * Splitting after the **last** `)` is what makes a name like
     * `(sh (deleted))` or `(my app)` safe.
     */
    private fun fieldsAfterComm(statLine: String): List<String>? {
        val close = statLine.lastIndexOf(')')
        if (close < 0 || close + 1 >= statLine.length) return null
        return statLine.substring(close + 1)
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * The first numeric value on the first line starting with [label] in a
     * `key: value kB` style file.
     */
    private fun firstFieldValue(body: String, label: String): Long? {
        val line = body.lineSequence().firstOrNull { it.startsWith(label) } ?: return null
        val value = line.removePrefix(label)
            .trim()
            .split(WHITESPACE)
            .firstOrNull()
            ?.toLongOrNull()
            ?: return null
        return if (value < 0) null else value
    }

    private val WHITESPACE = Regex("\\s+")

    // Field 3 is index 0 in the post-comm token list, so field N is index N-3.
    private const val PPID_INDEX = 1    // field 4
    private const val STATE_INDEX = 0   // field 3
    private const val UTIME_INDEX = 11  // field 14
    private const val STIME_INDEX = 12  // field 15
    private const val CUTIME_INDEX = 13 // field 16
    private const val CSTIME_INDEX = 14 // field 17
    private const val STARTTIME_INDEX = 19 // field 22 (process start time)
    private const val PROCESSOR_INDEX = 36 // field 39 (last-ran CPU)

    /** A /proc/stat cpuN row must carry through at least the steal column. */
    private const val MIN_STAT_COLUMNS = 8

    /** Sanity bound so a corrupt status line cannot balloon an expansion loop. */
    private const val MAX_AFFINITY_SPAN = 1024
}
