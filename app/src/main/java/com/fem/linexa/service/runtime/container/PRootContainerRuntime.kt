package com.fem.linexa.service.runtime.container

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.filesystem.processHostPid
import android.system.Os
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.resolveFeatureToggles
import com.fem.linexa.domain.hostinfo.HostInfoKey
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.FEATURE_GPU
import com.fem.linexa.domain.runtime.FEATURE_STORAGE
import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.service.runtime.container.root.RootExecBridge
import com.fem.linexa.service.installer.GuestRootfsInitializer
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.rootfs.DeclaredInit
import com.fem.linexa.service.rootfs.RootFsMetadata
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.PRootLauncher.LaunchSpec
import com.fem.linexa.service.runtime.hostinfo.HostInfoRuntimeBridge
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionFactory
import com.fem.linexa.service.workspaceconfig.WorkspaceConfig
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.fem.linexa.di.SessionCreationDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * PRoot-backed [ContainerRuntime] (§1.20). Delegates argv/env construction
 * to [PRootLauncher] and pty allocation to [PtySessionFactory] — this class
 * only owns per-(workspace, terminal) session bookkeeping, per-workspace
 * config.yaml overrides (env §1.27, launch shell/args + extra binds §1.73 —
 * all routed through the same fail-open load and the same typed-argv
 * discipline, never shell-interpreted), the one-shot [exec] path, and making
 * sure the bootstrap layout is in place before anything is launched
 * (verification fix #1, 2026-08-02).
 *
 * §1.97/W1: sessions are keyed by [TerminalKey] (workspaceId, terminalId).
 * W1 keeps the single-terminal call path (default "T0"); each terminal is
 * an independent proot process with its own PROOT_TMP_DIR scope.
 */
@Singleton
class PRootContainerRuntime @Inject constructor(
    private val launcher: PRootLauncher,
    private val ptySessionFactory: PtySessionFactory,
    private val fileManager: WorkspaceFileManager,
    private val configParser: WorkspaceConfigParser,
    private val bootstrapManager: BootstrapManager,
    private val runtimeTrace: RuntimeTrace,
    private val guestRootfsInitializer: GuestRootfsInitializer,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceRootFsMetadata: WorkspaceRootFsMetadata,
    /**
     * Host System Information bridge (2026-08-26 track): the Android-host →
     * RootFS seam this runtime is the sole launch authority for. Owns (a) the
     * host-backed /proc overlay refresh layered after the R-6 initializer's
     * baseline snapshots and (b) the selective `LINEXA_HOST_*` env injection
     * honoring config.yaml allowlists. Kept OUT of [PRootLauncher] on purpose:
     * the launcher stays pure argv/env construction with its own pinned test
     * surface, and every path that needs host info already passes through here.
     */
    private val hostInfoBridge: HostInfoRuntimeBridge,
    /**
     * Transformation spec §6/§8: the workspace component-health registry the
     * daemon exit watchers report unexpected daemon deaths to (the workspace
     * then surfaces as Degraded in RuntimeState.Running.health).
     */
    private val healthRegistry: WorkspaceHealthRegistry,
    /**
     * Transformation spec §6/§8: session-state owner the watchers notify so a
     * daemon death re-aggregates the running workspace's health immediately
     * (not only at the next reconcile).
     */
    private val sessionManager: SessionManager,
    /**
     * 2026-08-30 (session-lifecycle fix): the dispatcher `ptySessionFactory.create`
     * is dispatched onto — the app's main-Looper dispatcher in production
     * ([com.fem.linexa.di.SessionCreationDispatcher]). The vendored
     * `TerminalSession` constructor captures the constructing thread's Looper
     * (no-arg `Handler()` → `mMainThreadHandler`) and drives every emulator
     * state write through it, so construction MUST happen on the main-Looper
     * thread no matter which dispatcher the CALLER arrived on (the exit-driven
     * replacement open arrived on a `Dispatchers.Default` worker and crashed
     * the constructor). Injected so JVM tests can substitute a plain dispatcher.
     */
    @SessionCreationDispatcher
    private val sessionCreationDispatcher: CoroutineDispatcher,
    /**
     * 2026-09 GPU work: the workspace GPU feature's host-side owner. Consulted
     * by the three launch sites (session/daemon/exec) for the guest env and
     * the host `virgl_test_server_android` process; torn down by
     * [closeWorkspaceSessions] and swept for stale processes by
     * [startupRecoverySweep]. Launch-path feature like Shared Storage — the
     * toggle is read here (`isGpuEnabled`), not by the post-launch reconciler,
     * because the guest env must be part of the PRoot argv/env itself.
     */
    private val gpuSupervisor: com.fem.linexa.service.runtime.graphics.WorkspaceGpuSupervisor,
    /**
     * Issue #2 (2026-09-03 rework): the ONE package-operation queue. Owns the
     * per-workspace mutexes (moved out of this class — there must be exactly
     * one lock implementation) plus the queue observability (current owner,
     * waiters, contended-wait logging) that surfaces as "queued behind X"
     * state. Classification lives in [com.fem.linexa.domain.packagemanager.PackageOperationClassifier]
     * so the Shortcuts tab and this runtime agree on what a package operation is.
     */
    private val packageOpCoordinator: com.fem.linexa.domain.packagemanager.PackageOperationCoordinator,
) : ContainerRuntime {

    // Transformation spec §7/§8: concurrent maps so cross-thread readers
    // (isTerminalAlive/getTerminal/isDaemonAlive from UI-adjacent threads)
    // never read a HashMap mid-resize. Writers keep their mutexes for the
    // compound check-then-act sequences; plain reads are now lock-free and
    // race-free.
    private val sessions = ConcurrentHashMap<TerminalKey, PtySession>()
    private val sessionsLock = Mutex()

    /** §1.98: one-shot latch so the full bootstrap forensics dump runs only on
     * the first terminal open of the process, not on every "New terminal". */
    private val bootstrapDiagnosticsDumped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** §1.99: workspaces whose first-open exec forensics (incl. the blocking
     * guest proot probe) have already run this process. Keyed by workspace so
     * the first open of EACH workspace still captures the cold-start dump;
     * additional terminals of the same workspace skip it. An entry is removed
     * on [closeWorkspaceSessions] so a fresh session re-captures. */
    private val execForensicsDone = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // §1.83: named guest daemons (Xvnc now, sshd later), keyed by (workspace, name).
    // ConcurrentHashMap for the same §7/§8 cross-thread read guarantee as [sessions].
    private val daemons = ConcurrentHashMap<Pair<String, String>, ManagedDaemon>()
    private val daemonsLock = Mutex()

    // 2026-11-13: the guest `sudo` bridge. One per workspace, started when
    // the first terminal opens and stopped when the workspace's last terminal
    // closes / the workspace is stopped. It runs requests through
    // [execInternal] — the same privileged one-shot path Root Shell and the
    // package manager use — and never fakes a uid transition in the caller.
    private val rootExecBridges = ConcurrentHashMap<String, RootExecBridge>()

    // Transformation spec §8 — process supervision: every daemon's exit is
    // observed. An *unexpected* exit (crash or clean death with no stop in
    // flight) is reported to the [WorkspaceHealthRegistry] (degrading the
    // workspace, observable via RuntimeBinder) and mirrored in the trace;
    // an intentional stop is silent. The daemon entry and its scratch scope
    // are always reclaimed either way — no daemon slot can leak.
    private fun watchDaemon(workspaceId: String, name: String, managed: ManagedDaemon) {
        activityWatchScope.launch {
            val exitCode = runCatching { managed.process.waitFor() }.getOrElse { -1 }
            // Intentional stop (stopDaemon / restart / workspace teardown):
            // the stopper already removed the entry and released the scope.
            if (managed.stopping.get()) return@launch
            // Pre-release audit fix (2026-09-02, finding RT-5): only remove
            // the entry if it is still THIS daemon. The old unconditional
            // remove raced a replacement: a daemon restarted between its
            // predecessor's death and this watcher's wake-up had its map
            // entry evicted from under it, losing stop/isDaemonAlive control
            // of the live daemon.
            daemonsLock.withLock {
                if (daemons[workspaceId to name] === managed) {
                    daemons.remove(workspaceId to name)
                }
            }
            bootstrapManager.releaseProotTmpDir(managed.scope)
            val detail = "daemon '$name' exited unexpectedly (exit code $exitCode)"
            Logger.w(TAG, "§1.83 $workspaceId: $detail — workspace degrades, keeps running")
            runtimeTrace.record(workspaceId, detail)
            healthRegistry.reportDegraded(
                workspaceId,
                RuntimeDegradation.DAEMON_COMPONENT_PREFIX + name,
                detail
            )
            sessionManager.refreshHealth(workspaceId)
        }
    }

    // ── F-6 (2026-08-25 audit): dpkg-lock safety registry ──────────────
    //
    // The audit proved `ensureDpkgHealthy` could delete `var/lib/dpkg/lock*`
    // while another live guest process for the SAME workspace was mid-dpkg:
    // `openTerminal(T1)` does not stop T0, and one-shot execs (Package
    // Manager UI) run while interactive terminals are alive. Deleting the
    // file a live dpkg holds fcntl-locked lets a second dpkg lock a fresh
    // inode and corrupt the dpkg database.
    //
    // The registry counts, per workspace, every guest process that could
    // legitimately be running dpkg/apt right now:
    //   - live interactive PTY sessions (registered when the pty child is
    //     spawned; released by an awaitExit watcher, so both natural exits
    //     and destroy() release the slot),
    //   - in-flight one-shot execs (registered under [dpkgHealthLock]
    //     BEFORE the process is spawned, released when it has exited).
    // Daemons (Xvnc/sshd) never run package managers, so they deliberately
    // do not gate lock cleanup — counting them would disable stale-lock
    // recovery for the whole lifetime of a desktop session while protecting
    // against nothing.
    //
    // [dpkgHealthLock] makes "consult the registry, then delete locks"
    // atomic against "register a new activity": a cleanup that starts before
    // an activity registers always FINISHES before that activity's process
    // can spawn (registration waits for the lock), so locks are never
    // deleted under a running dpkg. It is a leaf mutex — nothing acquires
    // sessionsLock/daemonsLock while holding it, and openTerminal only takes
    // it in short bounded sections, so terminal startup cannot deadlock.
    private val liveSessions = ConcurrentHashMap<String, AtomicInteger>()
    private val inflightExecs = ConcurrentHashMap<String, AtomicInteger>()
    private val dpkgHealthLock = Mutex()

    // ── LX-2 (2026-08-29): single-owner workspace initialization ────────
    //
    // Every guest-rootfs repair (guest /tmp, /dev/shm, /run, /proc
    // snapshots, hostinfo helper, host /proc overlays, group-db
    // reconciliation, dpkg-health check) is read-modify-write state INSIDE
    // the workspace's rootfs. Multiple entry points mutate it concurrently
    // in practice: openTerminal (serialized by [sessionsLock] against other
    // opens, but not against execs), one-shot execs (Package Manager UI,
    // feature provisioners, desktop presets) and daemon starts (Xvnc/sshd)
    // can all run at the same moment for the SAME workspace. The individual
    // ensure* repairs are idempotent but not interleaving-safe (temp-file
    // paths are fixed, read-modify-write sections are not atomic), and the
    // F-6 dpkg-health consult must be atomic with activity registration.
    //
    // This per-workspace mutex makes initialization single-owner: at most
    // one openTerminal/exec/startDaemon at a time performs rootfs repairs
    // for a given workspace, while different workspaces proceed in
    // parallel. It is held ONLY around the repair section — never across
    // process launches — so a long guest command cannot stall another
    // session's bookkeeping.
    //
    // Deliberately never removed (not even on workspace close): an entry is
    // a tiny object, and removing it while a slow in-flight exec still
    // holds the mutex would let a newly-computed mutex bypass the
    // serialization it exists to provide.
    private val workspaceInitLocks = ConcurrentHashMap<String, Mutex>()

    private fun workspaceInitLock(workspaceId: String): Mutex =
        workspaceInitLocks.computeIfAbsent(workspaceId) { Mutex() }

    // ── LX-2 / issue #2: Linexa-initiated package operations are single-owner ──
    //
    // The per-workspace package mutexes and their observability moved to
    // [com.fem.linexa.domain.packagemanager.PackageOperationCoordinator]
    // (2026-09-03): one lock implementation shared by every mutating
    // surface — this runtime's one-shot exec path, and (since the same
    // rework) the Commands tab's package commands, which now run through
    // execCommandStreaming instead of being typed into a terminal. See the
    // coordinator's kdoc for the full architecture.
    //
    // User-terminal apt is untouched (it never passes through this class),
    // so native locking remains the visible behavior for manual use; the
    // guest's APT::Lock::Timeout drop-in makes user-typed apt WAIT for a
    // queued Linexa operation rather than fail.


    /** F-6: watchers park on each session's awaitExit and release its slot. */
    private val activityWatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * F-6: whether any guest process for [workspaceId] that could be running
     * dpkg/apt is live right now (an alive interactive terminal, or an
     * in-flight one-shot exec).
     */
    private fun hasLiveGuestActivity(workspaceId: String): Boolean =
        (liveSessions[workspaceId]?.get() ?: 0) > 0 ||
            (inflightExecs[workspaceId]?.get() ?: 0) > 0

    /**
     * LX-2: whether a one-shot command is a package-manager operation, and
     * therefore must be serialized per workspace through
     * [packageOpCoordinator]. Delegates to the ONE classifier
     * ([com.fem.linexa.domain.packagemanager.PackageOperationClassifier]) —
     * the Shortcuts tab's routing decision and this runtime's queueing
     * decision must be the same decision.
     */
    private fun isPackageOperation(command: String): Boolean =
        com.fem.linexa.domain.packagemanager.PackageOperationClassifier.isPackageOperation(command)

    /**
     * 2026-11-13: starts (idempotently) the guest `sudo` mailbox for
     * [workspaceId]. The bridge itself is Android-free; the privileged
     * execution is this class's existing [execInternal] root-emulated path,
     * including its package-operation queue, dpkg-health preflight,
     * non-interactive environment and operation timeout.
     *
     * Failure is fail-open for the session (a workspace must open even if the
     * mailbox cannot be created) but the guest's helper then reports the
     * unavailable bridge with actionable guidance instead of pretending
     * `sudo` worked.
     */
    private fun ensureRootExecBridge(workspaceId: String, rootfsPath: String) {
        try {
            // A workspace re-import can move the rootfs; never let a stale
            // bridge keep polling the old tree.
            val existing = rootExecBridges[workspaceId]
            if (existing != null && existing.rootfsPath != rootfsPath) {
                existing.stop()
                rootExecBridges.remove(workspaceId, existing)
            }
            val bridge = rootExecBridges.computeIfAbsent(workspaceId) { ws ->
                RootExecBridge(
                    workspaceId = ws,
                    rootfsPath = rootfsPath,
                    execute = { command, onLine ->
                        execInternal(
                            workspaceId = ws,
                            rootfsPath = rootfsPath,
                            command = command,
                            onLine = onLine,
                            timeoutSeconds = OPERATION_TIMEOUT_SECONDS
                        )
                    },
                    log = { message ->
                        Logger.i(TAG, message)
                        runtimeTrace.record(ws, message)
                    }
                )
            }
            bridge.start()
        } catch (e: Exception) {
            Logger.w(TAG, "root-exec bridge could not be started for $workspaceId: ${e.message}")
            runtimeTrace.record(workspaceId, "root-exec bridge start FAILED: ${e.message}")
        }
    }

    private fun stopRootExecBridge(workspaceId: String) {
        rootExecBridges.remove(workspaceId)?.stop()
    }

    /**
     * F-6: attaches the exit watcher that releases one live-session slot
     * when [session]'s child exits — for both natural exits and destroy().
     * The slot itself must already have been incremented by the caller
     * BEFORE the pty child was forked (see [openTerminal]): a slot registered
     * only after the fork would leave a window in which a concurrent
     * [execInternal] health check observes an empty registry while the
     * just-forked child is already able to run dpkg.
     */
    private fun watchSessionActivity(session: PtySession) {
        activityWatchScope.launch {
            try {
                session.awaitExit()
            } catch (_: Exception) {
                // Session teardown raced us; the slot is released below either way.
            } finally {
                liveSessions[session.workspaceId]?.decrementAndGet()
            }
        }
    }

    /**
     * Called before every launch so stale bootstrap symlinks self-heal.
     *
     * §1.98 perf: [BootstrapManager.setup] runs the full layout self-heal +
     * binary/manifest validation + host-terminfo install. On the FIRST launch
     * of the process it also dumps the expensive [BootstrapManager.diagnoseAll]
     * forensics (stats/reads every binary, symlink, loader and manifest to build
     * a large string). That dump is a *diagnostic*, not a launch gate — the
     * launch gate is binary-access validation inside setup() itself. Re-running
     * the full dump on EVERY terminal open added real, serialized cost to each
     * "New terminal" (T1/T2/...) under [sessionsLock] with zero correctness
     * benefit once the layout is known-good. So the dump is gated to the first
     * open of the process; subsequent opens still run setup()'s cheap self-heal
     * and validation, only the forensics string is skipped. Diagnostics remain
     * available on demand (Settings → Debug) via [diagnoseAll].
     *
     * **UI/UX Phase 7:** the dump is additionally gated on [DebugCapture]. §1.98
     * made it once-per-process; it was still built and stringified on that first
     * open for a user who has debug info off and can never read it — and building
     * it stats and reads every bundled binary, symlink, loader and manifest. The
     * latch is only consumed when capture is on, so enabling the toggle after a
     * launch still yields the dump on the next open rather than having silently
     * spent it.
     * **§16.3.2:** `setup()` itself is now memoised per `BootstrapManager`
     * instance (it is a `@Singleton`, so per process). The self-heal + validation
     * pass runs on the first call that converges cleanly and is skipped
     * afterwards, which matters here specifically: this function is reached from
     * `openTerminal`, `execInternal` **and** `startDaemon`, so a cold Desktop
     * start ran the whole pass several times before Xvnc was asked to start
     * (audit §10.1/§10.2). Nothing about the *first* pass changed, and a pass
     * that reports any issue is not memoised, so a broken layout still self-heals
     * on the next launch. Callers that have themselves detected corruption use
     * `forceSetup()` instead; this one has not, so `setup()` is correct here.
     */
    private fun ensureBootstrap() {
        bootstrapManager.setup()
        if (DebugCapture.isEnabled() && bootstrapDiagnosticsDumped.compareAndSet(false, true)) {
            // §1.40: log full state once per process for device debugging.
            Logger.d("ContainerRuntime", bootstrapManager.diagnoseAll())
        }
    }

    /**
     * §1.27/§1.73: loads the workspace's persisted config.yaml once per
     * launch (fail-open: a missing file is the default case, an unparseable
     * one is logged and ignored by [WorkspaceConfigParser.load]). Every
     * guest-authored override — env, launch shell/args, extra binds — is
     * read from here at launch; the overrides are applied by the
     * callers below.
     */
    private fun loadWorkspaceConfig(workspaceId: String): WorkspaceConfig? =
        configParser.load(File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml"))

    /**
     * §1.82: whether the workspace's Shared Storage feature toggle is enabled
     * for the next launch.
     *
     * Resolves the effective per-workspace toggle exactly the way the UI does
     * ([resolveFeatureToggles] over the built-in defaults), so the runtime and
     * the Workspace detail screen can never disagree about whether `/mnt/shared`
     * should be mounted. A missing persisted toggle (pre-§1.81 workspace, or a
     * record read before import persisted one) resolves to the built-in default
     * (`storage` = enabled), which is also the pre-§1.82 unconditional bind —
     * so existing workspaces keep their shared mount unless the user explicitly
     * turns the toggle off.
     */
    private suspend fun isSharedStorageEnabled(workspaceId: String): Boolean {
        val persisted = workspaceRepository.getById(workspaceId)?.featureToggles.orEmpty()
        val effective = resolveFeatureToggles(WorkspaceContent.builtinFeatureDefaults, persisted)
        return effective[FEATURE_STORAGE] ?: true
    }

    /**
     * Session Persistence: whether this workspace's terminals opt into the
     * per-workspace tmux backend (create-or-reattach). Read from the same
     * single source of truth as the Features tab ([FeatureSettings] over the
     * Room `attributes` map), fail-open to OFF — the §1.76 direct
     * PTY → proot → shell launch remains the default for every workspace
     * that did not explicitly enable persistence.
     */
    private suspend fun isPersistenceEnabled(workspaceId: String): Boolean {
        val attributes = workspaceRepository.getById(workspaceId)?.attributes.orEmpty()
        return FeatureSettings.from(attributes).terminalPersistence
    }

    /**
     * 2026-09 GPU work: whether this workspace's GPU toggle is on, resolved
     * from the same single source of truth as every other feature (Room, via
     * [resolveFeatureToggles] over the built-in defaults), so the launch path
     * and the Features tab cannot disagree. Fails open to OFF — a corrupt
     * metadata row must cost graphics acceleration, not the session.
     */
    private suspend fun isGpuEnabled(workspaceId: String): Boolean {
        val persisted = workspaceRepository.getById(workspaceId)?.featureToggles.orEmpty()
        val effective = resolveFeatureToggles(WorkspaceContent.builtinFeatureDefaults, persisted)
        return effective[FEATURE_GPU] ?: false
    }

    /**
     * Applies the workspace's persisted config.yaml env overrides (§1.27).
     * Fail-open: a missing file is the default case (no overrides), an
     * unparseable one is logged and ignored by [WorkspaceConfigParser.load].
     *
     * §1.49 (Issue 3 fix): a workspace's `linexa.env` is guest-authored
     * content that can come from a user-imported rootfs archive, so it must
     * never be allowed to override host-infrastructure keys
     * ([BootstrapManager.HOST_ENV_KEYS]). Violating entries are dropped with
     * a clear per-key warning so the workspace author can see why their
     * override didn't take effect — enforcement is in code, not a comment.
     *
     * §1.73: [config] is the same workspace config the launch shell/bind
     * overrides are read from, loaded once by the caller via
     * [loadWorkspaceConfig] — no second disk read.
     */
    private fun applyConfigEnv(workspaceId: String, spec: LaunchSpec, config: WorkspaceConfig?): LaunchSpec {
        if (config == null || config.env.isEmpty()) return spec
        val allowed = config.env.filterKeys { key ->
            if (key in BootstrapManager.HOST_ENV_KEYS) {
                Logger.w(
                    "ContainerRuntime",
                    "Dropping config.yaml env override '$key' for $workspaceId — it is host-launcher-owned (§1.49, " +
                        "protected: ${BootstrapManager.HOST_ENV_KEYS.sorted().joinToString(", ")})"
                )
                false
            } else true
        }
        if (allowed.isEmpty()) return spec
        Logger.d("ContainerRuntime", "Applying ${allowed.size} env override(s) from config.yaml for $workspaceId")
        return spec.copy(env = spec.env + allowed)
    }

    /**
     * Issue #3 (2026-09-03): runs the image's declared one-shot initialization
     * (`linexa.init.post_install` then `linexa.init.first_boot`) inside the
     * guest, once per rootfs, the first time a terminal opens.
     *
     * ## Discipline (why this cannot become a launch-time hazard)
     *
     *  - **Marker:** `{rootfs}/.linexa/first-boot.done` records completion;
     *    every later launch is a file-exists check (zero cost).
     *  - **Bounded attempts:** on failure the attempt counter
     *    (`first-boot.attempts`) increments and the commands retry on the
     *    next launch; after [DeclaredInit.MAX_ATTEMPTS] failures the marker
     *    is written anyway with a `gave-up` body and the failure stays in the
     *    trace — a permanently failing command must not slow every launch
     *    forever, and it must not silently vanish either.
     *  - **Sequential, stop-at-first-failure:** each command is its own
     *    one-shot exec (precise exit code + output per command, issue #12's
     *    diagnostics), and a failed command stops the sequence — declared
     *    init steps are conventionally dependent (later steps assume earlier
     *    ones). Retries re-run the whole list: the schema convention is
     *    idempotent commands (`mkdir -p`, byte-identical writes).
     *  - **Fail-open for the launch:** a failed first boot never blocks the
     *    terminal — the shell opens and the diagnostic lives in the trace
     *    and log. A first boot that cannot complete is a workspace-quality
     *    problem, not a reason to refuse the user a shell.
     *
     * Runs OUTSIDE [workspaceInitLock] (the dpkg preflight has just released
     * it): [execInternal] takes that lock itself, so calling this inside the
     * repair section would self-deadlock.
     */
    private suspend fun runDeclaredFirstBootIfNeeded(
        workspaceId: String,
        rootfsPath: String,
        metadata: RootFsMetadata?
    ) {
        val init = metadata?.init ?: return
        val commands = init.postInstall + init.firstBoot
        if (commands.isEmpty()) return
        val rootfs = File(rootfsPath)
        val markerDir = File(rootfs, FIRST_BOOT_MARKER_DIR)
        val marker = File(markerDir, FIRST_BOOT_MARKER_FILE)
        if (marker.isFile) return
        val context = "distro=${metadata.distro.id} version=${metadata.distro.version ?: "?"} " +
            "arch=${metadata.architecture.abi}"

        try {
            if (!markerDir.isDirectory && !markerDir.mkdirs()) {
                Logger.w(TAG, "first boot: could not create ${markerDir.absolutePath} — skipping declared init ($context)")
                return
            }
            val attempts = File(markerDir, FIRST_BOOT_ATTEMPTS_FILE)
                .takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
            if (attempts >= DeclaredInit.MAX_ATTEMPTS) {
                marker.writeText("gave-up after $attempts attempts\n")
                Logger.w(
                    TAG,
                    "first boot gave up for $workspaceId after $attempts failed attempts — " +
                        "marker written; commands stay available as manual shortcuts ($context)"
                )
                runtimeTrace.record(workspaceId, "declaredFirstBoot GAVE-UP after $attempts attempts")
                return
            }

            Logger.i(TAG, "first boot for $workspaceId: ${commands.size} declared command(s), attempt ${attempts + 1} ($context)")
            runtimeTrace.record(workspaceId, "declaredFirstBoot ENTER commands=${commands.size} attempt=${attempts + 1}")
            for ((index, command) in commands.withIndex()) {
                runtimeTrace.record(workspaceId, "declaredFirstBoot[$index] ENTER: $command")
                val result = execInternal(
                    workspaceId,
                    rootfsPath,
                    command = command,
                    onLine = null,
                    timeoutSeconds = COMMAND_TIMEOUT_SECONDS
                )
                if (result.exitCode != 0) {
                    val tail = result.output.trim().takeLast(500).ifBlank { "<no output>" }
                    Logger.w(
                        TAG,
                        "declared init command $index FAILED for $workspaceId: cmd='$command' " +
                            "exit=${result.exitCode} output=$tail ($context)"
                    )
                    runtimeTrace.record(
                        workspaceId,
                        "declaredFirstBoot[$index] FAILED exit=${result.exitCode} out=${tail.take(256)}"
                    )
                    File(markerDir, FIRST_BOOT_ATTEMPTS_FILE).writeText((attempts + 1).toString())
                    return
                }
                Logger.i(TAG, "declared init command $index ok for $workspaceId ($context)")
                runtimeTrace.record(workspaceId, "declaredFirstBoot[$index] OK")
            }
            marker.writeText("ok at ${java.time.Instant.now()}\n")
            File(markerDir, FIRST_BOOT_ATTEMPTS_FILE).delete()
            Logger.i(TAG, "first boot completed for $workspaceId ($context)")
            runtimeTrace.record(workspaceId, "declaredFirstBoot COMPLETE")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail-open: never block the terminal over declared init.
            Logger.e(TAG, "first boot threw for $workspaceId — continuing ($context): ${e.message}", e)
            runtimeTrace.record(workspaceId, "declaredFirstBoot THREW ${e.message}")
        }
    }

    /**
     * Issue #3 (2026-09-03): merges the image's DECLARED
     * `linexa.environment.variables` into the launch environment. Layered
     * BEFORE the config env so the workspace's own config.yaml still wins on
     * a collision (§1.27 "config wins" semantics), and under the same
     * HOST_ENV_KEYS guard — a declaration can never override host launcher
     * plumbing (PATH/HOME/LD_LIBRARY_PATH/…). variables were validated at
     * import (name shape, single-line values); a fail-open read at launch
     * may still hand us whatever, so the guard re-runs here.
     */
    private fun applyDeclaredEnv(workspaceId: String, spec: LaunchSpec, metadata: RootFsMetadata?): LaunchSpec {
        val declared = metadata?.environment?.variables.orEmpty()
        if (declared.isEmpty()) return spec
        val allowed = declared.filterKeys { key ->
            if (key in BootstrapManager.HOST_ENV_KEYS) {
                Logger.w(
                    "ContainerRuntime",
                    "Dropping declared env variable '$key' for $workspaceId — it is host-launcher-owned (§1.49)"
                )
                false
            } else true
        }
        if (allowed.isEmpty()) return spec
        Logger.d("ContainerRuntime", "Applying ${allowed.size} declared env variable(s) for $workspaceId")
        return spec.copy(env = spec.env + allowed)
    }

    // ── Host System Information pass-through (2026-08-26 track) ────────────
    //
    // Two additive layers, both fail-open, both routed through the injected
    // [hostInfoBridge]. See docs/HOST_SYSTEM_INFO.md for the architecture.

    /**
     * Layer ONE — guest env: after config env so a host-info value can never
     * be overridden by anything except an explicit future decision (the
     * `LINEXA_HOST_` prefix makes collisions impossible today), before any
     * later launch-specific layer (F-7 noninteractive etc.).
     */
    private suspend fun applyHostInfoEnv(
        workspaceId: String,
        spec: LaunchSpec,
        config: WorkspaceConfig?
    ): LaunchSpec {
        val keys = hostInfoKeysFor(config)
        if (keys.isEmpty()) return spec
        val additions = runCatching { hostInfoBridge.buildGuestEnv(keys) }
            .getOrElse {
                Logger.w(
                    TAG,
                    "host-info env build failed for $workspaceId — launching without LINEXA_HOST_* vars: ${it.message}"
                )
                emptyMap()
            }
        if (additions.isEmpty()) return spec
        runtimeTrace.record(workspaceId, "hostInfoEnv applied (${additions.size} keys)")
        Logger.d(TAG, "Injecting ${additions.size} host-info env var(s) for $workspaceId: ${additions.keys}")
        return spec.copy(env = spec.env + additions)
    }

    /**
     * Which facts THIS workspace asked for: config allowlist when valid,
     * conservative default set otherwise, NOTHING when `enable: false`.
     */
    private fun hostInfoKeysFor(config: WorkspaceConfig?): Set<HostInfoKey> = when {
        config == null -> HostInfoRuntimeBridge.DEFAULT_GUEST_KEYS
        !config.hostInfo.isEnabledByDefault() -> emptySet()
        else -> config.hostInfo.effectiveKeys(HostInfoRuntimeBridge.DEFAULT_GUEST_KEYS)
    }

    /**
     * Layer TWO — /proc overlay refresh. Called AFTER the initializer's
     * baseline R-6 synthetic snapshots at every site that has them; on the
     * strict session path failures abort exactly like a failed baseline
     * snapshot would ([openTerminal]), elsewhere they downgrade to legacy
     * content with a log.
     *
     * Must run inside the IO contexts these sites already own: it is file I/O
     * plus one provider pull.
     */
    private suspend fun refreshHostProcOverlays(rootfs: File, workspaceId: String, strict: Boolean) {
        runtimeTrace.record(workspaceId, "hostProcOverlays ENTER")
        try {
            val failure = hostInfoBridge.refreshProcOverlays(rootfs, workspaceId)
            if (failure != null) {
                if (strict) throw IllegalStateException("Guest host /proc overlays failed for $workspaceId: $failure")
                Logger.w(TAG, "host /proc overlays degraded to baseline for $workspaceId: $failure")
            }
            runtimeTrace.record(workspaceId, "hostProcOverlays EXIT ok=${failure == null}")
        } catch (e: IllegalStateException) {
            throw e
        } catch (t: Throwable) {
            if (strict) throw t
            Logger.w(TAG, "host /proc overlay refresh threw for $workspaceId — continuing with baseline: ${t.message}")
            runtimeTrace.record(workspaceId, "hostProcOverlays THREW ${t.message}")
        }
    }

    /**
     * §1.73: resolves the launch shell for [rootfsPath] after applying the
     * workspace's `linexa.launch.shell` override. The override is an
     * absolute guest path used verbatim (single argv token, never
     * shell-interpreted); without one, the default [resolveShellPath]
     * fallback (`/bin/bash` → `/usr/bin/bash` → `/bin/sh`) is preserved.
     * Logs loudly so an obvious "no such shell" mistake is visible in
     * diagnostics rather than a silent exec failure.
     *
     * §1.118 inserts one step in the middle: the rootfs's declared
     * `linexa.environment.shell`. Precedence is
     * `launch.shell` → `environment.shell` → probe, because the two declared
     * values mean different things — `launch.shell` is an explicit *override
     * of launch behaviour* (§1.73's whole purpose), while
     * `environment.shell` states what the image's login shell **is**. A
     * statement of fact must not beat an explicit override, but it must beat
     * a guess. The declared path is still verified to exist in the rootfs
     * before it is used: a declaration that is wrong about its own image
     * would otherwise turn a working workspace into a session that can't
     * exec, and the probe answer is right there.
     */
    private fun resolveLaunchShell(rootfsPath: String, config: WorkspaceConfig?, workspaceId: String): String {
        val override = config?.launch?.shell
        if (override != null) {
            Logger.i("ContainerRuntime", "config.yaml launch.shell override for $workspaceId: $override (replaces resolveShellPath default)")
            return override
        }
        val declared = workspaceRootFsMetadata.of(workspaceId)?.environment?.shell
        if (declared != null) {
            if (File(File(rootfsPath), declared.removePrefix("/")).exists()) {
                Logger.i(
                    "ContainerRuntime",
                    "§1.118 using declared linexa.environment.shell for $workspaceId: $declared (skips the shell probe)"
                )
                return declared
            }
            Logger.w(
                "ContainerRuntime",
                "§1.118 declared linexa.environment.shell '$declared' does not exist in $workspaceId's rootfs — " +
                    "falling back to the probe"
            )
        }
        return resolveShellPath(rootfsPath)
    }

    /**
     * Detect which shell exists inside [rootfsPath]. Checks common locations
     * in priority order: `/bin/bash` → `/usr/bin/bash` → `/bin/sh`.
     *
     * This avoids hardcoding `/bin/bash` (§1.1 default) which fails on
     * Alpine, minimal Fedora, and other rootfs images that ship only
     * busybox ash. Every supported distro (§0 — Debian/Ubuntu/Arch/Fedora/Alpine)
     * guarantees `/bin/sh`; bash is checked first for the full login
     * experience (bash history, prompt, profile), but `/bin/sh` is always
     * available as a fallback.
     */
    private fun resolveShellPath(rootfsPath: String): String {
        val rootfs = File(rootfsPath)
        for (candidate in SHELL_CANDIDATES) {
            if (File(rootfs, candidate).exists()) return candidate
        }
        // Final fallback — `/bin/sh` exists in every POSIX rootfs, but if
        // even that's missing the session will report a clear error from
        // proot itself rather than a silent "shell not found".
        return "/bin/sh"
    }

    override suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        asRoot: Boolean
    ): PtySession = sessionsLock.withLock {
        val key = TerminalKey(workspaceId, terminalId)
        val t0 = System.currentTimeMillis()
        Logger.i(TAG, "CHAIN: ENTER openTerminal workspaceId=$workspaceId terminalId=$terminalId rootfs=$rootfsPath")
        runtimeTrace.record(workspaceId, "PRootContainerRuntime.openTerminal ENTER terminalId=$terminalId")
        // §1.68: setup() runs the busybox --install + applet layer + 34-binary
        // validation on the IO dispatcher, never on the RuntimeService Main
        // dispatcher this call arrives on — a slow/wedged busybox used to
        // block openTerminal before Running was ever set, keeping the Terminal
        // stuck on "Connecting to workspace session…".
        runtimeTrace.record(workspaceId, "ensureBootstrap ENTER")
        withContext(Dispatchers.IO) { ensureBootstrap() }
        runtimeTrace.record(workspaceId, "ensureBootstrap EXIT")
        Logger.i(TAG, "CHAIN: ensureBootstrap done elapsed=${elapsed(t0)}")
        // §1.48/§1.97: replace any prior session for THIS (workspace, terminal)
        // key first (its scratch dir is released below), then create a fresh
        // per-session PROOT_TMP_DIR scope owned by this session. Each terminal
        // is an independent proot process with its own scratch scope, so a
        // second terminal in the same workspace cannot clobber the first's.
        // §1.76: tmux is no longer part of the startup path (the pty executes
        // proot directly), so there is no stale detached tmux server to kill
        // on open; the kill-server helper stays only for close/future
        // persistence.
        sessions.remove(key)?.destroy()
        runtimeTrace.record(workspaceId, "replaced prior session (if any) for terminalId=$terminalId")

        // §1.80: the guest rootfs must have a usable /tmp (drwxrwxrwt)
        // BEFORE bash is launched. Distro tarballs (Debian/Ubuntu) omit
        // /tmp entirely — it is a tmpfs mount created at boot — so apt
        // failed with "unable to mkstemp /tmp/apt.sig: No such file or
        // directory" otherwise. This is an idempotent repair on every
        // launch (fresh and already-installed workspaces alike). A
        // failure aborts the session open the same way a missing rootfs
        // does, so a workspace with no usable /tmp never starts broken.
        //
        // §1.145 (terminal-freeze fix): the four steps below are all blocking
        // filesystem IO and every one of them used to run on the *caller's*
        // dispatcher, which is `Dispatchers.Main` for BOTH entry points into
        // this function (TerminalsViewModel.viewModelScope, and the workspace
        // launch path through RuntimeService.serviceScope). `releaseProotTmpDir`
        // and `scopedProotTmpDir` each `deleteRecursively()` a scratch tree,
        // `ensureGuestTmp` stats/creates inside the guest image, and the rootfs
        // existence check stats a path on app-private flash. The terminal module runs
        // ALL sessions' output emulation on the Handler its sessions were
        // constructed on (Main) — see the pty comment at the create call below —
        // so any stall here starves every *already-open* terminal's output and
        // input echo, not just this one. They are hoisted into a single IO
        // context: same order, same failure semantics (a throw here is still
        // *before* the try/catch, so no scratch scope needs releasing yet), only
        // a different thread.
        //
        // The block returns the scoped tmp dir, the dpkg health report AND
        // whether the workspace was guest-idle at consult time: the report's
        // consumer (the `dpkg --configure -a` preflight below) runs AFTER the
        // IO hop, and its recovery decision is only allowed to fire when no
        // live guest activity could own the package database (LX-2).
        //
        // LX-2: the whole repair section is single-owner per workspace
        // ([workspaceInitLock]) — concurrent opens (serialized here by
        // [sessionsLock]) no longer race execs/daemons mutating the same
        // rootfs, and the dpkg-health consult below is atomic with the
        // registration it drives.
        //
        // Issue #4 (2026-09-03): the guest environment (HOME / startup
        // directory / USER) is resolved from the workspace's declared RootFS
        // metadata before the repair section, so the workspace-directory and
        // shell-config ensures below can act on it and the launch spec at the
        // end uses the SAME resolved values. Pure resolution + the
        // WorkspaceRootFsMetadata read (memoized) run here, off the lock.
        val declaredMetadata = workspaceRootFsMetadata.of(workspaceId)
        val guestEnv = GuestEnvironmentResolver.resolve(declaredMetadata)
        // Set inside the repair section below (the workspace ensure can fall
        // back to HOME when the declared directory cannot be created) and read
        // by the launch-spec build at the end — the spec and the ensures must
        // never disagree about where the terminal opens.
        var resolvedStartupDirectory = guestEnv.startupDirectory
        // Issue #1 (2026-09-03): the interactive session runs as the REAL
        // normal-user account — `ensureGuestUser` guarantees the passwd entry
        // (creating `linexa` host-side when missing) and returns the identity
        // the launch spec fakes via --change-id. Resolved inside the repair
        // section so the account exists before any launch decision is made.
        // Placeholder replaced by the repair section below once
        // ensureGuestUser has guaranteed the account; defaulting to root
        // keeps a hypothetical skip-of-repair path on the historical
        // (root-emulated) behavior instead of a half-resolved identity.
        var guestIdentity = GuestIdentity.root(guestEnv.home)
        // 2026-11-13 unified home architecture: the declared metadata picks the
        // account NAME, but the passwd record owns the actual home and uid/gid.
        // These mutable values start from the declaration and are replaced with
        // the resolved account's facts below, so a custom/imported home can
        // never disagree with `$HOME` and the terminal's `-w`.
        var resolvedGuestHome = guestEnv.home
        var resolvedGuestUser = guestEnv.user
        val (tmp, dpkgReport, guestIdle) = withContext(Dispatchers.IO) {
            bootstrapManager.releaseProotTmpDir(prootTmpScope(workspaceId, terminalId))

            val rootfs = File(rootfsPath)
            check(rootfs.exists()) { "Rootfs not found at $rootfsPath" }

            val (dpkgReport, guestIdle) = workspaceInitLock(workspaceId).withLock {
                runtimeTrace.record(workspaceId, "ensureGuestTmp ENTER")
                guestRootfsInitializer.ensureGuestTmp(rootfs, workspaceId)?.let { message ->
                    throw IllegalStateException("Guest /tmp could not be prepared for $workspaceId: $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestTmp EXIT")

                // F-5 (2026-08-25 audit): the guest's POSIX shm surface. Same
                // self-heal-before-launch shape as /tmp: existing/imported
                // workspaces get /dev/shm on their next launch, fresh ones start
                // clean from the installer's ENSURE_DEV_SHM step. Strict gate —
                // a failed chmod here means something is deeply wrong with the
                // rootfs, and a session without working /tmp was already fatal.
                runtimeTrace.record(workspaceId, "ensureGuestDevShm ENTER")
                guestRootfsInitializer.ensureGuestDevShm(rootfs, workspaceId)?.let { message ->
                    throw IllegalStateException("Guest /dev/shm could not be prepared for $workspaceId: $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestDevShm EXIT")

                // R-5 (2026-08-26 runtime parity audit): the guest's /run
                // surface. Same shape as /dev/shm: existing/imported workspaces
                // self-heal at launch, fresh ones start clean from the
                // installer's ENSURE_RUN step. Fail-open is NOT appropriate
                // here — like /dev/shm, a missing /run would silently break
                // any package that writes a pidfile/socket to /run, so a
                // failure to prepare aborts the session.
                runtimeTrace.record(workspaceId, "ensureGuestRun ENTER")
                guestRootfsInitializer.ensureGuestRun(rootfs, workspaceId)?.let { message ->
                    throw IllegalStateException("Guest /run could not be prepared for $workspaceId: $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestRun EXIT")

                // R-6 (2026-08-26 runtime parity audit): static snapshots for
                // /proc/stat, /proc/uptime, /proc/loadavg, /proc/version,
                // /proc/vmstat. These are bound over the Android-restricted
                // /proc paths by PRootLauncher's -b flags. The snapshots are
                // idempotent and re-written on every launch — values stay
                // "frozen" but at least non-EACCES, so top/btop/vmstat/uptime
                // don't refuse to start. Fail-open: a missing /proc in the
                // rootfs just skips the bind-overs (the launcher's
                // procBindOvers helper handles the "no snapshot file" case).
                runtimeTrace.record(workspaceId, "ensureProcSnapshots ENTER")
                guestRootfsInitializer.ensureProcSnapshots(rootfs, workspaceId)?.let { message ->
                    throw IllegalStateException("Guest /proc snapshots could not be prepared for $workspaceId: $message")
                }
                runtimeTrace.record(workspaceId, "ensureProcSnapshots EXIT")

                // 2026-08-27 expansion (Phase 15): the /usr/local/bin/
                // linexa-hostinfo viewer. Idempotent byte-compare + explicit
                // 0755 — installed once per content change, self-heals on roots
                // imported before this step existed.
                runtimeTrace.record(workspaceId, "ensureGuestHostInfo ENTER")
                guestRootfsInitializer.ensureGuestHostInfo(rootfs, workspaceId)?.let { message ->
                    throw IllegalStateException("Guest hostinfo helper could not be prepared for $workspaceId: $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestHostInfo EXIT")

                // Host System Information layer (2026-08-26 track): replaces the
                // synthetic R-6 content above with ANDROID-HOST-derived values
                // (real core count, real uptime, real counters where the host
                // reports them). Strict like the baseline: a workspace that cannot
                // get coherent /proc overlays is treated like one whose other
                // pre-launch guarantees failed.
                refreshHostProcOverlays(rootfs, workspaceId, strict = true)

                // LX-1 (2026-08-29): guest supplementary-group reconciliation.
                // PRoot propagates the app process's LIVE kernel groups into
                // every guest process; the installer's account fixup mirrored
                // them into /etc/group only once at install time, so a drifted
                // Android group set (e.g. the runtime-granted ext_data_rw GID
                // 1077) left new GIDs unnamed — every shell opened with
                // "groups: cannot find name for group ID 1077". Re-run the
                // mirror here so every propagated GID resolves, on every
                // session, deterministically. Fail-open: a cosmetic group-name
                // gap must never block a session.
                runtimeTrace.record(workspaceId, "ensureGuestGroups ENTER")
                guestRootfsInitializer.ensureGuestGroups(rootfs, workspaceId)?.let { message ->
                    Logger.w(TAG, "guest supplementary-group reconciliation failed for $workspaceId — continuing: $message")
                    runtimeTrace.record(workspaceId, "ensureGuestGroups FAILED $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestGroups EXIT")

                // Issue #1 (2026-09-03): the normal-user account. Creates
                // `linexa` in passwd/group/shadow (+ home seeded from
                // /etc/skel, the ~/linexa project dir, sudo-group membership
                // and the privilege bridge) when missing; resolves the
                // existing account otherwise. The launch below runs
                // --change-id with THIS identity, so `id`/`whoami`/the bash
                // prompt are genuinely the user's, not a prompt-string edit.
                runtimeTrace.record(workspaceId, "ensureGuestUser ENTER user=${guestEnv.user}")
                // The account is ensured regardless of which identity launches
                // (an existing workspace must always have its normal user);
                // only the LAUNCH identity follows [asRoot].
                guestRootfsInitializer.ensureGuestUser(
                    rootfs,
                    workspaceId,
                    preferredUser = guestEnv.user,
                    preferredHome = guestEnv.home
                )
                if (asRoot) {
                    // Root Shell is its OWN context. It must keep `HOME=/root`
                    // and `-w /root` even when the image declares a normal
                    // `linexa` user — the root account is never redirected into
                    // the normal user's home. Resolve root's own passwd record
                    // so a distribution that chose another root home still
                    // stays self-consistent; `/root` is the fallback.
                    guestIdentity = GuestIdentity.resolveNamedUser(rootfs, "root")
                        ?.takeIf { it.isRoot }
                        ?: GuestIdentity.root()
                    resolvedGuestHome = guestIdentity.home
                    resolvedGuestUser = guestIdentity.name
                } else {
                    guestIdentity = GuestIdentity.resolveNamedUser(rootfs, guestEnv.user)
                        ?: GuestIdentity.root(guestEnv.home)
                    if (!guestIdentity.isRoot) {
                        resolvedGuestHome = guestIdentity.home
                        resolvedGuestUser = guestIdentity.name
                    }
                }
                runtimeTrace.record(
                    workspaceId,
                    "ensureGuestUser EXIT user=${guestIdentity.name} uid=${guestIdentity.uid} " +
                        "gid=${guestIdentity.gid} home=$resolvedGuestHome root=${guestIdentity.isRoot}"
                )
                // The startup directory is ALWAYS the resolved home. The
                // declared `linexa.workspace.directory` has been ignored since
                // 2026-09-04; this additionally makes the passwd fact win over
                // a stale declaration.
                resolvedStartupDirectory = resolvedGuestHome

                // 2026-08-31 (terminal appearance): provision the guest's default
                // terminal color scheme (root/.termux/colors.properties) when the
                // workspace ships none. Without it every emulator started from the
                // vendored upstream default palette — background #000000 — so a
                // fresh shell rendered as an (almost) empty pure-black screen: the
                // on-device "new session is black" report. Terminal-owned config
                // file, never overwritten, fail-open — see
                // GuestRootfsInitializer.ensureGuestTerminalColors.
                runtimeTrace.record(workspaceId, "ensureGuestTerminalColors ENTER")
                guestRootfsInitializer.ensureGuestTerminalColors(
                    rootfs,
                    workspaceId,
                    guestHomeDir = resolvedGuestHome
                )?.let { message ->
                    Logger.w(TAG, "guest terminal colors provisioning failed for $workspaceId — continuing: $message")
                    runtimeTrace.record(workspaceId, "ensureGuestTerminalColors FAILED $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestTerminalColors EXIT")

                // Issue #4 (2026-09-03) + #2 (2026-09-04): make the startup
                // directory real before anything can `-w` into it. Since #2
                // the startup directory is ALWAYS the home (the resolver
                // ignores the declared workspace directory), so this ensures
                // `/home/linexa` and self-heals the guest at every launch.
                // Fail-open with a home fallback: a launch must never be
                // refused over a mkdir when a usable directory exists — if
                // the workspace cannot be created the terminal still opens,
                // in the home directory instead.
                runtimeTrace.record(workspaceId, "ensureGuestWorkspace ENTER dir=$resolvedStartupDirectory")
                guestRootfsInitializer.ensureGuestWorkspace(rootfs, workspaceId, resolvedStartupDirectory)?.let { message ->
                    Logger.w(
                        TAG,
                        "guest workspace '$resolvedStartupDirectory' could not be prepared for $workspaceId — " +
                            "falling back to HOME ($resolvedGuestHome): $message"
                    )
                    runtimeTrace.record(workspaceId, "ensureGuestWorkspace FALLBACK to home: $message")
                    // The home itself is also ensured (same fail-open posture:
                    // a missing HOME breaks shell rc lookups and `cd ~`).
                    guestRootfsInitializer.ensureGuestWorkspace(rootfs, workspaceId, resolvedGuestHome)?.let {
                        Logger.w(TAG, "guest HOME $resolvedGuestHome also missing for $workspaceId: $it")
                    }
                    resolvedStartupDirectory = resolvedGuestHome
                }
                runtimeTrace.record(workspaceId, "ensureGuestWorkspace EXIT dir=$resolvedStartupDirectory")

                // Issue #7 (2026-09-03): Termux-like shell conveniences for
                // EVERY distribution — `la`/`ll`/`l` and colored listings,
                // declared aliases merged over the built-in baseline. Login
                // shells only; fail-open (the shell keeps the distribution's
                // own config on failure).
                runtimeTrace.record(workspaceId, "ensureGuestShellConfig ENTER")
                val effectiveAliases = GuestRootfsInitializer.DEFAULT_SHELL_ALIASES +
                    (declaredMetadata?.shellConfig?.aliases.orEmpty())
                guestRootfsInitializer.ensureGuestShellConfig(rootfs, workspaceId, effectiveAliases)?.let { message ->
                    Logger.w(TAG, "guest shell config provisioning failed for $workspaceId — continuing: $message")
                    runtimeTrace.record(workspaceId, "ensureGuestShellConfig FAILED $message")
                }
                runtimeTrace.record(workspaceId, "ensureGuestShellConfig EXIT")

                // Phase 6b bug-fix: clean stale dpkg locks + detect partial state
                // before the session opens, so the user's `apt install` does not
                // hit "Sub-process /usr/bin/dpkg returned an error code (1)" from
                // a transaction that was killed mid-flight on a previous launch.
                // The detection is host-side file ops only; if recovery needs a
                // `dpkg --configure -a` inside the guest, that runs as a one-shot
                // exec below (after the scope is allocated) rather than inside this
                // block, so the scratch dir is already in place.
                runtimeTrace.record(workspaceId, "ensureDpkgHealthy ENTER")
                // F-6: lock cleanup is gated on the workspace having NO other live
                // guest activity — opening T1 must not delete dpkg locks under a
                // dpkg running in T0. LX-2: the consult + the caller's recovery
                // gate share the SAME idleness value, and both are computed under
                // [dpkgHealthLock] so they are atomic against concurrent
                // session/exec registration (see the F-6/LX-2 blocks above).
                val (dpkgHealth, guestIdle) = dpkgHealthLock.withLock {
                    val idle = !hasLiveGuestActivity(workspaceId)
                    guestRootfsInitializer.ensureDpkgHealthy(
                        rootfs,
                        workspaceId,
                        allowLockCleanup = idle
                    ) to idle
                }
                runtimeTrace.record(workspaceId, "ensureDpkgHealthy EXIT needsConfigure=${dpkgHealth.needsConfigure} freedLocks=${dpkgHealth.freedLocks} lowFreeSpace=${dpkgHealth.lowFreeSpace}")
                // LX-4 (2026-09-01): make apt in the guest WAIT for a live
                // apt/dpkg transaction instead of erroring with 'could not
                // get lock' — the multi-session `apt update` in terminal A
                // then terminal B / Package Manager report. Host-side file
                // op, idempotent, apt-only images; failure is logged there
                // and non-fatal (guest keeps native fail-fast behavior).
                guestRootfsInitializer.ensureAptLockWait(rootfs, workspaceId)
                dpkgHealth to guestIdle
            }

            // §1.145: the tmp scope AND the health report (with its idleness
            // gate) travel out together.
            Triple(
                bootstrapManager.scopedProotTmpDir(prootTmpScope(workspaceId, terminalId)),
                dpkgReport,
                guestIdle
            )
        }
        try {
            // Phase 6b bug-fix — pre-flight `dpkg --configure -a` when the
            // health report said the guest's dpkg database was in a partial
            // state. Runs as a one-shot PRoot exec (separate process from the
            // interactive bash session that is about to open) so the user
            // never sees the recovery output interleaved with their shell.
            // Bounded by COMMAND_TIMEOUT_SECONDS; failures are logged but
            // non-fatal — a terminal that opens into a still-broken dpkg is
            // strictly better than one that refuses to open at all.
            //
            // LX-2: the recovery fires ONLY when the workspace was guest-idle
            // at consult time. While any live guest process exists (an apt
            // running in another terminal, an in-flight Package-Manager-UI
            // exec, a provisioner install), the "partial state" evidence is
            // ambiguous — it is just as likely the LIVE transaction's own
            // artifacts (apt writes `archives/partial` while downloading,
            // dpkg writes `updates/` mid-transaction) — and starting a
            // package operation against a live one is the avoidable
            // "Waiting for cache lock … held by process NNNN (apt)"
            // contention this file must never create itself. In that case
            // recovery is DEFERRED to the next launch that observes an idle
            // workspace (dpkg's own fcntl locking keeps every intervening
            // user operation safe in the meantime).
            if (dpkgReport.needsConfigure && !guestIdle) {
                val notice = "dpkg --configure -a preflight DEFERRED for $workspaceId (terminal $terminalId): " +
                    "live guest activity may own the dpkg database — recovery will run at the next idle launch (LX-2)"
                Logger.i(TAG, notice)
                runtimeTrace.record(workspaceId, "dpkg --configure -a preflight DEFERRED (live guest activity)")
            } else if (dpkgReport.needsConfigure) {
                runtimeTrace.record(workspaceId, "dpkg --configure -a preflight ENTER")
                Logger.i(TAG, "CHAIN: preflight dpkg --configure -a for $workspaceId (terminal $terminalId)")
                try {
                    val recovery = execInternal(
                        workspaceId,
                        rootfsPath,
                        command = "dpkg --configure -a",
                        onLine = null,
                        timeoutSeconds = COMMAND_TIMEOUT_SECONDS
                    )
                    if (recovery.exitCode != 0) {
                        Logger.w(
                            TAG,
                            "preflight dpkg --configure -a exit=${recovery.exitCode} for $workspaceId " +
                                "(continuing to open the session) — output: ${recovery.output.take(512)}"
                        )
                        runtimeTrace.record(
                            workspaceId,
                            "dpkg --configure -a preflight FAILED exit=${recovery.exitCode} output=${recovery.output.take(256)}"
                        )
                    } else {
                        Logger.i(TAG, "CHAIN: preflight dpkg --configure -a ok for $workspaceId")
                        runtimeTrace.record(workspaceId, "dpkg --configure -a preflight OK")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "preflight dpkg --configure -a threw for $workspaceId — continuing: ${e.message}", e)
                    runtimeTrace.record(workspaceId, "dpkg --configure -a preflight THREW ${e.message}")
                }
                runtimeTrace.record(workspaceId, "dpkg --configure -a preflight EXIT")
            }

            // Issue #3 (2026-09-03): declared first-boot initialization.
            // `linexa.init.post_install` + `linexa.init.first_boot` run inside
            // the guest ONCE per rootfs, before the first terminal opens, with
            // a marker file + bounded attempts so a permanently failing
            // command cannot slow every launch forever. Details in
            // [runDeclaredFirstBootIfNeeded]. Light commands only by schema
            // convention (bundled profiles keep these sub-second); the
            // marker/attempt discipline is what keeps heavier declared
            // commands from becoming a launch-time hazard.
            runDeclaredFirstBootIfNeeded(workspaceId, rootfsPath, declaredMetadata)

            // §1.145 (terminal-freeze fix): argv/env construction is pure
            // computation, but everything it *reads* is blocking IO, and all of
            // it used to run on the caller's Main dispatcher:
            //   * `loadWorkspaceConfig` reads and parses `config.yaml` off flash;
            //   * `resolveLaunchShell` stats the declared shell inside the rootfs
            //     and, without a declaration, probes `/bin/bash` → `/usr/bin/bash`
            //     → `/bin/sh` (up to three guest-image stats);
            //   * `buildLaunchSpec` → `sharedStorageBindMount()` stats external
            //     files, and `prootEnv` resolves the loader binary path.
            // One IO hop covers the whole argv/env construction. `buildLaunchSpec`
            // itself is called with exactly the same arguments as before — no argv,
            // bind, env, ordering or shell-resolution change; only the thread it
            // runs on. `isSharedStorageEnabled` (a Room read) is already a suspend
            // call and keeps its position as the toggle source, so the runtime and
            // the Workspace screen still cannot disagree. `config` is returned
            // alongside the spec so the env merge below still uses the SINGLE
            // load this launch performed (§1.73) rather than reading the file a
            // second time.
            val (baseSpec, config) = withContext(Dispatchers.IO) {
                // §1.73: config.yaml is loaded ONCE for this launch and feeds
                // the shell override, the shell-args override and the extra
                // binds below — the same fail-open load the env merge uses.
                val loadedConfig = loadWorkspaceConfig(workspaceId)
                val resolvedShell = resolveLaunchShell(rootfsPath, loadedConfig, workspaceId)
                // §1.73: non-empty `linexa.launch.shellArgs` replaces the default
                // `--login`; empty keeps the existing default behavior.
                val shellArgs = loadedConfig?.launch?.shellArgs?.takeIf { it.isNotEmpty() } ?: listOf("--login")
                val extraBinds = loadedConfig?.launch?.bindMounts.orEmpty()
                if (loadedConfig?.launch?.hasOverrides == true) {
                    Logger.i(
                        "ContainerRuntime",
                        "Applying config.yaml launch overrides for $workspaceId: shell=$resolvedShell args=$shellArgs extraBinds=${extraBinds.size}"
                    )
                }
                Logger.i(
                    "ContainerRuntime",
                    "TERMINAL_CHAIN: openTerminal start workspaceId=$workspaceId terminalId=$terminalId rootfs=$rootfsPath shell=$resolvedShell tmuxAvailable=${launcher.isTmuxAvailable}"
                )
                launcher.buildLaunchSpec(
                    rootfsPath,
                    command = listOf(resolvedShell) + shellArgs,
                    // Issue #4 (2026-09-03): the terminal opens in the
                    // declared guest WORKSPACE (fallback-resolved inside the
                    // repair section above), while HOME stays the declared
                    // guest home. Previously both were the coupled `-w
                    // /root` + `HOME=/root` default.
                    workingDirectory = resolvedStartupDirectory,
                    homeDirectory = resolvedGuestHome,
                    userName = resolvedGuestUser,
                    prootTmpDir = tmp.absolutePath,
                    extraBindMounts = extraBinds,
                    includeSharedStorage = isSharedStorageEnabled(workspaceId),
                    // Issue #1 (2026-09-03): the interactive session's real
                    // emulated account — `-0` becomes `--change-id=uid:gid`
                    // for the linexa user (root stays `-0`), so `id`,
                    // `whoami`, `$SHELL` and the `\u` prompt component all
                    // resolve through /etc/passwd to the account that
                    // ensureGuestUser guaranteed above.
                    identity = guestIdentity,
                    shellPathForEnv = resolvedShell
                ) to loadedConfig
            }
            // §1.50: one-time-per-process seccomp probe so affected OEM
            // kernels get the documented fallback pre-applied to the PTY.
            val seccompSafeSpec = resolveSeccompFallback(baseSpec)
            runtimeTrace.record(workspaceId, "seccomp fallback resolve done (needsNoSeccomp=${BootstrapManager.needsNoSeccomp})")
            Logger.i(TAG, "CHAIN: seccomp fallback resolved needsNoSeccomp=${BootstrapManager.needsNoSeccomp} elapsed=${elapsed(t0)}")
            // §1.76: tmux removed from the critical startup path. The pty
            // used to be attached to the tmux *client* (`tmux new-session -A
            // -s linexa <proot…>`), and that client exits (code 0) once the
            // tmux server accepts the session — closing the pty with it, so
            // the terminal showed "[exited]" while the detached server kept
            // proot/bash alive invisibly. The pty child now IS proot (the
            // same direct-pty shape tiny_container uses with its shell), so
            // the pty stays alive exactly as long as the guest shell does.
            // tmux persistence remains available via
            // [PRootLauncher.wrapWithTmux]/[TmuxPersist] for a future
            // explicit persistence feature, but nothing calls it at launch.
            //
            // Session Persistence: that future feature is now wired — as an
            // EXPLICIT per-workspace opt-in ([isPersistenceEnabled] over
            // `feature.terminal.persistence`, default OFF). Only a workspace
            // that chose persistence is wrapped; the core path below still
            // never sees tmux, and [isPersistenceEnabled] failing open to
            // false keeps a corrupt metadata row on the proven path.
            val persistenceRequested = isPersistenceEnabled(workspaceId)
            // 2026-09 GPU work: launch-path provisioning, before the PTY child
            // exists. Confirms (or starts) the workspace's host VirGL server
            // and returns the guest env — EMPTY unless the server is verified
            // running, so a failed bring-up leaves the guest on software
            // rendering (fail-open, file-bridge style). The env merge is
            // unconditional on purpose: an empty map changes nothing.
            val gpuEnv = gpuSupervisor.provisionForLaunch(
                workspaceId = workspaceId,
                rootfsPath = rootfsPath,
                enabled = isGpuEnabled(workspaceId)
            )
            val envAppliedSpec = applyHostInfoEnv(
                workspaceId,
                applyConfigEnv(
                    workspaceId,
                    // Issue #3: declared image variables first — the user's
                    // config.yaml env (applied after) still wins on collision.
                    applyDeclaredEnv(workspaceId, seccompSafeSpec, declaredMetadata),
                    config
                ),
                config
            ).let { base ->
                if (gpuEnv.isEmpty()) base else base.copy(
                    env = base.env + gpuEnv
                )
            }
            val spec = if (persistenceRequested && launcher.isTmuxAvailable) {
                runtimeTrace.record(
                    workspaceId,
                    "Session persistence: wrapping launch with the per-workspace tmux backend (reattach-or-create, session ${TmuxPersist.sessionNameFor(terminalId)})"
                )
                launcher.wrapWithTmux(envAppliedSpec, workspaceId, terminalId)
            } else {
                if (persistenceRequested) {
                    // Persistence requested but the bundled tmux binary is
                    // unavailable (never observed in production — the binary
                    // ships in every APK). Degrade to the direct path and say
                    // so, rather than failing the launch over a diagnostic.
                    Logger.w(TAG, "$workspaceId: persistence enabled but tmux is unavailable — launching direct")
                    runtimeTrace.record(workspaceId, "Session persistence requested but tmux unavailable — direct launch")
                }
                envAppliedSpec
            }
            Logger.d(
                "ContainerRuntime",
                "Opening PTY session for $workspaceId terminal=$terminalId: exec=${spec.executable} args=${spec.args.joinToString(" ").take(384)} envKeys=${spec.env.size} elapsed=${elapsed(t0)}"
            )
            // §1.68: the pty path forks through terminal-library's native
            // forkpty and never reaches runProcess(), so runProcess's FORENSIC
            // dumps don't cover it. Dump the full child-startup fact set here:
            // final argv, env (PATH/HOME/TERM/TMPDIR/PROOT_*), every path
            // on the exec chain (rootfs, tmux, shell, guest home dirs).
            //
            // §1.99 (input-lag fix): this dump runs `probeGuestFilesystem` —
            // a FULL second blocking PRoot boot (`/bin/sh -c …`, up to
            // PROBE_TIMEOUT_SECONDS) plus ~20 on-caller-thread fingerprintPath
            // syscall batches. openTerminal runs on Dispatchers.Main and holds
            // sessionsLock for its whole body, so running the probe on EVERY
            // terminal open blocked the main thread for seconds. the terminal module's
            // TerminalSession does ALL output emulation on the main thread
            // (mMainThreadHandler → emulator.append → notifyScreenUpdate), so a
            // wedged main thread starved T0's output AND the new session's
            // input echo — the reported "new session freezes / typing not
            // received / T0 stutters". The probe is a cold-start diagnostic for
            // a failing FIRST launch; re-running it for every additional
            // terminal (T1/T2/...) bought nothing and cost real main-thread
            // time. Gate it to the first open of each workspace per process
            // (when the diagnostic is actually valuable); later opens of the
            // same workspace skip it. The full dump remains available on demand
            // via Settings → Debug.
            //
            // UI/UX Phase 7: also gated on DebugCapture. §1.99 bounded this to
            // once per workspace per process; that first run is still a full
            // second blocking PRoot boot plus ~20 syscall batches, on the main
            // thread, under sessionsLock — paid for a report a user with debug
            // info off cannot read. The latch is only consumed when capture is
            // on, so enabling the toggle later still yields the forensics on the
            // next terminal open instead of finding the latch already spent.
            // §1.145 (terminal-freeze fix): the ~20 `fingerprintPath` stat batches
            // that make up this dump ran on the caller's thread — only the nested
            // `probeGuestFilesystem` PRoot boot was already on IO. The whole dump
            // now runs on IO. It stays behind both existing gates (DebugCapture and
            // the once-per-workspace latch) and the latch is still only consumed
            // when capture is on, so gating behaviour is unchanged.
            if (DebugCapture.isEnabled() && execForensicsDone.putIfAbsent(workspaceId, true) == null) {
                withContext(Dispatchers.IO) { logExecForensics(workspaceId, rootfsPath, spec) }
            }
            Logger.i(
                TAG,
                "CHAIN: ENTER ptySessionFactory.create exec=${spec.executable} args=${spec.args.joinToString(" ").take(384)} elapsed=${elapsed(t0)}"
            )
            runtimeTrace.record(workspaceId, "PtySessionFactory.create ENTER (exec=${spec.executable}, tmux=${launcher.isTmuxAvailable})")
            // §1.97/W6 gate: the pty child MUST be proot directly — never a
            // tmux wrapper. tmux-as-pty-child was the §1.76 lifecycle
            // regression: the tmux *client* exits 0 once the server accepts
            // the session, closing the pty with it (terminal showed
            // "[exited]" while the detached server kept proot/bash alive
            // invisibly). tmux is an OPTIONAL, non-core backend
            // (PRootLauncher.wrapWithTmux is its only seam); if a change ever
            // routes a CORE launch through it by accident, fail fast and
            // loudly here instead of silently breaking the terminal
            // lifecycle.
            //
            // Session Persistence: the explicit opt-in is the one sanctioned
            // exception — a workspace whose metadata set
            // `feature.terminal.persistence` reaches this check with the
            // tmux executable BY DESIGN (the wrap above records it in the
            // trace), and the core path (persistence off) still refuses.
            check(spec.executable != launcher.tmuxBinaryPath || persistenceRequested) {
                "refusing to run tmux as the core PTY child (executable=${spec.executable}); " +
                    "tmux is optional/non-core — use PRootLauncher.wrapWithTmux explicitly for the persistence backend"
            }
            // §1.145 (terminal-freeze fix): this call — and ONLY this call — must
            // run on the app's MAIN Looper thread. It is not an oversight, and it
            // must not be moved to another dispatcher. Two independent properties
            // of the terminal module require the constructing thread to be the
            // Android UI thread:
            //
            //  1. Looper capture. `TermuxPtySession` builds its `TerminalSession`
            //     in a property initializer, and that constructor assigns
            //     `mMainThreadHandler` by instantiating an inner Handler subclass
            //     whose own constructor calls the NO-ARG `android.os.Handler()`
            //     super — which adopts the constructing thread's Looper. On a
            //     Dispatchers.IO/Default worker (no Looper) that throws
            //     "Can't create handler inside thread that has not called
            //     Looper.prepare()", so the session fails to open at all — and
            //     that failure IS the device-reported "Session error:
            //     Can't create handler inside thread
            //     Thread[DefaultDispatcher-worker-N,5,main] …": the
            //     never-zero-terminals replacement open runs from the exit
            //     observer's Dispatchers.Default scope.
            //
            //  2. Single-threaded emulator access. The Handler above is the WRITER
            //     of emulator state (MSG_NEW_INPUT → `mEmulator.append(...)` →
            //     `notifyScreenUpdate()`), while `TerminalView.onDraw`/
            //     `onScreenUpdated` are the READERS, on the UI thread. Across all
            //     19 terminal backend classes only `ByteQueue` (the pty byte
            //     handoff) and `TerminalSession.isRunning`/`getExitStatus` are
            //     synchronized: `TerminalEmulator` and `TerminalBuffer` have no
            //     synchronized members, no monitor blocks and no volatile fields,
            //     and `TerminalBuffer.resize` reallocates its `mLines` array. The
            //     library is safe only because writer and reader are the SAME
            //     thread. Constructing on a HandlerThread would keep the Looper
            //     happy and silently split them, turning every screen update into
            //     an unsynchronized race against the renderer — a correctness
            //     regression traded for a latency win, and a change to session
            //     semantics rather than to dispatch.
            //
            // 2026-08-30 (session-lifecycle fix): the Main requirement is now
            // ENFORCED here — `withContext(Dispatchers.Main.immediate)` — instead
            // of being trusted from every caller. The direct paths (RuntimeService
            // start, TerminalsViewModel create) already ran on Main, but the
            // exit-observer replacement open ran on the manager's
            // Dispatchers.Default scope and crashed the TerminalSession
            // constructor (symptom 1 above). Dispatching at the single choke
            // point every pty creation passes through makes the contract hold for
            // ALL callers — current and future — deterministically, and keeps the
            // blocking host work around it on Dispatchers.IO exactly as before.
            // F-6: count this terminal in the dpkg-lock safety registry
            // BEFORE the pty child is forked inside create() — the native
            // fork happens there, and a slot registered only afterwards
            // would leave a window in which a concurrent exec's health check
            // observes an empty registry while the child is already coming
            // up. Released by the awaitExit watcher attached below (natural
            // exit or destroy), or here if the open itself fails.
            val sessionCounter = liveSessions
                .computeIfAbsent(workspaceId) { AtomicInteger() }
                .also { it.incrementAndGet() }
            val session = try {
                withContext(sessionCreationDispatcher) {
                    ptySessionFactory.create(
                        workspaceId = workspaceId,
                        executable = spec.executable,
                        args = spec.args,
                        env = spec.env,
                        // §1.75: HOST cwd for the forkpty child — deliberately NOT
                        // spec.workingDirectory. That value is the GUEST cwd in argv
                        // (`-w /root`, `HOME=/root`); the native forkpty chdirs on the
                        // HOST before proot/tmux exec, and `/root` does not exist on
                        // the Android host, so the guest path as host cwd killed the
                        // child with chdir("/root") before anything ran. filesDir is a
                        // guaranteed host directory; the guest still starts in /root
                        // once proot is live. See PRootLauncher.hostPtyWorkingDirectory.
                        workingDirectory = launcher.hostPtyWorkingDirectory,
                        // 2026-08-29 (appearance fix): the guest's actual HOME
                        // resolved to its HOST path (<rootfs><HOME>) — the terminal
                        // layer reads its own `.termux/colors.properties` and
                        // `.termux/font.ttf` from there. Only a String crosses this
                        // seam; the container layer stays free of terminal imports.
                        terminalConfigHome = spec.env["HOME"]?.let { home ->
                            File(rootfsPath, home.removePrefix("/")).absolutePath
                        }
                    )
                }
            } catch (e: Exception) {
                sessionCounter.decrementAndGet()
                throw e
            }
            runtimeTrace.record(workspaceId, "PtySessionFactory.create EXIT pid=${session.pid} isAlive=${session.isAlive}")
            Logger.i(TAG, "CHAIN: ptySessionFactory.create RETURNED pid=${session.pid} isAlive=${session.isAlive} elapsed=${elapsed(t0)}")
            sessions[key] = session
            // 2026-11-13: the terminal's shell can now invoke the guest
            // `sudo` helper. Start the mailbox only after the pty is live so
            // the first prompt never races a request without a reader.
            ensureRootExecBridge(workspaceId, rootfsPath)
            // F-6: the pty child (a PRoot that could run dpkg at any moment)
            // is live — attach the watcher that releases its pre-registered
            // slot on natural exit or destroy().
            watchSessionActivity(session)
            runtimeTrace.record(workspaceId, "openTerminal EXIT (session registered) terminalId=$terminalId")
            Logger.i(
                "ContainerRuntime",
                "TERMINAL_CHAIN: openTerminal end workspaceId=$workspaceId terminalId=$terminalId ptyId=${session.id} pid=${session.pid} isAlive=${session.isAlive}"
            )
            session
        } catch (e: Exception) {
            runtimeTrace.record(workspaceId, "openTerminal FAILURE ${e::class.simpleName}: ${e.message}")
            Logger.i(TAG, "CHAIN: openTerminal FAILURE ${e::class.simpleName}: ${e.message} elapsed=${elapsed(t0)}")
            // Nothing owns the scratch scope yet — drop it so a failed open
            // never leaves a stale PROOT_TMP_DIR behind.
            //
            // §1.145 (terminal-freeze fix): `releaseProotTmpDir` is a
            // `deleteRecursively()`, so it moves off Main with the rest of the
            // scratch-dir work. `NonCancellable` is load-bearing and not
            // defensive padding: `catch (e: Exception)` also catches
            // `CancellationException`, and a bare `withContext` in an already
            // cancelled coroutine throws before running its block — which would
            // turn "the user backed out mid-open" into a leaked PROOT_TMP_DIR.
            // The previous code was a plain blocking call and therefore always
            // ran; this keeps that guarantee exactly. It does not make anything
            // else uncancellable and does not touch the `throw e` below, so the
            // failure still propagates to the caller unchanged.
            withContext(NonCancellable + Dispatchers.IO) {
                bootstrapManager.releaseProotTmpDir(prootTmpScope(workspaceId, terminalId))
            }
            throw e
        }
    }

    override fun getTerminal(workspaceId: String, terminalId: String): PtySession? =
        sessions[TerminalKey(workspaceId, terminalId)]

    /**
     * §1.68: full child-startup forensics for the PTY launch path.
     *
     * `runProcess()` already dumps argv/env/path-metadata for one-shot execs,
     * but the pty path goes straight through `PtySessionFactory` →
     * `LibTerminalSession` (native forkpty) and never touches it. Everything
     * needed to diagnose "tmux / proot / shell exited 1 at startup" is logged
     * to logcat AND recorded into the [RuntimeTrace] (so it ships in the Copy
     * Error bundle without logcat): final argv, the complete final env, and a
     * file-metadata fingerprint of every HOST path on the exec chain.
     *
     * HOST and GUEST are reported separately and never mixed: host paths are
     * checked with `java.io.File` (which is HOST-truth), while guest paths are
     * probed INSIDE the rootfs by actually running them through proot (see
     * [probeGuestFilesystem]) — a host `File.exists("/root")` answers about
     * the Android host filesystem and always lies about guest content.
     */
    private suspend fun logExecForensics(workspaceId: String, rootfsPath: String, spec: LaunchSpec) {
        val lines = mutableListOf<String>()
        val fullArgv = listOf(spec.executable) + spec.args
        lines += "=== FORENSIC: PTY child full argv (§1.68) ==="
        fullArgv.forEachIndexed { i, arg ->
            val redacted = redactSecretArgv(arg)
            val truncated = if (redacted.length > 192) " (${redacted.length} chars, truncated)" else ""
            lines += "  argv[$i] = ${redacted.take(192)}$truncated"
        }

        lines += "=== FORENSIC: PTY child env (§1.68) =="
        val highlightedKeys = setOf(
            "PATH", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR",
            "TMPDIR", "HOME", "PWD", "LANG", "TERM", "USER", "TMUX", "TMUX_PANE"
        )
        spec.env.toSortedMap().forEach { (key, value) ->
            val mark = if (key in highlightedKeys) "   <--" else ""
            lines += "  $key = ${value.orEmpty()}$mark"
        }
        lines += "  unset in launch env: TMUX=${spec.env["TMUX"] ?: "<unset>"} TMUX_PANE=${spec.env["TMUX_PANE"] ?: "<unset>"} TMPDIR=${spec.env["TMPDIR"] ?: "<unset>"}"
        val ambient = System.getenv()
        lines += "  ambient app-process env: TMPDIR=${ambient["TMPDIR"] ?: "<unset>"} HOME=${ambient["HOME"] ?: "<unset>"} PATH=${ambient["PATH"] ?: "<unset>"}"

        lines += "=== FORENSIC: HOST filesystem (§1.68) — java.io.File is HOST-truth only ==="
        lines += "  exec chain: tmux-wrapped=${spec.executable == launcher.tmuxBinaryPath}"
        lines += fingerprintPath("executable(argv[0], host)", File(spec.executable))
        // Guest working directory (-w, HOME) is a GUEST path — never validated
        // with host java.io.File. The old line "workingDirectory(-w, guest cwd):
        // path=/root exists=false" probed a guest path with a host File and always
        // lied; the GUEST section below probes /root inside the rootfs via proot.
        lines += fingerprintPath("pty host workingDirectory (host, §1.75)", File(launcher.hostPtyWorkingDirectory))
        lines += "  host cwd is the GUEST -w /root only via argv: -w=${spec.args.getOrNull(spec.args.indexOf("-w") + 1)} HOME=${spec.env["HOME"] ?: "<unset>"} (guest, never chdir'd on host)"
        lines += fingerprintPath("rootfsPath (host staging dir)", File(rootfsPath))
        spec.env["PROOT_TMP_DIR"]?.let { lines += fingerprintPath("PROOT_TMP_DIR (host)", File(it)) }
        spec.env["PROOT_LOADER"]?.let { lines += fingerprintPath("PROOT_LOADER (host)", File(it)) }
        spec.env["LD_LIBRARY_PATH"]?.let { lines += fingerprintPath("LD_LIBRARY_PATH (host)", File(it)) }

        // The tmux binary path from PRootLauncher (nativeLibraryDir, not binDir).
        lines += fingerprintPath("tmuxBinary(nativeLibraryDir=${launcher.tmuxBinaryPath}, host)", File(launcher.tmuxBinaryPath))
        lines += if (launcher.isTmuxAvailable) "  tmux availability (host File.exists) = true" else "  tmux availability (host File.exists) = FALSE"
        lines += "  tmux managed-binary validation: ${bootstrapManager.validateBinary("tmux") ?: "OK"}"
        lines += "  tmux socket path: ${launcher.tmuxSocketPath(workspaceId)}"
        lines += fingerprintPath("tmux socket parent (host)", File(launcher.tmuxSocketPath(workspaceId)).parentFile ?: File("/"))
        lines += "  launch env TERMINFO=${spec.env["TERMINFO"] ?: "<unset>"} TERMINFO_DIRS=${spec.env["TERMINFO_DIRS"] ?: "<unset>"}"
        spec.env["TERMINFO"]?.takeIf { it.isNotEmpty() }?.let {
            lines += fingerprintPath("host terminfo vendored TERMINFO (§1.75, host)", File(it))
        }
        lines += fingerprintPath("host terminfo /usr/share/terminfo", File("/usr/share/terminfo"))
        lines += fingerprintPath("host terminfo /etc/terminfo", File("/etc/terminfo"))
        lines += fingerprintPath("host terminfo /lib/terminfo", File("/lib/terminfo"))
        hostTerminfoPaths(ambient).forEach { lines += fingerprintPath("host terminfo (from TERMINFO/TERMINFO_DIRS) $it", File(it)) }
        lines += fingerprintPath("host /tmp (tmux socket/lock dir)", File("/tmp"))

        lines += "=== FORENSIC: GUEST filesystem (§1.68) — probed inside the rootfs via proot, no host File.exists ==="
        lines += probeGuestFilesystem(rootfsPath, spec)

        val report = lines.joinToString("\n")
        Logger.i(TAG, report)
        // Mirror a bounded preview into the copyable runtime trace.
        runtimeTrace.record(workspaceId, "exec forensics (${report.length} chars):\n${report.take(2400)}")
    }

    /** §1.68: candidate host terminfo dirs from the ambient TERMINFO/TERMINFO_DIRS env (search order per terminfo(5)). */
    private fun hostTerminfoPaths(ambient: Map<String, String>): List<String> = buildList {
        ambient["TERMINFO"]?.takeIf { it.isNotEmpty() }?.let(::add)
        ambient["TERMINFO_DIRS"]?.takeIf { it.isNotEmpty() }?.split(":")?.forEach { if (it.isNotEmpty()) add(it) }
    }

    /**
     * §1.68: probe guest paths INSIDE the rootfs by actually running them
     * through proot itself — never with host `java.io.File.exists`.
     *
     * Launches the same proot binary/argv builder as the real session
     * ([PRootLauncher.buildLaunchSpec], so binds, -0, loader and env are
     * identical to the failing launch) with the command replaced by a
     * `/bin/sh -c` probe that runs `test -e` on each guest path of interest.
     * The whole guest rootfs contents are reachable at `/` inside proot, so
     * a path resolves relative to the GUEST root — if the entry is missing we
     * see "MISSING", if it exists we see dir/file. If the rootfs is so broken
     * that even `/bin/sh` can't start, proot's own exec error becomes the
     * evidence. Bounded by [PROBE_TIMEOUT_SECONDS] and dispatched to IO so a
     * wedged proot can't stall a caller on the UI dispatcher.
     */
    private suspend fun probeGuestFilesystem(rootfsPath: String, spec: LaunchSpec): String {
        val probeScript = """
            for p in /root /bin/bash /usr/bin/bash /bin/sh /etc/passwd /tmp /usr/share/terminfo /usr/share/terminfo/x/xterm-256color /etc/terminfo /lib/terminfo; do
              if [ -e "${'$'}p" ]; then
                if [ -d "${'$'}p" ]; then echo "guest OK dir   ${'$'}p"
                else                        echo "guest OK file  ${'$'}p"; fi
              else                          echo "guest MISSING ${'$'}p"; fi
            done
            echo "guest env: TERM=${'$'}TERM HOME=${'$'}HOME PWD=${'$'}(pwd)"
        """.trimIndent()
        val probeSpec = try {
            launcher.buildLaunchSpec(
                rootfsPath = rootfsPath,
                command = listOf("/bin/sh", "-c", probeScript),
                workingDirectory = "/",
                prootTmpDir = spec.env["PROOT_TMP_DIR"].orEmpty()
            )
        } catch (e: Exception) {
            return "  guest probe build failed: ${e::class.simpleName}: ${e.message}"
        }
        val seccompSafe = if (BootstrapManager.needsNoSeccomp) {
            probeSpec.copy(env = probeSpec.env + BootstrapManager.NO_SECCOMP_ENV)
        } else {
            probeSpec
        }
        val result = withContext(Dispatchers.IO) {
            runProcess(seccompSafe, timeoutSeconds = PROBE_TIMEOUT_SECONDS)
        }
        val probeOut = result.output.trim().lines()
            .filter { it.isNotBlank() }
            .joinToString("\n") { "  $it" }
        // Strip the shell-copyable echo line so the guest answers stand alone.
        val cleanOut = probeOut.lines()
            .filterNot { it.startsWith("  shell-copyable: ") }
            .joinToString("\n")
        return "  guest probe rc=${result.exitCode} (timeout/exec-fail=-1) — run inside rootfs via proot ${launcher.binaryPath}:\n$cleanOut"
    }

    override fun isTerminalAlive(workspaceId: String, terminalId: String): Boolean =
        sessions[TerminalKey(workspaceId, terminalId)]?.isAlive == true

    override suspend fun closeTerminal(workspaceId: String, terminalId: String) {
        val key = TerminalKey(workspaceId, terminalId)
        var closed: PtySession? = null
        val noRemaining = sessionsLock.withLock {
            closed = sessions.remove(key)
            sessions.keys.none { it.workspaceId == workspaceId }
        }
        closed?.destroy()
        // 2026-11-13: no shell remains that could issue a `sudo`, so stop the
        // mailbox and remove its marker. The manager may immediately open a
        // replacement terminal (§1.106); that open starts it again.
        if (noRemaining) stopRootExecBridge(workspaceId)
        // §1.48/§1.97: this terminal owned its own PROOT_TMP_DIR scope —
        // release it now that this terminal's process is gone. Other
        // terminals in the same workspace keep their scopes.
        bootstrapManager.releaseProotTmpDir(prootTmpScope(workspaceId, terminalId))
    }

    override suspend fun closeWorkspaceSessions(workspaceId: String) {
        // 2026-11-13: stop accepting new sudo requests before the shells that
        // could send them are destroyed. In-flight privileged commands keep
        // their own lifetime (the bridge never cancels a claimed request).
        stopRootExecBridge(workspaceId)
        val keys = sessionsLock.withLock {
            sessions.keys.filter { it.workspaceId == workspaceId }
        }
        // Destroy every terminal's pty first (under the lock), then the
        // workspace-scoped teardown. §1.58: destroying the pty only kills the
        // tmux *client*; the detached tmux server (proot + guest shell)
        // survives without this, so "stop" must explicitly tear the server
        // down to actually stop the workspace.
        keys.forEach { key ->
            sessionsLock.withLock {
                sessions.remove(key)
            }?.destroy()
            bootstrapManager.releaseProotTmpDir(prootTmpScope(key.workspaceId, key.terminalId))
        }
        killTmuxServer(workspaceId)
        // §1.83: the workspace owns its guest daemons (Xvnc). Stop them when
        // the workspace stops so a stopped workspace doesn't leave a daemon
        // host bound to the VNC port. Idempotent — no-op when none are running.
        stopAllDaemons(workspaceId)
        // 2026-09 GPU work: the workspace owns its host GPU server too. Stop
        // it after the daemons (an Xvnc that might still hold a vtest client
        // connection dies first) so the server is never killed under a live
        // client. Idempotent — no-op when the feature never started.
        gpuSupervisor.stopFor(workspaceId)
        // §1.99: the workspace session is over — reset the first-open forensics
        // latch so the NEXT session's first terminal re-captures the cold-start
        // dump (the diagnostic is for a fresh launch, not a reused workspace).
        execForensicsDone.remove(workspaceId)
    }

    /**
     * §1.97/W5 — see [ContainerRuntime.startupRecoverySweep]. A fresh process
     * holds no live sessions/execs/daemons, so EVERY child of the shared
     * `proot_tmp` parent is stale (per-terminal `$workspace/$terminal` scopes,
     * one-shot `exec()` UUID scopes, daemon scopes) and every workspace whose
     * directory still carries a `.tmux.sock` has a dead server. Reuses the
     * existing idempotent primitives ([BootstrapManager.releaseProotTmpDir],
     * [killTmuxServer]); each step is individually failure-tolerant so a
     * wedged delete can never abort the rest of the sweep. Bounded by the
     * lifecycle manager's teardown bound around this call.
     */
    override suspend fun startupRecoverySweep(): RecoverySweepReport {
        var releasedScopes = 0
        var staleSocketsCleaned = 0
        var failures = 0
        withContext(Dispatchers.IO) {
            // 1. Stale PROOT_TMP_DIR scopes: nothing in this fresh process
            //    references any of them, so release every child of the parent.
            bootstrapManager.prootTmpDir.listFiles()?.forEach { child ->
                try {
                    bootstrapManager.releaseProotTmpDir(child.name)
                    releasedScopes++
                    Logger.i(TAG, "startup sweep: released stale PROOT_TMP_DIR scope ${child.name}")
                } catch (e: Exception) {
                    failures++
                    Logger.w(TAG, "startup sweep: failed to release stale scope ${child.name}: ${e.message}")
                }
            }
            // 2. Stale tmux sockets: only workspaces whose directory still
            //    carries a control socket from a dead run get touched. The
            //    kill-server is best-effort ("no server running" is a normal
            //    outcome, §1.58); the leftover socket file is then removed.
            fileManager.listWorkspaceIds().forEach { workspaceId ->
                val socket = TmuxPersist.socketFile(fileManager.getWorkspaceBasePath(workspaceId))
                if (!socket.exists()) return@forEach
                try {
                    killTmuxServer(workspaceId)
                    if (socket.exists() && !socket.delete()) {
                        Logger.w(TAG, "startup sweep: stale tmux socket for $workspaceId not removed")
                    }
                    staleSocketsCleaned++
                    Logger.i(TAG, "startup sweep: cleaned stale tmux socket for $workspaceId")
                } catch (e: Exception) {
                    failures++
                    Logger.w(TAG, "startup sweep: failed to clean tmux socket for $workspaceId: ${e.message}")
                }
            }
            // 3. 2026-09 GPU work: orphaned host VirGL servers. The server is a
            //    plain child process, so a previous process death (force-stop,
            //    LMK) can leave it running with a stale socket. Best-effort
            //    kill over the caller's own-uid /proc; the count is logged
            //    (not folded into [RecoverySweepReport] — its schema is pinned
            //    and GPU cleanup has no partial-failure state to report).
            val staleGpuKilled = gpuSupervisor.killStaleServers()
            if (staleGpuKilled > 0) {
                Logger.i(TAG, "startup sweep: killed $staleGpuKilled stale GPU host server(s)")
            }
            // 4. Pre-release audit fix (2026-09-02, finding RT-2): orphaned
            //    workspace process trees. Daemon liveness was in-memory only,
            //    so after the app process died (LMK / force-stop) with a
            //    desktop or ssh daemon running, the daemon-host proot AND the
            //    guest daemon survived as orphans; on relaunch the sweep never
            //    saw them and the next Xvnc start failed with "address already
            //    in use". A fresh process owns NO live workspace processes, so
            //    every own-uid process whose /proc cmdline still references a
            //    workspace rootfs path is stale — SIGKILL it (the same
            //    /proc-scan contract as the GPU sweep, one marker per
            //    workspace so other workspaces' processes are never touched).
            fileManager.listWorkspaceIds().forEach { workspaceId ->
                try {
                    val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
                    // livePtyPids() is normally empty here (fresh process),
                    // but excluding live ptys makes the sweep safe even if a
                    // start raced it.
                    val killed = killRootfsProcesses(rootfsPath, sigterm = false, excludePids = livePtyPids())
                    if (killed > 0) {
                        Logger.w(TAG, "startup sweep: killed $killed stale workspace process(es) of $workspaceId (orphaned by a previous app process)")
                    }
                } catch (e: Exception) {
                    failures++
                    Logger.w(TAG, "startup sweep: stale-process scan failed for $workspaceId: ${e.message}")
                }
            }
        }
        Logger.i(TAG, "startup recovery sweep complete: releasedScopes=$releasedScopes staleSocketsCleaned=$staleSocketsCleaned failures=$failures")
        return RecoverySweepReport(releasedScopes, staleSocketsCleaned, failures)
    }

    /**
     * §1.58: best-effort `tmux -S <sock> kill-server` for [workspaceId].
     * tmux is a host-side static binary, so this runs without proot and
     * needs no special environment; "no server running" is a normal outcome
     * (exit 1), not an error. Bounded wait so a wedged server can't block
     * session teardown.
     */
    private suspend fun killTmuxServer(workspaceId: String) = withContext(Dispatchers.IO) {
        if (!launcher.isTmuxAvailable) return@withContext
        val command = listOf(launcher.tmuxBinaryPath) + launcher.tmuxKillServerArgs(workspaceId)
        try {
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            // tmux is a dynamic binary (libevent/ncurses from bootstrap/lib) —
            // same full-replacement env shape the pty launch path uses.
            pb.environment().clear()
            pb.environment().putAll(bootstrapManager.processEnv())
            val process = pb.start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Logger.w("ContainerRuntime", "tmux kill-server for $workspaceId timed out")
            }
        } catch (e: IOException) {
            Logger.w("ContainerRuntime", "tmux kill-server failed for $workspaceId: ${e.message}")
        }
    }

    override suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult =
        execInternal(workspaceId, rootfsPath, command, onLine = null, timeoutSeconds = COMMAND_TIMEOUT_SECONDS)

    override suspend fun execStreaming(
        workspaceId: String,
        rootfsPath: String,
        command: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit
    ): CommandResult = execInternal(workspaceId, rootfsPath, command, onLine = onLine, timeoutSeconds = timeoutSeconds)

    /**
     * §1.92: shared implementation of [exec] and [execStreaming]. When
     * [onLine] is non-null, each output line is streamed to it live; the
     * captured [CommandResult] is identical either way. Streaming uses
     * [timeoutSeconds] (defaults to [OPERATION_TIMEOUT_SECONDS] for
     * long-running package operations) instead of the query-oriented 120s.
     */
    private suspend fun execInternal(
        workspaceId: String,
        rootfsPath: String,
        command: String,
        onLine: ((String) -> Unit)?,
        timeoutSeconds: Long = OPERATION_TIMEOUT_SECONDS
    ): CommandResult =
        withContext(Dispatchers.IO) {
            ensureBootstrap()
            // LX-2: single-owner repair section. All guest-rootfs guarantees
            // below (and their openTerminal/startDaemon counterparts) are
            // serialized per workspace, so concurrent execs can no longer
            // interleave read-modify-write repairs inside the same rootfs.
            // The section is bounded to repairs — the guest process launch
            // below happens OUTSIDE the init mutex.
            val (dpkgReport, guestIdle, execCounter) = workspaceInitLock(workspaceId).withLock {
                // §1.80: same guest /tmp guarantee as openTerminal, so one-shot
                // commands (package manager's apt, features, file ops) never hit
                // "unable to mkstemp /tmp/...". Fail-open here: a one-shot exec
                // should not be blocked by an exceptional chmod failure, but it
                // must be logged loudly — the session path (openTerminal) is the
                // strict gate that throws instead.
                guestRootfsInitializer.ensureGuestTmp(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /tmp could not be prepared for $workspaceId — continuing exec: $message")
                }
                // F-5: same fail-open /dev/shm guarantee for one-shot execs
                // (package-manager ops and feature provisioners are exactly the
                // paths whose consumers need shm).
                guestRootfsInitializer.ensureGuestDevShm(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /dev/shm could not be prepared for $workspaceId — continuing exec: $message")
                }
                // R-5: same fail-open /run guarantee for one-shot execs
                // (package-manager init scripts, dbus, XDG_RUNTIME_DIR consumers).
                guestRootfsInitializer.ensureGuestRun(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /run could not be prepared for $workspaceId — continuing exec: $message")
                }
                // R-6: same fail-open /proc bind-over snapshots for one-shot execs
                // (vmstat, uptime, btop may be invoked as one-shot commands too).
                guestRootfsInitializer.ensureProcSnapshots(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /proc snapshots could not be prepared for $workspaceId — continuing exec: $message")
                }
                // Host info layer: same goal, host-backed values. Fail-open here
                // matches the exec path's overall posture.
                refreshHostProcOverlays(File(rootfsPath), workspaceId, strict = false)
                // LX-1: same guest supplementary-group reconciliation as
                // openTerminal — one-shot execs (package-manager processes,
                // feature installers) inherit the same live host group set and
                // must resolve it too. Fail-open like the exec path itself.
                guestRootfsInitializer.ensureGuestGroups(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest supplementary-group reconciliation failed for $workspaceId — continuing exec: $message")
                }
                // Issue #1 (2026-09-03): same normal-user account guarantee as
                // openTerminal — one-shot execs (file-manager operations in
                // the user's home, first-boot provisioning) must find the
                // account and home even before the first terminal opened.
                // The exec itself stays root-emulated: it IS one of the
                // privileged operations the normal user delegates to Linexa.
                runCatching {
                    val execMeta = workspaceRootFsMetadata.of(workspaceId)
                    val execGuestEnv = GuestEnvironmentResolver.resolve(execMeta)
                    guestRootfsInitializer.ensureGuestUser(
                        File(rootfsPath),
                        workspaceId,
                        preferredUser = execGuestEnv.user,
                        preferredHome = execGuestEnv.home
                    )
                }.onFailure { e ->
                    Logger.w(TAG, "guest user ensure failed for $workspaceId exec — continuing: ${e.message}")
                }

                // Phase 6b bug-fix: same dpkg-health check as openTerminal, but
                // for one-shot execs (Package Manager UI's apt invocations, File
                // Manager ops, Feature toggles). When the guest's dpkg database is
                // in a partial state from a previously-killed transaction, prepend
                // `dpkg --configure -a &&` to the user's command so recovery
                // happens *before* the user's command runs — otherwise apt would
                // surface as the exact "Sub-process /usr/bin/dpkg returned an
                // error code (1)" the user reported, even when the cause was an
                // interrupted install on a previous launch. Lock cleanup is
                // host-side file ops only; the auto-configure is the guest-side
                // one-shot exec, which is what the user's command was going to be
                // anyway, so the overhead is one extra PRoot startup only when
                // recovery is needed.
                //
                // F-6/LX-2: the idleness consult and THIS exec's activity
                // registration are now ONE atomic section under [dpkgHealthLock]
                // (they were two, leaving a window where a second caller could
                // consult an empty registry and act on a stale "idle"). The
                // registration happens BEFORE the process can spawn and is
                // released when the process has exited — a concurrent caller can
                // never delete dpkg locks under OUR running dpkg, nor act on a
                // recovery decision that predates ours.
                val (report, idle) = dpkgHealthLock.withLock {
                    val idle = !hasLiveGuestActivity(workspaceId)
                    guestRootfsInitializer.ensureDpkgHealthy(
                        File(rootfsPath),
                        workspaceId,
                        allowLockCleanup = idle
                    ) to idle
                }
                // LX-4 (2026-09-01): same queueing behavior for one-shot execs
                // as for sessions — Package-Manager-UI apt commands wait for
                // an apt running in a terminal instead of failing on the
                // native lock. Idempotent host-side file op; see
                // GuestRootfsInitializer.ensureAptLockWait.
                guestRootfsInitializer.ensureAptLockWait(File(rootfsPath), workspaceId)
                val counter = inflightExecs.computeIfAbsent(workspaceId) { AtomicInteger() }
                dpkgHealthLock.withLock { counter.incrementAndGet() }
                Triple(report, idle, counter)
            }
            // LX-2: `dpkg --configure -a` recovery is prepended only when the
            // workspace was guest-idle at consult time. While live activity
            // exists, the "partial state" evidence may be the LIVE transaction's
            // own artifacts — prepending recovery then would make Linexa fight
            // the very process that owns the lock (the reported "Waiting for
            // cache lock … held by process NNNN (apt)"). Deferred recovery is
            // safe: dpkg's own fcntl locking governs every intervening user
            // operation, and the next idle launch repairs genuinely-dead state.
            val effectiveCommand = when {
                dpkgReport.needsConfigure && guestIdle -> {
                    Logger.i(TAG, "CHAIN: prepending dpkg --configure -a to one-shot exec for $workspaceId")
                    "dpkg --configure -a 2>&1 | tail -5 ; $command"
                }
                dpkgReport.needsConfigure -> {
                    val notice = "dpkg --configure -a recovery DEFERRED for $workspaceId exec: live guest " +
                        "activity may own the dpkg database (LX-2) — running the user's command under native dpkg locking"
                    Logger.i(TAG, notice)
                    runtimeTrace.record(workspaceId, "dpkg --configure -a exec DEFERRED (live guest activity)")
                    command
                }
                else -> command
            }
            // §1.48: one-shot exec gets its own throwaway scope (UUID), so a
            // long-running prompt in one workspace can't share scratch with a
            // concurrent command in another (Issue 2 — shared mutable tmp).
            val scope = "exec-${UUID.randomUUID()}"
            val tmp = bootstrapManager.scopedProotTmpDir(scope)
            try {
                // §1.73: one-shot exec always uses the fixed /bin/sh -c bridge,
                // never the config's interactive launch shell — that override
                // is for the login session only. Extra binds DO apply here so
                // a workspace with extra mounts sees them in one-shot commands
                // too (package manager, features, file ops).
                val config = loadWorkspaceConfig(workspaceId)
                var spec = applyHostInfoEnv(
                    workspaceId,
                    applyConfigEnv(
                        workspaceId,
                        launcher.buildLaunchSpec(
                            rootfsPath,
                            command = listOf("/bin/sh", "-c", effectiveCommand),
                            prootTmpDir = tmp.absolutePath,
                            extraBindMounts = config?.launch?.bindMounts.orEmpty(),
                            includeSharedStorage = isSharedStorageEnabled(workspaceId)
                        ),
                        config
                    ),
                    config
                ).let { base ->
                    // 2026-09 GPU work: one-shot execs merge the GPU env ONLY
                    // when the workspace's server is already running — a
                    // package-manager exec must never silently spin up a GPU
                    // process. Sessions and daemons own that decision.
                    val gpuEnv = gpuSupervisor.envIfRunning(workspaceId)
                    if (gpuEnv.isEmpty()) base else base.copy(env = base.env + gpuEnv)
                }
                // F-7 (2026-08-25 audit): the one-shot exec bridge has NO TTY
                // and its stdin is a pipe nobody will ever write to — any
                // debconf prompt above default priority would therefore stall
                // until the operation timeout instead of being answerable.
                // Layering DEBIAN_FRONTEND=noninteractive here makes apt/dpkg
                // take documented defaults instead of blocking: exactly the
                // semantics a headless bridge can honor.
                //
                // Scope is deliberate: THIS LAYER ONLY (every one-shot exec —
                // Package Manager UI operations, the dpkg --configure -a
                // preflight, and feature provisioners' apt-get installs, which
                // are the audit's stall cases). It is layered AFTER
                // applyConfigEnv and therefore WINS over a config.yaml
                // `linexa.env` DEBIAN_FRONTEND entry on purpose: a prompt on
                // this bridge can never be answered, so any config value that
                // would reintroduce one (readline/dialog) is the F-7 stall by
                // another route. Interactive terminal sessions are untouched —
                // this is never added to PRootLauncher.buildLaunchSpec or
                // BootstrapManager, whose base env feeds them.
                spec = spec.copy(env = spec.env + NONINTERACTIVE_EXEC_ENV)
                if (BootstrapManager.needsNoSeccomp) {
                    spec = spec.copy(env = spec.env + BootstrapManager.NO_SECCOMP_ENV)
                }
                // LX-2 / issue #2: Linexa-initiated package operations of one
                // workspace are single-owner — a second one waits here instead
                // of starting and fighting the first for the dpkg locks. The
                // coordinator also publishes who owns the queue and for how
                // long, which is the diagnostics the raw apt lock error never
                // offered. Every other (non-package) exec path, and every
                // user-typed terminal command, is untouched: native apt/dpkg
                // locking remains the only authority for manual use.
                val result = if (isPackageOperation(effectiveCommand)) {
                    packageOpCoordinator.runExclusive(workspaceId, effectiveCommand) {
                        runGuestCommand(workspaceId, spec, timeoutSeconds, onLine)
                    }
                } else {
                    runGuestCommand(workspaceId, spec, timeoutSeconds, onLine)
                }
                if (result.exitCode == -1) {
                    CommandResult(exitCode = -1, output = result.output, error = "Command timed out after $timeoutSeconds seconds")
                } else {
                    CommandResult(
                        exitCode = result.exitCode,
                        output = result.output,
                        error = if (result.exitCode != 0) result.output else ""
                    )
                }
            } finally {
                // §1.48: scope is single-use — clean it up regardless of exit.
                // F-6: release the exec's activity slot with it.
                execCounter.decrementAndGet()
                bootstrapManager.releaseProotTmpDir(scope)
            }
        }

    /**
     * LX-2: one bounded guest process run plus the §1.50 seccomp fallback
     * retry, extracted verbatim from [execInternal] so the package-operation
     * mutex can wrap it without dragging the spec-construction noise along.
     * On the seccomp signature the memo flag is flipped and the SAME spec is
     * retried once with `PROOT_NO_SECCOMP=1` — behavior is unchanged.
     */
    private fun runGuestCommand(
        workspaceId: String,
        spec: LaunchSpec,
        timeoutSeconds: Long,
        onLine: ((String) -> Unit)?
    ): ProcessResult {
        var result = runProcess(spec, timeoutSeconds = timeoutSeconds, onLine = onLine)
        // §1.50: detect the known seccomp/kernel-incompatibility
        // signature and retry once with PROOT_NO_SECCOMP=1. Only
        // fires on a genuine signature (verified against the shipped
        // binary), never on a generic non-zero exit. Logs loudly so
        // the device-specific quirk is visible in diagnostics, not
        // silently masked.
        if (result.exitCode != 0 && bootstrapManager.isSeccompKernelBugFailure(result.output) && !BootstrapManager.needsNoSeccomp) {
            Logger.w(
                TAG,
                "Seccomp/kernel-incompatibility detected in $workspaceId exec — retrying once with PROOT_NO_SECCOMP=1 (§1.50)"
            )
            BootstrapManager.needsNoSeccomp = true
            result = runProcess(spec.copy(env = spec.env + BootstrapManager.NO_SECCOMP_ENV), timeoutSeconds = timeoutSeconds, onLine = onLine)
        }
        return result
    }

    // ── §1.83 guest-daemon host ───────────────────────────────────────────
    //
    // A guest daemon (Xvnc now, sshd later) must OUTLIVE the call that
    // launched it, which a one-shot exec can never do: exec's PRoot runs
    // with --kill-on-exit, so the moment its /bin/sh -c command finishes
    // (which it does the instant a `Xvnc ... &` background job is forked),
    // PRoot reaps every remaining tracee and the daemon dies before the
    // VNC port is ever probed (audit §4.2).
    //
    // The daemon host solves this by making the daemon the FOREGROUND
    // command of its own dedicated, persistent PRoot process:
    //   proot ... /bin/sh -c 'exec <command>'
    // `exec` replaces the shell, so the daemon IS the initial tracee. PRoot
    // stays alive exactly as long as the daemon does (the VNC port stays
    // bound across the launching call), and --kill-on-exit is still correct:
    // when the daemon exits, PRoot tears itself down. Stop sends SIGTERM to
    // the PRoot host, which PRoot forwards to the initial tracee (the
    // daemon), then force-kills the host if it doesn't exit in time.
    //
    // Each daemon gets its own throwaway PROOT_TMP_DIR scope (like a
    // one-shot exec), released on stop so a stopped daemon can't leak
    // scratch. The same seccomp fallback, config env and shared-storage
    // toggle resolution as sessions/exec are applied, so a daemon sees
    // exactly the mount/env set the workspace's interactive session does.

    /**
     * §1.83: [ContainerRuntime.startDaemon]. Builds a dedicated PRoot host
     * running the daemon as its foreground command (see the block comment
     * above). If a daemon with the same name is already alive for the
     * workspace, it is stopped first — restart is idempotent.
     */
    override suspend fun startDaemon(
        workspaceId: String,
        rootfsPath: String,
        name: String,
        command: String
    ): Boolean = daemonsLock.withLock {
        val key = workspaceId to name
        val existing = daemons[key]
        if (existing != null && existing.process.isAlive) {
            Logger.i(TAG, "§1.83 daemon '$name' already alive for $workspaceId — restarting")
            daemons.remove(key)
            // §8: an idempotent restart is an intentional stop for the incumbent.
            existing.stopping.set(true)
            stopDaemonProcess(existing)
            // Pre-release audit fix (2026-09-02, finding RT-4): the restart
            // path removed the map entry and marked the stop intentional, so
            // the exit watcher returns early ("the stopper already removed
            // the entry and released the scope") — but the stopper never
            // released the incumbent's PROOT_TMP_DIR scope. Release it here,
            // or every idempotent restart leaked a scratch dir until the
            // next process's startup sweep.
            runCatching { bootstrapManager.releaseProotTmpDir(existing.scope) }
                .onFailure { Logger.w(TAG, "restart: releasing the old daemon scope failed: ${it.message}") }
        }
        // Issue #1: resolved inside the repair section below; default root so
        // a resolution failure keeps the historical (root-emulated) behavior.
        var daemonIdentity = GuestIdentity.root()
        withContext(Dispatchers.IO) {
            ensureBootstrap()
            // LX-2: same single-owner repair section as openTerminal/exec —
            // a daemon start races the same rootfs repairs.
            workspaceInitLock(workspaceId).withLock {
                // §1.80: same guest /tmp guarantee as openTerminal/exec, fail-open.
                guestRootfsInitializer.ensureGuestTmp(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /tmp could not be prepared for $workspaceId daemon '$name' — continuing: $message")
                }
                // F-5: same fail-open /dev/shm guarantee for daemons.
                guestRootfsInitializer.ensureGuestDevShm(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /dev/shm could not be prepared for $workspaceId daemon '$name' — continuing: $message")
                }
                // R-5: same fail-open /run guarantee for daemons (dbus, sshd,
                // pipewire all write pid files to /run).
                guestRootfsInitializer.ensureGuestRun(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /run could not be prepared for $workspaceId daemon '$name' — continuing: $message")
                }
                // R-6: same fail-open /proc snapshots for daemons (many daemons
                // read /proc/loadavg or /proc/stat at startup).
                guestRootfsInitializer.ensureProcSnapshots(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest /proc snapshots could not be prepared for $workspaceId daemon '$name' — continuing: $message")
                }
                // LX-1: guest daemons (Xvnc, sshd) inherit the same live host
                // supplementary-group set as terminals and execs — reconcile the
                // guest group db for them too. Fail-open like the daemon path.
                guestRootfsInitializer.ensureGuestGroups(File(rootfsPath), workspaceId)?.let { message ->
                    Logger.w(TAG, "guest supplementary-group reconciliation failed for $workspaceId daemon '$name' — continuing: $message")
                }
                // Issue #1 (2026-09-03): daemons that are part of the USER's
                // desktop environment (Xvnc, the X session) run as the
                // normal-user account — a desktop is not a privileged
                // operation. Root-listed daemons (sshd: port <1024 binding
                // semantics, host keys, /run/sshd) keep the root emulation.
                // Resolution lives with the account ensure so the identity is
                // always the on-disk account's.
                val declaredMeta = workspaceRootFsMetadata.of(workspaceId)
                val daemonGuestEnv = GuestEnvironmentResolver.resolve(declaredMeta)
                daemonIdentity = guestRootfsInitializer.ensureGuestUser(
                    File(rootfsPath),
                    workspaceId,
                    preferredUser = daemonGuestEnv.user,
                    preferredHome = daemonGuestEnv.home
                )
                // Host info layer for daemons, same fail-open posture.
                refreshHostProcOverlays(File(rootfsPath), workspaceId, strict = false)
            }
            val scope = "daemon-${UUID.randomUUID()}"
            val tmp = bootstrapManager.scopedProotTmpDir(scope)
            // Issue #1: the user-desktop daemons run as the normal-user
            // account; anything on the root list (sshd) keeps the root
            // emulation. HOME/cwd follow the identity either way.
            val identityForDaemon =
                if (name.substringAfterLast('/').lowercase() in ROOT_DAEMONS) GuestIdentity.root()
                else daemonIdentity
            try {
                val config = loadWorkspaceConfig(workspaceId)
                // §1.83: `exec` so the daemon replaces the shell and becomes
                // the initial tracee — the process then lives as long as the
                // daemon, and --kill-on-exit maps to a clean teardown.
                val baseSpec = launcher.buildLaunchSpec(
                    rootfsPath,
                    command = listOf("/bin/sh", "-c", "exec $command"),
                    workingDirectory = identityForDaemon.home,
                    homeDirectory = identityForDaemon.home,
                    prootTmpDir = tmp.absolutePath,
                    extraBindMounts = config?.launch?.bindMounts.orEmpty(),
                    includeSharedStorage = isSharedStorageEnabled(workspaceId),
                    identity = identityForDaemon
                )
                val spec = applyHostInfoEnv(
                    workspaceId,
                    applyConfigEnv(
                        workspaceId,
                        resolveSeccompFallback(baseSpec),
                        config
                    ),
                    config
                ).let { base ->
                    // 2026-09 GPU work: daemons (Xvnc) are guest GL consumers
                    // too, so they provision the same way sessions do. The
                    // supervisor is per-workspace and idempotent — an
                    // already-running server returns its env without a second
                    // process.
                    val gpuEnv = gpuSupervisor.provisionForLaunch(
                        workspaceId = workspaceId,
                        rootfsPath = rootfsPath,
                        enabled = isGpuEnabled(workspaceId)
                    )
                    if (gpuEnv.isEmpty()) base else base.copy(env = base.env + gpuEnv)
                }
                // Forensics mirror of runProcess — a daemon's argv/env is as
                // important to diagnose as a session's, and as of §16.3 (§1.167)
                // it is gated the same way. See [logDaemonForensics].
                if (DebugCapture.isEnabled()) logDaemonForensics(spec, name)

                val process = launchDaemonProcess(spec, workspaceId, name)
                // Pre-release audit fix (2026-09-02, finding RT-1): carry the
                // rootfs path so the stop path can sweep the guest daemon
                // tree (proot's SIGKILLed host cannot run --kill-on-exit —
                // the guest daemon would survive holding its port).
                val managed = ManagedDaemon(process = process, scope = scope, rootfsPath = rootfsPath)
                daemons[key] = managed
                // §8 process supervision: observe this daemon's exit. An
                // unexpected death degrades the workspace (health registry →
                // RuntimeState.Running.health) instead of going unnoticed.
                watchDaemon(workspaceId, name, managed)
                Logger.i(TAG, "§1.83 daemon '$name' started for $workspaceId alive=${process.isAlive}")
                // Desktop Zoom work (2026-08): a successful (re)start clears any
                // prior unexpected-exit degradation for this daemon, and the
                // aggregate is re-published so consumers observe the recovery.
                // Without this, a restarted desktop would inherit the dead
                // predecessor's degradation forever — the health surface would
                // contradict the live daemons it is meant to describe.
                healthRegistry.reportHealthy(
                    workspaceId,
                    RuntimeDegradation.DAEMON_COMPONENT_PREFIX + name
                )
                sessionManager.refreshHealth(workspaceId)
                process.isAlive
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Pre-release audit fix (RT-9): a cancelled start must not
                // swallow the cancellation into `false` — rethrow so the
                // caller's scope unwinds properly.
                runCatching { bootstrapManager.releaseProotTmpDir(scope) }
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "§1.83 daemon '$name' start failed for $workspaceId: ${e.message}", e)
                bootstrapManager.releaseProotTmpDir(scope)
                false
            }
        }
    }

    override suspend fun stopDaemon(workspaceId: String, name: String) {
        val key = workspaceId to name
        val managed = daemonsLock.withLock {
            val m = daemons.remove(key)
            // §8: mark the stop as intentional BEFORE the process can die, so
            // the exit watcher never reports an intentional stop as a crash.
            m?.stopping?.set(true)
            m
        } ?: return
        withContext(Dispatchers.IO) {
            stopDaemonProcess(managed)
            bootstrapManager.releaseProotTmpDir(managed.scope)
        }
        Logger.i(TAG, "§1.83 daemon '$name' stopped for $workspaceId")
    }

    override fun isDaemonAlive(workspaceId: String, name: String): Boolean {
        val managed = daemons[workspaceId to name] ?: return false
        return managed.process.isAlive
    }

    override suspend fun stopAllDaemons(workspaceId: String) {
        val keys = daemonsLock.withLock { daemons.keys.filter { it.first == workspaceId } }
        keys.forEach { (_, name) -> stopDaemon(workspaceId, name) }
    }

    /**
     * §1.83: launch one dedicated, long-lived PRoot host for a guest daemon.
     * Unlike [runProcess] (bounded, drains output) this redirects output to
     * a per-daemon log file so a daemon that writes to stdout/stderr can't
     * block on a full pipe, and returns immediately with the live Process.
     */
    private fun launchDaemonProcess(spec: LaunchSpec, workspaceId: String, name: String): Process {
        val logDir = File(fileManager.getWorkspaceBasePath(workspaceId), "logs")
        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, "daemon-$name.log")
        val pb = ProcessBuilder(listOf(spec.executable) + spec.args)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        // §1.49: same full-replacement env shape as every other launch path.
        pb.environment().clear()
        pb.environment().putAll(spec.env)
        return pb.start()
    }

    /**
     * §1.83: stop a daemon's PRoot host — SIGTERM first (PRoot forwards it
     * to the initial tracee = the daemon, a graceful shutdown), then SIGKILL
     * the host if it hasn't exited within [DAEMON_STOP_GRACE_MS]. Bounded so
     * a wedged daemon can't block teardown.
     *
     * Pre-release audit fix (2026-09-02, finding RT-1 — the audit's highest
     * -severity runtime issue): `Process.destroy*()` signals the DIRECT child
     * only. PRoot's `--kill-on-exit` reaper is an exit handler and therefore
     * never runs under SIGKILL, so the SIGKILL path left the GUEST daemon
     * (Xvnc, sshd) alive as an orphan — still bound to its port, invisible
     * to [daemons] (in-memory only), and the next start failed with
     * "address already in use". The stop now ALSO sweeps any surviving
     * own-uid process whose /proc cmdline references this daemon's rootfs
     * (proot passes the translated host path to the real execve, so every
     * guest process of this workspace carries the rootfs path in its
     * cmdline; other workspaces' processes never match). SIGTERM first,
     * bounded SIGKILL after — mirroring the host's own escalation.
     */
    private fun stopDaemonProcess(managed: ManagedDaemon) {
        if (!managed.process.isAlive) {
            killRootfsProcesses(managed.rootfsPath, sigterm = true)
            return
        }
        // Phase 04: capture the PRoot host's start time BEFORE destroying it —
        // the sweep's PID-reuse guard below is only meaningful while the
        // launch's own start time is known.
        val hostStartTicks = processHostPid(managed.process)
            ?.let { RootfsProcessMatcher.startTimeTicksOf(it) }
        managed.process.destroy()
        if (!managed.process.waitFor(DAEMON_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
            Logger.w(TAG, "§1.83 daemon host did not exit within ${DAEMON_STOP_GRACE_MS}ms — force-killing")
            managed.process.destroyForcibly()
        }
        // Guest-tree sweep: SIGTERM survivors (graceful daemons shut down
        // here), then SIGKILL anything still holding on after the grace.
        // Live pty sessions are excluded — a same-workspace terminal must
        // survive its workspace's daemon stop.
        val termKilled = killRootfsProcesses(
            managed.rootfsPath,
            sigterm = true,
            excludePids = livePtyPids(),
            notBeforeTicks = hostStartTicks
        )
        if (termKilled > 0) {
            Thread.sleep(DAEMON_STOP_GRACE_MS)
            killRootfsProcesses(
                managed.rootfsPath,
                sigterm = false,
                excludePids = livePtyPids(),
                notBeforeTicks = hostStartTicks
            )
        }
    }

    /** Host pids of this process's live pty sessions — the sweep's exclusion set. */
    private fun livePtyPids(): Set<Int> =
        sessions.values.mapNotNull { session ->
            session.pid.takeIf { it > 0 && session.isAlive }
        }.toSet()

    /**
     * Kill every own-uid process whose /proc cmdline references [rootfsPath]
     * — the guest process tree of one workspace — EXCLUDING [excludePids]
     * (the live pty sessions: a workspace's terminal must never be caught by
     * its own daemon stop or exec timeout). SIGTERM ([sigterm] = true) for a
     * graceful stop, SIGKILL otherwise. Never another workspace (different
     * rootfs path). Best-effort by contract: vanished pids and permission
     * errors are the scan's normal noise. Returns the number of signals sent.
     *
     * Phase 04 hardening (was `cmdline.contains(marker)` plus nothing else):
     *
     * - **Path boundary:** the match is a whole token or a path below the
     *   rootfs (`<marker>/…`), so `/…/rootfs2` and `/…/rootfs-backup` are no
     *   longer killed ([RootfsProcessMatcher.referencesRootfs]).
     * - **UID check:** the row's `Uid:` must equal the app UID. `/proc`'s
     *   hidepid usually guarantees this; the check is explicit and asserted.
     * - **PID-reuse guard:** when [notBeforeTicks] is supplied (the launch's
     *   own `/proc/<pid>/stat` field 22), only PIDs started at or after that
     *   launch are signalled; an unreadable/malformed start time fails closed.
     * - The app's own PID is excluded explicitly (fail-safe even though its
     *   cmdline is the Android runtime).
     *
     * The cmdline scan remains the *fallback*: the primary containment for
     * extraction is the process group (Phase 04), and the recorded PRoot
     * host is destroyed before this sweep runs.
     */
    private fun killRootfsProcesses(
        rootfsPath: String,
        sigterm: Boolean,
        excludePids: Set<Int> = emptySet(),
        notBeforeTicks: Long? = null
    ): Int {
        if (rootfsPath.isBlank()) return 0
        val marker = File(rootfsPath).absolutePath
        val selfUid = runCatching { android.os.Process.myUid() }.getOrNull()
        val selfPid = runCatching { android.os.Process.myPid() }.getOrNull()
        val exclusions = excludePids + listOfNotNull(selfPid)
        var signalled = 0
        File("/proc").listFiles { f -> f.name.toIntOrNull() != null }?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid in exclusions) return@forEach
            try {
                val cmdline = File(entry, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                val status = runCatching { File(entry, "status").readText() }.getOrNull()
                val stat = runCatching { File(entry, "stat").readText() }.getOrNull()
                val matched = RootfsProcessMatcher.shouldSignal(
                    cmdline = cmdline,
                    status = status,
                    stat = stat,
                    marker = marker,
                    selfUid = selfUid,
                    notBeforeTicks = notBeforeTicks
                )
                if (matched) {
                    val start = RootfsProcessMatcher.startTimeTicks(stat)
                    val display = cmdline.replace('\u0000', ' ').trim()
                        .take(CMDLINE_LOG_LIMIT)
                    Logger.d(
                        TAG,
                        "sweep candidate pid=$pid start=$start cmdline=\"$display\""
                    )
                    Os.kill(
                        pid,
                        if (sigterm) android.system.OsConstants.SIGTERM else android.system.OsConstants.SIGKILL
                    )
                    signalled++
                }
            } catch (_: Exception) {
                // vanished pid / zombie / permission — normal scan noise
            }
        }
        if (signalled > 0) {
            val signalName = if (sigterm) "SIGTERM" else "SIGKILL"
            Logger.i(TAG, "rootfs process sweep ($signalName): signalled $signalled process(es) of $marker")
        }
        return signalled
    }

    // ── §1.50 seccomp/kernel-incompatibility fallback ─────────────────────
    //
    // PRoot on affected OEM/hardened kernels cannot install its seccomp
    // filter and aborts with a documented marker string (verified in the
    // shipped binary, 2026-08-03). The workaround is PROOT_NO_SECCOMP=1.
    //
    // Detection here is memoized AND real-time: [resolveSeccompFallback]
    // runs a single bounded probe per process the first time a PTY session
    // is opened (proot's syscall interception is what fails, so a
    // `true`-via-sh probe is a faithful canary), and [exec] retries on the
    // actual failure signature. Both write the same flag, so the first
    // detection — from either path — makes every later launch pre-apply the
    // fallback at zero probe cost. The flag lives in
    // [com.fem.linexa.service.runtime.bootstrap.BootstrapManager]'s
    // companion — shared with [com.fem.linexa.service.installer.RootfsExtractor],
    // so an extraction-time detection (§1.50, Roadmap Item 3) also protects
    // every later launch.

    /**
     * §1.52's launch forensics, extracted verbatim from [runProcess] and gated.
     *
     * ## Why this moved behind [DebugCapture] (§16.3, audit finding 10.2)
     *
     * This dump was the last **unconditional** expensive diagnostic in the runtime.
     * The other two in this file are already gated — the bootstrap `diagnoseAll()`
     * (see [ensureBootstrap]) and the exec/`probeGuestFilesystem` report (§1.99) —
     * and the reasoning that put them behind the flag applies here unchanged, only
     * more often: `runProcess` runs on **every one-shot exec**, so a single desktop
     * start paid this three times before Xvnc was even launched, plus once per
     * package-manager query, file operation and feature reconcile.
     *
     * What the dump costs, counted from the code rather than measured:
     *
     *  * ~20 `Logger.i` calls for argv (`PRootLauncher.buildLaunchSpec` emits
     *    `-r -w -0 --link2symlink --kill-on-exit` plus three-to-four `-b` pairs),
     *  * a `toSortedMap()` allocation and ~12 more `Logger.i` calls for the env,
     *    twice — once for the sorted listing, once for the shell-copyable line,
     *  * and **up to five [fingerprintPath] calls at nine filesystem operations
     *    each** (`isDirectory`, `isFile`, `readSymbolicLink`, `canonicalPath`,
     *    `normalize`, `exists`, `canRead`, `canWrite`, `canExecute`) — up to 45
     *    stat-class syscalls per launch.
     *
     * None of that was reachable by a user with debug info off: [Logger]'s file
     * sink is already `DebugCapture`-gated (`FileLogSink`), and its logcat half is
     * UID-restricted by the platform with no reader in the app. So the work was
     * performed, formatted and then effectively discarded — the exact condition
     * §1.98/§1.99 were written to remove.
     *
     * ## What deliberately did **not** change
     *
     * The dump's content and format are byte-identical to the pre-§16.3 version:
     * same headers, same order, same truncation at 192 chars, same `<--` markers,
     * same `fingerprintPath` output. This is a *reachability* change only, so a
     * reader who turns debug info on sees exactly what the audit's §1.52 evidence
     * describes. Nothing about process execution moved: [runProcess] builds the
     * same `ProcessBuilder` from the same [LaunchSpec], with the same environment,
     * on the same thread, whether or not this function runs — the only statement
     * between the gate and `pb.start()` is the `ProcessBuilder` construction that
     * was always there.
     *
     * The gate is checked at the **call site**, not inside this function, so that a
     * disabled capture skips even the argument evaluation; and the call site's
     * `if` is the whole cost paid on the fast path (one `@Volatile` boolean read).
     */
    private fun logLaunchForensics(spec: LaunchSpec) {
        // ── §1.52 FORENSIC: full argv (argv[0]..argv[n]) + shell-copyable ──
        val fullArgv = listOf(spec.executable) + spec.args
        Logger.i(TAG, "=== FORENSIC: full argv (§1.52) ===")
        fullArgv.forEachIndexed { i, arg ->
            val redacted = redactSecretArgv(arg)
            val truncated = if (redacted.length > 192) " (${redacted.length} chars, truncated)" else ""
            Logger.i(TAG, "  argv[$i] = ${redacted.take(192)}$truncated")
        }
        val shellCopyable = (spec.env.toSortedMap().map { (k, v) -> "$k=${shellQuote(v)}" } +
            fullArgv.map { shellQuote(redactSecretArgv(it)) }).joinToString(" ")
        Logger.i(TAG, "  shell-copyable: $shellCopyable")

        // ── §1.52 FORENSIC: complete env passed to proot (sorted) ──────────
        Logger.i(TAG, "=== FORENSIC: complete env passed to proot (§1.52, sorted) ===")
        val highlightedKeys = setOf(
            "PATH", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR",
            "TMPDIR", "HOME", "PWD", "LANG", "TERM", "USER"
        )
        spec.env.toSortedMap().forEach { (key, value) ->
            val mark = if (key in highlightedKeys) "   <--" else ""
            Logger.i(TAG, "  $key = ${value.orEmpty()}$mark")
        }
        Logger.i(TAG, "  keys=${spec.env.size}; unset in env: TMPDIR=${spec.env["TMPDIR"] ?: "<unset>"}, HOME=${spec.env["HOME"] ?: "<unset>"}, PWD=${spec.env["PWD"] ?: "<unset>"}, LANG=${spec.env["LANG"] ?: "<unset>"}, TERM=${spec.env["TERM"] ?: "<unset>"}, USER=${spec.env["USER"] ?: "<unset>"}")

        // ── §1.52 FORENSIC: pipeline path verification (file metadata) ─────
        Logger.i(TAG, "=== FORENSIC: pipeline path verification (§1.52) ===")
        val executableFile = File(spec.executable)
        Logger.i(TAG, fingerprintPath("executable", executableFile))
        val cwdFile = File(spec.workingDirectory)
        Logger.i(TAG, fingerprintPath("workingDirectory(guest cwd)", cwdFile))
        val rootfsArgIndex = spec.args.indexOf("-r")
        if (rootfsArgIndex >= 0 && rootfsArgIndex + 1 < spec.args.size) {
            Logger.i(TAG, fingerprintPath("rootfsPath", File(spec.args[rootfsArgIndex + 1])))
        }
        val prootTmpDir = spec.env["PROOT_TMP_DIR"]
        if (prootTmpDir != null) {
            Logger.i(TAG, fingerprintPath("prootTmpDir", File(prootTmpDir)))
        }
        val prootLoader = spec.env["PROOT_LOADER"]
        if (prootLoader != null) {
            Logger.i(TAG, fingerprintPath("prootLoader", File(prootLoader)))
        }
    }

    /**
     * §1.83's daemon launch forensics, extracted from [startDaemon] and gated.
     *
     * The same §16.3 reasoning as [logLaunchForensics] applies, with one
     * difference worth stating: this dump is **cheaper** than `runProcess`'s
     * because it has no [fingerprintPath] calls, so it costs allocations and
     * ~30 `Logger.i` calls rather than filesystem syscalls. It is gated anyway,
     * for two reasons. It is on the desktop start path — Xvnc is a §1.83 daemon,
     * so every desktop launch paid it — and leaving one dump ungated would make
     * "is this diagnostic reachable with debug off?" a per-call-site question
     * again instead of a property of the file.
     *
     * Content and format are unchanged: same three headers with the daemon name
     * interpolated, same 192-char truncation, same sorted env, same
     * shell-copyable line. Daemon lifecycle is untouched — [launchDaemonProcess]
     * receives the identical [spec], the `daemons` map, `ManagedDaemon` scope and
     * the `daemonsLock`/`releaseProotTmpDir` error path are all as they were.
     */
    private fun logDaemonForensics(spec: LaunchSpec, name: String) {
        val fullArgv = listOf(spec.executable) + spec.args
        Logger.i(TAG, "=== FORENSIC: §1.83 daemon '$name' full argv (§1.52) ===")
        fullArgv.forEachIndexed { i, arg ->
            val redacted = redactSecretArgv(arg)
            val truncated = if (redacted.length > 192) " (${redacted.length} chars, truncated)" else ""
            Logger.i(TAG, "  argv[$i] = ${redacted.take(192)}$truncated")
        }
        Logger.i(TAG, "=== FORENSIC: §1.83 daemon '$name' env keys (§1.52) ===")
        spec.env.toSortedMap().forEach { (k, v) -> Logger.i(TAG, "  $k = ${v.orEmpty().take(192)}") }
        Logger.i(TAG, "=== FORENSIC: §1.83 daemon '$name' shell-copyable ===")
        val shellCopyable = (spec.env.toSortedMap().map { (k, v) -> "$k=${shellQuote(v)}" } +
            fullArgv.map { shellQuote(redactSecretArgv(it)) }).joinToString(" ")
        Logger.i(TAG, "  $shellCopyable")
    }

    /**
     * Pre-release audit fix (2026-09-02): redact the VNC password out of
     * forensic argv dumps. The one secret that legitimately travels through a
     * guest argv is the `vncpasswd -f` provisioning command — the configured
     * password is embedded in the `sh -c` payload after `printf '%s\n'`.
     * Forensic dumps are DebugCapture-gated, but they land in `linexa.log` /
     * runtime-trace files that ship inside the user's "Copy error" report, so
     * the secret must not survive into them. Everything else passes through
     * byte-identical.
     */
    private fun redactSecretArgv(arg: String): String {
        if (!arg.contains("vncpasswd -f")) return arg
        return VNC_PASSWD_SECRET_REGEX.replace(arg) { _ ->
            "printf '%s\\n' <redacted> | vncpasswd"
        }
    }

    /**
     * §1.50: Run a bounded one-shot [LaunchSpec] process and capture its
     * output. The timeout bounds e.g. the seccomp probe so a wedged guest
     * can't hang session-open forever. Output is drained on a background
     * thread while [Process.waitFor] enforces the timeout — reading inline
     * before `waitFor` would deadlock on a guest that fills the pipe and
     * then hangs.
     *
     * §1.92: [onLine] (when supplied) is invoked for each output line as it
     * is read, enabling the Package Manager feature to surface live
     * progress from `apt-get`/`pacman`/`apk`/`dnf`/`zypper` operations
     * without waiting for the command to finish. The captured output is
     * identical whether or not [onLine] is supplied.
     */
    private fun runProcess(
        spec: LaunchSpec,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        onLine: ((String) -> Unit)? = null
    ): ProcessResult {
        // §16.3 (§1.167): the §1.52 forensic dump is now behind [DebugCapture],
        // exactly like the two dumps above it in this file. See
        // [logLaunchForensics] for why, and for what is and is not skipped.
        if (DebugCapture.isEnabled()) logLaunchForensics(spec)

        val pb = ProcessBuilder(listOf(spec.executable) + spec.args)
            .redirectErrorStream(true)
        // §1.49 (Issue 4 fix): explicit full-replacement environment, matching
        // the PTY path — no ambient app-process env is inherited. The complete
        // final env for a libproot.so invocation comes from the single source
        // (BootstrapManager.prootEnv + PRootLauncher + filtered config env),
        // identical for openTerminal/exec/extraction. ProcessBuilder's ambient
        //-merge default (putAll) is deliberately not used, so a variable
        // added to one launch path can't silently fail to reach the others.
        pb.environment().clear()
        pb.environment().putAll(spec.env)
        val process = pb.start()
        val reader = Executors.newSingleThreadExecutor()
        return try {
            val outputFuture = reader.submit<String> {
                try {
                    if (onLine != null) {
                        val sb = StringBuilder()
                        process.inputStream.bufferedReader().use { br ->
                            while (true) {
                                val line = br.readLine() ?: break
                                sb.append(line).append('\n')
                                onLine(line)
                            }
                        }
                        sb.toString().trim()
                    } else {
                        process.inputStream.bufferedReader().use { it.readText().trim() }
                    }
                } catch (_: Exception) {
                    ""
                }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                // Phase 04: capture the exec's own start time before killing —
                // the sweep below only signals PIDs started at/after it.
                val hostStartTicks = processHostPid(process)
                    ?.let { RootfsProcessMatcher.startTimeTicksOf(it) }
                process.destroyForcibly()
                // Pre-release audit fix (2026-09-02, finding RT-6): SIGKILL
                // reaches only the proot host — --kill-on-exit is an exit
                // handler and never runs — so the timed-out guest command
                // (e.g. an apt transaction) survived as an orphan holding
                // dpkg locks. Sweep the workspace's guest tree the same way
                // the daemon stop path does (rootfs path extracted from the
                // spec's -r argument).
                val rootfsMarker = spec.args.indexOf("-r").takeIf { it >= 0 && it + 1 < spec.args.size }
                    ?.let { spec.args[it + 1] }
                if (rootfsMarker != null) {
                    killRootfsProcesses(
                        rootfsMarker,
                        sigterm = false,
                        excludePids = livePtyPids(),
                        notBeforeTicks = hostStartTicks
                    )
                }
                val drained = try { outputFuture.get(5, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                return ProcessResult(exitCode = -1, output = drained)
            }
            val output = try { outputFuture.get(10, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            ProcessResult(exitCode = process.exitValue(), output = output)
        } finally {
            reader.shutdownNow()
        }
    }

    /**
     * §1.50: On the first openTerminal of the process, probe whether this
     * kernel is affected by the seccomp/ptrace incompatibility. Returns a
     * spec with `PROOT_NO_SECCOMP=1` pre-applied when needed; otherwise the
     * input spec unchanged. Memoized — one probe per process, then the
     * [BootstrapManager.needsNoSeccomp] flag short-circuits every later launch. The probe's
     * blocking process run is dispatched to [Dispatchers.IO] so it can't
     * stall an arbitrary calling thread (e.g. the UI dispatcher) up to the
     * probe timeout.
     */
    private suspend fun resolveSeccompFallback(spec: LaunchSpec): LaunchSpec {
        val t0 = System.currentTimeMillis()
        Logger.d(TAG, "CHAIN: ENTER resolveSeccompFallback needsNoSeccomp=${BootstrapManager.needsNoSeccomp} probeDone=${BootstrapManager.seccompProbeDone}")
        if (BootstrapManager.needsNoSeccomp) {
            Logger.d(TAG, "CHAIN: resolveSeccompFallback short-circuit needsNoSeccomp")
            return spec.copy(env = spec.env + BootstrapManager.NO_SECCOMP_ENV)
        }
        if (BootstrapManager.seccompProbeDone) {
            Logger.d(TAG, "CHAIN: resolveSeccompFallback short-circuit probeDone")
            return spec
        }
        BootstrapManager.seccompProbeDone = true
        val probe = withContext(Dispatchers.IO) {
            runProcess(
                // §1.193: the probe must test the KERNEL, not the launch's
                // own workaround. A fake-root spec now always carries
                // PROOT_NO_SECCOMP=1 (§1.193), so probing it verbatim would
                // silently never detect the §1.50 kernel bug for the
                // normal-user sessions that still rely on this probe.
                spec.copy(
                    args = listOf("/bin/sh", "-c", "exit 0"),
                    env = spec.env - BootstrapManager.NO_SECCOMP_ENV.keys
                ),
                timeoutSeconds = PROBE_TIMEOUT_SECONDS
            )
        }
        Logger.i(
            TAG,
            "CHAIN: seccomp probe exit=${probe.exitCode} outputBytes=${probe.output.length} " +
                "marker=${bootstrapManager.isSeccompKernelBugFailure(probe.output)} elapsed=${elapsed(t0)}"
        )
        if (bootstrapManager.isSeccompKernelBugFailure(probe.output)) {
            Logger.w(
                "ContainerRuntime",
                "Seccomp probe detected kernel-incompatibility — enabling PROOT_NO_SECCOMP=1 for all launches (§1.50)"
            )
            BootstrapManager.needsNoSeccomp = true
            return spec.copy(env = spec.env + BootstrapManager.NO_SECCOMP_ENV)
        }
        return spec
    }

    /** §1.68 chain diagnostic: ms since [t0]. */
    private fun elapsed(t0: Long): String = "${System.currentTimeMillis() - t0}ms"

    /**
     * §1.48/§1.97: the PROOT_TMP_DIR scratch scope owned by one terminal —
     * keyed by BOTH workspace and terminal so independent terminals in the
     * same workspace get independent scratch dirs. The scope string is used
     * as a directory name under the shared proot_tmp parent, so it must be
     * filesystem-safe; terminal ids are charset-constrained (`[A-Za-z0-9_-]`,
     * §9.1 of the audit).
     */
    private fun prootTmpScope(workspaceId: String, terminalId: String): String =
        "$workspaceId/$terminalId"

    companion object {
        private const val TAG = "ContainerRuntime"

        /**
         * Pre-release audit fix (2026-09-02): matches the `printf … | vncpasswd`
         * payload region of the VNC-auth provisioning command so
         * [redactSecretArgv] can mask the configured password in forensic
         * argv dumps. The literal guest text is `printf '%s\n' <pw> | vncpasswd`.
         */
        private val VNC_PASSWD_SECRET_REGEX =
            Regex("""printf '%s\\n' .*? \| vncpasswd""")

        // LX-2 / issue #2: the package-manager command set and its whitespace
        // tokenizer moved to
        // [com.fem.linexa.domain.packagemanager.PackageOperationClassifier]
        // (2026-09-03) — one classification authority shared by the runtime
        // queue and the Shortcuts tab routing.

        /**
         * Issue #1 (2026-09-03): daemon names that keep the ROOT emulation
         * when their workspace launches as the normal user. sshd needs the
         * root-shaped environment (privileged-port semantics, host keys,
         * /run/sshd); everything else (Xvnc, the X session, future desktop
         * helpers) is a USER-desktop component and runs as the normal
         * account.
         */
        val ROOT_DAEMONS: Set<String> = setOf("sshd", "ssh", "dropbear")

        /**
         * Shell candidates to try inside a rootfs, in priority order.
         * Checked by [resolveShellPath] for every new session.
         * Every supported distro (§0) guarantees `/bin/sh`; bash is tried
         * first for the full interactive experience (history, prompt, profile).
         */
        private val SHELL_CANDIDATES = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh")

        // FEATURE_STORAGE moved to com.fem.linexa.domain.runtime.FeatureIds (Phase 6b).
        // It is a plain string constant with no PRoot-specific meaning, so it lives in
        // the domain layer alongside DEFAULT_TERMINAL_ID / CommandResult / RuntimeState /
        // TerminalState — the same Phase 6a precedent. Imported at the top of this file.
        // The previous in-companion declaration made every ui.* consumer of the constant
        // a frozen Rule-3 violator (the source-scan backstop that catches inlined
        // `const val` reads the bytecode-based Rule 2 cannot see).

        /**
         * Maximum time in seconds to wait for a one-shot command before force-killing.
         */
        private const val COMMAND_TIMEOUT_SECONDS = 120L

        // ── Declared first boot (issue #3, 2026-09-03) ──────────────────────

        /** Rootfs-relative directory holding Linexa's first-boot markers. */
        const val FIRST_BOOT_MARKER_DIR = ".linexa"

        /** The completion marker inside [FIRST_BOOT_MARKER_DIR]. */
        const val FIRST_BOOT_MARKER_FILE = "first-boot.done"

        /** The bounded-attempt counter inside [FIRST_BOOT_MARKER_DIR]. */
        const val FIRST_BOOT_ATTEMPTS_FILE = "first-boot.attempts"

        /**
         * F-7 (2026-08-25 audit): debconf frontend for the ONE-SHOT exec
         * bridge only — layered in [execInternal], never in the base launch
         * env (interactive terminal sessions keep full debconf
         * interactivity). The bridge has no TTY and an unwritable stdin, so
         * a prompt could never be answered; noninteractive takes documented
         * defaults instead of stalling to the operation timeout.
         */
        val NONINTERACTIVE_EXEC_ENV: Map<String, String> =
            mapOf("DEBIAN_FRONTEND" to "noninteractive")

        /** §1.50: Bounded time for the one-time seccomp probe launch. */
        private const val PROBE_TIMEOUT_SECONDS = 30L

        /** §1.83: grace period for a daemon's PRoot host to exit on SIGTERM before SIGKILL. */
        private const val DAEMON_STOP_GRACE_MS = 2_000L

        /** Phase 04: sweep debug lines truncate a candidate's argv to this. */
        private const val CMDLINE_LOG_LIMIT = 200

    }

    /** §1.83: a live guest daemon and the scratch scope it owns.
     *
     * §8 supervision: [stopping] distinguishes an intentional stop
     * (stopDaemon / idempotent restart / workspace teardown) from an
     * unexpected daemon death — the exit watcher only reports the latter.
     * An [java.util.concurrent.atomic.AtomicBoolean] because the watcher
     * thread and the stopping caller race on opposite sides of process exit.
     */
    private data class ManagedDaemon(
        val process: Process,
        val scope: String,
        val stopping: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        /** Rootfs path of the workspace this daemon runs in — the /proc sweep marker for guest-tree kills (RT-1 fix). */
        val rootfsPath: String = ""
    )

    /** Result of a bounded one-shot launch (§1.50). */
    private data class ProcessResult(val exitCode: Int, val output: String)
}

/**
 * §1.52: shellQuote for the diagnostics "shell-copyable" echo. The
 * real runner passes [ProcessBuilder] argv directly (no shell); this
 * only helps a human paste the exact same invocation into a shell.
 */
private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * §1.52: verbose file-metadata fingerprint for one pipeline path.
 * Reports every view the audit needs (original/absolute/canonical/
 * normalized, existence, kind, permission bits, and the symlink
 * target) without ever throwing — a metadata failure becomes a field,
 * not a crash.
 */
private fun fingerprintPath(label: String, file: File): String {
    val type = when {
        file.isDirectory -> "dir"
        file.isFile -> "file"
        else -> "-"
    }
    val symlinkTarget = try {
        java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
    } catch (_: Exception) {
        ""
    }
    val canonical = try { file.canonicalPath } catch (_: Exception) { "<error>" }
    val normalized = try { file.normalize().path } catch (_: Exception) { "<error>" }
    val sym = if (symlinkTarget.isEmpty()) "<not-symlink>" else symlinkTarget
    return "  $label: path=${file.path} abs=${file.absolutePath} canonical=$canonical normalized=$normalized exists=${file.exists()} type=$type readable=${file.canRead()} writable=${file.canWrite()} executable=${file.canExecute()} symlinkTarget=$sym"
}
