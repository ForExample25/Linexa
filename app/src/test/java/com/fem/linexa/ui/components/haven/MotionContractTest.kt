package com.fem.linexa.ui.components.haven

import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Behavioural contract for the motion tokens.
 *
 * These assert the *math* the design document fixes, not an implementation
 * string: the bounded stagger must stop growing at six items, a tile's own
 * reveal must fit inside one list-item entrance, and animator scale 0 must
 * collapse every duration to zero. `HavenMotions` is a plain data class, so all
 * of this is verifiable on the JVM without a Compose UI harness.
 */
class MotionContractTest {

    @Test
    fun staggerDelayStopsGrowingAtTheDocumentedCap() {
        val motions = motionSet()
        assertEquals("first tile starts immediately", 0, motions.staggerDelayMs(0))
        assertEquals("one 20 ms step per index", 20, motions.staggerDelayMs(1))
        assertEquals(
            "the final capped tile is the last distinct delay",
            HavenMotion.STAGGER_MS * (HavenMotion.STAGGER_MAX_ITEMS - 1),
            motions.staggerDelayMs(HavenMotion.STAGGER_MAX_ITEMS - 1)
        )
        assertEquals(
            "past the cap every tile shares the final step, so the window cannot grow with list size",
            motions.staggerDelayMs(HavenMotion.STAGGER_MAX_ITEMS - 1),
            motions.staggerDelayMs(HavenMotion.STAGGER_MAX_ITEMS)
        )
        assertEquals(
            "a very long list must not extend the entrance",
            motions.staggerDelayMs(1_000),
            motions.staggerDelayMs(HavenMotion.STAGGER_MAX_ITEMS)
        )
    }

    @Test
    fun everyStaggeredTileKeepsItsFullEntranceInsideTheBoundedWindow() {
        val motions = motionSet()
        val first = motions.staggerSpec<Float>(0)
        val last = motions.staggerSpec<Float>(HavenMotion.STAGGER_MAX_ITEMS - 1)
        val beyondCap = motions.staggerSpec<Float>(HavenMotion.STAGGER_MAX_ITEMS + 5)

        assertEquals("the first tile has no delay", 0, first.delay)
        assertEquals("the first tile gets the full list entrance", HavenMotion.LIST_ITEM_ENTER_MS, first.durationMillis)
        assertEquals("the last tile waits for its step", 100, last.delay)
        assertEquals(
            "later tiles keep the same entrance speed, not a shorter one",
            HavenMotion.LIST_ITEM_ENTER_MS,
            last.durationMillis
        )
        assertEquals(
            "past the cap the spec is identical",
            last.delay to last.durationMillis,
            beyondCap.delay to beyondCap.durationMillis
        )
        assertEquals(
            "the bounded window is the last capped delay plus one entrance",
            HavenMotion.LIST_ITEM_ENTER_MS + 100,
            motions.staggerWindowMs()
        )
        (0..(HavenMotion.STAGGER_MAX_ITEMS + 5)).forEach { index ->
            val spec = motions.staggerSpec<Float>(index)
            val expectedDelay =
                minOf(index, HavenMotion.STAGGER_MAX_ITEMS - 1) * HavenMotion.STAGGER_MS
            assertEquals("tile $index delay", expectedDelay, spec.delay)
            assertEquals("tile $index entrance", HavenMotion.LIST_ITEM_ENTER_MS, spec.durationMillis)
        }
    }

    @Test
    fun animatorScaleZeroCollapsesEveryTransition() {
        val reduced = motionSet(scale = 0f)

        assertFalse("scale 0 must report animations disabled", reduced.animationsEnabled)
        assertEquals("stagger steps collapse", 0, reduced.staggerStepMs)
        assertEquals("stagger delays collapse", 0, reduced.staggerDelayMs(7))
        assertEquals("the stagger window collapses to zero", 0, reduced.staggerWindowMs())

        listOf(
            "push" to reduced.push.durationMillis,
            "segment" to reduced.segment.durationMillis,
            "fade" to reduced.fade.durationMillis,
            "selection" to reduced.selection.durationMillis,
            "press" to reduced.press.durationMillis,
            "enter" to reduced.enter.durationMillis,
            "exit" to reduced.exit.durationMillis,
            "listEnter" to reduced.listEnter.durationMillis,
            "listExit" to reduced.listExit.durationMillis,
            "container" to reduced.container.durationMillis,
            "progress" to reduced.progress.durationMillis,
            "enterSpec" to reduced.enterSpec<Float>().durationMillis,
            "exitSpec" to reduced.exitSpec<Float>().durationMillis,
            "selectionSpec" to reduced.selectionSpec<Float>().durationMillis,
            "segmentSpec" to reduced.segmentSpec<Float>().durationMillis,
            "pushSpec" to reduced.pushSpec<Float>().durationMillis,
            "containerSpec" to reduced.containerSpec<Float>().durationMillis,
            "listEnterSpec" to reduced.listEnterSpec<Float>().durationMillis,
            "listExitSpec" to reduced.listExitSpec<Float>().durationMillis,
            "emphasisSpec" to reduced.emphasisSpec<Float>().durationMillis,
            "quickSpec" to reduced.quickSpec<Float>().durationMillis,
            "staggerSpec" to reduced.staggerSpec<Float>(3).durationMillis
        ).forEach { (name, duration) ->
            assertEquals("$name must settle immediately at animator scale 0", 0, duration)
        }
    }

