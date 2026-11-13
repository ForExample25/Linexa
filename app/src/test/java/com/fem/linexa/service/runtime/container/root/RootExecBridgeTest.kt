package com.fem.linexa.service.runtime.container.root

import com.fem.linexa.domain.runtime.CommandResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end state-machine test for [RootExecBridge] on a fake rootfs:
 * a test plays the guest (writes `cmd`, polls `claimed`/`out`/`status`) and
 * a fake [RootExecBridge.execute] stands in for the root-emulated PRoot exec.
 * No Android or PRoot is involved.
 */
class RootExecBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var bridge: RootExecBridge? = null

    @After
    fun tearDown() {
        bridge?.stop()
    }

    private fun rootfs(): File {
        val root = tmp.newFolder("rootfs-${System.nanoTime()}")
        File(root, "tmp").mkdirs()
        return root
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun `a valid request is claimed executed streamed and given a status`() = runBlocking {
        val root = rootfs()
        val calls = AtomicInteger()
        val seenCommand = arrayOfNulls<String>(1)
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { command, onLine ->
                calls.incrementAndGet()
                seenCommand[0] = command
                onLine("alpha")
                onLine("beta")
                CommandResult(exitCode = 7, output = "alpha\nbeta\n", error = "")
            },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        bridge.start()
        assertTrue("marker must exist", File(root, "run/linexa-root/bridge").isFile)
        assertTrue("heartbeat must exist", File(root, "run/linexa-root/heartbeat").isFile)

        val request = File(root, "tmp/.linexa-root-req.case1")
        request.mkdirs()
        File(request, "cmd").writeText("apt install -y htop\n")

        waitFor { File(request, "claimed").isFile }
        waitFor { File(request, "status").isFile }
        // Let any duplicate poll observe the claimed marker too.
        Thread.sleep(100)

        assertEquals(1, calls.get())
        assertEquals("apt install -y htop", seenCommand[0])
        assertEquals("alpha\nbeta\n", File(request, "out").readText())
        assertEquals(7, RootExecProtocol.parseStatus(File(request, "status").readText()))
        assertTrue(File(request, "claimed").isFile)
    }

    @Test
    fun `a pre-claimed request is never executed`() = runBlocking {
        val root = rootfs()
        val calls = AtomicInteger()
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { _, _ -> calls.incrementAndGet(); CommandResult(0, "", "") },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        val request = File(root, "tmp/.linexa-root-req.claimed")
        request.mkdirs()
        File(request, "cmd").writeText("id\n")
        File(request, "claimed").createNewFile()
        bridge.start()
        Thread.sleep(300)
        assertEquals("a claimed request must never be replayed", 0, calls.get())
        assertFalse(File(request, "status").exists())
    }

    @Test
    fun `a symlinked request directory is rejected`() = runBlocking {
        val root = rootfs()
        val outside = tmp.newFolder("outside")
        File(outside, "cmd").writeText("id\n")
        val link = File(root, "tmp/.linexa-root-req.link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val calls = AtomicInteger()
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { _, _ -> calls.incrementAndGet(); CommandResult(0, "", "") },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        bridge.start()
        Thread.sleep(300)
        assertEquals(0, calls.get())
    }

    @Test
    fun `an oversized command is rejected with an explicit failure`() = runBlocking {
        val root = rootfs()
        val request = File(root, "tmp/.linexa-root-req.big")
        request.mkdirs()
        File(request, "cmd").writeText("x".repeat(RootExecProtocol.MAX_COMMAND_BYTES + 1))
        val calls = AtomicInteger()
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { _, _ -> calls.incrementAndGet(); CommandResult(0, "", "") },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        bridge.start()
        waitFor { File(request, "status").isFile }
        assertEquals(0, calls.get())
        assertEquals(
            RootExecProtocol.EXIT_BRIDGE_ERROR,
            RootExecProtocol.parseStatus(File(request, "status").readText())
        )
        assertTrue(File(request, "out").readText().contains("too large"))
    }

    @Test
    fun `heartbeat advances while the bridge is live and disappears on stop`() = runBlocking {
        val root = rootfs()
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { _, _ -> CommandResult(0, "", "") },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        val heartbeat = File(root, "run/linexa-root/heartbeat")
        bridge.start()
        val first = heartbeat.readText()
        // The interval is wall-clock based; give it one full cycle.
        waitFor(timeoutMs = 5_000) { heartbeat.readText() != first }
        bridge.stop()
        assertFalse("a stopped bridge must not leave a live heartbeat", heartbeat.exists())
    }

    @Test
    fun `stop removes the marker and stops claiming`() = runBlocking {
        val root = rootfs()
        val calls = AtomicInteger()
        val bridge = RootExecBridge(
            workspaceId = "ws",
            rootfsPath = root.absolutePath,
            execute = { _, _ -> calls.incrementAndGet(); CommandResult(0, "", "") },
            dispatcher = Dispatchers.IO,
            pollIntervalMs = 10
        ).also { this@RootExecBridgeTest.bridge = it }
        bridge.start()
        val marker = File(root, "run/linexa-root/bridge")
        assertTrue(marker.isFile)
        bridge.stop()
        assertFalse(marker.exists())
        assertFalse(bridge.isRunning)

        val request = File(root, "tmp/.linexa-root-req.after-stop")
        request.mkdirs()
        File(request, "cmd").writeText("id\n")
        Thread.sleep(200)
        assertEquals(0, calls.get())
        assertFalse(File(request, "claimed").exists())
    }
}
