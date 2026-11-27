package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * HAVEN controls — buttons, icon buttons, toggles, segmented pills, chips
 * rows, stepper, fields (concept `.btn/.hbtn/.ibtn/.tog/.segwrap/.chip/
 * .stepper/.field/.search`).
 */

/** `.btn` — 48px (`.sm` 40px) rounded-14 button. */
enum class HavenButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }
enum class HavenButtonSize { DEFAULT, SMALL }

@Composable
fun HButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HavenButtonVariant = HavenButtonVariant.PRIMARY,
    size: HavenButtonSize = HavenButtonSize.DEFAULT,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    block: Boolean = false
) {
    val h = HavenTheme.colors
    val (bg, fg) = when (variant) {
        HavenButtonVariant.PRIMARY -> h.primary to h.onPrimary
        HavenButtonVariant.SECONDARY -> h.surface2 to h.text
        HavenButtonVariant.GHOST -> Color.Transparent to h.text2
        HavenButtonVariant.DANGER -> h.redTint to h.red
    }
    val height = if (size == HavenButtonSize.DEFAULT) HavenDim.btnH else HavenDim.btnSmH
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .havenPressScale(interaction, enabled)
            // The height is a minimum, not a cap: a longer Persian label wraps
            // and the button grows while keeping the minimum touch target.
            .heightIn(min = height)
            .clip(RoundedCornerShape(if (size == HavenButtonSize.DEFAULT) HavenDim.rBtn else 12.dp))
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (size == HavenButtonSize.DEFAULT) 22.dp else 16.dp,
                vertical = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) icon()
        Text(
            text = label,
            style = if (size == HavenButtonSize.DEFAULT) {
                HavenType.body.copy(fontWeight = FontWeight.SemiBold)
            } else {
                HavenType.desc.copy(fontWeight = FontWeight.SemiBold, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
            },
            color = if (enabled) fg else fg.copy(alpha = 0.45f),
            textAlign = TextAlign.Center
        )
    }
}

/** `.hbtn` — the 40px circular header action. */
@Composable
fun HIconCircle(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    size: Int = 40
) {
    val h = HavenTheme.colors
    val bg = if (accent) h.tint else h.surface
    val fg = if (accent) h.primary else h.text2
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .havenPressScale(interaction, enabled)
            .size(size.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) fg else fg.copy(alpha = 0.4f),
            modifier = Modifier.size((size * 0.5f).dp)
        )
    }
}

/** `.ibtn` — the 34px (sm 28px) quiet icon button.
 *
 *  The regular variant's hit box is the project's 40dp compact touch target
 *  (HavenDim.ibtnHit) while its visual footprint stays 34dp — the button is
 *  transparent, so only the tappable area grows, never the row it sits in.
 *  The `small` variant keeps its own geometry: it lives inside dense pinned
 *  controls (the stepper pill, HavenDim.stepBtn) where a 40dp box would
 *  inflate the parent. */
@Composable
fun HIconGhost(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    small: Boolean = false,
    enabled: Boolean = true
) {
    val h = HavenTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .havenPressScale(interaction, enabled)
            .size(if (small) HavenDim.ibtnSm else HavenDim.ibtnHit)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint ?: h.muted,
            modifier = Modifier.size(if (small) 16.dp else 20.dp)
        )
    }
}

/** `.tog` — the 46×28 toggle. */
@Composable
fun HToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) h.primary else h.surface2,
        animationSpec = motions.selection,
        label = "tog-bg"
    )
    val knobX by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = motions.selectionSpec(),
        label = "tog-knob"
    )
    val knobTint = if (checked) h.onPrimary else Color.White
    Box(
        modifier = modifier
            .size(width = HavenDim.toggleW, height = HavenDim.toggleH)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp)
                .offset(x = knobX)
                .size(22.dp)
                .clip(CircleShape)
                .background(knobTint)
        )
    }
}

