package com.fem.linexa.service.runtime

import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.runtime.RuntimeHealth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Workspace-scoped component-health registry (§6/§29 of the transformation
 * spec): the single place where the runtime answers, per workspace, "is every
 * owned component actually ready?" — the observable difference between
 * "running" and "running but degraded".
 *
 * ## Ownership and lifetime
 *
 * The registry holds **ephemeral, process-lifetime** state keyed by
 * workspaceId — the same lifetime discipline as [SessionManager]. Every
 * runtime component that reports through [report] must also be cleared on
 * workspace teardown: [clear] is called by the same close path that removes
 * the session, so a stale degradation can never outlive the workspace it
 * belonged to.
 *
 * ## Who reports what
 *
 * - [com.fem.linexa.service.runtime.features.WorkspaceFeatureReconciler] —
 *   the fail-open feature outcomes (ssh, notifications, clipboard, audio,
 *   network) it used to only log;
 * - [com.fem.linexa.service.runtime.container.PRootContainerRuntime]'s
 *   daemon watchers — a supervised guest daemon (xvnc/xsession/sshd/…) that
 *   exited without being asked to (`daemon:<name>`);
 * - the persistence layer — tmux/reattach failures that degrade the
 *   terminal without killing it.
 *
 * ## Consumption
 *
 * [WorkspaceSessionSupervisor] aggregates the snapshot into the workspace's
 * `RuntimeState.Running.health` right after the feature reconcile, and
 * `RuntimeBinder` exposes [observeWorkspace] for the diagnostics surface
 * (§29: the user must not need logcat to learn that ssh failed).
 */
@Singleton
class WorkspaceHealthRegistry @Inject constructor() {

    /** component → degradation, per workspace. Insertion order preserved. */
    private val _health = MutableStateFlow<Map<String, Map<String, RuntimeDegradation>>>(emptyMap())

    /** Observe every workspace's degradation map. */
    val health: StateFlow<Map<String, Map<String, RuntimeDegradation>>> = _health.asStateFlow()

    /** Report one healthy component — removes any prior degradation for it. */
    fun reportHealthy(workspaceId: String, component: String) {
        mutate(workspaceId) { it - component }
    }

    /**
     * Report one degraded component. A later [reportHealthy] (or [clear]) is
     * the only way the entry leaves — a recovery is always explicit, never
     * implied by time.
     */
    fun reportDegraded(workspaceId: String, degradation: RuntimeDegradation) {
        mutate(workspaceId) { it + (degradation.component to degradation) }
    }

    /** Convenience overload for the common "component X failed because Y". */
    fun reportDegraded(workspaceId: String, component: String, detail: String) {
        reportDegraded(workspaceId, RuntimeDegradation(component, detail))
    }

    /** Every degradation currently recorded for [workspaceId]. */
    fun degradations(workspaceId: String): List<RuntimeDegradation> =
        _health.value[workspaceId].orEmpty().values.toList()

    /** Aggregate [RuntimeHealth] for [workspaceId] (Healthy when empty). */
    fun healthOf(workspaceId: String): RuntimeHealth =
        degradations(workspaceId).let { if (it.isEmpty()) RuntimeHealth.Healthy else RuntimeHealth.Degraded(it) }

    /** Forget everything for [workspaceId] — the workspace was torn down. */
    fun clear(workspaceId: String) {
        mutate(workspaceId) { emptyMap() }
    }

    private fun mutate(workspaceId: String, transform: (Map<String, RuntimeDegradation>) -> Map<String, RuntimeDegradation>) {
        _health.value = _health.value + (workspaceId to transform(_health.value[workspaceId].orEmpty()))
    }
}
