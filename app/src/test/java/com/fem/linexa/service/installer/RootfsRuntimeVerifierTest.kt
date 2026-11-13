package com.fem.linexa.service.installer

import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.PRootLauncher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.147 — the pre-activation smoke test's contract.
 *
 * The verifier itself executes PRoot, which cannot run on the JVM host. The
 * parts that decide *what* runs and *whether a failure is reported* are pure
 * and are pinned here: the shell choice, the script's non-mutating package
 * manager probe, and the two fail-fast reasons that must not touch a process.
 */
class RootfsRuntimeVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun verifier(): RootfsRuntimeVerifier =
        RootfsRuntimeVerifier(
            Mockito.mock(PRootLauncher::class.java),
            Mockito.mock(BootstrapManager::class.java)
        )

    private fun analysis(
        loginShell: String? = null,
        shellCandidates: List<String> = listOf("bin/sh", "bin/bash"),
        packageManager: String? = null
    ) = AnalyzeResult.Success(
        sha256 = "test",
        requiredStorageBytes = 0,
        archiveBytes = 0,
        totalEntries = 0,
        identity = RootFsProbe.Identity(
            RootFsProbe.DistroId.DEBIAN,
            "Debian",
            "12",
            RootFsProbe.RootFsArch.ARM64_V8A,
            detected = true
        ),
        requiredFreeBytes = 0,
        capturedConfig = null,
        loginShell = loginShell,
        shellCandidates = shellCandidates,
        packageManager = packageManager
    )

    // ── fail-fast paths ──────────────────────────────────────────────────

    @Test
    fun `a missing staging tree is reported without starting PRoot`() = kotlinx.coroutines.runBlocking {
        val missing = File(tmp.root, "nope")
        val reason = verifier().verify(missing, "ws", analysis())
        assertTrue("expected a missing-staging reason, got: $reason", reason != null)
        assertTrue(reason!!.contains("Staged rootfs is missing"))
    }

    @Test
    fun `a staging tree without any shell names every candidate it looked for`() =
        kotlinx.coroutines.runBlocking {
            val staging = tmp.newFolder("staging")
            val reason = verifier().verify(
                staging,
                "ws",
                analysis(shellCandidates = listOf("bin/zsh", "usr/bin/fish"))
            )
            assertTrue("expected a no-shell reason, got: $reason", reason != null)
            assertTrue(reason!!.contains("could not find a shell"))
            assertTrue("the reason must list bin/zsh", reason.contains("/bin/zsh"))
            assertTrue("the reason must list usr/bin/fish", reason.contains("/usr/bin/fish"))
        }

    @Test
    fun `a shell path that is a directory is not accepted`() {
        val staging = tmp.newFolder("staging")
        assertTrue(File(staging, "bin/sh").mkdirs())
        assertNull(verifier().resolveShell(staging, analysis(shellCandidates = listOf("bin/sh"))))
    }

    // ── shell choice ────────────────────────────────────────────────────

    @Test
    fun `the profile login shell wins over the candidate list`() {
        val staging = tmp.newFolder("staging")
        File(staging, "bin/sh").apply { parentFile.mkdirs(); writeText("") }
        File(staging, "bin/bash").apply { parentFile.mkdirs(); writeText("") }
        File(staging, "usr/bin/zsh").apply { parentFile.mkdirs(); writeText("") }

        val resolved = verifier().resolveShell(
            staging,
            analysis(loginShell = "/usr/bin/zsh", shellCandidates = listOf("bin/bash", "bin/sh"))
        )
        assertEquals("/usr/bin/zsh", resolved)
    }

    @Test
    fun `a missing login shell falls through to the first real candidate`() {
        val staging = tmp.newFolder("staging")
        File(staging, "bin/bash").apply { parentFile.mkdirs(); writeText("") }
        File(staging, "bin/sh").apply { parentFile.mkdirs(); writeText("") }

        val resolved = verifier().resolveShell(
            staging,
            analysis(loginShell = "/usr/bin/zsh", shellCandidates = listOf("bin/bash", "bin/sh"))
        )
        assertEquals("/bin/bash", resolved)
    }

    @Test
    fun `the hardcoded sh bash ash fallbacks keep a profile-less image working`() {
        val staging = tmp.newFolder("staging")
        File(staging, "bin/ash").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(
            "/bin/ash",
            verifier().resolveShell(staging, analysis(shellCandidates = emptyList()))
        )
    }

    // ── the smoke script itself ─────────────────────────────────────────

    @Test
    fun `without a package manager the script only prints the marker and succeeds`() {
        val script = verifier().buildScript(packageManager = null)
        assertTrue(script.contains("printf 'linexa-smoke-ok"))
        assertTrue(script.trimEnd().endsWith("exit 0"))
        assertFalse("no package-manager probe expected", script.contains("--version"))
    }

    @Test
    fun `with apt the script probes apt-get and never mutates the guest`() {
        val script = verifier().buildScript("apt" to "apt-get")
        assertTrue(script.contains("command -v apt-get"))
        assertTrue(script.contains("apt-get --version"))
        assertTrue("missing binary must be its own failure", script.contains("exit 42"))
        assertTrue("non-starting binary must be its own failure", script.contains("exit 43"))
        assertTrue(script.contains("linexa-smoke: apt binary apt-get not found"))
        assertTrue(script.contains("linexa-smoke: apt did not start"))
        for (mutating in listOf("install", "update", "upgrade", "remove", "dist-upgrade")) {
            assertFalse(
                "the smoke test must never run apt $mutating",
                Regex("apt-get\\s+$mutating\\b").containsMatchIn(script)
            )
        }
    }

    @Test
    fun `with apk the script uses apk's own binary and version flag`() {
        val script = verifier().buildScript("apk" to "apk")
        assertTrue(script.contains("command -v apk"))
        assertTrue(script.contains("apk --version"))
    }

    @Test
    fun `every catalog package manager maps to a probe binary`() {
        for (key in listOf("apt", "pacman", "apk", "dnf", "zypper")) {
            val binary = com.fem.linexa.domain.packagemanager.PackageManagerDetector.binaryFor(key)
            assertTrue("no binary for '$key'", binary != null)
            assertTrue(verifier().buildScript(key to binary!!).contains("$binary --version"))
        }
    }
}
