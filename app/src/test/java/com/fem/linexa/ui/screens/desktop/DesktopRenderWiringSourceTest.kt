package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.147 structural guard for the desktop's **render** path, revised by §1.150 and
 * §1.152.
 *
 * [VncDirtyRegionTest] covers the bookkeeping, which is pure. The half that is
 * not pure — `VncSurfaceView`'s upload — cannot be unit-tested in this repo:
 * `Bitmap.setPixels`, `Choreographer` and `invalidate(Rect)` are Android
 * framework calls with no JVM implementation, and there is no Robolectric or
 * Compose harness here (§1.124(a)). The alternative to a source test is no
 * coverage at all of the wiring, which is precisely where this class of defect
 * lives: every pre-§1.147 render cost compiled perfectly and could only be seen
 * on a device.
 *
 * Same technique and rationale as [DesktopInputWiringSourceTest] (§1.138),
 * `AboutWiringSourceTest` (§1.130) and `DebugInfoWiringSourceTest` (§1.137).
 *
 * The asserted properties are the ones whose loss silently restores the old
 * behaviour:
 *
 *  - the upload is dispatched on frame arrival, not deferred to a vsync (§1.152);
 *  - a burst of updates still collapses to one copy at a time;
 *  - the upload is scoped to the dirty region, with the frame width as stride;
 *  - the acknowledgement loop is closed, or the region only ever grows;
 *  - a resize and an undescribed frame both upload in full;
 *  - the copy runs off Main and the swap runs on it;
 *  - the receive loop and the input writes are not dispatched on Main (§1.153);
 *  - the three counters that make any of the above measurable exist (§1.153);
 *  - those counters are reachable from a UI without adb, and reaching them costs the
 *    frame path nothing (§1.165).
 */
class DesktopRenderWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/desktop/$path").readText()

    private val surfaceView: String get() = source("VncSurfaceView.kt")
    private val viewer: String get() = source("VncViewer.kt")
    private val client: String get() = source("VncClient.kt")

    /** §1.153 — the screen owns the client's construction, and therefore its scope. */
    private val screen: String get() = source("DesktopScreen.kt")

    /**
     * The body of `uploadPendingFrame` and the two halves it dispatches to
     * (`copyRegionIntoBuffer`, `promoteUploadedFrame`) — §1.150 split one function
     * into three, but the upload *decision* is still exactly this span.
     */
    private val uploadBody: String
        get() = surfaceView
            .substringAfter("private fun uploadPendingFrame()")
            .substringBefore("private fun uploadRegion(")

    // ── dispatch latency (§1.152) ────────────────────────────────────────

    @Test
    fun theUploadIsNotDeferredToAVsync() {
        // §1.147 posted the upload to a Choreographer frame callback, which was
        // correct while the copy ran inline: the callback runs in the ANIMATION phase
        // and the traversal later in the SAME vsync drew the result. §1.150 moved the
        // copy to a worker, and that broke the ordering silently — the callback now
        // only dispatches, the copy finishes after that vsync's traversal, and the
        // promotion's invalidate() can only be served by the NEXT vsync. Every update
        // paid a full extra display frame.
        //
        // Asserted as an absence because reinstating the hop looks like frame pacing
        // and produces a correct picture — just a consistently later one.
        assertFalse(
            "the render path must not post a Choreographer frame callback",
            surfaceView.contains("postFrameCallback(")
        )
        assertFalse(
            "and must not hold a frame callback at all",
            surfaceView.contains("Choreographer.FrameCallback")
        )
    }

    @Test
    fun submitFrameDispatchesTheUploadItself() {
        val submitBody = surfaceView
            .substringAfter("fun submitFrame(frame: VncFrame)")
            .substringBefore("/**")
        assertTrue(
            "submitFrame must queue the frame: $submitBody",
            submitBody.contains("pendingFrame = frame")
        )
        assertTrue(
            "and dispatch it immediately: $submitBody",
            submitBody.contains("uploadPendingFrame()")
        )
    }

    @Test
    fun submittingAFrameDoesNotCopyPixelsOnTheCallersThread() {
        // submitFrame runs on Main (the frame collector posts it). It may DISPATCH the
        // copy — that is §1.152 — but it must never perform one: that is the §1.150
        // cost, and it would be back on Main.
        val submitBody = surfaceView
            .substringAfter("fun submitFrame(frame: VncFrame)")
            .substringBefore("/**")
        assertFalse(
            "submitFrame must not touch the bitmap: $submitBody",
            submitBody.contains("setPixels")
        )
    }

    @Test
    fun onlyTheNewestQueuedFrameIsUploaded() {
        // Queueing into a list would reintroduce one upload per RFB update; the
        // frames share one live pixel array, so the newest carries the rest.
        assertTrue(
            "the pending frame must be a single slot",
            surfaceView.contains("private var pendingFrame: VncFrame? = null")
        )
    }

    @Test
    fun aBurstOfFramesStillCollapsesToOneCopyAtATime() {
        // This is what replaced the vsync gate. Without it §1.152 would dispatch one
        // copy per RFB update — two threads inside setPixels on one bitmap, and the
        // very cost the pacing existed to remove.
        assertTrue(
            "the upload must bail out while a copy is running: $uploadBody",
            uploadBody.contains("if (uploadInFlight) return")
        )
        assertTrue(
            "and the held-back frame must be dispatched by the promotion: $promoteBody",
            promoteBody.contains("if (pendingFrame != null) uploadPendingFrame()")
        )
    }

    @Test
    fun aDetachedViewCopiesInlineRatherThanQueueingForever() {
        // A detached view has no thread it could reliably shut down, so the copy is
        // done inline — but it must still happen, or a reattached view would draw a
        // buffer nothing ever filled.
        assertTrue(
            "the detached path must copy inline: $uploadBody",
            uploadBody.contains("if (!isAttachedToWindow)") &&
                uploadBody.contains("copyRegionIntoBuffer(target, frame, region)")
        )
    }

    @Test
    fun theSocketDoesNotHoldBackSmallWrites() {
        // §1.152. Every message this client sends is tiny and latency-critical — a
        // PointerEvent is 6 bytes, a KeyEvent 8, a FramebufferUpdateRequest 10 — and
        // Nagle's algorithm holds a small write until the previous segment is
        // acknowledged. A deferred pointer write delays the server's cursor repaint,
        // which delays the FramebufferUpdate this whole render path is waiting to
        // draw, so the finger and the remote cursor visibly diverge.
        //
        // Guarded here because the default is ON and its absence is invisible: the
        // session works, it is just intermittently late.
        assertTrue(
            "the socket must disable Nagle",
            client.contains("tcpNoDelay = true")
        )
    }

    // ── scoped upload ────────────────────────────────────────────────────

    @Test
    fun theUploadIsScopedToTheDirtyRegion() {
        assertTrue(
            "the upload must consult the frame's dirty region",
            uploadBody.contains("uploadRegion(frame)")
        )
        assertTrue(
            "and pass the region's origin and extent to setPixels: $uploadBody",
            uploadBody.contains("region.x") &&
                uploadBody.contains("region.y") &&
                uploadBody.contains("region.width") &&
                uploadBody.contains("region.height")
        )
    }

    @Test
    fun theUploadStrideIsTheFrameWidthNotTheRegionWidth() {
        // The one scoped-upload mistake that still produces a plausible image:
        // setPixels walks the SOURCE by stride per row, and the source is the full
        // framebuffer, so a region-width stride skews every row.
        assertTrue(
            "stride must be the frame width: $uploadBody",
            uploadBody.contains("frame.width,\n            region.x,")
        )
        assertFalse(
            "stride must not be the region width: $uploadBody",
            uploadBody.contains("region.width,\n            region.x,")
        )
    }

    @Test
    fun theSourceOffsetSkipsToTheRegionsFirstPixel() {
        // Offset 0 with a scoped destination uploads the top-left of the
        // framebuffer INTO the dirty rectangle — a visible smear, not a no-op.
        assertTrue(
            "the source offset must be the region's first index: $uploadBody",
            uploadBody.contains("region.y * frame.width + region.x")
        )
    }

    @Test
    fun theRedrawRequestDoesNotUseTheDiscardedScopedOverload() {
        // The region-scoped invalidate(l, t, r, b) is deprecated *because* the
        // platform has ignored its rectangle since API 21 and computes its own
        // damage. minSdk is 28, so calling it would buy nothing and only look like
        // an optimisation. §1.147's saving is the upload, not the draw.
        assertTrue(
            "the upload must request a redraw",
            uploadBody.contains("requestRedraw()")
        )
        assertFalse(
            "the deprecated four-int invalidate must not be reintroduced",
            surfaceView.contains("invalidate(\n")
        )
    }

    // ── full-upload fallbacks ────────────────────────────────────────────

    @Test
    fun aFreshBitmapIsUploadedInFull() {
        // A reallocated bitmap holds nothing, so a scoped upload leaves the rest
        // transparent.
        val regionBody = surfaceView
            .substringAfter("private fun uploadRegion(frame: VncFrame): VncRect")
            .substringBefore("private fun requestRedraw()")
        assertTrue(
            "a just-allocated bitmap must force a full upload: $regionBody",
            regionBody.contains("if (uploadedGeneration == -1L) return full")
        )
    }

    @Test
    fun anUndescribedFrameIsUploadedInFull() {
        // EMPTY means "the producer did not say", and the only safe reading of an
        // unknown change is all of it. The opposite default turns any future
        // publish path that forgets its rectangles into a frozen desktop.
        val regionBody = surfaceView
            .substringAfter("private fun uploadRegion(frame: VncFrame): VncRect")
            .substringBefore("private fun requestRedraw()")
        assertTrue(
            "an empty dirty region must mean full upload: $regionBody",
            regionBody.contains("if (dirty.isEmpty) return full")
        )
    }

    @Test
    fun aResizeMarksTheWholeFramebufferDirty() {
        val resizeBody = client
            .substringAfter("private fun resizeFramebuffer(width: Int, height: Int)")
            .substringBefore("private suspend fun sendSetPixelFormat()")
        assertTrue(
            "resizeFramebuffer must reset the dirty region to the new bounds: $resizeBody",
            resizeBody.contains("dirty.resize(width, height)")
        )
    }

    // ── the acknowledgement loop ─────────────────────────────────────────

    @Test
    fun theProducerRecordsEveryRectangleItComposes() {
        // A rectangle blitted but not recorded is a permanently stale area of the
        // screen — the §1.114 defect class in a new place.
        val rectangleBody = client
            .substringAfter("private fun readRectangle(input: BufferedInputStream): Boolean")
            .substringBefore("private fun publishFrame()")
        assertTrue(
            "the Raw path must record its rectangle: $rectangleBody",
            rectangleBody.contains("if (written) dirty.union(x, y, width, height)")
        )
        assertTrue(
            "the CopyRect path must record its DESTINATION: $rectangleBody",
            rectangleBody.contains("if (changed) dirty.union(x, y, width, height)")
        )
    }

    @Test
    fun theCopyRectSourceIsNotMarkedDirty() {
        // A CopyRect reads its source and writes only its destination. Unioning
        // both roughly doubles the uploaded area for the commonest encoding on a
        // scroll — the exact case this change exists to make cheaper.
        val rectangleBody = client
            .substringAfter("ENCODING_COPY_RECT ->")
            .substringBefore("else -> throw")
        assertFalse(
            "the CopyRect source must not be unioned: $rectangleBody",
            rectangleBody.contains("dirty.union(u16(src")
        )
    }

    @Test
    fun everyPublishedFrameCarriesTheRegion() {
        // A frame published without one is read as "undescribed" and uploaded in
        // full — correct, but silently back to the pre-§1.147 cost.
        client.split("VncFrame(").drop(1).forEach { construction ->
            val args = construction.substringBefore(")")
            assertTrue(
                "every VncFrame VncClient publishes must carry a dirty region: $args",
                args.contains("dirty.publish(")
            )
        }
    }

    @Test
    fun theConsumerAcknowledgesWhatItUploaded() {
        // Without this the union only grows and every upload is full-framebuffer.
        assertTrue(
            "the view must report the uploaded generation",
            uploadBody.contains("onFrameUploaded(frame.generation)")
        )
        assertTrue(
            "and the viewer must wire it to the client",
            viewer.contains("onFrameUploaded = { generation -> vncClient.acknowledgeFrame(generation) }")
        )
    }

    @Test
    fun theAcknowledgementHappensAfterThePixelsAreOnTheBitmap() {
        // Acknowledging first lets the producer clear a region this upload had not
        // yet copied — a dropped update with no way to notice.
        val setPixelsAt = uploadBody.indexOf("setPixels")
        val ackAt = uploadBody.indexOf("onFrameUploaded(")
        assertTrue("setPixels must appear in the upload", setPixelsAt >= 0)
        assertTrue("the acknowledgement must appear in the upload", ackAt >= 0)
        assertTrue(
            "the acknowledgement must follow the upload: $uploadBody",
            ackAt > setPixelsAt
        )
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    @Test
    fun theQueuedFrameDoesNotOutliveTheView() {
        // A frame held here keeps a multi-megabyte pixel array — and the client that
        // owns it — reachable from a detached view. §1.152 removed the Choreographer
        // callback that also had to be unposted here (it held the view strongly);
        // dropping the queued frame is the part that still applies.
        val detachBody = surfaceView.substringAfter("override fun onDetachedFromWindow()")
        assertTrue(
            "detach must drop the queued frame: $detachBody",
            detachBody.contains("pendingFrame = null")
        )
    }

    @Test
    fun clearingTheFrameDropsTheQueuedOne() {
        // Otherwise a disconnect repaints the desktop from the disconnected client's
        // array after it was cleared.
        val clearBody = surfaceView
            .substringAfter("fun clearFrame()")
            .substringBefore("/**")
        assertTrue(
            "clearFrame must drop the pending frame: $clearBody",
            clearBody.contains("pendingFrame = null")
        )
    }

    // ── unchanged invariants ─────────────────────────────────────────────

    @Test
    fun theGenerationGuardStillPreventsRedundantUploads() {
        // §1.114's rule: an unrelated invalidate must not re-copy megabytes.
        assertTrue(
            "the upload must still skip an already-uploaded generation",
            uploadBody.contains("if (frame.generation == uploadedGeneration) return")
        )
    }

    @Test
    fun theUndersizedFrameGuardIsStillPresent() {
        assertTrue(
            "a short pixel array must still be refused",
            uploadBody.contains("if (frame.pixels.size < frame.width * frame.height) return")
        )
    }

    // ── §1.150: the copy is off the main thread ──────────────────────────

    /** The body of the copy itself, which must be the only thing off Main. */
    private val copyBody: String
        get() = surfaceView
            .substringAfter("private fun copyRegionIntoBuffer(")
            .substringBefore("private fun promoteUploadedFrame(")

    private val promoteBody: String
        get() = surfaceView
            .substringAfter("private fun promoteUploadedFrame(")
            .substringBefore("private fun allocateBuffers(")

    @Test
    fun thePixelCopyIsDispatchedToAWorkerThread() {
        // The whole point of §1.150. §1.147 measured setPixels at up to 8.3 MB per
        // frame and explicitly left it on Main; if the dispatch goes, that cost is
        // silently back and nothing else in this file would notice.
        assertTrue(
            "the upload must post the copy to the executor: $uploadBody",
            uploadBody.contains("uploadExecutor().execute {")
        )
        assertTrue(
            "and the copy must be what is posted: $uploadBody",
            uploadBody.contains("copyRegionIntoBuffer(target, frame, region)")
        )
    }

    @Test
    fun theOnlyPixelCopyLivesInTheWorkerFunction() {
        // A setPixels anywhere else is by definition on Main: every other method
        // here is called from the view's own callbacks.
        val copies = surfaceView.split("setPixels(").size - 1
        assertEquals("there must be exactly one setPixels call site", 1, copies)
        assertTrue(
            "and it must be the worker's: $copyBody",
            copyBody.contains("target.setPixels(")
        )
    }

    @Test
    fun theCopyTargetsTheBackBufferNotTheOneBeingDrawn() {
        // Writing `bitmap` off Main while onDraw reads it is exactly the tearing
        // this change has to avoid — and it would look like an intermittent glitch,
        // not a crash.
        assertTrue(
            "the copy target must be the back buffer: $uploadBody",
            uploadBody.contains("val target = backBitmap ?: return")
        )
    }

    @Test
    fun theBufferSwapHappensOnTheMainThread() {
        // The swap is what onDraw observes, so it must be posted back rather than
        // performed on the worker.
        assertTrue(
            "the promotion must be posted to the main looper: $uploadBody",
            uploadBody.contains("mainHandler.post { promoteUploadedFrame(")
        )
        assertTrue(
            "and it must swap the two references: $promoteBody",
            promoteBody.contains("backBitmap = bitmap") && promoteBody.contains("bitmap = shown")
        )
    }

    @Test
    fun theSwapIsAReferenceExchangeAndNotALockedCopy() {
        // A lock held across the copy would serialise the worker against every draw
        // and reintroduce the stall in a less visible place.
        assertFalse(
            "the upload path must not synchronize: $uploadBody",
            uploadBody.contains("synchronized(")
        )
        assertFalse(
            "and neither must the copy: $copyBody",
            copyBody.contains("synchronized(")
        )
    }

    @Test
    fun asecondCopyIsNotDispatchedWhileOneIsInFlight() {
        // Two workers on one back buffer, or a copy into the buffer a pending swap
        // is about to show. Since §1.152 removed the vsync gate this is also the only
        // thing coalescing a burst — see aBurstOfFramesStillCollapsesToOneCopyAtATime.
        assertTrue(
            "the upload must bail out while a copy is running: $uploadBody",
            uploadBody.contains("if (uploadInFlight) return")
        )
        assertTrue(
            "the executor must be single-threaded",
            surfaceView.contains("Executors.newSingleThreadExecutor")
        )
    }

    @Test
    fun aFrameHeldBackDuringACopyIsUploadedAfterIt() {
        // The frame is kept in `pendingFrame`, so dropping this would freeze the
        // desktop at whatever was on screen when the copy started.
        assertTrue(
            "the promotion must dispatch a queued frame: $promoteBody",
            promoteBody.contains("if (pendingFrame != null) uploadPendingFrame()")
        )
    }

    @Test
    fun theDemotedBufferRemembersWhatItMissed() {
        // Double buffering makes a scoped upload subtler: the buffer coming off
        // screen is missing the rectangle just applied to its twin, so the next copy
        // into it must cover that too. Without this the desktop alternates between
        // two versions of itself — a flicker, not a freeze.
        assertTrue(
            "the promotion must record the demoted buffer's debt: $promoteBody",
            promoteBody.contains("backStale = unionRects(frontStale, changed)")
        )
        assertTrue(
            "and the upload must add it to the copied region: $uploadBody",
            uploadBody.contains("unionRects(changed, backStale)")
        )
    }

    @Test
    fun bothBuffersStartOwingAFullCopy() {
        val allocateBody = surfaceView
            .substringAfter("private fun allocateBuffers(width: Int, height: Int)")
            .substringBefore("private fun uploadRegion(")
        assertTrue(
            "a fresh pair holds nothing, so both owe everything: $allocateBody",
            allocateBody.contains("frontStale = full") && allocateBody.contains("backStale = full")
        )
    }

    @Test
    fun aCopyAgainstAnAbandonedBufferIsNotInstalled() {
        // A resize or a disconnect can land while a copy is running; promoting it
        // afterwards puts the previous desktop back on screen.
        assertTrue(
            "the promotion must check the epoch it captured: $promoteBody",
            promoteBody.contains("if (epoch != uploadEpoch)")
        )
        assertTrue(
            "clearFrame must invalidate copies in flight",
            surfaceView
                .substringAfter("fun clearFrame()")
                .substringBefore("/**")
                .contains("uploadEpoch++")
        )
    }

    @Test
    fun theAcknowledgementStillFollowsTheCopyAcrossTheThreadHop() {
        // §1.147's rule survives the split: the producer may only release a dirty
        // region once the pixels are actually on a bitmap, which is now one thread
        // and one post later.
        val copyAt = uploadBody.indexOf("copyRegionIntoBuffer(target, frame, region)")
        val ackAt = uploadBody.indexOf("onFrameUploaded(")
        assertTrue("the copy must appear in the upload path", copyAt >= 0)
        assertTrue(
            "the acknowledgement must live in the promotion, after the copy: $promoteBody",
            ackAt > copyAt && promoteBody.contains("onFrameUploaded(frame.generation)")
        )
    }

    @Test
    fun theUploadThreadDoesNotOutliveTheView() {
        // An executor left running holds its thread, and a posted promotion holds
        // the view and the multi-megabyte frame it points at.
        val detachBody = surfaceView.substringAfter("override fun onDetachedFromWindow()")
        assertTrue(
            "detach must shut the executor down: $detachBody",
            detachBody.contains("uploadExecutor?.shutdown()")
        )
        assertTrue(
            "and drop any posted promotion: $detachBody",
            detachBody.contains("mainHandler.removeCallbacksAndMessages(null)")
        )
        assertTrue(
            "and invalidate a copy still in flight: $detachBody",
            detachBody.contains("uploadEpoch++")
        )
        assertFalse(
            // Matched as a call, not as a word: the detach comment names
            // `shutdownNow()` in order to explain why it is not used.
            "shutdownNow would interrupt a thread mid-setPixels: $detachBody",
            detachBody.contains("uploadExecutor?.shutdownNow()")
        )
    }

    // ── §1.153: the receive loop and the input writes are off the UI thread ──

    @Test
    fun theClientDoesNotTakeTheCompositionsScope() {
        // The strongest finding of the §1.153 audit, and the one that compiles
        // perfectly either way. `rememberCoroutineScope()` is dispatched on
        // AndroidUiDispatcher.Main, so a client constructed from it read every RFB
        // message and wrote every pointer event through the Main queue — behind the
        // frame collector, the buffer swap, recomposition and the traversal that the
        // previous frame caused. Frame delivery was bounded by the availability of the
        // thread that draws the frames, and competed with the drawing it produced.
        assertTrue(
            "DesktopScreen must construct the client with its own scope: $screen",
            screen.contains("remember { VncClient() }")
        )
        assertFalse(
            "the composition's scope must not be handed to the client",
            screen.contains("VncClient(scope)")
        )
        assertFalse(
            "and it must not be created for one either",
            screen.contains("val scope = rememberCoroutineScope()")
        )
    }

    @Test
    fun theClientsOwnScopeIsIoDispatched() {
        assertTrue(
            "the default scope must be a factory on the client: $client",
            client.contains("internal fun createReceiveScope(): CoroutineScope")
        )
        val factory = client
            .substringAfter("internal fun createReceiveScope(): CoroutineScope")
            .substringBefore("\n\n")
        assertTrue(
            "the scope must be IO-dispatched: $factory",
            factory.contains("Dispatchers.IO")
        )
        assertTrue(
            // A plain Job would let one failed socket write cancel the receive loop.
            "and supervised so a failed write cannot kill the loop: $factory",
            factory.contains("SupervisorJob()")
        )
    }

    @Test
    fun theReceiveLoopDoesNotRedispatchPerMessage() {
        // With the loop on IO, a `withContext(Dispatchers.IO)` inside the message read
        // is re-entering the dispatcher it is already on — two dispatches per RFB
        // message, one of them a Main-queue turn, on the hottest path in the app.
        val readBody = client
            .substringAfter("private suspend fun readServerMessage()")
            .substringBefore("private fun readRectangle(")
        assertFalse(
            "readServerMessage must not switch dispatchers: $readBody",
            readBody.contains("withContext(")
        )
        val requestBody = client
            .substringAfter("private suspend fun requestFramebufferUpdate(incremental: Boolean)")
            .substringBefore("/**")
        assertFalse(
            "and neither must the per-update request: $requestBody",
            requestBody.contains("withContext(")
        )
    }

    @Test
    fun theInputWritesReachTheSocketWithoutAMainQueueTurn() {
        // The other half of the same defect: a pointer move whose send waits for Main
        // delays the server's cursor repaint, which delays the FramebufferUpdate the
        // render path is waiting to draw — so the finger and the cursor diverge. This
        // is what §1.152's tcpNoDelay could not fix, because the delay was before the
        // write and not inside it.
        //
        // The dispatcher is named on each `launch` rather than inherited so that a
        // substituted scope (a test's) cannot put a blocking socket write on Main.
        //
        // Each body is taken up to the *end of its launch block* — the closing brace at
        // the function's own indentation — rather than up to the next kdoc, because the
        // last of the three has no following declaration to anchor on. Comments are
        // stripped for the reason `theRunningSurfaceIsNotInsetByTheScaffoldPadding`
        // already documents: the comment explaining why the hop was removed necessarily
        // names it.
        listOf(
            "fun sendKeysym(keysym: Int, pressed: Boolean)",
            "fun sendWheel(x: Int, y: Int, deltaY: Float)",
            "fun sendPointerEvent(x: Int, y: Int, buttonMask: Int)"
        ).forEach { signature ->
            val body = client
                .substringAfter(signature)
                .substringBefore("\n    }")
                .lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertTrue(
                "$signature must launch on IO: $body",
                body.contains("scope.launch(Dispatchers.IO)")
            )
            assertFalse(
                "$signature must not nest a withContext inside its launch: $body",
                body.contains("withContext(")
            )
        }
    }

    @Test
    fun theClientStopsItsOwnCoroutinesOnDisconnect() {
        // The lifecycle consequence of owning the scope: the composition going away no
        // longer cancels the loop, so disconnect() is the only thing that does. A
        // missed disconnect used to leak a socket; it would now leak a thread too.
        val disconnectBody = client
            .substringAfter("fun disconnect()")
            .substringBefore("private fun closeSocketQuietly()")
        assertTrue(
            "disconnect must cancel the receive job: $disconnectBody",
            disconnectBody.contains("receiveJob?.cancel()")
        )
        assertTrue(
            "and any queued input write with it: $disconnectBody",
            disconnectBody.contains("cancelChildren()")
        )
        assertFalse(
            // Cancelling the scope's own Job would make the client permanently
            // unusable, and the screen legitimately reconnects the same instance
            // (Running → not-Running → Running).
            "but it must not cancel the scope itself: $disconnectBody",
            disconnectBody.contains("scope.cancel()")
        )
        assertTrue(
            "and the screen must still call it on dispose",
            screen.contains("DisposableEffect(vncClient)") &&
                screen.contains("vncClient.disconnect()")
        )
    }

    // ── §1.153: the render path is measurable ────────────────────────────

    @Test
    fun theThreeCountersAreWired() {
        // The audit could prove three mechanisms from source and measure none of them,
        // because this path emits no timing at all — so every fix to it was
        // unfalsifiable. These are the three quantities that separate the candidate
        // causes from each other; see VncRenderStats.
        assertTrue(
            "the client must record the inter-update interval: $client",
            client.contains("stats.recordServerUpdate(")
        )
        assertTrue(
            "and whether an acknowledgement released anything: $client",
            client.contains("stats.recordAcknowledge(dirty.acknowledge(generation))")
        )
        assertTrue(
            "the view must report the size of the copy it performed: $copyBody",
            copyBody.contains("onFrameUploadedArea(")
        )
        assertTrue(
            "and the viewer must route it to the client",
            viewer.contains("vncClient.recordUploadedArea(uploaded, total)")
        )
    }

    @Test
    fun theUploadedAreaIsMeasuredAtTheCopyAndNotAtTheDispatch() {
        // A copy against a buffer the view has since abandoned is discarded by the
        // promotion's epoch check, but its pixels were still moved. Measuring at the
        // dispatch would understate the cost during exactly the churn worth
        // investigating.
        val setPixelsAt = copyBody.indexOf("setPixels(")
        val reportAt = copyBody.indexOf("onFrameUploadedArea(")
        assertTrue("the copy must be in the worker: $copyBody", setPixelsAt >= 0)
        assertTrue(
            "and the report must follow it there: $copyBody",
            reportAt > setPixelsAt
        )
    }

    @Test
    fun theInterUpdateIntervalIsMeasuredOnTheWireAndNotOnTheConsumersPace() {
        // Recorded before publishFrame, so it reports how often the server actually
        // delivers an update. Measured after it, it would report the collector's rate
        // and would improve on paper whenever the consumer fell further behind.
        val readBody = client
            .substringAfter("private suspend fun readServerMessage()")
            .substringBefore("private fun readRectangle(")
        val recordAt = readBody.indexOf("stats.recordServerUpdate(")
        val publishAt = readBody.indexOf("publishFrame()")
        assertTrue("the interval must be recorded: $readBody", recordAt >= 0)
        assertTrue("the frame must be published: $readBody", publishAt >= 0)
        assertTrue(
            "the record must precede the publish: $readBody",
            recordAt < publishAt
        )
    }

    @Test
    fun theMeasurementIsGatedOnTheUsersDebugSetting() {
        // Phase 7's rule, and it matters most here: this is the frame path, and an
        // ungated log per update would be a new instance of the cost the audit was
        // investigating. Asserted on the stats class itself rather than at the call
        // sites, because the call sites are deliberately unconditional — the gate is
        // one volatile read and putting it at each of them would invite drift.
        val stats = source("VncRenderStats.kt")
        assertTrue(
            "every record path must check DebugCapture: $stats",
            stats.split("if (!DebugCapture.isEnabled()) return").size - 1 >= 4
        )
        assertTrue(
            "and the periodic log must be a summary, not one line per frame: $stats",
            stats.contains("LOG_EVERY_UPDATES")
        )
    }

    @Test
    fun theSessionSummaryIsEmittedOnDisconnect() {
        // Most diagnostic sessions are shorter than the periodic interval — especially
        // while chasing a startup or first-frame problem — so without this they would
        // report nothing at all.
        val disconnectBody = client
            .substringAfter("fun disconnect()")
            .substringBefore("private fun closeSocketQuietly()")
        assertTrue(
            "disconnect must emit the session summary: $disconnectBody",
            disconnectBody.contains("stats.logSessionSummary()")
        )
    }

    @Test
    fun theCountersAreScopedToOneSession() {
        // The screen reconnects the SAME client instance (Running → not-Running →
        // Running), so without a reset the numbers from before a change and after it
        // would land in one mean — the exact confusion this instrumentation exists to
        // prevent, and invisible in the output.
        val connectBody = client
            .substringAfter("suspend fun connect(host: String")
            .substringBefore("performHandshake()")
        assertTrue(
            "connect must reset the counters: $connectBody",
            connectBody.contains("stats.reset()")
        )
    }

    // ── §1.165: the counters are readable on-device, not only loggable ────

    @Test
    fun theConnectingClientPublishesItsCountersForTheDebugPanel() {
        // §1.153's counters were unreadable without adb — Logger's only outlets are
        // logcat (restricted to the app's own UID) and an app-private file — which is
        // what blocked the device-verification pass. The registry is the fix, and it is
        // useless unless the client actually registers.
        val connectBody = client
            .substringAfter("suspend fun connect(host: String")
            .substringBefore("performHandshake()")
        assertTrue(
            "connect must attach the counters to the registry: $connectBody",
            connectBody.contains("VncRenderStatsRegistry.attach(stats)")
        )
        val resetAt = connectBody.indexOf("stats.reset()")
        val attachAt = connectBody.indexOf("VncRenderStatsRegistry.attach(stats)")
        assertTrue(
            "the attach must follow the reset, or the published window starts before the session: $connectBody",
            resetAt in 0 until attachAt
        )
    }

    @Test
    fun disconnectFreezesTheFinalNumbersForTheDebugPanel() {
        // Leaving the desktop disposes the client (§1.140), which is exactly when the
        // user goes to read the scenario they just ran. Retiring also freezes the
        // elapsed window — read live afterwards it would keep growing and flatter every
        // derived rate.
        val disconnectBody = client
            .substringAfter("fun disconnect()")
            .substringBefore("private fun closeSocketQuietly()")
        assertTrue(
            "disconnect must retire the registry entry: $disconnectBody",
            disconnectBody.contains("VncRenderStatsRegistry.retire()")
        )
    }

    @Test
    fun theDiagnosticsSurfaceAddsNoWorkToTheFramePath() {
        // The whole risk of a readable panel is that reading becomes a per-frame cost.
        // The registry is written twice per *connection* and read on a button press, so
        // neither of the two hot paths may mention it.
        val readBody = client
            .substringAfter("private suspend fun readServerMessage()")
            .substringBefore("private fun readRectangle(")
        assertFalse(
            "the receive loop must not touch the registry: $readBody",
            readBody.contains("VncRenderStatsRegistry")
        )
        assertFalse(
            "and neither must the upload report: $viewer",
            viewer.contains("VncRenderStatsRegistry")
        )
    }

    @Test
    fun theSnapshotCarriesTheWindowItCovers() {
        // Every counter is a total since reset, so a reading without its window is
        // uninterpretable — the specific gap the previous verification report named.
        val stats = source("VncRenderStats.kt")
        assertTrue(
            "the snapshot must report elapsed time: $stats",
            stats.contains("val elapsedMillis")
        )
        assertTrue(
            "and reset must restart the window: $stats",
            stats.contains("resetAtNanos = nowNanos()")
        )
    }

    @Test
    fun theRegistryHasExactlyOneWriterPair() {
        // Same rule DebugCapture follows: process-wide state with one writer, so the
        // panel and the session can never disagree about which counters are live. A
        // second attach site would be a second source of truth.
        val mainDir = File(moduleRoot, "src/main/java")
        val writers = mainDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.name != "VncRenderStatsRegistry.kt" }
            .filter { it.readText().contains("VncRenderStatsRegistry.attach(") }
            .map { it.name }
            .toList()
        assertEquals("exactly one attach site expected, found $writers", 1, writers.size)
        assertEquals("VncClient.kt", writers.first())
    }
}
