package com.fem.linexa.service.runtime.desktop

/**
 * Pure builder for the X11 guest environment provisioning (transformation
 * spec §14). Installs an idempotent profile script so EVERY guest shell —
 * not only the desktop session daemon — can address the workspace's X
 * server: `DISPLAY` from the workspace's allocated display, `XKB_CONFIG_ROOT`
 * at the guest's standard xkb location, and a deterministic `TMPDIR`.
 * Resolved at *guest* runtime, so nothing here hardcodes a host-specific
 * path (§14's rule).
 *
 * Moved from `ui.screens.desktop` (as `X11Environment`, internal) to the
 * runtime layer so the same builder serves the desktop UI, the daemon
 * environment and the diagnostics surface without a UI→runtime dependency.
 */
object X11Environment {

    const val PROFILE_PATH = "/etc/profile.d/linexa-x11.sh"

    /**
     * The one-shot command that (re)writes the profile script. Idempotent by
     * overwrite; the marker comment lets users see the file is Linexa's.
     */
    fun profileScriptCommand(display: String): String {
        val script = buildString {
            appendLine("# managed by Linexa — X11 desktop environment")
            appendLine("export DISPLAY=$display")
            appendLine("export XKB_CONFIG_ROOT=\"\${XKB_CONFIG_ROOT:-/usr/share/X11/xkb}\"")
            appendLine("export TMPDIR=\"\${TMPDIR:-/tmp}\"")
        }.trimEnd()
        val singleQuoted = script.replace("'", "'\\''")
        return "mkdir -p /etc/profile.d && printf '%s\\n' '$singleQuoted' > $PROFILE_PATH"
    }

    /**
     * The env-assignment prefix the desktop-session daemon command carries
     * (§14: TMPDIR, DISPLAY, XKB_CONFIG_ROOT — the same key set Tiny
     * Container passes through EXTRA_ENV_KEYS/EXTRA_ENV_VALUES, resolved to
     * guest-native paths). The daemon host runs commands under `/bin/sh -c
     * exec …`, so a leading `env K=V …` sets exactly these variables for the
     * session process regardless of the rootfs's own profile state.
     */
    fun daemonEnvPrefix(display: String): String =
        "env DISPLAY=$display TMPDIR=/tmp XKB_CONFIG_ROOT=/usr/share/X11/xkb"
}
