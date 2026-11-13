package com.fem.linexa.service.runtime

/**
 * §1.97/W4 — pure, JVM-testable content for the runtime foreground
 * notification. The service assembles the actual `NotificationCompat` (Android
 * types) from these strings; all user-visible content, the action labels and
 * the deep-link target live here so the "Open / Stop" contract is unit-testable
 * without a device (audit §19 Tier 1).
 *
 * Audit §10: one foreground notification per running workspace; content =
 * workspace display name + active-terminal count + guest status; the Open
 * action targets the `workspace/{id}` deep-link route, the Stop action
 * self-sends [RuntimeService.ACTION_STOP] so it works from the notification
 * without the app being foreground.
 */
object RuntimeNotificationContent {

    /** Deep-link scheme for the workspace route (notification Open). */
    const val DEEP_LINK_SCHEME = "linexa"

    /** Notification title. */
    fun contentTitle(): String = "Linexa Runtime"

    /**
     * Notification body text. [displayName] falls back to the workspace id in
     * the service when the workspace metadata hasn't been resolved yet.
     */
    fun contentText(displayName: String, terminalCount: Int, status: String): String {
        return "Workspace: $displayName ($terminalCount terminals, $status)"
    }

    /** The "Open / return to Workspace" notification-action label. */
    fun openActionLabel(): String = "Open"

    /** The "Stop Workspace runtime" notification-action label. */
    fun stopActionLabel(): String = "Stop"

    /**
     * The deep-link URI the Open action navigates to — matches the
     * `navDeepLink` on the `workspace/{workspaceId}` destination
     * (`NavGraph.kt`). §1.103: the URI also carries the terminal to restore
     * (`?terminalId=…`, the last-active session), so the tap lands on the
     * Terminal screen — never on the Workspace main screen first.
     */
    fun openDeepLink(workspaceId: String, terminalId: String): String =
        "$DEEP_LINK_SCHEME://workspace/$workspaceId?terminalId=$terminalId"

    /** Guest status shown while the workspace is starting. */
    fun statusStarting(): String = "Starting"

    /** Guest status shown once the workspace's first terminal is live. */
    fun statusRunning(): String = "Running"

    /**
     * §1.142 — the wake-lock action's label, which doubles as the only display of
     * how much of the bounded hold is left.
     *
     * The label carries the remaining time because the hold expires on its own
     * ([com.fem.linexa.service.runtime.wakelock.WakeLockPolicy.MAX_HOLD_MS]) and a
     * user who cannot see that would experience the expiry as the feature
     * randomly failing. Minutes are rounded **up** so a hold with 30 s left reads
     * "Awake 1m" rather than "Awake 0m" — a live hold must never render as zero.
     *
     * @param remainingMs null when nothing is held; ignored when [held] is false.
     */
    fun wakeLockActionLabel(held: Boolean, remainingMs: Long?): String {
        if (!held || remainingMs == null) return "Keep awake"
        val minutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "Awake ${minutes}m"
    }
}
