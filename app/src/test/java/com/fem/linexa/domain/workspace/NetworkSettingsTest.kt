package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network Configuration: the persistence encoding and validation rules.
 * Mirrors [com.fem.linexa.service.runtime.features.FeatureSettings]'s
 * contract — fail-open reads, validated writes — because the network
 * provisioner and the Settings tab read/write the same attribute keys.
 */
class NetworkSettingsTest {

    @Test
    fun defaultsAreFullyPassive() {
        val settings = NetworkSettings()
        assertTrue(settings.isEffectivelyDefault)
        assertEquals(NetworkSettings.DnsMode.SYSTEM, settings.dnsMode)
        assertTrue(settings.dnsServers.isEmpty())
        assertEquals(NetworkSettings.PackageMirrorMode.DEFAULT, settings.packageMirrorMode)
    }

    @Test
    fun roundTripPreservesEveryField() {
        val settings = NetworkSettings(
            dnsMode = NetworkSettings.DnsMode.CUSTOM,
            dnsServers = listOf("10.10.0.1", "fd00::1"),
            packageMirrorMode = NetworkSettings.PackageMirrorMode.CUSTOM,
            packageMirrorUrl = "https://mirror.example.com/debian",
            rootfsMirrorUrl = "https://mirror.example.com/rootfs"
        )
        assertEquals(settings, NetworkSettings.from(settings.toAttributes()))
    }

    @Test
    fun fromAbsentAttributesIsDefault() {
        assertEquals(NetworkSettings(), NetworkSettings.from(emptyMap()))
    }

    @Test
    fun fromIsFailOpen() {
        val settings = NetworkSettings.from(
            mapOf(
                NetworkSettings.KEY_DNS_MODE to "garbage",
                NetworkSettings.KEY_DNS_SERVERS to "not-an-ip, 1.2.3.4.5.6, 8.8.8.8",
                NetworkSettings.KEY_PACKAGE_MIRROR_MODE to "??",
                NetworkSettings.KEY_PACKAGE_MIRROR_URL to " ",
                NetworkSettings.KEY_ROOTFS_MIRROR_URL to ""
            )
        )
        assertEquals(NetworkSettings.DnsMode.SYSTEM, settings.dnsMode)
        assertEquals(listOf("8.8.8.8"), settings.dnsServers)
        assertEquals(NetworkSettings.PackageMirrorMode.DEFAULT, settings.packageMirrorMode)
        assertTrue(settings.isEffectivelyDefault)
    }

    @Test
    fun dnsServerValidationAcceptsV4AndV6AndRejectsTheRest() {
        assertNull(NetworkSettings.validateDnsServers("1.1.1.1, 8.8.8.8"))
        assertNull(NetworkSettings.validateDnsServers("fd00::1"))
        assertTrue(NetworkSettings.validateDnsServers("  ") is NetworkSettings.Companion.DnsRejection.EMPTY)
        assertTrue(
            NetworkSettings.validateDnsServers("dns.example.com") is
                NetworkSettings.Companion.DnsRejection.INVALID_SERVER
        )
        assertTrue(
            NetworkSettings.validateDnsServers("1.2.3.4, oops") is
                NetworkSettings.Companion.DnsRejection.INVALID_SERVER
        )
        assertTrue(NetworkSettings.validateDnsServers("1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5") is
            NetworkSettings.Companion.DnsRejection.TOO_MANY)
    }

    @Test
    fun ipv4ParsingEnforcesOctetRules() {
        assertTrue(NetworkSettings.isValidIpAddress("192.168.1.10"))
        assertTrue(NetworkSettings.isValidIpAddress("0.0.0.0"))
        assertFalse(NetworkSettings.isValidIpAddress("256.1.1.1"))
        assertFalse(NetworkSettings.isValidIpAddress("01.2.3.4"))
        assertFalse(NetworkSettings.isValidIpAddress("1.2.3"))
    }

    @Test
    fun ipv6ParsingHandlesCompression() {
        assertTrue(NetworkSettings.isValidIpAddress("::1"))
        assertTrue(NetworkSettings.isValidIpAddress("fe80::1234:5678"))
        assertTrue(NetworkSettings.isValidIpAddress("2001:db8:0:0:0:0:0:1"))
        assertFalse(NetworkSettings.isValidIpAddress("fe80::1::2"))
        assertFalse(NetworkSettings.isValidIpAddress("2001:db8::zzzz"))
    }

    @Test
    fun mirrorUrlValidationIsStructural() {
        assertTrue(NetworkSettings.validateMirrorUrl("https://mirror.example.com/debian"))
        assertTrue(NetworkSettings.validateMirrorUrl("http://10.0.0.5:3142/alpine"))
        assertFalse(NetworkSettings.validateMirrorUrl("ftp://mirror.example.com"))
        assertFalse(NetworkSettings.validateMirrorUrl("https://mir ror.example.com"))
        assertFalse(NetworkSettings.validateMirrorUrl("https://mirror.example.com\$(rm -rf /)"))
        assertFalse(NetworkSettings.validateMirrorUrl(""))
    }
}
