package com.fem.linexa.service.runtime

import android.app.Application
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.RuntimeHealth
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.network.HostDnsProvider
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.desktop.X11SessionManager
import com.fem.linexa.service.runtime.desktop.X11SessionState
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.AudioBridgeProvisioner
import com.fem.linexa.service.runtime.features.NotifySendProvisioner
import com.fem.linexa.service.runtime.features.RecordingAudioSink
import com.fem.linexa.service.runtime.features.RecordingGuestFeatureHost
import com.fem.linexa.service.runtime.features.RecordingHostClipboard
import com.fem.linexa.service.runtime.features.RecordingNotificationBridge
import com.fem.linexa.service.runtime.features.ScriptedAudioSource
import com.fem.linexa.service.runtime.features.SshdProvisioner
import com.fem.linexa.service.runtime.features.WorkspaceAudioSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceClipboardSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceFeatureReconciler
import com.fem.linexa.service.runtime.features.WorkspaceNotificationSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceNetworkProvisioner
import com.fem.linexa.service.runtime.wakelock.RecordingWakeLock
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.buildWorkspaceMetadata
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §P0 — [WorkspaceSessionSupervisor] tests: the exact choreography
 * `RuntimeService.launchSession` used to run inline, exercised against the REAL
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime] through the
 * fake-PRoot harness (controllable stub ptys). RuntimeService itself is an
 * Android Service a JVM test cannot instantiate; driving the extracted
 * supervisor is what makes the §P0 stale-coroutine race fix actually testable
 * (per the P0 requirement: reproduce the real launchSession choreography, not
 * an artificial bypass).
 *
 * The regression under test: a launchSession coroutine that was waiting on
 * [WorkspaceTerminalManager.awaitSessionEnd] when a leave (STOP) completed it
 * may resume AFTER a re-enter (START) has already opened a replacement session
 * for the same workspace. Before the fix, its unconditional unwind — the
 * `finally { closeWorkspace }` plus the `updateState(Stopped)` tail — destroyed
 * the replacement. The generation guard ([RuntimeLifecycleManager.isCurrentSession])
 * must make the stale coroutine leave the replacement untouched.
 */
class WorkspaceSessionSupervisorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: ProotRuntimeTestHarness
    private lateinit var runtimeTrace: RuntimeTrace
    private lateinit var sessionManager: SessionManager
    private lateinit var healthRegistry: com.fem.linexa.service.runtime.WorkspaceHealthRegistry
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var terminals: WorkspaceTerminalManager
    private lateinit var manager: RuntimeLifecycleManager
    private lateinit var events: MutableSharedFlow<RuntimeEvent>
    private lateinit var host: RecordingHost
    private lateinit var featureHost: RecordingGuestFeatureHost
    private lateinit var notificationBridge: RecordingNotificationBridge
    private lateinit var hostClipboard: RecordingHostClipboard

    /**
     * §1.113: unavailable by DEFAULT on purpose. A launch-path test must not start
     * a pump thread that then blocks reading a FIFO for the rest of the suite; the
     * one test that exercises the audio launch flips it on first.
     */
    private val audioSink = RecordingAudioSink(available = false)
    private val audioSource = ScriptedAudioSource()
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var supervisor: WorkspaceSessionSupervisor

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        harness = ProotRuntimeTestHarness(
            prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS),
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.ptyFactory.controllable = true

        // A fileManager rooted at the SAME filesDir the runtime's own manager
        // uses, so seeding a rootfs here is what the validator and the runtime
        // both see ({files}/workspaces/{id}/rootfs).
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(harness.filesDir)
        runtimeTrace = RuntimeTrace()
        healthRegistry = com.fem.linexa.service.runtime.WorkspaceHealthRegistry()
        sessionManager = SessionManager(runtimeTrace, healthRegistry)
        fileManager = WorkspaceFileManager(app)
        // §P0: drive the REAL runtime, but keep closeWorkspaceSessions
        // SYNCHRONOUS. The real teardown hops to Dispatchers.IO
        // (killTmuxServer/stopAllDaemons), which would yield the runBlocking
        // event loop and let a stale launchSession coroutine resume DURING a
        // requestStop — before the replacement's requestStart bumps the
        // session generation — collapsing the race into an ordinary
        // stop-then-start (the stale coroutine emits a legitimate Stopped and
        // the generation guard is never exercised). Running the real teardown
        // inside a nested runBlocking blocks this thread until it completes
        // WITHOUT yielding the outer event loop.
        val synchronousTeardownRuntime = object : ContainerRuntime by harness.runtime {
            override suspend fun closeWorkspaceSessions(workspaceId: String) {
                runBlocking { harness.runtime.closeWorkspaceSessions(workspaceId) }
            }
        }
        terminals = WorkspaceTerminalManager(synchronousTeardownRuntime, runtimeTrace)
        manager = RuntimeLifecycleManager(
            sessionManager,
            terminals,
            fileManager,
            runtimeTrace,
            healthRegistry,
            // §1.142: the wake lock is inert in these tests (nothing toggles it);
            // it is a constructor dependency of the one teardown path.
            WorkspaceWakeLockManager(
                wakeLock = RecordingWakeLock(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )
        )
        events = MutableSharedFlow()
        host = RecordingHost()
        // §1.110: the launch path applies the workspace's persisted feature
        // toggles. The default test workspace has ssh off, so this is inert
        // here; WorkspaceFeatureReconcilerTest covers the reconcile itself.
        // §1.111: `notifications` defaults to ON, so tests that assert on the
        // guest commands a launch runs set it explicitly via [toggles].
        featureHost = RecordingGuestFeatureHost()
        notificationBridge = RecordingNotificationBridge()
        hostClipboard = RecordingHostClipboard()
        supervisor = WorkspaceSessionSupervisor(
            workspaceRepository = harness.workspaceRepository,
            launchValidator = WorkspaceLaunchValidator(fileManager),
            sessionManager = sessionManager,
            workspaceTerminalManager = terminals,
            lifecycleManager = manager,
            runtimeTrace = runtimeTrace,
            diagnosticLogManager = Mockito.mock(DiagnosticLogManager::class.java),
            events = events,
            featureReconciler = WorkspaceFeatureReconciler(
                harness.workspaceRepository,
                featureHost,
                // §1.111: the notification half of the same launch-path reconcile.
                // Given its own IO scope (not this runBlocking's) so its spool
                // poller can never hold the test's event loop, and a spool under
                // the temp folder so nothing touches a real rootfs.
                WorkspaceNotificationSupervisor(
                    host = featureHost,
                    bridge = notificationBridge,
                    scope = notificationScope,
                    spoolDirFor = { File(harness.filesDir, "spool-$it") }
                ),
                // §1.112: the clipboard half. Same isolation for the same
                // reasons — its own IO scope, and a bridge under the temp folder.
                WorkspaceClipboardSupervisor(
                    host = featureHost,
                    clipboard = hostClipboard,
                    scope = notificationScope,
                    outDirFor = { File(harness.filesDir, "clip-out-$it") },
                    mirrorFileFor = { File(harness.filesDir, "clip-in-$it/clipboard") }
                ),
                // §1.113: the audio half. Same isolation, plus a sink that is
                // deliberately UNAVAILABLE by default — a launch-path test must
                // not start a pump thread that blocks reading a FIFO for the
                // duration of the suite.
                WorkspaceAudioSupervisor(
                    host = featureHost,
                    sink = audioSink,
                    scope = notificationScope,
                    sourceFor = { audioSource },
                    reopenDelayMillis = 20L
                ),
                // Network Configuration: the launch-path network reconcile.
                // The harness workspaces carry no network attributes, so this
                // provisions nothing — it only has to exist for the constructor.
                WorkspaceNetworkProvisioner(harness.workspaceRepository, featureHost, HostDnsProvider { emptyList() }),
                // Transformation spec §6: real registry so launch-path feature
                // outcomes report through the production path under tests.
                WorkspaceHealthRegistry()
            ),
            // Phase 2E-1: no X11 manager under test — the deferred reconcile
            // phase runs immediately via its bounded fallback (a null manager
            // skips the observation entirely).
            x11SessionManager = null,
            host = host
        )
    }

    @After
    fun tearDown() {
        notificationScope.cancel()
    }

    /**
     * §1.111/§1.112/§1.113: `notifications`, `clipboard` and `audio` all default to
     * ON, so a test asserting on the guest commands a launch runs must state every
     * toggle explicitly — otherwise their installs show up as unexplained execs.
     */
    private fun toggles(
        ssh: Boolean,
        notifications: Boolean = false,
        clipboard: Boolean = false,
        audio: Boolean = false
    ) = mapOf(
        SshdProvisioner.FEATURE_SSH to ssh,
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to notifications,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to clipboard,
        AudioBridgeProvisioner.FEATURE_AUDIO to audio
    )

    /** Seeds {files}/workspaces/{id}/rootfs with the essential dirs + a file so the launch validator passes. */
    private fun seedReadyRootfs(workspaceId: String): String {
        val rootfs = fileManager.ensureWorkspaceDirs(workspaceId)
        listOf("bin", "etc", "lib", "usr", "var").forEach { File(rootfs, it).mkdirs() }
        File(rootfs, "etc/os-release").writeText("NAME=LinexaTest\n")
        return rootfs.absolutePath
    }

    private suspend fun awaitState(workspaceId: String, predicate: (RuntimeState) -> Boolean) {
        withTimeout(5_000) {
            while (!predicate(sessionManager.getState(workspaceId))) yield()
        }
    }

    @Test
    fun staleLaunchedCoroutineMustNotDestroyReplacementSession() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-a"))

        val emitted = mutableListOf<RuntimeEvent>()
        val collector = launch { events.collect { emitted += it } }

        // C1 reproduces RuntimeService.launchSession for ws-a: readiness gate →
        // requestStart → promote Running → awaitSessionEnd → guarded unwind.
        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }

        // User leaves: STOP fully tears the first session down and completes
        // C1's awaitSessionEnd. The whole stop runs on a DEDICATED thread:
        // requestStop's internal withTimeoutOrNull otherwise resumes the
        // stop-coroutine by dispatching onto the OUTER event loop — and C1's
        // resumption (queued when closeWorkspace completes the end-deferred) is
        // already ahead of it in that queue, so C1 would run while the session
        // generation is still 1 (legitimately emitting Stopped) and the race
        // would collapse into an ordinary stop-then-start. Blocking this thread
        // until the stop thread finishes keeps C1 pending on the outer loop
        // until after the replacement's requestStart bumps the generation below.
        val stopResult = Executors.newSingleThreadExecutor().submit<Boolean> {
            runBlocking { manager.requestStop("ws-a") }
        }.get()
        assertTrue("leave (STOP) must succeed", stopResult)

        // User immediately re-enters: START opens a REPLACEMENT session for the
        // same workspace BEFORE C1 has a chance to unwind. requestStart bumps
        // ws-a's session generation at entry (inside the mutex, before any
        // suspension), so when C1 finally runs it must see itself as stale.
        assertTrue("re-entry (START) must open a replacement", manager.requestStart("ws-a", root) is StartOutcome.Started)

        c1.join()

        // The replacement session must have survived the stale coroutine.
        assertTrue(
            "replacement T0 must survive the stale coroutine's finally",
            harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID)
        )
        assertNotNull("replacement pty must survive", terminals.getPtySession("ws-a", DEFAULT_TERMINAL_ID))
        assertNotNull("replacement session must survive", sessionManager.getSession("ws-a"))
        val state = sessionManager.getState("ws-a")
        assertTrue(
            "stale coroutine must not clobber the replacement's state back to Stopped (was $state)",
            state is RuntimeState.Starting || state is RuntimeState.Running
        )
        assertEquals("replacement still owns the runtime gate", "ws-a", manager.activeWorkspaceId.value)
        collector.cancel()
        assertTrue("stale coroutine must not emit a spurious Stopped: $emitted", emitted.none { it is RuntimeEvent.Stopped })

        // The replacement is a real, fully-owned session: it must stop cleanly.
        assertTrue("replacement must be stoppable", manager.requestStop("ws-a"))
        assertFalse("replacement T0 must be torn down on stop", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertEquals(RuntimeState.Stopped, sessionManager.getState("ws-a"))
        assertNull("session must be removed after the final stop", sessionManager.getSession("ws-a"))
    }

    @Test
    fun currentCoroutineUnwindsOnNormalStop() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-a"))

        val emitted = mutableListOf<RuntimeEvent>()
        val collector = launch { events.collect { emitted += it } }

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()
        collector.cancel()

        // No newer session replaced ws-a, so C1 is still the current session
        // and performs its normal unwind.
        assertEquals(RuntimeState.Stopped, sessionManager.getState("ws-a"))
        assertNull("session must be removed after normal stop", sessionManager.getSession("ws-a"))
        assertNull("gate must clear after normal stop", manager.activeWorkspaceId.value)
        assertTrue("normal stop must emit Stopped", emitted.any { it is RuntimeEvent.Stopped })
        assertTrue("foreground teardown must be requested when the gate empties", host.foregroundIdleCalls > 0)
    }

    /**
     * §1.165 — the reported bug, driven through the real launch choreography:
     * two rapid launches for the SAME workspace (fast double-tap, a lifecycle
     * callback firing twice, two racing callers).
     *
     * Before the fix, both coroutines ran the full body. The second's
     * `requestStart` opened a *second* terminal for the workspace (the phantom),
     * and because each coroutine re-read the generation with a separate
     * `currentGeneration()` call *after* `requestStart` had released the
     * transition mutex, both ended up holding the same (newer) value — so both
     * passed the §P0 `isCurrentSession` guard, and whichever unwound first ran
     * `closeWorkspace` on a session the other was still using. That teardown
     * kills the workspace's PRoot process group: the reported SIGKILL (-9).
     *
     * The assertion that pins the fix is not just "one terminal" — it is that the
     * duplicate coroutine *completes* while the session stays alive and Running.
     */
    @Test
    fun twoConcurrentLaunchesOfTheSameWorkspaceLeaveOneLiveSession() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-a"))

        val emitted = mutableListOf<RuntimeEvent>()
        val collector = launch { events.collect { emitted += it } }

        val c1 = supervisor.launch(this, "ws-a", root)
        val c2 = supervisor.launch(this, "ws-a", root)

        // One of the two is the duplicate and returns without waiting on the end
        // signal, so exactly one coroutine must still be parked in awaitSessionEnd
        // once the session is up.
        awaitState("ws-a") { it is RuntimeState.Running }
        withTimeout(5_000) { while (!(c1.isCompleted || c2.isCompleted)) yield() }

        assertEquals(
            "no phantom second terminal",
            1,
            terminals.terminalStates.value["ws-a"]?.size
        )
        assertTrue(
            "the session must still be alive — a duplicate's unwind must not kill the process group",
            harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID)
        )
        assertNotNull("the pty must still resolve", terminals.getPtySession("ws-a", DEFAULT_TERMINAL_ID))
        assertNotNull("the session record must survive", sessionManager.getSession("ws-a"))
        assertTrue(
            "state must stay Running, not be dragged to Stopped/Error by the duplicate (was ${sessionManager.getState("ws-a")})",
            sessionManager.getState("ws-a") is RuntimeState.Running
        )
        assertEquals("the gate must still be held", "ws-a", manager.activeWorkspaceId.value)

        assertTrue(
            "exactly one Started event — a duplicate start is not a start",
            emitted.count { it is RuntimeEvent.Started } == 1
        )
        assertTrue(
            "a duplicate must not emit Stopped: $emitted",
            emitted.none { it is RuntimeEvent.Stopped }
        )
        assertTrue(
            "a duplicate must not emit Crashed: $emitted",
            emitted.none { it is RuntimeEvent.Crashed }
        )
        assertEquals(
            "the duplicate must not have torn the foreground service down",
            0,
            host.foregroundIdleCalls
        )

        // And the surviving session is a real one: a genuine stop still tears it
        // down through the normal path, and the still-parked coroutine unwinds.
        collector.cancel()
        assertTrue("the surviving session must be stoppable", manager.requestStop("ws-a"))
        c1.join()
        c2.join()
        assertFalse(harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))
        assertEquals(RuntimeState.Stopped, sessionManager.getState("ws-a"))
        assertNull("session must be removed after the genuine stop", sessionManager.getSession("ws-a"))
    }

    /**
     * §1.165 — the duplicate must not merely be harmless *now*; it must not own
     * the teardown path at all. After the duplicate has completed, the genuine
     * stop must still produce exactly one `Stopped` and a clean removal, driven
     * by the coroutine that actually started the session.
     */
    @Test
    fun theGenuineLaunchStillOwnsTheStopUnwindAfterADuplicate() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(buildWorkspaceMetadata("ws-a"))

        val emitted = mutableListOf<RuntimeEvent>()
        val collector = launch { events.collect { emitted += it } }

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        // A second launch arriving while the first is parked in awaitSessionEnd —
        // the double-tap that lands late.
        val c2 = supervisor.launch(this, "ws-a", root)
        withTimeout(5_000) { while (!c2.isCompleted) yield() }

        assertFalse("the genuine launch must still be parked", c1.isCompleted)
        assertTrue("the session must be untouched", harness.runtime.isTerminalAlive("ws-a", DEFAULT_TERMINAL_ID))

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()
        collector.cancel()

        assertEquals(
            "exactly one Stopped, from the coroutine that owned the session",
            1,
            emitted.count { it is RuntimeEvent.Stopped }
        )
        assertEquals(RuntimeState.Stopped, sessionManager.getState("ws-a"))
        assertNull(sessionManager.getSession("ws-a"))
        assertNull("gate must clear", manager.activeWorkspaceId.value)
        assertTrue("foreground teardown must be requested exactly once", host.foregroundIdleCalls == 1)
    }

    /**
     * §1.110: the launch path itself applies the workspace's persisted feature
     * toggles. This is the wiring test — that the enabled `ssh` toggle reaches
     * the guest from a *launch*, with no Features tab and no binder involved
     * (the gap §1.109 left open) — and that the stop unwind releases it.
     */
    @Test
    fun launchAppliesPersistedFeatureTogglesAndStopReleasesThem() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-a", featureToggles = toggles(ssh = true))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        // The reconcile runs after Running is published, so wait for its effect
        // rather than assuming the ordering of two coroutine resumptions.
        withTimeout(5_000) { while (featureHost.startedDaemons.isEmpty()) yield() }

        assertEquals(
            "the enabled ssh toggle must start sshd from the launch path",
            SshdProvisioner.DAEMON_NAME,
            featureHost.startedDaemons.single().first
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()

        assertEquals(
            "the stop unwind must release the guest-side feature half",
            listOf(SshdProvisioner.DAEMON_NAME),
            featureHost.stoppedDaemons
        )
    }

    /** §1.110: a workspace with the toggle off must run no guest feature command.
     *
     * Transformation spec §20/§21 exception: the NETWORK provisioning step now
     * runs at EVERY launch regardless of toggles — DNS (SYSTEM mode) and the
     * hosts-file merge are boot-time provisioning, not an opt-in feature — so
     * this test asserts no *feature* command (ssh shim, notifications,
     * clipboard, audio) runs while allowing the network step's own
     * idempotent execs.
     */
    @Test
    fun launchWithTheToggleOffRunsNoFeatureCommand() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-a", featureToggles = toggles(ssh = false))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()

        assertTrue("no daemon should have started", featureHost.startedDaemons.isEmpty())
        // The only execs permitted on a toggles-off launch are the network
        // provisioning step's own resolv.conf/hosts read/write (DNS is
        // skipped when the fake host DNS provider returns nothing and the
        // modeled guest has a resolver; SYSTEM mode leaves the guest resolver
        // untouched). Every ssh/notify/clipboard/audio exec is a bug.
        val nonNetworkExecs = featureHost.execCalls.filter { exec ->
            !(exec.contains("/etc/hosts") || exec.contains("hostname") ||
                exec.contains("resolv.conf"))
        }
        assertTrue("no feature exec should have run for a disabled feature: $nonNetworkExecs", nonNetworkExecs.isEmpty())
    }

    /**
     * §1.111: the notification half of the same launch path — the enabled
     * `notifications` toggle installs the guest shim from a launch (no Features
     * tab, no binder), and the stop unwind withdraws the workspace's
     * notifications so a stopped workspace can't keep implying it runs.
     */
    @Test
    fun launchInstallsTheNotificationShimAndStopWithdrawsNotifications() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-a", featureToggles = toggles(ssh = false, notifications = true))
        )

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it == NotifySendProvisioner.installCommand() }) yield()
        }

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()

        assertTrue(
            "the stop unwind must withdraw the workspace's guest notifications",
            notificationBridge.cancelled.contains("ws-a")
        )
        assertTrue("no daemon belongs to the notification feature", featureHost.startedDaemons.isEmpty())
    }

    /**
     * §1.112: the clipboard half of the same launch path — the enabled
     * `clipboard` toggle installs the guest shims from a launch (no Features tab,
     * no binder) and seeds the paste mirror, and the stop unwind *deletes* that
     * mirror so a stopped workspace isn't left holding a copy of the user's
     * clipboard in its rootfs.
     */
    @Test
    fun launchInstallsTheClipboardBridgeAndStopDeletesTheMirror() = runBlocking {
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-a", featureToggles = toggles(ssh = false, clipboard = true))
        )
        hostClipboard.text = "user clipboard"

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it == ClipboardBridgeProvisioner.installCommand() }) yield()
        }

        val mirror = File(harness.filesDir, "clip-in-ws-a/clipboard")
        withTimeout(5_000) { while (!mirror.exists()) yield() }
        assertEquals("the launch must seed the paste mirror", "user clipboard", mirror.readText())

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()

        assertFalse(
            "the stop unwind must delete the mirror — it holds the user's clipboard",
            mirror.exists()
        )
        assertTrue("no daemon belongs to the clipboard feature", featureHost.startedDaemons.isEmpty())
    }

    /**
     * §1.113: the audio half of the same launch path — the enabled `audio` toggle
     * creates the FIFO and wires the guest's ALSA default device from a launch (no
     * Features tab, no binder), and the stop unwind stops the pump without running
     * a guest command (the guest may already be gone, and a FIFO leaves no data at
     * rest to clean up — unlike §1.112's mirror).
     */
    @Test
    fun launchCreatesTheAudioBridgeAndStopRunsNoGuestUninstall() = runBlocking {
        audioSink.available = true
        val root = seedReadyRootfs("ws-a")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-a", featureToggles = toggles(ssh = false, audio = true))
        )

        val c1 = supervisor.launch(this, "ws-a", root)
        awaitState("ws-a") { it is RuntimeState.Running }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it == AudioBridgeProvisioner.prepareCommand() }) yield()
        }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it == AudioBridgeProvisioner.installAlsaCommand() }) yield()
        }

        // Ordering, from the launch path: the FIFO exists and is being read before
        // the guest's default device is pointed at it. The reverse would hang the
        // first program the user played something with (ALSA's file plugin opens
        // its target O_WRONLY, which blocks with no reader).
        val prepareAt = featureHost.execCalls.indexOf(AudioBridgeProvisioner.prepareCommand())
        val installAt = featureHost.execCalls.indexOf(AudioBridgeProvisioner.installAlsaCommand())
        assertTrue("prepare must precede the ALSA install", prepareAt in 0 until installAt)

        assertTrue("STOP must succeed", manager.requestStop("ws-a"))
        c1.join()

        assertFalse(
            "the stop unwind must not run a guest uninstall — the toggle is still on",
            featureHost.execCalls.any { it == AudioBridgeProvisioner.uninstallCommand() }
        )
        assertTrue("no daemon belongs to the audio feature", featureHost.startedDaemons.isEmpty())
    }

    // ── Phase 2E-1: the deferred feature-reconcile phase ──

    /**
     * Fresh collaborators + supervisor for one deferred-phase test (the setUp
     * ones are shared and stateful across tests). [x11SessionManager] is the
     * service-side X11 state machine the phase observes; null skips the
     * observation entirely.
     */
    private fun deferredSupervisor(
        x11SessionManager: X11SessionManager?,
        fallbackMillis: Long
    ): WorkspaceSessionSupervisor {
        featureHost = RecordingGuestFeatureHost()
        notificationBridge = RecordingNotificationBridge()
        hostClipboard = RecordingHostClipboard()
        return WorkspaceSessionSupervisor(
            workspaceRepository = harness.workspaceRepository,
            launchValidator = WorkspaceLaunchValidator(fileManager),
            sessionManager = sessionManager,
            workspaceTerminalManager = terminals,
            lifecycleManager = manager,
            runtimeTrace = runtimeTrace,
            diagnosticLogManager = Mockito.mock(DiagnosticLogManager::class.java),
            events = events,
            featureReconciler = WorkspaceFeatureReconciler(
                harness.workspaceRepository,
                featureHost,
                WorkspaceNotificationSupervisor(
                    host = featureHost,
                    bridge = notificationBridge,
                    scope = notificationScope,
                    spoolDirFor = { File(harness.filesDir, "spool-$it") }
                ),
                WorkspaceClipboardSupervisor(
                    host = featureHost,
                    clipboard = hostClipboard,
                    scope = notificationScope,
                    outDirFor = { File(harness.filesDir, "clip-out-$it") },
                    mirrorFileFor = { File(harness.filesDir, "clip-in-$it/clipboard") }
                ),
                WorkspaceAudioSupervisor(
                    host = featureHost,
                    sink = audioSink,
                    scope = notificationScope,
                    sourceFor = { audioSource },
                    reopenDelayMillis = 20L
                ),
                WorkspaceNetworkProvisioner(harness.workspaceRepository, featureHost, HostDnsProvider { emptyList() }),
                // The SAME registry the SessionManager aggregates from — the
                // health-refreshed test asserts the production report path.
                healthRegistry
            ),
            x11SessionManager = x11SessionManager,
            host = host,
            deferredReconcileFallbackMillis = fallbackMillis
        )
    }

    /**
     * Phase 2E-1: network provisioning must run INLINE — before the deferred
     * feature phase can possibly have applied anything. The desktop start path
     * consumes /etc/resolv.conf, which is the whole reason network stayed
     * awaited; with the X11 gate held in STARTING (and a fallback an hour
     * away) the resolv.conf exec must already be on the wire while no feature
     * has run, and the full run must keep network-before-ssh order.
     */
    @Test
    fun networkProvisioningRunsInlineBeforeTheDeferredPhase() = runBlocking {
        val x11 = X11SessionManager(runtimeTrace)
        x11.setState("ws-defer", X11SessionState.X11_STARTING)
        val supervisor = deferredSupervisor(x11, fallbackMillis = 60_000)
        val root = seedReadyRootfs("ws-defer")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-defer", featureToggles = toggles(ssh = true))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-defer", root)
        awaitState("ws-defer") { it is RuntimeState.Running }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it.contains("resolv.conf") }) yield()
        }

        assertTrue(
            "network provisioning must run inline: ${featureHost.execCalls}",
            featureHost.execCalls.any { it.contains("resolv.conf") }
        )
        assertTrue(
            "the deferred phase must not have applied features while the desktop start is in flight",
            featureHost.startedDaemons.isEmpty()
        )

        // The desktop start settles → the deferred phase converges.
        x11.setState("ws-defer", X11SessionState.X11_READY)
        withTimeout(5_000) {
            while (featureHost.startedDaemons.isEmpty()) yield()
        }
        val networkAt = featureHost.execCalls.indexOfFirst { it.contains("resolv.conf") }
        val sshAt = featureHost.execCalls.indexOfFirst { it.contains("sshd") }
        assertTrue(
            "network must be applied before the deferred ssh reconcile: ${featureHost.execCalls}",
            networkAt in 0 until sshAt
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-defer"))
        c1.join()
    }

    /**
     * Phase 2E-1: the deferred phase must converge even when the desktop never
     * starts — no X11 state is ever set (the desktop feature is disabled or
     * the user never opens it). IDLE holds the gate (a desktop may still be
     * requested), so convergence here comes from the bounded fallback deadline,
     * not an immediate run: the deferred execs must never land in a still-open
     * desktop-start contention window.
     */
    @Test
    fun deferredPhaseRunsEvenWhenTheDesktopNeverStarts() = runBlocking {
        val x11 = X11SessionManager(runtimeTrace) // stays IDLE forever
        val supervisor = deferredSupervisor(x11, fallbackMillis = 250)
        val root = seedReadyRootfs("ws-fallback")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-fallback", featureToggles = toggles(ssh = true, notifications = true))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-fallback", root)
        awaitState("ws-fallback") { it is RuntimeState.Running }

        withTimeout(5_000) {
            while (featureHost.startedDaemons.isEmpty()) yield()
        }
        assertTrue(
            "the deferred phase must converge with no desktop start: ${featureHost.startedDaemons}",
            featureHost.startedDaemons.any { it.first == SshdProvisioner.DAEMON_NAME }
        )
        assertTrue(
            "the deferred phase must install the notification shim",
            featureHost.execCalls.any { it == NotifySendProvisioner.installCommand() }
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-fallback"))
        c1.join()
        assertEquals(
            "the deferred phase's daemon must still be released on stop",
            listOf(SshdProvisioner.DAEMON_NAME),
            featureHost.stoppedDaemons
        )
    }

    /**
     * Phase 2E-1: the bounded fallback — a desktop start that NEVER settles
     * (STARTING forever) must not defer the features forever. After the bound
     * expires the phase runs anyway.
     */
    @Test
    fun deferredPhaseConvergesWhenTheDesktopStartNeverSettles() = runBlocking {
        val x11 = X11SessionManager(runtimeTrace)
        x11.setState("ws-stuck", X11SessionState.X11_STARTING)
        val supervisor = deferredSupervisor(x11, fallbackMillis = 300)
        val root = seedReadyRootfs("ws-stuck")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-stuck", featureToggles = toggles(ssh = true))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-stuck", root)
        awaitState("ws-stuck") { it is RuntimeState.Running }

        withTimeout(5_000) {
            while (featureHost.startedDaemons.isEmpty()) yield()
        }
        assertTrue(
            "the bounded fallback must converge despite a start that never settles",
            featureHost.startedDaemons.any { it.first == SshdProvisioner.DAEMON_NAME }
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-stuck"))
        c1.join()
    }

    /**
     * Phase 2E-1: the desktop-start gate — while an X11 start is STARTING, the
     * deferred phase must WAIT (its execs would compete with the desktop's);
     * the moment the start settles (READY), the phase converges.
     */
    @Test
    fun deferredPhaseWaitsForAnActiveDesktopStartAndRunsWhenItSettles() = runBlocking {
        val x11 = X11SessionManager(runtimeTrace)
        x11.setState("ws-gate", X11SessionState.X11_STARTING)
        val supervisor = deferredSupervisor(x11, fallbackMillis = 60_000)
        val root = seedReadyRootfs("ws-gate")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-gate", featureToggles = toggles(ssh = true))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-gate", root)
        awaitState("ws-gate") { it is RuntimeState.Running }

        // While STARTING: nothing applied (the fallback is an hour away).
        withTimeout(5_000) { delay(700) }
        assertTrue(
            "the deferred phase must WAIT while a desktop start is in flight: ${featureHost.startedDaemons}",
            featureHost.startedDaemons.isEmpty()
        )

        // The desktop start settles → the phase converges.
        x11.setState("ws-gate", X11SessionState.X11_READY)
        withTimeout(5_000) {
            while (featureHost.startedDaemons.isEmpty()) yield()
        }
        assertTrue(
            "the deferred phase must run once the desktop start settles",
            featureHost.startedDaemons.any { it.first == SshdProvisioner.DAEMON_NAME }
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-gate"))
        c1.join()
        assertEquals(
            listOf(SshdProvisioner.DAEMON_NAME),
            featureHost.stoppedDaemons
        )
    }

    /**
     * Phase 2E-1: a failure inside the deferred phase must not crash the
     * session scope — the launch coroutine keeps running, the session stays
     * alive, and a stop still unwinds cleanly (fail-open, same contract as the
     * inline reconcile).
     */
    @Test
    fun deferredPhaseFailureIsFailOpenAndKeepsTheSessionAlive() = runBlocking {
        val supervisor = deferredSupervisor(null, fallbackMillis = 250)
        val root = seedReadyRootfs("ws-failopen")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-failopen", featureToggles = toggles(ssh = true))
        )
        featureHost.withSshdInstalled().apply { execThrows = IllegalStateException("proot host died") }

        val c1 = supervisor.launch(this, "ws-failopen", root)
        awaitState("ws-failopen") { it is RuntimeState.Running }

        // Give the deferred phase its fallback window plus margin; the session
        // must still be alive afterwards (a scope crash would end the coroutine
        // and clobber the state).
        withTimeout(5_000) { delay(600) }
        assertTrue("the session must survive a deferred-phase failure", c1.isActive)
        assertTrue(
            "the session must still be Running",
            sessionManager.getState("ws-failopen") is RuntimeState.Running
        )
        assertTrue(
            "the runtime must still be alive",
            harness.runtime.isTerminalAlive("ws-failopen", DEFAULT_TERMINAL_ID)
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-failopen"))
        c1.join()
        assertEquals(RuntimeState.Stopped, sessionManager.getState("ws-failopen"))
    }

    /**
     * Phase 2E-1: a deferred feature that reports Degraded must be aggregated
     * into the session's health by the phase's final refreshHealth — the
     * post-deferred aggregate equals the pre-split single-pass reconcile's.
     */
    @Test
    fun deferredPhaseFailureIsHealthRefreshed() = runBlocking {
        val supervisor = deferredSupervisor(null, fallbackMillis = 250)
        val root = seedReadyRootfs("ws-health")
        // ssh enabled but unstartable: no package manager, no sshd →
        // SshFeatureState.Failed → COMPONENT_SSH reported Degraded.
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-health", featureToggles = toggles(ssh = true))
        )
        featureHost.apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses["which apt-get"] = CommandResult(1, "", "")
            responses["os-release"] = CommandResult(1, "", "")
        }

        val c1 = supervisor.launch(this, "ws-health", root)
        awaitState("ws-health") { it is RuntimeState.Running }

        // NB: the INLINE network phase may report its own degradation first —
        // wait until the DEFERRED phase's ssh failure has been reported AND
        // aggregated by the phase's final refreshHealth.
        fun sshDegradationVisibleInState(): Boolean {
            val current = sessionManager.getState("ws-health")
            val health = (current as? RuntimeState.Running)?.health ?: return false
            return (health as? RuntimeHealth.Degraded)?.degradations?.any { it.component == "ssh" } == true
        }
        withTimeout(5_000) {
            while (!sshDegradationVisibleInState()) yield()
        }
        val state = sessionManager.getState("ws-health")
        assertTrue("the session must still be Running (fail-open)", state is RuntimeState.Running)
        val health = (state as RuntimeState.Running).health
        assertTrue("expected Degraded health, was $health", health is RuntimeHealth.Degraded)
        val components = (health as RuntimeHealth.Degraded).degradations.map { it.component }
        assertTrue(
            "the deferred ssh failure must be among the degradations: $components",
            "ssh" in components
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-health"))
        c1.join()
    }

    /**
     * Phase 2E-1: the audio unit must stay atomic inside the deferred phase —
     * pump started BEFORE the guest's ALSA config is installed (the reverse
     * hangs the first playback program on an O_WRONLY FIFO open).
     */
    @Test
    fun deferredAudioUnitKeepsPumpBeforeAlsaConfig() = runBlocking {
        audioSink.available = true
        val supervisor = deferredSupervisor(null, fallbackMillis = 250)
        val root = seedReadyRootfs("ws-audio-defer")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-audio-defer", featureToggles = toggles(ssh = false, audio = true))
        )

        val c1 = supervisor.launch(this, "ws-audio-defer", root)
        awaitState("ws-audio-defer") { it is RuntimeState.Running }
        withTimeout(5_000) {
            while (featureHost.execCalls.none { it == AudioBridgeProvisioner.installAlsaCommand() }) yield()
        }

        val prepareAt = featureHost.execCalls.indexOf(AudioBridgeProvisioner.prepareCommand())
        val installAt = featureHost.execCalls.indexOf(AudioBridgeProvisioner.installAlsaCommand())
        assertTrue(
            "the deferred audio unit must keep pump (prepare) before the ALSA config",
            prepareAt in 0 until installAt
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-audio-defer"))
        c1.join()
    }

    /**
     * Phase 2E-1: a disabled toggle still runs no guest command in the
     * deferred phase — same scope-guard semantics as the pre-split launch.
     */
    @Test
    fun deferredPhaseSkipsDisabledFeatures() = runBlocking {
        val supervisor = deferredSupervisor(null, fallbackMillis = 250)
        val root = seedReadyRootfs("ws-off")
        harness.workspaceRepository.add(
            buildWorkspaceMetadata("ws-off", featureToggles = toggles(ssh = false))
        )
        featureHost.withSshdInstalled()

        val c1 = supervisor.launch(this, "ws-off", root)
        awaitState("ws-off") { it is RuntimeState.Running }
        // Let the deferred phase fall out and run; then stop.
        withTimeout(5_000) { delay(500) }

        assertTrue("no daemon should have started for a disabled toggle", featureHost.startedDaemons.isEmpty())
        val nonNetworkExecs = featureHost.execCalls.filter { exec ->
            !(exec.contains("/etc/hosts") || exec.contains("hostname") || exec.contains("resolv.conf"))
        }
        assertTrue(
            "no feature exec may run for a disabled toggle: $nonNetworkExecs",
            nonNetworkExecs.isEmpty()
        )

        assertTrue("STOP must succeed", manager.requestStop("ws-off"))
        c1.join()
    }
}

private class RecordingHost : WorkspaceSessionSupervisor.Host {
    val displayNames = mutableListOf<Pair<String, String>>()
    val forgotten = mutableListOf<String>()
    var runningCalls = 0
    var foregroundIdleCalls = 0

    override fun onDisplayName(workspaceId: String, displayName: String) {
        displayNames += workspaceId to displayName
    }

    override fun onDisplayNameForgotten(workspaceId: String) {
        forgotten += workspaceId
    }

    override fun onSessionRunning(workspaceId: String) {
        runningCalls++
    }

    override fun onForegroundIdle() {
        foregroundIdleCalls++
    }
}
