package com.fem.linexa.service.runtime

import com.fem.linexa.domain.runtime.RuntimeState
import java.time.Instant

/**
 * Tracks the state of a running Workspace's PRoot process (§1.5).
 * One RuntimeSession per active Workspace, owned by SessionManager.
 *
 * The pre-transformation `process: Process?` field is gone: it was never
 * written or read (the pid has always lived in [RuntimeState.Running]), so
 * it was model residue that implied an ownership the runtime does not have —
 * the PRoot child is owned and supervised by
 * [com.fem.linexa.service.runtime.container.PRootContainerRuntime], not by
 * this carrier.
 */
data class RuntimeSession(
    val workspaceId: String,
    val state: RuntimeState,
    val rootfsPath: String,
    val startedAt: Instant? = null,
)
