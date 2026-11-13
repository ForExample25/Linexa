package com.fem.linexa.service.runtime.features

import com.fem.linexa.service.runtime.desktop.X11Environment
import com.fem.linexa.service.runtime.desktop.X11SocketReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Backends + Desktop Provisioning: the selection/persistence layer
 * ([GuiBackend], [DesktopPreset], the new [FeatureSettings] fields) and the
 * pure X11 readiness/environment builders (§13/§14/§15/§16).
 */
class DesktopBackendsAndPresetsTest {

    // ── GuiBackend ──

    @Test
    fun backendIdsAreStable() {
        assertEquals("x11", GuiBackend.X11.id)
        assertEquals("vnc", GuiBackend.VNC.id)
        assertEquals(GuiBackend.X11, GuiBackend.DEFAULT)
    }

    @Test
    fun backendFromIdIsTotal() {
        assertEquals(GuiBackend.X11, GuiBackend.fromId(null))
        assertEquals(GuiBackend.X11, GuiBackend.fromId(""))
        assertEquals(GuiBackend.X11, GuiBackend.fromId("nonsense"))
        assertEquals(GuiBackend.X11, GuiBackend.fromId("X11"))
        assertEquals(GuiBackend.VNC, GuiBackend.fromId("vnc"))
        assertEquals(GuiBackend.VNC, GuiBackend.fromId(" VNC "))
    }

    // ── FeatureSettings round-trips with the new fields ──

    @Test
    fun newFieldsRoundTripThroughAttributes() {
        val settings = FeatureSettings(
            desktopBackend = GuiBackend.VNC,
            desktopPreset = DesktopPreset.XFCE,
            terminalPersistence = true
        )
        assertEquals(settings, FeatureSettings.from(settings.toAttributes()))
    }

    @Test
    fun absentAttributesResolveToTheDocumentedDefaults() {
        val settings = FeatureSettings.from(emptyMap())
        assertEquals(GuiBackend.X11, settings.desktopBackend)
        assertEquals(DesktopPreset.NONE, settings.desktopPreset)
        assertFalse(settings.terminalPersistence)
    }

    @Test
    fun garbageValuesFailOpenToDefaults() {
        val settings = FeatureSettings.from(
            mapOf(
                FeatureSettings.KEY_DESKTOP_BACKEND to "wayland",
                FeatureSettings.KEY_DESKTOP_PRESET to "kde",
                FeatureSettings.KEY_TERMINAL_PERSISTENCE to "yes-please"
            )
        )
        assertEquals(GuiBackend.X11, settings.desktopBackend)
        assertEquals(DesktopPreset.NONE, settings.desktopPreset)
        assertFalse(settings.terminalPersistence)
    }

    // ── X11 readiness (§13) ──

    @Test
    fun x11SocketPathMatchesTheDesktopDisplay() {
        assertEquals("/tmp/.X11-unix/X1", X11SocketReadiness.guestSocketPath(":1"))
        assertEquals("/tmp/.X11-unix/X2", X11SocketReadiness.guestSocketPath(":2"))
        // The host-side file is the SAME path inside the workspace rootfs
        // (transformation spec §13 — host-side readiness, no guest exec).
        assertEquals(
            "/data/rootfs/tmp/.X11-unix/X1",
            X11SocketReadiness.hostSocketFile("/data/rootfs", ":1").absolutePath
        )
    }

    // ── X11 environment (§14) ──

    @Test
    fun x11ProfileScriptIsIdempotentAndDeterministic() {
        val once = X11Environment.profileScriptCommand(":1")
        val twice = X11Environment.profileScriptCommand(":1")
        assertEquals(once, twice)
        assertTrue(once.contains("export DISPLAY=:1"))
        assertTrue(once.contains("XKB_CONFIG_ROOT"))
        assertTrue(once.contains("TMPDIR"))
        assertTrue(once.contains(X11Environment.PROFILE_PATH))
        assertTrue(once.contains("managed by Linexa"))
    }
}
