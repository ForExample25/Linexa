package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.112: [WorkspaceClipboardSupervisor] — the host half of the Clipboard
 * feature, driven against a real temp-folder bridge and the fake guest/clipboard
 * seams.
 *
 * Both directions are asserted through their real mechanisms rather than through
 * the poller (the poll interval is not the contract):
 * - guest→host: [WorkspaceClipboardSupervisor.drainOnce] — one record becomes one
 *   clipboard write, then is deleted (a surviving record would re-apply forever).
 * - host→guest: [WorkspaceClipboardSupervisor.mirrorOnce] and the change
 *   listener — the mirror file is what a `linexa-clip-paste` `cat`s.
 *
 * The mirror's *privacy* rules get as much attention as its content, because the
 * mirror holds the user's own clipboard text inside a workspace's rootfs.
 */
class WorkspaceClipboardSupervisorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ws = "ws-1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var outDir: File
    private lateinit var mirror: File

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun supervisor(
        guest: RecordingGuestFeatureHost = RecordingGuestFeatureHost(),
        clipboard: RecordingHostClipboard = RecordingHostClipboard()
    ): WorkspaceClipboardSupervisor {
        val root = temp.newFolder("rootfs-${counter++}")
        outDir = File(root, ClipboardBridgeProvisioner.OUT_RELATIVE_PATH).apply { mkdirs() }
        mirror = File(root, ClipboardBridgeProvisioner.IN_FILE_RELATIVE_PATH)
        mirror.parentFile?.mkdirs()
        return WorkspaceClipboardSupervisor(
            host = guest,
            clipboard = clipboard,
            scope = scope,
            outDirFor = { outDir },
            mirrorFileFor = { mirror },
            pollIntervalMillis = 10L
        )
    }

    /** Writes one committed copy record. */
    private fun writeRecord(name: String, text: String) {
        File(outDir, "$name${ClipboardBridgeProvisioner.RECORD_EXTENSION}")
            .writeBytes(text.toByteArray())
    }

    private fun records(): List<String> = outDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun mirrorText(): String? = if (mirror.exists()) mirror.readText() else null

    // ── enable / disable ──

    @Test
    fun enablingInstallsBothCanonicalShimsInTheGuest() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        assertTrue("expected Active, was $state", state is ClipboardFeatureState.Active)
        assertTrue(
            "must run the shared install command: ${guest.execCalls}",
            guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() }
        )
    }

    @Test
    fun enablingInstallsEveryAliasAndReportsWhichOnesLanded() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        val expected = ClipboardBridgeProvisioner.ALIASES.map { it.name }
        assertEquals(expected, (state as ClipboardFeatureState.Active).aliases)
        expected.forEach { name ->
            assertTrue(
                "alias $name was never attempted",
                guest.execCalls.any { it.contains("/usr/local/bin/$name") }
            )
        }
    }

    @Test
    fun oneFailingAliasCostsNeitherTheOtherAliasesNorTheBridge() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses["/usr/local/bin/xclip"] = CommandResult(1, "", "permission denied")
        }
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        // The canonical shims already installed — the bridge works, xclip just
        // isn't aliased.
        val active = state as ClipboardFeatureState.Active
        assertFalse("xclip must not be reported as provided", active.aliases.contains("xclip"))
        assertTrue("the other aliases still landed", active.aliases.contains("wl-copy"))
    }

    @Test
    fun enablingWithoutALiveSessionIsUnavailableNotAFailure() = runBlocking {
        // The toggle stays on; §1.110's launch reconcile installs the shims once
        // a guest exists. Reporting Failed here would look broken.
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        assertEquals(ClipboardFeatureState.Unavailable, state)
        assertTrue("no guest command without a guest", guest.execCalls.isEmpty())
    }

    @Test
    fun aFailedShimInstallIsReportedWithTheGuestErrorTail() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses[ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH] =
                CommandResult(1, "", "read-only file system")
        }
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        assertTrue(state is ClipboardFeatureState.Failed)
        assertTrue((state as ClipboardFeatureState.Failed).reason.contains("read-only file system"))
    }

    @Test
    fun enablingSeedsTheMirrorSoTheFirstPasteInAFreshWorkspaceWorks() = runBlocking {
        val clipboard = RecordingHostClipboard(text = "already on the phone")
        supervisor(clipboard = clipboard).reconcileClipboard(ws, enabled = true)

        assertEquals("already on the phone", mirrorText())
    }

    @Test
    fun disablingRemovesTheShimsAndTheMirror() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val clipboard = RecordingHostClipboard(text = "user text")
        val supervisor = supervisor(guest, clipboard)

        supervisor.reconcileClipboard(ws, enabled = true)
        assertEquals("user text", mirrorText())

        val state = supervisor.reconcileClipboard(ws, enabled = false)

        assertEquals(ClipboardFeatureState.Disabled, state)
        assertTrue(guest.execCalls.any { it == ClipboardBridgeProvisioner.uninstallCommand() })
        assertNull(
            "the mirror holds the USER's clipboard — it must not survive the toggle going off",
            mirrorText()
        )
    }

    @Test
    fun aLateWatcherTickCannotResurrectTheMirrorAfterDisable() = runBlocking {
        // Cancellation is cooperative: a poller tick can begin after `clearMirror`
        // bumped the epoch. Such a tick sees the NEW epoch, so the epoch guard
        // alone would let it write the user's clipboard back into a workspace
        // whose Clipboard feature is off. The watcher path checks the enabled set
        // as well; this drives that path directly, deterministically.
        val clipboard = RecordingHostClipboard(text = "user text")
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.reconcileClipboard(ws, enabled = true)
        supervisor.reconcileClipboard(ws, enabled = false)

        supervisor.mirrorOnce(ws, onlyIfEnabled = true)

        assertNull("a disabled workspace must not regain a clipboard mirror", mirrorText())
    }

    @Test
    fun disablingDrainsWhatTheGuestAlreadyCopiedBeforeRemovingTheShims() = runBlocking {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        writeRecord("1", "copied just before the toggle flipped")

        supervisor.reconcileClipboard(ws, enabled = false)

        assertEquals(listOf("copied just before the toggle flipped"), clipboard.writes)
    }

    @Test
    fun disablingIsIdempotentAndSafeWithoutAGuest() = runBlocking {
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        val supervisor = supervisor(guest)

        supervisor.reconcileClipboard(ws, enabled = false)
        supervisor.reconcileClipboard(ws, enabled = false)

        assertTrue("no guest exec attempted — there is no guest", guest.execCalls.isEmpty())
        assertNull(mirrorText())
    }

    @Test
    fun enablingTwiceDoesNotStartASecondWatcher() = runBlocking {
        val supervisor = supervisor()

        supervisor.reconcileClipboard(ws, enabled = true)
        supervisor.reconcileClipboard(ws, enabled = true)

        // Two pollers draining one spool is a resource leak even though the
        // per-workspace drain lock keeps each record applied exactly once.
        assertEquals(1, supervisor.activeWatcherCount(ws))
    }

    // ── UI/UX Phase 6: the alias selection ──

    @Test
    fun onlyTheSelectedAliasesAreInstalled() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val selection = ClipboardBridgeProvisioner.ALIASES.filter { it.name == "xclip" }

        val state = supervisor(guest).reconcileClipboard(ws, enabled = true, aliases = selection)

        assertEquals(listOf("xclip"), (state as ClipboardFeatureState.Active).aliases)
        ClipboardBridgeProvisioner.ALIASES.filter { it.name != "xclip" }.forEach { alias ->
            assertFalse(
                "${alias.name} was deselected and must never be written into the guest",
                guest.execCalls.any { it.contains("/usr/local/bin/${alias.name}") }
            )
        }
    }

    @Test
    fun anEmptySelectionStillInstallsTheCanonicalShims() = runBlocking {
        // "No aliases" is a real choice, not a disabled bridge: the canonical
        // linexa-clip-copy/-paste shims are not optional, so the feature stays
        // Active with an empty alias list.
        val guest = RecordingGuestFeatureHost()
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true, aliases = emptyList())

        val active = state as ClipboardFeatureState.Active
        assertEquals(emptyList<String>(), active.aliases)
        assertTrue(
            guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() }
        )
    }

    @Test
    fun disablingSweepsTheWholeCatalogRatherThanTheCurrentSelection() = runBlocking {
        // The shim that most needs removing is precisely the one the user just
        // deselected — filtering the removal by the current selection would
        // strand every alias ever turned off. Safe to sweep unconditionally
        // because uninstallCommand is content-guarded on the §1.112 marker.
        val guest = RecordingGuestFeatureHost()
        val supervisor = supervisor(guest)

        supervisor.reconcileClipboard(ws, enabled = true, aliases = ClipboardBridgeProvisioner.ALIASES)
        guest.execCalls.clear()
        supervisor.reconcileClipboard(ws, enabled = false, aliases = emptyList())

        assertTrue(guest.execCalls.any { it == ClipboardBridgeProvisioner.uninstallCommand() })
    }

    @Test
    fun theDefaultIsTheWholeCatalogSoAPreviousCallerIsUnchanged() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val state = supervisor(guest).reconcileClipboard(ws, enabled = true)

        assertEquals(
            ClipboardBridgeProvisioner.ALIASES.map { it.name },
            (state as ClipboardFeatureState.Active).aliases
        )
    }

    // ── guest → host: the copy spool ──

    @Test
    fun aCompleteRecordBecomesOneClipboardWriteAndIsDeleted() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        writeRecord("1", "from the guest")

        supervisor.drainOnce(ws)

        assertEquals(listOf("from the guest"), clipboard.writes)
        assertTrue("a consumed record must be deleted or it re-applies forever", records().isEmpty())
    }

    @Test
    fun onlyTheNewestOfSeveralQueuedCopiesReachesTheClipboard() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        writeRecord("a", "older")
        File(outDir, "a${ClipboardBridgeProvisioner.RECORD_EXTENSION}").setLastModified(1_000L)
        writeRecord("b", "newest")
        File(outDir, "b${ClipboardBridgeProvisioner.RECORD_EXTENSION}").setLastModified(2_000L)

        supervisor.drainOnce(ws)

        // A clipboard holds one value: writing each record in turn would flash
        // intermediate content through the system clipboard (and any clipboard
        // history UI) for no benefit.
        assertEquals(listOf("newest"), clipboard.writes)
        assertTrue("every record is still consumed", records().isEmpty())
    }

    @Test
    fun anInFlightTempRecordIsNeverRead() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        // The shim writes .tmp then renames; a .tmp is by definition incomplete.
        File(outDir, "1${ClipboardBridgeProvisioner.RECORD_TEMP_EXTENSION}").writeBytes("half".toByteArray())

        supervisor.drainOnce(ws)

        assertTrue(clipboard.writes.isEmpty())
        assertEquals(1, records().size)
    }

    @Test
    fun anUnusableRecordIsDroppedRatherThanRetriedForever() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        File(outDir, "1${ClipboardBridgeProvisioner.RECORD_EXTENSION}").writeBytes(ByteArray(0))

        supervisor.drainOnce(ws)

        assertTrue(clipboard.writes.isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun anOversizedRecordIsDroppedWithoutBeingRead() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        File(outDir, "1${ClipboardBridgeProvisioner.RECORD_EXTENSION}")
            .writeBytes(ByteArray(ClipboardBridgeProvisioner.MAX_CLIP_BYTES + 1) { 'x'.code.toByte() })

        supervisor.drainOnce(ws)

        assertTrue("a guest must not be able to make the host allocate", clipboard.writes.isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun guestTextIsSanitizedBeforeItReachesTheHostClipboard() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        writeRecord("1", "safe\u001B[31m\u0007text\nsecond line")

        supervisor.drainOnce(ws)

        // The ESC/BEL bytes are gone (they'd fire wherever the user pastes), but
        // the newline is kept — a clipboard that mangles a copied snippet is
        // useless. The "[31m" tail survives as ordinary printable text.
        assertEquals(listOf("safe[31mtext\nsecond line"), clipboard.writes)
    }

    @Test
    fun aRefusedClipboardWriteDoesNotLeaveTheRecordToLoopForever() {
        val clipboard = RecordingHostClipboard().apply {
            writeThrows = SecurityException("clipboard denied")
        }
        val supervisor = supervisor(clipboard = clipboard)
        writeRecord("1", "one")

        supervisor.drainOnce(ws)

        assertTrue(records().isEmpty())
    }

    @Test
    fun aMissingSpoolDirectoryIsNotAnError() {
        val supervisor = supervisor()
        assertTrue(outDir.deleteRecursively())

        supervisor.drainOnce(ws)
        // A guest that never copied must cost nothing and log no failure.
    }

    @Test
    fun oneDrainIsBoundedSoARunawayProducerCannotMonopolizeThePoll() {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        repeat(100) { i -> writeRecord("r$i", "c$i") }

        supervisor.drainOnce(ws)

        assertFalse("the remainder stays for the next tick", records().isEmpty())
    }

    @Test
    fun oneRecordIsAppliedExactlyOnceEvenWhileThePollerIsRunning() = runBlocking {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        supervisor.reconcileClipboard(ws, enabled = true)

        writeRecord("1", "once")
        repeat(20) { supervisor.drainOnce(ws) }

        assertEquals(listOf("once"), clipboard.writes)
    }

    // ── host → guest: the paste mirror ──

    @Test
    fun aHostCopyBecomesThePasteMirrorsContent() {
        val clipboard = RecordingHostClipboard(text = "copied on the phone")
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.mirrorOnce(ws)

        // This file is literally what `linexa-clip-paste` cats.
        assertEquals("copied on the phone", mirrorText())
    }

    @Test
    fun aClipboardChangeFansOutToEveryEnabledWorkspace() = runBlocking {
        val clipboard = RecordingHostClipboard(text = "first")
        val supervisor = supervisor(clipboard = clipboard)
        supervisor.reconcileClipboard(ws, enabled = true)

        clipboard.userCopies("second")

        // Driven through the real listener the supervisor registered.
        assertEquals("second", mirrorText())
    }

    @Test
    fun theChangeListenerIsRegisteredOnlyWhileAWorkspaceHasTheBridgeEnabled() = runBlocking {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.reconcileClipboard(ws, enabled = true)
        assertTrue("must observe while enabled", clipboard.listener != null)

        supervisor.reconcileClipboard(ws, enabled = false)
        assertNull("must stop observing once nothing is enabled", clipboard.listener)
    }

    @Test
    fun aNullClipboardReadNeverClearsAnExistingMirror() {
        // The API 29+ focus restriction makes a null read NORMAL. Blanking the
        // guest's paste buffer because Android declined to answer would turn a
        // working paste into an empty one.
        val clipboard = RecordingHostClipboard(text = "user text")
        val supervisor = supervisor(clipboard = clipboard)
        supervisor.mirrorOnce(ws)
        assertEquals("user text", mirrorText())

        clipboard.readable = false
        supervisor.mirrorOnce(ws)

        assertEquals("a stale mirror beats an empty one", "user text", mirrorText())
    }

    @Test
    fun aThrowingClipboardReadIsSwallowedAndLeavesTheMirrorAlone() {
        val clipboard = RecordingHostClipboard(text = "user text")
        val supervisor = supervisor(clipboard = clipboard)
        supervisor.mirrorOnce(ws)

        clipboard.readThrows = SecurityException("no focus")
        supervisor.mirrorOnce(ws)

        assertEquals("user text", mirrorText())
    }

    @Test
    fun anUnchangedClipboardIsNotRewritten() {
        val clipboard = RecordingHostClipboard(text = "same")
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.mirrorOnce(ws)
        val firstModified = mirror.lastModified()
        mirror.setLastModified(firstModified - 10_000L)
        val marker = mirror.lastModified()

        supervisor.mirrorOnce(ws)

        assertEquals("an unchanged clipboard must cost no file write", marker, mirror.lastModified())
    }

    @Test
    fun hostTextIsBoundedOnTheWayIntoTheMirror() {
        val huge = "q".repeat(ClipboardBridgeProvisioner.MAX_CLIP_CHARS + 1_000)
        val clipboard = RecordingHostClipboard(text = huge)
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.mirrorOnce(ws)

        assertEquals(
            "a huge host clipboard must not be written into every running workspace",
            ClipboardBridgeProvisioner.MAX_CLIP_CHARS,
            mirrorText()?.length
        )
    }

    @Test
    fun theMirrorIsNotLeftHalfWrittenForAConcurrentPaste() {
        // The commit is a rename, mirroring the guest shim's own discipline: a
        // paste is a `cat`, so a guest must never observe a partial mirror.
        val clipboard = RecordingHostClipboard(text = "complete")
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.mirrorOnce(ws)

        val stray = mirror.parentFile?.listFiles()
            ?.filter { it.name.endsWith(ClipboardBridgeProvisioner.RECORD_TEMP_EXTENSION) }
            .orEmpty()
        assertTrue("no temp file may be left behind: $stray", stray.isEmpty())
        assertEquals("complete", mirrorText())
    }

    @Test
    fun anEmptyHostClipboardWritesNoMirrorAtAll() {
        val clipboard = RecordingHostClipboard(text = null)
        val supervisor = supervisor(clipboard = clipboard)

        supervisor.mirrorOnce(ws)

        assertNull("an empty clipboard is not an empty mirror", mirrorText())
    }

    // ── host-state release (the §1.110 teardown path) ──

    @Test
    fun releasingHostStateDeletesTheMirrorWithoutTouchingTheGuest() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val clipboard = RecordingHostClipboard(text = "user text")
        val supervisor = supervisor(guest, clipboard)
        supervisor.reconcileClipboard(ws, enabled = true)
        guest.execCalls.clear()

        supervisor.releaseHostState(ws)

        // By teardown the guest may already be gone, so an uninstall would only
        // log a failure — but the user's clipboard text must not be left in a
        // stopped workspace's rootfs.
        assertTrue(guest.execCalls.isEmpty())
        assertNull(mirrorText())
    }

    @Test
    fun releasingHostStateStopsObservingTheClipboard() = runBlocking {
        val clipboard = RecordingHostClipboard()
        val supervisor = supervisor(clipboard = clipboard)
        supervisor.reconcileClipboard(ws, enabled = true)

        supervisor.releaseHostState(ws)

        assertNull(clipboard.listener)
        assertEquals(0, supervisor.activeWatcherCount(ws))
    }

    @Test
    fun releasingHostStateIsIdempotent() = runBlocking {
        val supervisor = supervisor()
        supervisor.reconcileClipboard(ws, enabled = true)

        supervisor.releaseHostState(ws)
        supervisor.releaseHostState(ws)

        assertEquals(0, supervisor.activeWatcherCount(ws))
    }

    // ── scope guard ──

    @Test
    fun theFeatureNeverStartsAGuestDaemonOrTouchesAnotherFeature() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val supervisor = supervisor(guest)

        supervisor.reconcileClipboard(ws, enabled = true)
        supervisor.reconcileClipboard(ws, enabled = false)

        // A clipboard shim is one-shot by nature — no daemon, so nothing to leak
        // if the app dies.
        assertTrue(guest.startedDaemons.isEmpty())
        assertTrue(guest.stoppedDaemons.isEmpty())
        guest.execCalls.forEach { command ->
            assertFalse(command, command.contains("sshd"))
            assertFalse(command, command.contains("notify-send"))
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
        }
    }

    private companion object {
        /** Distinct rootfs per supervisor so a test cannot see another's files. */
        var counter = 0
    }
}
