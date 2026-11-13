package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.runtime.RuntimeHealth
import com.fem.linexa.domain.runtime.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Desktop failure-surfacing (2026-08) — the pure [DesktopHealthGate] decision:
 * an unexpected death of one of the desktop's OWN daemons, while the screen
 * shows a live desktop, becomes an honest desktop Error; every other
 * degradation stays a workspace fact and must not interrupt the session.
 */
class DesktopHealthGateTest {

    private val watched = setOf("xvnc", "xsession", "x11server", "webserver")

    private fun runningWith(vararg components: String): RuntimeState {
        val degradations = components.map { component ->
            RuntimeDegradation(
                component = RuntimeDegradation.DAEMON_COMPONENT_PREFIX + component,
                detail = "daemon '$component' exited unexpectedly (exit code 1)"
            )
        }
        return RuntimeState.Running(pid = 1, since = Instant.EPOCH, health = RuntimeHealth.Degraded(degradations))
    }

    @Test
    fun aDeadXvncWhileRunningIsSurfacedWithTheRuntimeDetail() {
        val detail = DesktopHealthGate.failureDetail(
            runtimeState = runningWith("xvnc"),
            watchedDaemons = watched,
            desktopState = DesktopUiState.Running("ws1")
        )
        assertEquals("daemon 'xvnc' exited unexpectedly (exit code 1)", detail)
    }

    @Test
    fun aDeadXServerDuringStartIsSurfacedSoX11StartingCannotLinger() {
        // 2026-09-05 (state-machine correctness): an X server that dies during
        // X11_STARTING can never reach READY — the machine must move to
        // X11_FAILED immediately, not when the port poll's budget expires.
        val detail = DesktopHealthGate.startingFailureDetail(
            runtimeState = runningWith("xvnc"),
            watchedXServerDaemons = setOf("xvnc", "x11server")
        )
        assertEquals("daemon 'xvnc' exited unexpectedly (exit code 1)", detail)
    }

    @Test
    fun anUnrelatedDaemonDeathDuringStartDoesNotFailTheX11Machine() {
        // sshd dying degrades the workspace, not the in-flight X11 lifecycle.
        assertNull(
            DesktopHealthGate.startingFailureDetail(
                runtimeState = runningWith("sshd"),
                watchedXServerDaemons = setOf("xvnc", "x11server")
            )
        )
    }

    @Test
    fun aHealthyRuntimeSurfacesNothingDuringStart() {
        assertNull(
            DesktopHealthGate.startingFailureDetail(
                runtimeState = runningWith(),
                watchedXServerDaemons = setOf("xvnc", "x11server")
            )
        )
    }

    @Test
    fun aDeadSessionWhileRunningExternallyIsSurfaced() {
        val detail = DesktopHealthGate.failureDetail(
            runtimeState = runningWith("xsession"),
            watchedDaemons = watched,
            desktopState = DesktopUiState.RunningExternal("ws1", "termux-x11")
        )
        assertEquals("daemon 'xsession' exited unexpectedly (exit code 1)", detail)
    }

    @Test
    fun anUnrelatedDaemonDeathDoesNotInterruptTheDesktop() {
        assertNull(
            DesktopHealthGate.failureDetail(
                runtimeState = runningWith("sshd"),
                watchedDaemons = watched,
                desktopState = DesktopUiState.Running("ws1")
            )
        )
    }

    @Test
    fun aHealthyRuntimeSurfacesNothing() {
        assertNull(
            DesktopHealthGate.failureDetail(
                runtimeState = RuntimeState.Running(pid = 1, since = Instant.EPOCH),
                watchedDaemons = watched,
                desktopState = DesktopUiState.Running("ws1")
            )
        )
    }

    @Test
    fun aDesktopThatIsNotLiveIsNotTouched() {
        // Starting, Stopped, Error, WebRunning, Unbound: none is a live RFB/X11
        // desktop, so no daemon death may clobber what the screen shows. (null
        // is covered by the dedicated test below — the parameter accepts a
        // nullable state, Kotlin just will not put null in this typed list.)
        for (state in listOf<DesktopUiState>(
            DesktopUiState.Starting,
            DesktopUiState.Stopped,
            DesktopUiState.WebRunning("ws1", "http://127.0.0.1:8080"),
            DesktopUiState.Unbound,
            DesktopUiState.Connected
        )) {
            assertNull(
                "state $state must not be converted",
                DesktopHealthGate.failureDetail(runningWith("xvnc"), watched, state)
            )
        }
        assertNull(
            DesktopHealthGate.failureDetail(runningWith("xvnc"), watched, null)
        )
    }

    @Test
    fun aNullRuntimeStateSurfacesNothing() {
        assertNull(
            DesktopHealthGate.failureDetail(null, watched, DesktopUiState.Running("ws1"))
        )
    }

    @Test
    fun theAnswerIsDeterministicWhenSeveralWatchedDaemonsDied() {
        val state = runningWith("xsession", "xvnc")
        val first = DesktopHealthGate.failureDetail(state, watched, DesktopUiState.Running("ws1"))
        val second = DesktopHealthGate.failureDetail(state, watched, DesktopUiState.Running("ws1"))
        assertEquals(first, second)
        // And it is one of the two watched failures.
        assertTrue(first!!.contains("xvnc") || first.contains("xsession"))
    }

    @Test
    fun aNonDaemonComponentWithTheSameNameDoesNotQualify() {
        // "xvnc" without the daemon: prefix is a different component key — the
        // prefix check is part of the contract, not decoration.
        val state = RuntimeState.Running(
            pid = 1,
            since = Instant.EPOCH,
            health = RuntimeHealth.Degraded(
                listOf(RuntimeDegradation("xvnc", "degraded for an unrelated reason"))
            )
        )
        assertNull(
            DesktopHealthGate.failureDetail(state, watched, DesktopUiState.Running("ws1"))
        )
    }
}
