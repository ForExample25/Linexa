package com.fem.linexa.data.hostinfo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import com.fem.linexa.data.resource.AndroidDeviceCpuCounterReader
import com.fem.linexa.data.resource.CpuTopologyProvider
import com.fem.linexa.data.resource.DeviceCpuMonitor
import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuCore
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostGpuInfo
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetInterface
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostStorageVolume
import com.fem.linexa.domain.hostinfo.HostSwapInfo
import com.fem.linexa.domain.hostinfo.HostThermalInfo
import com.fem.linexa.domain.hostinfo.HostZramInfo
import com.fem.linexa.domain.resource.ProcMetricParsers
import java.io.File
import java.net.Inet4Address
import java.util.Locale

/**
 * The one place in Linexa that touches Android platform surfaces to answer
 * "what does this HOST look like".
 *
 * Every method here embodies the same four disciplines:
 *
 * 1. **No fabrication.** A value the OS does not hand out becomes `null`,
 *    never 0, never a guessed string. Consumers see unknown; nothing sees a
 *    plausible lie.
 * 2. **Sandbox-aware sourcing.** Where /proc/<file> is SELinux-denied to
 *    untrusted_app on modern Android (stat/loadavg/version…), an equivalent
 *    syscall or platform API is preferred (`Os.uname()` over /proc/version,
 *    `ActivityManager.MemoryInfo` alongside meminfo). The denied path is
 *    still ATTEMPTED where devices differ, because plenty of OEM kernels do
 *    allow app-uid reads — absence, not presence, is what must be proven.
 * 3. **Failure isolation.** Every single platform touch is individually
 *    guarded: one broken sysfs node cannot poison a whole category, let
 *    alone crash a caller. Methods return complete models whose field-level
 *    nullability IS the failure report.
 * 4. **No hidden I/O on unexpected threads.** Callers (the provider) drive
 *    everything from Dispatchers.IO; these functions stay synchronous by
 *    design so their cost is explicit at every call site.
 *
 * Device CPU is delegated to the singleton [DeviceCpuMonitor] so this screen
 * and every other Linexa surface diff the SAME counters over the SAME window.
 * The monitor is a constructor parameter rather than private state because two
 * independent delta tables would reproduce the very bug this class used to
 * have: simultaneous consumers shortening each other's sample interval.
 */
