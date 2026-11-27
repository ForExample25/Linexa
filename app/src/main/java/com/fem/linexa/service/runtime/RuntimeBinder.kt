package com.fem.linexa.service.runtime

import android.os.Binder
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.CommandExecutor
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalErrorReport
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.features.GuestFeatureHost
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionSource
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Binder for RuntimeService (§1.4).
 * ViewModels bind to the service through this and expose operations.
 *
 * §1.20: this class' public surface (method names/signatures) is
 * deliberately unchanged from the pre-§1.20 version even though the
 * implementation now goes through [ContainerRuntime]/[PtySession] instead
 * of the old `PRootBridge` — every existing ViewModel/Feature/PackageManager
 * caller (18+ call sites) keeps working without modification. §1.58 removed
 * the line-oriented `writeInput` bridge: the Terminal writes to the pty
 * through the emulator's own session (see [getPtySession], used by
 * `TerminalScreen`/`RealTerminalView`).
 *
 * §1.97/W2: per-terminal operations (open/close/resolve, [terminalStates])
 * route through [WorkspaceTerminalManager].
 *
 * §1.97/W3: [PtySessionSource] now also exposes the per-terminal resolution
 * ([terminalStates], [getPtySession] (workspaceId, terminalId)); the
 * workspace-keyed [getPtySession] stays as the single-terminal "T0" compat
 * seam (audit §18 W3 rollback).
 *
 * §1.109: also implements [GuestFeatureHost] — the narrow exec + guest-daemon
 * subset the workspace feature layer
 * ([com.fem.linexa.service.runtime.features.WorkspaceFeatureSupervisor]) needs.
 * No new capability is added; the interface exists so the feature supervisor
 * can be JVM-tested against a fake instead of this `android.os.Binder`.
 *
 * §1.110: that implementation is now *delegated* to the injected
 * [com.fem.linexa.service.runtime.features.RuntimeGuestFeatureHost] singleton
 * instead of being duplicated here, so the Features tab (which reaches the
 * feature layer through this binder) and the workspace launch path (which has
 * no binder) act on exactly the same host.
 *
 * §9A (Phase 9, OPT-ARCH-001): also implements the domain port
 * [CommandExecutor] — the two exec methods the domain layer actually calls
 * (the package-manager detector + six providers, the file-manager interface +
 * impl). This is an interface refinement, not a new capability: the same
 * method bodies serve both the service callers (this class) and the domain
 * callers (the port), so the `domain → service` import cycle is broken
 * without touching a single call site anywhere.
 */
