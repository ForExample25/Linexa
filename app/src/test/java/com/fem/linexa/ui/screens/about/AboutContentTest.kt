package com.fem.linexa.ui.screens.about

import com.fem.linexa.domain.attribution.AttributionCatalog
import com.fem.linexa.domain.attribution.AttributionComponent
import com.fem.linexa.domain.attribution.AttributionKind
import com.fem.linexa.domain.attribution.LicenseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.130 — what the About screen says, asserted on the real catalog.
 *
 * The interesting cases here are all *data* cases, which is the reason [AboutContent] is a
 * pure type at all: a component under two licenses at once, a component with no single
 * version, a license with no document, and a source URL too long to render. None of those
 * is visible in a screenshot of one scroll position, and all of them occur in the shipped
 * catalog rather than in a fixture.
 */
class AboutContentTest {

    private val content = AboutContent.from()

    private fun row(id: String): AboutContent.ComponentRow? =
        content.sections.flatMap { it.rows }.firstOrNull { it.id == id }

    // ── Sections ───────────────────────────────────────────────────────────

    @Test
    fun sectionOrderFollowsTheKindDeclarationNotTheCatalogOrder() {
        // So adding a component cannot reorder the screen.
        assertEquals(
            listOf(
                AttributionKind.BUNDLED_BINARY,
                AttributionKind.BUNDLED_LIBRARY,
                AttributionKind.APP_DEPENDENCY,
                AttributionKind.BUNDLED_ICON
            ),
            content.sections.map { it.kind }
        )
    }

    @Test
    fun everyCatalogComponentAppearsExactlyOnce() {
        val rendered = content.sections.flatMap { it.rows }.map { it.id }
        assertEquals(AttributionCatalog.all.map { it.id }, rendered)
        assertEquals(rendered.size, rendered.toSet().size)
    }

    @Test
    fun anEmptySectionIsOmittedRatherThanRenderedAsABareHeading() {
        val onlyDependencies = AboutContent.from(
            AttributionCatalog.byKind(AttributionKind.APP_DEPENDENCY)
        )
        assertEquals(
            listOf(AttributionKind.APP_DEPENDENCY),
            onlyDependencies.sections.map { it.kind }
        )
    }

    @Test
    fun anEmptyCatalogProducesNoSectionsAndNoLicenses() {
        // Not a state the app can reach, but the screen must degrade to an empty list
        // rather than throwing on an informational surface.
        val empty = AboutContent.from(emptyList())
        assertTrue(empty.sections.isEmpty())
        assertTrue(empty.licenses.isEmpty())
    }

    // ── The license label ──────────────────────────────────────────────────

    @Test
    fun aComponentUnderTwoLicensesJoinsThemWithAndNotAComma() {
        // "and" because both genuinely apply, to different files. A comma would read as
        // alternatives, which is what a dual license looks like — the opposite meaning.
        assertEquals(
            "Apache License 2.0 and MIT License",
            row("libandroid-support")?.licenseLabel
        )
    }

    @Test
    fun aDualLicensedComponentShowsTheOneLicenseSelectedPlusTheNote() {
        // Zstandard is BSD-3-Clause OR GPL-2.0 at the recipient's option; the catalog
        // records the selection, so the label must name one license and the note must
        // explain why — otherwise the entry looks like it ignored the GPL option.
        val zstd = row("zstd")
        assertEquals("BSD 3-Clause License", zstd?.licenseLabel)
        assertTrue(zstd?.note?.contains("Dual-licensed") == true)
    }

    @Test
    fun theTwoGplGrantsRenderDifferentlyFromEachOther() {
        // Same document, different grants. If these ever read identically the
        // distinction LicenseRef exists for has been lost in the UI layer.
        assertEquals(
            "GNU General Public License v2.0 only",
            row("busybox")?.licenseLabel
        )
        assertEquals(
            "GNU General Public License v2.0 or later",
            row("proot")?.licenseLabel
        )
    }

    @Test
    fun everyRowNamesALicense() {
        content.sections.flatMap { it.rows }.forEach {
            assertTrue("${it.id} renders a blank license label", it.licenseLabel.isNotBlank())
        }
    }

    // ── Absent fields render as nothing ────────────────────────────────────

    @Test
    fun aComponentWithNoSingleVersionRendersNoVersionAtAll() {
        // AndroidX is pinned per artifact. "Unknown" would claim a fact was expected and
        // missing (§1.127(c)); having no single version here is simply correct.
        assertNotNull("the AndroidX row must exist", row("androidx"))
        assertNull(row("androidx")?.versionLabel)
    }

    @Test
    fun aBlankVersionIsTreatedAsAbsent() {
        val blank = AboutContent.from(
            listOf(
                AttributionComponent(
                    id = "blank",
                    name = "Blank",
                    version = "   ",
                    licenses = listOf(LicenseRef.MIT),
                    sourceUrl = null,
                    kind = AttributionKind.APP_DEPENDENCY
                )
            )
        )
        assertNull(blank.sections.single().rows.single().versionLabel)
    }

    @Test
    fun aComponentWithNoNoteRendersNoNote() {
        assertNull(row("zlib")?.note)
    }

    @Test
    fun thePcre2EntryNamesItsLicenseException() {
        // PCRE2 is not plain BSD-3-Clause: its licence carries the PCRE2 binary
        // linking exception. The row must say so rather than silently dropping the
        // distinction.
        assertTrue(row("pcre2")?.note?.contains("exception") == true)
        assertEquals(
            "BSD 3-Clause License with PCRE2 exception",
            row("pcre2")?.licenseLabel
        )
    }

