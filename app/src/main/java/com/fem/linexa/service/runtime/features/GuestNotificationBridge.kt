package com.fem.linexa.service.runtime.features

/**
 * §1.111 — the narrow seam [WorkspaceNotificationSupervisor] uses to surface a
 * guest notification on Android.
 *
 * Exists for the same reason [GuestFeatureHost] does: the real implementation
 * ([AndroidGuestNotificationBridge]) needs `NotificationManagerCompat` and an
 * `Application`, so a supervisor that depended on it directly would be
 * untestable off-device. The spool → parse → post chain is therefore driven by a
 * plain fake in JVM tests.
 *
 * Deliberately minimal, and deliberately one-directional: a guest can *ask for*
 * a notification and nothing else. There is no API here to read existing
 * notifications, to open an activity, to bind a listener service, or to address
 * another workspace — [cancelWorkspace] is scoped to the workspace whose session
 * is ending. A workspace feature must never be able to observe or act on the
 * user's other notifications.
 */
interface GuestNotificationBridge {

    /**
     * Whether the host can post at all right now (channel available and
     * notifications not blocked by the user). Used to report
     * [NotificationFeatureState.Blocked] instead of silently dropping records.
     */
    fun canPost(): Boolean

    /**
     * Post (or update) [notification] on behalf of [workspaceId].
     *
     * Updating rather than stacking is the point of
     * [NotifySendProvisioner.notificationTag]: `notify-send -r 42` twice must
     * replace, not accumulate.
     */
    fun post(workspaceId: String, notification: GuestNotification)

    /**
     * Withdraw every notification this bridge posted for [workspaceId].
     *
     * Called when the workspace's session ends, and when the toggle is turned
     * off: a stopped workspace must not leave live notifications behind that
     * imply it is still running.
     */
    fun cancelWorkspace(workspaceId: String)

    /**
     * Tell the bridge how to *name* [workspaceId] in a posted notification.
     *
     * Attribution is not cosmetic: a user with several workspaces has to be able
     * to tell which one is talking, and the raw id is a UUID. Kept on this
     * interface (rather than only on the Android implementation) so the launch
     * path — the one place that already reads the workspace's metadata — has a
     * seam to supply it through, and so the wiring is assertable off-device.
     * Optional: an unnamed workspace falls back to its id.
     */
    fun setDisplayName(workspaceId: String, displayName: String)
}
