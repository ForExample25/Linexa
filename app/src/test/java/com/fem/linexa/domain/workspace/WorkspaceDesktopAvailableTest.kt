package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDesktopAvailableTest {

    @Test
    fun `detected desktop with no explicit choice is available without flipping a toggle`() {
        assertTrue(WorkspaceCapabilities.desktopAvailable(emptyMap(), emptyMap(), capable = true))
    }

    @Test
    fun `no capability is never available even when the user toggled it on`() {
        assertFalse(WorkspaceCapabilities.desktopAvailable(mapOf("desktop" to true), emptyMap(), capable = false))
        assertFalse(WorkspaceCapabilities.desktopAvailable(emptyMap(), emptyMap(), capable = false))
    }

    @Test
    fun `an explicit user off wins over capability`() {
        assertFalse(
            WorkspaceCapabilities.desktopAvailable(
                mapOf("desktop" to false),
                mapOf(WorkspaceCapabilities.ATTR_DESKTOP_INTENT to "false"),
                capable = true
            )
        )
    }

    @Test
    fun `an imported default off toggle is not a choice and auto-detection still wins`() {
        // Import persists the defaults-merged map, so every imported workspace
        // carries `desktop=false` even though nobody chose it.
        assertTrue(WorkspaceCapabilities.desktopAvailable(mapOf("desktop" to false), emptyMap(), capable = true))
    }

    @Test
    fun `desktopIntentAttribute records only declared config keys`() {
        assertEquals("true", WorkspaceCapabilities.desktopIntentAttribute(mapOf("desktop" to true)))
        assertEquals("false", WorkspaceCapabilities.desktopIntentAttribute(mapOf("desktop.enabled" to false)))
        assertNull(WorkspaceCapabilities.desktopIntentAttribute(mapOf("ssh" to true)))
        assertNull(WorkspaceCapabilities.desktopIntentAttribute(emptyMap()))
    }

    @Test
    fun `explicit config enabled false attribute wins over capability`() {
        assertFalse(
            WorkspaceCapabilities.desktopAvailable(emptyMap(), mapOf("desktop.enabled" to "false"), capable = true)
        )
    }

    @Test
    fun `the capability cache attribute is not an intent signal`() {
        assertTrue(WorkspaceCapabilities.desktopAvailable(emptyMap(), mapOf("desktop" to "false"), capable = true))
    }
}
