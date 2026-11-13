package com.fem.linexa.navigation

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * The frozen top-level tab crossfade.
 *
 * This duration is **not** part of the Haven motion vocabulary on purpose.
 * It is a measured regression fix, not a design token: the three top-level
 * destinations used to fall back to Navigation-Compose's defaults (90ms
 * outgoing fade + a 90ms enter delay + a 220ms incoming fade), which produced
 * a visible background-coloured blink and kept both full destination trees
 * composed for ~310ms on every tab switch. A simultaneous 90ms crossfade
 * removed the gap and cut the double-composition window roughly threefold.
 *
 * `Motion.kt` deliberately does not own this value: wiring it to the shared
 * `ENTER_MS`/`FADE_MS` tokens would silently change established navigation
 * behaviour. The regression guard `TabTransitionGuardTest` pins both the
 * value and the fact that `NavGraph` routes both shared tab lambdas (enter and
 * exit, including their pop counterparts) through [spec]. Pushed destinations
 * keep their own motion (`HavenMotions.push`).
 */
internal object TabTransition {

    /** The documented, measured tab crossfade duration. Do not "tune" casually. */
    const val TAB_FADE_MS = 90

    /**
     * The one spec both the enter and exit lambdas use. Returning the spec
     * (rather than an `EnterTransition`) keeps the duration behaviourally
     * testable without mounting a Compose UI harness: `spec().durationMillis`
     * is a public property of `TweenSpec`.
     */
    fun spec(): TweenSpec<Float> = tween(durationMillis = TAB_FADE_MS)
}
