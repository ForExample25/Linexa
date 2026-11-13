package com.fem.linexa.service.runtime

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks one RuntimeSession per active Workspace (§1.5).
 * Internal component of RuntimeService, not a top-level module.
 */
@Singleton
class SessionManager @Inject constructor(
    private val runtimeTrace: RuntimeTrace,
    /**
     * Transformation spec §6: the component-health registry backing
     * [refreshHealth] — Running workspaces re-aggregate their
     * `RuntimeState.Running.health` from here whenever a component reports.
     */
    private val healthRegistry: WorkspaceHealthRegistry,
) {

    private val _sessions = MutableStateFlow<Map<String, RuntimeSession>>(emptyMap())
    val sessions: StateFlow<Map<String, RuntimeSession>> = _sessions.asStateFlow()

    private val _sessionStates = MutableStateFlow<Map<String, RuntimeState>>(emptyMap())
    val sessionStates: StateFlow<Map<String, RuntimeState>> = _sessionStates.asStateFlow()

    /**
     * Pre-release audit fix (2026-09-02, finding RT-15): the map writes below
     * are read-modify-write sequences over two StateFlows, issued from BOTH
     * the Main dispatcher (lifecycle transitions) and Dispatchers.Default
     * (daemon/GPU watchers). Without serialization, two concurrent writers
     * could each read the same base map and last-write-wins — a lost
     * state/health update. Every mutation now runs under [writeLock]; the
     * two maps are updated together under it so they cannot diverge.
     */
    private val writeLock = Any()

    fun getSession(workspaceId: String): RuntimeSession? {
        return _sessions.value[workspaceId]
    }

    fun getState(workspaceId: String): RuntimeState {
        return _sessionStates.value[workspaceId] ?: RuntimeState.Stopped
    }

    fun createSession(workspaceId: String, rootfsPath: String): RuntimeSession {
        Logger.i(TAG, "STATE_SEQ: $workspaceId CREATE_SESSION at ${Instant.now()}")
        Logger.i(TAG, "  caller: ${stackSource()}")
        runtimeTrace.record(workspaceId, "SessionManager.createSession → Starting")
        val session = RuntimeSession(
            workspaceId = workspaceId,
            state = RuntimeState.Starting,
            rootfsPath = rootfsPath,
            startedAt = Instant.now()
        )
        synchronized(writeLock) {
            _sessions.value = _sessions.value + (workspaceId to session)
            _sessionStates.value = _sessionStates.value + (workspaceId to RuntimeState.Starting)
        }
        return session
    }

    fun updateState(workspaceId: String, state: RuntimeState) {
        Logger.i(TAG, "STATE_SEQ: $workspaceId → ${state::class.simpleName} at ${Instant.now()}")
        Logger.i(TAG, "  caller: ${stackSource()}")
        runtimeTrace.record(workspaceId, "RuntimeState → ${state::class.simpleName}")
        synchronized(writeLock) {
            _sessionStates.value = _sessionStates.value + (workspaceId to state)
            val session = _sessions.value[workspaceId]
            if (session != null) {
                _sessions.value = _sessions.value + (workspaceId to session.copy(state = state))
            }
        }
    }

    fun removeSession(workspaceId: String) {
        Logger.i(TAG, "STATE_SEQ: $workspaceId REMOVE_SESSION at ${Instant.now()}")
        Logger.i(TAG, "  caller: ${stackSource()}")
        runtimeTrace.record(workspaceId, "SessionManager.removeSession (state entry deleted)")
        synchronized(writeLock) {
            _sessions.value = _sessions.value - workspaceId
            _sessionStates.value = _sessionStates.value - workspaceId
        }
    }

    /**
     * Transformation spec §6: re-aggregate a *running* workspace's health
     * from [WorkspaceHealthRegistry] into its [RuntimeState.Running.health].
     *
     * Called whenever a component reports (feature reconcile outcome, daemon
     * exit watcher) so the degraded/healthy fact is observable without
     * waiting for the next lifecycle transition. A workspace that is not
     * Running is untouched — degradation is only meaningful mid-flight.
     */
    fun refreshHealth(workspaceId: String) {
        val current = _sessionStates.value[workspaceId]
        if (current !is RuntimeState.Running) return
        val health = healthRegistry.healthOf(workspaceId)
        if (health == current.health) return
        runtimeTrace.record(
            workspaceId,
            "SessionManager.refreshHealth → ${health::class.simpleName}" +
                ((health as? com.fem.linexa.domain.runtime.RuntimeHealth.Degraded)
                    ?.degradations?.joinToString(", ") { it.component }?.let { " ($it)" } ?: "")
        )
        updateState(workspaceId, current.copy(health = health))
    }

    fun hasActiveSession(workspaceId: String): Boolean {
        val state = getState(workspaceId)
        return state !is RuntimeState.Stopped && state !is RuntimeState.Error
    }

    fun getActiveSessions(): List<RuntimeSession> {
        return _sessions.value.values.filter { session ->
            val state = session.state
            state !is RuntimeState.Stopped && state !is RuntimeState.Error
        }
    }

    private companion object {
        const val TAG = "SessionManager"

        /** §1.68: temporary diagnostic — caller of the state transition. */
        fun stackSource(): String = Throwable().stackTrace
            .drop(2)
            .take(4)
            .joinToString(" ← ") { el ->
                "${el.className.substringAfterLast('.').substringAfterLast('$')}.${el.methodName}:${el.lineNumber}"
            }
    }
}
