package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.125 — JVM tests for [DesktopGeometry].
 *
 * The pre-§1.125 geometry was a string constant, so there was nothing to test and
 * nothing to notice: every device was served 1280×720 and the viewer dutifully
 * letterboxed it. These assertions pin the parts that would silently regress —
 * the landscape flip, the memory ceiling, and the fallback that keeps a device
 * with unusable display metrics from losing the desktop entirely.
 */
class DesktopGeometryTest {

    private fun widthOf(geometry: String) = geometry.substringBefore('x').toInt()
    private fun heightOf(geometry: String) = geometry.substringAfter('x').toInt()

    // ---- landscape orientation ----

    @Test
    fun aPortraitPhoneStillGetsALandscapeDesktop() {
        // 1080x2400 portrait: an X session at 1080 wide puts an xfce4 panel down a
        // narrow strip and leaves most applications unusable.
        val geometry = DesktopGeometry.geometryFor(1080, 2400)
        assertTrue("width must be the long edge", widthOf(geometry) > heightOf(geometry))
    }

    @Test
    fun aLandscapeDisplayKeepsItsOwnOrientation() {
        val geometry = DesktopGeometry.geometryFor(1920, 1080)
        assertEquals("1920x1080", geometry)
    }

    @Test
    fun aSquareDisplayIsAccepted() {
        val geometry = DesktopGeometry.geometryFor(1000, 1000)
        assertEquals("1000x1000", geometry)
    }

    // ---- §16.5: what the measurement's TIMING can and cannot change ----
    //
    // The audit's §4.2 hypothesis is that `xServerCommand()` reads the display
    // metrics before DesktopScreen has put the window into fullscreen-landscape, and
    // that the resulting framebuffer is therefore shaped for the wrong window. The
    // ordering is real (DesktopViewModel sets Running only after Xvnc is up, and
    // DesktopWindowMode.forState only requests landscape for Running), so the
    // question these two tests settle is which *part* of that pre-fullscreen state
    // can actually reach the geometry.
    //
    // Rotation cannot: `geometryFor` is defined on the long and short edges, so it is
    // invariant under swapping its arguments. Bar insets can: they change the length
    // of an edge, not which edge is longer. Stated as tests because the hypothesis is
    // about ordering, and these are the properties that decide whether the ordering
    // has any consequence at all.

    @Test
    fun measuringBeforeTheWindowRotatesCannotChangeTheGeometry() {
        // The orientation half of §4.2, refuted by construction: portrait metrics and
        // the landscape metrics of the same display produce the identical framebuffer,
        // so reading them before the landscape request is not observable.
        assertEquals(
            DesktopGeometry.geometryFor(1080, 2400),
            DesktopGeometry.geometryFor(2400, 1080)
        )
    }

    @Test
    fun theGeometryIsInvariantUnderAxisSwapForEveryShape() {
        // The same property across the clamp ceiling, the raise floor and the 1:1
        // band, so it cannot be an accident of one input.
        listOf(1080 to 2400, 2800 to 1752, 320 to 240, 1600 to 900, 1440 to 3200)
            .forEach { (w, h) ->
                assertEquals(
                    "swapping the axes must not change the geometry for ${w}x$h",
                    DesktopGeometry.geometryFor(w, h),
                    DesktopGeometry.geometryFor(h, w)
                )
            }
    }

    @Test
    fun measuringAnInsetReducedWindowDoesChangeTheAspectRatio() {
        // The half of §4.2 that survives: if the metrics report a window whose long
        // edge has been shortened by the system bars, the framebuffer is shaped for
        // that shorter window and the viewer's contain-scale letterboxes the
        // difference for the whole session. 2277 = 2400 less ~123px of bars.
        val full = DesktopGeometry.geometryFor(1080, 2400)
        val insetReduced = DesktopGeometry.geometryFor(1080, 2277)
        assertNotEquals(
            "an inset-reduced measurement must be visible as a different geometry",
            full,
            insetReduced
        )
        // And the difference is an aspect-ratio difference, i.e. exactly the thing
        // §1.138's uniform scale exists to prevent — it preserves the ratio it is
        // GIVEN, so a wrong input cannot be detected downstream.
        assertTrue(
            "the shapes must differ by more than the tolerance: $full vs $insetReduced",
            kotlin.math.abs(aspectOf(full) - aspectOf(insetReduced)) / aspectOf(full) >
                DesktopGeometry.ASPECT_TOLERANCE
        )
    }

    // ---- clamping ----

    private fun aspectOf(geometry: String) =
        widthOf(geometry).toDouble() / heightOf(geometry).toDouble()

