package com.fem.linexa.service.runtime.pty

import com.fem.linexa.core.filesystem.ProcessIdentity
import com.fem.linexa.core.filesystem.ProcessTableReader
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.perf.PerfCounters
import com.fem.linexa.core.perf.PerfTrace
import com.fem.linexa.service.runtime.RuntimeTrace
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalInstrumentation
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * SS-002 + F-021: discrete events must reach consumers, but the queue that
     * carries them must not be unbounded. The old design drained an UNLIMITED
     * channel, so a guest that floods OSC 52 (ClipboardText) or repeatedly
     * retitles itself could grow the queue for as long as a subscriber lagged
     * — the one unbounded buffer in the terminal pipeline.
     *
     * The queue is now split by consequence:
     *
     *  * [eventChannel] carries *transient* events (title, bell, clipboard,
     *    paste) in a fixed 64-slot buffer with DROP_OLDEST. Losing an old
     *    clipboard chunk or an old title under a flood is harmless: the newest
     *    value is the meaningful one, and every drop is counted
     *    ([PerfCounters.ptyEventDropped]).
     *  * [lifecycleChannel] carries [PtyEvent.Finished], which is emitted at
     *    most once per session and must never be lost. Its capacity is
     *    UNLIMITED by type, not by traffic: exactly one producer send exists
     *    and it is guarded by [finishQueued].
     *
     * ScreenUpdated keeps using the 64-slot shared flow's `tryEmit`:
     * repaint correctness is already guaranteed by the conflated
     * [screenRevision] StateFlow, so coalescing it is both safe and desirable.
     */
    private val eventChannel = Channel<PtyEvent>(
        capacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { PerfCounters.ptyEventDropped() }
    )
    private val lifecycleChannel = Channel<PtyEvent>(Channel.UNLIMITED)
    private val finishQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventPump = eventScope.launch {
        for (event in eventChannel) _events.emit(event)
    }
    private val lifecyclePump = eventScope.launch {
        for (event in lifecycleChannel) _events.emit(event)
    }

    private fun emitEvent(event: PtyEvent) {
        if (event is PtyEvent.Finished) {
            // One-shot by construction: a second Finished can only be a
            // duplicate callback and carries no new state.
            if (finishQueued.compareAndSet(false, true)) lifecycleChannel.trySend(event)
        } else {
            eventChannel.trySend(event)
        }
    }

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
        PerfCounters.screenRevision()
    }

    private val exitDeferred = CompletableDeferred<Int>()

    /** §1.68 chain diagnostic: session age anchor. */
    private val createdAtMs = System.currentTimeMillis()

    private fun ageMs(): String = "${System.currentTimeMillis() - createdAtMs}ms"

    /**
     * 2026-08-29 (appearance fix, Issue 3): terminal-owned color scheme.
     * If the workspace's HOME carries a Termux-format
     * `.termux/colors.properties`, parse it here — BEFORE the emulator is
     * constructed (the emulator copies the scheme at construction). Parse
     * errors are logged and ignored; a malformed file must never block a
     * terminal. Runtime (session-only) color changes stay the OSC layer's
     * domain (OSC 4/10/11/12 mutate the emulator's own current colors;
     * OSC 104 resets to this scheme).
     *
     * RN-006: the result is a PER-SESSION scheme handed to this session's
     * emulator. It deliberately does not touch the process-global palette
     * singleton: that object is shared by
     * every terminal in the process, so applying one workspace's file there
     * changed the defaults (and OSC 104 results) of terminals in other
     * workspaces. Haven UI tokens never touch this path.
     */
    private val configuredColorScheme: TerminalColorScheme? = terminalConfigHome?.let { configHome ->
        try {
            val props = java.util.Properties()
            java.io.File(configHome, ".termux/colors.properties")
                .takeIf { it.isFile }
                ?.inputStream()?.use { props.load(it) }
            if (props.size > 0) {
                // Parse into a scratch scheme: updateWith is strict (throws on
                // unknown keys/invalid colors) and partially mutates, so an
                // exception leaves only the scratch object inconsistent.
                val scratch = TerminalColorScheme()
                scratch.updateWith(props)
                Logger.i(TAG, "Terminal colors applied from $configHome/.termux/colors.properties (${props.size} keys)")
                scratch
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Ignoring invalid .termux/colors.properties: ${e.message}")
            null
        }
    }

    private val callback = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession?) {
            // Phase M0-A: the vendored module cannot read DebugCapture, so this
            // sanctioned adapter mirrors the flag on each output batch. Volatile
            // write only; no logging/allocation.
            TerminalInstrumentation.setEnabled(DebugCapture.isEnabled())
            bumpScreenRevision()
            PerfCounters.screenUpdate()
            _events.tryEmit(PtyEvent.ScreenUpdated)
        }

        override fun onFirstOutput(changedSession: TerminalSession?, bytesRead: Int) {
            // Phase M0-A (E6): first pty output, correlated by the session id
            // attached before the child was forked. Not a prompt-ready signal.
            PerfTrace.markFirstOutput(id, bytesRead)
        }

        override fun onTitleChanged(changedSession: TerminalSession?) {
            emitEvent(PtyEvent.TitleChanged(changedSession?.title))
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
            emitEvent(PtyEvent.Finished(code))
            eventChannel.close()
            lifecycleChannel.close()
        }

        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
            // OSC 52 and the vendored TerminalView's selection Copy action both
            // land here; the terminal screen owns the Android clipboard, so the
            // request travels out as an event (see RealTerminalView's collector).
            if (text != null) emitEvent(PtyEvent.ClipboardText(text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            // The vendored selection toolbar's Paste button. Same ownership
            // reasoning as onCopyTextToClipboard: the screen reads the Android
            // clipboard and writes it into the pty.
            emitEvent(PtyEvent.PasteRequested)
        }

        override fun onBell(session: TerminalSession?) {
            emitEvent(PtyEvent.Bell)
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
        // Phase M0-A: mirror capture into the vendored module (it cannot read
        // DebugCapture), then correlate this pty with the workspace trace BEFORE
        // updateSize forks the child — a very fast first read must find its record.
        TerminalInstrumentation.setEnabled(DebugCapture.isEnabled())
        PerfTrace.attachPty(workspaceId, id)
        // RN-006: install the workspace's own scheme on THIS session before
        // the emulator exists. TerminalSession forwards it to the emulator at
        // construction; the process-global default is never mutated.
        configuredColorScheme?.let { session.setColorScheme(it) }
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

    /**
     * SS-001: captured once, immediately after the child is forked. The
     * container snapshots this tree before [destroy] can kill the pty child;
     * the start time makes a recycled PID unreachable.
     */
    override val processIdentity: ProcessIdentity? = session.pid
        .takeIf { it > 0 }
        ?.let { p ->
            ProcessTableReader.readEntry(p)?.let {
                ProcessIdentity(p, it.startTimeTicks, it.processGroupId)
            }
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

    override fun currentTranscript(): String = synchronized(session.mEmulatorLock) {
        session.emulator?.screen?.getTranscriptText().orEmpty()
    }

    override fun recentOutput(maxChars: Int): String {
        val full = synchronized(session.mEmulatorLock) {
            session.emulator?.screen?.getTranscriptText().orEmpty()
        }
        if (full.length <= maxChars) return full
        return full.takeLast(maxChars)
    }

    // CC-003: titles are emulator state too; the main-looper append path is
    // the only writer, so every cross-thread read goes through the same lock.
    override val title: String? get() = synchronized(session.mEmulatorLock) { session.title }

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
            synchronized(session.mEmulatorLock) {
                session.emulator?.screen
                    ?.getSelectedText(0, 0, PREVIEW_MAX_COLUMNS, PREVIEW_MAX_ROWS)
                    .orEmpty()
            }
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
        // SS-001: SIGHUP first, then a bounded escalation to SIGKILL inside
        // TerminalSession. The container awaits awaitExit() before releasing
        // this terminal's scratch tree.
        PerfTrace.abandonTerminal(id, "destroyed-before-first-screen-update")
        session.finishGracefully()
        // SS-002 + F-021: no consumer can need this session's discrete events
        // after an explicit destroy; stop both pumps so neither can outlive
        // the session.
        eventChannel.close()
        lifecycleChannel.close()
        eventScope.cancel()
    }

    private companion object {
        const val TAG = "TermuxPtySession"

        /** F-021: fixed capacity of the transient-event queue (drop-oldest). */
        const val EVENT_BUFFER_CAPACITY = 64

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
