package com.fem.linexa.ui.screens.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.commands.ShortcutPins
import com.fem.linexa.domain.commands.ShortcutPinsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the App grid's pinned/favorited command ids for one workspace
 * (App-grid redesign).
 *
 * Deliberately a separate ViewModel from [ShortcutsViewModel]: that one owns
 * *running* commands and is constructed bare in its JVM tests; this one owns
 * *arrangement* state and needs the persistence seam. Both attach to the same
 * workspace id, and neither knows about the other's flows.
 *
 * The load is one suspend read on [attach]; toggles mutate the in-memory
 * state FIRST (the badge must not wait on disk) and persist through
 * [ShortcutPinsStore.persist], which is fail-open — a failed write keeps the
 * session's arrangement and logs, never crashes the grid.
 */
@HiltViewModel
class ShortcutPinsViewModel @Inject constructor(
    private val pinsStore: ShortcutPinsStore
) : ViewModel() {

    private val _pins = MutableStateFlow(ShortcutPins())
    val pins: StateFlow<ShortcutPins> = _pins.asStateFlow()

    private var workspaceId: String? = null

    /** Loads the workspace's persisted pins (empty when none). */
    fun attach(wsId: String) {
        if (workspaceId == wsId) return
        workspaceId = wsId
        _pins.value = ShortcutPins()
        viewModelScope.launch {
            _pins.value = pinsStore.load(wsId)
        }
    }

    fun detach() {
        workspaceId = null
        _pins.value = ShortcutPins()
    }

    /** Toggles [shortcutId]'s pinned state ("Pin to top" / "Unpin"). */
    fun togglePinned(shortcutId: String) {
        mutate { it.withPinned(shortcutId, !it.isPinned(shortcutId)) }
    }

    /** Toggles [shortcutId]'s favorite state ("Add to favorites" / "Remove"). */
    fun toggleFavorite(shortcutId: String) {
        mutate { it.withFavorite(shortcutId, !it.isFavorite(shortcutId)) }
    }

    private fun mutate(transform: (ShortcutPins) -> ShortcutPins) {
        val ws = workspaceId ?: run {
            Logger.w(TAG, "pins mutation ignored: no workspace attached")
            return
        }
        val previous = _pins.value
        val updated = transform(previous)
        if (updated == previous) return
        _pins.update { updated }
        viewModelScope.launch {
            val persisted = pinsStore.persist(ws, updated, previous)
            if (persisted != updated) {
                // The write failed and the store kept the fallback; the UI
                // follows storage rather than pretending the pin landed.
                _pins.value = persisted
            }
        }
    }

    private companion object {
        const val TAG = "ShortcutPinsViewModel"
    }
}
