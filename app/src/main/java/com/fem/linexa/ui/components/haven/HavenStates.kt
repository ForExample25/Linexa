package com.fem.linexa.ui.components.haven

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenType
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * HAVEN states — honest empties, quiet loading, recoverable errors
 * (concept section 09: `.empty/.eicon/.et/.ed`, `.spin`, error+retry).
 *
 * The whole UI uses these three shapes; a screen never hand-rolls a dead end.
 */

/** Empty state: 64px icon tile, one title, one teaching line, one action. */
@Composable
fun HEmpty(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    iconRes: Int = com.fem.linexa.R.drawable.ic_haven_box,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: Color? = null,
    fill: Boolean = true
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (fill) Arrangement.Center else Arrangement.spacedBy(0.dp)
    ) {
        if (fill) Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(h.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint ?: h.muted,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = title,
            style = HavenType.title,
            color = h.text,
            modifier = Modifier.padding(top = 20.dp),
            textAlign = TextAlign.Center
        )
        if (description != null) {
            Text(
                text = description,
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            HButton(
                label = actionLabel,
                onClick = onAction,
                size = HavenButtonSize.SMALL,
                modifier = Modifier.padding(top = 22.dp)
            )
        }
        if (fill) Spacer(modifier = Modifier.weight(1.2f))
    }
}

/** Loading state: spinner + one quiet line. */
@Composable
fun HLoading(
    modifier: Modifier = Modifier,
    label: String? = null,
    fill: Boolean = true
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HSpinner()
        if (label != null) {
            Text(
                text = label,
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Error state: red icon tile, specific message, Retry that keeps you in place. */
@Composable
fun HError(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    fill: Boolean = true
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (fill) Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(h.redTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(com.fem.linexa.R.drawable.ic_custom_warning),
                contentDescription = null,
                tint = h.red,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = title,
            style = HavenType.title,
            color = h.text,
            modifier = Modifier.padding(top = 20.dp),
            textAlign = TextAlign.Center
        )
        if (detail != null) {
            Text(
                text = detail,
                style = HavenType.desc,
                color = h.text2,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center
            )
        }
        if (retryLabel != null && onRetry != null) {
            HButton(
                label = retryLabel,
                onClick = onRetry,
                variant = HavenButtonVariant.SECONDARY,
                size = HavenButtonSize.SMALL,
                modifier = Modifier.padding(top = 22.dp)
            )
        }
        if (fill) Spacer(modifier = Modifier.weight(1.2f))
    }
}
