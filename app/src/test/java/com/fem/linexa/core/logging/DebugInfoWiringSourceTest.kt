package com.fem.linexa.core.logging

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 7 structural guard: "Enable debug info" actually disables the
 * **collection** logic, and every capture site is behind the one flag.
 *
 * This is the §1.19 defect class in its purest form. A `DebugCapture` object, a
 * gate, and a guarded sink can each be perfectly correct while nothing starts the
 * gate — in which case the flag never changes, the app compiles, every other test
 * passes, and the user's toggle does nothing. Worse, the failure is *silent in the
 * bad direction* for any site that is left unguarded: it just keeps logging with
 * the switch off, which is precisely what the phase forbids and exactly what no
 * screenshot can reveal.
 *
 * Asserted against module source because the pieces that must be connected are an
 * `Application`, an `object` with static call sites, and a Compose ViewModel — none
 * of which this repo can instantiate in a JVM test (there is no Robolectric and no
 * Compose UI harness, §1.124(a)). Same idiom as `FeatureSettingsWiringSourceTest`
 * and `AboutWiringSourceTest`.
 */
class DebugInfoWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/core/logging/DebugCapture.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val application: String
        get() = source("src/main/java/com/fem/linexa/LinexaApplication.kt")
    private val gate: String
        get() = source("src/main/java/com/fem/linexa/core/logging/DebugCaptureGate.kt")
    private val sink: String
        get() = source("src/main/java/com/fem/linexa/core/logging/FileLogSink.kt")
    private val runtimeTrace: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/RuntimeTrace.kt")
    private val prootRuntime: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt")
    private val detailViewModel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceDetailViewModel.kt")
    private val settingsTab: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")
    private val strings: String
        get() = source("src/main/res/values/strings.xml")

    // ── The gate is actually started ──────────────────────────────────────

    @Test
    fun theApplicationStartsTheCaptureGate() {
        assertTrue(
            "LinexaApplication must inject DebugCaptureGate — without it nothing ever writes DebugCapture",
            application.contains("DebugCaptureGate")
        )
        assertTrue(
            "and it must start it, or the flag stays at its fail-closed default forever and debug info is permanently unavailable",
            application.contains("debugCaptureGate.start(")
        )
    }

    @Test
    fun theGateObservesTheSettingRatherThanReadingItOnce() {
        assertTrue(
            "a one-shot isDebugEnabled() read would freeze capture at process start, so the user's toggle would need an app restart",
            gate.contains("observeDebugEnabled()")
        )
        assertFalse(
            "the gate must not read the one-shot accessor",
            gate.contains("isDebugEnabled()")
        )
    }

    @Test
    fun theGateIsTheOnlyProductionWriterOfTheFlag() {
        // One writer is what makes "the setting and the behaviour cannot disagree"
        // true. A second one would reintroduce the half-capturing state.
        val writers = mainSourceFiles()
            .filter { it.readText().contains("DebugCapture.setEnabled(") }
            .map { it.name }
            .sorted()
        assertTrue(
            "expected DebugCaptureGate to be the only production writer, found: $writers",
            writers == listOf("DebugCaptureGate.kt")
        )
    }

    @Test
    fun turningTheToggleOffAlsoClearsTheBufferedTrace() {
        assertTrue(
            "stopping appends still leaves up to 400 lines per workspace resident for a hidden panel",
            gate.contains("runtimeTrace.clear()")
        )
    }

    // ── Every capture site is gated ───────────────────────────────────────

    @Test
    fun theFileLogSinkChecksTheFlagBeforeTouchingDisk() {
        assertTrue(
            "the ~500 Logger.* call sites all land here; unguarded, the app writes a rotating 2 MB file set with the switch off",
            sink.contains("if (!DebugCapture.isEnabled()) return")
        )
        val guardIndex = sink.indexOf("if (!DebugCapture.isEnabled()) return")
        val diskIndex = sink.indexOf("AppLogFiles.writeLock")
        assertTrue(
            "the guard must precede the lock and the append, not sit inside them",
            guardIndex in 1 until diskIndex
        )
    }

    @Test
    fun theLogcatWriteStaysUnconditional() {
        // A decision, not an omission: logcat is a platform-owned fixed-size ring
        // that Linexa cannot read, display, clear or retain, and it is the entire
        // documented on-device diagnosis path (§1.59). Silencing it would make a
        // crash report from a user with debug off unactionable while reclaiming
        // nothing observable.
        val guardIndex = sink.indexOf("if (!DebugCapture.isEnabled()) return")
        val logcatIndex = sink.indexOf("Log.println(")
        assertTrue(
            "Log.println must run before the capture guard",
            logcatIndex in 1 until guardIndex
        )
    }

    @Test
    fun theRuntimeTraceGatesBothWritersButNeitherReader() {
        val recordBody = runtimeTrace.substringAfter("fun record(").substringBefore("}")
        val newRunBody = runtimeTrace.substringAfter("fun newRun(").substringBefore("}")
        assertTrue(
            "record must refuse while capture is off",
            recordBody.contains("if (!DebugCapture.isEnabled()) return")
        )
        assertTrue(
            "newRun must refuse too — it appends a header line like any other",
            newRunBody.contains("if (!DebugCapture.isEnabled()) return")
        )

        val renderBody = runtimeTrace.substringAfter("fun render(").substringBefore("\n    }")
        assertFalse(
            "render must NOT be gated: a user who enables debug, reproduces a failure and opens the panel has to see what was captured",
            renderBody.contains("DebugCapture")
        )
        val clearBody = runtimeTrace.substringAfter("fun clear(").substringBefore("\n    }")
        assertFalse(
            "clear must NOT be gated: it runs after the flag is already false, so a gated clear would never execute",
            clearBody.contains("DebugCapture.isEnabled()")
        )
    }

    @Test
    fun theTwoExpensivePRootDumpsAreGated() {
        assertTrue(
            "the bootstrap diagnoseAll dump stats/reads every bundled binary — don't build it for a user who cannot read it",
            prootRuntime.contains("if (DebugCapture.isEnabled() && bootstrapDiagnosticsDumped.compareAndSet(false, true))")
        )
        assertTrue(
            "the exec forensics dump is a full second blocking PRoot boot on the main thread under sessionsLock",
            prootRuntime.contains("if (DebugCapture.isEnabled() && execForensicsDone.putIfAbsent(workspaceId, true) == null)")
        )
    }

    // ── §16.3 (§1.167): the remaining two launch dumps ─────────────────────

    @Test
    fun theLaunchAndDaemonForensicDumpsAreGatedToo() {
        // These were the last unconditional expensive diagnostics in the runtime.
        // runProcess's dump is the costly one — up to five fingerprintPath calls at
        // nine filesystem operations each — and it ran on EVERY one-shot exec;
        // startDaemon's ran on every desktop launch, because Xvnc is a §1.83 daemon.
        assertTrue(
            "runProcess must consult the flag before building any argv/env/fingerprint data",
            prootRuntime.contains("if (DebugCapture.isEnabled()) logLaunchForensics(spec)")
        )
        assertTrue(
            "startDaemon must do the same for its argv/env mirror",
            prootRuntime.contains("if (DebugCapture.isEnabled()) logDaemonForensics(spec, name)")
        )
    }

    @Test
    fun theLaunchDumpsAreGatedAtTheCallSiteNotInsideTheHelpers() {
        // The gate has to precede argument evaluation and the whole dump body. A
        // check *inside* the helper would still pay for the call and would invite a
        // later edit to do work before it; only hiding the Logger output would leave
        // the fingerprintPath syscalls — the actual cost — in place.
        listOf("logLaunchForensics", "logDaemonForensics").forEach { helper ->
            val body = prootRuntime
                .substringAfter("private fun $helper(")
                .substringBefore("\n    }")
            assertFalse(
                "$helper must not re-check the flag; its single call site owns that decision",
                body.contains("DebugCapture")
            )
        }
        // And the expensive part must live in the helper, i.e. behind the gate —
        // not inline in runProcess where it would run regardless.
        val runProcessBody = prootRuntime
            .substringAfter("private fun runProcess(")
            .substringBefore("private fun ")
        assertFalse(
            "fingerprintPath is the syscall-heavy part and must not run on the ungated path",
            runProcessBody.contains("fingerprintPath(")
        )
    }

    @Test
    fun theLaunchDumpsKeepTheirPreGatingContent() {
        // §16.3's first item is a reachability change only. If the headers or the
        // truncation were rewritten at the same time, a reader who turns debug on
        // would no longer see what the §1.52 audit evidence describes.
        listOf(
            "=== FORENSIC: full argv (§1.52) ===",
            "=== FORENSIC: complete env passed to proot (§1.52, sorted) ===",
            "=== FORENSIC: pipeline path verification (§1.52) ===",
            "=== FORENSIC: §1.83 daemon '\$name' full argv (§1.52) ==="
        ).forEach { line ->
            assertTrue("the dump format must be unchanged: missing $line", prootRuntime.contains(line))
        }
    }

    @Test
    fun theOnceOnlyLatchesAreOnlySpentWhenCaptureIsOn() {
        // The flag is evaluated BEFORE the latch in both cases (&& short-circuits),
        // so enabling debug after a launch still produces the dump on the next open
        // instead of finding the latch already consumed by a run that discarded it.
        listOf(
            "DebugCapture.isEnabled() && bootstrapDiagnosticsDumped",
            "DebugCapture.isEnabled() && execForensicsDone"
        ).forEach { ordering ->
            assertTrue("expected short-circuit ordering: $ordering", prootRuntime.contains(ordering))
        }
    }

    @Test
    fun theRootfsInitLogGatesItsSuccessLineButNotItsFailures() {
        // The highest-frequency workspace-log write in the app: ensureGuestTmp runs
        // before every terminal open AND every one-shot exec, and `rootfs-init.log`
        // has no rotation or size cap (§1.18/§1.53 keep workspace logs out of the
        // app log's generation scheme). The asymmetry is the decision — a user
        // cannot have enabled a debug setting before their workspace broke.
        val initializer = source("src/main/java/com/fem/linexa/service/installer/GuestRootfsInitializer.kt")
        assertTrue(
            "the routine success line must go through the gated helper",
            initializer.contains("logDebugEvent(workspaceId, \"guest /tmp ready")
        )
        assertTrue(
            "and that helper is what checks the flag",
            initializer.contains("if (!DebugCapture.isEnabled()) return")
        )
        listOf("chmod 1777 FAILED", "mode verification FAILED", "created guest /tmp").forEach { entry ->
            assertTrue(
                "\"$entry\" explains a broken workspace and must stay unconditional",
                initializer.contains("logEvent(workspaceId, \"$entry")
            )
        }
    }

    // ── The display path ──────────────────────────────────────────────────

    @Test
    fun theTracePanelRendersOnlyWhileDebugIsOnAndAtAControlledRate() {
        assertTrue(
            "the render collector must be scoped to the setting, not run unconditionally for a hidden panel",
            detailViewModel.contains("flatMapLatest")
        )
        assertTrue(
            "and it must be rate-limited — startup bursts dozens of hops, each re-joining up to 400 lines",
            detailViewModel.contains("sample(TRACE_REFRESH_INTERVAL_MS)")
        )
        assertFalse(
            "the old unconditional collector must be gone",
            detailViewModel.contains("runtimeTrace.revision.collect {")
        )
    }

    @Test
    fun theRefreshIntervalIsBoundedAndNotAPollingLoop() {
        assertTrue(
            "a named constant, so the rate is a stated decision rather than a literal",
            detailViewModel.contains("TRACE_REFRESH_INTERVAL_MS")
        )
        val value = Regex("TRACE_REFRESH_INTERVAL_MS: Long = (\\d+)L")
            .find(detailViewModel)?.groupValues?.get(1)?.toLong()
        assertTrue("expected a bounded sub-second interval, got $value", value != null && value in 100..1000)
        assertFalse(
            "must not be a delay-driven poll — an idle workspace with the panel open should do no work",
            detailViewModel.contains("while (true)")
        )
    }

    // ── The renamed buttons ───────────────────────────────────────────────

    @Test
    fun theTraceCopyActionSurvivesTheHavenRebuild() {
        // HAVEN: the trace reads as a mono block with a Copy ghost button
        // (concept fr-wsset). The Haven button is a labelled text button, so
        // the icon-era contentDescription strings are gone with the buttons.
        assertTrue(settingsTab.contains("R.string.common_copy"))
        assertFalse(
            "the old \"Copy Runtime Trace\" label must be gone",
            settingsTab.contains("R.string.workspace_debug_copy_trace)")
        )
        assertFalse(
            "and the old string resources must be removed, not orphaned",
            strings.contains("name=\"workspace_debug_copy_trace\"")
        )
    }

    @Test
    fun theTraceBlockIsRenderedAsTheHavenMonoBlock() {
        // HAVEN: the trace renders inside the settings segment as the shared
        // mono block; the section itself keeps the debug toggle that gates it.
        assertTrue(settingsTab.contains("HMonoBlock(") || settingsTab.contains("HavenType.monoBlock"))
        assertTrue(settingsTab.contains("onSetDebugEnabled"))
    }

    @Test
    fun bothTogglesDescribeCollectionRatherThanDisplay() {
        // Two screens host the same app-scoped switch, so their subtitles must not
        // imply different behaviour — and neither may claim it only shows things.
        assertTrue(strings.contains("<string name=\"workspace_debug_enable_desc\">Collect"))
        assertTrue(strings.contains("<string name=\"global_settings_debug_desc\">Collect"))
    }

    private fun mainSourceFiles(): List<File> =
        File(moduleRoot, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
}
