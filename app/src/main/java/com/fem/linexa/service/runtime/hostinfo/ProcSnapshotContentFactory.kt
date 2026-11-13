package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetInterface
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostZramInfo
import java.util.Locale
import kotlin.math.min

/**
 * Pure generator of every `/proc` compatibility file the runtime binds over
 * the guest's Android-restricted proc paths — plus the synthetic fallbacks
 * used ONLY where the platform denies native passthrough entirely.
 *
 * This is the runtime-bridge half of "Android host → provider → runtime →
 * RootFS": every HOST-AUTHORITATIVE piece comes from a
 * [com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot], and anything the
 * host genuinely cannot see either stays byte-identical to the pre-existing
 * R-6 synthetic shape OR is produced by the documented environment-scoped
 * accounting model — never invented wholesale.
 *
 * ══ Mode table (stat / loadavg) ══════════════════════════════════════════
 *
 * | context                        | stat sources                          |
 * |--------------------------------|---------------------------------------|
 * | HOST    — host /proc/stat read | real aggregate + REAL per-core totals |
 * |                                 | (grid added 2026-08-27; previously    |
 * |                                 | per-core was always-zero even here)   |
 * | ENV     — stat restricted      | [EnvTickSampler.Snapshot] cumulative  |
 * |                                 | LXCFS-style accounting of the Linexa  |
 * |                                 | process tree; document-scope honest   |
 * | LEGACY  — first sample yet     | R-6 zeroed counters (unchanged shape) |
 *
 * loadavg follows the same precedence (real → environment EWMA → legacy).
 *
 * Other files:
 * - uptime/version/vmstat: unchanged contract from the audited design;
 * - `net/dev`, `net/route`, `net/if_inet6`: synthesized from the multi-
 *   interface collector ([HostNetworkInfo.interfaces]) with REAL TrafficStats
 *   counters, feeding htop/network meters that previously starved;
 * - `meminfo` / `swaps` / `cpuinfo`: emitted only when the caller reports the
 *   native proc path unreadable (probe-memoized upstream); every number is a
 *   provider-measured value, absent rows are simply omitted.
 *
 * Class invariants pinned by tests: trailing `\n`; per-core line count ==
 * environment-visible core count; spoof-version equality with
 * [PRootLauncher.SPOOFED_KERNEL_RELEASE]; monotonic cumulative counters per
 * consecutive snapshots (HOST and ENV modes alike).
 */
object ProcSnapshotContentFactory {

    /** Fallback when the host uptime is somehow unmeasurable — matches R-6. */
    private const val LEGACY_ZERO_UPTIME = "0.00 0.00\n"

    /** Same fallback rationale as above (loadavg unknowable on locked hosts). */
    private const val LEGACY_ZERO_LOADAVG = "0.00 0.00 0.00 1/1 1\n"

    /**
     * Legacy stable btime (2026-01-01 UTC) kept verbatim from R-6 — process
     * ages computed against it are wrong-by-a-few-weeks instead of nonsense;
     * replacing it silently with today's time each launch would make ps
     * print absurd negative elapsed times within one session.
     */
    const val LEGACY_BTIME_EPOCH_SECONDS = 1767225600L

    const val SPOOF_RELEASE = "6.1.0-linexa"
    const val SPOOF_COMPAT_VERSION_LINE =
        "Linux version $SPOOF_RELEASE (proot@linexa) (gcc 13.3.0, GNU ld 2.42) " +
            "#1 SMP PREEMPT_DYNAMIC Fri, 26 Aug 2026 00:00:00 +0000 aarch64 GNU/Linux\n"

    // ── /proc/stat ────────────────────────────────────────────────────────

