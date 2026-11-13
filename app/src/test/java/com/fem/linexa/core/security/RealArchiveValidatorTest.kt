package com.fem.linexa.core.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for [RealArchiveValidator] file-based and streaming validation
 * (Phase 9 §1.35 follow-up — archive-validation coverage audit).
 *
 * Covers:
 * - File-based validate(): non-existent, empty, unsupported format, truncated
 * - Streaming validateDuringExtraction(): non-archive data, format detection
 * - ZIP: valid archive, path traversal (zip-slip), absolute paths
 * - checkEntry(): additional edge cases (backslash separators, dot segments)
 */
class RealArchiveValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var validator: RealArchiveValidator

    @Before
    fun setUp() {
        validator = RealArchiveValidator()
    }

    // ── File-based validate() edge cases ──────────────────────────────

    @Test
    fun `validate rejects non-existent file`() {
        val result = validator.validate(File("/tmp/does-not-exist-${System.nanoTime()}.tar.gz"))
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `validate rejects empty file`() {
        val empty = tempFolder.newFile("empty.tar.gz")
        val result = validator.validate(empty)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `validate rejects random non-archive bytes`() {
        val file = tempFolder.newFile("garbage.dat")
        file.writeBytes(ByteArray(256) { (it % 127).toByte() })
        val result = validator.validate(file)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        val reason = (result as ArchiveValidator.ValidationResult.Invalid).reason
        assertTrue("Expected unsupported format, got: $reason", reason.contains("Unsupported"))
    }

    @Test
    fun `validate rejects truncated gzip magic`() {
        val file = tempFolder.newFile("truncated.gz")
        // Gzip magic: 0x1F 0x8B — but only 2 bytes, too small for valid tar.gz
        file.writeBytes(byteArrayOf(0x1F.toByte(), 0x8B.toByte()))
        val result = validator.validate(file)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `validate rejects truncated xz magic`() {
        val file = tempFolder.newFile("truncated.xz")
        // XZ magic: 0xFD 0x37 0x7A 0x58 0x5A 0x00
        file.writeBytes(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00))
        val result = validator.validate(file)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `validate rejects truncated zstd magic`() {
        val file = tempFolder.newFile("truncated.zst")
        // Zstd magic: 0x28 0xB5 0x2F 0xFD
        file.writeBytes(byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte()))
        val result = validator.validate(file)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    // ── Streaming validateDuringExtraction() edge cases ───────────────

    @Test
    fun `streaming validation rejects non-archive data`() {
        val stream = ByteArrayInputStream(ByteArray(128) { 0x42 })
        val result = validator.validateDuringExtraction(stream)
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `streaming validation detects zip format`() {
        val zipBytes = createMinimalZip("test.txt" to "hello".toByteArray())
        val result = validator.validateDuringExtraction(ByteArrayInputStream(zipBytes))
        assertTrue("Valid ZIP should pass streaming validation", result is ArchiveValidator.ValidationResult.Valid)
    }

    @Test
    fun `streaming validation rejects zip with path traversal`() {
        val zipBytes = createMinimalZip("../../../etc/passwd" to "root:x:0:0:".toByteArray())
        val result = validator.validateDuringExtraction(ByteArrayInputStream(zipBytes))
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        val reason = (result as ArchiveValidator.ValidationResult.Invalid).reason
        assertTrue("Expected path traversal rejection, got: $reason", reason.contains("traversal") || reason.contains("absolute") || reason.contains("escape"))
    }

    @Test
    fun `streaming validation rejects zip with absolute path entry`() {
        val zipBytes = createMinimalZip("/etc/passwd" to "root:x:0:0:".toByteArray())
        val result = validator.validateDuringExtraction(ByteArrayInputStream(zipBytes))
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
    }

    @Test
    fun `streaming validation rejects zip bomb`() {
        // A single STORED entry declaring 3 GB uncompressed — should trip the
        // size limit. §1.57: this was previously built with ZipOutputStream
        // and a declared size, but ZipOutputStream.closeEntry() itself throws
        // ZipException ("invalid entry size (expected 3221225472 but got 1024
        // bytes)") on the size mismatch, so the helper never produced a byte
        // array. The bomb entry is now hand-crafted (local file header +
        // central directory + EOCD all declaring the huge size); the validator
        // reads `entry.size` from the local header, so the bomb check fires
        // before any file data is read.
        val zipBytes = createStoredZipWithDeclaredSize("huge.bin", 3L * 1024 * 1024 * 1024)
        val result = validator.validateDuringExtraction(
            ByteArrayInputStream(zipBytes),
            maxTotalSize = 1024 * 1024 // 1 MB limit
        )
        assertTrue(result is ArchiveValidator.ValidationResult.Invalid)
        val reason = (result as ArchiveValidator.ValidationResult.Invalid).reason
        assertTrue("Expected archive bomb detection, got: $reason", reason.contains("bomb") || reason.contains("exceeds"))
    }

    @Test
    fun `streaming validation rejects truncated zip`() {
        val zipBytes = createMinimalZip("ok.txt" to "data".toByteArray())
        // §1.57: cut INSIDE the DEFLATED payload (2 bytes past the 30-byte
        // local header + entry name). The previous cut `zipBytes.size / 2`
        // landed after the payload, so ZipInputStream reported a clean
        // end-of-archive and the validator returned Valid — the corruption was
        // never observed. Cutting inside the payload forces the inflater to
        // hit EOF → EOFException → Invalid, the rejection path the validator
        // actually implements.
        val truncated = zipBytes.copyOf(30 + "ok.txt".length + 2)
        val result = validator.validateDuringExtraction(ByteArrayInputStream(truncated))
        assertTrue("Truncated ZIP should be rejected", result is ArchiveValidator.ValidationResult.Invalid)
    }

    // ── checkEntry edge cases ─────────────────────────────────────────

    @Test
    fun `checkEntry rejects backslash-delimited name with no forward slash`() {
        // §1.147: this is the genuine Windows-relative-path shape — '\' is used
        // *instead of* '/', so the name has zero forward slashes. That
        // structural property (not the number of backslashes) is why it is
        // rejected; a normal '/'-delimited path containing '\' inside a leaf
        // segment is legitimate and must be accepted (see the systemd cases
        // below).
        val result = validator.checkEntry("usr\\bin\\sh")
        assertNotNull("Backslash-instead-of-slash path should be rejected", result)
    }

    @Test
    fun `checkEntry rejects entry with embedded dot-dot after normalization`() {
        // a/./b/../c normalizes to a/c — safe. But a/b/../../etc/passwd normalizes to ../etc/passwd — escapes.
        val result = validator.checkEntry("a/b/../../etc/passwd")
        assertNotNull("Nested traversal should be rejected", result)
    }

    @Test
    fun `checkEntry allows simple relative path`() {
        val result = validator.checkEntry("usr/bin/ls")
        assertNull("Simple relative path should be allowed", result)
    }

    @Test
    fun `checkEntry allows entry starting with dot-slash`() {
        val result = validator.checkEntry("./usr/bin/ls")
        assertNull("Dot-slash prefix should be allowed", result)
    }

    @Test
    fun `checkEntry allows entry with multiple dot segments that stay inside`() {
        // ./a/./b/./c normalizes to a/b/c — safe
        val result = validator.checkEntry("./a/./b/./c")
        assertNull("Dot segments that don't escape should be allowed", result)
    }

    @Test
    fun `checkEntry rejects absolute path starting with backslash`() {
        val result = validator.checkEntry("\\\\server\\share")
        assertNotNull("UNC-style path should be rejected", result)
    }

    @Test
    fun `checkEntry allows literal backslash inside linux filename`() {
        // systemd auto-generates unit names using its own "\xNN" escaping
        // (e.g. '-' becomes "\x2d"). A single embedded backslash here is a
        // valid Linux filename byte, not a Windows-style path separator or
        // a traversal attempt, and must be allowed.
        val result = validator.checkEntry(
            "usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice"
        )
        assertNull("Single literal backslash inside a filename should be allowed", result)
    }

    @Test
    fun `checkEntry allows the systemd unit name with two escapes`() {
        // §1.147 regression test for the Fedora/Arch install failure. This is
        // the exact entry that aborted the install: ONE path segment, TWO
        // literal backslashes, ZERO separator semantics. The Kotlin literal
        // needs "\\" per backslash; the string under test contains exactly 2.
        val result = validator.checkEntry(
            "./usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice"
        )
        assertNull("systemd unit name with two \\xNN escapes must be allowed", result)
    }

    @Test
    fun `checkEntry allows escaped unit names regardless of escape count`() {
        // §1.147: generality guard. The rule must be structural, so no future
        // backslash *count* threshold can creep back in. Real siblings from
        // the Debian/Fedora/Arch samples plus synthetic 3- and 4-escape names.
        val names = listOf(
            "usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice",
            "usr/lib/systemd/system/system-systemd\\x2dveritysetup.slice",
            "usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice",
            "usr/lib/systemd/system/a\\x2db\\x2dc\\x2dd.mount",
            "usr/lib/systemd/system/a\\x2db\\x2dc\\x2dd\\x2de.mount"
        )
        names.forEach { name ->
            assertNull("Escaped unit name should be allowed: $name", validator.checkEntry(name))
        }
    }

    @Test
    fun `checkEntry still rejects traversal expressed with backslashes`() {
        // §1.147: proves the widened acceptance above is not a relaxation of
        // traversal coverage. Each of these must still be rejected.
        val attacks = listOf(
            "a\\..\\..\\etc\\passwd",
            "..\\..\\etc\\passwd",
            "\\\\server\\share",
            "\\absolute",
            "usr/..\\..\\etc/passwd"
        )
        attacks.forEach { name ->
            assertNotNull("Backslash traversal/absolute must be rejected: $name", validator.checkEntry(name))
        }
    }

    @Test
    fun `checkEntry distinguishes dot-dot segment from dot-dot substring`() {
        // §1.147 (audit §6.4): ".." is a traversal construct only as a whole
        // path segment. A filename that merely contains two dots is ordinary.
        assertNull("libfoo..so is a filename, not traversal", validator.checkEntry("usr/lib/libfoo..so"))
        assertNull("foo..bar is a filename, not traversal", validator.checkEntry("etc/foo..bar"))
        assertNotNull("Real traversal segment must be rejected", validator.checkEntry("usr/../../etc/passwd"))
    }

    @Test
    fun `checkEntry accepts a name mixing backslashes and forward slashes`() {
        // §1.147 (audit §15): the pathological mix needs a *decided* verdict
        // rather than an accidental one, so it is pinned here.
        //
        // Verdict: accepted. "usr\bin/sh\x2dfoo" has real '/' separators, so
        // '\' is not being used as the separator — the name is two segments,
        // "usr\bin" and "sh\x2dfoo", each an ordinary Linux filename. Extracted
        // on Linux it creates a directory literally called "usr\bin" under the
        // extraction root: ugly, but strictly inside it, which is the only
        // property this check exists to guarantee. Rejecting it would mean
        // reintroducing a judgement about backslash *quantity* — the exact
        // reasoning that produced the Fedora/Arch failure.
        assertNull(
            "a '/'-separated name that also contains '\\' must be accepted",
            validator.checkEntry("usr\\bin/sh\\x2dfoo")
        )
        // The same shape does NOT buy an escape: a genuine '..' in either
        // spelling is still refused.
        assertNotNull(
            "backslash-delimited '..' must still be rejected in a mixed name",
            validator.checkEntry("usr\\bin/..\\..\\etc/passwd")
        )
    }

    // ── Valid ZIP round-trip ──────────────────────────────────────────

    @Test
    fun `validate accepts a well-formed zip with safe entries`() {
        val file = tempFolder.newFile("safe.zip")
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("usr/bin/ls"))
                zos.write("binary-data-here".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("etc/passwd"))
                zos.write("root:x:0:0:root:/root:/bin/sh".toByteArray())
                zos.closeEntry()
            }
        }
        val result = validator.validate(file)
        assertTrue("Valid ZIP with safe entries should pass", result is ArchiveValidator.ValidationResult.Valid)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun createMinimalZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * §1.57: hand-crafted STORED zip whose local file header, central
     * directory and EOCD all declare [declaredSize] uncompressed bytes, while
     * the actual payload is tiny. ZipOutputStream cannot produce this (it
     * validates actual-vs-declared size on closeEntry); ZipInputStream reads
     * `entry.size` from the local header, so the validator's archive-bomb
     * check sees the real declared size.
     */
    private fun createStoredZipWithDeclaredSize(name: String, declaredSize: Long): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = java.util.zip.CRC32()
        crc.update(ByteArray(16))
        val bos = ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0..3) bos.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun u16(v: Int) { bos.write(v and 0xFF); bos.write((v ushr 8) and 0xFF) }

        // Local file header (30 bytes + name)
        u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
        u32(crc.value); u32(declaredSize); u32(declaredSize)
        u16(nameBytes.size); u16(0)
        bos.write(nameBytes)
        bos.write(ByteArray(16))
        val localSize = 30 + nameBytes.size + 16
        val cdStart = localSize

        // Central directory entry
        val cd = ByteArrayOutputStream()
        fun cdu32(v: Long) { for (i in 0..3) cd.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun cdu16(v: Int) { cd.write(v and 0xFF); cd.write((v ushr 8) and 0xFF) }
        cdu32(0x02014b50L); cdu16(20); cdu16(20); cdu16(0); cdu16(0); cdu16(0); cdu16(0)
        cdu32(crc.value); cdu32(declaredSize); cdu32(declaredSize)
        cdu16(nameBytes.size); cdu16(0); cdu16(0); cdu16(0); cdu16(0)
        cdu32(0L) // external file attributes
        cdu32(localSize.toLong()) // relative offset of local file header
        cd.write(nameBytes)
        val cdBytes = cd.toByteArray()
        bos.write(cdBytes)

        // End of central directory
        u32(0x06054b50L); u16(0); u16(0); u16(1); u16(1)
        u32(cdBytes.size.toLong()); u32(cdStart.toLong()); u16(0)
        return bos.toByteArray()
    }
}
