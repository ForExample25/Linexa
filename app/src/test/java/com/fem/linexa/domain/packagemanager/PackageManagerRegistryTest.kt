package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 — JVM tests for the provider registry lookup.
 */
class PackageManagerRegistryTest {

    private fun registry() = PackageManagerRegistry(
        aptProvider = AptProvider(),
        pacmanProvider = PacmanProvider(),
        apkProvider = ApkProvider(),
        dnfProvider = DnfProvider(),
        zypperProvider = ZypperProvider()
    )

    @Test
    fun resolvesEverySupportedKey() {
        val r = registry()
        assertTrue(r.providerFor("apt") is AptProvider)
        assertTrue(r.providerFor("pacman") is PacmanProvider)
        assertTrue(r.providerFor("apk") is ApkProvider)
        assertTrue(r.providerFor("dnf") is DnfProvider)
        assertTrue(r.providerFor("zypper") is ZypperProvider)
    }

    @Test
    fun unknownKeyReturnsNull() {
        assertNull(registry().providerFor("brew"))
        assertNull(registry().providerFor(""))
    }

    @Test
    fun supportedKeysMatchDetectorProbeOrder() {
        assertEquals(PackageManagerDetector.supportedKeys, registry().supportedKeys)
        assertEquals(listOf("apt", "pacman", "apk", "dnf", "zypper"), registry().supportedKeys)
    }

    /**
     * §1.147 — every key the detector can *produce* must resolve to a provider.
     * The ViewModel turns an unresolvable key into
     * `PackageManagerUiState.Unsupported("unsupported_pm_<key>")`, so a gap here
     * would show "not supported" for a manager Linexa in fact drives. Asserted
     * over the detector's own distro map rather than a copied list, so adding a
     * distro id without its provider fails here.
     */
    @Test
    fun everyKeyTheDetectorCanProduceResolvesToAProvider() {
        val r = registry()
        val distroIds = listOf(
            "debian", "ubuntu", "kali", "raspbian", "linuxmint", "pop", "elementary", "devuan", "mx", "deepin",
            "arch", "manjaro", "endeavouros", "garuda", "arcolinux", "artix",
            "alpine", "postmarketos",
            "fedora", "rhel", "centos", "rocky", "almalinux", "amazon", "ol", "cloudlinux",
            "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sle", "sles", "sled"
        )
        val mapped = distroIds.mapNotNull { PackageManagerDetector.distroIdToPm(it) }.distinct()
        assertEquals(registry().supportedKeys.toSet(), mapped.toSet())
        mapped.forEach { key ->
            assertTrue("no provider for detector key '$key'", r.providerFor(key) != null)
        }
        // The probe path resolves the same keys via `which`.
        PackageManagerDetector.supportedKeys.forEach { key ->
            val binary = PackageManagerDetector.binaryFor(key)
            assertTrue("no guest binary mapped for '$key'", binary != null)
            assertEquals(key, PackageManagerDetector.parseWhichOutput("/usr/bin/$binary").single())
            assertTrue("probe key '$key' unresolvable", r.providerFor(key) != null)
        }
    }

    /** Each provider reports the key it was registered under. */
    @Test
    fun providerKeysMatchTheirRegistrationKeys() {
        val r = registry()
        PackageManagerDetector.supportedKeys.forEach { key ->
            assertEquals(key, r.providerFor(key)!!.key)
        }
    }
}
