package com.fem.linexa.service.runtime.pty

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.RuntimeTrace
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Real-PTY backend on the vendored Apache-2.0 Termux terminal modules
 * (`com.termux.terminal.TerminalSession`, from `:terminal-emulator`) — the
 * 2026-08-29 replacement for the former GPL-3.0-only
 * `com.github.tiny-computer:terminal-lib` (`com.offsec.nhterm.*`), which this
 * class previously wrapped one-for-one. forkpty happens inside that module's
 * own native code (`JNI.createSubprocess`, backed by the vendored
 * `libtermux.so`, built from the module's own `jni/termux.c`); Linexa does not
 * implement, vendor, or touch any forkpty JNI itself, per the brief this was
 * adopted from.
 *
 * This class does not paint anything itself, but the session it owns *is*
 * rendered: [TermuxTerminalSessionProvider] exposes [termuxSession], and
 * `RealTerminalView.kt` attaches the vendored `TerminalView` to it, which is
 * the only rendering path in the app. Escape sequences are therefore
 * interpreted by the module's emulator (cursor state, colours, alternate
 * screen) *and* drawn from that same emulator state — this class is the
 * process/pty half of one pipeline, not a stand-in for a renderer that does
 * not exist.
 *
 * [currentTranscript] survives for diagnostics only — see [PtySession].
 */
