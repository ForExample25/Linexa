package com.fem.linexa.ui.components.haven

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.fem.linexa.ui.components.TerminalOverlayEasing
import java.lang.Math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HAVEN motion — the one place the app's motion vocabulary lives.
 *
 * The pass that introduced this object replaced three ad-hoc motion systems:
 * the 90ms crossfade the top-level tabs already had (`NavGraph.kt`), the
 * terminal overlay's 240ms `TerminalOverlayEasing` page slide, and nothing at
 * all for the workspace's own segments and sub-destinations. Everything below
 * that is transition-shaped now binds the same durations + the one Haven curve
 * so a tab switch, a segment switch and a push all read as one product.
 *
 * ## What is deliberately NOT here
 *
 *  - Snapshot/loop/emphasize easings — Haven has exactly one non-linear curve
 *    ([TerminalOverlayEasing]) and gains nothing from a second.
 *  - New dependencies — everything composes from `androidx.compose.animation`.
 *
 * ## Why there is a CompositionLocal
 *
 * [rememberHavenMotions] reads `Settings.Global.ANIMATOR_DURATION_SCALE`
 * through a `ContentResolver`. Calling it from every animated component would
 * repeat that resolver read per component per scale change. [LinexaTheme]
 * calls it **once** and publishes the resulting [HavenMotions] through
 * [LocalHavenMotions]; every component reads the same already-scaled set.
 *
 * ## Reduced motion
 *
 * [rememberHavenMotions] multiplies every duration by the system's
 * `ANIMATOR_DURATION_SCALE`. Users who turn animations off (scale 0 — the
 * Android accessibility "remove animations" switch) get instant switches:
 * `tween(0)` settles on the first frame, so no branch of the UI needs a
 * separate "reduced" path and no transition can lag behind a rapid tap.
 * [HavenMotions.animationsEnabled] lets indeterminate decorations (spinners,
 * pulses) render their final, static frame instead of looping forever.
 */
object HavenMotion {
    /** Press-down scale on buttons, rows, cards and tiles. */
    const val PRESS_MS = 90

    /** Sibling swaps inside one screen — the workspace's four segments. */
    const val SEGMENT_CHANGE_MS = 220

    /** Forward/back navigation between destinations (workspace Packages/Files…). */
    const val NAV_PUSH_MS = 240

    /**
     * A full-page container change. The workspace terminal ↔ Recent Terminals
     * transform is a page-sized change, so it sits at the low end of the
     * 250–300 ms page band rather than the shorter navigation band.
     */
    const val PAGE_MS = 260

    /**
     * A shared-element-style container transform whose source and destination
     * are two different layers (terminal window → overview card and back).
     * It is deliberately one frame longer than [NAV_PUSH_MS] so the layer
     * being transformed has settled before the incoming layer finishes.
     */
    const val CONTAINER_MS = 260

    /** The fade of the page that stays put during a push/pull. */
    const val FADE_MS = 160

    /** Selection-state changes on controls (segments, chips, tab bar). */
    const val SELECTION_MS = 180

    /** Content entering a region (bounded entrances/expansions). */
    const val ENTER_MS = 240

    /** Content leaving a region. Exits are deliberately faster than entrances. */
    const val EXIT_MS = 220

    /**
     * List/grid item entrances. Collection edits are a smaller state change
     * than a page entrance, so they sit in the 180–220 ms list band.
     */
    const val LIST_ITEM_ENTER_MS = 200

    /** List/grid item exits. Faster than the entrance, never a frozen row. */
    const val LIST_ITEM_EXIT_MS = 160

    /**
     * Per-item delay in the bounded first-composition stagger. Twenty
     * milliseconds is one M3 stagger step: visible as a cascade, never as
     * items waiting their turn.
     */
    const val STAGGER_MS = 20

    /**
     * Stagger cap. Six items is the largest cascade that still finishes
     * inside one page transition on a phone; longer lists enter as a group
     * at the final step instead of becoming a waterfall.
     */
    const val STAGGER_MAX_ITEMS = 6

    /**
     * Default cadence for indeterminate presence loops. Loop cadences are
     * deliberately **not** scaled by [rememberHavenMotions]: they describe a
     * live state, not a transition, and a 0ms infinite loop would be worse
     * than a calm static frame. The loop sites check
     * [HavenMotions.animationsEnabled] and stop instead.
     */
    const val LOOP_MS = 1200

    /** Spinner rotation preserved from the Haven concept (`@keyframes rot`). */
    const val SPIN_MS = 800

    /** Blob/dot presence wave preserved from the Haven concept. */
    const val PULSE_MS = 1100

    /** Determinate progress-rail width transition (concept `width .3s`). */
    const val PROGRESS_MS = 300

    /** Selection emphasis used by terminal window cards. */
    const val EMPHASIS_MS = 180

    /** Fast hover/press tint on dense window controls. */
    const val QUICK_MS = 120
}

