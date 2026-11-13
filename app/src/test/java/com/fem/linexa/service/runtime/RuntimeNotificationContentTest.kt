package com.fem.linexa.service.runtime

import com.fem.linexa.service.runtime.wakelock.WakeLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1.97/W4 — [RuntimeNotificationContent] is pure/string-only, so the
 * Open/Stop notification contract is verifiable without a device (audit
 * §19 Tier 1). The actual NotificationCompat assembly (Android types) lives
 * in RuntimeService; everything user-visible lives here.
 */
class RuntimeNotificationContentTest {

    @Test
    fun titleIsLinexaRuntime() {
        assertEquals("Linexa Runtime", RuntimeNotificationContent.contentTitle())
    }

    @Test
    fun contentTextIncludesDisplayNameTerminalCountAndStatus() {
        assertEquals(
            "Workspace: Debian (3 terminals, Running)",
            RuntimeNotificationContent.contentText("Debian", 3, RuntimeNotificationContent.statusRunning())
        )
    }

    @Test
    fun contentTextSingleTerminalStarting() {
        assertEquals(
            "Workspace: ws-1 (1 terminals, Starting)",
            RuntimeNotificationContent.contentText("ws-1", 1, RuntimeNotificationContent.statusStarting())
        )
    }

    @Test
    fun contentTextZeroTerminalsFallsBackToWorkspaceId() {
        // Before the session resolves its display name, the service passes the
        // workspace id as the display-name fallback.
        assertEquals(
            "Workspace: ws-1 (0 terminals, Starting)",
            RuntimeNotificationContent.contentText("ws-1", 0, RuntimeNotificationContent.statusStarting())
        )
    }

    @Test
    fun openDeepLinkTargetsWorkspaceRoute() {
        assertEquals("linexa://workspace/ws-1?terminalId=T0", RuntimeNotificationContent.openDeepLink("ws-1", "T0"))
        assertEquals("linexa://workspace/ws-abc?terminalId=T1", RuntimeNotificationContent.openDeepLink("ws-abc", "T1"))
    }

    @Test
    fun openDeepLinkCarriesRestoredTerminalId() {
        // §1.103: the Open action restores the last-active terminal session,
        // so the deep link must name the terminal, not just the workspace.
        assertEquals("T0", RuntimeNotificationContent.openDeepLink("ws-1", "T0").substringAfter("terminalId="))
        assertEquals("T1", RuntimeNotificationContent.openDeepLink("ws-1", "T1").substringAfter("terminalId="))
    }

    @Test
    fun openDeepLinkMatchesGraphScheme() {
        // NavGraph.kt's navDeepLink uses the same scheme + pattern.
        assertEquals("linexa", RuntimeNotificationContent.DEEP_LINK_SCHEME)
    }

    @Test
    fun actionLabels() {
        assertEquals("Open", RuntimeNotificationContent.openActionLabel())
        assertEquals("Stop", RuntimeNotificationContent.stopActionLabel())
    }

    @Test
    fun stopActionMatchesServiceActionConstant() {
        // The Stop action must self-send RuntimeService.ACTION_STOP (audit §10),
        // so the notification Stop and a programmatic stop share one teardown.
        assertEquals("com.fem.linexa.ACTION_STOP_RUNTIME", RuntimeService.ACTION_STOP)
    }

    @Test
    fun refreshNotificationActionIsDefined() {
        // §W6 bug fix: a late POST_NOTIFICATIONS grant re-issues the foreground
        // notification via RuntimeService.ACTION_REFRESH_NOTIFICATION so the
        // Open/Stop actions are not stuck invisible on API 33+ first run.
        assertEquals("com.fem.linexa.ACTION_REFRESH_NOTIFICATION", RuntimeService.ACTION_REFRESH_NOTIFICATION)
    }

    // ── §1.142: the wake-lock action label ──────────────────────────────────

    @Test
    fun wakeLockLabelIsAnInvitationWhenNothingIsHeld() {
        assertEquals("Keep awake", RuntimeNotificationContent.wakeLockActionLabel(held = false, remainingMs = null))
    }

    @Test
    fun wakeLockLabelShowsRemainingMinutesWhileHeld() {
        assertEquals("Awake 60m", RuntimeNotificationContent.wakeLockActionLabel(true, WakeLockPolicy.MAX_HOLD_MS))
        assertEquals("Awake 42m", RuntimeNotificationContent.wakeLockActionLabel(true, 42L * 60_000L))
    }

    @Test
    fun aLiveHoldNeverRendersAsZeroMinutes() {
        // Rounded up: a hold with 30 s left is still a hold, and "Awake 0m" would
        // read as broken. The floor is 1m for anything above zero.
        assertEquals("Awake 1m", RuntimeNotificationContent.wakeLockActionLabel(true, 30_000L))
        assertEquals("Awake 1m", RuntimeNotificationContent.wakeLockActionLabel(true, 1L))
        assertEquals("Awake 1m", RuntimeNotificationContent.wakeLockActionLabel(true, 0L))
    }

    @Test
    fun wakeLockLabelIgnoresRemainingTimeWhenNotHeld() {
        // A stale remaining value must never make the action claim a hold that
        // is gone — `held` is the authority.
        assertEquals("Keep awake", RuntimeNotificationContent.wakeLockActionLabel(false, 30L * 60_000L))
    }

    @Test
    fun wakeLockLabelHandlesAMissingRemainingValue() {
        // held-but-unknown can only happen if the hold vanished between the two
        // reads; it must degrade to the invitation, not crash or show "Awake nullm".
        assertEquals("Keep awake", RuntimeNotificationContent.wakeLockActionLabel(true, null))
    }

    @Test
    fun wakeLockToggleActionMatchesServiceActionConstant() {
        // Same contract as Stop: the notification action self-sends to the
        // service, so the label and the intent cannot drift apart.
        assertEquals("com.fem.linexa.ACTION_TOGGLE_WAKE_LOCK", RuntimeService.ACTION_TOGGLE_WAKE_LOCK)
    }

    @Test
    fun theWakeLockActionHasItsOwnPendingIntentRequestCode() {
        // A shared request code + FLAG_UPDATE_CURRENT would rewrite the Stop
        // action's intent into a wake-lock toggle, so Stop would keep the phone
        // awake instead of stopping the workspace.
        val codes = listOf(
            RuntimeService.OPEN_NOTIFICATION_REQUEST_CODE,
            RuntimeService.STOP_NOTIFICATION_REQUEST_CODE,
            RuntimeService.WAKE_LOCK_NOTIFICATION_REQUEST_CODE
        )
        assertEquals("notification request codes must be distinct", codes.size, codes.toSet().size)
    }
}
