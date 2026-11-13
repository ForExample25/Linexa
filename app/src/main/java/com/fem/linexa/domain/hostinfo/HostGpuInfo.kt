package com.fem.linexa.domain.hostinfo

/**
 * Android host GPU information, as observed by the app-uid process.
 *
 * Completes the hardware-info plugin family (CPU/Memory/...): until this
 * model existed the GPU was representable only as a hard-coded null inside
 * [HostThermalInfo], and no surface — app UI or RootFS guest — could learn
 * anything about the device's graphics silicon. Every field keeps the
 * subsystem's established contract: `null` means "not knowable from this
 * context" (the OS withheld it), never "zero" and never a synthesized
 * placeholder.
 *
 * ## Where each field comes from
 *
 * - **[model]** is the user-space renderer identity. Primary source is an
 *   EGL client probe (`glGetString(GL_RENDERER)` on a 1×1 pbuffer context),
 *   which every GLES device answers with the actual driver string
 *   ("Mali-G720", "Adreno (TM) 750", …). On devices routing GL through
 *   ANGLE the string names ANGLE — still the honest driver identity. The
 *   probe result is cached for the process lifetime: a GPU cannot change
 *   while the app runs. Sysfs fallbacks (`/sys/kernel/gpu/gpu_model`,
 *   `/sys/class/kgsl/kgsl-3d0/gpu_model`) are consulted only when the EGL
 *   probe failed, because they exist on a subset of OEM kernels only.
 * - **[vendorFamily]** is the coarse silicon family derived from the model
 *   string (mali/adreno/powervr/xclipse/videocore/unknown) — what a guest
 *   uses to pick e.g. Mesa driver expectations. Derived, never asked for.
 * - **[temperatureC]** comes from `/sys/class/thermal/thermal_zoneN/` zones
 *   whose `type` names the GPU (`gpu0-thermal`, `gpu_thermal`, …). Modern
 *   SELinux policy denies apps the `temp` attribute on most kernels — the
 *   collector reads what it can and leaves [temperatureC] null where denied
 *   (exactly the honesty contract [HostThermalInfo.gpuTemperatureC]
 *   documented before this model existed).
 * - **[busyPercent]** / **[clockKhz]** / **[maxClockKhz]** are best-effort
 *   sysfs reads (the `/sys/kernel/gpu/` directory on MediaTek-Mali, the
 *   `/sys/class/kgsl/kgsl-3d0/` directory on Adreno). Absent nodes leave nulls.
 *
 * @property model driver/renderer identity string; null when neither the EGL
 *   probe nor any sysfs fallback answered.
 * @property vendorFamily coarse family token derived from [model]; null when
 *   the model is unknown or matches no known family.
 * @property temperatureC GPU zone temperature in °C, when the kernel serves
 *   it to this uid.
 * @property busyPercent GPU utilization 0..100, when the vendor node exists.
 * @property clockKhz current GPU core clock in kHz, when the vendor node
 *   exists.
 * @property maxClockKhz maximum GPU core clock in kHz, when the vendor node
 *   exists.
 * @property sourceLabel where [model] came from ("egl", "sysfs", …) — for
 *   diagnostics and the device screen's provenance caption, mirroring how
 *   other collectors name their sources. Null when the model is unknown.
 */
data class HostGpuInfo(
    val model: String?,
    val vendorFamily: String?,
    val temperatureC: Float?,
    val busyPercent: Int?,
    val clockKhz: Long?,
    val maxClockKhz: Long?,
    val sourceLabel: String?
) {
    /** True when at least one GPU measurement came back valid this refresh. */
    val hasAnyMeasurement: Boolean
        get() = model != null || temperatureC != null || busyPercent != null || clockKhz != null

    companion object {
        /** Explicit all-unknown value (never fabricated facts). */
        fun unknown(): HostGpuInfo = HostGpuInfo(
            model = null, vendorFamily = null, temperatureC = null,
            busyPercent = null, clockKhz = null, maxClockKhz = null, sourceLabel = null
        )

        /**
         * Coarse vendor family for a renderer/model string. Lowercased
         * substring matching is deliberate: driver strings vary
         * ("Mali-G720 MC7", "Adreno (TM) 750") but the family token is
         * stable. Returns null for unknown/absent rather than guessing.
         */
        fun vendorFamilyOf(model: String?): String? {
            val m = model?.lowercase() ?: return null
            return when {
                "mali" in m -> "mali"
                "adreno" in m -> "adreno"
                "powervr" in m -> "powervr"
                "xclipse" in m -> "xclipse"
                "videocore" in m -> "videocore"
                else -> null
            }
        }
    }
}
