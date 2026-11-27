package com.fem.linexa.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase M0-A — the pure startup/event engine.
 *
 * These tests deliberately exercise the engine directly with an injected
 * monotonic clock: no Android clock, no logging, no coroutines. What matters
 * for the instrumentation contract is that the event math, skip flags,
 * pty correlation and the retention bounds are correct even when the capture
 * toggle is off.
 */
class PerfTraceEngineTest {

    private var nowNanos = 1_000_000_000L
    private val lines = ArrayList<String>()
    private val asyncBegun = ArrayList<Pair<String, Int>>()
    private val asyncEnded = ArrayList<Pair<String, Int>>()
    private var captureEnabled = true

    private fun engine(
        maxActiveTraces: Int = PerfTraceEngine.DEFAULT_MAX_ACTIVE_TRACES,
        maxPtyIds: Int = PerfTraceEngine.DEFAULT_MAX_PTY_IDS,
        maxAgeNanos: Long = PerfTraceEngine.DEFAULT_MAX_TRACE_AGE_NANOS
    ): PerfTraceEngine = PerfTraceEngine(
        clockNanos = { nowNanos },
        enabled = { captureEnabled },
        sink = { lines += it },
        asyncBegin = { name ->
            val cookie = asyncBegun.size + 1
            asyncBegun += name to cookie
            cookie
        },
        asyncEnd = { name, cookie -> asyncEnded += name to cookie },
        maxActiveTraces = maxActiveTraces,
        maxPtyIdsPerTrace = maxPtyIds,
        maxTraceAgeNanos = maxAgeNanos
    )

    @Test
    fun disabledEngineDoesNothingAndNeverReadsTheClock() {
        captureEnabled = false
        var clockReads = 0
        val disabled = PerfTraceEngine(
            clockNanos = { clockReads++; nowNanos },
            enabled = { false },
            sink = { error("sink must not be called while disabled") },
            asyncBegin = { error("async trace must not start while disabled") },
            asyncEnd = { _, _ -> error("async trace must not end while disabled") }
        )

        assertFalse(disabled.beginWorkspaceStart("ws"))
        disabled.beginTerminalOpen("ws", "t1")
        disabled.attachPty("ws", "pty")
        assertFalse(disabled.markPhase("ws", "t1", StartupEvent.E1))
        assertFalse(disabled.markFirstOutput("pty", 10))
        assertFalse(
            disabled.markScreenUpdate("pty", 1, 10L, 2L, 3L)
        )
        disabled.abandonWorkspace("ws", "stop")
        disabled.abandonTerminalOpen("ws", "failed")
        disabled.abandonPty("pty", "destroyed")

        assertEquals(0, clockReads)
        assertEquals(0, disabled.activeTraceCount())
        assertNull(disabled.snapshot("ws"))
    }

