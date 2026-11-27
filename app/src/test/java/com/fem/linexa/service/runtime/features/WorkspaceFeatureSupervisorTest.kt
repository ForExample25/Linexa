package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.runtime.CommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.110: the scripted guest fake moved to [RecordingGuestFeatureHost] so the
 * launch-path tests can drive the same seam. The local name is kept so these
 * §1.109 cases read unchanged.
 */
private typealias FakeGuest = RecordingGuestFeatureHost

/**
 * §1.109: [WorkspaceFeatureSupervisor] reconciliation tests.
 *
 * Driven through a scripted [GuestFeatureHost] fake — which is exactly why
 * that interface exists: the real host is [com.fem.linexa.service.runtime.RuntimeBinder],
 * an `android.os.Binder` a JVM test cannot instantiate.
 *
 * The properties pinned here are the ones a device test would be slowest to
 * catch: that enabling is idempotent, that a missing binary triggers exactly
 * one install then a re-probe, that every failure mode ends in a user-facing
 * [SshFeatureState.Failed] instead of an exception escaping into the UI, and
 * that a feature can never reach the workspace lifecycle.
 */
class WorkspaceFeatureSupervisorTest {

    private val ws = "ws-1"

    private fun guestWithSshdPresent() = FakeGuest().withSshdInstalled()

    // ── enable: happy path ──

    @Test
    fun enableStartsSshdWhenAlreadyInstalled() = runBlocking {
        val guest = guestWithSshdPresent()
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertEquals(SshFeatureState.Running(SshdProvisioner.SSH_PORT), state)
        assertEquals(1, guest.startedDaemons.size)
        assertEquals(SshdProvisioner.DAEMON_NAME, guest.startedDaemons.single().first)
        assertEquals("/usr/sbin/sshd -D -e -p 8022", guest.startedDaemons.single().second)
        // No install ran — the binary was already there.
        assertFalse(guest.execCalls.any { it.contains("install") || it.contains("apk add") })
        // Host keys / privsep dirs were prepared before the daemon launched.
        assertTrue(guest.execCalls.any { it.contains("ssh-keygen -A") })
    }

    @Test
    fun enableReportsProgressStatesInOrder() = runBlocking {
        val guest = FakeGuest().apply {
            responses["command -v sshd"] = CommandResult(1, "", "not found")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
        }
        // Re-probe after install must succeed, so flip the probe response
        // once the install command has run.
        val supervisor = WorkspaceFeatureSupervisor(object : GuestFeatureHost by guest {
            var installed = false
            override suspend fun execCommand(workspaceId: String, command: String): CommandResult {
                if (command.contains("apt-get install")) installed = true
                if (command.contains("command -v sshd") && installed) {
                    return CommandResult(0, "/usr/sbin/sshd\n", "")
                }
                return guest.execCommand(workspaceId, command)
            }
        })

        val seen = mutableListOf<SshFeatureState>()
        val state = supervisor.reconcileSsh(ws, enabled = true) { seen += it }

        assertEquals(SshFeatureState.Running(SshdProvisioner.SSH_PORT), state)
        assertEquals(
            listOf(
                SshFeatureState.Probing,
                SshFeatureState.Installing(PackageManagerDetector.KEY_APT),
                SshFeatureState.Starting
            ),
            seen
        )
    }

    @Test
    fun enableIsIdempotentWhenTheDaemonIsAlreadyRunning() = runBlocking {
        val guest = guestWithSshdPresent().apply { daemonAlive = true }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertEquals(SshFeatureState.Running(SshdProvisioner.SSH_PORT), state)
        // Nothing was re-run: no probe, no prepare, no second daemon.
        assertTrue(guest.execCalls.isEmpty())
        assertTrue(guest.startedDaemons.isEmpty())
    }

    // ── enable: refusals and failures ──

