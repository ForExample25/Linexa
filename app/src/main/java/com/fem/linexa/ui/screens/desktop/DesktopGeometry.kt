package com.fem.linexa.ui.screens.desktop

import kotlin.math.roundToInt

/**
 * §1.125 — Pure (JVM-testable) resolution of the Xvnc framebuffer geometry —
 * and, since the 2026-09-05 TinyContainer-parity fix, of the X server's DPI.
 *
 * Why this exists: the X server command was the constant
 * `Xvnc :1 -geometry 1280x720 -depth 32 -SecurityTypes None`, so **every**
 * device got a 16:9 1280×720 desktop regardless of its actual screen. The
 * viewer contain-scales the framebuffer ([VncViewer]), so on a 20:9 phone that
 * is correct-but-letterboxed: black bars top and bottom, and the desktop's
 * usable pixels scaled down to fit the *narrow* axis. Nothing failed and nothing
 * logged — the feature just looked like it was rendering a small window on a
 * large screen, which is the same class of silent wrongness §1.114 fixed.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.125):
 *
 * - **Landscape is forced, by taking the larger axis as the width.** An X
 *   desktop's window manager, panels and application defaults all assume a
 *   landscape work area; a 1080×2400 portrait X session puts an xfce4 panel down
 *   a 1080-pixel-wide strip and leaves most applications unusable. The viewer
 *   already scales and letterboxes, so a landscape framebuffer on a portrait
 *   phone is the same trade every remote-desktop client on Android makes.
 *   **UI/UX Phase 8 (§1.138)** made that trade unnecessary in the common case:
 *   the desktop surface itself is now landscape, so a landscape framebuffer
 *   matches the window it is drawn into.
 *
 * - **The result is clamped and bounded, not just copied from the display.**
 *   A framebuffer is held twice on the client (the [VncClient] `IntArray` plus
 *   the viewer's ARGB_8888 `Bitmap`), i.e. 8 bytes per pixel of *client* memory
 *   before the server's own copy. [MAX_WIDTH]/[MAX_HEIGHT] bound it at 4K as a
 *   degenerate-display guard, not as an everyday rescale.
 *
 * - **2026-09-05 (TinyContainer parity): AUTO is now 1:1 with the panel.**
 *   The previous 1080p ceiling asked a 2712×1220 display for a 1920×862
 *   framebuffer, and the viewer then upscaled it ~1.41× to fill the same
 *   panel — a bilinear resample of every pixel, which is exactly the
 *   "Linexa desktop is blurry / text is dull" defect measured against Tiny
 *   Container (whose termux-x11 frontend renders the X framebuffer natively
 *   at device resolution, 1 framebuffer pixel = 1 panel pixel). The ceiling
 *   now only engages for displays beyond 4K, where the client-side copy
 *   (~33 MB at 3840×2160) and a raw full-frame update would genuinely cost
 *   more than they buy; every phone and tablet shipped this decade sits
 *   inside it and gets its own pixels back. The fixed resolution presets
 *   ([com.fem.linexa.service.runtime.features.DesktopResolution]) remain the
 *   explicit performance lever for slow devices.
 *
 * - **§1.138: the cap preserves the display's aspect ratio.** Each axis used to
 *   be clamped *independently*, which quietly converted the aspect ratio to
 *   whatever the two ceilings happened to be. On the most common target — a 20:9
 *   phone at 1080×2400 — that produced a 16:9 1920×1080 framebuffer for a 20:9
 *   window, so the fullscreen desktop was permanently letterboxed with ~11% of
 *   the screen unusable, which is exactly the defect §1.125 set out to remove and
 *   only moved from the resolution to the ratio. The cap is now one uniform scale
 *   factor applied to both axes (and the minimum is raised the same way), so the
 *   framebuffer always has the display's shape and the viewer's contain-scale has
 *   nothing left to letterbox.
 *
 * - **Each axis is rounded DOWN to an even number.** Odd framebuffer widths are
 *   legal RFB but are a well-known source of stride/rounding bugs in server-side
 *   encoders, and rounding *down* can only ever ask for less than the display
 *   really has.
 *
 * - **An unusable display falls back to [DEFAULT_GEOMETRY] rather than throwing.**
 *   This runs on the launch path of a feature the user just tapped; a display
 *   metric of 0 (which happens on some emulators and before a window exists) must
 *   degrade to the pre-§1.125 behaviour, not refuse to start the desktop.
 */
