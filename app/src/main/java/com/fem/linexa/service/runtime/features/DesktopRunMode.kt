package com.fem.linexa.service.runtime.features

/**
 * The Desktop screen's **run mode** — how the guest's graphical session is
 * presented to the user (Desktop Modes work, 2026-08).
 *
 * This is Linexa's answer to the "one GUI frontend per container" model the
 * reference Tiny Container app popularised (`webview` / `avnc` / `x11`), with
 * two deliberate differences:
 *
 * 1. **The modes are selected in the app, not baked into the image.** The
 *    Tiny Container model stores the frontend choice in each container's
 *    `.tiny.yaml`, so the app cannot display, validate, or change it. Linexa's
 *    argv is built in [com.fem.linexa.ui.screens.desktop.DesktopViewModel] and
 *    the choice lives in persisted [FeatureSettings] — the same honesty rule
 *    every other launch parameter here follows.
 * 2. **No new third-party frontend libraries.** Tiny Container embeds GPL-3.0
 *    AAR forks (termux-x11, AVNC). Linexa's two modes are built entirely from
 *    in-repo code and platform APIs: [VNC] drives the hand-written RFB client
 *    (`ui/screens/desktop/VncClient.kt`), and [WEBVIEW] renders a guest HTTP
 *    server through the platform `android.webkit.WebView`. Nothing new is
 *    linked, so nothing new constrains the license.
 *
 * ## What each mode actually does
 *
 * - [VNC] — the pre-Desktop-Modes behaviour, retained and tightened: start
 *   `Xvnc` inside the workspace as a supervised guest daemon, probe a desktop
 *   session (§1.114), and stream the framebuffer through the embedded RFB 3.8
 *   client on a per-workspace loopback port. Needs an X client (DE/WM/xterm)
 *   inside the guest.
 * - [WEBVIEW] — start a **guest-side web server** (a user-supplied command:
 *   a webtop image's service, `noVNC`, Apache Guacamole, a code-server, a
 *   simple `python3 -m http.server`, anything) as a supervised daemon, wait
 *   for its TCP port to accept, and open it in a fullscreen in-app WebView.
 *   Needs no X client at all, which makes it the only mode a bare rootfs can
 *   run — and the reason the `desktop` feature toggle defaults to on even for
 *   images the desktop probe would call headless.
 *
 * Persisted as the `feature.desktop.mode` attribute (see
 * [FeatureSettings.from]); reading is fail-open to [DEFAULT].
 */
enum class DesktopRunMode(
    /** The stable persisted id. Never renamed — it is stored in Room attributes. */
    val id: String
) {
    /** Embedded VNC (the hand-written RFB client). The pre-Desktop-Modes behaviour. */
    VNC("vnc"),

    /** Guest web server rendered in the platform WebView. */
    WEBVIEW("webview");

    companion object {

        /** The mode a workspace with no persisted choice gets. */
        val DEFAULT: DesktopRunMode = VNC

        /**
         * Fail-open resolution of a persisted mode id: anything unknown —
         * including null and blank — degrades to [DEFAULT] rather than
         * refusing to start, the same stance §1.27 takes for config.yaml
         * values and [FeatureSettings.from] takes for every attribute.
         */
        fun fromId(raw: String?): DesktopRunMode =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * The Xvnc framebuffer presets offered by the VNC run mode's settings.
 *
 * [AUTO] is the pre-Desktop-Modes behaviour: derive the geometry from the
 * device display (landscape-forced, aspect-preserving, clamped to 1080p —
 * see `DesktopGeometry.geometryFor` for the measured rationale). The fixed
 * presets exist because a *smaller* framebuffer is a genuine performance
 * lever on a phone: every Xvnc rectangle traverses the guest's encoder, the
 * loopback socket and the client's decode+upload path, and a 720p desktop
 * does roughly half the per-frame pixel work of the 1080p-or-better geometry
 * a modern display would otherwise request. Users on slow devices get a
 * switch for that; users who want every pixel keep [AUTO].
 */
enum class DesktopResolution(
    /** The stable persisted id. Never renamed. */
    val id: String,

    /**
     * The `WxH` geometry string, or null for [AUTO] (where the display
     * decides). Both axes are already even, matching `DesktopGeometry`'s
     * even-down rule, so the preset needs no further rounding.
     */
    val geometry: String?
) {
    AUTO("auto", null),

    /** 1280×720 — the lightest usable desktop; halves per-frame pixel work. */
    P720("720p", "1280x720"),

    /** 1600×900 — mid-point between the 720p floor and the 1080p ceiling. */
    P900("900p", "1600x900"),

    /** 1920×1080 — the display-derived geometry's ceiling, forced exactly. */
    P1080("1080p", "1920x1080");

    companion object {

        /** Fail-open resolution of a persisted id, defaulting to [AUTO]. */
        fun fromId(raw: String?): DesktopResolution =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: AUTO
    }
}
