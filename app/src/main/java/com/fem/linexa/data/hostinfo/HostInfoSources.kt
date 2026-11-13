package com.fem.linexa.data.hostinfo

import com.fem.linexa.domain.hostinfo.HostBatteryInfo
import com.fem.linexa.domain.hostinfo.HostCpuInfo
import com.fem.linexa.domain.hostinfo.HostDeviceInfo
import com.fem.linexa.domain.hostinfo.HostGpuInfo
import com.fem.linexa.domain.hostinfo.HostMemoryInfo
import com.fem.linexa.domain.hostinfo.HostNetworkInfo
import com.fem.linexa.domain.hostinfo.HostProcessInfo
import com.fem.linexa.domain.hostinfo.HostStorageInfo
import com.fem.linexa.domain.hostinfo.HostThermalInfo

/**
 * Public seam for [AndroidHostSystemInfoProvider]'s collectors.
 *
 * One method per [com.fem.linexa.domain.hostinfo.HostInfoCategory]; every
 * implementation must honor the four disciplines documented on
 * [AndroidHostInfoSources] (no fabrication, sandbox-aware sourcing, failure
 * isolation via guarded calls INSIDE each method, caller-owned threading).
 *
 * Public (rather than internal) so the provider's own signature can stay
 * public without leaking-platform boundary surprises; production wiring keeps
 * exactly one [AndroidHostInfoSources], JVM tests substitute fakes here.
 */
interface HostInfoSources {
    fun collectSystem(): HostDeviceInfo
    fun collectCpu(): HostCpuInfo

    /**
     * Graphics silicon identity + live vendor telemetry. The renderer probe
     * is cached inside the implementation (a GPU cannot change mid-process);
     * temperature/busy/clock re-read their sysfs nodes per call.
     */
    fun collectGpu(): HostGpuInfo
    fun collectMemory(): HostMemoryInfo
    fun collectStorage(): HostStorageInfo
    fun collectBattery(): HostBatteryInfo

    /**
     * Receives the SAME-cycle battery temperature so thermal mirrors one
     * instant rather than two; implementations may ignore it entirely.
     */
    fun collectThermal(batteryTemperatureC: Float?): HostThermalInfo

    fun collectNetwork(): HostNetworkInfo
    fun collectProcesses(): HostProcessInfo
}
