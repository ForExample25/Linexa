package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §1.111 — the guest-side consumer of the `notifications` workspace feature
 * toggle: `notify-send` inside the workspace becomes a real Android
 * notification.
 *
 * **Reconciliation, not event handling** — the same contract as
 * [WorkspaceFeatureSupervisor] (§1.109). The single entry point
 * ([reconcileNotifications]) takes the *desired* state and converges to it, so
 * it is correct on a toggle tap, on workspace launch (§1.110), and after a
 * `config.yaml` import that pre-enabled the feature; calling it twice is
 * harmless. Nothing here holds authoritative state — desired = the persisted
 * toggle, actual = "is the shim installed / is a watcher running" — so it cannot
 * drift out of sync with the user's intent.
 *
 * **Why a spool file and not a daemon.** §4 specifies "a small `notify-send` →
 * Android `Notification` shim", and a notification producer is inherently
 * one-shot: the guest half is a *script* that writes one record and exits. That
 * means no guest process to supervise, nothing to leak when the app dies, and
 * zero cost for a workspace that never calls `notify-send`. Contrast `ssh`
 * (§1.109), which genuinely needs a listening daemon and therefore
 * [GuestFeatureHost.startDaemon]. The host side is a bounded poll of the spool
 * directory rather than an inotify watch: `FileObserver` on a PRoot-managed path
 * is unreliable (the guest writes through proot's syscall interception, and the
 * directory can be recreated under us), while a 1-second poll of a normally
 * empty directory is a single `list()` — cheap, and it cannot miss a record
 * because the shim's write-then-rename means a record is only visible once
 * complete.
 *
 * **It cannot touch the Workspace lifecycle.** Its two collaborators are
 * [GuestFeatureHost] (exec only — see §1.109's scope limit) and
 * [GuestNotificationBridge] (post/cancel only). There is no path from here to a
 * pty, a terminal, or a session start/stop (§1.2: the graph owns that).
 *
 * **Fail-open.** Every failure becomes a [NotificationFeatureState] and a log
 * line; nothing throws except [CancellationException]. The feature is an add-on,
 * so a guest whose shim could not be installed keeps its session, and a
 * malformed record is dropped rather than retried forever.
 */
class WorkspaceNotificationSupervisor(
    private val host: GuestFeatureHost,
    private val bridge: GuestNotificationBridge,
    private val scope: CoroutineScope,
    /**
     * Resolves the HOST view of a workspace's spool directory
     * (`{workspace}/rootfs/var/spool/linexa-notify`). Injected rather than
     * derived so this class needs no Android `Application` and stays
     * JVM-testable against a temp folder.
     */
    private val spoolDirFor: (String) -> File,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS
) {

    /** Live spool watchers, keyed by workspace. Guarded by [watcherLock]. */
    private val watchers = mutableMapOf<String, Job>()
    private val watcherLock = Any()

    /**
     * Per-workspace drain locks. Two drains of one spool must never overlap:
     * [consumeRecord] reads a record before deleting it, so concurrent drainers
     * could both read the same file and post it twice. There are genuinely two
     * drainers — the poll tick and the explicit drain [disable] performs — so
     * this is a real interleaving, not a test artifact.
     */
    private val drainLocks = mutableMapOf<String, Any>()

    private fun drainLockFor(workspaceId: String): Any =
        synchronized(watcherLock) { drainLocks.getOrPut(workspaceId) { Any() } }

    /**
     * Name [workspaceId] for the notifications it posts (see
     * [GuestNotificationBridge.setDisplayName]). Fail-open: attribution is a
     * nicety, so a bridge that refuses it must not fail the reconcile that
     * follows.
     */
    fun setDisplayName(workspaceId: String, displayName: String) {
        runCatching { bridge.setDisplayName(workspaceId, displayName) }
            .onFailure { Logger.w(TAG, "$workspaceId: could not set the notification display name: ${it.message}") }
    }

    /**
     * Converge [workspaceId]'s notification bridge to [enabled].
     *
     * Enabling installs (or refreshes) the guest shim and starts the spool
     * watcher; disabling removes the shim, stops the watcher and withdraws the
     * workspace's notifications.
     */
    suspend fun reconcileNotifications(
        workspaceId: String,
        enabled: Boolean
    ): NotificationFeatureState = try {
        if (!enabled) disable(workspaceId) else enable(workspaceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Notification reconcile failed for $workspaceId (enabled=$enabled)", e)
        NotificationFeatureState.Failed(e.message ?: "Unknown error")
    }

    private suspend fun enable(workspaceId: String): NotificationFeatureState {
        if (!host.hasActiveSession(workspaceId)) {
            // Not a failure — the toggle stays on and the launch-path reconcile
            // (§1.110) installs the shim once a guest exists.
            return NotificationFeatureState.Unavailable
        }

        val install = host.execCommand(workspaceId, NotifySendProvisioner.installCommand())
        if (install.exitCode != 0) {
            val detail = install.error.ifBlank { install.output }.trim().take(DETAIL_CHARS)
            Logger.e(TAG, "notify-send shim install failed for $workspaceId (rc=${install.exitCode}): $detail")
            return NotificationFeatureState.Failed("Could not install the notification shim: $detail")
        }

        startWatcher(workspaceId)

        // The shim is installed either way: a user who grants POST_NOTIFICATIONS
        // later gets working notifications without re-enabling the feature. Only
        // the reported state differs, so the UI can explain the silence.
        return if (bridge.canPost()) {
            Logger.i(TAG, "Notifications enabled for $workspaceId (shim at ${NotifySendProvisioner.SHIM_GUEST_PATH})")
            NotificationFeatureState.Active
        } else {
            Logger.w(TAG, "Notifications enabled for $workspaceId but the host cannot post (permission/channel blocked)")
            NotificationFeatureState.Blocked
        }
    }

    /**
     * Remove the guest shim, stop watching, and withdraw this workspace's
     * notifications. Idempotent — correct for "already off", which is what makes
     * it safe to call on every teardown and what reaps a shim left behind by a
     * toggle flipped off while the app was dead.
     */
    private suspend fun disable(workspaceId: String): NotificationFeatureState {
        stopWatcher(workspaceId)
        // Drain what the guest already produced before tearing the shim out, so
        // a notification emitted moments before the toggle flipped isn't lost.
        drainOnce(workspaceId)
        if (host.hasActiveSession(workspaceId)) {
            host.execCommand(workspaceId, NotifySendProvisioner.uninstallCommand())
        }
        bridge.cancelWorkspace(workspaceId)
        Logger.i(TAG, "Notifications disabled for $workspaceId (shim removed, notifications withdrawn)")
        return NotificationFeatureState.Disabled
    }

    /**
     * Release [workspaceId]'s host-side state without touching the guest.
     *
     * Called from the launch path's teardown (§1.110's `onSessionStopping`): by
     * then the guest may already be gone, so running the uninstall would just
     * log a failure. The watcher must stop and the notifications must be
     * withdrawn regardless — a stopped workspace leaving live notifications
     * behind would imply it is still running. The shim deliberately stays in the
     * rootfs: the toggle is still on, so the next session should have it, and
     * [enable] refreshes it anyway.
     */
    fun releaseHostState(workspaceId: String) {
        stopWatcher(workspaceId)
        bridge.cancelWorkspace(workspaceId)
    }

    /**
     * Start (or keep) the spool watcher for [workspaceId]. Idempotent: an
     * already-running watcher is left alone rather than replaced, so a repeated
     * enable cannot end up with two pollers draining the same directory.
     */
    private fun startWatcher(workspaceId: String) {
        synchronized(watcherLock) {
            val existing = watchers[workspaceId]
            if (existing != null && existing.isActive) return
            watchers[workspaceId] = scope.launch(Dispatchers.IO) {
                Logger.i(TAG, "Watching notification spool for $workspaceId")
                while (isActive) {
                    drainOnce(workspaceId)
                    delay(pollIntervalMillis)
                }
            }
        }
    }

    private fun stopWatcher(workspaceId: String) {
        synchronized(watcherLock) { watchers.remove(workspaceId) }?.cancel()
    }

    /**
     * Number of live spool watchers for [workspaceId] — 0 or 1, never more.
     * Visible for tests: "enabling twice does not start a second poller" is a
     * resource invariant that cannot be observed through posted notifications
     * (the per-workspace drain lock makes any number of drainers post a record
     * exactly once), so it is asserted directly.
     */
    internal fun activeWatcherCount(workspaceId: String): Int =
        synchronized(watcherLock) { watchers[workspaceId]?.takeIf { it.isActive } }?.let { 1 } ?: 0

    /**
     * Read every complete record in [workspaceId]'s spool, post it, and delete
     * it. Visible for tests — this is the whole host half of the feature, and
     * driving it directly is what makes the parse → post → delete contract
     * assertable without a live poller.
     *
     * Records are deleted whether or not they parsed: an undecodable record will
     * never become decodable, so keeping it would make the poll leak and log
     * forever. Deletion is also what bounds the spool — a guest looping on
     * `notify-send` cannot grow it faster than one poll drains it, and
     * [NotifySendProvisioner.MAX_RECORD_BYTES] bounds each entry.
     */
    fun drainOnce(workspaceId: String) {
        val dir = try {
            spoolDirFor(workspaceId)
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: could not resolve the notification spool: ${e.message}")
            return
        }
        if (!dir.isDirectory) return

        // Serialized per workspace: the poll tick and an explicit drain (from
        // disable/a UI toggle) can otherwise both read the same record before
        // either deletes it, posting one guest notification twice.
        synchronized(drainLockFor(workspaceId)) {
            val records = dir.listFiles { file ->
                file.isFile && file.name.endsWith(NotifySendProvisioner.RECORD_EXTENSION)
            } ?: return

            // Oldest first so notifications arrive in the order the guest emitted
            // them; bounded per drain so one runaway producer can't monopolize the
            // poll (the remainder is picked up by the next tick).
            records.sortedBy { it.lastModified() }
                .take(MAX_RECORDS_PER_DRAIN)
                .forEach { file -> consumeRecord(workspaceId, file) }
        }
    }

    private fun consumeRecord(workspaceId: String, file: File) {
        try {
            // Read before delete so a failure can't post the same record twice;
            // delete before post so a throwing bridge can't make it loop.
            val bytes = if (file.length() > NotifySendProvisioner.MAX_RECORD_BYTES) {
                Logger.w(TAG, "$workspaceId: dropping oversized notification record (${file.length()} bytes)")
                null
            } else {
                file.readBytes()
            }
            file.delete()

            val notification = bytes
                ?.let { NotifySendProvisioner.decodeRecord(it) }
                ?.let { NotifySendProvisioner.parseNotifySend(it) }
            if (notification == null) {
                if (bytes != null) Logger.w(TAG, "$workspaceId: dropping unusable notification record ${file.name}")
                return
            }
            bridge.post(workspaceId, notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let one bad record stop the drain (or the workspace).
            Logger.w(TAG, "$workspaceId: notification record ${file.name} failed: ${e.message}")
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val TAG = "NotifySupervisor"

        /** Bounded length of a guest error tail carried into a UI reason. */
        const val DETAIL_CHARS = 300

        /**
         * Spool poll interval. The directory is empty in the normal case, so a
         * tick is one `list()`; 1s keeps a `notify-send` feeling immediate
         * without a measurable idle cost.
         */
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L

        /**
         * Records consumed per tick. A guest that emits thousands of
         * notifications is misbehaving; draining a bounded slice per tick keeps
         * the poll's worst case bounded instead of blocking on one burst.
         */
        const val MAX_RECORDS_PER_DRAIN = 32
    }
}

/**
 * §1.111 — observable state of a workspace's Notifications feature. Separate
 * from the persisted toggle for the same reason [SshFeatureState] is (§1.16's
 * rule at feature scope): the toggle is the user's *intent*, this is the actual
 * condition, so a shim that could not be installed — or an Android permission
 * the user denied — is visible instead of silently swallowed.
 */
sealed interface NotificationFeatureState {

    /** The toggle is off; no shim in the guest, nothing watching. */
    data object Disabled : NotificationFeatureState

    /**
     * The toggle is on but the workspace session isn't running, so there is no
     * guest to install the shim into. Not an error — the launch-path reconcile
     * (§1.110) handles it once a session exists.
     */
    data object Unavailable : NotificationFeatureState

    /** Shim installed, spool watched, notifications posting. */
    data object Active : NotificationFeatureState

    /**
     * Shim installed and the spool is watched, but Android will not let the app
     * post (POST_NOTIFICATIONS denied, or the channel was blocked by the user).
     * Deliberately distinct from [Failed]: nothing is broken, the user has to
     * grant something.
     */
    data object Blocked : NotificationFeatureState

    /** Enabling failed; [reason] is user-facing. */
    data class Failed(val reason: String) : NotificationFeatureState
}
