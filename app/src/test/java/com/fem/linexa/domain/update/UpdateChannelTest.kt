package com.fem.linexa.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beta-channel default: a beta build must be able to find its own successor,
 * while a stable build must not silently offer pre-releases.
 */
class UpdateChannelTest {

    @Test
    fun aDeclaredBetaDefaultsToIncludingPrereleases() {
        assertTrue(UpdateChannel.isBeta("0.2.0", declaredBeta = true))
        assertTrue(UpdateChannel.defaultIncludePrereleases("0.2.0", declaredBeta = true))
    }

    @Test
    fun aBetaSuffixIsStillRecognisedForOlderBuilds() {
        assertTrue(UpdateChannel.isBeta("0.1.0-beta", declaredBeta = false))
        assertTrue(UpdateChannel.defaultIncludePrereleases("0.1.0-beta", declaredBeta = false))
    }

    @Test
    fun aStableBuildDefaultsToStableReleasesOnly() {
        assertFalse(UpdateChannel.isBeta("1.0.0", declaredBeta = false))
        assertFalse(UpdateChannel.defaultIncludePrereleases("1.0.0", declaredBeta = false))
    }

    @Test
    fun aMissingVersionNameDoesNotAccidentallyEnablePrereleases() {
        assertFalse(UpdateChannel.isBeta(null, declaredBeta = false))
        assertFalse(UpdateChannel.isBeta("", declaredBeta = false))
    }
}
