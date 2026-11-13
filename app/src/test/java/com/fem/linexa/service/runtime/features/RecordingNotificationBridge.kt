package com.fem.linexa.service.runtime.features

/**
 * §1.111 — a recording [GuestNotificationBridge] for the JVM tests.
 *
 * Lives next to [RecordingGuestFeatureHost] for the same reason: it fakes a
 * *seam* of the feature layer (the Android posting half), so the spool → parse →
 * post → cancel chain can be asserted without a device.
 */
open class RecordingNotificationBridge(
    var postingAllowed: Boolean = true
) : GuestNotificationBridge {

    /** Every posted notification, in order, as (workspaceId, notification). */
    val posted = mutableListOf<Pair<String, GuestNotification>>()

    /** Workspaces whose notifications were withdrawn, in order. */
    val cancelled = mutableListOf<String>()

    /** Display names supplied per workspace (last write wins, as the real one). */
    val displayNames = mutableMapOf<String, String>()

    var postThrows: Exception? = null

    override fun canPost(): Boolean = postingAllowed

    override fun setDisplayName(workspaceId: String, displayName: String) {
        displayNames[workspaceId] = displayName
    }

    override fun post(workspaceId: String, notification: GuestNotification) {
        postThrows?.let { throw it }
        posted += workspaceId to notification
    }

    override fun cancelWorkspace(workspaceId: String) {
        cancelled += workspaceId
    }

    /** Summaries posted for [workspaceId], in order — the common assertion. */
    fun summariesFor(workspaceId: String): List<String> =
        posted.filter { it.first == workspaceId }.map { it.second.summary }
}
