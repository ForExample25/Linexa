package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.core.filesystem.RootfsWriteLock
import com.fem.linexa.core.filesystem.RootfsWriteWindow
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.perf.PerfCounters
import com.fem.linexa.core.perf.PerfTrace
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.domain.hostinfo.HostInfoKey
import com.fem.linexa.domain.hostinfo.HostInfoKeyResolver
import com.fem.linexa.domain.hostinfo.HostSystemInfoProvider
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.PRootLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The runtime-neutral bridge between the Android host and every RootFS.
 *
 * Guests live under PRoot inside THIS app's processes — there is no IPC to a
 * daemon, so "the RootFS asks Linexa" happens through three deliberate,
 * file/env-shaped channels this bridge owns:
 *
 * 1. **`/proc` bind-overs** ([refreshProcOverlays]) — replaces the legacy R-6
 *    STATIC synthetic snapshot values with Android-host-derived content at
 *    every session/exec/daemon launch, and keeps them fresh while sessions
 *    run ([startOverlayTicker], driven by [RuntimeService]). Guest tools
 *    (`uptime`, `htop`, `nproc`, `free`, `ip -s link`) therefore read host
 *    truth without any guest-side helper — and WITHOUT this layer touching
 *    the real host procfs/sysfs/mount namespace (PRoot translates paths
 *    per-open; nothing is mounted).
 *
 *    The 2026-08-27 expansion adds, under the SAME mechanism and the SAME
 *    atomicity/chmod discipline:
 *    - LIVE `/proc/stat` + `/proc/loadavg`: real device-wide counters when
 *      the host permits; otherwise [EnvTickSampler]'s LXCFS-style real
 *      accounting of this environment (fixes "htop CPU% = N/A");
 *    - synthesized `/proc/net/{dev,route,if_inet6}` with REAL TrafficStats
 *      counters (fixes the missing-network section) — bound ONLY where the
 *      native path is unreadable, never papering over a richer native view;
 *    - conditional `/proc/{meminfo,swaps,cpuinfo}` fallbacks, likewise only
 *      where native passthrough is denied.
 *
 * 2. **Selective `LINEXA_*` environment** ([buildGuestEnv]) — §15's contract
 *    made concrete: a workspace names the facts it wants in
 *    `config.yaml linexa.host_info.keys`; unnamed-but-defaulted keys ship a
 *    conservative base set; guests receive ONLY what was named.
 *
 * 3. **`/run/linexa/hostinfo.conf`** ([writeHostInfoFeed]) — the structured,
 *    re-readable key=value feed consumed by the guest-side
 *    `/usr/local/bin/linexa-hostinfo` helper (installed 0755 by
 *    GuestRootfsInitializer.ensureGuestHostInfo; THE fix for the observed
 *    `-rw------- root root` permission class of failure). Written read-only
 *    alongside the overlays so the conf can never drift from them.
 *
 * Failure discipline mirrors the strict/fail-open gates around each call
 * site: file-write failures return a message (session paths abort like any
 * other /proc-snapshot failure; exec/daemon paths log-and-continue), while
 * collection failures degrade content to the legacy synthetic values rather
 * than shrinking the bind-over set.
 */
