package com.fem.linexa.ui.screens.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Modes (2026-08) — the [DesktopPortRegistry], exercised on the real
 * JVM loopback (it has no Android dependencies by design, so the JVM test is
 * the real thing, not a fake).
 */
class DesktopPortRegistryTest {

    @Test
    fun unknownWorkspaceFallsBackToTheHistoricalDefault() {
        // The pre-Desktop-Modes constant keeps a workspace whose desktop was
        // started before the registry existed reconnecting as it always did.
        assertEquals(5901, DesktopPortRegistry().vncPortFor("ws-a"))
    }

    @Test
    fun recordThenReadRoundTripsPerWorkspace() {
        val registry = DesktopPortRegistry()
        registry.recordVnc("ws-a", 5901)
        registry.recordVnc("ws-b", 5902)
        assertEquals(5901, registry.vncPortFor("ws-a"))
        assertEquals(5902, registry.vncPortFor("ws-b"))
        // And the two records are independent — that is the entire point of
        // replacing the shared constant.
        assertTrue(registry.vncPortFor("ws-a") != registry.vncPortFor("ws-b"))
    }

    @Test
    fun clearRemovesOnlyThatWorkspace() {
        val registry = DesktopPortRegistry()
        registry.recordVnc("ws-a", 5901)
        registry.recordVnc("ws-b", 5902)
        registry.clear("ws-a")
        assertEquals(5901, registry.vncPortFor("ws-a")) // default again
        assertEquals(5902, registry.vncPortFor("ws-b")) // untouched
    }

    @Test
    fun webPortsAreTrackedSeparatelyFromVncPorts() {
        val registry = DesktopPortRegistry()
        registry.recordWeb("ws-a", 8080)
        assertEquals(8080, registry.webPortFor("ws-a", default = 8081))
        // VNC's record is unaffected by the web record — and vice versa.
        assertEquals(DesktopPortRegistry.DEFAULT_VNC_PORT, registry.vncPortFor("ws-a"))
        assertEquals(8081, registry.webPortFor("ws-b", default = 8081))
    }

    @Test
    fun allocatePrefersTheHistoricalPortWhenFree() {
        // Reserve-then-release the preferred port: binding first proves this
        // test owns a bindable 5901 (not one the host already holds), and
        // closing it — a listening socket with no accepted connections leaves
        // no TIME_WAIT — makes it free again. The allocator must then honour
        // the preference exactly.
        val probe = java.net.ServerSocket(
            DesktopPortRegistry.DEFAULT_VNC_PORT, 1, java.net.InetAddress.getByName("127.0.0.1")
        )
        val boundPort = probe.localPort
        probe.close()
        assertEquals(boundPort, DesktopPortRegistry.findFreeLoopbackPort(boundPort))
    }

    @Test
    fun allocateSkipsABusyPortAndRecordsWhereItLanded() {
        java.net.ServerSocket(DesktopPortRegistry.DEFAULT_VNC_PORT, 1, java.net.InetAddress.getByName("127.0.0.1")).use { busy ->
            val next = DesktopPortRegistry.findFreeLoopbackPort(busy.localPort)
            assertTrue("must not pick the busy port", next != busy.localPort)
            assertTrue(next in (busy.localPort + 1)..(busy.localPort + DesktopPortRegistry.PORT_SCAN_LIMIT))
            val registry = DesktopPortRegistry()
            val allocated = registry.allocateVnc("ws-a", preferred = busy.localPort)
            assertEquals(next, allocated)
            assertEquals(next, registry.vncPortFor("ws-a", default = 5901))
        }
    }

    @Test
    fun nonConcretePortsAreRejectedNotSilentlyReinterpreted() {
        // ServerSocket(0) means "pick ephemeral" — a start of 0 would record a
        // number Xvnc was never told. Refuse instead of reinterpret.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopPortRegistry.findFreeLoopbackPort(0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DesktopPortRegistry.findFreeLoopbackPort(-1)
        }
    }
}
