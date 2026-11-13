package com.fem.linexa.domain.hostinfo

/**
 * Android host memory information, in kB (the kernel's own unit) so
 * meminfo-sourced values never round-trip through bytes and back.
 *
 * This model exists precisely because two different "available memory"
 * answers are both real and neither may be presented as the other:
 *
 * - **Linux semantics** (`/proc/meminfo`): `MemTotal`, `MemFree`,
 *   `MemAvailable` — the physical-RAM view the kernel itself keeps, including
 *   page cache. `/proc/meminfo` is one of the few proc files still readable by
 *   app uid on the target devices (verified in the runtime parity audit), so
 *   these values are genuine host measurements.
 * - **Android semantics** (`ActivityManager.MemoryInfo`): `totalMem`,
 *   `availMem` — what the OS will let processes actually use before killing
 *   them, factoring low-memory watchdog state and reserved pages differently.
 *   `availMem < MemAvailable` on most devices; both are correct for their own
 *   question.
 *
 * A guest running `free` consumes Linux semantics; the Terminal Workspace's
 * pressure indicators should consume Android semantics. Mixing them produced
 * exactly the "plausible wrong number" class of bug this subsystem exists to
 * prevent.
 *
 * Swap/zRAM lives here as first-class fields (see [HostSwapInfo]) but is ALSO
 * exposed as its own [HostSystemInfoSnapshot.swap] entry so a guest can
 * request swap alone without refreshing anything else.
 *
 * @property memTotalKb kernel MemTotal — physical RAM visible to Linux.
 * @property memFreeKb completely-unused physical RAM (kernel MemFree).
 * @property memAvailableKb kernel MemAvailable: an estimate of memory that can
 *   be reclaimed without swapping — the Linux-semantic "available".
 * @property buffersKb / cachedKb / sReclaimableKb raw meminfo lines.
 * @property usedLinuxKb derived `memTotal - memAvailable` (Linux convention;
 *   NOT `total - free`, which would count page cache as "used").
 * @property androidTotalKb ActivityManager.MemoryInfo.totalMem.
 * @property androidAvailKb ActivityManager.MemoryInfo.availMem (see above).
 * @property androidThresholdKb MemoryInfo.threshold — below this, the system
 *   starts killing; null when AM declines.
 * @property isLowMemory MemoryInfo.lowMemory at sample time; null when unknown.
 * @property swap see [HostSwapInfo].
 * @property zram present/disksize where sysfs exposes it.
 * @property memoryClassMb this app's standard heap budget (`memoryClass`),
 *   included because §"memory class" asks for it; it describes the APP heap,
 *   not device RAM, and must not be rendered beside totalMem without context.
 * @property largeMemoryClassMb heap budget under largeHeap manifests.
 */
data class HostMemoryInfo(
    val memTotalKb: Long?,
    val memFreeKb: Long?,
    val memAvailableKb: Long?,
    val buffersKb: Long?,
    val cachedKb: Long?,
    val sReclaimableKb: Long?,
    val usedLinuxKb: Long?,
    val androidTotalKb: Long?,
    val androidAvailKb: Long?,
    val androidThresholdKb: Long?,
    val isLowMemory: Boolean?,
    val swap: HostSwapInfo?,
    val zram: HostZramInfo?,
    val memoryClassMb: Int?,
    val largeMemoryClassMb: Int?
)

/**
 * Host swap view, all in kB.
 *
 * On Android, swap is almost always zRAM (compressed RAM-backed), never a
 * block device, and its size is a kernel setting no public API reports — the
 * values come from the same readable `/proc/meminfo` the guest tools parse.
 *
 * @property totalKb SwapTotal.
 * @property freeKb SwapFree.
 * @property usedKb derived `total - free`; null when either side unknown.
 */
data class HostSwapInfo(
    val totalKb: Long?,
    val freeKb: Long?,
    val usedKb: Long?
)

/**
 * zRAM backing of swap, when the device exposes it via sysfs.
 *
 * `disksizeBytes` is the compressed-disk capacity zRAM presents, not RAM
 * saved; on many OEM kernels `/sys/block/zram0/disksize` is unreadable to
 * apps, in which case both fields are null and presence is null too — not
 * false.
 */
data class HostZramInfo(
    val present: Boolean?,
    val diskSizeBytes: Long?
)
