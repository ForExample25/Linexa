package com.fem.linexa.service.runtime.container.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the guest `sudo` protocol — no Android, no filesystem. */
class RootExecProtocolTest {

    @Test
    fun `safe shell words stay bare`() {
        assertEquals("htop", RootExecProtocol.shellQuote("htop"))
        assertEquals("apt", RootExecProtocol.shellQuote("apt"))
        assertEquals("-y", RootExecProtocol.shellQuote("-y"))
        assertEquals("/usr/bin/env", RootExecProtocol.shellQuote("/usr/bin/env"))
        assertEquals("KEY=value", RootExecProtocol.shellQuote("KEY=value"))
        assertTrue(RootExecProtocol.isSafeShellWord("ubuntu:22.04"))
        assertTrue(RootExecProtocol.isSafeShellWord("user@host"))
    }

    @Test
    fun `shellQuote wraps everything that could be reinterpreted`() {
        assertEquals("''", RootExecProtocol.shellQuote(""))
        assertEquals("'a b'", RootExecProtocol.shellQuote("a b"))
        assertEquals("'*'", RootExecProtocol.shellQuote("*"))
        assertEquals("'\$HOME'", RootExecProtocol.shellQuote("\$HOME"))
        assertEquals("'line\nbreak'", RootExecProtocol.shellQuote("line\nbreak"))
        assertEquals("'a;reboot'", RootExecProtocol.shellQuote("a;reboot"))
        assertEquals("'a&&b'", RootExecProtocol.shellQuote("a&&b"))
        assertEquals("'~'", RootExecProtocol.shellQuote("~"))
        assertEquals("'#x'", RootExecProtocol.shellQuote("#x"))
        assertFalse(RootExecProtocol.isSafeShellWord("awk '{print \$1}'"))
    }

    @Test
    fun `shellQuote escapes embedded single quotes exactly once`() {
        assertEquals("'a'\\''b'", RootExecProtocol.shellQuote("a'b"))
        assertEquals("''\\'''", RootExecProtocol.shellQuote("'"))
        assertEquals("'a'\\'''\\''b'", RootExecProtocol.shellQuote("a''b"))
        assertEquals("'a b'\\''c'", RootExecProtocol.shellQuote("a b'c"))
    }

    @Test
    fun `shellJoin keeps argv boundaries and the package classifier's first token`() {
        assertEquals("apt install -y htop", RootExecProtocol.shellJoin(listOf("apt", "install", "-y", "htop")))
        assertEquals("echo 'it'\\''s fine'", RootExecProtocol.shellJoin(listOf("echo", "it's fine")))
        assertEquals("dpkg --configure -a", RootExecProtocol.shellJoin(listOf("dpkg", "--configure", "-a")))
    }

    @Test
    fun `exit codes map to sudo conventions`() {
        assertEquals(0, RootExecProtocol.normalizeExitCode(0))
        assertEquals(7, RootExecProtocol.normalizeExitCode(7))
        assertEquals(255, RootExecProtocol.normalizeExitCode(255))
        assertEquals(RootExecProtocol.EXIT_TIMEOUT, RootExecProtocol.normalizeExitCode(-1))
        assertEquals(RootExecProtocol.EXIT_BRIDGE_ERROR, RootExecProtocol.normalizeExitCode(256))
        assertEquals(RootExecProtocol.EXIT_BRIDGE_ERROR, RootExecProtocol.normalizeExitCode(-2))
    }

    @Test
    fun `status parsing accepts only 0 through 255`() {
        assertEquals(0, RootExecProtocol.parseStatus("0\n"))
        assertEquals(7, RootExecProtocol.parseStatus(" 7 "))
        assertEquals(255, RootExecProtocol.parseStatus("255\n"))
        assertEquals(null, RootExecProtocol.parseStatus(""))
        assertEquals(null, RootExecProtocol.parseStatus("256"))
        assertEquals(null, RootExecProtocol.parseStatus("7x"))
        assertEquals(null, RootExecProtocol.parseStatus("-1"))
    }

    @Test
    fun `request names are strictly prefixed`() {
        assertTrue(RootExecProtocol.isRequestName(".linexa-root-req.abc"))
        assertFalse(RootExecProtocol.isRequestName(".linexa-root-req."))
        assertFalse(RootExecProtocol.isRequestName("linexa-root-req.abc"))
        assertFalse(RootExecProtocol.isRequestName(".linexa-root-req"))
    }

    @Test
    fun `marker starts with its version banner`() {
        val marker = RootExecProtocol.markerContents("ws", "token")
        assertTrue(marker.startsWith(RootExecProtocol.MARKER_BANNER + "\n"))
        assertTrue(marker.contains("workspace=ws"))
        assertTrue(marker.contains("token=token"))
    }
}
