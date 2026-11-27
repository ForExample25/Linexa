package com.fem.linexa.core.perf

import java.util.EnumMap
import java.util.LinkedHashMap

/**
 * Phase M0-A — the pure, testable half of the startup/event instrumentation.
 *
 * This class deliberately has **no Android dependency**. [com.fem.linexa.core.perf.PerfTrace]
 * supplies [SystemClock.elapsedRealtimeNanos] in production; JVM tests supply a
 * fake monotonic clock. Everything here is gated on [enabled] (wired to
 * [com.fem.linexa.core.logging.DebugCapture] in production), fail-closed, and
 * bounded:
 *
 *  * at most [maxActiveTraces] live startup records — the oldest is evicted;
 *  * at most [maxPtyIdsPerTrace] pty correlations per record;
 *  * a record older than [maxTraceAgeNanos] is discarded rather than retained
 *    for the life of the process.
 *
 * Nothing here logs file contents, commands, environment variables, or paths.
 * A record carries only the caller-supplied workspace/terminal ids, the pty
 * session id, monotonic offsets, skipped/out-of-order flags and integer
 * counters.
 *
 * ## Events
 *
 * E0 workspace start request accepted
 * E1 container startup/bootstrap completed
 * E2 guest repair phase completed or skipped
 * E3 dpkg recovery completed or skipped
 * E4 declared first-boot initialization completed or skipped
 * E5 PTY creation completed
 * E6 first PTY output received
 * E7 first terminal screen update actually delivered to a bound view
 *
 * E6/E7 are looked up by pty id because they happen on the reader/UI threads,
 * not on the coroutine that owns E0–E5. A pty is attached to its record BEFORE
 * the child is forked (see [com.fem.linexa.service.runtime.pty.TermuxPtySession]),
 * so a very fast first read can never lose its correlation.
 */
