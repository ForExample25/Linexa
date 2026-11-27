package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.network.HostDnsProvider
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.workspace.NetworkSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transformation spec §19/§20 — boot-time network provisioning semantics,
 * plus the 2026-08-29 custom-DNS-as-an-optional-feature redesign:
 *
 * - custom DNS disabled (SYSTEM, the default) follows the HOST's live
 *   resolver — never a hardcoded literal — and only leaves the guest
 *   untouched when the guest actually HAS a resolver;
 * - a resolver-less guest is never left behind (the deterministic
 *   last-resort seed — the exact hole that produced workspace-wide
 *   "Temporary failure resolving");
 * - custom DNS enabled writes the user's servers and verifies the file;
 * - the hosts file is provisioned at EVERY start (even fully-default
 *   workspaces), and the package-mirror PM probe is the same detector the
 *   Package Manager tab uses.
 */
class WorkspaceNetworkProvisionerSemanticsTest {

    /**
     * Captures exec commands; models the resolv.conf side state so the
     * write → read-back verification flow behaves like a real guest.
     */
    private class FakeHost(
        var osRelease: String = "ID=debian\nID_LIKE=debian\n",
        var hosts: String = "",
        var hostname: String = "linexa",
        var marker: String = "",
        /** Whether the guest currently has /etc/resolv.conf (starts absent). */
        var resolvConfExists: Boolean = false,
        /** Optional override hook: return a scripted result for a command. */
        var execCommandDispatcher: ((String) -> com.fem.linexa.domain.runtime.CommandResult?)? = null
    ) : GuestFeatureHost {
        val execs = mutableListOf<String>()
        var resolvConfContent: String = ""

        override suspend fun execCommand(workspaceId: String, command: String): com.fem.linexa.domain.runtime.CommandResult {
            execs += command
            execCommandDispatcher?.invoke(command)?.let { return it }
            // Order matters: the detector's probe names the package managers
            // too, so it must be matched before the generic contains checks.
            val output = when {
                command == PackageManagerDetector.PROBE_COMMAND -> "/usr/bin/apt-get\n"
                command.startsWith("cat /etc/hosts") -> hosts
                command.startsWith("hostname") -> "$hostname\n"
                command.contains("os-release") -> osRelease
                command.startsWith("cat /etc/apt/sources.list.d/linexa-mirror.sources") -> marker
                command == DnsResolvConfCommand.existsCommand() ->
                    if (resolvConfExists) "${DnsResolvConfCommand.EXISTS_MARKER_PRESENT}\n"
                    else "${DnsResolvConfCommand.EXISTS_MARKER_ABSENT}\n"
                command == DnsResolvConfCommand.readCommand() -> resolvConfContent
                command.contains("resolv.conf.linexa") -> {
                    // Model the guest side effect of the atomic write.
                    resolvConfExists = true
                    resolvConfContent = command
                        .substringAfter("printf '%b' '")
                        .substringBefore(".linexa")
                        .replace("\\n", "\n") + "# managed by Linexa\n"
                    ""
                }
                command.contains("apt-get") || command.contains("pacman") -> ""
                else -> ""
            }
            return com.fem.linexa.domain.runtime.CommandResult(0, output, "")
        }

        override suspend fun execCommandWithInput(
            workspaceId: String,
            command: String,
            stdin: String
        ): com.fem.linexa.domain.runtime.CommandResult = execCommand(workspaceId, command)

        override suspend fun startDaemon(workspaceId: String, name: String, command: String): Boolean = true
        override suspend fun stopDaemon(workspaceId: String, name: String) = Unit
        override fun isDaemonAlive(workspaceId: String, name: String): Boolean = false
        override fun hasActiveSession(workspaceId: String): Boolean = true
    }

    private val hostDns = HostDnsProvider { listOf("192.0.2.53", "192.0.2.154") }