    @Test
    fun recordsMonotonicOffsetsAndDeltasInEventOrder() {
        val engine = engine()
        assertTrue(engine.beginWorkspaceStart("ws"))

        nowNanos += 1_500_000L // 1.5 ms
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E1))

        nowNanos += 500_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E2, skipped = true))

        nowNanos += 250_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E3, skipped = true))

        nowNanos += 250_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E4, skipped = false))

        nowNanos += 1_000_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E5))

        engine.attachPty("ws", "pty-1", "t1")
        nowNanos += 2_000_000L
        assertTrue(engine.markFirstOutput("pty-1", 4096))

        val snapshot = engine.snapshot("ws")
        assertNotNull(snapshot)
        assertEquals(StartupOrigin.WORKSPACE_START, snapshot!!.origin)
        assertEquals("t1", snapshot.terminalId)
        assertTrue(snapshot.e0Present)
        assertEquals(setOf(StartupEvent.E2, StartupEvent.E3), snapshot.skipped)
        assertEquals(0L, snapshot.offsetsNanos[StartupEvent.E0])
        assertEquals(1_500_000L, snapshot.offsetsNanos[StartupEvent.E1])
        assertEquals(2_000_000L, snapshot.offsetsNanos[StartupEvent.E2])
        assertEquals(2_250_000L, snapshot.offsetsNanos[StartupEvent.E3])
        assertEquals(2_500_000L, snapshot.offsetsNanos[StartupEvent.E4])
        assertEquals(3_500_000L, snapshot.offsetsNanos[StartupEvent.E5])
        assertEquals(5_500_000L, snapshot.offsetsNanos[StartupEvent.E6])

        // Every emitted line carries the timestamp, the elapsed offset and the
        // skip flag, and none carries file contents/commands.
        assertTrue(lines.any { it.contains("event=E2") && it.contains("skipped=1") })
        assertTrue(lines.any { it.contains("event=E5") && it.contains("elapsedUs=3500") })
        assertTrue(lines.any { it.contains("event=E6") && it.contains("bytes=4096") })
    }

    @Test
    fun duplicateMarksAreIgnoredAndOutOfOrderMarksAreFlagged() {
        val engine = engine()
        engine.beginWorkspaceStart("ws")

        nowNanos += 1_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E3))
        val first = engine.snapshot("ws")!!.offsetsNanos.getValue(StartupEvent.E3)

        nowNanos += 5_000L
        assertFalse(engine.markPhase("ws", "t1", StartupEvent.E3))
        assertEquals(first, engine.snapshot("ws")!!.offsetsNanos.getValue(StartupEvent.E3))

        // E1 arrives after E3: retained, but the log says it was out of order.
        nowNanos += 1_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E1))
        assertTrue(lines.any { it.contains("event=E1") && it.contains("outOfOrder=1") })
    }

    @Test
    fun firstScreenUpdateCompletesTheRecordAndEndsTheAsyncSection() {
        val engine = engine()
        engine.beginWorkspaceStart("ws")
        engine.attachPty("ws", "pty-1", "t1")

        nowNanos += 10_000_000L
        assertTrue(engine.markFirstOutput("pty-1", 128))
        nowNanos += 5_000_000L
        assertTrue(engine.markScreenUpdate("pty-1", 7, 4096L, 3L, 2L))

        assertEquals(0, engine.activeTraceCount())
        assertNull(engine.snapshot("ws"))
        assertEquals(1, asyncBegun.size)
        assertEquals(PerfTraceEngine.ASYNC_WORKSPACE_START, asyncBegun[0].first)
        assertEquals(1, asyncEnded.size)
        assertEquals(asyncBegun[0], asyncEnded[0])
        assertTrue(lines.any { it.contains("event=E7") && it.contains("screenRevision=7") })

        // A second E7 for the same pty cannot resurrect the finished record.
        assertFalse(engine.markScreenUpdate("pty-1", 8, 5000L, 4L, 3L))
    }

    @Test
    fun terminalOpenWithoutWorkspaceStartHasNoE0() {
        val engine = engine()
        engine.beginTerminalOpen("ws", "t2")
        engine.attachPty("ws", "pty-2", "t2")

        val snapshot = engine.snapshot("ws")
        assertNotNull(snapshot)
        assertFalse(snapshot!!.e0Present)
        assertEquals(StartupOrigin.TERMINAL_OPEN, snapshot.origin)
        assertEquals(setOf("pty-2"), snapshot.ptyIds)
        assertEquals(1, asyncBegun.size)
        assertEquals(PerfTraceEngine.ASYNC_TERMINAL_OPEN, asyncBegun[0].first)
    }

    @Test
    fun activeTraceRecordsAreBoundedOldestFirst() {
        val engine = engine(maxActiveTraces = 2)
        engine.beginWorkspaceStart("ws-1")
        engine.beginWorkspaceStart("ws-2")
        engine.beginWorkspaceStart("ws-3")

        assertEquals(2, engine.activeTraceCount())
        assertNull(engine.snapshot("ws-1"))
        assertNotNull(engine.snapshot("ws-2"))
        assertNotNull(engine.snapshot("ws-3"))
        // Eviction must close the evicted record's async section.
        assertEquals(3, asyncBegun.size)
        assertEquals(1, asyncEnded.size)
        assertEquals(asyncBegun.first(), asyncEnded.first())
    }

    @Test
    fun ptyIdsPerTraceAreBounded() {
        val engine = engine(maxPtyIds = 2)
        engine.beginWorkspaceStart("ws")
        engine.attachPty("ws", "pty-1")
        engine.attachPty("ws", "pty-2")
        engine.attachPty("ws", "pty-3")

        assertEquals(setOf("pty-2", "pty-3"), engine.snapshot("ws")!!.ptyIds)
    }

    @Test
    fun staleRecordsAreDiscarded() {
        val engine = engine(maxAgeNanos = 1_000_000L)
        engine.beginWorkspaceStart("ws")

        nowNanos += 2_000_000L
        // Any subsequent event consults the clock, discards the stale record
        // and creates a fresh terminal-open record rather than reviving it.
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E1))
        assertEquals(1, engine.activeTraceCount())
        assertEquals(StartupOrigin.TERMINAL_OPEN, engine.snapshot("ws")!!.origin)
    }

    @Test
    fun abandoningAnExtraTerminalKeepsTheWorkspaceStartRecord() {
        val engine = engine()
        engine.beginWorkspaceStart("ws")
        engine.attachPty("ws", "pty-1", "t1")

        engine.abandonTerminalOpen("ws", "terminal-open-failed")
        assertNotNull(engine.snapshot("ws"))
        assertEquals(0, asyncEnded.size)

        engine.abandonWorkspace("ws", "stopped")
        assertNull(engine.snapshot("ws"))
        assertEquals(1, asyncEnded.size)
    }

    @Test
    fun abandoningAStandaloneTerminalOpenEndsItsSection() {
        val engine = engine()
        engine.beginTerminalOpen("ws", "t2")

        engine.abandonTerminalOpen("ws", "terminal-open-failed")
        assertNull(engine.snapshot("ws"))
        assertEquals(1, asyncEnded.size)
    }

    @Test
    fun disablingCaptureDropsLiveRecordsAndClosesSections() {
        val engine = engine()
        engine.beginWorkspaceStart("ws-1")
        engine.beginWorkspaceStart("ws-2")
        assertEquals(2, engine.activeTraceCount())

        engine.onCaptureDisabled()

        assertEquals(0, engine.activeTraceCount())
        assertEquals(2, asyncEnded.size)
    }

    @Test
    fun aClockThatMovesBackwardsNeverProducesNegativeOffsets() {
        val engine = engine()
        engine.beginWorkspaceStart("ws")
        nowNanos += 1_000_000L
        engine.markPhase("ws", "t1", StartupEvent.E1)

        nowNanos -= 5_000_000L
        assertTrue(engine.markPhase("ws", "t1", StartupEvent.E2))

        assertEquals(0L, engine.snapshot("ws")!!.offsetsNanos.getValue(StartupEvent.E2))
        assertTrue(lines.last().contains("elapsedUs=0"))
    }

    @Test
    fun ptyEventsWithoutACorrelatedRecordAreNoOps() {
        val engine = engine()
        assertFalse(engine.markFirstOutput("unknown", 10))
        assertFalse(engine.markScreenUpdate("unknown", 1, 10L, 1L, 1L))
        assertEquals(0, engine.activeTraceCount())
        assertTrue(lines.isEmpty())
    }
}
