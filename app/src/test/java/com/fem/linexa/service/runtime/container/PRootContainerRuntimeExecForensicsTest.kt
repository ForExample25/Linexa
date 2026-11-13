package com.fem.linexa.service.runtime.container

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.99 — regression test for the "new terminal freezes / typing not received"
 * input lag. Root cause: [PRootContainerRuntime.openTerminal] ran
 * `logExecForensics` — which contains `probeGuestFilesystem`, a FULL second
 * blocking PRoot boot — on EVERY terminal open, on Dispatchers.Main and while
 * holding the global sessionsLock. terminal-lib's TerminalSession does all
 * output emulation on the main thread, so a wedged main thread starved the
 * live terminal's output and the new session's input echo.
 *
 * The fix gates the forensic dump (and its blocking guest proot probe) to the
 * FIRST open of each workspace per process. These tests pin that boundary by
 * counting how many times the fake PRoot binary is invoked with the guest
 * probe command (`/bin/sh -c <probe script>`), which is exactly the blocking
 * second process the fix removes from the per-terminal hot path.
 *
 * Covered:
 *  - the FIRST open of a workspace runs the guest probe once (cold-start
 *    diagnostics preserved);
 *  - a SECOND terminal of the SAME workspace does NOT run the probe again;
 *  - a DIFFERENT workspace still gets its own first-open probe;
 *  - closing the workspace resets the latch so a fresh session re-probes once.
 *
 * **UI/UX Phase 7** added a second, outer condition: the dump is a *diagnostic*,
 * so it only runs while the user has "Enable debug info" on. Every §1.99 test
 * below therefore enables [DebugCapture] explicitly — the §1.99 boundary is about
 * how often the probe runs *when it is wanted*, and asserting it against the
 * default (off) would silently pass by never probing at all.
 * [forensicsAreSkippedEntirelyWhileCaptureIsOff] is the Phase 7 half.
 */
class PRootContainerRuntimeExecForensicsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        // UI/UX Phase 7: capture is fail-closed by default, and these tests are
        // about the diagnostic that capture permits.
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
        // Process-wide flag — leaking it would enable capture for every later test.
        DebugCapture.resetForTesting()
    }

    /**
     * Count fake-PRoot invocations that are the guest-filesystem probe: the
     * probe replaces the launch command with `/bin/sh -c <probe script>` whose
     * body contains the distinctive "guest MISSING"/"guest OK" loop marker.
     * A real session launch runs the shell directly (`-w /root … bash`), never
     * this probe script.
     *
     * The marker is matched against the raw invocation block, not the
     * `argv[`-parsed form: the whole probe script is ONE argv element with
     * embedded newlines, so its marker lines fall on continuation lines that
     * [com.fem.linexa.test.support.FakeProot.invocations] drops.
     */
    private fun guestProbeInvocations(): Int =
        FakeProot.rawInvocations(prootDir).count { block ->
            block.contains("guest MISSING") || block.contains("guest OK")
        }

    private suspend fun open(workspaceId: String, terminalId: String) {
        harness.runtime.openTerminal(workspaceId, harness.fakeRootfs().absolutePath, terminalId, asRoot = false)
    }

    @Test
    fun firstOpenRunsGuestProbeOnce() = runBlocking {
        open("ws-1", "T0")

        assertEquals(
            "the first open of a workspace must run the guest probe exactly once (cold-start diagnostics)",
            1,
            guestProbeInvocations()
        )
    }

    @Test
    fun secondTerminalOfSameWorkspaceSkipsGuestProbe() = runBlocking {
        open("ws-1", "T0")
        assertEquals(1, guestProbeInvocations())

        // The regression: opening T1 used to run the blocking probe again.
        open("ws-1", "T1")
        assertEquals(
            "a second terminal of the same workspace must NOT re-run the blocking guest probe",
            1,
            guestProbeInvocations()
        )
        // Both sessions were still created (the probe is diagnostic-only; the
        // real launches are unaffected by the gate).
        assertEquals(2, harness.createCalls.size)
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T0"))
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T1"))
    }

    @Test
    fun differentWorkspaceGetsItsOwnFirstOpenProbe() = runBlocking {
        open("ws-1", "T0")
        open("ws-2", "T0")

        assertEquals(
            "each workspace's first open must run its own cold-start probe",
            2,
            guestProbeInvocations()
        )
    }

    @Test
    fun closingWorkspaceResetsForensicsLatch() = runBlocking {
        open("ws-1", "T0")
        open("ws-1", "T1")
        assertEquals(1, guestProbeInvocations())

        // After the workspace closes, a fresh session's first open re-captures
        // the cold-start dump once.
        harness.runtime.closeWorkspaceSessions("ws-1")
        open("ws-1", "T0")

        assertEquals(
            "a fresh workspace session must re-run its first-open probe once",
            2,
            guestProbeInvocations()
        )
    }

    // ── UI/UX Phase 7 ─────────────────────────────────────────────────────

    @Test
    fun forensicsAreSkippedEntirelyWhileCaptureIsOff() = runBlocking {
        // §1.99 bounded this to once per workspace per process. That first run is
        // still a full second blocking PRoot boot on the main thread under
        // sessionsLock — paid for a report a user with debug info off cannot read.
        DebugCapture.setEnabled(false)

        open("ws-1", "T0")

        assertEquals(
            "no forensic guest probe may run while debug info is off",
            0,
            guestProbeInvocations()
        )
        // The launch itself is unaffected — the dump is diagnostic-only.
        assertTrue(harness.runtime.isTerminalAlive("ws-1", "T0"))
    }

    @Test
    fun enablingCaptureAfterALaunchStillYieldsTheForensicsOnTheNextOpen() = runBlocking {
        // The once-only latch must be spent only when the dump actually happened.
        // Consuming it during a capture-off launch would mean a user who turns debug
        // on after hitting a problem never gets the cold-start forensics for that
        // workspace — the exact evidence they enabled it to collect.
        DebugCapture.setEnabled(false)
        open("ws-1", "T0")
        assertEquals(0, guestProbeInvocations())

        DebugCapture.setEnabled(true)
        open("ws-1", "T1")

        assertEquals(
            "the latch must not have been consumed by the capture-off launch",
            1,
            guestProbeInvocations()
        )
    }
}