class RuntimeBinder internal constructor(
    private val sessionManager: SessionManager,
    private val containerRuntime: ContainerRuntime,
    private val fileManager: WorkspaceFileManager,
    private val workspaceTerminalManager: WorkspaceTerminalManager,
    private val guestFeatureHost: GuestFeatureHost,
    val events: SharedFlow<RuntimeEvent>,
    /** Transformation spec §6/§29: component degradations, per workspace. */
    private val healthRegistry: WorkspaceHealthRegistry,
) : Binder(), PtySessionSource, GuestFeatureHost by guestFeatureHost, CommandExecutor {

    /** Observe all session states. */
    override val sessionStates: StateFlow<Map<String, RuntimeState>>
        get() = sessionManager.sessionStates

    /**
     * Transformation spec §6/§29: observe every workspace's component
     * degradations (feature failures, unexpected daemon exits). Empty map
     * entries mean healthy — a non-empty entry is a "running but degraded"
     * fact the UI must render (§29: no logcat-diving for basic state).
     */
    val workspaceHealth: StateFlow<Map<String, Map<String, RuntimeDegradation>>>
        get() = healthRegistry.health

    /** The degradation list currently recorded for one workspace. */
    fun degradations(workspaceId: String): List<RuntimeDegradation> =
        healthRegistry.degradations(workspaceId)

    /** Get the current state for a specific workspace. */
    fun getState(workspaceId: String): RuntimeState {
        return sessionManager.getState(workspaceId)
    }

    /** Get all active sessions. */
    fun getActiveSessions(): List<RuntimeSession> {
        return sessionManager.getActiveSessions()
    }

    /**
     * §1.92: like [execCommand], but streams each output line to [onLine] as
     * it is produced. Used by the Package Manager for long-running
     * operations (install/remove/update/upgrade/refresh) so the UI can show
     * live progress. Uses the generous operation timeout by default.
     *
     * §9A: overrides the [CommandExecutor] port — the default timeout is
     * inherited from the port's declaration
     * ([CommandExecutor.DEFAULT_OPERATION_TIMEOUT_SECONDS], pinned equal to
     * the container seam's `OPERATION_TIMEOUT_SECONDS` by `DomainPortsTest`),
     * so this override declares none. Same method, same default, same
     * callers; the only change is that the domain now names the port, not
     * this class.
     */
    override suspend fun execCommandStreaming(
        workspaceId: String,
        command: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit
    ): CommandResult {
        val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
        return containerRuntime.execStreaming(workspaceId, rootfsPath, command, timeoutSeconds, onLine)
    }

    /**
     * §1.83 guest-daemon host + one-shot [GuestFeatureHost.execCommand] +
     * [GuestFeatureHost.hasActiveSession] are supplied by the delegated
     * [guestFeatureHost] (§1.110) — the same singleton the workspace launch path
     * reconciles features through. Nothing is reimplemented here; this comment
     * exists so the missing method bodies read as deliberate.
     */

    /** Check if a workspace's session is running.
     *
     * §P0-fix (audit G8): this used to answer on T0 alone, which lied after
     * T0 was closed and replaced (§1.106) — a perfectly running workspace
     * reported "not running". The truthful answer is the workspace's own
     * lifecycle state: the runtime is running exactly when the session state
     * says Starting/Running (the state that is only ever set after the pty
     * child was verified alive, §1.76).
     */
    fun isProcessRunning(workspaceId: String): Boolean {
        return when (sessionManager.getState(workspaceId)) {
            is RuntimeState.Running, is RuntimeState.Starting -> true
            else -> false
        }
    }

    // ── §1.97/W2 per-terminal operations (multi-terminal UI lands in W3) ──

    /**
     * §1.97/W2: observe per-terminal process state, keyed workspace → terminal
     * id ([TerminalState] — separate from workspace [RuntimeState]).
     */
    override val terminalStates: StateFlow<Map<String, Map<String, TerminalState>>>
        get() = workspaceTerminalManager.terminalStates

    /**
     * The live terminal records, keyed workspace then terminal id. The Sessions
     * segment uses this to show the real sessions — their ids, pty pids,
     * creation times and states — rather than a synthesized list.
     */
    val terminalSessions: StateFlow<Map<String, Map<String, TerminalSession>>>
        get() = workspaceTerminalManager.terminalSessions

    /**
     * §1.97/W2: open a fresh terminal in [workspaceId] (allocates the next
     * per-workspace terminal id, §9.1) and return its id. W3 UI will call this
     * for "new terminal"; the current single-terminal path opens "T0" through
     * RuntimeService directly.
     */
    suspend fun openTerminal(workspaceId: String): String {
        val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
        val terminalId = workspaceTerminalManager.nextTerminalId(workspaceId)
        workspaceTerminalManager.openTerminal(workspaceId, rootfsPath, terminalId)
        return terminalId
    }

    /**
     * Issue #1 (2026-09-03): opens a terminal session as the emulated ROOT
     * account — the explicit elevation surface for operations that
     * require root (`as_root: true` metadata shortcuts, "Root Shell"
     * cards). Distinct terminal id namespace is unnecessary: the normal
     * allocator keeps sessions apart by id.
     */
    suspend fun openRootTerminal(workspaceId: String): String {
        val rootfsPath = fileManager.getRootfsPath(workspaceId).absolutePath
        val terminalId = workspaceTerminalManager.nextTerminalId(workspaceId)
        workspaceTerminalManager.openTerminal(workspaceId, rootfsPath, terminalId, asRoot = true)
        return terminalId
    }

    /**
     * §1.97/W2: close exactly one terminal of [workspaceId]. Idempotent.
     *
     * §1.106: returns the id of the replacement terminal that was opened
     * because this close would otherwise have left the Running workspace with
     * zero terminals (Termux-like semantics), or null when siblings remained.
     * The Terminal screen switches to a returned id so closing the last session
     * lands on a live terminal instead of the "unavailable" placeholder.
     */
    suspend fun closeTerminal(workspaceId: String, terminalId: String): String? =
        workspaceTerminalManager.closeTerminal(workspaceId, terminalId)

    /**
     * Finding 011: restart an errored terminal under the same id. The
     * manager's [WorkspaceTerminalManager.restartTerminal] is authoritative —
     * it replaces the dead pty and preserves the terminal's elevation. The UI
     * calls this from the error card's Restart action; nothing auto-restarts.
     *
     * [allowRunning] is forwarded only by Finding 019's no-output banner, whose
     * explicit Restart button is the confirmation for replacing a live but
     * silent pty. Every other caller keeps the manager's Error-only default.
     */
    suspend fun restartTerminal(
        workspaceId: String,
        terminalId: String,
        allowRunning: Boolean = false
    ): PtySession =
        workspaceTerminalManager.restartTerminal(
            workspaceId = workspaceId,
            terminalId = terminalId,
            rootfsPathOverride = fileManager.getRootfsPath(workspaceId).absolutePath,
            allowRunning = allowRunning
        )

    /**
     * Finding 011: the dead terminal's retained screen, for the Copy error /
     * Diagnostics payloads. Returns "" when there is no record or the backend
     * cannot read the screen — a diagnostic must never fail because its
     * evidence is missing.
     */
    fun terminalLastOutput(
        workspaceId: String,
        terminalId: String,
        maxChars: Int = TerminalErrorReport.DEFAULT_OUTPUT_CHARS
    ): String {
        val pty = workspaceTerminalManager.getRetainedPtySession(workspaceId, terminalId) ?: return ""
        return runCatching { pty.recentOutput(maxChars) }.getOrDefault("")
    }

    /**
     * §1.103: the terminal the runtime notification's Open action should
     * restore for [workspaceId] — the last-active (most recently viewed /
     * opened) open terminal. See [WorkspaceTerminalManager.lastActiveTerminalId].
     */
    fun lastActiveTerminalId(workspaceId: String): String =
        workspaceTerminalManager.lastActiveTerminalId(workspaceId)

    /**
     * §1.103: record that the user is currently viewing [terminalId] of
     * [workspaceId], so the notification's Open action restores the
     * last-active session. Called by the Terminal screen on attach/switch.
     */
    suspend fun markTerminalActive(workspaceId: String, terminalId: String) {
        workspaceTerminalManager.markTerminalActive(workspaceId, terminalId)
    }

    /** §1.97/W2: the live pty for one terminal of [workspaceId], if open. */
    override fun getPtySession(workspaceId: String, terminalId: String): PtySession? =
        workspaceTerminalManager.getPtySession(workspaceId, terminalId)

    /**
     * The live pty session for a workspace, if any (§1.20). The Terminal
     * screen hosts a real `TerminalView` attached to this session — this is
     * the authoritative rendering path since §1.58 (the line-oriented
     * events bridge is gone).
     *
     * §1.97/W1: the UI seam stays workspace-keyed; this resolves the
     * workspace's default terminal (T0). Per-terminal resolution lands in
     * W3 when the UI is multi-terminal.
     */
    override fun getPtySession(workspaceId: String): PtySession? {
        val s = containerRuntime.getTerminal(workspaceId, DEFAULT_TERMINAL_ID)
        Logger.d(
            "TerminalChain",
            "CHAIN: getPtySession($workspaceId) -> ${if (s == null) "null" else "pid=${s.pid} alive=${s.isAlive}"}"
        )
        return s
    }
}
