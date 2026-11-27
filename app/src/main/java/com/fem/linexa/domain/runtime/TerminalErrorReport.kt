package com.fem.linexa.domain.runtime

/**
 * Finding 011 — one pure, deterministic rendering of a terminal failure.
 *
 * The error card offers two text outputs: **Copy error** (what the user
 * pastes into a bug report) and **Diagnostics** (the same facts plus the
 * workspace's runtime trace). Both are built here so the panel never formats
 * evidence itself and the two views cannot drift.
 *
 * Three invariants matter:
 *
 *  - the raw exit status is always present (or explicitly `unavailable`), never
 *    replaced by prose — the finding-027 table already owns the meaning and
 *    this report carries its verdict alongside the number;
 *  - the reason and hint are copied verbatim from [TerminalState.Error], so the
 *    report cannot claim a cause the state does not carry;
 *  - the last output is bounded from the **end**, because the tail is what
 *    explains a failure; the omitted prefix is stated rather than silently
 *    dropped.
 *
 * Pure JVM: no Android, no Compose, no clock. The same inputs always produce
 * the same text, which is what makes the tests meaningful.
 */
object TerminalErrorReport {

    /** How much of the dead terminal's screen is retained in a report. */
    const val DEFAULT_OUTPUT_CHARS: Int = 8_000

    /**
     * The **Copy error** payload: exit status, reason, hint, and last output.
     * Deliberately compact — this is what a user pastes into an issue.
     */
    fun copyText(
        workspaceId: String,
        terminalId: String,
        error: TerminalState.Error,
        lastOutput: String
    ): String = buildString {
        appendLine("Linexa terminal error")
        appendLine("Workspace: $workspaceId")
        appendLine("Terminal: $terminalId")
        appendLine("Exit status: ${status(error)}")
        appendLine("Reason: ${error.reason}")
        if (!error.hint.isNullOrBlank()) {
            appendLine("Hint: ${error.hint}")
        }
        appendLine()
        appendLine("Last output:")
        appendLine(boundedOutput(lastOutput))
    }.trimEnd()

    /**
     * The **Diagnostics** payload: the same failure facts in section headers
     * the existing diagnostics parser understands, plus the workspace runtime
     * trace. The trace is already scoped to the workspace by
     * `RuntimeTrace.render(workspaceId)`; the terminal id is stated in the
     * header so the reader knows which session of that workspace this is.
     */
    fun diagnosticsText(
        workspaceId: String,
        terminalId: String,
        error: TerminalState.Error,
        lastOutput: String,
        runtimeTrace: String
    ): String = buildString {
        appendLine("=== Terminal $terminalId ===")
        appendLine("Workspace: $workspaceId")
        appendLine("Terminal: $terminalId")
        appendLine("Exit status: ${status(error)}")
        appendLine("Reason: ${error.reason}")
        if (!error.hint.isNullOrBlank()) {
            appendLine("Hint: ${error.hint}")
        }
        appendLine()
        appendLine("=== Last output ===")
        appendLine(boundedOutput(lastOutput))
        appendLine()
        appendLine("=== Runtime trace ===")
        appendLine(runtimeTrace.trimEnd().ifBlank { "(no runtime trace captured)" })
    }.trimEnd()

    /**
     * Finding 019 — the **Diagnostics** payload for a live pty that has not
     * printed anything yet. It is deliberately separate from [diagnosticsText]:
     * there is no exit status or failure reason to report, and claiming one
     * would misrepresent a slow shell as a dead one. The pty is still alive;
     * the report says so and carries the same workspace-scoped runtime trace.
     */
    fun noOutputDiagnostics(
        workspaceId: String,
        terminalId: String,
        pid: Int,
        silentMs: Long,
        runtimeTrace: String
    ): String = buildString {
        appendLine("=== Terminal $terminalId ===")
        appendLine("Workspace: $workspaceId")
        appendLine("Terminal: $terminalId")
        appendLine("State: live, no output")
        appendLine("PID: $pid")
        appendLine("Silent for: ${silentMs}ms")
        appendLine()
        appendLine("=== Runtime trace ===")
        appendLine(runtimeTrace.trimEnd().ifBlank { "(no runtime trace captured)" })
    }.trimEnd()

    /**
     * The status line. `null` means the observer could not obtain one (finding
     * 027); it is named as unavailable rather than filled with a sentinel such
     * as `-1`, which would read as a real SIGHUP.
     */
    private fun status(error: TerminalState.Error): String =
        error.exitCode?.toString() ?: "unavailable"

    /**
     * Keeps the tail of the transcript, which is where the failure usually is,
     * and states the omitted length instead of pretending the report is
     * complete.
     */
    private fun boundedOutput(
        raw: String,
        maxChars: Int = DEFAULT_OUTPUT_CHARS
    ): String {
        val text = raw.trimEnd()
        if (text.isBlank()) return "(no terminal output was captured)"
        if (text.length <= maxChars) return text
        val omitted = text.length - maxChars
        return "[… $omitted earlier characters omitted …]\n" + text.takeLast(maxChars)
    }
}
