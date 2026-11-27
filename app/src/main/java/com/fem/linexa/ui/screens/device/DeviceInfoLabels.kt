package com.fem.linexa.ui.screens.device

import android.content.Context
import androidx.annotation.StringRes
import com.fem.linexa.R
import com.fem.linexa.core.i18n.AppLocale
import com.fem.linexa.domain.settings.AppLanguage
import java.util.Locale

/**
 * Localized prose for [DeviceInfoMapper].
 *
 * The mapper stays a pure JVM function (the screen's tests require no Android
 * runtime), so every word it puts around a measurement is resolved through this
 * seam. Measurements and unit symbols — GB, MHz, GHz, °C, mV, µA, mAh, Kbps —
 * are universal and remain in the mapper; only natural-language labels and the
 * uptime words come from resources.
 *
 * [English] is the mapper's default and keeps unit tests Android-free. The
 * runtime binding resolves each label at call time through the persisted app
 * language, so a language switch updates the next poll without recreating the
 * screen or capturing a stale locale in a singleton.
 */
interface DeviceInfoLabels {

    fun text(@StringRes id: Int, vararg args: Any): String

    companion object {
        val English: DeviceInfoLabels = EnglishDeviceInfoLabels

        fun from(context: Context): DeviceInfoLabels =
            ResourceDeviceInfoLabels(context.applicationContext)
    }
}

private class ResourceDeviceInfoLabels(private val appContext: Context) : DeviceInfoLabels {

    /**
     * Device info polls every two seconds and each poll asks for ~30 labels.
     * Rebuilding a configuration context per label would churn resources; the
     * cached context is reused until the persisted language changes.
     */
    private var cachedLanguage: AppLanguage? = null
    private var cachedContext: Context? = null

    override fun text(id: Int, vararg args: Any): String {
        val preference = AppLocale.readPreference(appContext)
        val localized = cachedContext?.takeIf { cachedLanguage == preference }
            ?: AppLocale.localizedContext(appContext, preference).also {
                cachedLanguage = preference
                cachedContext = it
            }
        return localized.getString(id, *args)
    }
}

/**
 * English templates mirroring `values/strings.xml`. The mapper's unit tests
 * assert these exact labels, and [DeviceInfoLabels.English] must keep them
 * byte-identical; `LocalizationSourceTest` pins the resource side.
 */
private object EnglishDeviceInfoLabels : DeviceInfoLabels {

    override fun text(id: Int, vararg args: Any): String {
        val template = when (id) {
            R.string.device_summary_cores -> "%1\$d cores"
            R.string.device_storage_free -> "%1\$s free"
            R.string.device_value_present -> "present"
            R.string.device_core_max -> "max %1\$s"
            R.string.device_memory_buffers -> "buffers %1\$s"
            R.string.device_memory_cached -> "cached %1\$s"
            R.string.device_memory_reclaimable -> "reclaimable %1\$s"
            R.string.device_selinux_enforcing -> "enforcing"
            R.string.device_selinux_permissive -> "permissive"
            R.string.device_value_on -> "on"
            R.string.device_value_off -> "off"
            R.string.device_network_internet -> "internet"
            R.string.device_network_connected_only -> "connected only"
            R.string.device_uptime_seconds -> "%1\$ds"
            R.string.device_uptime_minutes_seconds -> "%1\$dm %2\$ds"
            R.string.device_uptime_hours_minutes -> "%1\$dh %2\$dm"
            R.string.device_uptime_days_hours_minutes -> "%1\$dd %2\$dh %3\$dm"
            R.string.device_power_source_ac -> "AC"
            R.string.device_power_source_usb -> "USB"
            R.string.device_power_source_wireless -> "wireless"
            R.string.device_power_source_dock -> "dock"
            R.string.device_power_source_none -> "none"
            R.string.device_power_source_unknown -> "unknown"
            R.string.device_health_good -> "good"
            R.string.device_health_overheat -> "overheat"
            R.string.device_health_dead -> "dead"
            R.string.device_health_overvoltage -> "overvoltage"
            R.string.device_health_cold -> "cold"
            R.string.device_health_unspecified_failure -> "unspecified failure"
            R.string.device_health_unknown -> "unknown"
            R.string.device_thermal_none -> "none"
            R.string.device_thermal_light -> "light"
            R.string.device_thermal_moderate -> "moderate"
            R.string.device_thermal_severe -> "severe"
            R.string.device_thermal_critical -> "critical"
            R.string.device_thermal_emergency -> "emergency"
            R.string.device_thermal_shutdown -> "shutdown"
            else -> error("No English DeviceInfo label for resource $id")
        }
        return String.format(Locale.US, template, *args)
    }
}