    @Test
    fun aDisplayWithinThe4KGuardGetsItsOwnPixelsBack() {
        // 2026-09-05 (TinyContainer parity): the previous 1080p ceiling asked a
        // 2800x1752 display for a downscaled framebuffer and the viewer then
        // upscaled it back — a resample of every pixel. Within the 4K guard
        // the answer is the display itself, 1:1, no resample anywhere.
        val geometry = DesktopGeometry.geometryFor(2800, 1752)
        assertEquals("2800x1752", geometry)
        // And the reference report's device: realDisplay=2712x1220 used to
        // come back as 1920x862 (a 1.41x upscale); it must now be native.
        assertEquals("2712x1220", DesktopGeometry.geometryFor(2712, 1220))
        assertTrue(widthOf(geometry) <= DesktopGeometry.MAX_WIDTH)
        assertTrue(heightOf(geometry) <= DesktopGeometry.MAX_HEIGHT)
    }

    @Test
    fun clampingPreservesTheDisplaysAspectRatio() {
        // §1.138: the two ceilings used to be applied per-axis, which silently
        // converted a 2800x1752 (1.598) display into 1920x1080 (1.778). The viewer
        // contain-scales, so the difference came back as a permanent bar.
        // 2026-09-05: the clamp itself now only engages beyond 4K, so the
        // property is stated on a display that actually trips it.
        val geometry = DesktopGeometry.geometryFor(5120, 2880)
        val displayAspect = 5120.0 / 2880.0
        assertTrue(widthOf(geometry) <= DesktopGeometry.MAX_WIDTH)
        assertTrue(heightOf(geometry) <= DesktopGeometry.MAX_HEIGHT)
        assertTrue(
            "aspect ${aspectOf(geometry)} must match $displayAspect: $geometry",
            kotlin.math.abs(aspectOf(geometry) - displayAspect) / displayAspect <=
                DesktopGeometry.ASPECT_TOLERANCE
        )
    }

    @Test
    fun aTallPhoneGetsAFramebufferItsOwnShape() {
        // The §1.138 headline case: 1080x2400 (20:9) used to yield 1920x1080 (16:9),
        // leaving ~11% of a fullscreen desktop as black bars.
        val geometry = DesktopGeometry.geometryFor(1080, 2400)
        val displayAspect = 2400.0 / 1080.0
        assertTrue(
            "aspect ${aspectOf(geometry)} must match $displayAspect: $geometry",
            kotlin.math.abs(aspectOf(geometry) - displayAspect) / displayAspect <=
                DesktopGeometry.ASPECT_TOLERANCE
        )
    }

    @Test
    fun aVeryTallPortraitDisplayIsClampedOnBothAxes() {
        val geometry = DesktopGeometry.geometryFor(1440, 3200)
        assertTrue(widthOf(geometry) <= DesktopGeometry.MAX_WIDTH)
        assertTrue(heightOf(geometry) <= DesktopGeometry.MAX_HEIGHT)
    }

    @Test
    fun aTinyDisplayIsRaisedToClearTheUsableMinimum() {
        val geometry = DesktopGeometry.geometryFor(320, 240)
        assertTrue(widthOf(geometry) >= DesktopGeometry.MIN_WIDTH)
        assertTrue(heightOf(geometry) >= DesktopGeometry.MIN_HEIGHT)
    }

    @Test
    fun raisingToTheMinimumAlsoPreservesTheAspectRatio() {
        // The floor is scaled by one factor for the same reason the ceiling is.
        val geometry = DesktopGeometry.geometryFor(320, 240)
        assertTrue(
            "aspect ${aspectOf(geometry)} must stay 4:3: $geometry",
            kotlin.math.abs(aspectOf(geometry) - 4.0 / 3.0) / (4.0 / 3.0) <=
                DesktopGeometry.ASPECT_TOLERANCE
        )
    }

    @Test
    fun aDisplayAlreadyWithinBoundsIsUsedAsIs() {
        // No scaling at all in the common tablet/landscape case: 1:1 is the honest
        // answer and any rescale would cost sharpness for nothing.
        assertEquals("1600x900", DesktopGeometry.geometryFor(1600, 900))
    }

    // ---- even rounding ----

    @Test
    fun bothAxesAreEven() {
        // Odd framebuffer widths are legal RFB but a known source of stride bugs in
        // server-side encoders.
        val geometry = DesktopGeometry.geometryFor(1081, 2401)
        assertEquals(0, widthOf(geometry) % 2)
        assertEquals(0, heightOf(geometry) % 2)
    }

