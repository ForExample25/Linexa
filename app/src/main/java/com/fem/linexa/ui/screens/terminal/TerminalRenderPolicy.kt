package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.domain.terminal.TerminalDesktopState
import com.fem.linexa.domain.terminal.TerminalWindow
import com.fem.linexa.service.runtime.pty.PtySession

/**
 * Pure header/content pairing for the workspace terminal panel.
 *
 * The desktop's active window is the authority for *selection*, but the
 * terminal ViewModel is bound to a terminal one frame later (its attach effect
 * runs after composition). Rendering the new header and the old pty in that
 * gap is exactly the header/content identity mismatch this policy removes:
 *
 *  - the rendered window is the window the ViewModel is actually bound to,
 *    as long as it is still visible in the desktop;
 *  - otherwise the desktop's active window is used (first frame / transition);
 *  - the pty is exposed only when the bound terminal id equals the rendered
 *    window's id. A stale pty is never shown under another window's header.
 *
 * The panel therefore always paints at most ONE window identity and, when
 * present, exactly that window's pty.
 */
object TerminalRenderPolicy {

    data class Render(
        val window: TerminalWindow?,
        val ptySession: PtySession?,
        /** True when [ptySession] belongs to [window]'s terminal id. */
        val identityMatches: Boolean
    )

    fun select(
        desktop: TerminalDesktopState,
        boundTerminalId: String?,
        ptyTerminalId: String?,
        ptySession: PtySession?
    ): Render {
        val boundWindow = boundTerminalId
            ?.let { id -> desktop.window(id) }
            ?.takeIf { it.isVisible }
        val window = boundWindow ?: desktop.activeWindow
        val identityMatches = window != null && boundTerminalId == window.terminalId
        val ptyMatches = ptySession != null &&
            window != null &&
            ptyTerminalId == window.terminalId
        return Render(
            window = window,
            ptySession = ptySession.takeIf { ptyMatches },
            identityMatches = identityMatches
        )
    }
}