    /**
     * Aggregate line values are absolute CUMULATIVE tick sums in whichever
     * mode applies; per-core rows carry the kernel's ONLINE core count
     * (issue #8 — see [coreRowCount]).
     */
    fun statContent(
        cpu: HostCpuInfo,
        device: HostDeviceInfo,
        envTicks: EnvTickSampler.Snapshot? = null,
        loadEmulationRunningProcs: Int? = null
    ): String = buildString {
        val hostTotal = cpu.totalStatTicks
        val hostIdle = cpu.idleInclIowaitStatTicks
        val envUsable = envTicks != null && envTicks.valid &&
            (envTicks.busyTotalTicks + envTicks.idleTotalTicks) > 0

        appendAggregateLine(
            total = when {
                hostTotal != null -> hostTotal
                envUsable -> envTicks!!.busyTotalTicks + envTicks.idleTotalTicks
                else -> 0L
            },
            idlePlusIowait = when {
                hostIdle != null -> hostIdle
                envUsable -> envTicks!!.idleTotalTicks
                else -> 0L
            }
        )
        appendPerCoreLines(cpu, envTicks, hostTotal != null)
        append("intr 0\n")
        append("ctxt 0\n")
        val btime = device.bootTimeEpochMillis?.let { ms ->
            if (ms > 0) ms / 1000L else null
        } ?: LEGACY_BTIME_EPOCH_SECONDS
        append("btime $btime\n")
        // Environment process counts when measured (REAL observations of our
        // own tree); R-6 statics otherwise. Never a guess between the two.
        append("processes ${maxOf(1, envTicks?.seenProcsTotal ?: 1)}\n")
        append("procs_running ${maxOf(1, loadEmulationRunningProcs ?: envTicks?.runningProcs ?: 1)}\n")
        append("procs_blocked ${envTicks?.blockedProcs ?: 0}\n")
        append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
    }

    /**
     * Issue #8 (2026-09-03): the per-core row count is the kernel's ONLINE
     * core count — what a real /proc/stat on this device shows — NOT the
     * scheduler affinity mask. The affinity-trimmed view made htop inside
     * one rootfs report 3 cores while a rootfs whose overlays had never
     * been refreshed passed the real 8-core /proc/stat through: two
     * workspaces on one device disagreed about the machine they run on.
     * The kernel lists every online CPU in /proc/stat regardless of any
     * process's cpuset, so the overlay must too. The honest "what THIS
     * environment may run on" number stays available through the
     * `cores.granted` field of `linexa-hostinfo` and `LINEXA_HOST_*` env —
     * it is scheduling truth, not /proc/stat truth.
     */
    private fun coreRowCount(cpu: HostCpuInfo): Int =
        (cpu.onlineCores ?: cpu.logicalCores).coerceAtLeast(HostCpuInfo.FALLBACK_CORES)

    private fun StringBuilder.appendPerCoreLines(cpu: HostCpuInfo, envTicks: EnvTickSampler.Snapshot?, hostLive: Boolean) {
        val rowCount = coreRowCount(cpu)

        if (hostLive && cpu.totalPerCoreStatTicks != null) {
            // HOST mode with a real grid: expose the kernel's own grid
            // verbatim (every core the kernel reports, in kernel order) —
            // the closest a synthesis can be to the real file.
            val orderedIndices = cpu.totalPerCoreStatTicks.keys.sorted()
            if (orderedIndices.isNotEmpty()) {
                for ((slot, coreIndex) in orderedIndices.withIndex()) {
                    val total = cpu.totalPerCoreStatTicks[coreIndex]
                    if (total != null && total >= 0L) {
                        append(numberedCoreLine(slot, totalToColumns(total)))
                    } else {
                        append(zeroCoreLine(slot))
                    }
                }
                return
            }
        }

        if (envTicks != null && envTicks.valid) {
            // ENV mode: REAL per-core attribution (the scheduler's own
            // placement of this environment's tasks, from /proc/<pid>/stat)
            // for the cores the tasks ran on; zero rows for the remaining
            // ONLINE cores — zeros are the truthful "no task of this
            // environment ran here", not a fabrication, and the count now
            // matches the kernel's online view.
            val busyMap = envTicks.perCoreBusyTicks
            val idleMap = envTicks.perCoreIdleTicks
            if (busyMap.isNotEmpty()) {
                var slot = 0
                for (coreIndex in busyMap.keys.sorted()) {
                    if (slot >= rowCount) break
                    val busy = busyMap[coreIndex] ?: 0L
                    val idle = idleMap[coreIndex] ?: 0L
                    append(numberedCoreLine(slot, capacityColumns(busy = busy, idle = idle)))
                    slot++
                }
                while (slot < rowCount) {
                    append(zeroCoreLine(slot)); slot++
                }
                return
            }
        }

        // Legacy shape (R-6): zeros keep parsers fed; same reasoning as ever.
        repeat(rowCount) { index -> append(zeroCoreLine(index)) }
    }

