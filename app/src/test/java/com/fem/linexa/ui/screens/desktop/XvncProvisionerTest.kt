package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.85: JVM tests for the pure Xvnc-provisioning logic (audit item 4.5).
 *
 * These pin the guest package-manager detection → install-command mapping so
 * a workspace's real package manager (apt/apk/pacman/dnf) always resolves to
 * the correct Xvnc package, and an unsupported/empty probe fails closed to
 * "no supported package manager" (which the UI surfaces as the install
 * instruction error instead of a wrong distro command).
 */
class XvncProvisionerTest {

    @Test
    fun theProbeUsesCommandVAndStopsAtTheFirstManagerFound() {
        // `which` may be absent on minimal Alpine/BusyBox images (finding 036).
        // `command -v` is a POSIX builtin; chaining with `||` reproduces the old
        // `| head -1` first-hit behavior without truncating in the guest.
        val command = XvncProvisioner.DETECT_PACKAGE_MANAGER_COMMAND
        assertFalse("must not depend on the external which binary", command.contains("which "))
        assertFalse("the probe must not truncate via head", command.contains("head"))
        listOf("apt-get", "apk", "pacman", "dnf", "zypper").forEach { binary ->
            assertTrue(
                "probe is missing $binary",
                command.contains("command -v $binary")
            )
        }
        assertEquals(
            "each || stops at the first hit, so four separators join five probes",
            4,
            Regex("\\|\\|").findAll(command).count()
        )
    }

    @Test
    fun aptGetProbeResolvesToAptKeyAndDebianPackage() {
        assertEquals(XvncProvisioner.KEY_APT, XvncProvisioner.packageManagerKey("/usr/bin/apt-get"))
        assertEquals(
            "apt-get update && apt-get install -y tigervnc-standalone-server",
            XvncProvisioner.installCommand(XvncProvisioner.KEY_APT)
        )
    }

    @Test
    fun bareAptGetProbeAlsoResolvesByBasename() {
        assertEquals(XvncProvisioner.KEY_APT, XvncProvisioner.packageManagerKey("apt-get"))
    }

    @Test
    fun apkProbeResolvesToAlpinePackage() {
        assertEquals(XvncProvisioner.KEY_APK, XvncProvisioner.packageManagerKey("/sbin/apk"))
        assertEquals(
            "apk add --no-cache tigervnc-server",
            XvncProvisioner.installCommand(XvncProvisioner.KEY_APK)
        )
    }

    @Test
    fun pacmanProbeResolvesToArchPackage() {
        assertEquals(XvncProvisioner.KEY_PACMAN, XvncProvisioner.packageManagerKey("/usr/bin/pacman"))
        assertEquals(
            "pacman -Sy --noconfirm tigervnc",
            XvncProvisioner.installCommand(XvncProvisioner.KEY_PACMAN)
        )
    }

    @Test
    fun dnfProbeResolvesToFedoraPackage() {
        assertEquals(XvncProvisioner.KEY_DNF, XvncProvisioner.packageManagerKey("/usr/bin/dnf"))
        assertEquals(
            "dnf install -y tigervnc-server",
            XvncProvisioner.installCommand(XvncProvisioner.KEY_DNF)
        )
    }

    @Test
    fun zypperProbeResolvesToOpenSusePackage() {
        assertEquals(XvncProvisioner.KEY_ZYPPER, XvncProvisioner.packageManagerKey("/usr/bin/zypper"))
        assertEquals(
            "zypper --non-interactive install -y tigervnc",
            XvncProvisioner.installCommand(XvncProvisioner.KEY_ZYPPER)
        )
    }

    @Test
    fun unknownOrBlankProbeFailsClosed() {
        assertNull(XvncProvisioner.packageManagerKey(""))
        assertNull(XvncProvisioner.packageManagerKey("   "))
        assertNull(XvncProvisioner.packageManagerKey("/usr/bin/emerge"))
        assertNull(XvncProvisioner.packageManagerKey("not-a-package-manager"))
    }

    @Test
    fun unknownKeyHasNoInstallCommand() {
        assertNull(XvncProvisioner.installCommand("emerge"))
        assertNull(XvncProvisioner.installCommand(""))
    }
}
