package com.fem.linexa.ui.screens.desktop

import kotlin.math.max
import kotlin.math.min

/**
 * Desktop Zoom (2026-08) — pure zoom/pan state for the VNC viewer.
 *
 * ## Why this exists
 *
 * The viewer used to have exactly one scaling decision: the contain-scale
 * (`min(viewW/frameW, viewH/frameH)`), applied to draw *and* to pointer
 * translation by construction. That is correct as far as it goes, but it means
 * a 1920×1080 desktop drawn on a ~6-inch phone screen renders every panel and
 * icon at a fraction of its intended physical size, with no way to look closer.
 * Every mature mobile VNC client (including the Tiny Container frontend's
 * AVNC-based viewer, whose `scale_ratio` feature feeds a zoom factor) exposes a
 * user-controlled zoom for exactly this reason.
 *
 * This object is that zoom, as pure maths: no `View`, no `MotionEvent`, no
 * Android import, so the whole coordinate contract is assertable in a JVM test.
 *
 * ## The coordinate contract
 *
 * There are now **two** multiplicative factors and one additive one between a
 * view pixel and a framebuffer pixel:
 *
 * ```
 * view = pan + frame * baseScale * userZoom
 * ```
 *
 * - `baseScale` — the contain-scale, unchanged from the pre-zoom behaviour.
 *   At `userZoom == 1` the whole drawing degrades to exactly the historical
 *   contain-fit image, centred: the zoom feature is invisible when unused.
 * - `userZoom` — the user's multiplier, clamped to [MIN_ZOOM]..[MAX_ZOOM].
 *   `MIN_ZOOM` is 1 so the framebuffer is never drawn smaller than the
 *   contain-fit: shrinking a desktop below fit only letterboxes it further,
 *   which the contain-scale already handles at 1.
 * - `pan` — the view-coordinate offset of the framebuffer's top-left corner.
 *   At `userZoom == 1` it is the historical centring offset and panning is
 *   disabled; when zoomed in, panning is clamped so the framebuffer always
 *   covers the view (no black gutters inside the zoomed content, no flinging
 *   the desktop off-screen).
 *
 * Both directions of the input mapping ([viewToFrame] for direct touch,
 * [frameDelta] for relative cursor movement and scroll deltas) derive from the
 * same three numbers the draw path uses, so a tap lands where it was drawn and
 * the cursor keeps pace with the finger at any zoom — the same
 * "one scale for draw and input" rule the contain-scale established, extended
 * rather than duplicated.
 *
 * ## What is deliberately NOT here
 *
 * Pinch-gesture recognition (the `ScaleGestureDetector` wiring) and zoom
 * persistence live in the view/screen layers. Zoom is a *viewing* aid, not a
 * workspace setting: it is per-session state, reset when the framebuffer
 * geometry changes or the session ends, like every other viewer (AVNC
 * included) treats it by default.
 */
