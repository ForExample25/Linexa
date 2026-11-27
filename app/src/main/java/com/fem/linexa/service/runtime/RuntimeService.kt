package com.fem.linexa.service.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fem.linexa.MainActivity
import com.fem.linexa.R
import com.fem.linexa.core.i18n.linexaString
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.desktop.X11SessionManager
import com.fem.linexa.service.runtime.features.GuestFeatureHost
import com.fem.linexa.service.runtime.features.WorkspaceFeatureReconciler
import com.fem.linexa.service.runtime.hostinfo.HostInfoRuntimeBridge
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground Service owning the container runtime lifecycle (§1.4).
 * SessionManager (workspace state bookkeeping) is internal to this service
 * (§1.5). §1.97/W2: per-terminal bookkeeping lives in
 * [WorkspaceTerminalManager] (runtime-owned terminals + [TerminalState]
 * flows, layered above [ContainerRuntime]); the single-terminal UI path
 * opens the workspace's default terminal
 * ([com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID], "T0")
 * through that manager. Process/pty I/O is delegated to [ContainerRuntime]
 * (§1.20) rather than driven directly here — this class' job is Android
 * service lifecycle (foreground notification, Intent actions, binder), not
 * pty plumbing.
 *
 * §1.97/W4: the lifecycle *transitions* (single-active-workspace gate,
 * START/STOP serialization, the one shared teardown path) are
 * delegated to [RuntimeLifecycleManager]. §P0: the workspace-session launch
 * choreography (readiness gate, child-alive check, the long-lived
 * [WorkspaceTerminalManager.awaitSessionEnd] wait, and the generation-guarded
 * unwind that fixes the stale-coroutine race) lives in
 * [WorkspaceSessionSupervisor], which this service feeds with Android side
 * effects through [WorkspaceSessionSupervisor.Host]. §1.97/PT0: the wait is on
 * the workspace-level [WorkspaceTerminalManager.awaitSessionEnd] signal, not
 * the T0 pty's `awaitExit`. That signal completes only on an explicit
 * workspace teardown — so closing the T0 tab AND any terminal exiting on its
 * own (natural T0 exit included) keep the workspace Running and its sibling
 * terminals alive (terminal lifecycle ≠ workspace lifecycle). The foreground
 * notification now carries workspace display name +
 * terminal count and Open / Stop actions (audit §10); its content is built by
 * [RuntimeNotificationContent].
 *
 * Workspace validation gate fix: before this, the launch path handed
 * whatever rootfs path it was given straight to [ContainerRuntime]
 * with no check that the workspace record existed, was marked [WorkspaceStatus][com.fem.linexa.domain.workspace.WorkspaceStatus.Ready],
 * or that the rootfs directory it pointed at actually contained anything.
 * A caller could "launch" a broken/never-installed/emptied workspace and
 * PRoot would either fail deep inside native code or silently do nothing
 * useful. [launchValidator] is now consulted first — this is the
 * service-level backstop: even if a future UI entry point forgets to check
 * readiness itself, this is the one place every launch necessarily passes
 * through, so it's the one place that can't be bypassed.
 */
@AndroidEntryPoint
class RuntimeService : Service(), WorkspaceSessionSupervisor.Host {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var containerRuntime: ContainerRuntime
    @Inject lateinit var workspaceTerminalManager: WorkspaceTerminalManager
    @Inject lateinit var fileManager: WorkspaceFileManager
    @Inject lateinit var workspaceRepository: WorkspaceRepository
    @Inject lateinit var launchValidator: WorkspaceLaunchValidator
    @Inject lateinit var runtimeTrace: RuntimeTrace
    @Inject lateinit var diagnosticLogManager: DiagnosticLogManager
    @Inject lateinit var lifecycleManager: RuntimeLifecycleManager

    /**
     * §1.110: applies the workspace's persisted feature toggles to the guest at
     * launch (and releases them at stop). Injected here and handed to
     * [WorkspaceSessionSupervisor] — the launch path has no [RuntimeBinder], so
     * the feature layer's guest seam is a singleton
     * ([com.fem.linexa.service.runtime.features.RuntimeGuestFeatureHost])
     * rather than a binder capability.
     */
    @Inject lateinit var featureReconciler: WorkspaceFeatureReconciler

