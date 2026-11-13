package com.fem.linexa.core.logging

/**
 * A destination for log messages. Implementations write to file, logcat, etc.
 */
interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
}
