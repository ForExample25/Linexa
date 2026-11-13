package com.fem.linexa.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 4 (2) — the Help / Information links.
 *
 * The plan's requirement for this section is "use only real existing project URLs; do not
 * invent URLs", and that is a requirement no other kind of check can enforce. An invented
 * link compiles, passes lint, renders correctly, looks right in review, and fails only for
 * the user who taps it — landing them on a dead page or, worse, on a stranger's Telegram
 * channel that happens to own the guessed handle. `assembleDebug` cannot see it and a
 * screenshot cannot see it.
 *
 * So these tests pin the exact URLs the project owner supplied, and additionally reject
 * the *shapes* a guessed URL takes: a non-`https` scheme, a host outside the two this
 * project actually uses, a placeholder segment (`example`, `your-`, `TODO`), a trailing
 * slash, or whitespace. That way a future edit that adds a plausible-looking link fails
 * here rather than shipping.
 */
class SettingsLinksTest {

    /** The only hosts this project has anything on. Anything else is a guess. */
    private val allowedHosts = setOf("github.com", "t.me")

    /** Substrings that betray a placeholder rather than a real address. */
    private val placeholderMarkers = listOf(
        "example.com", "your-", "yourname", "todo", "changeme", "xxx", "<", ">"
    )

    private fun host(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/')

    // ── The exact links shipped ────────────────────────────────────────────

    @Test
    fun theHelpLinkIsTheProjectReadme() {
        // The #readme fragment, not the bare repo: GitHub renders the README *below* the
        // file tree, which on a phone is a scroll the user should not have to discover.
        assertEquals(
            "https://github.com/ForExample25/Linexa#readme",
            HelpLink.HELP.url
        )
    }

    @Test
    fun theProjectLinksPointAtTheRealRepository() {
        assertEquals("https://github.com/ForExample25/Linexa", HelpLink.SOURCE.url)
        assertEquals("https://github.com/ForExample25/Linexa/issues", HelpLink.ISSUES.url)
    }

    @Test
    fun theCommunityLinksAreTheOnesTheProjectActuallyHas() {
        assertEquals("https://t.me/aniartx", HelpLink.TELEGRAM_CHANNEL.url)
        assertEquals("https://t.me/for_example_me", HelpLink.DEVELOPER_CONTACT.url)
    }

    @Test
    fun theIssuesLinkBelongsToTheSameRepositoryAsTheSource() {
        // A bug tracker on a *different* repository is a silent misroute: reports would
        // land somewhere the maintainer does not read.
        assertTrue(
            "the Issues URL must be the source repository's own issues page",
            HelpLink.ISSUES.url == "${HelpLink.SOURCE.url}/issues"
        )
    }

    @Test
    fun theHelpLinkPointsIntoTheSameRepositoryToo() {
        assertTrue(
            HelpLink.HELP.url.startsWith(HelpLink.SOURCE.url)
        )
    }

    // ── Shape rules that reject a guess ────────────────────────────────────

    @Test
    fun everyLinkIsHttps() {
        // Not cosmetic: a plain-http link to a page that requires a login (a Telegram
        // chat, a GitHub issue form) is a credential exposure, and both hosts redirect
        // anyway — so http would only ever be a mistake.
        HelpLink.entries.forEach { link ->
            assertTrue(
                "${link.name} must use https, was ${link.url}",
                link.url.startsWith("https://")
            )
        }
    }

    @Test
    fun everyLinkIsOnAHostThisProjectActuallyUses() {
        HelpLink.entries.forEach { link ->
            assertTrue(
                "${link.name} points at ${host(link.url)}, which is not one of $allowedHosts — a new host means a new real account, so add it here deliberately",
                host(link.url) in allowedHosts
            )
        }
    }

    @Test
    fun noLinkContainsAPlaceholder() {
        HelpLink.entries.forEach { link ->
            val lowered = link.url.lowercase()
            placeholderMarkers.forEach { marker ->
                assertFalse(
                    "${link.name} contains the placeholder '$marker': ${link.url}",
                    lowered.contains(marker)
                )
            }
        }
    }

    @Test
    fun noLinkIsMalformedInAWayThatWouldOpenTheWrongPage() {
        HelpLink.entries.forEach { link ->
            assertFalse(
                "${link.name} must not contain whitespace",
                link.url.any { it.isWhitespace() }
            )
            assertFalse(
                "${link.name} must not end in a slash — it changes nothing for the user and makes the displayed address inconsistent",
                link.url.endsWith("/")
            )
            assertTrue(
                "${link.name} must have a path or handle after the host, not just the host",
                link.url.removePrefix("https://").substringAfter('/', "").isNotEmpty()
            )
        }
    }

    @Test
    fun theLinksAreDistinct() {
        // Two rows opening the same page is a copy-paste slip that reads as two
        // different destinations.
        val urls = HelpLink.entries.map { it.url }
        assertEquals(urls.size, urls.toSet().size)
    }

    // ── Grouping ──────────────────────────────────────────────────────────

    @Test
    fun everyKindHasAtLeastOneLink() {
        // An empty group would render a heading over nothing.
        HelpLink.Kind.entries.forEach { kind ->
            assertTrue(
                "no links of kind $kind — the group would render as an empty heading",
                HelpLink.of(kind).isNotEmpty()
            )
        }
    }

    @Test
    fun everyLinkIsReachableThroughItsKind() {
        // The screen renders by kind, so a link belonging to no rendered kind would be
        // invisible while still existing in the source — the §1.19 defect class in
        // miniature.
        val grouped = HelpLink.Kind.entries.flatMap { HelpLink.of(it) }
        assertEquals(HelpLink.entries.toSet(), grouped.toSet())
        assertEquals(HelpLink.entries.size, grouped.size)
    }

    @Test
    fun linksAreReturnedInDeclarationOrder() {
        // Declaration order is section order, so adding a link cannot silently reorder
        // the rows the user has learned the position of.
        assertEquals(
            listOf(HelpLink.SOURCE, HelpLink.ISSUES),
            HelpLink.of(HelpLink.Kind.PROJECT)
        )
        assertEquals(
            listOf(HelpLink.TELEGRAM_CHANNEL, HelpLink.DEVELOPER_CONTACT),
            HelpLink.of(HelpLink.Kind.COMMUNITY)
        )
    }

    // ── The displayed address ─────────────────────────────────────────────

    @Test
    fun theDisplayedAddressDropsOnlyTheScheme() {
        // The row shows where the tap goes, so the user can see the destination before
        // leaving the app. Dropping anything more than the scheme would hide the part
        // that identifies it.
        assertEquals(
            "github.com/ForExample25/Linexa/issues",
            HelpLink.displayUrl(HelpLink.ISSUES.url)
        )
        assertEquals("t.me/aniartx", HelpLink.displayUrl(HelpLink.TELEGRAM_CHANNEL.url))
    }

    @Test
    fun theDisplayedAddressIsNeverEmptyAndNeverTheWholeUrl() {
        HelpLink.entries.forEach { link ->
            val shown = HelpLink.displayUrl(link.url)
            assertTrue("${link.name} displays as nothing", shown.isNotEmpty())
            assertFalse(
                "${link.name} still shows its scheme",
                shown.startsWith("https://")
            )
            assertTrue(
                "${link.name}'s displayed form must still identify the destination",
                shown.startsWith(host(link.url))
            )
        }
    }

    @Test
    fun theDisplayFormatterIsTotal() {
        // Runs over a constant, but must not be able to throw on the one screen whose
        // job is to be readable.
        assertEquals("", HelpLink.displayUrl(""))
        assertEquals("", HelpLink.displayUrl("https://"))
        assertEquals("t.me/x", HelpLink.displayUrl("http://t.me/x/"))
    }
}
