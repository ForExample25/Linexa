package com.fem.linexa.core.logging

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File-backed log sink with bounded rotation.
 *
 * Writes to Logcat and appends to [AppLogFiles.LOG_FILE_NAME] in [logDir]. When the
 * active file exceeds [AppLogFiles.MAX_FILE_BYTES] it is rotated:
 * `linexa.log` → `linexa.log.1` → `linexa.log.2` → discarded, keeping
 * `MAX_GENERATIONS - 1` archived files.
 *
 * UI/UX Phase 4: the file names, the size bound and the write lock moved to
 * [AppLogFiles] because there is now a second component that touches these exact
 * files — [AppLogStore], behind the Global Settings "Clear app logs" row. Sharing
 * the monitor is what makes that safe: a clear can no longer land between this
 * sink's length check and its append, or midway through a rotation, and it can no
 * longer delete a file this sink is about to recreate under a name the deleter
 * didn't know about.
 *
 * **UI/UX Phase 7: the file half is gated on [DebugCapture].** This sink is the
 * app's log *capture* mechanism — the ~500 `Logger.*` call sites across the module
 * all land here, and until Phase 7 every one of them appended to a rotating
 * ~2 MB file set whether or not the user had ever enabled debug info. With capture
 * off, [write] now does no timestamp formatting, allocates no `File`, takes no
 * lock and touches no disk: the whole retained-and-growing half is off, which is
 * what "turning the UI off must actually disable the underlying collection logic"
 * requires.
 *
 * The **logcat** half stays unconditional, and that is a decision rather than an
 * oversight. Logcat is not something Linexa collects: there is no logcat reader in
 * the app (deliberately — nothing here can read it back), no screen displays it,
 * the app cannot clear it, and it is a fixed-size ring owned by the platform, so
 * it neither grows the app's storage nor is retained by Linexa. It is also the
 * entire documented on-device diagnosis path for this project
 * (`adb logcat -s TerminalChain RuntimeService ContainerRuntime TermuxPtySession`,
 * see the roadmap's §1.59) and the only channel that survives a process death
 * before any file write could complete. Silencing it would make a crash report
 * from a user who has debug off unactionable, while reclaiming nothing the user
 * can observe. The gate deliberately is **not** a log-level filter either: it does
 * not keep ERROR and drop INFO, because then "capture is off" would be a partial
 * truth in the one direction the user cannot check.
 */
class FileLogSink(private val logDir: File) : LogSink {

    init {
        logDir.mkdirs()
    }

    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val logcatPriority = when (level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(logcatPriority, tag, message)
        throwable?.let { Log.getStackTraceString(it) }?.let { Log.e(tag, it) }

        // UI/UX Phase 7: everything below this line is the app's own capture, and
        // it is skipped entirely while debug info is off — including the timestamp
        // formatting, which is otherwise paid on every single log call for a line
        // that would be discarded.
        if (!DebugCapture.isEnabled()) return

        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val logLine = "$timestamp [${level.name}] $tag: $message"
            synchronized(AppLogFiles.writeLock) {
                val logFile = File(logDir, AppLogFiles.LOG_FILE_NAME)
                if (logFile.length() >= AppLogFiles.MAX_FILE_BYTES) rotate()
                logFile.appendText("$logLine\n")
                throwable?.let { logFile.appendText("  ${Log.getStackTraceString(it)}\n") }
            }
        } catch (_: Exception) {
            // Best effort — don't crash the app for logging failures
        }
    }

    private fun rotate() {
        val name = AppLogFiles.LOG_FILE_NAME
        for (generation in AppLogFiles.MAX_GENERATIONS - 1 downTo 1) {
            val from = File(logDir, "$name.$generation")
            val to = File(logDir, "$name.${generation + 1}")
            if (from.exists()) from.renameTo(to) else to.delete()
        }
        val newest = File(logDir, "$name.1")
        newest.delete()
        File(logDir, name).renameTo(newest)
    }
}
