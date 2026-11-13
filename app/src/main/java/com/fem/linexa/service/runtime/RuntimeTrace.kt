package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.DiagnosticLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.68: in-memory + file-backed capture of the full workspace startup chain.
 *
 * Every hop of the runtime startup sequence records a line here with a wall-clock
 * timestamp (see the `CHAIN:` instrumentations in RuntimeService,
 * PRootContainerRuntime, TermuxPtySession, SessionManager and
 * TerminalViewModel). The buffer is surfaced in a Debug section on the Workspace
 * page and copied by the "Copy" button; the same content is persisted
 * to `{workspace}/logs/runtime-trace.log` so it ships in the "Copy Error" report
 * without logcat.
 *
 * A `revision` StateFlow is bumped on every append so Compose can recompose the
 * trace without holding the raw lines in state. The buffer is bounded per
 * workspace (ring, newest-replaces-oldest) so a long-lived session can never grow
 * the trace unboundedly.
 *
 * **UI/UX Phase 7: every append is gated on [DebugCapture].** This class is the
 * app's only in-memory log buffer, and it used to fill unconditionally — ~50 call
 * sites across the runtime, up to 400 lines per workspace, never cleared for the
 * life of the process — for a panel that is hidden unless the user turned "Enable
 * debug info" on. With capture off, [record] and [newRun] now return before
 * allocating anything and the `revision` flow never bumps, so nothing downstream
 * recomposes either. Three consequences are deliberate:
 *
 *  * The gate is checked **here**, not at the call sites. Fifty guarded call sites
 *    would be fifty chances to miss one, and a missed one is invisible: it just
 *    keeps quietly buffering. The cost of the unguarded call is the argument
 *    string the caller interpolates, which is why [record] takes a `String` and
 *    not a lambda — a by-name message would save that interpolation but change
 *    every existing call site, and these strings are short (the one genuinely
 *    expensive producer, `logExecForensics`, checks [DebugCapture] itself before
 *    building its multi-KB report).
 *  * [render] and [persist] are **not** gated. They read what was already
 *    captured, and gating them would break the case where a user turns debug on,
 *    reproduces a failure, then reads the trace — the read must work exactly when
 *    the flag is on, and returning "" for data that exists would be a lie.
 *  * Turning the toggle off calls [clear], so the memory is actually released
 *    rather than merely stopping its growth.
 */
@Singleton
class RuntimeTrace @Inject constructor() {

    private val buffers = LinkedHashMap<String, MutableList<String>>()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    fun newRun(workspaceId: String, header: String) {
        if (!DebugCapture.isEnabled()) return
        val buf = buffers.getOrPut(workspaceId) { mutableListOf() }
        buf.add("[${now()}] === $header ===")
        _revision.value = _revision.value + 1
    }

    @Synchronized
    fun record(workspaceId: String, message: String) {
        if (!DebugCapture.isEnabled()) return
        val list = buffers.getOrPut(workspaceId) { mutableListOf() }
        list.add("[${now()}] $message")
        if (list.size > MAX_LINES_PER_RUN) list.removeAt(0)
        _revision.value = _revision.value + 1
    }

    /** The latest recorded run for a workspace ("" if none recorded yet). */
    @Synchronized
    fun render(workspaceId: String): String {
        val buf = buffers[workspaceId] ?: return ""
        if (buf.isEmpty()) return ""
        return buf.joinToString("\n") + "\n\n$FOOTER"
    }

    /**
     * UI/UX Phase 7: drops every buffered line, for every workspace.
     *
     * Called by `DebugCaptureGate` when the user turns "Enable debug info" off.
     * Stopping new appends is not sufficient on its own: up to 400 lines per
     * workspace may already be held, for a panel that is now hidden, for the rest
     * of the process — the plan's "not continuously buffer logs in the
     * background" covers what is *retained* as well as what is added.
     *
     * This drops **memory only**. The persisted copies
     * (`{workspace}/logs/runtime-trace.log`, and the app log) are untouched on
     * purpose: they are diagnostic evidence for a failed install (§1.53), a
     * toggle is not a delete, and the app log has its own explicit Clear action
     * in Global Settings. Bumping `revision` is what makes the (now hidden) panel
     * converge on empty instead of showing a stale trace if it is reopened.
     */
    @Synchronized
    fun clear() {
        if (buffers.isEmpty()) return
        buffers.clear()
        _revision.value = _revision.value + 1
    }

    /**
     * Persist the latest run to `{workspace}/logs/runtime-trace.log` (appended to
     * the Copy Error report set). Needs the [DiagnosticLogManager] to locate the
     * workspace logs directory — passed in by the caller so this class stays
     * JVM-testable without an Application.
     */
    @Synchronized
    fun persist(workspaceId: String, log: DiagnosticLogManager) {
        val text = render(workspaceId)
        if (text.isEmpty()) return
        log.appendLog(workspaceId, TRACE_LOG_FILE, text)
    }

    private fun now(): String = LocalDateTime.now().format(TIME_FORMAT)

    private companion object {
        const val MAX_LINES_PER_RUN = 400
        const val TRACE_LOG_FILE = "runtime-trace.log"
        const val FOOTER = "— Linexa runtime trace (see logs/runtime-trace.log) —"
    }
}