class HostInfoRuntimeBridge(
    private val hostSystemInfoProvider: HostSystemInfoProvider,
    private val fileManager: WorkspaceFileManager,
    private val envTickSampler: EnvTickSampler? = null,
    /**
     * Issue #9 (2026-09-03): per-workspace session start resolver. When it
     * returns a non-null instant for a running workspace, /proc/uptime and
     * the `uptime.workspace.seconds` feed key report the WORKSPACE's own
     * uptime (computed from the real recorded start time, refreshed live by
     * the overlay ticker); null keeps the host-uptime passthrough for
     * exec/daemon refresh paths. Wired to SessionManager in production; JVM
     * tests inject fixed instants.
     */
    private val sessionStartProvider: (String) -> java.time.Instant? = { null },
    /**
     * Native-readability seam (tests inject verdicts so the conditional
     * meminfo/swaps/cpuinfo/net paths are deterministic regardless of what
     * the MACHINE running the JVM can read).
     */
    internal val nativeReadableProbe: (String) -> Boolean = { path ->
        runCatching { File(path).readText().isNotEmpty() }.getOrDefault(false)
    }
) {

    private var tickerJob: Job? = null
    private var tickerScope: CoroutineScope? = null

    /**
     * LINEXA-PERF-001: last content successfully written per workspace. A tick
     * regenerates every overlay; most of them (version, vmstat, cpuinfo,
     * meminfo, network subtree) are byte-identical because their source only
     * changes on its own slow tier. Comparing against this map avoids the
     * previous read-the-whole-file-back verification for those entries. A
     * missing file still forces a write, so the memo can never mask an
     * externally deleted overlay.
     */
    private val lastOverlayContent = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** One-time readability verdicts; SELinux policy does not flip mid-boot. */
    private val nativeReadableCache = HashMap<String, Boolean>()

    // ── Channel 1: /proc bind-overs ────────────────────────────────────────

    /**
     * Rewrite `{rootfs}/proc/.<name>` (+ `.net/<name>`) from ONE selective
     * host pull, and mirror the structured feed into `/run/linexa`.
     *
     * Returns null on success or a user-meaningful failure string mirroring
     * [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureProcSnapshots]'s
     * contract so both call-site styles behave identically.
     */
    suspend fun refreshProcOverlays(
        rootfs: File,
        workspaceId: String,
        includeFeed: Boolean = true
    ): String? {
        // Phase M0-A: one async section per refresh (the snapshot provider and
        // the write lock make this cross-thread). Duration/files are counted
        // when capture is on; the finally still runs on every return path.
        val overlayTrace = PerfTrace.beginAsync("Linexa:hostInfoOverlay")
        val tickStartNanos = if (DebugCapture.isEnabled()) PerfTrace.nowNanos() else -1L
        var filesWritten = 0
        try {
            val procDir = File(rootfs, "proc")
            val procIsSymlink = runCatching { Files.isSymbolicLink(procDir.toPath()) }.getOrDefault(false)
            if (procIsSymlink) {
                Logger.w(TAG, "rootfs /proc is a symlink for $workspaceId — refusing to write overlays through it")
            }
            val hasProcDir = !procIsSymlink && procDir.isDirectory

            // LINEXA-PERF-001: the TIMER path must never bypass the provider
            // cache. `cachedSnapshotOf` collects only categories whose TTL has
            // expired; on the 2 s tick that is normally CPU alone. Categories
            // that only feed `/run/linexa/hostinfo.conf` are requested on feed
            // ticks (see FEED_REFRESH_EVERY_N_TICKS), not on every overlay tick.
            val overlayCategories = buildSet {
                add(HostInfoCategory.CPU)
                // meminfo/swaps overlays only exist where native passthrough
                // is denied; asking for them unconditionally would pay for a
                // meminfo collection the overlay does not consume.
                if (hasProcDir && !isNativeReadable("/proc/meminfo")) {
                    add(HostInfoCategory.MEMORY)
                    add(HostInfoCategory.SWAP)
                }
            }
            val snapshot = hostSystemInfoProvider.cachedSnapshotOf(
                if (includeFeed) BRIDGE_CATEGORIES else overlayCategories
            )

            val needEnvironmentMode = snapshot.cpu.totalStatTicks == null
            val envSnapshot = if (needEnvironmentMode) {
                envTickSampler?.sampleOnce(
                    snapshot.cpu.affinityCoreIndices
                        ?: List(snapshot.cpu.environmentVisibleCores) { it }
                )
            } else {
                null
            }

            if (hasProcDir) {
                val meminfoReadable = isNativeReadable("/proc/meminfo")
                val cpuinfoReadable = isNativeReadable("/proc/cpuinfo")
                val netDevReadable = isNativeReadable("/proc/net/dev")

                // Issue #9: the workspace's own uptime, from the real recorded
                // session start. Only a session that is actually running reports
                // it (an exec-path refresh has no session → host uptime stays).
                val workspaceStartedAt = sessionStartProvider(workspaceId)
                val workspaceUptimeSeconds = workspaceStartedAt?.let { started ->
                    java.time.Duration.between(started, java.time.Instant.now()).seconds
                        .coerceAtLeast(0L)
                }

                val contents = LinkedHashMap<String, String>()
                contents["stat"] = ProcSnapshotContentFactory.statContent(
                    cpu = snapshot.cpu,
                    device = snapshot.device,
                    envTicks = envSnapshot
                )
                contents["uptime"] = ProcSnapshotContentFactory.uptimeContent(
                    snapshot.device,
                    workspaceUptimeSeconds
                )
                contents["loadavg"] = ProcSnapshotContentFactory.loadavgContent(snapshot.cpu, envSnapshot)
                contents["version"] = ProcSnapshotContentFactory.versionContent()
                contents["vmstat"] = ProcSnapshotContentFactory.vmstatContent()

                // Conditional fallbacks — native richer ⇒ never shadowed.
                if (!meminfoReadable) {
                    ProcSnapshotContentFactory.meminfoContent(snapshot.memory, snapshot.swap)
                        .takeIf { it.isNotEmpty() }?.let { contents["meminfo"] = it }
                    ProcSnapshotContentFactory.swapsContent(snapshot.swap, snapshot.memory.zram)
                        .takeIf { it.isNotEmpty() }?.let { contents["swaps"] = it }
                }
                if (!cpuinfoReadable) {
                    ProcSnapshotContentFactory.cpuinfoContent(snapshot.cpu)
                        .takeIf { it.isNotEmpty() }?.let { contents["cpuinfo"] = it }
                }
                // Network telemetry follows the SAME policy ladder as
                // meminfo/swaps/cpuinfo: passthrough stays authoritative when the
                // kernel serves it; synthesis covers only the denied case.
                if (!netDevReadable) {
                    contents["net/dev"] = ProcSnapshotContentFactory.netDevContent(snapshot.network)
                    contents["net/route"] = ProcSnapshotContentFactory.netRouteContent(snapshot.network)
                    if (snapshot.network.interfaces.any { it.ipv6WithPrefix.isNotEmpty() }) {
                        contents["net/if_inet6"] = ProcSnapshotContentFactory.ifInet6Content(snapshot.network)
                    }
                }

                // Guard against drift between THIS factory's names and the
                // launcher's authoritative set: an extra/missing name here would
                // silently break the bind pairing ("keys MUST be a subset"
                // invariant of R-6, extended verbatim to the net subtree).
                val knownNames = PRootLauncher.PROC_BIND_OVER_RELATIVE_PATHS
                require(contents.keys.all { key ->
                    key in knownNames || "net/${key.removePrefix("net/")}" in knownNames
                }) {
                    "proc overlay names diverged from PRootLauncher.PROC_BIND_OVER_RELATIVE_PATHS"
                }

                val writeFailure = synchronized(RootfsWriteLock.monitor) {
                    // Phase M0-A: same-thread synchronous section around the
                    // hidden-bind-source write set (no suspension inside).
                    val writeTrace = PerfTrace.beginSync("Linexa:hostInfoOverlayWrite")
                    try {
                        writeProcOverlaysLocked(procDir, contents, workspaceId)
                    } finally {
                        PerfTrace.endSync(writeTrace)
                    }
                }
                if (writeFailure != null) return writeFailure
                filesWritten += contents.size

                if (DebugCapture.isEnabled()) {
                    Logger.d(TAG, "host /proc overlays refreshed for $workspaceId (${contents.size} files)")
                }
            }

            if (includeFeed) {
                val feedFailure = writeHostInfoFeed(rootfs, workspaceId, snapshot)
                if (feedFailure != null) return feedFailure
                filesWritten += 1
            }
            return null
        } finally {
            PerfTrace.endAsync("Linexa:hostInfoOverlay", overlayTrace)
            if (tickStartNanos >= 0L) {
                PerfCounters.overlayTick(PerfTrace.nowNanos() - tickStartNanos, filesWritten)
            }
        }
    }

    /**
     * Writes the overlay set while the archive's `/proc` mode is temporarily
     * widened.
     *
     * Arch and Fedora archives restore `/proc` as `0555`; PRoot's fake root
     * cannot bypass the host DAC check, so the app's own uid needs an
     * owner-write window for the few hidden bind-source files. The window is
     * closed (exact archive mode restored) even when a write fails. A failed
     * restore is reported, never swallowed: a silently more-writable `/proc`
     * is a provisioning defect even though the directory is shadowed by the
     * host `/proc` inside the guest.
     *
     * Nested sources live in the hidden `.net` directory (`/proc/.net/dev`
     * bound to `/proc/net/dev`). It is created under the widened `/proc`
     * window, then gets its own window because an imported image may carry it
     * read-only too.
     */
    private fun writeProcOverlaysLocked(
        procDir: File,
        contents: Map<String, String>,
        workspaceId: String
    ): String? {
        val windows = ArrayList<RootfsWriteWindow>()
        var failure: String? = null
        var skipWrites = false

        fun rememberFailure(message: String) {
            failure = failure?.let { "$it; $message" } ?: message
            Logger.e(TAG, message)
        }

        try {
            windows += RootfsWriteWindow.open(procDir)

            if (contents.keys.any { '/' in it }) {
                val nestedDir = File(procDir, ".net")
                if (!nestedDir.exists() && !nestedDir.mkdirs()) {
                    rememberFailure(
                        "could not create host /proc overlay directory ${nestedDir.absolutePath} for $workspaceId"
                    )
                    skipWrites = true
                } else if (!nestedDir.isDirectory ||
                    runCatching { Files.isSymbolicLink(nestedDir.toPath()) }.getOrDefault(false)
                ) {
                    rememberFailure(
                        "host /proc overlay directory ${nestedDir.absolutePath} is not a real directory for $workspaceId"
                    )
                    skipWrites = true
                } else {
                    windows += RootfsWriteWindow.open(nestedDir)
                }
            }

            if (!skipWrites) {
                // LINEXA-PERF-001: write only the overlays whose content moved
                // (or that are missing on disk); identical ones are verified
                // with a single metadata check instead of a full read-back.
                val previous = lastOverlayContent[workspaceId].orEmpty()
                val pending = contents.entries.filter { (name, content) ->
                    previous[name] != content || !File(procDir, ".$name").isFile
                }
                for ((relativeName, content) in pending) {
                    try {
                        ReadOnlyOverlayFile.write(File(procDir, ".$relativeName"), content)
                    } catch (e: Exception) {
                        rememberFailure(
                            "could not write host /proc overlay $relativeName for $workspaceId: ${e.message}"
                        )
                        break
                    }
                }
                if (failure == null) {
                    lastOverlayContent[workspaceId] = contents
                }
            }
        } catch (e: Exception) {
            rememberFailure("could not prepare host /proc overlays for $workspaceId: ${e.message}")
        } finally {
            for (window in windows.asReversed()) {
                try {
                    window.close()
                } catch (e: Exception) {
                    rememberFailure(e.message ?: "could not restore ${window.directory.absolutePath}")
                }
            }
        }

        return failure
    }

    /** Memoized verdict from the injectable [nativeReadableProbe]. */
    private fun isNativeReadable(path: String): Boolean =
        synchronized(nativeReadableCache) {
            nativeReadableCache.getOrPut(path) { nativeReadableProbe(path) }
        }

    /**
     * Channel 3: `/run/linexa/hostinfo.conf`. Deterministic `KEY=value`
     * rendering of the feed-superset (documented in docs/HOST_SYSTEM_INFO.md);
     * values are resolver-canonical (no whitespace/newlines possible), so
     * shell consumers never face quoting traps. Mode 0444.
     */
    private fun writeHostInfoFeed(
        rootfs: File,
        workspaceId: String,
        snapshot: com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot
    ): String? {
        val runLinexa = File(File(rootfs, "run"), "linexa")
        return try {
            if (!runLinexa.isDirectory && !runLinexa.mkdirs()) {
                // Rootfs without a writable /run (should not happen after
                // ENSURE_RUN) — skip silently like the overlays' fail-open.
                Logger.w(TAG, "hostinfo feed skipped: cannot create ${runLinexa.absolutePath}")
                return null
            }
            val resolved = HostInfoKeyResolver.resolve(snapshot, HOSTINFO_FEED_KEYS)
            val body = buildString {
                append("# Generated by Linexa — authoritative host information feed.\n")
                append("#consumed-by: /usr/local/bin/linexa-hostinfo\n")
                append("#generated-at-ms: ${System.currentTimeMillis()}\n")
                for ((key, value) in resolved) append("$key=$value\n")
                // Issue #9: the workspace's own uptime alongside the host's —
                // clearly LABELED as workspace-relative so no consumer can
                // mistake it for a host fact. Absent while no session runs.
                sessionStartProvider(workspaceId)?.let { started ->
                    val seconds = java.time.Duration.between(started, java.time.Instant.now()).seconds
                        .coerceAtLeast(0L)
                    append("uptime.workspace.seconds=$seconds\n")
                }
            }
            val feedTrace = PerfTrace.beginSync("Linexa:hostInfoOverlayWrite")
            try {
                ReadOnlyOverlayFile.write(File(runLinexa, HOSTINFO_FEED_NAME), body)
            } finally {
                PerfTrace.endSync(feedTrace)
            }
            null
        } catch (e: Exception) {
            val msg = "could not write hostinfo feed for workspace: ${e.message}"
            Logger.e(TAG, msg)
            msg
        }
    }

    /**
     * Session-driven freshness for /proc overlays: telemetry only ticks
     * while at least one workspace runs. Cadence tightened from R-6's 30 s
     * to [OVERLAY_REFRESH_MS] so guest-side delta tools (htop samples every
     * ~1.5 s over two consecutive WRITES) see moving counters instead of
     * frozen ones — the exact mechanical cause of the audited "CPU% = N/A".
     *
     * **PERF-013 (2026-11-23):** the refresh loop is driven by the session
     * state itself, not by an unconditional `while (true)`. The job suspends on
     * [sessionStates] and only starts ticking when the set of `Running`
     * workspaces becomes non-empty; when the last one stops, `collectLatest`
     * cancels the loop and the job is idle again. `collectLatest` also means a
     * state change (a second workspace starting, or one stopping) cancels the
     * in-flight pass and restarts from the new set immediately rather than
     * waiting out the delay.
     *
     * **LINEXA-PERF-001 (2026-11-26):** three bounded changes to the same
     * mechanism, because the audit measured this loop as the app's entire
     * idle cost (~4.5–6.7 % of one core, background included):
     *
     *  1. [foreground] (when supplied) selects [BACKGROUND_REFRESH_MS] while
     *     no Linexa activity is visible. Guest `/proc` remains readable and
     *     consistent — counters are cumulative, so a moved window never
     *     fabricates a value — but the app no longer pays 2 s cadence for a
     *     guest nobody can see. Returning to the foreground restarts the loop
     *     through `collectLatest` and refreshes immediately.
     *  2. The structured `/run/linexa/hostinfo.conf` feed (which needs every
     *     host category) is rewritten once per
     *     [FEED_REFRESH_EVERY_N_TICKS] ticks instead of on every tick; the
     *     `/proc` files that guest tools actually diff stay on the fast
     *     cadence.
     *  3. Collection is TTL-gated (`cachedSnapshotOf`) and byte-identical
     *     overlays are skipped without a read-back, so a tick no longer
     *     performs a full host collection plus eight file reads.
     */
    @JvmOverloads
    fun startOverlayTicker(
        sessionStates: StateFlow<Map<String, RuntimeState>>,
        refreshMs: Long = OVERLAY_REFRESH_MS,
        foreground: StateFlow<Boolean>? = null
    ) {
        synchronized(tickerLock) {
            if (tickerJob?.isActive == true) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            tickerScope = scope
            val runningFlow = sessionStates
                .map { states -> states.filterValues { it is RuntimeState.Running }.keys.toSet() }
                .distinctUntilChanged()
            val gated = if (foreground == null) {
                runningFlow.map { running -> running to true }
            } else {
                kotlinx.coroutines.flow.combine(
                    runningFlow,
                    foreground
                ) { running, visible -> running to visible }
            }
            tickerJob = scope.launch {
                gated.collectLatest { (runningWorkspaces, visible) ->
                    if (runningWorkspaces.isEmpty()) return@collectLatest
                    val cadence = if (visible) refreshMs else BACKGROUND_REFRESH_MS
                    var tick = 0L
                    while (true) {
                        // Feed ticks pay the full host collection; overlay-only
                        // ticks collect just what their files render.
                        val includeFeed = tick % FEED_REFRESH_EVERY_N_TICKS == 0L
                        for (workspaceId in runningWorkspaces) {
                            val rootfs = fileManager.getRootfsPath(workspaceId)
                            if (!rootfs.isDirectory) continue
                            runCatching { refreshProcOverlays(rootfs, workspaceId, includeFeed) }
                                .onFailure { Logger.w(TAG, "overlay refresh failed for $workspaceId: ${it.message}") }
                        }
                        tick++
                        delay(cadence)
                    }
                }
            }
        }
    }

    /** Stop the overlay refresher (RuntimeService teardown path). Idempotent. */
    fun stopOverlayTicker() {
        synchronized(tickerLock) {
            tickerJob?.cancel()
            tickerJob = null
            tickerScope?.cancel()
            tickerScope = null
        }
        // Drop accumulated pass state — the next session re-seeds cleanly.
        lastOverlayContent.clear()
        envTickSampler?.reset()
    }

    // ── Channel 2: selective LINEXA_* env ──────────────────────────────

    /**
     * Build the additive guest environment for one launch.
     *
     * Resolution order honors §15 exactly: [keysRequested] (config allowlist)
     * governs what appears AT ALL; empty means the conservative
     * [DEFAULT_GUEST_KEYS]. Only keys whose value is genuinely known are
     * emitted — unknown host facts leave NO variable behind, so `env | grep
     * LINEXA_` cannot lie about availability either.
     */
    suspend fun buildGuestEnv(keysRequested: Set<HostInfoKey>): Map<String, String> {
        if (keysRequested.isEmpty()) return emptyMap()
        val resolved = hostSystemInfoProvider.snapshotOf(
            HostInfoKeyResolver.categoriesFor(keysRequested)
        ).let { snapshot -> HostInfoKeyResolver.resolve(snapshot, keysRequested) }

        return LinkedHashMap<String, String>(resolved.size).apply {
            for ((keyName, value) in resolved) {
                put("$ENV_PREFIX$keyName", value)
            }
        }
    }

    private val tickerLock = Any()

    companion object {
        private const val TAG = "HostInfoBridge"

        /** Every injected variable starts with this — see [buildGuestEnv]. */
        const val ENV_PREFIX = "LINEXA_HOST_"

        /**
         * Name of the structured feed inside the guest `/run/linexa/`.
         */
        const val HOSTINFO_FEED_NAME = "hostinfo.conf"

        /**
         * The minimum useful guest-visible set when a workspace/config asks
         * for host info without naming keys. Conservative but COMPLETE about
         * resource semantics: identity + capacity + uptime + the Phase 2
         * triad (configured/granted/online) + transport posture.
         */
        val DEFAULT_GUEST_KEYS: Set<HostInfoKey> = setOf(
            HostInfoKey.CPU_CORES,
            HostInfoKey.CPU_AFFINITY_COUNT,
            HostInfoKey.CPU_ONLINE_COUNT,
            HostInfoKey.GPU_MODEL,
            HostInfoKey.GPU_VENDOR,
            HostInfoKey.MEMORY_TOTAL_KB,
            HostInfoKey.MEMORY_AVAILABLE_KB,
            HostInfoKey.SWAP_TOTAL_KB,
            HostInfoKey.SWAP_FREE_KB,
            HostInfoKey.STORAGE_FREE_BYTES,
            HostInfoKey.UPTIME_SECONDS,
            HostInfoKey.ANDROID_VERSION,
            HostInfoKey.ANDROID_SDK_INT,
            HostInfoKey.DEVICE_MODEL,
            HostInfoKey.DEVICE_MANUFACTURER,
            HostInfoKey.MACHINE_ARCH,
            HostInfoKey.NETWORK_TYPE,
            HostInfoKey.NETWORK_INTERFACE
        )

        /**
         * Everything `/run/linexa/hostinfo.conf` carries. Deliberately the
         * READ_ONLY_SAFE ∪ SANITIZED classifications from the capability
         * matrix — nothing here needs a permission prompt, identifies the
         * user's location, or admits control.
         */
        val HOSTINFO_FEED_KEYS: Set<HostInfoKey> = DEFAULT_GUEST_KEYS + setOf(
            HostInfoKey.CPU_ARCHITECTURE,
            HostInfoKey.CPU_MODEL,
            HostInfoKey.CPU_MAX_FREQ_KHZ,
            HostInfoKey.CPU_AFFINITY_CORES,
            HostInfoKey.CPU_USAGE_PERCENT,
            HostInfoKey.CPU_LOADAVG_1M,
            HostInfoKey.GPU_TEMP_C,
            HostInfoKey.GPU_BUSY_PERCENT,
            HostInfoKey.GPU_CLOCK_KHZ,
            HostInfoKey.MEMORY_USED_KB,
            HostInfoKey.KERNEL_RELEASE_HOST,
            HostInfoKey.BATTERY_PERCENT,
            HostInfoKey.BATTERY_CHARGING,
            HostInfoKey.THERMAL_STATUS,
            HostInfoKey.NETWORK_METERED,
            HostInfoKey.NETWORK_IPV4,
            HostInfoKey.SELINUX_ENFORCING
        )

        /** Categories the bridge's own consumers need on every pull. */
        internal val BRIDGE_CATEGORIES: Set<HostInfoCategory> = setOf(
            HostInfoCategory.SYSTEM,
            HostInfoCategory.CPU,
            HostInfoCategory.GPU,
            HostInfoCategory.MEMORY,
            HostInfoCategory.SWAP,
            HostInfoCategory.NETWORK,
            HostInfoCategory.BATTERY,
            HostInfoCategory.THERMAL,
            HostInfoCategory.STORAGE
        )

        /**
         * Long-lived-session overlay cadence — sized to guest tooling's own
         * sampling windows (see [startOverlayTicker]); hardware-fresh values
         * always come from env/provider on demand instead of tighter polling
         * here ("no unnecessary background polling" rule).
         */
        const val OVERLAY_REFRESH_MS = 2_000L

        /**
         * LINEXA-PERF-001: cadence while no Linexa activity is visible. The
         * guest keeps receiving monotonic counters; only the visible-window
         * resolution changes. 15 s is still far inside every guest tool's
         * tolerance (fifteen consecutive htop refreshes share one window and
         * the average is identical), while cutting background wake-ups by
         * 7.5×.
         */
        const val BACKGROUND_REFRESH_MS = 15_000L

        /**
         * LINEXA-PERF-001: `/run/linexa/hostinfo.conf` carries slow-moving
         * identity/capacity facts; rewriting it (and paying the full host
         * collection it needs) every tenth of a second is waste. It is
         * refreshed on the first tick and every 5th tick thereafter (10 s at
         * the foreground cadence).
         */
        const val FEED_REFRESH_EVERY_N_TICKS = 5L
    }
}

