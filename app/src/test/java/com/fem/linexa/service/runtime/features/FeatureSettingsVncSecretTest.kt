package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P11: the unreadable marker and the redaction on [FeatureSettings]. */
class FeatureSettingsVncSecretTest {

    private val markerKey = FeatureSettings.KEY_DESKTOP_VNC_PASSWORD_UNREADABLE

    @Test
    fun theMarkerDefaultsToFalseAndAlwaysRoundTrips() {
        val plain = FeatureSettings(desktopVncPassword = "pw12345678")
        assertEquals("false", plain.toAttributes()[markerKey])
        assertEquals(plain, FeatureSettings.from(plain.toAttributes()))

        val unreadable = FeatureSettings(desktopVncPasswordUnreadable = true)
        assertEquals("true", unreadable.toAttributes()[markerKey])
        assertEquals(unreadable, FeatureSettings.from(unreadable.toAttributes()))
    }

    @Test
    fun aMarkerNextToARealPasswordIsIgnored() {
        val read = FeatureSettings.from(
            mapOf(FeatureSettings.KEY_DESKTOP_VNC_PASSWORD to "pw12345678", markerKey to "true")
        )
        assertFalse(read.desktopVncPasswordUnreadable)
        // and a stale flag is never written next to a real password
        assertEquals(
            "false",
            FeatureSettings(desktopVncPassword = "pw12345678", desktopVncPasswordUnreadable = true)
                .toAttributes()[markerKey]
        )
    }

    @Test
    fun anAbsentMarkerMeansReadable() {
        assertFalse(FeatureSettings.from(emptyMap()).desktopVncPasswordUnreadable)
    }

    @Test
    fun noAuthConsentDefaultsOffAndRoundTrips() {
        // Finding 007: a no-password start must require this explicit flag.
        assertFalse(FeatureSettings().desktopVncAllowUnauthenticated)
        assertFalse(FeatureSettings.from(emptyMap()).desktopVncAllowUnauthenticated)
        assertEquals(
            "false",
            FeatureSettings().toAttributes()[FeatureSettings.KEY_DESKTOP_VNC_ALLOW_UNAUTHENTICATED]
        )

        val consented = FeatureSettings(desktopVncAllowUnauthenticated = true)
        assertEquals(
            "true",
            consented.toAttributes()[FeatureSettings.KEY_DESKTOP_VNC_ALLOW_UNAUTHENTICATED]
        )
        assertTrue(FeatureSettings.from(consented.toAttributes()).desktopVncAllowUnauthenticated)
    }

    @Test
    fun toStringNeverContainsThePassword() {
        val text = FeatureSettings(desktopVncPassword = "topSecret99").toString()
        assertFalse(text.contains("topSecret99"))
        assertTrue(text.contains("<redacted>"))
        assertTrue(FeatureSettings().toString().contains("<empty>"))
    }
}
