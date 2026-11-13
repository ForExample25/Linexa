package com.fem.linexa.ui.components.haven

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * HAVEN collection motion — item ENTER / LEAVE for lazy lists and grids.
 *
 * ## Why this exists
 * The project is pinned to Compose BOM 2024.06.00 (foundation 1.6.x). In that
 * version `Modifier.animateItemPlacement()` animates only the POSITION of
 * surviving items; a removed item is dropped from the lazy layout on the very
 * next frame, so it can never fade out, and a new item has no entrance. The
 * newer `animateItem()` (fade in/out) arrives in foundation 1.7, and the
 * project must not be upgraded just to get it.
 *
 * ## How it works (no lazy-layout hacks)
 * [rememberMotionItems] diffs the caller's list against the previous one by
 * KEY and returns a merged list in which:
 *  - surviving items are [ItemPhase.PRESENT],
 *  - items whose key is new (after the first submit) are [ItemPhase.ENTERING],
 *  - items whose key vanished are RETAINED for exactly the shared exit
 *    duration as [ItemPhase.LEAVING], at their previous index.
 * The list is still an ordinary keyed `items`/`itemsIndexed` source, so lazy
 * recycling, scrolling and identity are untouched. Once the exit has played
 * the leaving entry is dropped and `animateItemPlacement` glides the
 * neighbours into the gap. [Modifier.motionItem] draws the alpha/scale/offset
 * in the graphics layer only (no layout invalidation) and swallows input on a
 * leaving item.
 *
 * ## Rules baked in
 *  - First submit is never animated (returning to a screen must not replay
 *    entrances). Items arriving later — async load into an empty list, paging,
 *    search, add — enter.
 *  - A change of [scope] (e.g. the Files directory) resets the diff: the whole
 *    list is swapped without any enter/leave, because that is navigation, not
 *    a collection edit.
 *  - Durations, easing and stagger come from [LocalHavenMotions]; at animator
 *    scale 0 nothing is retained and every item is simply PRESENT.
 *  - Stagger is bounded by `HavenMotion.STAGGER_MAX_ITEMS` (see Motion.kt).
 *  - Not for live telemetry lists that re-sort every second (processes,
 *    activity): placement animation there would never settle.
 */
enum class ItemPhase { ENTERING, PRESENT, LEAVING }

/** One entry of the merged list. [key] is the caller's stable identity. */
@Immutable
data class MotionItem<T>(
    val key: Any,
    val value: T,
    val phase: ItemPhase,
    /** Position among this update's entering items (drives the bounded stagger). */
    val enterOrdinal: Int
)

internal class LeavingEntry<T>(val value: T, val anchor: Int, val sinceNanos: Long)

internal class MotionListHolder<T> {
    var scope: Any? = Unit
    var started = false
    var prev: List<T> = emptyList()
    val prevKeys = HashSet<Any>()
    val leaving = LinkedHashMap<Any, LeavingEntry<T>>()

    val hasLeaving: Boolean get() = leaving.isNotEmpty()

    /** Drops expired leaving entries. Returns true when anything was removed. */
    fun sweep(nowNanos: Long, exitMs: Int): Boolean {
        val limit = exitMs.toLong() * 1_000_000L
        val it = leaving.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            if (nowNanos - it.next().value.sinceNanos >= limit) {
                it.remove()
                removed = true
            }
        }
        return removed
    }

    fun update(
        items: List<T>,
        scope: Any?,
        key: (T) -> Any,
        retain: Boolean,
        nowNanos: Long
    ): List<MotionItem<T>> {
        val newKeys = HashSet<Any>(items.size * 2)
        items.forEach { newKeys.add(key(it)) }

        val reset = !started || scope != this.scope
        val animate = started && !reset

        // A key that comes back while it is still leaving simply stays alive.
        newKeys.forEach { leaving.remove(it) }

        if (reset) {
            leaving.clear()
        } else if (retain) {
            prev.forEachIndexed { index, old ->
                val k = key(old)
                if (k !in newKeys && k !in leaving) {
                    leaving[k] = LeavingEntry(old, index, nowNanos)
                }
            }
        } else {
            leaving.clear()
        }

        val out = ArrayList<MotionItem<T>>(items.size + leaving.size)
        var ordinal = 0
        for (item in items) {
            val k = key(item)
            val entering = animate && k !in prevKeys
            out.add(
                MotionItem(
                    key = k,
                    value = item,
                    phase = if (entering) ItemPhase.ENTERING else ItemPhase.PRESENT,
                    enterOrdinal = if (entering) ordinal++ else 0
                )
            )
        }
        if (leaving.isNotEmpty()) {
            leaving.entries
                .sortedBy { it.value.anchor }
                .forEach { (k, e) ->
                    out.add(
                        e.anchor.coerceAtMost(out.size),
                        MotionItem(k, e.value, ItemPhase.LEAVING, 0)
                    )
                }
        }

        this.scope = scope
        started = true
        prev = items
        prevKeys.clear()
        prevKeys.addAll(newKeys)
        return out
    }
}

