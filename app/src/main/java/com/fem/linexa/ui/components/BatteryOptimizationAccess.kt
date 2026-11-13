package com.fem.linexa.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * §1.191 — the battery-optimization whitelist state and its two system
 * entry points, in one place.
 *
 * Extracted from
 * [com.fem.linexa.ui.screens.settings.AccessSettingsGroup] when a second,
 * unrelated caller appeared: the per-terminal SIGKILL notice
 * ([com.fem.linexa.ui.screens.terminal.TerminalKilledNotice]). Global Settings
 * offers the whitelist as a *permission row*; the terminal notice offers it as
 * a *remedy* for a terminal Android just reclaimed. Same platform state, same
 * intents, two presentations — so the platform interaction lives here and the
 * two call sites own only their own UI.
 *
 * Not a ViewModel or an injected service: this is three stateless platform
 * calls with no lifecycle of its own, and the state it reports has no change
 * notification to observe (both call sites re-query on resume).
 */

/**
 * Whether the app is currently exempt from battery optimization.
 *
 * `false` when `PowerManager` is unavailable, which is the honest answer for a
 * UI whose only purpose is to offer the exemption: reporting `true` would hide
 * the affordance on exactly the devices where nothing can be verified.
 */
internal fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * If the app is already whitelisted, open the full battery-optimization list;
 * otherwise request the whitelist (the system dialog). The
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission is declared in the manifest so
 * the request dialog is actionable.
 *
 * Both launches are wrapped: a device with no activity for either intent is
 * unusual but real (heavily modified OEM builds, some emulator images), and
 * `startActivity` throws rather than no-oping. A failed launch leaves the caller's
 * UI untouched, which is the correct outcome for a suggestion.
 */
internal fun requestIgnoreBatteryOptimizations(context: Context) {
    if (isIgnoringBatteryOptimizations(context)) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    } else {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }
}