internal object DesktopGeometry {

    /** Smallest framebuffer worth serving; below this X applications don't fit. */
    const val MIN_WIDTH = 640
    const val MIN_HEIGHT = 480

    /**
     * 4K degenerate-display guard — NOT an everyday rescale. Every real phone
     * and tablet panel is below it, so [geometryFor] hands back the display's
     * own pixels 1:1; only a hypothetical display beyond 4K is scaled down, and
     * it is scaled uniformly (aspect preserved). See the class kdoc for the
     * memory/bandwidth arithmetic and the TinyContainer-parity rationale.
     */
    const val MAX_WIDTH = 3840
    const val MAX_HEIGHT = 2160

    /** Bounds for the [dpiFor] derivation; X servers accept 50..1200 sanely. */
    const val MIN_DPI = 64
    const val MAX_DPI = 1024

    /**
     * The DPI Xvnc reports when the caller cannot supply a device reading —
     * the X server's own conventional default, kept as the explicit fallback
     * so the command is always deterministic.
     */
    const val FALLBACK_DPI = 96

    /** The pre-§1.125 constant, kept as the fallback for an unusable display. */
    const val DEFAULT_GEOMETRY = "1280x720"

    /**
     * Colour depth requested from Xvnc.
     *
     * 24, not the pre-§1.125 `32`. [VncClient.sendSetPixelFormat] asks the server
     * for `bits-per-pixel = 32, depth = 24` with shifts 16/8/0 — which is exactly
     * what [VncPixels.decodeArgbInto] decodes — so a server started at depth 32 was
     * being told to convert to depth 24 for its only client. 24 is also the depth
     * every Xvnc build supports without argument, whereas depth 32 is accepted by
     * some builds and rejected at startup by others; a server that refuses to
     * start surfaces here as the generic [DesktopUiState.Error] "Failed to launch
     * Xvnc", which is a needlessly obscure way to report a hardcoded flag.
     */
    const val DEPTH = 24

    /**
     * How far the framebuffer's aspect ratio may differ from the display's before
     * the viewer would show a visible bar, as a fraction. Used by tests to state
     * the §1.138 rule as a measurable property rather than a claim.
     */
    const val ASPECT_TOLERANCE = 0.02

    /**
     * The `WxH` geometry string for a device whose display is
     * [displayWidthPx] × [displayHeightPx] physical pixels.
     *
     * Desktop Modes: [preset] short-circuits the display-derived decision —
     * a fixed preset returns its own geometry verbatim (already even, already
     * within the [MAX_WIDTH]/[MAX_HEIGHT] bound), so the user's smaller-framebuffer
     * choice cannot be silently widened by whatever this device reports. The
     * default keeps the pre-Desktop-Modes behaviour byte-identical.
     */
    fun geometryFor(
        displayWidthPx: Int,
        displayHeightPx: Int,
        preset: com.fem.linexa.service.runtime.features.DesktopResolution =
            com.fem.linexa.service.runtime.features.DesktopResolution.AUTO
    ): String {
        preset.geometry?.let { return it }
        if (displayWidthPx <= 0 || displayHeightPx <= 0) return DEFAULT_GEOMETRY
        val longEdge = maxOf(displayWidthPx, displayHeightPx).toDouble()
        val shortEdge = minOf(displayWidthPx, displayHeightPx).toDouble()

        // §1.138: ONE scale factor for both axes. Two independent clamps changed
        // the aspect ratio, and the viewer then letterboxed the difference.
        val ceilingScale = minOf(MAX_WIDTH / longEdge, MAX_HEIGHT / shortEdge)
        val floorScale = maxOf(MIN_WIDTH / longEdge, MIN_HEIGHT / shortEdge)

        // Down to fit the ceiling, up to clear the floor, otherwise 1:1. The
        // ceiling wins if a display is so extreme that it cannot satisfy both
        // (nothing shipping does) — it bounds *client memory*, whereas the floor is
        // only a usability preference.
        val scale = when {
            ceilingScale < 1.0 -> ceilingScale
            floorScale > 1.0 -> minOf(floorScale, ceilingScale)
            else -> 1.0
        }

        return "${evenDown(longEdge * scale)}x${evenDown(shortEdge * scale)}"
    }