/**
 * Returns [items] merged with the entries that are still playing their exit.
 * Feed the result to `items`/`itemsIndexed(key = { it.key })`.
 *
 * @param retainLeaving `false` for lists whose content is REPLACED wholesale
 *   (server search results): entering items still animate, but the old rows
 *   are not kept around, which would briefly double the list.
 */
@Composable
fun <T> rememberMotionItems(
    items: List<T>,
    scope: Any? = null,
    retainLeaving: Boolean = true,
    key: (T) -> Any
): List<MotionItem<T>> {
    val motions = LocalHavenMotions.current
    val holder = remember { MotionListHolder<T>() }
    var tick by remember { mutableIntStateOf(0) }
    val retain = retainLeaving && motions.animationsEnabled && motions.listExitMs > 0
    val merged = remember(items, scope, tick, retain) {
        holder.update(items, scope, key, retain, System.nanoTime())
    }
    if (holder.hasLeaving) {
        LaunchedEffect(merged) {
            delay(motions.listExitMs.toLong() + 32L)
            if (holder.sweep(System.nanoTime(), motions.listExitMs)) tick++
        }
    }
    return merged
}

/**
 * Entrance-only variant for lists whose source must stay a plain keyed
 * `itemsIndexed(source…)`: returns the keys that are entering in this update.
 * Pair with [Modifier.motionItem] (`phase = ENTERING` for keys in the set).
 */
@Composable
fun <T> rememberEnteringKeys(
    items: List<T>,
    scope: Any? = null,
    key: (T) -> Any
): Map<Any, Int> {
    val merged = rememberMotionItems(items, scope, retainLeaving = false, key = key)
    return remember(merged) {
        val map = HashMap<Any, Int>()
        merged.forEach { if (it.phase == ItemPhase.ENTERING) map[it.key] = it.enterOrdinal }
        map
    }
}

/**
 * The shared item lifecycle modifier. Alpha always; [scaleFrom] / [liftFrom]
 * add the subtle grid-tile entrance (pass `1f` / `0.dp` for hairline rows that
 * should only fade). Everything is applied inside `graphicsLayer {}` so the
 * animation never invalidates layout or measure.
 *
 * The enter decision is captured ONCE per item composition: recomposition,
 * scrolling an item back into view or returning to the screen never replays
 * an entrance, and an item that comes back while leaving animates from its
 * current value instead of restarting.
 */
@Composable
fun Modifier.motionItem(
    phase: ItemPhase,
    enterOrdinal: Int = 0,
    scaleFrom: Float = 0.94f,
    liftFrom: Dp = 8.dp
): Modifier {
    val motions = LocalHavenMotions.current
    val enteredWithAnimation = remember { phase == ItemPhase.ENTERING }
    val progress = remember { Animatable(if (enteredWithAnimation) 0f else 1f) }
    val leaving = phase == ItemPhase.LEAVING
    LaunchedEffect(leaving) {
        if (leaving) {
            progress.animateTo(0f, motions.listExitSpec<Float>())
        } else if (progress.value < 1f) {
            val spec = if (enteredWithAnimation && progress.value == 0f) {
                motions.staggerSpec<Float>(enterOrdinal)
            } else {
                motions.listEnterSpec<Float>()
            }
            progress.animateTo(1f, spec)
        }
    }
    // Always applied: the lambda is read in the draw phase only, so a settled
    // item costs one cheap layer and nothing recomposes per animation frame.
    val base = this.graphicsLayer {
        val p = progress.value
        alpha = p
        val s = scaleFrom + (1f - scaleFrom) * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * liftFrom.toPx()
    }
    return if (leaving) {
        base
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .clearAndSetSemantics { }
    } else {
        base
    }
}
