package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.73 — Roadmap Item 7: `RuntimeConfig` (persisted on `WorkspaceMetadata`
 * as JSON in a single Room column via [RuntimeConfigConverter]) must stay
 * backward-compatible with workspaces created before the launch fields
 * existed. The Room schema/columns do not change — only the JSON inside the
 * column — so old rows must decode cleanly with the new fields absent,
 * and new rows must round-trip their overrides.
 */
class RuntimeConfigSerializationTest {

    private val converter = RuntimeConfigConverter()

    @Test
    fun legacyJsonDeserializesWithDefaults() {
        // The exact JSON a pre-§1.73 RuntimeConfigConverter would have
        // persisted: only bindMounts + environmentVars.
        val legacy = """{"bindMounts":[],"environmentVars":{}}"""

        val config = converter.toRuntimeConfig(legacy)

        assertNull("legacy rows must deserialize launchShell as null", config.launchShell)
        assertTrue(config.launchShellArgs.isEmpty())
        assertTrue(config.extraBindMounts.isEmpty())
    }

    @Test
    fun legacyJsonWithOldBindMountsStillDeserializes() {
        // A legacy row where the (previously unused) bindMounts list carried
        // "host:guest" strings must still decode with those strings intact.
        val legacy = """{"bindMounts":["/a:/b"],"environmentVars":{"FOO":"bar"}}"""

        val config = converter.toRuntimeConfig(legacy)

        assertEquals(listOf("/a:/b"), config.bindMounts)
        assertEquals("bar", config.environmentVars["FOO"])
        assertNull(config.launchShell)
        assertTrue(config.extraBindMounts.isEmpty())
    }

    @Test
    fun newFieldsRoundTripIncludingOverrides() {
        val config = RuntimeConfig(
            launchShell = "/bin/zsh",
            launchShellArgs = listOf("--login", "--no-globalrc"),
            extraBindMounts = listOf(BindMount("/sdcard/x", "/mnt/x"))
        )

        val json = converter.fromRuntimeConfig(config)
        val decoded = converter.toRuntimeConfig(json)

        assertEquals(config, decoded)
    }

    @Test
    fun defaultsRoundTripToStableJson() {
        val empty = converter.fromRuntimeConfig(RuntimeConfig())
        val decoded = converter.toRuntimeConfig(empty)
        assertNull(decoded.launchShell)
        assertTrue(decoded.launchShellArgs.isEmpty())
        assertTrue(decoded.extraBindMounts.isEmpty())
    }
}