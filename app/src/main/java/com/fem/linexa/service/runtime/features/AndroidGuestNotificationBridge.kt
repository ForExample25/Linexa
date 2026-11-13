package com.fem.linexa.service.runtime.features

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fem.linexa.MainActivity
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.RuntimeNotificationContent
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.111 — posts a guest's `notify-send` as a real Android notification.
 *
 * Kept separate from [WorkspaceNotificationSupervisor] (which owns the spool and
 * the reconcile) so all Android types live behind [GuestNotificationBridge] and
 * the parse → post chain stays JVM-testable.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.111):
 *
 * - **Its own channel, separate from the runtime FGS channel.** The runtime
 *   notification (`RuntimeService.CHANNEL_ID`, IMPORTANCE_LOW, ongoing) is
 *   infrastructure the user should not silence casually; guest notifications are
 *   content, and the user must be able to mute the workspace's chatter without
 *   losing the runtime's Stop action. IMPORTANCE_DEFAULT because a
 *   `notify-send` is a deliberate, user-initiated alert.
 *
 * - **Notification ids are host-allocated, tags are guest-derived.** Android
 *   replaces a notification on a matching `(tag, id)` pair. Using
 *   [NotifySendProvisioner.notificationTag] as the tag with one fixed id makes
 *   `notify-send -r 42` twice *replace* (its documented purpose) while different
 *   summaries stack — and because the tag is namespaced per workspace, one
 *   workspace can never overwrite another's notification. The id is a constant,
 *   never a guest value, so a guest cannot collide with the FGS notification
 *   (id 1), an import (2) or a download (3).
 *
 * - **Every notification is attributed to its workspace and is not ongoing.**
 *   The workspace display name goes in the subtext, so a user with two
 *   workspaces can tell which one is talking; nothing a guest posts is
 *   `setOngoing`, so all of it is dismissible. Tapping opens the workspace
 *   (the existing `linexa://workspace/{id}` deep link, §1.97/W4) rather than any
 *   guest-controlled target — a guest supplies no intent, no URL and no action.
 */
@Singleton
class AndroidGuestNotificationBridge @Inject constructor(
    private val application: Application
) : GuestNotificationBridge {

    private val manager get() = NotificationManagerCompat.from(application)

    /** Tags posted per workspace, so [cancelWorkspace] can withdraw exactly those. */
    private val postedTags = mutableMapOf<String, MutableSet<String>>()
    private val postedLock = Any()

    /** Distinct request codes for the content PendingIntents. */
    private val requestCodes = AtomicInteger(REQUEST_CODE_BASE)

    /** Workspace display names, supplied by the supervisor's callers if known. */
    private val displayNames = mutableMapOf<String, String>()

    override fun canPost(): Boolean {
        ensureChannel()
        if (!manager.areNotificationsEnabled()) return false
        // A channel the user blocked individually would silently swallow every
        // post, which is exactly the case Blocked exists to report.
        val channel = application.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Remember a workspace's display name so posted notifications can be
     * attributed to it. Optional — the id is used until this is called.
     */
    override fun setDisplayName(workspaceId: String, displayName: String) {
        synchronized(postedLock) { displayNames[workspaceId] = displayName }
    }

    override fun post(workspaceId: String, notification: GuestNotification) {
        ensureChannel()
        val tag = NotifySendProvisioner.notificationTag(workspaceId, notification)
        val built = build(workspaceId, notification)
        try {
            manager.notify(tag, NOTIFICATION_ID, built)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between canPost() and here.
            Logger.w(TAG, "$workspaceId: notification refused by the system: ${e.message}")
            return
        }
        synchronized(postedLock) {
            postedTags.getOrPut(workspaceId) { mutableSetOf() }.add(tag)
        }
        Logger.d(TAG, "$workspaceId: posted guest notification '${notification.summary}' (tag=$tag)")
    }

    override fun cancelWorkspace(workspaceId: String) {
        val tags = synchronized(postedLock) { postedTags.remove(workspaceId).orEmpty().toList() }
        tags.forEach { tag ->
            runCatching { manager.cancel(tag, NOTIFICATION_ID) }
                .onFailure { Logger.w(TAG, "$workspaceId: could not cancel $tag: ${it.message}") }
        }
        if (tags.isNotEmpty()) {
            Logger.i(TAG, "$workspaceId: withdrew ${tags.size} guest notification(s)")
        }
    }

    private fun build(workspaceId: String, notification: GuestNotification): Notification {
        val displayName = synchronized(postedLock) { displayNames[workspaceId] } ?: workspaceId
        // Tapping opens the workspace — never a guest-supplied target.
        val openIntent = Intent(application, MainActivity::class.java).apply {
            data = Uri.parse("${RuntimeNotificationContent.DEEP_LINK_SCHEME}://workspace/$workspaceId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            application,
            requestCodes.getAndIncrement(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.summary)
            .setSubText(notification.appName?.let { "$displayName · $it" } ?: displayName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            // Never ongoing: everything a guest posts must be dismissible.
            .setOngoing(false)
            .setPriority(notification.urgency.toPriority())
        notification.body?.let {
            builder.setContentText(it)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }
        // notify-send's -t is a display duration; Android's analog is a timeout
        // after which the system withdraws it (ignored below API 26 by compat).
        notification.expireMillis?.let { builder.setTimeoutAfter(it) }
        return builder.build()
    }

    private fun ensureChannel() {
        val system = application.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_ID) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESCRIPTION
            }
        )
    }

    private fun NotificationUrgency.toPriority(): Int = when (this) {
        NotificationUrgency.LOW -> NotificationCompat.PRIORITY_LOW
        NotificationUrgency.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
        NotificationUrgency.CRITICAL -> NotificationCompat.PRIORITY_HIGH
    }

    companion object {
        const val TAG = "GuestNotify"

        /** Separate from the runtime FGS channel — see the class kdoc. */
        const val CHANNEL_ID = "workspace_notifications"
        const val CHANNEL_NAME = "Workspace notifications"
        const val CHANNEL_DESCRIPTION = "Notifications sent by programs running inside a workspace"

        /**
         * Fixed id; uniqueness comes from the tag. Deliberately distinct from
         * RuntimeService (1), ImportService (2) and DownloadService (3).
         */
        const val NOTIFICATION_ID = 100

        /** Base for content-intent request codes (kept clear of the runtime's). */
        const val REQUEST_CODE_BASE = 5000
    }
}