/**
 * The pre-scaled transition specs every animated surface binds. Created once
 * per scale change so rapid navigation reuses identical spec objects.
 *
 * Typed helpers ([enterSpec], [exitSpec], [selectionSpec], …) exist because
 * Compose's animation APIs are generic over the animated value (`Float`,
 * `Color`, `Dp`, `IntSize`, `IntOffset`), while the duration/easing decision is
 * always Haven's. Call sites pass the helper and let Kotlin infer the type.
 */
data class HavenMotions(
    /** Forward/back pushes between destinations (moving page, no fade). */
    val push: TweenSpec<IntOffset>,
    /** Sibling segment swaps (moving pages slide a quarter-width). */
    val segment: TweenSpec<IntOffset>,
    /** The stationary page's fade during a push or a pull. */
    val fade: TweenSpec<Float>,
    /** Selection-state color tweens (segment pills, chips, bottom nav). */
    val selection: TweenSpec<Color>,
    /** Press-down / release scale. */
    val press: TweenSpec<Float>,
    /** Bounded content entrances. */
    val enter: TweenSpec<Float>,
    /** Bounded content exits. */
    val exit: TweenSpec<Float>,
    /** List/grid item entrances (alpha, scale, lift). */
    val listEnter: TweenSpec<Float>,
    /** List/grid item exits. */
    val listExit: TweenSpec<Float>,
    /** Terminal window ↔ Recent Terminals container transform. */
    val container: TweenSpec<Float>,
    /** Determinate progress-rail width. */
    val progress: TweenSpec<Float>,
    /** Whether the system animator scale allows motion at all. */
    val animationsEnabled: Boolean,
    /** The raw animator scale (1f = full motion, 0f = removed). */
    val scale: Float,
    /** Pre-scaled per-item stagger step (0 at scale 0). */
    val staggerStepMs: Int
) {
    /** Scaled entrance duration in milliseconds. */
    val enterMs: Int get() = enter.durationMillis

    /** Scaled exit duration in milliseconds. */
    val exitMs: Int get() = exit.durationMillis

    /** Scaled push duration in milliseconds. */
    val pushMs: Int get() = push.durationMillis

    /** Scaled page/container-transform duration in milliseconds. */
    val containerMs: Int get() = container.durationMillis

    /** Scaled list-item entrance duration in milliseconds. */
    val listEnterMs: Int get() = listEnter.durationMillis

    /** Scaled list-item exit duration in milliseconds. */
    val listExitMs: Int get() = listExit.durationMillis

    /** Scaled press duration in milliseconds. */
    val pressMs: Int get() = press.durationMillis

    /** Generic entrance spec for values Compose types separately (Dp, IntSize…). */
    fun <T> enterSpec(): TweenSpec<T> = tween(enterMs, easing = TerminalOverlayEasing)

    /**
     * Generic exit spec for values Compose types separately. [delayMs] lets a
     * stationary layer start its fade only after a moving page is under way —
     * still the shared [HavenMotion.EXIT_MS] duration, so the whole exit can
     * never outlive the paired push.
     */
    fun <T> exitSpec(delayMs: Int = 0): TweenSpec<T> =
        tween(exitMs, delayMs, easing = TerminalOverlayEasing)

    /** Generic selection spec for values Compose types separately (Dp, IntSize…). */
    fun <T> selectionSpec(): TweenSpec<T> =
        tween(selection.durationMillis, easing = TerminalOverlayEasing)

    /** Generic segment-change spec for values Compose types separately. */
    fun <T> segmentSpec(): TweenSpec<T> =
        tween(segment.durationMillis, easing = TerminalOverlayEasing)

    /** Generic push spec for values Compose types separately (fades, scale). */
    fun <T> pushSpec(): TweenSpec<T> =
        tween(push.durationMillis, easing = TerminalOverlayEasing)

    /** Generic container-transform spec (scale/translation of whole layers). */
    fun <T> containerSpec(): TweenSpec<T> =
        tween(container.durationMillis, easing = TerminalOverlayEasing)

    /** Generic list-item entrance spec for values typed separately. */
    fun <T> listEnterSpec(): TweenSpec<T> =
        tween(listEnter.durationMillis, easing = TerminalOverlayEasing)

    /** Generic list-item exit spec for values typed separately. */
    fun <T> listExitSpec(): TweenSpec<T> =
        tween(listExit.durationMillis, easing = TerminalOverlayEasing)

    /** Scaled 180ms emphasis used by terminal window cards. */
    fun <T> emphasisSpec(): TweenSpec<T> =
        tween(tweenFor(HavenMotion.EMPHASIS_MS), easing = TerminalOverlayEasing)

    /** Scaled 120ms quick tint used by dense window controls. */
    fun <T> quickSpec(): TweenSpec<T> =
        tween(tweenFor(HavenMotion.QUICK_MS), easing = TerminalOverlayEasing)

    /**
     * Index delay for the bounded stagger. Items past [HavenMotion.STAGGER_MAX_ITEMS]
     * all share the final step, so the overall entrance can never grow with
     * list length.
     */
    fun staggerDelayMs(index: Int): Int =
        min(index, HavenMotion.STAGGER_MAX_ITEMS - 1) * staggerStepMs

    /**
     * The whole bounded stagger: the last capped item's delay plus one shared
     * entrance. Delay stops growing at the cap; duration never shrinks, so
     * every tile moves at the same speed.
     */
    fun staggerWindowMs(): Int =
        staggerDelayMs(HavenMotion.STAGGER_MAX_ITEMS - 1) + listEnterMs

    /**
     * One tile's bounded reveal: its own index delay, then the full shared
     * list-item entrance. Past the cap every tile shares the final delay, so
     * the whole cascade can never grow with list length.
     */
    fun <T> staggerSpec(index: Int): TweenSpec<T> =
        tween(
            durationMillis = listEnterMs,
            delayMillis = staggerDelayMs(index),
            easing = TerminalOverlayEasing
        )

    /**
     * A tween scaled for a non-semantic cadence (`EMPHASIS_MS`/`QUICK_MS`).
     * These are interaction durations too, so they still collapse at scale 0.
     */
    private fun tweenFor(baseMs: Int): Int =
        if (animationsEnabled) max(1, (baseMs * scale).roundToInt()) else 0
}