class PerfTraceEngine(
    private val clockNanos: () -> Long = { System.nanoTime() },
    private val enabled: () -> Boolean = { false },
    private val sink: (String) -> Unit = {},
    private val asyncBegin: (String) -> Int = { -1 },
    private val asyncEnd: (String, Int) -> Unit = { _, _ -> },
    maxActiveTraces: Int = DEFAULT_MAX_ACTIVE_TRACES,
    private val maxPtyIdsPerTrace: Int = DEFAULT_MAX_PTY_IDS,
    private val maxTraceAgeNanos: Long = DEFAULT_MAX_TRACE_AGE_NANOS
) {

    private val lock = Any()
    private val traces = LinkedHashMap<String, TraceRecord>()
    private val capacity = maxActiveTraces.coerceAtLeast(1)

    /** Whether capture is currently permitted. */
    fun isEnabled(): Boolean = enabled()

    /**
     * Starts a workspace-start trace (E0). Returns false when disabled or when
     * this workspace already has a live workspace-start record — a re-entrant
     * duplicate start must not reset the incumbent's timestamps.
     */
    fun beginWorkspaceStart(workspaceId: String): Boolean {
        if (!enabled()) return false
        var line: String? = null
        var started = false
        synchronized(lock) {
            evictExpiredLocked()
            val existing = traces[workspaceId]
            if (existing != null && existing.origin == StartupOrigin.WORKSPACE_START) {
                return@synchronized
            }
            val now = clockNanos()
            val record = TraceRecord(
                workspaceId = workspaceId,
                origin = StartupOrigin.WORKSPACE_START,
                startNanos = now,
                terminalId = null,
                e0Present = true
            )
            record.marks[StartupEvent.E0] = EventMark(offsetNanos = 0L, skipped = false)
            record.lastEventRank = StartupEvent.E0.ordinal
            record.lastEventNanos = now
            putLocked(workspaceId, record)
            record.asyncCookie = asyncBegin(ASYNC_WORKSPACE_START)
            line = format(record, StartupEvent.E0, now, 0L, 0L, false, false, null, null)
            started = true
        }
        line?.let(sink)
        return started
    }

    /**
     * Associates the terminal about to open with the workspace's trace. If no
     * workspace-start record exists (a manually created "New terminal"), a
     * terminal-open trace without E0 is created — the first mark's offsets are
     * then relative to this call, and the log says `origin=terminal-open`.
     */
    fun beginTerminalOpen(workspaceId: String, terminalId: String) {
        if (!enabled()) return
        var line: String? = null
        synchronized(lock) {
            evictExpiredLocked()
            val existing = traces[workspaceId]
            if (existing != null) {
                if (existing.terminalId == null) existing.terminalId = terminalId
                return@synchronized
            }
            val now = clockNanos()
            val record = TraceRecord(
                workspaceId = workspaceId,
                origin = StartupOrigin.TERMINAL_OPEN,
                startNanos = now,
                terminalId = terminalId,
                e0Present = false
            )
            record.lastEventNanos = now
            record.lastEventRank = -1
            putLocked(workspaceId, record)
            record.asyncCookie = asyncBegin(ASYNC_TERMINAL_OPEN)
            line = format(record, null, now, 0L, 0L, false, false, null, null)
        }
        line?.let(sink)
    }

    /**
     * Records the pty session id generated by the backend. Called before the
     * child process is forked, so E6 can always resolve its record.
     */
    fun attachPty(workspaceId: String, ptyId: String, terminalId: String? = null) {
        if (!enabled()) return
        synchronized(lock) {
            evictExpiredLocked()
            val record = traces[workspaceId] ?: run {
                val now = clockNanos()
                val created = TraceRecord(
                    workspaceId = workspaceId,
                    origin = StartupOrigin.TERMINAL_OPEN,
                    startNanos = now,
                    terminalId = terminalId,
                    e0Present = false
                )
                created.lastEventNanos = now
                created.lastEventRank = -1
                putLocked(workspaceId, created)
                created.asyncCookie = asyncBegin(ASYNC_TERMINAL_OPEN)
                created
            }
            if (record.ptyIds.size >= maxPtyIdsPerTrace) {
                record.ptyIds.keys.firstOrNull()?.let(record.ptyIds::remove)
            }
            record.ptyIds[ptyId] = terminalId ?: record.terminalId
        }
    }

    /**
     * Marks E1–E5 on [workspaceId]'s current record. Duplicate marks are
     * ignored (an additional terminal does not rewrite the workspace's E1–E5),
     * and out-of-order marks are preserved with `outOfOrder=1` rather than
     * dropped.
     */
    fun markPhase(
        workspaceId: String,
        terminalId: String?,
        event: StartupEvent,
        skipped: Boolean = false,
        ptyId: String? = null,
        detail: String? = null
    ): Boolean {
        if (!enabled()) return false
        var line: String? = null
        var recorded = false
        synchronized(lock) {
            evictExpiredLocked()
            val record = traces[workspaceId] ?: createTerminalOpenLocked(workspaceId, terminalId)
            if (record.terminalId == null) record.terminalId = terminalId
            if (record.marks.containsKey(event)) return@synchronized
            val now = clockNanos()
            val offset = (now - record.startNanos).coerceAtLeast(0L)
            val delta = (now - record.lastEventNanos).coerceAtLeast(0L)
            val outOfOrder = event.ordinal < record.lastEventRank
            record.marks[event] = EventMark(offset, skipped)
            if (event.ordinal > record.lastEventRank) record.lastEventRank = event.ordinal
            record.lastEventNanos = now
            line = format(record, event, now, offset, delta, skipped, outOfOrder, detail, ptyId)
            recorded = true
        }
        line?.let(sink)
        return recorded
    }

    /** E6 — first bytes received from the pty master. */
    fun markFirstOutput(ptyId: String, bytesRead: Int): Boolean {
        if (!enabled()) return false
        return markPtyEvent(ptyId, StartupEvent.E6, detail = "bytes=$bytesRead")
    }

    /**
     * E7 — the first screen revision actually pushed to a bound terminal view.
     * Completes and removes the record after logging.
     */
    fun markScreenUpdate(
        ptyId: String,
        screenRevision: Int,
        bytesRead: Long,
        handlerMessages: Long,
        screenUpdates: Long
    ): Boolean {
        if (!enabled()) return false
        var line: String? = null
        var recorded = false
        var endName: String? = null
        var endCookie = -1
        synchronized(lock) {
            evictExpiredLocked()
            val record = findPtyLocked(ptyId) ?: return@synchronized
            if (record.marks.containsKey(StartupEvent.E7)) return@synchronized
            val now = clockNanos()
            val offset = (now - record.startNanos).coerceAtLeast(0L)
            val delta = (now - record.lastEventNanos).coerceAtLeast(0L)
            val outOfOrder = StartupEvent.E7.ordinal < record.lastEventRank
            record.marks[StartupEvent.E7] = EventMark(offset, skipped = false)
            record.lastEventNanos = now
            line = format(
                record,
                StartupEvent.E7,
                now,
                offset,
                delta,
                skipped = false,
                outOfOrder = outOfOrder,
                detail = "screenRevision=$screenRevision bytes=$bytesRead " +
                    "handlerMessages=$handlerMessages screenUpdates=$screenUpdates",
                ptyId = ptyId
            )
            traces.remove(record.workspaceId)
            if (record.asyncCookie >= 0) {
                endName = record.asyncName
                endCookie = record.asyncCookie
                record.asyncCookie = -1
            }
            recorded = true
        }
        line?.let(sink)
        if (endName != null) asyncEnd(endName, endCookie)
        return recorded
    }

    /**
     * Ends any live trace for [workspaceId] (stop, failed start, workspace
     * closed before the UI attached). A no-op when nothing is live.
     */
    fun abandonWorkspace(workspaceId: String, reason: String) {
        if (!enabled()) return
        var line: String? = null
        var endName: String? = null
        var endCookie = -1
        synchronized(lock) {
            val record = traces.remove(workspaceId) ?: return@synchronized
            line = formatAbandon(record, reason, null, clockNanos())
            if (record.asyncCookie >= 0) {
                endName = record.asyncName
                endCookie = record.asyncCookie
                record.asyncCookie = -1
            }
        }
        line?.let(sink)
        if (endName != null) asyncEnd(endName, endCookie)
    }

    /**
     * Ends a trace ONLY when it belongs to an extra terminal open. A failed
     * extra terminal must not discard the workspace-start record of a still
     * live first terminal.
     */
    fun abandonTerminalOpen(workspaceId: String, reason: String) {
        if (!enabled()) return
        var line: String? = null
        var endName: String? = null
        var endCookie = -1
        synchronized(lock) {
            val record = traces[workspaceId] ?: return@synchronized
            if (record.origin != StartupOrigin.TERMINAL_OPEN) return@synchronized
            traces.remove(workspaceId)
            line = formatAbandon(record, reason, null, clockNanos())
            if (record.asyncCookie >= 0) {
                endName = record.asyncName
                endCookie = record.asyncCookie
                record.asyncCookie = -1
            }
        }
        line?.let(sink)
        if (endName != null) asyncEnd(endName, endCookie)
    }

    /** Ends the trace correlated with [ptyId], if one is live. */
    fun abandonPty(ptyId: String, reason: String) {
        if (!enabled()) return
        var line: String? = null
        var endName: String? = null
        var endCookie = -1
        synchronized(lock) {
            val record = findPtyLocked(ptyId) ?: return@synchronized
            record.ptyIds.remove(ptyId)
            line = formatAbandon(record, reason, ptyId, clockNanos())
            if (record.ptyIds.isEmpty()) {
                traces.remove(record.workspaceId)
                if (record.asyncCookie >= 0) {
                    endName = record.asyncName
                    endCookie = record.asyncCookie
                    record.asyncCookie = -1
                }
            }
        }
        line?.let(sink)
        if (endName != null) asyncEnd(endName, endCookie)
    }

    /** Drops every live record and closes its async trace section. */
    fun onCaptureDisabled() {
        var ends: ArrayList<Pair<String, Int>>? = null
        synchronized(lock) {
            // Capture is already off by the time this runs; do not allocate on
            // the common "toggle off with nothing live" path.
            if (traces.isEmpty()) return
            val closing = ArrayList<Pair<String, Int>>(traces.size)
            for (record in traces.values) {
                if (record.asyncCookie >= 0) closing += record.asyncName to record.asyncCookie
            }
            ends = closing
            traces.clear()
        }
        ends?.forEach { (name, cookie) -> asyncEnd(name, cookie) }
    }

    /** Test/diagnostic surface: number of live records. */
    fun activeTraceCount(): Int = synchronized(lock) { traces.size }

    /** Test/diagnostic surface: bounded copy of one live record. */
    fun snapshot(workspaceId: String): TraceSnapshot? = synchronized(lock) {
        val record = traces[workspaceId] ?: return@synchronized null
        TraceSnapshot(
            workspaceId = record.workspaceId,
            origin = record.origin,
            terminalId = record.terminalId,
            e0Present = record.e0Present,
            offsetsNanos = record.marks.mapValues { it.value.offsetNanos }.toMap(EnumMap(StartupEvent::class.java)),
            skipped = record.marks.filterValues { it.skipped }.keys.toSet(),
            ptyIds = record.ptyIds.keys.toSet()
        )
    }

    private fun markPtyEvent(ptyId: String, event: StartupEvent, detail: String?): Boolean {
        var line: String? = null
        var recorded = false
        synchronized(lock) {
            evictExpiredLocked()
            val record = findPtyLocked(ptyId) ?: return@synchronized
            if (record.marks.containsKey(event)) return@synchronized
            val now = clockNanos()
            val offset = (now - record.startNanos).coerceAtLeast(0L)
            val delta = (now - record.lastEventNanos).coerceAtLeast(0L)
            val outOfOrder = event.ordinal < record.lastEventRank
            record.marks[event] = EventMark(offset, skipped = false)
            if (event.ordinal > record.lastEventRank) record.lastEventRank = event.ordinal
            record.lastEventNanos = now
            line = format(record, event, now, offset, delta, false, outOfOrder, detail, ptyId)
            recorded = true
        }
        line?.let(sink)
        return recorded
    }

    private fun createTerminalOpenLocked(workspaceId: String, terminalId: String?): TraceRecord {
        val now = clockNanos()
        val record = TraceRecord(
            workspaceId = workspaceId,
            origin = StartupOrigin.TERMINAL_OPEN,
            startNanos = now,
            terminalId = terminalId,
            e0Present = false
        )
        record.lastEventNanos = now
        record.lastEventRank = -1
        putLocked(workspaceId, record)
        record.asyncCookie = asyncBegin(ASYNC_TERMINAL_OPEN)
        return record
    }

    private fun findPtyLocked(ptyId: String): TraceRecord? {
        for (record in traces.values) {
            if (record.ptyIds.containsKey(ptyId)) return record
        }
        return null
    }

    private fun putLocked(workspaceId: String, record: TraceRecord) {
        val replaced = traces.put(workspaceId, record)
        if (replaced != null && replaced.asyncCookie >= 0) {
            asyncEnd(replaced.asyncName, replaced.asyncCookie)
        }
        while (traces.size > capacity) {
            val oldestKey = traces.keys.firstOrNull() ?: break
            val evicted = traces.remove(oldestKey) ?: break
            if (evicted.asyncCookie >= 0) asyncEnd(evicted.asyncName, evicted.asyncCookie)
        }
    }

    private fun evictExpiredLocked() {
        if (traces.isEmpty()) return
        val now = clockNanos()
        val expired = traces.values.filter { now - it.startNanos > maxTraceAgeNanos }
        for (record in expired) {
            traces.remove(record.workspaceId)
            if (record.asyncCookie >= 0) asyncEnd(record.asyncName, record.asyncCookie)
        }
    }

    private fun format(
        record: TraceRecord,
        event: StartupEvent?,
        nowNanos: Long,
        offsetNanos: Long,
        deltaNanos: Long,
        skipped: Boolean,
        outOfOrder: Boolean,
        detail: String?,
        ptyId: String?
    ): String = buildString(192) {
        append("M0A startup event=").append(event?.code ?: "OPEN")
        append(" workspace=").append(record.workspaceId)
        append(" terminal=").append(record.terminalId ?: "-")
        append(" pty=").append(ptyId ?: "-")
        append(" origin=").append(record.origin.label)
        append(" tsNanos=").append(nowNanos)
        append(" elapsedUs=").append(offsetNanos / 1_000L)
        append(" deltaUs=").append(deltaNanos / 1_000L)
        append(" skipped=").append(if (skipped) 1 else 0)
        append(" outOfOrder=").append(if (outOfOrder) 1 else 0)
        if (detail != null) append(' ').append(detail)
    }

    private fun formatAbandon(
        record: TraceRecord,
        reason: String,
        ptyId: String?,
        nowNanos: Long
    ): String = buildString(160) {
        append("M0A startup event=ABANDON workspace=").append(record.workspaceId)
        append(" terminal=").append(record.terminalId ?: "-")
        append(" pty=").append(ptyId ?: "-")
        append(" origin=").append(record.origin.label)
        append(" elapsedUs=").append(((nowNanos - record.startNanos).coerceAtLeast(0L)) / 1_000L)
        append(" reason=").append(reason)
    }

    /** A bounded, read-only view of one live record (tests/diagnostics only). */
    data class TraceSnapshot(
        val workspaceId: String,
        val origin: StartupOrigin,
        val terminalId: String?,
        val e0Present: Boolean,
        val offsetsNanos: Map<StartupEvent, Long>,
        val skipped: Set<StartupEvent>,
        val ptyIds: Set<String>
    )

    private data class EventMark(val offsetNanos: Long, val skipped: Boolean)

    private class TraceRecord(
        val workspaceId: String,
        val origin: StartupOrigin,
        val startNanos: Long,
        var terminalId: String?,
        val e0Present: Boolean
    ) {
        val marks = EnumMap<StartupEvent, EventMark>(StartupEvent::class.java)
        val ptyIds = LinkedHashMap<String, String?>()
        var lastEventNanos: Long = startNanos
        var lastEventRank: Int = -1
        var asyncCookie: Int = -1
        var asyncName: String =
            if (origin == StartupOrigin.WORKSPACE_START) ASYNC_WORKSPACE_START else ASYNC_TERMINAL_OPEN
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE_TRACES: Int = 32
        const val DEFAULT_MAX_PTY_IDS: Int = 8
        const val DEFAULT_MAX_TRACE_AGE_NANOS: Long = 5L * 60L * 1_000_000_000L

        const val ASYNC_WORKSPACE_START: String = "Linexa:workspaceStartup"
        const val ASYNC_TERMINAL_OPEN: String = "Linexa:terminalOpen"
    }
}

/** The fixed, ordered startup events of Phase M0-A. */
enum class StartupEvent(val code: String) {
    E0("E0"),
    E1("E1"),
    E2("E2"),
    E3("E3"),
    E4("E4"),
    E5("E5"),
    E6("E6"),
    E7("E7")
}

/** Where a trace began: a real workspace start, or an extra terminal open. */
enum class StartupOrigin(val label: String) {
    WORKSPACE_START("workspace-start"),
    TERMINAL_OPEN("terminal-open")
}
