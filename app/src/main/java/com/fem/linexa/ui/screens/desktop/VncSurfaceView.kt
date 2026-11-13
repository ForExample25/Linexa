package com.fem.linexa.ui.screens.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fem.linexa.service.runtime.features.DesktopTouchMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * §1.125 — the `View` that gives the Android IME somewhere to send text, and
 * draws the remote framebuffer.
 *
 * This exists because a Compose `Box` cannot receive soft-keyboard input. The
 * pre-§1.125 viewer was `focusable()` with an `onKeyEvent` modifier, which handles
 * *hardware* key events only: an IME needs a `View` that returns true from
 * [onCheckIsTextEditor] and supplies an [InputConnection] from
 * [onCreateInputConnection]. Without one, `showSoftInput` is a no-op and every
 * character an on-screen keyboard commits is dropped by the platform before any
 * Compose code runs. The same reason `TerminalView` (§1.20) is an `AndroidView`
 * rather than a Compose surface.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.125):
 *
 * - **Text arrives as text; keys arrive as keys.** [InputConnection.commitText]
 *   routes through [VncTextInput]/[XKeysyms.unicodeKeysym] (the character's own
 *   keysym, because an IME has already applied Shift and sends `!` not
 *   `Shift`+`1`), while a real hardware [KeyEvent] still routes through
 *   [XKeysyms.androidKeysym] (the base key, because X composes the modifier
 *   itself). Sending soft-keyboard input down the hardware path would type `a`
 *   for `A`; sending hardware input down the text path would double every
 *   modifier. They are genuinely two paths, not a duplication.
 *
 * - **A press/release pair is always emitted, per keysym.** A VNC server treats a
 *   missing release as a key held down, so a dropped release wedges the remote
 *   session on that key — the same defect §1.79 fixed for hardware keys, which is
 *   trivially reintroducible on a new input path.
 *
 * - **The `InputType` is deliberately plain and unlearning.** `TYPE_NULL` would
 *   make some IMEs fall back to sending raw key events (nice in theory) and others
 *   refuse to open at all, which is the failure being fixed. `TYPE_CLASS_TEXT`
 *   with `IME_FLAG_NO_EXTRACT_UI`, no autocorrect, no suggestions and
 *   `TYPE_TEXT_FLAG_NO_SUGGESTIONS` keeps the IME in a plain, per-character mode:
 *   a composing region would let the IME batch and revise text that has already
 *   been typed into a remote application it cannot see.
 *
 * - **Drawing stays here rather than in Compose.** Once the view exists for input,
 *   having Compose draw the framebuffer and this view own focus would mean two
 *   components with two coordinate systems for one surface — the exact drift
 *   §1.114 removed between the screen's hardcoded port and the ViewModel's. The
 *   contain-scale maths is shared with pointer translation by construction here,
 *   so a tap lands where it was drawn.
 */
