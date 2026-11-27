package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.ThreadRecordingLogSink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RF-013 — the extraction's full argv/environment dumps are behind
 * [DebugCapture], exactly like the runtime's launch forensics.
 *
 * Behavioural, not structural: the recording sink observes whether the
 * `Logger.i` calls happened at all, which distinguishes "the dump was skipped"
 * from "the dump ran and its output went nowhere". One-line operational facts
 * (paths, sizes, strip count) must survive in both states so a support report
 * still says what ran.
 */
class RootfsExtractorLoggingGatingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    private val fullDumpMarkers = listOf(
        "=== FORENSIC: full argv (§1.52) ===",
        "=== FORENSIC: complete host env passed to proot (§1.52, sorted) ===",
        "=== FORENSIC: pipeline path verification (§1.52) ==="
    )

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
        ThreadRecordingLogSink.install()
    }

    @After
    fun tearDown() {
        ThreadRecordingLogSink.reset()
        DebugCapture.resetForTesting()
        ProotRuntimeTestHarness.resetSeccompMemo()
    }

    private fun extract(id: String): String? = runBlocking {
        harness.rootfsExtractor.extract(
            tempArchive = File(tmp.root, "archive-${System.nanoTime()}.tar.xz")
                .apply { writeText("fake archive payload") },
            staging = harness.fakeRootfs(),
            workspaceId = id
        )
    }

    private fun messages(): List<String> = ThreadRecordingLogSink.all().map { it.message }

    @Test
    fun whileCaptureIsOffNoFullArgvOrEnvironmentDumpIsProduced() {
        DebugCapture.setEnabled(false)
        val failure = extract("logging-off")
        assertTrue("the fake extraction itself must succeed: $failure", failure == null)

        val messages = messages()
        fullDumpMarkers.forEach { marker ->
            assertFalse(
                "no full dump may be produced with debug info off: $marker",
                messages.any { it.contains(marker) }
            )
        }
        assertFalse(
            "individual argv lines must not be built",
            messages.any { it.contains("argv[0] = ") }
        )
        assertFalse(
            "environment values must not be logged",
            messages.any { it.contains("LD_LIBRARY_PATH = ") }
        )
        // The concise operational line — and env keys without values — must
        // remain, or a support report loses what actually ran.
        assertTrue(
            "the one-line extraction summary must survive the gate",
            messages.any { it.contains("PRoot extraction:") }
        )
        assertTrue(
            "env keys (never values) must remain available",
            messages.any { it.contains("env keys=") }
        )
    }

    @Test
    fun enablingCaptureRestoresTheFullForensics() {
        DebugCapture.setEnabled(true)
        val failure = extract("logging-on")
        assertTrue("the fake extraction itself must succeed: $failure", failure == null)

        val messages = messages()
        fullDumpMarkers.forEach { marker ->
            assertTrue(
                "the full dump must be produced when debug info is on: $marker",
                messages.any { it.contains(marker) }
            )
        }
        assertTrue(
            "the full environment values must be available for debugging",
            messages.any { it.contains("LD_LIBRARY_PATH = ") }
        )
    }
}
