package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Modes (2026-08) — pure helpers of the WebView run mode.
 *
 * [DesktopWebMode] holds the string arithmetic the Desktop screen would
 * otherwise inline: the loopback URL, the LAN-share rewrite, and the LAN
 * address pick. A wrong URL is the failure mode a screenshot cannot catch
 * (the WebView renders *something* for any host), which is why the mapping is
 * pinned here rather than trusted to the panel.
 */
class DesktopWebModeTest {

    // ── URL building ─────────────────────────────────────────────────────

    @Test
    fun buildUrlIsLoopbackHttp() {
        assertEquals("http://127.0.0.1:8080/", DesktopWebMode.buildUrl(8080))
    }

    @Test
    fun buildUrlNormalisesThePath() {
        assertEquals("http://127.0.0.1:8080/", DesktopWebMode.buildUrl(8080, ""))
        assertEquals("http://127.0.0.1:8080/app", DesktopWebMode.buildUrl(8080, "app"))
        assertEquals("http://127.0.0.1:8080/app/", DesktopWebMode.buildUrl(8080, "/app/"))
    }

    @Test
    fun buildUrlKeepsArbitraryPorts() {
        assertEquals("http://127.0.0.1:1024/", DesktopWebMode.buildUrl(1024))
        assertEquals("http://127.0.0.1:65535/", DesktopWebMode.buildUrl(65535))
    }

    // ── origin pinning (finding 037) ─────────────────────────────────────

    @Test
    fun navigationWithinTheSameOriginIsAllowed() {
        val pinned = "http://127.0.0.1:8080/"
        assertTrue(DesktopWebMode.isPinnedNavigation(pinned, "http://127.0.0.1:8080/app"))
        assertTrue(DesktopWebMode.isPinnedNavigation(pinned, "http://127.0.0.1:8080/app?q=1"))
        // Userinfo does not change the origin the browser connects to.
        assertTrue(DesktopWebMode.isPinnedNavigation(pinned, "http://user@127.0.0.1:8080/app"))
    }

    @Test
    fun defaultPortsCompareAsTheSameOrigin() {
        assertTrue(
            DesktopWebMode.isPinnedNavigation(
                "http://127.0.0.1/",
                "http://127.0.0.1:80/app"
            )
        )
        assertTrue(
            DesktopWebMode.isPinnedNavigation(
                "https://127.0.0.1:443/",
                "https://127.0.0.1/app"
            )
        )
    }

    @Test
    fun anotherHostPortOrSchemeIsRefused() {
        val pinned = "http://127.0.0.1:8080/"
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "http://127.0.0.1:8081/"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "http://192.168.1.7:8080/"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "https://127.0.0.1:8080/"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "http://localhost:8080/"))
    }

    @Test
    fun nonHttpSchemesAndMalformedTargetsAreRefused() {
        val pinned = "http://127.0.0.1:8080/"
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "file:///etc/passwd"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "content://com.example/x"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "javascript:alert(1)"))
        assertEquals(false, DesktopWebMode.isPinnedNavigation(pinned, "not a url"))
    }

    // ── LAN share ────────────────────────────────────────────────────────

    @Test
    fun shareRewritesLoopbackToTheLanAddress() {
        assertEquals(
            "http://192.168.1.7:8080/",
            DesktopWebMode.lanShareUrl("http://127.0.0.1:8080/", "192.168.1.7")
        )
    }

    @Test
    fun shareRewritesTheSpelledOutLocalhostToo() {
        assertEquals(
            "http://10.0.0.2:3000/app",
            DesktopWebMode.lanShareUrl("http://localhost:3000/app", "10.0.0.2")
        )
    }

    @Test
    fun shareWithoutALanAddressIsDisabled() {
        // A URL that cannot work from another device is worse than no action.
        assertEquals(null, DesktopWebMode.lanShareUrl("http://127.0.0.1:8080/", null))
        assertEquals(null, DesktopWebMode.lanShareUrl("http://127.0.0.1:8080/", ""))
        assertEquals(null, DesktopWebMode.lanShareUrl("http://127.0.0.1:8080/", "  "))
    }

    // ── LAN address pick ────────────────────────────────────────────────

    @Test
    fun wifiRangeIsPreferred() {
        assertEquals(
            "192.168.1.7",
            DesktopWebMode.pickSiteLocalIpv4(listOf("10.20.30.40", "192.168.1.7", "172.16.5.5"))
        )
    }

    @Test
    fun carrierRangeFallsBackBeforePrivate172() {
        assertEquals(
            "10.20.30.40",
            DesktopWebMode.pickSiteLocalIpv4(listOf("172.16.5.5", "10.20.30.40"))
        )
    }

    @Test
    fun private172AcceptsOnlyTheUsableRange() {
        assertEquals("172.20.1.2", DesktopWebMode.pickSiteLocalIpv4(listOf("172.20.1.2")))
        // 172.15 and 172.32 are public space — not a shareable LAN address.
        assertEquals(null, DesktopWebMode.pickSiteLocalIpv4(listOf("172.15.1.2")))
        assertEquals(null, DesktopWebMode.pickSiteLocalIpv4(listOf("172.32.1.2")))
    }

    @Test
    fun loopbackAndIpv6AndPublicAddressesNeverWin() {
        assertEquals(
            null,
            DesktopWebMode.pickSiteLocalIpv4(
                listOf("127.0.1.1", "fe80::1", "8.8.8.8", "169.254.3.4")
            )
        )
    }

    @Test
    fun emptyAddressListHasNoPick() {
        assertEquals(null, DesktopWebMode.pickSiteLocalIpv4(emptyList()))
    }

    @Test
    fun pickIsStableForRealisticDeviceState() {
        // A phone on Wi-Fi typically reports: loopback (filtered), the v4 LAN
        // address, and a link-local v6. The pick must be the LAN v4, every time.
        val addresses = listOf("127.0.1.1", "192.168.43.1", "fe80::a1b2:c3d4")
        assertEquals("192.168.43.1", DesktopWebMode.pickSiteLocalIpv4(addresses))
        assertTrue(DesktopWebMode.lanShareUrl("http://127.0.0.1:8080/", "192.168.43.1") != null)
    }
}
