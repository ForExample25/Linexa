package com.fem.linexa.ui.screens.settings

import com.fem.linexa.domain.runtime.PhantomLimitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomLimitPresentationTest {

    @Test
    fun `all four statuses map to their own label`() {
        assertEquals(
            PhantomLimitStatusText.MONITOR_ON,
            phantomLimitStatusText(PhantomLimitStatus.MONITORING_ON)
        )
        assertEquals(
            PhantomLimitStatusText.MONITOR_OFF,
            phantomLimitStatusText(PhantomLimitStatus.MONITORING_OFF)
        )
        assertEquals(
            PhantomLimitStatusText.UNKNOWN,
            phantomLimitStatusText(PhantomLimitStatus.UNKNOWN)
        )
        assertEquals(
            PhantomLimitStatusText.NOT_APPLICABLE,
            phantomLimitStatusText(PhantomLimitStatus.NOT_APPLICABLE)
        )
    }

    @Test
    fun `known configuration states use the short descriptions`() {
        assertEquals(
            PhantomLimitDescriptionText.ON,
            phantomLimitDescriptionText(34, PhantomLimitStatus.MONITORING_ON)
        )
        assertEquals(
            PhantomLimitDescriptionText.OFF,
            phantomLimitDescriptionText(34, PhantomLimitStatus.MONITORING_OFF)
        )
        assertEquals(
            PhantomLimitDescriptionText.NOT_APPLICABLE,
            phantomLimitDescriptionText(30, PhantomLimitStatus.NOT_APPLICABLE)
        )
    }

    @Test
    fun `unknown descriptions explain the actual version specific reason`() {
        assertEquals(
            PhantomLimitDescriptionText.UNKNOWN_ANDROID_12,
            phantomLimitDescriptionText(31, PhantomLimitStatus.UNKNOWN)
        )
        assertEquals(
            PhantomLimitDescriptionText.UNKNOWN_ANDROID_12L_13,
            phantomLimitDescriptionText(32, PhantomLimitStatus.UNKNOWN)
        )
        assertEquals(
            PhantomLimitDescriptionText.UNKNOWN_ANDROID_12L_13,
            phantomLimitDescriptionText(33, PhantomLimitStatus.UNKNOWN)
        )
        assertEquals(
            PhantomLimitDescriptionText.UNKNOWN_MODERN,
            phantomLimitDescriptionText(34, PhantomLimitStatus.UNKNOWN)
        )
    }

    @Test
    fun `the runtime caveat is shown only for known configuration states`() {
        assertTrue(phantomLimitShowsRuntimeCaveat(PhantomLimitStatus.MONITORING_ON))
        assertTrue(phantomLimitShowsRuntimeCaveat(PhantomLimitStatus.MONITORING_OFF))
        assertFalse(phantomLimitShowsRuntimeCaveat(PhantomLimitStatus.UNKNOWN))
        assertFalse(phantomLimitShowsRuntimeCaveat(PhantomLimitStatus.NOT_APPLICABLE))
    }
}
