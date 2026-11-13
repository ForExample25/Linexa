package com.fem.linexa.service.runtime.pty

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Implemented by [PtySession] backends that are literally a vendored
 * `com.termux.terminal.TerminalSession` (the Apache-2.0 `:terminal-emulator`
 * module) under the hood, so the terminal screen can attach the vendored
 * `com.termux.view.TerminalView` (the `:terminal-view` module) directly to it
 * for full ANSI/cursor-accurate rendering.
 *
 * This is the **one deliberate leak** of the terminal-implementation type into
 * Linexa's own code, and it replaces the old GPL `TerminalLibSession` escape
 * hatch one-for-one. It is deliberately **not** part of [PtySession] itself:
 * every other consumer (diagnostics, exec-style callers, a future non-Termux
 * backend) keeps depending only on the library-agnostic [PtySession] interface.
 * Only the terminal screen's adapter (`RealTerminalView`) may reach through
 * this — a rule pinned by `ImportBoundaryTest`'s `com.termux` confinement
 * rule, so a second consumer shows up as a failing test rather than as a
 * silently widened coupling.
 */
interface TermuxTerminalSessionProvider {
    val termuxSession: TerminalSession

    /**
     * 2026-08-30 (session-lifecycle fix): a monotonically increasing repaint
     * revision, bumped every time the emulator's VISIBLE state changes (screen
     * output, OSC color changes). This is the terminal view's **deterministic
     * repaint signal** — a conflated [StateFlow], so a subscriber that attaches
     * at ANY point (fresh view, re-attach after a session switch, a view whose
     * events collector lost the race against the shell's first output) always
     * receives the CURRENT value immediately and repaints the emulator's
     * current state, and every later bump repaints again.
     *
     * The previous repaint path — [PtyEvent.ScreenUpdated] on a replay-less
     * `SharedFlow` — dropped every notification emitted before the view's
     * collector subscribed (or while it was cancelled mid-switch), which left
     * freshly created/re-attached sessions painting their bind-time (empty)
     * frame forever: "new session → black until the session exits". A repaint
     * signal must be state, not an event stream.
     */
    val screenRevision: StateFlow<Int>
}
