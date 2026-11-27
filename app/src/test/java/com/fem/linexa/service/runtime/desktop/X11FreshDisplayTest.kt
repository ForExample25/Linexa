package com.fem.linexa.service.runtime.desktop

import com.fem.linexa.service.runtime.RuntimeTrace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Second-launch regression: a retry after a failed/killed start must sweep the
 * stale X socket and lock file of the display the workspace already holds.
 */
class X11FreshDisplayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plantStale(root: File, display: String) {
        val socket = X11SocketReadiness.hostSocketFile(root.absolutePath, display)
        val lock = X11SocketReadiness.hostLockFile(root.absolutePath, display)
        socket.parentFile!!.mkdirs()
        socket.writeText("")
        lock.writeText("12345")
    }

    @Test
    fun `plain re-allocation returns the recorded display without sweeping - the old second-launch gap`() {
        val root = tmp.newFolder()
        val manager = X11SessionManager(RuntimeTrace())
        assertEquals(":1", manager.allocateDisplay("ws", root.absolutePath))
        plantStale(root, ":1")
        assertEquals(":1", manager.allocateDisplay("ws", root.absolutePath))
        assertTrue(
            "allocateDisplay must stay a pure reattach lookup (it is used to reattach)",
            X11SocketReadiness.hostLockFile(root.absolutePath, ":1").exists()
        )
    }

    @Test
    fun `fresh allocation sweeps stale socket and lock even when the display is already recorded`() {
        val root = tmp.newFolder()
        val manager = X11SessionManager(RuntimeTrace())
        manager.allocateDisplay("ws", root.absolutePath)
        plantStale(root, ":1")
        assertEquals(":1", manager.allocateFreshDisplay("ws", root.absolutePath))
        assertFalse(X11SocketReadiness.hostSocketFile(root.absolutePath, ":1").exists())
        assertFalse(X11SocketReadiness.hostLockFile(root.absolutePath, ":1").exists())
    }

    @Test
    fun `fresh allocation does not disturb another workspace's display`() {
        val root = tmp.newFolder()
        val manager = X11SessionManager(RuntimeTrace())
        assertEquals(":1", manager.allocateDisplay("ws-a", root.absolutePath))
        assertEquals(":2", manager.allocateDisplay("ws-b", root.absolutePath))
        plantStale(root, ":1")
        plantStale(root, ":2")
        assertEquals(":2", manager.allocateFreshDisplay("ws-b", root.absolutePath))
        assertTrue("ws-a's files must be untouched", X11SocketReadiness.hostLockFile(root.absolutePath, ":1").exists())
        assertFalse(X11SocketReadiness.hostLockFile(root.absolutePath, ":2").exists())
        assertEquals(":1", manager.displayFor("ws-a"))
    }

    @Test
    fun `repeated fresh starts keep a stable display number`() {
        val root = tmp.newFolder()
        val manager = X11SessionManager(RuntimeTrace())
        repeat(5) { assertEquals(":1", manager.allocateFreshDisplay("ws", root.absolutePath)) }
    }
}
