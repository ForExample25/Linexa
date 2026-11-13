package com.fem.linexa.domain.terminal

import com.fem.linexa.domain.runtime.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the terminal desktop reducer.
 *
 * These tests deliberately treat the pty as an external fact: the manager is
 * handed a process state and may only change presentation. The lifecycle half
 * (same pty across minimize/restore) is pinned in `TerminalsViewModelTest`.
 */
class TerminalWindowManagerTest {

    private fun session(
        id: String,
        state: TerminalState = TerminalState.Running,
        label: String = id,
        runtimeTitle: String? = null
    ) = TerminalWindowSession(
        terminalId = id,
        displayLabel = label,
        processState = state,
        runtimeTitle = runtimeTitle
    )

    @Test
    fun syncCreatesOneWindowPerSessionAndFocusesTheFirst() {
        val manager = TerminalWindowManager()

        manager.syncSessions(listOf(session("T0"), session("T1")))

        assertEquals(listOf("T0", "T1"), manager.state.windows.map { it.terminalId })
        assertEquals("T0", manager.state.activeTerminalId)
        assertEquals(TerminalWindowUiState.ACTIVE, manager.state.window("T0")?.uiState)
        assertEquals(TerminalWindowUiState.CREATED, manager.state.window("T1")?.uiState)
    }

