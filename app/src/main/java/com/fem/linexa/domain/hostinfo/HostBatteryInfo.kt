package com.fem.linexa.domain.hostinfo

/**
 * Host battery/power state, from the standard sticky battery intent and
 * BatteryManager properties — no privileged APIs, no fabricated defaults.
 *
 * All nullable per [HostInfoAvailability] field semantics: OEM intent extras
 * vary, hardware current sensors often read 0 or fail, and design capacity is
 * effectively never public — absence carries that reality.
 *
 * @property percent level 0..100 derived from level/scale (the classic pair),
 *   cross-checked with BATTERY_PROPERTY_CAPACITY when it answers.
 * @property charging coarse state machine the UI/bridge can branch on.
 * @property powerSource where charge (if any) comes from.
 * @property health battery health constant decoded to a name.
 * @property temperatureC extra temperature / 10 (the intent's unit).
 * @property voltageMillivolts EXTRA_VOLTAGE.
 * @property currentMicroamps signed BATTERY_PROPERTY_CURRENT_NOW (µA);
 *   positive usually = discharge on most devices. null when property fails.
 * @property chargeCounterMicroAh remaining charge estimate from
 *   BATTERY_PROPERTY_CHARGE_COUNTER; null when unsupported.
 * @property designCapacityMilliampHours not exposed by public Android APIs —
 *   always collected as unavailable; kept as an explicit field so consumers
 *   see "known-unknown" rather than a mystery gap.
 * @property isPowerSaveMode PowerManager.isPowerSaveMode at sample time.
 */
data class HostBatteryInfo(
    val present: Boolean?,
    val percent: Int?,
    val charging: ChargingState,
    val powerSource: PowerSource,
    val health: Health,
    val temperatureC: Float?,
    val voltageMillivolts: Int?,
    val currentMicroamps: Int?,
    val chargeCounterMicroAh: Int?,
    val designCapacityMilliampHours: Int?,
    val isPowerSaveMode: Boolean?
) {
    enum class ChargingState { CHARGING, FULL, DISCHARGING, UNKNOWN }

    enum class PowerSource { AC, USB, WIRELESS, DOCK, NONE, UNKNOWN }

    enum class Health { GOOD, OVERHEAT, DEAD, OVERVOLTAGE, COLD, UNSPECIFIED_FAILURE, UNKNOWN }
}
