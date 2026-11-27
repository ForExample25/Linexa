package com.fem.linexa.service.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the published signing identity the updater trusts.
 *
 * The value is public (it is printed beside every release), and changing it silently
 * would make every future in-app update fail verification. The format assertion also
 * catches a truncated paste, which would otherwise reject every real APK.
 */
class ReleaseSigningIdentityTest {

    @Test
    fun theCertificateIsAFullLowercaseSha256() {
        val value = ReleaseSigningIdentity.SHA256
        assertEquals(64, value.length)
        assertTrue("must be lowercase hex", value.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun theAcceptedSetContainsExactlyThePublishedCertificate() {
        assertEquals(setOf(ReleaseSigningIdentity.SHA256), ReleaseSigningIdentity.ACCEPTED_SHA256)
    }
}
