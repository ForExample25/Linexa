package com.fem.linexa.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.LinexaColors
import com.fem.linexa.ui.theme.Spacing

/**
 * One command card in the workspace Commands list (UI/UX Phase 5C).
 *
 * The plan's four content slots map to parameters here — [iconRes], [title],
 * [description] and [distribution] — so a card renders exactly what the image's
 * metadata supplied and nothing else.
 *
 * @param distribution the distribution the image declared, e.g.
 *   `Debian GNU/Linux 13 (trixie)`, or null when it declared none. Null renders
 *   **no line at all** rather than a placeholder: the plan asks for
 *   "distribution information *if present in metadata*", and a card that filled
 *   the gap with the workspace's probed distro would present a detected guess as
 *   the image author's declaration.
 */
@Composable
fun ShortcutCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    distribution: String? = null,
    /** Replaces the chevron (e.g. a progress indicator or status icon). */
    trailing: (@Composable () -> Unit)? = null,
    /** Optional supporting content rendered under the description. */
    supporting: (@Composable () -> Unit)? = null
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.12f else 0.06f),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (distribution != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    // Deliberately the quietest text on the card: it is
                    // provenance, not the command's purpose, and it repeats on
                    // every card in the list.
                    Text(
                        text = distribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = LinexaColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                supporting?.invoke()
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = LinexaColors.TextMuted
                )
            }
        }
    }
}
