package com.fem.linexa.data.hostinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for the `EXTRA_PRESENT` class-cast defect.
 *
 * The platform delivers the battery-present extra as a Boolean; the previous
 * `getIntExtra` read logged a `ClassCastException` on every host-info tick and
 * lost the value. Both encodings must parse, and unknown shapes must stay
 * unknown instead of being coerced.
 */
class BatteryPresentExtraTest {

    @Test
    fun booleanExtraIsReadDirectly() {
        assertEquals(true, batteryPresentExtra(true))
        assertEquals(false, batteryPresentExtra(false))
    }

    @Test
    fun integerExtraIsAcceptedForDocumentedEncoding() {
        assertEquals(true, batteryPresentExtra(1))
        assertEquals(false, batteryPresentExtra(0))
        assertEquals(true, batteryPresentExtra(-1))
    }

    @Test
    fun absentOrUnknownExtraIsUnknown() {
        assertNull(batteryPresentExtra(null))
        assertNull(batteryPresentExtra("true"))
        assertNull(batteryPresentExtra(1L))
        assertNull(batteryPresentExtra(Any()))
    }
}
