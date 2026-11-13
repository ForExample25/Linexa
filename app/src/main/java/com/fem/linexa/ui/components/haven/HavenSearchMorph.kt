package com.fem.linexa.ui.components.haven

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlin.math.roundToInt

/**
 * HAVEN search morph — a search ICON that becomes the search FIELD in place.
 *
 * Idle: the toolbar shows [idleContent] with a single search circle docked at
 * its end. Active: the circle's width grows leftwards across the toolbar
 * (the idle controls fade out underneath it), its magnifier cross-fades into a
 * back arrow, the text field fades in, takes focus and raises the keyboard.
 * Closing runs the same motion backwards and restores the toolbar.
 *
 * ## Why it does not jump or recompose per frame
 *  - One progress float on the shared segment token ([LocalHavenMotions]).
 *    The pill's WIDTH is resolved inside a `layout {}` lambda and the idle
 *    fade inside `graphicsLayer {}`, so the animation re-measures one node and
 *    never recomposes the bar or the content under it.
 *  - Rapid taps simply retarget the running animation; animator scale 0 makes
 *    the tween instant (same switch, no separate path).
 *  - The text field is composed only while active or animating, so an idle
 *    toolbar carries no input node.
 *
 * ## State ownership
 * [active] and [query] are hoisted. Closing calls [onActiveChange]`(false)`
 * AND [onQueryChange]`("")` — the existing launcher/files behavior of "no
 * query = unfiltered list" — and clears focus + hides the keyboard. System
 * Back closes the search first ([BackHandler] is enabled only while active),
 * so it never pops the screen underneath.
 */
@Composable
fun HSearchMorphBar(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Bar height; match the idle controls (e.g. HavenDim.btnH) so nothing is squeezed. */
    barHeight: androidx.compose.ui.unit.Dp = HavenDim.searchH,
    idleContent: @Composable () -> Unit
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val barH = barHeight
    val progress = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = motions.segmentSpec(),
        label = "search-morph"
    )
    val showIdle by remember { derivedStateOf { progress.value < 0.99f } }
    val showField by remember { derivedStateOf { progress.value > 0.01f } }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val close = {
        onQueryChange("")
        onActiveChange(false)
    }
    BackHandler(enabled = active) { close() }

    LaunchedEffect(active) {
        if (active) {
            // One frame so the field exists and has been placed before focus.
            withFrameNanos { }
            try {
                focusRequester.requestFocus()
                keyboard?.show()
            } catch (_: IllegalStateException) {
                // Field not attached (rapid toggle) — the next activation retries.
            }
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val openLabel = stringResource(R.string.haven_search_open)
    val closeLabel = stringResource(R.string.haven_search_close)
    val clearLabel = stringResource(R.string.haven_search_clear)

    Box(modifier = modifier.fillMaxWidth().height(barH)) {
        if (showIdle) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(end = barH + 10.dp)
                    .graphicsLayer { alpha = 1f - progress.value }
            ) {
                idleContent()
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .layout { measurable, constraints ->
                    val barPx = barH.roundToPx()
                    val maxW = constraints.maxWidth
                    val w = (barPx + (maxW - barPx) * progress.value).roundToInt().coerceIn(barPx, maxW)
                    val placeable = measurable.measure(Constraints.fixed(w, barPx))
                    layout(w, barPx) { placeable.placeRelative(0, 0) }
                }
                .clip(RoundedCornerShape(HavenDim.rField))
                .background(h.surface2)
                .then(
                    if (!active) {
                        Modifier
                            .semantics { contentDescription = openLabel }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.foundation.LocalIndication.current,
                                role = Role.Button
                            ) { onActiveChange(true) }
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading control: magnifier ⇄ back arrow, cross-faded in place.
            Crossfade(
                targetState = active,
                animationSpec = motions.selectionSpec(),
                label = "search-leading"
            ) { isActive ->
                Box(
                    modifier = Modifier
                        .size(barH)
                        .then(
                            if (isActive) {
                                Modifier
                                    .semantics { contentDescription = closeLabel }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button
                                    ) { close() }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isActive) R.drawable.ic_haven_arrow_back else R.drawable.ic_haven_search
                        ),
                        contentDescription = null,
                        tint = if (isActive) h.text2 else h.muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (showField) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = ((progress.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = HavenType.body.copy(color = h.text),
                        cursorBrush = SolidColor(h.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty() && placeholder.isNotEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = HavenType.body,
                                        color = h.muted,
                                        maxLines = 1
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                // Clear-query action: only while there is something to clear.
                Crossfade(
                    targetState = active && query.isNotEmpty(),
                    animationSpec = motions.selectionSpec(),
                    label = "search-clear"
                ) { canClear ->
                    Box(
                        modifier = Modifier
                            .size(barH)
                            .then(
                                if (canClear) {
                                    Modifier
                                        .semantics { contentDescription = clearLabel }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button
                                        ) { onQueryChange("") }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (canClear) {
                            Icon(
                                painter = painterResource(R.drawable.ic_haven_close),
                                contentDescription = null,
                                tint = h.muted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
