package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 — JVM tests for automatic package-manager detection.
 *
 * Pin the `command -v` probe → key mapping, the multi-manager precedence via
 * /etc/os-release, and the fail-closed behavior when nothing is found.
 */
class PackageManagerDetectorTest {

    @Test
    fun aptOnlyProbeResolvesToApt() {
        assertEquals("apt", PackageManagerDetector.resolve("/usr/bin/apt-get\n", ""))
    }

    @Test
    fun pacmanOnlyProbeResolvesToPacman() {
        assertEquals("pacman", PackageManagerDetector.resolve("/usr/bin/pacman\n", ""))
    }

    @Test
    fun apkOnlyProbeResolvesToApk() {
        assertEquals("apk", PackageManagerDetector.resolve("/sbin/apk\n", ""))
    }

    @Test
    fun dnfOnlyProbeResolvesToDnf() {
        assertEquals("dnf", PackageManagerDetector.resolve("/usr/bin/dnf\n", ""))
    }

    @Test
    fun zypperOnlyProbeResolvesToZypper() {
        assertEquals("zypper", PackageManagerDetector.resolve("/usr/bin/zypper\n", ""))
    }

    @Test
    fun blankProbeFailsClosed() {
        assertNull(PackageManagerDetector.resolve("", ""))
        assertNull(PackageManagerDetector.resolve("command -v: not found\n", ""))
        assertNull(PackageManagerDetector.resolve("   \n  \n", ""))
    }

    @Test
    fun absoluteAndBarePathsResolveByBasename() {
        assertEquals("apt", PackageManagerDetector.parseWhichOutput("/usr/bin/apt-get").single())
        assertEquals("apt", PackageManagerDetector.parseWhichOutput("apt-get").single())
        assertEquals("dnf", PackageManagerDetector.parseWhichOutput("/bin/dnf").single())
    }

    @Test
    fun multipleManagersPreferOsReleaseDistro() {
        // dnf + apt both present, but the distro is Debian → apt wins.
        val which = "/usr/bin/apt-get\n/usr/bin/dnf\n"
        val osRelease = "NAME=\"Debian GNU/Linux\"\nID=debian\nID_LIKE=\"\"\n"
        assertEquals("apt", PackageManagerDetector.resolve(which, osRelease))
    }

    @Test
    fun multipleManagersPreferIdLikeWhenIdUnknown() {
        // ID is a fork we don't map, but ID_LIKE names fedora → dnf.
        val which = "/usr/bin/dnf\n/usr/bin/apt-get\n"
        val osRelease = "ID=myfork\nID_LIKE=\"fedora\"\n"
        assertEquals("dnf", PackageManagerDetector.resolve(which, osRelease))
    }

    @Test
    fun multipleManagersFallBackToPriorityWhenOsUnknown() {
        val which = "/usr/bin/dnf\n/usr/bin/zypper\n"
        assertEquals("dnf", PackageManagerDetector.resolve(which, ""))
    }

    @Test
    fun distroIdMapping() {
        assertEquals("apt", PackageManagerDetector.distroIdToPm("debian"))
        assertEquals("apt", PackageManagerDetector.distroIdToPm("ubuntu"))
        assertEquals("apt", PackageManagerDetector.distroIdToPm("kali"))
        assertEquals("pacman", PackageManagerDetector.distroIdToPm("arch"))
        assertEquals("pacman", PackageManagerDetector.distroIdToPm("manjaro"))
        assertEquals("apk", PackageManagerDetector.distroIdToPm("alpine"))
        assertEquals("dnf", PackageManagerDetector.distroIdToPm("fedora"))
        assertEquals("dnf", PackageManagerDetector.distroIdToPm("rocky"))
        assertEquals("zypper", PackageManagerDetector.distroIdToPm("opensuse"))
        assertNull(PackageManagerDetector.distroIdToPm("gentoo"))
        assertNull(PackageManagerDetector.distroIdToPm(""))
    }

