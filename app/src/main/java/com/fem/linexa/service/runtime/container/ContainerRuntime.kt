package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.service.runtime.pty.PtySession

/**
 * The internal seam between [com.fem.linexa.service.runtime.RuntimeService]
 * and the concrete container backend (§1.20, evolving §1.1).
 *
 * This is **not** a reintroduction of the pluggable `RuntimeEngine`
 * abstraction §1.1 deliberately rejected for v1 — there is exactly one
 * implementation ([PRootContainerRuntime]), it is PRoot-only, and nothing
 * here is designed around swapping in Docker/VM/chroot backends. What
 * changed is that the "small internal contract" §1.1 already called for is
 * now a named, documented interface instead of `RuntimeService` calling
 * `ProcessBuilder`/PRoot argv-building directly — see §1.20 for the full
 * rationale.
 */
interface ContainerRuntime {

    /**
     * Open a persistent, interactive session inside [rootfsPath] for
     * [workspaceId] and [terminalId], backed by a real pseudo-terminal
     * (§1.20) rather than a line-buffered process pipe (§1.97/W1: sessions
     * are keyed by `(workspaceId, terminalId)`; each terminal is its own
     * independent proot process).
     *
     * Closes and replaces any existing session for the same
     * `(workspaceId, terminalId)` key first. §1.97/PT0: the terminal id has
     * NO default — a terminal only ever opens under an explicit id, so the
     * workspace's default terminal must be named as [DEFAULT_TERMINAL_ID]
     * ("T0") by the caller (the launch path does; behavior is identical to
     * the pre-W1 `openSession`).
     *
     * **The shell is not a parameter, deliberately** (§17.1). It is resolved
     * by the implementation from the workspace itself:
     * `config.yaml`'s `launch.shell` → the rootfs's declared
     * `linexa.environment.shell` → a probe of the image
     * (`PRootContainerRuntime.resolveLaunchShell`). This interface used to
     * declare `shellPath: String = "/bin/bash"`, which no implementation ever
     * read — a parameter that appeared to select the shell and could not. It
     * was removed rather than honoured: a caller-supplied value would have to
     * outrank all three of those tiers to mean anything, and its `/bin/bash`
     * default would then be a hardcoded shell on images that ship only
     * busybox ash — the precise failure the probe exists to avoid. Callers
     * that need to choose a shell set `launch.shell`, which is the supported,
     * per-workspace, persisted way to do it.
     */
    suspend fun openTerminal(
        workspaceId: String,
        rootfsPath: String,
        terminalId: String,
        /**
         * Issue #1 (2026-09-03): launch the session as the emulated ROOT
         * account instead of the workspace's normal user. `false` is the
         * normal `linexa` user session; the explicit root terminal is the
         * elevation surface for "a specific operation [that] explicitly
         * requires root privileges" (metadata shortcuts declare it with
         * `as_root: true`). Deliberately NO default value on the interface:
         * every caller states which identity it wants (the same
         * no-silent-default discipline the shell parameter ban enforces —
         * and it keeps the `$default` bridge out of the API, which
         * ContainerRuntimeShellOwnershipTest pins).
         */
        asRoot: Boolean
    ): PtySession

    /** The current session for a workspace's terminal, if one is open. */
    fun getTerminal(workspaceId: String, terminalId: String = DEFAULT_TERMINAL_ID): PtySession?

    /** Whether a workspace's terminal currently has a live session. */
    fun isTerminalAlive(workspaceId: String, terminalId: String = DEFAULT_TERMINAL_ID): Boolean

    /** Destroy and forget one workspace terminal's session, if any. Idempotent. */
    suspend fun closeTerminal(workspaceId: String, terminalId: String = DEFAULT_TERMINAL_ID)

    /**
     * Destroy and forget every session for a workspace — the workspace-wide
     * teardown primitive (stop/restart/leave all terminate the whole
     * workspace, not one terminal). Idempotent.
     */
    suspend fun closeWorkspaceSessions(workspaceId: String)

    /**
     * Run a one-shot, non-interactive command inside [rootfsPath] and wait
     * for it to finish. Used by Package Manager / File Manager / Feature
     * toggles, which don't need an interactive, stateful shell — a fresh
     * PRoot invocation per call is the right tool here, not the PTY.
     */
    suspend fun exec(workspaceId: String, rootfsPath: String, command: String): CommandResult

    /**
     * §1.92: like [exec], but streams each output line to [onLine] as it is
     * produced. Used by the Package Manager for long-running operations
     * (install/remove/update/upgrade/refresh) so the UI can show live
     * progress. [timeoutSeconds] defaults to a generous operation bound
     * (e.g. a big `apt-get install`), unlike [exec]'s query-oriented 120s.
     */
    suspend fun execStreaming(
        workspaceId: String,
        rootfsPath: String,
        command: String,
        timeoutSeconds: Long = OPERATION_TIMEOUT_SECONDS,
        onLine: (String) -> Unit
    ): CommandResult