/**
 * The single motion access point. [LinexaTheme] provides this above
 * `MaterialTheme`; components read `LocalHavenMotions.current` instead of
 * calling [rememberHavenMotions] themselves.
 */
val LocalHavenMotions = staticCompositionLocalOf<HavenMotions> {
    error(
        "LocalHavenMotions has no provider. Wrap the composition in LinexaTheme; " +
            "motion must never be constructed ad hoc inside a component."
    )
}

/**
 * Reads the system animator scale once per value change and hands out the
 * Haven durations through it. Scale 0 (accessibility "remove animations")
 * shortens every transition to zero — every tween settles immediately.
 *
 * This is called **once**, by [LinexaTheme]. Components consume
 * [LocalHavenMotions] instead, so a screen with dozens of animated surfaces
 * still performs exactly one `ContentResolver` read per scale change. A
 * [ContentObserver] keeps that single read live: toggling the system setting
 * while the app is running re-derives the whole motion set rather than waiting
 * for a process restart.
 *
 * The `try`/`catch` keeps this honest in harnesses without a real resolver: the
 * fallback is the system's own default of `1f`, never a fast path.
 */
@Composable
fun rememberHavenMotions(): HavenMotions {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readAnimatorScale(resolver)) }

    // MOT-1: re-read exactly when the system setting changes, not per
    // component and never during scrolling. Without this observer a user who
    // toggles "remove animations" would keep the old motion until the process
    // restarts.
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(resolver)
            }
        }
        try {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        } catch (_: Exception) {
            // Same harness fallback as readAnimatorScale: no resolver, no observer.
        }
        onDispose {
            try {
                resolver.unregisterContentObserver(observer)
            } catch (_: Exception) {
                // Already gone (harness shutdown).
            }
        }
    }

    return remember(scale) {
        HavenMotions(
            push = tween(
                durationMillis = roundPositiveMs(HavenMotion.NAV_PUSH_MS, scale),
                easing = TerminalOverlayEasing
            ),
            segment = tween(
                durationMillis = roundPositiveMs(HavenMotion.SEGMENT_CHANGE_MS, scale),
                easing = TerminalOverlayEasing
            ),
            fade = tween(
                durationMillis = roundPositiveMs(HavenMotion.FADE_MS, scale),
                easing = TerminalOverlayEasing
            ),
            selection = tween(
                durationMillis = roundPositiveMs(HavenMotion.SELECTION_MS, scale),
                easing = TerminalOverlayEasing
            ),
            press = tween(
                durationMillis = roundPositiveMs(HavenMotion.PRESS_MS, scale),
                easing = TerminalOverlayEasing
            ),
            enter = tween(
                durationMillis = roundPositiveMs(HavenMotion.ENTER_MS, scale),
                easing = TerminalOverlayEasing
            ),
            exit = tween(
                durationMillis = roundPositiveMs(HavenMotion.EXIT_MS, scale),
                easing = TerminalOverlayEasing
            ),
            listEnter = tween(
                durationMillis = roundPositiveMs(HavenMotion.LIST_ITEM_ENTER_MS, scale),
                easing = TerminalOverlayEasing
            ),
            listExit = tween(
                durationMillis = roundPositiveMs(HavenMotion.LIST_ITEM_EXIT_MS, scale),
                easing = TerminalOverlayEasing
            ),
            container = tween(
                durationMillis = roundPositiveMs(HavenMotion.CONTAINER_MS, scale),
                easing = TerminalOverlayEasing
            ),
            progress = tween(
                durationMillis = roundPositiveMs(HavenMotion.PROGRESS_MS, scale),
                easing = TerminalOverlayEasing
            ),
            animationsEnabled = scale > 0f,
            scale = scale,
            staggerStepMs = roundPositiveMs(HavenMotion.STAGGER_MS, scale)
        )
    }
}

private fun readAnimatorScale(resolver: ContentResolver): Float =
    try {
        Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    } catch (_: Exception) {
        1f
    }.coerceAtLeast(0f)

private fun roundPositiveMs(baseMillis: Int, scale: Float): Int =
    max(0, (baseMillis.toFloat() * scale).roundToInt())
