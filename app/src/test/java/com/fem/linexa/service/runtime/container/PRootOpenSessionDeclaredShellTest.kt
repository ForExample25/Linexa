package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.118 — end-to-end JVM tests for the declared `linexa.environment.shell`
 * through the REAL [PRootContainerRuntime] launch path against the fake PRoot
 * stand-in ([FakeProot]).
 *
 * These exist because the declared shell is the one §1.118 consumer that can
 * make a workspace *worse* than it was: `launch.shell` (§1.73) is a user
 * override of a path the user chose, but `environment.shell` is a statement by
 * the archive's author about their own image — and if it is wrong, believing it
 * hands PRoot a command that cannot exec, on every launch, for a workspace that
 * would otherwise have started fine.
 *
 * The precedence under test is `launch.shell` → `environment.shell` → probe,
 * plus the existence check that makes the middle step safe.
 */
class PRootOpenSessionDeclaredShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    private fun writeConfig(workspaceId: String, yaml: String) {
        val dir = File(harness.filesDir, "workspaces/$workspaceId")
        dir.mkdirs()
        File(dir, "config.yaml").writeText(yaml)
    }

    /** A rootfs that really contains [shells], so the existence check passes. */
    private fun rootfsWith(vararg shells: String): String {
        val rootfs = harness.fakeRootfs()
        shells.forEach { shell ->
            val file = File(rootfs, shell.removePrefix("/"))
            file.parentFile?.mkdirs()
            file.writeText("#!/bin/sh\n")
        }
        return rootfs.absolutePath
    }

    private fun openSession(workspaceId: String, rootfsPath: String): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, rootfsPath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    private fun declaring(shell: String) = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
            version: 13
          architecture: arm64
          environment:
            shell: $shell
    """.trimIndent()

    @Test
    fun aDeclaredShellIsUsedInsteadOfTheProbe() {
        // The probe would find /bin/sh (it is the last fallback and the rootfs
        // has both), so a wrong result here is invisible without the assertion.
        writeConfig("ws-1", declaring("/bin/zsh"))
        val rootfs = rootfsWith("/bin/sh", "/bin/zsh")

        openSession("ws-1", rootfs)

        assertEquals(
            "declared environment.shell must reach argv",
            listOf("/bin/zsh", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun anExplicitLaunchOverrideStillWinsOverADeclaration() {
        // §1.73's block exists to override launch behaviour; a statement of
        // fact must not beat it.
        writeConfig(
            "ws-1",
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian GNU/Linux
              architecture: arm64
              environment:
                shell: /bin/zsh
              launch:
                shell: /bin/ash
            """.trimIndent()
        )
        val rootfs = rootfsWith("/bin/sh", "/bin/zsh", "/bin/ash")

        openSession("ws-1", rootfs)

        assertEquals(
            "launch.shell must outrank environment.shell",
            listOf("/bin/ash", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun aDeclaredShellMissingFromTheRootfsFallsBackToTheProbe() {
        // The regression that matters: believing a wrong declaration would
        // hand PRoot a command that cannot exec, on every launch.
        writeConfig("ws-1", declaring("/bin/zsh"))
        val rootfs = rootfsWith("/bin/sh")

        openSession("ws-1", rootfs)

        assertEquals(
            "a declaration that is wrong about its own image must not be used",
            listOf("/bin/sh", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun aConfigWithNoEnvironmentBlockKeepsTheProbe() {
        writeConfig(
            "ws-1",
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian GNU/Linux
              architecture: arm64
            """.trimIndent()
        )
        val rootfs = rootfsWith("/bin/sh")

        openSession("ws-1", rootfs)

        assertEquals(
            listOf("/bin/sh", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun anInvalidDeclarationFailsOpenToTheProbe() {
        // Fail-open (§1.27): a config.yaml tampered with after import must not
        // stop the workspace from launching.
        writeConfig("ws-1", "linexa:\n  schema_version: 99\n  architecture: arm64\n")
        val rootfs = rootfsWith("/bin/sh")

        openSession("ws-1", rootfs)

        assertEquals(
            listOf("/bin/sh", "--login"),
            harness.createCalls.single().args.takeLast(2)
        )
    }

    @Test
    fun theDeclaredShellDoesNotLeakIntoAnotherWorkspace() {
        writeConfig("ws-1", declaring("/bin/zsh"))
        val declaringRootfs = rootfsWith("/bin/sh", "/bin/zsh")
        val plainRootfs = rootfsWith("/bin/sh", "/bin/zsh")

        openSession("ws-1", declaringRootfs)
        openSession("ws-2", plainRootfs)

        assertEquals(2, harness.createCalls.size)
        assertEquals(listOf("/bin/zsh", "--login"), harness.createCalls[0].args.takeLast(2))
        assertEquals(
            "ws-2 declared nothing, so it must keep the probe result",
            listOf("/bin/sh", "--login"),
            harness.createCalls[1].args.takeLast(2)
        )
    }

    @Test
    fun oneShotExecKeepsTheShBridgeRegardlessOfTheDeclaration() {
        // Same rule §1.73 set for launch.shell: the shell choice is
        // interactive-only, and exec's `/bin/sh -c` bridge is fixed.
        writeConfig("ws-1", declaring("/bin/zsh"))
        val rootfs = rootfsWith("/bin/sh", "/bin/zsh")

        val result = runBlocking { harness.runtime.exec("ws-1", rootfs, "true") }

        assertEquals(0, result.exitCode)
        assertEquals(
            listOf("/bin/sh", "-c", "true"),
            FakeProot.invocations(prootDir).single().argv.takeLast(3)
        )
    }
}