    /**
     * Splits one raw cumulative TOTAL into the canonical ten columns under
     * the documented coarse bucketing (idle→(idle,iowait) and busy→
     * (user,nice,system,…)/system residuals). The bucketing itself is not
     * knowable app-side; the TOTALS are what downstream delta math reads.
     */
    private fun totalToColumns(total: Long): List<Long> {
        // Without a readable idle split we assume HALF the lifetime idled —
        // valid for a mid-life mobile system and consistent between writes
        // (deltas stay truthful because the assumption is CONSTANT).
        val idleTotal = total * DEFAULT_IDLE_SHARE_UNKNOWN / 100
        return splitColumns(total = total, idlePlusIowait = idleTotal)
    }

    private const val DEFAULT_IDLE_SHARE_UNKNOWN = 50

    private fun capacityColumns(busy: Long, idle: Long): List<Long> =
        splitColumns(total = busy + idle, idlePlusIowait = idle)

    private fun splitColumns(total: Long, idlePlusIowait: Long): List<Long> {
        if (total <= 0 || idlePlusIowait <= 0) {
            return List(COLUMN_COUNT) { 0L }
        }
        val idle = min(idlePlusIowait, total)
        val busy = total - idle
        val idleShare = idle / 2
        val iowaitShare = idle - idleShare
        val userShare = busy / 2
        val systemShare = busy - userShare
        return listOf(userShare, 0L, systemShare, idleShare, iowaitShare, 0L, 0L, 0L, 0L, 0L)
    }

    private fun numberedCoreLine(slot: Int, cols: List<Long>): String =
        "cpu$slot ${cols.joinToString(" ")}\n"

    private fun zeroCoreLine(slot: Int): String = "cpu$slot ${List(COLUMN_COUNT) { 0L }.joinToString(" ")}\n"

    private const val COLUMN_COUNT = 10

    /**
     * Aggregate line: the previous single-purpose implementation, kept as a
     * thin wrapper over [splitColumns] so both lines share ONE bucketing rule.
     */
    private fun StringBuilder.appendAggregateLine(total: Long, idlePlusIowait: Long) {
        append("cpu  ${splitColumns(total, idlePlusIowait).joinToString(" ")}\n")
    }

    // ── /proc/uptime ──────────────────────────────────────────────────────

    fun uptimeContent(
        device: HostDeviceInfo,
        workspaceUptimeSeconds: Long? = null,
        workspaceIdleSeconds: Long? = null
    ): String {
        // Issue #9 (2026-09-03): a RUNNING workspace's /proc/uptime reports
        // the WORKSPACE's uptime (LXCFS-style container-relative clock), not
        // the host's — `uptime` inside the terminal answers "how long has
        // THIS environment been up", which is the only uptime a workspace
        // actually has. When no session is running (exec/daemon refresh
        // paths) the host value keeps its previous meaning. Values are
        // real: they come from the session manager's recorded start instant,
        // never invented.
        val workspaceUptime = workspaceUptimeSeconds?.takeIf { it >= 0 }
        if (workspaceUptime != null) {
            val idle = workspaceIdleSeconds?.takeIf { it >= 0 } ?: workspaceUptime
            return "${twoDecimal(workspaceUptime.toFloat())} ${twoDecimal(idle.toFloat())}\n"
        }
        val uptimeSecs = device.uptimeMillis ?: return LEGACY_ZERO_UPTIME
        val uptimeText = twoDecimal(uptimeSecs / 1000f)
        // Second column ("idle") — deepest truthful approximation available
        // app-side: uptime minus accumulated deep sleep. When deep sleep is
        // withheld, mirror uptime rather than inventing an idle figure.
        val idleMillis = device.deepSleepMillis?.takeIf { it >= 0 }
        val idleSecs = (uptimeSecs - (idleMillis ?: 0L)).coerceAtLeast(0L)
        return "$uptimeText ${twoDecimal(idleSecs / 1000f)}\n"
    }

    // ── /proc/loadavg ─────────────────────────────────────────────────────