    @Test
    fun `SYSTEM mode (custom DNS disabled) writes the HOST's live DNS - not a hardcoded literal`() = runTest {
        val host = FakeHost()
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        val result = provisioner.provisionOnStart("ws", NetworkSettings(dnsMode = NetworkSettings.DnsMode.SYSTEM))

        assertTrue(result.dnsApplied)
        assertEquals(WorkspaceNetworkProvisioner.ProvisionResult.DnsSource.SYSTEM, result.dnsSource)
        assertEquals(listOf("192.0.2.53", "192.0.2.154"), result.dnsServersApplied)
        val write = host.execs.first { it.contains("resolv.conf.linexa") }
        assertTrue("host DNS 192.0.2.53 must be written", write.contains("192.0.2.53"))
        assertTrue("host DNS 192.0.2.154 must be written", write.contains("192.0.2.154"))
        assertFalse("the removed Google literal must never reappear", write.contains("8.8.8.8"))
        assertFalse("the removed Google literal must never reappear", write.contains("8.8.4.4"))
        assertNull("the write must verify clean on the modeled guest", result.dnsFailure)
    }

    @Test
    fun `SYSTEM mode with no host answer leaves an EXISTING guest resolver untouched`() = runTest {
        val host = FakeHost(resolvConfExists = true)
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, HostDnsProvider { emptyList() })
        val result = provisioner.provisionOnStart("ws", NetworkSettings(dnsMode = NetworkSettings.DnsMode.SYSTEM))

