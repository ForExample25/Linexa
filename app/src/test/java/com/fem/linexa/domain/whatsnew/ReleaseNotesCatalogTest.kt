package com.fem.linexa.domain.whatsnew

import com.fem.linexa.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The shipped changelog itself, as data.
 *
 * The catalog is the one file a release touches, so the failure modes are editing
 * mistakes rather than logic errors: an entry whose `versionCode` does not match the
 * build it ships in (the card then never appears, silently), two entries claiming the
 * same version, or an entry with no content. Each is a one-line edit away and none of
 * them fails a build.
 */
class ReleaseNotesCatalogTest {

    @Test
    fun theRunningBuildHasAChangelogEntry() {
        // The versionCode is pinned per release (1002 for 0.2.0), so both variants
        // carry the same code and this check is meaningful in debug unit tests too.
        // The whole feature is inert without it: `BuildConfig` IS available to unit
        // tests for the values it holds as constants — it is the *variant-generated
        // resource ids* that are not meaningfully testable — so this is the one place
        // the catalog can be checked against the real build number rather than a
        // hardcoded one that would drift on the next bump.
        assertNotNull(
            "versionCode ${BuildConfig.VERSION_CODE} ships no release notes — bump the " +
                "catalog in ReleaseNotesCatalog.kt (and LINEXA_VERSION_CODE) when bumping versionCode",
            ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE)
        )
    }

    @Test
    fun theRunningBuildsEntryAgreesWithTheBuildsOwnVersionName() {
        // A mismatch here would render "Version 0.9 (3)" on a build named 1.0 — the card
        // would misidentify the release it is describing.
        val notes = ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE)
        assertEquals(BuildConfig.VERSION_NAME, notes?.versionName)
    }

    @Test
    fun everyEntryHasSomethingToSay() {
        ReleaseNotesCatalog.entries.forEach { notes ->
            assertTrue(
                "versionCode ${notes.versionCode} has neither a summary nor any changes, " +
                    "so its card would render blank and is suppressed entirely",
                notes.hasContent
            )
        }
    }

    @Test
    fun versionCodesAreUnique() {
        // `forVersion` returns the first match, so a duplicate would make the second
        // entry dead text that looks present in the file.
        val codes = ReleaseNotesCatalog.entries.map { it.versionCode }
        assertEquals(
            "two catalog entries claim the same versionCode",
            codes.size,
            codes.toSet().size
        )
    }

    @Test
    fun everyVersionCodeIsARealAndroidVersionCode() {
        ReleaseNotesCatalog.entries.forEach { notes ->
            assertTrue(
                "versionCode ${notes.versionCode} is not a valid Android versionCode, so it " +
                    "could never match a running build",
                notes.versionCode >= 1
            )
        }
    }

    @Test
    fun noEntryCarriesBlankProse() {
        // A change whose text is empty renders as a category pill beside nothing.
        ReleaseNotesCatalog.entries.forEach { notes ->
            notes.summary?.let {
                assertTrue("versionCode ${notes.versionCode} has a blank summary", it.isNotBlank())
            }
            notes.changes.forEach { change ->
                assertTrue(
                    "versionCode ${notes.versionCode} has a ${change.category} change with no text",
                    change.text.isNotBlank()
                )
            }
        }
    }

    @Test
    fun everyEntryCarriesAValidOptionalReleaseDate() {
        // A malformed date is tolerated at render time, but it is still a catalog
        // mistake: the card would silently omit the row the author meant to show.
        ReleaseNotesCatalog.entries.forEach { notes ->
            notes.releaseDate?.let { raw ->
                try {
                    LocalDate.parse(raw)
                } catch (e: DateTimeParseException) {
                    throw AssertionError(
                        "versionCode ${notes.versionCode} has an invalid releaseDate '$raw'",
                        e
                    )
                }
            }
        }
    }

    @Test
    fun everyBetaNamedEntryIsMarkedBeta() {
        // 0.2.0 is deliberately named exactly "0.2.0" while still being a beta, so
        // beta-ness cannot be inferred from the name alone. What must hold is the
        // one-way rule: a name that says beta may never be displayed without the badge.
        ReleaseNotesCatalog.entries
            .filter { it.versionName.contains("beta", ignoreCase = true) }
            .forEach { notes ->
                assertTrue(
                    "versionCode ${notes.versionCode} names itself '${notes.versionName}' " +
                        "but is not marked beta",
                    notes.isBeta
                )
            }
    }

    @Test
    fun theRunningEntryIsBetaExactlyWhenTheBuildIs() {
        // BuildConfig.IS_BETA is the source of truth for 0.2.0 (its name has no
        // suffix), and the badge must not contradict it.
        val running = ReleaseNotesCatalog.forVersion(BuildConfig.VERSION_CODE) ?: return
        assertEquals(BuildConfig.IS_BETA, running.isBeta)
    }

    @Test
    fun previousVersionsAreOlderAndNewestFirst() {
        val current = ReleaseNotesCatalog.entries.maxOf { it.versionCode }
        val previous = ReleaseNotesCatalog.previousVersions(current)
        assertTrue("the catalog must retain at least one earlier release", previous.isNotEmpty())
        assertTrue(previous.all { it.versionCode < current })
        assertEquals(
            previous.sortedByDescending { it.versionCode },
            previous
        )
        assertEquals(
            "the running release must never appear in its own history",
            null,
            previous.firstOrNull { it.versionCode == current }
        )
    }

    @Test
    fun anUnknownVersionHasNoPreviousVersions() {
        assertEquals(emptyList<ReleaseNotes>(), ReleaseNotesCatalog.previousVersions(0))
        assertEquals(emptyList<ReleaseNotes>(), ReleaseNotesCatalog.previousVersions(-1))
    }

    @Test
    fun anUnknownVersionResolvesToNullRatherThanTheNewestEntry() {
        // Load-bearing: falling back to the newest known entry would attribute another
        // release's changes to a build that shipped none, which is worse than showing
        // nothing.
        val beyondEverything = ReleaseNotesCatalog.entries.maxOf { it.versionCode } + 1
        assertEquals(null, ReleaseNotesCatalog.forVersion(beyondEverything))
        assertEquals(null, ReleaseNotesCatalog.forVersion(0))
        assertEquals(null, ReleaseNotesCatalog.forVersion(-1))
    }

    @Test
    fun lookupFindsAnEntryRegardlessOfItsPositionInTheList() {
        // The list is kept chronological for readability, but the lookup must not depend
        // on that — a future insertion in the middle would otherwise break it.
        ReleaseNotesCatalog.entries.forEach { notes ->
            assertEquals(notes, ReleaseNotesCatalog.forVersion(notes.versionCode))
        }
    }

    @Test
    fun theCatalogCarriesNoStringResourceIds() {
        // Structural: `ChangeCategory` deliberately has no @StringRes on it, and the text
        // is literal prose. If a future entry started storing resource ids, the catalog
        // would stop being constructible in a JVM test and every assertion above would
        // have to be deleted. Asserted by checking the text is real prose rather than
        // something that could only be an id.
        ReleaseNotesCatalog.entries.forEach { notes ->
            notes.changes.forEach { change ->
                assertFalse(
                    "change text must be prose, not a resource reference",
                    change.text.startsWith("@string/") || change.text.toIntOrNull() != null
                )
            }
        }
    }
}
