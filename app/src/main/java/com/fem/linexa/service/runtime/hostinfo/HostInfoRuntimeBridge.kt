package com.fem.linexa.service.runtime.hostinfo

import com.fem.linexa.core.filesystem.RootfsWriteLock
import com.fem.linexa.core.filesystem.RootfsWriteWindow
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger
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
    suspend fun refreshProcOverlays(rootfs: File, workspaceId: String): String? {
        val procDir = File(rootfs, "proc")
        val procIsSymlink = runCatching { Files.isSymbolicLink(procDir.toPath()) }.getOrDefault(false)
        if (procIsSymlink) {
            Logger.w(TAG, "rootfs /proc is a symlink for $workspaceId — refusing to write overlays through it")
        }
        val hasProcDir = !procIsSymlink && procDir.isDirectory

        // The full-feed pull runs EVEN without a /proc dir so the conf feed
        // still updates (Phase 15 contract); category TTLs keep repeats cheap.
        val snapshot = hostSystemInfoProvider.snapshotOf(BRIDGE_CATEGORIES)

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
                writeProcOverlaysLocked(procDir, contents, workspaceId)
            }
            if (writeFailure != null) return writeFailure

            if (DebugCapture.isEnabled()) {
                Logger.d(TAG, "host /proc overlays refreshed for $workspaceId (${contents.size} files)")
            }
        }

        val feedFailure = writeHostInfoFeed(rootfs, workspaceId, snapshot)
        if (feedFailure != null) return feedFailure
        return null
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
                for ((relativeName, content) in contents) {
                    try {
                        ReadOnlyOverlayFile.write(File(procDir, ".$relativeName"), content)
                    } catch (e: Exception) {
                        rememberFailure(
                            "could not write host /proc overlay $relativeName for $workspaceId: ${e.message}"
                        )
                        break
                    }
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
            ReadOnlyOverlayFile.write(File(runLinexa, HOSTINFO_FEED_NAME), body)
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
     * Cost stays bounded: category TTLs gate re-collection, and the tick
     * work itself is a handful of small atomic file writes.
     */
    @JvmOverloads
    fun startOverlayTicker(sessionStates: StateFlow<Map<String, RuntimeState>>, refreshMs: Long = OVERLAY_REFRESH_MS) {
        synchronized(tickerLock) {
            if (tickerJob?.isActive == true) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            tickerScope = scope
            tickerJob = scope.launch {
                while (true) {
                    // Single read per tick: consecutive .value reads could race
                    // a state flip and mix keys across two maps.
                    val states = sessionStates.value
                    val runningWorkspaces = states.keys.filter { id ->
                        states[id] is RuntimeState.Running
                    }
                    for (workspaceId in runningWorkspaces) {
                        val rootfs = fileManager.getRootfsPath(workspaceId)
                        if (!rootfs.isDirectory) continue
                        runCatching { refreshProcOverlays(rootfs, workspaceId) }
                            .onFailure { Logger.w(TAG, "overlay refresh failed for $workspaceId: ${it.message}") }
                    }
                    delay(refreshMs)
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
}
