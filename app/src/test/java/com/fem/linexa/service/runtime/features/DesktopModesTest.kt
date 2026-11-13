package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Modes (2026-08) — the persisted encoding of the desktop run-mode
 * settings, plus the id enums the encoding stores.
 *
 * Same contract as [FeatureSettingsTest] pins for the SSH port and clipboard
 * aliases: [FeatureSettings.from] is total and fail-open (a corrupt or absent
 * value degrades to the pre-Desktop-Modes behaviour, never a broken
 * workspace), and [FeatureSettings.toAttributes] writes every key so the
 * persisted record is self-describing.
 */
class DesktopModesTest {

    // ── DesktopRunMode ───────────────────────────────────────────────────

    @Test
    fun modeIdsAreStableBecauseTheyAreStoredInRoom() {
        // Renaming one of these silently corrupts every workspace's saved
        // choice — if a rename is ever unavoidable, add a fromId alias instead.
        assertEquals("vnc", DesktopRunMode.VNC.id)
        assertEquals("webview", DesktopRunMode.WEBVIEW.id)
    }

    @Test
    fun defaultModeIsVnc() {
        assertEquals(DesktopRunMode.VNC, DesktopRunMode.DEFAULT)
        assertEquals(DesktopRunMode.VNC, DesktopRunMode.fromId(null))
        assertEquals(DesktopRunMode.VNC, DesktopRunMode.fromId(""))
        assertEquals(DesktopRunMode.VNC, DesktopRunMode.fromId("nonsense"))
    }

    @Test
    fun modeIdsParseCaseInsensitivelyAndTrimmed() {
        assertEquals(DesktopRunMode.WEBVIEW, DesktopRunMode.fromId("webview"))
        assertEquals(DesktopRunMode.WEBVIEW, DesktopRunMode.fromId(" WebView "))
        assertEquals(DesktopRunMode.VNC, DesktopRunMode.fromId("VNC"))
    }

    // ── DesktopResolution ────────────────────────────────────────────────

    @Test
    fun presetIdsAndGeometriesMatchTheSettingsUi() {
        assertEquals("auto", DesktopResolution.AUTO.id)
        assertEquals(null, DesktopResolution.AUTO.geometry)
        assertEquals("720p", DesktopResolution.P720.id)
        assertEquals("1280x720", DesktopResolution.P720.geometry)
        assertEquals("900p", DesktopResolution.P900.id)
        assertEquals("1600x900", DesktopResolution.P900.geometry)
        assertEquals("1080p", DesktopResolution.P1080.id)
        assertEquals("1920x1080", DesktopResolution.P1080.geometry)
    }

    @Test
    fun presetGeometriesAreEvenAndWithinThe1080pCeiling() {
        // DesktopGeometry rounds down to even and clamps to 1080p for the
        // display-derived path; fixed presets must arrive pre-conformed.
        DesktopResolution.entries.forEach { preset ->
            val geometry = preset.geometry ?: return@forEach
            val (w, h) = geometry.split("x").map { it.toInt() }
            assertEquals(0, w % 2)
            assertEquals(0, h % 2)
            assertTrue(w <= 1920 && h <= 1080)
        }
    }

    @Test
    fun resolutionIdsParseFailOpen() {
        assertEquals(DesktopResolution.AUTO, DesktopResolution.fromId(null))
        assertEquals(DesktopResolution.AUTO, DesktopResolution.fromId("garbage"))
        assertEquals(DesktopResolution.P720, DesktopResolution.fromId("720p"))
    }

    // ── FeatureSettings round-trip ──────────────────────────────────────

    @Test
    fun defaultsMatchThePreDesktopModesBehaviour() {
        val settings = FeatureSettings()
        assertEquals(DesktopRunMode.VNC, settings.desktopMode)
        assertEquals(DesktopResolution.AUTO, settings.desktopResolution)
        assertFalse(settings.desktopVncPreferZrle)
        assertEquals("", settings.desktopVncPassword)
        assertEquals(FeatureSettings.DEFAULT_WEB_PORT, settings.desktopWebPort)
        assertEquals("", settings.desktopWebCommand)
    }

    @Test
    fun desktopSettingsRoundTripThroughAttributes() {
        val settings = FeatureSettings(
            desktopMode = DesktopRunMode.WEBVIEW,
            desktopResolution = DesktopResolution.P720,
            desktopVncPreferZrle = true,
            desktopVncPassword = "s3cret12",
            desktopWebPort = 3000,
            desktopWebCommand = "python3 -m http.server 3000"
        )
        val decoded = FeatureSettings.from(settings.toAttributes())
        assertEquals(DesktopRunMode.WEBVIEW, decoded.desktopMode)
        assertEquals(DesktopResolution.P720, decoded.desktopResolution)
        assertTrue(decoded.desktopVncPreferZrle)
        assertEquals("s3cret12", decoded.desktopVncPassword)
        assertEquals(3000, decoded.desktopWebPort)
        assertEquals("python3 -m http.server 3000", decoded.desktopWebCommand)
    }

    @Test
    fun legacyAttributesWithoutDesktopKeysResolveToDefaults() {
        // A pre-Desktop-Modes workspace: only the Phase-6 keys exist. The
        // desktop settings must fail open to exactly the old behaviour.
        val legacy = FeatureSettings(sshPort = 22222).toAttributes().filterKeys { it.startsWith("feature.ssh") }
        val decoded = FeatureSettings.from(legacy)
        assertEquals(DesktopRunMode.VNC, decoded.desktopMode)
        assertEquals(DesktopResolution.AUTO, decoded.desktopResolution)
        assertFalse(decoded.desktopVncPreferZrle)
        assertEquals("", decoded.desktopVncPassword)
        assertEquals(FeatureSettings.DEFAULT_WEB_PORT, decoded.desktopWebPort)
        assertEquals("", decoded.desktopWebCommand)
    }

    @Test
    fun corruptDesktopValuesFailOpen() {
        val decoded = FeatureSettings.from(
            mapOf(
                FeatureSettings.KEY_DESKTOP_MODE to "termux-x11",
                FeatureSettings.KEY_DESKTOP_RESOLUTION to "8k",
                FeatureSettings.KEY_DESKTOP_VNC_ZRLE to "1",
                FeatureSettings.KEY_DESKTOP_WEB_PORT to "80",
                FeatureSettings.KEY_DESKTOP_WEB_COMMAND to ""
            )
        )
        assertEquals(DesktopRunMode.VNC, decoded.desktopMode)
        assertEquals(DesktopResolution.AUTO, decoded.desktopResolution)
        // ZRLE accepts only the literal "true" — "1" is not a boolean here.
        assertFalse(decoded.desktopVncPreferZrle)
        assertEquals(FeatureSettings.DEFAULT_WEB_PORT, decoded.desktopWebPort)
    }

    @Test
    fun webPortOutsideTheSelectableRangeFallsBackToDefault() {
        val decoded = FeatureSettings.from(mapOf(FeatureSettings.KEY_DESKTOP_WEB_PORT to "22"))
        assertEquals(FeatureSettings.DEFAULT_WEB_PORT, decoded.desktopWebPort)
    }

    @Test
    fun theDesktopFeatureIdIsTheOneTheCapabilityLayerReads() {
        // WorkspaceCapabilities.desktopEnabled reads the "desktop" toggle key;
        // the feature catalog must use exactly that id or the toggle gates
        // nothing. Pinned here because a rename in either place is silent.
        assertEquals("desktop", FEATURE_DESKTOP)
    }
}
