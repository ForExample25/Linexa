package com.fem.linexa.service.installer

import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 04: the extraction timeout path runs the containment helper instead
 * of a bare `destroyForcibly()`, and the test-only timeout override makes the
 * path reachable in milliseconds.
 *
 * The REAL [RootfsExtractor] runs against [FakeProot]'s `SLOW_EXIT` scenario
 * (it sleeps `SLEEP_SLEEP_SECONDS` before exiting 0) with a 500 ms override:
 * the extraction therefore hits the deadline while the fake is still alive,
 * [ProcessContainment.killTree] is invoked for real (SIGTERM lands on the
 * host), and the failure must be the timeout message — not the seccomp
 * detector, not a crash.
 */
class RootfsExtractorTimeoutContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.SLOW_EXIT)
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

    @Test
    fun aHungExtractionTimesOutThroughContainmentInsteadOfHangingForHalfAnHour() {
        // SLOW_EXIT keeps the fake alive for FakeProot.SLOW_SLEEP_SECONDS; the
        // override turns the 30-minute production deadline into 500 ms.
        harness.rootfsExtractor.extractionTimeoutOverrideMs = 500L

        val started = System.nanoTime()
        val failure = runBlocking {
            harness.rootfsExtractor.extract(
                tempArchive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
                    .apply { writeText("fake archive payload") },
                staging = harness.fakeRootfs(),
                workspaceId = "timeout-test"
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull("a hung extraction must surface a failure", failure)
        assertTrue(
            "the failure must be the timeout message, got: $failure",
            failure!!.contains("Native extraction timed out after 500ms")
        )
        // Containment itself is bounded by grace (3 s) + hard limit (10 s).
        // The remaining wall time is the output drains: the fake's `sleep 5`
        // child survives its (SIGKILLed) parent and holds the pipes open, so
        // the two sequential drain joins can add up to ~10 s on a JVM host.
        assertTrue(
            "the run must stay bounded, took ${elapsedMs}ms",
            elapsedMs < 25_000
        )
        assertTrue(
            "the fake must have been launched (the timeout hit a real child)",
            FakeProot.invocations(prootDir).any { "--link2symlink" in it.argv }
        )
    }
}
