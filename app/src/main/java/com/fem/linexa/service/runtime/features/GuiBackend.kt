package com.fem.linexa.service.runtime.features

/**
 * Desktop Backends — which GUI delivery backend a workspace's Desktop uses
 * (§11/§13/§15 of the Core Runtime & Desktop Transformation spec).
 *
 * `X11` is the *preferred* local backend: the guest runs a real X11 stack
 * (X server + session manager + `DISPLAY` exported to the whole workspace),
 * readiness is detected from the X11 unix socket rather than the TCP port
 * alone, and the embedded viewer renders the X server's framebuffer. `VNC`
 * remains the compatibility fallback: the pre-Desktop-Backends path exactly
 * (probe → Xvnc → session), unchanged, so an X11 failure never costs the
 * user their desktop (§28's fallback rule).
 *
 * Both backends are *Linexa-native*: the X server itself is the Xvnc the
 * guest already provisions (an X11 server in its own right), and no
 * external GUI component — GPL-3.0 or otherwise — is embedded. The
 * abstraction exists so a future external X provider can be added behind
 * the same lifecycle without touching the selection UI.
 *
 * Pure and JVM-testable, mirroring [DesktopRunMode]: stable `id`s are the
 * persisted attribute encoding and [fromId] is total/fail-open, so a
 * downgraded or hand-edited attribute degrades to the preferred default
 * instead of breaking the desktop.
 */
enum class GuiBackend(val id: String) {
    X11("x11"),
    VNC("vnc");

    companion object {
        /** §11: X11 is the preferred backend — the default for new sessions. */
        val DEFAULT = X11

        /** Stable persisted id, for [FeatureSettings.KEY_DESKTOP_BACKEND]. */
        const val X11_ID = "x11"
        const val VNC_ID = "vnc"

        /** Total, fail-open: any unknown/absent value resolves to [DEFAULT]. */
        fun fromId(raw: String?): GuiBackend = when (raw?.trim()?.lowercase()) {
            VNC_ID -> VNC
            X11_ID -> X11
            else -> DEFAULT
        }
    }
}
