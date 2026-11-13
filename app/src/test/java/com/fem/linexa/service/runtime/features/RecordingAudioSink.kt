package com.fem.linexa.service.runtime.features

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * §1.113 — a recording [AudioSink] for the JVM tests.
 *
 * Lives next to [RecordingGuestFeatureHost]/[RecordingHostClipboard] for the same
 * reason: it fakes a *seam* of the feature layer (the Android playback half), so
 * the FIFO → stream → playback chain is assertable without a device.
 *
 * [available] models the case the real sink has to live with — a device that
 * refuses a track — because "the guest must keep running even with no playback"
 * is only assertable with a sink that can say no.
 */
open class RecordingAudioSink(
    /** When false, [open] returns null as a device with no output route does. */
    var available: Boolean = true
) : AudioSink {

    /** Every stream handed out, in order. */
    val streams = mutableListOf<RecordingAudioStream>()

    var openCount: Int = 0
        private set

    var openThrows: Exception? = null

    /** All PCM written across every stream, concatenated in order. */
    val played: ByteArray
        get() = streams.fold(ByteArray(0)) { acc, s -> acc + s.written }

    override fun open(): AudioStream? {
        openCount++
        openThrows?.let { throw it }
        if (!available) return null
        return RecordingAudioStream().also { synchronized(streams) { streams += it } }
    }

    /** Streams that were closed — a released track is a resource invariant. */
    val closedCount: Int get() = synchronized(streams) { streams.count { it.closed } }
}

/** §1.113 — one recorded playback stream. */
class RecordingAudioStream : AudioStream {

    private val buffer = java.io.ByteArrayOutputStream()

    /** Set to make [write] report a fatal stream error. */
    var failWrites: Boolean = false

    var closed: Boolean = false
        private set

    val written: ByteArray get() = synchronized(this) { buffer.toByteArray() }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        if (failWrites) return -1
        synchronized(this) { this.buffer.write(buffer, offset, length) }
        return length
    }

    override fun close() {
        closed = true
    }
}

/**
 * §1.113 — a scripted [AudioSource].
 *
 * A FIFO's defining behavior is that [open] **blocks** until a writer appears and
 * that the returned stream ends when that writer closes — which a temp file cannot
 * reproduce. This fake makes both explicit: each [emit] queues one "writer", and
 * [open] blocks on that queue exactly as the real open blocks on the kernel.
 */
class ScriptedAudioSource : AudioSource {

    private val writers = LinkedBlockingQueue<ByteArray>()

    var openCount: Int = 0
        private set

    /** When set, [open] returns null (the "FIFO not there yet" case). */
    var unavailable: Boolean = false

    /** How long [open] waits for a writer before reporting nothing to read. */
    var waitMillis: Long = 200L

    /** Queue one guest program's worth of PCM. */
    fun emit(bytes: ByteArray) {
        writers.put(bytes)
    }

    override fun open(): InputStream? {
        openCount++
        if (unavailable) return null
        val next = writers.poll(waitMillis, TimeUnit.MILLISECONDS) ?: return null
        return ByteArrayInputStream(next)
    }
}