    /** §1.110: the shared guest seam the binder delegates its feature methods to. */
    @Inject lateinit var guestFeatureHost: GuestFeatureHost

    /**
     * §1.142: the Workspace-runtime wake lock (§1.100(5)). Toggled by the
     * notification's [ACTION_TOGGLE_WAKE_LOCK] action; released by
     * [RuntimeLifecycleManager]'s single teardown path and by its `shutdown()`
     * from [onDestroy]. This service only *renders* the hold and forwards the
     * user's tap — it owns no release logic of its own, so a stop trigger that
     * never reaches this service still lets go.
     */
    @Inject lateinit var wakeLockManager: WorkspaceWakeLockManager

    /**
     * Host System Information bridge (2026-08-26 track). The service owns its
     * /proc-overlay refresher lifecycle — started here because the foreground
     * service is exactly "the app is doing runtime work", stopped on teardown.
     * No work happens while zero workspaces run (see bridge internals), so a
     * created-but-idle service costs nothing beyond one coroutine suspension.
     */
    @Inject lateinit var hostInfoBridge: HostInfoRuntimeBridge

    /**
     * Transformation spec §6/§29: workspace component health (degradations),
     * exposed through the binder so the UI can render "running but degraded"
     * without logcat.
     */
    @Inject lateinit var healthRegistry: WorkspaceHealthRegistry

    /** Phase 2E-1: service-side X11 lifecycle state the deferred reconcile phase observes. */
    @Inject lateinit var x11SessionManager: X11SessionManager