    @Test
    fun minimizeIsPresentationOnlyAndBackgroundMinimizeKeepsFocus() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))

        manager.minimize("T1")
        assertEquals("T0", manager.state.activeTerminalId)
        assertTrue(manager.state.window("T1")!!.isMinimized)
        assertEquals(TerminalWindowUiState.ACTIVE, manager.state.window("T0")?.uiState)

        manager.minimize("T0")
        assertNull("minimizing the focused window leaves the desktop idle", manager.state.activeWindow)
        assertEquals(listOf("T0", "T1"), manager.state.minimizedWindows.map { it.terminalId })
        // The process half is untouched by either minimize.
        assertEquals(TerminalState.Running, manager.state.window("T0")?.processState)
        assertEquals(TerminalState.Running, manager.state.window("T1")?.processState)
    }

    @Test
    fun restoreReusesTheExistingWindowAndItsPreview() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0")))
        manager.updatePreview("T0", "user@linexa:~$ uname -a")
        manager.minimize("T0")

        manager.restore("T0")

        val window = manager.state.window("T0")!!
        assertEquals(TerminalWindowUiState.RESTORED, window.uiState)
        assertEquals("T0", manager.state.activeTerminalId)
        assertEquals("user@linexa:~$ uname -a", window.preview)
        assertEquals(TerminalState.Running, window.processState)
    }

    @Test
    fun maximizeFocusesOneWindowAndRestoreReturnsToNormal() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))

        manager.maximize("T1")
        assertEquals("T1", manager.state.activeTerminalId)
        assertEquals("T1", manager.state.maximizedTerminalId)
        assertTrue(manager.state.window("T1")!!.isMaximized)

        manager.toggleMaximize("T1")
        assertEquals(TerminalWindowUiState.ACTIVE, manager.state.window("T1")?.uiState)
        assertNull(manager.state.maximizedTerminalId)
        assertEquals("T1", manager.state.activeTerminalId)
    }

    @Test
    fun activatingAnotherWindowLeavesMaximizeAndRestoresThePrevious() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))
        manager.maximize("T0")

        manager.activate("T1")

        assertEquals("T1", manager.state.activeTerminalId)
        assertNull("focusing another window leaves maximize", manager.state.maximizedTerminalId)
        assertEquals(TerminalWindowUiState.RESTORED, manager.state.window("T0")?.uiState)
    }

    @Test
    fun beginCloseMarksTheWindowAndFinishCloseFocusesASibling() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))
        manager.activate("T1")

        manager.beginClose("T1")
        assertEquals(TerminalWindowUiState.CLOSING, manager.state.window("T1")?.uiState)
        assertTrue("a closing window is not visible", !manager.state.window("T1")!!.isVisible)

        manager.finishClose("T1")
        assertEquals(listOf("T0"), manager.state.windows.map { it.terminalId })
        assertEquals("T0", manager.state.activeTerminalId)
    }

    @Test
    fun closeConfirmationFollowsProcessStateNotWindowState() {
        val manager = TerminalWindowManager()
        manager.syncSessions(
            listOf(
                session("T0", TerminalState.Running),
                session("T1", TerminalState.Error("boom")),
                session("T2", TerminalState.Starting),
                session("T3", TerminalState.Stopped)
            )
        )

        assertTrue(manager.requiresCloseConfirmation("T0"))
        assertTrue(manager.requiresCloseConfirmation("T2"))
        assertFalse(manager.requiresCloseConfirmation("T1"))
        assertFalse(manager.requiresCloseConfirmation("T3"))
        assertFalse(manager.requiresCloseConfirmation("missing"))
    }

    @Test
    fun titlePrefersAppIdentityThenOscTitleThenStableFallback() {
        val manager = TerminalWindowManager()
        manager.syncSessions(
            listOf(session("T0", runtimeTitle = "vim README.md")),
            mapOf(
                "T0" to TerminalWindowIdentity(
                    title = "Python 3 + venv",
                    appId = "builtin:python",
                    command = "python3"
                )
            )
        )
        assertEquals("Python 3 + venv", manager.state.window("T0")?.title)

        manager.updateRuntimeTitle("T0", "node app.js")
        assertEquals(
            "an app identity owns the title; OSC cannot overwrite it",
            "Python 3 + venv",
            manager.state.window("T0")?.title
        )

        val plain = TerminalWindowManager()
        plain.syncSessions(listOf(session("T1", label = "T2", runtimeTitle = "bash")))
        assertEquals("bash", plain.state.window("T1")?.title)
        plain.updateRuntimeTitle("T1", null)
        assertEquals("Terminal T2", plain.state.window("T1")?.title)
    }

    @Test
    fun previewAndForegroundAreIsolatedPerWindow() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))

        manager.updatePreview("T0", "python3\n>>> ", "python3")
        manager.updatePreview("T1", "node\n> ", "node")

        assertEquals("python3\n>>> ", manager.state.window("T0")?.preview)
        assertEquals("python3", manager.state.window("T0")?.foregroundProcess)
        assertEquals("node\n> ", manager.state.window("T1")?.preview)
        assertEquals("node", manager.state.window("T1")?.foregroundProcess)
    }

    @Test
    fun resyncKeepsWindowStateIdentityAndPreview() {
        val manager = TerminalWindowManager()
        val identity = TerminalWindowIdentity(title = "htop", appId = "builtin:htop", command = "htop")
        manager.syncSessions(listOf(session("T0")), mapOf("T0" to identity))
        manager.updatePreview("T0", "htop output")
        manager.maximize("T0")

        manager.syncSessions(
            listOf(session("T0", TerminalState.Stopping), session("T1")),
            mapOf("T0" to identity)
        )

        val t0 = manager.state.window("T0")!!
        assertTrue("maximize survives a process-state emission", t0.isMaximized)
        assertEquals("htop", t0.title)
        assertEquals("htop output", t0.preview)
        assertEquals(TerminalState.Stopping, t0.processState)
    }

    @Test
    fun aDisappearedActiveSessionIsReplacedByAVisibleSibling() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1"), session("T2")))
        manager.activate("T1")
        manager.minimize("T2")

        manager.syncSessions(listOf(session("T0"), session("T2")))

        assertEquals(listOf("T0", "T2"), manager.state.windows.map { it.terminalId })
        assertEquals("T0", manager.state.activeTerminalId)
        assertEquals(
            "a minimized sibling must not be silently focused",
            TerminalWindowUiState.MINIMIZED,
            manager.state.window("T2")?.uiState
        )
    }

    @Test
    fun unknownIdsAreSafeNoOps() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0")))
        val before = manager.state

        assertEquals(before, manager.activate("missing"))
        assertEquals(before, manager.minimize("missing"))
        assertEquals(before, manager.restore("missing"))
        assertEquals(before, manager.maximize("missing"))
        assertEquals(before, manager.toggleMaximize("missing"))
        assertEquals(before, manager.beginClose("missing"))
        assertEquals(before, manager.finishClose("missing"))
        assertEquals(before, manager.updatePreview("missing", "x"))
        assertEquals(before, manager.updateRuntimeTitle("missing", "x"))
    }

    @Test
    fun clearDropsEveryWindow() {
        val manager = TerminalWindowManager()
        manager.syncSessions(listOf(session("T0"), session("T1")))

        assertEquals(TerminalDesktopState(), manager.clear())
        assertTrue(manager.state.isIdle)
    }
}