internal class VncSurfaceView(
    context: Context,
    /** Sends one keysym as a press/release pair. */
    private val onKeysym: (Int) -> Unit,
    /** Sends a pointer event in **framebuffer** coordinates. */
    private val onPointer: (x: Int, y: Int, buttonMask: Int) -> Unit,
    /** Sends a wheel event in **framebuffer** coordinates. */
    private val onWheel: (x: Int, y: Int, deltaY: Float) -> Unit
) : View(context) {

    /**
     * The bitmap [onDraw] reads. Only ever swapped on Main, and never written to
     * while it holds this role — see [backBitmap].
     */
    private var bitmap: Bitmap? = null

    /**
     * §1.150 — the off-screen bitmap the pixel copy writes into.
     *
     * The copy ([Bitmap.setPixels], up to 8.3 MB on a 1920×1080 desktop) is the
     * cost §1.147 identified and left on Main. Moving it off Main is only safe if
     * the bitmap being written is *not* the one the draw is reading, hence two
     * buffers: the upload thread owns this one exclusively from the moment a frame
     * is dispatched until the swap that promotes it, and the promotion happens on
     * Main between draws.
     */
    private var backBitmap: Bitmap? = null

    private var frameWidth = 0
    private var frameHeight = 0
    private var uploadedGeneration = -1L

    private val destRect = Rect()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The desktop is the reason the screen is open; letting it sleep mid-session
        // is the same call TerminalView makes (§1.20).
        keepScreenOn = true
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    // ── Zoom (Desktop Zoom, 2026-08) ─────────────────────────────────────

    /**
     * The zoom/pan state. All view↔frame coordinate maths — draw placement,
     * relative-cursor deltas, direct-touch mapping — derives from this one
     * value, so the draw transform and the input transform can never disagree
     * (the same rule the contain-scale established, extended by the user zoom).
     *
     * Start degenerate (no geometry yet): [VncZoom] with a zero framebuffer is
     * the identity, and [allocateBuffers] replaces it on the first frame.
     */
    private var zoom = VncZoom(0, 0, 0, 0)

    /**
     * Reports the user zoom multiplier whenever it changes, so the viewer can
     * surface a "reset zoom" affordance only while zoomed. Fired from Main
     * (touch handling), cheap, and never fired for pure pan/geometry changes.
     */
    var onZoomChanged: (Float) -> Unit = {}

    /** Returns to the contain-fit view (zoom 1, centred). */
    fun resetZoom() {
        applyZoom(zoom.reset())
    }

    private fun applyZoom(next: VncZoom) {
        val zoomChanged = next.userZoom != zoom.userZoom
        zoom = next
        if (zoomChanged) onZoomChanged(zoom.userZoom)
        invalidate()
    }

    /**
     * The pinch recognizer. Its scale factor is *incremental* (per gesture
     * event), so it multiplies the current zoom rather than setting an absolute
     * one; the focal point is the pinch centroid, so the content under the
     * fingers stays under the fingers.
     */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = zoom.userZoom * detector.scaleFactor
                applyZoom(zoom.zoomed(next, detector.focusX, detector.focusY))
                return true
            }
        }
    )

    // ── Touch mode (Desktop Touch work, 2026-08) ─────────────────────────

    /**
     * How finger touches map onto the remote pointer: [DesktopTouchMode.RELATIVE]
     * integrates finger movement into the cursor (touchpad-style, the historical
     * behaviour); [DesktopTouchMode.DIRECT] places the cursor at the finger's
     * mapped position. Read per gesture start — switching takes effect on the
     * next touch, with no desktop restart.
     */
    var touchMode: DesktopTouchMode = DesktopTouchMode.RELATIVE

    /** The machine actually driving the current gesture, chosen at finger-down. */
    private var activeGesture: VncGestureMachine? = null

    // ── IME plumbing (the whole reason this View exists) ──────────────────

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = -1
        outAttrs.initialSelEnd = -1

        // `fullEditor = false`: there is no local text buffer to edit. The remote
        // X application owns the text, so every IME action is translated to a
        // keystroke immediately rather than accumulated here.
        return object : BaseInputConnection(this, false) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null) return true
                // §1.138: diffed against the composing region rather than typed
                // outright. An IME commits the region it has been showing, so
                // typing it again duplicated the whole word.
                emit(composing.commit(text))
                return true
            }

            /**
             * §1.138 — a composing update is the **entire** current region, not the
             * new characters. Typing it verbatim (the §1.125 behaviour) sent
             * `hhehelhellhello` for `hello` on every predictive or CJK keyboard.
             * [VncComposingText] converts it to the minimal BackSpace/type
             * sequence that leaves the remote buffer holding [text].
             */
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null) return true
                emit(composing.compose(text))
                return true
            }

            /**
             * The region was abandoned. Nothing is retracted — the characters are
             * already in a remote application that has no provisional region — so
             * this only stops the next region diffing against a stale tail.
             */
            override fun finishComposingText(): Boolean {
                composing.finish()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // §1.138: `beforeLength` is in UTF-16 chars, and one BackSpace
                // deletes a whole character — so deleting an emoji asked for 2 and
                // would have eaten the character before it as well.
                repeat(composing.retract(beforeLength)) { onKeysym(XKeysyms.BACKSPACE) }
                repeat(VncTextInput.deleteCount(afterLength)) { onKeysym(XKeysyms.DELETE) }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                // Already in the remote application's own unit; no conversion.
                repeat(VncTextInput.backspaceCount(beforeLength)) { onKeysym(XKeysyms.BACKSPACE) }
                repeat(VncTextInput.deleteCount(afterLength)) { onKeysym(XKeysyms.DELETE) }
                composing.finish()
                return true
            }

            /**
             * The IME's own Enter/Backspace arrive here as key events; forward
             * them through the *hardware* mapping, since they are real keycodes.
             */
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event == null) return false
                // A raw key event edits the remote buffer directly, so anything the
                // composing tracker believes it typed is no longer trustworthy.
                composing.finish()
                return this@VncSurfaceView.dispatchKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                composing.finish()
                onKeysym(XKeysyms.RETURN)
                return true
            }
        }
    }

    /** §1.138 — tracks the composing region so an update types only its delta. */
    private val composing = VncComposingText()

    /** Retract-then-type, in that order: typing first would delete what was sent. */
    private fun emit(batch: VncKeyBatch) {
        if (batch.isEmpty) return
        repeat(batch.backspaces) { onKeysym(XKeysyms.BACKSPACE) }
        batch.keysyms.forEach(onKeysym)
    }

    /**
     * Opens the soft keyboard on this view, or closes it if it is already up.
     *
     * §1.138: the fullscreen desktop hides the app bar, so the only pre-Phase-8
     * way to raise the IME was to tap the surface — which also left-clicks the
     * remote desktop, and there was no way to *dismiss* the keyboard without one
     * either. The overlay control calls this instead.
     */
    fun toggleSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.toggleSoftInputFromWindow(windowToken, 0, 0)
    }

    // ── Hardware keys ────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Back must stay the system back gesture: it is the only way off this
        // screen while the app bar is hidden by the fullscreen session.
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // Pre-release audit fix (2026-09-02, finding D2): drop OS auto-repeat
        // events. Android forwarded EVERY repeat as another RFB press while
        // the X server ALSO auto-repeats a held key — the remote saw doubled
        // repeat. The X server's own repeat is the single source of truth;
        // the first press is all we forward.
        if ((event?.repeatCount ?: 0) > 0) return true
        val keysym = XKeysyms.androidKeysym(keyCode) ?: return super.onKeyDown(keyCode, event)
        onKeyDownKeysym(keysym)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        val keysym = XKeysyms.androidKeysym(keyCode) ?: return super.onKeyUp(keyCode, event)
        onKeyUpKeysym(keysym)
        return true
    }

    /**
     * Hardware key press/release are forwarded separately (not as a pair) so a
     * held-down key repeats and a held modifier actually modifies — which is the
     * §1.79 behaviour. Set by [VncViewer].
     */
    var onKeyDownKeysym: (Int) -> Unit = {}
    var onKeyUpKeysym: (Int) -> Unit = {}

    // ── Framebuffer ──────────────────────────────────────────────────────

    /**
     * The newest frame not yet handed to the upload thread, or null when the view
     * is up to date.
     *
     * §1.147 introduced this slot to *coalesce*: `VncClient` publishes one frame per
     * RFB FramebufferUpdate, which an X server emits as fast as the socket drains,
     * and pre-§1.147 every one of them paid for a full-framebuffer upload plus an
     * `invalidate()` on Main. Keeping only the newest is what collapses a burst into
     * one upload. Nothing is dropped visually — every frame shares the same live
     * `pixels` array ([VncFrame]), so the newest frame's upload already carries the
     * older ones' pixels, and the dirty region they contributed stays in the union
     * until it is acknowledged ([VncDirtyRegion]).
     *
     * §1.152 — the coalescing is now done by [uploadInFlight] alone, and the
     * `Choreographer` frame callback §1.147 added has been **removed**. It was
     * costing a whole extra display frame of latency on every update, for a
     * coalescing guarantee §1.150 had already made redundant:
     *
     * - `postFrameCallback` runs in the ANIMATION phase of a vsync, *before* that
     *   vsync's traversal. While §1.147 copied inline, that ordering was the point:
     *   copy, `invalidate()`, and the traversal later in the same frame drew the
     *   result. §1.150 then moved the copy to a worker thread, so the callback only
     *   *dispatches* it — the copy finishes after that vsync's traversal has already
     *   run, [promoteUploadedFrame] lands as a later message, and its `invalidate()`
     *   can only be served by the **next** vsync. Every frame therefore waited for
     *   one vsync to be dispatched and another to be drawn.
     * - The gate it provided is already provided by [uploadInFlight]: a second frame
     *   arriving while a copy is running cannot dispatch a second copy either way, so
     *   a burst still collapses. What the vsync hop added on top of that was not
     *   coalescing but delay.
     *
     * Dispatching on arrival costs the opposite trade, taken deliberately: when
     * updates arrive faster than the display refreshes, a copy can be promoted
     * without ever being drawn, so more pixels are copied in total. Those copies are
     * on the worker thread and the frame they produce is never wrong — whereas the
     * latency the hop added was paid by every single update, including the ones a
     * user is actively waiting on (see [onTouchEvent]: the remote cursor is rendered
     * by the server into this same framebuffer, so pointer feedback is gated on
     * exactly this path).
     */
    private var pendingFrame: VncFrame? = null

    /**
     * §1.150 — whether a background pixel copy is running against [backBitmap].
     *
     * Dispatching a second copy while one is in flight would write the very bitmap
     * the pending swap is about to hand to [onDraw]. A frame arriving in that
     * window stays in [pendingFrame] and the promotion re-arms the callback, so
     * nothing is dropped and no buffer is written while it is being read.
     */
    private var uploadInFlight = false

    /**
     * §1.150 — the region of the front/back bitmap that is behind the framebuffer.
     *
     * Double buffering makes a *scoped* upload subtler than it looks: a rectangle
     * copied into one buffer is missing from the other, so uploading only
     * [VncFrame.dirty] into the alternate buffer would leave it holding pixels from
     * two frames ago outside that rectangle. Each buffer therefore tracks what it
     * missed while its twin was on screen, and the next copy into it covers the
     * union. Bounding boxes, like [VncDirtyRegion] — the bias is again towards
     * copying slightly too much, never too little.
     */
    private var frontStale: VncRect = VncRect.EMPTY
    private var backStale: VncRect = VncRect.EMPTY
    // Phase 2C instrumentation: one-shot flag for the T5 first-promote log line.
    private var firstPromoteLogged = false

    /**
     * §1.150 — bumped whenever the bitmaps stop being valid targets (a geometry
     * change, [clearFrame]). A copy dispatched before the bump must not install its
     * result afterwards: the promotion checks the epoch it captured and drops.
     */
    private var uploadEpoch = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * §1.150 — the one thread the pixel copy runs on.
     *
     * Single-threaded on purpose: it serialises copies so two of them can never
     * write one bitmap, which is half of what makes the swap safe (the other half
     * is [uploadInFlight]). Created lazily and released in
     * [onDetachedFromWindow] so a torn-down view leaves no thread behind, and
     * `shutdown()` (not `shutdownNow()`) so an already-queued copy still runs and
     * still clears [uploadInFlight] through its promotion.
     *
     * A dedicated executor rather than `Dispatchers.Default`: this view is
     * deliberately framework-only and holds no `CoroutineScope` — its lifecycle is
     * attach/detach, not a scope — and the app already uses
     * `Executors.newSingleThreadExecutor` for exactly this "one owned worker
     * thread" shape (`BootstrapManager`, `RootfsExtractor`, `PRootContainerRuntime`).
     */
    private var uploadExecutor: ExecutorService? = null

    private fun uploadExecutor(): ExecutorService =
        uploadExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "VncFrameUpload").apply { isDaemon = true }
        }.also { uploadExecutor = it }

    /**
     * Notifies the producer which generation reached the bitmap, so it can release
     * the dirty region it described. Set by [VncViewer]; a no-op default keeps this
     * view constructible without a client (its tests, and any future preview).
     */
    var onFrameUploaded: (Long) -> Unit = {}

    /**
     * §1.153 — reports the size of a completed copy: pixels copied, framebuffer
     * total.
     *
     * Separate from [onFrameUploaded] because it answers a different question and
     * has a different lifetime: the acknowledgement is load-bearing (without it the
     * dirty region never clears) while this is measurement, off unless debug capture
     * is on. Reported from here rather than measured in [VncClient] because the
     * region copied is not the region published — it is that union'd with the back
     * buffer's outstanding debt (§1.150) — and the cost worth knowing is the copy
     * that actually happened.
     *
     * A no-op default, like [onFrameUploaded], so the view stays constructible
     * without a client.
     */
    var onFrameUploadedArea: (Long, Long) -> Unit = { _, _ -> }

    /**
     * Publishes a new frame and dispatches its changed pixels immediately.
     *
     * Allocates only when the geometry changes; uploads only when
     * [VncFrame.generation] advances, so an unrelated invalidate does not re-copy
     * megabytes of pixels (§1.114's rule, preserved). The upload is scoped to the
     * frame's dirty region (§1.147) and runs on a worker thread (§1.150) — see
     * [uploadPendingFrame].
     *
     * §1.152: no longer defers to a `Choreographer` callback. See [pendingFrame] for
     * why that hop cost a display frame of latency without buying any coalescing
     * that [uploadInFlight] does not already provide.
     */
    fun submitFrame(frame: VncFrame) {
        if (frame.width <= 0 || frame.height <= 0) return
        // Keeping only the newest is the coalescing. An older frame still waiting
        // here is not "lost": its pixels are in the same live array this one
        // points at, and its dirty rectangles are still unacknowledged.
        pendingFrame = frame
        uploadPendingFrame()
    }

    /**
     * Dispatches the pending frame's changed pixels to the upload thread.
     *
     * The upload is scoped to [VncFrame.dirty] rather than the whole framebuffer.
     * On a 1920×1080 desktop a full `setPixels` moves 2 073 600 ints (8.3 MB) per
     * frame; a typical update — a blinking cursor, a scrolling terminal line, a
     * repainted menu — is a few thousand. `VncPixels.copyRect`/`decodeArgbInto`
     * already knew those rectangles and §1.147 stopped throwing them away.
     *
     * [VncRect.EMPTY] means the producer did not describe its change, and the only
     * safe reading of an unknown change is *all of it* — so an undescribed frame
     * falls back to exactly the pre-§1.147 full upload rather than to no upload.
     *
     * §1.150 — the copy itself no longer runs here. `setPixels` was the one
     * genuinely expensive step on Main (§1.147 measured it and explicitly left it),
     * so it is handed to [uploadExecutor] writing [backBitmap], and only the buffer
     * swap ([promoteUploadedFrame]) happens on Main. This method therefore does
     * three cheap things — decide the region, mark the copy in flight, post the
     * work — and returns.
     *
     * §1.152 — and it runs on frame *arrival* rather than on the next vsync, so the
     * dispatch is no longer delayed by up to one display frame. [uploadInFlight] is
     * what coalesces a burst; see [pendingFrame].
     */
    private fun uploadPendingFrame() {
        val frame = pendingFrame ?: return
        // §1.150: a copy in flight owns the back buffer exclusively. Dispatching a
        // second one would write the bitmap the pending swap is about to show. The
        // frame stays queued and the promotion picks it up — and since §1.152 this
        // is also the *only* thing coalescing a burst of updates into one copy.
        if (uploadInFlight) return
        pendingFrame = null

        if (frame.width != frameWidth || frame.height != frameHeight ||
            bitmap == null || backBitmap == null
        ) {
            allocateBuffers(frame.width, frame.height)
        }
        cursor.resize(frameWidth, frameHeight)
        // The BACK buffer is the target: the front one is what onDraw reads, and
        // writing it from another thread is the tearing this change exists to avoid.
        val target = backBitmap ?: return
        if (frame.generation == uploadedGeneration) return
        if (frame.pixels.size < frame.width * frame.height) return

        // §1.150: the frame's own dirty region is not sufficient for a *double*
        // buffer. The back buffer last held the frame before the one now on screen,
        // so everything its twin received while it was off screen is missing from it
        // — see [backStale]. Copying only the new rectangle would leave the rest of
        // this buffer one frame stale and make the desktop flicker between two
        // versions of itself.
        val changed = uploadRegion(frame)
        val region = unionRects(changed, backStale)
        if (region.isEmpty) return

        uploadInFlight = true
        val epoch = uploadEpoch
        // A detached view draws nothing and has no thread worth spinning up (nor one
        // it could reliably shut down); it does the copy inline, which is exactly the
        // pre-§1.150 behaviour on a path with no reader to tear against.
        if (!isAttachedToWindow) {
            copyRegionIntoBuffer(target, frame, region)
            promoteUploadedFrame(frame, changed, epoch)
            return
        }
        uploadExecutor().execute {
            copyRegionIntoBuffer(target, frame, region)
            mainHandler.post { promoteUploadedFrame(frame, changed, epoch) }
        }
    }

    /**
     * §1.150 — the pixel copy, on the upload thread.
     *
     * Runs against [target] only, which the caller guarantees is the *back* buffer:
     * no other copy can be writing it ([uploadInFlight]) and [onDraw] does not read
     * it (it is not [bitmap]). That exclusive ownership is what removes the need for
     * a lock — nothing here is shared with Main except the framebuffer array, which
     * was already shared with the RFB receive loop before this change and is
     * deliberately self-healing (see [VncDirtyRegion]).
     *
     * The reader that has to be argued about is not [onDraw] but hwui's
     * RenderThread, which uploads a mutable `Bitmap`'s pixels during the *sync*
     * phase of the traversal that recorded it — after `onDraw` returned. The
     * ordering that makes this safe is the looper's, and §1.152 does not weaken it:
     *
     * - A traversal runs to completion as a single Main message, with the UI thread
     *   blocked for the duration of the hwui sync. So when any *other* Main message
     *   runs, every traversal that recorded the buffer then on screen has finished
     *   reading it.
     * - [promoteUploadedFrame] is its own Main message, and a copy is only ever
     *   dispatched from Main — from [submitFrame] (itself a Main message, posted by
     *   the frame collector) or from the tail of a promotion. Either way the dispatch
     *   is a Main message at or after the swap that demoted the buffer being written.
     * - After the swap, no future traversal can record the demoted buffer either:
     *   [onDraw] reads [bitmap], which is now its twin.
     *
     * §1.147's `Choreographer` hop was therefore not what made this safe — the
     * message ordering was, which is why removing the hop in §1.152 changes the
     * latency and not the race. A single buffer written off Main would have no such
     * gap: `onDraw` returning would not mean the pixels had been read.
     *
     * §1.153 — the copy reports its own size through [onFrameUploadedArea], from this
     * thread. Measured here, at the copy, rather than at the dispatch: a copy against
     * a buffer the view has since abandoned is discarded by the promotion's epoch
     * check but its pixels were still moved, and a measurement that missed those
     * would understate the cost during exactly the churn worth investigating.
     */
    private fun copyRegionIntoBuffer(target: Bitmap, frame: VncFrame, region: VncRect) {
        // `stride` stays the FRAME width, not the region's: `setPixels` walks the
        // source array by stride per row, and the source here is the full
        // framebuffer with the region's rows embedded in it. Passing the region
        // width would read each row from the wrong offset and skew the image — the
        // one mistake in a scoped upload that still produces a plausible-looking
        // picture.
        target.setPixels(
            frame.pixels,
            region.y * frame.width + region.x,
            frame.width,
            region.x,
            region.y,
            region.width,
            region.height
        )
        // §1.153: the copy's own size, for the audit's "is the scoped upload actually
        // scoped?" question. Two multiplications and a call that returns immediately
        // unless debug capture is on.
        onFrameUploadedArea(
            region.width.toLong() * region.height.toLong(),
            frame.width.toLong() * frame.height.toLong()
        )
    }

    /**
     * §1.150 — installs a completed copy, on Main.
     *
     * The swap is a reference assignment between two draws, so [onDraw] sees either
     * the whole previous frame or the whole new one and never a half-written
     * bitmap. [epoch] is checked because the buffers can stop being valid targets
     * while a copy is in flight (a resize, a disconnect): installing a copy made
     * against a bitmap the view has since abandoned would put the old desktop's
     * pixels back on screen.
     *
     * @param changed the rectangle this frame actually changed — **not** the
     *   possibly-larger region that was copied. The buffer being demoted is missing
     *   exactly the change, not the catch-up the promoted buffer also needed, and
     *   recording the larger one here would make every buffer's debt grow instead of
     *   settling.
     */
    private fun promoteUploadedFrame(frame: VncFrame, changed: VncRect, epoch: Int) {
        uploadInFlight = false
        if (epoch != uploadEpoch) {
            // The copy targeted an abandoned buffer. Whatever replaced it will be
            // filled by the frame that caused the replacement.
            if (pendingFrame != null) uploadPendingFrame()
            return
        }

        val shown = backBitmap
        backBitmap = bitmap
        bitmap = shown
        // Phase 2C instrumentation: T5 = first frame promoted to the front buffer. One-shot, no behavior change.
        // android.util.Log used directly (fully qualified): this view is deliberately framework-only
        // and must not gain a dependency on the app's logging facade.
        if (!firstPromoteLogged) {
            firstPromoteLogged = true
            android.util.Log.i("VncSurfaceView", "DesktopTiming T5 firstFramePromoted wallMs=${System.currentTimeMillis()}")
        }
        // The promoted buffer now matches the framebuffer; the demoted one is
        // missing everything this frame changed, on top of whatever it was already
        // missing (non-empty only immediately after an allocation, where both
        // buffers start owing a full copy).
        backStale = unionRects(frontStale, changed)
        frontStale = VncRect.EMPTY

        uploadedGeneration = frame.generation
        // Only after the pixels are on the bitmap: acknowledging earlier would let
        // the producer clear a region this upload had not yet copied.
        onFrameUploaded(frame.generation)
        requestRedraw()
        // A frame that arrived while the copy was running was deliberately held
        // back rather than dropped. Dispatched here rather than on a vsync (§1.152),
        // so a burst is limited by how fast the worker can copy and not by the
        // display — which is what keeps the last update of a burst from waiting a
        // frame longer than it has to.
        if (pendingFrame != null) uploadPendingFrame()
    }

    /**
     * §1.150 — allocates both buffers for a new geometry and invalidates every copy
     * in flight against the old ones.
     *
     * The newly-allocated front buffer is blank until the first copy is promoted, so
     * a geometry change shows one frame of the view's black background instead of
     * the pre-§1.150 behaviour where the first copy landed synchronously before the
     * next draw. Accepted deliberately: it costs a single vsync, only on a resize or
     * a reconnect, and the alternative is either drawing the previous geometry's
     * bitmap stretched to the new aspect ratio (visibly wrong, for the same one
     * frame) or copying on Main again, which is the cost being removed.
     */
    private fun allocateBuffers(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        backBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Both buffers hold nothing, so both owe a full copy — the same reason
        // `uploadRegion` forces a full upload on `uploadedGeneration == -1L`.
        val full = VncRect(0, 0, width, height)
        frontStale = full
        backStale = full
        uploadedGeneration = -1L
        uploadEpoch++
        // Desktop Zoom: a new geometry re-derives the transform (scale, pan
        // bounds); the user's zoom multiplier is kept across a reconnect, the
        // pan restarts centred. Zero sizes degrade to the identity transform.
        // (`this.width`/`this.height` are the View's laid-out size — the bare
        // names are this function's framebuffer parameters.)
        zoom = zoom.resized(this.width, this.height, width, height)
        invalidate()
    }

    /**
     * The region of [frame] to upload: its dirty rectangle, clipped to the frame,
     * or the whole frame when undescribed or when the bitmap was just reallocated.
     */
    private fun uploadRegion(frame: VncFrame): VncRect {
        val full = VncRect(0, 0, frame.width, frame.height)
        // A fresh bitmap holds nothing, so a scoped upload would leave the rest of
        // it transparent — this is the resize case, and it must be a full upload
        // regardless of what the producer reported.
        if (uploadedGeneration == -1L) return full
        val dirty = frame.dirty
        if (dirty.isEmpty) return full
        val left = dirty.x.coerceIn(0, frame.width)
        val top = dirty.y.coerceIn(0, frame.height)
        val right = (dirty.x + dirty.width).coerceIn(0, frame.width)
        val bottom = (dirty.y + dirty.height).coerceIn(0, frame.height)
        if (right <= left || bottom <= top) return full
        return VncRect(left, top, right - left, bottom - top)
    }

    /**
     * §1.150 — the bounding box covering both rectangles.
     *
     * A bounding box rather than a rectangle list for the same reason
     * [VncDirtyRegion] uses one: `setPixels` is a single call per region, and two
     * calls plus the bookkeeping to keep them disjoint costs more than copying the
     * few rows between two nearby rectangles. [VncRect.EMPTY] is the identity.
     */
    private fun unionRects(a: VncRect, b: VncRect): VncRect {
        if (a.isEmpty) return b
        if (b.isEmpty) return a
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        val right = maxOf(a.x + a.width, b.x + b.width)
        val bottom = maxOf(a.y + a.height, b.y + b.height)
        return VncRect(left, top, right - left, bottom - top)
    }

    /**
     * Requests a redraw after an upload.
     *
     * §1.147 deliberately does **not** use the region-scoped
     * `invalidate(left, top, right, bottom)` overload here, despite having the
     * region to hand. That overload is deprecated, and the reason it is deprecated
     * is that since API 21 the platform **ignores the rectangle entirely** and
     * computes its own damage area. `minSdk` is 28, so on every API level this app
     * supports a scoped invalidate is byte-for-byte equivalent to this call — it
     * would buy nothing and cost a deprecation warning plus a scale/offset
     * calculation whose result is discarded.
     *
     * This is not a gap in the change: the per-frame cost §1.147 removes is the
     * **upload** (`Bitmap.setPixels` moving up to 8.3 MB on Main), not the draw.
     * [onDraw] is a single hardware-accelerated `drawBitmap`, and the redraw was
     * never the expensive half. Recorded explicitly so a future reader does not
     * "complete" the optimisation by reintroducing a call the platform discards.
     */
    private fun requestRedraw() = invalidate()

    /** Drops the framebuffer on disconnect so a stale desktop isn't left on screen. */
    fun clearFrame() {
        // §1.147: the queued frame points at the disconnected client's array; keeping
        // it would repaint the desktop after it was cleared.
        pendingFrame = null
        bitmap = null
        // §1.150: and the back buffer with it — plus an epoch bump, so a copy still
        // running against the old buffer cannot promote the cleared desktop back
        // onto the screen when it finishes.
        backBitmap = null
        frontStale = VncRect.EMPTY
        backStale = VncRect.EMPTY
        // Phase 2C instrumentation: per-session flag reset — clearFrame() is the session boundary.
        firstPromoteLogged = false
        uploadEpoch++
        frameWidth = 0
        frameHeight = 0
        uploadedGeneration = -1L
        // Desktop Zoom: no framebuffer means no content — identity transform
        // (fit scale 1, centred), and the next session starts unzoomed-panned.
        zoom = zoom.resized(this.width, this.height, 0, 0)
        invalidate()
    }

    /**
     * The draw/coordinate transform, entirely zoom-owned (Desktop Zoom,
     * 2026-08). The old contain-scale helpers ([scale]/[offsetX]/[offsetY])
     * were one implementation of the same contract; the zoom is its extension
     * with the user multiplier and pan, in [VncZoom] — the same value must
     * produce draw placement AND pointer translation or the cursor moves by a
     * different amount than the finger.
     */
    private val drawZoom: VncZoom get() = zoom

    /**
     * View-pixel distance → framebuffer-pixel distance.
     *
     * The only translation relative touch needs: the finger's *position* is
     * never converted to a cursor position (that is the teleport being fixed),
     * only its movement is. Dividing by the effective scale keeps the cursor
     * moving the same on-screen distance as the finger, whatever the resolution
     * the server is running and whatever the user's zoom is.
     */
    private fun toFrameDelta(viewDelta: Float): Float = drawZoom.frameDelta(viewDelta)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = bitmap ?: return
        val z = drawZoom
        val left = z.offsetX.toInt()
        val top = z.offsetY.toInt()
        destRect.set(
            left, top,
            left + z.contentWidth.toInt(), top + z.contentHeight.toInt()
        )
        canvas.drawBitmap(target, null, destRect, null)
    }

    // ── Touch ────────────────────────────────────────────────────────────
    //
    // Handled here rather than with a Compose `pointerInput` on top of the
    // AndroidView: two gesture owners for one surface is how a tap ends up
    // consumed by the wrong one. The *rules* stay in the pure [VncTouchGesture];
    // this only translates `MotionEvent`s into [VncTouch] and performs the
    // [VncPointerAction]s it returns.
    //
    // §1.138: the pre-Phase-8 code carried the state machine inline here (six
    // fields and a `when` over `actionMasked`), so the interaction could only be
    // verified by hand. Two of its rules were wrong in ways no test existed to
    // catch: any movement pressed the left button (there was no way to move the
    // pointer without dragging), and a hold — the natural start of a drag — was a
    // right click.
    //
    // Pointer input is **relative** (touchpad-style). RFB carries an absolute
    // framebuffer coordinate, so forwarding the touch position teleported the remote
    // cursor under the finger on the first sample of every gesture: the cursor left
    // wherever the user was working, and landed under the fingertip that was
    // covering the target. The absolute coordinate is therefore synthesised — the
    // finger's *movement* is integrated into [cursor], and its *position* is never
    // converted at all.

    // Both machines exist for the lifetime of the view; [activeGesture] names
    // the one driving the current gesture (chosen at finger-down from
    // [touchMode], so a settings switch takes effect on the next touch). A
    // machine's state never leaks across mode switches because a switch can
    // only be observed between gestures.
    private val relativeGesture = VncTouchGesture()
    private val directGesture = VncDirectGesture()

    /**
     * The remote cursor's own position. Touch input is **relative**: RFB has no
     * relative pointer message, so the absolute coordinate on the wire is
     * synthesised by integrating finger deltas into this rather than by translating
     * the finger's position. Placing the cursor under the finger meant every touch
     * teleported it away from wherever the user was working, and left it under the
     * fingertip that was covering the target.
     */
    private val cursor = VncRelativeCursor()

    /** The previous touch sample, in view pixels, to difference against. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /**
     * Finger movement seen but not yet sent, in **framebuffer** pixels.
     *
     * Accumulated rather than applied on the spot because the gesture machine drops
     * samples it considers jitter, and a delta applied without a message being sent
     * would move *this* client's idea of the cursor while the server's stayed where
     * it was — a divergence that grows for the rest of the session. Pending movement
     * is folded into the cursor at the moment something is actually put on the wire,
     * so slow movement accumulates instead of being lost, and a two-finger scroll
     * (which sends no pointer message) discards it at the end of the gesture.
     */
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f

    /** The armed hold timer, so it can be cancelled the moment the finger moves. */
    private val holdRunnable = Runnable {
        perform(currentMachine().onTouch(VncTouch.HoldElapsed(android.os.SystemClock.uptimeMillis())))
    }

    /** The machine driving the current gesture, or the default for a stray timer. */
    private fun currentMachine(): VncGestureMachine =
        activeGesture ?: relativeGesture

    /**
     * Two-finger midpoint tracking (Desktop Zoom, 2026-08): while zoomed in, a
     * two-finger drag pans the content instead of scrolling it, so the zoomed
     * area beyond the screen edges stays reachable. The midpoint delta is
     * accumulated here (the gesture machine only reports the vertical wheel
     * component) and consumed by [perform] when a wheel action arrives while
     * [VncZoom.pannable] — one input, one meaning, chosen by the zoom state:
     * at fit a two-finger drag scrolls, zoomed in it pans.
     */
    private var multiPanActive = false
    private var multiPanDeltaX = 0f
    private var multiPanDeltaY = 0f
    private var lastMultiX = 0f
    private var lastMultiY = 0f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Focus is what makes the IME target this view at all — and it was never
        // requested before §1.125, so the keyboard had no destination.
        if (!isFocused) requestFocus()

        // The pinch recognizer runs alongside the gesture machine: it consumes
        // the distance changes between the fingers (zoom), while the machine
        // consumes the button semantics of the same events. Its scale factor is
        // incremental, so it multiplies the current zoom; the focal point is the
        // pinch centroid, so the content under the fingers stays under them.
        scaleDetector.onTouchEvent(event)

        val touch = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // The gesture's machine is chosen at finger-down from
                // [touchMode]: a settings switch takes effect on the next
                // touch, never mid-gesture.
                activeGesture = when (touchMode) {
                    DesktopTouchMode.DIRECT -> directGesture
                    DesktopTouchMode.RELATIVE -> relativeGesture
                }
                multiPanActive = false
                multiPanDeltaX = 0f
                multiPanDeltaY = 0f
                // Relative input: the contact point is only an origin to measure
                // movement from. Nothing is sent, and the remote cursor stays
                // exactly where the user left it. (Direct input's use of the
                // contact point happens inside [VncDirectGesture]'s actions,
                // through [VncPointerAction.PointerAt].)
                beginTouch(event.x, event.y)
                VncTouch.Down(event.x, event.y, event.eventTime)
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    if (multiPanActive) {
                        multiPanDeltaX += midX - lastMultiX
                        multiPanDeltaY += midY - lastMultiY
                    }
                    lastMultiX = midX
                    lastMultiY = midY
                    multiPanActive = true
                } else {
                    accumulateTouchDelta(event.getX(0), event.getY(0))
                }
                VncTouch.Move(event.getX(0), event.getY(0), event.eventTime)
            }

            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger arriving must not read as movement of the first.
                resyncTouchOrigin(event.getX(0), event.getY(0))
                multiPanActive = false
                multiPanDeltaX = 0f
                multiPanDeltaY = 0f
                VncTouch.SecondaryDown(
                    x = event.getX(0),
                    y = event.getY(0),
                    timeMs = event.eventTime,
                    pointerCount = event.pointerCount
                )
            }

            android.view.MotionEvent.ACTION_POINTER_UP -> {
                resyncTouchOrigin(event.getX(0), event.getY(0))
                multiPanActive = false
                multiPanDeltaX = 0f
                multiPanDeltaY = 0f
                VncTouch.SecondaryUp(
                    x = event.getX(0),
                    y = event.getY(0),
                    timeMs = event.eventTime,
                    pointerCount = event.pointerCount - 1
                )
            }

            android.view.MotionEvent.ACTION_UP -> {
                // Deliberately no delta and no flush: lifting must not reposition
                // the cursor, so a release is sent at wherever the cursor already is.
                endTouch()
                VncTouch.Up(event.getX(0), event.getY(0), event.eventTime)
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                endTouch()
                VncTouch.Cancel
            }

            else -> return super.onTouchEvent(event)
        }

        // §1.138: no tap-to-show-keyboard. A tap is a left click on the remote
        // desktop, so raising the IME on it threw a keyboard over the window the
        // user had just clicked into, on every click — and it was still the only
        // way to *dismiss* the keyboard. The explicit toggle
        // ([toggleSoftKeyboard], surfaced by the desktop's overlay control) is the
        // one entry point now, so clicking and typing are separate intents.
        //
        // Pre-release audit fix (2026-09-02, finding D1): while a PINCH is in
        // progress, the two-finger gesture machine must not ALSO run — the old
        // path fed the same move events to both, so every pinch additionally
        // scrolled the remote desktop (fit mode) or panned the view (zoomed)
        // with steps derived from the pinch travel. The machine is skipped
        // during pinch (its button state was armed at Down and is released at
        // Up/Cancel below); touch deltas re-sync on the next single-finger
        // move via the existing ACTION_POINTER_UP/resync path.
        if (!scaleDetector.isInProgress) {
            perform(currentMachine().onTouch(touch))
        }
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        ) {
            activeGesture = null
        }
        return true
    }

    /** Starts a gesture: remember where the finger landed, send nothing. */
    private fun beginTouch(x: Float, y: Float) {
        lastTouchX = x
        lastTouchY = y
        pendingDeltaX = 0f
        pendingDeltaY = 0f
    }

    /** Adds this sample's movement to the pending delta. */
    private fun accumulateTouchDelta(x: Float, y: Float) {
        pendingDeltaX += toFrameDelta(x - lastTouchX)
        pendingDeltaY += toFrameDelta(y - lastTouchY)
        lastTouchX = x
        lastTouchY = y
    }

    /**
     * Moves the origin without producing movement — for a finger count change,
     * where the reported primary position can jump without the hand having moved.
     */
    private fun resyncTouchOrigin(x: Float, y: Float) {
        lastTouchX = x
        lastTouchY = y
    }

    private fun endTouch() {
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        multiPanDeltaX = 0f
        multiPanDeltaY = 0f
        multiPanActive = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The zoom's clamping bounds are view-size dependent: keep the user's
        // multiplier, re-derive scale/pan from the new size (VncZoom.resized
        // resets the pan to the centred fit, the same place the view starts).
        zoom = zoom.resized(w, h, frameWidth, frameHeight)
        invalidate()
    }

    /**
     * Performs one action list.
     *
     * For the relative machine, pointer messages carry the *cursor's* position:
     * it emits no coordinates at all (§1.139), and pending finger movement is
     * integrated into [cursor] here, at the one point where a message is
     * actually sent, so the client's cursor and the server's stay in step. The
     * direct machine's [VncPointerAction.PointerAt] supplies the finger's view
     * position, mapped through the zoom here.
     */
    private fun perform(actions: List<VncPointerAction>) {
        actions.forEach { action ->
            when (action) {
                is VncPointerAction.Pointer -> {
                    flushPendingMovement()
                    onPointer(cursor.frameX, cursor.frameY, action.buttonMask)
                }

                is VncPointerAction.PointerAt -> {
                    // Direct touch: the finger's VIEW position maps through the
                    // zoom transform to framebuffer coordinates; the cursor is
                    // placed there and the event goes out at that position (the
                    // machine's "where" answer, the view's coordinate maths).
                    val (fx, fy) = drawZoom.viewToFrame(action.x, action.y)
                    cursor.placeTo(fx, fy)
                    onPointer(cursor.frameX, cursor.frameY, action.buttonMask)
                }

                is VncPointerAction.Wheel -> {
                    if (drawZoom.pannable) {
                        // Zoomed in: a two-finger drag pans the content instead of
                        // scrolling it, so the parts of the desktop beyond the
                        // screen edges stay reachable. The machine's deltaY is the
                        // fingers' vertical travel (view px), and the midpoint's
                        // horizontal travel is tracked alongside it; content
                        // follows the fingers, so the pan adds the raw deltas.
                        applyZoom(drawZoom.panned(multiPanDeltaX, action.deltaY))
                        multiPanDeltaX = 0f
                        multiPanDeltaY = 0f
                    } else {
                        // A scroll happens at the cursor, and must not drag it along
                        // with the two fingers performing it.
                        endTouch()
                        onWheel(cursor.frameX, cursor.frameY, action.deltaY)
                    }
                }

                is VncPointerAction.Haptic ->
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

                is VncPointerAction.ScheduleHold -> {
                    removeCallbacks(holdRunnable)
                    postDelayed(holdRunnable, action.delayMs)
                }

                is VncPointerAction.CancelHold -> removeCallbacks(holdRunnable)
            }
        }
    }

    private fun flushPendingMovement() {
        if (pendingDeltaX == 0f && pendingDeltaY == 0f) return
        cursor.moveBy(pendingDeltaX, pendingDeltaY)
        pendingDeltaX = 0f
        pendingDeltaY = 0f
    }

    override fun onDetachedFromWindow() {
        // A view torn down mid-drag would otherwise leave the remote button down
        // for the rest of the session.
        removeCallbacks(holdRunnable)
        perform(currentMachine().reset())
        activeGesture = null
        // §1.152: there is no armed vsync callback to remove any more — the frame
        // callback §1.147 posted (and this method had to unpost, because the
        // Choreographer holds it strongly and it holds the view and the
        // multi-megabyte frame it points at) is gone with the hop. The queued frame is
        // still dropped, for the same reachability reason.
        pendingFrame = null
        // §1.150: the upload thread and any promotion it has already posted must not
        // outlive the view either. The epoch bump makes an in-flight copy's
        // promotion a no-op, the posted callbacks are dropped, and `shutdown()`
        // (not `shutdownNow()`) lets a copy already writing a bitmap finish rather
        // than be interrupted mid-`setPixels`.
        //
        // The back buffer is *released*, not kept, precisely because that copy may
        // still be writing it: an `AndroidView` can be detached and reattached, and
        // a new copy dispatched into the same bitmap would then have two threads
        // inside `setPixels` on one `Bitmap`. Dropping the reference means the next
        // frame allocates a fresh pair ([allocateBuffers]) and the finishing copy
        // writes something nothing will ever read. The *front* buffer is deliberately
        // kept: it is what [onDraw] shows if the view is reattached before the next
        // framebuffer update arrives, and an idle desktop can be a long time.
        uploadEpoch++
        mainHandler.removeCallbacksAndMessages(null)
        uploadExecutor?.shutdown()
        uploadExecutor = null
        uploadInFlight = false
        backBitmap = null
        super.onDetachedFromWindow()
    }
}
