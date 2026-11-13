package com.fem.linexa.ui.screens.device

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fem.linexa.data.hostinfo.AndroidHostInfoSources
import com.fem.linexa.data.hostinfo.AndroidHostSystemInfoProvider
import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.ui.theme.LinexaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * 2026-08-27 crash-repro track — the regression harness for the on-device
 * "tapping Device & system information crashes the app" report
 * (com.fem.linexa, build e5a95f89).
 *
 * WHY THIS TEST EXISTS: plain JVM tests cover the provider/mapper logic but
 * never exercise (a) the REAL Android Context surface the collectors touch,
 * (b) real resource/drawable inflation, (c) real Compose composition of the
 * destination, or (d) real string formatting with arguments. Any of
 * those can crash on a device while every JVM test stays green.
 *
 * WHAT IT DOES: boots Robolectric (SDK 34 — the app's target), constructs the
 * REAL [AndroidHostInfoSources] against a real Context, runs the REAL
 * provider pipeline (every collector), maps with the REAL mappers, and
 * composes the REAL [DeviceInfoScreen] through the REAL ViewModel — the exact
 * production chain minus Hilt (the ViewModel is a plain constructor; Hilt only
 * supplies the singleton provider, which is built here by hand identically to
 * AppModule).
 *
 * UI/UX Phase 3: the Performance dashboard destination (the second screen this
 * harness originally composed) was removed from the product; its harness
 * section went with it. Device & System Information is the surviving
 * destination and keeps the full pipeline coverage.
 *
 * Locale is pinned to Persian (fa) because the reporting device runs fa:
 * every formatted lookup then falls back to the default-locale strings, and
 * any `%`-placeholder/format-arg mismatch in those strings crashes HERE.
 *
 * A pass means "no crash found on this harness" — it does not prove the
 * on-device crash is fixed, only that this entire pipeline is clean on an
 * SDK-34 JVM. It runs in CI as a permanent regression net for the screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "fa")
class HostInfoScreensCrashReproTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Robolectric's main looper is paused and compose-test idling never drains
     * viewModelScope's Handler posts, so under the raw Main dispatcher the
     * ViewModels' poll loop never completes a single cycle here (the first
     * failing run showed 2957 idling evaluations with the state still
     * DISCONNECTED). Swapping Main for an unconfined test dispatcher makes the
     * loop run for real across the provider's IO hop; on a device the real Main
     * dispatcher drives the same code — this only changes the harness, not the
     * app.
     */
    private val testMain = UnconfinedTestDispatcher()

    @Before
    fun swapMainDispatcher() {
        Dispatchers.setMain(testMain)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** The exact AppModule wiring, minus the Hilt graph. */
    private fun realProvider(): AndroidHostSystemInfoProvider =
        AndroidHostSystemInfoProvider(
            sources = AndroidHostInfoSources(ApplicationProvider.getApplicationContext())
        )

    /**
     * Production reaches these screens through a NavHost, which provides
     * LocalLifecycleOwner around every destination. There is no NavHost in
     * this harness, so the test provides one — exactly the composition-local
     * the real wiring supplies (with lifecycle-runtime-compose 2.7.0 this is
     * the androidx.compose.ui.platform location).
     */
    private val screenLifecycleOwner: LifecycleOwner =
        object : LifecycleOwner {
            private val registry =
                LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }

    // ── 1. The collectors themselves, loudly (production swallows per- ──────
    //    category failures; HERE a throw must fail the test so it is seen).

    @Test
    fun providers_fullCollectionCycle_completesWithoutThrowing() {
        val provider = realProvider()
        val snapshot = runBlocking { provider.snapshot() }
        // Every category was attempted against the real Context + real /proc.
        org.junit.Assert.assertEquals(
            HostInfoCategory.entries.size,
            snapshot.capabilities.size
        )
        // A forced second pass exercises the rate-guard + delta paths.
        val forced = runBlocking { provider.refreshSnapshot() }
        org.junit.Assert.assertNotNull(forced.collectedAtEpochMs)
    }

    /**
     * Deterministic real-time condition await. Espresso-backed waitUntil
     * proved flaky on Robolectric (its idling never lets the unconfined IO
     * resume surface), while plain thread sleeping lets the provider's IO hop
     * complete and its unconfined continuation publish into the StateFlow.
     */
    private fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(200)
        }
    }

    // ── 2. The Device & System destination, end to end ──────────────────────

    @Test
    fun deviceInfoScreen_realPipeline_rendersContentWithoutCrashing() {
        val viewModel = DeviceInfoViewModel(realProvider())

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screenLifecycleOwner) {
                LinexaTheme(darkTheme = true) {
                    DeviceInfoScreen(onBack = {}, viewModel = viewModel)
                }
            }
        }

        // 2026-08-27: real-time await (see [awaitTrue]) — the first real
        // snapshot must transition the screen out of the loading blob without
        // any crash anywhere in the pipeline.
        viewModel.start()
        awaitTrue { !viewModel.state.value.loading }
        composeRule.waitForIdle()
        val state = viewModel.state.value
        org.junit.Assert.assertFalse(state.loading)
        org.junit.Assert.assertNotNull(state.collectedAtEpochMs)
        // The summary header rendered with the real device model from Build.*
        org.junit.Assert.assertNotEquals(DeviceInfoState.DASH, state.summaryModel)
    }

    // ── 3. Manual refresh path (the app-bar action on DeviceInfo) ───────────

    @Test
    fun deviceInfoScreen_manualRefresh_completesWithoutCrashing() {
        val provider = realProvider()
        val viewModel = DeviceInfoViewModel(provider)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screenLifecycleOwner) {
                LinexaTheme(darkTheme = true) {
                    DeviceInfoScreen(onBack = {}, viewModel = viewModel)
                }
            }
        }
        viewModel.start()
        awaitTrue { !viewModel.state.value.loading }
        viewModel.refresh()
        awaitTrue { !viewModel.state.value.refreshing }
        composeRule.waitForIdle()
    }
}
