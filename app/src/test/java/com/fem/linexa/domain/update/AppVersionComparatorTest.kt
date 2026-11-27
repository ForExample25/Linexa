package com.fem.linexa.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version-comparison rules the update decision rides on — pure JVM
 * cases pulled from the release spellings the project actually produces
 * (`v0.1.0-beta` tag vs `0.1.0-beta` `versionName`) plus the generic
 * semver-ish traps: `v` prefixes, missing components, multi-digit
 * components, pre-release suffixes, and unparseable tags.
 *
 * The invariant under everything: an unreadable or stale remote tag must
 * FAIL TOWARD "no update", never toward "install this".
 */
class AppVersionComparatorTest {

    @Test
    fun theTagPrefixAndVersionNameSpellTheSameVersion() {
        // The exact live case: tag `v0.1.0-beta`, versionName `0.1.0-beta`.
        assertEquals(0, AppVersionComparator.compare("v0.1.0-beta", "0.1.0-beta"))
        assertTrue(AppVersionComparator.isSame("v0.1.0-beta", "0.1.0-beta"))
        assertFalse(AppVersionComparator.isNewer("v0.1.0-beta", "0.1.0-beta"))
    }

    @Test
    fun aGenuinelyNewerCoreIsNewer() {
        assertTrue(AppVersionComparator.isNewer("0.2.0", "0.1.0-beta"))
        assertTrue(AppVersionComparator.isNewer("v1.0.0", "0.9.9"))
        assertFalse(AppVersionComparator.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun missingComponentsReadAsZero() {
        assertEquals(0, AppVersionComparator.compare("1.0", "1.0.0"))
        assertEquals(0, AppVersionComparator.compare("1", "1.0.0.0"))
        assertTrue(AppVersionComparator.isNewer("1.1", "1.0"))
    }

    @Test
    fun numericComponentsCompareNumericallyNotLexically() {
        // The classic string-compare bug: "1.10" < "1.2" lexicographically.
        assertTrue(AppVersionComparator.isNewer("1.10", "1.2"))
        assertTrue(AppVersionComparator.isNewer("1.2", "1.10").not())
        assertTrue(AppVersionComparator.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun aStableReleaseOutranksItsOwnPreRelease() {
        assertTrue(AppVersionComparator.isNewer("1.0.0", "1.0.0-beta"))
        assertTrue(AppVersionComparator.isNewer("1.0.0-rc", "1.0.0-beta"))
        assertTrue(AppVersionComparator.isNewer("1.0.0-beta2", "1.0.0-beta1"))
        assertFalse(AppVersionComparator.isNewer("1.0.0-beta", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("1.0.0-alpha", "1.0.0-beta"))
    }

    @Test
    fun suffixRunsCompareDigitRunsNumericallyAndLetterRunsLexically() {
        // beta1 > beta: the extra numeric run decides.
        assertTrue(AppVersionComparator.isNewer("1.0.0-beta1", "1.0.0-beta"))
        // rc vs beta: letter runs compare lexicographically.
        assertTrue(AppVersionComparator.isNewer("1.0.0-rc", "1.0.0-beta"))
        // Same spelling with different separators is the same version.
        assertEquals(0, AppVersionComparator.compare("1.0.0-beta.1", "1.0.0-beta1"))
    }

    @Test
    fun anUnparseableRemoteTagFailsTowardNoUpdate() {
        // No digits at all — never offered as an update over any parseable build.
        assertFalse(AppVersionComparator.isNewer("latest", "0.1.0"))
        assertFalse(AppVersionComparator.isNewer("latest", "0.0.1"))
        // Two unparseable spellings are "equal" (both unusable, neither newer).
        assertEquals(0, AppVersionComparator.compare("latest", "unknown"))
    }

    @Test
    fun installedVersionNewerThanTheReleaseIsNotAnUpdate() {
        // Dev build ahead of the published channel.
        assertFalse(AppVersionComparator.isNewer("0.2.0", "1.0.0"))
        assertTrue(AppVersionComparator.compare("0.2.0", "1.0.0") < 0)
    }

    @Test
    fun releaseNamePrefixesAreStrippedLikeTagPrefixes() {
        assertEquals(0, AppVersionComparator.compare("Linexa 0.1.0-beta", "0.1.0-beta"))
        assertTrue(AppVersionComparator.isNewer("Linexa 0.2.0", "0.1.0-beta"))
    }

    @Test
    fun parseabilityRequiresANumericComponent() {
        assertTrue(AppVersionComparator.isParseable("v0.1.0-beta"))
        assertTrue(AppVersionComparator.isParseable("0.1.0"))
        assertTrue(AppVersionComparator.isParseable("Linexa 0.1.0-beta"))
        // The moving-target channel tags GitHub can hand out: no version at all.
        assertFalse(AppVersionComparator.isParseable("latest"))
        assertFalse(AppVersionComparator.isParseable("nightly"))
        assertFalse(AppVersionComparator.isParseable(""))
    }

    @Test
    fun longNumericComponentsDoNotOverflow() {
        assertTrue(AppVersionComparator.isNewer("1.0.0." + Long.MAX_VALUE, "1.0.0"))
    }
}
