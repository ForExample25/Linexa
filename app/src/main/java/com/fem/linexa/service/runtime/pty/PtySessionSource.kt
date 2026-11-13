package com.fem.linexa.service.runtime.pty

import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalState
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of the runtime surface that the Terminal UI needs to obtain a
 * live [PtySession]: per-workspace [RuntimeState] observation, per-terminal
 * [TerminalState] observation, plus the session lookups. `RuntimeBinder`
 * implements this directly; the interface exists so `TerminalViewModel`'s
 * resolution logic can be exercised in a plain JVM test with a fake source
 * (§1.59) instead of requiring a bound Android service.
 *
 * Like [TerminalLibSession], this is deliberately not part of [PtySession]
 * itself — it is a *provisioning* seam for UI code, not a pty API.
 *
 * §1.97/W3: [terminalStates] + the per-terminal [getPtySession] overload are
 * the multi-terminal surface the UI resolves a screen against — every
 * terminal screen operates on `(workspaceId, terminalId)`, never silently
 * falling back to another terminal. The workspace-keyed
 * [getPtySession] overload remains as the single-terminal "T0" compat seam
 * (audit §18 W3 rollback: keep the single-terminal `T0` path).
 */
interface PtySessionSource {
    /** Current runtime state per workspace (always holds the latest value). */
    val sessionStates: StateFlow<Map<String, RuntimeState>>

    /**
     * Current per-terminal process state, keyed workspace then terminal id
     * (audit §9.2 — separate from [sessionStates]).
     */
    val terminalStates: StateFlow<Map<String, Map<String, TerminalState>>>

    /** The live pty session for a workspace, or null if none is open. */
    fun getPtySession(workspaceId: String): PtySession?

    /**
     * The live pty session for exactly one terminal of a workspace, or null
     * if that terminal is not open. The authoritative per-terminal
     * resolution the W3 UI uses.
     */
    fun getPtySession(workspaceId: String, terminalId: String): PtySession?
}