    @Test
    fun evenRoundingNeverAsksForMoreThanTheDisplayHas() {
        val geometry = DesktopGeometry.geometryFor(999, 777)
        assertTrue(widthOf(geometry) <= 999)
        assertTrue(heightOf(geometry) <= 999)
    }

    // ---- fallback ----

    @Test
    fun aZeroWidthDisplayFallsBackToTheHistoricalDefault() {
        // Happens on some emulators and before a window exists. This runs on the
        // launch path of a feature the user just tapped, so it must degrade rather
        // than refuse.
        assertEquals(DesktopGeometry.DEFAULT_GEOMETRY, DesktopGeometry.geometryFor(0, 1080))
    }

    @Test
    fun aZeroHeightDisplayFallsBackToTheHistoricalDefault() {
        assertEquals(DesktopGeometry.DEFAULT_GEOMETRY, DesktopGeometry.geometryFor(1920, 0))
    }

    @Test
    fun aNegativeDisplayMetricFallsBackToTheHistoricalDefault() {
        assertEquals(DesktopGeometry.DEFAULT_GEOMETRY, DesktopGeometry.geometryFor(-1, -1))
    }

    @Test
    fun theFallbackIsTheExactPreDecisionConstant() {
        // The fallback's whole purpose is to be the pre-§1.125 behaviour.
        assertEquals("1280x720", DesktopGeometry.DEFAULT_GEOMETRY)
    }

    // ---- the command ----

    @Test
    fun theXvncCommandCarriesTheResolvedGeometry() {
        val command = DesktopGeometry.xvncCommand("1600x900")
        assertTrue(command.contains("-geometry 1600x900"))
    }

    @Test
    fun theXvncCommandTargetsTheOnlyDisplayLinexaStarts() {
        val command = DesktopGeometry.xvncCommand("1280x720")
        assertTrue(command.startsWith("Xvnc ${DesktopSessionProvisioner.DISPLAY} "))
    }

    @Test
    fun theXvncCommandRequestsTheDepthTheClientActuallyDecodes() {
        // VncClient.sendSetPixelFormat asks for bpp=32/depth=24 with shifts
        // 16/8/0, which is exactly what VncPixels.decodeArgbInto decodes. Starting the
        // server at depth 32 made it convert to depth 24 for its only client, and
        // some Xvnc builds refuse depth 32 outright — surfacing as the opaque
        // "Failed to launch Xvnc" error.
        assertEquals(24, DesktopGeometry.DEPTH)
        assertTrue(DesktopGeometry.xvncCommand("1280x720").contains("-depth 24"))
    }

    @Test
    fun theXvncCommandKeepsSecurityTypesNone() {
        // Safe *because of where it listens*: §1.83 starts it inside the
        // workspace's PRoot on host loopback, and the client is this app on the
        // same device. The DEFAULT keeps this pre-Desktop-Modes shape exactly;
        // the authenticated variant is opt-in via the securityArgs parameter —
        // see the VncAuth tests.
        assertTrue(DesktopGeometry.xvncCommand("1280x720").contains("-SecurityTypes None"))
    }

    // ---- Desktop Modes (2026-08): presets, dynamic port, auth args ----

    @Test
    fun aFixedPresetOverridesTheDisplayDerivedGeometry() {
        // The user's smaller-framebuffer choice must not be silently widened by
        // whatever this device reports.
        assertEquals(
            "1280x720",
            com.fem.linexa.service.runtime.features.DesktopResolution.P720.geometry
                ?.let { DesktopGeometry.geometryFor(1080, 2400, com.fem.linexa.service.runtime.features.DesktopResolution.P720) }
        )
        assertEquals(
            "1920x1080",
            DesktopGeometry.geometryFor(640, 480, com.fem.linexa.service.runtime.features.DesktopResolution.P1080)
        )
    }

    @Test
    fun autoPresetKeepsTheDisplayDerivedBehaviour() {
        // The default parameter is AUTO — byte-identical to the pre-Desktop-Modes call.
        assertEquals(
            DesktopGeometry.geometryFor(1080, 2400),
            DesktopGeometry.geometryFor(1080, 2400, com.fem.linexa.service.runtime.features.DesktopResolution.AUTO)
        )
    }

    @Test
    fun anExplicitPortPinsTheServerAndLocalhostBinding() {
        val command = DesktopGeometry.xvncCommand("1280x720", rfbPort = 5942)
        assertTrue(command.contains("-rfbport 5942"))
        // Stated, not assumed: with VNC-Auth in play, the binding is a
        // security boundary and belongs in the command text.
        assertTrue(command.contains("-localhost yes"))
    }

