package com.fem.linexa.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI/UX Phase 4 (3) — the version line in the About footer.
 *
 * The plan asks for "the real version from the build configuration", and the failure mode
 * this guards is not a wrong number but a *dishonest* one: a version line is the single
 * thing a user quotes in a bug report, so a placeholder that looks like a version
 * ("unknown 0", "Version ") sends the maintainer chasing the wrong build. Every case below
 * is one a real release process produces, which is exactly why it cannot be verified by
 * looking at the developer's own debug build — that build only ever exercises the happy
 * path (`"1.0"`, code `1`).
 */
class AppVersionLabelTest {

    @Test
    fun theCurrentBuildsValuesRenderAsNameAndCode() {
        // versionName "1.0" / versionCode 1, as pinned in app/build.gradle.kts.
        assertEquals("1.0 (1)", AppVersionLabel.format("1.0", 1))
    }

    @Test
    fun theBuildCodeIsShownBecauseTheNameAloneCannotIdentifyABuild() {
        // Every APK produced so far is "1.0", so without the code two different CI
        // artifacts are indistinguishable in a bug report.
        assertEquals("1.0 (7)", AppVersionLabel.format("1.0", 7))
        assertEquals("2.1.3 (415)", AppVersionLabel.format("2.1.3", 415))
    }

    @Test
    fun aMissingVersionNameStatesThatItIsUnknownRatherThanRenderingNothing() {
        // "Version " followed by nothing reads as a broken app, not as a missing value.
        assertEquals("${AppVersionLabel.UNKNOWN_VERSION} (1)", AppVersionLabel.format(null, 1))
        assertEquals("${AppVersionLabel.UNKNOWN_VERSION} (1)", AppVersionLabel.format("", 1))
        assertEquals("${AppVersionLabel.UNKNOWN_VERSION} (1)", AppVersionLabel.format("   ", 1))
    }

    @Test
    fun aVersionNameIsTrimmedRatherThanRenderedWithItsWhitespace() {
        // `-PversionName=" 1.0 "` from a shell script is a real way to get here.
        assertEquals("1.0 (1)", AppVersionLabel.format(" 1.0 ", 1))
    }

    @Test
    fun aNonBuildCodeIsOmittedInsteadOfRenderedAsZero() {
        // "(0)" is not a build number, and "(-1)" is nothing at all.
        assertEquals("1.0", AppVersionLabel.format("1.0", 0))
        assertEquals("1.0", AppVersionLabel.format("1.0", -1))
    }

    @Test
    fun aVersionNameThatAlreadyCarriesItsCodeIsNotDoubled() {
        // Some release scripts set versionName = "1.0 (12)". Appending again would print
        // "1.0 (12) (12)".
        assertEquals("1.0 (12)", AppVersionLabel.format("1.0 (12)", 12))
    }

    @Test
    fun aVersionNameContainingTheNumberElsewhereStillGetsItsCode() {
        // The de-duplication matches the exact rendered suffix, not "contains the
        // number" — otherwise version "1.12" with code 12 would lose its build number.
        assertEquals("1.12 (12)", AppVersionLabel.format("1.12", 12))
        // And a name ending in a *different* parenthesised number is not a match.
        assertEquals("1.0 (11) (12)", AppVersionLabel.format("1.0 (11)", 12))
    }

    @Test
    fun everythingIsMissingAtOnce() {
        // Degenerate but must not produce an empty string, which would render a bare
        // "Version" with nothing after it.
        assertEquals(AppVersionLabel.UNKNOWN_VERSION, AppVersionLabel.format(null, 0))
    }
}
