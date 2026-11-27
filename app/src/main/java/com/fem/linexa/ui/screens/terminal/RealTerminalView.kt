package com.fem.linexa.ui.screens.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.perf.PerfCounters
import com.fem.linexa.core.perf.PerfTrace
import com.fem.linexa.domain.settings.TerminalFontPreference
import com.fem.linexa.domain.settings.TerminalShortcut
import com.fem.linexa.service.runtime.pty.PtyEvent
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.TermuxTerminalSessionProvider
import com.fem.linexa.ui.components.haven.HavenTerminalModifiers
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The handle the terminal screen hands out to the Haven chrome — the whole of
 * the chrome's coupling to the terminal *implementation*, expressed in Linexa
 * vocabulary. The panels hold this, never the vendored `TerminalView` type
 * (that confinement is pinned by `ImportBoundaryTest`'s `com.termux` rule).
 *
 *  * [dispatchTerminalKeyCode] — the extra-keys pill's Esc / Tab / arrows.
 *  * [focusAndShowKeyboard] — extra keys and the ⋯ sheet both want the soft
 *    keyboard back up afterwards; the IME anchor is the view itself, so the
 *    adapter is the right owner of that call.
 *  * [fontSizeDp] / [setFontSizeDp] — the ⋯ sheet's font slider. The terminal
 *    owns its font (2026-08-29 migration): the value is persisted
 *    terminal-locally by [TerminalFontPreference] and applied straight to the
 *    view; there is no application appearance layer behind it any more.
 */
interface TerminalHandle {
    /**
     * Dispatches a real Android key event to the terminal view.
     *
     * [metaState] carries modifier bits (currently `META_CTRL_*` for the
     * `^C`/`^D`/`^Z` shortcuts). The vendored terminal converts a ctrl-modified
     * letter into the matching control byte, so these are real terminal inputs,
     * not app-level string macros.
     */
    fun dispatchTerminalKeyCode(keyCode: Int, metaState: Int = 0)
    fun focusAndShowKeyboard()
    val fontSizeDp: Int
    fun setFontSizeDp(sizeDp: Int)
}

/**
 * Hosts the vendored Apache-2.0 Termux `TerminalView` (`:terminal-view`) — the
 * 2026-08-29 replacement for the GPL `terminal-lib` view, wired through
 * [TermuxTerminalSessionProvider]. The Haven rebuild's shape is unchanged: the
 * library's built-in `ExtraKeysView` row stays dropped (extra keys are the
 * floating translucent Compose pill,
 * [com.fem.linexa.ui.components.haven.HavenExtraKeys]), and the modifier state
 * it arms reaches this view through [HavenTerminalModifiers].
 *
 * The `AndroidView` factory builds ONLY the `TerminalView` (no LinearLayout
 * wrapper, no inflated attribute set). [onViewReady] hands a [TerminalHandle]
 * to the caller so the extra-keys pill can dispatch real `KeyEvent`s and the
 * sheet can reach the terminal-owned font.
 *
 * **§1.145 lifetime rule (unchanged):** the `terminalViewState` holder must
 * NOT be keyed on `ptySession.id` — `AndroidView`'s factory runs once per
 * composition instance, so switching the rendered terminal re-attaches the new
 * session to the SAME view via the `update` block below.
 *
 * Font size belongs to the terminal (migration contract): the initial size is
 * read from the terminal-local [TerminalFontPreference] store, the pinch
 * gesture and the sheet both mutate it through the view, and every change is
 * persisted back to that store. No ViewModel, no app-wide setting.
 *
 * Text selection belongs to the terminal too: long-press returns `false` from
 * the client, which is what makes the vendored `TerminalView` run its own
 * selection mode (handles + ActionMode). Its Copy action arrives here as
 * [PtyEvent.ClipboardText] (via `TerminalSessionClient.onCopyTextToClipboard`,
 * which also carries OSC 52), and its Paste action as [PtyEvent.PasteRequested].
 *
 * **2026-08-31 (fullscreen black overlay fix):** the `AndroidView` is wrapped
 * in `Modifier.clipToBounds()`. The Compose interop holder
 * (`ViewFactoryHolder`) constructs itself with `setClipChildren(false)` and
 * Compose draws interop views on the raw, unclipped window canvas — so the
 * vendored view's `onDraw` background fill (`canvas.drawColor`, the
 * terminal-owned default background) escaped the view's layout bounds and
 * painted the whole window: app bar, terminal bar and status strip all turned
 * the terminal background color while their controls stayed laid out and
 * clickable (hit-testing is bounds-based, not pixel-based). Clipping at the
 * `AndroidView` node confines the terminal's rendering to its own content
 * region — the fix is containment only: colors, session lifecycle, insets and
 * input are untouched.
 */
@Composable
fun RealTerminalView(
    ptySession: PtySession,
    modifier: Modifier = Modifier,
    modifiers: HavenTerminalModifiers = remember { HavenTerminalModifiers() },
    onViewReady: (TerminalHandle?) -> Unit = {},
    onUnavailable: @Composable () -> Unit = {}
) {
    val provider = ptySession as? TermuxTerminalSessionProvider
    val nativeSession = provider?.termuxSession
    if (provider == null || nativeSession == null) {
        onUnavailable()
        return
    }

    val context = LocalContext.current
    val terminalViewState = remember { mutableStateOf<TerminalView?>(null) }
    val clipboard = LocalClipboardManager.current
    // The terminal's own font size, terminal-locally persisted. Kept as state
    // only so the ⋯ sheet can show the current value while it is open.
    val fontSizeDpState = remember { mutableIntStateOf(TerminalFontPreference.load(context)) }

    val handle = remember {
        object : TerminalHandle {
            override val fontSizeDp: Int get() = fontSizeDpState.value
            override fun setFontSizeDp(sizeDp: Int) {
                val next = TerminalFontPreference.sanitize(sizeDp)
                if (next == fontSizeDpState.value) return
                fontSizeDpState.value = next
                terminalViewState.value?.setTextSize(TerminalFontPreference.toPx(next, context))
                TerminalFontPreference.save(context, next)
            }
            override fun dispatchTerminalKeyCode(keyCode: Int, metaState: Int) {
                terminalViewState.value?.dispatchTerminalKeyCode(keyCode, metaState)
            }
            override fun focusAndShowKeyboard() {
                focusTerminalView(context, terminalViewState.value, showKeyboard = true)
            }
        }
    }

    // ── Session binds focus the terminal; they do NOT raise the IME. ──────
    //
    // The vendored view's only automatic focus path is its touch handler, so
    // programmatic session switches must still request focus here — otherwise
    // the next keystroke would not target the visible session. Raising the
    // keyboard is a separate, explicit action: Recent Sessions returns to a
    // terminal without forcing the IME over it, and the user's next tap (or
    // the explicit keyboard action) opens it. This keeps the session surface
    // stable after a card selection.
    //
    //  - fresh view (factory)  → view exists when the effect runs → focused;
    //  - session switch on the SAME view (update block re-attach) → effect
    //    re-runs on `ptySession.id` change → focused;
    //  - placeholder gap destroyed the view → factory rebuild → focused;
    //  - overlay dismissed/reopened → whole composable re-enters → focused.
    //
    // Focus is requested once per session bind, NOT continuously: no
    // recomposition loop, no polling, and no stealing — requests only happen
    // when a terminal (re)appears under an already-visible overlay.
    LaunchedEffect(ptySession.id) {
        // The effect starts before AndroidView's factory has run on the very
        // first composition; retry on the next frame until the view binds.
        // The loop is bounded by the composition itself (it dies with it) and
        // each cycle is a frame — no timers, no sleeps.
        var attempts = 0
        while (terminalViewState.value == null && attempts < VIEW_BIND_ATTEMPTS) {
            kotlinx.coroutines.delay(VIEW_BIND_RETRY_MS)
            attempts++
        }
        focusTerminalView(context, terminalViewState.value)
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { factoryContext ->
            // The vendored view's only constructor takes (Context, AttributeSet)
            // — built programmatically here, so no attribute set.
            val terminalView = TerminalView(factoryContext, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
            // The renderer's size unit is px (Paint.setTextSize); the
            // stored preference unit is dp — converted here, matching
            // Termux's own store-px-apply-px behaviour with a
            // density-independent store.
            setTextSize(TerminalFontPreference.toPx(fontSizeDpState.value, factoryContext))
            // 2026-08-29 (appearance fix, Issue 3): terminal-owned font —
            // if the workspace HOME carries a Termux-format
            // `.termux/font.ttf`, the terminal uses it (no remote fonts, no
            // app settings). Malformed/missing files fall back to the
            // default monospace silently.
            try {
                val fontFile = ptySession.terminalConfigHome?.let { home ->
                    java.io.File(home, ".termux/font.ttf")
                }
                if (fontFile != null && fontFile.isFile) {
                    setTypeface(android.graphics.Typeface.createFromFile(fontFile))
                    Logger.i("TerminalView", "Terminal font loaded from ${fontFile.path}")
                }
            } catch (e: Exception) {
                Logger.w("TerminalView", "Ignoring invalid .termux/font.ttf: ${e.message}")
            }
            }
            terminalView.setTerminalViewClient(
                havenViewClient(
                    context = factoryContext,
                    view = terminalView,
                    modifiers = modifiers,
                    // The tracked dp is the single source of truth for the
                    // current size: the renderer's own mTextSize field is
                    // package-private upstream, and the state is updated by
                    // every writer (factory, pinch, sheet) anyway.
                    currentFontDp = { fontSizeDpState.value },
                    onFontSizeChanged = { nextDp ->
                        fontSizeDpState.value = nextDp
                        TerminalFontPreference.save(context, nextDp)
                    }
                )
            )
            val attached = terminalView.attachSession(nativeSession)
            Logger.i(
                "TerminalChain",
                "TerminalView FACTORY workspaceId=${ptySession.workspaceId} sessionId=${ptySession.id} " +
                    "sessionIsAlive=${ptySession.isAlive} attached=$attached view=${System.identityHashCode(terminalView)}"
            )
            terminalViewState.value = terminalView
            onViewReady(handle)
            terminalView
        },
        update = { terminalView ->
            if (terminalView.currentSession !== nativeSession) {
                Logger.i(
                    "TerminalChain",
                    "TerminalView UPDATE re-attach old=" +
                        (terminalView.currentSession?.let { System.identityHashCode(it) } ?: "null") +
                        " new=${System.identityHashCode(nativeSession)} view=${System.identityHashCode(terminalView)}"
                )
                terminalView.attachSession(nativeSession)
            }
        },
        onRelease = {
            val releasedView = terminalViewState.value
            terminalViewState.value = null
            onViewReady(null)
            Logger.i(
                "TerminalChain",
                "TerminalView RELEASE view=${releasedView?.let { System.identityHashCode(it) }} " +
                    "sessionId=${ptySession.id}"
            )
        }
    )

    // 2026-08-30 (session-lifecycle fix): the repaint bridge is now driven by
    // the session's conflated screenRevision StateFlow instead of the replay-less
    // PtyEvent flow. StateFlow semantics guarantee the CURRENT revision is
    // delivered to every subscriber at subscribe time — so whatever ordering the
    // world throws at this composable (fresh view bound before the shell's first
    // output, a re-attach after a session switch whose collector was cancelled
    // mid-recomposition, a bind that raced an emission), the view ALWAYS paints
    // the emulator's current state right after binding and after every later
    // output/color batch. The old lossy path dropped notifications emitted
    // before/around subscription, leaving freshly created or re-attached
    // sessions frozen on their empty bind-time frame — the "new session is
    // black until the session exits" defect.
    //
    // The PtyEvent collector below now handles only the non-repaint actions
    // (clipboard in/out); ScreenUpdated remains on the flow for diagnostics and
    // any future listener, but repainting from it would reintroduce the loss.
    LaunchedEffect(ptySession.id) {
        var lastRevision = -1
        provider.screenRevision.collect { revision ->
            if (lastRevision == -1) {
                // 2026-08-30 diagnostic: proves the repaint bridge armed and
                // painted the emulator's state as of attach (the first,
                // subscription-time StateFlow emission).
                Logger.i(
                    "TerminalChain",
                    "RepaintBridge armed sessionId=${ptySession.id} revision=$revision " +
                        "viewBound=${terminalViewState.value != null}"
                )
            }
            lastRevision = revision
            val boundView = terminalViewState.value
            if (boundView != null) {
                boundView.onScreenUpdated()
                // Phase M0-A (E7): a repaint was actually delivered to a bound
                // view. The engine records only the first one per pty; later
                // revisions are normal operation and cost one map lookup.
                val recorded = PerfTrace.markFirstScreenUpdate(
                    ptyId = ptySession.id,
                    screenRevision = revision,
                    bytesRead = nativeSession.perfBytesRead,
                    handlerMessages = nativeSession.perfHandlerMessages,
                    screenUpdates = nativeSession.perfScreenUpdates
                )
                if (recorded) PerfCounters.logSummary()
            }
        }
    }

    LaunchedEffect(ptySession.id) {
        ptySession.events.collect { event ->
            when (event) {
                is PtyEvent.ClipboardText -> clipboard.setText(AnnotatedString(event.text))
                is PtyEvent.PasteRequested -> {
                    // The vendored selection toolbar's Paste button. The
                    // screen owns the Android clipboard; the pty receives the
                    // text through the same write path the ⋯ sheet uses.
                    val text = clipboard.getText()?.text
                    if (!text.isNullOrEmpty()) ptySession.write(text)
                }
                else -> Unit
            }
        }
    }
}

/**
 * Dispatches a hardware-style key event to the terminal view. Used by the
 * Haven shortcut bar for every dispatched key.
 *
 * Both ACTION_DOWN and ACTION_UP are sent: the down event is what the vendored
 * view turns into bytes, and the up event keeps the view's key state honest
 * (no stuck key after a rapid tap). [metaState] is passed through unchanged, so
 * `META_CTRL_*` letters become real control bytes.
 */
fun TerminalView.dispatchTerminalKeyCode(keyCode: Int, metaState: Int = 0) {
    val downTime = android.os.SystemClock.uptimeMillis()
    dispatchKeyEvent(
        KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState
        )
    )
    dispatchKeyEvent(
        KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            metaState
        )
    )
}