    /**
     * §1.83: start a long-lived guest daemon ([command]) for [workspaceId].
     *
     * Unlike [exec] (a bounded one-shot PRoot invocation whose
     * `--kill-on-exit` reaps any backgrounded child the moment the command
     * shell exits — the mechanism that used to kill Xvnc the instant a
     * `Xvnc ... &` exec finished), a daemon is the **foreground** command
     * of its own dedicated, persistent PRoot process
     * (`/bin/sh -c 'exec <command>'`), so the process lives exactly as long
     * as the daemon does and the VNC port stays bound across the launching
     * call. `--kill-on-exit` is still correct here: when the daemon exits,
     * PRoot tears down its own process.
     *
     * @param name stable identifier (e.g. `xvnc`, later `sshd`) used to
     *   [stopDaemon]/[isDaemonAlive] the same daemon. Restarting a name that
     *   is already alive stops the old one first (idempotent restart).
     * @return true once the daemon process has been launched and is alive.
     */
    suspend fun startDaemon(
        workspaceId: String,
        rootfsPath: String,
        name: String,
        command: String
    ): Boolean

    /** §1.83: stop a named guest daemon (SIGTERM via its PRoot host, then forced kill if needed). Idempotent. */
    suspend fun stopDaemon(workspaceId: String, name: String)

    /** §1.83: whether a named guest daemon is currently alive for [workspaceId]. */
    fun isDaemonAlive(workspaceId: String, name: String): Boolean

    /** §1.83: stop every guest daemon for a workspace (used on session teardown). */
    suspend fun stopAllDaemons(workspaceId: String)

    /**
     * §1.97/W5 — startup recovery sweep: reclaim stale runtime scratch left by
     * a previous process that died abnormally (force-kill, LMK, process death).
     *
     * A fresh process has NO live in-memory state (§1.13/§1.16: RuntimeState
     * and per-terminal state reset to empty), so every on-disk `PROOT_TMP_DIR`
     * scope under the shared parent — and every workspace directory carrying a
     * leftover tmux control socket — is stale by definition. This method
     * releases/deletes them, reusing the idempotent primitives
     * ([BootstrapManager.releaseProotTmpDir], the workspace-close tmux
     * hygiene) rather than duplicating process-kill logic.
     *
     * Must only be called on a fresh process BEFORE any workspace/exec/daemon
     * opens (the lifecycle manager serializes this against START/STOP/RESTART);
     * it must never run while a session is live. Best-effort and bounded: a
     * partial failure is counted in the returned [RecoverySweepReport], never
     * thrown, and never blocks a start.
     */
    suspend fun startupRecoverySweep(): RecoverySweepReport
}

/** §1.97/W5 — deterministic outcome of a [ContainerRuntime.startupRecoverySweep]. */
data class RecoverySweepReport(
    val releasedScopes: Int,
    val staleSocketsCleaned: Int,
    val failures: Int
)

// CommandResult moved to com.fem.linexa.domain.runtime.CommandResult (Phase 6b).
// It is a carrier-only DTO (exitCode/output/error) with no service-layer-specific
// fields, so it lives in the domain layer alongside DEFAULT_TERMINAL_ID, RuntimeState
// and TerminalState — the same Phase 6a precedent that moved the terminal-id constant
// out of this package. Imported above. The previous in-seam declaration made every
// domain consumer of RuntimeBinder.execCommand() a frozen Rule-1 violator; this file's
// `data class` body is now just the seam-specific RecoverySweepReport/TerminalKey.

/**
 * §1.97/W1: composite key identifying one interactive session — the PRoot
 * runtime addresses sessions by `(workspaceId, terminalId)` rather than by
 * workspace alone. A typed key class (preferred over a raw `Pair` so the
 * shape is discoverable and can carry semantics later).
 */
data class TerminalKey(
    val workspaceId: String,
    val terminalId: String
)

// §1.97/W1: the default terminal of a workspace is
// [com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID], declared in
// `domain.runtime` next to RuntimeState/TerminalState rather than here.
// Phase 6a moved it so `ui.screens.workspace` (WorkspaceNavGraph resolves a
// requested terminal id against the workspace default) can name it without
// importing from this package — the internal container seam. It is imported
// above and used as the default argument of getTerminal/isTerminalAlive/
// closeTerminal, exactly as before.

/** §1.92: default timeout for streaming package-manager operations (30 min). */
const val OPERATION_TIMEOUT_SECONDS = 1800L