internal data class VncZoom(
    val viewWidth: Int,
    val viewHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val userZoom: Float = MIN_ZOOM,
    val panX: Float = 0f,
    val panY: Float = 0f
) {

    companion object {
        /** The contain-fit multiplier — never draw the desktop smaller than fit. */
        const val MIN_ZOOM = 1f

        /**
         * Zoom ceiling. 4× doubles each linear dimension twice over; beyond it a
         * phone-resolution framebuffer shows individual X pixels and the pointer
         * mapping degrades (one view pixel = a fraction of a frame pixel). The
         * same ceiling class of viewers converge on (AVNC's default range is
         * comparable), chosen to bound pan bookkeeping, not to be clever.
         */
        const val MAX_ZOOM = 4f

        /** A degenerate (zero-sized) view or frame yields the identity zoom. */
        private const val IDENTITY_SCALE = 1f
    }

    init {
        require(userZoom in MIN_ZOOM..MAX_ZOOM) { "userZoom out of range: $userZoom" }
    }

    /** The historical contain-scale: how the framebuffer fits the view untouched. */
    val baseScale: Float by lazy {
        if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            IDENTITY_SCALE
        } else {
            min(
                viewWidth.toFloat() / frameWidth.toFloat(),
                viewHeight.toFloat() / frameHeight.toFloat()
            )
        }
    }

    /** The multiplier actually applied to framebuffer pixels when drawing. */
    val effectiveScale: Float get() = baseScale * userZoom

    /** Whether the framebuffer currently extends beyond the view in either axis. */
    val pannable: Boolean get() = userZoom > MIN_ZOOM

    /**
     * The view-space position of the framebuffer's top-left corner.
     *
     * At fit this is the historical centring (the contain-scale letterbox);
     * when zoomed it is the pan, clamped by [clampedPan] so the content covers
     * the view.
     */
    val offsetX: Float by lazy { clampedPan(panX, contentWidth, viewWidth) }
    val offsetY: Float by lazy { clampedPan(panY, contentHeight, viewHeight) }

    val contentWidth: Float get() = frameWidth * effectiveScale
    val contentHeight: Float get() = frameHeight * effectiveScale

    /**
     * A zoomed state at [newZoom], keeping the framebuffer point under
     * [focusViewX]/[focusViewY] under the same view point after the change —
     * the standard focal-point transform, so a pinch zooms around where the
     * fingers are instead of around the top-left corner.
     */
    fun zoomed(newZoom: Float, focusViewX: Float, focusViewY: Float): VncZoom {
        val target = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (target == userZoom) return this
        // Content point (in view space, at the current zoom) that must stay put.
        val contentX = focusViewX - offsetX
        val contentY = focusViewY - offsetY
        // The same content point at the new zoom scales by the zoom ratio.
        val ratio = target / userZoom
        val rawPanX = focusViewX - contentX * ratio
        val rawPanY = focusViewY - contentY * ratio
        return copy(userZoom = target, panX = rawPanX, panY = rawPanY)
    }

    /** A state panned by a view-space [delta], clamped so content covers the view. */
    fun panned(deltaX: Float, deltaY: Float): VncZoom {
        if (!pannable || (deltaX == 0f && deltaY == 0f)) return this
        return copy(panX = panX + deltaX, panY = panY + deltaY)
    }

    /** Reset to the contain-fit view (zoom 1, centred). */
    fun reset(): VncZoom = copy(userZoom = MIN_ZOOM, panX = 0f, panY = 0f)

    /**
     * A framebuffer size change invalidates both the scale and the pan (the
     * clamping bounds are different numbers now). The zoom *multiplier* is
     * kept — a reconnect or resize should not lose the user's chosen
     * magnification — while the pan restarts at the centred fit, the same
     * place the view starts from anyway.
     */
    fun resized(newViewWidth: Int, newViewHeight: Int, newFrameWidth: Int, newFrameHeight: Int): VncZoom =
        copy(
            viewWidth = newViewWidth,
            viewHeight = newViewHeight,
            frameWidth = newFrameWidth,
            frameHeight = newFrameHeight,
            panX = 0f,
            panY = 0f
        )

    /**
     * View point → framebuffer point. Inverse of the draw transform. Used by
     * direct touch: the finger's view position is where the click means.
     */
    fun viewToFrame(viewX: Float, viewY: Float): Pair<Float, Float> {
        val s = effectiveScale
        if (s <= 0f) return 0f to 0f
        return (viewX - offsetX) / s to (viewY - offsetY) / s
    }

    /**
     * A view-space distance → framebuffer-space distance. Used by relative
     * cursor movement: the cursor moves the same *view* distance as the finger,
     * whatever the zoom.
     */
    fun frameDelta(viewDelta: Float): Float {
        val s = effectiveScale
        return if (s <= 0f) viewDelta else viewDelta / s
    }

    private fun clampedPan(raw: Float, content: Float, view: Int): Float {
        if (content <= view) {
            // Content fits (or exactly fills): centred, never pannable.
            return (view - content) / 2f
        }
        // Content overruns: keep it covering the view, with no slack at either edge.
        return raw.coerceIn(view - content, 0f)
    }
}
