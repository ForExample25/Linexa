package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.109: JVM tests for the pure sshd-provisioning logic — the guest-side
 * consumer of the `ssh` workspace feature toggle.
 *
 * These pin the detection → package → command chain (so a distro can never
 * resolve to another distro's package), the security-relevant properties of
 * the generated commands (no `PermitRootLogin`/`PasswordAuthentication`
 * weakening, unprivileged port, non-forking daemon), and the sshd-path
 * parsing that decides whether an install is needed at all.
 */
class SshdProvisionerTest {

    // ── detection: delegated to PackageManagerDetector, incl. tie-breaking ──

    @Test
    fun singlePackageManagerResolvesDirectly() {
        assertEquals(
            PackageManagerDetector.KEY_APT,
            SshdProvisioner.packageManagerKey("/usr/bin/apt-get", "")
        )
        assertEquals(
            PackageManagerDetector.KEY_APK,
            SshdProvisioner.packageManagerKey("/sbin/apk", "")
        )
        assertEquals(
            PackageManagerDetector.KEY_PACMAN,
            SshdProvisioner.packageManagerKey("/usr/bin/pacman", "")
        )
    }

    @Test
    fun emptyProbeResolvesToNull() {
        assertNull(SshdProvisioner.packageManagerKey("", ""))
        assertNull(SshdProvisioner.packageManagerKey("   \n  ", "ID=debian"))
    }

    /**
     * The reason this delegates to [PackageManagerDetector] instead of copying
     * XvncProvisioner's `head -1` shortcut: a rootfs carrying two managers must
     * resolve to the distro's own one, not to probe order.
     */
    @Test
    fun multiplePackageManagersAreBrokenByOsRelease() {
        val probe = "/usr/bin/apt-get\n/usr/bin/dnf"
        assertEquals(
            PackageManagerDetector.KEY_DNF,
            SshdProvisioner.packageManagerKey(probe, "ID=fedora")
        )
        assertEquals(
            PackageManagerDetector.KEY_APT,
            SshdProvisioner.packageManagerKey(probe, "ID=ubuntu\nID_LIKE=debian")
        )
    }

    // ── package selection: server package, not the client ──

    @Test
    fun debianAndFedoraLikesUseTheSplitServerPackage() {
        assertEquals("openssh-server", SshdProvisioner.packageFor(PackageManagerDetector.KEY_APT))
        assertEquals("openssh-server", SshdProvisioner.packageFor(PackageManagerDetector.KEY_DNF))
    }

    @Test
    fun alpineArchOpenSuseShipServerInTheOpensshPackage() {
        assertEquals("openssh", SshdProvisioner.packageFor(PackageManagerDetector.KEY_APK))
        assertEquals("openssh", SshdProvisioner.packageFor(PackageManagerDetector.KEY_PACMAN))
        assertEquals("openssh", SshdProvisioner.packageFor(PackageManagerDetector.KEY_ZYPPER))
    }

    @Test
    fun unsupportedKeyHasNoPackageOrCommand() {
        assertNull(SshdProvisioner.packageFor("emerge"))
        assertNull(SshdProvisioner.installCommand("emerge"))
    }

    @Test
    fun everySupportedPackageManagerHasAPackageAndInstallCommand() {
        // Drift guard: a new key added to PackageManagerDetector must be
        // handled here too, or SSH silently becomes uninstallable on it.
        PackageManagerDetector.supportedKeys.forEach { key ->
            assertNotNull("no package for $key", SshdProvisioner.packageFor(key))
            assertNotNull("no install command for $key", SshdProvisioner.installCommand(key))
        }
    }

    // ── install commands: index refresh where required, non-interactive ──

    @Test
    fun installCommandsRefreshTheIndexWhereTheManagerNeedsIt() {
        assertEquals(
            "apt-get update && apt-get install -y openssh-server",
            SshdProvisioner.installCommand(PackageManagerDetector.KEY_APT)
        )
        assertEquals(
            "pacman -Sy --noconfirm openssh",
            SshdProvisioner.installCommand(PackageManagerDetector.KEY_PACMAN)
        )
        assertEquals(
            "apk add --no-cache openssh",
            SshdProvisioner.installCommand(PackageManagerDetector.KEY_APK)
        )
    }

    @Test
    fun everyInstallCommandIsNonInteractive() {
        PackageManagerDetector.supportedKeys.forEach { key ->
            val command = SshdProvisioner.installCommand(key)!!
            assertTrue(
                "install command for $key can block on a prompt: $command",
                command.contains("-y") || command.contains("--noconfirm") || command.contains("--no-cache")
            )
        }
    }

    // ── prepare: privilege-separation dirs + host keys, idempotent ──

    @Test
    fun prepareCreatesBothPrivilegeSeparationDirsAndOnlyMissingHostKeys() {
        val prepare = SshdProvisioner.PREPARE_COMMAND
        // /run/sshd (Debian/Ubuntu/Fedora) and /var/empty (Alpine/Arch) —
        // sshd refuses to start without its own one, and a fresh rootfs has
        // neither (both are tmpfs on a real system).
        assertTrue(prepare.contains("/run/sshd"))
        assertTrue(prepare.contains("/var/empty"))
        assertTrue("mkdir must be idempotent", prepare.contains("mkdir -p"))
        // -A generates only MISSING keys, so re-enabling never rotates an
        // existing host key out from under a client's known_hosts.
        assertTrue(prepare.contains("ssh-keygen -A"))
    }

    @Test
    fun prepareDoesNotWeakenAuthentication() {
        // The distro's own sshd_config policy must apply verbatim: enabling the
        // toggle must never publish a passwordless root shell on the network.
        val prepare = SshdProvisioner.PREPARE_COMMAND
        assertFalse(prepare.contains("PermitRootLogin"))
        assertFalse(prepare.contains("PasswordAuthentication"))
        assertFalse(prepare.contains("sshd_config"))
        assertFalse(prepare.contains("passwd"))
        assertFalse(prepare.contains("chpasswd"))
    }

    // ── daemon command: non-forking, unprivileged port, stderr logging ──

    @Test
    fun daemonCommandRunsSshdInTheForegroundOnTheUnprivilegedPort() {
        val command = SshdProvisioner.daemonCommand("/usr/sbin/sshd")
        assertEquals("/usr/sbin/sshd -D -e -p 8022", command)
        // -D is load-bearing: the daemon is the INITIAL TRACEE of its own proot
        // host (§1.83), so a self-backgrounding sshd would exit that tracee and
        // --kill-on-exit would tear the whole host down.
        assertTrue(command.contains(" -D"))
        // -e sends the log to stderr, which the daemon host redirects to
        // {workspace}/logs/daemon-sshd.log.
        assertTrue(command.contains(" -e"))
        assertFalse("daemon must not self-background", command.contains("&"))
    }

    @Test
    fun sshPortIsUnprivileged() {
        // proot -0 gives emulated guest root, but the HOST process is not root,
        // so binding 22 is not reliable under PRoot; 8022 is Termux convention.
        assertEquals(8022, SshdProvisioner.SSH_PORT)
        assertTrue(SshdProvisioner.SSH_PORT > 1023)
    }

    @Test
    fun daemonCommandHonoursAnExplicitPort() {
        assertEquals("/usr/sbin/sshd -D -e -p 2222", SshdProvisioner.daemonCommand("/usr/sbin/sshd", 2222))
    }

    // ── sshd path probing ──

    @Test
    fun probeCommandChecksAbsoluteSbinCandidates() {
        // sshd lives in /usr/sbin, which is frequently absent from a
        // non-login shell's PATH — a bare `command -v sshd` would miss it.
        assertTrue(SshdProvisioner.SSHD_PROBE_COMMAND.contains("/usr/sbin/sshd"))
        assertTrue(SshdProvisioner.SSHD_PROBE_COMMAND.contains("/usr/local/sbin/sshd"))
    }

    @Test
    fun parsesFirstAbsolutePathFromProbeOutput() {
        assertEquals("/usr/sbin/sshd", SshdProvisioner.parseSshdPath("/usr/sbin/sshd\n"))
        assertEquals("/usr/sbin/sshd", SshdProvisioner.parseSshdPath("  /usr/sbin/sshd  "))
        assertEquals(
            "/usr/sbin/sshd",
            SshdProvisioner.parseSshdPath("\n/usr/sbin/sshd\n/usr/local/sbin/sshd\n")
        )
    }

    @Test
    fun rejectsMissingRelativeOrAmbiguousProbeOutput() {
        assertNull(SshdProvisioner.parseSshdPath(""))
        assertNull(SshdProvisioner.parseSshdPath("\n  \n"))
        // A relative answer would be resolved against the guest's PATH by the
        // daemon host — exactly the ambiguity the absolute probe removes.
        assertNull(SshdProvisioner.parseSshdPath("sshd"))
        // A path with a space cannot be handed to a bare-argv daemon command.
        assertNull(SshdProvisioner.parseSshdPath("/opt/my sshd/sshd"))
    }

    @Test
    fun featureIdAndDaemonNameAreStable() {
        // The feature id must match WorkspaceContent's `ssh` Feature, and the
        // daemon name is the key stopDaemon/isDaemonAlive are called with.
        assertEquals("ssh", SshdProvisioner.FEATURE_SSH)
        assertEquals("sshd", SshdProvisioner.DAEMON_NAME)
    }
}
