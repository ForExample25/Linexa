package com.fem.linexa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * True while the host lifecycle is at least STARTED — i.e. the screen is
 * actually on display, not merely composed.
 *
 * Resource polling keyed only on composition kept running while the app sat in
 * the background (the composable is still composed then), reading `/proc` every
 * two seconds for a screen nobody could see. Pollers key on this instead.
 *
 * Binds compose-ui's [LocalLifecycleOwner] (`androidx.compose.ui.platform`), not
 * the identically named local from `androidx.lifecycle.compose` — see the
 * §1.105 crash note in `TerminalKilledNotice`. Do not swap it for
 * `LifecycleResumeEffect`.
 */
@Composable
fun rememberIsForeground(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val foreground = remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground.value = true
                Lifecycle.Event.ON_STOP -> foreground.value = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return foreground
}
