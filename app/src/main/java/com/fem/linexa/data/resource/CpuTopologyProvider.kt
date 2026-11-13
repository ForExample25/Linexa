package com.fem.linexa.data.resource

import android.system.Os
import android.system.OsConstants
import com.fem.linexa.domain.resource.CpuTopology
import com.fem.linexa.domain.resource.ProcMetricParsers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the three CPU counts that must not be conflated:
 *
 * - configured: `_SC_NPROCESSORS_CONF` (can include offline cores);
 * - online: `/sys/devices/system/cpu/online` (the device-capacity denominator),
 *   falling back to the configured range when sysfs is unavailable;
 * - allowed: `Cpus_allowed_list` from `/proc/self/status` (what this app may
 *   actually schedule on).
 *
 * The capacity denominator uses **online** cores. Allowed cores are exposed
 * for qualification; they are not silently used as the denominator because the
 * app's own CPU percentage is a share of the whole device, not of its cpuset.
 */
@Singleton
class CpuTopologyProvider @Inject constructor() {

    val clockTicksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: FALLBACK_CLK_TCK
    }

    fun topology(): CpuTopology {
        val configured = runCatching {
            Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt()
        }.getOrNull()?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val online = readText("/sys/devices/system/cpu/online")
            ?.let(ProcMetricParsers::cpuRangeList)
            ?.takeIf { it.isNotEmpty() }
            ?: (0 until configured).toList()

        val allowed = readText("/proc/self/status")
            ?.let(ProcMetricParsers::cpuAffinityAllowedList)

        return CpuTopology(
            configuredCores = configured,
            onlineCores = online.distinct().sorted(),
            allowedCores = allowed
        )
    }

    private fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    private companion object {
        const val FALLBACK_CLK_TCK = 100L
    }
}
