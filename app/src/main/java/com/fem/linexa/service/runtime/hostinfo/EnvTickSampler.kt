package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.domain.resource.ProcMetricParsers
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Real, environment-scoped CPU accounting for the guest `/proc/stat` overlay.
 *
 * ## The problem it solves (Phase 5 of the host-bridge expansion)
 *
 * On modern Android the untrusted_app SELinux domain cannot read the HOST's
 * `/proc/stat`, so a guest that wants CPU numbers has nothing to diff and
 * every meter renders `N/A` (the audited "htop CPU% = N/A" symptom). The
 * pre-2026-08 bridge papered over this with constant-zero counters — which
 * is exactly what turns into zero deltas at htop's sampling cadence.
 *
 * ## What it measures instead (LXCFS precedent)
 *
 * Every process INSIDE the Linexa Linux environment — the app itself plus
 * all PRoot/traced children — is owned by THIS app uid. The kernel lets an
 * app read `/proc/<pid>/stat` for its own uid without any permission; other
 * uids simply fail to open and are skipped SILENTLY (platform hiding is
 * honored, never probed around). So:
 *
 * 1. each pass reads `/proc/<pid>/stat` for the pids readable to this uid.
 *    A full `/proc` enumeration runs once per
 *    [FULL_SCAN_INTERVAL_MS]; the passes in between re-read only the set that
 *    was readable at that sweep (LINEXA-PERF-001 — on a busy device the full
 *    sweep is ~1 100 entries, almost all of which fail the same-uid read);
 * 2. busy ticks per PID = utime+stime+cutime+cstime — REAL scheduler
 *    accounting, including reaped children so short-lived gcc/aptd forks
 *    still land in the totals;
 * 3. per-PID deltas are attributed to the core recorded in field 39 (the
 *    last-run CPU — again REAL data);
 * 4. idle = capacity-minus-busy on the AFFINITY-granted cores only: over a
 *    dt window those cores jointly offer `CLK_TCK·dt` ticks; whatever the
 *    environment did not consume IS its idle, by capacity accounting — the
 *    same construction LXCFS uses for containers. Never fabricated, only
 *    scoped;
 * 5. counters are CUMULATIVE (seeded from first-sample absolutes so guest
 *    tooling sees kernel-plausible magnitudes), monotonic, and moving at
 *    htop's own cadence: deltas between successive overlay writes are now
 *    non-zero whenever anything in the environment runs or time passes —
 *    which is what flips `N/A` into real percentages.
 *
 * It also derives running/blocked counts and exponentially-decayed load
 * averages (the classic 60/300/900 s decay) from REAL runnable-state
 * observations — feeding honest values into `/proc/loadavg` and the stat
 * header lines on restricted hosts.
 *
 * ## Scope honesty (documented, tested, intentional)
 *
 * These counters describe THE LINEXA ENVIRONMENT, not the whole Android
 * device. Android processes outside the app's uid contribute nothing (they
 * are invisible by platform policy — htop inside Debian shows precisely the
 * processes it could see anyway). When the HOST stat IS readable, the
 * factory prefers real device-wide counters and does not consult this class
 * at all; this sampler exists solely as the documented fallback whose values
 * are measurements instead of fabrications. See docs/HOST_SYSTEM_INFO.md
 * §"restricted-mode telemetry" in the expansion report.
 */
