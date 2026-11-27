package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.domain.settings.TerminalShortcut
import com.fem.linexa.domain.settings.TerminalShortcutModifier
import com.fem.linexa.domain.settings.TerminalToolbarConfig
import com.fem.linexa.domain.settings.TerminalToolbarSettings
import com.fem.linexa.ui.theme.haven.HavenColors
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * HAVEN terminal chrome (concept section 03) — near-chromeless terminal:
 *
 *  - a single 46dp translucent top bar holding the workspace name, the
 *    session dots (T0 · T1 …) and the ⋯ action;
 *  - the extra-keys floating as a translucent pill ABOVE the keyboard —
 *    Esc / Tab / Ctrl / Alt / ↑ ↓ ← → — never disappearing when the soft
 *    keyboard is up;
 *  - the ⋯ sheet carrying Copy / Paste / Font size / New terminal /
 *    Close session.
 *
 * The terminal surface is opaque and always dark (the concept's `--term` is
 * deliberately not overridden by the light theme).
 */

/** One session dot in the terminal bar. */
data class HavenSessionDot(
    val terminalId: String,
    // 2026-08-31 (Phase 6, compact session numbering): the VISIBLE chip label
    // (compact T1..TN over the live list). Defaults to blank so callers that
    // do not derive compact labels (single-session management terminal) keep
    // compiling; a blank renders as [terminalId] instead. terminalId remains
    // the identity used for clicks/navigation.
    val displayLabel: String = "",
    val active: Boolean,
    val alive: Boolean
)

