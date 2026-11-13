package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
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
 * §1.70 — Roadmap Item 1: JVM tests for [PRootContainerRuntime]'s
 * `resolveSeccompFallback` (the §1.50 one-time seccomp probe) exercised
 * end-to-end through the REAL `openTerminal` launch path against the fake
 * PRoot stand-in.
 *
 * The probe is a private method, but its only externally observable
 * contract is the [PRootLauncher.LaunchSpec] handed to the pty factory:
 *  - clean probe → the global [BootstrapManager.needsNoSeccomp] stays false,
 *  - marker probe → spec gains PROOT_NO_SECCOMP=1,
 *  - memoization → exactly ONE probe per process, and a later clean
 *    session still gets the fallback pre-applied.
 *
 * §1.193 note: the harness's minimal rootfs has no declared guest account,
 * so these sessions resolve to the root identity and therefore carry
 * PROOT_NO_SECCOMP=1 from launch construction. That is why the clean-probe
 * assertion is on the global memo, not on the spec's env.
 *
 * Invocation accounting per openTerminal (both verified by the fake's own
 * log): 1 seccomp probe + 1 guest-fs probe = 2 fake launches; the session
 * launch itself goes through the (recording) pty factory and is not a fake
 * launch. A memoized second openTerminal skips the probe → exactly 1 new
 * launch.
 *
 * **UI/UX Phase 7:** the guest-fs probe half of that accounting is a *diagnostic*
 * and now only runs while "Enable debug info" is on, so [setUp] enables
 * [DebugCapture]. The seccomp probe is deliberately NOT gated — it decides whether
 * `PROOT_NO_SECCOMP=1` is applied, so it is part of launching correctly rather than
 * part of reporting, and [theSeccompProbeStillRunsWithCaptureOff] pins that
 * distinction.
 */
class PRootOpenSessionSeccompProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        // UI/UX Phase 7: keeps this test's launch accounting at the documented
        // 2-per-open (seccomp probe + guest-fs probe).
        DebugCapture.setEnabled(true)
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
    fun resetCapture() {
        DebugCapture.resetForTesting()
    }

    private fun openSession(workspaceId: String = "ws-1", asRoot: Boolean = false): Unit {
        runBlocking {
            harness.runtime.openTerminal(
                workspaceId,
                harness.fakeRootfs().absolutePath,
                DEFAULT_TERMINAL_ID
            , asRoot = asRoot)
        }
    }

    @Test
    fun cleanProbeDoesNotEnableTheGlobalFallback() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        openSession()

        val call = harness.createCalls.single()
        assertEquals("session must launch the fake proot", File(prootDir, FakeProot.BINARY_NAME).absolutePath, call.executable)
        assertFalse("clean probe must not enable the global fallback", BootstrapManager.needsNoSeccomp)
    }

    @Test
    fun seccompMarkerProbeEnablesFallbackOnSession() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ALWAYS)

        openSession()

        val call = harness.createCalls.single()
        assertTrue("marker probe must pre-apply PROOT_NO_SECCOMP=1", call.envHadNoSeccomp)
    }

    @Test
    fun markerOnceProbeAlsoEnablesFallback() {
        // The probe is the first fake invocation — even a "transient" marker
        // bug must be caught by the probe, exactly as the retry path catches
        // it in exec.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ONCE)

        openSession()

        assertTrue(harness.createCalls.single().envHadNoSeccomp)
    }

    @Test
    fun probeRunsExactlyOnceWithShProbeCommand() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        openSession()

        // 2 fake launches: seccomp probe + guest-fs probe. The session
        // itself goes through the recording pty factory (tmux not bundled)
        // and is not a fake launch.
        assertEquals(2, FakeProot.invocationCount(prootDir))
        val first = FakeProot.invocations(prootDir).first()
        assertEquals(
            "first launch must be the §1.50 canary (`/bin/sh -c exit 0`)",
            listOf("/bin/sh", "-c", "exit 0"),
            first.argv.takeLast(3)
        )
    }

    @Test
    fun memoizedFallbackSkipsProbeAndPreAppliesToLaterSessions() {
        // First session: marker detected by the probe → fallback + memoized.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ALWAYS)
        openSession("ws-1")
        assertEquals(2, FakeProot.invocationCount(prootDir))
        assertTrue(harness.createCalls[0].envHadNoSeccomp)

        // Second session (same process, same runtime): the fake is now clean,
        // but the memoized flag must pre-apply the fallback WITHOUT a second
        // probe — exactly 1 new launch (the guest-fs probe), not 2.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)
        val countBefore = FakeProot.invocationCount(prootDir)

        openSession("ws-2")

        assertEquals("probe must not run again", 1, FakeProot.invocationCount(prootDir) - countBefore)
        assertTrue("later session must still get the fallback", harness.createCalls[1].envHadNoSeccomp)
    }

    @Test
    fun epermOutputProbeDoesNotEnableFallback() {
        // An EPERM/exit-70 launch must NOT be mistaken for the seccomp bug —
        // the same no-false-positive discipline the exec retry path uses.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.EPERM_NONZERO_ALWAYS)

        openSession()

        assertFalse(
            "an unrelated failure must not flip the global fallback",
            BootstrapManager.needsNoSeccomp
        )
    }

    @Test
    fun theSeccompProbeStillRunsWithCaptureOff() {
        // UI/UX Phase 7 boundary: the seccomp probe is not a diagnostic. Its result
        // decides whether PROOT_NO_SECCOMP=1 is applied to the real launch, so
        // gating it on the debug toggle would make workspaces fail to start on
        // affected kernels for every user who has debug off — a correctness
        // mechanism, not a report. Only the guest-fs *forensics* probe is gated.
        DebugCapture.setEnabled(false)
        FakeProot.setScenario(prootDir, FakeProot.Scenario.SECCOMP_BUG_ALWAYS)

        openSession()

        assertEquals(
            "exactly the seccomp probe — the guest-fs forensics probe must be skipped",
            1,
            FakeProot.invocationCount(prootDir)
        )
        assertTrue(
            "the fallback must still be detected and applied with debug info off",
            harness.createCalls.single().envHadNoSeccomp
        )
    }

    // ── §1.193 (2026-11-13 cross-distro audit) ──────────────────────────

    @Test
    fun rootSessionAlwaysCarriesTheWorkaroundEvenOnACleanProbe() {
        // A `-0` session is the elevation surface (Root Shell / as_root
        // shortcuts). The §1.193 workaround is applied by launch
        // construction, so it must be present without any probe result.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        openSession(asRoot = true)

        assertTrue(
            "fake-root sessions must disable PRoot seccomp acceleration",
            harness.createCalls.single().envHadNoSeccomp
        )
    }

    @Test
    fun probeItselfNeverRunsWithTheRootWorkaround() {
        // The probe's job is to test the kernel. If the §1.193 root
        // workaround leaked into it, a root launch that happened first would
        // mask the §1.50 kernel bug for every later normal-user session.
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        openSession(asRoot = true)

        val probe = FakeProot.invocations(prootDir).first()
        assertFalse(
            "the seccomp probe must run without PROOT_NO_SECCOMP",
            probe.hadNoSeccompEnv
        )
    }

    @Test
    fun rootSessionDoesNotMemoizeTheWorkaroundGlobally() {
        FakeProot.setScenario(prootDir, FakeProot.Scenario.CLEAN_ALWAYS)

        openSession("ws-root", asRoot = true)
        assertTrue(harness.createCalls[0].envHadNoSeccomp)

        // §1.193 is per-launch policy derived from the identity. It must not
        // flip the global §1.50 memo, which would silently disable PRoot's
        // acceleration for every normal-user session too.
        assertFalse(
            "the root workaround must not become a global fallback",
            BootstrapManager.needsNoSeccomp
        )
    }
}