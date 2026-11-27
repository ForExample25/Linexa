package com.fem.linexa.service.update

import com.fem.linexa.ui.screens.settings.HelpLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ONE place the GitHub coordinates live, against the user-facing
 * help catalog (the two can never drift apart silently) and against the
 * no-embedded-credentials rule.
 */
class UpdateServerConfigTest {

    @Test
    fun theEndpointsAreDerivedFromTheOneOwnerNamePair() {
        assertEquals(
            "https://api.github.com/repos/ForExample25/Linexa/releases?per_page=20",
            UpdateServerConfig.RELEASES_API_URL
        )
        assertEquals(
            "https://github.com/ForExample25/Linexa/releases",
            UpdateServerConfig.RELEASES_PAGE_URL
        )
        assertEquals(
            "https://github.com/ForExample25/Linexa",
            UpdateServerConfig.REPOSITORY_URL
        )
    }

    @Test
    fun theRepositoryMatchesTheHelpCatalogTheUserSees() {
        // `HelpLink.SOURCE` is the "Project on GitHub" row the user can already
        // open — the update checker must point at the same repository.
        assertEquals(HelpLink.SOURCE.url, UpdateServerConfig.REPOSITORY_URL)
        assertTrue(UpdateServerConfig.RELEASES_PAGE_URL.startsWith(HelpLink.SOURCE.url))
    }

    @Test
    fun everyEndpointIsHttpsAndCarriesNoCredential() {
        val endpoints = listOf(
            UpdateServerConfig.RELEASES_API_URL,
            UpdateServerConfig.RELEASES_PAGE_URL,
            UpdateServerConfig.REPOSITORY_URL
        )
        endpoints.forEach { url ->
            assertTrue("must be https: $url", url.startsWith("https://"))
            assertFalse("no tokens in URLs: $url", url.contains("token", ignoreCase = true))
            assertFalse("no credentials in URLs: $url", url.contains("access_token", ignoreCase = true))
            assertFalse("no basic-auth userinfo: $url", Regex("https://[^/@]+@").containsMatchIn(url))
        }
    }

    @Test
    fun aReleaseAssetFromThisRepositoryIsTrusted() {
        assertTrue(
            UpdateServerConfig.isTrustedAssetUrl(
                "https://github.com/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk"
            )
        )
    }

    @Test
    fun anAssetUrlFromAnotherRepositoryOrHostIsRejected() {
        val rejected = listOf(
            "https://github.com/attacker/Linexa/releases/download/v0.2.0/app-release.apk",
            "https://github.com/ForExample25/Other/releases/download/v0.2.0/app-release.apk",
            "https://github.com.evil.example/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
            "https://raw.githubusercontent.com/ForExample25/Linexa/main/app-release.apk",
            "http://github.com/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
            "https://user:pass@github.com/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
            "https://github.com:8443/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
            "https://github.com/ForExample25/Linexa/releases/tag/v0.2.0",
            "not a url"
        )
        rejected.forEach { url ->
            assertFalse("must be rejected: $url", UpdateServerConfig.isTrustedAssetUrl(url))
        }
    }
}