/**
 * The 46dp terminal top bar overlay — full-bleed: its translucent `rgba
 * (10,14,15,.86)` extends behind the status bar (the surface no longer pads
 * it away), while the ROW CONTENT sits below the system status inset, exactly
 * like the concept's `fr-term` (a dark statusbar strip over `--term`).
 *
 * 2026-08-29 (UX optimization, Issues 8+9): the bar carries the two mandatory
 * controls — **All Sessions** (☰, opens the session overview; optional for
 * the single-session management terminal) and **Keyboard** (⌨, explicit IME
 * request that works even while a mouse-reporting CLI owns the taps) — plus
 * the horizontally scrollable session-chip strip: with many sessions the
 * strip scrolls (all sessions stay reachable) and the ACTIVE chip is brought
 * into view automatically whenever it changes, including right after a
 * session is created. Chip scrolling is fully independent of the terminal
 * view's own scrolling.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HavenTerminalBar(
    workspaceName: String,
    sessions: List<HavenSessionDot>,
    modifier: Modifier = Modifier,
    onAllSessions: (() -> Unit)? = null,
    onKeyboard: (() -> Unit)? = null,
    onSessionClick: (String) -> Unit = {},
    onMore: () -> Unit = {}
) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 2026-08-30 (Issue 1): the bar is APP chrome with its own theme
            // surface — a solid, theme-controlled tone that stays visible over
            // ANY terminal background (the old translucent `termBar` vanished
            // against the terminal's own default black). Labels use theme text
            // tokens, not terminal-dim tokens.
            .background(h.surface2)
            .statusBarsPadding()
            .height(HavenDim.tbarH)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 2026-08-29 (UX optimization, Issue 8): the top bar carries the two
        // mandatory controls — All Sessions (opens the session overview) and
        // Keyboard (explicit IME request, works even while a mouse-reporting
        // CLI owns the taps). Both are optional params so the single-session
        // management terminal can omit the overview toggle.
        if (onAllSessions != null) {
            HIconGhost(
                iconRes = com.fem.linexa.R.drawable.ic_haven_grid,
                contentDescription = stringResource(R.string.haven_terminal_all_sessions),
                onClick = onAllSessions,
                tint = h.text2
            )
        }
        Text(
            text = workspaceName,
            style = HavenType.descMono.copy(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Medium),
            color = h.text2,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 96.dp)
        )
        // (Issue 9): the chip strip scrolls horizontally so every session
        // stays reachable no matter how many terminals exist. The fixed
        // controls stay outside the scroll range; the ACTIVE chip is scrolled
        // into view whenever it changes (including a newly created last
        // session). This is chip scrolling only — terminal content scrolling
        // is the terminal view's own behavior and is untouched.
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sessions.forEach { session ->
                val bringIntoView = remember { BringIntoViewRequester() }
                if (session.active) {
                    LaunchedEffect(session.terminalId, sessions.size) {
                        bringIntoView.bringIntoView()
                    }
                }
                HavenSessionDotChip(
                    // 2026-08-31 (Phase 6): show the compact derived label;
                    // fall back to the internal id when the caller did not
                    // opt in. Click identity stays on terminalId.
                    label = session.displayLabel.ifBlank { session.terminalId },
                    active = session.active,
                    alive = session.alive,
                    onClick = { onSessionClick(session.terminalId) },
                    modifier = Modifier.bringIntoViewRequester(bringIntoView)
                )
            }
        }
        HIconGhost(
            iconRes = com.fem.linexa.R.drawable.ic_custom_more_vert,
            contentDescription = stringResource(R.string.haven_terminal_menu),
            onClick = onMore,
            tint = h.text2
        )
        if (onKeyboard != null) {
            HIconGhost(
                iconRes = com.fem.linexa.R.drawable.ic_haven_keyboard,
                contentDescription = stringResource(R.string.haven_terminal_keyboard),
                onClick = onKeyboard,
                tint = h.text2
            )
        }
    }
}

/** `.sdot` — the mono session pill; `.on` state in primary. */
@Composable
fun HavenSessionDotChip(
    label: String,
    active: Boolean,
    alive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    // Inactive chip: a theme-derived tint (white 7% was invisible on the
    // light-theme surface).
    val bg = if (active) h.primary.copy(alpha = 0.16f) else h.text2.copy(alpha = 0.10f)
    val fg = if (active) h.primary else h.text2
    Row(
        modifier = modifier
            .height(27.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        HDot(
            color = when {
                active && alive -> h.primary
                alive -> h.muted
                else -> h.red
            },
            size = 6f
        )
        Text(
            text = label,
            style = HavenType.small.copy(
                fontFamily = HavenType.mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * Sticky modifier state shared between the shortcut bar and the terminal's
 * `TerminalViewClient` (`readControlKey` / `readAltKey` / `readShiftKey` /
 * `readFnKey`).
 *
 * The library's own `ExtraKeysView` buttons wrote bytes directly to the pty;
 * the Haven bar instead exposes one-shot sticky modifiers: tapping CTRL arms
 * the flag, the next soft-keyboard letter consumes it — exactly the library's
 * `readControlButton()` contract, without the legacy row.
 *
 * 2026-08-29 (UX optimization, Issue 6): the four flags are Compose state now
 * (they were plain `var`s, so arming a modifier did not recompose and the key
 * could render a stale state). Arming and one-shot consumption both update
 * observable state, so the shortcut bar always shows the REAL arming state.
 * Ctrl/Alt remain mutually exclusive (arming one disarms the other, as
 * before); Shift/Fn are independent toggles so Ctrl+Shift-style arming works.
 */
class HavenTerminalModifiers {
    var control by mutableStateOf(false)
        private set
    var alt by mutableStateOf(false)
        private set
    var shift by mutableStateOf(false)
        private set
    var fn by mutableStateOf(false)
        private set

    fun toggleControl() {
        control = !control
        if (control) alt = false
    }

    fun toggleAlt() {
        alt = !alt
        if (alt) control = false
    }

    fun toggleShift() {
        shift = !shift
    }

    fun toggleFn() {
        fn = !fn
    }

    /** Toggle by domain identity, used by the configurable shortcut bar. */
    fun toggle(modifier: TerminalShortcutModifier) {
        when (modifier) {
            TerminalShortcutModifier.CONTROL -> toggleControl()
            TerminalShortcutModifier.ALT -> toggleAlt()
            TerminalShortcutModifier.SHIFT -> toggleShift()
        }
    }

    /** Is the given sticky modifier currently armed? */
    fun isArmed(modifier: TerminalShortcutModifier): Boolean = when (modifier) {
        TerminalShortcutModifier.CONTROL -> control
        TerminalShortcutModifier.ALT -> alt
        TerminalShortcutModifier.SHIFT -> shift
    }

    fun clear() {
        control = false
        alt = false
        shift = false
        fn = false
    }

    /** One-shot read used by TerminalViewClient.readControlKey(). */
    fun consumeControl(): Boolean {
        val value = control
        control = false
        return value
    }

    /** One-shot read used by TerminalViewClient.readAltKey(). */
    fun consumeAlt(): Boolean {
        val value = alt
        alt = false
        return value
    }

    /** One-shot read used by TerminalViewClient.readShiftKey(). */
    fun consumeShift(): Boolean {
        val value = shift
        shift = false
        return value
    }

    /** One-shot read used by TerminalViewClient.readFnKey(). */
    fun consumeFn(): Boolean {
        val value = fn
        fn = false
        return value
    }
}

/**
 * 10D (Phase 10) — the shortcut-bar key label's armed style, extracted from
 * [HavenExtraKeys]'s inline `if (active)` pair so the armed→accent mapping is
 * asserted behaviourally in [HavenShortcutLabelStyleTest] against both Haven
 * palettes, instead of being pinned as source text.
 *
 * Contract (2026-08-30, Issues 2+3): an ARMED modifier's label takes the theme
 * accent `primary` and goes Bold; an unarmed key takes the shortcut foreground
 * `keyFg` at SemiBold. Only the LABEL changes — never a key background (that
 * half stays pinned in ShortcutBarAppThemeSourceTest).
 */
object HavenShortcutLabel {

    /** Armed keys take the theme's accent; resting keys the shortcut foreground. */
    fun color(h: HavenColors, active: Boolean): Color = if (active) h.primary else h.keyFg

    /** Armed labels go Bold so the arming state stays legible without color alone. */
    fun weight(active: Boolean): FontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
}

/**
 * `.xkeys` — the terminal shortcut bar. 2026-08-29 (UI optimization Phase 1):
 * this is a **layout row, not a floating overlay** — the caller composes it as
 * the LAST child of the terminal column (below the terminal surface, above the
 * soft keyboard), so it can never cover terminal text. The terminal area takes
 * the remaining height, and keyboard appearance/disappearance re-flows the
 * column through the surface's `navigationBars ∪ ime` inset; the bar's own
 * `navigationBarsPadding()` keeps it clear of the gesture strip while the
 * keyboard is closed (Compose inset consumption zeroes it while the IME is up,
 * so the bar sits directly on the keyboard with no gap).
 *
 * 2026-11 Settings pass: the row content is now [config]-driven (preset, row
 * mode and custom ordering) instead of the fixed 12-key list. The SAME
 * composable renders the live bar and the Settings previews, so a preview
 * cannot show a key the terminal cannot dispatch.
 */
@Composable
fun HavenExtraKeys(
    modifiers: HavenTerminalModifiers,
    config: TerminalToolbarConfig = TerminalToolbarSettings.DEFAULT.config,
    interactive: Boolean = true,
    applyNavigationBarsPadding: Boolean = true,
    onModifierTap: (TerminalShortcut) -> Unit = {},
    onKey: (TerminalShortcut) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    // 2026-08-30 (Issue 2 — shortcut bar is APP UI): the bar surface and the
    // key foregrounds come from Haven theme tokens (surface + keyFg), never
    // from terminal colors. The ARMED modifier state changes ONLY the label
    // color — to the theme's accent — with the background untouched: no
    // filled/pill/colored key background in any state.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(h.surface)
            .then(
                if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        config.rows.forEach { row ->
            if (row.isEmpty()) return@forEach
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 4.dp
                val available = (maxWidth - 4.dp).coerceAtLeast(0.dp)
                // Fill the width when the row fits; grow to a comfortable
                // 44dp-ish target and scroll horizontally when it does not.
                // No key is ever squeezed below the touch minimum or clipped
                // without a way to reach it.
                val itemWidth = maxOf(
                    HavenDim.xkMinW,
                    if (row.isEmpty()) 0.dp else (available - gap * (row.size - 1)) / row.size
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { key ->
                        val active = key.modifier?.let { modifiers.isArmed(it) } ?: false
                        val fg = HavenShortcutLabel.color(h, active)
                        Box(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(HavenDim.xkBarH)
                                .clickable(
                                    enabled = interactive,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (key.isModifier) onModifierTap(key) else onKey(key)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key.label,
                                style = HavenType.small.copy(
                                    fontFamily = HavenType.mono,
                                    fontWeight = HavenShortcutLabel.weight(active),
                                    fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                                ),
                                color = fg,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The terminal surface — opaque, always dark, keyboard-aware, **truly
 * fullscreen** (concept `fr-term`: the `--term` color reaches behind the
 * status bar AND the gesture nav bar; the 46dp translucent bar is the only
 * chrome). Replaces the legacy [com.fem.linexa.ui.components.TerminalSurface]
 * with the Haven palette; the three load-bearing rules are unchanged:
 *
 *  - **opaque** — the content underneath stays composed without re-layout;
 *  - **bottom-inset aware** — the surface consumes the navigation-bar ∪ IME
 *    union once, which shrinks its content above the soft keyboard and keeps
 *    the extra-keys row visible without double-padding when the keyboard is up;
 *  - **edge-to-edge paint** — the background is applied before that inset
 *    modifier, so it still reaches behind the status bar and the gesture nav
 *    bar; the bar carries its own `statusBarsPadding()` and the extra-keys row
 *    clears the gesture area, so the terminal reads as a full-bleed page
 *    exactly like the concept frame.
 */
@Composable
fun HavenTerminalSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(h.term)
            // Consume navigation bars and IME as ONE inset. When the keyboard
            // is up the IME band already covers the navigation bar, and the
            // extra-keys row below applies navigationBarsPadding itself; two
            // separate paddings here plus that row would stack twice.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        content = content
    )
}

/**
 * The ⋯ sheet — Copy / Paste / Font size stepper / New terminal / Close
 * session (+ optional extra rows like "All sessions").
 */
@Composable
fun HavenTerminalSheet(
    sessionLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canCopy: Boolean = true,
    canClose: Boolean = true,
    fontDp: Int,
    fontRange: IntRange,
    onFontChange: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onNewTerminal: (() -> Unit)?,
    onCloseSession: (() -> Unit)?,
    extraRows: List<HMenuItem> = emptyList()
) {
    val h = HavenTheme.colors
    HSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.haven_terminal_sheet_title),
        subtitle = sessionLabel,
        modifier = modifier
    ) {
        Column {
            HRow(
                title = stringResource(R.string.haven_terminal_copy),
                description = stringResource(R.string.haven_terminal_copy_desc),
                onClick = if (canCopy) onCopy else null,
                leading = { HRic(com.fem.linexa.R.drawable.ic_haven_copy) }
            )
            HRow(
                title = stringResource(R.string.haven_terminal_paste),
                description = stringResource(R.string.haven_terminal_paste_desc),
                onClick = onPaste,
                leading = { HRic(com.fem.linexa.R.drawable.ic_custom_clipboard) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HavenDim.rowMin)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HRic(com.fem.linexa.R.drawable.ic_haven_keyboard)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.haven_terminal_font_size),
                        style = HavenType.rowTitle,
                        color = h.text
                    )
                    Text(
                        text = stringResource(R.string.haven_terminal_font_size_desc),
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                HStepper(
                    value = fontDp,
                    onIncrement = { onFontChange((fontDp + 2).coerceAtMost(fontRange.last)) },
                    onDecrement = { onFontChange((fontDp - 2).coerceAtLeast(fontRange.first)) },
                    canIncrement = fontDp < fontRange.last,
                    canDecrement = fontDp > fontRange.first,
                    valueLabel = "$fontDp dp"
                )
            }
            if (onNewTerminal != null) {
                HRow(
                    title = stringResource(R.string.haven_terminal_new),
                    onClick = onNewTerminal,
                    leading = { HRic(com.fem.linexa.R.drawable.ic_custom_add, tinted = true) }
                )
            }
            extraRows.forEach { item ->
                HRow(
                    title = item.label,
                    onClick = item.onClick,
                    leading = {
                        HRic(
                            item.iconRes,
                            danger = item.danger
                        )
                    }
                )
            }
            if (onCloseSession != null) {
                HRow(
                    title = stringResource(R.string.haven_terminal_close),
                    onClick = onCloseSession,
                    titleColor = h.red,
                    leading = { HRic(com.fem.linexa.R.drawable.ic_haven_close, danger = true) }
                )
            }
        }
    }
}
