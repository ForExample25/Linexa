package com.fem.linexa.service.workspaceconfig

import com.fem.linexa.domain.workspace.BindMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.73 — Roadmap Item 7: schema tests for the `linexa.launch` block
 * (shell/args/bindMounts) added to [WorkspaceConfigParser].
 *
 * Every test here encodes the structured-typed-argv discipline: malformed
 * launch blocks fail hard at import (the same fail-hard stance the rest of
 * the schema already uses), and there is no path from any YAML value to a
 * shell-interpreted command string — the only thing a `launch` block can
 * produce is typed argv fragments that map directly into
 * `PRootLauncher.LaunchSpec`'s argv array.
 */
class WorkspaceConfigParserTest {

    private val parser = WorkspaceConfigParser()

    private fun parseValid(yaml: String): WorkspaceConfig {
        val result = parser.parse(yaml.trimIndent().toByteArray())
        assertTrue("expected Valid, got: $result", result is WorkspaceConfigParser.Result.Valid)
        return (result as WorkspaceConfigParser.Result.Valid).config
    }

    private fun parseInvalid(yaml: String): String {
        val result = parser.parse(yaml.trimIndent().toByteArray())
        assertTrue("expected Invalid, got: $result", result is WorkspaceConfigParser.Result.Invalid)
        return (result as WorkspaceConfigParser.Result.Invalid).reason
    }

    @Test
    fun parsesFullLaunchBlock() {
        val config = parseValid(
            """
            linexa:
              features:
                x11: true
              env:
                FOO: bar
              launch:
                shell: /bin/zsh
                shellArgs:
                  - --login
                  - --no-globalrc
                bindMounts:
                  - host: /sdcard/Downloads
                    guest: /mnt/downloads
            """
        )
        assertEquals("/bin/zsh", config.launch.shell)
        assertEquals(listOf("--login", "--no-globalrc"), config.launch.shellArgs)
        assertEquals(listOf(BindMount("/sdcard/Downloads", "/mnt/downloads")), config.launch.bindMounts)
        assertTrue(config.launch.hasOverrides)
        // the rest of the schema still parses alongside the launch block
        assertEquals(true, config.features["x11"])
        assertEquals("bar", config.env["FOO"])
    }

    @Test
    fun parsesShellOnlyOverride() {
        val config = parseValid(
            """
            linexa:
              launch:
                shell: /bin/ash
            """
        )
        assertEquals("/bin/ash", config.launch.shell)
        assertTrue(config.launch.shellArgs.isEmpty())
        assertTrue(config.launch.hasOverrides)
    }

    @Test
    fun emptyShellArgsKeepsDefaultLoginBehavior() {
        val config = parseValid(
            """
            linexa:
              launch:
                shell: /bin/zsh
                shellArgs: []
            """
        )
        assertEquals("/bin/zsh", config.launch.shell)
        assertTrue("empty shellArgs means the default --login is kept at launch", config.launch.shellArgs.isEmpty())
    }

    @Test
    fun oldSchemaWithoutLaunchKeepsDefaults() {
        val config = parseValid(
            """
            linexa:
              features:
                x11: false
              env:
                FOO: bar
            """
        )
        assertNull("no launch block must deserialize to null shell", config.launch.shell)
        assertTrue(config.launch.shellArgs.isEmpty())
        assertTrue(config.launch.bindMounts.isEmpty())
        assertFalse(config.launch.hasOverrides)
    }

    @Test
    fun emptyLinexaBlockIsValidAndDefault() {
        val config = parseValid("linexa: {}")
        assertNull(config.launch.shell)
        assertFalse(config.launch.hasOverrides)
    }

    @Test
    fun unknownLaunchKeyThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shellArgs: ["--login"]
                bogus: true
            """
        )
        assertTrue(reason.contains("unknown key 'bogus'"))
    }

    @Test
    fun shellArgsWithoutShellThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shellArgs: ["--login"]
            """
        )
        assertTrue(reason.contains("requires 'linexa.launch.shell'"))
    }

    @Test
    fun relativeShellPathThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shell: bin/zsh
            """
        )
        assertTrue(reason.contains("must be an absolute path"))
    }

    @Test
    fun whitespaceInShellPathThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shell: "/bin/my shell"
            """
        )
        assertTrue(reason.contains("whitespace"))
    }

    @Test
    fun nonStringShellArgsEntryThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shell: /bin/sh
                shellArgs: [42]
            """
        )
        assertTrue(reason.contains("non-empty strings"))
    }

    @Test
    fun shellArgsEntryWithWhitespaceThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                shell: /bin/sh
                shellArgs: ["-c some script"]
            """
        )
        assertTrue(reason.contains("single argv tokens"))
    }

    @Test
    fun relativeBindHostThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                bindMounts:
                  - host: sdcard/x
                    guest: /mnt/x
            """
        )
        assertTrue(reason.contains("absolute"))
    }

    @Test
    fun relativeBindGuestThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                bindMounts:
                  - host: /sdcard/x
                    guest: mnt/x
            """
        )
        assertTrue(reason.contains("absolute"))
    }

    @Test
    fun bindEntryWithUnknownKeyThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                bindMounts:
                  - host: /sdcard/x
                    guest: /mnt/x
                    ro: true
            """
        )
        assertTrue(reason.contains("only 'host' and 'guest'"))
        assertTrue(reason.contains("ro"))
    }

    @Test
    fun bindEntryMissingGuestThrowsInvalid() {
        val reason = parseInvalid(
            """
            linexa:
              launch:
                bindMounts:
                  - host: /sdcard/x
            """
        )
        assertTrue(reason.contains("'host' and 'guest'"))
    }

    @Test
    fun bindCountOverCapThrowsInvalid() {
        // Built with exact indentation (no trimIndent — the interpolated
        // lines would be re-indented and the YAML structure broken).
        val binds = (1..WorkspaceConfigParser.MAX_BIND_MOUNTS + 1)
            .joinToString("\n") { "      - host: /sdcard/$it\n        guest: /mnt/$it" }
        val yaml = "linexa:\n  launch:\n    bindMounts:\n$binds\n"

        val result = parser.parse(yaml.toByteArray())
        assertTrue("expected Invalid, got: $result", result is WorkspaceConfigParser.Result.Invalid)
        val reason = (result as WorkspaceConfigParser.Result.Invalid).reason
        assertTrue(
            "must reject over-cap binds, got: $reason",
            reason.contains("maximum of ${WorkspaceConfigParser.MAX_BIND_MOUNTS}")
        )
    }

    @Test
    fun launchSnapshotMapsLaunchBlockIntoRuntimeConfig() {
        val config = parseValid(
            """
            linexa:
              launch:
                shell: /bin/zsh
                shellArgs: ["--login"]
                bindMounts:
                  - host: /sdcard/x
                    guest: /mnt/x
            """
        )
        val snapshot = config.launchSnapshot()
        assertEquals("/bin/zsh", snapshot.launchShell)
        assertEquals(listOf("--login"), snapshot.launchShellArgs)
        assertEquals(listOf(BindMount("/sdcard/x", "/mnt/x")), snapshot.extraBindMounts)
    }

    @Test
    fun launchSnapshotWithoutOverridesIsEmptyRuntimeConfig() {
        val config = parseValid("linexa: {}")
        val snapshot = config.launchSnapshot()
        assertNull(snapshot.launchShell)
        assertTrue(snapshot.launchShellArgs.isEmpty())
        assertTrue(snapshot.extraBindMounts.isEmpty())
    }
}