    /**
     * Precedence documented on the class: REAL host averages → environment
     * EWMA of genuinely-runnable processes (restricted hosts) → legacy
     * zeros. The running/total tail keeps its R-6 static shape.
     */
    fun loadavgContent(cpu: HostCpuInfo, envTicks: EnvTickSampler.Snapshot? = null): String {
        val l1: Float
        val l5: Float
        val l15: Float
        when {
            cpu.loadAvg1m != null -> {
                l1 = cpu.loadAvg1m!!
                l5 = cpu.loadAvg5m ?: 0f
                l15 = cpu.loadAvg15m ?: 0f
            }
            envTicks?.valid == true && envTicks.loadAvg1m != null -> {
                l1 = envTicks.loadAvg1m!!
                l5 = envTicks.loadAvg5m ?: 0f
                l15 = envTicks.loadAvg15m ?: 0f
            }
            else -> return LEGACY_ZERO_LOADAVG
        }
        return String.format(Locale.US, "%.2f %.2f %.2f 1/1 1\n", l1, l5, l15)
    }

    // ── /proc/version ─────────────────────────────────────────────────────

    fun versionContent(): String = SPOOF_COMPAT_VERSION_LINE

    // ── /proc/vmstat ──────────────────────────────────────────────────────

    /** Legacy R-6 minimal set verbatim — deliberately not host-derived. */
    fun vmstatContent(): String = buildString {
        append("nr_running 1\n")
        append("nr_idle 0\n")
        append("nr_iowait 0\n")
        append("nr_anon_pages 0\n")
        append("nr_file_pages 0\n")
        append("pgpgin 0\n")
        append("pgpgout 0\n")
        append("pswpin 0\n")
        append("pswpout 0\n")
        append("pgfault 0\n")
        append("pgmajfault 0\n")
    }

    // ── /proc/meminfo (conditional synthesis — only when native denied) ──

    /**
     * Present-row-only synthesis: a row enters the file iff the provider
     * MEASURED it. Missing rows omitted entirely (parsers branch on
     * presence; a zero-filled fake row would masquerade as fact).
     */
    fun meminfoContent(memory: HostMemoryInfo, swap: HostSwapInfo?): String {
        val rows = linkedMapOf<String, Long?>(
            "MemTotal:" to memory.memTotalKb,
            "MemFree:" to memory.memFreeKb,
            "MemAvailable:" to memory.memAvailableKb,
            "Buffers:" to memory.buffersKb,
            "Cached:" to memory.cachedKb,
            "SReclaimable:" to memory.sReclaimableKb,
            "SwapTotal:" to swap?.totalKb,
            "SwapFree:" to swap?.freeKb
        ).filterValues { it != null }.mapValues { it.value!! }
        if (rows.isEmpty()) return ""
        return buildString {
            for ((label, value) in rows) {
                append(String.format(Locale.US, "%-14s%12d kB\n", label, value))
            }
        }
    }

    /**
     * Read-only representation of the host swap configuration for tools
     * that parse `/proc/swaps`. Linux exposes NO way for an unprivileged
     * process to reconfigure what this file describes — swapon/swapoff go
     * through privileged syscalls that fail under this uid regardless, so
     * the file stays telemetry-only BY CONSTRUCTION.
     */
    fun swapsContent(swap: HostSwapInfo, zram: HostZramInfo?): String {
        val totalKb = swap.totalKb ?: return ""
        val usedKb = swap.usedKb ?: 0L
        return buildString {
            append(String.format(Locale.US, "%-27s %-15s %-11s %-13s %s\n",
                "Filename", "Type", "Size", "Used", "Priority"))
            // The current audited reality is exactly one kernel-managed swap
            // backend (zRAM on essentially every modern Android device);
            // Total already accounts for it once.
            val filename = "/dev/block/zram0"
            val type = if (zram?.present == false) "device" else "partition"
            append(String.format(Locale.US, "%-27s %-15s %-11d %-13d %s\n",
                filename, type, totalKb, usedKb, "-2"))
        }
    }

