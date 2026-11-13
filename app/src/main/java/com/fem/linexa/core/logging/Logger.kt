package com.fem.linexa.core.logging

import android.app.Application
import java.io.File

/**
 * Centralized Logger facade (§1.11). Structured tags, file-backed sink,
 * per-workspace log scope.
 *
 * Usage:
 *   Logger.d("Runtime", "PRoot started with pid $pid")
 *   Logger.e("Import", "Extraction failed", exception)
 *   Logger.workspaceLogger("ubuntu").i("Terminal", "Command executed")
 */
object Logger {
    private val sinks = mutableListOf<LogSink>()
    private var initialized = false

    fun init(application: Application) {
        if (initialized) return
        val logDir = File(application.filesDir, "logs")
        sinks.add(FileLogSink(logDir))
        initialized = true
    }

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    fun workspaceLogger(workspaceId: String): ScopedLogger = ScopedLogger(workspaceId)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        sinks.forEach { it.write(level, tag, message, throwable) }
    }
}

class ScopedLogger(private val workspaceId: String) {
    fun v(tag: String, message: String) = Logger.v("$workspaceId:$tag", message)
    fun d(tag: String, message: String) = Logger.d("$workspaceId:$tag", message)
    fun i(tag: String, message: String) = Logger.i("$workspaceId:$tag", message)
    fun w(tag: String, message: String) = Logger.w("$workspaceId:$tag", message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = Logger.e("$workspaceId:$tag", message, throwable)
}
