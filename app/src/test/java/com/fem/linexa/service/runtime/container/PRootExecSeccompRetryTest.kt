package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.70 — Roadmap Item 1: JVM tests for the one-shot exec path's
 * seccomp-fallback retry logic in [PRootContainerRuntime.exec].
 *
 * The REAL [PRootContainerRuntime] runs against the fake PRoot stand-in
 * ([FakeProot]) substituted at `nativeLibraryDir`, so these assert the
 * actual failure-handling code — not a mock of it:
 *
 *  - a genuine §1.50 marker failure retries exactly ONCE with
 *    `PROOT_NO_SECCOMP=1`,
 *  - a clean exit never retries,
 *  - an EPERM/`Operation not permitted` exit 70 (the "tar exit 70" bug
 *    class from Roadmap Item 3) never retries — no false positives,
 *  - the memoized `needsNoSeccomp` flag survives across calls and
 *    pre-applies the fallback to later launches.
 *
 * The roadmap scoped this to test infra + tests only: no production code
 * was changed to make these pass; the harness substitutes the binary path
 * via a test-only mock Application.
 */
class PRootExecSeccompRetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        // §1.50 memoization is process-global in production; reset so one
        // test's detection doesn't leak into the next.
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    private fun execOnce(workspaceId: String = "exec-test"): CommandResult =
        runBlocking { harness.runtime.exec(workspaceId, harness.fakeRootfs().absolutePath, "true") }

    @Test
    fun rootExecPreAppliesTheSeccompWorkaroundAndRunsOnce() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        val result = execOnce()

        assertEquals(0, result.exitCode)
        assertEquals("", result.error)
        assertEquals(1, FakeProot.invocationCount(prootDir))
        // §1.193: one-shot execs are always fake-root, so the workaround is
        // pre-applied by launch construction rather than discovered by a
        // failure. This is the policy the cross-distro audit pinned.
        assertTrue(
            "root one-shot execs always disable PRoot's seccomp acceleration",
            FakeProot.invocations(prootDir).single().hadNoSeccompEnv
        )
    }

    @Test
    fun markerAlwaysRetriesOnceThenSurfacesTheFailure() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ALWAYS)

        val result = execOnce()

        assertTrue("fake exit 1 must surface as a real failure", result.exitCode != 0)
        assertEquals(result.output, result.error)
        assertEquals("retry must happen exactly once", 2, FakeProot.invocationCount(prootDir))
        val invocations = FakeProot.invocations(prootDir)
        assertTrue("second call must carry PROOT_NO_SECCOMP=1", invocations[1].hadNoSeccompEnv)
        assertTrue(
            "the §1.193 root workaround is already on the first call — the retry " +
                "is defence-in-depth for a PRoot that ignores the variable",
            invocations[0].hadNoSeccompEnv
        )
    }

    @Test
    fun markerOnceRetriesOnceWithFallbackAndSucceeds() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ONCE)

        val result = execOnce()

        assertEquals(0, result.exitCode)
        assertEquals("", result.error)
        assertEquals(2, FakeProot.invocationCount(prootDir))
        val invocations = FakeProot.invocations(prootDir)
        assertTrue("the root launch already carries the workaround", invocations[0].hadNoSeccompEnv)
        assertTrue("retry call runs with PROOT_NO_SECCOMP=1", invocations[1].hadNoSeccompEnv)
    }

    @Test
    fun epermExit70DoesNotRetry() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.EPERM_NONZERO_ALWAYS)

        val result = execOnce()

        // Roadmap Item 3 / §1.50: an EPERM "Operation not permitted" exit 70
        // is NOT the seccomp marker, so the retry must not fire — retrying
        // on an unrelated failure would mask a real bug.
        assertEquals(70, result.exitCode)
        assertEquals(result.output, result.error)
        assertEquals("no retry", 1, FakeProot.invocationCount(prootDir))
    }

    @Test
    fun genericNonZeroExitDoesNotRetry() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.GENERIC_FAILURE_ALWAYS)

        val result = execOnce()

        assertEquals(1, result.exitCode)
        assertEquals("no retry", 1, FakeProot.invocationCount(prootDir))
    }

    @Test
    fun memoizedFallbackPreAppliesToLaterExecs() {
        // First exec triggers the one retry + memoizes needsNoSeccomp=true.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ONCE)
        assertEquals(0, execOnce("first").exitCode)

        // Now the underlying "kernel bug" is gone (fake cleaned up): the
        // memoized flag must pre-apply PROOT_NO_SECCOMP=1 to the very first
        // call of a later exec instead of probing/retrying per call.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val countBefore = FakeProot.invocationCount(prootDir)

        val result = execOnce("second")

        assertEquals(0, result.exitCode)
        assertEquals("later exec must not re-retry", 1, FakeProot.invocationCount(prootDir) - countBefore)
        assertTrue(
            "later exec must pre-apply the flag from the memo",
            FakeProot.invocations(prootDir).last().hadNoSeccompEnv
        )
    }
}