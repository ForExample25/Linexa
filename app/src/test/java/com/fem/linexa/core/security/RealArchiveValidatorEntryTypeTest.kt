package com.fem.linexa.core.security

import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 3 unit tests for the entry-type-aware [ArchiveValidator.checkEntry]
 * contract: hardlink targets use the same escape boundary as symlinks (and
 * fail closed on ambiguity), while character devices, block devices and FIFOs
 * are refused outright.
 *
 * The classifier tests pin the single `TarArchiveEntry` → [ArchiveEntryType]
 * mapping shared by the streaming scan, the file validator and the OCI layer
 * walk, so those three call sites cannot drift apart.
 */
class RealArchiveValidatorEntryTypeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var validator: RealArchiveValidator

    @Before
    fun setUp() {
        validator = RealArchiveValidator()
    }

    // ── Hardlink targets: allowed ─────────────────────────────────────

    @Test
    fun `hardlink to a sibling inside the root is allowed`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "usr/bin/tool.real",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNull("an in-root sibling hardlink must be allowed", result)
    }

    @Test
    fun `hardlink to a path in another in-root directory is allowed`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "usr/lib/tool/tool.real",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNull("a plain relative hardlink target stays inside the root", result)
    }

    @Test
    fun `hardlink target with a literal backslash inside a segment is allowed`() {
        // Same systemd-escaped-name shape the name validator accepts: '/' is
        // the separator, so the backslash is an ordinary filename byte.
        val result = validator.checkEntry(
            name = "usr/lib/systemd/system/a.slice",
            linkTarget = "usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNull(result)
    }

    // ── Hardlink targets: rejected ────────────────────────────────────

    @Test
    fun `hardlink target with parent traversal is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "../../outside",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull("a hardlink target containing '..' must be rejected", result)
        assertTrue(
            "reason must name the traversal, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("parent directory traversal")
        )
    }

    @Test
    fun `hardlink target with an in-root parent step is still rejected`() {
        // Fail closed: the tar producer's anchor (archive root vs entry
        // directory) is not knowable here, so every '..' is refused even when
        // one particular anchor would have kept it inside.
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "../lib/tool.real",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull(result)
    }

    @Test
    fun `hardlink to an absolute target is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "/etc/passwd",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull("an absolute hardlink target must be rejected", result)
        assertTrue(
            "reason must name the absolute path, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("absolute")
        )
    }

    @Test
    fun `hardlink to a UNC target is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "\\\\server\\share\\passwd",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull(result)
    }

    @Test
    fun `hardlink to a windows drive target is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "C:\\Windows\\System32\\config\\SAM",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull(result)
    }

    @Test
    fun `hardlink with an empty or blank target is rejected`() {
        listOf(null, "", "   ").forEach { target ->
            val result = validator.checkEntry(
                name = "usr/bin/tool",
                linkTarget = target,
                entryType = ArchiveEntryType.HARDLINK
            )
            assertNotNull("a hardlink target of '$target' must be rejected", result)
        }
    }

    @Test
    fun `hardlink target that normalizes to nothing is rejected`() {
        listOf(".", "./", "././").forEach { target ->
            val result = validator.checkEntry(
                name = "usr/bin/tool",
                linkTarget = target,
                entryType = ArchiveEntryType.HARDLINK
            )
            assertNotNull("a hardlink target of '$target' names no file", result)
        }
    }

    @Test
    fun `hardlink target with a null byte is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "usr/bin/tool\u0000/etc/passwd",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull(result)
    }

    @Test
    fun `hardlink target with backslash traversal is rejected`() {
        val result = validator.checkEntry(
            name = "usr/bin/tool",
            linkTarget = "..\\..\\etc\\passwd",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull(result)
    }

    @Test
    fun `hardlink entry name traversal is rejected before its target is considered`() {
        val result = validator.checkEntry(
            name = "../../usr/bin/tool",
            linkTarget = "usr/bin/tool.real",
            entryType = ArchiveEntryType.HARDLINK
        )
        assertNotNull("the entry's own name must still be checked", result)
    }

    // ── Special files ─────────────────────────────────────────────────

    @Test
    fun `special file entries are rejected with a clear reason`() {
        val result = validator.checkEntry(
            name = "dev/null",
            entryType = ArchiveEntryType.SPECIAL
        )
        assertNotNull(result)
        assertTrue(
            "reason must name the special-file policy, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("Special file type")
        )
    }

    @Test
    fun `regular files and directories are allowed`() {
        assertNull(
            validator.checkEntry("usr/bin/tool", entryType = ArchiveEntryType.REGULAR)
        )
        assertNull(
            validator.checkEntry("usr/bin", entryType = ArchiveEntryType.DIRECTORY)
        )
    }

    // ── Legacy callers keep the old contract ──────────────────────────

    @Test
    fun `a legacy caller without an entry type keeps the pre-Phase-3 behavior`() {
        // The exporter and old tests pass only name + isSymlink + linkTarget.
        // Their linkTarget is meaningful only for symlinks; a hardlink-shaped
        // call with no type must not change verdicts under their feet.
        assertNull(
            validator.checkEntry(
                name = "usr/bin/tool",
                isSymlink = false,
                linkTarget = "../../outside"
            )
        )
    }

    // ── Classifier: one mapping for every tar walk ────────────────────

    @Test
    fun `classifier maps every tar member kind to one entry type`() {
        assertEquals(
            ArchiveEntryType.REGULAR,
            TarArchiveEntry("usr/bin/tool").toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.DIRECTORY,
            TarArchiveEntry("usr/bin/", TarArchiveEntry.LF_DIR).toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.SYMLINK,
            TarArchiveEntry("usr/bin/sh", TarArchiveEntry.LF_SYMLINK).toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.HARDLINK,
            TarArchiveEntry("usr/bin/tool", TarArchiveEntry.LF_LINK).toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.SPECIAL,
            TarArchiveEntry("dev/tty0", TarArchiveEntry.LF_CHR).toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.SPECIAL,
            TarArchiveEntry("dev/sda", TarArchiveEntry.LF_BLK).toArchiveEntryType()
        )
        assertEquals(
            ArchiveEntryType.SPECIAL,
            TarArchiveEntry("run/initctl", TarArchiveEntry.LF_FIFO).toArchiveEntryType()
        )
    }

    // ── Streaming/file validation walks real tar bytes ────────────────

    @Test
    fun `file validation rejects a tar with an absolute hardlink target`() {
        val archive = tempFolder.newFile("hardlink-absolute.tar.gz")
        archive.writeBytes(
            tarGz(
                regular("usr/bin/tool.real", "payload"),
                hardlink("usr/bin/tool", "/etc/passwd")
            )
        )

        val result = validator.validate(archive)

        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        assertTrue(
            "reason must name the hardlink target, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("Hardlink target has absolute path")
        )
    }

    @Test
    fun `file validation rejects a tar with a traversing hardlink target`() {
        val archive = tempFolder.newFile("hardlink-traversal.tar.gz")
        archive.writeBytes(
            tarGz(
                regular("usr/bin/tool.real", "payload"),
                hardlink("usr/bin/tool", "../../outside")
            )
        )

        val result = validator.validate(archive)

        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        assertTrue(
            "reason must name the hardlink traversal, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("Hardlink target contains parent directory traversal")
        )
    }

    @Test
    fun `file validation accepts a tar with an in-root hardlink`() {
        val archive = tempFolder.newFile("hardlink-safe.tar.gz")
        archive.writeBytes(
            tarGz(
                regular("usr/bin/tool.real", "payload"),
                hardlink("usr/bin/tool", "usr/bin/tool.real")
            )
        )

        val result = validator.validate(archive)

        assertTrue(
            "a normal rootfs hardlink must keep validating, got: ${reasonOf(result)}",
            result is ArchiveValidator.ValidationResult.Valid
        )
    }

    @Test
    fun `file validation rejects a tar carrying a FIFO`() {
        val archive = tempFolder.newFile("fifo.tar.gz")
        archive.writeBytes(tarGz(special("run/initctl", TarArchiveEntry.LF_FIFO)))

        val result = validator.validate(archive)

        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        assertTrue(
            "reason must name the special-file policy, got: ${reasonOf(result)}",
            reasonOf(result)!!.contains("Special file type")
        )
    }

    @Test
    fun `file validation rejects a tar carrying a character device`() {
        val archive = tempFolder.newFile("chardev.tar.gz")
        archive.writeBytes(
            tarGz(
                special("dev/null", TarArchiveEntry.LF_CHR).apply {
                    devMajor = 1
                    devMinor = 3
                }
            )
        )

        val result = validator.validate(archive)

        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        assertTrue(reasonOf(result)!!.contains("Special file type"))
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private fun reasonOf(result: ArchiveValidator.ValidationResult?): String? =
        (result as? ArchiveValidator.ValidationResult.Invalid)?.reason

    private fun regular(name: String, text: String) =
        TarArchiveEntry(name).apply { size = text.toByteArray().size.toLong() }

    private fun hardlink(name: String, target: String) =
        TarArchiveEntry(name, TarArchiveEntry.LF_LINK).apply {
            linkName = target
            size = 0
        }

    private fun special(name: String, flag: Byte) =
        TarArchiveEntry(name, flag).apply { size = 0 }

    private fun tarGz(vararg entries: TarArchiveEntry): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { entry ->
                    tar.putArchiveEntry(entry)
                    if (entry.isFile && entry.size > 0) {
                        tar.write(ByteArray(entry.size.toInt()) { 'x'.code.toByte() })
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
