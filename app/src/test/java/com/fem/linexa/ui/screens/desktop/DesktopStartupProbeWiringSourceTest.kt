package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §16.3.1 structural guard for the **combined cold-start discovery probe** in
 * `DesktopViewModel.startDesktop`.
 *
 * ## Why a source test
 *
 * [DesktopStartupProbeTest] covers the probe's command and parsing exhaustively,
 * because those are pure. What it cannot cover is the part §16.3.1 is actually
 * risky in: the **order of operations inside `startDesktop`**. That function is an
 * `AndroidViewModel` coroutine needing a real `Application`, a bound
 * `RuntimeBinder` and a live guest session, and this module has no Robolectric or
 * Compose harness (§1.124(a)) — so the alternative to a source assertion is no
 * coverage of the one invariant that makes this optimization safe.
 *
 * Same idiom and rationale as [DesktopGeometryTraceWiringSourceTest] (§1.166) and
 * `DebugInfoWiringSourceTest` (§1.137).
 *
 * ## The invariant
 *
 * `pkill Xvnc` must remain **after** the null-session return. The audit's original
 * §16.3 proposal merged it into the probe; the follow-up review rejected that as
 * not semantically neutral, because it would kill a running X server in exactly
 * the case the current code refuses to touch anything — a workspace with no
 * desktop session installed. The gap between "discovery got cheaper" and "cleanup
 * moved earlier" is one reordered statement and produces no compile error, no test
 * failure anywhere else, and no log line: the user simply loses an X server they
 * started by other means. That is what this file pins.
 */
class DesktopStartupProbeWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/desktop/$path").readText()

    private val viewModel: String get() = source("DesktopViewModel.kt")
    private val probe: String get() = source("DesktopStartupProbe.kt")
    private val provisioner: String get() = source("DesktopSessionProvisioner.kt")

    /** `startDesktop`'s body, where the ordering lives. */
    private val startDesktopBody: String
        get() = viewModel
            .substringAfter("fun startDesktop(workspaceId: String)")
            .substringBefore("private fun sessionPathFrom(")

    /**
     * The **stale-server cleanup statement**, located by its daemon key rather
     * than by a bare word.
     *
     * Transformation spec §13.5: `pkill Xvnc` (host-global — PRoot does
     * not isolate pids, so starting workspace B's desktop killed workspace A's
     * server) was replaced by a per-workspace `stopDaemon(workspaceId,
     * DAEMON_XVNC)`. The ordering invariants the §16.3.1 review pinned still
     * hold, keyed on this statement: the cleanup sits after the null-session
     * return, precedes the Xvnc daemon start, and stays best-effort
     * (`runCatching`).
     */
    private val cleanupStatement = "stopDaemon(workspaceId, DAEMON_XVNC)"

    /**
     * Source with comments removed, for the ordering and absence assertions.
     *
     * Both files under test discuss `pkill` in prose — the whole point of the
     * §16.3.1 review was to record *why* it is not merged — so an assertion that
     * matched raw source would be failed by its own justification. Line comments
     * (`//`) and KDoc/block bodies (lines whose first non-space character is `*`,
     * plus the `/**` and `*/` delimiters) are both dropped, because the
     * explanations live in KDoc and the inline notes live in `//`.
     *
     * The alternative — matching ever-longer literals to dodge the prose — gets
     * fragile in the opposite direction: it starts asserting comment wording.
     */
    private fun codeOnly(source: String): String = source
        .lines()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/**") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val startDesktopCode: String get() = codeOnly(startDesktopBody)

    // ── the merge happened ────────────────────────────────────────────────

    @Test
    fun theStartPathUsesTheCombinedProbe() {
        assertTrue(
            "startDesktop must issue the combined discovery exec: $startDesktopBody",
            startDesktopBody.contains("DesktopStartupProbe.command(")
        )
        assertTrue(
            "and must decode it",
            startDesktopBody.contains("DesktopStartupProbe.parse(")
        )
    }

    @Test
    fun theSeparateXvncLookupIsGone() {
        // The whole saving. A surviving standalone `which Xvnc` in the start path
        // would mean the merge added an exec instead of removing one.
        assertFalse(
            "no standalone `which Xvnc` may remain in startDesktop: $startDesktopBody",
            startDesktopCode.contains("\"which Xvnc\"")
        )
    }

    @Test
    fun theHappyPathIssuesExactlyOneDiscoveryExec() {
        // Count the execs between entering the try block and the pkill: on the
        // path where Xvnc is present there must be exactly one — the combined
        // probe. The post-install re-probe sits inside the `!xvncFound` branch and
        // is deliberately excluded by cutting at that branch.
        val happyPath = startDesktopCode.substringBefore("if (!discovery.xvncFound)")
        assertEquals(
            "expected exactly one guest exec before the Xvnc-missing branch: $happyPath",
            1,
            Regex("runtimeBinder\\.execCommand\\(").findAll(happyPath).count()
        )
    }

    @Test
    fun theReResolveAfterInstallIsStillASeparateProbe() {
        // §1.85's contract: after installing Xvnc the session is re-probed, because
        // the install may have pulled in an X client. Folding this into the first
        // (pre-install) reading would make a session that appeared during the
        // install invisible.
        val installBranch = startDesktopCode
            .substringAfter("if (!discovery.xvncFound)")
            .substringBefore("val sessionPath =")
        assertTrue(
            "the install branch must re-probe the session: $installBranch",
            installBranch.contains("DesktopSessionProvisioner.detectSessionCommand(")
        )
        assertTrue(
            "and must still run the auto-install",
            installBranch.contains("autoInstallXvnc(")
        )
    }

    // ── pkill stayed out, and stayed conditional ───────────────────────────

    @Test
    fun pkillIsNotPartOfTheCombinedProbe() {
        // Comments in the probe explain why cleanup is excluded, so the
        // assertion has to look at code only — otherwise the explanation would
        // fail the test it exists to justify.
        assertFalse(
            "the probe command must never kill anything: $probe",
            codeOnly(probe).contains("pkill")
        )
        assertFalse(
            "the probe command must never stop daemons either: $probe",
            codeOnly(probe).contains("stopDaemon")
        )
    }

    @Test
    fun cleanupStillRunsAfterTheNullSessionReturn() {
        // THE invariant (§13.5 form). The stale-server cleanup must sit after
        // the `sessionPath == null` early return, so a workspace with no
        // desktop session never has its X server torn down by a start attempt
        // that then refuses to start anything.
        val body = startDesktopCode
        val nullCheckIndex = body.indexOf("if (sessionPath == null)")
        val noSessionStateIndex = body.indexOf("DesktopUiState.NoSession")
        val cleanupIndex = body.indexOf(cleanupStatement)
        assertTrue("the null-session check must exist", nullCheckIndex > 0)
        assertTrue("the NoSession state must be set inside it", noSessionStateIndex > nullCheckIndex)
        assertTrue("the cleanup call site must exist", cleanupIndex > 0)
        assertTrue(
            "cleanup must come AFTER the null-session return, not before it",
            cleanupIndex > noSessionStateIndex
        )
    }

    @Test
    fun theNullSessionBranchReturnsBeforeReachingPkill() {
        // The ordering above is only meaningful if the branch actually exits. A
        // missing `return` would fall through to pkill despite the correct
        // statement order.
        //
        // Desktop Modes (2026-08): the launch body was extracted into the
        // `startVncDesktop` suspend function (so the run-mode dispatcher can
        // choose between VNC and WebView), so the branch's early exit changed
        // from `return@launch` to a plain `return`. Same semantics — the
        // branch must still exit — different spelling.
        val nullBranch = startDesktopCode
            .substringAfter("if (sessionPath == null)")
            .substringBefore(cleanupStatement)
        assertTrue(
            "the no-session branch must return: $nullBranch",
            nullBranch.contains("return")
        )
    }

    @Test
    fun theSessionIsResolvedBeforeCleanup() {
        // Restates the §1.114 order from the other side: discovery and selection
        // both complete before the one mutation in the sequence. Both start
        // paths (VNC and the X11 backend) resolve through sessionPathFrom and
        // may reassign for a preset fallback — hence the var and the helper call.
        val body = startDesktopCode
        val sessionResolveIndex = body.indexOf("sessionPath = sessionPathFrom(")
        val cleanupIndex = body.indexOf(cleanupStatement)
        assertTrue("session resolution must exist", sessionResolveIndex > 0)
        assertTrue("the cleanup call site must exist", cleanupIndex > 0)
        assertTrue("and resolution must precede cleanup", sessionResolveIndex < cleanupIndex)
    }

    @Test
    fun cleanupIsPerWorkspaceStopDaemonNeverHostGlobalPkill() {
        // Transformation spec §13.5: the host-global `pkill Xvnc` (which killed
        // EVERY workspace's server, because PRoot does not isolate pids) must
        // not exist anywhere in the desktop start path.
        val code = codeOnly(viewModel)
        assertFalse(
            "the host-global pkill must be gone from DesktopViewModel: ",
            code.contains("\"pkill Xvnc || true\"")
        )
        assertTrue(
            "cleanup must target THIS workspace's daemon key",
            code.contains("runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }")
        )
    }

    @Test
    fun cleanupStillPrecedesTheXvncDaemonStart() {
        // The cleanup's own purpose: clear a stale server before binding the port.
        val body = startDesktopCode
        val cleanupIndex = body.indexOf(cleanupStatement)
        val startDaemonIndex = body.indexOf("DAEMON_XVNC,")
        assertTrue(cleanupIndex in 1 until startDaemonIndex)
    }

    @Test
    fun cleanupKeptItsFailureTolerance() {
        // `runCatching` is why a workspace with no server running does not fail
        // the start; dropping it would turn a no-op cleanup into an error path
        // (the same job pkill's `|| true` used to do).
        assertTrue(
            "cleanup must stay best-effort: $startDesktopBody",
            startDesktopCode.contains("runCatching { runtimeBinder.stopDaemon(workspaceId, DAEMON_XVNC) }")
        )
    }

    // ── one source of truth for the candidates ────────────────────────────

    @Test
    fun theCandidateListLivesOnlyInTheProvisioner() {
        // §16.3.1's constraint. The probe must build its session half FROM
        // detectSessionCommand rather than restating candidates.
        assertTrue(
            "the provisioner still owns the list",
            provisioner.contains("val SESSION_CANDIDATES")
        )
        assertTrue(
            "the probe must delegate to it: $probe",
            probe.contains("DesktopSessionProvisioner.detectSessionCommand(")
        )
        listOf("x-session-manager", "startxfce4", "openbox", "xterm").forEach { candidate ->
            assertFalse(
                "candidate '$candidate' must not be restated in the probe",
                probe.contains("\"$candidate\"")
            )
        }
    }

    @Test
    fun theDeclaredSessionStillFlowsThroughTheValidatingBuilder() {
        // §1.118 + §1.14: a declared binary reaches the guest only via
        // detectSessionCommand, which re-checks it. The probe must not interpolate
        // it directly.
        assertTrue(
            "the probe must pass the declaration through: $probe",
            probe.contains("detectSessionCommand(declaredSession)")
        )
        assertTrue(
            "and startDesktop must supply the declared value: $startDesktopBody",
            startDesktopBody.contains("DesktopStartupProbe.command(declared?.session)")
        )
    }

    // ── nothing else moved ────────────────────────────────────────────────

    @Test
    fun theSelectionRulesStillLiveInOnePlace() {
        // parseSessionPath owns §1.114's ordering and §1.118's preference; the
        // ViewModel's helper is the only caller in the start path, so the combined
        // probe cannot select differently from a standalone one.
        assertTrue(
            "the ViewModel must keep a single selection helper: $viewModel",
            viewModel.contains("private fun sessionPathFrom(")
        )
        assertTrue(
            "which delegates to the provisioner",
            viewModel.contains("DesktopSessionProvisioner.parseSessionPath(")
        )
        assertEquals(
            "parseSessionPath must be called from exactly two places in the ViewModel: " +
                "the shared selection helper and the preset installer's own post-install " +
                "re-probe (which resolves the JUST-INSTALLED preset's session with its own " +
                "preferred-candidate derivation, outside the start path)",
            2,
            Regex("DesktopSessionProvisioner\\.parseSessionPath\\(").findAll(viewModel).count()
        )
    }

    @Test
    fun theStateMachineWasNotTouched() {
        // §16.3.1 is a probe-count change. The states the start path can reach,
        // and the resume path, must be exactly as before.
        listOf(
            "DesktopUiState.Starting",
            "DesktopUiState.NoSession",
            "DesktopUiState.InstallingDependencies",
            "DesktopUiState.Running(workspaceId)",
            "DesktopUiState.RunningExternal(workspaceId"
        ).forEach { state ->
            assertTrue("$state must still be reachable: $viewModel", viewModel.contains(state))
        }
        assertTrue(
            "resumeIfAlreadyRunning must be unchanged in shape",
            viewModel.contains("private fun resumeIfAlreadyRunning(workspaceId: String, binder: RuntimeBinder)")
        )
        assertTrue(
            "and must still require BOTH server and session daemons alive \u00a713 (xvnc OR the termux-x11 helper, plus the session)",
            viewModel.contains("if (xServerAlive && sessionAlive)")
        )
    }

    @Test
    fun thePortPollAndGeometryWereNotTouched() {
        // Explicitly excluded from §16.3.1's task. The *call* is what this file
        // guards: §16.3.1 must not have moved or reshaped the poll.
        //
        // §16.3.4 later replaced the poll's fixed `delay(150)` with
        // DesktopPortPoll's schedule, so the interval assertion moved to
        // DesktopPortPollTest / DesktopPortPollWiringSourceTest. Asserting the
        // policy call here instead keeps this test's original purpose — "the
        // poll is still the poll" — without pinning a constant that a later,
        // separately reviewed task deliberately changed.
        assertTrue(viewModel.contains("DesktopPortPoll.delayAfterFailure("))
        // Desktop Modes (2026-08): the poll still targets loopback with the same
        // budget, but the port is now the workspace's own (the `port` local,
        // allocated from DesktopPortRegistry), and the geometry decision runs
        // through the resolution preset before reaching xvncCommand.
        assertTrue(
            viewModel.contains("waitForVncPort(\n") ||
                viewModel.contains("waitForVncPort(host = \"127.0.0.1\", port = port, timeoutMs = 5000")
        )
        assertTrue(
            "the call still targets loopback with the 5000 ms budget and the workspace port " +
                "(2026-09-05: the optional isServerAlive short-circuit is appended after the budget)",
            viewModel.contains("host = \"127.0.0.1\", port = port, timeoutMs = 5000")
        )
        assertTrue(
            viewModel.contains(
                "DesktopGeometry.xvncCommand(\n" +
                "            geometry,\n" +
                "            display = display,\n" +
                "            rfbPort = port,\n" +
                "            securityArgs = securityArgs,"
            )
        )
    }

    @Test
    fun theDaemonStartOrderIsUnchanged() {
        // §1.114: Xvnc first, then the port poll, then the session — a session
        // started before the display exists dies immediately.
        val body = startDesktopCode
        val xvncIndex = body.indexOf("DAEMON_XVNC,")
        val portIndex = body.indexOf("waitForVncPort(")
        val sessionIndex = body.indexOf("DesktopSessionProvisioner.DAEMON_NAME,")
        assertTrue("Xvnc must start first", xvncIndex > 0)
        assertTrue("then the port poll", xvncIndex < portIndex)
        assertTrue("then the session daemon", portIndex < sessionIndex)
    }
}
