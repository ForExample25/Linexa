package com.fem.linexa.domain.hostinfo

/**
 * Android host CPU information, as observed by the app-uid process.
 *
 * Every field is nullable and that is the design: Android hides different
 * subsets of CPU detail per OEM kernel, and `/proc/stat` — the file every
 * Linux tool treats as the source of truth for usage/load — is SELinux-denied
 * to untrusted_app on the same devices Linexa targets. A `null` field means
 * "not knowable from this context", never "zero" and never a synthesized
 * placeholder; consumers render an explicit unknown, the same contract
 * [com.fem.linexa.domain.resource.WorkspaceResourceSample] established for
 * workspace-scoped metrics.
 *
 * Units follow the kernel files the values come from so no conversion can
 * silently lose precision: frequencies in kHz, usage in ticks-derived percent.
 *
 * @property logicalCores configured logical core count (`_SC_NPROCESSORS_CONF`).
 *   This is the value `nproc`/`btop` need in a guest overlay's `/proc/stat`.
 * @property onlineCores cores currently online where `/sys/devices/system/cpu/online`
 *   is readable; null when sysfs refuses (per-core cpufreq paths are OEM-dependent).
 * @property architecture host machine architecture from `uname(2)` (aarch64…).
 *   Deliberately read via the syscall, NOT /proc/cpuinfo, which restricted
 *   kernels deny.
 * @property supportedAbis `Build.SUPPORTED_ABIS`, ordered by preference.
 * @property cpuModelName model string from `/proc/cpuinfo` ("model name",
 *   "Processor", or "Hardware") when that file is readable — otherwise null.
 *   Never derived from Build.*, which describes marketing, not silicon.
 * @property cpuImplementation vendor/implementation line from `/proc/cpuinfo`
 *   ("CPU implementer"/"CPU part") when present; null when not.
 * @property coresPerCoreInfo per-core rows (index, online, min/max/current kHz)
 *   collected while sysfs allows it; empty when none were readable.
 * @property maxFrequencyKhz fastest core clock observed across readable cpufreq
 *   entries (or `/proc/cpuinfo max freq`); null when none readable.
 * @property minFrequencyKhz slowest readable minimum; null likewise.
 * @property currentFrequencyKhz aggregate "now" frequency (mean of readable
 *   per-core current clocks); null when unreadable — never stale-cached.
 * @property overallUsagePercent device-wide CPU use 0..100 computed from two
 *   consecutive readable `/proc/stat` samples; null on first sample or when
 *   `/proc/stat` is RESTRICTED. This is the HOST-wide number, distinct from
 *   [com.fem.linexa.domain.resource.WorkspaceResourceSample] which is
 *   workspace-tree-scoped by design.
 * @property perCoreUsagePercent device-wide per-core use 0..100 indexed by
 *   core, only when both a previous full sample exists AND all lines parsed;
 *   partial reads are reported as absent rather than half-guessed.
 * @property loadAvg1m/[loadAvg5m]/[loadAvg15m] from `/proc/loadavg`; null when
 *   restricted. Load average cannot be computed any other way without
 *   privileged APIs, so absence here is common on modern Android and honest.
 * @property totalStatTicks cumulative all-core tick sum from the last readable
 *   `/proc/stat` sample — the RAW denominator the guest compatibility layer
 *   needs to emit monotonic counters (guest-side `top` computes its own deltas
 *   BETWEEN overlay refreshes, which only works on absolute totals).
 * @property idleInclIowaitStatTicks same sample's idle+iowait sum.
 * @property totalPerCoreStatTicks cumulative tick sums indexed BY KERNEL CORE
 *   NUMBER when `/proc/stat` was readable (one entry per cpuN line); null
 *   when the aggregate was restricted or the grid was truncated. Feeds REAL
 *   per-core rows in the guest stat overlay — previously those lines were
 *   always-zero even when the host answered.
 * @property affinityCoreIndices the cores the OS SCHEDULER actually grants
 *   this process tree (`sched_getaffinity`, parsed from `/proc/self/status`
 *   `Cpus_allowed_list`). Null when unparsable. This is the third leg of the
 *   CPU-count triad: [logicalCores] describes hardware, [onlineCores]
 *   describes kernel hotplug state, and THIS describes what this process tree
 *   may run on. On cpuset-restricted devices Android grants fewer cores than
 *   configured — which is why coreutils `nproc` (affinity-based) can report 3
 *   while the Android UI legitimately shows 8/8. Exposed as its own concept,
 *   never merged into either other count (docs/HOST_SYSTEM_INFO.md §6).
 */
data class HostCpuInfo(
    val logicalCores: Int,
    val onlineCores: Int?,
    val architecture: String?,
    val supportedAbis: List<String>,
    val cpuModelName: String?,
    val cpuImplementation: String?,
    val coresPerCoreInfo: List<HostCpuCore>,
    val minFrequencyKhz: Long?,
    val maxFrequencyKhz: Long?,
    val currentFrequencyKhz: Long?,
    val overallUsagePercent: Float?,
    val perCoreUsagePercent: List<Float?>,
    val loadAvg1m: Float?,
    val loadAvg5m: Float?,
    val loadAvg15m: Float?,
    val totalStatTicks: Long? = null,
    val idleInclIowaitStatTicks: Long? = null,
    val totalPerCoreStatTicks: Map<Int, Long>? = null,
    val affinityCoreIndices: List<Int>? = null
) {
    /** True when at least one usage measurement came back valid this refresh. */
    val hasUsageMeasurement: Boolean
        get() = overallUsagePercent != null || loadAvg1m != null

    /**
     * Cores visible to a Linux environment under this snapshot's scheduling
     * constraints: the measured affinity subset when known, otherwise ALL
     * configured cores (the typical unrestricted case).
     *
     * Issue #8 (2026-09-03): this triad view is NO LONGER the shape source
     * for the guest /proc/stat + /proc/cpuinfo overlays — those follow the
     * kernel's ONLINE count ([onlineCores] ?: [logicalCores]), because the
     * kernel lists every online CPU in /proc/stat regardless of any process's
     * cpuset, and an affinity-trimmed overlay made sibling workspaces
     * disagree about the machine (htop 3 vs 8). THIS property remains the
     * honest "what this environment may run on" number for the
     * `cores.granted` surfaces (`linexa-hostinfo`, `LINEXA_HOST_*` env).
     */
    val environmentVisibleCores: Int
        get() = affinityCoreIndices?.size?.coerceAtMost(logicalCores) ?: logicalCores

    companion object {
        /**
         * The safe fallback when even the core count could not be determined:
         * JVM/bionic `_SC_NPROCESSORS_ONLN` never returns < 1 in practice, but
         * the arithmetic guards stay rather than divide by zero anywhere else.
         */
        const val FALLBACK_CORES = 1
    }
}

/**
 * One CPU core as seen through sysfs cpufreq.
 *
 * @property index 0-based kernel core number (matches `cpu0`..`cpuN` naming).
 * @property online whether the core is currently hotplugged-in, when knowable.
 * @property minKhz/maxKhz scaling_min/max_freq for this core.
 * @property currentKhz scaling_cur_freq snapshot — changes constantly, treat
 *   as indicative.
 */
data class HostCpuCore(
    val index: Int,
    val online: Boolean?,
    val minKhz: Long?,
    val maxKhz: Long?,
    val currentKhz: Long?
)
