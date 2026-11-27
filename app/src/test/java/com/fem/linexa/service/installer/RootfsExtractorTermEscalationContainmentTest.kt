package com.fem.linexa.service.installer

import com.fem.linexa.core.filesystem.ProcessSignal
import com.fem.linexa.core.filesystem.ProcessSignalSender
import com.fem.linexa.core.filesystem.ProcessTableReader
import com.fem.linexa.core.filesystem.ProcessTreeTerminator
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import java.util.concurrent.TimeUnit
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
 * 10E (OPT-TEST-005) / RootFS R1: the extraction timeout path against a
 * PRoot host with a descendant that IGNORES SIGTERM — [FakeProot]'s
 * `IGNORES_TERM` scenario, now with a real `sleep` child.
 *
 * Two contracts are pinned here:
 *
 * 1. **No sender → fail closed.** Production's signal sender calls
 *    `android.system.Os.kill`, which is a JVM stub. Every send fails, the
 *    grace and hard-limit windows elapse, the terminator reports survivors,
 *    and the extractor must NOT claim success: it `destroyForcibly()`s the
 *    host and QUARANTINES staging instead of deleting anything underneath a
 *    possibly-alive tracee. The TERM-ignoring child must still be alive —
 *    proving the "not confirmed" verdict was truthful.
 * 2. **Real sender → whole tree.** Injecting a sender that shells out to the
 *    host `kill` (the only substituted seam; the terminator, `/proc`
 *    snapshot, escalation and verification are real) must terminate BOTH the
 *    host and its TERM-ignoring child, report containment as confirmed, and
 *    leave staging in place for the caller's normal cleanup.
 *
 * The real `Os.kill` variant of test 2 remains the Phase 4 DEVICE row, where
 * Android's kernel and `/proc` visibility are the system under test.
 */
class RootfsExtractorTermEscalationContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    private val selfPid: Int by lazy { File("/proc/self").canonicalFile.name.toInt() }
    private val selfUid: Int by lazy {
        File("/proc/self/status").readLines()
            .first { it.startsWith("Uid:") }
            .removePrefix("Uid:").trim().split(Regex("\\s+")).first().toInt()
    }

    /** Real-signal terminator: the only substitution is `Os.kill`. */
    private val hostTerminator: ProcessTreeTerminator by lazy {
        ProcessTreeTerminator(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                if (target <= 0) {
                    false
                } else {
                    val sig = if (signal == ProcessSignal.TERM) "TERM" else "KILL"
                    runCatching {
                        val p = ProcessBuilder("/bin/sh", "-c", "kill -$sig $target").start()
                        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                    }.getOrDefault(false)
                }
            },
            selfPid = selfPid
        )
    }

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        // RF-013: the §1.46 execve preflights are debug-capture gated now,
        // and IGNORES_TERM counts them to make the real attempt invocation
        // #3. Run this test in the configuration that produces preflights.
        DebugCapture.setEnabled(true)
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.IGNORES_TERM)
        for (name in listOf(
            "lib__bin__tar__.so",
            "lib__bin__busybox__.so",
            "lib__bin__loader__.so",
            "lib__bin__zstd__.so",
            "lib__bin__tmux__.so"
        )) {
            val inert = File(prootDir, name)
            inert.writeText("#!/bin/sh\n# inert bootstrap stand-in for JVM tests\nexit 0\n")
            check(inert.setExecutable(true)) { "could not chmod +x $inert" }
        }
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        harness.bootstrapManager.setup()
    }

    @After
    fun killLeftovers() {
        DebugCapture.resetForTesting()
        if (!::prootDir.isInitialized) return
        for (name in listOf(FakeProot.HOST_PID_FILE, FakeProot.IGNORES_TERM_CHILD_FILE)) {
            val pid = FakeProot.pidFile(prootDir, name) ?: continue
            if (ProcessTableReader.readEntry(pid)?.isLive != true) continue
            runCatching {
                ProcessBuilder("/bin/sh", "-c", "kill -KILL $pid")
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun isLive(pid: Int): Boolean = ProcessTableReader.readEntry(pid)?.isLive == true

    private fun awaitDead(pid: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLive(pid)) return true
            Thread.sleep(25)
        }
        return !isLive(pid)
    }

    private fun extractTimedOut(): String? = runBlocking {
        harness.rootfsExtractor.extract(
            tempArchive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
                .apply { writeText("fake archive payload") },
            staging = harness.fakeRootfs(),
            workspaceId = "term-escalation-test"
        )
    }

    @Test
    fun aTimeoutAgainstATermIgnoringDescendantFailsClosedWithQuarantine() {
        // IGNORES_TERM keeps the fake alive until SIGKILL; the override turns
        // the 30-minute production deadline into 2 s — long enough that the
        // TERM cannot land before the sh has installed its trap, short
        // enough that the timeout path is reached quickly. Production's
        // Os.kill is a JVM stub, so no signal lands: the verification
        // windows are shortened only because the outcome is already certain.
        harness.rootfsExtractor.extractionTimeoutOverrideMs = 2_000L
        harness.rootfsExtractor.containmentGraceOverrideMs = 300L
        harness.rootfsExtractor.containmentHardLimitOverrideMs = 300L

        val started = System.nanoTime()
        val failure = extractTimedOut()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull("a TERM-ignoring extraction must surface a failure", failure)
        assertTrue(
            "the failure must be the timeout message, got: $failure",
            failure!!.contains("Native extraction timed out after 2000ms")
        )
        // The fail-closed contract: containment must NOT be claimed when the
        // send could not land, and staging must be quarantined (kept, renamed
        // out of the way) rather than deleted.
        assertTrue(
            "containment must not be claimed while a descendant survives: $failure",
            failure.contains("process containment could not be confirmed")
        )
        assertTrue(
            "staging must be quarantined, not deleted: $failure",
            failure.contains("staging quarantined at ")
        )
        // The deadline was actually spent, and the fallback stayed bounded.
        assertTrue(
            "the extraction deadline must actually have elapsed, took ${elapsedMs}ms",
            elapsedMs >= 2_000
        )
        assertTrue(
            "the run must stay bounded (deadline + destroyForcibly + drain caps), took ${elapsedMs}ms",
            elapsedMs < 25_000
        )
        // The scenario really produced a descendant, and that descendant —
        // which ignores TERM — is still alive: the "not confirmed" verdict
        // was not a formality.
        val host = FakeProot.awaitPidFile(prootDir, FakeProot.HOST_PID_FILE)
        val child = FakeProot.awaitPidFile(prootDir, FakeProot.IGNORES_TERM_CHILD_FILE)
        assertNotNull("the fake must record its host pid", host)
        assertNotNull("the fake must record its TERM-ignoring child pid", child)
        assertTrue(
            "without a real sender the TERM-ignoring child must survive — containment was not confirmed",
            isLive(child!!)
        )
        val invocations = FakeProot.invocations(prootDir)
        assertTrue(
            "the IGNORES_TERM scenario must have been entered by the real attempt",
            File(prootDir, FakeProot.TERM_STARTED_FILE).exists()
        )
        assertTrue(
            "the fake must have been launched as the extractor's real child",
            invocations.any { "--link2symlink" in it.argv }
        )
    }

    @Test
    fun aRealSignalSenderTerminatesTheHostAndItsTermIgnoringChild() {
        harness.rootfsExtractor.processTerminatorForTest = hostTerminator
        harness.rootfsExtractor.extractionTimeoutOverrideMs = 2_000L
        // The child ignores TERM, so the terminator must escalate. Short but
        // non-zero windows keep the run fast while still letting the grace
        // poll observe the survivor.
        harness.rootfsExtractor.containmentGraceOverrideMs = 500L
        harness.rootfsExtractor.containmentHardLimitOverrideMs = 5_000L

        val started = System.nanoTime()
        val failure = extractTimedOut()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull("a TERM-ignoring extraction must surface a failure", failure)
        assertTrue(
            "the failure must be the timeout message, got: $failure",
            failure!!.contains("Native extraction timed out after 2000ms")
        )
        assertFalse(
            "tree containment must be confirmed when a real sender killed host + child: $failure",
            failure.contains("could not be confirmed")
        )
        assertFalse(
            "a confirmed containment must not quarantine staging: $failure",
            failure.contains("staging quarantined at ")
        )
        val host = FakeProot.awaitPidFile(prootDir, FakeProot.HOST_PID_FILE)
        val child = FakeProot.awaitPidFile(prootDir, FakeProot.IGNORES_TERM_CHILD_FILE)
        assertNotNull("the fake must record its host pid", host)
        assertNotNull("the fake must record its TERM-ignoring child pid", child)
        assertTrue(
            "the SIGKILL escalation must terminate the TERM-ignoring child (pid $child)",
            awaitDead(child!!)
        )
        assertTrue(
            "the PRoot host must be gone with its tree (pid $host)",
            awaitDead(host!!)
        )
        assertTrue(
            "the run must stay bounded, took ${elapsedMs}ms",
            elapsedMs < 25_000
        )
    }
}