    @Test
    fun aComponentWithNoSourceRendersNeitherLabelNorUrl() {
        val noSource = AboutContent.from(
            listOf(
                AttributionComponent(
                    id = "no-source",
                    name = "No source",
                    version = "1.0",
                    // Permissive on purpose: AttributionComponent refuses to exist if a
                    // copyleft component names no source, so this case is only reachable
                    // for a permissive one.
                    licenses = listOf(LicenseRef.MIT),
                    sourceUrl = null,
                    kind = AttributionKind.APP_DEPENDENCY
                )
            )
        )
        val row = noSource.sections.single().rows.single()
        assertNull(row.sourceLabel)
        assertNull(row.sourceUrl)
    }

    @Test
    fun everyCopyleftRowRendersATappableSource() {
        // The obligation that actually binds, checked at the layer the user sees rather
        // than only in the catalog.
        AttributionCatalog.all
            .filter { component -> component.licenses.any { it.isCopyleft } }
            .forEach { component ->
                val row = row(component.id)
                assertNotNull("${component.id} has no row", row)
                assertTrue(
                    "${component.id} is copyleft and renders no source link",
                    !row?.sourceUrl.isNullOrBlank() && !row?.sourceLabel.isNullOrBlank()
                )
            }
    }

    // ── The source label is short but the URL is whole ─────────────────────

    @Test
    fun theFullUrlIsAlwaysPreservedEvenWhenTheLabelIsShortened() {
        // The label is for reading; the tap must open the real thing.
        content.sections.flatMap { it.rows }.forEach { row ->
            val original = AttributionCatalog.findById(row.id)?.sourceUrl
            assertEquals("${row.id} altered its source URL", original, row.sourceUrl)
        }
    }

    @Test
    fun aShortSourceIsShownInFullWithoutItsScheme() {
        assertEquals("github.com/PCRE2Project/pcre2", row("pcre2")?.sourceLabel)
        assertEquals("github.com/termux/proot", row("proot")?.sourceLabel)
    }

    @Test
    fun aLongSourceKeepsTheHostAndTheLeaf() {
        // The termux-packages tree paths are 70+ characters and truncate to nothing
        // readable; the host says where it lives and the leaf says which package.
        assertEquals(
            "github.com/…/libandroid-selinux",
            row("libandroid-selinux")?.sourceLabel
        )
    }

    @Test
    fun noSourceLabelIsLongEnoughToTruncateOnARow() {
        content.sections.flatMap { it.rows }.mapNotNull { it.sourceLabel }.forEach {
            assertTrue("source label too long to read: $it", it.length <= 44)
        }
    }

    @Test
    fun shorteningIsTotal() {
        // Runs over a hand-written constant, but must never crash the one screen whose
        // job is to be readable.
        assertNull(AboutContent.shortenSource(""))
        assertNull(AboutContent.shortenSource("   "))
        assertNull(AboutContent.shortenSource("https://"))
        assertEquals("example.com", AboutContent.shortenSource("https://example.com/"))
        assertEquals("example.com", AboutContent.shortenSource("http://example.com"))
        // No scheme, no path: degrades to the string as given rather than throwing.
        assertEquals("not a url", AboutContent.shortenSource("not a url"))
    }

    @Test
    fun aHostOnlyUrlTooLongToShowFallsBackToTheHost() {
        val host = "a".repeat(60) + ".example.com"
        assertEquals(host, AboutContent.shortenSource("https://$host"))
    }

    // ── The license list ───────────────────────────────────────────────────

    @Test
    fun theLicenseListIsBuiltFromTheCatalogNotTheEnum() {
        // An enum constant no component uses is not a license Linexa is bound by.
        assertEquals(
            AttributionCatalog.licenses.map { it.spdxId },
            content.licenses.map { it.spdxId }
        )
    }

    @Test
    fun aLicenseWithNoDocumentIsMarkedAsHavingNoText() {
        // Public domain: the row must state a fact, not offer a link to an empty screen.
        val publicDomain = content.licenses
            .single { it.spdxId == LicenseRef.PUBLIC_DOMAIN.spdxId }
        assertTrue("public domain must not look tappable", !publicDomain.hasText)
    }

    @Test
    fun everyOtherLicenseIsMarkedAsHavingText() {
        content.licenses
            .filter { it.spdxId != LicenseRef.PUBLIC_DOMAIN.spdxId }
            .forEach {
                assertTrue("${it.spdxId} should offer its text", it.hasText)
            }
    }

    @Test
    fun everyLicenseRowResolvesBackToItsRef() {
        // The screen looks the ref up by spdxId to open the document; an unresolvable row
        // would render a heading with no action.
        content.licenses.forEach {
            assertNotNull(
                "${it.spdxId} does not resolve back to a LicenseRef",
                LicenseRef.fromSpdxId(it.spdxId)
            )
        }
    }

    @Test
    fun bothGplGrantsAppearAsSeparateRows() {
        // They share one document but are two grants, so the list must show both.
        val ids = content.licenses.map { it.spdxId }
        assertTrue(ids.contains("GPL-2.0-only"))
        assertTrue(ids.contains("GPL-2.0-or-later"))
    }
}
