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

    /**
     * 30 s, not 5 s: this suite runs on a swapping Android/Termux build box
     * where the test JVM and the poller can both be descheduled for tens of
     * seconds. The poll interval is 10 ms, so a healthy bridge answers in
     * milliseconds; a longer wall-clock budget only changes how a stalled
     * host is reported.
     */
    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    /** F-035: the guest echoes the current marker token into its request. */
    private fun writeToken(root: File, request: File) {
        val marker = File(root, "run/linexa-root/bridge")
        val token = requireNotNull(RootExecProtocol.markerToken(marker.readText()))
        File(request, RootExecProtocol.TOKEN_FILE).writeText("$token\n")
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
        writeToken(root, request)
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

    /**
     * Writes a complete request under a staging name and renames the directory
     * into the polled namespace in one atomic step, so the bridge never sees a
     * half-written `cmd`. The production guest writes in place; the bridge's
     * tolerance of that is covered by the valid-request test.
     */
    private fun stageRequest(root: File, name: String, command: String): File {
        val staging = File(root, "tmp/.linexa-staging-$name")
        staging.mkdirs()
        writeToken(root, staging)
        File(staging, RootExecProtocol.COMMAND_FILE).writeText(command)
        val request = File(root, "tmp/$name")
        java.nio.file.Files.move(
            staging.toPath(),
            request.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
        return request
    }

    @Test
    fun `an oversized command is rejected with an explicit failure`() = runBlocking {
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
        val request = stageRequest(
            root,
            ".linexa-root-req.big",
            "x".repeat(RootExecProtocol.MAX_COMMAND_BYTES + 1)
        )
        waitFor { File(request, "status").isFile }
        assertEquals(0, calls.get())
        assertEquals(
            RootExecProtocol.EXIT_BRIDGE_ERROR,
            RootExecProtocol.parseStatus(File(request, "status").readText())
        )
        val out = File(request, "out").readText()
        assertTrue("out was: $out", out.contains("too large"))
    }

    @Test
    fun `a request without the current session token is rejected`() = runBlocking {
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
        val request = File(root, "tmp/.linexa-root-req.stale")
        request.mkdirs()
        File(request, RootExecProtocol.TOKEN_FILE).writeText("not-this-session\n")
        File(request, "cmd").writeText("id\n")

        waitFor { File(request, "status").isFile }
        assertEquals(0, calls.get())
        assertEquals(
            RootExecProtocol.EXIT_BRIDGE_ERROR,
            RootExecProtocol.parseStatus(File(request, "status").readText())
        )
        assertTrue(File(request, "out").readText().contains("not from this bridge session"))
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
        waitFor(timeoutMs = 30_000) { heartbeat.readText() != first }
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
