package com.fem.linexa.service.rootfs

import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.118 — the RootFS metadata schema, end to end through the real
 * [RootFsInspector] (snakeyaml → [RootFsMetadataParser] →
 * [RootFsMetadataValidator] → [RootFsDescriptor]).
 *
 * Driving the real inspector rather than the parser in isolation is
 * deliberate: the §1.19/§1.115 failure mode this project keeps hitting is a
 * correct unit that nothing calls correctly, and the two things most likely to
 * break here are the *seam* between the two schemas that share one `linexa:`
 * mapping and the precedence of a declaration over the probe. Both only exist
 * at the inspector level.
 */
class RootFsMetadataTest {

    private val inspector = RootFsInspector(WorkspaceConfigParser())

    private fun inspect(
        yaml: String,
        format: RootFsArchiveFormat? = null,
        abis: List<String> = emptyList()
    ): RootFsInspector.Result = inspector.inspect(yaml.toByteArray(), format, abis)

    private fun success(yaml: String, format: RootFsArchiveFormat? = null, abis: List<String> = emptyList()) =
        inspect(yaml, format, abis) as? RootFsInspector.Result.Success
            ?: error("expected Success, got ${inspect(yaml, format, abis)}")

    private fun invalidReason(yaml: String): String {
        val result = inspect(yaml)
        assertTrue("expected Invalid, got $result", result is RootFsInspector.Result.Invalid)
        return (result as RootFsInspector.Result.Invalid).reason
    }

    // ── the happy path ──────────────────────────────────────────────────