class EnvTickSampler(
    /** Injected for tests; defaults to the real /proc mount. */
    private val procDir: File,
    /** Kernel clock-ticks-per-second (`_SC_CLK_TCK`; bionic: 100). */
    private val clockTicksPerSecond: Long,
    /** Monotonic clock injection for deterministic tests. */
    private val nowMs: () -> Long
) {

    class Snapshot internal constructor(
        val perCoreBusyTicks: Map<Int, Long>,
        val perCoreIdleTicks: Map<Int, Long>,
        val busyTotalTicks: Long,
        val idleTotalTicks: Long,
        val runningProcs: Int,
        val blockedProcs: Int,
        val seenProcsTotal: Int,
        val loadAvg1m: Float?,
        val loadAvg5m: Float?,
        val loadAvg15m: Float?
    ) {
        val valid: Boolean get() = perCoreBusyTicks.isNotEmpty()
    }

    private val lock = Any()
    private var seeded = false
    private var lastNowMs: Long = 0
    private val perPidBaseline = HashMap<Int, Long>()
    private val lastSeenPids = HashSet<Int>()
    private val cumulativeBusy = LinkedHashMap<Int, Long>()
    private val cumulativeIdle = LinkedHashMap<Int, Long>()
    private var distinctPidsSeen = 0
    private var loadExp1m: Double? = null
    private var loadExp5m: Double? = null
    private var loadExp15m: Double? = null

    /**
     * LINEXA-PERF-001: pids that were readable at the last full enumeration.
     * A full `/proc` sweep opens every numeric `/proc/<pid>/stat`; on the
     * audited device that is ~1 100 entries, of which ~5 are this app's. Doing
     * that every 2 s (≈550 failed opens/s, each a syscall plus an SELinux
     * check) was the dominant per-tick cost — far more expensive than the host
     * collection the audit originally blamed.
     *
     * Between full sweeps only these pids are re-read. A process started in
     * between is picked up at the next full sweep (its already-consumed ticks
     * are baselined then), so a window can under-count a brand-new child but
     * can never fabricate or double-count: emitted counters stay cumulative
     * and monotonic.
     */
    private val knownPids = HashSet<Int>()
    private var lastFullScanMs = Long.MIN_VALUE

    /**
     * Run one sampling pass across [allowedCores] (the affinity mask; the
     * caller supplies the snapshot's value so capacity math matches what the
     * scheduler actually grants). Returns null before the SECOND pass — a
     * delta model needs a baseline — letting callers emit their legacy shape
     * exactly once rather than guessing.
     */
    fun sampleOnce(allowedCores: List<Int>): Snapshot? {
        synchronized(lock) {
            val now = nowMs()
            if (!seeded) {
                seedLocked(allowedCores, now)
                lastNowMs = now
                return null
            }
            val dtSeconds = max((now - lastNowMs), MIN_INTERVAL_MS) / 1000.0
            lastNowMs = now
            val cores = allowedCores.ifEmpty { defaultCoresLocked() }

            var running = 0
            var blocked = 0
            val currentBusyByPid = HashMap<Int, Long>()
            val deltaBusyPerCore = HashMap<Int, Long>()

            for ((pid, rawStat) in pidStatsForPass(now)) {
                val parsed = ProcMetricParsers.pidTickSample(rawStat) ?: continue
                if (parsed.stateChar == 'R') running++
                if (parsed.stateChar == 'D') blocked++
                currentBusyByPid[pid] = parsed.busyTicks
                val previousBusy = perPidBaseline[pid]
                if (previousBusy != null && parsed.busyTicks >= previousBusy) {
                    // Attribute by last-ran core when known; unknown → clamp
                    // into the FIRST granted core so no tick ever vanishes.
                    val targetCore = parsed.lastCore?.takeIf { cores.contains(it) } ?: cores.firstOrNull() ?: continue
                    deltaBusyPerCore[targetCore] = (deltaBusyPerCore[targetCore] ?: 0L) + (parsed.busyTicks - previousBusy)
                }
            }
            // Creation-counter (monotonic): pids never seen in ANY prior
            // pass count once toward /proc/stat `processes`. Short-lived
            // forks still land because children ticks fold into parents.
            distinctPidsSeen += currentBusyByPid.keys.count { it !in lastSeenPids }
            lastSeenPids.addAll(currentBusyByPid.keys)
            perPidBaseline.keys.retainAll(currentBusyByPid.keys)
            perPidBaseline.putAll(currentBusyByPid)

            val capacityPerCore = (clockTicksPerSecond * dtSeconds).roundToLong().coerceAtLeast(1L)
            for (core in cores) {
                val measured = (deltaBusyPerCore[core] ?: 0L).coerceAtLeast(0L)
                    .let { min(it, capacityPerCore) }   // bookkeeping jitter can exceed one window; cap, never leak negative idle
                val newBusy = (cumulativeBusy[core] ?: 0L) + measured
                val newIdle = (cumulativeIdle[core] ?: 0L) + (capacityPerCore - measured)
                cumulativeBusy[core] = newBusy
                cumulativeIdle[core] = max(newIdle, 0L)
            }
            updateLoadLocked(running + blocked, dtSeconds)
            return Snapshot(
                perCoreBusyTicks = LinkedHashMap(cumulativeBusy),
                perCoreIdleTicks = LinkedHashMap(cumulativeIdle),
                busyTotalTicks = cumulativeBusy.values.sum(),
                idleTotalTicks = cumulativeIdle.values.sum(),
                runningProcs = running.coerceAtLeast(1),   // we ourselves ran this pass — never claim a dead system
                blockedProcs = blocked,
                seenProcsTotal = max(distinctPidsSeen, 1),
                loadAvg1m = loadExp1m?.toFloat(),
                loadAvg5m = loadExp5m?.toFloat(),
                loadAvg15m = loadExp15m?.toFloat()
            )
        }
    }

    /** Test/diagnostic visibility: pids the incremental pass will re-read. */
    internal fun knownPidCountForTest(): Int = synchronized(lock) { knownPids.size }

    /** Drop all state (called when the overlay ticker stops; cheap re-seed later). */
    fun reset() {
        synchronized(lock) {
            seeded = false
            perPidBaseline.clear()
            lastSeenPids.clear()
            cumulativeBusy.clear()
            cumulativeIdle.clear()
            distinctPidsSeen = 0
            knownPids.clear()
            lastFullScanMs = Long.MIN_VALUE
            loadExp1m = null; loadExp5m = null; loadExp15m = null
        }
    }

    /**
     * Seed pass: record per-PID baselines WITHOUT emitting numbers yet.
     *
     * Cumulative counters start AT ZERO on purpose: they describe the
     * Linexa environment SINCE SAMPLING BEGAN — precisely how lxcfs-style
     * container accounting behaves (accounting starts at container start,
     * not host boot). Constant-and-documented semantics; guest tools diff
     * deltas anyway, and `/proc/stat` consumers only need monotonicity.
     */
    private fun seedLocked(cores: List<Int>, nowMs: Long) {
        for ((pid, rawStat) in pidStatsForPass(nowMs)) {
            ProcMetricParsers.pidTickSample(rawStat)?.let { parsed ->
                perPidBaseline[pid] = parsed.busyTicks
                lastSeenPids.add(pid)
            }
        }
        distinctPidsSeen = perPidBaseline.size
        seeded = true
    }

    private fun defaultCoresLocked(): List<Int> =
        cumulativeBusy.keys.filter { it >= 0 }.ifEmpty { listOf(0) }

    private fun updateLoadLocked(activeCount: Int, dtSeconds: Double) {
        // Kernel-style exponential decay over the ACTUAL pass interval:
        //   load ← load·e^(−dt/T) + observed·(1 − e^(−dt/T))
        // where observed = runnable+uninterruptible count at a random instant
        // (exactly what /proc/loadavg samples). First pass seeds from 0, so
        // values converge like a freshly booted kernel's would.
        val instantaneous = activeCount.toDouble()
        loadExp1m = decay(loadExp1m, instantaneous, dtSeconds, LOAD_TAU_1M_S)
        loadExp5m = decay(loadExp5m, instantaneous, dtSeconds, LOAD_TAU_5M_S)
        loadExp15m = decay(loadExp15m, instantaneous, dtSeconds, LOAD_TAU_15M_S)
    }

    private fun decay(previous: Double?, observed: Double, dt: Double, tauS: Double): Double {
        val alpha = exp(-dt / tauS)
        val base = previous ?: 0.0
        return base * alpha + observed * (1 - alpha)
    }

    /** Same-uid-only scan: unreadable pids fail silently — policy honored. */
    private fun readablePidStats(): Sequence<Pair<Int, String>> = sequence {
        val entries = procDir.listFiles { f -> f.name.all { it.isDigit() } } ?: return@sequence
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            val statFile = File(dir, "stat")
            val content = runCatching { statFile.readText() }.getOrNull() ?: continue
            yield(pid to content)
        }
    }

    /**
     * One pass's readable pid set.
     *
     * LINEXA-PERF-001: a full directory sweep is expensive on a busy device
     * (~1 100 entries on the audited phone) and almost all of it fails the
     * same-uid read. It therefore runs at most once per
     * [FULL_SCAN_INTERVAL_MS]; the passes in between re-read only the pids that
     * were readable last time, which is the environment's actual process set.
     */
    private fun pidStatsForPass(nowMs: Long): List<Pair<Int, String>> {
        val fullScan = knownPids.isEmpty() ||
            lastFullScanMs == Long.MIN_VALUE ||
            nowMs - lastFullScanMs >= FULL_SCAN_INTERVAL_MS
        if (fullScan) {
            val stats = readablePidStats().toList()
            knownPids.clear()
            knownPids.addAll(stats.map { it.first })
            lastFullScanMs = nowMs
            return stats
        }
        val incremental = ArrayList<Pair<Int, String>>(knownPids.size)
        val vanished = ArrayList<Int>(0)
        for (pid in knownPids) {
            val content = runCatching { File(procDir, "$pid/stat").readText() }.getOrNull()
            if (content == null) vanished.add(pid) else incremental.add(pid to content)
        }
        // A dead pid must not be re-probed until the next full sweep re-admits
        // it; keeping it would reintroduce one failed open per tick per exit.
        knownPids.removeAll(vanished.toSet())
        return incremental
    }

    companion object {
        private const val MIN_INTERVAL_MS = 50L

        /**
         * LINEXA-PERF-001: how often the sampler pays for a full `/proc`
         * enumeration. Between sweeps it re-reads only the pids that were
         * readable last time (see [knownPids]).
         */
        internal const val FULL_SCAN_INTERVAL_MS = 10_000L
        private const val LOAD_TAU_1M_S = 60.0
        private const val LOAD_TAU_5M_S = 300.0
        private const val LOAD_TAU_15M_S = 900.0
    }
}