/** `.segwrap` — the pill segmented control.
 *
 *  The selected pill is one shared surface that SLIDES between options — the
 *  same indicator language as [HBottomNav] — instead of a per-option colour
 *  that fades in and out. Each cell keeps the design minimum height and a
 *  longer translation wraps to two lines; the row takes the tallest cell, so
 *  the indicator still spans the full control without clipping a label.
 */
@Composable
fun HSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mini: Boolean = false
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val selectedIndex by androidx.compose.animation.core.animateFloatAsState(
        targetValue = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)).toFloat(),
        animationSpec = motions.segmentSpec(),
        label = "seg-indicator"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The row takes the tallest label's height. Each cell keeps the
            // familiar minimum pill height; a longer translation wraps to two
            // lines and the control grows instead of clipping. No fixed row
            // height is imposed, so intrinsic measurement can never undercount
            // a wrapped Persian label.
            .clip(RoundedCornerShape(50))
            .background(h.surface2)
            .padding(4.dp)
            .segSelectionPill(options.size, selectedIndex, h.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isOn = index == selected
            val fg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isOn) h.text else h.text2,
                animationSpec = motions.selection,
                label = "seg-fg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (mini) HavenDim.segMiniH else HavenDim.segH)
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) }
                    .semantics { this.selected = isOn },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = if (mini) HavenType.desc.copy(fontWeight = FontWeight.SemiBold) else HavenType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = fg,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Draws the sliding segmented-control pill behind the labels. Draw-phase only:
 * the animated index is read inside [drawBehind], so sliding never re-composes
 * or re-measures the control.
 */
private fun Modifier.segSelectionPill(
    count: Int,
    index: Float,
    color: Color
): Modifier = this.drawBehind {
    if (count <= 0) return@drawBehind
    val cell = size.width / count
    val radius = size.height / 2f
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(index * cell, 0f),
        size = androidx.compose.ui.geometry.Size(cell, size.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    )
}

/** Horizontal `.chips` row with single-select convenience. */
@Composable
fun HChipRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** `.stepper` — the mono −/value/+ control. */
@Composable
fun HStepper(
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    canIncrement: Boolean = true,
    canDecrement: Boolean = true,
    valueLabel: String = value.toString()
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(h.surface2)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HIconGhost(
            iconRes = com.fem.linexa.R.drawable.ic_haven_minus,
            contentDescription = null,
            onClick = onDecrement,
            enabled = canDecrement,
            small = true,
            tint = if (canDecrement) h.text2 else h.text2.copy(alpha = 0.4f)
        )
        Text(
            text = valueLabel,
            style = HavenType.body.copy(
                fontFamily = HavenType.mono,
                fontWeight = FontWeight.SemiBold
            ),
            color = h.text,
            modifier = Modifier
                .width(62.dp)
                .padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        HIconGhost(
            iconRes = com.fem.linexa.R.drawable.ic_custom_add,
            contentDescription = null,
            onClick = onIncrement,
            enabled = canIncrement,
            small = true,
            tint = if (canIncrement) h.text2 else h.text2.copy(alpha = 0.4f)
        )
    }
}

/** `.field` — the 50px rounded-14 input. */
@Composable
fun HField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val h = HavenTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = (if (mono) HavenType.mono12 else HavenType.body).copy(color = h.text),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(h.primary),
        modifier = modifier
            .fillMaxWidth()
            .height(HavenDim.fieldH)
            .clip(RoundedCornerShape(HavenDim.rField))
            .background(h.surface2)
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = if (mono) HavenType.mono12 else HavenType.body,
                        color = h.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

/** `.search` — the field with a leading magnifier. */
@Composable
fun HSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    val h = HavenTheme.colors
    Box(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = HavenType.body.copy(color = h.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(h.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(HavenDim.searchH)
                .clip(RoundedCornerShape(HavenDim.rField))
                .background(h.surface2)
                .padding(start = 44.dp, end = 16.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
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
        Icon(
            painter = painterResource(com.fem.linexa.R.drawable.ic_haven_search),
            contentDescription = null,
            tint = h.muted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 15.dp)
                .size(18.dp)
        )
    }
}
