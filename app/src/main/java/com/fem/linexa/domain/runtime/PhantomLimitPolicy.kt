package com.fem.linexa.domain.runtime

enum class PhantomLimitStatus {
    NOT_APPLICABLE,
    UNKNOWN,
    MONITORING_ON,
    MONITORING_OFF
}

enum class PhantomLimitGuidanceText {
    ANDROID_12,
    MONITOR_ON,
    UNKNOWN
}

private const val GLOBAL_SETTING_COMMAND =
    "adb shell settings put global settings_enable_monitor_phantom_procs false"

/**
 * Copy-only adb mechanisms that match AOSP for the detected API level and state.
 *
 * Android 12 (API 31): `PhantomProcessList` clamps to
 * `ActivityManagerConstants.MAX_PHANTOM_PROCESSES`, which is populated from the
 * `activity_manager` DeviceConfig namespace. Android 12L+ (API 32+) gates the
 * same behavior through `FeatureFlagUtils`, which reads the
 * `settings_enable_monitor_phantom_procs` Global setting. The Android 12
 * setting key does not exist in Android 12, so showing that command there
 * would be wrong.
 */
enum class PhantomLimitGuidance(
    val command: String,
    val text: PhantomLimitGuidanceText
) {
    ANDROID_12_DEVICE_CONFIG(
        command = "adb shell device_config put activity_manager max_phantom_processes 2147483647",
        text = PhantomLimitGuidanceText.ANDROID_12
    ),
    GLOBAL_SETTING_ON(
        command = GLOBAL_SETTING_COMMAND,
        text = PhantomLimitGuidanceText.MONITOR_ON
    ),
    GLOBAL_SETTING_UNKNOWN(
        command = GLOBAL_SETTING_COMMAND,
        text = PhantomLimitGuidanceText.UNKNOWN
    )
}

object PhantomLimitPolicy {

    const val SETTING_KEY = "settings_enable_monitor_phantom_procs"
    const val PHANTOM_MONITOR_API = 31
    const val GLOBAL_SETTING_API = 32
    const val READABLE_API = 34

    fun assess(sdkInt: Int, rawSetting: String?): PhantomLimitStatus {
        if (sdkInt < PHANTOM_MONITOR_API) return PhantomLimitStatus.NOT_APPLICABLE
        if (sdkInt < READABLE_API) return PhantomLimitStatus.UNKNOWN

        return when (parseSetting(rawSetting)) {
            true -> PhantomLimitStatus.MONITORING_ON
            false -> PhantomLimitStatus.MONITORING_OFF
            null -> PhantomLimitStatus.UNKNOWN
        }
    }

    /**
     * Returns the copy-only command for a state that warrants guidance, or null
     * when this SDK/status combination must not show one.
     *
     * API 31 is Android 12, where the Global setting does not exist. API 32+
     * (12L and newer) use the Global setting. The status selects the copy
     * variant so the UI never has to guess from an "else" branch.
     */
    fun guidanceFor(sdkInt: Int, status: PhantomLimitStatus): PhantomLimitGuidance? {
        if (sdkInt < PHANTOM_MONITOR_API) return null
        if (status == PhantomLimitStatus.NOT_APPLICABLE ||
            status == PhantomLimitStatus.MONITORING_OFF
        ) {
            return null
        }
        if (sdkInt < GLOBAL_SETTING_API) {
            return PhantomLimitGuidance.ANDROID_12_DEVICE_CONFIG
        }
        return if (status == PhantomLimitStatus.MONITORING_ON) {
            PhantomLimitGuidance.GLOBAL_SETTING_ON
        } else {
            PhantomLimitGuidance.GLOBAL_SETTING_UNKNOWN
        }
    }

    /**
     * Mirrors Java's `Boolean.parseBoolean` exactly: only case-insensitive
     * "true"/"false" are canonical. There is no trimming, and aliases such as
     * "1", "on", or "enabled" are not booleans here. Invalid values are
     * UNKNOWN rather than OFF, because an unrecognized value is not evidence
     * that the platform disabled the monitor.
     */
    private fun parseSetting(rawSetting: String?): Boolean? = when {
        rawSetting == null -> null
        rawSetting.equals("true", ignoreCase = true) -> true
        rawSetting.equals("false", ignoreCase = true) -> false
        else -> null
    }
}
