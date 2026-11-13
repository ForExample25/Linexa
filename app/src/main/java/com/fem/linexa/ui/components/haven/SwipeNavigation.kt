package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which neighbouring destination a horizontal gesture asks for. */
enum class SwipeDirection { LEFT, RIGHT }

/**
 * Pure decision for a completed horizontal drag.
 *
 * Kept separate from the pointer loop so the thresholds are JVM-testable. A
 * gesture is a page swipe only when the horizontal travel clearly dominates
 * the vertical travel (so a diagonal scroll cannot change pages), and either
 * the distance or a short fast flick crosses the threshold.
 */
internal fun resolveSwipeDirection(
    totalX: Float,
    totalY: Float,
    velocityX: Float,
    touchSlop: Float,
    distanceThreshold: Float,
    velocityThreshold: Float
): SwipeDirection? {
    if (abs(totalX) <= abs(totalY) * HORIZONTAL_DOMINANCE) return null
    val longEnough = abs(totalX) >= distanceThreshold
    val fastFlick = abs(velocityX) >= velocityThreshold && abs(totalX) >= touchSlop * 2f
    if (!longEnough && !fastFlick) return null
    return if (totalX < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
}

internal const val HORIZONTAL_DOMINANCE = 1.25f

/**
 * Horizontal swipe detection shared by the primary destinations and every
 * sibling-tab group.
 *
 * Design rules (the conflict contract):
 *  - the gesture belongs to the CHILD first: Compose's Main pass delivers
 *    pointer events child → parent, so a vertical scroller, a horizontal
 *    carousel or a WebView that consumes the drag cancels this detector before
 *    it can navigate;
 *  - vertical movement must not dominate;
 *  - drags that start within the system-gesture edge band are ignored;
 *  - multi-touch (pinch/selection) is ignored;
 *  - the IME being visible disables page swipes, so cursor/selection drags
 *    while typing never change destination;
 *  - a tap never triggers: a minimum distance or flick velocity is required.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun Modifier.swipeNavigation(
    enabled: Boolean = true,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
): Modifier {
    val left by rememberUpdatedState(onSwipeLeft)
    val right by rememberUpdatedState(onSwipeRight)
    // Typing/selection owns horizontal movement while the keyboard is up.
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val effective = enabled && !imeVisible
    return this.then(
        Modifier.pointerInput(effective) {
            if (!effective) return@pointerInput
            detectHorizontalSwipe(
                onLeft = { left() },
                onRight = { right() }
            )
        }
    )
}

/** Primary-destination variant: swipes follow [HavenDestination.entries] order. */
@Composable
fun Modifier.havenTabSwipe(
    current: HavenDestination,
    enabled: Boolean = true,
    onSelectTab: (HavenDestination) -> Unit
): Modifier {
    // The tablet shell uses a persistent sidebar; a content swipe there would
    // compete with the two-pane navigation instead of complementing it.
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840
    return swipeNavigation(
        enabled = enabled && !isTablet,
        onSwipeLeft = {
            HavenDestination.entries.getOrNull(current.ordinal + 1)?.let(onSelectTab)
        },
        onSwipeRight = {
            HavenDestination.entries.getOrNull(current.ordinal - 1)?.let(onSelectTab)
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
private suspend fun PointerInputScope.detectHorizontalSwipe(
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    val edgeGuard = 24.dp.toPx()
    val distanceThreshold = 64.dp.toPx()
    val velocityThreshold = 650.dp.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x < edgeGuard || down.position.x > size.width - edgeGuard) {
            return@awaitEachGesture
        }

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val pointerId = down.id
        var totalX = 0f
        var totalY = 0f
        var horizontal = false
        var cancelled = false
        var multiTouch = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.changes.size > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            // A child scroller/pager/WebView claimed the drag: it stays its
            // gesture, even if the movement looks horizontal from out here.
            if (change.isConsumed) cancelled = true

            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            tracker.addPosition(change.uptimeMillis, change.position)

            if (!horizontal && abs(totalX) > viewConfiguration.touchSlop) {
                horizontal = abs(totalX) > abs(totalY) * HORIZONTAL_DOMINANCE
                if (!horizontal) cancelled = true
            }
            if (horizontal && !cancelled) change.consume()

            if (!change.pressed) break
        }

        if (!horizontal || cancelled || multiTouch) return@awaitEachGesture

        val velocityX = tracker.calculateVelocity().x
        when (
            resolveSwipeDirection(
                totalX = totalX,
                totalY = totalY,
                velocityX = velocityX,
                touchSlop = viewConfiguration.touchSlop,
                distanceThreshold = distanceThreshold,
                velocityThreshold = velocityThreshold
            )
        ) {
            SwipeDirection.LEFT -> onLeft()
            SwipeDirection.RIGHT -> onRight()
            null -> Unit
        }
    }
}
