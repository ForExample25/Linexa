package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.113: [WorkspaceAudioSupervisor] — the host half of the Audio feature, driven
 * against the fake guest/source/sink seams.
 *
 * The behaviors worth protecting here are not "does it play" but the three ways
 * this bridge can hurt the *workspace* rather than merely fail:
 *
 * 1. **Ordering.** The pump must be reading before the guest is pointed at the
 *    FIFO, because opening a FIFO for writing blocks until a reader exists.
 * 2. **Never blocking the guest.** With no playback stream the pump must still
 *    drain, or a guest `write()` hangs forever and the workspace looks frozen.
 * 3. **Exactly one reader.** Two pumps on one FIFO would split the PCM stream
 *    between them and play garbage rather than just wasting a thread.
 */
class WorkspaceAudioSupervisorTest {

    private val ws = "ws-1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun supervisor(
        guest: RecordingGuestFeatureHost = RecordingGuestFeatureHost(),
        sink: RecordingAudioSink = RecordingAudioSink(),
        source: ScriptedAudioSource = ScriptedAudioSource()
    ) = WorkspaceAudioSupervisor(
        host = guest,
        sink = sink,
        scope = scope,
        sourceFor = { source },
        reopenDelayMillis = 10L
    )

    /** Guest scripted so both the ALSA verify and the pulse probe succeed. */
    private fun bothMechanisms() = RecordingGuestFeatureHost().apply {
        responses[AudioBridgeProvisioner.ALSA_OK] =
            CommandResult(0, "${AudioBridgeProvisioner.ALSA_OK}\n", "")
        responses["pactl info"] =
            CommandResult(0, "${AudioBridgeProvisioner.PULSE_OK}\n", "")
    }

    /** Guest scripted as a plain rootfs: ALSA config lands, no pulse server. */
    private fun alsaOnly() = RecordingGuestFeatureHost().apply {
        responses[AudioBridgeProvisioner.ALSA_OK] =
            CommandResult(0, "${AudioBridgeProvisioner.ALSA_OK}\n", "")
        responses["pactl info"] = CommandResult(1, "", "")
    }

    /** Waits for [predicate], so a pump running on another thread is assertable. */
    private fun await(what: String, timeoutMs: Long = 2_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    // ── enable / disable ──

    @Test
    fun enablingCreatesTheFifoAndWiresTheGuest() = runBlocking {
        val guest = alsaOnly()
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertTrue("expected Active, was $state", state is AudioFeatureState.Active)
        assertTrue(
            "must create the FIFO: ${guest.execCalls}",
            guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() }
        )
        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.installAlsaCommand() })
    }

    @Test
    fun theFifoIsCreatedAndReadBeforeTheGuestIsPointedAtIt() = runBlocking {
        // The ordering bug this pins is not subtle in effect: ALSA's file plugin
        // opens its target O_WRONLY, which BLOCKS on a FIFO with no reader — so
        // installing the config first would hang the first program the user played
        // something with, and it would look like the workspace had frozen.
        val guest = alsaOnly()
        val source = ScriptedAudioSource()
        val supervisor = supervisor(guest = guest, source = source)

        supervisor.reconcileAudio(ws, enabled = true)

        val prepareAt = guest.execCalls.indexOf(AudioBridgeProvisioner.prepareCommand())
        val installAt = guest.execCalls.indexOf(AudioBridgeProvisioner.installAlsaCommand())
        assertTrue("prepare must precede the ALSA install", prepareAt in 0 until installAt)
        // And the pump must already be trying to read by the time the guest is
        // wired — that is the half that actually unblocks a guest writer.
        await("the pump to open the source") { source.openCount > 0 }
    }

    @Test
    fun enablingReportsBothMechanismsWhenPulseIsAlreadyRunning() = runBlocking {
        val guest = bothMechanisms()
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertEquals(
            listOf("ALSA", "PulseAudio"),
            (state as AudioFeatureState.Active).mechanisms
        )
        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND })
    }

    @Test
    fun aGuestWithoutPulseGetsTheAlsaPathOnlyAndNoPulseCommands() = runBlocking {
        val guest = alsaOnly()
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertEquals(listOf("ALSA"), (state as AudioFeatureState.Active).mechanisms)
        // Probing is fine; loading a module into a server that isn't there is not.
        assertFalse(guest.execCalls.any { it == AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND })
    }

    @Test
    fun enablingWithoutALiveSessionIsUnavailableNotAFailure() = runBlocking {
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        // The toggle stays on; §1.110's launch reconcile brings it up later.
        assertEquals(AudioFeatureState.Unavailable, state)
        assertTrue("nothing may run in a dead guest", guest.execCalls.isEmpty())
    }

    @Test
    fun aFailedFifoCreationFailsLoudlyInsteadOfHalfWorking() = runBlocking {
        // mkfifo on app-private storage is §1.113's explicitly unverified
        // assumption, so this is the path that must report rather than pretend.
        val guest = alsaOnly().apply {
            responses["mkfifo"] = CommandResult(1, "", "mkfifo: Operation not permitted")
        }
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertTrue("expected Failed, was $state", state is AudioFeatureState.Failed)
        assertTrue((state as AudioFeatureState.Failed).reason.contains("not permitted"))
        // And nothing was pointed at a channel that does not exist.
        assertFalse(guest.execCalls.any { it == AudioBridgeProvisioner.installAlsaCommand() })
    }

    @Test
    fun aDeviceWithNoOutputIsUnsupportedAndTouchesTheGuestNotAtAll() = runBlocking {
        val guest = alsaOnly()
        val state = supervisor(guest = guest, sink = RecordingAudioSink(available = false))
            .reconcileAudio(ws, enabled = true)

        // Distinct from Failed: the bridge didn't break, the device can't play.
        assertEquals(AudioFeatureState.Unsupported, state)
        assertTrue("no bridge may be installed for nothing: ${guest.execCalls}", guest.execCalls.isEmpty())
    }

    @Test
    fun theProbeTrackIsReleasedSoItDoesNotHoldAnOutputRoute() = runBlocking {
        val sink = RecordingAudioSink()
        supervisor(guest = alsaOnly(), sink = sink).reconcileAudio(ws, enabled = true)

        // The probe opens a track to learn whether the device will give one; a
        // track left open would keep an output route alive for a workspace that
        // may never play anything.
        assertTrue("the probe stream must be closed", sink.closedCount >= 1)
    }

    @Test
    fun aWorkspaceWithItsOwnAlsaConfigReportsConflictRatherThanSuccess() = runBlocking {
        // install exits 0 but leaves the user's config alone, so the verify finds
        // no marker; with no pulse server there is then nothing routing into the
        // bridge. Active would be a lie and Failed would blame the bridge for the
        // user's working setup.
        val guest = RecordingGuestFeatureHost().apply {
            responses[AudioBridgeProvisioner.ALSA_OK] = CommandResult(1, "", "")
            responses["pactl info"] = CommandResult(1, "", "")
        }
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertEquals(AudioFeatureState.Conflict, state)
    }

    @Test
    fun aUserConfigPlusARunningPulseServerStillWorksThroughPulse() = runBlocking {
        // The interesting half of Conflict: pulse alone is a real, working route,
        // so this must NOT be reported as a conflict.
        val guest = RecordingGuestFeatureHost().apply {
            responses[AudioBridgeProvisioner.ALSA_OK] = CommandResult(1, "", "")
            responses["pactl info"] = CommandResult(0, "${AudioBridgeProvisioner.PULSE_OK}\n", "")
        }
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertEquals(listOf("PulseAudio"), (state as AudioFeatureState.Active).mechanisms)
    }

    @Test
    fun disablingRemovesTheGuestRoutingAndStopsThePump() = runBlocking {
        val guest = alsaOnly()
        val supervisor = supervisor(guest)

        supervisor.reconcileAudio(ws, enabled = true)
        await("the pump to start") { supervisor.activePumpCount(ws) == 1 }

        val state = supervisor.reconcileAudio(ws, enabled = false)

        assertEquals(AudioFeatureState.Disabled, state)
        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.uninstallCommand() })
        assertEquals(0, supervisor.activePumpCount(ws))
    }

    @Test
    fun disablingIsIdempotent() = runBlocking {
        val guest = alsaOnly()
        val supervisor = supervisor(guest)

        assertEquals(AudioFeatureState.Disabled, supervisor.reconcileAudio(ws, enabled = false))
        assertEquals(AudioFeatureState.Disabled, supervisor.reconcileAudio(ws, enabled = false))
        assertEquals(0, supervisor.activePumpCount(ws))
    }

    @Test
    fun disablingWithADeadGuestDoesNotRunAGuestCommand() = runBlocking {
        val guest = RecordingGuestFeatureHost(sessionAlive = false)
        supervisor(guest).reconcileAudio(ws, enabled = false)

        assertTrue(guest.execCalls.isEmpty())
    }

    @Test
    fun aThrowingGuestBecomesFailedRatherThanAnEscapingException() = runBlocking {
        val guest = alsaOnly().apply { execThrows = IllegalStateException("proot host died") }
        val state = supervisor(guest).reconcileAudio(ws, enabled = true)

        assertTrue("expected Failed, was $state", state is AudioFeatureState.Failed)
    }

    // ── the pump: playback ──

    @Test
    fun pcmWrittenByTheGuestReachesThePlaybackStream() = runBlocking {
        val sink = RecordingAudioSink()
        val source = ScriptedAudioSource()
        val pcm = ByteArray(1024) { (it % 251).toByte() }
        source.emit(pcm)

        supervisor(guest = alsaOnly(), sink = sink, source = source)
            .reconcileAudio(ws, enabled = true)

        await("the PCM to be played") { sink.played.size >= pcm.size }
        assertTrue(sink.played.contentEquals(pcm))
    }

    @Test
    fun aSecondProgramPlaysAfterTheFirstOneEnds() = runBlocking {
        // EOF on the FIFO means "that program finished", not "the bridge is done" —
        // if the pump exited at EOF, only the first sound a workspace ever made
        // would be audible.
        val sink = RecordingAudioSink()
        val source = ScriptedAudioSource()
        source.emit(ByteArray(64) { 1 })

        supervisor(guest = alsaOnly(), sink = sink, source = source)
            .reconcileAudio(ws, enabled = true)
        await("the first program") { sink.played.size >= 64 }

        source.emit(ByteArray(64) { 2 })
        await("the second program") { sink.played.size >= 128 }

        assertTrue(sink.played.drop(64).all { it == 2.toByte() })
    }

    @Test
    fun eachProgramGetsItsOwnPlaybackStreamAndTheOldOneIsReleased() = runBlocking {
        val sink = RecordingAudioSink()
        val source = ScriptedAudioSource()
        source.emit(ByteArray(32))

        supervisor(guest = alsaOnly(), sink = sink, source = source)
            .reconcileAudio(ws, enabled = true)
        await("the first stream to be closed") { sink.closedCount >= 2 } // probe + first

        source.emit(ByteArray(32))
        // An AudioTrack held open for an idle workspace keeps an output route
        // alive for hours, so a stream per writer that is then released is the
        // resource contract.
        await("the second stream") { sink.streams.size >= 3 }
        await("every finished stream released") { sink.closedCount >= 3 }
    }

    @Test
    fun aGuestWriterIsDrainedEvenWhenThereIsNoPlaybackStream() = runBlocking {
        // The failure mode this prevents: the guest's write() blocks forever and
        // the user sees the whole workspace as hung. Silence is a disappointment;
        // a frozen mpv is a bug report about Linexa.
        val sink = RecordingAudioSink()
        val source = ScriptedAudioSource()
        val supervisor = supervisor(guest = alsaOnly(), sink = sink, source = source)

        supervisor.reconcileAudio(ws, enabled = true)
        await("the pump to start") { supervisor.activePumpCount(ws) == 1 }

        // The device loses its output AFTER the bridge came up.
        sink.available = false
        source.emit(ByteArray(4096))

        // The stream must be consumed to EOF, which is observable as the pump
        // coming back for the next writer.
        val opensBefore = source.openCount
        await("the drained stream to be consumed") { source.openCount > opensBefore }
        assertTrue("nothing should have played", sink.played.isEmpty())
    }

    @Test
    fun aPlaybackStreamThatDiesMidWayDoesNotStrandTheGuest() = runBlocking {
        val sink = object : RecordingAudioSink() {
            override fun open(): AudioStream? =
                super.open()?.also { (it as RecordingAudioStream).failWrites = true }
        }
        val source = ScriptedAudioSource()
        val supervisor = supervisor(guest = alsaOnly(), sink = sink, source = source)
        supervisor.reconcileAudio(ws, enabled = true)
        await("the pump to start") { supervisor.activePumpCount(ws) == 1 }

        source.emit(ByteArray(8192))
        val opensBefore = source.openCount

        // A dead track must not silently swallow the rest of the stream: the pump
        // drains it and comes back, so the guest's write() completes.
        await("the pump to finish the stream") { source.openCount > opensBefore }
        assertTrue(supervisor.activePumpCount(ws) == 1)
    }

    @Test
    fun aSourceThatIsNotReadyIsRetriedRatherThanTreatedAsAFailure() = runBlocking {
        val source = ScriptedAudioSource().apply { unavailable = true }
        val supervisor = supervisor(guest = alsaOnly(), source = source)

        val state = supervisor.reconcileAudio(ws, enabled = true)

        // "The FIFO isn't there yet" is normal at launch, not an error.
        assertTrue(state is AudioFeatureState.Active)
        await("the pump to retry") { source.openCount >= 2 }
    }

    // ── the pump: exactly one reader ──

    @Test
    fun enablingTwiceDoesNotStartASecondReaderOnTheSameFifo() = runBlocking {
        val supervisor = supervisor(alsaOnly())

        supervisor.reconcileAudio(ws, enabled = true)
        supervisor.reconcileAudio(ws, enabled = true)

        // Two readers would not merely waste a thread — they would split the
        // guest's PCM stream between them and play garbage.
        assertEquals(1, supervisor.activePumpCount(ws))
    }

    @Test
    fun releasingHostStateStopsThePumpWithoutTouchingTheGuest() = runBlocking {
        val guest = alsaOnly()
        val supervisor = supervisor(guest)
        supervisor.reconcileAudio(ws, enabled = true)
        await("the pump to start") { supervisor.activePumpCount(ws) == 1 }
        val callsBefore = guest.execCalls.size

        supervisor.releaseHostState(ws)

        assertEquals(0, supervisor.activePumpCount(ws))
        // Teardown may run after the guest is already gone; a guest command there
        // would only log a failure.
        assertEquals(callsBefore, guest.execCalls.size)
    }

    @Test
    fun releasingHostStateIsIdempotentAndSafeBeforeAnyEnable() {
        val supervisor = supervisor(alsaOnly())
        supervisor.releaseHostState(ws)
        supervisor.releaseHostState(ws)
        assertEquals(0, supervisor.activePumpCount(ws))
    }

    @Test
    fun eachWorkspaceGetsItsOwnPump() = runBlocking {
        val supervisor = supervisor(alsaOnly())

        supervisor.reconcileAudio("ws-a", enabled = true)
        supervisor.reconcileAudio("ws-b", enabled = true)

        assertEquals(1, supervisor.activePumpCount("ws-a"))
        assertEquals(1, supervisor.activePumpCount("ws-b"))

        // Stopping one must not stop the other.
        supervisor.releaseHostState("ws-a")
        assertEquals(0, supervisor.activePumpCount("ws-a"))
        assertEquals(1, supervisor.activePumpCount("ws-b"))
    }

    // ── scope guard (§1.109's rule, re-asserted) ──

    @Test
    fun theSupervisorRunsNoLifecycleOrCaptureCommand() = runBlocking {
        val guest = bothMechanisms()
        val supervisor = supervisor(guest)
        supervisor.reconcileAudio(ws, enabled = true)
        supervisor.reconcileAudio(ws, enabled = false)

        guest.execCalls.forEach { command ->
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
            assertFalse(command, command.contains("halt"))
            assertFalse(command, command.contains("Xvnc"))
            // `mic` is out of scope (§3.3) — this seam must not become the place
            // audio capture silently arrives.
            assertFalse(command, command.contains("arecord"))
            assertFalse(command, command.contains("module-pipe-source"))
        }
        // A feature must never be able to start or stop a Workspace session.
        assertTrue(guest.startedDaemons.isEmpty())
        assertTrue(guest.stoppedDaemons.isEmpty())
    }
}
