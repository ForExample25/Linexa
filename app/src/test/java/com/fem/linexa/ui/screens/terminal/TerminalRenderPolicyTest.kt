package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.domain.terminal.TerminalDesktopState
import com.fem.linexa.domain.terminal.TerminalWindow
import com.fem.linexa.domain.terminal.TerminalWindowUiState
import com.fem.linexa.service.runtime.pty.PtySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * P0 regression (first Root Shell launch): the panel must never paint one
 * window's header over another window's pty while the per-terminal ViewModel
 * is still binding. This is the pure half of the identity guard; Compose UI
 * tests cannot run in this project's harness.
 */
class TerminalRenderPolicyTest {

    private val ptyT0: PtySession = Mockito.mock(PtySession::class.java)
    private val ptyT1: PtySession = Mockito.mock(PtySession::class.java)

    @Test
    fun theTransitionFrameKeepsRenderingTheBoundWindowAndItsPty() {
        val desktop = desktop(active = "T1", windows = listOf(window("T0"), window("T1")))

        val render = TerminalRenderPolicy.select(
            desktop = desktop,
            boundTerminalId = "T0",
            ptyTerminalId = "T0",
            ptySession = ptyT0
        )

        assertEquals("T0", render.window?.terminalId)
        assertSame(ptyT0, render.ptySession)
        assertTrue(render.identityMatches)
    }

    @Test
    fun onceTheBoundIdentityCatchesUpTheNewWindowAndItsPtyAreRendered() {
        val desktop = desktop(active = "T1", windows = listOf(window("T0"), window("T1")))

        val render = TerminalRenderPolicy.select(desktop, "T1", "T1", ptyT1)

        assertEquals("T1", render.window?.terminalId)
        assertSame(ptyT1, render.ptySession)
        assertTrue(render.identityMatches)
    }

    @Test
    fun aStalePtyIsNeverRenderedUnderTheNewHeader() {
        val desktop = desktop(active = "T1", windows = listOf(window("T0"), window("T1")))

        // The header/binding already moved to T1, but a stale T0 pty is still
        // in the state snapshot. The policy must hide it.
        val render = TerminalRenderPolicy.select(desktop, "T1", "T0", ptyT0)

        assertEquals("T1", render.window?.terminalId)
        assertNull(render.ptySession)
        assertTrue(render.identityMatches)
    }

    @Test
    fun aStartingTerminalShowsItsOwnHeaderWithoutThePreviousPty() {
        val desktop = desktop(active = "T1", windows = listOf(window("T0"), window("T1")))

        val render = TerminalRenderPolicy.select(desktop, "T1", null, null)

        assertEquals("T1", render.window?.terminalId)
        assertNull(render.ptySession)
        assertTrue(render.identityMatches)
    }

    @Test
    fun aMinimizedDesktopRendersNothingEvenIfTheBoundIdLingers() {
        val minimized = window("T0", TerminalWindowUiState.MINIMIZED)
        val desktop = desktop(active = null, windows = listOf(minimized))

        val render = TerminalRenderPolicy.select(desktop, "T0", "T0", ptyT0)

        assertNull(render.window)
        assertNull(render.ptySession)
        assertFalse(render.identityMatches)
    }

    @Test
    fun aRemovedBoundWindowFallsBackToTheActiveHeaderWithoutItsStalePty() {
        val desktop = desktop(active = "T1", windows = listOf(window("T1")))

        val render = TerminalRenderPolicy.select(desktop, "T0", "T0", ptyT0)

        assertEquals("T1", render.window?.terminalId)
        assertNull(render.ptySession)
        assertFalse(render.identityMatches)
    }

    @Test
    fun aClosedWindowIsNeverRendered() {
        val closing = window("T0", TerminalWindowUiState.CLOSING)
        val desktop = desktop(active = null, windows = listOf(closing))

        val render = TerminalRenderPolicy.select(desktop, "T0", "T0", ptyT0)

        assertNull(render.window)
        assertNull(render.ptySession)
    }

    private fun desktop(active: String?, windows: List<TerminalWindow>) =
        TerminalDesktopState(windows = windows, activeTerminalId = active)

    private fun window(
        id: String,
        uiState: TerminalWindowUiState = TerminalWindowUiState.ACTIVE
    ) = TerminalWindow(
        terminalId = id,
        displayLabel = id,
        title = "Terminal $id",
        uiState = uiState,
        processState = TerminalState.Running
    )
}