    /**
     * Reduced-schema cpuinfo synthesis — the exact case policy ladder lives
     * on the class KDoc. Rows limited to genuinely-known facts plus the
     * loop scaffolding consumers require; never invented hex IDs.
     *
     * Issue #8 (2026-09-03): the processor row count is the kernel's ONLINE
     * core count, consistent with /proc/stat's — an affinity-trimmed list
     * here disagreed with both the real passthrough file and the sibling
     * rootfs workspaces. Each row's `# kernel cpu index` comment keeps the
     * real core index visible, so the granted/online distinction survives.
     */
    fun cpuinfoContent(cpu: HostCpuInfo, environmentOnly: Boolean = true): String {
        val rowCount = coreRowCount(cpu)
        val freqByCore = cpu.coresPerCoreInfo.associateBy({ it.index }, { it })
        val indices = cpu.affinityCoreIndices?.sorted()
            ?: freqByCore.keys.sorted()
        return buildString {
            for (slot in 0 until rowCount) {
                val coreIdx = indices.getOrNull(slot) ?: freqByCore.keys.sorted().getOrNull(slot) ?: slot
                append("processor\t: $slot\n")
                if (environmentOnly) {
                    // The real kernel index travels with every row, so the
                    // granted/online distinction stays auditable from the
                    // guest side (and from taskset/linexa-hostinfo output).
                    append("# kernel cpu index\t: $coreIdx\n")
                }
                cpu.cpuModelName?.let { append("model name\t: $it\n") }
                cpu.cpuImplementation?.let { append("CPU part\t: $it\n") }
                freqByCore[coreIdx]?.currentKhz?.let { khz ->
                    append(String.format(Locale.US, "cpu MHz\t\t: %.0f\n", khz / 1000.0))
                }
                freqByCore[coreIdx]?.minKhz?.let { append("min MHz\t\t: ${it / 1000}\n") }
                freqByCore[coreIdx]?.maxKhz?.let { append("max MHz\t\t: ${it / 1000}\n") }
                append("\n")
            }
        }
    }

    // ── /proc/net/dev ─────────────────────────────────────────────────────

    /**
     * Loopback plus every app-visible interface with REAL TrafficStats
     * counters. Counter cells the OS did not track collapse to 0 — the file
     * format has no N/A cell — while the capability matrix records which
     * fields were missing on each refresh (the honesty lives in the report,
     * not in breaking a fixed-width parser). htop/nload/iftool diff THESE
     * numbers; being real they finally move.
     */
    fun netDevContent(network: HostNetworkInfo): String {
        val width = 16
        val sb = StringBuilder()
        sb.append("Inter-|   Receive                                                |  Transmit\n")
        sb.append(" face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n")
        appendDevRow(sb, LOOPBACK_IFACE, width,
            rxBytes = 0L, rxPackets = 0L, txBytes = 0L, txPackets = 0L)
        for (iface in network.interfaces.sortedWith(
            compareByDescending<HostNetInterface> { it.isActiveNetwork }.thenBy { it.name }
        )) {
            appendDevRow(sb, iface.name.take(7), width,
                rxBytes = iface.rxBytes ?: 0L,
                rxPackets = iface.rxPackets ?: 0L,
                txBytes = iface.txBytes ?: 0L,
                txPackets = iface.txPackets ?: 0L)
        }
        return sb.toString()
    }

    private const val LOOPBACK_IFACE = "lo"

    private fun appendDevRow(
        sb: StringBuilder,
        name: String,
        width: Int,
        rxBytes: Long,
        rxPackets: Long,
        txBytes: Long,
        txPackets: Long
    ) {
        sb.append(String.format(Locale.US, "%${width}s:%${width}d %${width - 2}d    0    0    0     0          0         0 %${width}d %${width - 2}d    0    0    0     0       0          0\n",
            name, rxBytes, rxPackets, txBytes, txPackets))
    }

    // ── /proc/net/route ───────────────────────────────────────────────────

    /**
     * IPv4 routes from LinkProperties (READ_ONLY visibility — configuring
     * routes goes through privileged netlink SET operations this uid can
     * never perform; see the Phase 14 negative tests in the expansion
     * report). No routes known → header-only body (graceful, parseable).
     */
    fun netRouteContent(network: HostNetworkInfo): String {
        val sb = StringBuilder()
        sb.append("Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n")
        for (iface in network.interfaces) {
            val gwV4 = iface.gatewayV4 ?: continue
            // Default route toward the gateway.
            sb.append(routeRow(iface.name, "00000000", gwV4.hexNetworkAddress(), "0003"))
            for (address in iface.ipv4WithPrefix) {
                val parts = address.split('/')
                if (parts.size != 2) continue
                val prefixLen = parts[1].toIntOrNull() ?: continue
                if (prefixLen == 32) continue
                val mask = prefixLengthToMask(prefixLen) ?: continue
                val hostBits = ipv4ToUnsignedInt(parts[0]) ?: continue
                val maskVal = java.lang.Long.parseLong(mask, 16)
                // Kernel routes carry the NETWORK address, not the interface
                // address: host bits are masked away exactly like route -n shows.
                val dest = hostBits and maskVal
                // A zeroed network is already covered by the gateway row above.
                if (dest == 0L) continue
                sb.append(routeRow(iface.name,
                    java.lang.Long.toString(dest, 16).uppercase(Locale.US).padStart(8, '0'),
                    "00000000", "0001", mask))
            }
        }
        return sb.toString()
    }

