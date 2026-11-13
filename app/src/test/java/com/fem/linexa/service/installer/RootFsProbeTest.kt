package com.fem.linexa.service.installer

import com.fem.linexa.service.installer.RootFsProbe.DistroId
import com.fem.linexa.service.installer.RootFsProbe.RootFsArch
import com.fem.linexa.service.installer.RootFsProbe.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure rootfs distro/architecture detector (§RootFsProbe).
 */
class RootFsProbeTest {

    private val debianOsRelease = """
        PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
        NAME="Debian GNU/Linux"
        VERSION_ID="12"
        VERSION="12 (bookworm)"
        ID=debian
        HOME_URL="https://www.debian.org/"
    """.trimIndent()

    private val ubuntuOsRelease = """
        NAME="Ubuntu"
        VERSION="22.04.5 LTS (Jammy Jellyfish)"
        ID=ubuntu
        ID_LIKE=debian
        VERSION_ID="22.04"
        HOME_URL="https://www.ubuntu.com/"
    """.trimIndent()

    private val alpineOsRelease = """
        NAME="Alpine Linux"
        ID=alpine
        VERSION_ID=3.24.1
        PRETTY_NAME="Alpine Linux v3.24"
    """.trimIndent()

    @Test
    fun detectsDebianFromOsRelease() {
        val identity = RootFsProbe.detect(
            Signals(osRelease = debianOsRelease, archHints = setOf("aarch64"))
        )
        assertEquals(DistroId.DEBIAN, identity.distroId)
        assertEquals("Debian", identity.distroName)
        assertEquals("12", identity.version)
        assertEquals(RootFsArch.ARM64_V8A, identity.arch)
        assertTrue(identity.detected)
    }

    @Test
    fun detectsUbuntuFromOsRelease() {
        val identity = RootFsProbe.detect(
            Signals(osRelease = ubuntuOsRelease, archHints = setOf("aarch64"))
        )
        assertEquals(DistroId.UBUNTU, identity.distroId)
        assertEquals("22.04", identity.version)
    }

    @Test
    fun detectsAlpineFromOsReleaseAndMarker() {
        val viaRelease = RootFsProbe.detect(Signals(osRelease = alpineOsRelease))
        assertEquals(DistroId.ALPINE, viaRelease.distroId)
        assertEquals("3.24.1", viaRelease.version)

        val viaMarker = RootFsProbe.detect(Signals(alpineRelease = "3.24.1"))
        assertEquals(DistroId.ALPINE, viaMarker.distroId)
        assertEquals("3.24.1", viaMarker.version)
    }

    @Test
    fun detectsArchFromMarkerFile() {
        val identity = RootFsProbe.detect(Signals(archRelease = "Arch Linux ARM (aarch64)"))
        assertEquals(DistroId.ARCH, identity.distroId)
    }

    @Test
    fun detectsDebianFromDebianVersionMarker() {
        val identity = RootFsProbe.detect(Signals(debianVersion = "12.5\n"))
        assertEquals(DistroId.DEBIAN, identity.distroId)
        assertEquals("12.5", identity.version)
    }

    @Test
    fun detectsFedoraFromMarker() {
        val identity = RootFsProbe.detect(Signals(fedoraRelease = "Fedora release 42 (Forty Two)"))
        assertEquals(DistroId.FEDORA, identity.distroId)
    }

    @Test
    fun detectsArchitectureFromHints() {
        assertEquals(RootFsArch.ARM64_V8A, RootFsProbe.detect(Signals(archHints = setOf("aarch64"))).arch)
        assertEquals(RootFsArch.ARMEABI_V7A, RootFsProbe.detect(Signals(archHints = setOf("armv7"))).arch)
        assertEquals(RootFsArch.X86_64, RootFsProbe.detect(Signals(archHints = setOf("x86_64"))).arch)
        assertEquals(RootFsArch.X86, RootFsProbe.detect(Signals(archHints = setOf("i386"))).arch)
    }

    @Test
    fun unknownWhenNoSignalsMatch() {
        val identity = RootFsProbe.detect(Signals())
        assertEquals(DistroId.OTHER, identity.distroId)
        assertEquals(RootFsArch.UNKNOWN, identity.arch)
        assertFalse(identity.detected)
    }
}
