package com.fem.linexa.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release-selection rules: drafts NEVER, pre-releases only on the
 * explicit user toggle, first surviving entry of GitHub's newest-first list.
 */
class ReleaseSelectorTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false
    ): GithubRelease = GithubRelease(
        tagName = tag,
        isPrerelease = prerelease,
        isDraft = draft
    )

    @Test
    fun aDraftIsNeverSelectedEvenWithPrereleasesOptedIn() {
        val releases = listOf(
            release("v0.2.0", draft = true),
            release("v0.1.0")
        )
        // The draft is skipped; the newest SURVIVING entry — the stable
        // v0.1.0 — is the selection, never the draft.
        assertEquals("v0.1.0", ReleaseSelector.select(releases, includePrereleases = true)?.tagName)
    }

    @Test
    fun aPrereleaseIsSkippedWhenTheChannelIsStableOnly() {
        val releases = listOf(
            release("v0.2.0-beta", prerelease = true),
            release("v0.1.0")
        )
        assertEquals("v0.1.0", ReleaseSelector.select(releases, includePrereleases = false)?.tagName)
    }

    @Test
    fun theNewestSurvivingEntryIsEvaluatedFirst() {
        val releases = listOf(
            release("v0.3.0"),
            release("v0.2.0"),
            release("v0.1.0")
        )
        assertEquals("v0.3.0", ReleaseSelector.select(releases, includePrereleases = false)?.tagName)
    }

    @Test
    fun optingIntoPrereleasesOffersTheNewestPrerelease() {
        val releases = listOf(
            release("v0.2.0-beta", prerelease = true),
            release("v0.1.0")
        )
        assertEquals(
            "v0.2.0-beta",
            ReleaseSelector.select(releases, includePrereleases = true)?.tagName
        )
    }

    @Test
    fun onlyPrereleasesAndTheChannelIsStableMeansNoRelease() {
        val releases = listOf(
            release("v0.1.0-beta", prerelease = true),
            release("v0.1.0-alpha", prerelease = true)
        )
        assertNull(ReleaseSelector.select(releases, includePrereleases = false))
    }

    @Test
    fun anEmptyListMeansNoRelease() {
        assertNull(ReleaseSelector.select(emptyList(), includePrereleases = true))
    }

    @Test
    fun aTaglessEntryIsNeverSelected() {
        val releases = listOf(release(""))
        assertNull(ReleaseSelector.select(releases, includePrereleases = true))
    }
}
