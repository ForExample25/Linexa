package com.fem.linexa.domain.terminal

import com.fem.linexa.domain.runtime.TerminalState

/**
 * The UI-side state of one terminal window.
 *
 * Deliberately separate from [TerminalState] (the Linux/pty process state):
 * minimizing a window, maximizing it or focusing another window never changes
 * whether the child process is alive. [TerminalWindow.processState] carries the
 * process half; this enum carries only presentation.
 */
enum class TerminalWindowUiState {
    /** A newly discovered session, before the desktop focused it. */
    CREATED,

    /** The focused, normal-sized window. */
    ACTIVE,

    /** Hidden to the Recent Terminals overview; the pty keeps running. */
    MINIMIZED,

    /** Focused and filling the desktop content area. */
    MAXIMIZED,

    /** Focused again after being minimized or maximized. */
    RESTORED,

    /** A close was requested; the window is about to leave the list. */
    CLOSING,

    /** Terminal tombstone; closed windows are removed from [TerminalDesktopState.windows]. */
    CLOSED
}

/**
 * One open terminal as the desktop sees it.
 *
 * [title] is the presentation title (app identity first, then the terminal's
 * own OSC title, then a stable `Terminal Tn` fallback). [preview] is a bounded
 * snapshot of THAT session's screen — never a shared or synthesized value.
 */
data class TerminalWindow(
    val terminalId: String,
    val displayLabel: String,
    val title: String,
    val uiState: TerminalWindowUiState,
    val processState: TerminalState,
    val appId: String? = null,
    val appTitle: String? = null,
    val iconKey: String? = null,
    val iconRes: Int? = null,
    val launchCommand: String? = null,
    val runtimeTitle: String? = null,
    val foregroundProcess: String? = null,
    val preview: String = ""
) {
    val isMinimized: Boolean get() = uiState == TerminalWindowUiState.MINIMIZED
    val isMaximized: Boolean get() = uiState == TerminalWindowUiState.MAXIMIZED
    val isClosing: Boolean get() = uiState == TerminalWindowUiState.CLOSING
    val isVisible: Boolean
        get() = uiState != TerminalWindowUiState.MINIMIZED &&
            uiState != TerminalWindowUiState.CLOSING &&
            uiState != TerminalWindowUiState.CLOSED

    /** True while the pty child is alive (Starting counts: it is not closed yet). */
    val hasLiveProcess: Boolean
        get() = processState is TerminalState.Running || processState is TerminalState.Starting
}

/** The input row the [TerminalWindowManager] syncs from the runtime terminal list. */
data class TerminalWindowSession(
    val terminalId: String,
    val displayLabel: String,
    val processState: TerminalState,
    val runtimeTitle: String? = null,
    val foregroundProcess: String? = null
)

/** The whole desktop: ordered windows, focus and maximize state. */
data class TerminalDesktopState(
    val windows: List<TerminalWindow> = emptyList(),
    val activeTerminalId: String? = null,
    val maximizedTerminalId: String? = null
) {
    /** The window that should own the content area, or null when the desktop is idle. */
    val activeWindow: TerminalWindow?
        get() = activeTerminalId?.let { id ->
            windows.firstOrNull { it.terminalId == id && it.isVisible }
        }

    val minimizedWindows: List<TerminalWindow>
        get() = windows.filter { it.isMinimized }

    val isIdle: Boolean get() = activeWindow == null

    fun window(terminalId: String): TerminalWindow? =
        windows.firstOrNull { it.terminalId == terminalId }
}

/**
 * Pure terminal-window reducer (desktop-style window management).
 *
 * The manager owns ONLY presentation state. Session creation, destruction,
 * pty lifetime and process state stay with the runtime
 * (`WorkspaceTerminalManager`); this class is fed the runtime's terminal list
 * and never opens or closes a pty itself. That split is what makes
 * "minimize = hide, not kill" and "switch = re-focus, not recreate" structural
 * rather than accidental.
 *
 * All operations are synchronous and JVM-testable. The ViewModel publishes
 * [state] after each operation.
 */
class TerminalWindowManager {

    var state: TerminalDesktopState = TerminalDesktopState()
        private set

