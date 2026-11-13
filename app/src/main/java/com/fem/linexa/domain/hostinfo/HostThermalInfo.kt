package com.fem.linexa.domain.hostinfo

/**
 * Host thermal state through the public PowerManager thermal service.
 *
 * Android exposes thermal SEVERITY to apps (API 29+), not zone temperatures:
 * hardware sensor values and per-zone details live behind the HAL and are
 * invisible to untrusted_app. Fields the platform withholds are explicit
 * nulls — they must not be back-filled from battery temperature, which
 * answers a different question.
 *
 * @property status PowerManager.getCurrentThermalStatus() decoded; null when
 *   API < 29 or the call fails.
 * @property severityLabel stable lowercase name (none/light/...) for logs
 *   and bridge strings so numeric constants never leak into user text.
 * @property headroom Float margin to criticality (API 30+
 *   getThermalHeadroom(0)); null when unsupported. Absent ≠ 0.
 * @property cpuTemperatureC NOT collectible without privileged access — kept
 *   null-by-contract; documents the gap instead of hinting it is coming.
 * @property gpuTemperatureC likewise unavailable publicly.
 * @property skinTemperatureC likewise.
 * @property batteryTemperatureC mirrored from the battery sample for the one
 *   real temperature Android DOES expose; present only when that category
 *   refreshed alongside this one.
 */
data class HostThermalInfo(
    val status: Status?,
    val severityLabel: String?,
    val headroom: Float?,
    val cpuTemperatureC: Float?,
    val gpuTemperatureC: Float?,
    val skinTemperatureC: Float?,
    val batteryTemperatureC: Float?
) {
    /** Mirrors PowerManager.THERMAL_STATUS_* constants. */
    enum class Status { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }
}