    @Test
    fun noExplicitPortReproducesTheHistoricalCommand() {
        // Backwards compatibility is a property, not an accident: the
        // no-arg-port call must be byte-identical to the pre-Desktop-Modes
        // output.
        assertEquals(
            "Xvnc ${DesktopSessionProvisioner.DISPLAY} -geometry 1280x720 -depth 24 -SecurityTypes None",
            DesktopGeometry.xvncCommand("1280x720")
        )
    }

    @Test
    fun securityArgsReplaceTheDefaultTail() {
        val command = DesktopGeometry.xvncCommand(
            "1280x720",
            rfbPort = 5942,
            securityArgs = DesktopVncAuthProvisioner.securityArgs(hasPassword = true)
        )
        assertTrue(command.contains("-SecurityTypes VncAuth -rfbauth ${DesktopVncAuthProvisioner.PASSWD_PATH}"))
        assertTrue(!command.contains("-SecurityTypes None"))
    }

    @Test
    fun theXvncCommandContainsNoShellMetacharacters() {
        // §1.14: the command reaches the guest through the daemon host's
        // `/bin/sh -c 'exec …'`, so anything injectable here would be executed.
        val command = DesktopGeometry.xvncCommand(DesktopGeometry.geometryFor(1080, 2400))
        listOf(";", "&&", "||", "|", "`", "$(", ">", "<").forEach {
            assertTrue("command must not contain '$it': $command", !command.contains(it))
        }
    }

    @Test
    fun differentDevicesProduceDifferentGeometry() {
        // The regression this decision fixes: one constant for every screen.
        assertNotEquals(
            DesktopGeometry.geometryFor(1080, 2400),
            DesktopGeometry.geometryFor(800, 1280)
        )
    }

    // ---- 2026-09-05 (TinyContainer parity): DPI derivation ----

    @Test
    fun aNativeFramebufferReportsThePanelDensity() {
        // 1:1 framebuffer on the reference device: the X server must report
        // exactly the density Android reports, or Xft/GTK fonts render at the
        // wrong physical size (the "text is tiny/dull" half of the report).
        assertEquals(
            520,
            DesktopGeometry.dpiFor(2712, 1220, 2712, 1220, 520)
        )
    }

    @Test
    fun aScaledFramebufferScalesTheDensityByTheSameRatio() {
        // A preset framebuffer is fewer PIXELS, not smaller TEXT: the density
        // scales by the long-edge ratio so the desktop's physical layout —
        // and therefore the rendered font size — stays what the panel wants.
        // 1920/2712 * 520 ≈ 368.
        val dpi = DesktopGeometry.dpiFor(1920, 862, 2712, 1220, 520)
        assertTrue("dpi $dpi must be below the panel density", dpi in 340..400)
    }

    @Test
    fun aDegenerateMetricFallsBackToTheConventionalDefault() {
        assertEquals(DesktopGeometry.FALLBACK_DPI, DesktopGeometry.dpiFor(0, 0, 2712, 1220, 520))
        assertEquals(DesktopGeometry.FALLBACK_DPI, DesktopGeometry.dpiFor(1920, 862, 0, 1220, 520))
        assertEquals(DesktopGeometry.FALLBACK_DPI, DesktopGeometry.dpiFor(1920, 862, 2712, 1220, 0))
    }

    @Test
    fun theDpiResultIsBounded() {
        assertTrue(DesktopGeometry.dpiFor(4000, 2000, 100, 100, 10_000) <= DesktopGeometry.MAX_DPI)
        assertTrue(DesktopGeometry.dpiFor(100, 100, 4000, 2000, 10) >= DesktopGeometry.MIN_DPI)
    }

    @Test
    fun theCommandCarriesTheDerivedDpi() {
        val command = DesktopGeometry.xvncCommand("2712x1220", dpi = 520)
        assertTrue(command.contains("-dpi 520"))
    }

    @Test
    fun theCommandOmitsDpiWhenTheCallerHasNoReading() {
        // Backwards compatibility for the diagnostic-only paths that build the
        // command without device metrics.
        assertFalse(DesktopGeometry.xvncCommand("1280x720").contains("-dpi"))
    }

    @Test
    fun anUnsupportedLocalhostFlagIsEarnedNotAssumed() {
        // TigerVNC ≤ 1.10 rejects -localhost with a fatal error — exit code 1
        // on EVERY start. The command builder must be able to omit it.
        val command = DesktopGeometry.xvncCommand("1280x720", rfbPort = 5942, localhostSupported = false)
        assertTrue(command.contains("-rfbport 5942"))
        assertFalse(command.contains("-localhost"))
    }
}