    /**
     * Reconciles the desktop with the runtime's current terminal list.
     *
     * - Existing windows keep their UI state, preview and identity.
     * - New sessions become [TerminalWindowUiState.CREATED].
     * - Sessions that disappeared are removed (a closed window is a tombstone,
     *   not a lingering card).
     * - The active/maximized ids are dropped when their windows disappear.
     * - If the active window is gone and another visible window remains, the
     *   first one is focused so the desktop is never stranded.
     */
    fun syncSessions(
        sessions: List<TerminalWindowSession>,
        identities: Map<String, TerminalWindowIdentity> = emptyMap()
    ): TerminalDesktopState {
        val previous = state.windows.associateBy { it.terminalId }
        var windows = sessions.map { session ->
            val old = previous[session.terminalId]
            val identity = identities[session.terminalId]
            val runtimeTitle = session.runtimeTitle ?: old?.runtimeTitle
            val appTitle = identity?.title ?: old?.appTitle
            val fallback = old?.let { defaultTitle(it.displayLabel) } ?: defaultTitle(session.displayLabel)
            val title = TerminalWindowTitles.resolve(appTitle, runtimeTitle, fallback)
            if (old == null) {
                TerminalWindow(
                    terminalId = session.terminalId,
                    displayLabel = session.displayLabel,
                    title = title,
                    uiState = TerminalWindowUiState.CREATED,
                    processState = session.processState,
                    appId = identity?.appId,
                    appTitle = appTitle,
                    iconKey = identity?.iconKey,
                    iconRes = identity?.iconRes,
                    launchCommand = identity?.command,
                    runtimeTitle = runtimeTitle,
                    foregroundProcess = session.foregroundProcess
                )
            } else {
                old.copy(
                    displayLabel = session.displayLabel,
                    title = title,
                    processState = session.processState,
                    appId = identity?.appId ?: old.appId,
                    appTitle = appTitle,
                    iconKey = identity?.iconKey ?: old.iconKey,
                    iconRes = identity?.iconRes ?: old.iconRes,
                    launchCommand = identity?.command ?: old.launchCommand,
                    runtimeTitle = runtimeTitle,
                    foregroundProcess = session.foregroundProcess ?: old.foregroundProcess
                )
            }
        }

        var active = state.activeTerminalId?.takeIf { id -> windows.any { it.terminalId == id } }
        var maximized = state.maximizedTerminalId?.takeIf { id ->
            windows.any { it.terminalId == id && it.uiState == TerminalWindowUiState.MAXIMIZED }
        }
        if (active == null) {
            val fallback = windows.firstOrNull { it.isVisible }
            if (fallback != null) {
                active = fallback.terminalId
                if (fallback.uiState == TerminalWindowUiState.CREATED) {
                    windows = windows.map {
                        if (it.terminalId == fallback.terminalId) {
                            it.copy(uiState = TerminalWindowUiState.ACTIVE)
                        } else {
                            it
                        }
                    }
                }
            }
        }

        state = TerminalDesktopState(
            windows = windows,
            activeTerminalId = active,
            maximizedTerminalId = maximized
        )
        return state
    }

    /** Focus a window; restoring a minimized window gives it RESTORED state. */
    fun activate(terminalId: String): TerminalDesktopState {
        val target = state.window(terminalId) ?: return state
        val nextState = when (target.uiState) {
            TerminalWindowUiState.MINIMIZED,
            TerminalWindowUiState.RESTORED -> TerminalWindowUiState.RESTORED
            TerminalWindowUiState.MAXIMIZED -> TerminalWindowUiState.MAXIMIZED
            TerminalWindowUiState.CLOSING,
            TerminalWindowUiState.CLOSED -> return state
            else -> TerminalWindowUiState.ACTIVE
        }
        val wasMaximized = state.maximizedTerminalId != null &&
            state.maximizedTerminalId != terminalId
        state = state.copy(
            windows = state.windows.map {
                when {
                    it.terminalId == terminalId -> it.copy(uiState = nextState)
                    wasMaximized && it.isMaximized -> it.copy(uiState = TerminalWindowUiState.RESTORED)
                    else -> it
                }
            },
            activeTerminalId = terminalId,
            maximizedTerminalId = if (nextState == TerminalWindowUiState.MAXIMIZED) {
                terminalId
            } else {
                null
            }
        )
        return state
    }

    /** Hide a window to the overview. The pty and its output continue. */
    fun minimize(terminalId: String): TerminalDesktopState {
        if (state.window(terminalId) == null) return state
        state = state.copy(
            windows = state.windows.map {
                if (it.terminalId == terminalId) {
                    it.copy(uiState = TerminalWindowUiState.MINIMIZED)
                } else {
                    it
                }
            },
            // Minimizing the focused window leaves the desktop idle (the
            // overview/dock owns restore); minimizing a background window must
            // not steal focus from the window the user is actually looking at.
            activeTerminalId = if (state.activeTerminalId == terminalId) {
                terminalId
            } else {
                state.activeTerminalId
            },
            maximizedTerminalId = state.maximizedTerminalId?.takeUnless { it == terminalId }
        )
        return state
    }

    /** Restore a minimized/maximized window to normal focus. */
    fun restore(terminalId: String): TerminalDesktopState {
        val target = state.window(terminalId) ?: return state
        if (target.isClosing) return state
        val nextState = if (target.isMinimized) {
            TerminalWindowUiState.RESTORED
        } else {
            TerminalWindowUiState.ACTIVE
        }
        state = state.copy(
            windows = state.windows.map {
                if (it.terminalId == terminalId) it.copy(uiState = nextState) else it
            },
            activeTerminalId = terminalId,
            maximizedTerminalId = state.maximizedTerminalId?.takeUnless { it == terminalId }
        )
        return state
    }

