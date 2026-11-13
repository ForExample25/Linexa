package com.fem.linexa.domain.hostinfo

/**
 * Android device identity + kernel + uptime — the host's "who am I".
 *
 * Identity strings come from `Build.*` (authoritative, unrestricted).
 * Kernel identity comes from `uname(2)` via `android.system.Os.uname()`,
 * deliberately NOT from `/proc/version`: on the target devices that file is
 * SELinux-denied to untrusted_app, while the syscall is not. This is also
 * how the provider can report a real kernel release even though guests see
 * PRoot's spoofed `6.1.0-linexa` release inside their environment — see the
 * Runtime Parity audit for why that spoof is deliberate and stays.
 *
 * @property androidRelease Build.VERSION.RELEASE.
 * @property sdkInt Build.VERSION.SDK_INT.
 * @property codename Build.VERSION.CODENAME (REL on production builds).
 * @property incremental build increment.
 * @property securityPatch security patch level string, when present.
 * @property manufacturer / brand / model / product / device / board /
 *   hardware / bootloader — verbatim Build fields; unknown ones null rather
 *   than empty-string, so "field withheld" renders as a dash.
 * @property fingerprint full OTA fingerprint. Sensitive-adjacent but public,
 *   standard and non-user-identifying enough to be included; consumers that
 *   relay telemetry should still treat it as device-grain data (see docs).
 * @property kernelRelease uname.release (e.g. `6.6.89-android15-8-…`).
 * @property kernelVersion uname.version (build string/timestamp/compiler).
 * @property machineArch uname.machine (aarch64 etc.) — mirrors CPU.architecture.
 * @property selinuxEnforcing from `android.os.SELinux.isSELinuxEnforced()`
 *   via reflection against the hidden-but-stable API; null when reflection is
 *   blocked by future SDKs. Never guessed from heuristics.
 * @property uptimeMillis SystemClock.elapsedRealtime since boot. THE
 *   authoritative host uptime; guests derive `/proc/uptime` overlays from it.
 * @property deepSleepMillis elapsedRealtime − uptimeMillis — cumulative deep
 *   sleep; null on clock skew (can be negative mid-boot, reported absent).
 * @property bootTimeEpochMillis wall-clock estimate: currentTimeMillis −
 *   elapsedRealtime. Drifts with NTP corrections — an ESTIMATE, never used to
 *   back-compute anything load-bearing without re-measuring.
 * @property deviceName the user-facing device name (Settings.Global
 *   "device_name", e.g. "Pixel 8 Pro") when the OS shares it. Distinct from
 *   [model] (marketing silicon string) and [device] (codename); OEM firmware
 *   may withhold it entirely, in which case it is null like every other
 *   field the platform refuses to hand out.
 * @property buildId Build.ID — the build identifier Android's own About-page
 *   pairs with the fingerprint; additive-nullable (defaults null) so every
 *   pre-existing named-argument construction site keeps compiling.
 */
data class HostDeviceInfo(
    val androidRelease: String?,
    val sdkInt: Int,
    val codename: String?,
    val incremental: String?,
    val securityPatch: String?,
    val manufacturer: String?,
    val brand: String?,
    val model: String?,
    val product: String?,
    val device: String?,
    val board: String?,
    val hardware: String?,
    val bootloader: String?,
    val fingerprint: String?,
    val kernelRelease: String?,
    val kernelVersion: String?,
    val machineArch: String?,
    val selinuxEnforcing: Boolean?,
    val uptimeMillis: Long?,
    val deepSleepMillis: Long?,
    val bootTimeEpochMillis: Long?,
    val deviceName: String? = null,
    val buildId: String? = null
)
