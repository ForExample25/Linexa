package com.fem.linexa.core.logging

import java.io.File

/**
 * UI/UX Phase 4 — read and clear the app-wide log ([AppLogFiles]).
 *
 * The log has been written since Phase 0 and read by **nothing**: no viewer, no
 * share, no clear, and it is deliberately not part of diagnostic export (§1.18).
 * So the app accumulated up to ~1.5 MB of rotating logs that the user could
 * neither see the existence of nor reclaim. This is the missing half.
 *
 * Scope is exactly the app log. A workspace's own `{workspace}/logs/` (§1.53) is
 * *evidence about an install* — it is what the Broken-workspace diagnostics route
 * reads, and it is preserved across failures on purpose — so clearing the app log
 * must not touch it. That is enforced by construction: this only ever visits names
 * [AppLogFiles.isAppLogFile] accepts, inside the one directory it is given.
 *
 * Deliberately **not** a Hilt-injected singleton over `Application`. It takes a
 * plain [File] directory so the whole thing is testable on the JVM against a temp
 * folder; the one production call site resolves `{filesDir}/logs`, the same path
 * [Logger.init] passes to [FileLogSink].
 */
class AppLogStore(private val logDir: File) {

    /**
     * Total bytes of the app log files that exist.
     *
     * Counts only files [AppLogFiles] owns, so an unrelated file that someone drops
     * in the log directory is never attributed to the app log — and, more to the
     * point, never implied to be something [clear] will remove.
     */
    fun totalSizeBytes(): Long = existingFiles().sumOf { it.length() }

    /** How many app log files currently exist (0 when nothing has been logged yet). */
    fun fileCount(): Int = existingFiles().size

    /**
     * Delete every app log file.
     *
     * Returns the number of bytes reclaimed — measured *inside* the lock, before
     * deleting, so the number reported to the user is the number actually removed
     * rather than a size sampled earlier and possibly grown since.
     *
     * Synchronized on [AppLogFiles.writeLock], the same monitor [FileLogSink]
     * holds while appending and rotating. Without that, a delete could land between
     * the sink's rotation rename and its append, or remove the active file
     * mid-write; the sink would then recreate what the user just cleared.
     *
     * Best-effort per file: an undeletable file is skipped and not counted, because
     * a partial reclaim is a correct outcome to report and a thrown exception from
     * a settings tap is not. Logging is *not* re-initialized or stopped — the sink
     * recreates the active file on the next line, which is the desired behaviour:
     * clearing frees space, it does not turn logging off.
     */
    fun clear(): Long {
        var reclaimed = 0L
        synchronized(AppLogFiles.writeLock) {
            for (file in existingFiles()) {
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    reclaimed += length
                }
            }
        }
        return reclaimed
    }

    /** The absolute path shown in the UI, so the user can find the files themselves. */
    fun displayPath(): String = logDir.absolutePath

    private fun existingFiles(): List<File> = AppLogFiles.names()
        .map { File(logDir, it) }
        .filter { runCatching { it.isFile }.getOrDefault(false) }
}
