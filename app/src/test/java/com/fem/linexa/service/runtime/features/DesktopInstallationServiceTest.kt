package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopInstallationServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val aptDetect: suspend (String) -> CommandResult = { CommandResult(0, "/usr/bin/apt-get\n") }

    private fun createLauncher(root: File) {
        val f = File(root, "usr/bin/startxfce4")
        f.parentFile!!.mkdirs()
        f.writeText("x")
    }

    @Test
    fun `installs through the package manager command and verifies the launcher on disk`() = runTest {
        val root = tmp.newFolder()
        val seen = mutableListOf<String>()
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.XFCE, root, aptDetect,
            execStreaming = { cmd, forward ->
                seen += cmd
                forward("Setting up xfce4 (4.20)")
                createLauncher(root)
                CommandResult(0, "")
            }
        )
        assertEquals(DesktopInstallationService.Result.Installed("usr/bin/startxfce4"), result)
        assertEquals(listOf(DesktopPreset.installCommand(DesktopPreset.XFCE, "apt")), seen)
    }

    @Test
    fun `both entry points produce the identical command - one definition`() = runTest {
        // The Features installer and Start Desktop both call install(); the command
        // is derived only from (preset, package manager), so two callers cannot differ.
        val commands = mutableListOf<String>()
        repeat(2) {
            val root = tmp.newFolder()
            DesktopInstallationService().install(
                "ws", DesktopPreset.XFCE, root, aptDetect,
                execStreaming = { cmd, _ -> commands += cmd; createLauncher(root); CommandResult(0, "") }
            )
        }
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
    }

    @Test
    fun `already installed is idempotent and runs nothing`() = runTest {
        val root = tmp.newFolder()
        createLauncher(root)
        var ran = false
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.XFCE, root,
            exec = { ran = true; CommandResult(0, "") },
            execStreaming = { _, _ -> ran = true; CommandResult(0, "") }
        )
        assertEquals(DesktopInstallationService.Result.AlreadyInstalled("usr/bin/startxfce4"), result)
        assertFalse(ran)
    }

    @Test
    fun `exit zero without a launcher is VerifyFailed never Installed`() = runTest {
        val root = tmp.newFolder()
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.XFCE, root, aptDetect,
            execStreaming = { _, _ -> CommandResult(0, "") }
        )
        assertEquals(DesktopInstallationService.Result.VerifyFailed, result)
    }

    @Test
    fun `package manager failure reports the real tail and exit code`() = runTest {
        val root = tmp.newFolder()
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.XFCE, root, aptDetect,
            execStreaming = { _, _ -> CommandResult(100, "", "E: Unable to locate package xfce4") }
        )
        result as DesktopInstallationService.Result.Failed
        assertEquals(100, result.exitCode)
        assertTrue(result.reason.contains("Unable to locate package"))
    }

    @Test
    fun `no package manager is Unsupported and nothing is installed`() = runTest {
        val root = tmp.newFolder()
        var streamed = false
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.XFCE, root,
            exec = { CommandResult(1, "") },
            execStreaming = { _, _ -> streamed = true; CommandResult(0, "") }
        )
        assertTrue(result is DesktopInstallationService.Result.Unsupported)
        assertFalse(streamed)
    }

    @Test
    fun `preset NONE is Unsupported`() = runTest {
        val result = DesktopInstallationService().install(
            "ws", DesktopPreset.NONE, tmp.newFolder(), aptDetect, { _, _ -> CommandResult(0, "") }
        )
        assertTrue(result is DesktopInstallationService.Result.Unsupported)
    }

    @Test
    fun `a concurrent install for the same workspace is Busy and the first still completes`() = runTest {
        val root = tmp.newFolder()
        val service = DesktopInstallationService()
        val inStream = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = async {
            service.install(
                "ws", DesktopPreset.XFCE, root, aptDetect,
                execStreaming = { _, _ -> inStream.complete(Unit); gate.await(); createLauncher(root); CommandResult(0, "") }
            )
        }
        inStream.await()
        assertTrue(service.isInstalling("ws"))
        val second = service.install(
            "ws", DesktopPreset.XFCE, root, aptDetect, { _, _ -> CommandResult(0, "") }
        )
        assertEquals(DesktopInstallationService.Result.Busy, second)
        gate.complete(Unit)
        assertTrue(first.await() is DesktopInstallationService.Result.Installed)
        assertFalse(service.isInstalling("ws"))
    }

    @Test
    fun `different workspaces do not block each other`() = runTest {
        val service = DesktopInstallationService()
        val rootA = tmp.newFolder()
        val rootB = tmp.newFolder()
        val inStream = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val a = async {
            service.install("a", DesktopPreset.XFCE, rootA, aptDetect, { _, _ ->
                inStream.complete(Unit); gate.await(); createLauncher(rootA); CommandResult(0, "")
            })
        }
        inStream.await()
        val b = service.install("b", DesktopPreset.XFCE, rootB, aptDetect, { _, _ ->
            createLauncher(rootB); CommandResult(0, "")
        })
        assertTrue(b is DesktopInstallationService.Result.Installed)
        gate.complete(Unit)
        a.await()
    }

    @Test
    fun `cancellation releases the lock so the next install can run (interrupted install recovery)`() = runTest {
        val root = tmp.newFolder()
        val service = DesktopInstallationService()
        try {
            service.install(
                "ws", DesktopPreset.XFCE, root, aptDetect,
                execStreaming = { _, _ -> throw CancellationException("user cancelled") }
            )
        } catch (_: CancellationException) {
        }
        assertFalse(service.isInstalling("ws"))
        val retry = service.install(
            "ws", DesktopPreset.XFCE, root, aptDetect,
            execStreaming = { _, _ -> createLauncher(root); CommandResult(0, "") }
        )
        assertTrue(retry is DesktopInstallationService.Result.Installed)
    }

    @Test
    fun `repeated install after success is AlreadyInstalled`() = runTest {
        val root = tmp.newFolder()
        val service = DesktopInstallationService()
        service.install("ws", DesktopPreset.XFCE, root, aptDetect, { _, _ -> createLauncher(root); CommandResult(0, "") })
        val again = service.install("ws", DesktopPreset.XFCE, root, aptDetect, { _, _ -> CommandResult(1, "boom") })
        assertTrue(again is DesktopInstallationService.Result.AlreadyInstalled)
    }
}
