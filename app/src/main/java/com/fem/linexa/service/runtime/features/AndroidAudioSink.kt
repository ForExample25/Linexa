package com.fem.linexa.service.runtime.features

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.fem.linexa.core.logging.Logger
import javax.inject.Inject

/**
 * §1.113 — the Android half of the Workspace Audio feature.
 *
 * Kept behind [AudioSink] so [WorkspaceAudioSupervisor]'s FIFO/lifecycle logic
 * stays JVM-testable, exactly as [AndroidHostClipboard] does for §1.112.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.113):
 *
 * - **`AudioTrack` in `MODE_STREAM`, not a `MediaPlayer` or an `AudioTrack` per
 *   buffer.** The bridge receives an endless stream of raw PCM with no container
 *   and no known length, which is precisely what `MODE_STREAM` is for;
 *   `MediaPlayer` needs a decodable source.
 *
 * - **`USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`.** A workspace playing audio is media
 *   from the user's point of view: it should duck for a call, respect the media
 *   volume slider, and route to whatever the user is listening on. The
 *   alternative (`USAGE_ASSISTANCE_SONIFICATION`) would tie workspace audio to
 *   the notification volume, which is wrong for `mpv`.
 *
 * - **No audio focus request.** Requesting focus would *stop the user's music*
 *   the moment a workspace's ALSA device was opened — including by a program
 *   that then plays nothing. A workspace is a background tool, so it mixes rather
 *   than interrupts. This is a deliberate limitation, recorded rather than
 *   implied.
 *
 * - **A failure to obtain a track is null, not an exception.** Devices refuse
 *   tracks (no output route, a per-app limit already reached), and that must
 *   surface as [AudioFeatureState.Unsupported] rather than as a crash in the
 *   launch coroutine.
 */
class AndroidAudioSink @Inject constructor() : AudioSink {

    override fun open(): AudioStream? {
        val track = try {
            buildTrack()
        } catch (e: Exception) {
            Logger.w(TAG, "AudioTrack could not be created: ${e.message}")
            null
        } ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            // An uninitialized track never plays; release it rather than handing
            // back a stream whose every write fails.
            runCatching { track.release() }
            Logger.w(TAG, "AudioTrack did not initialize (state=${track.state})")
            return null
        }

        return try {
            track.play()
            AndroidAudioStream(track)
        } catch (e: Exception) {
            runCatching { track.release() }
            Logger.w(TAG, "AudioTrack could not start: ${e.message}")
            null
        }
    }

    private fun buildTrack(): AudioTrack {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuffer = AudioTrack.getMinBufferSize(
            AudioBridgeProvisioner.SAMPLE_RATE_HZ,
            channelMask,
            encoding
        )
        // A negative return means the device rejects the format outright; fall
        // back to a size derived from the pinned format so `open()` still gets a
        // chance to report the real failure through `state`.
        val bufferBytes = if (minBuffer > 0) {
            // Two device buffers: enough to absorb a scheduling hiccup without
            // adding latency the user would notice as lip-sync drift.
            maxOf(minBuffer * 2, AudioBridgeProvisioner.READ_BUFFER_BYTES * 2)
        } else {
            AudioBridgeProvisioner.READ_BUFFER_BYTES * 4
        }

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(AudioBridgeProvisioner.SAMPLE_RATE_HZ)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
    }

    private companion object {
        const val TAG = "AudioSink"

        /** Kept for readability in reviews: this bridge never records. */
        @Suppress("unused")
        val STREAM_TYPE = AudioManager.STREAM_MUSIC
    }
}

/**
 * §1.113 — one live `AudioTrack` stream.
 *
 * [write] uses `WRITE_BLOCKING` deliberately: it is what pushes backpressure all
 * the way back through the FIFO to the guest's own `write()`, so a program
 * producing faster than the device plays is throttled instead of the host
 * buffering an unbounded amount of audio.
 */
private class AndroidAudioStream(private val track: AudioTrack) : AudioStream {

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = try {
        track.write(buffer, offset, length, AudioTrack.WRITE_BLOCKING)
    } catch (e: Exception) {
        Logger.w(TAG, "AudioTrack write failed: ${e.message}")
        -1
    }

    override fun close() {
        // stop() before release() so queued audio is flushed rather than clipped
        // mid-sample; both are best-effort because a track can already be dead.
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private companion object {
        const val TAG = "AudioSink"
    }
}
