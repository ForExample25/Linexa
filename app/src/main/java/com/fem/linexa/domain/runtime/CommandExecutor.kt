package com.fem.linexa.domain.runtime

/**
 * Phase 9A (OPT-ARCH-001) — the domain's vocabulary for "run a command inside
 * a workspace's guest".
 *
 * Before this port, every domain consumer of command execution named
 * `service.runtime.RuntimeBinder` directly (9 files: the file-manager
 * interface + impl, the package-manager detector + six providers — 58
 * `execCommand` and 6 `execCommandStreaming` call sites). That made the
 * `domain → service` direction the one structural cycle left in the app:
 * `service` legitimately imports `domain` everywhere (the DI graph, the
 * runtime, the repositories), and `domain` reached back for exactly this
 * pair of methods.
 *
 * The port is deliberately NARROW: it names only the two methods domain
 * actually calls, measured rather than designed. It is NOT a
 * mirror of `RuntimeBinder`'s full surface (session state, terminal
 * operations, root terminals, daemon control are all service-level
 * concerns the domain never asks for). A proposed
 * `WorkspaceRuntimeStatus`/`isActive` port has ZERO domain call sites and
 * is deliberately not created.
 *
 * Implemented by `service.runtime.RuntimeBinder` (an interface refinement:
 * its existing method bodies already match both signatures, and every
 * existing caller keeps passing the same binder instance — the concrete
 * type now *is* the port, so no UI or DI call site changes). The
 * `GuestFeatureHost` seam (§1.109) is the same pattern one layer down:
 * a narrow interface naming the subset one consumer needs, JVM-testable
 * against a fake.
 */
interface CommandExecutor {

    /** Run a bounded one-shot command inside the guest and wait for it. */
    suspend fun execCommand(workspaceId: String, command: String): CommandResult

    /**
     * Like [execCommand], but streams each output line to [onLine] as it is
     * produced. Used for long-running package operations so the UI can show
     * live progress. The generous operation timeout is the default; callers
     * never need to pass [timeoutSeconds] explicitly.
     */
    suspend fun execCommandStreaming(
        workspaceId: String,
        command: String,
        timeoutSeconds: Long = DEFAULT_OPERATION_TIMEOUT_SECONDS,
        onLine: (String) -> Unit
    ): CommandResult

    companion object {
        /**
         * The operation timeout the exec path uses by default — the same
         * 1800 s the container seam's `OPERATION_TIMEOUT_SECONDS` carries.
         * Duplicated here because `domain` must not import the container
         * seam (`ImportBoundaryTest` Rule 1); the two are pinned equal by
         * `DomainPortsTest.thePortTimeoutMatchesTheContainerSeam`, so a
         * change on either side breaks the build instead of drifting.
         */
        const val DEFAULT_OPERATION_TIMEOUT_SECONDS = 1_800L
    }
}
