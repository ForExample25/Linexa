package com.fem.linexa.service.runtime.features

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.111: [WorkspaceNotificationSupervisor] — the host half of the Notifications
 * feature, driven against a real temp-folder spool and the fake guest/bridge
 * seams.
 *
 * The drain is exercised directly ([WorkspaceNotificationSupervisor.drainOnce])
 * rather than through the poller: the poll interval is not the contract, the
 * "one complete record becomes exactly one notification, then is deleted" cycle
 * is. Deletion is asserted explicitly because a record that survived its drain
 * would repost forever.
 */
class WorkspaceNotificationSupervisorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ws = "ws-1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var spool: File

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun supervisor(
        guest: RecordingGuestFeatureHost = RecordingGuestFeatureHost(),
        bridge: RecordingNotificationBridge = RecordingNotificationBridge()
    ): WorkspaceNotificationSupervisor {
        spool = temp.newFolder("rootfs-$ws", "spool")
        return WorkspaceNotificationSupervisor(
            host = guest,
            bridge = bridge,
            scope = scope,
            spoolDirFor = { spool },
            pollIntervalMillis = 10L
        )
    }

    /** Writes one committed spool record with the given argv. */
    private fun writeRecord(name: String, vararg argv: String) {
        File(spool, "$name${NotifySendProvisioner.RECORD_EXTENSION}")
            .writeBytes(argv.joinToString("") { "$it\u0000" }.toByteArray())
    }

    private fun records(): List<String> =
        spool.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ── enable / disable ──

    @Test
    fun enablingInstallsTheShimInTheGuest() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val state = supervisor(guest).reconcileNotifications(ws, enabled = true)

        assertEquals(NotificationFeatureState.Active, state)
        assertTrue(
            "must install the shared install command: ${guest.execCalls}",
            guest.execCalls.any { it == NotifySendProvisioner.installCommand() }
        )
    }

    @Test
    fun enablingWithoutALiveSessionIsUnavailableNotAFailure() = runBlocking {
        // The toggle stays on; §1.110's launch reconcile installs the shim once
        // a guest exists. Reporting Failed here would look broken.
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        val state = supervisor(guest).reconcileNotifications(ws, enabled = true)

        assertEquals(NotificationFeatureState.Unavailable, state)
        assertTrue("no guest command without a guest", guest.execCalls.isEmpty())
    }

    @Test
    fun aFailedShimInstallIsReportedWithTheGuestErrorTail() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses[NotifySendProvisioner.SHIM_GUEST_PATH] =
                com.fem.linexa.domain.runtime.CommandResult(1, "", "read-only file system")
        }
        val state = supervisor(guest).reconcileNotifications(ws, enabled = true)

        assertTrue(state is NotificationFeatureState.Failed)
        assertTrue((state as NotificationFeatureState.Failed).reason.contains("read-only file system"))
    }

    @Test
    fun theShimIsInstalledEvenWhenTheHostCannotPostSoALaterGrantJustWorks() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val bridge = RecordingNotificationBridge(postingAllowed = false)
        val state = supervisor(guest, bridge).reconcileNotifications(ws, enabled = true)

        // Blocked, not Failed: nothing is broken, the user has to grant something.
        assertEquals(NotificationFeatureState.Blocked, state)
        assertTrue(guest.execCalls.any { it == NotifySendProvisioner.installCommand() })
    }

    @Test
    fun disablingRemovesTheShimAndWithdrawsTheWorkspacesNotifications() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(guest, bridge)

        supervisor.reconcileNotifications(ws, enabled = true)
        val state = supervisor.reconcileNotifications(ws, enabled = false)

        assertEquals(NotificationFeatureState.Disabled, state)
        assertTrue(guest.execCalls.any { it == NotifySendProvisioner.uninstallCommand() })
        assertEquals(listOf(ws), bridge.cancelled)
    }

    @Test
    fun disablingDrainsWhatTheGuestAlreadyProducedBeforeRemovingTheShim() = runBlocking {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        writeRecord("1", "Emitted just before the toggle flipped")

        supervisor.reconcileNotifications(ws, enabled = false)

        assertEquals(listOf("Emitted just before the toggle flipped"), bridge.summariesFor(ws))
    }

    @Test
    fun disablingIsIdempotentAndSafeWithoutAGuest() = runBlocking {
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(guest, bridge)

        supervisor.reconcileNotifications(ws, enabled = false)
        supervisor.reconcileNotifications(ws, enabled = false)

        // No guest exec attempted (there is no guest), but the host state is
        // still released both times — that is what reaps a stale watcher.
        assertTrue(guest.execCalls.isEmpty())
        assertEquals(listOf(ws, ws), bridge.cancelled)
    }

    @Test
    fun enablingTwiceDoesNotStartASecondWatcher() = runBlocking {
        val supervisor = supervisor()

        supervisor.reconcileNotifications(ws, enabled = true)
        supervisor.reconcileNotifications(ws, enabled = true)

        // Asserted on the watcher itself rather than on posted notifications:
        // two pollers draining one directory is a resource leak even though the
        // per-workspace drain lock keeps each record posted exactly once, so
        // counting posts could not distinguish one poller from two.
        assertEquals(1, supervisor.activeWatcherCount(ws))
    }

    @Test
    fun oneRecordIsPostedExactlyOnceEvenWhileThePollerIsRunning() = runBlocking {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        supervisor.reconcileNotifications(ws, enabled = true)

        // A record read by the poller and by an explicit drain concurrently
        // would otherwise be posted twice (read-then-delete).
        writeRecord("1", "Once")
        repeat(20) { supervisor.drainOnce(ws) }

        assertEquals(listOf("Once"), bridge.summariesFor(ws))
    }

    // ── the drain: spool → notification ──

    @Test
    fun aCompleteRecordBecomesOneNotificationAndIsDeleted() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        writeRecord("1", "Build finished", "42 tests passed")

        supervisor.drainOnce(ws)

        assertEquals(1, bridge.posted.size)
        val posted = bridge.posted.single().second
        assertEquals("Build finished", posted.summary)
        assertEquals("42 tests passed", posted.body)
        assertTrue("a consumed record must be deleted or it reposts forever", records().isEmpty())
    }

    @Test
    fun anInFlightTempRecordIsNeverRead() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        // The shim writes .tmp then renames; a .tmp is by definition incomplete.
        File(spool, "1${NotifySendProvisioner.RECORD_TEMP_EXTENSION}").writeBytes("Half".toByteArray())

        supervisor.drainOnce(ws)

        assertTrue(bridge.posted.isEmpty())
        assertEquals(1, records().size)
    }

    @Test
    fun recordsArePostedOldestFirst() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        writeRecord("b", "Second")
        File(spool, "b${NotifySendProvisioner.RECORD_EXTENSION}").setLastModified(2_000L)
        writeRecord("a", "First")
        File(spool, "a${NotifySendProvisioner.RECORD_EXTENSION}").setLastModified(1_000L)

        supervisor.drainOnce(ws)

        assertEquals(listOf("First", "Second"), bridge.summariesFor(ws))
    }

    @Test
    fun anUnusableRecordIsDroppedRatherThanRetriedForever() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        // Empty (no argv at all) — will never become decodable.
        File(spool, "1${NotifySendProvisioner.RECORD_EXTENSION}").writeBytes(ByteArray(0))

        supervisor.drainOnce(ws)

        assertTrue(bridge.posted.isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun anOversizedRecordIsDroppedWithoutBeingRead() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        File(spool, "1${NotifySendProvisioner.RECORD_EXTENSION}")
            .writeBytes(ByteArray(NotifySendProvisioner.MAX_RECORD_BYTES + 1) { 'x'.code.toByte() })

        supervisor.drainOnce(ws)

        assertTrue("a guest must not be able to make the host allocate", bridge.posted.isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun aThrowingBridgeDoesNotStopTheDrainOrLeaveTheRecordBehind() {
        val bridge = RecordingNotificationBridge().apply {
            postThrows = IllegalStateException("system UI refused")
        }
        val supervisor = supervisor(bridge = bridge)
        writeRecord("1", "One")
        writeRecord("2", "Two")

        supervisor.drainOnce(ws)

        // Both were attempted, and neither is left to be retried forever.
        assertTrue(records().isEmpty())
    }

    @Test
    fun aMissingSpoolDirectoryIsNotAnError() {
        val supervisor = supervisor()
        assertTrue(spool.deleteRecursively())

        supervisor.drainOnce(ws)
        // A guest that never called notify-send must cost nothing and log no
        // failure — the directory only exists once the shim ran.
    }

    @Test
    fun oneDrainIsBoundedSoARunawayProducerCannotMonopolizeThePoll() {
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(bridge = bridge)
        repeat(100) { i -> writeRecord("r$i", "N$i") }

        supervisor.drainOnce(ws)

        assertTrue("bounded per tick", bridge.posted.size < 100)
        assertFalse("the remainder stays for the next tick", records().isEmpty())
    }

    // ── host-state release (the §1.110 teardown path) ──

    @Test
    fun releasingHostStateWithdrawsNotificationsWithoutTouchingTheGuest() {
        val guest = RecordingGuestFeatureHost()
        val bridge = RecordingNotificationBridge()
        val supervisor = supervisor(guest, bridge)

        supervisor.releaseHostState(ws)

        // By teardown the guest may already be gone, so running the uninstall
        // would only log a failure; the notifications must go regardless.
        assertTrue(guest.execCalls.isEmpty())
        assertEquals(listOf(ws), bridge.cancelled)
    }

    // ── scope guard ──

    @Test
    fun theFeatureNeverStartsAGuestDaemonOrTouchesAnotherFeature() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val supervisor = supervisor(guest)

        supervisor.reconcileNotifications(ws, enabled = true)
        supervisor.reconcileNotifications(ws, enabled = false)

        // A notification producer is one-shot by nature — no daemon, so nothing
        // to leak if the app dies.
        assertTrue(guest.startedDaemons.isEmpty())
        assertTrue(guest.stoppedDaemons.isEmpty())
        guest.execCalls.forEach { command ->
            assertFalse(command, command.contains("sshd"))
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
        }
    }
}
