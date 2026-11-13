package com.fem.linexa.service.runtime

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for RuntimeService binding (§1.4, §7.2 Phase 2).
 *
 * §1.2 session boundary: created once per Workspace *nested nav-graph*
 * (via `hiltViewModel()` from `WorkspaceNavGraph`, which scopes it to the
 * `workspace/{id}` back-stack entry), so it lives exactly for the lifetime
 * of the nested graph — not once per sub-screen (Terminal/Desktop/…) inside
 * it. It owns the single RuntimeService bind for that graph: binding one
 * time per graph, starting the workspace's PRoot session once when the
 * workspace is entered, and stopping it when the whole graph is popped back
 * to Home (`onCleared` tracks the workspace it started and sends
 * `ACTION_STOP` before unbinding). Sub-screens observe [sessionStates] /
 * [binder] instead of binding the service themselves.
 */
@HiltViewModel
class RuntimeViewModel @Inject constructor(
    application: Application,
    private val workspaceRepository: WorkspaceRepository
) : AndroidViewModel(application) {

    private var binder: RuntimeBinder? = null
    private var isBound = false

    /** Workspace this graph started, if any — stopped again in [onCleared]. */
    private var activeWorkspaceId: String? = null

    private val _serviceState = MutableStateFlow<RuntimeServiceState>(RuntimeServiceState.Unbound)
    val serviceState: StateFlow<RuntimeServiceState> = _serviceState.asStateFlow()

    private val _workspaceStates = MutableStateFlow<Map<String, RuntimeState>>(emptyMap())
    val workspaceStates: StateFlow<Map<String, RuntimeState>> = _workspaceStates.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val runtimeBinder = service as? RuntimeBinder ?: return
            binder = runtimeBinder
            isBound = true
            _serviceState.value = RuntimeServiceState.Connected
            Logger.d("RuntimeViewModel", "Connected to RuntimeService")

            viewModelScope.launch {
                runtimeBinder.sessionStates.collect { states ->
                    _workspaceStates.value = states
                    // §1.68 fix: clear activeWorkspaceId when the session ends
                    // (Error or Stopped) so a later startWorkspace (re-entering
                    // the workspace after a crash) isn't blocked — without this,
                    // the guard `if (activeWorkspaceId == workspaceId) return`
                    // blocks re-entry after a crash because activeWorkspaceId was
                    // set during the first startWorkspace and never cleared.
                    val state = states[activeWorkspaceId]
                    if (state is RuntimeState.Error || state is RuntimeState.Stopped) {
                        activeWorkspaceId = null
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            isBound = false
            _serviceState.value = RuntimeServiceState.Disconnected
            Logger.d("RuntimeViewModel", "Disconnected from RuntimeService")
        }
    }

    /**
     * The live [RuntimeBinder], once the service has been connected.
     * `null` until the async bind resolves. Recomposing consumers should
     * read this after [bindService] and react to it becoming non-null.
     */
    val currentBinder: RuntimeBinder?
        get() = binder

    fun bindService() {
        // Pre-release audit fix (2026-09-02, finding U10/M-7): guard against
        // double binding. The workspace nav graph re-runs its
        // LaunchedEffect(workspace.id) on every configuration change while
        // this ViewModel survives; the unguarded second bindService leaked
        // the app-context binding on onCleared's single unbind (the
        // sessionStates collector stayed live for the process lifetime).
        if (isBound) {
            Logger.d(TAG, "bindService: already bound — ignoring")
            return
        }
        val context = getApplication<Application>()
        val intent = Intent(context, RuntimeService::class.java)
        Logger.d(TAG, "bindService: starting + binding RuntimeService")
        context.startService(intent)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        isBound = bound
    }

    fun unbindService() {
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
            _serviceState.value = RuntimeServiceState.Unbound
        }
    }

    /**
     * Starts the workspace's Linux session (§1.2: entering the workspace
     * starts its system). Guards with the current in-memory state so
     * re-entry/tab-hopping doesn't respawn a live session; a workspace that
     * is Stopped (or errored) gets started, one already
     * [RuntimeState.Running] / [RuntimeState.Starting] is left alone.
     *
     * Resolves the workspace row from the repository (the UI `Workspace`
     * model is presentation-only); the row's EXISTENCE is the launch gate —
     * the rootfs path is derived inside the service from the workspace id
     * (Phase 9B: the workspace ID is the only rootfs identity).
     */
    fun startWorkspace(workspaceId: String) {
        if (activeWorkspaceId == workspaceId) return
        val state = getState(workspaceId)
        if (state is RuntimeState.Running || state is RuntimeState.Starting) return

        // §1.29: the row must exist (getById is suspend — Room DAO, resolve
        // inside the coroutine). Phase 9B: the persisted rootfs path is no
        // longer read here — the field is diagnostic; the service derives
        // the path from the id via WorkspaceFileManager.
        viewModelScope.launch {
            workspaceRepository.getById(workspaceId) ?: return@launch
            val context = getApplication<Application>()
            activeWorkspaceId = workspaceId
            Logger.i(TAG, "TERMINAL_CHAIN: sending ACTION_START for workspaceId=$workspaceId")
            context.startService(RuntimeService.startIntent(context, workspaceId))
        }
    }

    fun stopWorkspace(workspaceId: String) {
        val context = getApplication<Application>()
        val intent = RuntimeService.stopIntent(context, workspaceId)
        context.startService(intent)
        activeWorkspaceId = null
    }

    fun getState(workspaceId: String): RuntimeState {
        return binder?.getState(workspaceId) ?: RuntimeState.Stopped
    }

    suspend fun execCommand(workspaceId: String, command: String): CommandResult? {
        return binder?.execCommand(workspaceId, command)
    }

    override fun onCleared() {
        // Transformation spec §9/§10 — UI lifecycle ≠ runtime lifecycle: the
        // workspace graph being cleared (the user backed out to Home) is a UI
        // fact, NOT a runtime termination trigger. The workspace keeps running
        // under its foreground service — long-running commands keep running,
        // tmux/persistence stays attached, and the user returns via the
        // notification's Open action or by re-entering the workspace (the
        // start path re-attaches to a live session instead of respawning it).
        //
        // Explicit stop remains exactly where it was: the FGS notification's
        // Stop action (ACTION_STOP), the leave-confirmation dialog's "Stop
        // workspace" option (WorkspaceNavGraph), and workspace deletion. This
        // VM deliberately has no stop here so terminal UI destruction can
        // never again be equivalent to runtime termination.
        unbindService()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RuntimeViewModel"
    }
}

sealed interface RuntimeServiceState {
    data object Unbound : RuntimeServiceState
    data object Connected : RuntimeServiceState
    data object Disconnected : RuntimeServiceState
}