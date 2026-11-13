package com.fem.linexa.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * Shared chrome for the two pushed terminal-preference destinations.
 *
 * They are Settings drill-downs, not top-level tabs, so they use the pushed
 * header established by About / Devices & System Information (back circle +
 * title + one explanatory line) instead of a bottom bar. The content column is
 * the same `HavenDim.screenH` gutter as the Settings list, so a row opened from
 * Settings does not visibly shift sideways on the way in.
 */
@Composable
internal fun TerminalSettingsPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val h = HavenTheme.colors
    HavenScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = HavenDim.screenH - 4.dp,
                        end = HavenDim.screenH,
                        top = 4.dp,
                        bottom = 6.dp
                    ),
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                HIconCircle(
                    iconRes = R.drawable.ic_haven_arrow_back,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onBack
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, top = 2.dp)
                ) {
                    Text(
                        text = title,
                        style = HavenType.h2.copy(fontSize = 20.sp),
                        color = h.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = HavenType.desc,
                        color = h.text2,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HavenDim.screenH)
                    .padding(top = 8.dp, bottom = 36.dp),
                content = content
            )
        }
    }
}
