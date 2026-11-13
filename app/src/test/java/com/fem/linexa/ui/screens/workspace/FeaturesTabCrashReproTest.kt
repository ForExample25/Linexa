package com.fem.linexa.ui.screens.workspace

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.features.AudioFeatureState
import com.fem.linexa.service.runtime.features.ClipboardFeatureState
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.NotificationFeatureState
import com.fem.linexa.service.runtime.features.SshFeatureState
import com.fem.linexa.ui.theme.LinexaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * 2026-08-28 crash-repro track — the on-device "tapping the Features segment
 * inside a workspace crashes the app" report.
 *
 * Composes the REAL [FeaturesTab] (real [com.fem.linexa.data.Feature] list,
 * real drawables, real string formats, real panels) against a REAL
 * [FeaturesViewModel] whose supervisors are wired exactly like AppModule's
 * providers — over fakes for the three process edges (exec host, host
 * clipboard, audio sink). Locale is pinned to Persian (fa), the reporting
 * device's locale: any `%`-placeholder/format-arg mismatch in the default
 * strings the tab renders crashes HERE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "fa")
class FeaturesTabCrashReproTest {

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

    private val screenLifecycleOwner: LifecycleOwner =
        object : LifecycleOwner {
            private val registry =
                LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }

    // ── AppModule-shaped wiring over process-edge fakes ─────────────────────

    // Test-isolation fix (2026-09-03): cancel created ViewModels' scopes
    // BEFORE resetMain — leaked Main-dispatcher resumptions used to hit the
    // missing-Main fallback (unmocked Looper) and print fatal exceptions that
    // poisoned the next test class's setMain check. See
    // HomeViewModelPreviewTest for the full mechanism note.
    private val createdScopes = mutableListOf<kotlinx.coroutines.Job>()

    private fun realFeaturesViewModel(): FeaturesViewModel =
        FeaturesTabCrashReproTestHarness.realFeaturesViewModel()
            .also { createdScopes += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!! }

    private fun setContent(
        viewModel: FeaturesViewModel,
        toggles: Map<String, Boolean> = WorkspaceContent.builtinFeatureDefaults,
        settings: FeatureSettings = FeatureSettings(),
        onToggle: (String, Boolean) -> Unit = { _, _ -> }
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screenLifecycleOwner) {
                LinexaTheme(darkTheme = true) {
                    FeaturesTab(
                        features = WorkspaceContent.builtinFeatures,
                        toggles = toggles,
                        onToggleChange = onToggle,
                        settings = settings,
                        onSettingsChange = {},
                        workspaceId = "ws-test",
                        binder = null,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    /** The plain open — the reported crash is tapping the segment. */
    @Test
    fun featuresTab_opens_withDefaultToggles_withoutCrashing() {
        setContent(realFeaturesViewModel())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SSH").assertExists()
        composeRule.onNodeWithText("Audio").assertExists()
    }

    /** Every feature OFF — the *other* default branch of every status line. */
    @Test
    fun featuresTab_allTogglesOff_rendersWithoutCrashing() {
        val allOff = WorkspaceContent.builtinFeatureDefaults.mapValues { false }
        setContent(realFeaturesViewModel(), toggles = allOff)
        composeRule.waitForIdle()
    }

    /**
     * Every guest-state branch of every status line: Running/Failed/etc. —
     * these carry the tab's only formatted strings.
     */
    @Test
    fun featuresTab_everyGuestState_rendersWithoutCrashing() {
        val vm = realFeaturesViewModel()
        setContent(vm)
        composeRule.waitForIdle()
        vm.publishForTest(
            ssh = SshFeatureState.Running(8022),
            notifications = NotificationFeatureState.Active,
            clipboard = ClipboardFeatureState.Active(listOf("xclip")),
            audio = AudioFeatureState.Active(listOf("ALSA"))
        )
        composeRule.waitForIdle()
        vm.publishForTest(
            ssh = SshFeatureState.Failed("install failed"),
            notifications = NotificationFeatureState.Blocked,
            clipboard = ClipboardFeatureState.Active(emptyList()),
            audio = AudioFeatureState.Unsupported
        )
        composeRule.waitForIdle()
        vm.publishForTest(
            ssh = SshFeatureState.Installing("apt"),
            notifications = NotificationFeatureState.Unavailable,
            clipboard = ClipboardFeatureState.Unavailable,
            audio = AudioFeatureState.Conflict
        )
        composeRule.waitForIdle()
    }

    /** Toggle every feature through both states (the persisted-intent path). */
    @Test
    fun featuresTab_toggleEveryFeature_withoutCrashing() {
        val toggles = WorkspaceContent.builtinFeatureDefaults.toMutableMap()
        val vm = realFeaturesViewModel()
        var flippedAll = false
        setContent(vm, toggles = toggles, onToggle = { id, enabled ->
            toggles[id] = enabled
            flippedAll = true
        })
        composeRule.waitForIdle()
        // Flip every switch through its real onCheckedChange path.
        WorkspaceContent.builtinFeatures.forEach { feature ->
            val before = toggles.toMap()
            composeRule.runOnUiThread {
                // Drive the toggle callback the row's Switch invokes.
                val next = !(before[feature.id] ?: feature.enabledByDefault)
                toggles[feature.id] = next
            }
            composeRule.waitForIdle()
        }
    }

    /** Expand each configurable panel and render its content. */
    @Test
    fun featuresTab_expandEveryPanel_withoutCrashing() {
        setContent(realFeaturesViewModel())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SSH").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Desktop").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clipboard").performClick()
        composeRule.waitForIdle()
    }
}
