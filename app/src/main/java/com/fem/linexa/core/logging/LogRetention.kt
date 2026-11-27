package com.fem.linexa.core.logging

import java.io.File
import java.io.RandomAccessFile

/**
 * F-033 / R-40: bounded retention for the per-workspace diagnostic logs.
 *
 * [DiagnosticLogManager] appends to files such as `runtime.log`,
 * `extraction.log` and `latest-error.log` for the whole life of a workspace
 * and never removed anything. A long-lived workspace therefore accumulated
 * logs without a ceiling — and the extraction log, before F-004 gated proot's
 * trace, could grow by hundreds of megabytes in a single install.
 *
 * The policy is deliberately simple and lossless where it matters:
 *
 *  * A single file is trimmed to its newest [MAX_FILE_BYTES] bytes, cut at a
 *    line boundary so the retained text still parses.
 *  * A workspace's whole log directory is capped at [MAX_WORKSPACE_BYTES].
 *    When over, the oldest non-essential files are deleted first.
 *  * [ESSENTIAL_FILE] (`latest-error.log`) is never deleted by the total cap;
 *    it is still trimmed per-file, because a runaway stack trace must not be
 *    able to fill the device either.
 *
 * The helpers take plain [File]s so the arithmetic is JVM-testable without an
 * Android Application (the manager itself needs one for `filesDir`).
 */
internal object LogRetention {

    /** Per-file ceiling: 2 MiB of text is far more than a support report needs. */
    const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    /** Whole-workspace ceiling. */
    const val MAX_WORKSPACE_BYTES = 16L * 1024L * 1024L

    /** The one file that survives total-cap eviction. */
    const val ESSENTIAL_FILE = "latest-error.log"

    /** Keep the newest [keepBytes] of [file], starting at a line boundary. */
    internal fun trimToTail(file: File, keepBytes: Long = MAX_FILE_BYTES): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= keepBytes || keepBytes <= 0L) return false
        val start = (length - keepBytes).coerceAtLeast(0L)
        val readLength = (length - start).toInt()
        val tail = ByteArray(readLength)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(tail)
            }
        } catch (_: Exception) {
            return false
        }
        // Do not start in the middle of a line (the trim point is arbitrary).
        val newline = tail.indexOf('\n'.code.toByte())
        val from = if (newline in 0 until tail.size - 1) newline + 1 else 0
        val tmp = File(file.parentFile, "${file.name}.trim")
        return try {
            tmp.writeBytes(tail.copyOfRange(from, tail.size))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Enforce [maxTotalBytes] across [files], deleting the oldest files first.
     * [essentialName] is never deleted.
     *
     * @return the names that were deleted, for logging and tests.
     */
    internal fun enforceTotalCap(
        files: List<File>,
        maxTotalBytes: Long = MAX_WORKSPACE_BYTES,
        essentialName: String = ESSENTIAL_FILE
    ): List<String> {
        val present = files.filter { it.isFile }
        var total = present.sumOf { it.length() }
        if (total <= maxTotalBytes) return emptyList()

        val deleted = mutableListOf<String>()
        val candidates = present
            .filter { it.name != essentialName }
            .sortedBy { it.lastModified() }
        for (file in candidates) {
            if (total <= maxTotalBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                deleted += file.name
            }
        }
        return deleted
    }
}
