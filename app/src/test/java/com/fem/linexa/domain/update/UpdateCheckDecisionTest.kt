package com.fem.linexa.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check decision: a release is offered only when its tag names a
 * strictly newer version than the installed build; same version reports
 * up-to-date; an installed build ahead of the channel is reported as such —
 * and is NEVER offered.
 */
class UpdateCheckDecisionTest {

    private fun release(tag: String): GithubRelease = GithubRelease(tagName = tag)

    private fun codedRelease(tag: String, code: Int): GithubRelease =
        GithubRelease(tagName = tag, declaredVersionCode = code)

    @Test
    fun aNewerTagIsOffered() {
        val outcome = UpdateCheckDecision.decide(
            installedVersionName = "0.1.0-beta",
            release = release("v0.2.0")
        )
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpdateAvailable)
        assertEquals("v0.2.0", (outcome as UpdateCheckDecision.Outcome.UpdateAvailable).release.tagName)
    }

    @Test
    fun theSameVersionSpelledDifferentlyIsUpToDate() {
        // tag v0.1.0-beta vs versionName 0.1.0-beta — the live repo's exact case.
        val outcome = UpdateCheckDecision.decide("0.1.0-beta", release("v0.1.0-beta"))
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpToDate)
    }

    @Test
    fun anInstalledBuildAheadOfTheChannelIsNeverOffered() {
        val outcome = UpdateCheckDecision.decide("1.0.0", release("v0.2.0"))
        assertTrue(outcome is UpdateCheckDecision.Outcome.InstalledVersionNewer)
    }

    @Test
    fun aNullInstalledVersionOffersAnyParseableNewerRelease() {
        val outcome = UpdateCheckDecision.decide(null, release("v0.1.0"))
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpdateAvailable)
    }

    @Test
    fun aBlankTagFailsTowardUpToDate() {
        assertTrue(UpdateCheckDecision.decide("0.1.0", release("")) is UpdateCheckDecision.Outcome.UpToDate)
        assertTrue(
            UpdateCheckDecision.decide("0.1.0", release("   ")) is UpdateCheckDecision.Outcome.UpToDate
        )
    }

    @Test
    fun anUnparseableTagFailsTowardUpToDate() {
        assertTrue(
            UpdateCheckDecision.decide("0.1.0", release("nightly")) is UpdateCheckDecision.Outcome.UpToDate
        )
    }

    @Test
    fun aDeclaredVersionCodeWinsOverAVersionNameThatLooksOlder() {
        // The tag says 0.1.9 but the publisher declared code 1002, and the installed
        // code is 1001 — the code is the authoritative Android upgrade signal.
        val outcome = UpdateCheckDecision.decide(
            installedVersionName = "0.1.0-beta",
            release = codedRelease("v0.1.9", 1002),
            installedVersionCode = 1001
        )
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpdateAvailable)
    }

    @Test
    fun anEqualDeclaredVersionCodeIsUpToDateEvenWithADifferentName() {
        val outcome = UpdateCheckDecision.decide(
            installedVersionName = "0.2.0",
            release = codedRelease("v0.2.0-build-7", 1002),
            installedVersionCode = 1002
        )
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpToDate)
    }

    @Test
    fun aLowerDeclaredVersionCodeIsANeverOfferedDowngrade() {
        val outcome = UpdateCheckDecision.decide(
            installedVersionName = "0.2.0",
            release = codedRelease("v0.3.0", 1001),
            installedVersionCode = 1002
        )
        assertTrue(outcome is UpdateCheckDecision.Outcome.InstalledVersionNewer)
    }

    @Test
    fun withoutADeclaredCodeTheVersionNameRuleStillApplies() {
        val outcome = UpdateCheckDecision.decide(
            installedVersionName = "0.1.0-beta",
            release = release("v0.2.0"),
            installedVersionCode = 1002
        )
        assertTrue(outcome is UpdateCheckDecision.Outcome.UpdateAvailable)
    }
}
