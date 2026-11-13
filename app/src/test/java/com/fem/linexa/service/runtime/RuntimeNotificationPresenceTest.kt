package com.fem.linexa.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1.166 — the notification-presence decision table.
 *
 * These tests are the unit-level statement of the bug: the runtime's foreground
 * notification could be permanently invisible because the recovery from a late
 * POST_NOTIFICATIONS grant had exactly one trigger. The fix re-checks on every
 * workspace resume, which is only safe if the check is idempotent — so both
 * halves are pinned here: "a grant that arrives after the start posts", and
 * "an already-correct notification is never re-posted".
 */
class RuntimeNotificationPresenceTest {

    private val presence = RuntimeNotificationPresence()

    private fun snapshot(
        workspaceId: String = WORKSPACE,
        contentText: String = "Workspace: Debian (1 terminals, Running)",
        wakeLockActionLabel: String = "Keep awake",
        openTerminalId: String = "T0"
    ) = RuntimeNotificationPresence.Snapshot(
        workspaceId = workspaceId,
        contentTitle = "Linexa Runtime",
        contentText = contentText,
        wakeLockActionLabel = wakeLockActionLabel,
        stopActionLabel = "Stop",
        openTerminalId = openTerminalId
    )

    private fun evaluate(
        desired: RuntimeNotificationPresence.Snapshot = snapshot(),
        enabled: Boolean = true,
        visible: Boolean = true
    ) = presence.evaluate(desired, notificationsEnabled = enabled, systemVisible = visible)

    // ── The bug: a grant that arrives after the workspace already started ──

    @Test
    fun postsOnFirstEvaluationWhenNothingHasBeenPostedYet() {
        val decision = evaluate()

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED), decision)
    }

    @Test
    fun grantArrivingAfterStartPostsWithoutAWorkspaceRestart() {
        // ACTION_START ran while the permission was still denied: startForeground
        // put the service in the foreground but nothing is on screen, so the
        // service records nothing.
        val startup = snapshot(contentText = "Workspace: Debian (0 terminals, Starting)")
        assertEquals(
            RuntimeNotificationPresence.Decision.Skip(Reason.NOTIFICATIONS_DISABLED),
            evaluate(desired = startup, enabled = false)
        )

        // The user grants POST_NOTIFICATIONS. The next lifecycle re-check must
        // post — this is the case that previously stayed hidden for the rest of
        // the session.
        val decision = evaluate(desired = startup, enabled = true)

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED), decision)
    }

    @Test
    fun revokedPermissionForgetsThePostedSnapshotSoARegrantReposts() {
        val shown = snapshot()
        presence.recordPosted(shown)

        // Permission revoked (or the channel blocked) from the system settings.
        assertEquals(
            RuntimeNotificationPresence.Decision.Skip(Reason.NOTIFICATIONS_DISABLED),
            evaluate(desired = shown, enabled = false)
        )
        assertNull(
            "a disabled verdict must forget what was on screen, or the re-grant reads UNCHANGED",
            presence.postedSnapshot
        )

        assertEquals(
            RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED),
            evaluate(desired = shown, enabled = true)
        )
    }

    @Test
    fun repostsWhenTheNotificationWasClearedWhileTheWorkspaceKeptRunning() {
        val shown = snapshot()
        presence.recordPosted(shown)

        val decision = evaluate(desired = shown, visible = false)

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.NOT_ON_SCREEN), decision)
    }

    // ── Idempotence: no flicker from the repeated lifecycle checks ──

    @Test
    fun identicalContentIsANoOpNoMatterHowOftenItIsChecked() {
        val shown = snapshot()
        presence.recordPosted(shown)

        repeat(20) {
            assertEquals(
                "a resume must not repost an already-correct notification",
                RuntimeNotificationPresence.Decision.Skip(Reason.UNCHANGED),
                evaluate(desired = shown)
            )
        }
    }

    @Test
    fun anEqualButNotIdenticalSnapshotIsStillANoOp() {
        // The service rebuilds the snapshot from live state on every refresh, so
        // the instance always differs; only the *values* may not.
        presence.recordPosted(snapshot())

        assertEquals(
            RuntimeNotificationPresence.Decision.Skip(Reason.UNCHANGED),
            evaluate(desired = snapshot())
        )
    }

    // ── Content drift still updates ──

    @Test
    fun statusChangeFromStartingToRunningPosts() {
        presence.recordPosted(snapshot(contentText = "Workspace: Debian (1 terminals, Starting)"))

        val decision = evaluate(desired = snapshot(contentText = "Workspace: Debian (1 terminals, Running)"))

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.CONTENT_CHANGED), decision)
    }

    @Test
    fun terminalCountChangePosts() {
        presence.recordPosted(snapshot(contentText = "Workspace: Debian (1 terminals, Running)"))

        val decision = evaluate(desired = snapshot(contentText = "Workspace: Debian (2 terminals, Running)"))

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.CONTENT_CHANGED), decision)
    }

    @Test
    fun wakeLockLabelChangePosts() {
        presence.recordPosted(snapshot(wakeLockActionLabel = "Keep awake"))

        val decision = evaluate(desired = snapshot(wakeLockActionLabel = "Awake 30m"))

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.CONTENT_CHANGED), decision)
    }

    @Test
    fun openDeepLinkTerminalChangePosts() {
        // §1.103: the Open action targets the last-active terminal, so a switch
        // has to rewrite the PendingIntent even though the visible text is equal.
        presence.recordPosted(snapshot(openTerminalId = "T0"))

        val decision = evaluate(desired = snapshot(openTerminalId = "T1"))

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.CONTENT_CHANGED), decision)
    }

    @Test
    fun aDifferentWorkspacePosts() {
        presence.recordPosted(snapshot(workspaceId = WORKSPACE))

        val decision = evaluate(desired = snapshot(workspaceId = "ws-other"))

        assertEquals(RuntimeNotificationPresence.Decision.Post(Reason.CONTENT_CHANGED), decision)
    }

    // ── Teardown ──

    @Test
    fun clearMakesTheNextEvaluationPostAgain() {
        presence.recordPosted(snapshot())
        presence.clear()

        assertNull(presence.postedSnapshot)
        assertEquals(
            RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED),
            evaluate()
        )
    }

    @Test
    fun restartingTheSameWorkspaceWithIdenticalContentPostsAfterForegroundIdle() {
        val shown = snapshot()
        presence.recordPosted(shown)
        // RuntimeService.onForegroundIdle removes the notification.
        presence.clear()

        assertEquals(
            "a fresh start must post even when the content is byte-identical",
            RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED),
            evaluate(desired = shown)
        )
    }

    @Test
    fun recordPostedIsTheOnlyThingThatMakesTheNextCheckANoOp() {
        val desired = snapshot()
        assertEquals(
            RuntimeNotificationPresence.Decision.Post(Reason.NEVER_POSTED),
            evaluate(desired = desired)
        )
        // Evaluate alone must not mark it posted — the service records only after
        // the platform call returns, so a throwing notify() cannot leave us
        // believing something is on screen.
        assertNull(presence.postedSnapshot)

        presence.recordPosted(desired)
        assertEquals(
            RuntimeNotificationPresence.Decision.Skip(Reason.UNCHANGED),
            evaluate(desired = desired)
        )
    }

    private companion object {
        const val WORKSPACE = "ws-debian"
    }
}

private typealias Reason = RuntimeNotificationPresence.Reason
