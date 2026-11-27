package com.fem.linexa.service.installer

import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RF-012 — the extraction limit is progress-aware.
 *
 * The old 30-minute wall clock failed a large rootfs on slow storage even
 * while tar was reading, and allowed a wedged child to burn the full half
 * hour. Production now stops after [RootfsExtractor]'s stall window without a
 * new checkpoint/member, with a much larger absolute ceiling for a runaway
 * process.
 *
 * The decision itself is tested purely; one end-to-end case proves the
 * stall message reaches the caller through the REAL extractor and
 * [FakeProot] (which emits no checkpoints, so it genuinely stalls).
 */
class RootfsExtractorProgressTimeoutTest {

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

    private fun reason(
        now: Long,
        lastProgress: Long,
        absolute: Long = 6 * 60 * 60_000L,
        stall: Long = 15 * 60_000L
    ) = RootfsExtractor.extractionTimeoutReason(
        nowMs = now,
        startedAtMs = 0L,
        lastProgressAtMs = lastProgress,
        absoluteTimeoutMs = absolute,
        stallTimeoutMs = stall,
        absoluteLabel = "6 hours",
        stallLabel = "15 minutes"
    )

    @Test
    fun aSlowExtractionThatKeepsMakingProgressIsNotTimedOut() {
        // 90 minutes total — far past the old 30-minute absolute limit — but
        // the last checkpoint was 30 seconds ago, so it must continue.
        val now = 90 * 60_000L
        assertNull(reason(now = now, lastProgress = now - 30_000L))
    }

    @Test
    fun noProgressForTheStallWindowIsReportedAsAStall() {
        val now = 20 * 60_000L
        val result = reason(now = now, lastProgress = now - 15 * 60_000L)
        assertNotNull("a stalled extraction must be stopped", result)
        assertEquals("15 minutes without progress", result)
    }

    @Test
    fun theAbsoluteCeilingStillBoundsARunawayProcessEvenWithProgress() {
        val now = 7 * 60 * 60_000L
        val result = reason(
            now = now,
            lastProgress = now - 1_000L, // "progress" every second, forever
            absolute = 6 * 60 * 60_000L
        )
        assertEquals("6 hours", result)
    }

    @Test
    fun aSilentRealExtractionReportsTheStallMessage() {
        // No absolute override: the stall override alone must drive the
        // decision. Containment windows are shortened because the JVM
        // Os.kill sender cannot actually signal.
        harness.rootfsExtractor.extractionStallTimeoutOverrideMs = 500L
        harness.rootfsExtractor.containmentGraceOverrideMs = 300L
        harness.rootfsExtractor.containmentHardLimitOverrideMs = 300L

        val failure = runBlocking {
            harness.rootfsExtractor.extract(
                tempArchive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
                    .apply { writeText("fake archive payload") },
                staging = harness.fakeRootfs(),
                workspaceId = "stall-test"
            )
        }

        assertNotNull("a stalled extraction must fail", failure)
        assertTrue(
            "the message must name the stall, got: $failure",
            failure!!.contains("500ms without progress")
        )
        assertTrue(
            "the message must stay a timeout failure, got: $failure",
            failure.contains("Native extraction timed out after")
        )
    }
}
