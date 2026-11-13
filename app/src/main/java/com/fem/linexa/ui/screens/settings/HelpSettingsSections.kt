package com.fem.linexa.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fem.linexa.BuildConfig
import com.fem.linexa.R
import com.fem.linexa.ui.theme.LinexaColors

/**
 * UI/UX Phase 4 (2) — Help and the project's own links.
 *
 * Two groups rather than one, because the plan asks two different questions and they
 * have different answers: "how do I use this / where do I report that it broke" is
 * documentation, and "where is the community" is people. Merging them would put a
 * private Telegram chat one row below a bug tracker as if they were interchangeable
 * places to file a defect.
 *
 * Which links exist comes from [HelpLink], not from this composable — see its doc for
 * why the URLs are a typed catalog with tests rather than strings on a row.
 */
@Composable
fun HelpSettingsGroups() {
    val context = LocalContext.current
    val haven = com.fem.linexa.ui.theme.haven.HavenTheme.colors

    com.fem.linexa.ui.components.haven.HSectionHeader(
        title = stringResource(R.string.global_settings_help_group)
    )
    com.fem.linexa.ui.components.haven.HCard(tight = true) {
        LinkRows(HelpLink.of(HelpLink.Kind.HELP) + HelpLink.of(HelpLink.Kind.PROJECT)) { link ->
            openLink(context, link.url)
        }
    }

    com.fem.linexa.ui.components.haven.HSectionHeader(
        title = stringResource(R.string.global_settings_community_group)
    )
    com.fem.linexa.ui.components.haven.HCard(tight = true) {
        LinkRows(HelpLink.of(HelpLink.Kind.COMMUNITY)) { link ->
            openLink(context, link.url)
        }
    }
}

/**
 * The rows of one group.
 *
 * Issue #11 (2026-09-03): the raw URL is NO LONGER rendered under the row
 * title — the requirement is a clean `Help [button] / Community [button]`
 * settings screen, with the destination revealed only when the row is used
 * (it opens the page, as before). The row still reads as a link: icon,
 * chevron, and a short human subtitle naming WHERE each entry leads, from
 * [HelpLink.subtitleRes] — never the URL itself. Functionality is
 * untouched; only the permanently-visible raw address is gone.
 *
 * The last row's divider is suppressed by position rather than by a per-link flag, so
 * reordering [HelpLink] cannot leave a hairline under the final row.
 */
@Composable
private fun LinkRows(links: List<HelpLink>, onOpen: (HelpLink) -> Unit) {
    links.forEachIndexed { index, link ->
        com.fem.linexa.ui.components.haven.HRow(
            title = stringResource(link.titleRes()),
            description = stringResource(link.subtitleRes()),
            descriptionMono = false,
            onClick = { onOpen(link) },
            leading = { com.fem.linexa.ui.components.haven.HRic(link.iconRes()) },
            trailing = if (index != links.lastIndex) {
                null
            } else {
                { com.fem.linexa.ui.components.haven.HChevron() }
            }
        )
        if (index != links.lastIndex) {
            com.fem.linexa.ui.components.haven.HHairline()
        }
    }
}

/**
 * UI/UX Phase 4 (3) — the About footer: the app's icon and its real version.
 *
 * Sits at the very bottom of the scroll, centred and unboxed (no settings group card),
 * because it is an identification of the app rather than a setting — giving it a card
 * would make it look like a row that does something.
 *
 * The version text comes from [AppVersionLabel], which is fed `BuildConfig` at this call
 * site: the value is the build's own, never a hardcoded string that a release bump could
 * leave behind. `versionName` is the only place in the app that states which build the
 * user is running, so it is also the line a bug report will quote — hence the build code
 * alongside it (every build so far is `1.0`).
 */
@Composable
fun AppInfoFooter(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppIcon()
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(
                R.string.global_settings_version,
                AppVersionLabel.format(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE
                )
            ),
            style = MaterialTheme.typography.bodySmall,
            color = LinexaColors.TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The launcher icon, composed from its two layers.
 *
 * **Not** `painterResource(R.mipmap.ic_launcher)`: that resource is an `<adaptive-icon>`,
 * which inflates to an `AdaptiveIconDrawable`. `painterResource` handles vector and
 * bitmap resources only — it routes anything else through `BitmapFactory`, which cannot
 * decode an adaptive-icon XML, so that call throws at runtime rather than failing to
 * compile. Drawing the background colour and the foreground vector directly is the same
 * two layers the launcher composites, with no new dependency (§1.124(b) — the build
 * resolves `--offline`, so an image-loading library is not an option).
 *
 * The foreground vector is inset because an adaptive icon's foreground is designed with
 * ~18dp of bleed on each side of a 108dp canvas; drawing it edge-to-edge would render
 * the glyph noticeably larger than the icon the user taps on their home screen.
 */
@Composable
private fun AppIcon() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(
                color = colorResource(R.color.ic_launcher_background),
                shape = RoundedCornerShape(18.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // `Image`, not `Icon`: `Icon` tints its painter with the current content colour
        // by default, which would flatten the foreground vector's own colours into one.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
    }
}

/**
 * Opens a link in whatever handles it.
 *
 * Wrapped for the same reason as the About screen's source links (§1.130): there is no
 * guarantee a device has an activity for `https` (or for `t.me`), and `startActivity`
 * throws rather than returning. Failing silently is acceptable *here specifically*
 * because the row already displays the URL — the information is not lost, and a toast
 * about a missing browser is not something the user can act on.
 */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No handler. The row still shows the address.
    }
}

/**
 * The row SUBTITLE for a link — a short human phrase about where the row
 * leads, never the raw URL (issue #11, 2026-09-03: the settings screen shows
 * `Help [button]`, not `Help` with `github.com/...` printed underneath).
 * Exhaustive like [titleRes], so a new link cannot ship without one.
 */
private fun HelpLink.subtitleRes(): Int = when (this) {
    HelpLink.HELP -> R.string.global_settings_help_subtitle
    HelpLink.SOURCE -> R.string.global_settings_source_subtitle
    HelpLink.ISSUES -> R.string.global_settings_issues_subtitle
    HelpLink.TELEGRAM_CHANNEL -> R.string.global_settings_telegram_channel_subtitle
    HelpLink.DEVELOPER_CONTACT -> R.string.global_settings_developer_subtitle
}

/** The row title for a link. Exhaustive, so a new link cannot ship untitled. */
private fun HelpLink.titleRes(): Int = when (this) {
    HelpLink.HELP -> R.string.global_settings_help_title
    HelpLink.SOURCE -> R.string.global_settings_source_title
    HelpLink.ISSUES -> R.string.global_settings_issues_title
    HelpLink.TELEGRAM_CHANNEL -> R.string.global_settings_telegram_channel_title
    HelpLink.DEVELOPER_CONTACT -> R.string.global_settings_developer_title
}

/**
 * The row icon for a link — the Haven stroke set. Exhaustive for the same reason
 * as [titleRes]. Telegram has no redrawable logo, so the two Telegram rows are
 * distinguished by what they *are* — an announcement channel and a chat.
 */
private fun HelpLink.iconRes(): Int = when (this) {
    HelpLink.HELP -> com.fem.linexa.R.drawable.ic_haven_file
    HelpLink.SOURCE -> com.fem.linexa.R.drawable.ic_haven_term
    HelpLink.ISSUES -> com.fem.linexa.R.drawable.ic_haven_warning
    HelpLink.TELEGRAM_CHANNEL -> com.fem.linexa.R.drawable.ic_haven_share
    HelpLink.DEVELOPER_CONTACT -> com.fem.linexa.R.drawable.ic_haven_ext
}
