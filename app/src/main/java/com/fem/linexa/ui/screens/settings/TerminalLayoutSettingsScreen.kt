package com.fem.linexa.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.domain.settings.RecentSessionsLayout
import com.fem.linexa.domain.terminal.TerminalOverviewMetrics
import com.fem.linexa.ui.components.haven.LocalHavenMotions
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * `Settings → Terminal → Recent Sessions`: two large visual choices.
 *
 * The previews are drawn from the same geometry tokens the overview uses
 * ([TerminalOverviewMetrics.CARD_ASPECT_RATIO] and the column count) so the
 * card cannot promise a shape the terminal does not deliver. Tapping a card
 * writes the app-scoped setting immediately; the overview observes it and
 * re-arranges in place, keeping every pty untouched.
 */
@Composable
fun TerminalLayoutSettingsScreen(
    onBack: () -> Unit,
    viewModel: GlobalSettingsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val selected by viewModel.recentSessionsLayout.collectAsState()

    TerminalSettingsPage(
        title = stringResource(R.string.terminal_layout_title),
        subtitle = stringResource(R.string.terminal_layout_subtitle),
        onBack = onBack
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            LayoutChoiceCard(
                layout = RecentSessionsLayout.VERTICAL,
                isSelected = selected == RecentSessionsLayout.VERTICAL,
                onSelect = { viewModel.setRecentSessionsLayout(RecentSessionsLayout.VERTICAL) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            LayoutChoiceCard(
                layout = RecentSessionsLayout.HORIZONTAL,
                isSelected = selected == RecentSessionsLayout.HORIZONTAL,
                onSelect = { viewModel.setRecentSessionsLayout(RecentSessionsLayout.HORIZONTAL) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Text(
            text = stringResource(R.string.terminal_layout_new_session_note),
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 18.dp)
        )
    }
}

@Composable
private fun LayoutChoiceCard(
    layout: RecentSessionsLayout,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    val motions = LocalHavenMotions.current
    val shape = RoundedCornerShape(HavenDim.rCard)
    val border by animateColorAsState(
        targetValue = if (isSelected) h.primary else h.border,
        animationSpec = motions.selection,
        label = "layout-choice-border"
    )
    val title = recentSessionsLayoutTitle(layout)
    val description = recentSessionsLayoutDescription(layout)

    Column(
        modifier = modifier
            .clip(shape)
            .background(h.surface)
            .border(width = if (isSelected) 2.dp else 1.dp, color = border, shape = shape)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = stringResource(R.string.terminal_layout_selected, title),
                onClick = onSelect
            )
            .semantics { this.selected = isSelected }
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(h.bg)
                .padding(10.dp)
        ) {
            when (layout) {
                RecentSessionsLayout.VERTICAL -> VerticalLayoutPreview()
                RecentSessionsLayout.HORIZONTAL -> HorizontalLayoutPreview()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = HavenType.rowTitle,
                color = if (isSelected) h.primary else h.text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.ic_haven_check),
                    contentDescription = null,
                    tint = h.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = description,
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Vertically: the phone grid from [TerminalOverviewMetrics]. Cards are the
 * shared portrait ratio and use real width-derived sizing, so the bottom row
 * clipping at the preview edge reads exactly like "scroll for more".
 */
@Composable
private fun VerticalLayoutPreview() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniSessionCard(
                active = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
            MiniSessionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniSessionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
            MiniSessionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
        }
    }
}

/** Horizontally: one large card with the next page peeking, plus page dots. */
@Composable
private fun HorizontalLayoutPreview() {
    val h = HavenTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniSessionCard(
                active = true,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
            MiniSessionCard(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(TerminalOverviewMetrics.CARD_ASPECT_RATIO)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == 0) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(if (index == 0) h.primary else h.text.copy(alpha = 0.22f))
                )
            }
        }
    }
}

/** A miniature terminal window — chrome, prompt and two output lines. */
@Composable
private fun MiniSessionCard(
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val h = HavenTheme.colors
    val shape = RoundedCornerShape(7.dp)
    BoxWithConstraints(modifier = modifier) {
        // The tiny card paints its own terminal surface, matching the real
        // overview cards rather than the Settings page behind them.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(h.term)
                .border(
                    width = 1.dp,
                    color = if (active) h.primary else h.termText.copy(alpha = 0.16f),
                    shape = shape
                )
                .padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (active) h.primary else h.termDim)
                )
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .weight(0.72f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(h.termText.copy(alpha = 0.30f))
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .fillMaxWidth(0.62f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(h.termPrompt.copy(alpha = 0.85f))
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .height(2.dp)
                    .fillMaxWidth(0.84f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(h.termDim.copy(alpha = 0.55f))
            )
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .height(2.dp)
                    .fillMaxWidth(0.48f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(h.termDim.copy(alpha = 0.40f))
            )
        }
    }
}
