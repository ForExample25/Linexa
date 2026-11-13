package com.fem.linexa.service.workspaceconfig

import com.fem.linexa.domain.hostinfo.HostInfoKey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `linexa.host_info` parsing: the §15 selection surface must be strict —
 * malformed structure fails import exactly like every other config block,
 * and unknown KEY NAMES fail loudly instead of silently thinning what a
 * rootfs receives forever.
 */
class WorkspaceConfigParserHostInfoTest {

    private val parser = WorkspaceConfigParser()

    private fun parse(yaml: String): WorkspaceConfigParser.Result = parser.parse(yaml.toByteArray())

    @Test
    fun `absent block keeps defaults - enabled with runtime default keys`() {
        val result = parse(
            """
            linexa:
              env:
                FOO: bar
            """.trimIndent()
        )
        assertTrue(result is WorkspaceConfigParser.Result.Valid)
        val config = (result as WorkspaceConfigParser.Result.Valid).config
        assertNull(config.hostInfo.enable)
        assertNull(config.hostInfo.keys)
        assertTrue(config.hostInfo.isEnabledByDefault())
        // The default key set lives with the bridge (runtime concern); absence here means defaults.
    }

    @Test
    fun `valid keys parse into enum values at import time`() {
        val result = parse(
            """
            linexa:
              host_info:
                enable: true
                keys:
                  - CPU_CORES
                  - UPTIME_SECONDS
                  - MEMORY_TOTAL_KB
            """.trimIndent()
        )
        val config = (result as WorkspaceConfigParser.Result.Valid).config.hostInfo
        assertNotNull(config.keys)
        assertEquals(
            setOf(HostInfoKey.CPU_CORES, HostInfoKey.UPTIME_SECONDS, HostInfoKey.MEMORY_TOTAL_KB),
            config.keys
        )
    }

    @Test
    fun `unknown key name fails with the valid list`() {
        val result = parse(
            """
            linexa:
              host_info:
                keys:
                  - CPU_COREZZZ
            """.trimIndent()
        )
        assertTrue(result is WorkspaceConfigParser.Result.Invalid)
        val reason = (result as WorkspaceConfigParser.Result.Invalid).reason
        assertTrue(reason.contains("CPU_COREZZZ"))
        assertTrue("must enumerate valid names", reason.contains("UPTIME_SECONDS"))
    }

    @Test
    fun `non-boolean enable and non-list keys fail structurally`() {
        assertTrue(parse("linexa:\n  host_info:\n    enable: yes-please") is WorkspaceConfigParser.Result.Invalid)
        assertTrue(parse("linexa:\n  host_info:\n    keys: not-a-list") is WorkspaceConfigParser.Result.Invalid)
    }

    @Test
    fun `unknown sub-key fails with supported list`() {
        val result = parse(
            """
            linexa:
              host_info:
                secrets: everything
            """.trimIndent()
        )
        assertTrue(result is WorkspaceConfigParser.Result.Invalid)
        assertTrue((result as WorkspaceConfigParser.Result.Invalid).reason.contains("enable"))
    }

    @Test
    fun `enable false parses as explicit opt-out`() {
        val result = parse(
            """
            linexa:
              host_info:
                enable: false
            """.trimIndent()
        )
        val config = (result as WorkspaceConfigParser.Result.Valid).config.hostInfo
        assertEquals(false, config.enable)
        assertTrue(!config.isEnabledByDefault())
    }
}