    @Test
    fun fullScaleKeepsTheDocumentedDurations() {
        val motions = motionSet()
        assertTrue("full scale enables motion", motions.animationsEnabled)
        assertEquals("press must feel instant", HavenMotion.PRESS_MS, motions.press.durationMillis)
        assertEquals(HavenMotion.SELECTION_MS, motions.selection.durationMillis)
        assertEquals(HavenMotion.FADE_MS, motions.fade.durationMillis)
        assertEquals(HavenMotion.SEGMENT_CHANGE_MS, motions.segment.durationMillis)
        assertEquals(HavenMotion.NAV_PUSH_MS, motions.push.durationMillis)
        assertEquals(HavenMotion.ENTER_MS, motions.enter.durationMillis)
        assertEquals(HavenMotion.EXIT_MS, motions.exit.durationMillis)
        assertEquals(HavenMotion.LIST_ITEM_ENTER_MS, motions.listEnter.durationMillis)
        assertEquals(HavenMotion.LIST_ITEM_EXIT_MS, motions.listExit.durationMillis)
        assertEquals(HavenMotion.CONTAINER_MS, motions.container.durationMillis)
        assertEquals(
            "a delayed exit keeps the shared exit duration and honours the requested start",
            100,
            motions.exitSpec<Float>(delayMs = 100).delay
        )
        assertEquals(HavenMotion.EXIT_MS, motions.exitSpec<Float>(delayMs = 100).durationMillis)
    }

    @Test
    fun everyTokenStaysInsideItsMaterial3RoleBand() {
        // The bands are the product contract: a token outside its band means
        // the surface it owns will feel out of family with the rest of the app.
        assertTrue("press", HavenMotion.PRESS_MS in 80..120)
        assertTrue("small state / selection", HavenMotion.SELECTION_MS in 150..200)
        assertTrue("tabs / segments", HavenMotion.SEGMENT_CHANGE_MS in 180..240)
        assertTrue("navigation push", HavenMotion.NAV_PUSH_MS in 220..280)
        assertTrue("page", HavenMotion.PAGE_MS in 250..300)
        assertTrue("container transform", HavenMotion.CONTAINER_MS in 250..300)
        assertTrue("overlay enter", HavenMotion.ENTER_MS in 200..280)
        assertTrue("overlay exit", HavenMotion.EXIT_MS in 200..260)
        assertTrue("expand/collapse", HavenMotion.ENTER_MS in 220..280)
        assertTrue("list item enter", HavenMotion.LIST_ITEM_ENTER_MS in 180..220)
        assertTrue("list item exit", HavenMotion.LIST_ITEM_EXIT_MS in 120..220)
        assertTrue("stagger step", HavenMotion.STAGGER_MS in 16..24)
        assertTrue("stagger cap", HavenMotion.STAGGER_MAX_ITEMS in 4..8)
    }

    /**
     * Mirrors `rememberHavenMotions` without touching `Settings` or Android:
     * the same per-token rounding, driven by an explicit scale.
     */
    private fun motionSet(scale: Float = 1f): HavenMotions {
        fun ms(base: Int): Int = (base * scale).roundToInt().coerceAtLeast(0)
        return HavenMotions(
            push = tween(ms(HavenMotion.NAV_PUSH_MS)),
            segment = tween(ms(HavenMotion.SEGMENT_CHANGE_MS)),
            fade = tween(ms(HavenMotion.FADE_MS)),
            selection = tween(ms(HavenMotion.SELECTION_MS)),
            press = tween(ms(HavenMotion.PRESS_MS)),
            enter = tween(ms(HavenMotion.ENTER_MS)),
            exit = tween(ms(HavenMotion.EXIT_MS)),
            listEnter = tween(ms(HavenMotion.LIST_ITEM_ENTER_MS)),
            listExit = tween(ms(HavenMotion.LIST_ITEM_EXIT_MS)),
            container = tween(ms(HavenMotion.CONTAINER_MS)),
            progress = tween(ms(HavenMotion.PROGRESS_MS)),
            animationsEnabled = scale > 0f,
            scale = scale,
            staggerStepMs = ms(HavenMotion.STAGGER_MS)
        )
    }
}
