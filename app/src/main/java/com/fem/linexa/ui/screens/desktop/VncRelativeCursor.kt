package com.fem.linexa.ui.screens.desktop

/**
 * The remote cursor's position, moved **relatively** (touchpad-style) rather than
 * being placed wherever the finger lands.
 *
 * ## Why
 *
 * RFB has no relative pointer message: `PointerEvent` carries an absolute
 * framebuffer coordinate (RFB 3.8 §7.5.5), so a client that forwards the touch
 * position teleports the remote cursor under the finger on the first sample of
 * every gesture. On a phone that is the wrong model twice over — the finger covers
 * the thing being pointed at, and the cursor's previous position (which is where
 * the user was working) is lost on every touch.
 *
 * The absolute coordinate therefore has to be *synthesised*: this holds the
 * cursor's own position and integrates finger deltas into it, so touching the
 * glass moves nothing and moving the finger moves the cursor by the same amount.
 *
 * Kept as a separate class with no Android types for the same reason as
 * [VncTouchGesture] (§1.138): it is a rule, and the only way a rule gets checked
 * in this repo is from a JVM test.
 */
internal class VncRelativeCursor {

    private var frameWidth = 0
    private var frameHeight = 0

    /** Cursor position in framebuffer coordinates, kept sub-pixel. */
    var x = 0f
        private set
    var y = 0f
        private set

    /** The coordinate to put on the wire. */
    val frameX: Int get() = x.toInt()
    val frameY: Int get() = y.toInt()

    /**
     * Adopts a new framebuffer geometry, centring the cursor.
     *
     * Centre rather than 0,0: it is where an X server puts the pointer at startup,
     * so the client's idea of the cursor starts out agreeing with the server's, and
     * it is reachable from anywhere on screen with one swipe.
     */
    fun resize(width: Int, height: Int) {
        if (width == frameWidth && height == frameHeight) return
        frameWidth = width
        frameHeight = height
        x = width / 2f
        y = height / 2f
    }

    /**
     * Moves the cursor by a delta already expressed in **framebuffer** pixels,
     * clamped to the framebuffer so it cannot be pushed off and then have to be
     * swiped all the way back.
     */
    fun moveBy(deltaX: Float, deltaY: Float) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        x = (x + deltaX).coerceIn(0f, (frameWidth - 1).toFloat())
        y = (y + deltaY).coerceIn(0f, (frameHeight - 1).toFloat())
    }

    /**
     * Desktop Touch work (2026-08): places the cursor at an absolute
     * **framebuffer** position, clamped to the framebuffer.
     *
     * Used only by the direct touch mode ([VncDirectGesture] →
     * `VncPointerAction.PointerAt`): there the finger's position, mapped
     * view→frame by the view, IS the pointer's position. The relative mode
     * deliberately never calls this (the class KDoc's teleport defect).
     */
    fun placeTo(frameX: Float, frameY: Float) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        x = frameX.coerceIn(0f, (frameWidth - 1).toFloat())
        y = frameY.coerceIn(0f, (frameHeight - 1).toFloat())
    }
}
