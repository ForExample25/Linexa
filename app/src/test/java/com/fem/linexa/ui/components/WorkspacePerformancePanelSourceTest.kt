package com.fem.linexa.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 5B structural guard for the performance panel's wiring.
 *
 * Why a source test: the two rules below are the ones that make the panel *honest*
 * and *cheap*, and neither is observable from a screenshot or reachable from a JVM
 * unit test — verifying them behaviourally needs Compose UI testing or Robolectric,
 * neither of which exists in this repo (§1.124(a)). Same technique and rationale as
 * `HomeTerminalOverlaySourceTest` and `LifecycleConformanceSourceTest`.
 *
 * The two rules:
 *
 * 1. **The sampler stops when the panel leaves composition.** It must be driven by
 *    a `DisposableEffect` with an `onDispose { pause() }`, not a `LaunchedEffect`.
 *    A `/proc` walk every 2 seconds that outlives its UI is real, permanent battery
 *    cost, and that was the pre-Phase-5B behaviour: the poll was started from a
 *    `LaunchedEffect(pid)` and cancelled only by `onCleared()` or by a `stop()` no
 *    caller ever made.
 * 2. **The panel is scoped to the workspace, not the device.** It must read its pid
 *    from `RuntimeState.Running` and must not reach for device-wide totals
 *    (`ActivityManager.MemoryInfo`, `Debug.getMemoryInfo`, `getRuntime().totalMemory`)
 *    which would silently turn a workspace metric into a phone or app metric — the
 *    one thing the plan calls a critical scope requirement for this panel.
 */
class WorkspacePerformancePanelSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/components/WorkspacePerformancePanel.kt")
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

    private fun source(path: String): String = File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    private val panel: String get() = source("ui/components/WorkspacePerformancePanel.kt")
    private val viewModel: String get() = source("ui/components/WorkspacePerformanceViewModel.kt")
    private val monitor: String get() = source("data/resource/ProcResourceMonitor.kt")
    private val topology: String get() = source("data/resource/CpuTopologyProvider.kt")
    private val workspaceScreen: String get() = source("ui/screens/workspace/WorkspaceScreen.kt")

    // ── rule 1: the sampler must not outlive the UI ───────────────────────────

    @Test
    fun theSamplerIsStartedAndStoppedByADisposableEffect() {
        assertTrue(
            "the panel must drive sampling from a DisposableEffect so it can stop on dispose",
            panel.contains("DisposableEffect(")
        )
        assertTrue(
            "the panel must pause sampling in onDispose — a LaunchedEffect has no such hook, " +
                "and a /proc poll that outlives its UI is permanent battery cost",
            Regex("onDispose\\s*\\{[^}]*viewModel\\.pause\\(\\)").containsMatchIn(panel)
        )
    }

    @Test
    fun theViewModelExposesBothAStartAndAPause() {
        assertTrue("start(workspaceId, pid) must exist", viewModel.contains("fun start("))
        assertTrue("pause() must exist so the composable can stop the poll", viewModel.contains("fun pause()"))
        assertTrue(
            "onCleared must also cancel the poll, for the case where the ViewModel dies first",
            Regex("override fun onCleared\\(\\)\\s*\\{[^}]*pollJob\\?\\.cancel\\(\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(viewModel)
        )
    }

    @Test
    fun theRefreshIntervalIsBoundedAndNamed() {
        assertTrue(
            "the poll interval must be a named constant, not a literal delay",
            viewModel.contains("POLL_INTERVAL_MS")
        )
        // Phase 08 (OPT-PERF-004): the VM may declare the literal itself or
        // delegate to the one shared ProcessPolling constant — either way the
        // effective value must be resolvable and at least 1 s.
        val interval = Regex("POLL_INTERVAL_MS\\s*=\\s*([0-9_]+)L")
            .find(viewModel)
            ?.groupValues
            ?.get(1)
            ?.replace("_", "")
            ?.toLongOrNull()
            ?: run {
                assertTrue(
                    "POLL_INTERVAL_MS must be numeric or delegate to ProcessPolling",
                    viewModel.contains("POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS")
                )
                val shared = File(moduleRoot, "src/main/java/com/fem/linexa/ui/misc/ProcessPolling.kt").readText()
                Regex("INTERVAL_MS\\s*=\\s*([0-9_]+)L")
                    .find(shared)
                    ?.groupValues
                    ?.get(1)
                    ?.replace("_", "")
                    ?.toLongOrNull()
            }
        assertTrue("POLL_INTERVAL_MS must be declared with a numeric value", interval != null)
        assertTrue(
            "a refresh faster than 1s is not the 'low, controlled rate' this panel requires (was ${interval}ms)",
            interval!! >= 1_000L
        )
    }

    @Test
    fun theHistoryIsBounded() {
        assertTrue(
            "the sparkline history must be bounded or the path grows without limit",
            viewModel.contains("HISTORY_SIZE") && viewModel.contains("takeLast(HISTORY_SIZE)")
        )
    }

    /**
     * Storage is a directory walk that can cover a multi-GB rootfs on a cache miss.
     * Reading it on the sampling tick would make it the most expensive thing in the
     * app, so it must be refreshed per-start rather than per-tick.
     */
    @Test
    fun theRootfsWalkIsNotOnTheSamplingTick() {
        val loop = Regex("while \\(true\\) \\{.*?\\n            \\}", RegexOption.DOT_MATCHES_ALL)
            .find(viewModel)
            ?.value
            ?: ""
        assertTrue("the poll loop must be present to check", loop.contains("resourceMonitor.snapshot("))
        assertFalse(
            "the workspace size walk must not run on every tick",
            loop.contains("getWorkspaceStorage") || loop.contains("refreshStorage")
        )
    }

    // ── rule 2: workspace scope, not device or app scope ──────────────────────

    @Test
    fun theSampledPidComesFromTheRunningWorkspace() {
        assertTrue(
            "the panel must take its pid from RuntimeState.Running — that is what makes this " +
                "a workspace metric rather than a device one",
            panel.contains("(runtimeState as? RuntimeState.Running)?.pid")
        )
    }

    @Test
    fun noDeviceWideOrAppWideMetricSourceIsUsed() {
        val forbidden = listOf(
            "ActivityManager.MemoryInfo",
            "getMemoryInfo",
            "Debug.getMemoryInfo",
            "Runtime.getRuntime().totalMemory",
            "Runtime.getRuntime().freeMemory",
            "/proc/stat\"",   // device-wide CPU, as opposed to /proc/<pid>/stat
            "/proc/loadavg"
        )
        for (source in listOf(panel, viewModel, monitor)) {
            for (token in forbidden) {
                assertFalse(
                    "$token measures the device or the whole app, not this workspace",
                    source.contains(token)
                )
            }
        }
    }

    /**
     * `/proc/<pid>/task/<tid>/children` requires `CONFIG_PROC_CHILDREN`, absent on
     * the Android kernels this app targets — and its absence fails *silently*,
     * yielding a one-process "tree". The tree must come from parent links instead.
     */
    @Test
    fun theProcessTreeIsNotBuiltFromTheOptionalChildrenFile() {
        // Checked against *string literals* only: the KDoc explains at length why
        // that file is not used, and a word-level search would flag the explanation
        // rather than a use of it.
        val quotedPaths = Regex("\"[^\"]*\"").findAll(monitor).map { it.value }.toList()
        assertFalse(
            "/proc/<pid>/task/<tid>/children requires CONFIG_PROC_CHILDREN, is absent on the " +
                "Android kernels this app targets, and fails silently into a one-process tree",
            quotedPaths.any { it.contains("children") || it.contains("/task/") }
        )
        assertTrue(
            "the tree must be derived from ppid links",
            monitor.contains("ProcessTree.descendantsOf(")
        )
    }

    @Test
    fun theClockTickRateIsReadAtRuntimeRatherThanAssumed() {
        assertTrue(
            "CLK_TCK is a runtime constant and must be read via Os.sysconf",
            topology.contains("_SC_CLK_TCK")
        )
        assertTrue(
            "CPU must be normalised by core count or a multi-core tree exceeds 100%",
            topology.contains("_SC_NPROCESSORS_CONF") || topology.contains("availableProcessors")
        )
        assertTrue(
            "the monitor must take the runtime tick rate from the shared topology provider",
            monitor.contains("topologyProvider.clockTicksPerSecond")
        )
    }

    @Test
    fun statParsingGoesThroughTheTestedParsers() {
        assertTrue(
            "stat/status/smaps parsing must go through ProcMetricParsers, whose comm-with-spaces " +
                "handling is asserted in ProcMetricParsersTest",
            monitor.contains("ProcMetricParsers.")
        )
        assertFalse(
            "the monitor must not split a stat line itself — that is the field-shift bug",
            monitor.contains("stat\").readText().split(\" \")")
        )
    }

    // ── no fabricated metrics ─────────────────────────────────────────────────

    /**
     * The plan forbids fabricated workspace metrics. A fallback that returns a
     * random walk is indistinguishable from a working sampler on screen, which is
     * why Phase 5B deleted `DemoResourceMonitor` rather than keeping it for
     * previews.
     */
    @Test
    fun thereIsNoFabricatingResourceMonitor() {
        val demo = File(moduleRoot, "src/main/java/com/fem/linexa/domain/resource/DemoResourceMonitor.kt")
        assertFalse(
            "DemoResourceMonitor fabricates plausible metrics and must not exist",
            demo.exists()
        )
        for (source in listOf(panel, viewModel, monitor)) {
            assertFalse("no randomness may reach a metric", source.contains("Random"))
        }
    }

    // ── wiring: the panel must actually be on screen (§1.19) ──────────────────

    /**
     * §1.19's rule: a component nothing calls compiles perfectly. `XServerManager`
     * was fully implemented and never injected; this asserts the panel has a real
     * caller and that its predecessor is gone.
     */
    @Test
    fun theWorkspaceScreenStillConsumesTheWorkspaceScopedSampler() {
        // HAVEN rebuild: the old card is gone, but the workspace-scoped data
        // pipeline is not — WorkspaceScreen now drives the same ViewModel and
        // renders its content through the Haven `.pnums` row.
        assertTrue(
            "WorkspaceScreen must drive WorkspacePerformanceViewModel",
            workspaceScreen.contains("WorkspacePerformanceViewModel") &&
                workspaceScreen.contains("performanceViewModel.start(workspace.id")
        )
        assertTrue(
            "and map its state through the pure content type",
            workspaceScreen.contains("WorkspacePerformanceContent.from(") &&
                workspaceScreen.contains("sample = performanceState.sample") &&
                workspaceScreen.contains("storage = performanceState.storage")
        )
    }

    @Test
    fun theSupersededResourceMonitorCardIsGone() {
        val card = File(moduleRoot, "src/main/java/com/fem/linexa/ui/components/ResourceMonitorCard.kt")
        val cardViewModel = File(moduleRoot, "src/main/java/com/fem/linexa/ui/components/ResourceMonitorViewModel.kt")
        assertFalse("ResourceMonitorCard.kt must be deleted, not left unused", card.exists())
        assertFalse("ResourceMonitorViewModel.kt must be deleted", cardViewModel.exists())
        assertFalse(
            "WorkspaceScreen must not still reference the old card",
            workspaceScreen.contains("ResourceMonitorCard")
        )
    }

    /**
     * `Workspace.storageUsedFraction` is `size / 10 GB` and its own KDoc calls it
     * "purely cosmetic". Inside a panel captioned "Storage" that is a fabricated
     * metric, so the panel must measure real free space instead.
     */
    @Test
    fun thePanelDoesNotRenderTheCosmeticStorageFraction() {
        assertFalse(
            "storageUsedFraction is a hardcoded 10GB budget — the panel must use measured free space",
            panel.contains("storageUsedFraction") || viewModel.contains("storageUsedFraction")
        )
        assertTrue(
            "the panel's storage must come from the measured WorkspaceStorage reading",
            viewModel.contains("getWorkspaceStorage(")
        )
    }
}
