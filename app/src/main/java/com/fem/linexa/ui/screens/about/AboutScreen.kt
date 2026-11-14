package com.fem.linexa.ui.screens.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.domain.attribution.AttributionKind
import com.fem.linexa.domain.attribution.LicenseRef
import com.fem.linexa.ui.components.haven.HButton
import com.fem.linexa.ui.components.haven.HCard
import com.fem.linexa.ui.components.haven.HIconCircle
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.components.haven.HavenScreen
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType

/**
 * §1.130 — About: what Linexa ships that it did not write, under which license, and where
 * that project's source is.
 *
 * This exists because Linexa distributes 17 prebuilt native projects (20 artifacts, since
 * PRoot, BusyBox and Zstandard each ship more than one) and 12 library coordinates,
 * several of them copyleft (PRoot GPL-2.0-or-later, BusyBox GPL-2.0-only,
 * GNU tar GPL-3.0-or-later — and, until the 2026-08-29 terminal migration replaced it
 * with the vendored Apache-2.0 Termux modules, terminal-lib GPL-3.0-only), and until
 * now the app named none of them anywhere a user could see. The attribution obligation
 * those licenses carry is not satisfied by a repository file the user never receives.
 *
 * **One destination, two views.** The list of components is the screen; opening a license
 * replaces the list *in place* rather than navigating, and system back returns to the
 * list. Same rationale as §1.126: a linear drill-down with a single exit is not a second
 * destination, and making it one would mean handing the loaded document across a
 * navigation boundary.
 *
 * Every string on the list comes from the pure [AboutContent] rather than being assembled
 * here, so the cases only real data produces — a component under two licenses at once, a
 * component with no single version, a license with no document — are asserted in tests.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Derived from a compile-time constant, so it is built once per composition scope
    // rather than on every recomposition of a scrolling list.
    val content = remember { AboutContent.from() }
    val licenseState by viewModel.licenseText.collectAsState()
    val showingLicense = licenseState !is LicenseTextState.None

    // Back closes the open document first, and only then leaves the screen. Intercepted
    // only while a document is open, so the screen's normal back is untouched.
    BackHandler(enabled = showingLicense) { viewModel.closeLicense() }

    HavenScreen(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // HAVEN header (concept `.hdr` pushed variant): back circle + flexing
            // title block; the subtitle names the open document so the SPDX id is
            // visible without scrolling a 40 KB text back to the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HIconCircle(
                    iconRes = R.drawable.ic_haven_arrow_back,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = {
                        if (showingLicense) viewModel.closeLicense() else onBack()
                    }
                )
                Column(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = HavenType.h2.copy(
                            fontSize = TextUnit(20f, TextUnitType.Sp)
                        ),
                        color = HavenTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    licenseState.currentRef?.spdxId?.let { spdx ->
                        Text(
                            text = spdx,
                            style = HavenType.descMono,
                            color = HavenTheme.colors.text2,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when (val state = licenseState) {
                LicenseTextState.None -> ComponentList(
                    content = content,
                    onOpenLicense = viewModel::openLicense,
                    onOpenSource = { url -> openUrl(context, url) }
                )

                is LicenseTextState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // The design system's one indeterminate indicator.
                    HSpinner()
                }

                is LicenseTextState.Loaded -> LicenseDocument(state.text)

                // Two different messages on purpose: one is a fact about the license,
                // the other is a fault in this app's packaging.
                is LicenseTextState.NoDocument -> LicenseMessage(
                    message = stringResource(R.string.about_license_no_document)
                )

                is LicenseTextState.Unavailable -> LicenseMessage(
                    message = stringResource(R.string.about_license_unavailable)
                )
            }
        }
    }
}

/**
 * The scrolling list: what Linexa is, then the components grouped by kind, then the
 * licenses.
 *
 * A `LazyColumn` rather than a `Column` + `verticalScroll`: there are more than thirty
 * component cards and fifteen license rows, each with several `Text`s, and composing all
 * of them for a screen the user scrolls through once is measurable work for no benefit.
 */
