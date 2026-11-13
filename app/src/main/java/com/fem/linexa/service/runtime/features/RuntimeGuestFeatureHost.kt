package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.ContainerRuntime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.110 — the process-wide [GuestFeatureHost].
 *
 * §1.109 satisfied this contract on [com.fem.linexa.service.runtime.RuntimeBinder]
 * because the only caller was a ViewModel holding a binder. That made the
 * *binder* the feature layer's entry point, which is wrong now that the
 * workspace launch path reconciles features too: the launch path runs inside
 * `RuntimeService` and has no binder, and a Binder is not injectable. Moving
 * the implementation to this `@Singleton` gives both callers the same host,
 * built from the same three runtime collaborators the binder delegated to.
 *
 * Still deliberately narrow (§1.109): one-shot exec + the §1.83 daemon host +
 * session liveness. Nothing here can start or stop a Workspace session — that
 * stays with the nav graph (§1.2) and [com.fem.linexa.service.runtime.RuntimeLifecycleManager].
 */
@Singleton
class RuntimeGuestFeatureHost @Inject constructor(
    private val sessionManager: SessionManager,
    private val containerRuntime: ContainerRuntime,
    private val fileManager: WorkspaceFileManager
) : GuestFeatureHost {

    override fun hasActiveSession(workspaceId: String): Boolean =
        sessionManager.hasActiveSession(workspaceId)

    override suspend fun execCommand(workspaceId: String, command: String): CommandResult =
        containerRuntime.exec(workspaceId, rootfsPathOf(workspaceId), command)

    override suspend fun startDaemon(workspaceId: String, name: String, command: String): Boolean =
        containerRuntime.startDaemon(workspaceId, rootfsPathOf(workspaceId), name, command)

    override suspend fun stopDaemon(workspaceId: String, name: String) {
        containerRuntime.stopDaemon(workspaceId, name)
    }

    override fun isDaemonAlive(workspaceId: String, name: String): Boolean =
        containerRuntime.isDaemonAlive(workspaceId, name)

    private fun rootfsPathOf(workspaceId: String): String =
        fileManager.getRootfsPath(workspaceId).absolutePath
}
