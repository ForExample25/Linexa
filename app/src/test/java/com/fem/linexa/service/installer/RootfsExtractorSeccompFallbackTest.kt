package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
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

/**
 * §1.50 — Roadmap Item 3: JVM tests for the native-extraction seccomp
 * fallback in [RootfsExtractor.extract].
 *
 * The REAL [RootfsExtractor] runs against the fake PRoot stand-in
 * ([FakeProot]) substituted at `nativeLibraryDir`, so these assert the
 * actual failure-handling code — not a mock of it:
 *
 *  - a genuine §1.50 marker failure on the first extraction attempt retries
 *    exactly ONCE, with a fresh §1.48 tmp scope and `PROOT_NO_SECCOMP=1`,
 *  - a clean extraction runs exactly once with no fallback env,
 *  - an EPERM/`Operation not permitted` exit 70 (the "tar exit 70" bug
 *    class) never retries — no false positives,
 *  - the shared memo makes a later extraction pre-apply the fallback from
 *    its very first attempt,
 *  - a detection during extraction also protects later runtime `exec`
 *    launches, and vice versa — both call sites share
 *    `BootstrapManager.needsNoSeccomp` (no second independent flag).
 */
class RootfsExtractorSeccompFallbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        // §1.50 memoization is process-global in production; reset so one
        // test's detection doesn't leak into the next.
        ProotRuntimeTestHarness.resetSeccompMemo()
        // RF-013: SECCOMP_BUG_ON_3RD counts the two debug-only proot
        // preflights before the extraction attempt; enable capture so that
        // contract remains the one under test.
        DebugCapture.setEnabled(true)
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        // Provide the applib-side bootstrap artifacts the extractor's
        // §1.46/§1.47 validators require (tar, loader, busybox, …).
        // libproot.so is the FakeProot script; the others are inert
        // executables that must never touch the fake's invocation log.
        for (name in listOf(
            "lib__bin__tar__.so",
            "lib__bin__busybox__.so",
            "lib__bin__loader__.so",
            "lib__bin__zstd__.so",
            "lib__bin__tmux__.so"
        )) {
            val inert = File(prootDir, name)
            inert.writeText("#!/bin/sh\n# inert bootstrap stand-in for JVM tests — not a real binary\nexit 0\n")
            check(inert.setExecutable(true)) { "could not chmod +x $inert" }
        }
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
        // binDir/proot → applib/libproot.so and friends; idempotent.
        harness.bootstrapManager.setup()
    }

    @After
    fun resetCapture() {
        // Process-wide flag — must not leak into later tests in this JVM.
        DebugCapture.resetForTesting()
    }

    /** One full extraction of a synthetic archive; returns extract()'s failure or null. */
    private fun extractOnce(workspaceId: String = "extract-test"): String? {
        // Unique name per call — extractOnce may be invoked multiple times in a
        // test (memo pre-apply), and TemporaryFolder.newFolder fails if a folder
        // of the same name already exists.
        val archive = File(tmp.root, "archive-${Math.abs(System.nanoTime())}.tar.xz").apply {
            writeText("fake archive payload — JVM tests never touch the real tar")
        }
        return runBlocking {
            harness.rootfsExtractor.extract(archive, harness.fakeRootfs(), workspaceId)
        }
    }

    /** The fake launches that were actual extraction attempts (not §1.46 preflights). */
    private fun extractionInvocations(): List<FakeProot.Invocation> =
        FakeProot.invocations(prootDir).filter { "--link2symlink" in it.argv }

    @Test
    fun cleanExtractionRunsOnceWithoutFallback() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        assertNull(extractOnce())

        val extractions = extractionInvocations()
        assertEquals("one extraction attempt on a clean kernel", 1, extractions.size)
        assertFalse(
            "no PROOT_NO_SECCOMP unless needed",
            extractions[0].hadNoSeccompEnv
        )
        assertTrue("no retry ever", FakeProot.noSeccompInvocations(prootDir).isEmpty())
    }

    @Test
    fun markerOnFirstExtractionRetriesOnceWithFallbackAndSucceeds() {
        // Invocation #3 = the first extraction ProcessBuilder attempt (the
        // §1.46/§1.52 forensics launch the fake twice before it).
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ON_3RD)

        assertNull(extractOnce())

        val extractions = extractionInvocations()
        assertEquals("retry must happen exactly once", 2, extractions.size)
        assertFalse("first attempt runs without the fallback", extractions[0].hadNoSeccompEnv)
        assertTrue("retry attempt runs with PROOT_NO_SECCOMP=1", extractions[1].hadNoSeccompEnv)
    }

    @Test
    fun markerAlwaysRetriesOnceThenSurfacesTheFailure() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ALWAYS)

        val failure = extractOnce()

        assertNotNull("the final failed attempt must still surface as a failure", failure)
        assertTrue("failure message must be the real proot failure, got: $failure", failure!!.contains("Native extraction failed"))
        val extractions = extractionInvocations()
        assertEquals("retry must happen exactly once", 2, extractions.size)
        assertTrue("retry attempt runs with PROOT_NO_SECCOMP=1", extractions[1].hadNoSeccompEnv)
    }

    @Test
    fun epermExit70DoesNotRetry() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.EPERM_NONZERO_ALWAYS)

        val failure = extractOnce()

        // §1.50: an EPERM "Operation not permitted" exit 70 is NOT the
        // seccomp marker, so the retry must not fire — retrying with
        // PROOT_NO_SECCOMP on an unrelated failure would mask a real bug.
        assertNotNull(failure)
        assertTrue("the real proot exit code must surface, got: $failure", failure!!.contains("proot exit 70"))
        assertEquals("no retry on EPERM", 1, extractionInvocations().size)
        assertTrue(FakeProot.noSeccompInvocations(prootDir).isEmpty())
    }

    @Test
    fun genericNonZeroExitDoesNotRetry() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.GENERIC_FAILURE_ALWAYS)

        val failure = extractOnce()

        assertNotNull(failure)
        assertTrue(failure!!.contains("Native extraction failed"))
        assertEquals("no retry on a generic failure", 1, extractionInvocations().size)
        assertTrue(FakeProot.noSeccompInvocations(prootDir).isEmpty())
    }

    @Test
    fun memoizedFallbackPreAppliesToLaterExtractions() {
        // First extraction triggers the one retry + memoizes needsNoSeccomp=true.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ON_3RD)
        assertNull(extractOnce("first"))
        assertEquals(2, extractionInvocations().size)

        // Now the underlying "kernel bug" is gone (fake cleaned up): the
        // memoized flag must pre-apply PROOT_NO_SECCOMP=1 to the very first
        // attempt of a later extraction instead of retrying per extraction.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val extractionsBefore = extractionInvocations().size

        assertNull(extractOnce("second"))

        val extractions = extractionInvocations()
        assertEquals("later extraction must not re-retry", extractionsBefore + 1, extractions.size)
        assertTrue(
            "later extraction must pre-apply the flag from the memo",
            extractions.last().hadNoSeccompEnv
        )
    }

    @Test
    fun extractionDetectionEnablesFallbackForLaterRuntimeExec() {
        // Extraction detects the kernel bug and memoizes needsNoSeccomp=true.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ON_3RD)
        assertNull(extractOnce("ws-extract"))

        // The shared flag (Item 3) must pre-apply the fallback to the next
        // runtime exec launch with no probe and no re-retry.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val countBefore = FakeProot.invocationCount(prootDir)

        val result = runBlocking {
            harness.runtime.exec("ws-exec", harness.fakeRootfs().absolutePath, "true")
        }

        assertEquals(0, result.exitCode)
        assertEquals("runtime exec must not re-probe/re-retry", 1, FakeProot.invocationCount(prootDir) - countBefore)
        assertTrue(
            "runtime exec must inherit the flag detected during extraction",
            FakeProot.invocations(prootDir).last().hadNoSeccompEnv
        )
    }

    @Test
    fun extractionKeepsHostEnvironmentForItsBionicTarTracee() {
        // F-8 (2026-08-25 audit): guest launches set PROOT_GUEST_ENV_FILTER so
        // the vendored proot scrubs host-only variables from the tracee's
        // environment. The EXTRACTION path must NOT: its tracee is the
        // bundled bionic `tar`, which needs the host LD_LIBRARY_PATH to
        // resolve its own shared libraries — scrubbing it would break every
        // rootfs install. This pins that exemption behaviorally.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        assertNull(extractOnce("ws-f8-exempt"))

        val invocations = extractionInvocations()
        assertTrue("an extraction attempt must have run", invocations.isNotEmpty())
        assertTrue(
            "the extraction env must keep the host LD_LIBRARY_PATH (bionic tar linkage)",
            invocations.all { it.env["LD_LIBRARY_PATH"]?.contains("bootstrap") == true }
        )
        assertTrue(
            "the extraction env must NOT set the guest-env filter",
            invocations.none { it.env.containsKey("PROOT_GUEST_ENV_FILTER") }
        )
    }
}
