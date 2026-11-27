package com.fem.linexa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.domain.whatsnew.ChangeCategory
import com.fem.linexa.domain.whatsnew.ReleaseChange
import com.fem.linexa.domain.whatsnew.ReleaseNotes
import com.fem.linexa.ui.screens.settings.AppVersionLabel
import com.fem.linexa.ui.theme.LinexaColors
import com.fem.linexa.ui.theme.Spacing

/**
 * The Home changelog card: what changed in the version the user has just installed.
 *
 * Composed as the first item of Home's workspace list rather than as a floating
 * overlay, so it scrolls away with the content and never covers a workspace. It is
 * shown at most once per version — see
 * [com.fem.linexa.domain.whatsnew.WhatsNewDecision] for the rule and
 * `HomeViewModel.whatsNew` for the wiring.
 *
 * The card is deliberately built from the same primitives as the Home workspace card,
 * which is its only neighbour on that list: a `Card` with a 24dp radius on `colorScheme.surface`
 * at zero elevation, [Spacing] for its insets, and `MaterialTheme.typography` for every
 * text style. A more eye-catching treatment (a tinted container, a border, an
 * elevation) was rejected on purpose — this is transient content sitting directly above
 * the user's workspaces, and the accented header icon is enough to distinguish it
 * without making Home look like a different screen on the one launch it appears.
 *
 * The layout belongs here; what is *present* belongs to [WhatsNewCardContent], which is
 * pure and unit-tested (§1.124(a) — there is no Compose UI harness in this repo).
 */
@Composable
fun WhatsNewCard(
    notes: ReleaseNotes,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Not `remember`ed: `from` is a cheap take/trim over a handful of strings, and the
    // version label it is handed comes from `stringResource`, which must be read in
    // composition so the card re-resolves it after a configuration change.
    val content = WhatsNewCardContent.from(
        notes = notes,
        expanded = expanded,
        versionLabel = stringResource(
            R.string.whats_new_version,
            AppVersionLabel.format(
                versionName = notes.versionName,
                versionCode = notes.versionCode
            )
        )
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.xl)) {

            WhatsNewHeader(
                versionLabel = content.versionLabel,
                onDismiss = onDismiss
            )

            if (content.summary != null) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = content.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // A release can legitimately carry a summary and no itemized changes, in
            // which case this contributes no height at all rather than an empty gap.
            if (content.visibleChanges.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    content.visibleChanges.forEach { change ->
                        ChangeRow(change = change)
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Only for a changelog long enough to have been truncated. Collapsing
                // is offered once expanded, so a fifteen-entry release can be put back
                // without dismissing the card.
                if (content.canExpand) {
                    TextButton(
                        onClick = onToggleExpanded,
                        contentPadding = COMPACT_BUTTON_PADDING
                    ) {
                        Text(
                            text = if (content.isExpanded) {
                                stringResource(R.string.whats_new_show_less)
                            } else {
                                pluralStringResource(
                                    R.plurals.whats_new_show_more,
                                    content.hiddenChangeCount,
                                    content.hiddenChangeCount
                                )
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // The second dismiss affordance, and the reason there are two: the X in
                // the header is the conventional place to close a card and is reachable
                // without reading it, while this is where a thumb lands after actually
                // reading to the end. Both call the same `onDismiss` — this is not a
                // separate "acknowledge" with different semantics.
                TextButton(
                    onClick = onDismiss,
                    contentPadding = COMPACT_BUTTON_PADDING
                ) {
                    Text(
                        text = stringResource(R.string.whats_new_got_it),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Tighter than `ButtonDefaults.TextButtonContentPadding` (24dp horizontal), which on a
 * card this dense pushes both controls away from the text they belong to and makes the
 * row read as a dialog's button bar.
 */
private val COMPACT_BUTTON_PADDING = PaddingValues(
    horizontal = Spacing.md,
    vertical = Spacing.xs
)

/** Icon · "What's New" · version · close. */
@Composable
private fun WhatsNewHeader(
    versionLabel: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_custom_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.whats_new_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Icons.Outlined.Close, not a drawable: the project has no ic_custom_close, and
        // every other close affordance in the app (OperationPanel, the desktop sheet)
        // uses this same Material icon.
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.whats_new_dismiss),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * One changelog line: its category as a small pill, then the text.
 *
 * The category is a pill rather than a colored bullet or a prefix word so that a mixed
 * list scans by kind — the user can find what got fixed without reading every line.
 * Aligned to the top of the row rather than centred, because a change's text wraps to
 * several lines and a vertically-centred pill beside a three-line paragraph reads as
 * belonging to the middle line.
 */
@Composable
private fun ChangeRow(change: ReleaseChange) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        CategoryPill(category = change.category)
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = change.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CategoryPill(category: ChangeCategory) {
    // Exhaustive `when` over the enum, so adding a category is a compile error here
    // rather than an unlabelled pill at runtime. Label and color are resolved together
    // because they describe the same thing. Every color comes from the existing
    // palette — LinexaColors is the app's documented gap-filler for concepts M3 has no
    // slot for, and these four are exactly that.
    val (labelRes, tint) = when (category) {
        ChangeCategory.NEW -> R.string.whats_new_category_new to MaterialTheme.colorScheme.primary
        ChangeCategory.IMPROVED -> R.string.whats_new_category_improved to LinexaColors.Accent
        ChangeCategory.FIXED -> R.string.whats_new_category_fixed to LinexaColors.Disk
        ChangeCategory.CHANGED -> R.string.whats_new_category_changed to LinexaColors.Warning
        ChangeCategory.PERFORMANCE -> R.string.whats_new_category_performance to LinexaColors.Net
    }

    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm, vertical = 3.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
