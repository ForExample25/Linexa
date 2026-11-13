package com.fem.linexa.service.runtime.desktop

import com.fem.linexa.service.runtime.RuntimeTrace
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Transformation spec §6/§13 — the runtime-owned X11 lifecycle:
 * workspace-scoped display allocation, host-side socket readiness, and the
 * observable [X11SessionState] machine.
 */
class X11SessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── display allocation (§7 — workspace-owned resources) ──

    @Test
    fun `first allocation is the historical display 1`() {
        val manager = X11SessionManager(RuntimeTrace())
        assertEquals(":1", manager.allocateDisplay("ws-a", tmp.newFolder().absolutePath))
    }

    @Test
    fun `each workspace gets its own display`() {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder().absolutePath
        val a = manager.allocateDisplay("ws-a", root)
        val b = manager.allocateDisplay("ws-b", root)
        assertEquals(":1", a)
        assertEquals(":2", b)
        assertTrue("displays must differ across workspaces", a != b)
    }

    @Test
    fun `allocation is idempotent per workspace`() {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder().absolutePath
        assertEquals(manager.allocateDisplay("ws-a", root), manager.allocateDisplay("ws-a", root))
    }

    @Test
    fun `released display is reused lowest-first`() {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder().absolutePath
        manager.allocateDisplay("ws-a", root)
        manager.allocateDisplay("ws-b", root)
        manager.releaseDisplay("ws-a")
        assertEquals(":1", manager.allocateDisplay("ws-c", root))
    }

    @Test
    fun `stale socket of the allocated display is cleaned host-side`() {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder()
        // Simulate a hard-killed previous desktop: the socket file is left behind.
        val stale = File(root, "tmp/.X11-unix/X1")
        stale.parentFile.mkdirs()
        stale.writeText("stale")
        assertEquals(":1", manager.allocateDisplay("ws-a", root.absolutePath))
        assertFalse("allocation must remove the stale socket so the new server can bind", stale.exists())
    }

    // ── host-side socket readiness (§13 — no magic delays) ──

    @Test
    fun `awaitSocketReady returns true as soon as the socket file exists`() = runTest {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder()
        val socket = File(root, "tmp/.X11-unix/X1")
        socket.parentFile.mkdirs()
        // Create the socket mid-flight (as Xvnc would) after a short delay.
        Thread {
            Thread.sleep(150)
            socket.createNewFile()
        }.start()
        assertTrue(manager.awaitSocketReady(root.absolutePath, ":1", timeoutMs = 5_000))
    }

    @Test
    fun `awaitSocketReady times out deterministically when the socket never appears`() = runTest {
        val manager = X11SessionManager(RuntimeTrace())
        val root = tmp.newFolder()
        assertFalse(manager.awaitSocketReady(root.absolutePath, ":7", timeoutMs = 300))
    }

    @Test
    fun `host socket path maps into the workspace rootfs`() {
        val f = X11SocketReadiness.hostSocketFile("/data/rootfs", ":1")
        assertEquals("/data/rootfs/tmp/.X11-unix/X1", f.absolutePath)
        assertEquals("/tmp/.X11-unix/X9", X11SocketReadiness.guestSocketPath(":9"))
    }

    @Test
    fun `stale socket removal is idempotent`() {
        val root = tmp.newFolder().absolutePath
        assertTrue(X11SocketReadiness.removeStaleSocket(root, ":3"))
        val socket = File(root, "tmp/.X11-unix/X3")
        socket.parentFile.mkdirs()
        socket.writeText("x")
        assertTrue(X11SocketReadiness.removeStaleSocket(root, ":3"))
        assertFalse(socket.exists())
    }

    @Test
    fun `stale display cleanup removes the X lock file too`() {
        // 2026-09-05: a SIGKILLed predecessor leaves /tmp/.X{n}-lock behind;
        // the new X server's display-number check can read its stale pid as
        // alive (pid reuse on Android) and abort with "Server is already
        // active for display" — exit code 1. The cleanup must cover both
        // display artifacts, not only the unix socket.
        val root = tmp.newFolder().absolutePath
        val lock = File(root, "tmp/.X5-lock")
        lock.parentFile.mkdirs()
        lock.writeText("  4242\n")
        val socket = File(root, "tmp/.X11-unix/X5")
        socket.parentFile.mkdirs()
        socket.createNewFile()

        assertTrue(X11SocketReadiness.removeStaleSocket(root, ":5"))

        assertFalse("the lock file must be removed", lock.exists())
        assertFalse("the socket must be removed", socket.exists())
    }

    @Test
    fun `lock file paths map into the workspace rootfs`() {
        assertEquals("/tmp/.X1-lock", X11SocketReadiness.guestLockFilePath(":1"))
        assertEquals(
            "/data/rootfs/tmp/.X9-lock",
            X11SocketReadiness.hostLockFile("/data/rootfs", ":9").absolutePath
        )
    }

    // ── state machine (observable, §29) ──

    @Test
    fun `state transitions are recorded and readable`() {
        val manager = X11SessionManager(RuntimeTrace())
        assertEquals(X11SessionState.IDLE, manager.stateFor("ws-a"))
        manager.setState("ws-a", X11SessionState.X11_STARTING)
        manager.setState("ws-a", X11SessionState.X11_READY)
        assertEquals(X11SessionState.X11_READY, manager.stateFor("ws-a"))
        assertTrue(manager.states.value["ws-a"] == X11SessionState.X11_READY)
        manager.clearState("ws-a")
        assertEquals(X11SessionState.IDLE, manager.stateFor("ws-a"))
        assertNull(manager.states.value["ws-a"])
    }

    // ── termux-x11 contract (pure pieces) ──

    @Test
    fun `helper daemon command carries the workspace display`() {
        assertEquals("termux-x11 :2", TermuxX11.helperDaemonCommand(":2"))
    }

    @Test
    fun `provider ids resolve fail-open`() {
        assertEquals(X11Provider.TERMUX_X11, X11Provider.fromId("termux-x11"))
        assertEquals(X11Provider.XVNC, X11Provider.fromId("xvnc"))
        assertNull(X11Provider.fromId("nope"))
        assertNull(X11Provider.fromId(null))
    }
}