@Composable
private fun ComponentList(
    content: AboutContent,
    onOpenLicense: (LicenseRef) -> Unit,
    onOpenSource: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "own-license") {
            Text(
                text = stringResource(R.string.about_own_license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item(key = "intro") {
            Text(
                text = stringResource(R.string.about_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        content.sections.forEach { section ->
            item(key = "header-${section.kind.name}") {
                SectionHeader(
                    title = stringResource(section.kind.titleRes()),
                    description = stringResource(section.kind.descriptionRes())
                )
            }
            items(section.rows, key = { "component-${it.id}" }) { row ->
                ComponentCard(row = row, onOpenSource = onOpenSource)
            }
        }

        item(key = "header-licenses") {
            SectionHeader(
                title = stringResource(R.string.about_licenses_group),
                description = stringResource(R.string.about_licenses_group_desc)
            )
        }
        items(content.licenses, key = { "license-${it.spdxId}" }) { row ->
            LicenseRowItem(row = row, onOpenLicense = onOpenLicense)
        }

        item(key = "source-offer") {
            // A written offer is what discharges the GPL/LGPL corresponding-source
            // obligation for the conveyed binaries. Keeping it in the app means the
            // offer travels with the APK, not only with the repository.
            Text(
                text = stringResource(R.string.about_source_offer),
                style = HavenType.desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String) {
    val h = HavenTheme.colors
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = title.uppercase(),
            style = HavenType.tileLabel,
            color = h.muted
        )
        Text(
            text = description,
            style = HavenType.desc,
            color = h.text2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * One upstream project.
 *
 * Absent fields render as *nothing* — no "unknown version", no empty source line — for
 * the same reason as the Home card (§1.127(c)): a placeholder claims a fact was expected
 * and missing, when for AndroidX (versions pinned per artifact) having no single version
 * is simply correct.
 */
@Composable
private fun ComponentCard(
    row: AboutContent.ComponentRow,
    onOpenSource: (String) -> Unit
) {
    val h = HavenTheme.colors
    HCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    style = HavenType.rowTitle,
                    color = h.text,
                    modifier = Modifier.weight(1f, fill = false)
                )
                row.versionLabel?.let { version ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = version,
                        style = HavenType.descMono,
                        color = h.muted
                    )
                }
            }

            Text(
                text = row.licenseLabel,
                style = HavenType.desc,
                color = h.primary,
                modifier = Modifier.padding(top = 4.dp)
            )

            row.note?.let { note ->
                Text(
                    text = note,
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // The label is shortened for readability; the tap opens the full URL. That
            // split is the whole reason AboutContent carries both.
            if (row.sourceLabel != null && row.sourceUrl != null) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.about_source, row.sourceLabel),
                    style = HavenType.desc,
                    color = h.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { onOpenSource(row.sourceUrl) }
                )
            }
        }
    }
}

/**
 * One license.
 *
 * A row with a document is a `TextButton`; one without is plain text. The difference is
 * load-bearing rather than cosmetic — a public-domain row that looked tappable would
 * promise a document that does not exist.
 */
@Composable
private fun LicenseRowItem(
    row: AboutContent.LicenseRow,
    onOpenLicense: (LicenseRef) -> Unit
) {
    val h = HavenTheme.colors
    HCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = row.displayName,
                style = HavenType.rowTitle,
                color = h.text
            )
            Text(
                text = row.spdxId,
                style = HavenType.descMono,
                color = h.muted,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (row.hasText) {
                // fromSpdxId is total (§1.130), and the row was built from a LicenseRef,
                // so this resolves — the null branch simply renders no action rather
                // than asserting, because a crash on an informational screen is worse
                // than a missing button.
                LicenseRef.fromSpdxId(row.spdxId)?.let { ref ->
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.about_read_license),
                        style = HavenType.desc,
                        color = h.primary,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable { onOpenLicense(ref) }
                    )
                }
            } else {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.about_license_no_document),
                    style = HavenType.desc,
                    color = h.text2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * A verbatim license document.
 *
 * Monospace and selectable, in one `Text` inside a plain scroll rather than a `LazyColumn`
 * of lines: these are legal documents whose paragraphs are single unwrapped lines up to
 * ~950 characters, so splitting them into list items would break the wrapping and make a
 * copy of the text unfaithful. Selection matters for the same reason — the user must be
 * able to copy the terms exactly as written.
 */
@Composable
private fun LicenseDocument(text: String) {
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LicenseMessage(message: String) {
    val h = HavenTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = HavenType.desc,
            color = h.text2
        )
    }
}

/**
 * Opens a project's source URL in the browser.
 *
 * Wrapped because there is no guarantee a device has an activity that handles `http(s)`:
 * an Android build with no browser is unusual but real, and `startActivity` throws rather
 * than returning. Failing silently is correct here — the URL is also printed on the card,
 * so the information is not lost.
 */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser. The label on the card still names the source.
    }
}

/** The heading for a group of components. Exhaustive, so a new kind cannot be untitled. */
private fun AttributionKind.titleRes(): Int = when (this) {
    AttributionKind.BUNDLED_BINARY -> R.string.about_group_binaries
    AttributionKind.BUNDLED_LIBRARY -> R.string.about_group_libraries
    AttributionKind.APP_DEPENDENCY -> R.string.about_group_dependencies
    AttributionKind.BUNDLED_ICON -> R.string.about_group_icons
}

/** What that group *is*, in one line. */
private fun AttributionKind.descriptionRes(): Int = when (this) {
    AttributionKind.BUNDLED_BINARY -> R.string.about_group_binaries_desc
    AttributionKind.BUNDLED_LIBRARY -> R.string.about_group_libraries_desc
    AttributionKind.APP_DEPENDENCY -> R.string.about_group_dependencies_desc
    AttributionKind.BUNDLED_ICON -> R.string.about_group_icons_desc
}
