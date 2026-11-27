package com.fem.linexa.service.installer

import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UX-003 — extraction progress must not overshoot to 100% and sit there.
 *
 * The checkpoint estimate counts uncompressed tar bytes, but the file-size
 * sum the analyzer produces excludes tar's per-member headers and padding.
 * A many-small-files rootfs therefore reached the old denominator before the
 * process ended. The fraction now includes a per-entry overhead estimate, is
 * monotonic, and is capped just below 1.0 until the child actually exits.
 */
class RootfsExtractorProgressAccuracyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
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

    private fun fraction(
        records: Long,
        bytes: Long = 1_000_000L,
        entries: Long = 0L,
        previous: Float = 0f
    ) = RootfsExtractor.extractionProgressFraction(
        records = records,
        expectedExtractedBytes = bytes,
        expectedEntryCount = entries,
        previous = previous
    )

    @Test
    fun memberOverheadLowersTheEstimateForManySmallFiles() {
        val withoutOverhead = fraction(records = 50)
        val withOverhead = fraction(records = 50, entries = 20_000)
        assertTrue(
            "tar headers/padding must be part of the denominator: $withOverhead vs $withoutOverhead",
            withOverhead < withoutOverhead
        )
    }

    @Test
    fun aRunningExtractionNeverReportsOneHundredPercent() {
        val atEnd = fraction(records = 10_000_000L)
        assertEquals(
            "the estimate may approach completion but not claim it",
            0.99f,
            atEnd,
            0.0001f
        )
    }

    @Test
    fun progressIsMonotonicEvenIfACheckpointRecounts() {
        assertEquals(
            "a lower recomputed value must not move the bar backwards",
            0.4f,
            fraction(records = 1, previous = 0.4f),
            0.0001f
        )
    }

    @Test
    fun noExpectedSizeLeavesProgressIndeterminate() {
        assertEquals(0f, fraction(records = 10, bytes = 0L), 0f)
        assertEquals(0f, fraction(records = 0), 0f)
    }

    @Test
    fun aCompletedExtractionSignalsExactlyOneHundredPercent() = runBlocking {
        val fractions = mutableListOf<Float>()
        val failure = harness.rootfsExtractor.extract(
            tempArchive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
                .apply { writeText("fake archive payload") },
            staging = harness.fakeRootfs(),
            workspaceId = "progress-test",
            expectedExtractedBytes = 1_000_000L,
            expectedEntryCount = 5_000L,
            onProgress = { fractions += it }
        )

        assertNull("the fake extraction itself must succeed", failure)
        assertTrue("completion must be reported", fractions.isNotEmpty())
        assertEquals(
            "completion is the only source of 100%",
            1f,
            fractions.last(),
            0f
        )
        assertEquals(
            "no running estimate may report 100%",
            0,
            fractions.dropLast(1).count { it >= 1f }
        )
        assertTrue(
            "reported progress must be monotonic: $fractions",
            fractions.zipWithNext().all { (a, b) -> b >= a }
        )
    }
}
