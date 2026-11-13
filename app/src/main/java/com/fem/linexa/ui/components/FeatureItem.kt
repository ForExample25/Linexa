package com.fem.linexa.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.ui.components.haven.HChevron
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HToggle
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.TouchTarget
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * One row of the workspace Features tab — HAVEN edition (concept frame
 * `fr-feat`): a 62px hairline-divided row holding a `.ric` icon tile (38dp
 * surface-2 rounded square), the title/status block, the `.tog` toggle, and
 * — for configurable features — a quiet chevron. The expandable settings
 * panel slides open in place beneath the row, indented to 72dp like the
 * concept's `padding:18px 20px 20px 72px`.
 *
 * §1.109: a feature whose guest-side half can be *in progress* or can *fail*
 * (the `ssh` toggle is the first) reports that through [statusText] /
 * [statusIsError] / [busy]. The switch deliberately stays interactive while
 * [busy] so a user can cancel a long install by flipping it back — the
 * supervisor reconciles to whatever the latest desired state is.
 *
 * UI/UX Phase 6: a feature whose backend takes a parameter also carries a
 * [settingsContent] panel, revealed in place. Which features those are is not
 * decided here — the caller passes a panel only for an id in
 * `FeatureConfig.CONFIGURABLE_FEATURE_IDS`, so this component cannot become a
 * second place that opinion lives. Details that are deliberate:
 *
 * - The panel expands **in place**, never as a dialog or a sub-screen: the
 *   settings are meaningless without the row's own status line (a port is worth
 *   reading next to "Listening on port 8022").
 * - The expand affordance is a DEDICATED secondary row BELOW the toggle line —
 *   label + end-anchored chevron — never a control squeezed next to the switch.
 *   The two intents get two distinct targets: the toggle is the primary
 *   enable/disable action, the secondary row is the configure action, and
 *   neither crowds the other. Tapping the toggle can therefore never expand
 *   anything, and reaching for the switch cannot open a panel (and conflating
 *   them is how a user disables SSH while reaching for its port field).
 * - [summaryText] renders whether or not the row is expanded, so the current
 *   configuration is legible without interacting at all.
 */
@Composable
fun FeatureItem(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    statusIsError: Boolean = false,
    busy: Boolean = false,
    summaryText: String? = null,
    expanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    settingsContent: (@Composable () -> Unit)? = null
) {
    val h = HavenTheme.colors
    val motions = com.fem.linexa.ui.components.haven.LocalHavenMotions.current
    val expandable = onExpandedChange != null && settingsContent != null
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HavenDim.rowMin)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
        ) {
            com.fem.linexa.ui.components.haven.HRic(iconRes = iconRes)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HavenType.rowTitle,
                    color = h.text
                )
                Text(
                    text = description,
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 3.dp)
                )
                // §1.109: the guest-side condition of this feature, when it
                // has one (sshd installing/starting/listening/failed).
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = HavenType.desc,
                        color = if (statusIsError) h.red else h.primary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                // Phase 6: the current configuration, always visible so the
                // panel does not have to be opened to see what is set.
                if (summaryText != null) {
                    Text(
                        text = summaryText,
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            if (busy) {
                HSpinner(small = true)
                Spacer(modifier = Modifier.width(6.dp))
            }

            // The toggle ends the main line — the primary action stands alone,
            // with the configure affordance on its own row below (UI/UX
            // Phase 6; the old tog+chev squeeze is gone).
            HToggle(checked = checked, onCheckedChange = onCheckedChange)
        }

        // Secondary configuration row — only for features with a real panel.
        // Same 72dp start as the expandable panel so the label lines up under
        // the row's text column; the chevron is anchored to the row END
        // (the shared trailing-anchor contract, independent of label length).
        if (expandable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget.compact)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = stringResource(
                            if (expanded) {
                                R.string.feature_settings_collapse
                            } else {
                                R.string.feature_settings_expand
                            }
                        )
                    ) { onExpandedChange?.invoke(!expanded) }
                    .padding(start = 72.dp, end = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.feature_settings_more),
                    style = HavenType.desc,
                    color = if (expanded) h.primary else h.text2,
                    modifier = Modifier.weight(1f)
                )
                HChevron(expanded = expanded)
            }
        }

        AnimatedVisibility(
            visible = expandable && expanded,
            enter = fadeIn(animationSpec = motions.enter) +
                expandVertically(animationSpec = motions.enterSpec()),
            exit = fadeOut(animationSpec = motions.exit) +
                shrinkVertically(animationSpec = motions.exitSpec())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The concept's panel inset: 20dp sides, 72dp start — the
                    // content lines up under the row's text column, not its icon.
                    .padding(start = 72.dp, end = 20.dp, top = 6.dp, bottom = 20.dp)
            ) {
                settingsContent?.invoke()
            }
        }
    }
}