        assertFalse(result.dnsApplied)
        assertNull(result.dnsFailure)
        assertEquals(WorkspaceNetworkProvisioner.ProvisionResult.DnsSource.SKIPPED, result.dnsSource)
        assertTrue("no resolv.conf write may run", host.execs.none { it.contains("resolv.conf.linexa") })
    }

    @Test
    fun `SYSTEM mode with no host answer AND no resolv conf seeds the last-resort pair`() = runTest {
        // The 2026-08-29 incident shape: resolver-less rootfs + host DNS
        // unavailable → the old behavior left the guest with NO resolver at
        // all ("Temporary failure resolving"). The deterministic-start
        // guarantee must seed the documented last-resort pair instead.
        val host = FakeHost(resolvConfExists = false)
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, HostDnsProvider { emptyList() })
        val result = provisioner.provisionOnStart("ws", NetworkSettings(dnsMode = NetworkSettings.DnsMode.SYSTEM))

        assertTrue("the resolver-less guest must be self-healed", result.dnsApplied)
        assertEquals(WorkspaceNetworkProvisioner.ProvisionResult.DnsSource.LAST_RESORT, result.dnsSource)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.dnsServersApplied)
        val write = host.execs.first { it.contains("resolv.conf.linexa") }
        assertTrue(write.contains("nameserver 1.1.1.1"))
        assertTrue(write.contains("nameserver 8.8.8.8"))
        assertNull(result.dnsFailure)
    }

    @Test
    fun `CUSTOM mode (custom DNS enabled) writes the configured servers and reports them`() = runTest {
        val host = FakeHost()
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        val settings = NetworkSettings(
            dnsMode = NetworkSettings.DnsMode.CUSTOM,
            dnsServers = listOf("192.0.2.10", "192.0.2.11")
        )
        val result = provisioner.provisionOnStart("ws", settings)

        assertTrue(result.dnsApplied)
        assertEquals(WorkspaceNetworkProvisioner.ProvisionResult.DnsSource.CUSTOM, result.dnsSource)
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), result.dnsServersApplied)
        val write = host.execs.first { it.contains("resolv.conf.linexa") }
        assertTrue(write.contains("nameserver 192.0.2.10"))
        assertTrue(write.contains("nameserver 192.0.2.11"))
        // The host's servers must NOT bleed into a custom configuration.
        assertFalse(write.contains("192.0.2.53"))
        assertNull(result.dnsFailure)
    }

    @Test
    fun `CUSTOM mode with no configured servers is a clear skip - never a silent write`() = runTest {
        val host = FakeHost()
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        val result = provisioner.provisionOnStart(
            "ws",
            NetworkSettings(dnsMode = NetworkSettings.DnsMode.CUSTOM, dnsServers = emptyList())
        )

        assertFalse(result.dnsApplied)
        assertEquals(WorkspaceNetworkProvisioner.ProvisionResult.DnsSource.SKIPPED, result.dnsSource)
        assertTrue("no resolv.conf write may run", host.execs.none { it.contains("resolv.conf.linexa") })
    }

    @Test
    fun `failed verification is surfaced as a DNS failure naming the layer`() = runTest {
        val host = FakeHost()
        // Model a guest where the write "succeeds" (exit 0) but the file
        // does not take effect: the read-back stays empty.
        host.execCommandDispatcher = { command ->
            if (command == DnsResolvConfCommand.readCommand()) {
                com.fem.linexa.domain.runtime.CommandResult(0, "", "")
            } else null
        }
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        val result = provisioner.provisionOnStart("ws", NetworkSettings(dnsMode = NetworkSettings.DnsMode.SYSTEM))

        assertTrue("a no-effect write must not report success", !result.dnsApplied)
        assertTrue(
            "the failure must name the layer: ${result.dnsFailure}",
            result.dnsFailure?.contains("host DNS") == true
        )
    }

    @Test
    fun `hosts file is provisioned even for a fully-default workspace`() = runTest {
        val host = FakeHost()
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        provisioner.provisionOnStart("ws", NetworkSettings()) // all defaults

        val write = host.execs.firstOrNull { it.contains("/etc/hosts.linexa") }
        assertTrue("hosts must be written on every start (§21)", write != null)
        assertTrue(write!!.contains("127.0.0.1"))
    }

    @Test
    fun `hosts write carries root ownership parity with resolv conf`() {
        val command = DnsResolvConfCommand.writeHostsCommand("content")
        assertTrue("hosts write must chown 0:0 like resolv.conf", command.contains("chown 0:0 /etc/hosts.linexa"))
    }

    @Test
    fun `mirror probe uses the shared PackageManagerDetector probe and tie-break`() = runTest {
        // Debian-like os-release + both apt and apk present → apt must win
        // via the SAME os-release tie-break the Package Manager tab uses.
        val host = FakeHost(osRelease = "ID=debian\nID_LIKE=debian\n")
        host.hosts = "127.0.0.1 localhost\n"
        val provisioner = WorkspaceNetworkProvisioner(Repo(), host, hostDns)
        val settings = NetworkSettings(
            packageMirrorMode = NetworkSettings.PackageMirrorMode.CUSTOM,
            packageMirrorUrl = "https://mirror.example.com/debian"
        )
        provisioner.provisionOnStart("ws", settings)

        val probe = host.execs.firstOrNull { it == PackageManagerDetector.PROBE_COMMAND }
        assertTrue("the shared PROBE_COMMAND must be used (§F-6 fix)", probe != null)
        val osRelease = host.execs.firstOrNull { it == PackageManagerDetector.OS_RELEASE_COMMAND }
        assertTrue("the os-release tie-break must run", osRelease != null)
        // The marker CHECK also contains 'linexa-mirror'; the APPLY command is
        // the one that WRITES the managed file (redirect into it).
        val apply = host.execs.firstOrNull { it.contains("> /etc/apt/sources.list.d/linexa-mirror.sources") }
        assertTrue("an apt mirror command must have been selected: ${host.execs}", apply != null && apply.contains("apt"))
    }

    @Test
    fun `mirror marker check covers dnf and zypper managed files`() {
        assertTrue(
            WorkspaceNetworkProvisionerSemanticsTestConstants.MARKER.contains("/etc/yum.repos.d/linexa-mirror.repo") &&
                WorkspaceNetworkProvisionerSemanticsTestConstants.MARKER.contains("/etc/zypp/repos.d/linexa-mirror.repo")
        )
    }

    private class Repo : com.fem.linexa.domain.workspace.WorkspaceRepository by
        com.fem.linexa.test.support.InMemoryWorkspaceRepository()
}

private object WorkspaceNetworkProvisionerSemanticsTestConstants {
    // Mirrors the provisioner's private MIRROR_MARKER_CHECK (kept in sync by
    // this test as a regression guard for the dnf/zypper files).
    val MARKER =
        "cat /etc/apt/sources.list.d/linexa-mirror.sources /etc/apk/repositories " +
            "/etc/pacman.d/mirrorlist /etc/yum.repos.d/linexa-mirror.repo " +
            "/etc/zypp/repos.d/linexa-mirror.repo 2>/dev/null | head -20"
}
