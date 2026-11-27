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
            .substringAfter("fun startDesktop(workspaceId: String, backendOverride: GuiBackend? = null)")
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
        // The whole saving. A surviving standalone Xvnc lookup in the start path
        // would mean the merge added an exec instead of removing one.
        assertFalse(
            "no standalone Xvnc lookup may remain in startDesktop: $startDesktopBody",
            startDesktopCode.contains("\"which Xvnc\"") ||
                startDesktopCode.contains("\"command -v Xvnc")
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
        // §1.85's contract: after installing Xvnc the guest is re-probed,
        // because the install may have pulled in an X client. Finding 001
        // extends the same refresh to the WHOLE combined probe: the install
        // replaced the Xvnc binary, so `localhostSupported` must be re-earned
        // from the server that will actually start. A session-only re-probe
        // leaves the pre-install `false` in place, and the auth gate then
        // rejects a no-password start with desktop_vnc_loopback_unsupported.
        val installBranch = startDesktopCode
            .substringAfter("if (!discovery.xvncFound)")
            .substringBefore("var sessionPath =")
        assertTrue(
            "the install branch must run the full post-install discovery probe: $installBranch",
            installBranch.contains("reProbeAfterXvncInstall(")
        )
        assertTrue(
            "and must still run the auto-install",
            installBranch.contains("autoInstallXvnc(")
        )
        assertFalse(
            "a session-only re-probe would leave localhostSupported stale: $installBranch",
            installBranch.contains("DesktopSessionProvisioner.detectSessionCommand(")
        )
    }

    @Test
    fun thePostInstallRefreshIsTheFullProbeNotASessionOnlyRead() {
        // The helper is the single place the refresh happens. It must issue
        // `DesktopStartupProbe.command` (Xvnc + session + capabilities), never
        // a bare session probe.
        val helper = codeOnly(
            viewModel
                .substringAfter("private suspend fun reProbeAfterXvncInstall(")
                .substringBefore("private suspend fun autoInstallXvnc(")
        )
        assertTrue(
            "the post-install refresh must issue the combined probe: $helper",
            helper.contains("DesktopStartupProbe.command(")
        )
        assertTrue(
            "and must decode it",
            helper.contains("DesktopStartupProbe.parse(")
        )
        assertFalse(
            "the refresh must not be session-only: $helper",
            helper.contains("DesktopSessionProvisioner.detectSessionCommand(")
        )
    }

    // ── finding 009: an unanswered probe must never install ───────────────

    @Test
    fun bothStartPathsRefuseToInstallOnAFailedProbe() {
        val code = startDesktopCode
        assertEquals(
            "both start paths must gate on Result.probeFailed after the initial probe and again " +
                "after a post-install refresh",
            4,
            Regex("if \\(discovery\\.probeFailed\\)").findAll(code).count()
        )
        // Each initial gate must precede its Xvnc-missing branch, so an
        // incomplete probe can never reach autoInstallXvnc (finding 009).
        val firstGate = code.indexOf("if (discovery.probeFailed)")
        val firstMissing = code.indexOf("if (!discovery.xvncFound)")
        assertTrue("VNC path: probeFailed gate must exist", firstGate > 0)
        assertTrue("VNC path: Xvnc-missing branch must exist", firstMissing > firstGate)
        val secondMissing = code.indexOf(
            "if (provider == X11Provider.XVNC && !discovery.xvncFound)"
        )
        val secondGate = code.lastIndexOf("if (discovery.probeFailed)", secondMissing)
        assertTrue("X11 path: probeFailed gate must exist", secondGate > firstMissing)
        assertTrue("X11 path: Xvnc-missing branch must follow its gate", secondMissing > secondGate)

        // The post-install refresh is an exec too: each call must be followed
        // by its own probeFailed gate before the retry or the session/preset
        // path, so an unanswered refresh cannot install anything either.
        val refreshIndices = Regex("discovery = reProbeAfterXvncInstall\\(")
            .findAll(code).map { it.range.first }.toList()
        assertEquals("both start paths must re-probe after an install", 2, refreshIndices.size)
        refreshIndices.forEachIndexed { index, refresh ->
            val postGate = code.indexOf("if (discovery.probeFailed)", refresh)
            val nextStep = listOf(
                "discovery = retryCapabilityIfNeeded(",
                "sessionPathFrom(declared, discovery.sessionProbeOutput)"
            ).map { code.indexOf(it, refresh) }
                .filter { it > refresh }
                .minOrNull() ?: Int.MAX_VALUE
            assertTrue("refresh $index must have a post-install probeFailed gate", postGate > refresh)
            assertTrue(
                "refresh $index's gate must run before the retry/session path",
                postGate < nextStep
            )
        }

        // And the branch body itself must not contain an install call.
        val firstBranch = code.substringAfter("if (discovery.probeFailed)").substringBefore("}")
        assertFalse(
            "a failed probe must not auto-install: $firstBranch",
            firstBranch.contains("autoInstallXvnc(") || firstBranch.contains("installDesktopPreset(")
        )
    }

    @Test
    fun theCapabilityRetryIsBoundedAndSharedByBothPaths() {
        val code = startDesktopCode
        assertEquals(
            "both start paths (the X11 one only for the Xvnc provider) must call the retry helper",
            2,
            Regex("discovery = retryCapabilityIfNeeded\\(").findAll(code).count()
        )
        val helper = codeOnly(
            viewModel
                .substringAfter("private suspend fun retryCapabilityIfNeeded(")
                .substringBefore("private suspend fun provisionVncPassword(")
        )
        assertTrue(
            "the retry must re-ask only the capability question: $helper",
            helper.contains("DesktopStartupProbe.capsCommand()")
        )
        assertFalse(
            "the retry must not re-run the whole combined probe: $helper",
            helper.contains("DesktopStartupProbe.command(")
        )
        assertTrue(
            "and must decode the bounded answer",
            helper.contains("DesktopStartupProbe.parseCaps(")
        )
    }

    @Test
    fun bothStartPathsRefreshDiscoveryBeforeTheirAuthGate() {
        // Finding 001: the same stale-capability bug existed in startVncDesktop
        // and startX11Desktop. Both declare discovery as a var, both replace it
        // in the install branch, and both select the session from the refreshed
        // result.
        val code = startDesktopCode
        assertEquals(
            "both start paths must declare a replaceable discovery result",
            2,
            Regex("var discovery = DesktopStartupProbe\\.parse\\(").findAll(code).count()
        )
        assertEquals(
            "both start paths must re-probe after a successful install",
            2,
            Regex("discovery = reProbeAfterXvncInstall\\(").findAll(code).count()
        )
        assertEquals(
            "both must resolve the session from the refreshed result",
            2,
            Regex("sessionPathFrom\\(declared, discovery\\.sessionProbeOutput\\)")
                .findAll(code).count()
        )
        // Ordering: each install branch must precede the auth gate that reads
        // discovery.localhostSupported.
        val firstInstall = code.indexOf("discovery = reProbeAfterXvncInstall(")
        val firstGate = code.indexOf("unauthenticatedStartBlocked(")
        assertTrue("the first install refresh must exist", firstInstall > 0)
        assertTrue("the first auth gate must exist", firstGate > firstInstall)
        val secondInstall = code.indexOf("discovery = reProbeAfterXvncInstall(", firstInstall + 1)
        val secondGate = code.indexOf("unauthenticatedStartBlocked(", firstGate + 1)
        assertTrue("the second install refresh must exist", secondInstall > firstGate)
        assertTrue("the second auth gate must follow it", secondGate > secondInstall)
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
        // Match the ASSIGNMENT, not any mention of the state: `startDesktop`
        // legitimately reads `outcome is DesktopUiState.NoSession` after the
        // start returns (to decide whether a failed start needs releasing), and
        // a bare-name search would find that read first. The invariant is about
        // where the state is SET — inside the null-session branch.
        val noSessionStateIndex = body.indexOf("_desktopState.value = DesktopUiState.NoSession")
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

    // ── a failed start releases only what it launched ─────────────────────

    @Test
    fun aFailedStartStopsOnlyTheDaemonsItTouched() {
        // 2026-09 integration fix: releaseFailedStart used to stop Xvnc, the
        // session, the termux-x11 helper and the web daemon unconditionally —
        // including on a NoSession refusal that never reached startDaemon.
        // That tore down an X server the user had started by other means,
        // contradicting the invariant this file exists to pin.
        val helper = codeOnly(
            viewModel
                .substringAfter("private suspend fun releaseFailedStart(")
                .substringBefore("fun stopDesktop(")
        )
        assertTrue(
            "the release must consult the attempt's touched set: $helper",
            helper.contains("attempt.touchedDaemons()")
        )
        assertTrue(
            "and stop exactly those daemons: $helper",
            helper.contains("for (daemonName in touched)")
        )
        listOf(
            "stopDaemon(workspaceId, DAEMON_XVNC)",
            "stopDaemon(workspaceId, DesktopSessionProvisioner.DAEMON_NAME)",
            "stopDaemon(workspaceId, TermuxX11.DAEMON_NAME)",
            "stopDaemon(workspaceId, DAEMON_WEB)"
        ).forEach { unconditional ->
            assertFalse(
                "releaseFailedStart must not stop an untouched daemon ($unconditional): $helper",
                helper.contains(unconditional)
            )
        }
    }

    @Test
    fun theVncPathMarksADaemonOnlyAfterTheSessionIsResolved() {
        // The no-session refusal must stay ahead of the first daemon mark:
        // a start that never reaches startDaemon has nothing to release.
        val body = startDesktopCode
        val noSessionIndex = body.indexOf("if (sessionPath == null)")
        val firstMarkIndex = body.indexOf("attempt.markTouched(")
        assertTrue("the null-session check must exist", noSessionIndex > 0)
        assertTrue("the first daemon mark must exist", firstMarkIndex > 0)
        assertTrue(
            "no daemon may be marked before the no-session refusal",
            firstMarkIndex > noSessionIndex
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
