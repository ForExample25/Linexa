package com.fem.linexa.core.perf

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only file in the M0-A instrumentation that imports [android.os.Trace].
 *
 * Keeping it separate means the pure engine — and the many JVM tests that load
 * [PerfTrace] with capture disabled — never touch the Android trace machinery
 * unless a trace section is actually opened.
 *
 * Fail-open by design: trace sections are diagnostics, and the JVM unit-test
 * Android stub throws `RuntimeException` for every method. A single failure
 * disables the sink for the rest of the process instead of aborting the
 * terminal open/read path that is being measured. On a device the calls do not
 * throw, so this guard is inert.
 *
 * API 28 (the app's `minSdk`) has only the synchronous `Trace` methods. The
 * async section calls are API 29, and on API 28 they fail with
 * `NoSuchMethodError` — an `Error`, not a `RuntimeException`, so the guard above
 * would not catch it. Both async entry points therefore decline on API 28 and
 * return the same "no section" sentinel as a disabled capture; the synchronous
 * sections and all startup logging remain available.
 */
internal object AndroidTraceSink {

    private val cookies = AtomicInteger(1)
    private val available = AtomicBoolean(true)

    private inline fun <T> guarded(fallback: T, block: () -> T): T {
        if (!available.get()) return fallback
        return try {
            block()
        } catch (_: RuntimeException) {
            // Unmocked android.os.Trace in a plain JVM test (or an unexpected
            // platform failure): never let instrumentation break the caller.
            available.set(false)
            fallback
        }
    }

    fun beginSync(name: String) = guarded(Unit) { Trace.beginSection(name) }

    fun endSync() = guarded(Unit) { Trace.endSection() }

    fun beginAsync(name: String): Int = guarded(-1) {
        // Trace.beginAsyncSection is API 29; calling it on API 28 throws
        // NoSuchMethodError, which guarded() deliberately does not swallow.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@guarded -1
        val cookie = cookies.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        cookie
    }

    fun endAsync(name: String, cookie: Int) =
        guarded(Unit) {
            // Symmetric with beginAsync: no section was opened below API 29.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(name, cookie)
            }
        }
}
