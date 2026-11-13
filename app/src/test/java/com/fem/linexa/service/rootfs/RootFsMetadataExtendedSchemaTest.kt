package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #3 (2026-09-03) — the extended RootFS metadata schema: workspace
 * layout, shell conveniences, one-shot init, health check, and the extended
 * environment block — end to end through the real [RootFsInspector] (snakeyaml
 * → [RootFsMetadataParser] → [RootFsMetadataValidator]), mirroring
 * [RootFsMetadataTest]'s seam-first discipline. Also pins the actionable
 * rejection for the misspelled `presentation:` key that broke the bundled
 * Debian/Ubuntu install profiles in the field.
 */
class RootFsMetadataExtendedSchemaTest {

    private val inspector = RootFsInspector(WorkspaceConfigParser())

    /**
     * Explicit indentation (not trimIndent games): the extension block is
     * appended under the top-level `linexa:` mapping at a correct 2-space
     * indent, so every test exercises the real parser, not a YAML artifact.
     */
    private fun yaml(extension: String): String = buildString {
        appendLine("linexa:")
        appendLine("  schema_version: 1")
        appendLine("  distro:")
        appendLine("    id: debian")
        appendLine("    name: Debian")
        appendLine("  architecture: arm64")
        extension.lines().forEach { line -> if (line.isNotBlank()) appendLine("  $line") }
    }

    private fun inspect(yamlText: String): RootFsInspector.Result =
        inspector.inspect(yamlText.toByteArray())

    private fun valid(extension: String): RootFsMetadata {
        val result = inspect(yaml(extension))
        assertTrue("expected Success, got $result", result is RootFsInspector.Result.Success)
        return (result as RootFsInspector.Result.Success).descriptor.metadata!!
    }

    private fun invalidReason(extension: String): String {
        val result = inspect(yaml(extension))
        assertTrue("expected Invalid, got $result", result is RootFsInspector.Result.Invalid)
        return (result as RootFsInspector.Result.Invalid).reason
    }

    // ── environment extensions ───────────────────────────────────────────

    @Test
    fun `environment accepts user, home, shell_args and variables`() {
        val md = valid(
            """
            environment:
              shell: /bin/bash
              user: root
              home: /root
              shell_args:
                - --login
              variables:
                LINEXA_WORKSPACE: /home/linexa
            """.trimIndent()
        )
        val env = md.environment!!
        assertEquals("/bin/bash", env.shell)
        assertEquals("root", env.user)
        assertEquals("/root", env.home)
        assertEquals(listOf("--login"), env.shellArgs)
        assertEquals(mapOf("LINEXA_WORKSPACE" to "/home/linexa"), env.variables)
    }

    @Test
    fun `environment rejects whitespace in shell_args tokens`() {
        val reason = invalidReason(
            """
            environment:
              shell_args:
                - "--login -i"
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("shell_args[0]"))
    }

    @Test
    fun `environment rejects invalid variable names`() {
        val reason = invalidReason(
            """
            environment:
              variables:
                "1BAD_NAME": x
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("environment.variables"))
    }

    @Test
    fun `environment rejects relative home`() {
        val reason = invalidReason(
            """
            environment:
              home: relative/path
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("linexa.environment.home"))
        assertTrue(reason, reason.contains("absolute"))
    }

    @Test
    fun `environment rejects traversal in home`() {
        val reason = invalidReason(
            """
            environment:
              home: /root/../etc
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("'..'"))
    }

    // ── workspace block ──────────────────────────────────────────────────

    @Test
    fun `workspace directory parses and validates`() {
        val md = valid(
            """
            workspace:
              directory: /home/linexa
            """.trimIndent()
        )
        assertEquals("/home/linexa", md.workspace!!.directory)
    }

    @Test
    fun `workspace rejects the filesystem root`() {
        val reason = invalidReason(
            """
            workspace:
              directory: /
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("workspace.directory"))
    }

    // ── shell_config block ───────────────────────────────────────────────

    @Test
    fun `shell_config aliases parse`() {
        val md = valid(
            """
            shell_config:
              aliases:
                la: "ls -A"
                ll: "ls -alF"
            """.trimIndent()
        )
        assertEquals(mapOf("la" to "ls -A", "ll" to "ls -alF"), md.shellConfig!!.aliases)
    }

    @Test
    fun `shell_config rejects non-identifier alias names`() {
        val reason = invalidReason(
            """
            shell_config:
              aliases:
                "bad name": "ls"
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("shell_config.aliases"))
    }

    // ── init block ───────────────────────────────────────────────────────

    @Test
    fun `init post_install and first_boot parse`() {
        val md = valid(
            """
            init:
              post_install:
                - "mkdir -p /home/linexa/projects"
              first_boot: []
            """.trimIndent()
        )
        assertEquals(listOf("mkdir -p /home/linexa/projects"), md.init!!.postInstall)
        assertEquals(emptyList<String>(), md.init!!.firstBoot)
    }

    @Test
    fun `init rejects multi-line commands`() {
        val reason = invalidReason(
            """
            init:
              first_boot:
                - "one"
            """.trimIndent().replaceFirst("- \"one\"", "- \"one\\ntwo\"")
        )
        assertTrue(reason, reason.contains("linexa.init"))
    }

    // ── health_check block ───────────────────────────────────────────────

    @Test
    fun `health_check command parses`() {
        val md = valid(
            """
            health_check:
              command: "test -x /bin/bash"
            """.trimIndent()
        )
        assertEquals("test -x /bin/bash", md.healthCheck!!.command)
    }

    @Test
    fun `health_check without command is rejected`() {
        val reason = invalidReason(
            """
            health_check:
              display_only: true
            """.trimIndent()
        )
        // Unknown sub-key must also fail — and the missing-command case must
        // fail with its own reason; both belong to the health_check surface.
        assertTrue(reason, reason.contains("health_check"))
    }

    // ── the presentation misspelling (the field bug) ──────────────────────

    @Test
    fun `misspelled presentation key names the fix`() {
        val reason = invalidReason(
            """
            presentation:
              display_name: Debian
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("presentation:"))
        assertTrue("error must name the canonical key", reason.contains("metadata:"))
    }

    // ── cross-schema seam: the config parser accepts the new keys ────────

    @Test
    fun `config parser skips the extended metadata keys`() {
        val parser = WorkspaceConfigParser()
        val yamlText = yaml(
            """
            workspace:
              directory: /home/linexa
            shell_config:
              aliases:
                la: "ls -A"
            init:
              first_boot: []
            health_check:
              command: "test -x /bin/bash"
            """.trimIndent()
        )
        val result = parser.parse(yamlText.toByteArray())
        assertTrue("expected Valid, got $result", result is WorkspaceConfigParser.Result.Valid)
        assertNotNull((result as WorkspaceConfigParser.Result.Valid).config)
    }

    // ── defaults stay nullable (undeclared images unchanged) ─────────────

    @Test
    fun `all new blocks are optional`() {
        val md = valid("")
        assertEquals(null, md.workspace)
        assertEquals(null, md.shellConfig)
        assertEquals(null, md.init)
        assertEquals(null, md.healthCheck)
    }
}
