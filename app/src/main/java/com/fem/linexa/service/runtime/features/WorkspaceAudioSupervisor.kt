package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * §1.113 — the guest-side consumer of the `audio` workspace feature toggle: sound
 * produced inside a workspace comes out of the phone's speaker.
 *
 * **Reconciliation, not event handling** — the same contract as
 * [WorkspaceFeatureSupervisor] (§1.109), [WorkspaceNotificationSupervisor]
 * (§1.111) and [WorkspaceClipboardSupervisor] (§1.112). [reconcileAudio] takes the
 * *desired* state and converges to it, so it is correct on a toggle tap, on
 * workspace launch (§1.110), and after a `config.yaml` import that pre-enabled the
 * feature; calling it twice is harmless.
 *
 * **The pump is started before the guest is pointed at the FIFO, and that order
 * is not incidental.** Opening a FIFO for writing blocks until a reader exists,
 * so installing an ALSA config that targets an unread FIFO would hang the first
 * program the user tried to play something with. [enable] therefore runs
 * `mkfifo`, starts the pump (which blocks in [AudioSource.open] waiting for a
 * writer), and only then installs the guest-side routing.
 *
 * **A guest writer is never left blocked.** If Android will not give a playback
 * stream, the pump still *drains* the FIFO and discards it. Silent audio is a
 * disappointment; a `mpv` frozen forever inside a `write()` looks like the
 * workspace itself has hung, which is much worse.
 *
 * **It cannot touch the Workspace lifecycle.** Its collaborators are
 * [GuestFeatureHost] (exec only — §1.109's scope limit), [AudioSource] (a FIFO
 * read) and [AudioSink] (playback). There is no path from here to a pty, a
 * terminal, or a session start/stop (§1.2: the graph owns that), and no path to
 * audio *capture* — `mic` is out of scope (§3.3).
 *
 * **Fail-open.** Every failure becomes an [AudioFeatureState] and a log line;
 * nothing throws except [CancellationException].
 */
