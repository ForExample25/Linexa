package com.fem.linexa.service.runtime.container

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.ThreadRecordingLogSink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §16.3 (§1.167) — the last two unconditional forensic dumps in
 * [PRootContainerRuntime] are behind [DebugCapture].
 *
 * These are behavioural, not structural: the sink observes whether the
 * `Logger.i` calls happened at all, which is the only way to tell "the dump was
 * skipped" apart from "the dump ran and its output went nowhere". The pre-§16.3
 * code produced the identical *user-visible* result in both states — that is why
 * the waste was invisible and why a source-level assertion alone would not have
 * caught a later refactor that moves the work back in front of the gate.
 *
 * The two paths, and why each matters on the desktop start path §16.3 measures:
 *
 *  * `runProcess` — every one-shot exec. Its dump is the expensive one: up to
 *    five `fingerprintPath` calls at nine filesystem operations each, plus ~30
 *    `Logger.i` calls and two `toSortedMap()` allocations.
 *  * `startDaemon` — Xvnc is a §1.83 daemon, so every desktop launch paid its
 *    argv/env dump too.
 *
 * Assertions are on **presence or absence of marker lines**, never on their
 * order or count relative to other lines: the point is reachability, and a test
 * pinned to log sequencing would fail on any unrelated logging change.
 * [ThreadRecordingLogSink] is reused as a plain recorder here (its thread
 * column is §1.145's concern, not this test's).
 */
class PRootLaunchForensicsGatingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    /** Marker lines emitted only by `logLaunchForensics` (the runProcess dump). */
    private val execDumpMarkers = listOf(
        "=== FORENSIC: full argv (§1.52) ===",
        "=== FORENSIC: complete env passed to proot (§1.52, sorted) ===",
        "=== FORENSIC: pipeline path verification (§1.52) ==="
    )

    /**
     * Builds a fresh runtime over a fresh fake PRoot.
     *
     * Callable more than once per test — [theGateChangesOnlyLoggingNotTheProcessThatRuns]
     * needs two independent invocation logs to compare. [TemporaryFolder.newFolder]
     * throws on a repeated name, so each setup gets its own numbered directories.
     */
    private fun setUpHarness(scenario: FakeProot.Scenario) {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val n = harnessCount++
        val root = tmp.newFolder("fake-proot-root-$n")
        prootDir = FakeProot.install(root, scenario)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files-$n"),
            cacheDir = tmp.newFolder("cache-$n")
        )
        harness.create()
    }

    private var harnessCount = 0

    @Before
    fun installRecorder() {
        ThreadRecordingLogSink.install()
    }

    @After
    fun tearDown() {
        ThreadRecordingLogSink.reset()
        // Process-wide flag and process-wide memo — both leak into later tests.
        DebugCapture.resetForTesting()
        ProotRuntimeTestHarness.resetSeccompMemo()
    }

    private fun loggedLines(): List<String> = ThreadRecordingLogSink.all().map { it.message }

    private fun linesContaining(marker: String): List<String> =
        loggedLines().filter { it.contains(marker) }

    // ── runProcess (one-shot exec) ────────────────────────────────────────

    @Test
    fun execEmitsNoLaunchForensicsWhileCaptureIsOff() {
        setUpHarness(FakeProot.Scenario.CLEAN_ALWAYS)
        // Fail-closed default; stated explicitly so the test does not depend on it.
        DebugCapture.setEnabled(false)

        val result = runBlocking {
            harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true")
        }

        execDumpMarkers.forEach { marker ->
            assertTrue(
                "no launch forensics may be produced while debug info is off, found: ${linesContaining(marker)}",
                linesContaining(marker).isEmpty()
            )
        }
        // The individual argv/env/fingerprint lines are gone too, not just headers.
        assertTrue(
            "argv dump lines must not be built at all",
            linesContaining("argv[0] = ").isEmpty()
        )
        // Execution is unchanged: the command still ran, once, and succeeded.
        assertEquals(0, result.exitCode)
        assertEquals(1, FakeProot.invocationCount(prootDir))
    }

    @Test
    fun execStillEmitsLaunchForensicsWhileCaptureIsOn() {
        setUpHarness(FakeProot.Scenario.CLEAN_ALWAYS)
        DebugCapture.setEnabled(true)

        val result = runBlocking {
            harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true")
        }

        execDumpMarkers.forEach { marker ->
            assertFalse(
                "the dump must remain available to a user who enabled debug info: missing $marker",
                linesContaining(marker).isEmpty()
            )
        }
        assertFalse(
            "the argv listing is the substance of the dump",
            linesContaining("argv[0] = ").isEmpty()
        )
        assertFalse(
            "the shell-copyable reproduction line must survive",
            linesContaining("shell-copyable:").isEmpty()
        )
        assertEquals(0, result.exitCode)
        assertEquals(1, FakeProot.invocationCount(prootDir))
    }

    @Test
    fun theGateChangesOnlyLoggingNotTheProcessThatRuns() {
        // The safety claim of §16.3's first item: identical execution either way.
        // Compare the fake PRoot's own record of what it was invoked with.
        setUpHarness(FakeProot.Scenario.CLEAN_ALWAYS)

        DebugCapture.setEnabled(false)
        runBlocking { harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true") }
        val withCaptureOff = FakeProot.invocations(prootDir).single()

        setUpHarness(FakeProot.Scenario.CLEAN_ALWAYS)
        DebugCapture.setEnabled(true)
        runBlocking { harness.runtime.exec("ws-1", harness.fakeRootfs().absolutePath, "true") }
        val withCaptureOn = FakeProot.invocations(prootDir).single()

        // argv is compared with the rootfs path dropped: the harness allocates a
        // fresh temp rootfs per call, so the paths legitimately differ. Everything
        // that the gate could plausibly have disturbed — the bind mounts, the
        // -0/--link2symlink/--kill-on-exit flags, the /bin/sh -c bridge and the
        // command itself — is in the compared remainder.
        fun shape(argv: List<String>): List<String> =
            argv.filterNot { it.contains("/rootfs-") }
        assertEquals(
            "the launch argv must not depend on whether debug capture is on",
            shape(withCaptureOff.argv),
            shape(withCaptureOn.argv)
        )
        assertEquals(
            "nor may the seccomp fallback state differ",
            withCaptureOff.hadNoSeccompEnv,
            withCaptureOn.hadNoSeccompEnv
        )
    }

    // ── startDaemon (Xvnc and friends) ────────────────────────────────────

    /**
     * The daemon dump interpolates the daemon name into every header, so the
     * marker is name-qualified — this also proves the gate did not accidentally
     * silence the *session* dump instead.
     */
    private fun daemonDumpMarkers(name: String) = listOf(
        "=== FORENSIC: §1.83 daemon '$name' full argv (§1.52) ===",
        "=== FORENSIC: §1.83 daemon '$name' env keys (§1.52) ===",
        "=== FORENSIC: §1.83 daemon '$name' shell-copyable ==="
    )

    private fun setUpDaemonHarness() {
        setUpHarness(FakeProot.Scenario.DAEMON_STAYS_ALIVE)
        // §1.83: short-circuit resolveSeccompFallback, whose probe would launch
        // the long-lived fake proot and hang for the whole probe timeout.
        ProotRuntimeTestHarness.markSeccompProbeDone()
    }

    @Test
    fun daemonStartEmitsNoForensicsWhileCaptureIsOff() {
        setUpDaemonHarness()
        DebugCapture.setEnabled(false)

        val started = runBlocking {
            harness.runtime.startDaemon("ws-1", harness.fakeRootfs().absolutePath, "xvnc", "Xvnc :1")
        }

        try {
            daemonDumpMarkers("xvnc").forEach { marker ->
                assertTrue(
                    "no daemon forensics may be produced while debug info is off, found: ${linesContaining(marker)}",
                    linesContaining(marker).isEmpty()
                )
            }
            // Daemon lifecycle is untouched by the gate.
            assertTrue("the daemon must still start", started)
            assertTrue(
                "and must still be tracked as alive",
                harness.runtime.isDaemonAlive("ws-1", "xvnc")
            )
        } finally {
            runBlocking { harness.runtime.stopAllDaemons("ws-1") }
        }
    }

    @Test
    fun daemonStartStillEmitsForensicsWhileCaptureIsOn() {
        setUpDaemonHarness()
        DebugCapture.setEnabled(true)

        val started = runBlocking {
            harness.runtime.startDaemon("ws-1", harness.fakeRootfs().absolutePath, "xvnc", "Xvnc :1")
        }

        try {
            daemonDumpMarkers("xvnc").forEach { marker ->
                assertFalse(
                    "a user who enabled debug info must still get the daemon dump: missing $marker",
                    linesContaining(marker).isEmpty()
                )
            }
            assertTrue(started)
        } finally {
            runBlocking { harness.runtime.stopAllDaemons("ws-1") }
        }
    }
}