internal class AndroidHostInfoSources(
    private val context: Context,
    private val deviceCpuMonitor: DeviceCpuMonitor = DeviceCpuMonitor(
        CpuTopologyProvider(),
        AndroidDeviceCpuCounterReader()
    )
) : HostInfoSources {

    // ── CPU ────────────────────────────────────────────────────────────────

    /** GPU renderer identity is process-cached: silicon cannot change mid-run. */
    private val gpuRendererProbeLock = Any()

    @Volatile
    private var cachedGpuRenderer: String? = null

    @Volatile
    private var gpuRendererProbed = false

    override fun collectCpu(): HostCpuInfo {
        val configuredCores = configuredCoreCount()
        val abis = if (Build.SUPPORTED_ABIS.isNullOrEmpty()) emptyList() else Build.SUPPORTED_ABIS.toList()

        val cpuinfo = readFileOrNull("/proc/cpuinfo")
        val modelName = cpuinfo?.firstLineValue("model name", "Processor", "Hardware")
        // KDoc contract names BOTH keys; kernels differ over which they ship.
        val implementation = cpuinfo?.let { it.lineValue("CPU implementer") ?: it.lineValue("CPU part") }

        val cores = enumerateSysfsCores(configuredCores)
        val sysOnline = parseCpuOnlineList(readFileOrNull("/sys/devices/system/cpu/online"))

        // Scheduler-granted CPU subset (the number coreutils `nproc` prints).
        // /proc/self is ALWAYS readable to the app regardless of SELinux
        // hidepid policy, so this works on the same locked devices where
        // /proc/stat refuses — no permission story between them.
        val affinityList = readFileOrNull("/proc/self/status")
            ?.let { ProcMetricParsers.cpuAffinityAllowedList(it) }
            ?.filter { it < configuredCores }

        // One authoritative delta for the whole process. `/proc/stat` is the
        // primary source; cpuidle is the non-root fallback on Android builds
        // that SELinux-deny app reads of /proc/stat. A first sample is
        // PENDING, and a device with neither source is UNAVAILABLE — never 0.
        val cpuSnapshot = deviceCpuMonitor.snapshot()
        val perCoreUsagePercent = coreIndexedUsages(
            perCore = cpuSnapshot.perCorePercent,
            configuredCores = configuredCores
        )
        val statSnapshot = cpuSnapshot.procStat
        val loadParts = readFileOrNull("/proc/loadavg")?.trim()?.split(WHITESPACE)

        return HostCpuInfo(
            logicalCores = configuredCores,
            onlineCores = sysOnline,
            architecture = runCatching { Os.uname().machine }.getOrNull(),
            supportedAbis = abis,
            cpuModelName = modelName,
            cpuImplementation = implementation,
            coresPerCoreInfo = cores,
            minFrequencyKhz = cores.mapNotNull { it.minKhz }.minOrNull(),
            maxFrequencyKhz = cores.mapNotNull { it.maxKhz }.maxOrNull(),
            currentFrequencyKhz = cores.mapNotNull { it.currentKhz }
                .takeIf { it.isNotEmpty() }?.average()?.toLong(),
            overallUsagePercent = cpuSnapshot.overallPercent,
            perCoreUsagePercent = perCoreUsagePercent,
            loadAvg1m = loadParts?.getOrNull(0)?.toFloatOrNull(),
            loadAvg5m = loadParts?.getOrNull(1)?.toFloatOrNull(),
            loadAvg15m = loadParts?.getOrNull(2)?.toFloatOrNull(),
            // Raw /proc/stat absolutes for the guest /proc/stat overlay factory:
            // guest-side `top` diffs between successive overlay writes, which
            // only stays valid when emitted values are cumulative totals. The
            // snapshot is the exact read behind overallUsagePercent, so the
            // overlay and the UI cannot disagree.
            totalStatTicks = statSnapshot?.aggregate?.totalTicks,
            idleInclIowaitStatTicks = statSnapshot?.aggregate?.idleTicks,
            totalPerCoreStatTicks = statSnapshot?.perCore?.mapValues { it.value.totalTicks },
            affinityCoreIndices = affinityList
        )
    }

    /**
     * Convert the monitor's kernel-core-keyed map into the flat list contract
     * of [HostCpuInfo.perCoreUsagePercent]: list index == kernel core number.
     *
     * Padding to [configuredCores] keeps `core.index` a valid list index for
     * every sysfs core. Offline cores keep a null slot instead of letting the
     * next core slide into their position.
     */
    private fun coreIndexedUsages(
        perCore: Map<Int, Float?>,
        configuredCores: Int
    ): List<Float?> {
        if (perCore.isEmpty()) return emptyList()
        val highestKey = perCore.keys.maxOrNull() ?: return emptyList()
        val size = maxOf(configuredCores, highestKey + 1)
        return List(size) { core -> perCore[core] }
    }

    private fun configuredCoreCount(): Int {
        val conf = runCatching {
            Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt()
        }.getOrNull()?.takeIf { it > 0 }
        return conf ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(HostCpuInfo.FALLBACK_CORES)
    }

    /** Per-core cpufreq sweep; bounded so a pathological sysfs cannot loop forever. */
    private fun enumerateSysfsCores(limit: Int): List<HostCpuCore> {
        val base = File("/sys/devices/system/cpu")
        val entries = runCatching {
            base.list { _, name -> name.matches(REGEX_CPU_DIR) }?.toList().orEmpty()
        }.getOrNull() ?: return emptyList()
        return entries.asSequence()
            .mapNotNull { it.removePrefix("cpu").toIntOrNull() }
            .filter { it >= 0 && it < limit.coerceAtMost(MAX_ENUMERATED_CORES) }
            .sorted()
            .map { index ->
                val freqDir = File(base, "cpu$index/cpufreq")
                HostCpuCore(
                    index = index,
                    online = readFileOrNull(File(base, "cpu$index/online"))?.trim()?.toIntOrNull()
                        ?.let { it != 0 },
                    minKhz = readFileOrNull(File(freqDir, "scaling_min_freq"))?.trim()?.toLongOrNull(),
                    maxKhz = readFileOrNull(File(freqDir, "scaling_max_freq"))?.trim()?.toLongOrNull(),
                    currentKhz = readFileOrNull(File(freqDir, "scaling_cur_freq"))?.trim()?.toLongOrNull()
                )
            }
            .toList()
    }

    /** "0-3,6" style online list → count of listed ranges' members, best effort. */
    private fun parseCpuOnlineList(content: String?): Int? {
        if (content.isNullOrBlank()) return null
        var total = 0
        for (part in content.trim().split(',')) {
            val range = part.split('-')
            val start = range.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val end = if (range.size > 1) range[1].trim().toIntOrNull() ?: start else start
            if (end >= start && end - start < MAX_ONLINE_RANGE_SPAN) total += (end - start + 1)
        }
        return total.takeIf { it > 0 }
    }

    // ── GPU ────────────────────────────────────────────────────────────────

    /**
     * Graphics silicon identity + live vendor telemetry (hardware-info plugin
     * family completion — the GPU previously existed only as a hard-coded
     * null inside [HostThermalInfo]).
     *
     * Sourcing ladder, in honesty order:
     *  1. [cachedGpuRenderer] — an EGL GLES2 context's `GL_RENDERER` string.
     *     Every GLES device answers with the actual driver identity, which
     *     makes this the one source that does not depend on OEM sysfs layout.
     *     Probed once per process; a failed probe stays failed (the decision
     *     is cached with the value) because re-creating contexts every
     *     refresh is exactly the kind of hidden polling this subsystem avoids.
     *  2. Vendor sysfs model nodes — consulted only when the EGL probe could
     *     not answer.
     *  3. Live telemetry (temperature / busy / clocks) — best-effort per
     *     refresh from the kernel's thermal-class and vendor nodes; every
     *     denied or absent node leaves its field null.
     */
    override fun collectGpu(): HostGpuInfo {
        val renderer = cachedGpuRenderer()
        val sysfsModel = if (renderer == null) sysfsGpuModel() else null
        val model = renderer ?: sysfsModel
        return HostGpuInfo(
            model = model,
            vendorFamily = HostGpuInfo.vendorFamilyOf(model),
            temperatureC = thermalZoneMaxTemperatureC("gpu"),
            busyPercent = gpuBusyPercent(),
            clockKhz = gpuCurrentClockKhz(),
            maxClockKhz = gpuMaxClockKhz(),
            sourceLabel = when {
                renderer != null -> "egl"
                sysfsModel != null -> "sysfs"
                else -> null
            }
        )
    }

    /** Memoized renderer probe; see [collectGpu] for the caching rationale. */
    private fun cachedGpuRenderer(): String? =
        synchronized(gpuRendererProbeLock) {
            if (!gpuRendererProbed) {
                gpuRendererProbed = true
                cachedGpuRenderer = probeGpuRendererViaEgl()
            }
            cachedGpuRenderer
        }

    /**
     * One-shot EGL probe: default display → GLES2 config → 1×1 pbuffer →
     * current context → `GL_RENDERER`, then full teardown. Every step is
     * guarded; any failure unwinds to null without touching global GL state
     * (the context is private to this probe and destroyed before returning).
     */
    private fun probeGpuRendererViaEgl(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) return@runCatching null
        val major = IntArray(1)
        val minor = IntArray(1)
        if (!EGL14.eglInitialize(display, major, 0, minor, 0)) {
            EGL14.eglTerminate(display)
            return@runCatching null
        }
        val renderer: String? = try {
            val configAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] < 1 || configs[0] == null
            ) {
                return@runCatching null
            }
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return@runCatching null
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroySurface(display, surface)
                return@runCatching null
            }
            try {
                if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    try {
                        GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() }
                    } finally {
                        EGL14.eglMakeCurrent(
                            display,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                        )
                    }
                } else null
            } finally {
                EGL14.eglDestroyContext(display, context)
                EGL14.eglDestroySurface(display, surface)
            }
        } finally {
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        renderer
    }.getOrNull()

    /** Vendor model nodes, first readable wins; all absent → null. */
    private fun sysfsGpuModel(): String? {
        for (path in GPU_MODEL_SYSFS_PATHS) {
            readFileOrNull(path)?.trim()?.takeIf { it.isNotEmpty() && it != "0" }?.let { return it }
        }
        return null
    }

    /**
     * GPU utilization 0..100. Adreno publishes a plain integer; MediaTek's
     * node carries a trailing `%`. Anything else (negative, >100, garbage)
     * is refused rather than clamped into plausible-looking truth.
     */
    private fun gpuBusyPercent(): Int? {
        for (path in GPU_BUSY_SYSFS_PATHS) {
            val value = readFileOrNull(path)?.trim()?.trimEnd('%')?.toIntOrNull() ?: continue
            if (value in 0..100) return value
        }
        return null
    }

    /**
     * GPU clocks. Only the devfreq kernel ABI is trusted here: `cur_freq`/
     * `max_freq` are documented Hz, converted to kHz exactly once. Vendor
     * nodes with undocumented units (MediaTek's freq files under
     * `/sys/kernel/gpu/`) are deliberately NOT consulted — a kHz/Hz guess
     * would be a fabricated unit, the exact defect class this subsystem
     * forbids.
     */
    private fun gpuCurrentClockKhz(): Long? =
        readFileOrNull(GPU_CUR_FREQ_PATH)?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.div(1000L)

    private fun gpuMaxClockKhz(): Long? {
        for (path in GPU_MAX_CLOCK_SYSFS_PATHS) {
            val hz = readFileOrNull(path)?.trim()?.toLongOrNull() ?: continue
            if (hz > 0) return hz / 1000L
        }
        return null
    }

    /**
     * Hottest readable thermal zone whose `type` contains [typeSubstring]
     * ("gpu" → GPU zones, "cpu" → CPU zones). Kernel ABI: `temp` is
     * millidegrees Celsius. Readings outside the 10–150 °C sanity window are
     * refused (tenths-of-a-degree kernels and garbage both fail it), so a
     * number here is always a plausible real temperature. Denied `temp`
     * reads (the modern SELinux default) simply skip the zone; zero readable
     * zones → null.
     */
    private fun thermalZoneMaxTemperatureC(typeSubstring: String): Float? {
        val base = File("/sys/class/thermal")
        val zones = runCatching {
            base.list { _, name -> name.startsWith("thermal_zone") }?.toList()
        }.getOrNull() ?: return null
        var best: Float? = null
        for (zone in zones.sorted()) {
            val type = readFileOrNull(File(base, "$zone/type"))?.trim()?.lowercase() ?: continue
            if (typeSubstring !in type) continue
            val milli = readFileOrNull(File(base, "$zone/temp"))?.trim()?.toFloatOrNull() ?: continue
            val celsius = milli / 1000f
            if (celsius < GPU_TEMP_SANITY_MIN_C || celsius > GPU_TEMP_SANITY_MAX_C) continue
            if (best == null || celsius > best) best = celsius
        }
        return best
    }

    // ── Memory / swap / zram ───────────────────────────────────────────────

    override fun collectMemory(): HostMemoryInfo {
        val meminfo = readFileOrNull("/proc/meminfo")

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val amInfo = activityManager?.let { manager ->
            // getMemoryInfo FILLS a caller-owned struct rather than returning
            // one — the classic Android out-param shape.
            val info = ActivityManager.MemoryInfo()
            runCatching {
                manager.getMemoryInfo(info)
                info
            }.getOrNull()
        }

        val memTotalKb = meminfo?.let { ProcMetricParsers.memTotalKb(it) }
        val memAvailableKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "MemAvailable") }
        val usedLinuxKb = if (memTotalKb != null && memAvailableKb != null && memAvailableKb <= memTotalKb) {
            memTotalKb - memAvailableKb
        } else null

        val swap = HostSwapInfo(
            totalKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "SwapTotal") },
            freeKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "SwapFree") },
            usedKb = null
        ).withDerivedUsed()

        return HostMemoryInfo(
            memTotalKb = memTotalKb,
            memFreeKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "MemFree") },
            memAvailableKb = memAvailableKb,
            buffersKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "Buffers") },
            cachedKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "Cached") },
            sReclaimableKb = meminfo?.let { ProcMetricParsers.meminfoValueKb(it, "SReclaimable") },
            usedLinuxKb = usedLinuxKb,
            androidTotalKb = amInfo?.totalMem?.div(1024L),
            androidAvailKb = amInfo?.availMem?.div(1024L),
            androidThresholdKb = amInfo?.threshold?.takeIf { it > 0 }?.div(1024L),
            isLowMemory = amInfo?.lowMemory,
            swap = swap,
            zram = collectZram(),
            memoryClassMb = activityManager?.memoryClass,
            largeMemoryClassMb = activityManager?.largeMemoryClass
        )
    }

    private fun HostSwapInfo.withDerivedUsed(): HostSwapInfo {
        val t = totalKb ?: return this
        val f = freeKb ?: return this
        if (f > t) return copy(usedKb = 0L)
        return copy(usedKb = t - f)
    }

    /** zRAM presence via sysfs; unreadable → present=null (unknown, not false). */
    private fun collectZram(): HostZramInfo {
        val zramCtlExists = File("/sys/class/zram-control").exists()
        val diskSizeBytes = readFileOrNull("/sys/block/zram0/disksize")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val present = when {
            diskSizeBytes != null -> true
            zramCtlExists -> true
            else -> null
        }
        return HostZramInfo(present = present, diskSizeBytes = diskSizeBytes)
    }

    // ── Storage ────────────────────────────────────────────────────────────

    override fun collectStorage(): HostStorageInfo {
        val primary = measureVolume(
            id = PRIMARY_VOLUME_ID,
            description = "Internal storage",
            path = context.filesDir.absolutePath,
            kind = HostStorageVolume.Kind.INTERNAL_PRIMARY,
            state = null
        )

        val externalFiles = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val externalAppVolume = externalFiles?.let {
            measureVolume(
                id = APP_EXTERNAL_VOLUME_ID,
                description = "App external storage",
                path = it.absolutePath,
                kind = HostStorageVolume.Kind.APP_EXTERNAL,
                state = Environment.getExternalStorageState(it)
            )
        }

        val sharedBindVolume = externalFiles?.parentFile?.let { parent ->
            val shared = File(parent, "shared")
            if (shared.exists()) {
                measureVolume(
                    id = SHARED_BIND_VOLUME_ID,
                    description = "Shared Storage bind source",
                    path = shared.absolutePath,
                    kind = HostStorageVolume.Kind.SHARED_BIND_SOURCE,
                    state = null
                )
            } else null
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val all = mutableListOf<HostStorageVolume>()
        listOfNotNull(primary, externalAppVolume, sharedBindVolume).forEach { all.add(it) }
        storageManager?.storageVolumes?.forEach { volume ->
            val dir = runCatching { volume.directory }.getOrNull() ?: return@forEach
            // Volumes already measured above would double-report the same
            // capacity under different ids; skip by path overlap.
            val alreadyCovered = all.any { it.path == dir.absolutePath || dir.absolutePath.startsWith(it.path) }
            if (alreadyCovered) return@forEach
            measureVolume(
                id = runCatching { volume.uuid }.getOrNull() ?: dir.absolutePath,
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { volume.getDescription(context) }.getOrNull()
                } else null,
                path = dir.absolutePath,
                kind = HostStorageVolume.Kind.OTHER,
                state = runCatching { volume.state }.getOrNull()
            )?.let { all.add(it) }
        }

        return HostStorageInfo(
            primaryVolume = primary,
            externalAppVolume = externalAppVolume,
            sharedStorageVolume = sharedBindVolume,
            allVolumes = all
        )
    }

    /** Null, never throwing, when StatFs refuses the path — unknown ≠ zero. */
    private fun measureVolume(
        id: String,
        description: String?,
        path: String,
        kind: HostStorageVolume.Kind,
        state: String?
    ): HostStorageVolume? {
        val file = File(path)
        if (!file.exists()) return null
        val stat = runCatching { android.os.StatFs(path) }.getOrNull() ?: return null
        val total = runCatching { stat.totalBytes }.getOrNull()?.takeIf { it > 0 }
        val free = runCatching { stat.availableBytes }.getOrNull()?.takeIf { it >= 0 }
        if (total == null && free == null) return null
        return HostStorageVolume(
            id = id,
            description = description,
            path = path,
            kind = kind,
            state = state,
            totalBytes = total,
            freeBytes = free
        )
    }

    // ── System / kernel / uptime ───────────────────────────────────────────

    override fun collectSystem(): HostDeviceInfo {
        val uname = runCatching { Os.uname() }.getOrNull()
        val uptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrNull()
        val uptimeUpOnlyMs = runCatching { SystemClock.uptimeMillis() }.getOrNull()
        val wallNow = nowWallMs()
        // The user-visible device name ("Pixel 8 Pro") is a Settings.Global
        // entry, not a Build.* field — readable without any permission on
        // mainstream devices, but OEM firmware may simply not set it (null,
        // the honest answer). Reading Settings can throw on hardened forks;
        // guarded like every other platform touch in this class.
        val deviceName = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                "device_name"
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return HostDeviceInfo(
            androidRelease = buildField { Build.VERSION.RELEASE },
            sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0),
            codename = buildField { Build.VERSION.CODENAME },
            incremental = buildField { Build.VERSION.INCREMENTAL },
            securityPatch = buildField { Build.VERSION.SECURITY_PATCH },
            manufacturer = buildField { Build.MANUFACTURER },
            brand = buildField { Build.BRAND },
            model = buildField { Build.MODEL },
            product = buildField { Build.PRODUCT },
            device = buildField { Build.DEVICE },
            board = buildField { Build.BOARD },
            hardware = buildField { Build.HARDWARE },
            bootloader = buildField { Build.BOOTLOADER },
            fingerprint = buildField { Build.FINGERPRINT },
            kernelRelease = uname?.release,
            kernelVersion = uname?.version,
            machineArch = uname?.machine,
            selinuxEnforcing = selinuxEnforcing(),
            uptimeMillis = uptimeMs,
            deepSleepMillis = if (uptimeMs != null && uptimeUpOnlyMs != null && uptimeMs >= uptimeUpOnlyMs) {
                uptimeMs - uptimeUpOnlyMs
            } else null,
            bootTimeEpochMillis = if (uptimeMs != null && wallNow > uptimeMs) wallNow - uptimeMs else null,
            deviceName = deviceName,
            buildId = buildField { Build.ID }
        )
    }

    /**
     * `android.os.SELinux.isSELinuxEnforced()` is public-API-shaped yet
     * hidden; reflection is how tools (and we) read it without privileged
     * APIs. Refusal (future SDK hardening) → null, the honest unknown.
     */
    private fun selinuxEnforcing(): Boolean? = runCatching {
        val cls = Class.forName("android.os.SELinux")
        val method = cls.getMethod("isSELinuxEnforced")
        method.invoke(null) as? Boolean
    }.getOrNull()

    private inline fun buildField(crossinline supplier: () -> String?): String? =
        runCatching { supplier() }.getOrNull()?.takeIf { it.isNotBlank() }

    // ── Battery ────────────────────────────────────────────────────────────

    override fun collectBattery(): HostBatteryInfo {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val rawPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val propertyCapacity = batteryManager?.let {
            intProperty(it, BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100)
        }

        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        return HostBatteryInfo(
            present = intent?.getIntExtra(BatteryManager.EXTRA_PRESENT, -1)
                ?.takeIf { it >= 0 }?.let { it != 0 },
            percent = rawPercent ?: propertyCapacity,
            charging = when (statusCode) {
                BatteryManager.BATTERY_STATUS_CHARGING -> HostBatteryInfo.ChargingState.CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> HostBatteryInfo.ChargingState.FULL
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> HostBatteryInfo.ChargingState.DISCHARGING
                else -> HostBatteryInfo.ChargingState.UNKNOWN
            },
            powerSource = when (pluggedCode) {
                BatteryManager.BATTERY_PLUGGED_AC -> HostBatteryInfo.PowerSource.AC
                BatteryManager.BATTERY_PLUGGED_USB -> HostBatteryInfo.PowerSource.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> HostBatteryInfo.PowerSource.WIRELESS
                0 -> HostBatteryInfo.PowerSource.NONE
                else -> HostBatteryInfo.PowerSource.UNKNOWN
            },
            health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> HostBatteryInfo.Health.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> HostBatteryInfo.Health.OVERHEAT
                BatteryManager.BATTERY_HEALTH_DEAD -> HostBatteryInfo.Health.DEAD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> HostBatteryInfo.Health.OVERVOLTAGE
                BatteryManager.BATTERY_HEALTH_COLD -> HostBatteryInfo.Health.COLD
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> HostBatteryInfo.Health.UNSPECIFIED_FAILURE
                else -> HostBatteryInfo.Health.UNKNOWN
            },
            temperatureC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.div(10f),
            voltageMillivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            currentMicroamps = batteryManager?.let {
                intProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            },
            chargeCounterMicroAh = batteryManager?.let {
                intProperty(it, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            },
            designCapacityMilliampHours = null, // no public Android API; kept explicit
            isPowerSaveMode = powerSaveFromSystem()
        )
    }

    private fun intProperty(manager: BatteryManager, property: Int): Int? =
        runCatching { manager.getIntProperty(property) }
            .getOrNull()
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }

    private fun powerSaveFromSystem(): Boolean? =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.let { pm -> runCatching { pm.isPowerSaveMode }.getOrNull() }

    // ── Thermal ────────────────────────────────────────────────────────────

    override fun collectThermal(batteryTemperatureC: Float?): HostThermalInfo {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status: HostThermalInfo.Status? = pm?.let { manager ->
            runCatching {
                when (manager.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> HostThermalInfo.Status.NONE
                    PowerManager.THERMAL_STATUS_LIGHT -> HostThermalInfo.Status.LIGHT
                    PowerManager.THERMAL_STATUS_MODERATE -> HostThermalInfo.Status.MODERATE
                    PowerManager.THERMAL_STATUS_SEVERE -> HostThermalInfo.Status.SEVERE
                    PowerManager.THERMAL_STATUS_CRITICAL -> HostThermalInfo.Status.CRITICAL
                    PowerManager.THERMAL_STATUS_EMERGENCY -> HostThermalInfo.Status.EMERGENCY
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> HostThermalInfo.Status.SHUTDOWN
                    else -> null
                }
            }.getOrNull()
        }
        // getThermalHeadroom landed in API 30; below that the method does not
        // exist on the device's framework side, which surfaces as a
        // NoSuchMethodError — caught by runCatching, but guarded explicitly
        // so the limitation is documented where the restriction lives.
        val headroom = pm?.let { manager ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@let null
            runCatching { manager.getThermalHeadroom(0) }.getOrNull()
        }
        return HostThermalInfo(
            status = status,
            severityLabel = status?.name?.lowercase(Locale.US),
            headroom = headroom,
            // Thermal-class zones are the one kernel surface that CAN name a
            // real silicon temperature; on kernels whose SELinux policy denies
            // apps the `temp` attribute these stay null (the documented gap,
            // honestly kept — never back-filled from another sensor).
            cpuTemperatureC = thermalZoneMaxTemperatureC("cpu"),
            gpuTemperatureC = thermalZoneMaxTemperatureC("gpu"),
            skinTemperatureC = null,
            batteryTemperatureC = batteryTemperatureC
        )
    }

    // ── Network ────────────────────────────────────────────────────────────

    override fun collectNetwork(): HostNetworkInfo {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return HostNetworkInfo.offline()

        val active = runCatching { connectivity.activeNetwork }.getOrNull()

        val nets = LinkedHashSet<Network>()
        if (active != null) nets.add(active)
        @Suppress("DEPRECATION") // allNetworks: still functional on modern APIs; system-filtered either way
        runCatching { connectivity.allNetworks?.toList() }.getOrNull()?.let { nets.addAll(it) }

        val accs = LinkedHashMap<String, IfaceAcc>()
        var activeCaps: NetworkCapabilities? = null
        var activeProps: LinkProperties? = null

        for (net in nets) {
            val caps = runCatching { connectivity.getNetworkCapabilities(net) }.getOrNull()
                ?: continue // vanished mid-walk / hidden now — skip silently, unknown ≠ guessed
            val props = runCatching { connectivity.getLinkProperties(net) }.getOrNull()
            val isActiveHere = net == active
            if (isActiveHere) {
                activeCaps = caps
                activeProps = props
            }
            val name = props?.interfaceName?.takeIf { it.isNotBlank() } ?: continue
            val existing = accs[name]
            if (existing == null) {
                accs[name] = IfaceAcc(
                    name = name,
                    transport = primaryTransportLabel(caps),
                    isActive = isActiveHere,
                    mtu = props.mtu.takeIf { it > 0 }
                )
            } else if (isActiveHere) {
                existing.isActive = true
            }
            val acc = accs.getValue(name)
            props.linkAddresses.forEach { address ->
                val textual = address.address?.hostAddress?.split('%')?.first() ?: return@forEach
                val prefix = address.prefixLength
                if (textual.contains('.')) acc.ipv4.add("$textual/$prefix")
                else acc.ipv6.add("$textual/$prefix")
            }
            if (acc.gatewayV4 == null) {
                acc.gatewayV4 = props.routes.firstOrNull { route ->
                    route.isDefaultRoute && route.gateway is Inet4Address
                }?.gateway?.hostAddress
            }
        }

        val countersAttached = attachTrafficCounters(accs.values.toList())
        val interfaces = accs.values
            .asSequence()
            .map { it.toModel(countersAttached) }
            .sortedWith(compareByDescending<HostNetInterface> { it.isActiveNetwork }.thenBy { it.name })
            .toList()

        val caps = activeCaps
        val props = activeProps

        val transports = buildList {
            if (caps == null) return@buildList
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
        val primary = transports.firstOrNull { it != "vpn" } ?: transports.firstOrNull()

        val ipv4 = mutableListOf<String>()
        val ipv6 = mutableListOf<String>()
        props?.linkAddresses?.forEach { address ->
            val textual = address.address?.hostAddress ?: return@forEach
            val clean = textual.split('%').first()
            if (clean.contains('.')) ipv4.add(clean) else ipv6.add(clean)
        }
        val dns = props?.dnsServers.orEmpty().mapNotNull { it?.hostAddress }

        return HostNetworkInfo(
            isActive = active != null,
            transports = transports,
            primaryTransport = primary,
            isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not(),
            downlinkKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            uplinkKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
            interfaceName = props?.interfaceName,
            ipv4Addresses = ipv4,
            ipv6Addresses = ipv6,
            dnsServers = dns,
            isPrivateDnsActive = props?.isPrivateDnsActive,
            interfaces = interfaces
        )
    }

    private fun primaryTransportLabel(caps: NetworkCapabilities): String? {
        val list = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) list.add("wifi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) list.add("cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) list.add("ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) list.add("vpn")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) list.add("bluetooth")
        return list.firstOrNull { it != "vpn" } ?: list.firstOrNull()
    }

    /** Mutable accumulation slot for one interface across the network walk. */
    private class IfaceAcc(
        val name: String,
        val transport: String?,
        var isActive: Boolean,
        val mtu: Int?
    ) {
        val ipv4 = mutableListOf<String>()
        val ipv6 = mutableListOf<String>()
        var gatewayV4: String? = null
        var rxBytes: Long? = null
        var txBytes: Long? = null
        var rxPackets: Long? = null
        var txPackets: Long? = null

        fun toModel(trafficValid: Boolean): HostNetInterface = HostNetInterface(
            name = name,
            transport = transport,
            isActiveNetwork = isActive,
            mtu = mtu,
            ipv4WithPrefix = ipv4.toList(),
            ipv6WithPrefix = ipv6.toList(),
            gatewayV4 = gatewayV4,
            rxBytes = rxBytes.takeIf { trafficValid },
            txBytes = txBytes.takeIf { trafficValid },
            rxPackets = rxPackets.takeIf { trafficValid },
            txPackets = txPackets.takeIf { trafficValid }
        )
    }

    /** True when at least one per-interface counter cell came back valid. */
    private fun attachTrafficCounters(accs: List<IfaceAcc>): Boolean {
        var anyValid = false
        for (acc in accs) {
            // Per-interface counters need API 26; below that every cell stays
            // null (unknown) rather than folding GLOBAL totals onto one row.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) break
            acc.rxBytes = counterValue { TrafficStats.getRxBytes(acc.name) }
            acc.txBytes = counterValue { TrafficStats.getTxBytes(acc.name) }
            acc.rxPackets = counterValue { TrafficStats.getRxPackets(acc.name) }
            acc.txPackets = counterValue { TrafficStats.getTxPackets(acc.name) }
            if (acc.rxBytes != null || acc.txBytes != null ||
                acc.rxPackets != null || acc.txPackets != null
            ) anyValid = true
        }
        return anyValid
    }

    /** TrafficStats signals "unsupported" with -1 or by throwing — both map to null. */
    private inline fun counterValue(reader: () -> Long): Long? =
        runCatching { reader() }.getOrNull()?.takeIf { it >= 0 }

    // ── Processes ──────────────────────────────────────────────────────────

    override fun collectProcesses(): HostProcessInfo {
        val selfPid = runCatching { android.os.Process.myPid() }.getOrDefault(0)
        val selfStat = readFileOrNull("/proc/self/stat")
        val smapsRollup = readFileOrNull("/proc/self/smaps_rollup")
        val status = readFileOrNull("/proc/self/status")

        return HostProcessInfo(
            selfPid = selfPid,
            selfProcessName = selfStat?.let { ProcMetricParsers.processName(it) }
                ?: context.packageName,
            selfRssKb = status?.let { ProcMetricParsers.vmRssKb(it) },
            selfPssKb = smapsRollup?.let { ProcMetricParsers.pssKb(it) },
            selfCpuTicks = selfStat?.let { ProcMetricParsers.cpuTicks(it) },
            otherAppsVisibility = HostProcessInfo.OTHER_APPS_RESTRICTED
        )
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    internal fun readFileOrNull(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    internal fun readFileOrNull(file: File): String? =
        runCatching { file.readText() }.getOrNull()

    private fun String.lineValue(label: String): String? =
        lineSequence().firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.firstLineValue(vararg labels: String): String? =
        labels.asSequence().map { lineValue(it) }.firstOrNull { it != null }

    protected fun nowWallMs(): Long = System.currentTimeMillis()

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val REGEX_CPU_DIR = Regex("^cpu\\d+$")

        /**
         * Vendor GPU model nodes, first readable wins. MediaTek exposes the
         * Mali model under /sys/kernel/gpu; Adreno under its kgsl class dir.
         * All absent/unreadable → null (the EGL probe is the primary source
         * anyway; these are fallbacks only).
         */
        private val GPU_MODEL_SYSFS_PATHS = listOf(
            "/sys/kernel/gpu/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/misc/mali0/device/gpu_model"
        )

        private val GPU_BUSY_SYSFS_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percent",
            "/sys/kernel/gpu/gpu_busy"
        )

        /** devfreq kernel ABI: cur_freq/max_freq are documented Hz. */
        private const val GPU_CUR_FREQ_PATH = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"

        private val GPU_MAX_CLOCK_SYSFS_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk"
        )

        /** °C sanity window for thermal-zone readings; outside → refused. */
        private const val GPU_TEMP_SANITY_MIN_C = 10f
        private const val GPU_TEMP_SANITY_MAX_C = 150f

        private const val MAX_ENUMERATED_CORES = 64
        private const val MAX_ONLINE_RANGE_SPAN = 10_000

        // Volume ids keep stable across snapshots for correlation/logging.
        const val PRIMARY_VOLUME_ID = "primary_internal"
        const val APP_EXTERNAL_VOLUME_ID = "app_external"
        const val SHARED_BIND_VOLUME_ID = "shared_bind_source"
    }
}
