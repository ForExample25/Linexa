package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 8 (§1.138) — JVM tests for [DesktopWindowMode].
 *
 * The pre-Phase-8 code was `if (isRunning) hide() else show()` inline in an
 * effect, which is the shape that let §1.114's missing-restore live: it *reads*
 * as though it handles both directions. Deriving the mode as a value is what makes
 * both directions assertable, so the rules go here rather than in the screen.
 */
class DesktopWindowModeTest {

    private val running = DesktopUiState.Running(workspaceId = "ws-1")

    @Test
    fun aRunningSessionTakesOverTheWindow() {
        val mode = DesktopWindowMode.forState(running)
        assertTrue(mode.hideSystemBars)
        assertTrue(mode.landscape)
    }

    @Test
    fun aRunningSessionIsLandscapeBecauseTheFramebufferIs() {
        // DesktopGeometry has taken the long edge as the width since §1.125, and the
        // viewer contain-scales — so a portrait window drew the desktop as a
        // letterboxed strip using roughly a fifth of the screen.
        assertTrue(DesktopWindowMode.forState(running).landscape)
    }

    @Test
    fun everyPlaceholderStateLeavesTheWindowAlone() {
        // These states are ordinary Compose UI with an app bar, a status chip and
        // buttons; rotating the phone to show "Stopped" would be absurd.
        listOf(
            DesktopUiState.Unbound,
            DesktopUiState.Connected,
            DesktopUiState.Stopped,
            DesktopUiState.Starting,
            DesktopUiState.InstallingDependencies,
            DesktopUiState.NoSession,
            DesktopUiState.Error("boom")
        ).forEach { state ->
            val mode = DesktopWindowMode.forState(state)
            assertFalse("$state must not hide the system bars", mode.hideSystemBars)
            assertFalse("$state must not request landscape", mode.landscape)
        }
    }

    @Test
    fun leavingTheRunningStateRestoresTheWindowWithoutWaitingForDisposal() {
        // The mode is derived, so a session that stops mid-screen releases the
        // window immediately — the user does not have to navigate away to get the
        // status bar back.
        assertEquals(
            DesktopWindowMode.forState(DesktopUiState.Stopped),
            DesktopWindowMode.forState(DesktopUiState.Error("stopped unexpectedly"))
        )
        assertFalse(DesktopWindowMode.forState(DesktopUiState.Stopped).hideSystemBars)
    }

    @Test
    fun theModeIsAValueSoTwoEqualStatesDoNotRestartTheEffect() {
        // The screen keys a DisposableEffect on this; an unstable type would tear
        // the window mode down and set it up again on every recomposition.
        assertEquals(
            DesktopWindowMode.forState(running),
            DesktopWindowMode.forState(DesktopUiState.Running(workspaceId = "ws-1"))
        )
    }

    @Test
    fun landscapeIsTheUserVariantNotAPinnedRotation() {
        // ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE == 11. The plain LANDSCAPE
        // (0) pins one rotation, so a user holding the phone the other way gets an
        // upside-down desktop and cannot turn it round.
        assertEquals(11, DesktopWindowMode.ORIENTATION_USER_LANDSCAPE)
    }

    @Test
    fun theRestoreSentinelIsTheUnspecifiedValue() {
        // ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED == -1: what an activity that
        // has never requested an orientation reports. Only ever restored as a
        // *captured* previous value, never assumed.
        assertEquals(-1, DesktopWindowMode.ORIENTATION_UNSPECIFIED)
    }

    @Test
    fun theTwoOrientationConstantsAreDistinct() {
        assertTrue(
            DesktopWindowMode.ORIENTATION_USER_LANDSCAPE !=
                DesktopWindowMode.ORIENTATION_UNSPECIFIED
        )
    }

    @Test
    fun thereAreExactlyTwoModes() {
        // Any additional combination would be a state the restore path has never
        // been reasoned about for.
        val modes = listOf(
            DesktopUiState.Unbound,
            DesktopUiState.Connected,
            DesktopUiState.Stopped,
            DesktopUiState.Starting,
            DesktopUiState.InstallingDependencies,
            DesktopUiState.NoSession,
            DesktopUiState.Error("x"),
            running
        ).map { DesktopWindowMode.forState(it) }.toSet()
        assertEquals(2, modes.size)
    }

    @Test
    fun theBarsAndTheOrientationMoveTogether() {
        // Hiding the bars without rotating would give a fullscreen portrait window
        // still showing a letterboxed strip — the §1.125 state with less chrome.
        listOf(
            DesktopUiState.Unbound,
            DesktopUiState.Stopped,
            DesktopUiState.Starting,
            running
        ).forEach {
            val mode = DesktopWindowMode.forState(it)
            assertEquals(mode.hideSystemBars, mode.landscape)
        }
    }
}