/**
 * The Android meta bits a [TerminalShortcut] must carry. Only the control
 * variants need any today; keeping the mapping here means the toolbar never
 * invents a bit pattern per caller.
 */
internal fun TerminalShortcut.dispatchMetaState(): Int =
    if (metaCtrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0

/**
 * The one focus/IME entry point for a bound terminal view.
 *
 * A programmatic session bind requests focus only: it must NOT raise the IME,
 * because selecting a card in Recent Sessions returns to a terminal without
 * asking the user to type. The explicit keyboard action passes
 * [showKeyboard] = true. A tap on the terminal is handled by the vendored view
 * and its client (which raises the IME). Null-safe: callers race the view's
 * bind (factory), so a missing view is a no-op, not a crash.
 */
internal fun focusTerminalView(
    context: Context,
    view: TerminalView?,
    showKeyboard: Boolean = false
) {
    if (view == null) return
    view.requestFocus()
    if (!showKeyboard) return
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/** Bounded wait for AndroidView's factory to bind the vendored view. */
private const val VIEW_BIND_ATTEMPTS = 20

/** Per-attempt wait for the bind above — one frame-ish, not a timer hack. */
private const val VIEW_BIND_RETRY_MS = 25L

/**
 * Tap-to-focus/show-keyboard client with the Haven modifier contract:
 * `readControlKey()`/`readAltKey()` consume the one-shot sticky flags set by
 * the floating pill — the same contract the library's `ExtraKeysView` used to
 * serve, minus the obsolete row.
 */
private fun havenViewClient(
    context: Context,
    view: TerminalView,
    modifiers: HavenTerminalModifiers,
    currentFontDp: () -> Int,
    onFontSizeChanged: (Int) -> Unit
): TerminalViewClient = object : TerminalViewClient {
    // The vendored view feeds back the ACCUMULATED scale factor and adopts
    // whatever factor we return — returning 1f after each applied step resets
    // the accumulation so the next 10% pinch is measured from neutral again.
    // 2026-08-29 (appearance/gesture fix, Issue 2): the pinch accumulator.
    //
    // ROOT CAUSE of the dead pinch: the vendored view's scale path is
    //   mScaleFactor *= rawScale;  mScaleFactor = client.onScale(mScaleFactor)
    // with mScaleFactor starting at 1.0f — and this client returned 1f
    // unconditionally, so the view's factor was reset to 1.0 after EVERY
    // event. The client therefore only ever saw the RAW per-event ratio
    // (~0.97–1.06 for a human pinch) and the old >1.1/<0.9 thresholds could
    // never fire. The gesture pipeline itself (ScaleGestureDetector inside
    // GestureAndScaleRecognizer) was healthy all along.
    //
    // The fix accumulates the raw ratios HERE (the view's factor is kept
    // neutral by still returning 1f), stepping the terminal-owned font once
    // per ~±15% of accumulated pinch and resetting the accumulator. No
    // gesture-end hook exists, so an accumulated remainder carries into the
    // next pinch — imperceptible and deterministic.
    private var scaleAccum = 1.0f

    override fun onScale(scale: Float): Float {
        scaleAccum *= scale
        when {
            scaleAccum >= 1.15f -> {
                val next = TerminalFontPreference.increase(currentFontDp())
                applyFontDp(next)
                scaleAccum = 1f
            }
            scaleAccum <= 0.87f -> {
                val next = TerminalFontPreference.decrease(currentFontDp())
                applyFontDp(next)
                scaleAccum = 1f
            }
        }
        // Keep the view's own accumulator neutral — the zoom state lives here.
        return 1f
    }

    private fun applyFontDp(next: Int) {
        val currentDp = currentFontDp()
        if (next != currentDp) {
            view.setTextSize(TerminalFontPreference.toPx(next, context))
            onFontSizeChanged(next)
        }
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // 2026-08-29 (UX optimization, Issue 1): Termux's tap model, state-based.
        //
        // The vendored view only calls this callback when the running program
        // has NOT enabled terminal mouse reporting (its own gesture handler
        // sends mouse-tracked taps to the pty instead, and it stops selection
        // first if a selection is active, without ever reaching here). So:
        //
        //   normal shell prompt  → tap → focus (taken by the vendored view)
        //                           + keyboard opens (below);
        //   mouse-reporting CLI  → tap → the vendored view delivers the pointer
        //                           event to the program; this callback is not
        //                           invoked; the keyboard stays closed;
        //   explicit ⌨ button    → TerminalHandle.focusAndShowKeyboard, always
        //                           works regardless of mouse reporting.
        //
        // The decision is the emulator's own mouse-tracking state — never an
        // application name.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    // This view is always the terminal the user is interacting with; the
    // vendored view uses the answer to gate hardware-key handling.
    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun readControlKey(): Boolean = modifiers.consumeControl()
    override fun readAltKey(): Boolean = modifiers.consumeAlt()
    // 2026-08-29 (Issue 6/7): Shift/Fn are real armed modifiers now — the
    // shortcut bar's Shift/Fn keys arm them, the next soft-key input consumes
    // them, and the bar reflects the live state.
    override fun readShiftKey(): Boolean = modifiers.consumeShift()
    override fun readFnKey(): Boolean = modifiers.consumeFn()
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    // Returning false is what arms the vendored view's OWN text selection mode
    // (handles + ActionMode) — the terminal owns selection, not the chrome.
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun onEmulatorSet() = Unit

    // ── Logging callbacks: bridge to Linexa's own logger. ──
    override fun logError(tag: String?, message: String?) {
        Logger.e(tag ?: "TerminalView", message ?: "")
    }
    override fun logWarn(tag: String?, message: String?) {
        Logger.w(tag ?: "TerminalView", message ?: "")
    }
    override fun logInfo(tag: String?, message: String?) {
        Logger.i(tag ?: "TerminalView", message ?: "")
    }
    override fun logDebug(tag: String?, message: String?) {
        Logger.d(tag ?: "TerminalView", message ?: "")
    }
    override fun logVerbose(tag: String?, message: String?) {
        Logger.d(tag ?: "TerminalView", message ?: "")
    }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Logger.e(tag ?: "TerminalView", "$message\n${e?.toString() ?: ""}")
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        Logger.e(tag ?: "TerminalView", e?.toString() ?: "")
    }
}
