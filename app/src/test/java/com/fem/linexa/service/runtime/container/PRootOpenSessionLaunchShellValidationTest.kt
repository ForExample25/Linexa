package com.fem.linexa.service.runtime.container

import com.fem.linexa.domain.runtime.DEFAULT_TERMINAL_ID
import com.fem.linexa.test.support.FakeProot
import com.fem.linexa.test.support.ProotRuntimeTestHarness
import com.fem.linexa.test.support.ThreadRecordingLogSink
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RootFS finding 018 — `linexa.launch.shell` must be usable **inside the
 * rootfs** before it is handed to PRoot.
 *
 * The real [PRootContainerRuntime] is driven against [FakeProot]; each case
 * writes a real rootfs and a real `config.yaml`, then asserts the argv the PTY
 * would execute and the diagnostic line. A rejected override must never stop
 * the workspace from opening: resolution falls back to
 * `environment.shell`/the probe. The accepted override remains the exact
 * absolute guest path (single argv token, never shell-interpreted).
 */
class PRootOpenSessionLaunchShellValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prootDir: File
    private lateinit var harness: ProotRuntimeTestHarness

    @Before
    fun setUp() {
        ProotRuntimeTestHarness.resetSeccompMemo()
        ThreadRecordingLogSink.install()
        val root = tmp.newFolder("fake-proot-root")
        prootDir = FakeProot.install(root, FakeProot.Scenario.CLEAN_ALWAYS)
        harness = ProotRuntimeTestHarness(
            prootDir = prootDir,
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache")
        )
        harness.create()
    }

    @After
    fun tearDown() {
        ThreadRecordingLogSink.reset()
    }

    private fun writeConfig(workspaceId: String, yaml: String) {
        val dir = File(harness.filesDir, "workspaces/$workspaceId")
        dir.mkdirs()
        File(dir, "config.yaml").writeText(yaml)
    }

    private fun launchShellConfig(shell: String) = """
        linexa:
          schema_version: 1
          launch:
            shell: $shell
    """.trimIndent()

    /**
     * Native rootfs metadata is only usable when the required `linexa.distro`
     * block is present, so the declaration here mirrors a real image rather
     * than relying on the fail-open parser to invent one.
     */
    private fun declaredShellConfig(shell: String) = """
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

    /** Creates an executable script (or the caller replaces it) inside [rootfs]. */
    private fun executableScript(rootfs: File, guestPath: String, body: String = "#!/bin/sh\n") {
        val file = File(rootfs, guestPath.removePrefix("/"))
        file.parentFile?.mkdirs()
        file.writeText(body)
        check(file.setExecutable(true)) { "could not chmod +x $file" }
    }

    private fun rootfsWithProbeShell(): File = harness.fakeRootfs().also {
        executableScript(it, "/bin/sh")
    }

    private fun openSession(workspaceId: String, rootfs: File): Unit = runBlocking {
        harness.runtime.openTerminal(workspaceId, rootfs.absolutePath, DEFAULT_TERMINAL_ID, asRoot = false)
    }

    private fun launchedCommand(): List<String> =
        harness.createCalls.single().args.takeLast(2)

    private fun logged(): List<String> = ThreadRecordingLogSink.all().map { it.message }

    @Test
    fun aMissingOverrideFallsBackToTheProbeAndIsLogged() {
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()

        openSession("ws-1", rootfs)

        assertEquals(
            "a missing launch.shell must not be returned",
            listOf("/bin/sh", "-l"),
            launchedCommand()
        )
        assertTrue(
            "the fallback must name the rejected path and the reason: ${logged()}",
            logged().any {
                it.contains("launch.shell '/bin/zsh' rejected for ws-1") &&
                    it.contains("does not exist inside the rootfs")
            }
        )
    }

    @Test
    fun aDirectoryOverrideFallsBackToTheProbe() {
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        File(rootfs, "bin/zsh").mkdirs()

        openSession("ws-1", rootfs)

        assertEquals(listOf("/bin/sh", "-l"), launchedCommand())
        assertTrue(
            "a directory must be rejected as not a regular file: ${logged()}",
            logged().any {
                it.contains("launch.shell '/bin/zsh' rejected") &&
                    it.contains("is not a regular file")
            }
        )
    }

    @Test
    fun aNonExecutableOverrideFallsBackToTheProbe() {
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        val notExecutable = File(rootfs, "bin/zsh")
        notExecutable.writeText("#!/bin/sh\n")
        notExecutable.setExecutable(false, false)
        check(!notExecutable.canExecute()) { "could not clear +x on $notExecutable" }

        openSession("ws-1", rootfs)

        assertEquals(listOf("/bin/sh", "-l"), launchedCommand())
        assertTrue(
            "a non-executable file must be rejected: ${logged()}",
            logged().any {
                it.contains("launch.shell '/bin/zsh' rejected") &&
                    it.contains("is not executable")
            }
        )
    }

    @Test
    fun aScriptWithAMissingInterpreterFallsBackToTheProbe() {
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        executableScript(rootfs, "/bin/zsh", "#!/bin/missing-zsh\n")

        openSession("ws-1", rootfs)

        assertEquals(listOf("/bin/sh", "-l"), launchedCommand())
        assertTrue(
            "a script whose interpreter is absent must be rejected: ${logged()}",
            logged().any {
                it.contains("launch.shell '/bin/zsh' rejected") &&
                    it.contains("shebang interpreter '/bin/missing-zsh' does not exist")
            }
        )
    }

    @Test
    fun aValidExecutableOverrideIsUsed() {
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        executableScript(rootfs, "/bin/zsh")

        openSession("ws-1", rootfs)

        assertEquals(
            "a valid launch.shell must still outrank the probe",
            listOf("/bin/zsh", "--login"),
            launchedCommand()
        )
    }

    @Test
    fun aValidSymlinkedOverrideIsUsedWithinTheRootfs() {
        // Absolute guest symlinks are common in real images (alternatives,
        // merged-/usr). The validator must resolve this against the rootfs,
        // not against the host's `/usr/bin/zsh`.
        writeConfig("ws-1", launchShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        executableScript(rootfs, "/usr/bin/zsh")
        Files.createSymbolicLink(
            File(rootfs, "bin/zsh").toPath(),
            Paths.get("/usr/bin/zsh")
        )

        openSession("ws-1", rootfs)

        assertEquals(listOf("/bin/zsh", "--login"), launchedCommand())
    }

    @Test
    fun aDeclaredShellWithAMissingInterpreterFallsBackToTheProbe() {
        writeConfig("ws-1", declaredShellConfig("/bin/zsh"))
        val rootfs = rootfsWithProbeShell()
        executableScript(rootfs, "/bin/zsh", "#!/bin/missing-zsh\n")

        openSession("ws-1", rootfs)

        assertEquals(
            "a broken environment.shell must not beat a usable probe",
            listOf("/bin/sh", "-l"),
            launchedCommand()
        )
        assertTrue(
            "the declared-shell fallback must be logged: ${logged()}",
            logged().any {
                it.contains("declared linexa.environment.shell '/bin/zsh' rejected for ws-1") &&
                    it.contains("shebang interpreter '/bin/missing-zsh' does not exist")
            }
        )
    }
}