    private fun routeRow(iface: String, destHex: String, gwHex: String, flags: String, maskHex: String = "00000000"): String =
        String.format(Locale.US, "%-8s\t%s\t%s\t%s\t%d\t%d\t%d\t%s\t%d\t%d\t%d\n",
            iface, destHex, gwHex, flags, 0, 0, 0, maskHex, 0, 0, 0)

    private fun String.hexNetworkAddress(): String {
        val v = ipv4ToUnsignedInt(this) ?: return "00000000"
        return v.toString(16).uppercase(Locale.US).padStart(8, '0')
    }

    private fun ipv4ToUnsignedInt(dotted: String): Long? {
        val octets = dotted.split('.')
        if (octets.size != 4) return null
        var acc = 0L
        for (octet in octets) {
            val b = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            acc = (acc shl 8) or b.toLong()
        }
        return acc
    }

    private fun prefixLengthToMask(prefix: Int): String? {
        if (prefix !in 0..32) return null
        val v = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return v.toString(16).uppercase(Locale.US).padStart(8, '0')
    }

    // ── /proc/net/if_inet6 ────────────────────────────────────────────────

    /**
     * One row per non-loopback IPv6 address with prefix length converted to
     * hex and the kernel's conventional scope nibble (link=20, site=40,
     * global=00). The if-index column is a stable EMISSION ordinal, not a
     * fabricated kernel measurement — documented in the capability matrix.
     */
    fun ifInet6Content(network: HostNetworkInfo): String {
        val sb = StringBuilder()
        var ordinal = 1
        for (iface in network.interfaces.sortedWith(compareByDescending<HostNetInterface> { it.isActiveNetwork }.thenBy { it.name })) {
            for (address in iface.ipv6WithPrefix) {
                val parts = address.split('/')
                if (parts.size != 2) continue
                val raw = parts[0].split('%').first()
                val compact = ipv6ExpandedHex(raw) ?: continue
                val prefix = parts[1].toIntOrNull() ?: continue
                if (prefix !in 0..128) continue
                val scope = when {
                    compact.startsWith("FE80") -> "20"
                    compact.startsWith("FC") || compact.startsWith("FD") -> "40"
                    else -> "00"
                }
                sb.append(compact)
                sb.append(String.format(Locale.US, " %X %02X %s %02X %-7s\n",
                    ordinal, prefix, scope, 0x80, iface.name.take(7)))
                ordinal++
            }
        }
        return sb.toString()
    }

    /** "::"-notation → 32 uppercase hex chars, or null when malformed. */
    private fun ipv6ExpandedHex(address: String): String? {
        val sections = address.split("::")
        if (sections.size > 2) return null
        val headParts = sections[0].split(':').filter { it.isNotEmpty() }
        val tailParts = if (sections.size == 2) sections[1].split(':').filter { it.isNotEmpty() } else emptyList()
        val missing = 8 - headParts.size - tailParts.size
        if (missing < 0 || (sections.size == 1 && missing > 0)) return null
        val groups = headParts.map(::expandGroup) +
            List(missing) { "0000" } +
            tailParts.map(::expandGroup)
        if (groups.any { it == null }) return null
        return groups.joinToString("") { it!! }.uppercase(Locale.US).takeIf { it.length == 32 }
    }

    private fun expandGroup(group: String): String? {
        if (group.isEmpty() || group.length > 4 || group.any { Character.digit(it, 16) < 0 }) return null
        return group.padStart(4, '0')
    }

    /** Shared formatting: /proc-style two decimals, dot-decimal canonical. */
    internal fun twoDecimal(value: Float): String =
        String.format(Locale.US, "%.2f", value)
}