    private lateinit var binder: RuntimeBinder
    private lateinit var sessionSupervisor: WorkspaceSessionSupervisor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _events = MutableSharedFlow<RuntimeEvent>()
    val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    /**
     * LINEXA-PERF-001: is any Linexa activity started (visible)? The runtime
     * overlay ticker uses this only to choose its cadence — foreground 2 s,
     * background [HostInfoRuntimeBridge.BACKGROUND_REFRESH_MS] — so the
     * always-on cost no longer follows a backgrounded app forever.
     *
     * Initialised `true` on purpose: the service can be created after
     * `MainActivity.onStart`, and guessing "background" would degrade the
     * first visible interval. The first `onActivityStopped` settles it.
     */
    private val appForeground = MutableStateFlow(true)
    private var startedActivities = 0
    private val activityVisibilityCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: android.app.Activity) {
            startedActivities++
            appForeground.value = true
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) appForeground.value = false
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    /**
     * §1.97/W4: display-name cache for the notification content, populated at
     * workspace start from the workspace metadata. The notification must not
     * hit Room on every update, so the resolved name is remembered here.
     */
    private val displayNames = mutableMapOf<String, String>()

    /**
     * §1.166: "what is currently on screen", so the lifecycle-driven refresh
     * ([ACTION_REFRESH_NOTIFICATION], now sent on every workspace `ON_RESUME`)
     * only calls `notify()` when it would actually change something. See
     * [RuntimeNotificationPresence] for why the decision is a separate,
     * JVM-testable object.
     */
    private val notificationPresence = RuntimeNotificationPresence()

    /**
     * Phase 07 (OPT-REL-005): the teardown scope — deliberately NOT
     * [serviceScope]. `serviceScope` is cancelled in [onDestroy] the moment the
     * service is destroyed, and the original P0-2 bug was exactly a cleanup
     * coroutine launched into it and cancelled before it ran; the first fix
     * (runBlocking on Main) stopped the leak but blocked the main thread for up
     * to [TEARDOWN_TIMEOUT_MS] on every destroy. This scope runs the bounded
     * teardown on IO, survives `serviceScope.cancel()`, and self-cancels via
     * `invokeOnCompletion` once the teardown job ends — so it cannot outlive
     * the work it exists for, and nothing UI-facing can be launched into it.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        binder = RuntimeBinder(
            sessionManager,
            containerRuntime,
            fileManager,
            workspaceTerminalManager,
            guestFeatureHost,
            events,
            healthRegistry
        )
        // §P0: the session launch choreography (readiness gate, lifecycle
        // transition, child-alive check, awaitSessionEnd, generation-guarded
        // unwind) lives in WorkspaceSessionSupervisor so it is JVM-testable
        // against the fake-PRoot harness; this service supplies the Android
        // side effects through the Host callbacks below.
        sessionSupervisor = WorkspaceSessionSupervisor(
            workspaceRepository,
            launchValidator,
            sessionManager,
            workspaceTerminalManager,
            lifecycleManager,
            runtimeTrace,
            diagnosticLogManager,
            _events,
            featureReconciler,
            // Phase 2E-1: the deferred feature-reconcile phase observes this
            // service-side X11 state machine to run after a desktop start
            // settles (X11 READY/FAILED) instead of inside its exec window.
            x11SessionManager,
            this
        )
        createNotificationChannel()
        observeTerminalStatesForNotification()
        // 2026-08-30 (Issues 4+7): keep the Open action's terminal target in
        // sync with the terminal the user is actually viewing. Without this
        // the deep link kept its build-time terminal id, so a tap while the
        // user was already on the terminal screen silently switched them to
        // an older session (the stale-target flash). The refresh is
        // idempotent: RuntimeNotificationPresence compares the full snapshot
        // (including openTerminalId) and skips the post when nothing changed,
        // and the StateFlow itself only emits when the recorded terminal
        // actually changes.
        observeLastActiveTerminalForNotification()
        application.registerActivityLifecycleCallbacks(activityVisibilityCallbacks)
        // Host System Information: keep guest-visible /proc overlays ticking
        // while any session runs. Scoped to the service lifetime — never app
        // lifetime — so leaving runtime mode leaves zero background work.
        // PERF-013: the job now suspends until a session is Running.
        // LINEXA-PERF-001: foreground/background cadence comes from
        // [appForeground]; the loop still stops entirely with zero sessions.
        hostInfoBridge.startOverlayTicker(
            sessionStates = sessionManager.sessionStates,
            foreground = appForeground
        )
        // PERF-003: no eager startup sweep here. It used to run on this
        // service's Main dispatcher the moment the service was created — and
        // the Activity tab created the service just by opening it — so the
        // sweep's filesystem scan competed with the destination's first frame
        // for no session at all. Every start path already ensures the sweep
        // before opening a workspace (RuntimeLifecycleManager.requestStart →
        // runStartupSweepLocked), which is the only point where its result can
        // matter; `startupSweep()` stays available for tests and recovery.
        Logger.i("RuntimeService", "RuntimeService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: return START_NOT_STICKY
                // Phase 9B (OPT-ARCH-002): the rootfs path is ALWAYS derived from
                // the workspace id — [WorkspaceFileManager] is the single source of
                // truth for the layout, and no Intent extra can override it. (This
                // is the §P0-fix audit-G11 derivation promoted from a recovery
                // path to THE path: the extra no longer exists to be missing.)
                val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
                Logger.i(
                    "TerminalChain",
                    "CHAIN: onStartCommand START workspaceId=$workspaceId rootfs=$rootfsPath (derived from id)"
                )
                runtimeTrace.newRun(workspaceId, "Workspace runtime startup chain")
                runtimeTrace.record(workspaceId, "RuntimeService.onStartCommand START")
                startForegroundNotification(workspaceId)
                launchSession(workspaceId, rootfsPath)
            }
            ACTION_STOP -> {
                val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: return START_NOT_STICKY
                stopWorkspace(workspaceId)
            }
            ACTION_REFRESH_NOTIFICATION -> {
                val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: return START_NOT_STICKY
                // §W6: the foreground notification is posted by startForeground at
                // ACTION_START — BEFORE the POST_NOTIFICATIONS dialog resolves — so
                // on API 33+ a first-run grant leaves it hidden. §W6 recovered from
                // that with a single trigger (the permission-launcher callback),
                // which is exactly one code path and therefore lost the
                // notification for the rest of the session whenever it did not
                // fire: a grant made from Settings → Access, an activity/process
                // death while the system dialog was up, or the notification being
                // cleared by the system afterwards.
                //
                // §1.166: this is now a *lifecycle* refresh — WorkspaceNavGraph
                // re-sends it on every ON_RESUME of the workspace graph, so any
                // missed grant recovers at the next resume instead of never. The
                // active-workspace guard stays (a stale trigger must not resurrect
                // a notification for an already-closed workspace) and
                // [updateNotification] is now idempotent, so the extra calls cost
                // one comparison and post nothing.
                if (lifecycleManager.activeWorkspaceId.value == workspaceId) {
                    updateNotification(workspaceId)
                }
            }
            ACTION_TOGGLE_WAKE_LOCK -> {
                val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: return START_NOT_STICKY
                // §1.142: the wake lock is a Workspace-runtime feature, so it is
                // only offered for the workspace that currently owns the runtime.
                // A stale PendingIntent (the notification of an already-stopped
                // workspace, still on screen) must not be able to take a lock for
                // a workspace with no guest to keep awake.
                if (lifecycleManager.activeWorkspaceId.value == workspaceId) {
                    val hold = wakeLockManager.toggle(workspaceId)
                    Logger.i(
                        "RuntimeService",
                        "Wake lock toggled for $workspaceId → ${if (hold != null) "held" else "released"}"
                    )
                    runtimeTrace.record(
                        workspaceId,
                        "Wake lock ${if (hold != null) "acquired" else "released"} (user action)"
                    )
                    updateNotification(workspaceId)
                } else {
                    Logger.w("RuntimeService", "Ignoring wake-lock toggle for inactive workspace $workspaceId")
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android Process-Lifecycle Hardening (§10): the task was removed (the
     * user swiped the app away from Recents). The decision is **explicit
     * and deterministic** instead of the implicit default:
     *
     * - a workspace is active → the foreground service (and its runtime)
     *   SURVIVES the task removal, and the notification is refreshed so the
     *   user still has a visible, actionable stop affordance. Android keeps
     *   a started foreground service alive across task removal; killing the
     *   runtime here would be a silent data-loss surprise for a user who
     *   expects the swipe only to clear the UI.
     * - no workspace is active → the service is idle; stop it instead of
     *   lingering as an empty foreground process.
     *
     * Note this is the swipe-away path ONLY: the notification's Stop action
     * and ACTION_STOP remain the user's explicit "stop the runtime" verbs.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = lifecycleManager.activeWorkspaceId.value
        Logger.i("RuntimeService", "onTaskRemoved: activeWorkspace=$active — ${if (active != null) "foreground service persists" else "idle, stopping"}")
        if (active != null) {
            runtimeTrace.record(active, "onTaskRemoved: service persists with the running workspace")
            updateNotification(active)
        } else {
            notificationPresence.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Start the workspace session. §P0: the full choreography — readiness gate,
     * lifecycle transition ([RuntimeLifecycleManager.requestStart]), the §1.76
     * child-alive check, the long-lived [WorkspaceTerminalManager.awaitSessionEnd]
     * wait, and the generation-guarded unwind (the §P0 stale-coroutine race
     * fix) — lives in [WorkspaceSessionSupervisor] so it is JVM-testable against
     * the fake-PRoot harness. This service supplies the Android side effects
     * through the [WorkspaceSessionSupervisor.Host] callbacks.
     */
    private fun launchSession(workspaceId: String, rootfsPath: String) {
        sessionSupervisor.launch(serviceScope, workspaceId, rootfsPath)
    }

    /**
     * ACTION_STOP handler. The manager's shared teardown path does the work
     * (idempotent, single-active-aware); the running session's `finally`
     * observes the cleared gate and tears the service's foreground state down.
     */
    private fun stopWorkspace(workspaceId: String) {
        serviceScope.launch {
            Logger.i("RuntimeService", "Stopping workspace: $workspaceId")
            runtimeTrace.record(workspaceId, "RuntimeService.stopWorkspace ENTER")
            lifecycleManager.requestStop(workspaceId)
        }
    }

    // ── §P0 WorkspaceSessionSupervisor.Host: the Android side effects the
    // pure supervisor cannot perform. ──

    override fun onDisplayName(workspaceId: String, displayName: String) {
        displayNames[workspaceId] = displayName
    }

    override fun onDisplayNameForgotten(workspaceId: String) {
        displayNames.remove(workspaceId)
    }

    override fun onSessionRunning(workspaceId: String) {
        updateNotification(workspaceId)
    }

    override fun onForegroundIdle() {
        // §1.166: the notification is being removed, so forget what was on
        // screen. Without this a later start of the same workspace with the same
        // content would compare equal to the stale snapshot and post nothing.
        notificationPresence.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── §1.97/W4 foreground notification (Open / Stop) ──

    /**
     * Refresh the foreground notification (startForeground path) — called as
     * early as possible on ACTION_START so the service is in the
     * foreground within the system window; content says "Starting".
     *
     * §1.166: unconditional, and deliberately so — `startForeground` is a
     * *service-lifecycle* obligation with a hard system deadline, not a
     * notification refresh, so it is never skipped by the presence check. The
     * post is recorded only when notifications are actually enabled: on API 33+
     * without the grant this call still puts the service in the foreground but
     * shows nothing, and recording it would make the later lifecycle refresh
     * read UNCHANGED and skip the repost that recovers the grant.
     */
    private fun startForegroundNotification(workspaceId: String) {
        val snapshot = notificationSnapshot(workspaceId, RuntimeNotificationContent.statusStarting(this))
        startForeground(NOTIFICATION_ID, buildNotification(snapshot))
        if (notificationsEnabled()) {
            notificationPresence.recordPosted(snapshot)
        } else {
            notificationPresence.clear()
            Logger.w(
                "RuntimeService",
                "startForeground for $workspaceId posted while notifications are disabled — " +
                    "the notification is hidden until the grant arrives (recovered on resume, §1.166)"
            )
        }
    }

    /**
     * Refresh the already-shown notification (NotificationManager.notify) — used
     * once the session is Running, whenever the active workspace's terminal
     * count changes (audit §10: "update via notify on terminal/state changes"),
     * and by [ACTION_REFRESH_NOTIFICATION] on every workspace resume (§1.166).
     * The status reflects the current session state so a refresh during
     * Starting still reads "Starting".
     *
     * §1.166: idempotent. The refresh is now lifecycle-driven, so it is called
     * far more often than the content changes; posting identical content would
     * re-animate the notification, re-sort it in the shade and re-trigger OEM
     * heads-up heuristics on every resume. [RuntimeNotificationPresence] decides,
     * and the common case is a single data-class comparison and no platform call.
     */
    private fun updateNotification(workspaceId: String) {
        val status = if (sessionManager.getState(workspaceId) is RuntimeState.Running) {
            RuntimeNotificationContent.statusRunning(this)
        } else {
            RuntimeNotificationContent.statusStarting(this)
        }
        val desired = notificationSnapshot(workspaceId, status)
        val decision = notificationPresence.evaluate(
            desired = desired,
            notificationsEnabled = notificationsEnabled(),
            systemVisible = isNotificationOnScreen()
        )
        if (decision !is RuntimeNotificationPresence.Decision.Post) return

        Logger.i("RuntimeService", "Posting runtime notification for $workspaceId (${decision.reason})")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(desired))
        notificationPresence.recordPosted(desired)
    }

    /**
     * The user-visible identity of the notification as it should look right now
     * (§1.166). Split out of [buildNotification] so the "does this differ from
     * what is on screen" comparison reads the same values the builder renders —
     * a snapshot derived independently would drift.
     */
    private fun notificationSnapshot(
        workspaceId: String,
        status: String
    ): RuntimeNotificationPresence.Snapshot {
        val displayName = displayNames[workspaceId] ?: workspaceId
        val terminalCount = workspaceTerminalManager.terminalStates.value[workspaceId]?.size ?: 0
        return RuntimeNotificationPresence.Snapshot(
            workspaceId = workspaceId,
            contentTitle = RuntimeNotificationContent.contentTitle(this),
            contentText = RuntimeNotificationContent.contentText(this, displayName, terminalCount, status),
            wakeLockActionLabel = RuntimeNotificationContent.wakeLockActionLabel(
                this,
                wakeLockManager.isHeldBy(workspaceId),
                wakeLockManager.remainingMs()
            ),
            stopActionLabel = RuntimeNotificationContent.stopActionLabel(this),
            openTerminalId = workspaceTerminalManager.lastActiveTerminalId(workspaceId)
        )
    }

    /**
     * §1.166: the POST_NOTIFICATIONS grant on API 33+ *and* the user's app/channel
     * block on every API level, in one query. `areNotificationsEnabled` is the
     * right predicate rather than `checkSelfPermission`: a granted permission with
     * a blocked channel still shows nothing, and pre-33 has no permission to check.
     */
    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    /**
     * §1.166: is [NOTIFICATION_ID] still in the shade? This is what distinguishes
     * "already correct, skip" from "was cleared by the system while the workspace
     * kept running, repost".
     *
     * Fails **open** (returns true) when the platform will not say: `true` means
     * "assume it is still there", which downgrades the verdict to a content
     * comparison rather than forcing a post. A fail-closed default would repost on
     * every single resume — precisely the flicker this change is avoiding.
     */
    private fun isNotificationOnScreen(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return true
        return runCatching {
            manager.activeNotifications.any { it.id == NOTIFICATION_ID }
        }.getOrDefault(true)
    }

    private fun buildNotification(snapshot: RuntimeNotificationPresence.Snapshot): Notification {
        val workspaceId = snapshot.workspaceId
        // §1.103: Open restores the last-active terminal session — the deep
        // link carries the terminal id (lastActiveTerminalId falls back to T0)
        // so the tap lands on the Terminal screen, not the workspace main.
        val terminalId = snapshot.openTerminalId

        // Open / return to the workspace's last-active terminal: deep-link
        // into MainActivity → the workspace/{id}?terminalId=… route
        // (FLAG_IMMUTABLE per targetSdk 34).
        val openIntent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(RuntimeNotificationContent.openDeepLink(workspaceId, terminalId))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            OPEN_NOTIFICATION_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop: self-send ACTION_STOP so it works from the notification without
        // the app being foreground (audit §10) — the manager's shared teardown
        // path, identical to a programmatic stop.
        val stopPending = PendingIntent.getService(
            this,
            STOP_NOTIFICATION_REQUEST_CODE,
            stopIntent(this, workspaceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // §1.142: the wake-lock toggle. Self-sent like Stop so it works with the
        // app backgrounded, and its label carries the remaining time on the
        // bounded hold — the expiry is silent otherwise, and a user who cannot
        // see the ceiling experiences it as the feature randomly failing.
        val wakeLockPending = PendingIntent.getService(
            this,
            WAKE_LOCK_NOTIFICATION_REQUEST_CODE,
            toggleWakeLockIntent(this, workspaceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(snapshot.contentTitle)
            .setContentText(snapshot.contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, snapshot.wakeLockActionLabel, wakeLockPending)
            .addAction(0, snapshot.stopActionLabel, stopPending)
            .build()
    }

    /** §1.97/W4: keep the notification's terminal count current while running. */
    private fun observeTerminalStatesForNotification() {
        serviceScope.launch {
            workspaceTerminalManager.terminalStates.collect { states ->
                val active = lifecycleManager.activeWorkspaceId.value
                if (active != null && sessionManager.getState(active) is RuntimeState.Running) {
                    updateNotification(active)
                }
            }
        }
    }

    /**
     * 2026-08-30 (Issues 4+7): same refresh shape as
     * [observeTerminalStatesForNotification], driven by the last-active
     * terminal record instead of terminal states. The Open deep link embeds
     * `lastActiveTerminalId`, so the record changing is exactly when the
     * notification's PendingIntent data must be rebuilt. Gated on the active
     * runtime like every other refresh, and idempotent through
     * [RuntimeNotificationPresence] — a re-record of the same terminal is
     * conflated by the StateFlow and a content-identical notification is
     * never reposted.
     */
    private fun observeLastActiveTerminalForNotification() {
        serviceScope.launch {
            workspaceTerminalManager.lastActiveTerminals.collect {
                val active = lifecycleManager.activeWorkspaceId.value
                if (active != null && sessionManager.getState(active) is RuntimeState.Running) {
                    updateNotification(active)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            linexaString(R.string.runtime_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = linexaString(R.string.runtime_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { application.unregisterActivityLifecycleCallbacks(activityVisibilityCallbacks) }
        // Stop the /proc overlay refresher BEFORE workspace teardown so its
        // last tick cannot race the rootfs being torn down underneath it.
        hostInfoBridge.stopOverlayTicker()
        // P0-2 fix (ADR-0006), amended by Phase 07 (OPT-REL-005). History:
        // the original code launched cleanup into serviceScope and cancelled
        // it before it ran (every proot process and tmux server leaked); the
        // first fix ran teardown synchronously on Main (runBlocking, 3 s
        // budget) — correct about not leaking, but it blocked the main thread
        // for up to the whole budget on every service destroy, which is an
        // ANR-class cost on the UI thread. The teardown is still bounded and
        // still runs the manager's ONE shared path, just off Main:
        //
        // 1. Write the shutdown markers SYNCHRONOUSLY first. Android may kill
        //    the process the moment onDestroy returns, so the durable record
        //    of "teardown pending for these workspaces" must be on disk before
        //    this method returns — the next process's startup sweep consumes
        //    it (RuntimeLifecycleManager.consumeShutdownMarkers).
        // 2. Launch the bounded teardown on cleanupScope (IO), NOT
        //    serviceScope — serviceScope is cancelled below and must never
        //    take the teardown with it (the original P0-2 leak). cleanupScope
        //    self-cancels via invokeOnCompletion once the job ends.
        // 3. Cancel serviceScope as before: Main-scope notification/UI work
        //    has no business outliving the service.
        lifecycleManager.writeShutdownMarkers()
        cleanupScope
            .launch { withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { lifecycleManager.shutdown() } }
            .invokeOnCompletion { cleanupScope.cancel() }
        serviceScope.cancel()
        Logger.i("RuntimeService", "RuntimeService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "runtime_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.fem.linexa.ACTION_START_RUNTIME"
        const val ACTION_STOP = "com.fem.linexa.ACTION_STOP_RUNTIME"
        const val ACTION_REFRESH_NOTIFICATION = "com.fem.linexa.ACTION_REFRESH_NOTIFICATION"

        /** §1.142: the notification's wake-lock toggle (§1.100(5)). */
        const val ACTION_TOGGLE_WAKE_LOCK = "com.fem.linexa.ACTION_TOGGLE_WAKE_LOCK"
        const val EXTRA_WORKSPACE_ID = "workspace_id"

        /** §1.97/W4: distinct PendingIntent request codes for the Open/Stop actions. */
        const val OPEN_NOTIFICATION_REQUEST_CODE = 1001
        const val STOP_NOTIFICATION_REQUEST_CODE = 1002

        /**
         * §1.142: distinct from Open/Stop — a shared request code would let
         * `FLAG_UPDATE_CURRENT` rewrite the Stop action's intent into a wake-lock
         * toggle, so tapping Stop would keep the phone awake instead.
         */
        const val WAKE_LOCK_NOTIFICATION_REQUEST_CODE = 1003

        /** P0-2 (ADR-0006): bounded timeout for synchronous teardown in onDestroy. */
        const val TEARDOWN_TIMEOUT_MS = 3_000L

        /**
         * Phase 9B (OPT-ARCH-002): the workspace ID is the ONLY rootfs
         * identity crossing a process boundary. The rootfs path is derived
         * inside the service ([WorkspaceFileManager.getRootfsPath] — the
         * single path authority); no Intent extra carries it, so a start can
         * never be pointed at a path other than the one the ID derives to.
         */
        fun startIntent(context: Context, workspaceId: String): Intent {
            return Intent(context, RuntimeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            }
        }

        fun stopIntent(context: Context, workspaceId: String): Intent {
            return Intent(context, RuntimeService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            }
        }

        /**
         * §1.142: toggle the Workspace-runtime wake lock (§1.100(5)). Self-sent
         * from the foreground notification's action, so it works with the app
         * backgrounded — the same mechanism as Stop. Ignored by
         * [onStartCommand] unless [workspaceId] is still the active runtime.
         */
        fun toggleWakeLockIntent(context: Context, workspaceId: String): Intent {
            return Intent(context, RuntimeService::class.java).apply {
                action = ACTION_TOGGLE_WAKE_LOCK
                putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            }
        }

        /**
         * §W6/§1.166: re-verify the foreground notification. Originally a
         * one-shot recovery for a late POST_NOTIFICATIONS grant (the notification
         * posted by `startForeground` before the grant stays hidden on API 33+);
         * §1.166 makes it the *lifecycle* re-check that `WorkspaceNavGraph` sends
         * on every `ON_RESUME` of the workspace graph, so a grant made anywhere
         * (system dialog, Settings → Access) — or a notification cleared by the
         * system while the workspace kept running — recovers at the next resume
         * instead of never.
         *
         * Safe to send repeatedly: it just re-delivers `onStartCommand`, is
         * ignored unless [workspaceId] still owns the runtime, and the refresh it
         * triggers posts nothing when the notification is already correct.
         */
        fun refreshNotificationIntent(context: Context, workspaceId: String): Intent {
            return Intent(context, RuntimeService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
                putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            }
        }
    }
}
