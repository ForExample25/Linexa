package com.fem.linexa.service.runtime

/**
 * §1.166 — "is the runtime's foreground notification actually on screen, and
 * does it still say the right thing?" bookkeeping for [RuntimeService].
 *
 * **The bug this exists for.** The foreground notification is posted by
 * `startForeground` at `ACTION_START`. On API 33+ that call succeeds whether or
 * not `POST_NOTIFICATIONS` has been granted — the service goes foreground, but
 * nothing appears. §W6 added a single recovery trigger: the
 * `rememberLauncherForActivityResult` callback in `WorkspaceNavGraph` sends
 * [RuntimeService.ACTION_REFRESH_NOTIFICATION] when the dialog resolves
 * `granted`. That is *one* trigger, on *one* code path, and it is the only one —
 * so the notification is lost for the rest of the session whenever it does not
 * fire:
 *
 *  - the grant arrives from **Settings → Access** (`AccessSettingsGroup`)
 *    rather than from the workspace-entry dialog, which has no callback here at
 *    all;
 *  - the launcher callback is lost because the activity was recreated (or the
 *    process died) while the system dialog was up;
 *  - the notification was posted correctly and then **cleared by the system**
 *    (or by an OEM notification-manager) while the workspace kept running.
 *
 * The fix is to stop treating the grant as a one-shot event and instead
 * re-verify at every lifecycle point where the notification is expected to be
 * visible (`WorkspaceNavGraph`'s `ON_RESUME`, plus every existing content
 * refresh). That turns a missed edge into at most one delayed repost.
 *
 * **Why this class.** Re-checking on every resume means the refresh path is now
 * called often, so it has to be *idempotent*: re-posting identical content on
 * every lifecycle tick is how you get a notification that re-animates, re-sorts
 * itself in the shade, and re-triggers OEM heads-up heuristics. So the decision
 * of whether a `notify()` is actually needed is separated from the Android
 * plumbing that performs it, and lives here: pure, stateful-but-JVM-testable,
 * with no `Context` and no `NotificationManager`.
 *
 * It deliberately does **not** decide *what* the notification says — that stays
 * in [RuntimeNotificationContent] and [RuntimeService.buildNotification]. This
 * class only compares "what is on screen" against "what should be on screen".
 */
class RuntimeNotificationPresence {

    /**
     * The user-visible identity of one posted notification: everything a
     * refresh could legitimately change. Two [Snapshot]s that compare equal
     * render identically, so re-posting one over the other is pure churn.
     *
     * The channel and importance are absent on purpose — they are fixed for the
     * lifetime of the channel (created once in
     * [RuntimeService.createNotificationChannel]) and cannot be changed by a
     * refresh, so including them could only ever produce false "content
     * changed" verdicts.
     */
    data class Snapshot(
        val workspaceId: String,
        val contentTitle: String,
        val contentText: String,
        val wakeLockActionLabel: String,
        val stopActionLabel: String,
        /** §1.103: the Open deep link's target terminal, which does change. */
        val openTerminalId: String
    )

    /** Why [evaluate] reached its verdict. Logged, and asserted in tests. */
    enum class Reason {
        /** Notifications are blocked/ungranted — a `notify()` would be swallowed. */
        NOTIFICATIONS_DISABLED,

        /** Nothing has been successfully posted yet (or the last post was invalidated). */
        NEVER_POSTED,

        /** Posted earlier, but it is not in the shade any more — dismissed or cleared. */
        NOT_ON_SCREEN,

        /** On screen, but stale: status, terminal count, name or wake-lock label moved. */
        CONTENT_CHANGED,

        /** On screen and already correct. The idempotent no-op case. */
        UNCHANGED
    }

    sealed interface Decision {
        val reason: Reason

        /** Call `notify()`, then [recordPosted]. */
        data class Post(override val reason: Reason) : Decision

        /** Do nothing. */
        data class Skip(override val reason: Reason) : Decision
    }

    /**
     * The last snapshot we know reached the shade, or null when nothing is (or
     * can be) on screen. Only ever written through [recordPosted] / [clear] /
     * the permission-revoked branch of [evaluate].
     */
    private var posted: Snapshot? = null

    /** Exposed for logging and tests; null means "we believe nothing is shown". */
    val postedSnapshot: Snapshot?
        get() = posted

    /**
     * Decide whether [desired] needs to be posted.
     *
     * @param notificationsEnabled `NotificationManagerCompat.areNotificationsEnabled()`
     *   — the POST_NOTIFICATIONS grant on API 33+, and the user's channel/app
     *   block on every API level.
     * @param systemVisible whether [RuntimeService.NOTIFICATION_ID] is currently
     *   among the app's active notifications. Callers that cannot determine this
     *   should pass `true`: the [posted] comparison then still catches content
     *   drift, and this method never *suppresses* a post it would otherwise make.
     *
     * Note the one mutation: a disabled-notifications verdict also forgets
     * [posted]. Losing the grant *is* the notification leaving the screen, so
     * remembering the old snapshot would make the eventual re-grant look
     * [UNCHANGED][Reason.UNCHANGED] and skip the very repost this whole class
     * exists to perform.
     */
    fun evaluate(
        desired: Snapshot,
        notificationsEnabled: Boolean,
        systemVisible: Boolean
    ): Decision {
        if (!notificationsEnabled) {
            posted = null
            return Decision.Skip(Reason.NOTIFICATIONS_DISABLED)
        }
        val last = posted ?: return Decision.Post(Reason.NEVER_POSTED)
        if (!systemVisible) return Decision.Post(Reason.NOT_ON_SCREEN)
        if (last != desired) return Decision.Post(Reason.CONTENT_CHANGED)
        return Decision.Skip(Reason.UNCHANGED)
    }

    /**
     * Record that [snapshot] was handed to the platform. Called after the
     * `notify()` / `startForeground()` returns, never before — a throwing post
     * must not leave us believing something is on screen.
     */
    fun recordPosted(snapshot: Snapshot) {
        posted = snapshot
    }

    /**
     * Forget any posted state: the workspace's foreground session is over
     * ([RuntimeService.onForegroundIdle] removes the notification), or the post
     * could not be performed. The next [evaluate] therefore reports
     * [Reason.NEVER_POSTED] rather than skipping.
     */
    fun clear() {
        posted = null
    }
}