    /** Focus a window and fill the desktop area with it. */
    fun maximize(terminalId: String): TerminalDesktopState {
        val target = state.window(terminalId) ?: return state
        if (target.isClosing) return state
        state = state.copy(
            windows = state.windows.map {
                when (it.terminalId) {
                    terminalId -> it.copy(uiState = TerminalWindowUiState.MAXIMIZED)
                    else -> if (it.isMaximized) {
                        it.copy(uiState = TerminalWindowUiState.RESTORED)
                    } else {
                        it
                    }
                }
            },
            activeTerminalId = terminalId,
            maximizedTerminalId = terminalId
        )
        return state
    }

    /** The title bar's Maximize/Restore control. */
    fun toggleMaximize(terminalId: String): TerminalDesktopState {
        val target = state.window(terminalId) ?: return state
        return if (target.isMaximized) restore(terminalId) else maximize(terminalId)
    }

    /** Drop every window (workspace detached / service gone). */
    fun clear(): TerminalDesktopState {
        state = TerminalDesktopState()
        return state
    }

    /** Mark a window as closing; the runtime close is the caller's job. */
    fun beginClose(terminalId: String): TerminalDesktopState {
        if (state.window(terminalId) == null) return state
        state = state.copy(
            windows = state.windows.map {
                if (it.terminalId == terminalId) {
                    it.copy(uiState = TerminalWindowUiState.CLOSING)
                } else {
                    it
                }
            }
        )
        return state
    }

    /**
     * Remove a window after the runtime closed it. Picks a visible sibling as
     * the next focus when the closed window was active, so the desktop never
     * keeps pointing at a closed id.
     */
    fun finishClose(terminalId: String): TerminalDesktopState {
        val remaining = state.windows.filterNot { it.terminalId == terminalId }
        if (remaining.size == state.windows.size) return state
        var active = state.activeTerminalId?.takeIf { id -> remaining.any { it.terminalId == id } }
        if (active == null) {
            val fallback = remaining.firstOrNull { it.isVisible }
            active = fallback?.terminalId
        }
        val windows = if (active != null) {
            remaining.map {
                if (it.terminalId == active && it.uiState == TerminalWindowUiState.CREATED) {
                    it.copy(uiState = TerminalWindowUiState.ACTIVE)
                } else {
                    it
                }
            }
        } else {
            remaining
        }
        state = TerminalDesktopState(
            windows = windows,
            activeTerminalId = active,
            maximizedTerminalId = state.maximizedTerminalId?.takeUnless { it == terminalId }
        )
        return state
    }

    /** Update the terminal's own OSC title (ignored while an app identity owns the title). */
    fun updateRuntimeTitle(terminalId: String, runtimeTitle: String?): TerminalDesktopState {
        val target = state.window(terminalId) ?: return state
        val cleaned = TerminalWindowTitles.clean(runtimeTitle)
        state = state.copy(
            windows = state.windows.map {
                if (it.terminalId == terminalId) {
                    it.copy(
                        runtimeTitle = cleaned,
                        title = TerminalWindowTitles.resolve(
                            appTitle = it.appTitle,
                            runtimeTitle = cleaned,
                            fallback = defaultTitle(it.displayLabel)
                        )
                    )
                } else {
                    it
                }
            }
        )
        return state
    }

    /** Replace one window's preview/foreground probe (per-session isolation). */
    fun updatePreview(
        terminalId: String,
        preview: String,
        foregroundProcess: String? = null
    ): TerminalDesktopState {
        if (state.window(terminalId) == null) return state
        state = state.copy(
            windows = state.windows.map {
                if (it.terminalId == terminalId) {
                    it.copy(
                        preview = preview,
                        // Exact assignment: when an interactive program exits,
                        // the next probe legitimately reports no foreground
                        // process and the stale program name must disappear.
                        foregroundProcess = foregroundProcess
                    )
                } else {
                    it
                }
            }
        )
        return state
    }

    /**
     * Closing a window with a live pty is destructive; the UI must confirm it.
     * This is a pure predicate so the dialog and the window list cannot drift.
     */
    fun requiresCloseConfirmation(terminalId: String): Boolean =
        state.window(terminalId)?.hasLiveProcess == true

    companion object {
        fun defaultTitle(displayLabel: String): String =
            if (displayLabel.isBlank()) "Terminal" else "Terminal $displayLabel"
    }
}

/** Title precedence: app/shortcut identity → terminal OSC title → stable fallback. */
object TerminalWindowTitles {
    private const val MAX_TITLE_CHARS = 80
    private val CONTROL = Regex("[\\x00-\\x1F\\x7F]")

    fun resolve(appTitle: String?, runtimeTitle: String?, fallback: String): String {
        clean(appTitle)?.let { return it }
        clean(runtimeTitle)?.let { return it }
        return fallback.ifBlank { "Terminal" }
    }

    fun clean(raw: String?): String? = raw
        ?.replace(CONTROL, "")
        ?.trim()
        ?.take(MAX_TITLE_CHARS)
        ?.ifBlank { null }
}
