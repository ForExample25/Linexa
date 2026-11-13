package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import java.io.File
import java.io.InputStream

/**
 * §1.113 — the seam [WorkspaceAudioSupervisor] reads a workspace's PCM through.
 *
 * Separate from [AudioSink] because the two ends fail differently and are faked
 * differently: the sink is Android (`AudioTrack`), while this is a **blocking
 * open of a FIFO** — the one piece of the bridge whose real behavior cannot be
 * reproduced by a plain `File` in a JVM test.
 *
 * The contract is deliberately "one open per writer", which is FIFO semantics
 * rather than file semantics:
 *
 * - [open] blocks until a guest process opens the FIFO for writing, and returns a
 *   stream that ends (EOF) when that writer closes it.
 * - EOF therefore means "that program finished playing", **not** "the bridge is
 *   done" — the supervisor loops and opens again for the next program.
 * - Returning null means "not available right now" (no FIFO yet, or the open was
 *   interrupted), which the supervisor retries after a delay instead of treating
 *   as a failure.
 */
interface AudioSource {

    /** Block until a writer appears, then return its stream — see the kdoc. */
    fun open(): InputStream?
}

/**
 * §1.113 — the real [AudioSource]: a FIFO in the workspace's rootfs.
 *
 * `FileInputStream` on a FIFO is exactly the blocking-open behavior the seam
 * documents, so this implementation is a thin wrapper — its whole job is to be
 * the one place that touches the filesystem, and to refuse a path that is not a
 * FIFO. That refusal matters: if `mkfifo` failed in the guest and something later
 * created a *regular* file at the same path, reading it would appear to work
 * while actually replaying stale bytes and growing the workspace's storage
 * without bound. Better to report nothing to play.
 */
class FifoAudioSource(
    private val fifo: File
) : AudioSource {

    override fun open(): InputStream? {
        if (!fifo.exists()) {
            // Normal before the guest-side prepare has run.
            return null
        }
        return try {
            // A FIFO reports length 0 and is not a directory; a regular file with
            // content is the case worth rejecting loudly, because it silently
            // "works". `isFile` is true for a FIFO on Android's JVM, so size is
            // the only signal available without JNI — and a non-empty file at
            // this path is never something the bridge created.
            if (fifo.length() > 0L) {
                Logger.w(TAG, "audio path ${fifo.path} is a regular file, not a FIFO — refusing to read")
                return null
            }
            fifo.inputStream()
        } catch (e: Exception) {
            Logger.d(TAG, "audio FIFO open failed (${fifo.path}): ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AudioSource"
    }
}