    /**
     * The `-dpi` value Xvnc must report so the desktop's font/UI scale matches
     * the device's physical size.
     *
     * Derivation (not a tuned constant): the panel's true density is
     * [densityDpi] (Android's own reading, e.g. 520 on the reference device).
     * When the framebuffer is 1:1 with the panel — the AUTO case since the
     * TinyContainer-parity fix — the X server must report exactly that density,
     * or every Xft/GTK font renders at the wrong physical size (the X server's
     * built-in default has no relationship to the panel). When the framebuffer
     * is scaled — a fixed preset, or the 4K guard — the density scales by the
     * same ratio so the desktop's PHYSICAL layout is unchanged: what the user
     * chose was fewer pixels, not smaller text.
     *
     * A degenerate input (non-positive metrics) falls back to [FALLBACK_DPI]
     * rather than guessing; the result is clamped to [MIN_DPI]..[MAX_DPI].
     */
    fun dpiFor(
        framebufferWidth: Int,
        framebufferHeight: Int,
        displayWidthPx: Int,
        displayHeightPx: Int,
        densityDpi: Int
    ): Int {
        val fbLong = maxOf(framebufferWidth, framebufferHeight)
        val displayLong = maxOf(displayWidthPx, displayHeightPx)
        // Any non-positive AXIS is a degenerate reading — not just a zero pair —
        // so a half-measured display cannot produce a silently wrong ratio.
        if (framebufferWidth <= 0 || framebufferHeight <= 0 ||
            displayWidthPx <= 0 || displayHeightPx <= 0 || densityDpi <= 0
        ) return FALLBACK_DPI
        val scaled = densityDpi.toDouble() * (fbLong.toDouble() / displayLong.toDouble())
        return scaled.roundToInt().coerceIn(MIN_DPI, MAX_DPI)
    }

    /**
     * The full Xvnc command for [geometry].
     *
     * Desktop Modes changes two things, both backwards-compatible:
     *
     * - **[rfbPort]** — when non-null the server is pinned to that loopback
     *   port with `-rfbport N` plus, when the guest's Xvnc supports the flag
     *   ([localhostSupported] — TigerVNC ≥ 1.11 only; 1.10 and older exit 1 on
     *   an unknown option, which is a start-killing bug if assumed),
     *   `-localhost yes`. The fixed `5901` made two workspaces' desktops
     *   collide (the second Xvnc bound nothing, and the client polled a port
     *   owned by the *first* workspace's server); the per-workspace port
     *   removes both. `-localhost yes` is stated explicitly when supported
     *   (the desktop is a security boundary now that VNC-Auth exists, so the
     *   binding is written rather than assumed); on an older Xvnc the RFB
     *   listener follows the server's own binding default and the
     *   [securityArgs] decision is what stands between it and the network.
     * - **[securityArgs]** — the full `-SecurityTypes …` tail, so the auth
     *   decision lives in [DesktopVncAuthProvisioner] next to the password
     *   provisioning it depends on. The default reproduces the historical
     *   `-SecurityTypes None` exactly.
     *
     * **[dpi]** — when non-null, stated as `-dpi N` so the X server's reported
     * resolution tracks the device's real density (see [dpiFor]). Without it
     * Xvnc reports a built-in default that has no relationship to the panel,
     * and every Xft/GTK font in the desktop renders at the wrong physical
     * size — the "text is tiny/dull" half of the TinyContainer parity report.
     *
     * The pre-Desktop-Modes `None` rationale (loopback-only, no network path)
     * still holds for an unauthenticated start on a modern Xvnc; see
     * [DesktopVncAuthProvisioner] for why an authenticated one prefers
     * VNC-Auth only.
     */
    fun xvncCommand(
        geometry: String,
        display: String = DesktopSessionProvisioner.DISPLAY,
        rfbPort: Int? = null,
        securityArgs: String = "-SecurityTypes None",
        dpi: Int? = null,
        localhostSupported: Boolean = true
    ): String = buildString {
        append("Xvnc $display -geometry $geometry -depth $DEPTH")
        if (dpi != null) append(" -dpi $dpi")
        if (rfbPort != null) {
            append(" -rfbport $rfbPort")
            if (localhostSupported) append(" -localhost yes")
        }
        append(' ')
        append(securityArgs)
    }

    /** Rounds down to the nearest even number, never below 2. */
    private fun evenDown(value: Double): Int {
        val rounded = value.toInt()
        return maxOf(2, rounded - (rounded % 2))
    }
}
