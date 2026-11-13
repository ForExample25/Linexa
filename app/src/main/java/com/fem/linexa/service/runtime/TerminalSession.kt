package com.fem.linexa.service.runtime

import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.pty.PtySession
import java.time.Instant

/**
 * The runtime-owned record of one open terminal in a Workspace (§1.97/W2,
 * audit §9.3).
 *
 * Owned by [WorkspaceTerminalManager]; keyed by `(workspaceId, terminalId)`.
 * [state] is the terminal's process state ([TerminalState]), deliberately
 * distinct from the workspace-level [com.fem.linexa.domain.runtime.RuntimeState]
 * tracked by [RuntimeSession] — two separate state machines (§1.16). UI
 * attachment is not modelled here (§9.2).
 */
data class TerminalSession(
    val workspaceId: String,
    val terminalId: String,
    val ptySession: PtySession,
    val state: TerminalState,
    val createdAt: Instant
)
