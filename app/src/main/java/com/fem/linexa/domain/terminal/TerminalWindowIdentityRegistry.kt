package com.fem.linexa.domain.terminal

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app/shortcut identity attached to a terminal that was opened for a
 * launcher action.
 *
 * The runtime owns terminal ids, but it does not know that T3 was opened by
 * tapping "Python 3 + venv". This record is that missing link: it lets the
 * desktop title/preview its windows with the app's own name and icon while the
 * pty and process lifetime remain entirely runtime-owned.
 */
data class TerminalWindowIdentity(
    val title: String,
    val appId: String? = null,
    val iconKey: String? = null,
    val iconRes: Int? = null,
    val command: String? = null
)

/**
 * App/UI-scoped registry keyed by `(workspaceId, terminalId)`.
 *
 * Entries are best-effort metadata, never authority: a missing or stale entry
 * simply falls back to the terminal's OSC title. [prune] is called with the
 * live terminal ids so a closed id cannot leak its old title onto a future
 * window (the runtime never reuses ids, but the registry must not depend on
 * that invariant).
 *
 * Thread-safe because launcher ViewModels record on the main dispatcher while
 * the terminal ViewModel snapshots on its own coroutine.
 */
@Singleton
class TerminalWindowIdentityRegistry @Inject constructor() {

    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, String>, TerminalWindowIdentity>()

    /**
     * Bumped on every actual change. The desktop snapshots this registry only
     * when it reconciles terminal state; an app identity is recorded just AFTER
     * its terminal id is allocated, so without this signal a window could sit
     * on its fallback title until an unrelated terminal-state emission.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun record(workspaceId: String, terminalId: String, identity: TerminalWindowIdentity) {
        if (workspaceId.isBlank() || terminalId.isBlank()) return
        synchronized(lock) {
            if (entries[workspaceId to terminalId] == identity) return
            entries[workspaceId to terminalId] = identity
        }
        _revision.update { it + 1 }
    }

    fun get(workspaceId: String, terminalId: String): TerminalWindowIdentity? =
        synchronized(lock) { entries[workspaceId to terminalId] }

    /** Snapshot of one workspace's identities, keyed by terminal id. */
    fun snapshot(workspaceId: String): Map<String, TerminalWindowIdentity> =
        synchronized(lock) {
            entries
                .filterKeys { it.first == workspaceId }
                .mapKeys { it.key.second }
        }

    /** Drop every entry whose terminal id is no longer live in [workspaceId]. */
    fun prune(workspaceId: String, liveTerminalIds: Set<String>) {
        val removed = synchronized(lock) {
            entries.keys.removeAll { (ws, terminalId) ->
                ws == workspaceId && terminalId !in liveTerminalIds
            }
        }
        if (removed) _revision.update { it + 1 }
    }

    fun remove(workspaceId: String, terminalId: String) {
        val removed = synchronized(lock) {
            entries.remove(workspaceId to terminalId) != null
        }
        if (removed) _revision.update { it + 1 }
    }
}