    @Test
    fun parseOsReleaseHandlesQuotedIds() {
        assertEquals("debian", PackageManagerDetector.parseOsReleaseId("ID=debian"))
        assertEquals("fedora", PackageManagerDetector.parseOsReleaseId("ID=\"fedora\""))
        assertTrue(PackageManagerDetector.parseOsReleaseIdLike("ID_LIKE=\"ubuntu debian\"") == listOf("ubuntu", "debian"))
    }

    @Test
    fun parseWhichDeduplicates() {
        val out = "/usr/bin/apt-get\n/usr/local/bin/apt-get\n"
        assertEquals(listOf("apt"), PackageManagerDetector.parseWhichOutput(out))
    }

    @Test
    fun supportedKeysCoverAllProbes() {
        assertEquals(5, PackageManagerDetector.supportedKeys.size)
        assertTrue(PackageManagerDetector.supportedKeys.containsAll(
            listOf("apt", "pacman", "apk", "dnf", "zypper")
        ))
    }

    // ── §1.118: declared-type confirmation ──────────────────────────────

    @Test
    fun everySupportedKeyMapsBackToTheBinaryTheProbeLooksFor() {
        // The declared-type shortcut is only sound if this inverse is total:
        // a key with no binary would silently fall through to the full probe.
        PackageManagerDetector.supportedKeys.forEach { key ->
            val binary = PackageManagerDetector.binaryFor(key)
            assertNotNull("no binary for key '$key'", binary)
            assertTrue(
                "probe does not look for '$binary'",
                PackageManagerDetector.PROBE_COMMAND.contains(" $binary")
            )
        }
    }

    @Test
    fun binaryForIsTheSameMappingParseWhichOutputUses() {
        // If these two disagreed, a declared manager could be confirmed by a
        // binary the parser would then refuse to recognize.
        PackageManagerDetector.supportedKeys.forEach { key ->
            val binary = PackageManagerDetector.binaryFor(key)!!
            assertEquals(listOf(key), PackageManagerDetector.parseWhichOutput("/usr/bin/$binary\n"))
        }
    }

    @Test
    fun binaryForIsCaseInsensitive() {
        assertEquals("apt-get", PackageManagerDetector.binaryFor("APT"))
    }

    @Test
    fun anUnsupportedDeclaredKeyHasNoVerifyCommand() {
        // Validation rejects these at import, but the detector must not build a
        // `which ` command with an empty argument if one ever reaches it.
        assertNull(PackageManagerDetector.binaryFor("emerge"))
        assertNull(PackageManagerDetector.verifyCommandFor("emerge"))
        assertNull(PackageManagerDetector.verifyCommandFor(""))
    }

    @Test
    fun theFullProbeUsesOneSingleNameCommandVPerManagerAndNeverWhich() {
        val command = PackageManagerDetector.PROBE_COMMAND
        assertFalse("the external which binary may be absent on Alpine", command.contains("which"))
        assertTrue("the probe must use the POSIX builtin", command.contains("command -v"))
        assertTrue("the probe must iterate the managers", command.contains("for "))
        // POSIX defines `command -v` for one name; a single invocation inside
        // the loop is what makes multi-manager lookup portable to dash.
        assertEquals(1, Regex("command -v").findAll(command).count())
        PackageManagerDetector.supportedKeys.forEach { key ->
            val binary = PackageManagerDetector.binaryFor(key)!!
            assertTrue("probe does not look for '$binary'", command.contains(binary))
        }
    }

    @Test
    fun theVerifyCommandChecksExactlyOneManager() {
        val command = PackageManagerDetector.verifyCommandFor("apk")!!
        assertEquals("command -v apk 2>/dev/null", command)
        // Cheaper than PROBE_COMMAND is the whole point.
        listOf("apt-get", "pacman", "dnf", "zypper").forEach {
            assertTrue("verify probe must not mention $it", !command.contains(it))
        }
    }

    @Test
    fun theVerifyCommandInstallsNothing() {
        PackageManagerDetector.supportedKeys.forEach { key ->
            val command = PackageManagerDetector.verifyCommandFor(key)!!
            assertTrue(command.startsWith("command -v "))
            assertTrue(!command.contains("install"))
        }
    }
}
