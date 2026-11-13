package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.ThreadRecordingLogSink
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.145 — the terminal-freeze fix is a **dispatch** fix, and this is the only
 * suite that can fail if it regresses.
 *
 * `openTerminal` is reached on `Dispatchers.Main` from both of its callers
 * (`TerminalsViewModel.viewModelScope`, and the workspace-launch path through
 * `RuntimeService.serviceScope`), and terminal-lib runs *every* session's output
 * emulation on the Handler its sessions were constructed on — the main thread.
 * So each blocking host step left on the caller's dispatcher starves the output
 * and input echo of terminals that are **already open**, which is what "the
 * terminal freezes after opening a new session" actually was.
 *
 * That makes the invariant a pair, not a single rule:
 *
 *  1. every blocking *host* step (scratch-dir churn, guest `/tmp` repair,
 *     `config.yaml` parse, shell probing, argv/env construction) must run OFF
 *     the caller's dispatcher; and
 *  2. the pty fork must stay ON it — `TerminalSession`'s constructor adopts the
 *     constructing thread's Looper for `mMainThreadHandler` and then owns
 *     emulator state from that thread, while `TerminalView.onDraw` reads it from
 *     the UI thread. Moving the fork to a worker would either throw
 *     (`Looper.prepare()`) or silently split writer from reader, since
 *     `TerminalEmulator`/`TerminalBuffer` have no synchronization at all.
 *
 * Rule 2 is why "just wrap the whole function in `withContext(Dispatchers.IO)`"
 * is wrong, and why a test that only checked "openTerminal does not block the
 * caller" would approve a broken fix.
 *
 * Every other runtime suite here asserts the *product* of a launch — argv, env,
 * binds, the session map — all of which §1.145 leaves byte-identical. They pass
 * before and after the fix and would pass again if it were reverted. Dispatch is
 * observed through the production log lines that each step already emits (see
 * [ThreadRecordingLogSink]) rather than a test-only hook, so what is asserted is
 * the shipped code path.
 *
 * The caller runs on a dedicated, distinctively named thread with its own
 * `runBlocking` event loop, standing in for the Main dispatcher: work that stays
 * on the caller is observed on that thread by name, and work that is dispatched
 * lands on a `DefaultDispatcher-worker-*` thread instead.
 */
class PRootOpenSessionDispatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        ThreadRecordingLogSink.install()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    @After
    fun tearDown() {
        ThreadRecordingLogSink.reset()
        harness.ptyFactory.beforeCreate = null
    }

    /**
     * Stands in for the Main dispatcher: one named thread, one `runBlocking`
     * event loop on it, so "the caller's dispatcher" is identifiable by thread
     * name and anything dispatched elsewhere is visibly elsewhere.
     */
    private fun <T> onCallerThread(block: suspend () -> T): T {
        val factory = ThreadFactory { r -> Thread(r, CALLER_THREAD) }
        val executor = Executors.newSingleThreadExecutor(factory)
        try {
            return executor.submit<T> { runBlocking { block() } }.get()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openOnCallerThread(workspaceId: String = "ws-1") {
        onCallerThread {
            harness.runtime.openTerminal(
                workspaceId,
                harness.fakeRootfs().absolutePath,
                DEFAULT_TERMINAL_ID
            , asRoot = false)
        }
    }

    private val prootTmpParent: File get() = harness.bootstrapManager.prootTmpDir

    /**
     * Whether [thread] is the stand-in caller ("Main") thread.
     *
     * A prefix match, not equality: `runBlocking` appends its coroutine debug
     * name (`" @coroutine#2"`) to the thread name while a coroutine is running
     * on it, so the recorded name is `"<caller> @coroutine#N"` rather than the
     * bare name the ThreadFactory assigned.
     */
    private fun isCallerThread(thread: String?): Boolean =
        thread != null && thread.startsWith(CALLER_THREAD)

    // ── rule 1: the blocking host work leaves the caller's dispatcher ─────

    @Test
    fun guestTmpRepairRunsOffTheCallersDispatcher() {
        openOnCallerThread()

        // Emitted by GuestRootfsInitializer.ensureGuestTmp, from inside the
        // hoisted scratch-dir/guest-/tmp block. It stats, mkdirs, forks a real
        // busybox `chmod 1777` and waits for it — the single most expensive
        // host step in the whole function.
        val thread = ThreadRecordingLogSink.threadOfLineContaining("guest /tmp ready:")
        assertNotNull("the guest /tmp repair must still happen on every open", thread)
        assertFalse(
            "guest /tmp repair must NOT run on the caller's dispatcher (it forks chmod and waits)",
            isCallerThread(thread)
        )
        assertTrue(
            "it must run on the IO/Default worker pool, not some ad-hoc thread; was $thread",
            thread!!.startsWith(WORKER_PREFIX)
        )
    }

    @Test
    fun argvAndEnvConstructionRunsOffTheCallersDispatcher() {
        openOnCallerThread()

        // Emitted from inside the hoisted argv/env block, after loadWorkspaceConfig
        // (a config.yaml read+parse) and resolveLaunchShell (up to three guest-image
        // stats) have run — it interpolates the resolved shell, so it cannot be
        // logged before them.
        val thread = ThreadRecordingLogSink.threadOfLineContaining("TERMINAL_CHAIN: openTerminal start")
        assertNotNull("the launch-start line must still be emitted", thread)
        assertFalse(
            "config parse + shell probe + argv construction must NOT run on the caller's dispatcher",
            isCallerThread(thread)
        )
        assertTrue(
            "and must run on the IO/Default worker pool; was $thread",
            thread!!.startsWith(WORKER_PREFIX)
        )
    }

    // ── rule 2: the fork itself stays on the caller's dispatcher ──────────

    @Test
    fun theProotForkStaysOnTheCallersDispatcher() {
        openOnCallerThread()

        val call = harness.createCalls.single()
        assertTrue(
            "the pty child must be forked on the CALLER's thread — terminal-lib's TerminalSession " +
                "adopts the constructing thread's Looper for mMainThreadHandler and then owns " +
                "unsynchronized emulator state from it. Dispatching this to a worker either throws " +
                "Looper.prepare() or races the renderer. Forked on: ${call.callThread}",
            isCallerThread(call.callThread)
        )
    }

    @Test
    fun theDispatchedWorkFinishesBeforeTheForkRatherThanRacingIt() {
        openOnCallerThread()

        val lines = ThreadRecordingLogSink.all().map { it.message }
        val tmpReady = lines.indexOfFirst { it.contains("guest /tmp ready:") }
        val launchStart = lines.indexOfFirst { it.contains("TERMINAL_CHAIN: openTerminal start") }
        val forkEntered = lines.indexOfFirst { it.contains("CHAIN: ENTER ptySessionFactory.create") }

        assertTrue("all three markers must be present", tmpReady >= 0 && launchStart >= 0 && forkEntered >= 0)
        // The hops are `withContext`, which suspends until the block completes —
        // not fire-and-forget. Ordering is the observable difference between the
        // two, and the argv the fork receives is built by the second block.
        assertTrue("guest /tmp must be repaired before argv is built", tmpReady < launchStart)
        assertTrue("argv must be built before the fork", launchStart < forkEntered)
    }

    // ── the scratch dir is released on the failure paths, off Main ────────

    @Test
    fun aFailedForkReleasesTheScratchScope() {
        harness.ptyFactory.beforeCreate = { throw IllegalStateException("forkpty refused") }

        val failure = runCatching {
            onCallerThread {
                harness.runtime.openTerminal(
                    "ws-fail",
                    harness.fakeRootfs().absolutePath,
                    DEFAULT_TERMINAL_ID
                , asRoot = false)
            }
        }

        assertTrue("the failure must still propagate to the caller", failure.isFailure)
        assertFalse(
            "a failed open must not leave a stale PROOT_TMP_DIR behind",
            File(prootTmpParent, "ws-fail/$DEFAULT_TERMINAL_ID").exists()
        )
    }

    @Test
    fun aCancelledOpenStillReleasesTheScratchScope() {
        // The release moved onto Dispatchers.IO with the rest of the scratch-dir
        // work, and `catch (e: Exception)` catches CancellationException too — so
        // without NonCancellable the release's own `withContext` would throw
        // before running, and "the user backed out of the workspace mid-open"
        // would leak a scratch tree per attempt. This is that case: the job is
        // cancelled at the exact moment the fork would happen.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobHolder = arrayOfNulls<Job>(1)
        val rootfs = harness.fakeRootfs().absolutePath

        val job = scope.launch(start = CoroutineStart.LAZY) {
            harness.runtime.openTerminal("ws-cancel", rootfs, DEFAULT_TERMINAL_ID, asRoot = false)
        }
        jobHolder[0] = job
        harness.ptyFactory.beforeCreate = {
            jobHolder[0]?.cancel()
            throw CancellationException("user left the workspace mid-open")
        }

        runBlocking {
            job.start()
            job.join()
        }

        assertTrue("the open must have been cancelled", job.isCancelled)
        assertFalse(
            "a cancelled open must not leak its PROOT_TMP_DIR scope — the release is NonCancellable",
            File(prootTmpParent, "ws-cancel/$DEFAULT_TERMINAL_ID").exists()
        )
    }

    private companion object {
        /** Stands in for the Main thread; distinctive so it cannot collide. */
        const val CALLER_THREAD = "linexa-test-caller-dispatcher"

        /** kotlinx.coroutines names its IO/Default pool threads with this prefix. */
        const val WORKER_PREFIX = "DefaultDispatcher-worker"
    }
}
