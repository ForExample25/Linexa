package com.fem.linexa.ui.screens.workspace

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.data.Workspace
import com.fem.linexa.data.resource.AndroidProcessSignals
import com.fem.linexa.data.resource.CpuTopologyProvider
import com.fem.linexa.data.resource.HostResourceSampler
import com.fem.linexa.domain.packagemanager.ApkProvider
import com.fem.linexa.domain.packagemanager.AptProvider
import com.fem.linexa.domain.packagemanager.DnfProvider
import com.fem.linexa.domain.packagemanager.PackageManagerRegistry
import com.fem.linexa.domain.packagemanager.PacmanProvider
import com.fem.linexa.domain.packagemanager.ZypperProvider
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.terminal.TerminalWindowIdentityRegistry
import com.fem.linexa.service.commands.QuickCommandsCatalogLoader
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.installer.AppInstallProbe
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.ui.components.WorkspacePerformanceViewModel
import com.fem.linexa.ui.theme.LinexaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-08-28 crash-repro track — composes the REAL [WorkspaceScreen] (header,
 * identity, segmented control, all four segments) and walks Apps → Activity →
 * Features → Settings exactly like the on-device report: "tapping the
 * Features tab inside Workspace crashes the app".
 *
 * Every ViewModel the screen resolves is the real production type, built by
 * hand the way AppModule/Hilt builds them (minus the DI graph):
 * [WorkspacePerformanceViewModel] over a no-op [ResourceMonitor] and the real
 * [WorkspaceFileManager], plus the real no-arg [ShortcutsViewModel] and the
 * real [FeaturesViewModel] from [FeaturesTabCrashReproTest]'s wiring.
 *
 * Persian (fa) locale pinned — the reporting device's locale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "fa")
class WorkspaceSegmentsCrashReproTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testMain = UnconfinedTestDispatcher()

    @Before
    fun swapMainDispatcher() {
        Dispatchers.setMain(testMain)
    }

    @After
    fun restoreMainDispatcher() {
        runBlocking { createdScopes.forEach { it.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun workspace() = Workspace(
        id = "ws-crash",
        name = "dev-box",
        description = "Ubuntu 22.04.5 LTS",
        storageUsedLabel = "1.4 GB",
        storageUsedFraction = 0.02f,
        avatarLetter = "U",
        avatarColor = android.graphics.Color.parseColor("#E95420").let {
            androidx.compose.ui.graphics.Color(it)
        },
        isRunning = false,
        status = WorkspaceStatus.Ready
    )

    // Test-isolation fix (2026-09-03): see HomeViewModelPreviewTest — cancel
    // leaked ViewModel scopes BEFORE resetMain so their Main-dispatcher
    // resumptions cannot hit the missing-Main fatal.
    private val createdScopes = mutableListOf<kotlinx.coroutines.Job>()

    private fun performanceViewModel(): WorkspacePerformanceViewModel =
        WorkspacePerformanceViewModel(
            resourceMonitor = NoopResourceMonitor,
            fileManager = WorkspaceFileManager(ApplicationProvider.getApplicationContext())
        )

    private fun featuresViewModel(): FeaturesViewModel =
        FeaturesTabCrashReproTestHarness.realFeaturesViewModel()
            .also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

    /**
     * App-grid redesign: the screen now also resolves the pins ViewModel.
     * Built by hand over a no-op store, the same way the performance VM is —
     * the segment must compose (and attach) without a Hilt graph.
     */
    private fun pinsViewModel(): ShortcutPinsViewModel =
        ShortcutPinsViewModel(NoopShortcutPinsStore)
            .also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

    /**
     * App Launcher redesign: the screen now also resolves the launcher's two
     * ViewModels. Built by hand exactly the way AppModule/Hilt builds them —
     * every type real (the five package providers, the registry, the rootfs
     * metadata chain, the catalog resolver over an absent catalog source, the
     * install coordinator) — so the Apps segment composes without a Hilt
     * graph while still being the production object graph.
     */
    private fun appInstallProbe(): AppInstallProbe =
        AppInstallProbe(
            registry = PackageManagerRegistry(
                AptProvider(),
                PacmanProvider(),
                ApkProvider(),
                DnfProvider(),
                ZypperProvider()
            ),
            workspaceRootFsMetadata = WorkspaceRootFsMetadata(
                fileManager = WorkspaceFileManager(ApplicationProvider.getApplicationContext()),
                inspector = RootFsInspector(WorkspaceConfigParser())
            )
        )

    private fun appInstallCoordinator(): AppInstallCoordinator =
        AppInstallCoordinator(appInstallProbe())

    private fun launcherViewModel(): AppLauncherViewModel =
        AppLauncherViewModel(
            quickCommandsResolver = QuickCommandsResolver(
                QuickCommandsCatalogLoader { null }
            ),
            coordinator = appInstallCoordinator(),
            windowIdentities = TerminalWindowIdentityRegistry()
        )
            .also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

    private fun installTracker(): AppInstallTracker =
        AppInstallTracker(appInstallCoordinator(), appInstallProbe())
            .also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

    private fun sessionsViewModel(): SessionsViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return SessionsViewModel(
            application = application,
            resourceMonitor = NoopResourceMonitor,
            processSignals = AndroidProcessSignals(),
            hostSampler = HostResourceSampler(application, CpuTopologyProvider())
        ).also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }
    }

    private fun setContent() {
        composeRule.setContent {
            LinexaTheme(darkTheme = true) {
                WorkspaceScreen(
                    workspace = workspace(),
                    commands = WorkspaceCommandsAssembler.assemble(
                        builtinNodes = emptyList(),
                        declaredShortcuts = null,
                        custom = emptyList(),
                        customSectionTitle = "فرمان‌های من",
                        customIconRes = android.R.drawable.ic_menu_manage
                    ),
                    customCommands = emptyList(),
                    features = WorkspaceContent.builtinFeatures,
                    featureToggles = WorkspaceContent.builtinFeatureDefaults,
                    featureSettings = FeatureSettings(),
                    runtimeState = RuntimeState.Stopped,
                    runtimeTraceText = "",
                    desktopEnabled = true,
                    debugEnabled = false,
                    binder = null,
                    terminalOpen = false,
                    onTerminalOpenChange = {},
                    activeTerminalId = "T0",
                    onActiveTerminalIdChange = {},
                    showOverview = false,
                    onShowOverviewChange = {},
                    onSetDebugEnabled = {},
                    onBack = {},
                    onRequestExit = {},
                    onOpenDesktop = {},
                    onOpenPackageManager = {},
                    onOpenFileManager = {},
                    onFeatureToggle = { _, _ -> },
                    onFeatureSettingsChange = {},
                    networkSettings = NetworkSettings(),
                    onNetworkSettingsChange = {},
                    onSetTerminalPersistence = {},
                    onWorkspaceDeleted = {},
                    onOpenTaskManager = {},
                    onAddCustomCommand = { _, _ -> },
                    onUpdateCustomCommand = { _, _, _ -> },
                    onDeleteCustomCommand = {},
                    performanceViewModel = performanceViewModel(),
                    shortcutsViewModel = ShortcutsViewModel(TerminalWindowIdentityRegistry()),
                    pinsViewModel = pinsViewModel(),
                    featuresViewModel = featuresViewModel(),
                    launcherViewModel = launcherViewModel(),
                    installTracker = installTracker(),
                    sessionsViewModel = sessionsViewModel()
                )
            }
        }
    }

    /** The reported gesture: open the workspace, tap the Features segment. */
    @Test
    fun workspaceScreen_tapFeaturesSegment_doesNotCrash() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Features").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SSH").assertExists()
        // And back out again — the reverse transition is part of the path.
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.waitForIdle()
    }

    /** The full segment walk, both directions, twice. */
    @Test
    fun workspaceScreen_fullSegmentWalk_doesNotCrash() {
        setContent()
        composeRule.waitForIdle()
        repeat(2) {
            composeRule.onNodeWithText("Features").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Settings").performClick()
            composeRule.waitForIdle()
            // The tab was renamed from "Sessions" to "Activity" (2026-11 pass);
            // all-nodes: during the segmented-content slide both the outgoing
            // and incoming pages compose, so index into the list rather than
            // asserting a single node.
            composeRule.onAllNodesWithText("Activity")[0].performClick()
            composeRule.waitForIdle()
        }
    }
}