    @Test
    fun fullDeclarationParsesEveryBlock() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian GNU/Linux
                version: 13
                codename: trixie
              architecture: arm64
              rootfs:
                type: desktop
                compressed_format: tar.zst
              desktop:
                enabled: true
                type: xfce
                session: /usr/bin/startxfce4
              package_manager:
                type: apt
              environment:
                shell: /bin/bash
              metadata:
                display_name: Debian 13 XFCE
                description: A desktop image.
            """.trimIndent()
        )

        val md = assertNotNull(result.descriptor.metadata).let { result.descriptor.metadata!! }
        assertEquals(1, md.schemaVersion)
        assertEquals("debian", md.distro.id)
        assertEquals("Debian GNU/Linux", md.distro.name)
        // `version: 13` is an unquoted YAML Int — normalized to text, not rejected.
        assertEquals("13", md.distro.version)
        assertEquals("trixie", md.distro.codename)
        assertEquals(DeclaredArchitecture.ARM64, md.architecture)
        assertEquals("arm64-v8a", md.architecture.abi)
        assertEquals("desktop", md.rootfs?.type)
        assertEquals(true, md.desktop?.enabled)
        assertEquals("xfce", md.desktop?.type)
        assertEquals("/usr/bin/startxfce4", md.desktop?.session)
        assertEquals("apt", md.packageManager?.type)
        assertEquals("/bin/bash", md.environment?.shell)
        assertEquals("Debian 13 XFCE", md.presentation?.displayName)
        assertTrue("a coherent declaration must produce no warnings", result.descriptor.warnings.isEmpty())
    }

    @Test
    fun architectureAliasesAllResolve() {
        listOf("arm64", "aarch64", "ARM64-V8A", "armv8") .forEach { alias ->
            assertEquals(DeclaredArchitecture.ARM64, DeclaredArchitecture.fromYaml(alias))
        }
        listOf("arm", "armv7", "armhf", "armeabi-v7a").forEach { alias ->
            assertEquals(DeclaredArchitecture.ARM32, DeclaredArchitecture.fromYaml(alias))
        }
        assertEquals(DeclaredArchitecture.X86_64, DeclaredArchitecture.fromYaml("amd64"))
        assertEquals(DeclaredArchitecture.X86, DeclaredArchitecture.fromYaml("i686"))
        assertNull(DeclaredArchitecture.fromYaml("sparc"))
    }

    // ── coexistence with the configuration schema (the shared mapping) ──

    @Test
    fun metadataAndConfigurationCoexistInOneFile() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: alpine
                name: Alpine Linux
              architecture: aarch64
              features:
                ssh: true
              env:
                MY_VAR: hello
              launch:
                shell: /bin/ash
            """.trimIndent()
        )

        assertEquals("alpine", result.descriptor.metadata?.distro?.id)
        assertEquals(mapOf("ssh" to true), result.config.features)
        assertEquals(mapOf("MY_VAR" to "hello"), result.config.env)
        assertEquals("/bin/ash", result.config.launch.shell)
    }

    @Test
    fun configurationOnlyFileIsUndeclaredNotAnError() {
        val result = success(
            """
            linexa:
              features:
                audio: false
            """.trimIndent()
        )
        assertFalse("a config-only file declares no metadata", result.descriptor.isDeclared)
        assertNull(result.descriptor.declaredIdentity())
        assertEquals(mapOf("audio" to false), result.config.features)
    }

    @Test
    fun metadataOnlyFileYieldsEmptyConfig() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: arch
                name: Arch Linux ARM
              architecture: arm64
            """.trimIndent()
        )
        assertTrue(result.config.features.isEmpty())
        assertTrue(result.config.env.isEmpty())
        assertFalse(result.config.launch.hasOverrides)
        assertTrue(result.descriptor.isDeclared)
    }

    @Test
    fun theConfigParserAloneAcceptsMetadataKeys() {
        // The regression this guards: before §1.118 the config parser rejected
        // every unknown key under `linexa:`, so an archive carrying metadata
        // would have failed the import outright at the scan pass.
        val result = WorkspaceConfigParser().parse(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
            """.trimIndent().toByteArray()
        )
        assertTrue("config parser must skip metadata keys, got $result", result is WorkspaceConfigParser.Result.Valid)
    }

    @Test
    fun aTrulyUnknownKeyIsStillRejected() {
        val reason = invalidReason(
            """
            linexa:
              nonsense: 1
            """.trimIndent()
        )
        assertTrue("reason must name the key: $reason", reason.contains("nonsense"))
    }

    // ── declaration outranks the probe ──────────────────────────────────

    @Test
    fun declaredIdentityIsMarkedDetectedAndMapsToProbeVocabulary() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: ubuntu
                name: Ubuntu
                version: "24.04"
              architecture: aarch64
            """.trimIndent()
        )
        val identity = result.descriptor.declaredIdentity()!!
        assertEquals(RootFsProbe.DistroId.UBUNTU, identity.distroId)
        assertEquals("Ubuntu", identity.distroName)
        assertEquals("24.04", identity.version)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, identity.arch)
        assertTrue("a declaration is a stronger signal than a probe", identity.detected)
    }

    @Test
    fun unknownDistroIdDegradesToOtherWithoutFailing() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: void
                name: Void Linux
              architecture: arm64
            """.trimIndent()
        )
        val identity = result.descriptor.declaredIdentity()!!
        assertEquals(RootFsProbe.DistroId.OTHER, identity.distroId)
        // The human-readable name still comes from the declaration, so the UI
        // shows "Void Linux" and not "Linux".
        assertEquals("Void Linux", identity.distroName)
    }

    @Test
    fun rollingReleaseWithNoVersionReportsUnknown() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: arch
                name: Arch Linux ARM
              architecture: arm64
            """.trimIndent()
        )
        assertEquals("unknown", result.descriptor.declaredIdentity()!!.version)
    }

    // ── warnings: declaration vs reality (never fatal) ──────────────────

    @Test
    fun wrongCompressedFormatWarnsButInstalls() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              rootfs:
                compressed_format: tar.gz
            """.trimIndent(),
            format = RootFsArchiveFormat.TAR_ZST
        )
        assertEquals(1, result.descriptor.warnings.size)
        assertTrue(result.descriptor.warnings.single().contains("tar.zst"))
    }

    @Test
    fun matchingCompressedFormatDeclarationDoesNotWarn() {
        listOf("tar.zst", "zst", ".zstd", "TZST").forEach { spelling ->
            val result = success(
                """
                linexa:
                  schema_version: 1
                  distro:
                    id: debian
                    name: Debian
                  architecture: arm64
                  rootfs:
                    compressed_format: "$spelling"
                """.trimIndent(),
                format = RootFsArchiveFormat.TAR_ZST
            )
            assertTrue("'$spelling' must match tar.zst: ${result.descriptor.warnings}", result.descriptor.warnings.isEmpty())
        }
    }

    @Test
    fun unsupportedDeclaredArchitectureWarnsButInstalls() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: x86_64
            """.trimIndent(),
            abis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals(1, result.descriptor.warnings.size)
        assertTrue(result.descriptor.warnings.single().contains("x86_64"))
        assertNotNull("a wrong-arch declaration is a warning, not a rejection", result.descriptor.metadata)
    }

    @Test
    fun emptyAbiListSkipsTheArchitectureCheck() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: x86
            """.trimIndent(),
            abis = emptyList()
        )
        assertTrue(result.descriptor.warnings.isEmpty())
    }

    // ── rejections: a declaration that cannot be trusted ────────────────

    @Test
    fun missingSchemaVersionIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              distro:
                id: debian
                name: Debian
              architecture: arm64
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("schema_version"))
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 99
              distro:
                id: debian
                name: Debian
              architecture: arm64
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("unsupported") && reason.contains("99"))
    }

    @Test
    fun missingDistroFieldsAreRejected() {
        assertTrue(
            invalidReason(
                """
                linexa:
                  schema_version: 1
                  architecture: arm64
                """.trimIndent()
            ).contains("distro")
        )
        assertTrue(
            invalidReason(
                """
                linexa:
                  schema_version: 1
                  distro:
                    id: debian
                  architecture: arm64
                """.trimIndent()
            ).contains("distro.name")
        )
    }

    @Test
    fun unknownArchitectureIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: sparc
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("sparc"))
    }

    @Test
    fun enabledDesktopWithoutTypeIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              desktop:
                enabled: true
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("desktop.type"))
    }

    @Test
    fun disabledDesktopNeedsNoType() {
        val result = success(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              desktop:
                enabled: false
            """.trimIndent()
        )
        assertEquals(false, result.descriptor.metadata?.desktop?.enabled)
    }

    @Test
    fun nonBooleanDesktopEnabledIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              desktop:
                enabled: "yes"
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("boolean"))
    }

    @Test
    fun unsupportedPackageManagerTypeIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              package_manager:
                type: emerge
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("emerge"))
    }

    @Test
    fun packageManagerCommandsBlockIsRejectedWithAnExplanation() {
        // §1.118 explicitly refuses per-operation command strings; the error
        // has to teach, because a tiny_container-style file will carry them.
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              package_manager:
                type: apt
                commands:
                  install: apt-get install -y
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("commands"))
        assertTrue("must explain why, not just refuse: $reason", reason.contains("parsers"))
    }

    @Test
    fun relativeDeclaredShellIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              environment:
                shell: bash
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("absolute"))
    }

    @Test
    fun shellAndSessionRejectShellMetacharactersAndWhitespace() {
        val hostile = listOf(
            "/bin/sh; rm -rf /",
            "/bin/sh && id",
            "/bin/sh | cat",
            "/bin/sh \$(id)",
            "/bin/sh `id`",
            "/bin/my shell",
            "/bin/sh*"
        )
        hostile.forEach { value ->
            val reason = invalidReason(
                """
                linexa:
                  schema_version: 1
                  distro:
                    id: debian
                    name: Debian
                  architecture: arm64
                  environment:
                    shell: "$value"
                """.trimIndent()
            )
            assertTrue("'$value' must be rejected, got: $reason", reason.contains("environment.shell"))
        }
    }

    @Test
    fun sessionRejectsShellMetacharacters() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              desktop:
                enabled: true
                type: xfce
                session: "startxfce4; id"
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("desktop.session"))
    }

    @Test
    fun unknownKeyInsideAMetadataBlockNamesTheFullPath() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
                nickname: deb
              architecture: arm64
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("linexa.distro") && reason.contains("nickname"))
    }

    @Test
    fun nonMappingMetadataBlockIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro: debian
              architecture: arm64
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("mapping"))
    }

    @Test
    fun oversizedFileIsRejectedBeforeParsing() {
        val padding = "#".repeat(WorkspaceConfigParser.MAX_CONFIG_BYTES + 1)
        val result = inspector.inspect(padding.toByteArray())
        assertTrue(result is RootFsInspector.Result.Invalid)
        assertTrue((result as RootFsInspector.Result.Invalid).reason.contains("exceeds"))
    }

    @Test
    fun emptyFileIsRejectedByTheConfigSchemaAsBefore() {
        // Unchanged §1.26 behaviour — the metadata schema must not have
        // loosened it.
        val result = inspector.inspect(ByteArray(0))
        assertTrue(result is RootFsInspector.Result.Invalid)
    }

    // ── §1.120: declared shortcuts ──────────────────────────────────────

    /**
     * A minimal valid declaration plus a `shortcuts:` list built from
     * [entries], one entry per element.
     *
     * Assembled by concatenation rather than an interpolated `trimIndent()`
     * block: `trimIndent` runs *after* interpolation, so a multi-line
     * interpolated value shifts the computed common indent and silently
     * reshapes the YAML. Building the indentation explicitly keeps each test's
     * input exactly what it looks like.
     */
    private fun withShortcuts(entries: List<String>): String =
        buildString {
            appendLine("linexa:")
            appendLine("  schema_version: 1")
            appendLine("  distro:")
            appendLine("    id: debian")
            appendLine("    name: Debian")
            appendLine("  architecture: arm64")
            append("  shortcuts:")
            if (entries.isEmpty()) {
                append(" []")
            } else {
                appendLine()
                entries.forEach { entry ->
                    entry.lines().forEachIndexed { index, line ->
                        appendLine(if (index == 0) "    - $line" else "      $line")
                    }
                }
            }
        }

    private fun withShortcut(vararg lines: String): String =
        withShortcuts(listOf(lines.joinToString("\n")))

    private val minimalDeclaration = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian
          architecture: arm64
    """.trimIndent()

    @Test
    fun declaredShortcutsParseInOrderWithTheirCommands() {
        val result = success(
            withShortcuts(
                listOf(
                    """
                    id: update
                    label: Update System
                    description: Refresh and upgrade packages
                    command: apt-get update && apt-get upgrade -y
                    icon: refresh
                    """.trimIndent(),
                    """
                    id: disk
                    label: Disk Usage
                    command: df -h
                    """.trimIndent()
                )
            )
        )

        val shortcuts = result.descriptor.metadata?.shortcuts!!
        assertEquals(2, shortcuts.size)
        // Order is the author's — the Shortcuts tab renders top to bottom.
        assertEquals("update", shortcuts[0].id)
        assertEquals("Update System", shortcuts[0].label)
        assertEquals("Refresh and upgrade packages", shortcuts[0].description)
        assertEquals("apt-get update && apt-get upgrade -y", shortcuts[0].command)
        assertEquals("refresh", shortcuts[0].icon)
        assertEquals("disk", shortcuts[1].id)
        assertNull("an omitted description stays null", shortcuts[1].description)
        assertNull(shortcuts[1].icon)
    }

    @Test
    fun aCommandWithShellOperatorsIsAcceptedBecauseTheUserMustTapItFirst() {
        // The contrast with environment.shell / desktop.session is the whole
        // §1.120 argument: those become argv tokens in commands the app builds,
        // so they are locked down. A shortcut IS a guest shell command the user
        // explicitly taps, exactly like the built-in catalog's
        // `apt update && apt upgrade -y`. Rejecting `&&` here would make the
        // schema unable to express the shortcuts the app already ships.
        val result = success(
            withShortcut(
                "id: chained",
                "label: Update",
                "command: apt-get update && apt-get upgrade -y | tee /tmp/log"
            )
        )
        assertEquals(
            "apt-get update && apt-get upgrade -y | tee /tmp/log",
            result.descriptor.metadata?.shortcuts?.single()?.command
        )
    }

    @Test
    fun absentShortcutsKeyIsNullSoTheBuiltInCatalogSurvives() {
        val result = success(minimalDeclaration)
        assertNull(
            "absent must stay distinguishable from an empty list",
            result.descriptor.metadata?.shortcuts
        )
    }

    @Test
    fun anExplicitlyEmptyListIsAnEmptyListNotAbsent() {
        val result = success(withShortcuts(emptyList()))
        val shortcuts = result.descriptor.metadata?.shortcuts
        assertNotNull("an explicit [] is the author declaring none", shortcuts)
        assertTrue(shortcuts!!.isEmpty())
    }

    @Test
    fun aShortcutWithoutALabelIsRejected() {
        val reason = invalidReason(
            withShortcut("id: mystery", "command: rm -rf /var/cache")
        )
        assertTrue(reason, reason.contains("shortcuts[0].label"))
    }

    @Test
    fun aShortcutWithoutACommandIsRejected() {
        val reason = invalidReason(
            withShortcut("id: empty", "label: Does Nothing")
        )
        assertTrue(reason, reason.contains("shortcuts[0].command"))
    }

    @Test
    fun aMultiLineLabelIsRejectedBecauseItCouldSpoofASecondCard() {
        val reason = invalidReason(
            withShortcut("id: spoof", """label: "Safe update\nrm -rf /"""", "command: id")
        )
        assertTrue(reason, reason.contains("shortcuts[0].label"))
        assertTrue("must explain the single-line rule: $reason", reason.contains("single line"))
    }

    @Test
    fun aMultiLineCommandIsRejectedBecauseTheCardCouldNotShowItAll() {
        val reason = invalidReason(
            withShortcut("id: hidden", "label: Cleanup", """command: "echo safe\nrm -rf /"""")
        )
        assertTrue(reason, reason.contains("shortcuts[0].command"))
    }

    @Test
    fun anOverlongCommandIsRejected() {
        val long = "echo " + "a".repeat(DeclaredShortcut.MAX_COMMAND_CHARS)
        val reason = invalidReason(
            withShortcut("id: long", "label: Long", "command: \"$long\"")
        )
        assertTrue(reason, reason.contains("shortcuts[0].command"))
    }

    @Test
    fun duplicateShortcutIdsAreRejectedBecauseIdKeysRunState() {
        val reason = invalidReason(
            withShortcuts(
                listOf(
                    "id: update\nlabel: Update\ncommand: apt-get update",
                    "id: UPDATE\nlabel: Update Again\ncommand: apt-get upgrade"
                )
            )
        )
        assertTrue(reason, reason.contains("shortcuts[1].id") && reason.contains("duplicate"))
    }

    @Test
    fun anUnsafeShortcutIdIsRejected() {
        val reason = invalidReason(
            withShortcut("""id: "my shortcut"""", "label: Update", "command: apt-get update")
        )
        assertTrue(reason, reason.contains("shortcuts[0].id"))
    }

    @Test
    fun tooManyShortcutsAreRejected() {
        val entries = (0..DeclaredShortcut.MAX_COUNT).map { index ->
            "id: s$index\nlabel: Shortcut $index\ncommand: echo $index"
        }
        val reason = invalidReason(withShortcuts(entries))
        assertTrue(reason, reason.contains("maximum"))
    }

    @Test
    fun anUnknownKeyInsideAShortcutNamesTheIndexedPath() {
        val reason = invalidReason(
            withShortcut("id: update", "label: Update", "command: apt-get update", "colour: red")
        )
        assertTrue(reason, reason.contains("shortcuts[0]") && reason.contains("colour"))
    }

    @Test
    fun aNonListShortcutsBlockIsRejected() {
        val reason = invalidReason(
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
              shortcuts:
                update: apt-get update
            """.trimIndent()
        )
        assertTrue(reason, reason.contains("linexa.shortcuts") && reason.contains("list"))
    }

    @Test
    fun theConfigParserAloneAcceptsAShortcutsBlock() {
        // Same seam as `theConfigParserAloneAcceptsMetadataKeys`: `shortcuts:`
        // is a new key under the shared `linexa:` mapping, so the config
        // parser must skip it rather than fail the import.
        val result = WorkspaceConfigParser().parse(
            withShortcut("id: update", "label: Update", "command: apt-get update").toByteArray()
        )
        assertTrue("config parser must skip 'shortcuts', got $result", result is WorkspaceConfigParser.Result.Valid)
    }
}
