package com.fem.linexa.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhantomLimitPolicyTest {

    @Test
    fun `android 11 and older are not applicable`() {
        listOf(28, 29, 30).forEach { sdkInt ->
            listOf("true", "false", null).forEach { rawSetting ->
                assertEquals(
                    "sdk=$sdkInt raw=$rawSetting",
                    PhantomLimitStatus.NOT_APPLICABLE,
                    PhantomLimitPolicy.assess(sdkInt, rawSetting)
                )
            }
        }
    }

    @Test
    fun `android 12 through 13 are unknown regardless of the raw value`() {
        listOf(31, 32, 33).forEach { sdkInt ->
            listOf("true", "false", "0", "1", null, "unexpected").forEach { rawSetting ->
                assertEquals(
                    "sdk=$sdkInt raw=$rawSetting",
                    PhantomLimitStatus.UNKNOWN,
                    PhantomLimitPolicy.assess(sdkInt, rawSetting)
                )
            }
        }
    }

    @Test
    fun `android 14 and newer accept only case-insensitive canonical true`() {
        listOf("true", "TRUE", "TrUe").forEach { rawSetting ->
            assertEquals(
                "raw=$rawSetting",
                PhantomLimitStatus.MONITORING_ON,
                PhantomLimitPolicy.assess(34, rawSetting)
            )
        }
    }

    @Test
    fun `android 14 and newer accept only case-insensitive canonical false`() {
        listOf("false", "FALSE", "FaLsE").forEach { rawSetting ->
            assertEquals(
                "raw=$rawSetting",
                PhantomLimitStatus.MONITORING_OFF,
                PhantomLimitPolicy.assess(34, rawSetting)
            )
        }
    }

    @Test
    fun `noncanonical aliases and untrimmed values are unknown`() {
        listOf(
            "1",
            "0",
            "on",
            "off",
            "yes",
            "no",
            "enabled",
            "disabled",
            " true",
            "true ",
            " true ",
            "\ttrue",
            "\ntrue",
            " false ",
            "false ",
            "",
            " ",
            "\t",
            "maybe",
            "2",
            "enabledish",
            null
        ).forEach { rawSetting ->
            assertEquals(
                "raw=$rawSetting",
                PhantomLimitStatus.UNKNOWN,
                PhantomLimitPolicy.assess(34, rawSetting)
            )
        }
    }

    @Test
    fun `unsupported and unknown states never map to off`() {
        listOf(28, 31, 34).forEach { sdkInt ->
            assertNotEquals(
                "sdk=$sdkInt",
                PhantomLimitStatus.MONITORING_OFF,
                PhantomLimitPolicy.assess(sdkInt, null)
            )
        }
    }

    @Test
    fun `all four statuses are reachable`() {
        assertEquals(
            PhantomLimitStatus.NOT_APPLICABLE,
            PhantomLimitPolicy.assess(30, null)
        )
        assertEquals(
            PhantomLimitStatus.UNKNOWN,
            PhantomLimitPolicy.assess(31, null)
        )
        assertEquals(
            PhantomLimitStatus.MONITORING_ON,
            PhantomLimitPolicy.assess(34, "true")
        )
        assertEquals(
            PhantomLimitStatus.MONITORING_OFF,
            PhantomLimitPolicy.assess(34, "false")
        )
    }

    @Test
    fun `guidance is hidden when there is nothing to change`() {
        assertNull(PhantomLimitPolicy.guidanceFor(28, PhantomLimitStatus.NOT_APPLICABLE))
        assertNull(PhantomLimitPolicy.guidanceFor(30, PhantomLimitStatus.NOT_APPLICABLE))
        assertNull(PhantomLimitPolicy.guidanceFor(31, PhantomLimitStatus.MONITORING_OFF))
        assertNull(PhantomLimitPolicy.guidanceFor(32, PhantomLimitStatus.NOT_APPLICABLE))
        assertNull(PhantomLimitPolicy.guidanceFor(34, PhantomLimitStatus.MONITORING_OFF))
    }

    @Test
    fun `android 12 uses the device config mechanism`() {
        assertEquals(
            PhantomLimitGuidance.ANDROID_12_DEVICE_CONFIG,
            PhantomLimitPolicy.guidanceFor(31, PhantomLimitStatus.UNKNOWN)
        )
        assertEquals(
            PhantomLimitGuidanceText.ANDROID_12,
            PhantomLimitGuidance.ANDROID_12_DEVICE_CONFIG.text
        )
    }

    @Test
    fun `android 12l and newer use the global setting mechanism`() {
        listOf(32, 33, 34).forEach { sdkInt ->
            assertEquals(
                "sdk=$sdkInt",
                PhantomLimitGuidance.GLOBAL_SETTING_UNKNOWN,
                PhantomLimitPolicy.guidanceFor(sdkInt, PhantomLimitStatus.UNKNOWN)
            )
        }
        assertEquals(
            PhantomLimitGuidance.GLOBAL_SETTING_ON,
            PhantomLimitPolicy.guidanceFor(34, PhantomLimitStatus.MONITORING_ON)
        )
        assertEquals(
            PhantomLimitGuidanceText.MONITOR_ON,
            PhantomLimitGuidance.GLOBAL_SETTING_ON.text
        )
        assertEquals(
            PhantomLimitGuidanceText.UNKNOWN,
            PhantomLimitGuidance.GLOBAL_SETTING_UNKNOWN.text
        )
    }

    @Test
    fun `guidance commands are exact and copy only`() {
        assertEquals(
            "adb shell device_config put activity_manager max_phantom_processes 2147483647",
            PhantomLimitGuidance.ANDROID_12_DEVICE_CONFIG.command
        )
        assertEquals(
            "adb shell settings put global settings_enable_monitor_phantom_procs false",
            PhantomLimitGuidance.GLOBAL_SETTING_ON.command
        )
        assertEquals(
            PhantomLimitGuidance.GLOBAL_SETTING_ON.command,
            PhantomLimitGuidance.GLOBAL_SETTING_UNKNOWN.command
        )
    }
}
