package com.fem.linexa.core.perf

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AP-005 regression guard: `minSdk` is 28, but `Trace.beginAsyncSection` and
 * `Trace.endAsyncSection` are API 29. On API 28 they throw `NoSuchMethodError`,
 * which is an `Error` — the sink's `RuntimeException` fail-open guard would not
 * catch it, so an unguarded call would crash the terminal path that tracing is
 * only supposed to observe.
 *
 * A behavioural Robolectric test at `sdk = 28` would be the strongest proof,
 * but the only android-all artifact cached for this offline build is API 34.
 * This source guard pins the same contract: both async entry points must test
 * the SDK level before touching the API-29 methods.
 */
class AndroidTraceSinkApiLevelTest {

    private lateinit var source: String

    @Before
    fun loadSource() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        val marker = "src/main/java/com/fem/linexa/core/perf/AndroidTraceSink.kt"
        while (dir != null && !File(dir, marker).exists()) dir = dir.parentFile
        source = File(dir ?: File("."), marker).readText()
    }

    private fun codeAfter(anchor: String): String =
        source.substringAfter(anchor)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun beginAsyncDeclinesBelowApi29BeforeCallingTrace() {
        val body = codeAfter("fun beginAsync").substringBefore("fun endAsync")
        assertTrue(
            "beginAsync must test Build.VERSION.SDK_INT before Trace.beginAsyncSection",
            body.indexOf("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q") in 0 until
                body.indexOf("Trace.beginAsyncSection")
        )
    }

    @Test
    fun endAsyncDeclinesBelowApi29BeforeCallingTrace() {
        val body = codeAfter("fun endAsync")
        assertTrue(
            "endAsync must test Build.VERSION.SDK_INT before Trace.endAsyncSection",
            body.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q") in 0 until
                body.indexOf("Trace.endAsyncSection")
        )
    }
}