/**
 * One temp-then-rename write ending chmod'd 0444, byte-identical in spirit
 * to the installer's snapshot discipline: a half-written overlay must never
 * be visible mid-launch, and a mode-0444 regular file inside app-private
 * storage cannot serve as a WRITE-THROUGH path to anything (the guest's O_WRONLY
 * opens hit DAC: owner-read-only, and no mount indirection exists — PRoot
 * resolves the very same inode the app wrote). Phase 12 requirement pinned
 * by tests.
 */
internal object ReadOnlyOverlayFile {
    fun write(target: File, content: String) {
        // P0-1: the overlay ticker re-renders the same small files every two
        // seconds; a byte-equal, correctly-moded file needs no write/rename
        // pair. Content that actually changed (uptime, counters) still writes.
        if (isCurrent(target, content)) return
        val tmp = File(target.parentFile, "${target.name}.hinfo-tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Fallback parity with the installer: remove target, retry once.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed for ${target.absolutePath}")
            }
        }
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ)
            )
        }
    }

    private fun isCurrent(target: File, content: String): Boolean {
        return try {
            if (!target.isFile || Files.isSymbolicLink(target.toPath())) return false
            val expected = content.toByteArray(Charsets.UTF_8)
            if (target.length() != expected.size.toLong() ||
                !target.readBytes().contentEquals(expected)
            ) {
                return false
            }
            Files.getPosixFilePermissions(target.toPath()) == setOf(PosixFilePermission.OWNER_READ)
        } catch (_: Exception) {
            false
        }
    }
}
