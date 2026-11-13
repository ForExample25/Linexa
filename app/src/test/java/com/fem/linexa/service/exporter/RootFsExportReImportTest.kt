package com.fem.linexa.service.exporter

import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import com.fem.linexa.service.rootfs.RootFsArchiveFormat
import com.fem.linexa.service.rootfs.CompressedArchiveStream
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * § export — the re-importability contract, end to end at the boundary the
 * unit tests can reach: a backup produced by [TarRootFsExporter] is fed back
 * through the *same* primitives the install pipeline uses, and must produce
 * the same declaration the original workspace had.
 *
 * The full device path (content Uri → [com.fem.linexa.service.installer.StagedRootFsInstaller])
 * needs an Android runtime, so the pipeline is entered at its two pure seams:
 * [CompressedArchiveStream.open] (the format sniff an import starts with) and
 * [RootFsInspector.inspectFile] (the fail-hard native-metadata validation the
 * scan runs on the root-level declaration it captures). If the exported
 * archive survives both, the import scan will capture its declaration and
 * validate it exactly as it validated the original image's.
 */
class RootFsExportReImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var exporter: TarRootFsExporter
    private lateinit var rootfs: File

    /** The declaration a Debian image would have shipped inside its archive. */
    private val declaration = """
        linexa:
          schema_version: 1
          distro:
            id: debian
            name: Debian GNU/Linux
            version: "12"
          architecture: arm64
          environment:
            shell: /bin/bash
    """.trimIndent()

    @Before
    fun setUp() {
        exporter = TarRootFsExporter(com.fem.linexa.core.security.RealArchiveValidator())
        rootfs = tmp.newFolder("rootfs")
    }

    private fun pack(embeddedConfig: ByteArray?, withInTreeDeclaration: Boolean): ByteArray {
        if (withInTreeDeclaration) {
            val declared = File(rootfs, RootFsMetadataFile.NATIVE_DOT.fileName)
            declared.writeText(declaration)
            Files.setPosixFilePermissions(
                declared.toPath(),
                PosixFilePermissions.fromString("rw-r--r--")
            )
        }
        write("etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 12\"\n")
        write("usr/bin/dash", "#!/bin/dash\n", "rwxr-xr-x")
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), Path.of("usr/bin"))

        val output = ByteArrayOutputStream()
        val result = runBlocking {
            exporter.export(
                ExportRequest(
                    rootfs = rootfs,
                    output = output,
                    verifyInput = { ByteArrayInputStream(output.toByteArray()) },
                    embeddedConfig = embeddedConfig,
                )
            )
        }
        assertTrue("expected Success, got $result", result is ExportResult.Success)
        return output.toByteArray()
    }

    private fun write(relative: String, content: String, mode: String = "rw-r--r--"): File {
        val file = File(rootfs, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
        return file
    }

    /** The root-level declaration bytes an import scan would capture, if any. */
    private fun capturedDeclarationBytes(archive: ByteArray): Pair<RootFsMetadataFile, ByteArray>? {
        val opened = CompressedArchiveStream.open(ByteArrayInputStream(archive))
        var winner: RootFsMetadataFile? = null
        val candidates = mutableMapOf<RootFsMetadataFile, ByteArray>()
        TarArchiveInputStream(opened.stream).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val match = RootFsMetadataFile.match(entry.name)
                if (match != null && entry.size in 1..WorkspaceConfigParser.MAX_CONFIG_BYTES &&
                    !candidates.containsKey(match)
                ) {
                    candidates[match] = tar.readBytes()
                } else {
                    while (tar.read(ByteArray(8192)) >= 0) { /* drain */ }
                }
                if (winner == null) winner = match
                entry = tar.nextTarEntry
            }
        }
        val best = RootFsMetadataFile.bestOf(candidates.keys) ?: return null
        return best to candidates.getValue(best)
    }

    @Test
    fun aBackupOfADeclaredImageReImportsWithTheSameDeclaration() {
        val archive = pack(embeddedConfig = null, withInTreeDeclaration = true)

        val captured = capturedDeclarationBytes(archive)
        assertNotNull("the declaration must survive the export", captured)
        val (file, bytes) = captured!!

        val inspected = RootFsInspector(WorkspaceConfigParser())
            .inspectFile(file, bytes, RootFsArchiveFormat.TAR_GZ)
        assertTrue("the declaration must validate: $inspected", inspected is RootFsInspector.Result.Success)
        inspected as RootFsInspector.Result.Success

        val metadata = inspected.descriptor.metadata
        assertNotNull(metadata)
        assertEquals("debian", metadata!!.distro.id)
        assertEquals("Debian GNU/Linux", metadata.distro.name)
        assertEquals("12", metadata.distro.version)
        assertEquals("arm64-v8a", metadata.architecture.abi)
        assertEquals("/bin/bash", metadata.environment?.shell)
    }

    @Test
    fun aBackupOfAnUndeclaredImageReImportsAsUndeclaredNotBroken() {
        val archive = pack(embeddedConfig = null, withInTreeDeclaration = false)
        org.junit.Assert.assertNull(capturedDeclarationBytes(archive))
    }

    @Test
    fun theEmbeddedDeclarationIsWhatAnImportScanCapturesWhenTheTreeLostItsOwn() {
        // The guest deleted .linexa.yaml inside the workspace; the coordinator
        // re-embeds the workspace's config.yaml bytes as .linexa.yaml.
        val archive = pack(embeddedConfig = declaration.toByteArray(), withInTreeDeclaration = false)

        val captured = capturedDeclarationBytes(archive)
        assertNotNull("the embedded declaration must be capturable", captured)
        val (file, bytes) = captured!!
        assertEquals(RootFsMetadataFile.NATIVE_DOT, file)

        val inspected = RootFsInspector(WorkspaceConfigParser())
            .inspectFile(file, bytes, RootFsArchiveFormat.TAR_GZ)
        assertTrue("the embedded declaration must validate: $inspected", inspected is RootFsInspector.Result.Success)
        inspected as RootFsInspector.Result.Success
        assertEquals("debian", inspected.descriptor.metadata!!.distro.id)
    }
}
