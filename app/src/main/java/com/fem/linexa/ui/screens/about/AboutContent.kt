package com.fem.linexa.ui.screens.about

import com.fem.linexa.domain.attribution.AttributionCatalog
import com.fem.linexa.domain.attribution.AttributionComponent
import com.fem.linexa.domain.attribution.AttributionKind
import com.fem.linexa.domain.attribution.LicenseRef

/**
 * §1.130 — what the About screen *says*, as a pure Compose-free type.
 *
 * Same rationale as [com.fem.linexa.ui.components.WorkspaceCardContent] (§1.127(c)),
 * [com.fem.linexa.ui.screens.home.HomeChromeState] (§1.128(c)) and
 * [com.fem.linexa.ui.screens.settings.GlobalSettingsContent] (§1.129(d)), but the
 * argument is stronger here: this screen's entire purpose is to state something
 * accurately, and every interesting case is a *data* case that a screenshot of one
 * scroll position cannot verify.
 *
 * The cases that matter and are therefore asserted rather than eyeballed:
 *
 *  * **A component with two real licenses.** `libandroid-support` is Apache-2.0 **and**
 *    MIT — both apply, to different files. Joining with a comma would read as a list of
 *    alternatives, which is what a *dual*-licensed component like Zstandard looks like,
 *    and those are opposite meanings. So the separator is the word "and".
 *  * **A component with no version.** AndroidX is pinned per artifact, so the entry has
 *    no single version. That must render as no version line at all — never "unknown"
 *    or an empty parenthesis, which would read as a failure to detect one.
 *  * **A license with no document.** Public domain has no text to reproduce, so the row
 *    must be a statement rather than a tappable link to an empty screen.
 *  * **The source is shown as a host, not a URL.** `https://github.com/termux/proot`
 *    truncates to something unreadable at row width; the host plus the path's last
 *    segment is what identifies it. The full URL is still what a tap opens.
 */
data class AboutContent(
    val sections: List<Section>,
    val licenses: List<LicenseRow>
) {

    /** One titled group of [ComponentRow]s: the bundled runtime, or the app's libraries. */
    data class Section(
        val kind: AttributionKind,
        val rows: List<ComponentRow>
    )

    /**
     * One upstream project, as text.
     *
     * @param licenseLabel every license, joined by "and" — see the class doc.
     * @param sourceLabel a short, readable identification of [sourceUrl] (host plus the
     *   final path segment), or null when the component names no source. A null here is
     *   only possible for a permissive component: [AttributionComponent] refuses to
     *   exist if a copyleft one has no source.
     */
    data class ComponentRow(
        val id: String,
        val name: String,
        val versionLabel: String?,
        val licenseLabel: String,
        val note: String?,
        val sourceLabel: String?,
        val sourceUrl: String?
    )

    /**
     * One license, and whether its verbatim text can be opened.
     *
     * [hasText] is not a rendering hint — it is the difference between a row that
     * navigates and a row that states a fact. A public-domain row that looked tappable
     * would promise a document that does not exist.
     */
    data class LicenseRow(
        val spdxId: String,
        val displayName: String,
        val hasText: Boolean
    )

    companion object {

        /**
         * Joins several licenses.
         *
         * "and", not a comma: every license listed on a component genuinely applies to
         * some part of it. A comma-separated list reads as alternatives, which is what a
         * dual-licensed component looks like — and a dual license is recorded in the
         * catalog as the *single* license Linexa selected, plus a note (Zstandard). The
         * two must not look the same.
         */
        internal const val LICENSE_SEPARATOR = " and "

        fun from(catalog: List<AttributionComponent> = AttributionCatalog.all): AboutContent {
            // Section order follows AttributionKind's declaration order rather than the
            // order kinds happen to appear in the catalog, so adding a component cannot
            // reorder the screen.
            val sections = AttributionKind.entries.mapNotNull { kind ->
                val rows = catalog.filter { it.kind == kind }.map { componentRow(it) }
                if (rows.isEmpty()) null else Section(kind = kind, rows = rows)
            }

            // Built from what the catalog actually references, never LicenseRef.entries:
            // an enum constant no component uses is not a license Linexa is bound by,
            // and listing it would overstate the obligations.
            val licenses = LicenseRef.entries
                .filter { ref -> catalog.any { ref in it.licenses } }
                .map { ref ->
                    LicenseRow(
                        spdxId = ref.spdxId,
                        displayName = ref.displayName,
                        hasText = ref.assetName != null
                    )
                }

            return AboutContent(sections = sections, licenses = licenses)
        }

        private fun componentRow(component: AttributionComponent): ComponentRow =
            ComponentRow(
                id = component.id,
                name = component.name,
                // A blank version is treated as absent: the catalog is hand-written, and
                // "" would otherwise render as a trailing space after the name.
                versionLabel = component.version?.takeIf { it.isNotBlank() },
                licenseLabel = component.licenses
                    .joinToString(LICENSE_SEPARATOR) { it.displayName },
                note = component.note?.takeIf { it.isNotBlank() },
                sourceLabel = component.sourceUrl?.let { shortenSource(it) },
                sourceUrl = component.sourceUrl?.takeIf { it.isNotBlank() }
            )

        /**
         * Shortens a source URL to something readable on one line.
         *
         * Keeps the host and the last meaningful path segment (`github.com/termux/proot`
         * → `github.com/proot`is wrong, so the *whole* path is kept when it is short and
         * the tail when it is not), drops the scheme and any trailing slash. Total
         * rather than throwing: this runs over a hand-written constant, but a malformed
         * entry must not be able to crash the one screen whose job is to be readable —
         * it degrades to the string as given.
         */
        internal fun shortenSource(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val withoutScheme = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")
            if (withoutScheme.isEmpty()) return null
            return if (withoutScheme.length <= MAX_SOURCE_LABEL_LENGTH) {
                withoutScheme
            } else {
                // Keep the host, which is what identifies the project's home, and the
                // final segment, which is what distinguishes one package from another in
                // a deep termux-packages path.
                val host = withoutScheme.substringBefore('/')
                val leaf = withoutScheme.substringAfterLast('/')
                if (leaf.isEmpty() || leaf == host) host else "$host/…/$leaf"
            }
        }

        /**
         * Longest source label shown in full.
         *
         * `github.com/PCRE2Project/pcre2` (29) must survive intact, while the
         * termux-packages tree paths (70+) are the ones that need shortening.
         */
        private const val MAX_SOURCE_LABEL_LENGTH = 44
    }
}