class TermuxPtySession(
    override val workspaceId: String,
    executable: String,
    args: List<String>,
    env: Map<String, String>,
    workingDirectory: String,
    columns: Int,
    rows: Int,
    override val terminalConfigHome: String?,
    private val runtimeTrace: RuntimeTrace
) : PtySession, TermuxTerminalSessionProvider {

    override val id: String = UUID.randomUUID().toString()

    private val _events = MutableSharedFlow<PtyEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PtyEvent> = _events.asSharedFlow()

    /**
     * 2026-08-30 (session-lifecycle fix): the deterministic repaint signal —
     * see [TermuxTerminalSessionProvider.screenRevision]. Bumped by the two
     * emulator callbacks that change what the terminal view draws: screen
     * output (`onTextChanged`) and runtime colors (`onColorsChanged` — OSC
     * 4/10/11/12). Conflated StateFlow semantics make the CURRENT value
     * observable at subscribe time, so the view can never miss the fact that
     * the emulator it just bound already has (or is about to receive) content.
     */
    private val _screenRevision = MutableStateFlow(0)
    override val screenRevision: StateFlow<Int> = _screenRevision.asStateFlow()

    private fun bumpScreenRevision() {
        _screenRevision.value += 1
    }

    private val exitDeferred = CompletableDeferred<Int>()

    /** §1.68 chain diagnostic: session age anchor. */
    private val createdAtMs = System.currentTimeMillis()

    private fun ageMs(): String = "${System.currentTimeMillis() - createdAtMs}ms"

    init {
        // 2026-08-29 (appearance fix, Issue 3): terminal-owned color scheme.
        // If the workspace's HOME carries a Termux-format
        // `.termux/colors.properties`, apply it to the terminal color scheme
        // BEFORE the emulator is constructed (the emulator copies the scheme
        // at construction). Parse errors are logged and ignored — a malformed
        // file must never block a terminal. Runtime (session-only) color
        // changes stay the OSC layer's domain (OSC 4/10/11/12 mutate the
        // emulator's own current colors; OSC 104 resets to this scheme).
        // Haven UI tokens never touch this path.
        if (terminalConfigHome != null) {
            try {
                val props = java.util.Properties()
                java.io.File(terminalConfigHome, ".termux/colors.properties")
                    .takeIf { it.isFile }
                    ?.inputStream()?.use { props.load(it) }
                if (props.size > 0) {
                    // Parse into a scratch scheme first: updateWith is strict
                    // (throws on unknown keys/invalid colors) and partially
                    // mutates — a scratch copy keeps the live scheme intact on
                    // failure.
                    val scratch = TerminalColorScheme()
                    scratch.updateWith(props)
                    System.arraycopy(
                        scratch.mDefaultColors, 0,
                        TerminalColors.COLOR_SCHEME.mDefaultColors, 0,
                        TextStyle.NUM_INDEXED_COLORS
                    )
                    Logger.i(TAG, "Terminal colors applied from $terminalConfigHome/.termux/colors.properties (${props.size} keys)")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Ignoring invalid .termux/colors.properties: ${e.message}")
            }
        }
    }

    private val callback = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession?) {
            bumpScreenRevision()
            _events.tryEmit(PtyEvent.ScreenUpdated)
        }

        override fun onTitleChanged(changedSession: TerminalSession?) {
            _events.tryEmit(PtyEvent.TitleChanged(changedSession?.title))
        }

        override fun onSessionFinished(finishedSession: TerminalSession?) {
            val code = finishedSession?.exitStatus ?: -1
            Logger.w("TermuxPtySession", "Session for $workspaceId finished, exit=$code (was pid=${finishedSession?.pid ?: "?"})")
            Logger.i("TerminalChain", "CHAIN: onSessionFinished workspaceId=$workspaceId exit=$code age=${ageMs()}")
            // §1.68: the child (tmux / proot / shell) writes its stderr to the
            // pty, so the emulator transcript holds the real failure message.
            // Capture the tail now — the transcript is read back out when a
            // non-zero exit is surfaced as RuntimeState.Error.
            val outputTail = transcriptTail()
            if (outputTail.isNotEmpty()) {
                Logger.w(TAG, "Child output tail for $workspaceId (${outputTail.length} chars):\n$outputTail")
            }
            runtimeTrace.record(
                workspaceId,
                "onSessionFinished exit=$code lifetime=${ageMs()} " +
                    "childOutput=${transcriptTailPreview(outputTail)}"
            )
            exitDeferred.complete(code)
            _events.tryEmit(PtyEvent.Finished(code))
        }

        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
            // OSC 52 and the vendored TerminalView's selection Copy action both
            // land here; the terminal screen owns the Android clipboard, so the
            // request travels out as an event (see RealTerminalView's collector).
            if (text != null) _events.tryEmit(PtyEvent.ClipboardText(text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            // The vendored selection toolbar's Paste button. Same ownership
            // reasoning as onCopyTextToClipboard: the screen reads the Android
            // clipboard and writes it into the pty.
            _events.tryEmit(PtyEvent.PasteRequested)
        }

        override fun onBell(session: TerminalSession?) {
            _events.tryEmit(PtyEvent.Bell)
        }

        override fun onColorsChanged(session: TerminalSession?) {
            bumpScreenRevision()
            _events.tryEmit(PtyEvent.ScreenUpdated)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            // Cursor visibility (DECTCEM). Rendering reacts through the next
            // screen update; no separate app-side handling needed.
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        // ── Logging callbacks: bridge to Linexa's own logger. ──
        override fun logError(tag: String?, message: String?) {
            Logger.e(tag ?: TAG, message ?: "")
        }

        override fun logWarn(tag: String?, message: String?) {
            Logger.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Logger.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logVerbose(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Logger.e(tag ?: TAG, "$message\n${e?.toString() ?: ""}")
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Logger.e(tag ?: TAG, e?.toString() ?: "")
        }
    }

    // The upstream session constructor is LAZY: it stores the launch
    // configuration only, and the child process is forked on the first
    // updateSize() call (initializeEmulator is called while the emulator is
    // null) — exactly the lifecycle the former GPL backend had, which the
    // §1.145 fork-on-caller's-thread rule was written against.
    //
    // §1.30: argv[0] MUST be the program path itself, never the first flag.
    // The vendored module's native create_subprocess builds the child argv
    // verbatim from this array and then execvp(executable, argv) — it does NOT
    // prepend the executable. Prepend here (the single point every PtySession
    // goes through) so callers keep passing "just arguments" and argv[0] is
    // correct for executables that dispatch on it (BusyBox applets, proot's
    // /proc-based argv[0] echoes, tmux's own name, etc.).
    //
    // Scrollback: 2000 transcript rows, Termux's own DEFAULT_TERMINAL_TRANSCRIPT_ROWS.
    private val session = TerminalSession(
        executable,
        workingDirectory,
        (listOf(executable) + args).toTypedArray(),
        env.map { (key, value) -> "$key=$value" }.toTypedArray(),
        TRANSCRIPT_ROWS,
        callback
    )

    init {
        Logger.i("TerminalChain", "CHAIN: ENTER TermuxPtySession.init (TerminalSession constructed)")
        runtimeTrace.record(workspaceId, "TermuxPtySession constructor ENTER")
        // The first updateSize() call both sets pty geometry AND starts the
        // child process (TerminalSession.initializeEmulator is only called
        // while its emulator is null) — this is not just cosmetic. Cell pixel
        // sizes are unknown at fork time; the attached TerminalView re-issues
        // updateSize with real metrics as soon as it lays out.
        runtimeTrace.record(workspaceId, "TerminalSession.updateSize ENTER (starts child)")
        session.updateSize(columns, rows, 0, 0)
        runtimeTrace.record(workspaceId, "TerminalSession.updateSize RETURN")
        // §1.59 chain audit: record what the pty actually spawned. A
        // non-positive pid here means the child failed to start (fork/exec
        // error inside the module's native code) — the session then exits
        // almost immediately and `onSessionFinished` logs the code.
        Logger.i(
            TAG,
            "PTY started workspaceId=$workspaceId pid=${session.pid} isRunning=${session.isRunning} " +
                "exe=$executable argv=${(listOf(executable) + args).joinToString(" ").take(384)}"
        )
        runtimeTrace.record(workspaceId, "child pid=${session.pid} isRunning=${session.isRunning} exe=$executable")
        Logger.i(
            "TerminalChain",
            "CHAIN: updateSize RETURNED pid=${session.pid} isRunning=${session.isRunning} age=${ageMs()}"
        )
        runtimeTrace.record(workspaceId, "TermuxPtySession constructor EXIT pid=${session.pid}")
    }

    override val pid: Int get() = session.pid
    override val isAlive: Boolean get() = session.isRunning
    override val exitCode: Int? get() = if (session.isRunning) null else session.exitStatus
    override val termuxSession: TerminalSession get() = session

    override fun write(data: ByteArray) = session.write(data, 0, data.size)
    override fun write(text: String) = session.write(text)

    /**
     * Inform the pty of a terminal resize (columns/rows, not pixels). Kept for
     * non-UI callers only: the terminal screen must route resizes through the
     * attached `TerminalView` (`TerminalView.updateSize` →
     * `TerminalSession.updateSize` → `JNI.setPtyWindowSize`), which also knows
     * the real cell metrics, never through this method.
     */
    override fun resize(columns: Int, rows: Int) = session.updateSize(columns, rows, 0, 0)

    override fun currentTranscript(): String =
        session.emulator?.screen?.getTranscriptText().orEmpty()

    override fun recentOutput(maxChars: Int): String {
        val full = session.emulator?.screen?.getTranscriptText().orEmpty()
        if (full.length <= maxChars) return full
        return full.takeLast(maxChars)
    }

    override val title: String? get() = session.title

    override fun foregroundProcessName(): String? =
        ProcForegroundProcess.foregroundProcessName(pid)

    /**
     * Overview-card preview: the emulator's CURRENT VISIBLE screen only —
     * not the whole transcript. The buffer clamps the requested rectangle to
     * its real columns/rows, so the large bounds below are safe and give the
     * exact viewport the user sees in the live window, including whatever a
     * full-screen program painted.
     *
     * Best-effort: the terminal reader thread can resize the buffer while the
     * overview polls, so a racing read returns an empty preview rather than
     * crashing the desktop.
     */
    override fun previewText(maxLines: Int, maxChars: Int): String {
        val visible = try {
            session.emulator?.screen
                ?.getSelectedText(0, 0, PREVIEW_MAX_COLUMNS, PREVIEW_MAX_ROWS)
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
        return com.fem.linexa.domain.terminal.TerminalPreview.sanitize(
            visible,
            maxLines = maxLines,
            maxChars = maxChars
        )
    }

    /**
     * §1.68: printable tail of the pty's scrollback transcript — the real
     * stderr/stdout the child wrote before it exited (tmux error message,
     * proot abort line, shell "not found", …). Control frames are stripped
     * but UTF-8 text is preserved so the message stays readable; the tail
     * is capped so a huge interactive transcript can't blow the error card.
     */
    private fun transcriptTail(maxChars: Int = MAX_CAPTURE_CHARS): String {
        val raw = currentTranscript()
        if (raw.isEmpty()) return ""
        // Drop C0 control frames except \n \r \t; keep every printable char
        // including UTF-8 multibyte (>=0x20 cannot appear inside a multibyte
        // sequence's continuation handling concern is irrelevant here: we only
        // remove characters, which never splits a UTF-8 codepoint).
        val printable = raw.replace(TRAIL_CONTROL_RE, "")
        val trimmed = printable.trimEnd()
        return if (trimmed.length > maxChars) trimmed.takeLast(maxChars) else trimmed
    }

    private fun transcriptTailPreview(tail: String, maxChars: Int = 240): String =
        if (tail.length <= maxChars) tail else tail.take(maxChars) + "…(${tail.length} chars total)"

    override suspend fun awaitExit(): Int {
        Logger.i("TerminalChain", "CHAIN: ENTER awaitExit workspaceId=$workspaceId age=${ageMs()}")
        runtimeTrace.record(workspaceId, "awaitExit start")
        val code = exitDeferred.await()
        Logger.i("TerminalChain", "CHAIN: awaitExit RETURNED code=$code workspaceId=$workspaceId age=${ageMs()}")
        runtimeTrace.record(workspaceId, "awaitExit end code=$code")
        return code
    }

    override fun destroy() {
        session.finishIfRunning()
    }

    private companion object {
        const val TAG = "TermuxPtySession"

        /**
         * Scrollback depth, in transcript rows: Termux's own
         * `TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS` (2000; upstream
         * clamps to 100..50000). Chosen so normal interactive history survives
         * without an artificially small cap.
         */
        const val TRANSCRIPT_ROWS: Int = 2000

        /** §1.68: max chars of child pty output surfaced on a failed session. */
        const val MAX_CAPTURE_CHARS = 1200

        /**
         * Bounds for the overview preview rectangle. The emulator clamps to its
         * real viewport, so these only need to be larger than any plausible
         * screen (a huge external display still fits comfortably).
         */
        const val PREVIEW_MAX_COLUMNS = 10_000
        const val PREVIEW_MAX_ROWS = 10_000

        /** §1.68: strip C0 control frames (\x00–\x08, \x0B, \x0C, \x0E–\x1F) but keep \n \r \t. */
        val TRAIL_CONTROL_RE = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")
    }
}
