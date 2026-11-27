package com.fem.linexa.service.runtime.pty

import com.fem.linexa.core.filesystem.ProcessIdentity
import com.fem.linexa.domain.terminal.TerminalPreview
import kotlinx.coroutines.flow.SharedFlow

/**
 * A real, allocated pseudo-terminal bound to one child process (§1.20).
 *
 * This is the seam that keeps the rest of Linexa from depending on any
 * specific terminal-emulation library. [TermuxPtySession] is the only
 * implementation in v1 (the vendored Apache-2.0 Termux terminal modules —
 * `:terminal-emulator` / `:terminal-view`, copied from termux/termux-app
 * v0.118.3; see those modules' PROVENANCE.md files, and AGENTS.md for why
 * Linexa doesn't otherwise resemble Termux's app). The Terminal UI screen
 * attaches a real `TerminalView` on top of the concrete session (see
 * [TermuxTerminalSessionProvider] / `RealTerminalView.kt`) for full
 * ANSI/cursor-accurate rendering — every *other* consumer stays on this
 * interface.
 */
interface PtySession {
    val id: String
    val workspaceId: String

    /**
     * 2026-08-29 (appearance fix): HOST-side path of this terminal's Linux
     * HOME (the workspace rootfs home), or null. The terminal layer reads its
     * OWN appearance configuration from there (`.termux/font.ttf`; colors
     * were applied at session creation) — terminal-owned config, not app
     * settings. Default null for fakes/backends without a Linux home.
     */
    val terminalConfigHome: String? get() = null

    /** OS process id of the child running behind this pty. */
    val pid: Int

    /**
     * SS-001: host identity (PID + kernel start time + process group) of the
     * pty child, captured when the child was launched. Container teardown
     * uses it to snapshot and terminate the whole component tree *before* the
     * child can die: PRoot's tracees are ordinary host processes that
     * reparent to init once their tracer is gone, and a bare PID is never
     * safe to signal after recycling. Null for fakes/backends that cannot
     * expose a host process.
     */
    val processIdentity: ProcessIdentity? get() = null

    /** True while the child process is alive. */
    val isAlive: Boolean

    /** Valid only once [isAlive] is false. */
    val exitCode: Int?

    /**
     * The terminal's own title (OSC 0/1/2), or null when the program has not
     * set one. Presentation only: the desktop falls back to the app identity
     * or a stable `Terminal Tn` label.
     */
    val title: String? get() = null

    /**
     * The program currently in the pty's foreground process group, when the
     * backend can observe it. Best-effort and display-only.
     */
    fun foregroundProcessName(): String? = null

    /**
     * A bounded, display-safe snapshot of THIS session's visible screen, for
     * the Recent Terminals window cards.
     *
     * The default reads [recentOutput] (the transcript tail) so fakes and
     * diagnostic backends still produce honest per-session content; the real
     * Termux backend overrides it with the emulator's visible screen. It is
     * never a shared buffer and never synthesized text.
     */
    fun previewText(
        maxLines: Int = TerminalPreview.DEFAULT_MAX_LINES,
        maxChars: Int = TerminalPreview.DEFAULT_MAX_CHARS
    ): String = TerminalPreview.sanitize(
        recentOutput(maxChars.coerceAtLeast(0) * 2),
        maxLines = maxLines,
        maxChars = maxChars
    )

    /** Emits whenever the terminal screen, title, or bell state changes. */
    val events: SharedFlow<PtyEvent>

    /** Write raw bytes to the child process's stdin, through the pty. */
    fun write(data: ByteArray)

    /** Write UTF-8 text to the child process's stdin, through the pty. */
    fun write(text: String)

    /** Inform the pty of a terminal resize (columns/rows, not pixels). */
    fun resize(columns: Int, rows: Int)

    /**
     * A plain-text snapshot of the current screen + scrollback transcript.
     *
     * **Diagnostics, not rendering.** The single production caller is
     * [com.fem.linexa.service.runtime.WorkspaceSessionSupervisor] (§1.76),
     * which reads it when the pty child is already dead at open so the guest's
     * own failure output becomes the surfaced error reason. Display goes
     * through `RealTerminalView`, which reads the emulator's screen state
     * directly and never calls this.
     *
     * §26.6/§16.2 (terminal audit): this kdoc used to point at a text-only
     * Terminal screen as the caller and told readers a real terminal-emulator
     * UI should bypass it "once that UI exists". The UI exists and this was
     * never its input path. `PtyTranscriptRoleSourceTest` pins the caller set
     * so the correction cannot silently rot again.
     */
    fun currentTranscript(): String

    /**
     * A bounded plain-text tail of the session's output, for the terminal's
     * own **Copy** action (the Haven ⋯ sheet's "copy the selection or last
     * output").
     *
     * A deliberate sibling of — not a reuse of — [currentTranscript]: that
     * method is the diagnostics channel with a single pinned production
     * caller (PtyTranscriptRoleSourceTest), and letting a UI copy action
     * borrow it would quietly re-role a diagnostics dump as a product
     * surface. [recentOutput] is the product surface, with exactly one
     * contract: the most recent [maxChars] characters of the transcript,
     * best-effort (empty when no emulator is attached).
     */
    fun recentOutput(maxChars: Int): String

    /** Suspends until the child process exits, then returns its exit code. */
    suspend fun awaitExit(): Int

    /** Forcibly terminate the child process and release pty resources. Idempotent. */
    fun destroy()
}

sealed interface PtyEvent {
    /** New output, a color change, or anything else that redraws the screen. */
    data object ScreenUpdated : PtyEvent
    data class TitleChanged(val title: String?) : PtyEvent
    data object Bell : PtyEvent

    /** The terminal asked for [text] to be copied to the host clipboard
     * (OSC 52, or the terminal's own selection-toolbar Copy action). */
    data class ClipboardText(val text: String) : PtyEvent

    /** The terminal (its selection-toolbar Paste action) requests the host
     * clipboard be written into the pty. The terminal screen is the party that
     * actually owns the Android clipboard, so it receives the request as an
     * event rather than the session touching the clipboard service itself. */
    data object PasteRequested : PtyEvent
    data class Finished(val exitCode: Int) : PtyEvent
}
