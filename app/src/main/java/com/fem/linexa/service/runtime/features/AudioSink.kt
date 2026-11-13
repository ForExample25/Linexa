package com.fem.linexa.service.runtime.features

import java.io.Closeable

/**
 * §1.113 — the narrow seam [WorkspaceAudioSupervisor] uses to play a workspace's
 * PCM through Android.
 *
 * Exists for the same reason [GuestFeatureHost], [GuestNotificationBridge] and
 * [HostClipboard] do: the real implementation ([AndroidAudioSink]) needs an
 * `AudioTrack`, so a supervisor that depended on it directly would be untestable
 * off-device. The whole FIFO → stream → playback chain is therefore driven by a
 * fake in JVM tests.
 *
 * **A factory, not a single stream.** Several workspaces can have Audio enabled
 * at once, and each needs its own playback stream: mixing two workspaces' PCM
 * into one `AudioTrack` would require the host to resample and mix, which is
 * precisely the work Android's mixer already does when given separate tracks.
 *
 * Deliberately one operation. There is no API here for volume, focus, routing,
 * device enumeration, or *recording* — the `mic` feature is out of scope (§3.3),
 * and this seam must not become the place it silently arrives.
 */
interface AudioSink {

    /**
     * Open a playback stream for the format pinned in [AudioBridgeProvisioner]
     * ([AudioBridgeProvisioner.SAMPLE_RATE_HZ], stereo, s16le), or null when
     * Android will not give one (no output device, a vendor failure, or a
     * hardware limit already reached).
     *
     * Null is a normal outcome, not an exception: the supervisor reports
     * [AudioFeatureState.Unsupported] and leaves the guest side alone rather
     * than failing a workspace launch.
     */
    fun open(): AudioStream?
}

/**
 * §1.113 — one live playback stream. Closing it releases the underlying
 * `AudioTrack`; a stream is never reused after [close].
 */
interface AudioStream : Closeable {

    /**
     * Write [length] bytes of s16le PCM from [buffer] at [offset], returning the
     * number of bytes accepted, or a negative value on a fatal stream error.
     *
     * Blocking by contract. That is what makes the FIFO's backpressure reach the
     * guest: a workspace producing audio faster than the device plays it is made
     * to wait at its own `write()`, instead of the host buffering without bound.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
}
