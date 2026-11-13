package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallMeasurementsTest {

    @Test
    fun `all packages present is the only installed verdict`() {
        assertEquals(
            AppInstallMeasurements.Presence.PRESENT,
            AppInstallMeasurements.presenceOf(setOf("git", "curl", "bash"), listOf("git", "curl"))
        )
        assertEquals(
            AppInstallMeasurements.Presence.ABSENT,
            AppInstallMeasurements.presenceOf(setOf("git", "bash"), listOf("git", "curl"))
        )
    }

    @Test
    fun `missing or empty package list is unknown`() {
        assertEquals(
            AppInstallMeasurements.Presence.UNKNOWN,
            AppInstallMeasurements.presenceOf(null, listOf("git"))
        )
        assertEquals(
            AppInstallMeasurements.Presence.UNKNOWN,
            AppInstallMeasurements.presenceOf(setOf("git"), emptyList())
        )
    }

    @Test
    fun `unsafe package tokens are ignored`() {
        assertEquals(
            AppInstallMeasurements.Presence.UNKNOWN,
            AppInstallMeasurements.presenceOf(setOf("git"), listOf("git; rm -rf /"))
        )
    }

    @Test
    fun `executable probe requires every name`() {
        val command = AppInstallMeasurements.executableProbeCommand(listOf("python3", "pip3"))
        assertEquals(
            "command -v python3 >/dev/null 2>&1 && command -v pip3 >/dev/null 2>&1",
            command
        )
    }

    @Test
    fun `unsafe executable tokens are rejected`() {
        assertNull(
            AppInstallMeasurements.executableProbeCommand(
                listOf("python3; rm -rf /", "\$(reboot)", "-rf")
            )
        )
        assertFalse(AppInstallMeasurements.isSafeExecutable("git && whoami"))
        assertTrue(AppInstallMeasurements.isSafeExecutable("/usr/bin/python3"))
    }
}