    @Test
    fun enableWithoutALiveSessionIsUnavailableNotFailed() = runBlocking {
        val guest = guestWithSshdPresent().apply { sessionAlive = false }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        // Deliberately not Failed: the toggle stays on and the next reconcile
        // (workspace entry) starts the daemon. Nothing was executed.
        assertEquals(SshFeatureState.Unavailable, state)
        assertTrue(guest.execCalls.isEmpty())
        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun noSupportedPackageManagerFailsWithoutRunningAnInstall() = runBlocking {
        val guest = FakeGuest().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(1, "", "")
            responses["os-release"] = CommandResult(1, "", "")
        }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        assertTrue((state as SshFeatureState.Failed).reason.contains("package manager"))
        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun failedInstallSurfacesTheGuestErrorTail() = runBlocking {
        val guest = FakeGuest().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
            responses["apt-get install"] = CommandResult(100, "", "E: Unable to locate package openssh-server")
        }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        assertTrue((state as SshFeatureState.Failed).reason.contains("Unable to locate package"))
        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun installThatExitsZeroButLeavesNoBinaryFails() = runBlocking {
        val guest = FakeGuest().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
            responses["apt-get install"] = CommandResult(0, "done", "")
        }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        // Never hand a null path to the daemon host.
        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun failedPrepareStopsBeforeStartingTheDaemon() = runBlocking {
        val guest = guestWithSshdPresent().apply {
            responses["ssh-keygen -A"] = CommandResult(1, "", "ssh-keygen: command not found")
        }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        assertTrue((state as SshFeatureState.Failed).reason.contains("prepare"))
        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun daemonThatFailsToStartIsReportedAsFailed() = runBlocking {
        val guest = guestWithSshdPresent().apply { daemonStartSucceeds = false }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        assertEquals(1, guest.startedDaemons.size)
    }

    @Test
    fun anExceptionFromTheGuestBecomesFailedInsteadOfPropagating() = runBlocking {
        val guest = guestWithSshdPresent().apply {
            execThrows = IllegalStateException("proot host died")
        }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        // A feature toggle must never crash the Features tab.
        val state = supervisor.reconcileSsh(ws, enabled = true)

        assertTrue(state is SshFeatureState.Failed)
        assertEquals("proot host died", (state as SshFeatureState.Failed).reason)
    }

    // ── disable ──

    @Test
    fun disableStopsTheDaemon() = runBlocking {
        val guest = guestWithSshdPresent().apply { daemonAlive = true }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = false)

        assertEquals(SshFeatureState.Disabled, state)
        assertEquals(listOf(SshdProvisioner.DAEMON_NAME), guest.stoppedDaemons)
        assertFalse(guest.daemonAlive)
    }

    @Test
    fun disableIsSafeWhenNothingIsRunningAndCleansUpALeftoverDaemon() = runBlocking {
        val guest = guestWithSshdPresent()
        val supervisor = WorkspaceFeatureSupervisor(guest)

        // Repeating a disable must converge, not fail — stopDaemon is
        // idempotent, which is also what reaps a daemon left behind by a
        // toggle flipped off while the app was dead.
        assertEquals(SshFeatureState.Disabled, supervisor.reconcileSsh(ws, enabled = false))
        assertEquals(SshFeatureState.Disabled, supervisor.reconcileSsh(ws, enabled = false))
        assertEquals(2, guest.stoppedDaemons.size)
        assertTrue(guest.execCalls.isEmpty())
    }

    @Test
    fun disableDoesNotRequireALiveSession() = runBlocking {
        val guest = guestWithSshdPresent().apply { sessionAlive = false }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        assertEquals(SshFeatureState.Disabled, supervisor.reconcileSsh(ws, enabled = false))
    }

    // ── UI/UX Phase 6: the configured port ──

    @Test
    fun aConfiguredPortReachesTheDaemonCommandAndTheReportedState() = runBlocking {
        val guest = guestWithSshdPresent()
        val supervisor = WorkspaceFeatureSupervisor(guest)

        val state = supervisor.reconcileSsh(ws, enabled = true, port = 2222)

        assertEquals(SshFeatureState.Running(2222), state)
        assertEquals("/usr/sbin/sshd -D -e -p 2222", guest.startedDaemons.single().second)
    }

    @Test
    fun aRunningDaemonIsNotRestartedToAdoptANewPortAndKeepsReportingTheLiveOne() = runBlocking {
        val guest = guestWithSshdPresent()
        val supervisor = WorkspaceFeatureSupervisor(guest)

        assertEquals(SshFeatureState.Running(2222), supervisor.reconcileSsh(ws, enabled = true, port = 2222))
        guest.execCalls.clear()

        // The user edits the port while sshd is up. Killing it here would drop
        // their open ssh connections as a side effect of typing in a text field,
        // so the change is deferred — and the state must keep naming the port
        // something is ACTUALLY listening on, not the one just requested.
        val state = supervisor.reconcileSsh(ws, enabled = true, port = 3333)

        assertEquals(SshFeatureState.Running(2222), state)
        assertEquals(1, guest.startedDaemons.size)
        assertTrue(guest.stoppedDaemons.isEmpty())
        assertTrue(guest.execCalls.isEmpty())
    }

    @Test
    fun theNewPortIsUsedOnceTheDaemonHasBeenStoppedAndStartedAgain() = runBlocking {
        val guest = guestWithSshdPresent()
        val supervisor = WorkspaceFeatureSupervisor(guest)

        supervisor.reconcileSsh(ws, enabled = true, port = 2222)
        supervisor.reconcileSsh(ws, enabled = false)
        val state = supervisor.reconcileSsh(ws, enabled = true, port = 3333)

        assertEquals(SshFeatureState.Running(3333), state)
        assertEquals("/usr/sbin/sshd -D -e -p 3333", guest.startedDaemons.last().second)
    }

    @Test
    fun aDaemonThisInstanceDidNotStartIsDescribedWithTheRequestedPort() = runBlocking {
        // A daemon left running by a previous process: nothing recorded its port,
        // and sshd is not going to be asked. The requested value is the best
        // available answer and matches the pre-Phase-6 report.
        val guest = guestWithSshdPresent().apply { daemonAlive = true }
        val supervisor = WorkspaceFeatureSupervisor(guest)

        assertEquals(
            SshFeatureState.Running(2222),
            supervisor.reconcileSsh(ws, enabled = true, port = 2222)
        )
        assertTrue(guest.execCalls.isEmpty())
    }

    @Test
    fun theDefaultPortIsUnchangedForACallerThatPassesNone() = runBlocking {
        val guest = guestWithSshdPresent()
        val state = WorkspaceFeatureSupervisor(guest).reconcileSsh(ws, enabled = true)

        assertEquals(SshFeatureState.Running(SshdProvisioner.SSH_PORT), state)
        assertEquals(
            "/usr/sbin/sshd -D -e -p ${SshdProvisioner.SSH_PORT}",
            guest.startedDaemons.single().second
        )
    }

    // ── scope guard ──

    @Test
    fun noCommandTouchesTheWorkspaceLifecycleOrOtherDaemons() = runBlocking {
        val guest = FakeGuest().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
        }
        WorkspaceFeatureSupervisor(guest).reconcileSsh(ws, enabled = true)

        // The feature layer must never reboot/halt the guest or interfere with
        // another feature's daemon (e.g. Xvnc).
        guest.execCalls.forEach { command ->
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
            assertFalse(command, command.contains("halt"))
            assertFalse(command, command.contains("Xvnc"))
        }
        assertTrue(guest.startedDaemons.all { it.first == SshdProvisioner.DAEMON_NAME })
        assertTrue(guest.stoppedDaemons.all { it == SshdProvisioner.DAEMON_NAME })
    }
}
