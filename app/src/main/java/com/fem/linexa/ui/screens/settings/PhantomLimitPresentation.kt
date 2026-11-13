package com.fem.linexa.ui.screens.settings

import com.fem.linexa.domain.runtime.PhantomLimitPolicy
import com.fem.linexa.domain.runtime.PhantomLimitStatus

internal enum class PhantomLimitStatusText {
    MONITOR_ON,
    MONITOR_OFF,
    UNKNOWN,
    NOT_APPLICABLE
}

internal enum class PhantomLimitDescriptionText {
    ON,
    OFF,
    UNKNOWN_ANDROID_12,
    UNKNOWN_ANDROID_12L_13,
    UNKNOWN_MODERN,
    NOT_APPLICABLE
}

internal fun phantomLimitStatusText(status: PhantomLimitStatus): PhantomLimitStatusText =
    when (status) {
        PhantomLimitStatus.MONITORING_ON -> PhantomLimitStatusText.MONITOR_ON
        PhantomLimitStatus.MONITORING_OFF -> PhantomLimitStatusText.MONITOR_OFF
        PhantomLimitStatus.UNKNOWN -> PhantomLimitStatusText.UNKNOWN
        PhantomLimitStatus.NOT_APPLICABLE -> PhantomLimitStatusText.NOT_APPLICABLE
    }

internal fun phantomLimitDescriptionText(
    sdkInt: Int,
    status: PhantomLimitStatus
): PhantomLimitDescriptionText =
    when (status) {
        PhantomLimitStatus.MONITORING_ON -> PhantomLimitDescriptionText.ON
        PhantomLimitStatus.MONITORING_OFF -> PhantomLimitDescriptionText.OFF
        PhantomLimitStatus.UNKNOWN -> when {
            sdkInt < PhantomLimitPolicy.GLOBAL_SETTING_API ->
                PhantomLimitDescriptionText.UNKNOWN_ANDROID_12
            sdkInt < PhantomLimitPolicy.READABLE_API ->
                PhantomLimitDescriptionText.UNKNOWN_ANDROID_12L_13
            else -> PhantomLimitDescriptionText.UNKNOWN_MODERN
        }
        PhantomLimitStatus.NOT_APPLICABLE -> PhantomLimitDescriptionText.NOT_APPLICABLE
    }

internal fun phantomLimitShowsRuntimeCaveat(status: PhantomLimitStatus): Boolean =
    status == PhantomLimitStatus.MONITORING_ON || status == PhantomLimitStatus.MONITORING_OFF