class WorkspaceAudioSupervisor(
    private val host: GuestFeatureHost,
    private val sink: AudioSink,
    private val scope: CoroutineScope,
    /**
     * Resolves a workspace's PCM source. Injected rather than derived so this
     * class needs no Android `Application` and stays JVM-testable against a fake
     * that can end a stream on demand (real FIFO semantics are the one thing a
     * temp file cannot reproduce).
     */
    private val sourceFor: (String) -> AudioSource,
    /** Backoff before re-opening after the source reported "nothing to read". */
    private val reopenDelayMillis: Long = DEFAULT_REOPEN_DELAY_MS
) {

    /** Live pump jobs, keyed by workspace. Guarded by [stateLock]. */
    private val pumps = mutableMapOf<String, Job>()

    private val stateLock = Any()

    /**
     * Converge [workspaceId]'s audio bridge to [enabled].
     *
     * Enabling creates the FIFO, starts the pump and points the guest's audio
     * stack at it; disabling stops the pump and removes the guest-side routing.
     */
    suspend fun reconcileAudio(
        workspaceId: String,
        enabled: Boolean
    ): AudioFeatureState = try {
        if (!enabled) disable(workspaceId) else enable(workspaceId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Audio reconcile failed for $workspaceId (enabled=$enabled)", e)
        AudioFeatureState.Failed(e.message ?: "Unknown error")
    }

    private suspend fun enable(workspaceId: String): AudioFeatureState {
        if (!host.hasActiveSession(workspaceId)) {
            // Not a failure — the toggle stays on and §1.110's launch reconcile
            // brings the bridge up once a guest exists.
            return AudioFeatureState.Unavailable
        }

        // Probed once, before anything is created in the guest: a device that
        // will not give this app a playback stream cannot play a workspace's
        // audio, and saying so is better than installing a bridge that leads
        // nowhere. The probe track is opened and immediately released; no PCM is
        // written, so it makes no sound.
        val playable = try {
            sink.open()?.also { runCatching { it.close() } } != null
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: audio output probe failed: ${e.message}")
            false
        }
        if (!playable) {
            Logger.w(TAG, "$workspaceId: this device gave no playback stream — audio bridge not installed")
            return AudioFeatureState.Unsupported
        }

        val prepare = host.execCommand(workspaceId, AudioBridgeProvisioner.prepareCommand())
        if (prepare.exitCode != 0) {
            // The likeliest cause is `mkfifo` being refused on the rootfs's
            // filesystem, which is exactly the unverified assumption §1.113
            // records — so it must fail loudly rather than half-work.
            val detail = prepare.error.ifBlank { prepare.output }.trim().take(DETAIL_CHARS)
            Logger.e(TAG, "audio FIFO creation failed for $workspaceId (rc=${prepare.exitCode}): $detail")
            return AudioFeatureState.Failed("Could not create the audio channel: $detail")
        }

        // Before the guest routing: see the class kdoc — a writer opening a FIFO
        // with no reader blocks, and that would look like a frozen program.
        startPump(workspaceId)

        val mechanisms = mutableListOf<String>()

        // ALSA: covers everything that talks ALSA directly, using only the
        // `file` plugin built into alsa-lib. Nothing is installed.
        val alsaInstalled = runCatching {
            host.execCommand(workspaceId, AudioBridgeProvisioner.installAlsaCommand())
            val verify = host.execCommand(workspaceId, AudioBridgeProvisioner.verifyAlsaCommand())
            AudioBridgeProvisioner.probeSucceeded(verify.output, AudioBridgeProvisioner.ALSA_OK)
        }.getOrElse {
            Logger.w(TAG, "$workspaceId: ALSA bridge config failed: ${it.message}")
            false
        }
        if (alsaInstalled) mechanisms += MECHANISM_ALSA

        // PulseAudio: additive, and only when a server is ALREADY running. This
        // deliberately installs no package — the ALSA path already works, so
        // pulling in pulse's dependency tree to duplicate it would be a large
        // unrequested change to the user's workspace.
        val pulseWired = runCatching {
            val probe = host.execCommand(workspaceId, AudioBridgeProvisioner.PACTL_PROBE_COMMAND)
            if (!AudioBridgeProvisioner.probeSucceeded(probe.output, AudioBridgeProvisioner.PULSE_OK)) {
                false
            } else {
                host.execCommand(workspaceId, AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND).exitCode == 0
            }
        }.getOrElse {
            Logger.w(TAG, "$workspaceId: PulseAudio sink not wired: ${it.message}")
            false
        }
        if (pulseWired) mechanisms += MECHANISM_PULSE

        if (mechanisms.isEmpty()) {
            // The FIFO and the pump exist, but nothing in the guest routes into
            // them — the only way to get here is a workspace that already had its
            // own /etc/asound.conf (which is deliberately never overwritten) and
            // no running pulse server. Reporting Active would be a lie, and
            // reporting Failed would blame the bridge for the user's own working
            // configuration, so this has its own state.
            Logger.w(TAG, "$workspaceId: audio bridge is up but the guest has its own audio configuration")
            return AudioFeatureState.Conflict
        }

        Logger.i(TAG, "Audio bridge enabled for $workspaceId (${mechanisms.joinToString()})")
        return AudioFeatureState.Active(mechanisms)
    }

    /**
     * Stop the pump and remove the guest-side routing. Idempotent — correct for
     * "already off", which is what makes it safe on every teardown and what reaps
     * a bridge left behind by a toggle flipped off while the app was dead.
     */
    private suspend fun disable(workspaceId: String): AudioFeatureState {
        stopPump(workspaceId)
        if (host.hasActiveSession(workspaceId)) {
            host.execCommand(workspaceId, AudioBridgeProvisioner.uninstallCommand())
        }
        Logger.i(TAG, "Audio bridge disabled for $workspaceId (routing removed)")
        return AudioFeatureState.Disabled
    }

    /**
     * Release [workspaceId]'s host-side state without touching the guest.
     *
     * Called from the launch path's teardown (§1.110's `onSessionStopping`), where
     * the guest may already be gone. Only the pump has to stop.
     *
     * Unlike §1.112's clipboard mirror, nothing is deleted here: a FIFO holds no
     * data at rest — bytes exist only in the kernel buffer while both ends are
     * open — so there is no leftover copy of anything to clean up, and the FIFO
     * is reused by the next session.
     */
    fun releaseHostState(workspaceId: String) {
        stopPump(workspaceId)
    }

    private fun stopPump(workspaceId: String) {
        val job = synchronized(stateLock) { pumps.remove(workspaceId) }
        job?.cancel()
    }

    /**
     * Start (or keep) the pump for [workspaceId]. Idempotent: an already-running
     * pump is left alone rather than replaced, so a repeated enable cannot end up
     * with two readers competing for the same FIFO — which would not merely waste
     * a thread, it would split the guest's PCM stream between them and play
     * garbage.
     */
    private fun startPump(workspaceId: String) {
        synchronized(stateLock) {
            val existing = pumps[workspaceId]
            if (existing != null && existing.isActive) return
            pumps[workspaceId] = scope.launch(Dispatchers.IO) {
                Logger.i(TAG, "Audio pump started for $workspaceId")
                val source = try {
                    sourceFor(workspaceId)
                } catch (e: Exception) {
                    Logger.w(TAG, "$workspaceId: could not resolve the audio source: ${e.message}")
                    return@launch
                }
                val buffer = ByteArray(AudioBridgeProvisioner.READ_BUFFER_BYTES)
                while (isActive) {
                    // Blocks until a guest program opens the FIFO for writing.
                    val stream = try {
                        source.open()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.d(TAG, "$workspaceId: audio source open failed: ${e.message}")
                        null
                    }
                    if (stream == null) {
                        delay(reopenDelayMillis)
                        continue
                    }
                    // EOF means "that program finished", not "the bridge is
                    // done" — so this loops and waits for the next writer.
                    stream.use { playUntilEof(workspaceId, it, buffer) }
                }
            }
        }
    }

    /**
     * Play one writer's stream to EOF.
     *
     * The sink is opened *per writer* rather than held open for the lifetime of
     * the bridge: an `AudioTrack` sitting idle keeps an output route (and on some
     * devices a wakelock-like cost) alive for a workspace that may play nothing
     * for hours.
     *
     * If no sink is available the bytes are **read and discarded**. That is the
     * deliberate choice recorded in the class kdoc: the alternative leaves the
     * guest's `write()` blocked forever, which presents to the user as the whole
     * workspace hanging.
     */
    private fun playUntilEof(workspaceId: String, stream: InputStream, buffer: ByteArray) {
        val playback = try {
            sink.open()
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: playback stream unavailable: ${e.message}")
            null
        }
        if (playback == null) {
            Logger.w(TAG, "$workspaceId: draining guest audio with no playback stream (guest must not block)")
        }
        try {
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (e: Exception) {
                    Logger.d(TAG, "$workspaceId: audio read ended: ${e.message}")
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                if (playback == null) continue
                val written = try {
                    playback.write(buffer, 0, read)
                } catch (e: Exception) {
                    Logger.w(TAG, "$workspaceId: audio write failed: ${e.message}")
                    -1
                }
                if (written < 0) {
                    // A dead track must not silently swallow the rest of the
                    // stream: close it and keep draining so the guest continues.
                    Logger.w(TAG, "$workspaceId: playback stream failed — draining the rest")
                    runCatching { playback.close() }
                    return drainRest(stream, buffer)
                }
            }
        } finally {
            playback?.let { runCatching { it.close() } }
        }
    }

    /** Consume the remainder of a writer's stream, discarding it. */
    private fun drainRest(stream: InputStream, buffer: ByteArray) {
        while (true) {
            val read = try {
                stream.read(buffer)
            } catch (e: Exception) {
                return
            }
            if (read < 0) return
        }
    }

    /**
     * Number of live pumps for [workspaceId] — 0 or 1, never more. Visible for
     * tests: "enabling twice does not start a second reader" is a resource
     * invariant that cannot be observed through playback.
     */
    internal fun activePumpCount(workspaceId: String): Int =
        synchronized(stateLock) { pumps[workspaceId]?.takeIf { it.isActive } }?.let { 1 } ?: 0

    private companion object {
        const val TAG = "AudioSupervisor"

        /** Bounded length of a guest error tail carried into a UI reason. */
        const val DETAIL_CHARS = 300

        /**
         * Backoff after the source reports nothing to read (usually: the FIFO
         * does not exist yet). Long enough not to spin, short enough that a
         * workspace whose guest-side prepare lands a moment later starts playing
         * without the user noticing a delay.
         */
        const val DEFAULT_REOPEN_DELAY_MS = 500L

        const val MECHANISM_ALSA = "ALSA"
        const val MECHANISM_PULSE = "PulseAudio"
    }
}

/**
 * §1.113 — observable state of a workspace's Audio feature. Separate from the
 * persisted toggle for the same reason [SshFeatureState],
 * [NotificationFeatureState] and [ClipboardFeatureState] are (§1.16's rule at
 * feature scope): the toggle is the user's *intent*, this is the actual condition.
 */
sealed interface AudioFeatureState {

    /** The toggle is off: no routing in the guest, nothing reading the FIFO. */
    data object Disabled : AudioFeatureState

    /**
     * The toggle is on but the workspace session isn't running, so there is no
     * guest to create the channel in. Not an error — §1.110's launch reconcile
     * handles it once a session exists.
     */
    data object Unavailable : AudioFeatureState

    /**
     * Android would not provide a playback stream on this device, so nothing was
     * installed in the guest. Distinct from [Failed]: the bridge did not break,
     * the device simply cannot play a workspace's audio.
     */
    data object Unsupported : AudioFeatureState

    /**
     * The bridge is up, but the workspace has its own audio configuration (an
     * `/etc/asound.conf` Linexa did not write, which is deliberately never
     * overwritten) and no running PulseAudio server — so nothing inside the guest
     * currently routes into it. Its own state because neither [Active] (a lie)
     * nor [Failed] (blaming the bridge for the user's working config) is honest.
     */
    data object Conflict : AudioFeatureState

    /**
     * Audio is bridged. [mechanisms] are the guest-side routes that were actually
     * wired (`ALSA`, `PulseAudio`) — genuinely variable per workspace, so the UI
     * can say what works instead of promising both.
     */
    data class Active(val mechanisms: List<String> = emptyList()) : AudioFeatureState

    /** Enabling failed; [reason] is user-facing. */
    data class Failed(val reason: String) : AudioFeatureState
}
