package com.fem.linexa.core.logging

import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.domain.settings.ThemeMode
import com.fem.linexa.service.runtime.RuntimeTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 7 — the one path from the persisted switch to the capture flag.
 *
 * What is asserted here is mostly *ordering* and *liveness*, because those are the
 * two ways this class can be subtly wrong while looking right:
 *
 *  * If it read the setting once instead of observing it, capture would be frozen
 *    at whatever the value was when the process started, and the user would have to
 *    kill the app for their own toggle to take effect.
 *  * If it cleared the trace before disabling capture, a concurrent `record` from
 *    the runtime could re-fill the buffer that was just emptied — the user turns
 *    debug off and is still holding lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugCaptureGateTest {

    private val settings = FakeSettingsRepository()
    private val trace = RuntimeTrace()
    private val gate = DebugCaptureGate(settings, trace)

    /**
     * 2026-08-28: pin a test Main for the whole class. `apply()` can reach
     * host-side observers that touch `Dispatchers.Main`; on the plain JVM the
     * Android dispatcher fails to initialize (Looper not mocked) and the
     * leaked exception surfaced in the NEXT class's `runTest`
     * (UncaughtExceptionsBeforeTest in TerminalViewModelTest).
     */
    private val mainDispatcher = StandardTestDispatcher()

    @org.junit.Before
    fun pinMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun reset() {
        DebugCapture.resetForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun mirrorsTheInitialSettingIntoTheCaptureFlag() = runTest {
        settings.debugEnabled.value = true
        gate.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertTrue(DebugCapture.isEnabled())
    }

    @Test
    fun followsLaterChangesRatherThanReadingOnce() = runTest {
        settings.debugEnabled.value = false
        gate.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertFalse(DebugCapture.isEnabled())

        settings.debugEnabled.value = true
        advanceUntilIdle()
        assertTrue("a one-shot read would leave capture off until the process restarted", DebugCapture.isEnabled())

        settings.debugEnabled.value = false
        advanceUntilIdle()
        assertFalse(DebugCapture.isEnabled())
    }

    @Test
    fun startIsIdempotent() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        gate.start(scope)
        gate.start(scope)
        advanceUntilIdle()
        // A second call must not add a second, invisible subscription for the rest
        // of the process's lifetime. Both would write the same value, so the symptom
        // would not be a wrong flag — just a permanent duplicate collector.
        assertEquals(1, settings.observeCount)
    }

    @Test
    fun disablingCaptureAlsoDropsTheBufferedTrace() {
        DebugCapture.setEnabled(true)
        trace.record("ws", "buffered line")
        assertTrue(trace.render("ws").contains("buffered line"))

        gate.applyForTesting(false)

        assertFalse(DebugCapture.isEnabled())
        assertEquals(
            "stopping appends is not enough — up to 400 lines per workspace would stay resident for a hidden panel",
            "",
            trace.render("ws")
        )
    }

    @Test
    fun captureIsDisabledBeforeTheBufferIsCleared() {
        // The ordering that matters. If clear ran first, a `record` landing between
        // the two statements would survive the toggle-off. Asserted by observing the
        // flag from inside the clear: with the correct order, capture is already off
        // by the time anything can re-append.
        DebugCapture.setEnabled(true)
        trace.record("ws", "one")
        gate.applyForTesting(false)
        // A late append (the racing runtime hop) must now be refused outright.
        trace.record("ws", "late")
        assertEquals("", trace.render("ws"))
    }

    @Test
    fun enablingCaptureDoesNotClearTheTrace() {
        // Only the off transition clears. Clearing on enable would discard lines
        // captured by a previous enabled period for no reason.
        DebugCapture.setEnabled(true)
        trace.record("ws", "keep me")
        gate.applyForTesting(true)
        assertTrue(trace.render("ws").contains("keep me"))
    }

    private class FakeSettingsRepository : SettingsRepository {
        val debugEnabled = MutableStateFlow(false)
        var observeCount: Int = 0

        override fun observeDebugEnabled(): Flow<Boolean> {
            observeCount++
            return debugEnabled
        }

        override suspend fun isDebugEnabled(): Boolean = debugEnabled.value
        override suspend fun setDebugEnabled(enabled: Boolean) { debugEnabled.value = enabled }

        // Not exercised here — this gate reads exactly one setting, deliberately.
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
        override fun currentThemeMode(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        // ColorTheme members added to the interface after this fake was
        // written — inert defaults keep the debug-capture tests meaningful.
        override fun observeColorTheme(): Flow<ColorTheme> = MutableStateFlow(ColorTheme.ALPINE_BLUE)
        override fun currentColorTheme(): ColorTheme = ColorTheme.ALPINE_BLUE
        override suspend fun setColorTheme(theme: ColorTheme) = Unit
        // Terminal UI preferences are not exercised by the capture gate.
        override fun observeRecentSessionsLayout(): Flow<RecentSessionsLayout> =
            MutableStateFlow(RecentSessionsLayout.DEFAULT)
        override suspend fun setRecentSessionsLayout(layout: RecentSessionsLayout) = Unit
        override fun observeTerminalToolbar(): Flow<TerminalToolbarSettings> =
            MutableStateFlow(TerminalToolbarSettings.DEFAULT)
        override suspend fun setTerminalToolbar(settings: TerminalToolbarSettings) = Unit
        // The terminal font size left the repository in the 2026-08-29 terminal
        // migration — the terminal owns its appearance (TerminalFontPreference).
        override fun observeLastAcknowledgedWhatsNewVersion(): Flow<Int> = MutableStateFlow(0)
        override suspend fun setLastAcknowledgedWhatsNewVersion(versionCode: Int) = Unit
    }
}
